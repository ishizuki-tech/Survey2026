/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyUploadReschedulerTest.kt
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */
package com.negi.survey.net

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SurveyUploadReschedulerTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("survey-upload-rescheduler-test").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun recover_noPendingSurveysDoesNotReconcile() {
        val operations = FakeOperations(discovery())

        val summary = SurveyUploadRescheduler.recoverForTesting(operations)

        assertEquals(0, summary.discoveredSurveyCount)
        assertEquals(0, summary.reconciledCandidateCount)
        assertTrue(summary.candidates.isEmpty())
        assertTrue(operations.reconciled.isEmpty())
    }

    @Test
    fun recover_reconcilesOneCanonicalCandidate() {
        val canonical = survey("a.json", "survey-a")
        val operations = FakeOperations(discovery()).apply { resultFor[canonical] = result(KEEP_TRACKED) }

        val summary = SurveyUploadRescheduler.recoverForTesting(operations)

        assertEquals(listOf(canonical), operations.reconciled)
        assertEquals(SurveyUploadRescheduler.RecoveryClassification.ACTIVE, summary.candidates.single().classification)
    }

    @Test
    fun recover_duplicatesAndCaseWhitespaceVariantsReconcileOnlyCanonical() {
        val canonical = survey("a.json", " Survey-A ")
        val duplicate = survey("z.json", "survey-a")
        val operations = FakeOperations(discovery()).apply { resultFor[canonical] = result(ENQUEUE_NEW) }

        val summary = SurveyUploadRescheduler.recoverForTesting(operations)

        assertEquals(1, summary.discoveredSurveyCount)
        assertEquals(1, summary.duplicateFileCount)
        assertEquals(listOf(canonical), operations.reconciled)
        assertFalse(operations.reconciled.contains(duplicate))
        assertTrue(canonical.exists())
        assertTrue(duplicate.exists())
    }

    @Test
    fun recover_unclassifiedFilesRemainUntouched() {
        val malformed = File(directory, "broken.json").apply { writeText("{not-json") }
        val temporary = File(directory, "upload.tmp").apply { writeText("temporary") }
        val operations = FakeOperations(discovery())

        val summary = SurveyUploadRescheduler.recoverForTesting(operations)

        assertEquals(2, summary.unclassifiedFileCount)
        assertTrue(operations.reconciled.isEmpty())
        assertTrue(malformed.exists())
        assertTrue(temporary.exists())
    }

    @Test
    fun recover_classifiesEveryCurrentSurveyWorkAction() {
        val actions = mapOf(
            ENQUEUE_NEW to SUBMITTED,
            RECOVER_NEW to SUBMITTED,
            KEEP_TRACKED to ACTIVE,
            KEEP_LEGACY to ACTIVE,
            ADOPT_LEGACY to ACTIVE,
            ENQUEUED_TRACKER_UNCONFIRMED to SUBMITTED_TRACKER_UNCONFIRMED,
            SKIP_UPLOADED to ALREADY_UPLOADED,
            SKIP_UPLOADED_TRACKER_CLEANUP_FAILED to ALREADY_UPLOADED,
            DEFER_SUCCEEDED_WITHOUT_LEDGER to DEFERRED,
            DEFER_ENQUEUED_MISSING to DEFERRED,
            DEFER_LEGACY_HISTORY to DEFERRED,
            DEFER_UNKNOWN_STATE to DEFERRED,
            DEFER_TRACKER_WRITE to DEFERRED,
            DEFER_TRACKER_FAILURE to DEFERRED,
            DEFER_LEDGER_FAILURE to DEFERRED,
            DEFER_ARTIFACT_FAILURE to DEFERRED,
            DEFER_BUILD_FAILURE to DEFERRED,
            DEFER_ENQUEUE_FAILURE to DEFERRED,
            DEFER_ENQUEUE_TIMEOUT to DEFERRED,
            INVALID_ARTIFACT to INVALID
        )
        val candidates = actions.keys.mapIndexed { index, action ->
            candidate("$index.json", "survey-$index")
        }
        val operations = FakeOperations(
            PendingSurveyUploads.PendingSurveyDiscovery(candidates, emptyList())
        ).apply {
            candidates.zip(actions.keys).forEach { (candidate, action) ->
                resultFor[candidate.canonicalFile] = result(action)
            }
        }

        val summary = SurveyUploadRescheduler.recoverForTesting(operations)

        assertEquals(candidates.map { it.canonicalFile }, operations.reconciled)
        assertEquals(actions.values.toList(), summary.candidates.map { it.classification })
    }

    @Test
    fun recover_operationalExceptionDoesNotBlockLaterCandidate() {
        val first = candidate("a.json", "survey-a")
        val second = candidate("b.json", "survey-b")
        val operations = FakeOperations(
            PendingSurveyUploads.PendingSurveyDiscovery(listOf(first, second), emptyList())
        ).apply {
            failureFor[first.canonicalFile] = IllegalStateException("temporary")
            resultFor[second.canonicalFile] = result(KEEP_TRACKED)
        }

        val summary = SurveyUploadRescheduler.recoverForTesting(operations)

        assertEquals(listOf(first.canonicalFile, second.canonicalFile), operations.reconciled)
        assertEquals(1, summary.operationalFailureCount)
        assertEquals(OPERATIONAL_FAILURE, summary.candidates.first().classification)
        assertEquals(ACTIVE, summary.candidates.last().classification)
    }

    @Test(expected = CancellationException::class)
    fun recover_cancellationPropagates() {
        val candidate = candidate("a.json", "survey-a")
        val operations = FakeOperations(
            PendingSurveyUploads.PendingSurveyDiscovery(listOf(candidate), emptyList())
        ).apply { failureFor[candidate.canonicalFile] = CancellationException("cancel") }

        SurveyUploadRescheduler.recoverForTesting(operations)
    }

    @Test(expected = AssertionError::class)
    fun recover_errorIsNotSwallowed() {
        val candidate = candidate("a.json", "survey-a")
        val operations = FakeOperations(
            PendingSurveyUploads.PendingSurveyDiscovery(listOf(candidate), emptyList())
        ).apply { errorFor[candidate.canonicalFile] = AssertionError("programmer error") }

        SurveyUploadRescheduler.recoverForTesting(operations)
    }

    @Test
    fun recover_usesDiscoveryOrderDeterministicallyWithoutFileMutation() {
        val zed = candidate("z.json", "zed")
        val alpha = candidate("a.json", "alpha")
        val unclassified = File(directory, "broken.tmp").apply { writeText("keep") }
        val operations = FakeOperations(
            PendingSurveyUploads.PendingSurveyDiscovery(listOf(zed, alpha), listOf(unclassified))
        ).apply {
            resultFor[zed.canonicalFile] = result(KEEP_TRACKED)
            resultFor[alpha.canonicalFile] = result(KEEP_TRACKED)
        }

        val summary = SurveyUploadRescheduler.recoverForTesting(operations)

        assertEquals(listOf(zed.canonicalFile, alpha.canonicalFile), operations.reconciled)
        assertSame(zed.canonicalFile, summary.candidates.first().canonicalFile)
        assertTrue(zed.canonicalFile.exists())
        assertTrue(alpha.canonicalFile.exists())
        assertTrue(unclassified.exists())
    }

    private fun discovery(): PendingSurveyUploads.PendingSurveyDiscovery =
        PendingSurveyUploads.discoverPendingSurveys(directory)

    private fun survey(name: String, surveyId: String): File =
        File(directory, name).apply { writeText("{\"survey_id\":\"$surveyId\"}") }

    private fun candidate(name: String, surveyId: String): PendingSurveyUploads.PendingSurveyCandidate {
        val file = survey(name, surveyId)
        return PendingSurveyUploads.PendingSurveyCandidate(surveyId, file, emptyList())
    }

    private fun result(action: SurveyUploadWork.SurveyWorkAction) =
        SurveyUploadWork.SurveyWorkReconcileResult(
            normalizedSurveyId = "survey",
            logicalWorkName = "logical",
            legacyWorkName = "legacy",
            decision = SurveyUploadWork.SurveyWorkDecision(action, action.name),
            enqueued = action == ENQUEUE_NEW || action == RECOVER_NEW
        )

    private class FakeOperations(
        private val pendingDiscovery: PendingSurveyUploads.PendingSurveyDiscovery
    ) : SurveyUploadRescheduler.Operations {
        val reconciled = mutableListOf<File>()
        val resultFor = mutableMapOf<File, SurveyUploadWork.SurveyWorkReconcileResult>()
        val failureFor = mutableMapOf<File, Exception>()
        val errorFor = mutableMapOf<File, Error>()

        override fun discover(): PendingSurveyUploads.PendingSurveyDiscovery = pendingDiscovery

        override fun reconcile(
            candidate: PendingSurveyUploads.PendingSurveyCandidate
        ): SurveyUploadWork.SurveyWorkReconcileResult {
            reconciled += candidate.canonicalFile
            errorFor[candidate.canonicalFile]?.let { throw it }
            failureFor[candidate.canonicalFile]?.let { throw it }
            return requireNotNull(resultFor[candidate.canonicalFile])
        }
    }

    private companion object {
        val ENQUEUE_NEW = SurveyUploadWork.SurveyWorkAction.ENQUEUE_NEW
        val RECOVER_NEW = SurveyUploadWork.SurveyWorkAction.RECOVER_NEW
        val KEEP_TRACKED = SurveyUploadWork.SurveyWorkAction.KEEP_TRACKED
        val KEEP_LEGACY = SurveyUploadWork.SurveyWorkAction.KEEP_LEGACY
        val ADOPT_LEGACY = SurveyUploadWork.SurveyWorkAction.ADOPT_LEGACY
        val ENQUEUED_TRACKER_UNCONFIRMED = SurveyUploadWork.SurveyWorkAction.ENQUEUED_TRACKER_UNCONFIRMED
        val SKIP_UPLOADED = SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED
        val SKIP_UPLOADED_TRACKER_CLEANUP_FAILED = SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED_TRACKER_CLEANUP_FAILED
        val DEFER_SUCCEEDED_WITHOUT_LEDGER = SurveyUploadWork.SurveyWorkAction.DEFER_SUCCEEDED_WITHOUT_LEDGER
        val DEFER_ENQUEUED_MISSING = SurveyUploadWork.SurveyWorkAction.DEFER_ENQUEUED_MISSING
        val DEFER_LEGACY_HISTORY = SurveyUploadWork.SurveyWorkAction.DEFER_LEGACY_HISTORY
        val DEFER_UNKNOWN_STATE = SurveyUploadWork.SurveyWorkAction.DEFER_UNKNOWN_STATE
        val DEFER_TRACKER_WRITE = SurveyUploadWork.SurveyWorkAction.DEFER_TRACKER_WRITE
        val DEFER_TRACKER_FAILURE = SurveyUploadWork.SurveyWorkAction.DEFER_TRACKER_FAILURE
        val DEFER_LEDGER_FAILURE = SurveyUploadWork.SurveyWorkAction.DEFER_LEDGER_FAILURE
        val DEFER_ARTIFACT_FAILURE = SurveyUploadWork.SurveyWorkAction.DEFER_ARTIFACT_FAILURE
        val DEFER_BUILD_FAILURE = SurveyUploadWork.SurveyWorkAction.DEFER_BUILD_FAILURE
        val DEFER_ENQUEUE_FAILURE = SurveyUploadWork.SurveyWorkAction.DEFER_ENQUEUE_FAILURE
        val DEFER_ENQUEUE_TIMEOUT = SurveyUploadWork.SurveyWorkAction.DEFER_ENQUEUE_TIMEOUT
        val INVALID_ARTIFACT = SurveyUploadWork.SurveyWorkAction.INVALID_ARTIFACT

        val SUBMITTED = SurveyUploadRescheduler.RecoveryClassification.SUBMITTED
        val ACTIVE = SurveyUploadRescheduler.RecoveryClassification.ACTIVE
        val SUBMITTED_TRACKER_UNCONFIRMED = SurveyUploadRescheduler.RecoveryClassification.SUBMITTED_TRACKER_UNCONFIRMED
        val ALREADY_UPLOADED = SurveyUploadRescheduler.RecoveryClassification.ALREADY_UPLOADED
        val DEFERRED = SurveyUploadRescheduler.RecoveryClassification.DEFERRED
        val INVALID = SurveyUploadRescheduler.RecoveryClassification.INVALID
        val OPERATIONAL_FAILURE = SurveyUploadRescheduler.RecoveryClassification.OPERATIONAL_FAILURE
    }
}
