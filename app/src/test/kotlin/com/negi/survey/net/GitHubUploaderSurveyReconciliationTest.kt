package com.negi.survey.net

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUploaderSurveyReconciliationTest {
    private val payload = """{"survey_id":"survey-uuid","answer":1}"""
    private val identity = requireNotNull(SurveyUploadIdempotency.validateLocalPayload("survey-uuid", payload))

    @Test
    fun absentRemote_createsOnceWithoutUpdateSha() = runBlocking {
        val transport = FakeTransport(
            getResponses = listOf(null),
            createResult = GitHubUploader.SurveyCreateResult.Created(GitHubUploader.UploadResult("url", "commit"))
        )

        val result = GitHubUploader.reconcileSurveyJsonUpload(identity, payload, transport)

        assertEquals("commit", result.commitSha)
        assertEquals(1, transport.getCalls)
        assertEquals(1, transport.createCalls)
    }

    @Test
    fun remoteSuccessWithMissingLedger_recoveryRecognizesMatchingRecordWithoutCreate() = runBlocking {
        val transport = FakeTransport(getResponses = listOf(record(payload)))

        val result = GitHubUploader.reconcileSurveyJsonUpload(identity, payload, transport)

        assertEquals("existing-url", result.fileUrl)
        assertEquals(1, transport.getCalls)
        assertEquals(0, transport.createCalls)
    }

    @Test
    fun concurrentCreateConflict_readsBackMatchingRecordWithoutOverwrite() = runBlocking {
        val transport = FakeTransport(
            getResponses = listOf(null, record(payload)),
            createResult = GitHubUploader.SurveyCreateResult.Conflict
        )

        GitHubUploader.reconcileSurveyJsonUpload(identity, payload, transport)

        assertEquals(2, transport.getCalls)
        assertEquals(1, transport.createCalls)
    }

    @Test
    fun ambiguousCreateFailure_isNotSuccess_thenRetryCanReconcileExistingRecord() = runBlocking {
        val ambiguous = FakeTransport(getResponses = listOf(null), createFailure = IOException("socket closed"))

        val failure = runCatching {
            GitHubUploader.reconcileSurveyJsonUpload(identity, payload, ambiguous)
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(1, ambiguous.createCalls)

        val retry = FakeTransport(getResponses = listOf(record(payload)))
        assertNotNull(GitHubUploader.reconcileSurveyJsonUpload(identity, payload, retry))
        assertEquals(0, retry.createCalls)
    }

    @Test
    fun conflictingRemoteAndFailedGet_neverCreateOrConfirmSuccess() = runBlocking {
        val conflicting = FakeTransport(getResponses = listOf(record("""{"survey_id":"survey-uuid","answer":2}""")))
        val conflictFailure = runCatching {
            GitHubUploader.reconcileSurveyJsonUpload(identity, payload, conflicting)
        }.exceptionOrNull()
        assertTrue(conflictFailure is IOException)
        assertEquals(0, conflicting.createCalls)

        val getFailure = FakeTransport(getFailure = IOException("GET timeout"))
        val getError = runCatching {
            GitHubUploader.reconcileSurveyJsonUpload(identity, payload, getFailure)
        }.exceptionOrNull()
        assertTrue(getError is IOException)
        assertEquals(0, getFailure.createCalls)
    }

    @Test
    fun failedLedgerPersistence_doesNotDeleteStagedArtifact() {
        var deleteCalls = 0
        val failure = runCatching {
            SurveyUploadIdempotency.completeLocalSurveySuccess(
                surveyId = "survey-uuid",
                markUploaded = { throw IOException("ledger write failed") },
                deleteStagedFile = { deleteCalls++; true }
            )
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(0, deleteCalls)
    }

    private fun record(content: String) = GitHubUploader.SurveyRemoteRecord(content, "existing-url")

    private class FakeTransport(
        private val getResponses: List<GitHubUploader.SurveyRemoteRecord?> = emptyList(),
        private val createResult: GitHubUploader.SurveyCreateResult? = null,
        private val getFailure: IOException? = null,
        private val createFailure: IOException? = null
    ) : GitHubUploader.SurveyContentsTransport {
        var getCalls = 0
        var createCalls = 0

        override suspend fun getExisting(): GitHubUploader.SurveyRemoteRecord? {
            getFailure?.let { throw it }
            return getResponses.getOrNull(getCalls++)
        }

        override suspend fun createOnly(): GitHubUploader.SurveyCreateResult {
            createCalls++
            createFailure?.let { throw it }
            return requireNotNull(createResult) { "Unexpected create." }
        }
    }
}
