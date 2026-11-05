package com.negi.survey

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InitializationUiStateTest {
    @Test fun cold_and_incompatible_acquisition_show_progress_before_content() = runTest {
        for (initialProgress in listOf(true, false)) {
            val ui = InitializationUiState(initialProgress)
            ui.run(initialProgress) { show ->
                show()
                assertEquals(InitializationUiState.Phase.LOADING, ui.phase)
            }
            assertEquals(InitializationUiState.Phase.READY, ui.phase)
        }
    }

    @Test fun compatible_transition_never_enters_loading_and_holds_content_until_complete() = runTest {
        val ui = InitializationUiState(false)
        assertEquals(InitializationUiState.Phase.PENDING, ui.phase)
        ui.run(false) {
            assertEquals(InitializationUiState.Phase.PENDING, ui.phase)
        }
        assertEquals(InitializationUiState.Phase.READY, ui.phase)
    }

    @Test fun failure_and_retry_preserve_error_and_progress() = runTest {
        val ui = InitializationUiState(false)
        val failure = IllegalStateException("poisoned")
        ui.run(false) { throw failure }
        assertSame(failure, ui.error)
        assertEquals(InitializationUiState.Phase.ERROR, ui.phase)
        ui.run(false) { show ->
            assertNull(ui.error)
            show()
            assertEquals(InitializationUiState.Phase.LOADING, ui.phase)
        }
        assertEquals(InitializationUiState.Phase.READY, ui.phase)
    }

    @Test fun epoch_and_cancelled_attempt_cannot_publish_stale_readiness() = runTest {
        val previous = InitializationUiState(false)
        val next = InitializationUiState(false)
        val wait = CompletableDeferred<Unit>()
        val job = async { previous.run(false) { wait.await() } }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(InitializationUiState.Phase.PENDING, previous.phase)
        assertEquals(InitializationUiState.Phase.PENDING, next.phase)
        next.run(false) { show -> show() }
        assertEquals(InitializationUiState.Phase.READY, next.phase)
    }
}
