package com.negi.survey.net

import com.negi.survey.net.V2VoiceDeliveryLedger.Evidence
import java.io.DataInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.CancellationException

internal fun interface V2VoiceTransport {
    suspend fun upload(config: GitHubUploader.GitHubConfig, path: String, bytes: ByteArray)
}

/** Only the verified in-memory snapshot reaches the binary create-only uploader. */
internal class V2VoiceDelivery(
    private val store: V2VoiceRouteStore,
    private val ledger: V2VoiceDeliveryLedger,
    private val execution: V2VoiceExecution,
    private val localOrDebugBuild: Boolean,
    private val resolveConfig: () -> GitHubUploader.GitHubConfig?,
    private val hasCompetingWork: (V2RecordingId) -> Boolean,
    private val transport: V2VoiceTransport = V2VoiceTransport { config, path, bytes ->
        GitHubUploader.uploadV2VoiceSnapshotAtPath(config, path, bytes)
    },
    private val deleteWav: (Path) -> Unit = { Files.delete(it) },
    private val sync: (Path) -> Unit = ::syncDirectory,
    private val afterRetirementPublication: () -> Unit = {}
) {
    sealed interface Result {
        data class Completed(val remotePath: String) : Result
        data class Blocked(val reason: String) : Result
        data class Retry(val reason: String) : Result
    }

    suspend fun execute(input: Map<String, Any?>): Result {
        val recordingId = V2VoiceWork.recordingId(input) ?: return Result.Blocked("Invalid V2 WAV request identity")
        return try {
            val route = store.withRecording(recordingId) {
                when (val state = deliveryState(ledger)) {
                    is V2VoiceDeliveryState.Blocked -> throw Stop(Result.Blocked(state.reason))
                    is V2VoiceDeliveryState.Valid -> state.route
                }
            }
            if (input != V2VoiceWork.metadata(route)) return Result.Blocked("V2 WAV request does not match its route")
            if (!allowsV2Destination(route.destination, localOrDebugBuild)) {
                return Result.Blocked("V2 WAV destination is prohibited in this build")
            }

            if (ledger.inspect(route) != Evidence.CONFIRMED) {
                execution.own(recordingId) { deliver(route) }
            }
            // The transport lease has been released successfully. The same lock now excludes
            // competing transport while retirement verifies and deletes the local artifact.
            execution.own(recordingId) { retire(route) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (stopped: Stop) {
            stopped.result
        } catch (_: V2VoiceEvidenceConflict) {
            Result.Blocked("Conflicting V2 WAV delivery evidence")
        } catch (_: IOException) {
            Result.Retry("V2 WAV storage, ownership, or synchronization could not be completed")
        } catch (_: IllegalStateException) {
            Result.Retry("V2 WAV state could not be confirmed")
        }
    }

    private suspend fun deliver(route: V2VoiceRoute) {
        when (ledger.inspect(route)) {
            Evidence.CONFIRMED -> return
            Evidence.CONFLICT -> throw V2VoiceEvidenceConflict()
            Evidence.MISSING -> Unit
        }
        val config = try {
            resolveConfig()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: throw Stop(Result.Retry("V2 WAV execution credentials unavailable"))
        if (config.token.isBlank()) throw Stop(Result.Retry("V2 WAV execution credentials unavailable"))
        if (!route.destination.matches(config)) throw Stop(Result.Blocked("Configured destination differs from frozen V2 WAV route"))
        if (!GitHubUploader.fitsV2VoiceSizeBounds(route.wavSize, config)) throw Stop(Result.Blocked("V2 WAV exceeds configured upload limits"))

        val bytes = store.withRecording(route.recordingId) {
            val state = requireState(route)
            if (state.confirmed) return@withRecording null
            val file = state.file ?: throw Stop(Result.Blocked("V2 WAV is missing"))
            val snapshot = ByteArray(route.wavSize.toInt())
            DataInputStream(Files.newInputStream(file, NOFOLLOW_LINKS)).use { input ->
                input.readFully(snapshot)
                if (input.read() != -1) throw Stop(Result.Blocked("V2 WAV grew during validation"))
            }
            if (SurveyUploadIdempotency.payloadFingerprint(snapshot) != route.wavSha256) {
                throw Stop(Result.Blocked("V2 WAV changed during validation"))
            }
            snapshot
        } ?: return

        send(config, route, bytes)
        ledger.recordConfirmed(route)
        if (ledger.inspect(route) != Evidence.CONFIRMED) throw IOException("V2 WAV confirmation could not be verified")
    }

    private suspend fun send(config: GitHubUploader.GitHubConfig, route: V2VoiceRoute, bytes: ByteArray) {
        try {
            transport.upload(config, route.remoteRelativePath, bytes)
        } catch (_: GitHubUploader.ArtifactConflictException) {
            throw Stop(Result.Blocked("Remote V2 WAV contains conflicting bytes"))
        } catch (_: GitHubUploader.UploadSizeLimitException) {
            throw Stop(Result.Blocked("V2 WAV exceeds configured upload limits"))
        } catch (failure: GitHubUploader.HttpFailureException) {
            val code = failure.code
            val reason = "V2 WAV GitHub request failed (HTTP $code)"
            throw Stop(if (code == 408 || code == 409 || code == 429 || code in 500..599) Result.Retry(reason)
                else Result.Blocked(reason))
        } catch (_: IOException) {
            // The next attempt starts with a GET of this same frozen target.
            throw Stop(Result.Retry("V2 WAV transport outcome is unavailable or ambiguous"))
        }
    }

    private fun retire(route: V2VoiceRoute): Result = store.withRecording(route.recordingId) {
        val state = requireState(route)
        if (!state.confirmed) return@withRecording Result.Blocked("V2 WAV retirement requires durable confirmation")
        if (state.file == null) return@withRecording Result.Completed(route.remoteRelativePath)
        if (competingOrUnknown(route.recordingId)) return@withRecording Result.Retry("Competing V2 WAV work or unknown state")

        if (!state.delivered) {
            publish(V2_VOICE_ROUTE, V2_VOICE_DELIVERED)
            afterRetirementPublication()
            if (competingOrUnknown(route.recordingId)) {
                return@withRecording Result.Retry("Competing V2 WAV work or unknown state")
            }
        }
        sync(directory)
        val retired = requireState(route)
        if (!retired.delivered || !retired.confirmed || retired.file == null) {
            return@withRecording Result.Blocked("V2 WAV retirement evidence changed")
        }
        deleteWav(retired.file)
        sync(directory)
        Result.Completed(route.remoteRelativePath)
    }

    private fun V2VoiceRouteStore.Entry.requireState(route: V2VoiceRoute): V2VoiceDeliveryState.Valid =
        when (val state = deliveryState(ledger)) {
            is V2VoiceDeliveryState.Blocked -> throw Stop(Result.Blocked(state.reason))
            is V2VoiceDeliveryState.Valid -> state.takeIf { it.route == route }
                ?: throw Stop(Result.Blocked("Immutable V2 WAV route changed"))
        }

    private fun competingOrUnknown(recordingId: V2RecordingId): Boolean = try {
        hasCompetingWork(recordingId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        true
    } catch (_: Exception) {
        true
    }

    private class Stop(val result: Result) : Exception()
}
