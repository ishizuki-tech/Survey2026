package com.negi.survey.net

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiagnosticCaptureRetryTest {
    private fun directory() = Files.createTempDirectory("diagnostic-retry").toFile()

    // Test-only calculation, independent of production upload hashing and capture SHA-256.
    private fun gitBlobSha(bytes: ByteArray): String {
        val header = "blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII)
        return java.security.MessageDigest.getInstance("SHA-1").digest(header + bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @Test fun gitBlobHashMatchesIndependentKnownVectors() {
        // Literal Git vectors: no expected value is calculated with the implementation above.
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", gitBlobSha(byteArrayOf()))
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", gitBlobSha("hello\n".toByteArray()))
    }

    @Test fun rawFileDigestsCannotStandInForRemoteGitBlobIdentity() = runBlocking {
        val payload = "hello\n".toByteArray()
        val localBlobSha = gitBlobSha(payload)
        for (algorithm in listOf("SHA-1", "SHA-256")) {
            val rawDigest = java.security.MessageDigest.getInstance(algorithm).digest(payload)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            var writes = 0
            val failure = runCatching {
                GitHubUploader.createArtifactWithConflictRetry(localBlobSha, { rawDigest }, {
                    writes++; error("Incorrect digest must not authorize a write")
                }, {})
            }.exceptionOrNull()
            assertTrue(failure is GitHubUploader.ArtifactConflictException)
            assertEquals(0, writes)
        }
    }

    @Test fun ambiguousRemoteSuccessReusesIntendedCaptureAfterRestart() = runBlocking {
        val directory = directory()
        try {
            var captures = 0
            suspend fun stage() = DiagnosticCaptureStore(directory).capture {
                captures++
                DiagnosticCaptureStore.write(File(it, "logcat.gz"), "capture A".toByteArray())
            }.single()
            val intended = stage().readBytes()
            val remoteSha = gitBlobSha(stage().readBytes())
            // The source has changed; a new store instance represents a restarted process.
            val retry = DiagnosticCaptureStore(directory).capture {
                captures++
                File(it, "logcat.gz").writeText("capture B")
            }.single()
            var writes = 0
            GitHubUploader.createArtifactWithConflictRetry(gitBlobSha(retry.readBytes()), { remoteSha }, {
                writes++; error("Must reconcile earlier success")
            }, {})
            assertArrayEquals(intended, retry.readBytes())
            assertEquals(1, captures)
            assertEquals(0, writes)
        } finally { directory.deleteRecursively() }
    }

    @Test fun otherCaptureIsNeverOverwritten() = runBlocking {
        val directory = directory()
        try {
            val capture = DiagnosticCaptureStore(directory).capture { File(it, "runtime.log").writeText("A") }.single()
            var writes = 0
            val error = runCatching {
                GitHubUploader.createArtifactWithConflictRetry(gitBlobSha(capture.readBytes()), { gitBlobSha("unrelated capture".toByteArray()) }, {
                    writes++; error("overwrite")
                }, {})
            }.exceptionOrNull()
            assertTrue(error is GitHubUploader.ArtifactConflictException)
            assertEquals(0, writes)
            assertEquals("A", capture.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun incompleteCancelledPreparationCanRestartBeforeAnyUpload() = runBlocking {
        val directory = directory()
        try {
            runCatching { DiagnosticCaptureStore(directory).capture {
                File(it, "partial").writeText("partial")
                throw CancellationException("stop")
            } }
            val recovered = DiagnosticCaptureStore(directory).capture { File(it, "complete").writeText("complete") }
            assertEquals(listOf("complete"), recovered.map { it.name })
        } finally { directory.deleteRecursively() }
    }

    @Test fun committedMissingOrChangedCaptureFailsClosed() = runBlocking {
        for (delete in listOf(false, true)) {
            val directory = directory()
            try {
                val capture = DiagnosticCaptureStore(directory).capture { File(it, "segment.log").writeText("A") }.single()
                if (delete) capture.delete() else capture.writeText("B")
                var regenerated = false
                val error = runCatching { DiagnosticCaptureStore(directory).capture { regenerated = true } }.exceptionOrNull()
                assertTrue(error is java.io.IOException)
                assertFalse(regenerated)
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun cleanupWaitsForDurableWorkManagerSuccessAndHonorsRetention() = runBlocking {
        val directory = directory()
        DiagnosticCaptureStore(directory).capture { File(it, "log").writeText("A") }
        DiagnosticCaptureStore.cleanupConfirmed(directory, false)
        assertTrue(directory.exists())
        File(directory, ".retain").writeText("keep")
        DiagnosticCaptureStore.cleanupConfirmed(directory, true)
        assertTrue(directory.exists())
        File(directory, ".retain").delete()
        DiagnosticCaptureStore.cleanupConfirmed(directory, true)
        assertFalse(directory.exists())
    }
}
