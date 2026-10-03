package com.negi.survey.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes ownership transitions between the app's two memory-heavy native runtimes.
 *
 * A transition retains the mutex through release and the caller-provided native acquisition,
 * so an opposite runtime cannot begin initializing in the gap between those operations.
 * The successful LiteRT acquisition supplies the exact process-wide instance release action
 * used for a later Whisper transition.
 */
interface HeavyRuntimeHandoff {
    suspend fun <T> withLiteRt(
        runtimeIdentity: String,
        releaseLiteRt: suspend () -> Unit,
        acquire: suspend () -> T,
    ): T

    suspend fun <T> withWhisper(acquire: suspend () -> T): T
}

/** Default for isolated callers that do not participate in app-level runtime handoff. */
object NoOpHeavyRuntimeHandoff : HeavyRuntimeHandoff {
    override suspend fun <T> withLiteRt(
        runtimeIdentity: String,
        releaseLiteRt: suspend () -> Unit,
        acquire: suspend () -> T,
    ): T = acquire()

    override suspend fun <T> withWhisper(acquire: suspend () -> T): T = acquire()
}

class HeavyRuntimeCoordinator(
    private val releaseWhisper: suspend () -> Unit,
) : HeavyRuntimeHandoff {
    private enum class Owner {
        NONE,
        LITERT,
        WHISPER,
    }

    private val transitionMutex = Mutex()
    private var owner = Owner.NONE
    private var activeLiteRtIdentity: String? = null
    private var releaseActiveLiteRt: (suspend () -> Unit)? = null

    override suspend fun <T> withLiteRt(
        runtimeIdentity: String,
        releaseLiteRt: suspend () -> Unit,
        acquire: suspend () -> T,
    ): T =
        transitionMutex.withLock {
            val sameRuntime = owner == Owner.LITERT && activeLiteRtIdentity == runtimeIdentity
            if (!sameRuntime) {
                when (owner) {
                    Owner.LITERT -> releaseOwnedLiteRt()
                    Owner.WHISPER -> releaseWhisper()
                    Owner.NONE -> Unit
                }
                owner = Owner.NONE
                activeLiteRtIdentity = null
                releaseActiveLiteRt = null
            }

            try {
                acquire().also {
                    owner = Owner.LITERT
                    activeLiteRtIdentity = runtimeIdentity
                    releaseActiveLiteRt = releaseLiteRt
                }
            } catch (error: Throwable) {
                if (!sameRuntime) {
                    owner = Owner.NONE
                    activeLiteRtIdentity = null
                    releaseActiveLiteRt = null
                }
                throw error
            }
        }

    override suspend fun <T> withWhisper(acquire: suspend () -> T): T =
        transitionMutex.withLock {
            transitionTo(
                requested = Owner.WHISPER,
                releaseOpposite = ::releaseOwnedLiteRt,
                acquire = acquire,
            )
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

    private suspend fun <T> transitionTo(
        requested: Owner,
        releaseOpposite: suspend () -> Unit,
        acquire: suspend () -> T,
        onAcquired: () -> Unit = {},
    ): T {
        val previous = owner
        if (previous != requested) {
            if (previous != Owner.NONE) {
                releaseOpposite()
            }
            owner = Owner.NONE
        }

        return try {
            acquire().also {
                owner = requested
                onAcquired()
            }
        } catch (error: Throwable) {
            if (previous != requested) {
                owner = Owner.NONE
            }
            throw error
        }
    }
}
