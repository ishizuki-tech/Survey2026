package com.negi.survey.slm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativeLifecycleCoordinatorTest {

    @Test
    fun blocked_close_deadline_poisons_and_denies_creation() {
        val coordinator = NativeLifecycleCoordinator()
        val closeIdentity = Any()
        val flight = coordinator.startConversationReplacement(closeIdentity)

        assertNotNull(
            coordinator.poisonIfCloseUnconfirmed(
                flight = flight,
                closeIdentity = closeIdentity,
            )
        )

        val snapshot = coordinator.snapshot()
        assertEquals(NativeLifecycleCoordinator.Status.POISONED, snapshot.status)
        assertEquals(NATIVE_RUNTIME_POISON_MESSAGE, snapshot.poisonError?.message)
        assertTrue(
            coordinator.acquireEngineCreationLease(Any()) is
                NativeLifecycleCoordinator.EngineLeaseResult.Rejected
        )
        assertNull(coordinator.authorizeReplacement(flight))
    }

    @Test
    fun late_close_success_does_not_clear_poison() {
        val coordinator = NativeLifecycleCoordinator()
        val closeIdentity = Any()
        val flight = coordinator.startConversationReplacement(closeIdentity)

        assertNotNull(coordinator.poisonIfCloseUnconfirmed(flight, closeIdentity))
        val poison = coordinator.poisonErrorOrNull()
        assertNotNull(poison)

        assertFalse(coordinator.confirmClose(flight, closeIdentity))
        assertSame(poison, coordinator.poisonErrorOrNull())
        assertEquals(
            NativeLifecycleCoordinator.Status.POISONED,
            coordinator.snapshot().status,
        )
    }

    @Test
    fun authorized_external_publication_happens_before_healthy() =
        runBlocking {
            val coordinator = NativeLifecycleCoordinator()
            val closeIdentity = Any()
            val flight = coordinator.startConversationReplacement(closeIdentity)
            val publicationCount = AtomicInteger(0)

            assertTrue(coordinator.confirmClose(flight, closeIdentity))
            assertNull(coordinator.poisonIfCloseUnconfirmed(flight, closeIdentity))
            val authorization = coordinator.authorizeReplacement(flight)
            assertNotNull(authorization)
            assertNull(coordinator.authorizeReplacement(flight))

            val leaseResult = coordinator.acquireEngineCreationLease(Any())
            assertTrue(
                leaseResult is NativeLifecycleCoordinator.EngineLeaseResult.Rejected
            )

            val terminalSignal =
                coordinator.finalizeReplacementPublication(authorization!!) {
                    assertEquals(
                        NativeLifecycleCoordinator.Status.TEARDOWN_IN_PROGRESS,
                        coordinator.snapshot().status,
                    )
                    publicationCount.incrementAndGet()
                }

            assertNotNull(terminalSignal)
            assertEquals(1, publicationCount.get())
            assertEquals(
                NativeLifecycleCoordinator.Status.HEALTHY,
                coordinator.snapshot().status,
            )
            assertFalse(flight.completion.isCompleted)
            assertTrue(terminalSignal!!.notifyWaiters())
            assertEquals(
                NativeLifecycleCoordinator.TeardownOutcome.ReplacementPublished,
                flight.completion.await(),
            )

            val snapshot = coordinator.snapshot()
            assertEquals(NativeLifecycleCoordinator.Status.HEALTHY, snapshot.status)
            assertFalse(snapshot.hasEngineCreationLease)
            assertNull(snapshot.teardownFlight)
        }

    @Test
    fun concurrent_flight_start_has_single_owner() {
        val coordinator = NativeLifecycleCoordinator()
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val runtimeIdentity = Any()
        val results =
            Collections.synchronizedList(
                mutableListOf<NativeLifecycleCoordinator.TeardownStartResult>()
            )
        val executor = Executors.newFixedThreadPool(2)

        try {
            repeat(2) {
                executor.execute {
                    ready.countDown()
                    release.await()
                    results +=
                        coordinator.startTeardown(
                            mode = NativeLifecycleCoordinator.TeardownMode.FULL_TEARDOWN,
                            metadata =
                                metadata(
                                    runtimeIdentity = runtimeIdentity,
                                    closeIdentities = listOf(Any()),
                                ),
                        )
                    finished.countDown()
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))

            val started =
                results.filterIsInstance<
                    NativeLifecycleCoordinator.TeardownStartResult.Started
                >()
            val existing =
                results.filterIsInstance<
                    NativeLifecycleCoordinator.TeardownStartResult.ExistingSameRuntime
                >()

            assertEquals(1, started.size)
            assertEquals(1, existing.size)
            assertSame(started.single().flight, existing.single().flight)
            assertSame(started.single().flight, coordinator.snapshot().teardownFlight)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun engine_creation_lease_is_exclusive() {
        val coordinator = NativeLifecycleCoordinator()
        val initializationIdentity = Any()
        val acquired = coordinator.acquireEngineCreationLease(initializationIdentity)
        assertTrue(acquired is NativeLifecycleCoordinator.EngineLeaseResult.Acquired)
        val lease =
            (acquired as NativeLifecycleCoordinator.EngineLeaseResult.Acquired).lease

        val sameOwner = coordinator.acquireEngineCreationLease(initializationIdentity)
        assertTrue(sameOwner is NativeLifecycleCoordinator.EngineLeaseResult.Existing)
        assertSame(
            lease,
            (sameOwner as NativeLifecycleCoordinator.EngineLeaseResult.Existing).lease,
        )

        val unrelated = coordinator.acquireEngineCreationLease(Any())
        assertTrue(unrelated is NativeLifecycleCoordinator.EngineLeaseResult.Rejected)

        var published = false
        assertTrue(
            coordinator.completeEnginePublication(lease) {
                assertTrue(coordinator.snapshot().hasEngineCreationLease)
                published = true
            }
        )
        assertTrue(published)
        val next = coordinator.acquireEngineCreationLease(Any())
        assertTrue(next is NativeLifecycleCoordinator.EngineLeaseResult.Acquired)
        assertTrue(
            (next as NativeLifecycleCoordinator.EngineLeaseResult.Acquired).lease !== lease
        )
    }

    @Test
    fun engine_failure_before_native_state_releases_exact_lease() {
        val coordinator = NativeLifecycleCoordinator()
        val acquired = coordinator.acquireEngineCreationLease(Any())
        val lease =
            (acquired as NativeLifecycleCoordinator.EngineLeaseResult.Acquired).lease

        assertTrue(coordinator.failEngineCreationBeforeNativeState(lease))
        assertFalse(coordinator.failEngineCreationBeforeNativeState(lease))

        val snapshot = coordinator.snapshot()
        assertEquals(NativeLifecycleCoordinator.Status.HEALTHY, snapshot.status)
        assertFalse(snapshot.hasEngineCreationLease)
        assertTrue(
            coordinator.acquireEngineCreationLease(Any()) is
                NativeLifecycleCoordinator.EngineLeaseResult.Acquired
        )
    }

    @Test
    fun failed_engine_lease_converts_directly_to_teardown() {
        val coordinator = NativeLifecycleCoordinator()
        val acquired = coordinator.acquireEngineCreationLease(Any())
        val lease =
            (acquired as NativeLifecycleCoordinator.EngineLeaseResult.Acquired).lease
        val failedEngineIdentity = Any()

        val conversion =
            coordinator.convertFailedEngineLeaseToTeardown(
                lease = lease,
                metadata = metadata(closeIdentities = listOf(failedEngineIdentity)),
            )

        assertTrue(conversion is NativeLifecycleCoordinator.TeardownStartResult.Started)
        val snapshot = coordinator.snapshot()
        assertEquals(
            NativeLifecycleCoordinator.Status.TEARDOWN_IN_PROGRESS,
            snapshot.status,
        )
        assertFalse(snapshot.hasEngineCreationLease)
        assertEquals(
            NativeLifecycleCoordinator.TeardownMode.FAILED_ENGINE_REPLACEMENT,
            snapshot.teardownMode,
        )
        assertTrue(
            coordinator.acquireEngineCreationLease(Any()) is
                NativeLifecycleCoordinator.EngineLeaseResult.Rejected
        )
    }

    @Test
    fun stale_engine_lease_conversion_distinguishes_runtime_owner() {
        val coordinator = NativeLifecycleCoordinator()
        val acquired = coordinator.acquireEngineCreationLease(Any())
        val staleLease =
            (acquired as NativeLifecycleCoordinator.EngineLeaseResult.Acquired).lease
        assertTrue(coordinator.completeEnginePublication(staleLease) {})

        val ownerRuntime = Any()
        val ownerResult =
            coordinator.startTeardown(
                mode = NativeLifecycleCoordinator.TeardownMode.FULL_TEARDOWN,
                metadata =
                    metadata(
                        runtimeIdentity = ownerRuntime,
                        closeIdentities = listOf(Any()),
                    ),
            )
        val ownerFlight =
            (ownerResult as NativeLifecycleCoordinator.TeardownStartResult.Started).flight

        val sameRuntime =
            coordinator.convertFailedEngineLeaseToTeardown(
                lease = staleLease,
                metadata =
                    metadata(
                        runtimeIdentity = ownerRuntime,
                        closeIdentities = listOf(Any()),
                    ),
            )
        assertTrue(
            sameRuntime is
                NativeLifecycleCoordinator.TeardownStartResult.ExistingSameRuntime
        )
        assertSame(
            ownerFlight,
            (sameRuntime as
                NativeLifecycleCoordinator.TeardownStartResult.ExistingSameRuntime).flight,
        )

        val otherRuntime =
            coordinator.convertFailedEngineLeaseToTeardown(
                lease = staleLease,
                metadata =
                    metadata(
                        runtimeIdentity = Any(),
                        closeIdentities = listOf(Any()),
                    ),
            )
        assertSame(
            NativeLifecycleCoordinator.TeardownStartResult.BusyOtherRuntime,
            otherRuntime,
        )
    }

    @Test
    fun poison_prevents_external_replacement_publication() {
        val coordinator = NativeLifecycleCoordinator()
        val closeIdentity = Any()
        val flight = coordinator.startConversationReplacement(closeIdentity)
        val publicationCount = AtomicInteger(0)

        assertTrue(coordinator.confirmClose(flight, closeIdentity))
        val authorization = coordinator.authorizeReplacement(flight)
        assertNotNull(authorization)

        assertNotNull(coordinator.poisonFlight(flight))
        val poison = coordinator.poisonErrorOrNull()
        assertNull(
            coordinator.finalizeReplacementPublication(authorization!!) {
                publicationCount.incrementAndGet()
            }
        )
        assertEquals(0, publicationCount.get())
        assertSame(poison, coordinator.poisonErrorOrNull())
        assertEquals(
            NativeLifecycleCoordinator.Status.POISONED,
            coordinator.snapshot().status,
        )
    }

    @Test
    fun publication_failure_does_not_publish_healthy() {
        val coordinator = NativeLifecycleCoordinator()
        val closeIdentity = Any()
        val flight = coordinator.startConversationReplacement(closeIdentity)
        val publicationFailure = IllegalStateException("publication failed")

        assertTrue(coordinator.confirmClose(flight, closeIdentity))
        val authorization = coordinator.authorizeReplacement(flight)
        assertNotNull(authorization)

        val thrown =
            runCatching {
                coordinator.finalizeReplacementPublication(authorization!!) {
                    throw publicationFailure
                }
            }.exceptionOrNull()

        assertSame(publicationFailure, thrown)
        assertEquals(
            NativeLifecycleCoordinator.Status.TEARDOWN_IN_PROGRESS,
            coordinator.snapshot().status,
        )
        assertSame(flight, coordinator.snapshot().teardownFlight)
        assertFalse(flight.completion.isCompleted)
    }

    @Test
    fun terminal_waiter_notification_is_outside_transition() =
        runBlocking {
            val coordinator = NativeLifecycleCoordinator()
            val closeIdentity = Any()
            val result =
                coordinator.startTeardown(
                    mode = NativeLifecycleCoordinator.TeardownMode.FULL_TEARDOWN,
                    metadata = metadata(closeIdentities = listOf(closeIdentity)),
                )
            val flight =
                (result as NativeLifecycleCoordinator.TeardownStartResult.Started).flight

            assertTrue(coordinator.confirmClose(flight, closeIdentity))
            val terminalSignal = coordinator.completeFullTeardown(flight)
            assertNotNull(terminalSignal)

            assertEquals(
                NativeLifecycleCoordinator.Status.HEALTHY,
                coordinator.snapshot().status,
            )
            assertFalse(flight.completion.isCompleted)

            assertTrue(terminalSignal!!.notifyWaiters())
            assertFalse(terminalSignal.notifyWaiters())
            assertEquals(
                NativeLifecycleCoordinator.TeardownOutcome.FullTeardownCompleted,
                flight.completion.await(),
            )
            assertEquals(
                NativeLifecycleCoordinator.Status.HEALTHY,
                coordinator.snapshot().status,
            )
        }

    @Test
    fun same_runtime_teardown_returns_existing_same_owner() {
        val coordinator = NativeLifecycleCoordinator()
        val runtimeIdentity = Any()
        val first =
            coordinator.startTeardown(
                mode = NativeLifecycleCoordinator.TeardownMode.FULL_TEARDOWN,
                metadata =
                    metadata(
                        runtimeIdentity = runtimeIdentity,
                        closeIdentities = listOf(Any()),
                    ),
            )
        val firstFlight =
            (first as NativeLifecycleCoordinator.TeardownStartResult.Started).flight

        val second =
            coordinator.startTeardown(
                mode = NativeLifecycleCoordinator.TeardownMode.CONVERSATION_REPLACEMENT,
                metadata =
                    metadata(
                        runtimeIdentity = runtimeIdentity,
                        closeIdentities = listOf(Any()),
                    ),
            )

        assertTrue(
            second is
                NativeLifecycleCoordinator.TeardownStartResult.ExistingSameRuntime
        )
        assertSame(
            firstFlight,
            (second as
                NativeLifecycleCoordinator.TeardownStartResult.ExistingSameRuntime).flight,
        )
    }

    @Test
    fun different_runtime_teardown_reports_busy_other_runtime() {
        val coordinator = NativeLifecycleCoordinator()
        coordinator.startTeardown(
            mode = NativeLifecycleCoordinator.TeardownMode.FULL_TEARDOWN,
            metadata = metadata(runtimeIdentity = Any(), closeIdentities = listOf(Any())),
        )

        val otherRuntimeResult =
            coordinator.startTeardown(
                mode = NativeLifecycleCoordinator.TeardownMode.FULL_TEARDOWN,
                metadata =
                    metadata(
                        runtimeIdentity = Any(),
                        closeIdentities = listOf(Any()),
                    ),
            )

        assertSame(
            NativeLifecycleCoordinator.TeardownStartResult.BusyOtherRuntime,
            otherRuntimeResult,
        )
    }

    @Test
    fun different_runtime_request_never_receives_owners_outcome() {
        val coordinator = NativeLifecycleCoordinator()
        val ownerRuntime = Any()
        val blockedRuntime = Any()
        val closeIdentity = Any()
        val ownerResult =
            coordinator.startTeardown(
                mode = NativeLifecycleCoordinator.TeardownMode.FULL_TEARDOWN,
                metadata =
                    metadata(
                        runtimeIdentity = ownerRuntime,
                        closeIdentities = listOf(closeIdentity),
                    ),
            )
        val ownerFlight =
            (ownerResult as NativeLifecycleCoordinator.TeardownStartResult.Started).flight

        val blockedResult =
            coordinator.startTeardown(
                mode = NativeLifecycleCoordinator.TeardownMode.FULL_TEARDOWN,
                metadata =
                    metadata(
                        runtimeIdentity = blockedRuntime,
                        closeIdentities = listOf(Any()),
                    ),
            )
        assertSame(
            NativeLifecycleCoordinator.TeardownStartResult.BusyOtherRuntime,
            blockedResult,
        )

        assertTrue(coordinator.confirmClose(ownerFlight, closeIdentity))
        val ownerSignal = coordinator.completeFullTeardown(ownerFlight)
        assertNotNull(ownerSignal)
        assertTrue(ownerSignal!!.notifyWaiters())

        val reevaluated =
            coordinator.startTeardown(
                mode = NativeLifecycleCoordinator.TeardownMode.FULL_TEARDOWN,
                metadata =
                    metadata(
                        runtimeIdentity = blockedRuntime,
                        closeIdentities = listOf(Any()),
                    ),
            )
        assertTrue(reevaluated is NativeLifecycleCoordinator.TeardownStartResult.Started)
        assertTrue(
            (reevaluated as NativeLifecycleCoordinator.TeardownStartResult.Started)
                .flight !== ownerFlight
        )
    }

    @Test
    fun close_exception_poisons_exact_flight() {
        val coordinator = NativeLifecycleCoordinator()
        val closeIdentity = Any()
        val flight = coordinator.startConversationReplacement(closeIdentity)
        val closeFailure = IllegalStateException("close failed")

        assertNotNull(
            coordinator.recordCloseException(
                flight = flight,
                closeIdentity = closeIdentity,
                cause = closeFailure,
            )
        )

        val poison = coordinator.poisonErrorOrNull()
        assertNotNull(poison)
        assertSame(closeFailure, poison?.cause)
        assertEquals(NATIVE_RUNTIME_POISON_MESSAGE, poison?.message)
        assertFalse(coordinator.confirmClose(flight, closeIdentity))
    }

    @Test
    fun engine_publication_failure_retains_exact_lease() {
        val coordinator = NativeLifecycleCoordinator()
        val acquired = coordinator.acquireEngineCreationLease(Any())
        val lease =
            (acquired as NativeLifecycleCoordinator.EngineLeaseResult.Acquired).lease
        val publicationFailure = IllegalStateException("engine publication failed")

        val thrown =
            runCatching {
                coordinator.completeEnginePublication(lease) {
                    throw publicationFailure
                }
            }.exceptionOrNull()

        assertSame(publicationFailure, thrown)
        assertEquals(NativeLifecycleCoordinator.Status.HEALTHY, coordinator.snapshot().status)
        assertTrue(coordinator.snapshot().hasEngineCreationLease)
        assertTrue(
            coordinator.acquireEngineCreationLease(Any()) is
                NativeLifecycleCoordinator.EngineLeaseResult.Rejected
        )
    }

    @Test
    fun confirmed_full_teardown_returns_healthy_uninitialized() =
        runBlocking {
            val coordinator = NativeLifecycleCoordinator()
            val conversationIdentity = Any()
            val engineIdentity = Any()
            val result =
                coordinator.startTeardown(
                    mode = NativeLifecycleCoordinator.TeardownMode.FULL_TEARDOWN,
                    metadata =
                        metadata(
                            closeIdentities =
                                listOf(conversationIdentity, engineIdentity)
                        ),
                )
            val flight =
                (result as NativeLifecycleCoordinator.TeardownStartResult.Started).flight

            assertTrue(coordinator.confirmClose(flight, conversationIdentity))
            assertNull(coordinator.completeFullTeardown(flight))
            assertTrue(coordinator.confirmClose(flight, engineIdentity))
            val terminalSignal = coordinator.completeFullTeardown(flight)
            assertNotNull(terminalSignal)
            assertFalse(flight.completion.isCompleted)
            assertTrue(terminalSignal!!.notifyWaiters())
            assertEquals(
                NativeLifecycleCoordinator.TeardownOutcome.FullTeardownCompleted,
                flight.completion.await(),
            )

            val snapshot = coordinator.snapshot()
            assertEquals(NativeLifecycleCoordinator.Status.HEALTHY, snapshot.status)
            assertFalse(snapshot.hasEngineCreationLease)
            assertNull(snapshot.teardownFlight)
        }

    @Test
    fun failed_engine_replacement_can_finish_healthy_uninitialized_when_new_engine_never_exists() =
        runBlocking {
            val coordinator = NativeLifecycleCoordinator()
            val runtimeIdentity = Any()
            val failedEngineIdentity = Any()
            val acquired = coordinator.acquireEngineCreationLease(Any())
            val lease =
                (acquired as NativeLifecycleCoordinator.EngineLeaseResult.Acquired).lease
            val result =
                coordinator.convertFailedEngineLeaseToTeardown(
                    lease = lease,
                    metadata =
                        metadata(
                            runtimeIdentity = runtimeIdentity,
                            closeIdentities = listOf(failedEngineIdentity),
                        ),
                )
            val flight =
                (result as NativeLifecycleCoordinator.TeardownStartResult.Started).flight

            assertTrue(coordinator.confirmClose(flight, failedEngineIdentity))
            val terminalSignal =
                coordinator.completeFailedEngineTeardownWithoutReplacement(flight)

            assertNotNull(terminalSignal)
            val snapshot = coordinator.snapshot()
            assertEquals(NativeLifecycleCoordinator.Status.HEALTHY, snapshot.status)
            assertFalse(snapshot.hasEngineCreationLease)
            assertNull(snapshot.teardownFlight)
            assertFalse(flight.completion.isCompleted)
            assertTrue(terminalSignal!!.notifyWaiters())
            assertEquals(
                NativeLifecycleCoordinator.TeardownOutcome
                    .TeardownCompletedWithoutReplacement,
                flight.completion.await(),
            )
        }

    @Test
    fun unconfirmed_failed_engine_teardown_cannot_complete_without_replacement() {
        val coordinator = NativeLifecycleCoordinator()
        val failedEngineIdentity = Any()
        val acquired = coordinator.acquireEngineCreationLease(Any())
        val lease =
            (acquired as NativeLifecycleCoordinator.EngineLeaseResult.Acquired).lease
        val result =
            coordinator.convertFailedEngineLeaseToTeardown(
                lease = lease,
                metadata = metadata(closeIdentities = listOf(failedEngineIdentity)),
            )
        val flight =
            (result as NativeLifecycleCoordinator.TeardownStartResult.Started).flight

        assertNull(coordinator.completeFailedEngineTeardownWithoutReplacement(flight))
        assertEquals(
            NativeLifecycleCoordinator.Status.TEARDOWN_IN_PROGRESS,
            coordinator.snapshot().status,
        )
        assertSame(flight, coordinator.snapshot().teardownFlight)
    }

    @Test
    fun conversation_replacement_cannot_use_complete_without_replacement() {
        val coordinator = NativeLifecycleCoordinator()
        val closeIdentity = Any()
        val flight = coordinator.startConversationReplacement(closeIdentity)

        assertTrue(coordinator.confirmClose(flight, closeIdentity))
        assertNull(coordinator.completeFailedEngineTeardownWithoutReplacement(flight))
        assertEquals(
            NativeLifecycleCoordinator.Status.TEARDOWN_IN_PROGRESS,
            coordinator.snapshot().status,
        )
        assertSame(flight, coordinator.snapshot().teardownFlight)
    }

    @Test
    fun publication_callback_observes_lifecycle_restricted_while_caller_conceptually_holds_external_state_ownership() {
        val coordinator = NativeLifecycleCoordinator()
        val closeIdentity = Any()
        val flight = coordinator.startConversationReplacement(closeIdentity)
        var externalStateOwnershipHeld = true

        assertTrue(coordinator.confirmClose(flight, closeIdentity))
        val authorization = coordinator.authorizeReplacement(flight)
        assertNotNull(authorization)

        val terminalSignal =
            coordinator.finalizeReplacementPublication(authorization!!) {
                assertTrue(externalStateOwnershipHeld)
                assertEquals(
                    NativeLifecycleCoordinator.Status.TEARDOWN_IN_PROGRESS,
                    coordinator.snapshot().status,
                )
            }
        externalStateOwnershipHeld = false

        assertNotNull(terminalSignal)
        assertEquals(
            NativeLifecycleCoordinator.Status.HEALTHY,
            coordinator.snapshot().status,
        )
        assertFalse(flight.completion.isCompleted)
    }

    @Test
    fun failed_replacement_escalates_same_flight_to_full_teardown() {
        val coordinator = NativeLifecycleCoordinator()
        val conversationIdentity = Any()
        val engineIdentity = Any()
        val flight = coordinator.startConversationReplacement(conversationIdentity)

        assertTrue(coordinator.confirmClose(flight, conversationIdentity))
        val staleAuthorization = coordinator.authorizeReplacement(flight)
        assertNotNull(staleAuthorization)
        assertTrue(
            coordinator.escalateReplacementFailureToFullTeardown(
                flight = flight,
                additionalCloseIdentity = engineIdentity,
            )
        )

        assertSame(flight, coordinator.snapshot().teardownFlight)
        assertEquals(
            NativeLifecycleCoordinator.TeardownMode.FULL_TEARDOWN,
            coordinator.snapshot().teardownMode,
        )
        assertNull(
            coordinator.finalizeReplacementPublication(staleAuthorization!!) {}
        )
        assertTrue(coordinator.confirmClose(flight, engineIdentity))
        assertNotNull(coordinator.completeFullTeardown(flight))
    }

    private fun NativeLifecycleCoordinator.startConversationReplacement(
        closeIdentity: Any,
        runtimeIdentity: Any = Any(),
    ): NativeLifecycleCoordinator.TeardownFlight {
        val result =
            startTeardown(
                mode = NativeLifecycleCoordinator.TeardownMode.CONVERSATION_REPLACEMENT,
                metadata =
                    metadata(
                        runtimeIdentity = runtimeIdentity,
                        closeIdentities = listOf(closeIdentity),
                    ),
            )
        return (result as NativeLifecycleCoordinator.TeardownStartResult.Started).flight
    }

    private fun metadata(
        runtimeIdentity: Any = Any(),
        closeIdentities: List<Any>,
    ): NativeLifecycleCoordinator.TeardownMetadata =
        NativeLifecycleCoordinator.TeardownMetadata(
            runtimeIdentity = runtimeIdentity,
            closeIdentities = closeIdentities,
            replacementIdentity = Any(),
        )
}
