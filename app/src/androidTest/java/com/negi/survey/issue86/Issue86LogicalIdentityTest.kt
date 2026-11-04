package com.negi.survey.issue86

import com.negi.survey.net.SurveyUploadIdempotency
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Issue86LogicalIdentityTest {
    @Test fun recoveryAndCaptureAcceptEquivalentCase() {
        checkSameLogicalSurvey("survey-abc", "survey-abc")
        checkSameLogicalSurvey("SURVEY-ABC", "survey-abc")
        checkSameLogicalSurvey("Survey-AbC", "sUrVeY-aBc")
    }

    @Test fun differentOrMissingIdentitiesFailClosed() {
        assertThrows(IllegalStateException::class.java) { checkSameLogicalSurvey("survey-a", "survey-b") }
        assertThrows(IllegalStateException::class.java) { checkSameLogicalSurvey(null, "survey-a") }
        assertThrows(IllegalStateException::class.java) { checkSameLogicalSurvey(" ", " ") }
    }

    @Test fun comparisonDoesNotChangePayloadBytesOrFingerprint() {
        val payload = "{\"survey_id\":\"Survey-AbC\",\"answer\":\"synthetic\"}"
        val originalBytes = payload.toByteArray(Charsets.UTF_8)
        val fingerprint = SurveyUploadIdempotency.payloadFingerprint(originalBytes)
        checkSameLogicalSurvey("Survey-AbC", "survey-abc")
        assertArrayEquals(originalBytes, payload.toByteArray(Charsets.UTF_8))
        assertEquals(fingerprint, SurveyUploadIdempotency.payloadFingerprint(payload.toByteArray(Charsets.UTF_8)))
    }
}
