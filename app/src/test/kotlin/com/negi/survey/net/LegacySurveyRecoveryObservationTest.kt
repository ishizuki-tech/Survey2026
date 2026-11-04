package com.negi.survey.net

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LegacySurveyRecoveryObservationTest {
    private lateinit var directory: File
    private val record = LegacySurveyRecoveryStore.Record("legacy-id", "legacy.json", "route missing")

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("legacy-recovery-observation").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun observe_ledgerConfirmationWinsWithoutMutation() {
        val operations = FakeOperations(fileWithSurvey()).apply { uploaded = true }

        val snapshot = LegacySurveyRecoveryObservation.observe(record, operations)

        assertEquals(LegacySurveyRecoveryObservation.Status.ALREADY_UPLOADED, snapshot.status)
        assertFalse(snapshot.historicalRemoteDestinationKnown)
        assertEquals(0, operations.mutationAttempts)
    }

    @Test
    fun observe_activeLegacyAndCanonicalWorkAreReportedAsActive() {
        val legacy = FakeOperations(fileWithSurvey()).apply {
            work = LegacySurveyRecoveryObservation.WorkInspection(
                LegacySurveyRecoveryObservation.WorkState.ACTIVE_WORK,
                SurveyUploadWorkTracker.IdentityKind.LEGACY,
            )
        }
        val canonical = FakeOperations(fileWithSurvey()).apply {
            work = LegacySurveyRecoveryObservation.WorkInspection(
                LegacySurveyRecoveryObservation.WorkState.ACTIVE_WORK,
                SurveyUploadWorkTracker.IdentityKind.LOGICAL,
            )
        }

        assertEquals(LegacySurveyRecoveryObservation.Status.ACTIVE_WORK, LegacySurveyRecoveryObservation.observe(record, legacy).status)
        assertEquals(LegacySurveyRecoveryObservation.Status.ACTIVE_WORK, LegacySurveyRecoveryObservation.observe(record, canonical).status)
        assertEquals(0, legacy.mutationAttempts + canonical.mutationAttempts)
    }

    @Test
    fun observe_missingInvalidUnknownAndManualStatesAreConservative() {
        assertEquals(
            LegacySurveyRecoveryObservation.Status.MISSING_ARTIFACT,
            LegacySurveyRecoveryObservation.observe(
                record,
                FakeOperations(File(directory, "missing.json")),
            ).status,
        )
        assertEquals(
            LegacySurveyRecoveryObservation.Status.INVALID_ARTIFACT,
            LegacySurveyRecoveryObservation.observe(record, FakeOperations(file("{bad"))).status,
        )
        assertEquals(
            LegacySurveyRecoveryObservation.Status.UNKNOWN_LOCAL_STATE,
            LegacySurveyRecoveryObservation.observe(record, FakeOperations(fileWithSurvey()).apply { failWork = true }).status,
        )
        val operations = FakeOperations(fileWithSurvey())
        val first = LegacySurveyRecoveryObservation.observe(record, operations)
        val second = LegacySurveyRecoveryObservation.observe(record, operations)
        assertEquals(LegacySurveyRecoveryObservation.Status.MANUAL_REVIEW_REQUIRED, first.status)
        assertEquals(first, second)
        assertTrue(first.operatorReviewRequired)
        assertEquals(0, operations.mutationAttempts)
    }

    @Test
    fun observeAuthorityPinIsReadOnlyOperationalMetadata() {
        val operations = FakeOperations(fileWithSurvey()).apply {
            authority = SurveyUploadExecutionAuthority.Owner.CORRUPT_OR_UNKNOWN
        }

        val snapshot = LegacySurveyRecoveryObservation.observe(record, operations)

        assertEquals(SurveyUploadExecutionAuthority.Owner.CORRUPT_OR_UNKNOWN, snapshot.executionAuthority)
        assertEquals(0, operations.mutationAttempts)
    }

    private fun fileWithSurvey(): File = file("""{"survey_id":"legacy-id"}""")
    private fun file(contents: String): File = File(directory, "legacy.json").apply { writeText(contents) }

    private class FakeOperations(private val staged: File) : LegacySurveyRecoveryObservation.Operations {
        var uploaded = false
        var failWork = false
        var work = LegacySurveyRecoveryObservation.WorkInspection(LegacySurveyRecoveryObservation.WorkState.NO_ACTIVE_WORK)
        var mutationAttempts = 0
        var authority: SurveyUploadExecutionAuthority.Owner = SurveyUploadExecutionAuthority.Owner.NONE

        override fun stagedFile(record: LegacySurveyRecoveryStore.Record): File = staged
        override fun validate(file: File, surveyId: String): SurveyUploadWork.SurveyArtifactValidation =
            SurveyUploadWork.validateCanonicalArtifact(file, surveyId)
        override fun ledgerContains(surveyId: String): Boolean = uploaded
        override fun canonicalRouteReserved(surveyId: String): Boolean = false
        override fun executionAuthority(surveyId: String): SurveyUploadExecutionAuthority.Owner =
            authority
        override fun inspectWork(
            record: LegacySurveyRecoveryStore.Record,
            file: File,
        ): LegacySurveyRecoveryObservation.WorkInspection {
            if (failWork) error("offline/unavailable")
            return work
        }
    }
}
