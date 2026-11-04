package com.negi.survey.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only fake transport and an isolated test directory; never calls a remote repository. */
@RunWith(AndroidJUnit4::class)
class UploadConflictLifecycleTest {
    private fun gitBlobSha(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-1")
        .digest("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII) + bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test fun gitBlobHashMatchesKnownVectorAndRejectsUnrelatedCapture() = runBlocking {
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", gitBlobSha("hello\n".toByteArray()))
        val failure = runCatching {
            GitHubUploader.createArtifactWithConflictRetry(
                gitBlobSha("capture A".toByteArray()), { gitBlobSha("capture B".toByteArray()) },
                { error("Unrelated remote capture must never be overwritten") }, {}
            )
        }.exceptionOrNull()
        assertTrue(failure is GitHubUploader.ArtifactConflictException)
    }

    @Test fun workerExhaustionRetainsWavAndRecoveryCannotLoop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File(context.filesDir, "issue128_test_${UUID.randomUUID()}.wav").apply { writeText("staged WAV") }
        val input = workDataOf(GitHubUploadWorker.KEY_FILE_PATH to wav.absolutePath,
            GitHubUploadWorker.KEY_FILE_NAME to "voice/${wav.name}", GitHubUploadWorker.KEY_TOKEN to "must-not-persist")
        val method = GitHubUploadWorker::class.java.getDeclaredMethod("retryOrFailure", Throwable::class.java, String::class.java)
            .apply { isAccessible = true }
        val store = UploadConflictRecoveryStore(context.filesDir)
        val key = UploadConflictRecoveryStore.fileKey(wav)
        try {
            for (attempt in 0..4) {
                val worker = TestListenableWorkerBuilder<GitHubUploadWorker>(context).setInputData(input).setRunAttemptCount(attempt).build()
                val result = method.invoke(worker, GitHubUploader.ConflictRetriesExhaustedException(), "conflict")
                if (attempt < 4) assertTrue(result is ListenableWorker.Result.Retry)
                else assertTrue(result is ListenableWorker.Result.Failure)
                assertEquals("staged WAV", wav.readText())
            }
            val retained = store.get(key)!!
            assertNull(retained.input.getString(GitHubUploadWorker.KEY_TOKEN))
            var recoveries = 0
            store.recoverOnce(retained, { false }) { recoveries++ }
            val recovery = androidx.work.Data.Builder().putAll(retained.input).putBoolean(UploadConflictRecoveryStore.RECOVERY, true).build()
            val worker = TestListenableWorkerBuilder<GitHubUploadWorker>(context).setInputData(recovery).setRunAttemptCount(4).build()
            assertTrue(method.invoke(worker, GitHubUploader.ConflictRetriesExhaustedException(), "conflict") is ListenableWorker.Result.Failure)
            repeat(5) { assertFalse(store.recoverOnce(retained, { false }) { recoveries++ }) }
            assertEquals(1, recoveries)
            assertEquals(2, store.get(key)!!.exhausted)
            assertTrue(wav.exists())
        } finally { wav.delete() }
    }

    @Test fun durableCaptureReconcilesLostSuccessWithoutRegeneration() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "issue128_capture_test_${UUID.randomUUID()}")
        try {
            val first = DiagnosticCaptureStore(directory).capture { File(it, "logcat.gz").writeText("capture A") }.single()
            val remote = gitBlobSha(first.readBytes())
            val restarted = DiagnosticCaptureStore(directory).capture { error("Must reuse capture A after restart") }.single()
            GitHubUploader.createArtifactWithConflictRetry(gitBlobSha(restarted.readBytes()), { remote }, { error("Must not PUT again") }, {})
            assertEquals("capture A", restarted.readText())
            DiagnosticCaptureStore.cleanupConfirmed(directory, false)
            assertTrue(restarted.exists())
            DiagnosticCaptureStore.cleanupConfirmed(directory, true)
            assertFalse(directory.exists())
        } finally { directory.deleteRecursively() }
    }
}
