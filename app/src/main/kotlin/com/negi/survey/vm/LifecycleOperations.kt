package com.negi.survey.vm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runs one cleanup operation and makes every caller observe its completed result. */
internal class OnceCleanup {
    private val mutex = Mutex()
    private var outcome: Result<Unit>? = null

    suspend fun run(action: suspend () -> Unit) = withContext(NonCancellable) {
        mutex.withLock {
            val result = outcome ?: runCatching { action() }.also { outcome = it }
            result.getOrThrow()
        }
    }
}

/** A single owned close operation whose completion, including failure, is shared. */
internal class SharedClose(
    private val scope: CoroutineScope,
    private val action: suspend () -> Unit,
) {
    private var pending: Deferred<Result<Unit>>? = null

    @Synchronized
    fun request(): Deferred<Result<Unit>> = pending ?: scope.async(start = CoroutineStart.LAZY) {
        runCatching { action() }
    }.also {
        pending = it
        it.start()
    }
}

/** Serializes Whisper sessions and rejects reuse after an uncertain native release. */
class WhisperSessionGate {
    private val mutex = Mutex()
    private var releaseFailure: Throwable? = null

    internal suspend fun acquire() {
        mutex.lock()
        val failure = releaseFailure
        if (failure != null) {
            mutex.unlock()
            throw IllegalStateException("Speech recognition cleanup failed; restart the app before using voice transcription", failure)
        }
    }

    internal suspend fun release(action: suspend () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            releaseFailure = error
            throw error
        } finally {
            mutex.unlock()
        }
    }

    internal suspend fun <T> serialized(action: suspend () -> T): T = mutex.withLock { action() }
}
