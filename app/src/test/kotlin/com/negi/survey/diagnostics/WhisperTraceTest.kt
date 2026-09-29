package com.negi.survey.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperTraceTest {
    @Test fun trace_payload_failure_is_isolated_from_voice_caller() {
            val trace = WhisperTrace(TraceIdentity("survey-uuid", "survey-u", "device", 7, "1"), TraceWriter(java.io.File.createTempFile("voice", ".open")))
            val answer = "answer-A"
            assertTrue(runCatching { trace.event("bad-payload", answer, "Q14", mapOf("nan" to Double.NaN)) }.isSuccess)
    }
}
