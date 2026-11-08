package com.negi.survey.net

import com.negi.survey.net.SurveyUploadExecutionAuthority.Owner
import com.negi.survey.net.V2SurveyDeliveryLedger.Evidence
import com.negi.survey.net.V2SurveyJsonRouteStore.ReadResult
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Reconciles previously staged V2 JSON. It only submits the existing V2 Worker protocol. */
internal class V2SurveyJsonRecovery(
    private val store: V2SurveyJsonRouteStore,
    private val ledger: V2SurveyDeliveryLedger,
    private val authority: SurveyUploadExecutionAuthority,
    private val localOrDebugBuild: Boolean,
    private val resolveConfig: () -> GitHubUploader.GitHubConfig?,
    private val schedule: (V2SurveyJsonRoute, File) -> V2SurveyJsonScheduler.Result
) {
    enum class Status { ENQUEUED, ACTIVE, COMPLETE, BLOCKED, DEFERRED }
    data class Outcome(val directoryName: String, val status: Status, val reason: String? = null)

    private sealed interface Candidate {
        data class Ready(val route: V2SurveyJsonRoute, val file: File, val temporary: Boolean = false) : Candidate
        data class Stop(val status: Status, val reason: String? = null) : Candidate
    }

    fun reconcile(): List<Outcome> = store.scanRecovery().map { entry ->
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val name = entry.directoryName
        try {
            when (val snapshot = entry.route) {
                ReadResult.Missing -> Outcome(name, Status.BLOCKED, "Missing V2 route")
                is ReadResult.Invalid -> Outcome(name, Status.BLOCKED, snapshot.reason)
                else -> when (val candidate = store.withSurvey(name) { inspect() }) {
                    is Candidate.Stop -> Outcome(name, candidate.status, candidate.reason)
                    is Candidate.Ready -> enqueue(name, candidate)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            Outcome(name, Status.DEFERRED, "V2 recovery evidence could not be read or synchronized")
        } catch (_: IllegalStateException) {
            Outcome(name, Status.DEFERRED, "V2 recovery state could not be confirmed")
        }
    }

    private fun V2SurveyJsonRouteStore.Entry.inspect(): Candidate {
        val state = read()
        val route = when (state) {
            is ReadResult.Pending -> state.route
            is ReadResult.Delivered -> state.route
            else -> return Candidate.Stop(Status.BLOCKED, "Missing or invalid V2 route")
        }
        if (!allowsV2Destination(route.destination, localOrDebugBuild)) {
            return Candidate.Stop(Status.BLOCKED, "V2 destination is prohibited in this build")
        }
        val evidence = ledger.inspect(route)
        if (evidence == Evidence.CONFLICT || (state is ReadResult.Delivered && evidence != Evidence.CONFIRMED)) {
            return Candidate.Stop(Status.BLOCKED, "Missing or conflicting V2 delivery evidence")
        }
        val owner = authority.ownerFor(route.surveyUuid)
        if (owner != Owner.NONE && (state is ReadResult.Delivered || !authority.ownsV2Route(owner, route))) {
            return Candidate.Stop(Status.BLOCKED, "Conflicting or unresolved execution authority")
        }

        val filenames = names()
        val routeName = if (state is ReadResult.Delivered) V2_DELIVERED_ROUTE else V2_PENDING_ROUTE
        val temporary = state is ReadResult.Pending && evidence == Evidence.MISSING &&
            filenames == setOf(routeName, V2_PAYLOAD_TEMP)
        if (state is ReadResult.Delivered && filenames == setOf(routeName)) {
            // Make a previously interrupted unlink durable without rescheduling a completed Worker.
            syncDirectory(directory)
            return Candidate.Stop(Status.COMPLETE)
        }
        val file = directory.resolve(route.stagedFilename)
        val payload = if (temporary) directory.resolve(V2_PAYLOAD_TEMP) else file
        if (filenames != setOf(routeName, payload.fileName.toString()) || readVerifiedV2Json(payload, route) == null) {
            return Candidate.Stop(Status.BLOCKED, "V2 JSON is missing, unexpected, or changed")
        }
        if (evidence == Evidence.MISSING) {
            val config = try {
                resolveConfig()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (config == null || config.token.isBlank() || !route.destination.matches(config)) {
                return Candidate.Stop(Status.DEFERRED, "Frozen V2 destination or credentials unavailable")
            }
        }
        return Candidate.Ready(route, file.toFile(), temporary)
    }

    private fun enqueue(name: String, candidate: Candidate.Ready): Outcome {
        if (candidate.temporary) {
            val recovered = V2SurveyJsonStager(store).recover(name)
            if (recovered !is V2SurveyJsonStager.Result.Ready ||
                recovered.route != candidate.route || recovered.file != candidate.file
            ) return Outcome(name, Status.DEFERRED, "Temporary V2 JSON changed during recovery")
        }
        return when (val result = schedule(candidate.route, candidate.file)) {
            is V2SurveyJsonScheduler.Result.Enqueued -> Outcome(name, Status.ENQUEUED)
            is V2SurveyJsonScheduler.Result.Kept -> Outcome(name, Status.ACTIVE)
            is V2SurveyJsonScheduler.Result.Deferred -> Outcome(name, Status.DEFERRED, result.reason)
        }
    }
}
