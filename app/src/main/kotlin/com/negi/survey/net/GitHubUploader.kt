/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: GitHubUploader.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.util.Base64
import android.util.Base64OutputStream
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Coroutine-based GitHub file uploader for JSON, text, and binary files.
 *
 * Key behavior:
 * - GET existing file SHA (if present) to decide create/update.
 * - PUT Base64 content to GitHub Contents API.
 * - Retry transient failures with backoff (429, 5xx, network IO).
 *
 * Notes:
 * - GitHub Contents API is not designed for extremely large binaries.
 * - Storing many WAV files inside a git repo can bloat clone/pull over time.
 * - Consider Releases assets or external object storage for long-term scaling.
 */
object GitHubUploader {

    private const val TAG = "GitHubUploader"

    private const val API_BASE = "https://api.github.com"
    private const val API_VERSION = "2022-11-28"
    private const val USER_AGENT = "AndroidSLM/1.0"

    private const val DEFAULT_MESSAGE = "Upload via SurveyNav"

    /**
     * Default guard for raw bytes before Base64 expansion.
     *
     * Base64 expands by ~4/3; request JSON adds overhead.
     *
     * For PCM_16BIT MONO WAV:
     *   bytes ≈ 44 + seconds * sampleRateHz * 2
     *
     * Examples (180s):
     * - 16kHz: 44 + 180*16000*2 = 5,760,044 bytes
     * - 48kHz: 44 + 180*48000*2 = 17,280,044 bytes
     */
    private const val DEFAULT_MAX_RAW_BYTES = 20_000_000

    /**
     * Default guard for the final JSON request size (rough).
     *
     * This is not an official GitHub limit; it is a safety guard to prevent
     * confusing failures on mobile networks and memory pressure on device.
     *
     * Roughly:
     *   requestBytes ≈ JSON_overhead + Base64(contentBytes)
     *   Base64(contentBytes) ≈ ceil(contentBytes/3)*4
     */
    private const val DEFAULT_MAX_REQUEST_BYTES = 32_000_000

    /**
     * Configuration container for GitHub upload operations.
     *
     * When using cfg overloads:
     *   pathPrefix / yyyy-MM-dd / relativePath
     */
    data class GitHubConfig(
        val owner: String,
        val repo: String,
        val token: String,
        val branch: String = "main",
        val pathPrefix: String = "",
        val maxRawBytesHint: Int = DEFAULT_MAX_RAW_BYTES,
        val maxRequestBytesHint: Int = DEFAULT_MAX_REQUEST_BYTES
    )

    /**
     * Result of a successful upload or update.
     */
    data class UploadResult(
        val fileUrl: String?,
        val commitSha: String?
    )

    // ---------------------------------------------------------------------
    // Public APIs — JSON/Text Upload
    // ---------------------------------------------------------------------

    /**
     * Upload a JSON or text file using [GitHubConfig] (includes date folder).
     */
    suspend fun uploadJson(
        cfg: GitHubConfig,
        relativePath: String,
        content: String,
        message: String = DEFAULT_MESSAGE,
        onProgress: (Int) -> Unit = {}
    ): UploadResult = uploadJson(
        owner = cfg.owner,
        repo = cfg.repo,
        branch = cfg.branch,
        path = buildPath(cfg.pathPrefix, relativePath),
        token = cfg.token,
        content = content,
        message = message,
        onProgress = onProgress,
        maxRawBytesHint = cfg.maxRawBytesHint,
        maxRequestBytesHint = cfg.maxRequestBytesHint
    )

