package com.negi.survey.net

import android.content.Context
import androidx.work.WorkManager
import com.negi.survey.net.SurveyUploadExecutionAuthority.Owner
import com.negi.survey.net.V2SurveyDeliveryLedger.Evidence
import com.negi.survey.net.V2SurveyJsonRouteStore.ReadResult
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Retires only the canonical V2 JSON after matching durable evidence and authority release. */
internal class V2SurveyJsonRetirement(
    private val store: V2SurveyJsonRouteStore,
    private val ledger: V2SurveyDeliveryLedger,
    private val authority: SurveyUploadExecutionAuthority,
    private val hasCompetingWork: (String) -> Boolean,
    private val publishDelivered: (V2SurveyJsonRouteStore.Entry, V2SurveyJsonRoute) -> Unit =
        { entry, route -> entry.removeAfterConfirmedDelivery(route) },
    private val deleteJson: (Path) -> Unit = { Files.delete(it) },
    private val sync: (Path) -> Unit = ::syncDirectory
) {
    constructor(context: Context, currentWorkId: UUID) : this(
        V2SurveyJsonRouteStore(context.filesDir),
        V2SurveyJsonLedger(context.filesDir, UploadedSurveyStore(context)::isUploaded),
        SurveyUploadExecutionAuthority(context),
        { surveyId ->
            val workManager = WorkManager.getInstance(context)
            val name = V2SurveyJsonWork.uniqueWorkName(surveyId)
            val named = workManager.getWorkInfosForUniqueWork(name).get(350, TimeUnit.MILLISECONDS)
            val tagged = workManager.getWorkInfosByTag(name).get(350, TimeUnit.MILLISECONDS)
            (named + tagged).any { it.id != currentWorkId && !it.state.isFinished }
        }
    )

    sealed interface Result {
        data class Completed(val remotePath: String) : Result
        data class Blocked(val reason: String) : Result
        data class Retry(val reason: String) : Result
        data object PendingDelivery : Result
    }

    suspend fun retire(surveyId: String, metadata: Map<String, Any?>): Result =
        withV2SurveyExecution(surveyId) { retireWhileLocked(surveyId, metadata) }

    /** The Worker holds the execution lock across dispatch, delivery, and retirement. */
    internal fun retireWhileLocked(
        surveyId: String,
        metadata: Map<String, Any?>,
        resumePendingDelivery: Boolean = false
    ): Result = try {
        store.withSurvey(surveyId) {
            val state = read()
            val route = when (state) {
                is ReadResult.Pending -> state.route
                is ReadResult.Delivered -> state.route
                else -> return@withSurvey Result.Blocked("Missing or invalid V2 retirement route")
            }
            if (route.surveyUuid != surveyId || metadata != V2SurveyJsonWork.metadata(route)) {
                return@withSurvey Result.Blocked("V2 retirement metadata does not match the immutable route")
            }
            if (state is ReadResult.Pending) {
                checkConfirmation(route, resumePendingDelivery)?.let { return@withSurvey it }
                val file = directory.resolve(route.stagedFilename)
                if (names() != setOf(V2_PENDING_ROUTE, route.stagedFilename) ||
                    readVerifiedV2Json(file, route) == null
                ) {
                    return@withSurvey Result.Blocked("V2 pending JSON is missing, unexpected, or changed")
                }
                if (competingOrUnknown(surveyId)) {
                    return@withSurvey Result.Retry("Competing V2 work or unknown WorkManager state")
                }
                publishDelivered(this, route)
            }

            val allowedFiles = setOf(V2_DELIVERED_ROUTE, route.stagedFilename)
            val hasJson = when (names()) {
                setOf(V2_DELIVERED_ROUTE) -> false
                allowedFiles -> true
                else -> return@withSurvey Result.Blocked("Unexpected files in delivered V2 survey directory")
            }
            if (hasJson && competingOrUnknown(surveyId)) {
                return@withSurvey Result.Retry("Competing V2 work or unknown WorkManager state")
            }
            if (read() != ReadResult.Delivered(route)) {
                return@withSurvey Result.Blocked("Delivered V2 route could not be verified")
            }
            checkConfirmation(route)?.let { return@withSurvey it }
            // Also completes a prior attempt whose rename was visible but whose sync failed.
            sync(directory)

            if (hasJson) {
                val file = directory.resolve(route.stagedFilename)
                if (names() != allowedFiles || readVerifiedV2Json(file, route) == null) {
                    return@withSurvey Result.Blocked("Delivered V2 JSON is unexpected or does not match the ledger")
                }
                deleteJson(file)
                sync(directory)
            }
            Result.Completed(route.remoteTargetPath)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalArgumentException) {
        Result.Blocked("Invalid V2 retirement identity")
    } catch (_: IOException) {
        Result.Retry("V2 retirement could not publish, delete, or synchronize local evidence")
    } catch (_: IllegalStateException) {
        Result.Retry("V2 retirement state could not be confirmed")
    }

    private fun checkConfirmation(route: V2SurveyJsonRoute, resumePendingDelivery: Boolean = false): Result? {
        when (ledger.inspect(route)) {
            Evidence.MISSING -> return if (resumePendingDelivery) Result.PendingDelivery
                else Result.Blocked("V2 retirement requires durable confirmation")
            Evidence.CONFLICT -> return Result.Blocked("V2 retirement found conflicting ledger evidence")
            Evidence.CONFIRMED -> Unit
        }
        return when (val owner = authority.ownerFor(route.surveyUuid)) {
            Owner.CORRUPT_OR_UNKNOWN -> Result.Blocked("Corrupt V2 retirement authority")
            is Owner.VALID -> if (resumePendingDelivery && authority.ownsV2Route(owner, route)) Result.PendingDelivery
                else Result.Blocked("An execution authority still owns this survey")
            Owner.NONE -> if (authority.verifyV2AuthorityReleased(route.surveyUuid)) null
            else Result.Retry("Execution authority release could not be verified durably")
        }
    }

    private fun competingOrUnknown(surveyId: String): Boolean = try {
        hasCompetingWork(surveyId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        true
    } catch (_: Exception) {
        true
    }
}
