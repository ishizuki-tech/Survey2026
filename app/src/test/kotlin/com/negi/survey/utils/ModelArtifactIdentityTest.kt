package com.negi.survey.utils

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal fun testIdentity(content: String) = ModelArtifactIdentity("test", "revision", "model.bin",
    content.toByteArray().size.toLong(), MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray()).joinToString("") { "%02x".format(it) })

class ModelArtifactIdentityTest {
    @get:Rule val directory = TemporaryFolder()
    private val file get() = File(directory.root, "model.bin")

    @Test fun officialIdentityIsPinnedAndUnknownUrlsFailClosed() {
        val identity = GemmaModelIdentity.trusted
        assertEquals(4919541760L, identity.size)
        assertEquals("297ed75955702dec3503e00c2c2ecbbf475300bc", identity.revision)
        assertEquals("2e67a6cd51dfe0f793431e6bd4ed8d029c88e10f52ca0469ad38445e3cd3c1f4", identity.sha256)
        assertEquals(identity, GemmaModelIdentity.resolve(identity.downloadUrl))
        assertTrue(runCatching { GemmaModelIdentity.resolve("https://example.invalid/model.bin") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun exactContentPassesWithoutModifyingFile() = runBlocking {
        file.writeText("valid")
        testIdentity("valid").validate(file)
        assertEquals("valid", file.readText())
    }

    @Test fun wrongTruncatedArbitraryAndSameSizeContentsFail() = runBlocking {
        for (content in listOf("", "v", "arbitrary", "wrong")) {
            file.writeText(content)
            assertTrue("accepted $content", runCatching { testIdentity("valid").validate(file) }.exceptionOrNull() is ModelIntegrityException)
            assertEquals(content, file.readText())
        }
    }

    @Test fun streamReadFailureRemainsAnIoFailure() = runBlocking {
        val failure = runCatching {
            testIdentity("valid").validateStream { object : ByteArrayInputStream(byteArrayOf()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("read failure")
            } }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(failure is ModelIntegrityException)
        assertEquals("read failure", failure?.message)
    }

    @Test fun cancellationBetweenHashChunksStopsReading() = runBlocking {
        var reads = 0
        val job = Job()
        val failure = runCatching {
            withContext(job) {
                testIdentity("valid").validateStream { object : ByteArrayInputStream("valid".toByteArray()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        reads++
                        job.cancel()
                        return super.read(b, off, len)
                    }
                } }
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, reads)
    }

    @Test fun supersessionDuringHashStopsOldOwner() = runBlocking {
        val lease = ModelArtifactOperations.begin(file)
        var reads = 0
        val failure = runCatching {
            withContext(lease) {
                testIdentity("valid").validateStream { object : ByteArrayInputStream("valid".toByteArray()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        reads++
                        ModelArtifactOperations.begin(file)
                        return super.read(b, off, len)
                    }
                } }
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, reads)
    }

    @Test fun invalidImportCannotPromoteOrPersistAndCleansStaging() = runBlocking {
        file.writeText("valid")
        val lease = ModelArtifactOperations.begin(file)
        var persisted = false
        val failure = runCatching {
            lease.import({ ByteArrayInputStream("wrong".toByteArray()) }, testIdentity("valid")::validate) { persisted = true }
        }.exceptionOrNull()
        assertTrue(failure is ModelIntegrityException)
        assertFalse(persisted)
        assertEquals("valid", file.readText())
        assertEquals(listOf(file), directory.root.listFiles()!!.toList())
    }

    @Test fun validImportPromotesAfterVerification() = runBlocking {
        file.writeText("older")
        val lease = ModelArtifactOperations.begin(file)
        lease.import({ ByteArrayInputStream("valid".toByteArray()) }, testIdentity("valid")::validate) {
            assertEquals("valid", it.readText())
        }
        assertEquals("valid", file.readText())
    }

    @Test fun cancellationAtValidationBoundaryCannotPromote() = runBlocking {
        file.writeText("older")
        val lease = ModelArtifactOperations.begin(file)
        val failure = runCatching {
            lease.import({ ByteArrayInputStream("valid".toByteArray()) }, {
                testIdentity("valid").validate(it)
                lease.cancel()
            }) { fail("Canceled candidate installed") }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("older", file.readText())
        assertEquals(1, directory.root.listFiles()!!.size)
    }
    @Test fun cancellationInsideStagingHashPreservesInstalledModel() = runBlocking {
        file.writeText("older")
        val lease = ModelArtifactOperations.begin(file)
        val failure = runCatching {
            lease.import({ ByteArrayInputStream("valid".toByteArray()) }, { staging ->
                val hashingJob = currentCoroutineContext()[Job]!!
                // Cancel from the first real staging-file read, before digest completion.
                testIdentity("valid").validateStream {
                    object : java.io.FilterInputStream(staging.inputStream()) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val n = super.read(b, off, len)
                            hashingJob.cancel()
                            return n
                        }
                    }
                }
            }) { fail("Canceled hash installed") }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("older", file.readText())
        assertEquals(1, directory.root.listFiles()!!.size)
    }

}
