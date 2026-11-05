package com.negi.survey

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Presentation state only: readiness is published exclusively after the runtime operation returns. */
internal class InitializationUiState(progressOnStart: Boolean) {
    enum class Phase { PENDING, LOADING, READY, ERROR }
    var phase by mutableStateOf(if (progressOnStart) Phase.LOADING else Phase.PENDING)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    @Volatile private var attempt = 0

    suspend fun run(progressOnStart: Boolean, initialize: suspend (() -> Unit) -> Unit) {
        val token = ++attempt
        phase = if (progressOnStart) Phase.LOADING else Phase.PENDING
        error = null
        try {
            initialize { if (token == attempt) phase = Phase.LOADING }
            currentCoroutineContext().ensureActive()
            if (token == attempt) phase = Phase.READY
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (token == attempt) {
                error = failure
                phase = Phase.ERROR
            }
        }
    }
}
