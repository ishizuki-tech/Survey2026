package com.negi.survey.slm

import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryTraceContextTest {
    @Test
    fun diagnostic_callback_failure_is_isolated() {
        val context = RepositoryTraceContext("answer", "Q14", PromptPhase.EVAL) {
            error("diagnostics unavailable")
        }

        context.emit(RepositoryTraceEvent.Started(7L, "model", "prompt"))
        assertTrue(true)
    }
}
