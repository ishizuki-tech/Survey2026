package com.negi.survey.diagnostics

import com.negi.survey.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticContentPolicyTest {
    private val sentinel = "SENSITIVE_RESPONDENT_SENTINEL_85"

    @Test
    fun runtimePolicyFollowsGeneratedBuildConfig() {
        assertEquals(
            !BuildConfig.DIAGNOSTICS_PRODUCTION,
            DiagnosticContentPolicy.permitsRawRespondentContent,
        )

        if (BuildConfig.DIAGNOSTICS_PRODUCTION) {
            assertNull(DiagnosticContentPolicy.rawOrNull(sentinel))
        } else {
            assertEquals(sentinel, DiagnosticContentPolicy.rawOrNull(sentinel))
        }
    }

    @Test
    fun nonProductionTraceFieldsPreserveRawDiagnostics() {
        val fields = rawFields()

        val sanitized = DiagnosticContentPolicy.sanitizeTraceFields(fields, production = false)

        assertEquals(fields, sanitized)
        assertEquals(sentinel, sanitized["transcript"])
        assertEquals(sentinel, sanitized["rawResponse"])
    }

    @Test
    fun productionTraceFieldsRemoveRawDiagnosticsAndKeepMetadata() {
        val sanitized = DiagnosticContentPolicy.sanitizeTraceFields(rawFields(), production = true)

        assertFalse(sanitized.values.any { it == sentinel })
        assertFalse(sanitized.containsKey("transcript"))
        assertFalse(sanitized.containsKey("prompt"))
        assertFalse(sanitized.containsKey("finalPrompt"))
        assertFalse(sanitized.containsKey("rawResponse"))
        assertFalse(sanitized.containsKey("candidate"))
        assertFalse(sanitized.containsKey("followup"))
        assertFalse(sanitized.containsKey("extractedFollowups"))
        assertFalse(sanitized.containsKey("error"))
        assertEquals(31, sanitized["transcriptLength"])
        assertEquals(42, sanitized["promptLength"])
        assertEquals(47, sanitized["responseLength"])
        assertEquals("Q14", sanitized["questionId"])
        assertEquals(95, sanitized["score"])
        assertEquals(true, sanitized["parseSuccess"])
        assertTrue(sanitized["missingComponentIds"] is List<*>)
    }

    @Test
    fun productionDirectLogFormattingKeepsClassAndDropsThrowableMessage() {
        val throwable = IllegalStateException(sentinel)

        val production = DiagnosticContentPolicy.directLogMessage(
            operation = "transcription failed",
            throwable = throwable,
            production = true,
        )
        val nonProduction = DiagnosticContentPolicy.directLogMessage(
            operation = "transcription failed",
            throwable = throwable,
            production = false,
        )

        assertFalse(production.contains(sentinel))
        assertTrue(production.contains(IllegalStateException::class.java.name))
        assertTrue(nonProduction.contains(sentinel))
    }

    private fun rawFields(): Map<String, Any?> = mapOf(
        "transcript" to sentinel,
        "prompt" to sentinel,
        "finalPrompt" to sentinel,
        "rawResponse" to sentinel,
        "candidate" to sentinel,
        "followup" to sentinel,
        "extractedFollowups" to listOf(sentinel),
        "error" to sentinel,
        "transcriptLength" to 31,
        "promptLength" to 42,
        "responseLength" to 47,
        "questionId" to "Q14",
        "score" to 95,
        "parseSuccess" to true,
        "missingComponentIds" to listOf("yield_estimate"),
    )
}
