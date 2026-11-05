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

/** Characterizes current ownership behavior; these are not cross-survey reuse tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeReuseSafetyCharacterizationTest {
    @Test fun same_generation_is_published_again_and_released_once() = runTest {
        val coordinator = HeavyRuntimeCoordinator { }
        val generation = LiteRtGenerationLease("model", Any())
        val publications = mutableListOf<LiteRtGenerationLease>()
        val closed = mutableListOf<LiteRtGenerationLease>()

        repeat(2) {
            coordinator.withLiteRt(
                releaseLiteRt = { closed += it },
                generationLease = { generation },
                onOwnershipPublished = { publications += it },
                acquire = {},
            )
        }
        assertEquals(2, publications.size)
        publications.forEach { assertSame(generation, it) }
        assertTrue(closed.isEmpty())
        coordinator.releaseCurrent()
        assertEquals(listOf(generation), closed)
    }

    @Test fun incompatible_acquisition_occurs_before_prior_owner_release() = runTest {
        val coordinator = HeavyRuntimeCoordinator { }
        val a = LiteRtGenerationLease("model-a", Any())
        val b = LiteRtGenerationLease("model-b", Any())
        val events = mutableListOf<String>()
        var aResident = false
        var observedOverlap = false

        coordinator.withLiteRt(
            releaseLiteRt = { assertSame(a, it); events += "release-a"; aResident = false },
            generationLease = { a },
            acquire = { events += "acquire-a"; aResident = true },
        )
        coordinator.withLiteRt(
            releaseLiteRt = { assertSame(b, it); events += "release-b" },
            generationLease = { b },
            acquire = { events += "acquire-b"; observedOverlap = aResident },
        )
        coordinator.releaseCurrent()

        assertTrue(observedOverlap)
        assertEquals(listOf("acquire-a", "acquire-b", "release-a", "release-b"), events)
    }

    @Test fun stale_lease_for_same_model_cannot_release_new_generation() = runTest {
        val coordinator = HeavyRuntimeCoordinator { }
        val old = LiteRtGenerationLease("same-model", Any())
        val replacement = LiteRtGenerationLease("same-model", Any())
        val closed = mutableListOf<LiteRtGenerationLease>()
        suspend fun acquire(lease: LiteRtGenerationLease) = coordinator.withLiteRt(
            releaseLiteRt = { closed += it }, generationLease = { lease }, acquire = {},
        )

        acquire(old)
        acquire(replacement)
        var staleCloseCalled = false
        assertFalse(coordinator.releaseLiteRt(old) { staleCloseCalled = true })
        assertFalse(staleCloseCalled)
        assertEquals(listOf(old), closed)
        coordinator.releaseCurrent()
        assertEquals(listOf(old, replacement), closed)
    }

    @Test fun cancelled_acquisition_does_not_replace_previous_owner() = runTest {
        val coordinator = HeavyRuntimeCoordinator { }
        val a = LiteRtGenerationLease("a", Any())
        val b = LiteRtGenerationLease("b", Any())
        val started = CompletableDeferred<Unit>()
        val block = CompletableDeferred<Unit>()
        val closed = mutableListOf<LiteRtGenerationLease>()
        coordinator.withLiteRt({ closed += it }, { a }, acquire = {})
        val pending = async {
            coordinator.withLiteRt({ closed += it }, { b }, acquire = {
                started.complete(Unit)
                block.await()
            })
        }
        started.await()
        pending.cancelAndJoin()
        coordinator.releaseCurrent()
        assertEquals(listOf(a), closed)
    }

    @Test fun cancelled_release_keeps_owner_for_retry() = runTest {
        val coordinator = HeavyRuntimeCoordinator { }
        val lease = LiteRtGenerationLease("model", Any())
        val started = CompletableDeferred<Unit>()
        val block = CompletableDeferred<Unit>()
        var attempts = 0
        coordinator.withLiteRt(
            releaseLiteRt = {
                assertSame(lease, it)
                attempts++
                if (attempts == 1) { started.complete(Unit); block.await() }
            },
            generationLease = { lease }, acquire = {},
        )
        val first = async { coordinator.releaseCurrent() }
        started.await()
        first.cancelAndJoin()
        coordinator.releaseCurrent()
        coordinator.releaseCurrent()
        assertEquals(2, attempts)
    }

    @Test fun failed_litert_release_keeps_exact_owner_for_retry() = runTest {
        val coordinator = HeavyRuntimeCoordinator { }
        val lease = LiteRtGenerationLease("model", Any())
        var attempts = 0
        coordinator.withLiteRt(
            releaseLiteRt = { assertSame(lease, it); attempts++; if (attempts == 1) error("close failed") },
            generationLease = { lease }, acquire = {},
        )
        assertEquals("close failed", runCatching { coordinator.releaseCurrent() }.exceptionOrNull()?.message)
        coordinator.releaseCurrent()
        assertEquals(2, attempts)
    }

    @Test fun overlapping_acquisitions_are_serialized_through_publication() = runTest {
        val coordinator = HeavyRuntimeCoordinator { }
        val a = LiteRtGenerationLease("a", Any())
        val b = LiteRtGenerationLease("b", Any())
        val started = CompletableDeferred<Unit>()
        val allowFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = async {
            coordinator.withLiteRt({ events += "release-a" }, { a },
                onOwnershipPublished = { events += "publish-a" }, acquire = {
                    events += "acquire-a"; started.complete(Unit); allowFirst.await()
                })
        }
        started.await()
        val second = async {
            coordinator.withLiteRt({ events += "release-b" }, { b },
                onOwnershipPublished = { events += "publish-b" }, acquire = { events += "acquire-b" })
        }
        runCurrent()
        assertEquals(listOf("acquire-a"), events)
        allowFirst.complete(Unit)
        first.await()
        second.await()
        coordinator.releaseCurrent()
        assertEquals(listOf("acquire-a", "publish-a", "acquire-b", "release-a", "publish-b", "release-b"), events)
    }
}
