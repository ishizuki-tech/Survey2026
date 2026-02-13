/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SupabaseUploader.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.util.Log
import java.io.BufferedInputStream
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
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Coroutine-based Supabase Storage uploader using HttpURLConnection.
 *
 * Key behavior:
 * - Streams payload (avoids loading huge binaries into memory).
 * - Retries transient failures (429, 5xx, IO) with body replay support.
 * - Supports anon key and optional user JWT override.
 *
 * Supabase Storage REST endpoint:
 *   POST/PUT {SUPABASE_URL}/storage/v1/object/{bucket}/{path}
 *
 * Required headers (typical):
 * - apikey: <anon_key>
 * - Authorization: Bearer <token>  (anon_key or user_jwt)
 */
object SupabaseUploader {

    private const val TAG = "SupabaseUploader"

    private const val API_PATH = "/storage/v1/object"
    private const val USER_AGENT = "AndroidSLM/1.0 (SupabaseUploader)"

    /**
     * Configuration container for Supabase upload operations.
     */
    data class SupabaseConfig(
        val supabaseUrl: String,
        val anonKey: String,
        val bucket: String,
        val pathPrefix: String = "surveyapp",
        val maxRawBytesHint: Long = 20_000_000L,
        val connectTimeoutMs: Int = 20_000,
        val readTimeoutMs: Int = 45_000
    )

    /**
     * Result of a successful upload.
     *
     * @param objectPath Path inside bucket (decoded, slash-separated).
     * @param publicUrl Convenience URL for public buckets (may 403 if bucket/policy is private).
     * @param etag ETag from response headers when available.
     * @param requestId Request identifier when available.
     */
    data class UploadResult(
        val objectPath: String,
        val publicUrl: String?,
        val etag: String?,
        val requestId: String?
    )

    private interface BodySource {
        val contentLength: Long
        fun open(): InputStream
    }

    /**
     * Upload an existing local file by streaming.
     *
     * @param cfg Supabase config.
     * @param objectPath Object path inside bucket (no leading slash). (Prefix is applied automatically.)
     * @param file Local file to upload.
     * @param contentType MIME type.
     * @param upsert If true, sets x-upsert=true (may require UPDATE RLS policy).
     * @param tokenOverride Optional user JWT. If null, uses anonKey as bearer.
     * @param onProgress 0..100 progress callback.
     */
    suspend fun uploadFile(
        cfg: SupabaseConfig,
        objectPath: String,
        file: File,
        contentType: String = "application/octet-stream",
        upsert: Boolean = false,
        tokenOverride: String? = null,
        onProgress: (Int) -> Unit = {}
    ): UploadResult = withContext(Dispatchers.IO) {

        require(cfg.supabaseUrl.isNotBlank()) { "Supabase URL cannot be blank." }
        require(cfg.anonKey.isNotBlank()) { "Supabase anon key cannot be blank." }
        require(cfg.bucket.isNotBlank()) { "Supabase bucket cannot be blank." }
        require(objectPath.isNotBlank()) { "Supabase objectPath cannot be blank." }
        require(file.exists() && file.isFile) { "File does not exist: ${file.absolutePath}" }

        val total = file.length()
        if (total <= 0L) throw IOException("File is empty: ${file.absolutePath}")
        if (total > cfg.maxRawBytesHint) {
            throw IOException("File too large for upload guard (size=$total, limit=${cfg.maxRawBytesHint}).")
        }

        val source = object : BodySource {
            override val contentLength: Long = total
            override fun open(): InputStream {
                return BufferedInputStream(file.inputStream(), 256 * 1024)
            }
        }

        uploadBodyInternal(
            cfg = cfg,
            objectPath = objectPath,
            contentType = contentType,
            upsert = upsert,
            tokenOverride = tokenOverride,
            body = source,
            onProgress = onProgress
        )
    }

