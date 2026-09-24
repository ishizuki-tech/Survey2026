/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyUploadFinalizerTest.kt
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */
package com.negi.survey.net

import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.vm.SurveyFinalizationSnapshot
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SurveyUploadFinalizerTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("survey-finalizer-test").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun finalize_blankSurveyIdFailsWithoutOperations() = runBlocking {
        val operations = FakeOperations(directory)

        val result = finalizer(operations).finalize(snapshot("  "), config(), tag(), STAMP)

        assertTrue(result is SurveyFinalizationResult.Failure)
        assertTrue(operations.events.isEmpty())
    }

    @Test
    fun finalize_alreadyUploadedSkipsLookupStageReconciliationAndArtifacts() = runBlocking {
        val operations = FakeOperations(directory).apply { uploaded = true }

        val result = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

        assertEquals(SurveyFinalizationResult.AlreadyUploaded, result)
        assertEquals(0, operations.pendingLookupCalls.get())
        assertEquals(0, operations.stageCalls.get())
        assertEquals(0, operations.reconcileCalls.get())
        assertEquals(0, operations.deleteCalls.get())
        assertTrue(operations.events.isEmpty())
    }

    @Test
    fun finalize_stagesThenReconcilesAndSchedulesArtifactsForNewEnqueue() = runBlocking {
        val operations = FakeOperations(directory).apply {
            reconciliationResult = reconciliation(SurveyUploadWork.SurveyWorkAction.ENQUEUE_NEW, enqueued = true)
        }

        val result = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

        assertEquals(SurveyFinalizationResult.Queued(File(directory, "survey-uuid.json"), reused = false), result)
        assertEquals(1, operations.stageCalls.get())
        assertEquals(1, operations.reconcileCalls.get())
        assertEquals(File(directory, "survey-uuid.json"), operations.reconciledFile)
        assertEquals("survey-uuid", operations.reconciledSurveyId)
        assertEquals(0, operations.deleteCalls.get())
        assertEquals(listOf("lookup", "stage", "reconcile", "voice", "log"), operations.events)
    }

    @Test
    fun finalize_stageFailureReturnsFailureWithoutReconciliationOrArtifacts() = runBlocking {
        val operations = FakeOperations(directory).apply { stageFailure = IllegalStateException("disk full") }

        val result = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

        assertTrue(result is SurveyFinalizationResult.Failure)
        assertEquals(0, operations.reconcileCalls.get())
        assertFalse(operations.events.contains("voice"))
        assertFalse(operations.events.contains("log"))
    }

    @Test
    fun finalize_reusesExistingPendingFileWithTrimmedCasePreservedLookup() = runBlocking {
        val existing = File(directory, "a-old.json").apply { writeText("{\"survey_id\":\"Survey-UUID\"}") }
        File(directory, "z-new.json").apply { writeText("{\"survey_id\":\"Survey-UUID\"}") }
        val operations = FakeOperations(directory).apply {
            pendingFile = existing
            reconciliationResult = reconciliation(SurveyUploadWork.SurveyWorkAction.KEEP_TRACKED)
        }

        val result = finalizer(operations).finalize(snapshot(" Survey-UUID "), config(), tag(), STAMP)

        assertEquals(SurveyFinalizationResult.Queued(existing, reused = true), result)
        assertEquals(0, operations.stageCalls.get())
        assertEquals(existing, operations.reconciledFile)
        assertEquals("Survey-UUID", operations.pendingLookupSurveyId)
        assertEquals("Survey-UUID", operations.reconciledSurveyId)
        assertEquals("a-old.json", operations.reconciledFile!!.name)
        assertEquals(2, directory.listFiles().orEmpty().count { it.extension == "json" })
    }

    @Test
    fun finalize_safeReconciliationOutcomesQueueAndScheduleArtifacts() = runBlocking {
        val safeOutcomes = listOf(
            reconciliation(SurveyUploadWork.SurveyWorkAction.ENQUEUE_NEW, enqueued = true),
            reconciliation(SurveyUploadWork.SurveyWorkAction.RECOVER_NEW, enqueued = true),
            reconciliation(SurveyUploadWork.SurveyWorkAction.KEEP_TRACKED),
            reconciliation(SurveyUploadWork.SurveyWorkAction.KEEP_LEGACY),
            reconciliation(SurveyUploadWork.SurveyWorkAction.ADOPT_LEGACY),
            reconciliation(SurveyUploadWork.SurveyWorkAction.ENQUEUED_TRACKER_UNCONFIRMED, enqueued = true)
        )

        safeOutcomes.forEach { reconciliation ->
            val operations = FakeOperations(directory).apply { reconciliationResult = reconciliation }
            val result = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

            assertTrue("${reconciliation.decision.action} should queue", result is SurveyFinalizationResult.Queued)
            assertEquals(1, operations.reconcileCalls.get())
            assertTrue(operations.events.contains("voice"))
            assertTrue(operations.events.contains("log"))
        }
    }

    @Test
    fun finalize_uploadedReconciliationOutcomesSkipArtifacts() = runBlocking {
        listOf(
            SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED,
            SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED_TRACKER_CLEANUP_FAILED
        ).forEach { action ->
            val operations = FakeOperations(directory).apply { reconciliationResult = reconciliation(action) }

            val result = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

            assertEquals(SurveyFinalizationResult.AlreadyUploaded, result)
            assertEquals(1, operations.reconcileCalls.get())
            assertEquals(1, operations.deleteCalls.get())
            assertFalse(operations.deletedFile!!.exists())
            assertFalse(operations.events.contains("voice"))
            assertFalse(operations.events.contains("log"))
        }
    }

    @Test
    fun finalize_lateUploadedDeletesSelectedReusedPendingFile() = runBlocking {
        val existing = File(directory, "existing.json").apply { writeText("{\"survey_id\":\"survey-uuid\"}") }
        val operations = FakeOperations(directory).apply {
            pendingFile = existing
            reconciliationResult = reconciliation(SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED)
        }

        val result = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

        assertEquals(SurveyFinalizationResult.AlreadyUploaded, result)
        assertEquals(0, operations.stageCalls.get())
        assertEquals(existing, operations.deletedFile)
        assertFalse(existing.exists())
        assertFalse(operations.events.contains("voice"))
        assertFalse(operations.events.contains("log"))
    }

    @Test
    fun finalize_lateUploadedCleanupFailureStillReturnsAlreadyUploaded() = runBlocking {
        val operations = FakeOperations(directory).apply {
            reconciliationResult = reconciliation(SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED)
            deleteResult = false
        }

        val result = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

        assertEquals(SurveyFinalizationResult.AlreadyUploaded, result)
        assertEquals(1, operations.deleteCalls.get())
        assertTrue(operations.deletedFile!!.exists())
        assertFalse(operations.events.contains("voice"))
        assertFalse(operations.events.contains("log"))
    }

    @Test
    fun finalize_lateUploadedCleanupExceptionStillReturnsAlreadyUploaded() = runBlocking {
        val operations = FakeOperations(directory).apply {
            reconciliationResult = reconciliation(SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED_TRACKER_CLEANUP_FAILED)
            deleteFailure = IllegalStateException("delete")
        }

        val result = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

        assertEquals(SurveyFinalizationResult.AlreadyUploaded, result)
        assertEquals(1, operations.deleteCalls.get())
        assertTrue(operations.deletedFile!!.exists())
        assertFalse(operations.events.contains("voice"))
        assertFalse(operations.events.contains("log"))
    }

    @Test
    fun finalize_unsafeReconciliationOutcomesFailWithoutArtifacts() = runBlocking {
        val unsafeActions = listOf(
            SurveyUploadWork.SurveyWorkAction.DEFER_ENQUEUE_TIMEOUT,
            SurveyUploadWork.SurveyWorkAction.DEFER_UNKNOWN_STATE,
            SurveyUploadWork.SurveyWorkAction.DEFER_ENQUEUED_MISSING,
            SurveyUploadWork.SurveyWorkAction.DEFER_SUCCEEDED_WITHOUT_LEDGER,
            SurveyUploadWork.SurveyWorkAction.INVALID_ARTIFACT,
            SurveyUploadWork.SurveyWorkAction.DEFER_ARTIFACT_FAILURE,
            SurveyUploadWork.SurveyWorkAction.DEFER_TRACKER_FAILURE,
            SurveyUploadWork.SurveyWorkAction.DEFER_TRACKER_WRITE,
            SurveyUploadWork.SurveyWorkAction.DEFER_LEDGER_FAILURE,
            SurveyUploadWork.SurveyWorkAction.DEFER_BUILD_FAILURE,
            SurveyUploadWork.SurveyWorkAction.DEFER_ENQUEUE_FAILURE,
            SurveyUploadWork.SurveyWorkAction.DEFER_LEGACY_HISTORY
        )

        unsafeActions.forEach { action ->
            val operations = FakeOperations(directory).apply { reconciliationResult = reconciliation(action) }
            val result = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

            assertTrue("$action should fail", result is SurveyFinalizationResult.Failure)
            assertEquals(0, operations.deleteCalls.get())
            assertFalse(operations.events.contains("voice"))
            assertFalse(operations.events.contains("log"))
        }
    }

    @Test
    fun finalize_unconfirmedNewOrRecoveryWithoutSubmissionFails() = runBlocking {
        listOf(
            SurveyUploadWork.SurveyWorkAction.ENQUEUE_NEW,
            SurveyUploadWork.SurveyWorkAction.RECOVER_NEW,
            SurveyUploadWork.SurveyWorkAction.ENQUEUED_TRACKER_UNCONFIRMED
        ).forEach { action ->
            val operations = FakeOperations(directory).apply { reconciliationResult = reconciliation(action) }
            val result = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

            assertTrue(result is SurveyFinalizationResult.Failure)
            assertFalse(operations.events.contains("voice"))
            assertFalse(operations.events.contains("log"))
        }
    }

    @Test
    fun finalize_retryAfterUnsafeReconciliationReusesStagedFile() = runBlocking {
        val operations = FakeOperations(directory).apply {
            reconciliationResult = reconciliation(SurveyUploadWork.SurveyWorkAction.DEFER_ENQUEUE_FAILURE)
        }
        val finalizer = finalizer(operations)

        val first = finalizer.finalize(snapshot(), config(), tag(), STAMP)
        operations.reconciliationResult = reconciliation(SurveyUploadWork.SurveyWorkAction.ENQUEUE_NEW, enqueued = true)
        val second = finalizer.finalize(snapshot(), config(), tag(), STAMP)

        assertTrue(first is SurveyFinalizationResult.Failure)
        assertTrue(second is SurveyFinalizationResult.Queued)
        assertEquals(1, operations.stageCalls.get())
        assertEquals(2, operations.reconcileCalls.get())
    }

    @Test
    fun finalize_voiceAndLogFailuresRemainBestEffortAfterSafeReconciliation() = runBlocking {
        val operations = FakeOperations(directory).apply {
            reconciliationResult = reconciliation(SurveyUploadWork.SurveyWorkAction.KEEP_TRACKED)
            voiceFailure = IllegalStateException("voice")
            logFailure = IllegalStateException("log")
        }

        val result = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

        assertTrue(result is SurveyFinalizationResult.Queued)
        assertEquals(listOf("lookup", "stage", "reconcile", "voice", "log"), operations.events)
    }

    @Test
    fun finalize_concurrentFinishStagesOnceThenReusesPendingFile() = runBlocking {
        val operations = FakeOperations(directory).apply {
            reconciliationResult = reconciliation(SurveyUploadWork.SurveyWorkAction.KEEP_TRACKED)
            blockStage = true
        }
        val finalizer = finalizer(operations)
        val first = async(Dispatchers.Default) { finalizer.finalize(snapshot(), config(), tag(), STAMP) }
        assertTrue(operations.stageEntered.await(2, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            finalizer.finalize(snapshot(), config(), tag(), STAMP)
        }
        assertEquals(1, operations.pendingLookupCalls.get())
        assertEquals(1, operations.stageCalls.get())
        assertEquals(0, operations.reconcileCalls.get())
        operations.allowStage.countDown()

        assertTrue(first.await() is SurveyFinalizationResult.Queued)
        assertTrue(second.await() is SurveyFinalizationResult.Queued)
        assertEquals(1, operations.stageCalls.get())
        assertEquals(2, operations.reconcileCalls.get())
        assertEquals(1, directory.listFiles().orEmpty().count { it.extension == "json" })
    }

    private fun finalizer(operations: FakeOperations): SurveyUploadFinalizer =
        SurveyUploadFinalizer(operations, testOnly = true)

    private fun snapshot(surveyId: String = "survey-uuid") = SurveyFinalizationSnapshot(
        surveyId = surveyId,
        questions = mapOf("Q1" to "Question"),
        answers = mapOf("Q1" to "Answer"),
        followups = emptyMap(),
        audioRefs = emptyList(),
        aiOutcomesJson = "{}",
        extraMeta = emptyMap()
    )

    private fun config() = GitHubUploader.GitHubConfig("owner", "repo", "token")

    private fun tag() = DeviceUploadTag("Device_CODE")

    private class FakeOperations(private val directory: File) : SurveyFinalizationOperations {
        var uploaded = false
        var pendingFile: File? = null
        var reconciliationResult = reconciliation(SurveyUploadWork.SurveyWorkAction.ENQUEUE_NEW, enqueued = true)
        var stageFailure: Exception? = null
        var deleteFailure: Exception? = null
        var deleteResult = true
        var voiceFailure: Exception? = null
        var logFailure: Exception? = null
        var blockStage = false
        val stageEntered = CountDownLatch(1)
        val allowStage = CountDownLatch(1)
        val events = mutableListOf<String>()
        val pendingLookupCalls = AtomicInteger()
        val stageCalls = AtomicInteger()
        val reconcileCalls = AtomicInteger()
        val deleteCalls = AtomicInteger()
        var pendingLookupSurveyId: String? = null
        var reconciledFile: File? = null
        var reconciledSurveyId: String? = null
        var deletedFile: File? = null

        override fun isUploaded(surveyId: String): Boolean = uploaded

        override fun findPendingSurveyFile(surveyId: String): File? {
            events += "lookup"
            pendingLookupCalls.incrementAndGet()
            pendingLookupSurveyId = surveyId
            return pendingFile ?: directory.listFiles().orEmpty()
                .filter { it.extension == "json" && it.readText().contains(surveyId) }
                .sortedBy { it.name }
                .firstOrNull()
        }

        override fun stageSurveyJson(
            snapshot: SurveyFinalizationSnapshot,
            tag: DeviceUploadTag,
            stamp: String
        ): File {
            events += "stage"
            stageCalls.incrementAndGet()
            stageEntered.countDown()
            if (blockStage) check(allowStage.await(2, TimeUnit.SECONDS))
            stageFailure?.let { throw it }
            return File(directory, snapshot.surveyId + ".json").apply {
                check(!exists()) { "duplicate stage" }
                writeText(SurveyExportJsonBuilder.build(snapshot, stamp))
            }
        }

        override fun reconcileSurveyJson(
            config: GitHubUploader.GitHubConfig,
            file: File,
            surveyId: String
        ): SurveyUploadWork.SurveyWorkReconcileResult {
            events += "reconcile"
            reconcileCalls.incrementAndGet()
            reconciledFile = file
            reconciledSurveyId = surveyId
            return reconciliationResult
        }

        override fun deletePendingSurveyFile(file: File): Boolean {
            events += "delete"
            deleteCalls.incrementAndGet()
            deletedFile = file
            deleteFailure?.let { throw it }
            return deleteResult && file.delete()
        }

        override suspend fun scheduleVoiceArtifacts(
            config: GitHubUploader.GitHubConfig,
            surveyId: String,
            expectedVoiceFileNames: Set<String>
        ) {
            events += "voice"
            voiceFailure?.let { throw it }
        }

        override suspend fun scheduleLogArtifact(
            config: GitHubUploader.GitHubConfig,
            surveyId: String,
            exportedAtStamp: String
        ) {
            events += "log"
            logFailure?.let { throw it }
        }
    }

    private companion object {
        const val STAMP = "2026-09-22_11-37-48"

        fun reconciliation(
            action: SurveyUploadWork.SurveyWorkAction,
            enqueued: Boolean = false
        ) = SurveyUploadWork.SurveyWorkReconcileResult(
            normalizedSurveyId = "survey-uuid",
            logicalWorkName = "logical-work",
            legacyWorkName = "legacy-work",
            decision = SurveyUploadWork.SurveyWorkDecision(action, "$action reason"),
            enqueued = enqueued
        )
    }
}
