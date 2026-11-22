package com.negi.survey.net

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.negi.survey.utils.ModelArtifactIdentity
import com.negi.survey.utils.HeavyInitializer
import android.os.StatFs
import com.negi.survey.utils.ModelArtifactOperations
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.*
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Small loopback transfers exercise the real downloader's part/resume/validation/promotion path. */
class ModelArtifactDownloadTest {
    @get:Rule val directory = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var url: String
    private val destination get() = File(directory.root, "model.bin")
    @Volatile private var body = "BBBBBBBB".toByteArray()
    @Volatile private var truncate = false
    @Volatile private var onGet: (HttpExchange) -> Unit = {}
    private val ranges = mutableListOf<String?>()

    @Before fun setup() {
        mockkConstructor(StatFs::class)
        every { anyConstructed<StatFs>().availableBytes } returns Long.MAX_VALUE
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model.bin") { exchange ->
            try {
                exchange.responseHeaders.add("Accept-Ranges", "bytes")
                exchange.responseHeaders.add("ETag", "\"test-version\"")
                if (exchange.requestMethod == "HEAD") {
                    exchange.responseHeaders.add("Content-Length", body.size.toString())
                    exchange.sendResponseHeaders(200, -1)
                } else {
                    val range = exchange.requestHeaders.getFirst("Range")
                    synchronized(ranges) { ranges += range }
                    val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toInt() ?: 0
                    val requestedEnd = range?.substringAfter('-')?.takeIf { it.isNotBlank() }?.toInt() ?: body.lastIndex
                    if (start >= body.size) {
                        exchange.responseHeaders.add("Content-Range", "bytes */${body.size}")
                        exchange.sendResponseHeaders(416, -1)
                        return@createContext
                    }
                    if (range != null) {
                        exchange.responseHeaders.add("Content-Range", "bytes $start-$requestedEnd/${body.size}")
                    }
                    val fullEnd = requestedEnd + 1
                    exchange.sendResponseHeaders(if (range == null) 200 else 206, (fullEnd - start).toLong())
                    onGet(exchange)
                    val end = if (truncate) minOf(start + 3, fullEnd - 1) else fullEnd
                    exchange.responseBody.write(body, start, end - start)
                    exchange.responseBody.flush()
                }
            } finally {
                exchange.close()
            }
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}/model.bin"
    }

    @After fun teardown() {
        server.stop(0)
        unmockkAll()
    }

    private fun identity(bytes: ByteArray = body) = ModelArtifactIdentity("test", "test", "model.bin", bytes.size.toLong(),
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    private suspend fun download(hash: String? = null, reuse: Boolean = false, trusted: ModelArtifactIdentity = identity(), parallel: Boolean = false, overlap: Int = 0, retries: Int = 1) {
        HttpUrlFileDownloader(hfTokenProvider = { null }, debugLogs = false, parallelMinFileBytes = if (parallel) 1 else Long.MAX_VALUE).downloadToFile(
            url = url, dst = destination, expectedSha256 = hash, reuseExisting = reuse,
            identity = trusted, maxRetries = retries, resumeOverlapBytes = overlap, stallTimeoutMs = 2000
        )
    }

    @Test fun downloadNewSupersedesBlockedImportWithoutSharedStaging() = runBlocking {
        val old = ModelArtifactOperations.begin(destination)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var oldPublished = false
        val oldJob = launch(Dispatchers.IO) {
            old.import(validate = { identity("AX".toByteArray()).validate(it) }, open = {
                object : ByteArrayInputStream("AX".toByteArray()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        entered.complete(Unit)
                        check(release.await(10, TimeUnit.SECONDS))
                        return super.read(b, off, len)
                    }
                }
            }) { oldPublished = true }
        }
        old.attach(oldJob)
        try {
            withTimeout(5000) { entered.await() }
            download()
            assertEquals("BBBBBBBB", destination.readText())
        } finally {
            release.countDown()
            withTimeout(5000) { oldJob.join() }
        }
        assertFalse(oldPublished)
        assertEquals("BBBBBBBB", destination.readText())
    }

    @Test fun importSupersedesDownloadWhileNetworkIsBlocked() = runBlocking {
        destination.writeText("valid")
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        onGet = { entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)) }
        val oldDownload = async(Dispatchers.IO) { runCatching { download() } }
        try {
            withTimeout(5000) { entered.await() }
            val replacement = ModelArtifactOperations.begin(destination)
            val imported = async { replacement.import(validate = { identity("new".toByteArray()).validate(it) }, open = { ByteArrayInputStream("new".toByteArray()) }) {} }
            release.countDown()
            val failure = withTimeout(5000) { oldDownload.await() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            withTimeout(5000) { imported.await() }
        } finally {
            release.countDown()
        }
        assertEquals("new", destination.readText())
    }

    @Test fun failedTransferPreservesInstalledModelAndResumesPartOnRetry() = runBlocking {
        destination.writeText("valid")
        truncate = true
        val failure = runCatching { download() }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("valid", destination.readText())
        val part = File(directory.root, "model.bin.part")
        assertTrue("Expected resumable bytes; failure=$failure; requests=$ranges", part.length() > 0L)
        truncate = false
        download()
        assertEquals("BBBBBBBB", destination.readText())
        assertTrue(synchronized(ranges) { ranges.any { it != null } })
        assertFalse(part.exists())
    }

    @Test fun invalidHashIsRejectedBeforeReplacingInstalledModel() = runBlocking {
        destination.writeText("valid")
        assertTrue(runCatching { download(hash = "00".repeat(32)) }.isFailure)
        assertEquals("valid", destination.readText())
        assertFalse(File(directory.root, "model.bin.part").exists())
        assertFalse(File(directory.root, "model.bin.part.meta").exists())
    }

    @Test fun heavyInitializerKeepsDestinationSingleFlightAndForcedRefresh() = runBlocking {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns directory.root
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000L }
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        onGet = { entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)) }
        suspend fun ensure() = HeavyInitializer.ensureInitialized(
            context, url, fileName = "model.bin", timeoutMs = 5000,
            forceFresh = true, identity = identity(), onProgress = { _, _ -> }
        )
        destination.writeText("AAAAAAAA")
        val owner = async(Dispatchers.IO) { ensure() }
        try {
            withTimeout(5000) { entered.await() }
            // UNDISPATCHED reaches the existing-flight await before the owner is released.
            val waiter = async(start = CoroutineStart.UNDISPATCHED) { ensure() }
            assertFalse(waiter.isCompleted)
            release.countDown()
            assertTrue(withTimeout(5000) { owner.await() }.isSuccess)
            assertTrue(withTimeout(5000) { waiter.await() }.isSuccess)
        } finally {
            release.countDown()
        }
        assertEquals("BBBBBBBB", destination.readText())
        assertEquals(1, synchronized(ranges) { ranges.size })
        assertFalse(HeavyInitializer.isInFlight())
    }

    @Test fun freshReplacementDoesNotReuseSameSizedInstalledFile() = runBlocking {
        destination.writeText("AAAAAAAA")
        download()
        assertEquals("BBBBBBBB", destination.readText())
    }
    @Test fun incorrectTrustedSizeAndSameSizeHashPreserveInstalledBytes() = runBlocking {
        destination.writeText("previous")
        for (trusted in listOf(identity("short".toByteArray()), identity("XXXXXXXX".toByteArray()))) {
            assertTrue(runCatching { download(trusted = trusted) }.isFailure)
            assertEquals("previous", destination.readText())
        }
    }

    @Test fun sameSizeExistingFileCannotBypassTrustedValidation() = runBlocking {
        destination.writeText("XXXXXXXX")
        download(reuse = true)
        assertEquals(String(body), destination.readText())
        assertTrue(ranges.isNotEmpty())
    }

    @Test fun completeResumedPartUses416AndTrustedValidation() = runBlocking {
        destination.writeText("previous")
        truncate = true
        assertTrue(runCatching { download() }.isFailure)
        truncate = false
        File(directory.root, "model.bin.part").writeBytes(body)
        download()
        assertEquals(String(body), destination.readText())
        assertTrue(synchronized(ranges) { ranges.contains("bytes=8-") })
    }

    @Test fun wrongCompleteResumedPartCannotPromoteVia416() = runBlocking {
        destination.writeText("previous")
        truncate = true
        assertTrue(runCatching { download() }.isFailure)
        truncate = false
        File(directory.root, "model.bin.part").writeText("XXXXXXXX")
        assertTrue(runCatching { download() }.isFailure)
        assertEquals("previous", destination.readText())
        assertTrue(synchronized(ranges) { ranges.contains("bytes=8-") })
        assertFalse(File(directory.root, "model.bin.part").exists())
        assertFalse(File(directory.root, "model.bin.part.meta").exists())
        download()
        assertEquals(String(body), destination.readText())
    }

    @Test fun parallelCompletionVerifiesMergedBytes() = runBlocking {
        download(parallel = true)
        assertEquals(String(body), destination.readText())
        assertEquals(4, synchronized(ranges) { ranges.count { it != null } })
    }

    @Test fun invalidParallelCompletionPreservesPreviousInstallation() = runBlocking {
        destination.writeText("previous")
        assertTrue(runCatching { download(parallel = true, trusted = identity("XXXXXXXX".toByteArray())) }.isFailure)
        assertEquals("previous", destination.readText())
    }

    @Test fun heavyInitializerRejectsUnverifiedNonemptyLocalFile() = runBlocking {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns directory.root
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        destination.writeText("XXXXXXXX")
        val result = HeavyInitializer.ensureInitialized(context, url, fileName = "model.bin", timeoutMs = 5000,
            forceFresh = false, identity = identity(), onProgress = { _, _ -> })
        assertTrue(result.isSuccess)
        assertEquals(String(body), destination.readText())
        assertTrue(ranges.isNotEmpty())
    }

    private suspend fun seedPoisonedPrefix() {
        body = ByteArray(128 * 1024) { 'B'.code.toByte() }
        truncate = true
        assertTrue(runCatching { download() }.isFailure)
        truncate = false
        val poisoned = body.copyOf().also { it[0] = 'X'.code.toByte() }
        File(directory.root, "model.bin.part").writeBytes(poisoned)
        assertTrue(File(directory.root, "model.bin.part.meta").isFile)
    }

    @Test fun poisonedPrefixIsDiscardedAndNormalRetryStartsWithCleanBytes() = runBlocking {
        destination.writeText("previous")
        seedPoisonedPrefix()
        assertTrue(runCatching { download(overlap = 64 * 1024) }.isFailure)
        assertEquals("previous", destination.readText())
        assertFalse(File(directory.root, "model.bin.part").exists())
        assertFalse(File(directory.root, "model.bin.part.meta").exists())
        download(overlap = 64 * 1024)
        assertArrayEquals(body, destination.readBytes())
        identity().validate(destination)
    }

    @Test fun automaticRetryRecoversFromCorruptionBeyondProductionOverlap() = runBlocking {
        destination.writeText("previous")
        seedPoisonedPrefix()
        synchronized(ranges) { ranges.clear() }
        download(overlap = 64 * 1024, retries = 2)
        assertArrayEquals(body, destination.readBytes())
        assertEquals(listOf("bytes=65536-", null), synchronized(ranges) { ranges.toList() })
    }

    @Test fun supersededInvalidDownloadCannotDeleteNewOwnersResumeState() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        onGet = { entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)) }
        val old = async(Dispatchers.IO) { runCatching { download(trusted = identity("XXXXXXXX".toByteArray())) } }
        try {
            withTimeout(5000) { entered.await() }
            val replacement = ModelArtifactOperations.begin(destination)
            val newWriter = async {
                replacement.locked {
                    File(directory.root, "model.bin.part").writeText("new-owner")
                    File(directory.root, "model.bin.part.meta").writeText("new-metadata")
                }
            }
            release.countDown()
            assertTrue(withTimeout(5000) { old.await() }.exceptionOrNull() is CancellationException)
            withTimeout(5000) { newWriter.await() }
            assertEquals("new-owner", File(directory.root, "model.bin.part").readText())
            assertEquals("new-metadata", File(directory.root, "model.bin.part.meta").readText())
        } finally { release.countDown() }
    }

    private fun heavyContext(): Context {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns directory.root
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000L }
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        return context
    }

    @Test fun invalidLegacyTempNeverReachesFinalWhenNetworkFails() = runBlocking {
        val context = heavyContext()
        File(directory.root, "model.bin.tmp").writeText("XXXXXXXX")
        mockkConstructor(HttpUrlFileDownloader::class)
        coEvery { anyConstructed<HttpUrlFileDownloader>().downloadToFile(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws java.io.IOException("offline")
        val result = HeavyInitializer.ensureInitialized(context, url, fileName = "model.bin", timeoutMs = 5000,
            forceFresh = false, identity = identity(), onProgress = { _, _ -> })
        assertTrue(result.isFailure)
        assertFalse(destination.exists())
    }

    @Test fun validLegacyTempMigratesWithoutNetworkAndExistingFinalIsProtected() = runBlocking {
        val context = heavyContext()
        val legacy = File(directory.root, "model.bin.tmp").apply { writeBytes(body) }
        suspend fun ensure() = HeavyInitializer.ensureInitialized(context, url, fileName = "model.bin", timeoutMs = 5000,
            forceFresh = false, identity = identity(), onProgress = { _, _ -> })
        assertTrue(ensure().isSuccess)
        assertFalse(legacy.exists())
        assertArrayEquals(body, destination.readBytes())
        legacy.writeText("bad legacy")
        assertTrue(ensure().isSuccess)
        assertArrayEquals(body, destination.readBytes())
        assertEquals("bad legacy", legacy.readText())
        assertTrue(ranges.isEmpty())
    }

    @Test fun cancellationWhileHashingLegacyTempPreventsPromotion() = runBlocking {
        heavyContext()
        val legacy = File(directory.root, "model.bin.tmp").apply { writeBytes(body) }
        val lease = ModelArtifactOperations.begin(destination)
        val hashingJob = Job()
        lease.attach(hashingJob)
        val failure = runCatching {
            withContext(hashingJob + lease + identity()) {
                lease.locked {
                    HeavyInitializer.migrateLegacyTransferState(destination) { candidate ->
                        identity().validateStream {
                            object : java.io.FilterInputStream(candidate.inputStream()) {
                                override fun read(b: ByteArray, off: Int, len: Int): Int {
                                    val n = super.read(b, off, len)
                                    hashingJob.cancel()
                                    return n
                                }
                            }
                        }
                    }
                }
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(destination.exists())
        assertArrayEquals(body, legacy.readBytes())
    }

}
