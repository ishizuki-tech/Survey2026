/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: HttpUrlFileDownloader.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  A robust coroutine-based HTTP file downloader built upon HttpURLConnection.
 *  Provides resumable, integrity-verified transfers with exponential backoff,
 *  progress tracking, Hugging Face token support, and parallel HTTP Range
 *  downloading for large files.
 *
 *  Features:
 *   • HEAD probe with manual redirects and ETag/Last-Modified validators
 *   • Parallel HTTP Range download for large files
 *   • Per-chunk resume support
 *   • Automatic fallback to single-stream download
 *   • Safe single-stream resume using Range/If-Range with `.part` and `.meta`
 *   • Resume overlap to reduce silent corruption risk
 *   • Content-Range validation for 206 responses
 *   • Exponential backoff retry with Retry-After compliance
 *   • SHA-256 integrity verification and free-space checks
 *   • Throttled progress callbacks
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.os.StatFs
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Coroutine-safe downloader for large, resumable HTTP transfers.
 *
 * Large files are downloaded using multiple HTTP Range requests when the
 * server supports byte ranges. Existing single-stream logic remains as a
 * fallback for servers that do not support parallel Range transfers.
 *
 * @property hfToken Optional Hugging Face token ("hf_xxx"), applied only to
 * Hugging Face hosts.
 * @property debugLogs Enables diagnostic logging.
 */
