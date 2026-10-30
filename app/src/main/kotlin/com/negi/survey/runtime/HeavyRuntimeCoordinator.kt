package com.negi.survey.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.negi.survey.slm.LiteRtGenerationLease
import com.negi.survey.slm.sameGeneration

/**
 * Serializes lifecycle transitions for the app's two memory-heavy native runtimes.
 *
 * LiteRT is allowed to remain resident while a transient Whisper operation runs. The mutex
 * still serializes runtime replacement and release with native acquisition, but residency is
 * tracked independently: Whisper must not imply that a healthy LiteRT Engine was destroyed.
 */
interface HeavyRuntimeHandoff {
    suspend fun <T> withLiteRt(
        releaseLiteRt: suspend (LiteRtGenerationLease) -> Unit,
        generationLease: suspend () -> LiteRtGenerationLease,
        onOwnershipPublished: (LiteRtGenerationLease) -> Unit = {},
        acquire: suspend () -> T,
    ): T

    suspend fun <T> withWhisper(acquire: suspend () -> T): T

    /** Releases the transient Whisper context without affecting a resident LiteRT runtime. */
    suspend fun releaseWhisper()

    /**
     * Runs a recovery close only if [ownership] is still the published owner.
     * Returning false means a newer generation superseded the caller; its native
     * resources must not be touched by the stale recovery path.
     */
    suspend fun releaseLiteRt(ownership: LiteRtGenerationLease, release: suspend () -> Unit): Boolean

    /**
     * Releases whichever runtime is currently owned (if any), without acquiring
     * a replacement. Use this to explicitly shut down a heavy runtime the user
     * has backed away from, rather than leaving release to the next acquisition.
     */
    suspend fun releaseCurrent()
}

/** Default for isolated callers that do not participate in app-level runtime handoff. */
object NoOpHeavyRuntimeHandoff : HeavyRuntimeHandoff {
    override suspend fun <T> withLiteRt(
        releaseLiteRt: suspend (LiteRtGenerationLease) -> Unit,
        generationLease: suspend () -> LiteRtGenerationLease,
        onOwnershipPublished: (LiteRtGenerationLease) -> Unit,
        acquire: suspend () -> T,
    ): T = acquire()

    override suspend fun <T> withWhisper(acquire: suspend () -> T): T = acquire()

    override suspend fun releaseWhisper() = Unit

    override suspend fun releaseLiteRt(ownership: LiteRtGenerationLease, release: suspend () -> Unit): Boolean {
        release()
        return true
    }

    override suspend fun releaseCurrent() = Unit
}

class HeavyRuntimeCoordinator(
    private val releaseWhisperAction: suspend () -> Unit,
) : HeavyRuntimeHandoff {
    private val transitionMutex = Mutex()
    private var activeLiteRtOwnership: LiteRtGenerationLease? = null
    private var releaseActiveLiteRt: (suspend (LiteRtGenerationLease) -> Unit)? = null
    private var whisperResident = false

    override suspend fun <T> withLiteRt(
        releaseLiteRt: suspend (LiteRtGenerationLease) -> Unit,
        generationLease: suspend () -> LiteRtGenerationLease,
        onOwnershipPublished: (LiteRtGenerationLease) -> Unit,
        acquire: suspend () -> T,
    ): T =
        transitionMutex.withLock {
            try {
                val acquired = acquire()
                // Once acquire() returns, the native generation may already have revoked an
                // abandoned publisher's cleanup authority. Keep lease lookup, replacement,
                // and publication in one non-cancellable ownership transfer.
                withContext(NonCancellable) {
                    val lease = generationLease()
                    val sameRuntime = activeLiteRtOwnership?.sameGeneration(lease) == true
                    if (!sameRuntime && activeLiteRtOwnership != null) releaseOwnedLiteRt()
                    activeLiteRtOwnership = lease
                    releaseActiveLiteRt = releaseLiteRt
                    onOwnershipPublished(lease)
                }
                acquired
            } catch (error: Throwable) {
                throw error
            }
        }

    override suspend fun <T> withWhisper(acquire: suspend () -> T): T =
        transitionMutex.withLock {
            val acquired = acquire()
            // Match LiteRT's handoff boundary: do not make native initialization
            // non-cancellable, but do publish a completed acquisition atomically.
            withContext(NonCancellable) { whisperResident = true }
            acquired
        }

    override suspend fun releaseWhisper() {
        transitionMutex.withLock {
            if (!whisperResident) return
            releaseWhisperAction()
            whisperResident = false
        }
    }

    override suspend fun releaseLiteRt(
        ownership: LiteRtGenerationLease,
        release: suspend () -> Unit,
    ): Boolean =
        transitionMutex.withLock {
            if (activeLiteRtOwnership?.sameGeneration(ownership) != true) return false
            release()
            activeLiteRtOwnership = null
            releaseActiveLiteRt = null
            true
        }

    override suspend fun releaseCurrent() {
        transitionMutex.withLock {
            var failure: Throwable? = null
            if (whisperResident) {
                try {
                    releaseWhisperAction()
                    whisperResident = false
                } catch (error: Throwable) {
                    failure = error
                }
            }
            if (activeLiteRtOwnership != null) {
                try {
                    releaseOwnedLiteRt()
                } catch (error: Throwable) {
                    failure?.addSuppressed(error) ?: run { failure = error }
                }
            }
            failure?.let { throw it }
        }
    }

    private suspend fun releaseOwnedLiteRt() {
        val lease = checkNotNull(activeLiteRtOwnership) { "LiteRT owner is missing its generation lease." }
        checkNotNull(releaseActiveLiteRt) {
            "LiteRT owner is missing its release action."
        }.invoke(lease)
        activeLiteRtOwnership = null
        releaseActiveLiteRt = null
    }
}
