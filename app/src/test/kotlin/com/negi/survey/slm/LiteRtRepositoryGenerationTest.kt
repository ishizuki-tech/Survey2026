package com.negi.survey.slm

import com.negi.aicore.AiGenerationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class LiteRtRepositoryGenerationTest {
    @Test fun cancellationBeforeInstallationIsLatchedAndDeliveredOnce() {
        val first = LiteRtRepositoryGeneration(AiGenerationId("first"), requestId = 11L)
        val second = LiteRtRepositoryGeneration(AiGenerationId("second"), requestId = 12L)
        val firstDispatches = AtomicInteger()
        val secondDispatches = AtomicInteger()

        first.cancel()
        first.installCancellationDispatcher { firstDispatches.incrementAndGet() }
        first.cancel()
        second.installCancellationDispatcher { secondDispatches.incrementAndGet() }

        assertEquals(1, firstDispatches.get())
        assertEquals(0, secondDispatches.get())
        assertNull(first.nativeRunIdOrNull())
    }

    @Test fun cancellationAfterInstallationIsDeliveredOnceDespiteRepeatedCancellation() {
        val generation = LiteRtRepositoryGeneration(AiGenerationId("owner"), requestId = 17L)
        val dispatches = AtomicInteger()

        generation.installCancellationDispatcher { dispatches.incrementAndGet() }
        generation.cancel()
        generation.cancel()

        assertEquals(1, dispatches.get())
    }

    @Test fun concurrentCancellationAndInstallationDeliverDispatcherExactlyOnce() {
        val cancellationRequested = CountDownLatch(1)
        val allowCancellationDispatch = CountDownLatch(1)
        val generation = LiteRtRepositoryGeneration(
            generationId = AiGenerationId("race"),
            requestId = 19L,
            afterCancellationRequested = {
                cancellationRequested.countDown()
                assertTrue(allowCancellationDispatch.await(1, TimeUnit.SECONDS))
            },
        )
        val dispatches = AtomicInteger()

        val cancellingThread = thread(start = true, name = "cancellation-thread") { generation.cancel() }
        try {
            assertTrue(cancellationRequested.await(1, TimeUnit.SECONDS))
            generation.installCancellationDispatcher { dispatches.incrementAndGet() }
            allowCancellationDispatch.countDown()
            cancellingThread.join(1_000)

            assertFalse(cancellingThread.isAlive)
            assertEquals(1, dispatches.get())
        } finally {
            allowCancellationDispatch.countDown()
            cancellingThread.join(1_000)
        }
    }

    @Test fun independentGenerationsKeepCancellationDispatchersIsolated() {
        val first = LiteRtRepositoryGeneration(AiGenerationId("first"), requestId = 21L)
        val second = LiteRtRepositoryGeneration(AiGenerationId("second"), requestId = 22L)
        val firstDispatches = AtomicInteger()
        val secondDispatches = AtomicInteger()
        first.installCancellationDispatcher { firstDispatches.incrementAndGet() }
        second.installCancellationDispatcher { secondDispatches.incrementAndGet() }

        first.cancel()

        assertEquals(1, firstDispatches.get())
        assertEquals(0, secondDispatches.get())
    }

    @Test fun nativeRunIdIsPublishedOnceForTheOwningGeneration() {
        val generation = LiteRtRepositoryGeneration(AiGenerationId("owner"), requestId = 17L)
        generation.publishNativeRunId(91L)

        assertEquals(91L, generation.nativeRunIdOrNull())
        assertTrue(runCatching { generation.publishNativeRunId(92L) }.isFailure)
    }

    @Test fun preNativeCancellationDispatchesOneScopedCancelForOwningGeneration() {
        val dispatchedRunIds = ConcurrentLinkedQueue<Long>()
        val scopedCancellation = LiteRtScopedCancellation { dispatchedRunIds += it }
        val generation = LiteRtRepositoryGeneration(AiGenerationId("owner"), requestId = 31L)

        generation.cancel()
        generation.installCancellationDispatcher { scopedCancellation.issueCancel() }
        assertTrue(dispatchedRunIds.isEmpty())

        generation.publishNativeRunId(101L)
        assertTrue(scopedCancellation.publishRunId(101L))
        generation.cancel()
        scopedCancellation.issueCancel()
        scopedCancellation.publishRunId(101L)

        assertEquals(listOf(101L), dispatchedRunIds.toList())
    }

    @Test fun concurrentCancellationAndRunPublicationDoNotLoseScopedCancel() {
        val dispatchedRunIds = ConcurrentLinkedQueue<Long>()
        val scopedCancellation = LiteRtScopedCancellation { dispatchedRunIds += it }
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val cancelThread = thread(name = "scoped-cancel") {
            ready.countDown()
            assertTrue(start.await(1, TimeUnit.SECONDS))
            scopedCancellation.issueCancel()
        }
        val publishThread = thread(name = "scoped-publication") {
            ready.countDown()
            assertTrue(start.await(1, TimeUnit.SECONDS))
            assertTrue(scopedCancellation.publishRunId(103L))
        }
        try {
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            start.countDown()
            cancelThread.join(1_000)
            publishThread.join(1_000)
            assertFalse(cancelThread.isAlive)
            assertFalse(publishThread.isAlive)
            assertEquals(listOf(103L), dispatchedRunIds.toList())
        } finally {
            start.countDown()
            cancelThread.join(1_000)
            publishThread.join(1_000)
        }
    }

    @Test fun scopedCancellationKeepsIndependentGenerationsIsolated() {
        val firstDispatches = ConcurrentLinkedQueue<Long>()
        val secondDispatches = ConcurrentLinkedQueue<Long>()
        val first = LiteRtScopedCancellation { firstDispatches += it }
        val second = LiteRtScopedCancellation { secondDispatches += it }

        first.issueCancel()
        first.publishRunId(107L)
        second.publishRunId(109L)

        assertEquals(listOf(107L), firstDispatches.toList())
        assertTrue(secondDispatches.isEmpty())
    }
}
