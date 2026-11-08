package com.negi.survey

import com.negi.survey.slm.RepositorySessionBoundary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeSurveyExitStateTest {
    @Test fun exit_timeout_restores_back_but_blocks_start_and_retry_clears_once() = runTest {
        val gate = Semaphore(1)
        gate.acquire()
        val boundary = RepositorySessionBoundary(gate)
        val ui = HomeSurveyExitState()
        var clears = 0
        var resets = 0
        val attempt = async {
            ui.exit({ boundary.closeAndAwait { resets++ } }, { clears++ })
        }
        runCurrent()
        assertFalse(ui.canGoBack)
        advanceTimeBy(60_000)
        runCurrent()
        attempt.await()
        assertTrue(ui.canGoBack)
        assertFalse(ui.canStart)
        assertNotNull(ui.error)
        assertTrue(runCatching { boundary.requireOpen() }.isFailure)
        assertEquals(0, clears)
        assertEquals(0, resets)
        gate.release()
        ui.exit({ boundary.closeAndAwait { resets++ } }, { clears++ })
        ui.exit({ error("completed exit must not repeat") }, { clears++ })
        assertEquals(1, clears)
        assertEquals(1, resets)
        assertNull(ui.error)
        assertFalse(ui.canStart)
        assertFalse(ui.canGoBack)
    }

    @Test fun native_failure_keeps_session_and_admission_closed_until_successful_retry() = runTest {
        val boundary = RepositorySessionBoundary(Semaphore(1))
        val ui = HomeSurveyExitState()
        var clears = 0
        ui.exit({ boundary.closeAndAwait { error("native cleanup failed") } }, { clears++ })
        assertEquals(0, clears)
        assertTrue(ui.canGoBack)
        assertFalse(ui.canStart)
        assertNotNull(ui.error)
        assertFalse(ui.canStart)
        assertTrue(runCatching { boundary.requireOpen() }.isFailure)
        ui.exit({ boundary.closeAndAwait {} }, { clears++ })
        assertEquals(1, clears)
    }

    @Test fun cancelling_parent_does_not_clear_session_or_report_recoverable_failure() = runTest {
        val ui = HomeSurveyExitState()
        val entered = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        var clears = 0
        val task = async { ui.exit({ entered.complete(Unit); wait.await() }, { clears++ }) }
        entered.await()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(0, clears)
        assertNull(ui.error)
        assertFalse(ui.running)
        assertFalse(ui.canStart)
        assertTrue(ui.canGoBack)
    }

    @Test fun parent_timeout_and_independent_cancellation_are_not_swallowed() = runTest {
        val ui = HomeSurveyExitState()
        var clears = 0
        val timed = runCatching {
            withTimeout(10) { ui.exit({ CompletableDeferred<Unit>().await() }, { clears++ }) }
        }.exceptionOrNull()
        assertTrue(timed is CancellationException)
        currentCoroutineContext().ensureActive()
        assertNull(ui.error)
        val independent = runCatching {
            ui.exit({ throw CancellationException("independent cancellation") }, { clears++ })
        }.exceptionOrNull()
        assertTrue(independent is CancellationException)
        assertEquals(0, clears)
        assertTrue(ui.canGoBack)
        assertFalse(ui.canStart)
    }
}
