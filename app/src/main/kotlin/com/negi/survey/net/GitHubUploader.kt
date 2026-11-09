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
import com.negi.survey.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min
import kotlin.random.Random
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
 * - Retry transient failures with backoff (429, 5xx, rate-limit, network IO).
 *
 * Notes:
 * - GitHub Contents API is best suited for relatively small payloads.
 * - For large binaries (e.g., long WAV), prefer Releases assets or object storage.
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
     * Conservative by default for Contents API workflows.
     * Caller may override via GitHubConfig if needed.
     */
    private const val DEFAULT_MAX_RAW_BYTES = 1_000_000

    /**
     * Default guard for the final JSON request size (rough).
     *
     * Roughly:
     *   requestBytes ≈ JSON_overhead + Base64(contentBytes)
     *   Base64(contentBytes) ≈ ceil(contentBytes/3)*4
     */
    private const val DEFAULT_MAX_REQUEST_BYTES = 2_800_000

    /** Additional overhead margin for JSON wrapper and small header variations. */
    private const val REQUEST_OVERHEAD_BYTES = 16_384L

    /** Retry defaults. */
    private const val MAX_CONFLICT_ATTEMPTS = 5
    private const val DEFAULT_MAX_ATTEMPTS = 3
    private const val BASE_BACKOFF_MS = 800L
    private const val MAX_BACKOFF_MS = 120_000L

    /**
     * Configuration container for GitHub upload operations.
     *
     * When using cfg overloads (dated path mode):
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

    internal data class SurveyRemoteRecord(val content: String, val fileUrl: String? = null)

    internal sealed interface SurveyCreateResult {
        data class Created(val upload: UploadResult) : SurveyCreateResult
        data class Reconcile(val statusCode: Int?) : SurveyCreateResult
    }

    internal interface SurveyContentsTransport {
        suspend fun getExisting(): SurveyRemoteRecord?
        suspend fun createOnly(): SurveyCreateResult
    }

    /** A response may have committed remotely although the client could not establish that fact. */
    internal class SurveyUploadAmbiguousException(message: String) : IOException(message)

    /** The remote target was not proven to be the staged survey and must not be overwritten. */
    internal class SurveyUploadValidationException(
        message: String,
        val remoteConflict: Boolean = false,
    ) : IOException(message)

    internal class ConflictRetriesExhaustedException : IOException("GitHub branch conflict retries exhausted (409).")

    // ---------------------------------------------------------------------
    // Public APIs — JSON/Text Upload
    // ---------------------------------------------------------------------

    /**
     * Upload a JSON or text file using [GitHubConfig] (includes UTC date folder).
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

    /**
     * Upload JSON/text using [GitHubConfig] to an explicit [path] (no auto date folder).
     *
     * This is useful when the caller wants deterministic paths (e.g., Worker-side path building).
     */
    suspend fun uploadJsonAtPath(
        cfg: GitHubConfig,
        path: String,
        content: String,
        message: String = DEFAULT_MESSAGE,
        onProgress: (Int) -> Unit = {}
    ): UploadResult = uploadJson(
        owner = cfg.owner,
        repo = cfg.repo,
        branch = cfg.branch,
        path = path,
        token = cfg.token,
        content = content,
        message = message,
        onProgress = onProgress,
        maxRawBytesHint = cfg.maxRawBytesHint,
        maxRequestBytesHint = cfg.maxRequestBytesHint
    )

    /**
     * Creates or reconciles an immutable canonical survey JSON target. Generic dated upload
     * semantics intentionally remain separate.
     */
    internal suspend fun uploadSurveyJsonIdempotentlyAtPath(
        cfg: GitHubConfig,
        path: String,
        expectedSurveyId: String,
        content: String,
        message: String = DEFAULT_MESSAGE,
        onProgress: (Int) -> Unit = {},
        verifyCreated: Boolean = false,
        connect: (URL) -> HttpURLConnection = ::openHttpConnection,
    ): UploadResult = withContext(Dispatchers.IO) {
        require(path.isNotBlank()) { "Canonical survey path is blank." }
        val identity = SurveyUploadIdempotency.validateLocalPayload(expectedSurveyId, content)
            ?: throw SurveyUploadValidationException("Staged survey JSON does not match its logical survey ID.")
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= cfg.maxRawBytesHint) { "Survey JSON exceeds the upload size guard." }
        val repoName = normalizeRepoName(cfg.repo)
        val encodedPath = encodePath(path)
        reconcileSurveyJsonUpload(
            identity = identity,
            content = content,
            verifyCreated = verifyCreated,
            transport = object : SurveyContentsTransport {
                override suspend fun getExisting(): SurveyRemoteRecord? =
                    getExistingContents(
                        cfg.owner, repoName, cfg.branch, encodedPath, cfg.token,
                        rawLimit = bytes.size.takeIf { verifyCreated }, connect = connect,
                    )?.let {
                        SurveyRemoteRecord(it.content, it.fileUrl)
                    }

                override suspend fun createOnly(): SurveyCreateResult = try {
                    val response = putContents(
                        owner = cfg.owner, repoName = repoName, branch = cfg.branch, path = path,
                        encodedPath = encodedPath, token = cfg.token, rawSize = bytes.size.toLong(),
                        openStream = { bytes.inputStream() }, message = message, sha = null,
                        onProgress = onProgress, maxRequestBytesHint = cfg.maxRequestBytesHint,
                        maxAttempts = if (verifyCreated) 1 else DEFAULT_MAX_ATTEMPTS,
                        connect = connect,
                    )
                    SurveyCreateResult.Created(parseUploadResult(response.body))
                } catch (failure: HttpFailureException) {
                    if (failure.code == 409 || failure.code == 422) {
                        SurveyCreateResult.Reconcile(failure.code)
                    } else {
                        throw failure
                    }
                } catch (failure: IOException) {
                    if (failure.message.orEmpty().contains("too large", ignoreCase = true)) {
                        throw failure
                    }
                    SurveyCreateResult.Reconcile(null)
                }
            }
        )
    }

    /** Pure survey-only reconciliation state machine, shared with focused JVM tests. */
    internal suspend fun reconcileSurveyJsonUpload(
        identity: SurveyUploadIdempotency.LocalSurveyIdentity,
        content: String,
        transport: SurveyContentsTransport,
        verifyCreated: Boolean = false,
        waitForConflict: suspend (Int) -> Unit = { delay(conflictDelayMs(it)) }
    ): UploadResult {
        fun matching(existing: SurveyRemoteRecord): UploadResult {
            if (SurveyUploadIdempotency.classifyExisting(identity, content, existing.content) !=
                SurveyUploadIdempotency.ExistingRecord.MATCHING
            ) {
                throw SurveyUploadValidationException("Canonical survey target contains conflicting content.", remoteConflict = true)
            }
            return UploadResult(existing.fileUrl, null)
        }

        transport.getExisting()?.let { return matching(it) }
        for (attempt in 1..MAX_CONFLICT_ATTEMPTS) {
            when (val created = transport.createOnly()) {
                is SurveyCreateResult.Created -> {
                    if (verifyCreated) {
                        matching(transport.getExisting() ?: throw SurveyUploadAmbiguousException(
                            "Created survey could not yet be verified."
                        ))
                    }
                    return created.upload
                }
                is SurveyCreateResult.Reconcile -> {
                    transport.getExisting()?.let { return matching(it) }
                    if (created.statusCode == 422) {
                        throw SurveyUploadValidationException("Canonical survey create was rejected (422) and no matching target exists.")
                    }
                    if (created.statusCode != 409) {
                        throw SurveyUploadAmbiguousException("Canonical survey create outcome is ambiguous.")
                    }
                    if (attempt == MAX_CONFLICT_ATTEMPTS) throw ConflictRetriesExhaustedException()
                    waitForConflict(attempt)
                    transport.getExisting()?.let { return matching(it) }
                }
            }
        }
        throw SurveyUploadAmbiguousException("Canonical survey conflict retries exhausted.")
    }

    // ---------------------------------------------------------------------
    // Public APIs — Binary Upload (ByteArray)
    // ---------------------------------------------------------------------

    /**
     * Upload binary bytes using [GitHubConfig] (includes UTC date folder).
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

    /**
     * Upload binary bytes using [GitHubConfig] to an explicit [path] (no auto date folder).
     */
    suspend fun uploadBytesAtPath(
        cfg: GitHubConfig,
        path: String,
        bytes: ByteArray,
        message: String = DEFAULT_MESSAGE,
        onProgress: (Int) -> Unit = {}
    ): UploadResult = uploadFile(
        owner = cfg.owner,
        repo = cfg.repo,
        branch = cfg.branch,
        path = path,
        token = cfg.token,
        bytes = bytes,
        message = message,
        onProgress = onProgress,
        maxRawBytesHint = cfg.maxRawBytesHint,
        maxRequestBytesHint = cfg.maxRequestBytesHint
    )

    // ---------------------------------------------------------------------
    // Public APIs — Binary Upload (File streaming)
    // ---------------------------------------------------------------------

    /**
     * Upload a local file using [GitHubConfig] (includes UTC date folder).
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
        maxRequestBytesHint: Int = DEFAULT_MAX_REQUEST_BYTES,
        verifyCreated: Boolean = false,
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
            maxRequestBytesHint = maxRequestBytesHint,
            verifyCreated = verifyCreated,
        )
    }

    /**
     * Upload a local file using [GitHubConfig] to an explicit [path] (no auto date folder).
     */
    suspend fun uploadFileAtPath(
        cfg: GitHubConfig,
        path: String,
        file: File,
        message: String = DEFAULT_MESSAGE,
        onProgress: (Int) -> Unit = {},
        verifyCreated: Boolean = false,
    ): UploadResult = uploadFile(
        owner = cfg.owner,
        repo = cfg.repo,
        branch = cfg.branch,
        path = path,
        token = cfg.token,
        file = file,
        message = message,
        onProgress = onProgress,
        maxRawBytesHint = cfg.maxRawBytesHint,
        maxRequestBytesHint = cfg.maxRequestBytesHint,
        verifyCreated = verifyCreated,
    )

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
     *
     * Special:
     * - On 409, back off and read the target before another create-only PUT.
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
        maxRequestBytesHint: Int,
        verifyCreated: Boolean = false,
    ): UploadResult = withContext(Dispatchers.IO) {

        require(owner.isNotBlank()) { "GitHub owner cannot be blank." }
        require(repo.isNotBlank()) { "GitHub repo cannot be blank." }
        require(branch.isNotBlank()) { "GitHub branch cannot be blank." }
        require(path.isNotBlank()) { "GitHub path cannot be blank." }
        require(token.isNotBlank()) { "GitHub token cannot be blank." }

        val repoName = normalizeRepoName(repo)
        if (repoName != repo) {
            Log.w(TAG, "uploadStream: repo contains '/'; using repoName='$repoName' (was='$repo')")
        }

        if (rawSize > maxRawBytesHint.toLong()) {
            val msg =
                "Content too large for upload guard (size=$rawSize, limit=$maxRawBytesHint). " +
                        "Base64 expands ~4/3; request grows further due to JSON."
            throw IOException(msg)
        }

        val encodedPath = encodePath(path)

        Log.d(
            TAG,
            "uploadStream: owner=$owner repo=$repoName branch=$branch path=$path size=$rawSize " +
                    "maxRawBytesHint=$maxRawBytesHint maxRequestBytesHint=$maxRequestBytesHint"
        )

        onProgress(0)

        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob $rawSize\u0000".toByteArray(Charsets.UTF_8))
        openStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val localSha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val result = createArtifactWithConflictRetry(
            localSha = localSha,
            verifyCreated = verifyCreated,
            getExistingSha = { getExistingSha(owner, repoName, branch, encodedPath, token) },
            create = {
                val response = putContents(
                    owner, repoName, branch, path, encodedPath, token, rawSize,
                    openStream, message, null, onProgress, maxRequestBytesHint,
                    maxAttempts = if (verifyCreated) 1 else DEFAULT_MAX_ATTEMPTS,
                )
                parseUploadResult(response.body)
            }
        )
        onProgress(95)
        result
    }

    /** Create-only artifact upload. A matching Git blob proves an earlier attempt succeeded. */
    internal suspend fun createArtifactWithConflictRetry(
        localSha: String,
        getExistingSha: suspend () -> String?,
        create: suspend () -> UploadResult,
        waitForConflict: suspend (Int) -> Unit = { delay(conflictDelayMs(it)) },
        verifyCreated: Boolean = false,
    ): UploadResult {
        fun checkRemote(sha: String?): UploadResult? {
            if (sha == null) return null
            if (sha != localSha) throw ArtifactConflictException()
            return UploadResult(null, null)
        }
        checkRemote(getExistingSha())?.let { return it }
        for (attempt in 1..MAX_CONFLICT_ATTEMPTS) {
            try {
                val result = create()
                if (verifyCreated) {
                    checkRemote(getExistingSha())
                        ?: throw SurveyUploadAmbiguousException("Created artifact could not yet be verified.")
                }
                return result
            } catch (failure: HttpFailureException) {
                if (failure.code != 409 && failure.code != 422) throw failure
                checkRemote(getExistingSha())?.let { return it }
                if (failure.code == 422) throw failure
                if (attempt == MAX_CONFLICT_ATTEMPTS) throw ConflictRetriesExhaustedException()
                waitForConflict(attempt)
                checkRemote(getExistingSha())?.let { return it }
            } catch (failure: IOException) {
                if (!verifyCreated || failure is ArtifactConflictException) throw failure
                checkRemote(getExistingSha())?.let { return it }
                throw failure
            }
        }
        throw IOException("Artifact conflict retries exhausted")
    }

    internal class ArtifactConflictException : IOException("Remote artifact contains different content.")

    internal fun conflictDelayMs(attempt: Int, random: Random = Random): Long {
        val cap = (400L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(6400L)
        return random.nextLong(cap / 2, cap + 1)
    }

    // ---------------------------------------------------------------------
    // PUT + Response parsing
    // ---------------------------------------------------------------------

    private fun parseUploadResult(body: String): UploadResult {
        val json = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw IOException("Malformed JSON from GitHub: ${e.message}", e)
        }

        val fileUrl =
            json.optJSONObject("content")
                ?.optString("html_url")
                ?.takeIf { it.isNotBlank() }

        val commitSha =
            json.optJSONObject("commit")
                ?.optString("sha")
                ?.takeIf { it.isNotBlank() }

        Log.d(TAG, "uploadStream: done url=$fileUrl sha=$commitSha")
        return UploadResult(fileUrl, commitSha)
    }

    private suspend fun putContents(
        owner: String,
        repoName: String,
        branch: String,
        path: String,
        encodedPath: String,
        token: String,
        rawSize: Long,
        openStream: () -> InputStream,
        message: String,
        sha: String?,
        onProgress: (Int) -> Unit,
        maxRequestBytesHint: Int,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        connect: (URL) -> HttpURLConnection = ::openHttpConnection,
    ): HttpResponse {
        val msgJson = JSONObject.quote(message.ifBlank { DEFAULT_MESSAGE })
        val branchJson = JSONObject.quote(branch)
        val shaJson = sha?.takeIf { it.isNotBlank() }?.let { JSONObject.quote(it) }

        val prefix = "{\"message\":$msgJson,\"branch\":$branchJson,\"content\":\""
        val suffix = if (shaJson != null) "\",\"sha\":$shaJson}" else "\"}"

        val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
        val suffixBytes = suffix.toByteArray(Charsets.UTF_8)

        val b64Len = estimateBase64Length(rawSize)
        val totalLenLong = prefixBytes.size.toLong() + b64Len + suffixBytes.size.toLong()

        val guardedTotalLen = totalLenLong + REQUEST_OVERHEAD_BYTES
        if (guardedTotalLen > maxRequestBytesHint.toLong()) {
            val msg =
                "Request too large for upload guard " +
                        "(requestBytes~$guardedTotalLen, limit=$maxRequestBytesHint). " +
                        "ContentBytes=$rawSize; Base64 expands ~4/3."
            throw IOException(msg)
        }

        val url = URL("$API_BASE/repos/$owner/$repoName/contents/$encodedPath")

        val writeBody: (HttpURLConnection) -> Unit = { conn ->
            if (totalLenLong <= Int.MAX_VALUE.toLong()) {
                conn.setFixedLengthStreamingMode(totalLenLong.toInt())
            } else {
                conn.setChunkedStreamingMode(0)
            }

            conn.outputStream.use { os ->
                os.write(prefixBytes)

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

                b64Out.close() // flush base64 tail without closing underlying os
                onProgress(90)

                os.write(suffixBytes)
                os.flush()
            }
        }

        return executeWithRetry(
            method = "PUT",
            url = url,
            token = token,
            writeBody = writeBody,
            maxAttempts = maxAttempts,
            connect = connect,
        )
    }

    // ---------------------------------------------------------------------
    // Retry/HTTP Internals
    // ---------------------------------------------------------------------

    private data class HttpResponse(
        val code: Int,
        val body: String,
        val headers: Map<String, List<String>>
    )

    private data class ExistingContents(val content: String, val fileUrl: String?)

    private class TransientHttpException(
        val code: Int,
        val body: String,
        val retryAfterSeconds: Long?,
        val rateLimitResetEpochSeconds: Long?
    ) : IOException()

    internal class HttpFailureException(val code: Int, val body: String) :
        IOException("GitHub request failed ($code): ${body.take(256)}")

    /**
     * Execute an HTTP request with retry/backoff.
     *
     * Retries:
     * - 429
     * - 5xx
     * - 403 with Retry-After (secondary rate limit) or primary rate limit signals
     * - IOException network errors
     *
     * Honors Retry-After or X-RateLimit-Reset if present.
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
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        allowNon2xxCodes: Set<Int> = emptySet(),
        rawLimit: Int? = null,
        connect: (URL) -> HttpURLConnection = ::openHttpConnection,
    ): HttpResponse {
        if (BuildConfig.ISSUE157_DA) throw IOException("GitHub uploads are disabled in Survey D+A.")
        var attempt = 0
        var lastError: IOException? = null

        while (attempt < maxAttempts) {
            attempt++

            val conn = connect(url).apply {
                requestMethod = method
                doInput = true
                doOutput = (writeBody != null)
                useCaches = false
                instanceFollowRedirects = false

                // GitHub REST docs commonly show "Bearer <token>".
                setRequestProperty("Authorization", "Bearer ${token.trim()}")
                setRequestProperty("Accept", if (rawLimit != null) "application/vnd.github.raw+json" else "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", API_VERSION)
                setRequestProperty("User-Agent", USER_AGENT)

                if (writeBody != null) {
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }

                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
            }

            try {
                writeBody?.invoke(conn)

                val code = conn.responseCode
                val headers = conn.headerFields.filterKeys { it != null }

                if (code in 200..299 || allowNon2xxCodes.contains(code)) {
                    val bodyStream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val body = bodyStream?.use {
                        if (rawLimit != null && code in 200..299) {
                            readRawSurvey(it, rawLimit)
                        } else readAll(it)
                    }.orEmpty()
                    return HttpResponse(code, body, headers)
                }

                val errBody = conn.errorStream?.use(::readAll).orEmpty()

                val retryAfter = parseRetryAfterSeconds(headers)
                val reset = parseRateLimitResetEpochSeconds(headers)
                val isRateLimit =
                    (code == 403 || code == 429) &&
                            (retryAfter != null || isPrimaryRateLimit(headers, errBody) || reset != null)

                val isTransient =
                    code == 429 || code in 500..599 || isRateLimit

                if (isTransient) {
                    throw TransientHttpException(code, errBody, retryAfter, reset)
                }

                throw HttpFailureException(code, errBody)

            } catch (e: TransientHttpException) {
                lastError = IOException("Transient HTTP ${e.code}: ${e.body.take(200)}", e)
                if (attempt >= maxAttempts) throw lastError

                val delayMs = computeRetryDelayMs(
                    attempt = attempt,
                    retryAfterSeconds = e.retryAfterSeconds,
                    rateLimitResetEpochSeconds = e.rateLimitResetEpochSeconds
                )
                delay(delayMs)

            } catch (e: HttpFailureException) {
                throw e
            } catch (e: SurveyUploadValidationException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                if (attempt >= maxAttempts) throw e

                val delayMs = computeBackoffMs(attempt)
                delay(delayMs)

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
     * Normalize repo string.
     *
     * Allows:
     * - "SurveyExports"
     * - "ishizuki-tech/SurveyExports" (returns "SurveyExports")
     */
    private fun normalizeRepoName(repo: String): String {
        val t = repo.trim()
        return if (t.contains('/')) t.substringAfterLast('/').trim() else t
    }

    /**
     * Parse Retry-After seconds with case-insensitive header matching.
     */
    private fun parseRetryAfterSeconds(headers: Map<String, List<String>>): Long? {
        val v = getHeaderFirst(headers, "Retry-After") ?: return null
        v.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
        return null
    }

    /**
     * Parse X-RateLimit-Reset (epoch seconds) if present.
     */
    private fun parseRateLimitResetEpochSeconds(headers: Map<String, List<String>>): Long? {
        val v = getHeaderFirst(headers, "X-RateLimit-Reset") ?: return null
        return v.toLongOrNull()
    }

    /**
     * Detect primary/secondary rate limit signals.
     */
    private fun isPrimaryRateLimit(headers: Map<String, List<String>>, body: String): Boolean {
        val remaining = getHeaderFirst(headers, "X-RateLimit-Remaining")?.toLongOrNull()
        if (remaining != null && remaining <= 0L) return true

        val b = body.lowercase(Locale.US)
        if (b.contains("api rate limit exceeded")) return true
        if (b.contains("secondary rate limit")) return true
        if (b.contains("rate limit")) return true
        return false
    }

    /**
     * Case-insensitive header lookup.
     */
    private fun getHeaderFirst(headers: Map<String, List<String>>, name: String): String? {
        val key = headers.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
        return headers[key]?.firstOrNull()
    }

    /**
     * Compute retry delay for transient HTTP errors.
     */
    private fun computeRetryDelayMs(
        attempt: Int,
        retryAfterSeconds: Long?,
        rateLimitResetEpochSeconds: Long?
    ): Long {
        retryAfterSeconds?.let {
            val ms = (it.coerceAtMost(180L) * 1000L)
            return ms.coerceIn(500L, MAX_BACKOFF_MS)
        }

        rateLimitResetEpochSeconds?.let { reset ->
            val nowSec = System.currentTimeMillis() / 1000L
            val waitSec = (reset - nowSec + 1L).coerceAtLeast(1L)
            val ms = waitSec * 1000L
            return ms.coerceIn(1_000L, MAX_BACKOFF_MS)
        }

        return computeBackoffMs(attempt)
    }

    /**
     * Exponential backoff with small jitter.
     */
    private fun computeBackoffMs(attempt: Int): Long {
        val pow = (attempt - 1).coerceIn(0, 7)
        val base = (BASE_BACKOFF_MS shl pow).coerceAtMost(MAX_BACKOFF_MS)
        val jitter = Random.nextLong(from = 0L, until = 350L)
        return (base + jitter).coerceAtMost(MAX_BACKOFF_MS)
    }

    /**
     * Read an entire input stream as UTF-8 text.
     */
    private fun readAll(stream: InputStream): String =
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }

    /**
     * Look up an existing file SHA if the path already exists on the target branch.
     *
     * Returns null when:
     * - File does not exist (404)
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
            maxAttempts = DEFAULT_MAX_ATTEMPTS,
            allowNon2xxCodes = setOf(404)
        )

        if (resp.code == 404) return null
        if (resp.code !in 200..299) return null

        return try {
            JSONObject(resp.body).optString("sha").takeIf { it.matches(Regex("[0-9a-f]{40}")) }
                ?: throw IOException("Existing artifact has no valid blob SHA.")
        } catch (error: JSONException) {
            throw IOException("Malformed existing artifact response.", error)
        }
    }

    private fun openHttpConnection(url: URL): HttpURLConnection = url.openConnection() as HttpURLConnection

    /** Bound raw read-back by the local payload size. Larger or invalid UTF-8 bytes cannot match. */
    private fun readRawSurvey(input: InputStream, limit: Int): String {
        val bytes = java.io.ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - bytes.size() + 1))
            if (count < 0) break
            if (count > limit - bytes.size()) {
                throw SurveyUploadValidationException("Remote survey exceeds the staged size.", remoteConflict = true)
            }
            bytes.write(buffer, 0, count)
        }
        return try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw SurveyUploadValidationException("Remote survey is invalid UTF-8.", remoteConflict = true)
        }
    }

    /** Reads the complete UTF-8 payload needed to validate an immutable survey target. */
    private suspend fun getExistingContents(
        owner: String,
        repo: String,
        branch: String,
        encodedPath: String,
        token: String,
        rawLimit: Int? = null,
        connect: (URL) -> HttpURLConnection = ::openHttpConnection,
    ): ExistingContents? {
        val refEncoded = URLEncoder.encode(branch.trim(), "UTF-8").replace("+", "%20")
        val url = URL("$API_BASE/repos/$owner/$repo/contents/$encodedPath?ref=$refEncoded")
        val response = executeWithRetry(
            method = "GET", url = url, token = token, writeBody = null,
            connectTimeoutMs = 15_000, readTimeoutMs = 20_000,
            maxAttempts = DEFAULT_MAX_ATTEMPTS, allowNon2xxCodes = setOf(404), rawLimit = rawLimit, connect = connect
        )
        if (response.code == 404) return null
        if (rawLimit != null) return ExistingContents(response.body, null)
        val json = try {
            JSONObject(response.body)
        } catch (error: JSONException) {
            throw IOException("Malformed JSON while reading canonical survey target.", error)
        }
        val encodedContent = json.optString("content").filterNot(Char::isWhitespace)
        if (encodedContent.isBlank()) throw IOException("Canonical survey target has no content.")
        val content = try {
            Base64.decode(encodedContent, Base64.DEFAULT).toString(Charsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            throw IOException("Canonical survey target content is not Base64.", error)
        }
        return ExistingContents(content, json.optString("html_url").takeIf { it.isNotBlank() })
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
