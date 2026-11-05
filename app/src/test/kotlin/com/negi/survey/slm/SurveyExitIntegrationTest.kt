package com.negi.survey.slm

import com.negi.survey.runtime.HeavyRuntimeCoordinator
import com.negi.survey.vm.SurveySessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SurveyExitIntegrationTest {
    @Test fun compatible_selection_after_exit_keeps_engine_and_clears_logical_owner() = runTest {
        val gate = Semaphore(1)
        val boundary = RepositorySessionBoundary(gate)
        val coordinator = HeavyRuntimeCoordinator {}
        val sessions = SurveySessionStore()
        val oldOwner = sessions.ownerFor("survey-a@1")
        var creates = 0
        var conversation = Any()
        val oldConversation = conversation
        val generation = LiteRtGenerationLease("model", Any())
        coordinator.transitionLiteRt({ false }, {}) { creates++; generation }
        boundary.closeAndAwait { conversation = Any() }
        sessions.clearSession("survey-a@1")
        val selected = coordinator.transitionLiteRt({ it.sameGeneration(generation) }, {}) {
            creates++; LiteRtGenerationLease("model", Any())
        }
        assertSame(generation, selected)
        assertEquals(1, creates)
        assertNotSame(oldConversation, conversation)
        assertNotSame(oldOwner.viewModelStore, sessions.ownerFor("survey-b@2").viewModelStore)
        assertTrue(runCatching { boundary.requireOpen() }.isFailure)
    }

    @Test fun exit_cancels_collector_and_waits_for_native_driver_gate() = runTest {
        val gate = Semaphore(1)
        val boundary = RepositorySessionBoundary(gate)
        val nativeEntered = CompletableDeferred<Unit>()
        val nativeFinished = CompletableDeferred<Unit>()
        val collectorWait = CompletableDeferred<Unit>()
        val collector = launch { collectorWait.await() }
        boundary.register(collector)
        val driver = launch {
            gate.withPermit { nativeEntered.complete(Unit); nativeFinished.await() }
        }
        nativeEntered.await()
        var reset = false
        val exit = async { boundary.closeAndAwait { reset = true } }
        runCurrent()
        assertTrue(collector.isCancelled)
        assertFalse(reset)
        assertFalse(exit.isCompleted)
        assertTrue(runCatching { boundary.requireOpen() }.isFailure)
        nativeFinished.complete(Unit)
        driver.join()
        exit.await()
        assertTrue(reset)
    }

    @Test fun failed_exit_never_reopens_old_session() = runTest {
        val boundary = RepositorySessionBoundary(Semaphore(1))
        val failure = runCatching { boundary.closeAndAwait { error("native close failed") } }.exceptionOrNull()
        assertEquals("native close failed", failure?.message)
        assertTrue(runCatching { boundary.requireOpen() }.isFailure)
    }

    @Test fun cancelled_exit_waiter_keeps_admission_closed() = runTest {
        val gate = Semaphore(1)
        gate.acquire()
        val boundary = RepositorySessionBoundary(gate)
        val exit = async { boundary.closeAndAwait {} }
        runCurrent()
        exit.cancelAndJoin()
        assertTrue(runCatching { boundary.requireOpen() }.isFailure)
        gate.release()
    }
}