    /**
     * Upload JSON/text to an explicit [path] (no auto date folder).
     */
    suspend fun uploadJson(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        token: String,
        content: String,
        message: String = DEFAULT_MESSAGE,
        onProgress: (Int) -> Unit = {},
        maxRawBytesHint: Int = DEFAULT_MAX_RAW_BYTES,
        maxRequestBytesHint: Int = DEFAULT_MAX_REQUEST_BYTES
    ): UploadResult = uploadBytes(
        owner = owner,
        repo = repo,
        branch = branch,
        path = path,
        token = token,
        contentBytes = content.toByteArray(Charsets.UTF_8),
        message = message,
        onProgress = onProgress,
        maxRawBytesHint = maxRawBytesHint,
        maxRequestBytesHint = maxRequestBytesHint
    )

    // ---------------------------------------------------------------------
    // Public APIs — Binary Upload (ByteArray)
    // ---------------------------------------------------------------------

    /**
     * Upload binary bytes using [GitHubConfig] (includes date folder).
     */
    suspend fun uploadFile(
        cfg: GitHubConfig,
        relativePath: String,
        bytes: ByteArray,
        message: String = DEFAULT_MESSAGE,
        onProgress: (Int) -> Unit = {}
    ): UploadResult = uploadFile(
        owner = cfg.owner,
        repo = cfg.repo,
        branch = cfg.branch,
        path = buildPath(cfg.pathPrefix, relativePath),
        token = cfg.token,
        bytes = bytes,
        message = message,
        onProgress = onProgress,
        maxRawBytesHint = cfg.maxRawBytesHint,
        maxRequestBytesHint = cfg.maxRequestBytesHint
    )

    /**
     * Upload binary bytes to an explicit [path] (no auto date folder).
     */
    suspend fun uploadFile(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        token: String,
        bytes: ByteArray,
        message: String = DEFAULT_MESSAGE,
        onProgress: (Int) -> Unit = {},
        maxRawBytesHint: Int = DEFAULT_MAX_RAW_BYTES,
        maxRequestBytesHint: Int = DEFAULT_MAX_REQUEST_BYTES
    ): UploadResult = uploadBytes(
        owner = owner,
        repo = repo,
        branch = branch,
        path = path,
        token = token,
        contentBytes = bytes,
        message = message,
        onProgress = onProgress,
        maxRawBytesHint = maxRawBytesHint,
        maxRequestBytesHint = maxRequestBytesHint
    )

    // ---------------------------------------------------------------------
    // Public APIs — Binary Upload (File streaming)
    // ---------------------------------------------------------------------

    /**
     * Upload a local file using [GitHubConfig] (includes date folder).
     *
     * This avoids loading the entire file into memory.
     */
    suspend fun uploadFile(
        cfg: GitHubConfig,
        relativePath: String,
        file: File,
        message: String = DEFAULT_MESSAGE,
        onProgress: (Int) -> Unit = {}
    ): UploadResult = uploadFile(
        owner = cfg.owner,
        repo = cfg.repo,
        branch = cfg.branch,
        path = buildPath(cfg.pathPrefix, relativePath),
        token = cfg.token,
        file = file,
        message = message,
        onProgress = onProgress,
        maxRawBytesHint = cfg.maxRawBytesHint,
        maxRequestBytesHint = cfg.maxRequestBytesHint
    )

    /**
     * Upload a local file to an explicit [path] (no auto date folder).
     *
     * This avoids loading the entire file into memory.
     */
    suspend fun uploadFile(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        token: String,
        file: File,
        message: String = DEFAULT_MESSAGE,
        onProgress: (Int) -> Unit = {},
        maxRawBytesHint: Int = DEFAULT_MAX_RAW_BYTES,
        maxRequestBytesHint: Int = DEFAULT_MAX_REQUEST_BYTES
    ): UploadResult {
        require(file.exists() && file.isFile) { "File does not exist: ${file.absolutePath}" }
        val rawSize = file.length().coerceAtLeast(0L)
        return uploadStream(
            owner = owner,
            repo = repo,
            branch = branch,
            path = path,
            token = token,
            rawSize = rawSize,
            openStream = { file.inputStream() },
            message = message,
            onProgress = onProgress,
            maxRawBytesHint = maxRawBytesHint,
            maxRequestBytesHint = maxRequestBytesHint
        )
    }

