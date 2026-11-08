package com.negi.survey.net

import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Discovers only previously finalized or exactly recoverable V2 recording evidence. */
internal class V2VoiceRecovery(
    private val store: V2VoiceRouteStore,
    private val ledger: V2VoiceDeliveryLedger,
    private val localOrDebugBuild: Boolean,
    private val resolveConfig: () -> GitHubUploader.GitHubConfig?,
    private val schedule: (V2VoiceRoute) -> V2VoiceScheduler.Result
) {
    enum class Status { ENQUEUED, ACTIVE, COMPLETE, BLOCKED, DEFERRED }
    data class Outcome(val recordingName: String, val status: Status, val reason: String? = null)

    fun reconcile(): List<Outcome> = store.recordingNames().map { name ->
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val id = try {
            V2RecordingId(name)
        } catch (_: IllegalArgumentException) {
            return@map Outcome(name, Status.BLOCKED, "Invalid V2 recording directory")
        }
        try {
            recover(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            Outcome(name, Status.DEFERRED, "V2 WAV recovery storage unavailable")
        } catch (_: IllegalStateException) {
            Outcome(name, Status.DEFERRED, "V2 WAV recovery state could not be confirmed")
        }
    }

    private fun recover(id: V2RecordingId): Outcome {
        var (state, interrupted) = store.withRecording(id) {
            val current = deliveryState(ledger)
            current to if (current is V2VoiceDeliveryState.Blocked) interruptedRoute() else null
        }

        if (interrupted != null) {
            if (ledger.inspect(interrupted) != V2VoiceDeliveryLedger.Evidence.MISSING) {
                return Outcome(id.value, Status.BLOCKED, "Interrupted WAV publication conflicts with delivery evidence")
            }
            checkEligibility(interrupted, confirmed = false)?.let { return it }
            when (val resumed = V2VoiceStager(store, localOrDebugBuild).recover(id)) {
                is V2VoiceStageResult.Blocked -> return Outcome(id.value, Status.BLOCKED, resumed.reason)
                is V2VoiceStageResult.Ready -> {
                    if (resumed.route != interrupted) {
                        return Outcome(id.value, Status.BLOCKED, "Interrupted V2 WAV route changed")
                    }
                }
            }
            state = store.withRecording(id) { deliveryState(ledger) }
        }

        if (state is V2VoiceDeliveryState.Blocked) return Outcome(id.value, Status.BLOCKED, state.reason)
        check(state is V2VoiceDeliveryState.Valid)
        if (state.delivered && state.file == null) return Outcome(id.value, Status.COMPLETE)
        checkEligibility(state.route, state.confirmed)?.let { return it }
        return when (schedule(state.route)) {
            V2VoiceScheduler.Result.ENQUEUED -> Outcome(id.value, Status.ENQUEUED)
            V2VoiceScheduler.Result.ACTIVE -> Outcome(id.value, Status.ACTIVE)
            V2VoiceScheduler.Result.DEFERRED -> Outcome(id.value, Status.DEFERRED, "V2 WAV WorkManager state unavailable")
        }
    }

    private fun checkEligibility(route: V2VoiceRoute, confirmed: Boolean): Outcome? {
        val id = route.recordingId.value
        if (!allowsV2Destination(route.destination, localOrDebugBuild)) {
            return Outcome(id, Status.BLOCKED, "V2 WAV destination is prohibited in this build")
        }
        if (confirmed) return null

        val config = try {
            resolveConfig()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (config == null || config.token.isBlank() || !route.destination.matches(config)) {
            return Outcome(id, Status.DEFERRED, "Frozen V2 WAV destination or credentials unavailable")
        }
        if (!GitHubUploader.fitsV2VoiceSizeBounds(route.wavSize, config)) return Outcome(id, Status.BLOCKED, "V2 WAV exceeds configured upload limits")
        return null
    }
}
