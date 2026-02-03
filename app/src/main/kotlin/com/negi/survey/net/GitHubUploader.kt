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
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 * - GitHub Contents API is not designed for large binaries.
 * - Prefer gzip + tail for logs. Use Releases/Git LFS for big files.
 */
object GitHubUploader {

    private const val TAG = "GitHubUploader"

    private const val API_BASE = "https://api.github.com"
    private const val API_VERSION = "2022-11-28"
    private const val USER_AGENT = "AndroidSLM/1.0"

    private const val DEFAULT_MESSAGE = "Upload via SurveyNav"

    /**
     * Conservative guard for raw bytes before Base64 expansion.
     *
     * Base64 expands by ~4/3; request JSON adds overhead.
     */
    private const val MAX_RAW_BYTES = 850_000

    /**
     * Conservative guard for final JSON request size (rough).
     *
     * This is not an official GitHub limit; it is a safety guard to prevent
     * confusing failures on mobile networks and Contents API.
     */
    private const val MAX_REQUEST_BYTES = 1_250_000

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
        val pathPrefix: String = ""
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
        onProgress = onProgress
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
        onProgress: (Int) -> Unit = {}
    ): UploadResult = uploadBytes(
        owner = owner,
        repo = repo,
        branch = branch,
        path = path,
        token = token,
        contentBytes = content.toByteArray(Charsets.UTF_8),
        message = message,
        onProgress = onProgress
    )

    // ---------------------------------------------------------------------
    // Public APIs — Binary Upload
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
        onProgress = onProgress
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
        onProgress: (Int) -> Unit = {}
    ): UploadResult = uploadBytes(
        owner = owner,
        repo = repo,
        branch = branch,
        path = path,
        token = token,
        contentBytes = bytes,
        message = message,
        onProgress = onProgress
    )

    // ---------------------------------------------------------------------
    // Shared Implementation
    // ---------------------------------------------------------------------

    /**
     * Shared implementation for both JSON/text and binary uploads.
     *
     * Flow:
     * 1) Validate args and payload size (raw + estimated request size).
     * 2) GET existing SHA (if any).
     * 3) PUT Base64 payload to Contents API.
     * 4) Parse JSON response.
     */
    private suspend fun uploadBytes(
        owner: String,
        repo: String,
        branch: String,
        path: String,
        token: String,
        contentBytes: ByteArray,
        message: String,
        onProgress: (Int) -> Unit
    ): UploadResult = withContext(Dispatchers.IO) {

        require(owner.isNotBlank()) { "GitHub owner cannot be blank." }
        require(repo.isNotBlank()) { "GitHub repo cannot be blank." }
        require(branch.isNotBlank()) { "GitHub branch cannot be blank." }
        require(path.isNotBlank()) { "GitHub path cannot be blank." }
        require(token.isNotBlank()) { "GitHub token cannot be blank." }

        if (contentBytes.size > MAX_RAW_BYTES) {
            throw IOException(
                "Content too large for Contents API guard " +
                        "(size=${contentBytes.size} bytes, limit=$MAX_RAW_BYTES)."
            )
        }

        val encodedPath = encodePath(path)

        Log.d(
            TAG,
            "uploadBytes: owner=$owner repo=$repo branch=$branch path=$path size=${contentBytes.size}"
        )

        // Phase 1 — Lookup existing SHA
        onProgress(0)
        val existingSha = getExistingSha(owner, repo, branch, encodedPath, token)
        onProgress(10)

        // Phase 2 — Prepare JSON payload
        val b64 = Base64.encodeToString(contentBytes, Base64.NO_WRAP)

        val payload = JSONObject().apply {
            put("message", message.ifBlank { DEFAULT_MESSAGE })
            put("branch", branch)
            put("content", b64)
            if (!existingSha.isNullOrBlank()) put("sha", existingSha)
        }.toString()

        val requestBytes = payload.toByteArray(Charsets.UTF_8)
        if (requestBytes.size > MAX_REQUEST_BYTES) {
            throw IOException(
                "Request too large for Contents API guard " +
                        "(requestBytes=${requestBytes.size}, limit=$MAX_REQUEST_BYTES). " +
                        "Consider stronger gzip/tail or alternative upload path."
            )
        }

        val url = URL("$API_BASE/repos/$owner/$repo/contents/$encodedPath")
        val total = requestBytes.size

        val writeBody: (HttpURLConnection) -> Unit = { conn ->
            conn.setFixedLengthStreamingMode(total)
            conn.outputStream.use { os: OutputStream ->
                val chunk = 8 * 1024
                var off = 0
                while (off < total) {
                    val len = min(chunk, total - off)
                    os.write(requestBytes, off, len)
                    off += len

                    val pct = 10 + ((off.toDouble() / total) * 80.0).toInt()
                    onProgress(min(90, pct))
                }
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

        Log.d(TAG, "uploadBytes: done url=$fileUrl sha=$commitSha")
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
     * - IOException network errors
     *
     * Honors Retry-After if present.
     */
    private suspend fun executeWithRetry(
        method: String,
        url: URL,
        token: String,
        writeBody: (HttpURLConnection) -> Unit,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 30_000,
        maxAttempts: Int = 3
    ): HttpResponse {
        var attempt = 0
        var lastError: IOException? = null

        while (attempt < maxAttempts) {
            attempt++

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                doOutput = true
                doInput = true

                setRequestProperty("Authorization", "Bearer ${token.trim()}")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-GitHub-Api-Version", API_VERSION)
                setRequestProperty("User-Agent", USER_AGENT)

                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
            }

            try {
                writeBody(conn)

                val code = conn.responseCode
                val headers = conn.headerFields.filterKeys { it != null }

                if (code in 200..299) {
                    val body = conn.inputStream.use(::readAll)
                    return HttpResponse(code, body, headers)
                }

                val errBody = conn.errorStream?.use(::readAll).orEmpty()

                if (code == 429 || code in 500..599) {
                    val retryAfter = parseRetryAfterSeconds(headers)
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
     */
    private fun buildPath(prefix: String, relative: String): String {
        val dateSegment = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
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
     * - File does not exist
     * - API returns non-200
     * - Parsing fails
     */
    private fun getExistingSha(
        owner: String,
        repo: String,
        branch: String,
        encodedPath: String,
        token: String
    ): String? {
        val refEncoded = URLEncoder.encode(branch.trim(), "UTF-8").replace("+", "%20")
        val url = URL("$API_BASE/repos/$owner/$repo/contents/$encodedPath?ref=$refEncoded")

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            doInput = true

            setRequestProperty("Authorization", "Bearer ${token.trim()}")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            setRequestProperty("User-Agent", USER_AGENT)

            connectTimeout = 15_000
            readTimeout = 20_000
        }

        return try {
            if (conn.responseCode == 200) {
                val body = conn.inputStream.use(::readAll)
                JSONObject(body).optString("sha").takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
