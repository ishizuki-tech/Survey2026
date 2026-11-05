package com.negi.survey.runtime

import com.negi.survey.slm.LiteRtGenerationLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompatibleRuntimeTransitionTest {
    @Test fun compatible_generation_is_returned_without_construction() = runTest {
        val owner = HeavyRuntimeCoordinator { }
        val a = LiteRtGenerationLease("a", Any())
        var creates = 0
        var closes = 0
        owner.withLiteRt({ closes++ }, { a }, acquire = {})
        val result = owner.transitionLiteRt({ it === a }, { closes++ }) { creates++; a }
        assertSame(a, result)
        assertEquals(0, creates)
        owner.releaseCurrent()
        assertEquals(1, closes)
    }

    @Test fun incompatible_generation_is_closed_before_replacement_creation() = runTest {
        val owner = HeavyRuntimeCoordinator { }
        val a = LiteRtGenerationLease("a", Any())
        val b = LiteRtGenerationLease("b", Any())
        val events = mutableListOf<String>()
        owner.withLiteRt({ assertSame(a, it); events += "close-a" }, { a }, acquire = {})
        val result = owner.transitionLiteRt({ false }, { assertSame(b, it); events += "close-b" }) {
            events += "create-b"
            b
        }
        assertSame(b, result)
        owner.releaseCurrent()
        assertEquals(listOf("close-a", "create-b", "close-b"), events)
    }

    @Test fun failed_close_prevents_replacement_and_preserves_owner() = runTest {
        val owner = HeavyRuntimeCoordinator { }
        val a = LiteRtGenerationLease("a", Any())
        var closes = 0
        var created = false
        owner.withLiteRt({ closes++; if (closes == 1) error("close failed") }, { a }, acquire = {})
        val failure = runCatching {
            owner.transitionLiteRt({ false }, {}) { created = true; LiteRtGenerationLease("b", Any()) }
        }.exceptionOrNull()
        assertEquals("close failed", failure?.message)
        assertFalse(created)
        owner.releaseCurrent()
        assertEquals(2, closes)
    }

    @Test fun replacement_waits_for_close_completion() = runTest {
        val owner = HeavyRuntimeCoordinator { }
        val a = LiteRtGenerationLease("a", Any())
        val b = LiteRtGenerationLease("b", Any())
        val closeStarted = CompletableDeferred<Unit>()
        val finishClose = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        owner.withLiteRt({
            events += "close-start"
            closeStarted.complete(Unit)
            finishClose.await()
            events += "close-finished"
        }, { a }, acquire = {})
        val replacement = async {
            owner.transitionLiteRt({ false }, {}) { events += "construct-b"; b }
        }
        closeStarted.await()
        runCurrent()
        assertEquals(listOf("close-start"), events)
        finishClose.complete(Unit)
        assertSame(b, replacement.await())
        assertEquals(listOf("close-start", "close-finished", "construct-b"), events)
    }

    @Test fun cancellation_after_retirement_leaves_no_published_replacement() = runTest {
        val owner = HeavyRuntimeCoordinator { }
        val a = LiteRtGenerationLease("a", Any())
        val started = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        var closes = 0
        owner.withLiteRt({ closes++ }, { a }, acquire = {})
        val transition = async {
            owner.transitionLiteRt({ false }, { closes++ }) {
                started.complete(Unit)
                wait.await()
                LiteRtGenerationLease("b", Any())
            }
        }
        started.await()
        transition.cancelAndJoin()
        owner.releaseCurrent()
        assertEquals(1, closes)
    }

    @Test fun failed_replacement_leaves_old_lease_stale() = runTest {
        val owner = HeavyRuntimeCoordinator { }
        val a = LiteRtGenerationLease("a", Any())
        owner.withLiteRt({}, { a }, acquire = {})
        val failure = runCatching {
            owner.transitionLiteRt({ false }, {}) { error("init failed") }
        }.exceptionOrNull()
        assertEquals("init failed", failure?.message)
        var stale = false
        assertFalse(owner.releaseLiteRt(a) { stale = true })
        assertFalse(stale)
        val b = LiteRtGenerationLease("b", Any())
        assertSame(b, owner.transitionLiteRt({ false }, {}) { b })
        assertTrue(owner.releaseLiteRt(b) {})
    }
}
