package com.negi.survey.diagnostics

import com.negi.survey.diagnostics.SurveyDiagnosticAttribution.Interval
import org.junit.Assert.*
import org.junit.Test

class SurveyDiagnosticAttributionTest {
    private val first = Interval("survey-a", "process-a", 23, 1000, 500, 2000)
    private val second = Interval("survey-b", "process-a", 23, 3000, 2500, 5000)

    @Test fun onlyEntireExclusiveIntervalCanBeAttributed() {
        assertEquals(first, match(listOf(first, second), 1100, 1900))
        assertEquals(second, match(listOf(first, second), 3100, 3200))
        assertNull(match(listOf(first, second), 1800, 3100))
        assertNull(match(listOf(first), 999, 1100))
        assertNull(match(listOf(first.copy(exclusive = false)), 1100, 1900))
        assertNull(match(listOf(first, second.copy(startMillis = 1500)), 1600, 1900))
    }

    @Test fun reusedPidAndUnknownCaptureBoundsFailClosed() {
        assertNull(match(listOf(first.copy(processSession = "previous")), 1100, 1900))
        assertNull(match(listOf(first), 1900, 1100))
        assertNull(match(listOf(first.copy(endMillis = null)), 1100, 6001))
    }

    @Test fun crashAttributionRequiresDurableMatchingOpenEvidence() {
        val session = "12345678-1234-1234-9234-123456789abc"
        val survey = "12345678-1234-7234-9234-123456789abc"
        val open = Interval(survey, session, 23, 1000, 500)
        val summary = "v3|$session|$survey|PRODUCTION|500".toByteArray()
        val crash = java.time.Instant.ofEpochMilli(1200)
        fun recover(record: Interval?, bytes: ByteArray = summary, pid: Int = 23) =
            SurveyDiagnosticAttribution.exitEvidence(bytes, pid, crash) { _, _ -> record }

        assertEquals(open, recover(open))
        assertNull(recover(null))
        assertNull(recover(open.copy(exclusive = false)))
        assertNull(recover(open.copy(endMillis = 1100)))
        assertNull(recover(open.copy(startElapsed = 501)))
        assertNull(recover(open, pid = 24))
        assertNull(recover(open, "v3|$session||PRODUCTION|".toByteArray()))
        val legacy = "v2|$session|$survey|PRODUCTION".toByteArray()
        assertNull(recover(open, legacy))
        assertEquals(com.negi.survey.net.V2ArtifactStore.Profile.PRODUCTION, SurveyDiagnosticAttribution.exitProfile(legacy))
        assertNull(SurveyDiagnosticAttribution.exitEvidence(summary, 23, crash) { _, _ ->
            throw java.io.IOException("interrupted evidence write")
        })
    }

    private fun match(intervals: List<Interval>, start: Long, end: Long) =
        SurveyDiagnosticAttribution.exclusiveInterval(intervals, "process-a", 23, start, end, 6000)
}
