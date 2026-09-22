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
    fun finalize_stagesQueuesThenSchedulesArtifactsInOrder() = runBlocking {
        val operations = FakeOperations(directory)
        val result = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

        assertTrue(result is SurveyFinalizationResult.Queued)
        assertEquals(1, operations.stageCalls.get())
        assertEquals(1, operations.enqueueCalls.get())
        assertEquals(listOf("stage", "enqueue", "voice", "log"), operations.events)
        assertEquals("survey-uuid", operations.enqueuedSurveyId)
        assertEquals("exports/survey-uuid.json", SurveyUploadWork.remoteRelativePath(operations.enqueuedFile!!.name))
    }

    @Test
    fun finalize_stageFailureDoesNotScheduleArtifacts() = runBlocking {
        val operations = FakeOperations(directory).apply { stageFailure = IllegalStateException("disk full") }
        val result = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

        assertTrue(result is SurveyFinalizationResult.Failure)
        assertEquals(0, operations.enqueueCalls.get())
        assertFalse(operations.events.contains("voice"))
        assertFalse(operations.events.contains("log"))
    }

    @Test
    fun finalize_retryAfterEnqueueFailureCanSucceedWithoutSecondStage() = runBlocking {
        val operations = FakeOperations(directory).apply { enqueueFailureCount = 1 }
        val first = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)
        val second = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

        assertTrue(first is SurveyFinalizationResult.Failure)
        assertTrue(second is SurveyFinalizationResult.Queued)
        assertEquals(1, operations.stageCalls.get())
        assertEquals(2, operations.enqueueCalls.get())
        assertEquals(1, directory.listFiles().orEmpty().count { it.extension == "json" })
    }

    @Test
    fun finalize_reusesExistingPendingFileForSequentialFinish() = runBlocking {
        val existing = File(directory, "existing-survey.json").apply {
            writeText("""{"survey_id":"survey-uuid"}""")
        }
        val operations = FakeOperations(directory).apply { pendingFile = existing }
        val first = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)
        val second = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

        assertEquals(SurveyFinalizationResult.Queued(existing, reused = true), first)
        assertEquals(SurveyFinalizationResult.Queued(existing, reused = true), second)
        assertEquals(0, operations.stageCalls.get())
        assertEquals(2, operations.enqueueCalls.get())
        assertEquals(existing.name, operations.enqueuedFile!!.name)
    }

    @Test
    fun finalize_concurrentFinishStagesOnlyOnePhysicalSurveyJson() = runBlocking {
        val operations = FakeOperations(directory).apply { blockStage = true }
        val finalizer = finalizer(operations)
        val first = async(Dispatchers.Default) { finalizer.finalize(snapshot(), config(), tag(), STAMP) }
        assertTrue(operations.stageEntered.await(2, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) { finalizer.finalize(snapshot(), config(), tag(), STAMP) }
        operations.allowStage.countDown()

        assertTrue(first.await() is SurveyFinalizationResult.Queued)
        assertTrue(second.await() is SurveyFinalizationResult.Queued)
        assertEquals(1, operations.stageCalls.get())
        assertEquals(1, directory.listFiles().orEmpty().count { it.extension == "json" })
    }

    @Test
    fun finalize_alreadyUploadedDoesNotStageEnqueueOrScheduleArtifacts() = runBlocking {
        val operations = FakeOperations(directory).apply { uploaded = true }
        val result = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

        assertEquals(SurveyFinalizationResult.AlreadyUploaded, result)
        assertEquals(0, operations.stageCalls.get())
        assertEquals(0, operations.enqueueCalls.get())
        assertTrue(operations.events.isEmpty())
    }

    @Test
    fun finalize_historicalDuplicatePendingFilesUsesDeterministicSelectedFile() = runBlocking {
        val first = File(directory, "a-old.json").apply { writeText("""{"survey_id":"survey-uuid"}""") }
        File(directory, "z-new.json").apply { writeText("""{"survey_id":"survey-uuid"}""") }
        val operations = FakeOperations(directory).apply { pendingFile = first }
        val result = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

        assertEquals(SurveyFinalizationResult.Queued(first, reused = true), result)
        assertEquals(0, operations.stageCalls.get())
        assertEquals(2, directory.listFiles().orEmpty().count { it.extension == "json" })
        assertEquals("a-old.json", operations.enqueuedFile!!.name)
    }

    @Test
    fun finalize_voiceAndLogFailuresRemainBestEffort() = runBlocking {
        val operations = FakeOperations(directory).apply {
            voiceFailure = IllegalStateException("voice")
            logFailure = IllegalStateException("log")
        }
        val result = finalizer(operations).finalize(snapshot(), config(), tag(), STAMP)

        assertTrue(result is SurveyFinalizationResult.Queued)
        assertEquals(1, operations.stageCalls.get())
        assertEquals(1, operations.enqueueCalls.get())
        assertEquals(listOf("stage", "enqueue", "voice", "log"), operations.events)
    }

    private fun finalizer(operations: FakeOperations): SurveyUploadFinalizer =
        SurveyUploadFinalizer(operations, testOnly = true)

    private fun snapshot() = SurveyFinalizationSnapshot(
        surveyId = "survey-uuid",
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
        var stageFailure: Throwable? = null
        var enqueueFailureCount = 0
        var voiceFailure: Throwable? = null
        var logFailure: Throwable? = null
        var blockStage = false
        val stageEntered = CountDownLatch(1)
        val allowStage = CountDownLatch(1)
        val events = mutableListOf<String>()
        val stageCalls = AtomicInteger()
        val enqueueCalls = AtomicInteger()
        var enqueuedFile: File? = null
        var enqueuedSurveyId: String? = null

        override fun isUploaded(surveyId: String): Boolean = uploaded

        override fun findPendingSurveyFile(surveyId: String): File? =
            pendingFile ?: directory.listFiles().orEmpty()
                .filter { it.extension == "json" && it.readText().contains(surveyId) }
                .sortedBy { it.name }
                .firstOrNull()

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

        override fun enqueueSurveyJson(config: GitHubUploader.GitHubConfig, file: File, surveyId: String) {
            events += "enqueue"
            enqueueCalls.incrementAndGet()
            enqueuedFile = file
            enqueuedSurveyId = surveyId
            if (enqueueFailureCount > 0) {
                enqueueFailureCount--
                throw IllegalStateException("enqueue failed")
            }
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
    }
}
