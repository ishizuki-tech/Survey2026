package com.negi.survey.net

import android.content.Context
import com.negi.survey.BuildConfig
import com.negi.survey.net.SurveyUploadExecutionAuthority.Protocol.IMMUTABLE_V2
import com.negi.survey.net.SurveyUploadExecutionGuard.Outcome
import com.negi.survey.net.V2SurveyDeliveryLedger.Evidence
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface V2SurveyJsonTransport {
    suspend fun upload(config: GitHubUploader.GitHubConfig, path: String, surveyId: String, content: String)
}

/** Executes only a fully validated, explicit V2 Worker request. */
internal class V2SurveyJsonDelivery(
    private val preflight: V2SurveyJsonPreflight,
    private val authority: SurveyUploadExecutionAuthority,
    private val ledger: V2SurveyDeliveryLedger,
    private val resolveConfig: () -> GitHubUploader.GitHubConfig?,
    private val transport: V2SurveyJsonTransport = V2SurveyJsonTransport { config, path, id, content ->
        GitHubUploader.uploadSurveyJsonIdempotentlyAtPath(config, path, id, content)
    },
    private val afterRemoteConfirmation: suspend () -> Unit = {}
) {
    constructor(context: Context) : this(
        V2SurveyJsonPreflight(context.filesDir, BuildConfig.DEBUG || BuildConfig.GH_UPLOAD_MODE == "local"),
        SurveyUploadExecutionAuthority(context),
        V2SurveyJsonLedger(context.filesDir, UploadedSurveyStore(context)::isUploaded),
        { GitHubDiagnosticsConfigStore.buildGitHubConfigOrNull(context) }
    )

    sealed interface Result {
        data class Confirmed(val remotePath: String) : Result
        data class Retry(val reason: String) : Result
        data class Rejected(val reason: String) : Result
    }

    suspend fun execute(input: Map<String, Any?>): Result {
        val surveyId = input[GitHubUploadWorker.KEY_SURVEY_ID] as? String
            ?: return Result.Rejected("Missing V2 survey UUID")
        return withV2SurveyExecution(surveyId) { executeWhileLocked(input) }
    }

    internal suspend fun executeWhileLocked(input: Map<String, Any?>): Result =
        when (val checked = preflight.validate(input)) {
            is V2SurveyJsonPreflight.Result.Rejected -> Result.Rejected(checked.reason)
            is V2SurveyJsonPreflight.Result.Validated -> deliver(checked)
        }

    private suspend fun deliver(checked: V2SurveyJsonPreflight.Result.Validated): Result {
        val route = checked.route
        val config = try {
            resolveConfig()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return try {
            // Preflight validates on-disk state. Use a fresh, verified immutable string for HTTP.
            val content = preflight.readVerifiedPayload(checked)
                ?: return Result.Rejected("V2 staged payload changed after preflight")
            fun ledgerContains(): Boolean = when (ledger.inspect(route)) {
                Evidence.MISSING -> false
                Evidence.CONFIRMED -> true
                Evidence.CONFLICT -> throw V2LedgerConflict()
            }

            val configFailure = when {
                config == null || config.token.isBlank() -> Result.Retry("V2 execution credentials unavailable")
                !route.destination.matches(config) ->
                    Result.Rejected("Configured GitHub destination differs from the immutable V2 route")
                else -> null
            }
            // A confirmed retry only releases the local authority, independent of credentials.
            if (configFailure != null && !ledgerContains()) return configFailure
            when (val outcome = SurveyUploadExecutionGuard.execute(
                surveyId = route.surveyUuid,
                protocol = IMMUTABLE_V2,
                authority = authority,
                ledgerContains = ::ledgerContains,
                remoteWrite = {
                    if (configFailure != null) throw TransportStop(configFailure)
                    send(checkNotNull(config), route, content)
                },
                recordUploaded = {
                    ledger.recordConfirmed(route)
                    check(ledgerContains()) { "V2 confirmation was not recorded" }
                },
                afterRemoteWrite = afterRemoteConfirmation,
                v2Route = route
            )) {
                Outcome.UPLOADED, Outcome.ALREADY_RECORDED -> Result.Confirmed(route.remoteTargetPath)
                is Outcome.BLOCKED_BY_OTHER_PROTOCOL ->
                    Result.Rejected("V2 delivery blocked by ${outcome.owner.name} execution authority")
                Outcome.CORRUPT_OR_UNKNOWN_AUTHORITY ->
                    Result.Rejected("V2 execution authority is corrupt or bound to another route")
                Outcome.AUTHORITY_PERSISTENCE_FAILURE -> Result.Retry("Could not persist V2 execution authority")
                Outcome.AUTHORITY_RELEASE_FAILURE -> Result.Retry("Could not release confirmed V2 execution authority")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (stop: TransportStop) {
            stop.result
        } catch (_: V2LedgerConflict) {
            Result.Rejected("Conflicting V1 or V2 delivery evidence")
        } catch (_: IOException) {
            Result.Retry("V2 local delivery state could not be read or persisted")
        } catch (_: IllegalStateException) {
            Result.Retry("V2 local delivery state could not be confirmed")
        }
    }

    private suspend fun send(config: GitHubUploader.GitHubConfig, route: V2SurveyJsonRoute, content: String) {
        try {
            transport.upload(config, route.remoteTargetPath, route.surveyUuid, content)
        } catch (_: GitHubUploader.SurveyUploadValidationException) {
            throw TransportStop(Result.Rejected("GitHub rejected V2 JSON or the remote content conflicts"))
        } catch (failure: GitHubUploader.HttpFailureException) {
            val code = failure.code
            val reason = "GitHub V2 request failed (HTTP $code)"
            throw TransportStop(
                if (code == 408 || code == 409 || code == 429 || code in 500..599) Result.Retry(reason)
                else Result.Rejected(reason)
            )
        } catch (failure: IOException) {
            throw TransportStop(
                when (failure) {
                    is GitHubUploader.ConflictRetriesExhaustedException ->
                        Result.Retry("GitHub V2 branch conflict retries exhausted")
                    is GitHubUploader.SurveyUploadAmbiguousException ->
                        Result.Retry("GitHub V2 create outcome is ambiguous")
                    else -> Result.Retry("GitHub V2 transport unavailable")
                }
            )
        } catch (_: IllegalArgumentException) {
            throw TransportStop(Result.Rejected("V2 JSON exceeds an upload limit or has an invalid target"))
        }
    }

    private class TransportStop(val result: Result) : Exception()

}

// A durable pin permits same-route retries. Hold the same coroutine lock across delivery and cleanup.
private val v2ExecutionLocks = Array(64) { Mutex() }

internal suspend fun <T> withV2SurveyExecution(surveyId: String, action: suspend () -> T): T {
    val identity = SurveyUploadWork.normalizeLogicalSurveyId(surveyId).orEmpty()
    return v2ExecutionLocks[(identity.hashCode() and Int.MAX_VALUE) % v2ExecutionLocks.size].withLock {
        action()
    }
}

internal fun V2SurveyJsonDestination.matches(config: GitHubUploader.GitHubConfig): Boolean =
    owner == config.owner && repository == config.repo && branch == config.branch && pathPrefix == config.pathPrefix
