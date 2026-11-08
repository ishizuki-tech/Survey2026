package com.negi.survey

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Home UI permissions after repository admission has irreversibly closed. */
internal class HomeSurveyExitState(
    admissionClosed: Boolean = false,
    completed: Boolean = false,
) {
    private var admissionClosed by mutableStateOf(admissionClosed)
    private var completed by mutableStateOf(completed)
    var running by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val canStart: Boolean get() = !admissionClosed
    val canGoBack: Boolean get() = !running && !completed


    suspend fun exit(endSession: suspend () -> Unit, onSuccess: () -> Unit) {
        if (!canGoBack) return
        admissionClosed = true
        running = true
        error = null
        try {
            endSession()
            currentCoroutineContext().ensureActive()
            onSuccess()
            completed = true
        } catch (timeout: TimeoutCancellationException) {
            // A timeout owned by the parent is genuine lifecycle cancellation.
            currentCoroutineContext().ensureActive()
            error = RETRY_MESSAGE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            error = RETRY_MESSAGE
        } finally {
            running = false
        }
    }

    companion object {
        private const val RETRY_MESSAGE = "Could not safely end the survey. Press Exit to try again."
        val Saver = listSaver<HomeSurveyExitState, Boolean>(
            save = { listOf(it.admissionClosed, it.completed) },
            restore = { values ->
                HomeSurveyExitState(values[0], values[1]).apply {
                    if (values[0] && !values[1]) error = RETRY_MESSAGE
                }
            },
        )
    }
}
