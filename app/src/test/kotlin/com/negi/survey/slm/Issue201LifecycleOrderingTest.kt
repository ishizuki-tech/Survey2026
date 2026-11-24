package com.negi.survey.slm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production coordinator, not native callbacks or native destruction. */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue201LifecycleOrderingTest {
    @Test fun delayed_old_notification_cannot_complete_successor_flight() = runTest {
        val coordinator = NativeLifecycleCoordinator()
        val runtime = Any()
        val old = Any()
        val first = replacement(coordinator, runtime, old)
        assertTrue(coordinator.confirmClose(first, old))
        val authorization = checkNotNull(coordinator.authorizeReplacement(first))
        val signal = checkNotNull(coordinator.finalizeReplacementPublication(authorization) {})

        // State publication and waiter notification are intentionally separate operations.
        val successor = replacement(coordinator, runtime, Any())
        val waiter = async { successor.completion.await() }
        runCurrent()
        assertTrue(signal.notifyWaiters())
        assertFalse(signal.notifyWaiters())
        assertFalse(waiter.isCompleted)
        assertFalse(coordinator.confirmClose(first, old))
        assertNull(coordinator.poisonFlight(first, IllegalStateException("late old error")))
        assertSame(successor, coordinator.snapshot().teardownFlight)
        assertEquals(NativeLifecycleCoordinator.Status.TEARDOWN_IN_PROGRESS, coordinator.snapshot().status)
        waiter.cancel()
        waiter.join()
    }

    @Test fun poison_between_authorization_and_publication_revokes_publication() = runTest {
        val coordinator = NativeLifecycleCoordinator()
        val old = Any()
        val flight = replacement(coordinator, Any(), old)
        assertTrue(coordinator.confirmClose(flight, old))
        val authorization = checkNotNull(coordinator.authorizeReplacement(flight))
        var publications = 0
        val publish = async {
            coordinator.finalizeReplacementPublication(authorization) { publications++ }
        }
        // The queued publisher has authorization but has not executed yet.
        val poison = checkNotNull(coordinator.poisonFlight(flight, IllegalStateException("synthetic failure")))
        runCurrent()
        assertNull(publish.await())
        assertEquals(0, publications)
        assertTrue(poison.notifyWaiters())
        assertFalse(poison.notifyWaiters())
        assertTrue(flight.completion.await() is NativeLifecycleCoordinator.TeardownOutcome.Poisoned)
        assertFalse(coordinator.confirmClose(flight, old))
        assertTrue(coordinator.acquireEngineCreationLease(Any()) is NativeLifecycleCoordinator.EngineLeaseResult.Rejected)
    }

    @Test fun conversation_close_alone_cannot_release_full_teardown_or_admit_engine() = runTest {
        val coordinator = NativeLifecycleCoordinator()
        val conversation = Any()
        val engine = Any()
        val flight = (coordinator.startTeardown(
            NativeLifecycleCoordinator.TeardownMode.FULL_TEARDOWN,
            NativeLifecycleCoordinator.TeardownMetadata(Any(), listOf(conversation, engine)),
        ) as NativeLifecycleCoordinator.TeardownStartResult.Started).flight
        val waiter = async { flight.completion.await() }
        runCurrent()
        assertTrue(coordinator.confirmClose(flight, conversation))
        assertFalse(coordinator.confirmClose(flight, conversation))
        assertNull(coordinator.completeFullTeardown(flight))
        assertNull(coordinator.authorizeReplacement(flight))
        assertTrue(coordinator.acquireEngineCreationLease(Any()) is NativeLifecycleCoordinator.EngineLeaseResult.Rejected)
        assertFalse(waiter.isCompleted)
        assertTrue(coordinator.confirmClose(flight, engine))
        val signal = checkNotNull(coordinator.completeFullTeardown(flight))
        assertFalse(waiter.isCompleted)
        assertTrue(signal.notifyWaiters())
        assertFalse(signal.notifyWaiters())
        assertEquals(NativeLifecycleCoordinator.TeardownOutcome.FullTeardownCompleted, waiter.await())
        assertNull(coordinator.completeFullTeardown(flight))
        assertTrue(coordinator.acquireEngineCreationLease(Any()) is NativeLifecycleCoordinator.EngineLeaseResult.Acquired)
    }

    private fun replacement(coordinator: NativeLifecycleCoordinator, runtime: Any, old: Any) =
        (coordinator.startTeardown(
            NativeLifecycleCoordinator.TeardownMode.CONVERSATION_REPLACEMENT,
            NativeLifecycleCoordinator.TeardownMetadata(runtime, listOf(old)),
        ) as NativeLifecycleCoordinator.TeardownStartResult.Started).flight
}
