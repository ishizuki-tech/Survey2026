package com.negi.survey.net

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUploaderSurveyIdempotencyTest {
    private val payload = """{"survey_id":"survey-uuid","exported_at":"2026-10-30"}"""
    private val identity = requireNotNull(SurveyUploadIdempotency.validateLocalPayload("survey-uuid", payload))

    @Test
    fun matchingRemote_isRecoveredWithoutCreate() = runBlocking {
        val transport = FakeTransport(listOf(record(payload)))

        GitHubUploader.reconcileSurveyJsonUpload(identity, payload, transport)

        assertEquals(0, transport.createCalls)
    }

    @Test
    fun conflictOr422_matchingReadBack_isRecovered() = runBlocking {
        listOf(409, 422).forEach { status ->
            val transport = FakeTransport(
                gets = listOf(null, record(payload)),
                create = GitHubUploader.SurveyCreateResult.Reconcile(status)
            )
            GitHubUploader.reconcileSurveyJsonUpload(identity, payload, transport)
            assertEquals(1, transport.createCalls)
        }
    }

    @Test
    fun differentRemote_neverOverwrites() = runBlocking {
        val transport = FakeTransport(listOf(record("""{"survey_id":"survey-uuid","answer":"other"}""")))
        val error = runCatching { GitHubUploader.reconcileSurveyJsonUpload(identity, payload, transport) }.exceptionOrNull()

        assertTrue(error is GitHubUploader.SurveyUploadValidationException)
        assertEquals(0, transport.createCalls)
    }

    @Test
    fun ambiguousCreateWithoutReadBack_isRetryable() = runBlocking {
        val transport = FakeTransport(
            gets = listOf(null, null),
            create = GitHubUploader.SurveyCreateResult.Reconcile(null)
        )
        val error = runCatching { GitHubUploader.reconcileSurveyJsonUpload(identity, payload, transport) }.exceptionOrNull()

        assertTrue(error is GitHubUploader.SurveyUploadAmbiguousException)
    }

    @Test
    fun rejected422WithoutMatchingTarget_isValidationFailure() = runBlocking {
        val transport = FakeTransport(
            gets = listOf(null, null),
            create = GitHubUploader.SurveyCreateResult.Reconcile(422)
        )
        val error = runCatching { GitHubUploader.reconcileSurveyJsonUpload(identity, payload, transport) }.exceptionOrNull()

        assertTrue(error is GitHubUploader.SurveyUploadValidationException)
    }

    @Test
    fun canonicalPath_isStableAcrossRetries() {
        assertEquals(
            SurveyUploadWork.canonicalRemotePath("survey-uuid"),
            SurveyUploadWork.canonicalRemotePath(" Survey-UUID ")
        )
    }

    private fun record(content: String) = GitHubUploader.SurveyRemoteRecord(content, "url")

    private class FakeTransport(
        private val gets: List<GitHubUploader.SurveyRemoteRecord?>,
        private val create: GitHubUploader.SurveyCreateResult? = null
    ) : GitHubUploader.SurveyContentsTransport {
        var getCalls = 0
        var createCalls = 0

        override suspend fun getExisting(): GitHubUploader.SurveyRemoteRecord? = gets.getOrNull(getCalls++)

        override suspend fun createOnly(): GitHubUploader.SurveyCreateResult {
            createCalls++
            return create ?: throw IOException("unexpected create")
        }
    }
}
