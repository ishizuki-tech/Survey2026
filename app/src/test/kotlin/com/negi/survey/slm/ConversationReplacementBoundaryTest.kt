package com.negi.survey.slm

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests lifecycle authorization, not LiteRT's native Conversation implementation. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationReplacementBoundaryTest {
    @Test fun cancelling_one_waiter_does_not_cancel_replacement_flight() = runTest {
        val coordinator = NativeLifecycleCoordinator()
        val old = Any()
        val flight = startReplacement(coordinator, Any(), old)
        val waiter = async { flight.completion.await() }
        runCurrent()
        waiter.cancelAndJoin()

        assertFalse(flight.completion.isCompleted)
        assertSame(flight, coordinator.snapshot().teardownFlight)
        assertTrue(coordinator.confirmClose(flight, old))
        val authorization = checkNotNull(coordinator.authorizeReplacement(flight))
        var published = false
        val signal = checkNotNull(coordinator.finalizeReplacementPublication(authorization) { published = true })
        assertTrue(published)
        assertTrue(signal.notifyWaiters())
        assertEquals(NativeLifecycleCoordinator.TeardownOutcome.ReplacementPublished, flight.completion.await())
    }

    @Test fun stale_flight_cannot_confirm_or_poison_successor_replacement() {
        val coordinator = NativeLifecycleCoordinator()
        val runtime = Any()
        val old = Any()
        val first = startReplacement(coordinator, runtime, old)
        assertTrue(coordinator.confirmClose(first, old))
        val oldAuthorization = checkNotNull(coordinator.authorizeReplacement(first))
        val signal = coordinator.finalizeReplacementPublication(oldAuthorization) {}
        assertNotNull(signal)
        checkNotNull(signal).notifyWaiters()

        val replacementConversation = Any()
        val second = startReplacement(coordinator, runtime, replacementConversation)
        assertFalse(coordinator.confirmClose(first, replacementConversation))
        assertNull(coordinator.poisonFlight(first, IllegalStateException("stale")))
        var stalePublished = false
        assertNull(coordinator.finalizeReplacementPublication(oldAuthorization) { stalePublished = true })
        assertFalse(stalePublished)
        assertSame(second, coordinator.snapshot().teardownFlight)
        assertNull(coordinator.authorizeReplacement(second))
        assertTrue(coordinator.confirmClose(second, replacementConversation))
        assertNotNull(coordinator.authorizeReplacement(second))
    }

    @Test fun unconfirmed_close_prevents_replacement_and_engine_creation() {
        val coordinator = NativeLifecycleCoordinator()
        val old = Any()
        val flight = startReplacement(coordinator, Any(), old)
        assertFalse(coordinator.confirmClose(flight, Any()))
        assertNull(coordinator.authorizeReplacement(flight))
        val attempt = coordinator.acquireEngineCreationLease(Any())
        assertTrue(attempt is NativeLifecycleCoordinator.EngineLeaseResult.Rejected)
        assertEquals(NativeLifecycleCoordinator.EngineLeaseRejection.TEARDOWN_IN_PROGRESS,
            (attempt as NativeLifecycleCoordinator.EngineLeaseResult.Rejected).reason)
        assertFalse(flight.completion.isCompleted)
    }

    private fun startReplacement(
        coordinator: NativeLifecycleCoordinator,
        runtime: Any,
        conversation: Any,
    ): NativeLifecycleCoordinator.TeardownFlight {
        val result = coordinator.startTeardown(
            mode = NativeLifecycleCoordinator.TeardownMode.CONVERSATION_REPLACEMENT,
            metadata = NativeLifecycleCoordinator.TeardownMetadata(runtime, listOf(conversation)),
        )
        return (result as NativeLifecycleCoordinator.TeardownStartResult.Started).flight
    }
}