    /**
     * Upload bytes already in memory (useful for gzipped logs).
     */
    suspend fun uploadBytes(
        cfg: SupabaseConfig,
        objectPath: String,
        bytes: ByteArray,
        contentType: String = "application/octet-stream",
        upsert: Boolean = false,
        tokenOverride: String? = null,
        onProgress: (Int) -> Unit = {}
    ): UploadResult = withContext(Dispatchers.IO) {

        val total = bytes.size.toLong()
        if (total <= 0L) throw IOException("Bytes are empty.")
        if (total > cfg.maxRawBytesHint) {
            throw IOException("Content too large for upload guard (size=$total, limit=${cfg.maxRawBytesHint}).")
        }

        val source = object : BodySource {
            override val contentLength: Long = total
            override fun open(): InputStream = bytes.inputStream()
        }

        uploadBodyInternal(
            cfg = cfg,
            objectPath = objectPath,
            contentType = contentType,
            upsert = upsert,
            tokenOverride = tokenOverride,
            body = source,
            onProgress = onProgress
        )
    }

    /**
     * Build date-based path:
     *   prefix / yyyy-MM-dd / relativeName
     */
    fun buildDatedObjectPath(prefix: String, relativeName: String): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return listOf(prefix.trim('/'), date, relativeName.trim('/'))
            .filter { it.isNotBlank() }
            .joinToString("/")
    }

    /**
     * Encode each segment for URL path usage.
     */
    fun encodePath(path: String): String =
        path.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { seg ->
                URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
            }

    private suspend fun uploadBodyInternal(
        cfg: SupabaseConfig,
        objectPath: String,
        contentType: String,
        upsert: Boolean,
        tokenOverride: String?,
        body: BodySource,
        onProgress: (Int) -> Unit
    ): UploadResult {

        val baseUrl = cfg.supabaseUrl.trimEnd('/')
        val resolvedObjectPath = applyPrefixIfNeeded(cfg.pathPrefix, objectPath)
        val encodedObjectPath = encodePath(resolvedObjectPath)

        val url = URL("$baseUrl$API_PATH/${cfg.bucket}/$encodedObjectPath")
        Log.d(
            TAG,
            "upload: url=$url len=${body.contentLength} upsert=$upsert prefix=${cfg.pathPrefix} objectPath=$resolvedObjectPath"
        )

        // Try POST first, and if 405, retry with PUT.
        val methods = listOf("POST", "PUT")

        var lastError: IOException? = null
        for (m in methods) {
            try {
                return executeWithRetry(
                    method = m,
                    url = url,
                    cfg = cfg,
                    contentType = contentType,
                    contentLength = body.contentLength,
                    upsert = upsert,
                    tokenOverride = tokenOverride,
                    body = body,
                    onProgress = onProgress,
                    resolvedObjectPath = resolvedObjectPath
                )
            } catch (e: MethodNotAllowedException) {
                lastError = e
                Log.w(TAG, "Method not allowed for $m, will try next. msg=${e.message}")
            } catch (e: IOException) {
                lastError = e
                throw e
            }
        }

        throw lastError ?: IOException("Upload failed: no method succeeded.")
    }

    private class MethodNotAllowedException(message: String) : IOException(message)

    private class TransientHttpException(
        val code: Int,
        val body: String,
        val retryAfterMs: Long?
    ) : IOException("Transient HTTP $code")

    private class HttpFailureException(val code: Int, val body: String) :
        IOException("Supabase request failed ($code): ${body.take(256)}")

    private suspend fun executeWithRetry(
        method: String,
        url: URL,
        cfg: SupabaseConfig,
        contentType: String,
        contentLength: Long,
        upsert: Boolean,
        tokenOverride: String?,
        body: BodySource,
        onProgress: (Int) -> Unit,
        resolvedObjectPath: String,
        maxAttempts: Int = 3
    ): UploadResult {

        var attempt = 0
        var lastError: IOException? = null

        while (attempt < maxAttempts) {
            attempt++

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                doOutput = true
                doInput = true
                useCaches = false

                connectTimeout = cfg.connectTimeoutMs
                readTimeout = cfg.readTimeoutMs

                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("apikey", cfg.anonKey.trim())

                val bearer = (tokenOverride?.trim().takeIf { !it.isNullOrBlank() }) ?: cfg.anonKey.trim()
                setRequestProperty("Authorization", "Bearer $bearer")

                if (upsert) setRequestProperty("x-upsert", "true")

                if (contentLength <= Int.MAX_VALUE.toLong()) {
                    setFixedLengthStreamingMode(contentLength.toInt())
                } else {
                    setChunkedStreamingMode(256 * 1024)
                }
            }

            try {
                onProgress(0)

                body.open().use { input ->
                    conn.outputStream.use { os ->
                        writeStreamWithProgress(
                            input = input,
                            output = os,
                            total = contentLength,
                            onProgress = onProgress
                        )
                    }
                }

                val code = conn.responseCode
                val headers = conn.headerFields.filterKeys { it != null }

                val bodyText = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { it.readBytes().decodeToString() }
                    .orEmpty()

                Log.d(
                    TAG,
                    "response: method=$method code=$code objectPath=$resolvedObjectPath reqId=${pickRequestId(headers)} bodyLen=${bodyText.length}"
                )

                if (code == 405) {
                    throw MethodNotAllowedException("HTTP 405 Method Not Allowed for $method")
                }

                if (code == 429 || code in 500..599) {
                    val retryAfterMs = parseRetryAfterMs(headers)
                    throw TransientHttpException(code, bodyText, retryAfterMs)
                }

                if (code !in 200..299) {
                    throw HttpFailureException(code, bodyText)
                }

                onProgress(100)

                val etag = headers.keys.firstOrNull { it.equals("etag", ignoreCase = true) }
                    ?.let { headers[it]?.firstOrNull() }

                val requestId = pickRequestId(headers)

                val publicUrl = buildPublicUrl(cfg, resolvedObjectPath)

                runCatching {
                    if (bodyText.isNotBlank()) JSONObject(bodyText)
                }.getOrNull()

                return UploadResult(
                    objectPath = resolvedObjectPath,
                    publicUrl = publicUrl,
                    etag = etag,
                    requestId = requestId
                )

            } catch (e: TransientHttpException) {
                lastError = IOException("Transient HTTP ${e.code}: ${e.body.take(200)}", e)
                if (attempt >= maxAttempts) throw lastError

                val backoff = e.retryAfterMs ?: (500L shl (attempt - 1))
                Log.w(TAG, "Retrying transient error in ${backoff}ms (attempt=$attempt/$maxAttempts)")
                delay(backoff)

            } catch (e: IOException) {
                lastError = e
                if (attempt >= maxAttempts) throw e
                val backoff = 500L shl (attempt - 1)
                Log.w(TAG, "Retrying IO error in ${backoff}ms (attempt=$attempt/$maxAttempts): ${e.message}")
                delay(backoff)

            } finally {
                conn.disconnect()
            }
        }

        throw lastError ?: IOException("Upload failed after $maxAttempts attempts.")
    }

    private fun writeStreamWithProgress(
        input: InputStream,
        output: OutputStream,
        total: Long,
        onProgress: (Int) -> Unit
    ) {
        val buf = ByteArray(256 * 1024)
        var sent = 0L

        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            sent += n

            val pct = if (total > 0) ((sent.toDouble() / total) * 100.0).toInt() else 0
            onProgress(min(99, pct.coerceIn(0, 100)))
        }
        output.flush()
    }

    private fun parseRetryAfterMs(headers: Map<String, List<String>>): Long? {
        val key = headers.keys.firstOrNull { it.equals("Retry-After", ignoreCase = true) }
        return key?.let { headers[it]?.firstOrNull()?.trim()?.toLongOrNull()?.times(1000L) }
    }

    private fun pickRequestId(headers: Map<String, List<String>>): String? {
        return headers.keys.firstOrNull {
            it.equals("x-request-id", ignoreCase = true) || it.equals("cf-ray", ignoreCase = true)
        }?.let { headers[it]?.firstOrNull() }
    }

    private fun buildPublicUrl(cfg: SupabaseConfig, objectPath: String): String {
        val baseUrl = cfg.supabaseUrl.trimEnd('/')
        val encoded = encodePath(objectPath)
        return "$baseUrl/storage/v1/object/public/${cfg.bucket}/$encoded"
    }

    /**
     * Apply config pathPrefix to a relative objectPath if needed.
     *
     * This is idempotent:
     * - prefix="" -> returns sanitized objectPath
     * - objectPath already starts with "prefix/" -> unchanged
     */
    private fun applyPrefixIfNeeded(pathPrefix: String, objectPath: String): String {
        val p = pathPrefix.trim().trim('/')
        val o = objectPath.trim().trim('/')

        if (o.isBlank()) return ""
        if (p.isBlank()) return o
        if (o == p || o.startsWith("$p/")) return o
        return "$p/$o"
    }
}