class HttpUrlFileDownloader(
    private val hfToken: String? = null,
    private val debugLogs: Boolean = true,
) {
    private val tag = "HttpUrlFileDl"

    /**
     * Downloads [url] to [dst].
     *
     * Large files use parallel HTTP Range requests when possible.
     * Smaller files and unsupported servers use the original single-stream
     * resumable transfer path.
     */
    suspend fun downloadToFile(
        url: String,
        dst: File,
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
        expectedSha256: String? = null,
        connectTimeoutMs: Int = 20_000,
        firstByteTimeoutMs: Int = 30_000,
        stallTimeoutMs: Int = 90_000,
        ioBufferBytes: Int = 1 * 1024 * 1024,
        maxRetries: Int = 3,
        resumeOverlapBytes: Int = 64 * 1024,
    ) = withContext(Dispatchers.IO) {

        val parent =
            dst.absoluteFile.parentFile
                ?: throw IOException(
                    "Invalid destination: ${dst.absolutePath}"
                )

        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException(
                "Unable to create destination directory: ${parent.absolutePath}"
            )
        }

        val part =
            File(
                parent,
                dst.name + ".part",
            )

        val meta =
            MetaFile(part)

        /*
         * Fast path.
         */
        runCatching {
            headProbeSmart(
                srcUrl = url,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = firstByteTimeoutMs,
            ).total
        }.getOrNull()?.let { headLength ->

            val sizeMatches =
                dst.exists() &&
                        dst.length() == headLength

            val hashMatches =
                expectedSha256 == null ||
                        (
                                dst.exists() &&
                                        sha256(dst).equals(
                                            expectedSha256,
                                            ignoreCase = true,
                                        )
                                )

            if (sizeMatches && hashMatches) {
                onProgress(
                    dst.length(),
                    dst.length(),
                )

                logd(
                    "Already complete, skipping download."
                )

                return@withContext
            }
        }

        var attempt = 0
        var lastError: Throwable? = null

        while (attempt < maxRetries) {
            coroutineContext.ensureActive()

            try {
                val probe =
                    headProbeSmart(
                        srcUrl = url,
                        connectTimeoutMs = connectTimeoutMs,
                        readTimeoutMs = firstByteTimeoutMs,
                    )

                val total =
                    probe.total

                /*
                 * ---------------------------------------------------------
                 * Parallel Range download
                 * ---------------------------------------------------------
                 */

                val parallelEligible =
                    total != null &&
                            total >= PARALLEL_MIN_FILE_BYTES &&
                            probe.acceptRanges &&
                            !part.exists()

                if (parallelEligible) {
                    try {
                        prepareParallelState(
                            parent = parent,
                            part = part,
                            meta = meta,
                            probe = probe,
                            total = total,
                        )

                        logd(
                            "Starting parallel download: " +
                                    "$PARALLEL_CHUNKS chunks, " +
                                    "${bytesToMiB(total)} MiB total"
                        )

                        downloadParallel(
                            probe = probe,
                            dst = dst,
                            part = part,
                            meta = meta,
                            total = total,
                            expectedSha256 = expectedSha256,
                            connectTimeoutMs = connectTimeoutMs,
                            stallTimeoutMs = stallTimeoutMs,
                            maxRetries = maxRetries,
                            onProgress = onProgress,
                        )

                        return@withContext
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: ParallelRangeUnsupportedException) {
                        /*
                         * Range is not usable on this server/CDN.
                         * Clean parallel state and continue below using
                         * the existing single-stream downloader.
                         */
                        logw(
                            "Parallel Range download unavailable; " +
                                    "falling back to single stream: ${t.message}"
                        )

                        cleanupParallelChunkFiles(
                            parent = parent,
                            partName = part.name,
                        )

                        meta.delete()
                    }
                }

                /*
                 * If a previous single-stream transfer exists, it wins over
                 * stale parallel chunks.
                 */
                if (part.exists()) {
                    cleanupParallelChunkFiles(
                        parent = parent,
                        partName = part.name,
                    )
                }

                /*
                 * ---------------------------------------------------------
                 * Existing single-stream resumable download
                 * ---------------------------------------------------------
                 */

                if (!probe.acceptRanges && part.exists()) {
                    logw(
                        "Server does not advertise Accept-Ranges; " +
                                "restarting cleanly."
                    )

                    safeDelete(part)
                    meta.delete()
                }

                val reconciled =
                    reconcilePartial(
                        part = part,
                        meta = meta,
                        probe = probe,
                        total = total,
                    )

                var resumeFrom =
                    reconciled.resumeFrom

                ensureMetaIfStartingFresh(
                    part = part,
                    meta = meta,
                    probe = probe,
                    total = total,
                )

                val required =
                    if (total != null) {
                        max(
                            0L,
                            total - resumeFrom,
                        ) + FREE_SPACE_MARGIN_BYTES
                    } else {
                        FREE_SPACE_MARGIN_BYTES
                    }

                checkFreeSpaceOrThrow(
                    dir = parent,
                    required = required,
                )

                var triesOnThisStream = 0
                var unauthorizedCount = 0

                STREAM@ while (true) {
                    coroutineContext.ensureActive()

                    ensureMetaIfStartingFresh(
                        part = part,
                        meta = meta,
                        probe = probe,
                        total = total,
                    )

                    resumeFrom =
                        applyResumeOverlap(
                            part = part,
                            resumeFrom = resumeFrom,
                            overlapBytes = resumeOverlapBytes,
                            total = total,
                        )

                    val ifRange =
                        meta.read()?.let { stored ->
                            etagForIfRange(stored.etag)
                                ?: stored.lastModified
                        }

                    val conn =
                        openGetWithRedirects(
                            srcUrl = probe.finalUrl,
                            connectTimeoutMs = connectTimeoutMs,
                            readTimeoutMs = stallTimeoutMs,
                            rangeFrom =
                                resumeFrom.takeIf {
                                    it > 0L &&
                                            probe.acceptRanges
                                },
                            ifRange = ifRange,
                            maxRedirects = MAX_REDIRECTS,
                        )

                    try {
                        val code =
                            conn.responseCode

                        when (code) {
                            HttpURLConnection.HTTP_UNAUTHORIZED,
                            HttpURLConnection.HTTP_FORBIDDEN,
                                -> {
                                unauthorizedCount++

                                val snippet =
                                    readErrorSnippet(conn)

                                logw(
                                    "GET $code: unauthorized/forbidden " +
                                            "(count=$unauthorizedCount) " +
                                            (snippet ?: "")
                                )

                                if (
                                    unauthorizedCount >=
                                    MAX_UNAUTHORIZED_RETRIES
                                ) {
                                    throw IOException(
                                        "GET HTTP $code: access denied. " +
                                                (snippet ?: "")
                                    )
                                }

                                triesOnThisStream++

                                resumeFrom =
                                    part.length()
                                        .coerceAtLeast(0L)

                                continue@STREAM
                            }

                            HttpURLConnection.HTTP_OK -> {
                                if (resumeFrom > 0L) {
                                    logw(
                                        "Server ignored Range; restarting from 0."
                                    )

                                    safeDelete(part)
                                    meta.delete()
                                    resumeFrom = 0L

                                    if (++triesOnThisStream <= 3) {
                                        continue@STREAM
                                    }

                                    throw IOException(
                                        "Server ignored Range repeatedly."
                                    )
                                }
                            }

                            HttpURLConnection.HTTP_PARTIAL -> {
                                if (resumeFrom > 0L) {
                                    validateContentRangeStart(
                                        conn = conn,
                                        expectedStart = resumeFrom,
                                    )
                                }
                            }

                            HTTP_RANGE_NOT_SATISFIABLE -> {
                                val done =
                                    handleRangeNotSatisfiable(
                                        dst = dst,
                                        part = part,
                                        meta = meta,
                                        total = total,
                                        expectedSha256 =
                                            expectedSha256,
                                        onProgress = onProgress,
                                    )

                                if (done) {
                                    return@withContext
                                }

                                resumeFrom = 0L

                                if (++triesOnThisStream <= 3) {
                                    continue@STREAM
                                }

                                throw IOException(
                                    "416 reconciliation failed repeatedly."
                                )
                            }

                            HTTP_TOO_MANY_REQUESTS,
                            HttpURLConnection.HTTP_UNAVAILABLE,
                            HttpURLConnection.HTTP_CLIENT_TIMEOUT,
                                -> {
                                throw HttpExceptionWithRetryAfter(
                                    message = "GET HTTP $code",
                                    retryAfterMs =
                                        readRetryAfterMs(conn),
                                )
                            }
                        }

                        if (code in 500..599) {
                            throw HttpExceptionWithRetryAfter(
                                message = "GET HTTP $code",
                                retryAfterMs =
                                    readRetryAfterMs(conn),
                            )
                        }

                        if (
                            code != HttpURLConnection.HTTP_OK &&
                            code != HttpURLConnection.HTTP_PARTIAL
                        ) {
                            val snippet =
                                readErrorSnippet(conn)

                            throw IOException(
                                "GET HTTP $code" +
                                        (
                                                snippet?.let {
                                                    ": $it"
                                                } ?: ""
                                                )
                            )
                        }

                        val bufferSize =
                            ioBufferBytes.coerceIn(
                                64 * 1024,
                                MAX_IO_BUFFER_BYTES,
                            )

                        var downloaded =
                            resumeFrom

                        val progressEmitter =
                            ProgressEmitter(
                                total = total,
                                initialBytes = downloaded,
                                callback = onProgress,
                            )

                        progressEmitter.force(
                            downloaded
                        )

                        try {
                            conn.inputStream.use { input ->

                                FileOutputStream(
                                    part,
                                    resumeFrom > 0L,
                                ).use { fos ->

                                    val output =
                                        BufferedOutputStream(
                                            fos,
                                            bufferSize,
                                        )

                                    try {
                                        val buffer =
                                            ByteArray(
                                                bufferSize
                                            )

                                        while (true) {
                                            coroutineContext
                                                .ensureActive()

                                            val count =
                                                input.read(buffer)

                                            if (count == -1) {
                                                break
                                            }

                                            output.write(
                                                buffer,
                                                0,
                                                count,
                                            )

                                            downloaded +=
                                                count.toLong()

                                            progressEmitter.update(
                                                downloaded
                                            )
                                        }

                                        output.flush()

                                        /*
                                         * Perform one durability sync after
                                         * the complete stream has been flushed.
                                         */
                                        runCatching {
                                            fos.fd.sync()
                                        }
                                    } finally {
                                        runCatching {
                                            output.close()
                                        }
                                    }
                                }
                            }
                        } catch (
                            t: CancellationException
                        ) {
                            throw t
                        } catch (
                            t: SocketTimeoutException
                        ) {
                            logw(
                                "Stall timeout; resuming."
                            )

                            resumeFrom =
                                part.length()
                                    .coerceAtLeast(0L)

                            if (++triesOnThisStream <= 3) {
                                continue@STREAM
                            }

                            throw t
                        } catch (
                            t: IOException
                        ) {
                            logw(
                                "Stream error: ${t.message}"
                            )

                            resumeFrom =
                                part.length()
                                    .coerceAtLeast(0L)

                            if (++triesOnThisStream <= 3) {
                                continue@STREAM
                            }

                            throw t
                        }

                        /*
                         * Promote .part to the final file.
                         */
                        promotePartToDestination(
                            part = part,
                            dst = dst,
                        )

                        meta.delete()

                        validateFinalFile(
                            dst = dst,
                            total = total,
                            expectedSha256 =
                                expectedSha256,
                        )

                        onProgress(
                            dst.length(),
                            total ?: dst.length(),
                        )

                        logd(
                            "Saved ${dst.name} " +
                                    "(${dst.length()} bytes)"
                        )

                        return@withContext
                    } finally {
                        conn.disconnect()
                    }
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                lastError = t

                logw(
                    "Attempt ${attempt + 1} failed: " +
                            "${t::class.simpleName}: ${t.message}"
                )

                val retryAfterMs =
                    (
                            t as?
                                    HttpExceptionWithRetryAfter
                            )?.retryAfterMs

                if (attempt < maxRetries - 1) {
                    val backoffMs =
                        computeBackoffMs(
                            attempt = attempt,
                            retryAfterMs = retryAfterMs,
                        )

                    logw(
                        "Retrying in ${backoffMs}ms..."
                    )

                    delay(backoffMs)
                }
            }

            attempt++
        }

        throw IOException(
            "Download failed after $maxRetries attempts: " +
                    "${lastError?.message}",
            lastError,
        )
    }

    /* ========================================================================
     * Parallel download
     * ====================================================================== */

    private data class DownloadChunk(
        val index: Int,
        val start: Long,
        val endInclusive: Long,
    ) {
        val length: Long
            get() =
                endInclusive - start + 1L
    }

    private fun buildChunks(
        total: Long,
        count: Int,
    ): List<DownloadChunk> {

        require(total > 0L)
        require(count > 0)

        val actualCount =
            min(
                count.toLong(),
                total,
            ).toInt()

        val baseSize =
            total / actualCount

        val remainder =
            total % actualCount

        var start = 0L

        return List(actualCount) { index ->

            val size =
                baseSize +
                        if (
                            index <
                            remainder
                        ) {
                            1L
                        } else {
                            0L
                        }

            val end =
                start + size - 1L

            DownloadChunk(
                index = index,
                start = start,
                endInclusive = end,
            ).also {
                start = end + 1L
            }
        }
    }

    private fun chunkFile(
        parent: File,
        partName: String,
        index: Int,
    ): File =
        File(
            parent,
            "$partName.chunk.$index",
        )

    private fun cleanupParallelChunkFiles(
        parent: File,
        partName: String,
    ) {
        parent.listFiles()
            ?.filter {
                it.name.startsWith(
                    "$partName.chunk."
                )
            }
            ?.forEach {
                safeDelete(it)
            }
    }

    private fun hasParallelChunkFiles(
        parent: File,
        partName: String,
    ): Boolean =
        parent.listFiles()
            ?.any {
                it.name.startsWith(
                    "$partName.chunk."
                )
            } == true

    /**
     * Validate persisted parallel chunk state before reusing it.
     */
    private fun prepareParallelState(
        parent: File,
        part: File,
        meta: MetaFile,
        probe: Probe,
        total: Long,
    ) {
        val hasChunks =
            hasParallelChunkFiles(
                parent = parent,
                partName = part.name,
            )

        val stored =
            meta.read()

        if (
            hasChunks &&
            stored == null
        ) {
            logw(
                "Parallel chunks exist without metadata; restarting."
            )

            cleanupParallelChunkFiles(
                parent = parent,
                partName = part.name,
            )
        }

        if (
            stored != null &&
            !parallelValidatorsMatch(
                stored = stored,
                probe = probe,
                total = total,
            )
        ) {
            logw(
                "Parallel download validators changed; restarting chunks."
            )

            cleanupParallelChunkFiles(
                parent = parent,
                partName = part.name,
            )

            meta.delete()
        }

        val chunks =
            buildChunks(
                total = total,
                count = PARALLEL_CHUNKS,
            )

        for (chunk in chunks) {
            val file =
                chunkFile(
                    parent = parent,
                    partName = part.name,
                    index = chunk.index,
                )

            if (
                file.exists() &&
                file.length() > chunk.length
            ) {
                logw(
                    "Chunk ${chunk.index} is too large; deleting."
                )

                safeDelete(file)
            }
        }

        if (meta.read() == null) {
            meta.write(
                Meta(
                    etag = probe.etag,
                    lastModified =
                        probe.lastModified,
                    total = total,
                )
            )
        }
    }

    private fun parallelValidatorsMatch(
        stored: Meta,
        probe: Probe,
        total: Long,
    ): Boolean {

        if (
            stored.total != null &&
            stored.total != total
        ) {
            return false
        }

        val storedEtag =
            etagForCompare(stored.etag)

        val probeEtag =
            etagForCompare(probe.etag)

        if (
            storedEtag != null ||
            probeEtag != null
        ) {
            return storedEtag == probeEtag
        }

        val storedModified =
            stored.lastModified
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }

        val probeModified =
            probe.lastModified
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }

        if (
            storedModified != null ||
            probeModified != null
        ) {
            return storedModified ==
                    probeModified
        }

        return true
    }

    private suspend fun downloadParallel(
        probe: Probe,
        dst: File,
        part: File,
        meta: MetaFile,
        total: Long,
        expectedSha256: String?,
        connectTimeoutMs: Int,
        stallTimeoutMs: Int,
        maxRetries: Int,
        onProgress: (Long, Long?) -> Unit,
    ) = coroutineScope {

        val parent =
            part.parentFile
                ?: throw IOException(
                    "Missing parent directory."
                )

        val chunks =
            buildChunks(
                total = total,
                count = PARALLEL_CHUNKS,
            )

        val chunkFiles =
            chunks.associateWith { chunk ->
                chunkFile(
                    parent = parent,
                    partName = part.name,
                    index = chunk.index,
                )
            }

        /*
         * Calculate how much data is already present.
         */
        val existingBytes =
            chunks.sumOf { chunk ->
                chunkFiles
                    .getValue(chunk)
                    .length()
                    .coerceIn(
                        0L,
                        chunk.length,
                    )
            }

        val remainingBytes =
            max(
                0L,
                total - existingBytes,
            )

        /*
         * The merge temporarily needs approximately one extra chunk because
         * the destination grows before the source chunk is deleted.
         */
        val largestChunk =
            chunks.maxOf {
                it.length
            }

        checkFreeSpaceOrThrow(
            dir = parent,
            required =
                remainingBytes +
                        largestChunk +
                        FREE_SPACE_MARGIN_BYTES,
        )

        val ifRange =
            etagForIfRange(
                probe.etag
            ) ?: probe.lastModified

        var downloaded =
            existingBytes

        val progressLock =
            Any()

        var lastEmitNs =
            0L

        var lastEmitBytes =
            downloaded

        var speedWindowNs =
            System.nanoTime()

        var speedWindowBytes =
            downloaded

        fun reportDelta(
            delta: Long
        ) {
            synchronized(
                progressLock
            ) {
                downloaded =
                    (
                            downloaded +
                                    delta
                            ).coerceIn(
                            0L,
                            total,
                        )

                val now =
                    System.nanoTime()

                val timeElapsed =
                    now -
                            lastEmitNs >=
                            PARALLEL_PROGRESS_INTERVAL_NS

                val bytesAdvanced =
                    kotlin.math.abs(
                        downloaded -
                                lastEmitBytes
                    ) >=
                            PARALLEL_PROGRESS_BYTES

                val finished =
                    downloaded == total

                if (
                    timeElapsed ||
                    bytesAdvanced ||
                    finished
                ) {
                    onProgress(
                        downloaded,
                        total,
                    )

                    lastEmitNs = now
                    lastEmitBytes =
                        downloaded
                }

                if (
                    now -
                    speedWindowNs >=
                    SPEED_LOG_INTERVAL_NS
                ) {
                    val deltaBytes =
                        max(
                            0L,
                            downloaded -
                                    speedWindowBytes,
                        )

                    val elapsedSeconds =
                        (
                                now -
                                        speedWindowNs
                                ) /
                                1_000_000_000.0

                    if (elapsedSeconds > 0.0) {
                        val mibPerSecond =
                            deltaBytes /
                                    (1024.0 * 1024.0) /
                                    elapsedSeconds

                        val percent =
                            downloaded *
                                    100.0 /
                                    total

                        logd(
                            "Parallel progress: " +
                                    "%.1f%%, %.1f MiB/s, %d/%d MiB"
                                        .format(
                                            percent,
                                            mibPerSecond,
                                            bytesToMiB(
                                                downloaded
                                            ),
                                            bytesToMiB(
                                                total
                                            ),
                                        )
                        )
                    }

                    speedWindowNs = now
                    speedWindowBytes =
                        downloaded
                }
            }
        }

        onProgress(
            downloaded,
            total,
        )

        chunks.map { chunk ->
            async(
                Dispatchers.IO
            ) {
                downloadChunk(
                    finalUrl =
                        probe.finalUrl,
                    chunk = chunk,
                    chunkFile =
                        chunkFiles
                            .getValue(chunk),
                    ifRange = ifRange,
                    connectTimeoutMs =
                        connectTimeoutMs,
                    stallTimeoutMs =
                        stallTimeoutMs,
                    maxRetries =
                        maxRetries,
                    onBytesDownloaded =
                        ::reportDelta,
                )
            }
        }.awaitAll()

        coroutineContext.ensureActive()

        /*
         * Verify chunks before merging.
         */
        for (chunk in chunks) {
            val file =
                chunkFiles
                    .getValue(chunk)

            if (
                file.length() !=
                chunk.length
            ) {
                throw IOException(
                    "Chunk ${chunk.index} incomplete: " +
                            "expected=${chunk.length}, " +
                            "got=${file.length()}"
                )
            }
        }

        /*
         * Recheck disk space before merge.
         */
        checkFreeSpaceOrThrow(
            dir = parent,
            required =
                largestChunk +
                        FREE_SPACE_MARGIN_BYTES,
        )

        safeDelete(part)

        FileOutputStream(
            part,
            false,
        ).use { fos ->

            val output =
                BufferedOutputStream(
                    fos,
                    PARALLEL_BUFFER_BYTES,
                )

            try {
                val buffer =
                    ByteArray(
                        PARALLEL_BUFFER_BYTES
                    )

                for (chunk in chunks) {
                    coroutineContext
                        .ensureActive()

                    val file =
                        chunkFiles
                            .getValue(chunk)

                    FileInputStream(
                        file
                    ).use { input ->

                        while (true) {
                            coroutineContext
                                .ensureActive()

                            val count =
                                input.read(buffer)

                            if (count == -1) {
                                break
                            }

                            output.write(
                                buffer,
                                0,
                                count,
                            )
                        }
                    }

                    /*
                     * Flush before deleting the source chunk so disk usage
                     * stays bounded during concatenation.
                     */
                    output.flush()

                    safeDelete(file)
                }

                output.flush()

                runCatching {
                    fos.fd.sync()
                }
            } finally {
                runCatching {
                    output.close()
                }
            }
        }

        if (
            part.length() != total
        ) {
            throw IOException(
                "Merged size mismatch: " +
                        "expected=$total, " +
                        "got=${part.length()}"
            )
        }

        if (
            expectedSha256 != null
        ) {
            val actual =
                sha256(part)

            if (
                !actual.equals(
                    expectedSha256,
                    ignoreCase = true,
                )
            ) {
                safeDelete(part)

                throw IOException(
                    "SHA-256 mismatch after parallel download: " +
                            "expected=$expectedSha256 got=$actual"
                )
            }
        }

        promotePartToDestination(
            part = part,
            dst = dst,
        )

        meta.delete()

        cleanupParallelChunkFiles(
            parent = parent,
            partName = part.name,
        )

        onProgress(
            dst.length(),
            dst.length(),
        )

        logd(
            "Parallel download complete: " +
                    "${dst.name} " +
                    "(${dst.length()} bytes)"
        )
    }

    private suspend fun downloadChunk(
        finalUrl: String,
        chunk: DownloadChunk,
        chunkFile: File,
        ifRange: String?,
        connectTimeoutMs: Int,
        stallTimeoutMs: Int,
        maxRetries: Int,
        onBytesDownloaded: (Long) -> Unit,
    ) {
        var attempt = 0

        while (
            attempt <
            maxRetries
        ) {
            coroutineContext.ensureActive()

            var existing =
                chunkFile.length()

            if (
                existing >
                chunk.length
            ) {
                safeDelete(chunkFile)
                existing = 0L
            }

            if (
                existing ==
                chunk.length
            ) {
                logd(
                    "Chunk ${chunk.index} already complete."
                )

                return
            }

            /*
             * Re-download a short tail when resuming a partially downloaded
             * chunk.
             */
            if (
                existing > 0L &&
                PARALLEL_RESUME_OVERLAP_BYTES > 0L
            ) {
                val overlap =
                    min(
                        existing,
                        PARALLEL_RESUME_OVERLAP_BYTES,
                    )

                val newLength =
                    existing - overlap

                RandomAccessFile(
                    chunkFile,
                    "rw",
                ).use { raf ->
                    raf.setLength(
                        newLength
                    )
                }

                onBytesDownloaded(
                    -overlap
                )

                existing =
                    newLength
            }

            val rangeStart =
                chunk.start +
                        existing

            val conn =
                openRangeGetWithRedirects(
                    srcUrl = finalUrl,
                    connectTimeoutMs =
                        connectTimeoutMs,
                    readTimeoutMs =
                        stallTimeoutMs,
                    rangeStart =
                        rangeStart,
                    rangeEndInclusive =
                        chunk.endInclusive,
                    ifRange =
                        ifRange,
                    maxRedirects =
                        MAX_REDIRECTS,
                )

            try {
                val code =
                    conn.responseCode

                when (code) {
                    HttpURLConnection.HTTP_UNAUTHORIZED,
                    HttpURLConnection.HTTP_FORBIDDEN,
                        -> {
                        val snippet =
                            readErrorSnippet(
                                conn
                            )

                        throw IOException(
                            "Range GET HTTP $code: " +
                                    "access denied. " +
                                    (snippet ?: "")
                        )
                    }

                    HTTP_TOO_MANY_REQUESTS,
                    HttpURLConnection.HTTP_UNAVAILABLE,
                    HttpURLConnection.HTTP_CLIENT_TIMEOUT,
                        -> {
                        throw HttpExceptionWithRetryAfter(
                            message =
                                "Range GET HTTP $code",
                            retryAfterMs =
                                readRetryAfterMs(
                                    conn
                                ),
                        )
                    }

                    HttpURLConnection.HTTP_OK -> {
                        /*
                         * A bounded Range request should return 206.
                         * 200 means the CDN ignored Range.
                         */
                        throw ParallelRangeUnsupportedException(
                            "Server ignored Range for chunk ${chunk.index}."
                        )
                    }
                }

                if (
                    code in 500..599
                ) {
                    throw HttpExceptionWithRetryAfter(
                        message =
                            "Range GET HTTP $code",
                        retryAfterMs =
                            readRetryAfterMs(
                                conn
                            ),
                    )
                }

                if (
                    code !=
                    HttpURLConnection.HTTP_PARTIAL
                ) {
                    throw ParallelRangeUnsupportedException(
                        "Expected HTTP 206 but got $code."
                    )
                }

                validateContentRangeStart(
                    conn = conn,
                    expectedStart =
                        rangeStart,
                )

                conn.inputStream.use { input ->

                    FileOutputStream(
                        chunkFile,
                        existing > 0L,
                    ).use { fos ->

                        val output =
                            BufferedOutputStream(
                                fos,
                                PARALLEL_BUFFER_BYTES,
                            )

                        try {
                            val buffer =
                                ByteArray(
                                    PARALLEL_BUFFER_BYTES
                                )

                            while (true) {
                                coroutineContext
                                    .ensureActive()

                                val count =
                                    input.read(
                                        buffer
                                    )

                                if (
                                    count == -1
                                ) {
                                    break
                                }

                                output.write(
                                    buffer,
                                    0,
                                    count,
                                )

                                onBytesDownloaded(
                                    count.toLong()
                                )
                            }

                            output.flush()
                        } finally {
                            runCatching {
                                output.close()
                            }
                        }
                    }
                }

                if (
                    chunkFile.length() !=
                    chunk.length
                ) {
                    throw IOException(
                        "Chunk ${chunk.index} size mismatch: " +
                                "expected=${chunk.length}, " +
                                "got=${chunkFile.length()}"
                    )
                }

                logd(
                    "Chunk ${chunk.index} completed: " +
                            "${bytesToMiB(chunk.length)} MiB"
                )

                return
            } catch (
                t: CancellationException
            ) {
                throw t
            } catch (
                t: ParallelRangeUnsupportedException
            ) {
                throw t
            } catch (
                t: Throwable
            ) {
                attempt++

                if (
                    attempt >=
                    maxRetries
                ) {
                    throw t
                }

                val retryAfterMs =
                    (
                            t as?
                                    HttpExceptionWithRetryAfter
                            )?.retryAfterMs

                val backoffMs =
                    computeBackoffMs(
                        attempt =
                            attempt - 1,
                        retryAfterMs =
                            retryAfterMs,
                    )

                logw(
                    "Chunk ${chunk.index} failed: " +
                            "${t.message}; " +
                            "retrying in ${backoffMs}ms"
                )

                delay(
                    backoffMs
                )
            } finally {
                conn.disconnect()
            }
        }

        throw IOException(
            "Chunk ${chunk.index} failed after $maxRetries attempts."
        )
    }

    private fun openRangeGetWithRedirects(
        srcUrl: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        rangeStart: Long,
        rangeEndInclusive: Long,
        ifRange: String?,
        maxRedirects: Int,
    ): HttpURLConnection {

        var current =
            srcUrl

        var hops = 0

        while (true) {
            val conn =
                openConn(
                    url = current,
                    method = "GET",
                    connectTimeoutMs =
                        connectTimeoutMs,
                    readTimeoutMs =
                        readTimeoutMs,
                    followRedirects = false,
                )

            setCommonHeaders(
                conn = conn,
                url = current,
            )

            conn.setRequestProperty(
                "Range",
                "bytes=$rangeStart-$rangeEndInclusive",
            )

            if (
                !ifRange.isNullOrBlank()
            ) {
                conn.setRequestProperty(
                    "If-Range",
                    ifRange,
                )
            }

            conn.connect()

            val code =
                conn.responseCode

            if (
                code in
                300..399
            ) {
                val location =
                    conn.getHeaderField(
                        "Location"
                    )
                        ?: run {
                            conn.disconnect()

                            throw IOException(
                                "Redirect without Location."
                            )
                        }

                val next =
                    URL(
                        URL(current),
                        location,
                    ).toString()

                conn.disconnect()

                current = next

                if (
                    ++hops >
                    maxRedirects
                ) {
                    throw IOException(
                        "Too many redirects."
                    )
                }

                continue
            }

            return conn
        }
    }

    /* ========================================================================
     * Probing
     * ====================================================================== */

    private data class Probe(
        val total: Long?,
        val acceptRanges: Boolean,
        val etag: String?,
        val lastModified: String?,
        val finalUrl: String,
    )

    /**
     * Probe with HEAD first and GET Range(0-0) as a fallback.
     */
    private fun headProbeSmart(
        srcUrl: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Probe {

        val head =
            headProbe(
                srcUrl = srcUrl,
                connectTimeoutMs =
                    connectTimeoutMs,
                readTimeoutMs =
                    readTimeoutMs,
            )

        if (
            head.total != null
        ) {
            return head
        }

        return runCatching {
            probeViaRangeGet(
                srcUrl =
                    head.finalUrl,
                connectTimeoutMs =
                    connectTimeoutMs,
                readTimeoutMs =
                    readTimeoutMs,
            )
        }.getOrElse {
            head
        }
    }

    private fun headProbe(
        srcUrl: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Probe {

        var current =
            srcUrl

        var hops = 0

        while (true) {
            val conn =
                openConn(
                    url = current,
                    method = "HEAD",
                    connectTimeoutMs =
                        connectTimeoutMs,
                    readTimeoutMs =
                        readTimeoutMs,
                    followRedirects =
                        false,
                )

            try {
                setCommonHeaders(
                    conn = conn,
                    url = current,
                )

                conn.connect()

                val code =
                    conn.responseCode

                if (
                    code in
                    300..399
                ) {
                    val location =
                        conn.getHeaderField(
                            "Location"
                        )
                            ?: throw IOException(
                                "Redirect without Location."
                            )

                    current =
                        URL(
                            URL(current),
                            location,
                        ).toString()

                    if (
                        ++hops >
                        MAX_REDIRECTS
                    ) {
                        throw IOException(
                            "Too many redirects."
                        )
                    }

                    continue
                }

                if (
                    code ==
                    HttpURLConnection.HTTP_BAD_METHOD ||
                    code ==
                    HttpURLConnection.HTTP_NOT_IMPLEMENTED
                ) {
                    return probeViaRangeGet(
                        srcUrl = current,
                        connectTimeoutMs =
                            connectTimeoutMs,
                        readTimeoutMs =
                            readTimeoutMs,
                    )
                }

                if (
                    code ==
                    HTTP_TOO_MANY_REQUESTS ||
                    code ==
                    HttpURLConnection.HTTP_UNAVAILABLE ||
                    code ==
                    HttpURLConnection.HTTP_CLIENT_TIMEOUT ||
                    code in 500..599
                ) {
                    throw HttpExceptionWithRetryAfter(
                        message =
                            "HEAD HTTP $code",
                        retryAfterMs =
                            readRetryAfterMs(
                                conn
                            ),
                    )
                }

                if (
                    code !in
                    200..299
                ) {
                    throw IOException(
                        "HEAD HTTP $code" +
                                (
                                        readErrorSnippet(
                                            conn
                                        )?.let {
                                            ": $it"
                                        } ?: ""
                                        )
                    )
                }

                val total =
                    conn.getHeaderFieldLong(
                        "Content-Length",
                        -1L,
                    ).takeIf {
                        it >= 0L
                    }

                val acceptRanges =
                    (
                            conn.getHeaderField(
                                "Accept-Ranges"
                            ) ?: ""
                            ).contains(
                            "bytes",
                            ignoreCase = true,
                        )

                val etag =
                    etagForIfRange(
                        conn.getHeaderField(
                            "ETag"
                        )
                    )

                val lastModified =
                    conn.getHeaderField(
                        "Last-Modified"
                    )

                val finalUrl =
                    conn.url.toString()

                return Probe(
                    total = total,
                    acceptRanges =
                        acceptRanges,
                    etag = etag,
                    lastModified =
                        lastModified,
                    finalUrl = finalUrl,
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun probeViaRangeGet(
        srcUrl: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): Probe {

        var current =
            srcUrl

        var hops = 0

        while (true) {
            val conn =
                openConn(
                    url = current,
                    method = "GET",
                    connectTimeoutMs =
                        connectTimeoutMs,
                    readTimeoutMs =
                        readTimeoutMs,
                    followRedirects =
                        false,
                )

            try {
                setCommonHeaders(
                    conn = conn,
                    url = current,
                )

                conn.setRequestProperty(
                    "Range",
                    "bytes=0-0",
                )

                conn.connect()

                val code =
                    conn.responseCode

                if (
                    code in
                    300..399
                ) {
                    val location =
                        conn.getHeaderField(
                            "Location"
                        )
                            ?: throw IOException(
                                "Redirect without Location."
                            )

                    current =
                        URL(
                            URL(current),
                            location,
                        ).toString()

                    if (
                        ++hops >
                        MAX_REDIRECTS
                    ) {
                        throw IOException(
                            "Too many redirects."
                        )
                    }

                    continue
                }

                if (
                    code ==
                    HTTP_TOO_MANY_REQUESTS ||
                    code ==
                    HttpURLConnection.HTTP_UNAVAILABLE ||
                    code ==
                    HttpURLConnection.HTTP_CLIENT_TIMEOUT ||
                    code in 500..599
                ) {
                    throw HttpExceptionWithRetryAfter(
                        message =
                            "GET-probe HTTP $code",
                        retryAfterMs =
                            readRetryAfterMs(
                                conn
                            ),
                    )
                }

                if (
                    code !in
                    200..299
                ) {
                    throw IOException(
                        "GET-probe HTTP $code" +
                                (
                                        readErrorSnippet(
                                            conn
                                        )?.let {
                                            ": $it"
                                        } ?: ""
                                        )
                    )
                }

                val contentRange =
                    conn.getHeaderField(
                        "Content-Range"
                    )

                val totalFromRange =
                    parseTotalFromContentRange(
                        contentRange
                    )

                val total =
                    totalFromRange
                        ?: conn.getHeaderFieldLong(
                            "Content-Length",
                            -1L,
                        ).takeIf {
                            it >= 0L
                        }

                val acceptRanges =
                    code ==
                            HttpURLConnection.HTTP_PARTIAL ||
                            (
                                    conn.getHeaderField(
                                        "Accept-Ranges"
                                    ) ?: ""
                                    ).contains(
                                    "bytes",
                                    ignoreCase = true,
                                )

                val etag =
                    etagForIfRange(
                        conn.getHeaderField(
                            "ETag"
                        )
                    )

                val lastModified =
                    conn.getHeaderField(
                        "Last-Modified"
                    )

                val finalUrl =
                    conn.url.toString()

                runCatching {
                    conn.inputStream.close()
                }

                return Probe(
                    total = total,
                    acceptRanges =
                        acceptRanges,
                    etag = etag,
                    lastModified =
                        lastModified,
                    finalUrl = finalUrl,
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun parseTotalFromContentRange(
        contentRange: String?
    ): Long? {

        val value =
            contentRange
                ?.trim()
                .orEmpty()

        val slash =
            value.lastIndexOf('/')

        if (
            slash < 0 ||
            slash + 1 >=
            value.length
        ) {
            return null
        }

        return value
            .substring(
                slash + 1
            )
            .trim()
            .toLongOrNull()
            ?.takeIf {
                it >= 0L
            }
    }

    private fun etagForIfRange(
        etag: String?
    ): String? =
        etag
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }

    private fun etagForCompare(
        etag: String?
    ): String? {

        var value =
            etagForIfRange(
                etag
            ) ?: return null

        if (
            value.startsWith(
                "W/",
                ignoreCase = true,
            )
        ) {
            value =
                value
                    .substring(2)
                    .trim()
        }

        if (
            value.length >= 2 &&
            value.first() == '"' &&
            value.last() == '"'
        ) {
            value =
                value
                    .substring(
                        1,
                        value.length - 1,
                    )
                    .trim()
        }

        return value
            .takeIf {
                it.isNotBlank()
            }
    }

    /* ========================================================================
     * Metadata and partial state
     * ====================================================================== */

    private data class Meta(
        val etag: String?,
        val lastModified: String?,
        val total: Long?,
    )

    private class MetaFile(
        private val part: File
    ) {
        private val file =
            File(
                part.parentFile,
                part.name + ".meta",
            )

        fun read(): Meta? =
            runCatching {
                if (!file.exists()) {
                    return@runCatching null
                }

                val values =
                    file.readLines()
                        .mapNotNull { line ->
                            val index =
                                line.indexOf('=')

                            if (
                                index <= 0
                            ) {
                                null
                            } else {
                                line.substring(
                                    0,
                                    index,
                                ) to
                                        line.substring(
                                            index + 1
                                        )
                            }
                        }
                        .toMap()

                Meta(
                    etag =
                        values["etag"],
                    lastModified =
                        values["lastModified"],
                    total =
                        values["total"]
                            ?.toLongOrNull(),
                )
            }.getOrNull()

        fun write(
            meta: Meta
        ) {
            runCatching {
                val tmp =
                    File(
                        file.parentFile,
                        file.name + ".tmp",
                    )

                tmp.writeText(
                    buildString {
                        meta.etag?.let {
                            append(
                                "etag=$it\n"
                            )
                        }

                        meta.lastModified?.let {
                            append(
                                "lastModified=$it\n"
                            )
                        }

                        meta.total?.let {
                            append(
                                "total=$it\n"
                            )
                        }
                    }
                )

                if (file.exists()) {
                    runCatching {
                        file.delete()
                    }
                }

                if (
                    !tmp.renameTo(file)
                ) {
                    file.writeText(
                        tmp.readText()
                    )

                    runCatching {
                        tmp.delete()
                    }
                }
            }
        }

        fun delete() {
            runCatching {
                if (file.exists()) {
                    file.delete()
                }
            }
        }

        fun exists(): Boolean =
            file.exists()
    }

    private data class PartialReconcile(
        val resumeFrom: Long
    )

    private fun reconcilePartial(
        part: File,
        meta: MetaFile,
        probe: Probe,
        total: Long?,
    ): PartialReconcile {

        if (!part.exists()) {
            return PartialReconcile(
                0L
            )
        }

        val onDisk =
            part.length()

        if (
            onDisk <= 0L
        ) {
            safeDelete(part)
            meta.delete()

            return PartialReconcile(
                0L
            )
        }

        if (
            total != null &&
            onDisk > total
        ) {
            logw(
                "Partial larger than total " +
                        "(part=$onDisk total=$total). Restarting."
            )

            safeDelete(part)
            meta.delete()

            return PartialReconcile(
                0L
            )
        }

        val stored =
            meta.read()

        if (
            stored == null
        ) {
            logw(
                "Partial exists but metadata is missing. Restarting."
            )

            safeDelete(part)
            meta.delete()

            return PartialReconcile(
                0L
            )
        }

        if (
            total != null &&
            stored.total != null &&
            stored.total != total
        ) {
            logw(
                "Metadata total mismatch " +
                        "(meta=${stored.total} probe=$total). Restarting."
            )

            safeDelete(part)
            meta.delete()

            return PartialReconcile(
                0L
            )
        }

        val probeEtag =
            etagForCompare(
                probe.etag
            )

        val storedEtag =
            etagForCompare(
                stored.etag
            )

        if (
            probeEtag != null &&
            storedEtag != null &&
            probeEtag != storedEtag
        ) {
            logw(
                "ETag changed. Restarting."
            )

            safeDelete(part)
            meta.delete()

            return PartialReconcile(
                0L
            )
        }

        val probeModified =
            probe.lastModified
                ?.trim()

        val storedModified =
            stored.lastModified
                ?.trim()

        if (
            probeEtag == null &&
            storedEtag == null &&
            probeModified != null &&
            storedModified != null &&
            probeModified != storedModified
        ) {
            logw(
                "Last-Modified changed. Restarting."
            )

            safeDelete(part)
            meta.delete()

            return PartialReconcile(
                0L
            )
        }

        val bounded =
            if (
                total != null
            ) {
                onDisk.coerceIn(
                    0L,
                    total,
                )
            } else {
                onDisk.coerceAtLeast(
                    0L
                )
            }

        return PartialReconcile(
            bounded
        )
    }

    private fun ensureMetaIfStartingFresh(
        part: File,
        meta: MetaFile,
        probe: Probe,
        total: Long?,
    ) {
        if (part.exists()) {
            return
        }

        if (meta.exists()) {
            return
        }

        meta.write(
            Meta(
                etag = probe.etag,
                lastModified =
                    probe.lastModified,
                total = total,
            )
        )
    }

    private fun applyResumeOverlap(
        part: File,
        resumeFrom: Long,
        overlapBytes: Int,
        total: Long?,
    ): Long {

        if (!part.exists()) {
            return 0L
        }

        val length =
            part.length()
                .coerceAtLeast(0L)

        val from =
            resumeFrom.coerceIn(
                0L,
                total ?: Long.MAX_VALUE,
            )

        if (
            from <= 0L
        ) {
            return 0L
        }

        val overlap =
            overlapBytes
                .coerceAtLeast(0)
                .toLong()

        if (
            overlap <= 0L
        ) {
            return from
        }

        val newFrom =
            (
                    from -
                            overlap
                    ).coerceAtLeast(
                    0L
                )

        if (
            newFrom <
            length
        ) {
            runCatching {
                RandomAccessFile(
                    part,
                    "rw",
                ).use { file ->
                    file.setLength(
                        newFrom
                    )
                }
            }

            return newFrom
        }

        return from
    }

    /* ========================================================================
     * Single-stream GET
     * ====================================================================== */

    private fun openGetWithRedirects(
        srcUrl: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        rangeFrom: Long?,
        ifRange: String?,
        maxRedirects: Int,
    ): HttpURLConnection {

        var current =
            srcUrl

        var hops = 0

        while (true) {
            val conn =
                openConn(
                    url = current,
                    method = "GET",
                    connectTimeoutMs =
                        connectTimeoutMs,
                    readTimeoutMs =
                        readTimeoutMs,
                    followRedirects = false,
                )

            setCommonHeaders(
                conn = conn,
                url = current,
            )

            if (
                rangeFrom != null &&
                rangeFrom > 0L
            ) {
                conn.setRequestProperty(
                    "Range",
                    "bytes=$rangeFrom-",
                )

                if (
                    !ifRange.isNullOrBlank()
                ) {
                    conn.setRequestProperty(
                        "If-Range",
                        ifRange,
                    )
                }
            }

            conn.connect()

            val code =
                conn.responseCode

            if (
                code in
                300..399
            ) {
                val location =
                    conn.getHeaderField(
                        "Location"
                    )
                        ?: run {
                            conn.disconnect()

                            throw IOException(
                                "Redirect without Location."
                            )
                        }

                val next =
                    URL(
                        URL(current),
                        location,
                    ).toString()

                conn.disconnect()

                current = next

                if (
                    ++hops >
                    maxRedirects
                ) {
                    throw IOException(
                        "Too many redirects."
                    )
                }

                continue
            }

            return conn
        }
    }

    private fun validateContentRangeStart(
        conn: HttpURLConnection,
        expectedStart: Long,
    ) {
        val contentRange =
            conn.getHeaderField(
                "Content-Range"
            )
                ?.trim()
                .orEmpty()

        if (
            !contentRange
                .lowercase()
                .startsWith(
                    "bytes "
                )
        ) {
            throw IOException(
                "206 without Content-Range header."
            )
        }

        val space =
            contentRange.indexOf(' ')

        val dash =
            contentRange.indexOf(
                '-',
                startIndex =
                    space + 1,
            )

        val slash =
            contentRange.indexOf(
                '/',
                startIndex =
                    dash + 1,
            )

        if (
            space < 0 ||
            dash < 0 ||
            slash < 0
        ) {
            throw IOException(
                "Malformed Content-Range: $contentRange"
            )
        }

        val start =
            contentRange
                .substring(
                    space + 1,
                    dash,
                )
                .trim()
                .toLongOrNull()
                ?: throw IOException(
                    "Malformed Content-Range start: $contentRange"
                )

        if (
            start != expectedStart
        ) {
            throw IOException(
                "Content-Range start mismatch: " +
                        "expected=$expectedStart got=$start " +
                        "($contentRange)"
            )
        }
    }

    /* ========================================================================
     * HTTP 416 reconciliation
     * ====================================================================== */

    private fun handleRangeNotSatisfiable(
        dst: File,
        part: File,
        meta: MetaFile,
        total: Long?,
        expectedSha256: String?,
        onProgress: (Long, Long?) -> Unit,
    ): Boolean {

        if (
            total == null
        ) {
            logw(
                "416 but total unknown; restarting."
            )

            safeDelete(part)
            meta.delete()

            return false
        }

        val onDisk =
            part.length()

        if (
            onDisk == total
        ) {
            promotePartToDestination(
                part = part,
                dst = dst,
            )

            meta.delete()

            if (
                expectedSha256 != null
            ) {
                val actual =
                    sha256(dst)

                if (
                    !actual.equals(
                        expectedSha256,
                        ignoreCase = true,
                    )
                ) {
                    safeDelete(dst)

                    throw IOException(
                        "SHA mismatch after 416 reconciliation."
                    )
                }
            }

            onProgress(
                total,
                total,
            )

            logd(
                "Completed via 416 reconciliation."
            )

            return true
        }

        logw(
            "416 mismatch " +
                    "(part=$onDisk total=$total); restarting."
        )

        safeDelete(part)
        meta.delete()

        return false
    }

    /* ========================================================================
     * File validation
     * ====================================================================== */

    private fun promotePartToDestination(
        part: File,
        dst: File,
    ) {
        if (
            dst.exists()
        ) {
            safeDelete(dst)
        }

        if (
            !part.renameTo(dst)
        ) {
            part.copyTo(
                target = dst,
                overwrite = true,
            )

            safeDelete(part)
        }
    }

    private fun validateFinalFile(
        dst: File,
        total: Long?,
        expectedSha256: String?,
    ) {
        if (
            total != null &&
            dst.length() != total
        ) {
            throw IOException(
                "Size mismatch: " +
                        "expected=$total got=${dst.length()}"
            )
        }

        if (
            expectedSha256 != null
        ) {
            val actual =
                sha256(dst)

            if (
                !actual.equals(
                    expectedSha256,
                    ignoreCase = true,
                )
            ) {
                safeDelete(dst)

                throw IOException(
                    "SHA-256 mismatch: " +
                            "expected=$expectedSha256 got=$actual"
                )
            }
        }
    }

    private fun sha256(
        file: File
    ): String {
        val digest =
            MessageDigest.getInstance(
                "SHA-256"
            )

        FileInputStream(
            file
        ).use { input ->

            val buffer =
                ByteArray(
                    128 * 1024
                )

            while (true) {
                val count =
                    input.read(
                        buffer
                    )

                if (
                    count <= 0
                ) {
                    break
                }

                digest.update(
                    buffer,
                    0,
                    count,
                )
            }
        }

        return digest
            .digest()
            .joinToString(
                ""
            ) {
                "%02x".format(
                    it
                )
            }
    }

    /* ========================================================================
     * HTTP helpers
     * ====================================================================== */

    private fun openConn(
        url: String,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        followRedirects: Boolean,
    ): HttpURLConnection {

        val parsed =
            URL(url)

        return (
                parsed.openConnection()
                        as HttpURLConnection
                ).apply {

                instanceFollowRedirects =
                    followRedirects

                requestMethod =
                    method

                connectTimeout =
                    connectTimeoutMs

                readTimeout =
                    readTimeoutMs

                useCaches =
                    false

                doInput =
                    true

                doOutput =
                    false
            }
    }

    /**
     * Do not force "Connection: close".
     *
     * HttpURLConnection may reuse HTTP connections internally. This is useful
     * for Range requests and avoids unnecessary TCP/TLS setup overhead.
     */
    private fun setCommonHeaders(
        conn: HttpURLConnection,
        url: String,
    ) {
        conn.setRequestProperty(
            "User-Agent",
            USER_AGENT,
        )

        conn.setRequestProperty(
            "Accept",
            "application/octet-stream",
        )

        conn.setRequestProperty(
            "Accept-Charset",
            "UTF-8",
        )

        conn.setRequestProperty(
            "Accept-Encoding",
            "identity",
        )

        if (
            isHfHost(url) &&
            !hfToken.isNullOrBlank()
        ) {
            conn.setRequestProperty(
                "Authorization",
                "Bearer $hfToken",
            )
        }
    }

    private fun readErrorSnippet(
        conn: HttpURLConnection,
        maxBytes: Int = 2048,
    ): String? {

        return try {
            val stream =
                conn.errorStream
                    ?: return null

            stream.use {
                val buffer =
                    ByteArray(
                        maxBytes
                    )

                val count =
                    it.read(
                        buffer
                    )

                if (
                    count <= 0
                ) {
                    return null
                }

                buffer
                    .copyOf(count)
                    .decodeToString()
                    .replace(
                        "\n",
                        " ",
                    )
                    .replace(
                        "\r",
                        " ",
                    )
                    .trim()
            }
        } catch (
            _: Throwable
        ) {
            null
        }
    }

    private fun readRetryAfterMs(
        conn: HttpURLConnection
    ): Long? {

        val value =
            conn.getHeaderField(
                "Retry-After"
            )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        value
            .toLongOrNull()
            ?.let { seconds ->
                return seconds
                    .coerceAtLeast(0L) *
                        1000L
            }

        return runCatching {
            val date =
                ZonedDateTime.parse(
                    value,
                    DateTimeFormatter
                        .RFC_1123_DATE_TIME,
                )

            val target =
                date.toInstant()
                    .toEpochMilli()

            val now =
                Instant.now()
                    .toEpochMilli()

            (
                    target -
                            now
                    ).coerceAtLeast(
                    0L
                )
        }.getOrNull()
    }

    private fun computeBackoffMs(
        attempt: Int,
        retryAfterMs: Long?,
    ): Long {

        retryAfterMs?.let {
            return it.coerceIn(
                500L,
                MAX_BACKOFF_MS,
            )
        }

        val base =
            (
                    BASE_BACKOFF_MS *
                            2.0.pow(
                                attempt.toDouble()
                            )
                    ).toLong()

        val capped =
            base.coerceAtMost(
                MAX_BACKOFF_MS
            )

        val jitter =
            Random.nextLong(
                0L,
                300L,
            )

        return (
                capped +
                        jitter
                ).coerceAtMost(
                MAX_BACKOFF_MS
            )
    }

    /* ========================================================================
     * Progress
     * ====================================================================== */

    /**
     * Throttle single-stream progress callbacks to avoid dispatching one UI
     * callback for every network buffer.
     */
    private class ProgressEmitter(
        private val total: Long?,
        initialBytes: Long,
        private val callback: (Long, Long?) -> Unit,
    ) {
        private var lastBytes =
            initialBytes

        private var lastNs =
            0L

        fun update(
            downloaded: Long
        ) {
            val now =
                System.nanoTime()

            val bytesChanged =
                kotlin.math.abs(
                    downloaded -
                            lastBytes
                ) >=
                        PROGRESS_EMIT_BYTES

            val timeChanged =
                now -
                        lastNs >=
                        PROGRESS_EMIT_INTERVAL_NS

            val complete =
                total != null &&
                        downloaded >= total

            if (
                bytesChanged ||
                timeChanged ||
                complete
            ) {
                callback(
                    downloaded,
                    total,
                )

                lastBytes =
                    downloaded

                lastNs =
                    now
            }
        }

        fun force(
            downloaded: Long
        ) {
            callback(
                downloaded,
                total,
            )

            lastBytes =
                downloaded

            lastNs =
                System.nanoTime()
        }
    }

    /* ========================================================================
     * Filesystem helpers
     * ====================================================================== */

    private fun checkFreeSpaceOrThrow(
        dir: File,
        required: Long,
    ) {
        val filesystem =
            StatFs(
                dir.absolutePath
            )

        val available =
            max(
                0L,
                filesystem.availableBytes,
            )

        if (
            available <
            required
        ) {
            throw IOException(
                "Not enough space: " +
                        "need=$required bytes, " +
                        "available=$available bytes"
            )
        }
    }

    private fun safeDelete(
        file: File
    ) {
        runCatching {
            if (
                file.exists() &&
                !file.delete()
            ) {
                logw(
                    "Failed to delete: ${file.absolutePath}"
                )
            }
        }
    }

    /* ========================================================================
     * Hugging Face
     * ====================================================================== */

    private fun isHfHost(
        url: String
    ): Boolean {

        val host =
            runCatching {
                URL(url).host ?: ""
            }.getOrElse {
                ""
            }

        return host ==
                "huggingface.co" ||
                host.endsWith(
                    ".huggingface.co"
                )
    }

    /* ========================================================================
     * Logging
     * ====================================================================== */

    private fun logd(
        message: String
    ) {
        if (
            debugLogs
        ) {
            Log.d(
                tag,
                message,
            )
        }
    }

    private fun logw(
        message: String
    ) {
        if (
            debugLogs
        ) {
            Log.w(
                tag,
                message,
            )
        }
    }

    private fun bytesToMiB(
        bytes: Long
    ): Long =
        bytes /
                (1024L * 1024L)

    /* ========================================================================
     * Exceptions
     * ====================================================================== */

    private class HttpExceptionWithRetryAfter(
        message: String,
        val retryAfterMs: Long?,
    ) : IOException(message)

    private class ParallelRangeUnsupportedException(
        message: String
    ) : IOException(message)

    companion object {
        private const val USER_AGENT =
            "AndroidSLM/1.1 (HttpUrlFileDownloader)"

        private const val MAX_REDIRECTS =
            10

        private const val MAX_UNAUTHORIZED_RETRIES =
            2

        private const val HTTP_RANGE_NOT_SATISFIABLE =
            416

        private const val HTTP_TOO_MANY_REQUESTS =
            429

        /*
         * Enable parallel download only for large files.
         */
        private const val PARALLEL_MIN_FILE_BYTES =
            256L * 1024L * 1024L

        /*
         * Four simultaneous HTTP Range requests.
         *
         * 4 is a conservative default for mobile devices and large HF models.
         * If testing shows the CDN and device can sustain more throughput,
         * this can later be tested with 6 or 8.
         */
        private const val PARALLEL_CHUNKS =
            4

        private const val PARALLEL_BUFFER_BYTES =
            2 * 1024 * 1024

        private const val MAX_IO_BUFFER_BYTES =
            2 * 1024 * 1024

        /*
         * Re-download 64 KiB when resuming a parallel chunk.
         */
        private const val PARALLEL_RESUME_OVERLAP_BYTES =
            64L * 1024L

        /*
         * Progress callback throttling.
         */
        private const val PROGRESS_EMIT_BYTES =
            2L * 1024L * 1024L

        private const val PROGRESS_EMIT_INTERVAL_NS =
            250_000_000L

        private const val PARALLEL_PROGRESS_BYTES =
            2L * 1024L * 1024L

        private const val PARALLEL_PROGRESS_INTERVAL_NS =
            250_000_000L

        private const val SPEED_LOG_INTERVAL_NS =
            2_000_000_000L

        /*
         * Keep extra free space for metadata, filesystem overhead and
         * temporary merge data.
         */
        private const val FREE_SPACE_MARGIN_BYTES =
            50L * 1024L * 1024L

        private const val BASE_BACKOFF_MS =
            500L

        private const val MAX_BACKOFF_MS =
            20_000L
    }
}