    // ---------------------------------------------------------------------
    // Shared Implementation
    // ---------------------------------------------------------------------

    /**
     * Shared implementation for ByteArray uploads (calls [uploadStream]).
     */
    private suspend fun uploadBytes(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        token: String,
        contentBytes: ByteArray,
        message: String,
        onProgress: (Int) -> Unit,
        maxRawBytesHint: Int,
        maxRequestBytesHint: Int
    ): UploadResult = uploadStream(
        owner = owner,
        repo = repo,
        branch = branch,
        path = path,
        token = token,
        rawSize = contentBytes.size.toLong(),
        openStream = { contentBytes.inputStream() },
        message = message,
        onProgress = onProgress,
        maxRawBytesHint = maxRawBytesHint,
        maxRequestBytesHint = maxRequestBytesHint
    )

    /**
     * Shared implementation for both JSON/text and binary uploads.
     *
     * Flow:
     * 1) Validate args and payload size (raw + estimated request size).
     * 2) GET existing SHA (if any).
     * 3) PUT Base64 payload to Contents API (streaming).
     * 4) Parse JSON response.
     */
    private suspend fun uploadStream(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        token: String,
        rawSize: Long,
        openStream: () -> InputStream,
        message: String,
        onProgress: (Int) -> Unit,
        maxRawBytesHint: Int,
        maxRequestBytesHint: Int
    ): UploadResult = withContext(Dispatchers.IO) {

        require(owner.isNotBlank()) { "GitHub owner cannot be blank." }
        require(repo.isNotBlank()) { "GitHub repo cannot be blank." }
        require(branch.isNotBlank()) { "GitHub branch cannot be blank." }
        require(path.isNotBlank()) { "GitHub path cannot be blank." }
        require(token.isNotBlank()) { "GitHub token cannot be blank." }

        if (rawSize > maxRawBytesHint.toLong()) {
            val msg =
                "Content too large for upload guard (size=$rawSize, limit=$maxRawBytesHint). " +
                        "Base64 expands ~4/3; request grows further due to JSON. " +
                        "For PCM_16BIT MONO WAV: bytes ≈ 44 + seconds * sampleRateHz * 2."
            throw IOException(msg)
        }

        val encodedPath = encodePath(path)

        Log.d(
            TAG,
            "uploadStream: owner=$owner repo=$repo branch=$branch path=$path size=$rawSize " +
                    "maxRawBytesHint=$maxRawBytesHint maxRequestBytesHint=$maxRequestBytesHint"
        )

        // Phase 1 — Lookup existing SHA (retry-capable, 404 allowed)
        onProgress(0)
        val existingSha = getExistingSha(owner, repo, branch, encodedPath, token)
        onProgress(10)

        // Phase 2 — Prepare streaming body pieces + size guard (estimate, fail-fast)
        val msgJson = JSONObject.quote(message.ifBlank { DEFAULT_MESSAGE })
        val branchJson = JSONObject.quote(branch)
        val shaJson = existingSha?.takeIf { it.isNotBlank() }?.let { JSONObject.quote(it) }

        val prefix = "{\"message\":$msgJson,\"branch\":$branchJson,\"content\":\""
        val suffix = if (shaJson != null) "\",\"sha\":$shaJson}" else "\"}"

        val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
        val suffixBytes = suffix.toByteArray(Charsets.UTF_8)

        val b64Len = estimateBase64Length(rawSize)
        val totalLenLong = prefixBytes.size.toLong() + b64Len + suffixBytes.size.toLong()

        if (totalLenLong > maxRequestBytesHint.toLong()) {
            val msg =
                "Request too large for upload guard " +
                        "(requestBytes~$totalLenLong, limit=$maxRequestBytesHint). " +
                        "ContentBytes=$rawSize; Base64 expands ~4/3. " +
                        "Consider stronger compression or alternate upload path for long-term scaling."
            throw IOException(msg)
        }

        val url = URL("$API_BASE/repos/$owner/$repo/contents/$encodedPath")

        val writeBody: (HttpURLConnection) -> Unit = { conn ->
            val totalLen = totalLenLong
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()

            conn.setFixedLengthStreamingMode(totalLen)

            conn.outputStream.use { os ->
                // Write JSON prefix
                os.write(prefixBytes)

                // Stream Base64 content without allocating a giant String/ByteArray
                val b64Out = Base64OutputStream(NonClosingOutputStream(os), Base64.NO_WRAP)

                var sent = 0L
                val buf = ByteArray(8 * 1024)

                openStream().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        b64Out.write(buf, 0, n)
                        sent += n.toLong()

                        if (rawSize > 0L) {
                            val pct = 10 + ((sent.toDouble() / rawSize.toDouble()) * 80.0).toInt()
                            onProgress(min(90, pct))
                        }
                    }
                }

                b64Out.close() // flush base64 padding without closing underlying os

                // Write JSON suffix
                os.write(suffixBytes)
                os.flush()
            }
        }

        // Phase 3 — Execute request with retry/backoff
        val response = executeWithRetry(
            method = "PUT",
            url = url,
            token = token,
            writeBody = writeBody
        )
        onProgress(95)

        val json = try {
            JSONObject(response.body)
        } catch (e: JSONException) {
            throw IOException("Malformed JSON from GitHub: ${e.message}", e)
        }
        onProgress(100)

        val fileUrl =
            json.optJSONObject("content")
                ?.optString("html_url")
                ?.takeIf { it.isNotBlank() }

        val commitSha =
            json.optJSONObject("commit")
                ?.optString("sha")
                ?.takeIf { it.isNotBlank() }

        Log.d(TAG, "uploadStream: done url=$fileUrl sha=$commitSha")
        UploadResult(fileUrl, commitSha)
    }

    // ---------------------------------------------------------------------
    // Retry/HTTP Internals
    // ---------------------------------------------------------------------

    private data class HttpResponse(
        val code: Int,
        val body: String,
        val headers: Map<String, List<String>>
    )

    private class TransientHttpException(
        val code: Int,
        val body: String,
        val retryAfterSeconds: Long?
    ) : IOException()

    private class HttpFailureException(val code: Int, val body: String) :
        IOException("GitHub request failed ($code): ${body.take(256)}")

    /**
     * Execute an HTTP request with retry/backoff.
     *
     * Retries:
     * - 429
     * - 5xx
     * - 403 with Retry-After (secondary rate limit style)
     * - IOException network errors
     *
     * Honors Retry-After if present.
     *
     * @param allowNon2xxCodes Treat these codes as non-fatal and return them as a normal response.
     *                         Use this to allow 404 for "file not found" lookups.
     */
    private suspend fun executeWithRetry(
        method: String,
        url: URL,
        token: String,
        writeBody: ((HttpURLConnection) -> Unit)?,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 30_000,
        maxAttempts: Int = 3,
        allowNon2xxCodes: Set<Int> = emptySet()
    ): HttpResponse {
        var attempt = 0
        var lastError: IOException? = null

        while (attempt < maxAttempts) {
            attempt++

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                doInput = true
                doOutput = (writeBody != null)

                setRequestProperty("Authorization", "Bearer ${token.trim()}")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", API_VERSION)
                setRequestProperty("User-Agent", USER_AGENT)

                if (writeBody != null) {
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }

                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
            }

            try {
                // For PUT/POST-like calls, caller writes the body.
                writeBody?.invoke(conn)

                val code = conn.responseCode
                val headers = conn.headerFields.filterKeys { it != null }

                if (code in 200..299 || allowNon2xxCodes.contains(code)) {
                    val bodyStream =
                        if (code in 200..299) conn.inputStream else conn.errorStream
                    val body = bodyStream?.use(::readAll).orEmpty()
                    return HttpResponse(code, body, headers)
                }

                val errBody = conn.errorStream?.use(::readAll).orEmpty()

                val retryAfter = parseRetryAfterSeconds(headers)
                val isTransient =
                    code == 429 || code in 500..599 || (code == 403 && retryAfter != null)

                if (isTransient) {
                    throw TransientHttpException(code, errBody, retryAfter)
                }

                throw HttpFailureException(code, errBody)

            } catch (e: TransientHttpException) {
                lastError = IOException("Transient HTTP ${e.code}: ${e.body.take(200)}", e)
                if (attempt >= maxAttempts) throw lastError

                val backoff =
                    e.retryAfterSeconds?.times(1000L) ?: (500L shl (attempt - 1))
                delay(backoff)

            } catch (e: IOException) {
                lastError = e
                if (attempt >= maxAttempts) throw e

                val backoff = 500L shl (attempt - 1)
                delay(backoff)

            } finally {
                conn.disconnect()
            }
        }

        throw lastError ?: IOException("HTTP failed after $maxAttempts attempts.")
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Encode a repo path safely for the Contents API URL.
     *
     * This encodes each path segment independently.
     */
    private fun encodePath(path: String): String =
        path.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { segment ->
                URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }

    /**
     * Build a dated GitHub path:
     *   prefix / yyyy-MM-dd / relative
     *
     * Uses UTC date to keep paths stable across timezones.
     */
    private fun buildPath(prefix: String, relative: String): String {
        val dateSegment = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val segments = listOf(prefix.trim('/'), dateSegment, relative.trim('/'))
            .filter { it.isNotBlank() }
        return segments.joinToString("/")
    }

    /**
     * Parse Retry-After seconds with case-insensitive header matching.
     */
    private fun parseRetryAfterSeconds(headers: Map<String, List<String>>): Long? {
        val key = headers.keys.firstOrNull { it.equals("Retry-After", ignoreCase = true) }
        return key?.let { headers[it]?.firstOrNull()?.toLongOrNull() }
    }

    /**
     * Read an entire input stream as UTF-8 text.
     */
    private fun readAll(stream: InputStream): String =
        stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)

    /**
     * Look up an existing file SHA if the path already exists on the target branch.
     *
     * Returns null when:
     * - File does not exist (404)
     *
     * Throws when:
     * - Non-404 non-2xx errors occur (rate limit, auth issues, 5xx, etc.)
     * - Network errors persist beyond retries
     */
    private suspend fun getExistingSha(
        owner: String,
        repo: String,
        branch: String,
        encodedPath: String,
        token: String
    ): String? {
        val refEncoded = URLEncoder.encode(branch.trim(), "UTF-8").replace("+", "%20")
        val url = URL("$API_BASE/repos/$owner/$repo/contents/$encodedPath?ref=$refEncoded")

        val resp = executeWithRetry(
            method = "GET",
            url = url,
            token = token,
            writeBody = null,
            connectTimeoutMs = 15_000,
            readTimeoutMs = 20_000,
            maxAttempts = 3,
            allowNon2xxCodes = setOf(404)
        )

        if (resp.code == 404) return null
        if (resp.code !in 200..299) return null

        return runCatching {
            JSONObject(resp.body).optString("sha").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /**
     * Estimate Base64 output length for a given raw byte size.
     *
     * Base64 length = ceil(n/3)*4
     */
    private fun estimateBase64Length(rawBytes: Long): Long {
        val n = rawBytes.coerceAtLeast(0L)
        return ((n + 2L) / 3L) * 4L
    }

    /**
     * OutputStream wrapper that ignores close() to allow Base64OutputStream to finalize
     * without closing the underlying network stream.
     */
    private class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
        override fun write(b: Int) = delegate.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
        override fun flush() = delegate.flush()
        override fun close() {
            // Intentionally no-op
        }
    }
}
