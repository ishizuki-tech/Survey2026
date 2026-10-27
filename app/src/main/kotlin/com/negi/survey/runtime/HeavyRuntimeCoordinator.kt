package com.negi.survey.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes ownership transitions between the app's two memory-heavy native runtimes.
 *
 * A transition retains the mutex through release and the caller-provided native acquisition,
 * so an opposite runtime cannot begin initializing in the gap between those operations.
 * The successful LiteRT acquisition supplies the exact process-wide instance release action
 * used for a later identity change or explicit release.
 */
interface HeavyRuntimeHandoff {
    suspend fun <T> withLiteRt(
        runtimeIdentity: String,
        releaseLiteRt: suspend () -> Unit,
        acquire: suspend () -> T,
    ): T

    suspend fun <T> withWhisper(acquire: suspend () -> T): T

    /**
     * Releases every heavy runtime that is currently resident, without acquiring
     * a replacement. Use this to explicitly shut down a heavy runtime the user
     * has backed away from, rather than leaving release to the next acquisition.
     */
    suspend fun releaseCurrent()
}

/** Default for isolated callers that do not participate in app-level runtime handoff. */
object NoOpHeavyRuntimeHandoff : HeavyRuntimeHandoff {
    override suspend fun <T> withLiteRt(
        runtimeIdentity: String,
        releaseLiteRt: suspend () -> Unit,
        acquire: suspend () -> T,
    ): T = acquire()

    override suspend fun <T> withWhisper(acquire: suspend () -> T): T = acquire()

    override suspend fun releaseCurrent() = Unit
}

/**
 * Process-wide residency policy for LiteRT-LM and Whisper.
 *
 * - LiteRT-LM stays resident while Whisper initializes and records. Re-initializing the
 *   LiteRT-LM GPU engine after every dictated answer caused multi-second whole-GPU stalls
 *   and Adreno driver process kills on the Galaxy S25 (issue #125).
 * - Whisper (CPU-only) also stays resident across inferences on an already-owned LiteRT-LM
 *   runtime, which avoids a Whisper re-init on every mic tap. S25 measurements in #125
 *   showed about 2.1 GB MemAvailable at worst with both runtimes resident.
 * - Whisper is released before a LiteRT-LM runtime the coordinator does not already own is
 *   acquired (first acquisition or a different identity), so a coordinated LiteRT-LM GPU
 *   initialization does not run alongside a resident Whisper context.
 * - A different LiteRT-LM identity releases the previous LiteRT-LM runtime first.
 */
class HeavyRuntimeCoordinator(
    private val releaseWhisper: suspend () -> Unit,
) : HeavyRuntimeHandoff {
    private val transitionMutex = Mutex()
    private var whisperResident = false
    private var activeLiteRtIdentity: String? = null
    private var releaseActiveLiteRt: (suspend () -> Unit)? = null

    override suspend fun <T> withLiteRt(
        runtimeIdentity: String,
        releaseLiteRt: suspend () -> Unit,
        acquire: suspend () -> T,
    ): T =
        transitionMutex.withLock {
            val sameRuntime = activeLiteRtIdentity == runtimeIdentity
            if (!sameRuntime) {
                releaseResidentWhisper()
                if (activeLiteRtIdentity != null) {
                    releaseOwnedLiteRt()
                }
            }

            try {
                acquire().also {
                    activeLiteRtIdentity = runtimeIdentity
                    releaseActiveLiteRt = releaseLiteRt
                }
            } catch (error: Throwable) {
                if (!sameRuntime) {
                    activeLiteRtIdentity = null
                    releaseActiveLiteRt = null
                }
                throw error
            }
        }

    override suspend fun <T> withWhisper(acquire: suspend () -> T): T =
        transitionMutex.withLock {
            // LiteRT-LM is intentionally left resident; see the class KDoc.
            acquire().also { whisperResident = true }
        }

    override suspend fun releaseCurrent() {
        transitionMutex.withLock {
            releaseResidentWhisper()
            if (activeLiteRtIdentity != null) {
                releaseOwnedLiteRt()
            }
        }
    }

    private suspend fun releaseResidentWhisper() {
        if (!whisperResident) return
        releaseWhisper()
        whisperResident = false
    }

    private suspend fun releaseOwnedLiteRt() {
        checkNotNull(activeLiteRtIdentity) {
            "LiteRT owner is missing its runtime identity."
        }
        checkNotNull(releaseActiveLiteRt) {
            "LiteRT owner is missing its release action."
        }.invoke()
        activeLiteRtIdentity = null
        releaseActiveLiteRt = null
    }
}
