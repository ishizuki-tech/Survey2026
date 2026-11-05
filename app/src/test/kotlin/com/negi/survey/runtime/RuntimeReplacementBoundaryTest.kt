package com.negi.survey.runtime

import com.negi.survey.slm.LiteRtGenerationLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Current coordinator behavior, not a future atomic replacement API. */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeReplacementBoundaryTest {
    @Test fun cancelling_waiter_before_release_leaves_owner_untouched() = runTest {
        val coordinator = HeavyRuntimeCoordinator { }
        val owner = LiteRtGenerationLease("a", Any())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var closes = 0
        coordinator.withLiteRt({ closes++ }, { owner }, acquire = {})
        val holding = async { coordinator.withWhisper { entered.complete(Unit); resume.await() } }
        entered.await()
        val release = async { coordinator.releaseCurrent() }
        runCurrent()
        release.cancelAndJoin()
        assertEquals(0, closes)
        resume.complete(Unit)
        holding.await()
        coordinator.releaseCurrent()
        assertEquals(1, closes)
    }

    @Test fun cancellation_after_explicit_retirement_leaves_no_owner_and_allows_retry() = runTest {
        val coordinator = HeavyRuntimeCoordinator { }
        val a = LiteRtGenerationLease("a", Any())
        val b = LiteRtGenerationLease("b", Any())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val closed = mutableListOf<LiteRtGenerationLease>()
        coordinator.withLiteRt({ closed += it }, { a }, acquire = {})
        assertTrue(coordinator.releaseLiteRt(a) { closed += a })
        val pending = async {
            coordinator.withLiteRt({ closed += it }, { b }, acquire = {
                entered.complete(Unit)
                resume.await()
            })
        }
        entered.await()
        pending.cancelAndJoin()
        coordinator.releaseCurrent()
        assertEquals(listOf(a), closed)
        coordinator.withLiteRt({ closed += it }, { b }, acquire = {})
        coordinator.releaseCurrent()
        assertEquals(listOf(a, b), closed)
    }

    @Test fun failed_replacement_acquisition_retains_original_owner() = runTest {
        val coordinator = HeavyRuntimeCoordinator { }
        val a = LiteRtGenerationLease("a", Any())
        val b = LiteRtGenerationLease("b", Any())
        val closed = mutableListOf<LiteRtGenerationLease>()
        var publishedB = false
        coordinator.withLiteRt({ closed += it }, { a }, acquire = {})
        val failure = runCatching {
            coordinator.withLiteRt({ closed += it }, { b },
                onOwnershipPublished = { publishedB = true }, acquire = { error("init failed") })
        }.exceptionOrNull()
        assertEquals("init failed", failure?.message)
        assertFalse(publishedB)
        coordinator.releaseCurrent()
        assertEquals(listOf(a), closed)
    }

    @Test fun failed_prior_release_after_new_acquisition_does_not_publish_new_owner() = runTest {
        val coordinator = HeavyRuntimeCoordinator { }
        val a = LiteRtGenerationLease("a", Any())
        val b = LiteRtGenerationLease("b", Any())
        var attempts = 0
        var bAcquired = false
        var bPublished = false
        var bReleaseCalled = false
        coordinator.withLiteRt({ assertSame(a, it); attempts++; if (attempts == 1) error("close failed") },
            { a }, acquire = {})
        val failure = runCatching {
            coordinator.withLiteRt({ bReleaseCalled = true }, { b },
                onOwnershipPublished = { bPublished = true }, acquire = { bAcquired = true })
        }.exceptionOrNull()
        assertEquals("close failed", failure?.message)
        assertTrue(bAcquired)
        assertFalse(bPublished)
        assertFalse(bReleaseCalled)
        coordinator.releaseCurrent()
        assertEquals(2, attempts)
        assertFalse(bReleaseCalled)
    }

    @Test fun competing_replacements_are_serialized_and_stale_cleanup_is_rejected() = runTest {
        val coordinator = HeavyRuntimeCoordinator { }
        val a = LiteRtGenerationLease("a", Any())
        val b = LiteRtGenerationLease("b", Any())
        val c = LiteRtGenerationLease("c", Any())
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        coordinator.withLiteRt({ events += "close-a" }, { a }, acquire = { events += "acquire-a" })
        val first = async {
            coordinator.withLiteRt({ events += "close-b" }, { b }, acquire = {
                events += "acquire-b"; entered.complete(Unit); resume.await()
            })
        }
        entered.await()
        val second = async {
            coordinator.withLiteRt({ events += "close-c" }, { c }, acquire = { events += "acquire-c" })
        }
        runCurrent()
        assertEquals(listOf("acquire-a", "acquire-b"), events)
        resume.complete(Unit)
        first.await()
        second.await()
        assertFalse(coordinator.releaseLiteRt(b) { events += "stale-close-b" })
        coordinator.releaseCurrent()
        assertEquals(listOf("acquire-a", "acquire-b", "close-a", "acquire-c", "close-b", "close-c"), events)
    }
}
