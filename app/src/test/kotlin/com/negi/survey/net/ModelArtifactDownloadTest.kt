package com.negi.survey.net

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
                    if (range != null) {
                        exchange.responseHeaders.add("Content-Range", "bytes $start-${body.lastIndex}/${body.size}")
                    }
                    exchange.sendResponseHeaders(if (range == null) 200 else 206, (body.size - start).toLong())
                    onGet(exchange)
                    val end = if (truncate) minOf(start + 3, body.size - 1) else body.size
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

    private suspend fun download(hash: String? = null, reuse: Boolean = false) {
        HttpUrlFileDownloader(hfTokenProvider = { null }, debugLogs = false).downloadToFile(
            url = url, dst = destination, expectedSha256 = hash, reuseExisting = reuse,
            maxRetries = 1, resumeOverlapBytes = 0, stallTimeoutMs = 2000
        )
    }

    @Test fun downloadNewSupersedesBlockedImportWithoutSharedStaging() = runBlocking {
        val old = ModelArtifactOperations.begin(destination)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var oldPublished = false
        val oldJob = launch(Dispatchers.IO) {
            old.import({
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
            val imported = async { replacement.import({ ByteArrayInputStream("new".toByteArray()) }) {} }
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
            forceFresh = true, onProgress = { _, _ -> }
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
}
