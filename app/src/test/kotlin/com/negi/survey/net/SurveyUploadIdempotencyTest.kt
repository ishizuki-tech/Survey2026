package com.negi.survey.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SurveyUploadIdempotencyTest {
    @Test
    fun canonicalPath_isStableAcrossRetriesAndNormalizesLogicalId() {
        val first = SurveyUploadWork.canonicalRemotePath(" Survey-UUID ")
        val retryAfterMidnight = SurveyUploadWork.canonicalRemotePath("survey-uuid")

        assertEquals(first, retryAfterMidnight)
        assertEquals("exports/surveys/", first.substringBeforeLast('/') + "/")
        assertNotEquals(first, SurveyUploadWork.canonicalRemotePath("another-survey"))
    }

    @Test
    fun matchingCanonicalRecord_isSafeToTreatAsRecoveredSuccess() {
        val payload = """{"survey_id":" Survey-UUID ","answer":1}"""
        val local = requireNotNull(SurveyUploadIdempotency.validateLocalPayload("survey-uuid", payload))

        assertEquals(
            SurveyUploadIdempotency.ExistingRecord.MATCHING,
            SurveyUploadIdempotency.classifyExisting(local, payload, payload)
        )
    }

    @Test
    fun conflictingCanonicalRecord_failsClosedWithoutOverwrite() {
        val expected = """{"survey_id":"survey-uuid","answer":1}"""
        val sameIdDifferentPayload = """{"survey_id":"survey-uuid","answer":2}"""
        val differentId = """{"survey_id":"other","answer":1}"""
        val local = requireNotNull(SurveyUploadIdempotency.validateLocalPayload("survey-uuid", expected))

        assertEquals(
            SurveyUploadIdempotency.ExistingRecord.CONFLICTING,
            SurveyUploadIdempotency.classifyExisting(local, expected, sameIdDifferentPayload)
        )
        assertEquals(
            SurveyUploadIdempotency.ExistingRecord.CONFLICTING,
            SurveyUploadIdempotency.classifyExisting(local, expected, differentId)
        )
        assertEquals(
            SurveyUploadIdempotency.ExistingRecord.CONFLICTING,
            SurveyUploadIdempotency.classifyExisting(local, expected, "not-json")
        )
    }

    @Test
    fun persistedLegacyWorkerFileName_cannotChooseTheCanonicalTarget() {
        val payload = """{"survey_id":"Survey-UUID","answer":1}"""
        val local = requireNotNull(SurveyUploadIdempotency.resolveLocalSurveyUpload("survey-uuid", payload))
        val persistedLegacyFileName = "exports/2026-10-19/old-survey-file.json"

        assertNotEquals(
            persistedLegacyFileName,
            local.canonicalRelativePath
        )
        assertEquals(
            SurveyUploadWork.canonicalRemotePath("survey-uuid"),
            local.canonicalRelativePath
        )
    }

    @Test
    fun localIdentityMustMatchPayloadButDoesNotUseRemoteComparisonRules() {
        val payload = """{"survey_id":"survey-uuid","answer":1}"""

        assertEquals(null, SurveyUploadIdempotency.validateLocalPayload("other", payload))
        assertEquals(null, SurveyUploadIdempotency.validateLocalPayload("survey-uuid", "not-json"))
        assertEquals(
            "survey-uuid",
            SurveyUploadIdempotency.validateLocalPayload(null, payload)?.normalizedSurveyId
        )
    }

    @Test
    fun onlyConflictJustifiesRemoteReconciliation_ambiguousAndValidationFailuresDoNot() {
        assertEquals(true, SurveyUploadIdempotency.shouldReconcileAfterCreateFailure(409))
        assertEquals(false, SurveyUploadIdempotency.shouldReconcileAfterCreateFailure(422))
        assertEquals(false, SurveyUploadIdempotency.shouldReconcileAfterCreateFailure(null))
    }
}
