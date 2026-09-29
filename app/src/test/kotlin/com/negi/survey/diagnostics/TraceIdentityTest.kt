package com.negi.survey.diagnostics

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceIdentityTest {
    @Test
    fun answerTraceIdsAreUniqueUuidsAndSurveyShortIdIsStable() {
        val identity = TraceIdentity("fa692d45-1234-5678-9999-000000000000", deviceTag = "Pixel_ABC", processId = 42, appVersion = "1")

        assertEquals("fa692d45", identity.shortSurveyId)
        val first = TraceIdentity.newAnswerTraceId()
        val second = TraceIdentity.newAnswerTraceId()
        assertNotEquals(first, second)
        assertTrue(runCatching { UUID.fromString(first) }.isSuccess)
    }
}
