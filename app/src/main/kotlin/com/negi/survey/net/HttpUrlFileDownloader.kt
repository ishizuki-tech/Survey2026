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
 *  progress tracking, and Hugging Face token support.
 *
 *  Features:
 *   • HEAD probe with manual redirects and ETag/Last-Modified validators
 *   • Safe resume using Range/If-Range with `.part` and `.meta` files
 *   • Exponential backoff retry with Retry-After compliance
 *   • SHA-256 integrity verification and free-space check
 *   • Progress callback suitable for Android foreground workers
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.pow

/**
 * Coroutine-safe downloader for large, resumable HTTP transfers.
 *
 * This class implements reliable file downloads with integrity validation
 * and resumable partial transfers, making it suitable for ML model downloads
 * or offline asset synchronization.
 *
 * @property hfToken Optional Hugging Face token ("hf_xxx"), applied only for `huggingface.co` hosts.
 * @property debugLogs Enables verbose diagnostic logs.
 */
class HttpUrlFileDownloader(
    private val hfToken: String? = null,
    private val debugLogs: Boolean = true
) {
    private val tag = "HttpUrlFileDl"

    /**
     * Downloads a file from the given [url] into [dst], resuming if partially complete.
     *
     * Performs HEAD probe, progress updates, SHA-256 verification, and
     * exponential retry on transient errors.
     *
     * @param url Remote resource URL.
     * @param dst Target destination file.
     * @param onProgress Called periodically with downloaded bytes and total length.
     * @param expectedSha256 Optional expected SHA-256 hash for final validation.
     * @param connectTimeoutMs Timeout for connection setup.
     * @param firstByteTimeoutMs Timeout for HEAD request read.
     * @param stallTimeoutMs Timeout for read stalls during transfer.
     * @param ioBufferBytes Buffer size in bytes (default: 1 MiB).
     * @param maxRetries Maximum number of retry attempts.
     * @throws IOException When the operation fails permanently.
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
        maxRetries: Int = 3
    ) = withContext(Dispatchers.IO) {
        val parent = dst.absoluteFile.parentFile
            ?: throw IOException("Invalid destination: ${dst.absolutePath}")
        parent.mkdirs()

        val part = File(parent, dst.name + ".part")
        val meta = MetaFile(part)

        // Fast path: skip if already complete and valid.
        runCatching { headProbe(url, connectTimeoutMs, firstByteTimeoutMs).total }.getOrNull()
            ?.let { headLen ->
                val okSize = dst.exists() && dst.length() == headLen
                val okHash = expectedSha256 == null || sha256(dst).equals(expectedSha256, true)
                if (okSize && okHash) {
                    onProgress(dst.length(), dst.length())
                    logd("Already complete, skipping download.")
                    return@withContext
                }
            }

        var attempt = 0
        var lastError: Throwable? = null

        while (attempt < maxRetries) {
            try {
                coroutineContext.ensureActive()

                val probe = headProbe(url, connectTimeoutMs, firstByteTimeoutMs)
                val total = probe.total ?: throw IOException("Missing Content-Length.")
                var finalUrl = probe.finalUrl

                val already = if (part.exists()) part.length() else 0L

                // Do not hard-fail only on missing Accept-Ranges header.
                // Some servers support Range but do not advertise it.
                if (!probe.acceptRanges && already > 0L) {
                    logw("Accept-Ranges missing, but partial exists; will attempt resume anyway.")
                }

                checkFreeSpaceOrThrow(parent, max(0L, (total - already)) + 50L * 1024 * 1024)

                meta.write(Meta(probe.etag, probe.lastModified, total))

                var resumeFrom = already.coerceIn(0, total)
                var triesOnThisStream = 0

                STREAM@ while (true) {
                    if (triesOnThisStream > 0) {
                        val refreshed = headProbe(url, connectTimeoutMs, firstByteTimeoutMs)
                        if (refreshed.total != null && refreshed.total != total) {
                            throw IOException("Remote size changed (old=$total new=${refreshed.total})")
                        }
                        finalUrl = refreshed.finalUrl
                    }

                    val conn = openConn(finalUrl, "GET", connectTimeoutMs, stallTimeoutMs, true)
                    try {
                        setCommonHeaders(conn, finalUrl)

                        if (resumeFrom > 0) {
                            conn.setRequestProperty("Range", "bytes=$resumeFrom-")
                            meta.read()?.let { m ->
                                val ifRange = m.etag ?: m.lastModified
                                if (ifRange != null) conn.setRequestProperty("If-Range", ifRange)
                            }
                        }

                        val code = conn.responseCode

                        when (code) {
                            HttpURLConnection.HTTP_UNAUTHORIZED,
                            HttpURLConnection.HTTP_FORBIDDEN -> {
                                // Token missing/expired or signed URL rotated.
                                logw("GET $code: may need refreshed access. Retrying HEAD.")
                                triesOnThisStream++
                                resumeFrom = part.length().coerceIn(0, total)
                                continue@STREAM
                            }

                            HttpURLConnection.HTTP_OK -> if (resumeFrom > 0) {
                                // Server ignored Range; restart cleanly.
                                logw("Server ignored Range, restarting from 0.")
                                part.delete()
                                meta.delete()
                                resumeFrom = 0L
                                if (++triesOnThisStream <= 3) continue@STREAM
                                throw IOException("Server ignored Range repeatedly.")
                            }

                            416 -> {
                                val done = handleRangeNotSatisfiable(
                                    dst = dst,
                                    part = part,
                                    meta = meta,
                                    total = total,
                                    expectedSha256 = expectedSha256,
                                    onProgress = onProgress
                                )
                                if (done) {
                                    return@withContext
                                } else {
                                    // We deleted .part inside handler; restart cleanly.
                                    resumeFrom = 0L
                                    if (++triesOnThisStream <= 3) continue@STREAM
                                    throw IOException("416 reconciliation failed repeatedly.")
                                }
                            }
                        }

                        if (code !in listOf(
                                HttpURLConnection.HTTP_OK,
                                HttpURLConnection.HTTP_PARTIAL
                            )
                        ) {
                            val snippet = readErrorSnippet(conn)
                            throw IOException("GET HTTP $code${snippet?.let { ": $it" } ?: ""}")
                        }

                        val bufSize = ioBufferBytes.coerceIn(64 * 1024, 2 * 1024 * 1024)
                        var downloaded = resumeFrom

                        onProgress(downloaded, total)

                        try {
                            conn.inputStream.use { input ->
                                FileOutputStream(part, resumeFrom > 0).use { fos ->
                                    BufferedOutputStream(fos, bufSize).use { out ->
                                        val buf = ByteArray(bufSize)
                                        while (true) {
                                            coroutineContext.ensureActive()
                                            val n = input.read(buf)
                                            if (n == -1) break
                                            out.write(buf, 0, n)
                                            downloaded += n
                                            onProgress(downloaded, total)
                                        }
                                        out.flush()
                                    }
                                }
                            }
                        } catch (t: SocketTimeoutException) {
                            logw("Stall timeout; resuming.")
                            resumeFrom = part.length().coerceIn(0, total)
                            if (++triesOnThisStream <= 3) continue@STREAM
                            throw t
                        } catch (t: IOException) {
                            logw("Stream error: ${t.message}")
                            resumeFrom = part.length().coerceIn(0, total)
                            if (++triesOnThisStream <= 3) continue@STREAM
                            throw t
                        }

                        // Promote .part → final.
                        if (dst.exists()) dst.delete()
                        if (!part.renameTo(dst)) {
                            part.copyTo(dst, overwrite = true)
                            part.delete()
                        }
                        meta.delete()

                        // Final validations.
                        if (dst.length() != total) {
                            throw IOException("Size mismatch: expected=$total got=${dst.length()}")
                        }
                        if (expectedSha256 != null) {
                            val got = sha256(dst)
                            if (!got.equals(expectedSha256, true)) {
                                dst.delete()
                                throw IOException("SHA-256 mismatch: expected=$expectedSha256 got=$got")
                            }
                        }

                        onProgress(total, total)
                        logd("Saved ${dst.name} (${dst.length()} bytes)")
                        return@withContext
                    } finally {
                        conn.disconnect()
                    }
                }
            } catch (t: Throwable) {
                lastError = t
                logw("Attempt ${attempt + 1} failed: ${t::class.simpleName}: ${t.message}")

                val retryAfterMs = (t as? HttpExceptionWithRetryAfter)?.retryAfterMs

                if (attempt < maxRetries - 1) {
                    val backoffMs =
                        retryAfterMs ?: (500.0 * 2.0.pow(attempt.toDouble())).toLong()
                    logw("Retrying in ${backoffMs}ms …")
                    delay(backoffMs)
                }
            }
            attempt++
        }

        throw IOException(
            "Download failed after $maxRetries attempts: ${lastError?.message}",
            lastError
        )
    }

    // ----------------------------------------------------------
    // HEAD probe (resolves redirects and captures validators)
    // ----------------------------------------------------------

    private data class Probe(
        val total: Long?,
        val acceptRanges: Boolean,
        val etag: String?,
        val lastModified: String?,
        val finalUrl: String
    )

    private fun headProbe(srcUrl: String, connectTimeoutMs: Int, readTimeoutMs: Int): Probe {
        var current = srcUrl
        var hops = 0
        var conn: HttpURLConnection? = null

        while (true) {
            conn?.disconnect()
            conn = openConn(current, "HEAD", connectTimeoutMs, readTimeoutMs, false)
            setCommonHeaders(conn, current)
            conn.connect()

            val code = conn.responseCode

            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                    ?: throw IOException("Redirect without Location.")
                current = URL(URL(current), loc).toString()
                if (++hops > 10) throw IOException("Too many redirects.")
                continue
            }

            if (code == 429 || code == 503) {
                throw HttpExceptionWithRetryAfter("HEAD HTTP $code", readRetryAfterMs(conn))
            }

            if (code !in 200..299) {
                throw IOException("HEAD HTTP $code${readErrorSnippet(conn)?.let { ": $it" } ?: ""}")
            }

            val total = conn.getHeaderFieldLong("Content-Length", -1L).takeIf { it >= 0 }
            val acceptRanges =
                (conn.getHeaderField("Accept-Ranges") ?: "").contains("bytes", true)

            val etag = conn.getHeaderField("ETag")
            val lastMod = conn.getHeaderField("Last-Modified")
            val finalUrl = conn.url.toString()

            return Probe(total, acceptRanges, etag, lastMod, finalUrl)
        }
    }

    // ----------------------------------------------------------
    // Meta file
    // ----------------------------------------------------------

    private data class Meta(val etag: String?, val lastModified: String?, val total: Long?)

    private class MetaFile(private val part: File) {
        private val file = File(part.parentFile, part.name + ".meta")

        fun read(): Meta? = runCatching {
            if (!file.exists()) return null
            val map = file.readLines().mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
            }.toMap()
            Meta(map["etag"], map["lastModified"], map["total"]?.toLongOrNull())
        }.getOrNull()

        fun write(meta: Meta) {
            runCatching {
                file.writeText(
                    buildString {
                        meta.etag?.let { append("etag=$it\n") }
                        meta.lastModified?.let { append("lastModified=$it\n") }
                        meta.total?.let { append("total=$it\n") }
                    }
                )
            }
        }

        fun delete() {
            runCatching { if (file.exists()) file.delete() }
        }
    }

    // ----------------------------------------------------------
    // 416 reconciliation
    // ----------------------------------------------------------

    /**
     * Handle HTTP 416 by reconciling local partial state.
     *
     * @return true if the download can be considered complete and promoted to [dst].
     *         false if caller should restart from 0.
     */
    private fun handleRangeNotSatisfiable(
        dst: File,
        part: File,
        meta: MetaFile,
        total: Long,
        expectedSha256: String?,
        onProgress: (Long, Long?) -> Unit
    ): Boolean {
        val onDisk = part.length()

        if (onDisk == total) {
            if (dst.exists()) dst.delete()
            if (!part.renameTo(dst)) {
                part.copyTo(dst, overwrite = true)
                part.delete()
            }
            meta.delete()

            if (expectedSha256 != null) {
                val got = sha256(dst)
                if (!got.equals(expectedSha256, true)) {
                    dst.delete()
                    throw IOException("SHA mismatch after 416 reconciliation.")
                }
            }

            onProgress(total, total)
            logd("Completed via 416 reconciliation.")
            return true
        }

        // Partial is inconsistent; reset local state.
        logw("416 mismatch (part=$onDisk, total=$total), restarting from 0.")
        part.delete()
        meta.delete()
        return false
    }

    // ----------------------------------------------------------
    // Utility functions
    // ----------------------------------------------------------

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { fis ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun openConn(
        url: String,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        followRedirects: Boolean
    ): HttpURLConnection {
        val u = URL(url)
        return (u.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = followRedirects
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            doInput = true
            doOutput = false
        }
    }

    private fun setCommonHeaders(conn: HttpURLConnection, url: String) {
        conn.setRequestProperty("User-Agent", "AndroidSLM/1.0 (HttpUrlFileDownloader)")
        conn.setRequestProperty("Accept", "application/octet-stream")
        conn.setRequestProperty("Accept-Charset", "UTF-8")
        conn.setRequestProperty("Accept-Encoding", "identity")

        if (isHfHost(url) && !hfToken.isNullOrBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $hfToken")
        }
    }

    private fun readErrorSnippet(conn: HttpURLConnection, maxBytes: Int = 2048): String? {
        return try {
            val es = conn.errorStream ?: return null
            es.use { stream ->
                val buf = ByteArray(maxBytes)
                val n = stream.read(buf)
                if (n <= 0) return null
                buf.copyOf(n).decodeToString()
                    .replace("\n", " ")
                    .replace("\r", " ")
                    .trim()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readRetryAfterMs(conn: HttpURLConnection): Long? =
        conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000)

    private fun checkFreeSpaceOrThrow(dir: File, required: Long) {
        val fs = StatFs(dir.absolutePath)
        val avail = max(0L, fs.availableBytes)
        if (avail < required) {
            throw IOException("Not enough space: need ${required}B, available ${avail}B")
        }
    }

    private fun isHfHost(u: String): Boolean {
        val host = runCatching { URL(u).host ?: "" }.getOrElse { "" }
        return host == "huggingface.co" || host.endsWith(".huggingface.co")
    }

    private fun logd(msg: String) {
        if (debugLogs) Log.d(tag, msg)
    }

    private fun logw(msg: String) {
        if (debugLogs) Log.w(tag, msg)
    }

    private class HttpExceptionWithRetryAfter(
        message: String,
        val retryAfterMs: Long?
    ) : IOException(message)
}
