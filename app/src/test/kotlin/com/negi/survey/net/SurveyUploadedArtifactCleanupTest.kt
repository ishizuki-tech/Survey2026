package com.negi.survey.net

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SurveyUploadedArtifactCleanupTest {
    private lateinit var directory: File

    @Before fun setUp() { directory = Files.createTempDirectory("uploaded-cleanup").toFile() }
    @After fun tearDown() { directory.deleteRecursively() }

    @Test
    fun matchingLedgerConfirmedArtifactIsDeleted() {
        val file = survey("survey")
        val ops = Ops(fingerprint = fingerprint(file))

        assertEquals(SurveyUploadedArtifactCleanup.Outcome.CLEANED,
            SurveyUploadedArtifactCleanup.cleanup("survey", file, ops))
        assertTrue(!file.exists())
    }

    @Test
    fun invalidIdentityOutsidePathActiveAndFailedDeleteAreRetained() {
        val mismatched = survey("other")
        assertEquals(SurveyUploadedArtifactCleanup.Outcome.DEFER_UNVERIFIED,
            SurveyUploadedArtifactCleanup.cleanup("survey", mismatched, Ops(fingerprint = fingerprint(mismatched))))
        assertTrue(mismatched.exists())

        val active = survey("survey")
        assertEquals(SurveyUploadedArtifactCleanup.Outcome.DEFER_ACTIVE_WORK,
            SurveyUploadedArtifactCleanup.cleanup("survey", active, Ops(fingerprint = fingerprint(active), active = true)))
        assertTrue(active.exists())

        val failed = survey("survey")
        assertEquals(SurveyUploadedArtifactCleanup.Outcome.RETRY_CLEANUP,
            SurveyUploadedArtifactCleanup.cleanup("survey", failed, Ops(fingerprint = fingerprint(failed), deleteSucceeds = false)))
        assertTrue(failed.exists())
    }

    @Test
    fun ledgerAbsenceAndUnownedCandidateNeverDelete() {
        val noLedger = survey("survey")
        assertEquals(SurveyUploadedArtifactCleanup.Outcome.DEFER_UNVERIFIED,
            SurveyUploadedArtifactCleanup.cleanup("survey", noLedger, Ops()))
        assertTrue(noLedger.exists())

        val unowned = survey("survey")
        assertEquals(SurveyUploadedArtifactCleanup.Outcome.DEFER_UNVERIFIED,
            SurveyUploadedArtifactCleanup.cleanup("survey", unowned, Ops(fingerprint = fingerprint(unowned), owned = false)))
        assertTrue(unowned.exists())
    }

    @Test
    fun differentPayloadWithSameSurveyIdAndHistoricalOrMalformedLedgerAreRetained() {
        val delivered = survey("survey", "A")
        val different = survey("survey", "B")
        val deliveredFingerprint = fingerprint(delivered)

        assertEquals(SurveyUploadedArtifactCleanup.Outcome.CLEANED,
            SurveyUploadedArtifactCleanup.cleanup("survey", delivered, Ops(fingerprint = deliveredFingerprint)))
        assertEquals(SurveyUploadedArtifactCleanup.Outcome.DEFER_UNVERIFIED,
            SurveyUploadedArtifactCleanup.cleanup("survey", different, Ops(fingerprint = deliveredFingerprint)))
        assertTrue(different.exists())

        val historical = survey("survey", "historical")
        assertEquals(SurveyUploadedArtifactCleanup.Outcome.DEFER_UNVERIFIED,
            SurveyUploadedArtifactCleanup.cleanup("survey", historical, Ops()))
        val malformed = survey("survey", "malformed")
        assertEquals(SurveyUploadedArtifactCleanup.Outcome.DEFER_UNVERIFIED,
            SurveyUploadedArtifactCleanup.cleanup("survey", malformed, Ops(fingerprint = "not-a-digest")))
        assertTrue(historical.exists())
        assertTrue(malformed.exists())
    }

    @Test
    fun byteLevelChangesIncludingExportedAtAreRetained() {
        val delivered = survey("survey", "answer", "2026-10-30")
        val whitespaceChanged = File(directory, "whitespace.json").apply {
            writeText("{ \"survey_id\" : \"survey\", \"answer\" : \"answer\", \"exported_at\" : \"2026-10-30\" }")
        }
        val differentExportedAt = survey("survey", "answer", "2026-10-31")
        val fingerprint = fingerprint(delivered)
        assertEquals(SurveyUploadedArtifactCleanup.Outcome.DEFER_UNVERIFIED,
            SurveyUploadedArtifactCleanup.cleanup("survey", whitespaceChanged, Ops(fingerprint = fingerprint)))
        assertEquals(SurveyUploadedArtifactCleanup.Outcome.DEFER_UNVERIFIED,
            SurveyUploadedArtifactCleanup.cleanup("survey", differentExportedAt, Ops(fingerprint = fingerprint)))
        assertTrue(whitespaceChanged.exists())
        assertTrue(differentExportedAt.exists())
    }

    private fun survey(id: String, answer: String = "", exportedAt: String? = null) = File(directory, "$id-${System.nanoTime()}.json").apply {
        writeText(buildString {
            append("{\"survey_id\":\"").append(id).append("\",\"answer\":\"").append(answer).append('\"')
            exportedAt?.let { append(",\"exported_at\":\"").append(it).append('\"') }
            append('}')
        })
    }

    private fun fingerprint(file: File) = SurveyUploadIdempotency.payloadFingerprint(file.readBytes())

    private class Ops(
        private val fingerprint: String? = null,
        private val owned: Boolean = true,
        private val active: Boolean = false,
        private val deleteSucceeds: Boolean = true,
    ) : SurveyUploadedArtifactCleanup.Operations {
        override fun ledgerPayloadFingerprint(surveyId: String) = fingerprint
        override fun isOwnedPendingFile(file: File) = owned
        override fun hasOtherActiveWork(surveyId: String, file: File) = active
        override fun payloadFingerprint(file: File, surveyId: String): String? {
            val content = file.readText()
            return SurveyUploadIdempotency.validateLocalPayload(surveyId, content)
                ?.let { SurveyUploadIdempotency.payloadFingerprint(file.readBytes()) }
        }
        override fun delete(file: File) = deleteSucceeds && file.delete()
    }
}
