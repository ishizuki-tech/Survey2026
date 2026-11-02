package com.negi.survey.vm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LifecycleOperationsTest {
    @Test
    fun concurrent_cleanup_waits_and_runs_once() = runTest {
        val cleanup = OnceCleanup()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val first = async { cleanup.run { calls++; entered.complete(Unit); finish.await() } }
        entered.await()
        val second = async { cleanup.run { calls++ } }
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        finish.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, calls)
    }

    @Test
    fun concurrent_cleanup_callers_share_failure_after_completion() = runTest {
        val cleanup = OnceCleanup()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val first = async { runCatching { cleanup.run { calls++; entered.complete(Unit); finish.await(); error("release failed") } } }
        entered.await()
        val second = async { runCatching { cleanup.run { calls++ } } }
        runCurrent()
        assertFalse(second.isCompleted)
        finish.complete(Unit)
        assertEquals("release failed", first.await().exceptionOrNull()?.message)
        assertEquals("release failed", second.await().exceptionOrNull()?.message)
        assertEquals(1, calls)
    }

    @Test
    fun failed_cleanup_is_shared_without_retry() = runTest {
        val cleanup = OnceCleanup()
        val failure = IllegalStateException("release failed")
        var calls = 0
        val first = runCatching { cleanup.run { calls++; throw failure } }.exceptionOrNull()
        val second = runCatching { cleanup.run { calls++ } }.exceptionOrNull()
        assertEquals("release failed", first?.message)
        assertEquals("release failed", second?.message)
        assertEquals(1, calls)
    }

    @Test
    fun failed_whisper_release_rejects_replacement_without_locking_detach() = runTest {
        val gate = WhisperSessionGate()
        val entered = CompletableDeferred<Unit>()
        val allowRelease = CompletableDeferred<Unit>()
        var releaseCalls = 0
        gate.acquire()
        val cleanup = async {
            runCatching { gate.release { releaseCalls++; entered.complete(Unit); allowRelease.await(); error("native release failed") } }
        }
        entered.await()
        val replacement = async { runCatching { gate.acquire() } }
        runCurrent()
        assertFalse(replacement.isCompleted)
        allowRelease.complete(Unit)
        assertEquals("native release failed", cleanup.await().exceptionOrNull()?.message)
        assertTrue(replacement.await().exceptionOrNull()?.message?.contains("restart") == true)
        var detached = false
        gate.serialized { detached = true }
        assertTrue(detached)
        assertEquals(1, releaseCalls)
    }

    @Test
    fun successful_whisper_release_allows_replacement() = runTest {
        val gate = WhisperSessionGate()
        gate.acquire()
        gate.release { }
        gate.acquire()
        gate.release { }
    }

    @Test
    fun close_is_shared_until_delayed_completion() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val close = SharedClose(this) { calls++; entered.complete(Unit); finish.await() }
        val first = close.request()
        entered.await()
        val second = close.request()
        assertSame(first, second)
        assertFalse(second.isCompleted)
        finish.complete(Unit)
        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)
        assertEquals(1, calls)
    }

    @Test
    fun failed_close_remains_failed_for_all_callers() = runTest {
        val failure = IllegalStateException("close failed")
        var calls = 0
        val close = SharedClose(this) { calls++; throw failure }
        val first = close.request()
        val second = close.request()
        assertSame(failure, first.await().exceptionOrNull())
        assertSame(failure, second.await().exceptionOrNull())
        assertEquals(1, calls)
    }
}
