package com.negi.survey.net

import androidx.work.Data
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class UploadConflictRecoveryStoreTest {
    private fun gitBlobSha(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-1")
        .digest("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII) + bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test fun exhaustedWavSurvivesRestartAndRecoversOnceWithOriginalRoute() {
        val directory = Files.createTempDirectory("conflict-recovery").toFile()
        try {
            val wav = File(directory, "pending_uploads_shared/voice/survey/a.wav").apply { parentFile!!.mkdirs(); writeText("wav bytes") }
            val input = Data.Builder().putString(GitHubUploadWorker.KEY_FILE_PATH, wav.absolutePath)
                .putString(GitHubUploadWorker.KEY_FILE_NAME, "voice/a.wav")
                .putString(GitHubUploadWorker.KEY_OWNER, "owner").putString(GitHubUploadWorker.KEY_REPO, "repo")
                .putString(UploadConflictRecoveryStore.DATE, "2026-11-03").build()
            val key = UploadConflictRecoveryStore.fileKey(wav)
            UploadConflictRecoveryStore(directory).exhaust(key, input)
            val restarted = UploadConflictRecoveryStore(directory)
            val record = restarted.get(key)!!
            var writes = 0
            var remoteBytes: ByteArray? = null
            assertTrue(restarted.recoverOnce(record, { false }) {
                assertEquals("voice/a.wav", it.input.getString(GitHubUploadWorker.KEY_FILE_NAME))
                assertEquals("2026-11-03", it.input.getString(UploadConflictRecoveryStore.DATE))
                assertEquals("wav bytes", wav.readText())
                kotlinx.coroutines.runBlocking {
                    GitHubUploader.createArtifactWithConflictRetry(
                        gitBlobSha(wav.readBytes()), { null }, {
                            writes++
                            remoteBytes = wav.readBytes()
                            GitHubUploader.UploadResult("fake://voice/a.wav", "commit")
                        }, {}
                    )
                }
                wav.delete()
            })
            assertEquals(1, writes)
            assertArrayEquals("wav bytes".toByteArray(), remoteBytes)
            assertFalse(restarted.recoverOnce(record, { false }) { writes++ })
            assertEquals(1, writes)
        } finally { directory.deleteRecursively() }
    }

    @Test fun secondExhaustionAndPermanentCollisionNeverReschedule() {
        val directory = Files.createTempDirectory("conflict-bound").toFile()
        try {
            val store = UploadConflictRecoveryStore(directory)
            val input = Data.Builder().build()
            val first = store.exhaust("file:wav", input)
            val second = store.exhaust(first.key, Data.Builder().putBoolean(UploadConflictRecoveryStore.RECOVERY, true).build())
            assertEquals(2, second.exhausted)
            store.exhaust(first.key, input) // Late first-batch failure cannot reopen the budget.
            repeat(10) { assertFalse(store.recoverOnce(first, { false }) { error("unbounded enqueue") }) }
            assertEquals(2, store.get(first.key)!!.exhausted)
            val collision = store.exhaust("file:collision", input, permanent = true)
            assertFalse(store.recoverOnce(collision, { false }) { error("collision enqueue") })
        } finally { directory.deleteRecursively() }
    }

    @Test fun enqueueAcknowledgementLossUsesSameRequestIdWithoutNewWork() {
        val directory = Files.createTempDirectory("conflict-ack").toFile()
        try {
            val store = UploadConflictRecoveryStore(directory)
            val record = store.exhaust("capture:id", Data.EMPTY)
            var durableId: java.util.UUID? = null
            runCatching { store.recoverOnce(record, { false }) { durableId = it.recoveryId; throw java.io.IOException("lost ack") } }
            val restarted = UploadConflictRecoveryStore(directory)
            assertTrue(restarted.recoverOnce(record, { it == durableId }) { error("duplicate work") })
            assertTrue(restarted.get(record.key)!!.dispatched)
        } finally { directory.deleteRecursively() }
    }

    @Test fun surveyRecoveryCannotBypassSharedReconciliation() {
        val directory = Files.createTempDirectory("conflict-survey").toFile()
        try {
            val store = UploadConflictRecoveryStore(directory)
            val record = store.exhaust(UploadConflictRecoveryStore.surveyKey(" SURVEY "), Data.EMPTY)
            assertEquals("survey:survey", record.key)
            assertFalse(store.recoverOnce(record, { false }) { error("second survey enqueue path") })
        } finally { directory.deleteRecursively() }
    }
}
