/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: GitHubUploadWorker.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Foreground-capable WorkManager coroutine worker that uploads either:
 *   - one local payload file (text/binary) using GitHubUploader, OR
 *   - a collected logcat snapshot (gzip) using GitHubLogUploader.
 *
 *  This worker is Android 14+ friendly (DATA_SYNC foreground service type)
 *  and reports determinate progress via notification + WorkData.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.negi.survey.R
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Coroutine-based [WorkManager] worker responsible for uploading either:
 *  - a local file, or
 *  - a logcat snapshot (collected at runtime).
 */
class GitHubUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Keep Worker-side hint and Uploader-side hint consistent.
        val maxFileBytesHint =
            inputData.getLong(KEY_FILE_MAX_BYTES_HINT, MAX_CONTENTS_API_BYTES_HINT)
                .coerceAtLeast(1L)

        val maxRawBytesHint = maxFileBytesHint
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        val maxRequestBytesHint = inputData.getInt(
            KEY_FILE_MAX_REQUEST_BYTES_HINT,
            estimateRequestBytesHint(maxRawBytesHint)
        )

        val cfg = GitHubUploader.GitHubConfig(
            owner = inputData.getString(KEY_OWNER).orEmpty(),
            repo = inputData.getString(KEY_REPO).orEmpty(),
            token = inputData.getString(KEY_TOKEN).orEmpty(),
            branch = inputData.getString(KEY_BRANCH)?.takeIf { it.isNotBlank() } ?: "main",
            pathPrefix = inputData.getString(KEY_PATH_PREFIX).orEmpty(),
            maxRawBytesHint = maxRawBytesHint,
            maxRequestBytesHint = maxRequestBytesHint
        )

        if (cfg.owner.isBlank() || cfg.repo.isBlank() || cfg.token.isBlank()) {
            return Result.failure(
                workDataOf(ERROR_MESSAGE to "Invalid GitHub configuration (owner/repo/token).")
            )
        }

        val mode = inputData.getString(KEY_MODE)?.lowercase(Locale.US) ?: MODE_FILE

        ensureChannel()

        val notifTitleBase = when (mode) {
            MODE_LOGCAT -> "Uploading logcat"
            else -> "Uploading payload"
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val notifId = NOTIF_BASE + (abs((mode + stamp).hashCode()) % 8000)

        setForegroundAsync(
            foregroundInfo(
                notificationId = notifId,
                pct = 0,
                title = "$notifTitleBase…"
            )
        )

        val lastPctRef = intArrayOf(-1)
        val progressCallback: (Int) -> Unit = progressCallback@{ pct ->
            val clamped = pct.coerceIn(0, 100)
            if (clamped == lastPctRef[0]) return@progressCallback
            lastPctRef[0] = clamped

            setProgressAsync(
                workDataOf(
                    PROGRESS_PCT to clamped,
                    PROGRESS_MODE to mode
                )
            )

            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = clamped,
                    title = "$notifTitleBase…"
                )
            )
        }

        val currentPct: () -> Int = { lastPctRef[0].coerceAtLeast(0) }

        return when (mode) {
            MODE_LOGCAT -> doLogcatUpload(cfg, notifId, progressCallback, currentPct)
            else -> doFileUpload(cfg, notifId, progressCallback, currentPct)
        }
    }

    /**
     * Execute file upload mode.
     */
    private suspend fun doFileUpload(
        cfg: GitHubUploader.GitHubConfig,
        notifId: Int,
        onProgress: (Int) -> Unit,
        currentPct: () -> Int,
    ): Result {

        val filePath = inputData.getString(KEY_FILE_PATH).orEmpty()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: File(filePath).name

        if (filePath.isBlank()) {
            return Result.failure(workDataOf(ERROR_MESSAGE to "Missing file path."))
        }

        val pendingFile = File(filePath)
        if (!pendingFile.exists()) {
            return Result.failure(workDataOf(ERROR_MESSAGE to "Pending file not found: $filePath"))
        }

        val fileSize = pendingFile.length()
        if (fileSize <= 0L) {
            return Result.failure(workDataOf(ERROR_MESSAGE to "Pending file is empty: $filePath"))
        }

        val maxBytesHint = inputData.getLong(KEY_FILE_MAX_BYTES_HINT, MAX_CONTENTS_API_BYTES_HINT)
        if (fileSize > maxBytesHint) {
            val msg =
                "File too large for this upload path (size=$fileSize, limit~$maxBytesHint). " +
                        "For PCM_16BIT MONO WAV: bytes = 44 + seconds * sampleRateHz * 2."
            return Result.failure(workDataOf(ERROR_MESSAGE to msg))
        }

        // Fail-fast guardrail: raw bytes can fit, but request bytes may exceed limits due to base64 expansion.
        val estRequestBytes = estimateBase64RequestBytes(fileSize)
        if (estRequestBytes > cfg.maxRequestBytesHint.toLong()) {
            val msg =
                "Request too large for this upload path (raw=$fileSize, request~$estRequestBytes, " +
                        "limit~${cfg.maxRequestBytesHint}). Base64 expands by ~4/3."
            return Result.failure(workDataOf(ERROR_MESSAGE to msg))
        }

        // UI path should match GitHubUploader.buildPath() behavior (UTC yyyy-MM-dd).
        val remotePathForUi = buildDatedRemotePathUtc(cfg.pathPrefix, fileName)

        Log.d(
            TAG,
            "doFileUpload: owner=${cfg.owner} repo=${cfg.repo} branch=${cfg.branch} " +
                    "prefix='${cfg.pathPrefix}' filePath=$filePath fileName=$fileName size=$fileSize " +
                    "maxBytesHint=$maxBytesHint rawHint=${cfg.maxRawBytesHint} reqHint=${cfg.maxRequestBytesHint}"
        )

        return try {
            val extension = pendingFile.extension.lowercase(Locale.US)
            val isText = TEXT_EXTENSIONS.contains(extension)

            val result = if (isText) {
                val text = runCatching { pendingFile.readText(Charsets.UTF_8) }.getOrElse {
                    return Result.failure(
                        workDataOf(ERROR_MESSAGE to "Failed to read text file: ${it.message}")
                    )
                }

                GitHubUploader.uploadJson(
                    cfg = cfg,
                    relativePath = fileName,
                    content = text,
                    message = "Upload $fileName (deferred)",
                    onProgress = onProgress
                )
            } else {
                // Stream upload to avoid loading the entire binary into memory.
                GitHubUploader.uploadFile(
                    cfg = cfg,
                    relativePath = fileName,
                    file = pendingFile,
                    message = "Upload $fileName (deferred)",
                    onProgress = onProgress
                )
            }

            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = 100,
                    title = "Uploaded $fileName",
                    finished = true
                )
            )

            runCatching { pendingFile.delete() }

            Result.success(
                workDataOf(
                    OUT_MODE to MODE_FILE,
                    OUT_FILE_NAME to fileName,
                    OUT_REMOTE_PATH to remotePathForUi,
                    OUT_COMMIT_SHA to (result.commitSha ?: ""),
                    OUT_FILE_URL to (result.fileUrl ?: "")
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "doFileUpload: upload failed for $filePath", t)

            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = currentPct(),
                    title = "Upload failed: $fileName",
                    error = true
                )
            )

            val failData = workDataOf(ERROR_MESSAGE to (t.message ?: "Unknown error"))

            if (shouldRetry(t) && runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure(failData)
        }
    }

    /**
     * Execute logcat upload mode.
     */
    private suspend fun doLogcatUpload(
        cfg: GitHubUploader.GitHubConfig,
        notifId: Int,
        onProgress: (Int) -> Unit,
        currentPct: () -> Int,
    ): Result {

        val remoteDir = inputData.getString(KEY_LOG_REMOTE_DIR) ?: "diagnostics/logs"
        val addDate = inputData.getBoolean(KEY_LOG_ADD_DATE, true)
        val includeHeader = inputData.getBoolean(KEY_LOG_INCLUDE_HEADER, true)
        val includeCrash = inputData.getBoolean(KEY_LOG_INCLUDE_CRASH, true)
        val maxBytes = inputData.getInt(KEY_LOG_MAX_UNCOMPRESSED, 850_000)

        return try {
            val out = GitHubLogUploader.collectAndUploadLogcat(
                context = applicationContext,
                cfg = cfg,
                remoteDir = remoteDir,
                addDateSubdir = addDate,
                includeDeviceHeader = includeHeader,
                maxUncompressedBytes = maxBytes,
                includeCrashBuffer = includeCrash,
                onProgress = onProgress,
            )

            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = 100,
                    title = "Uploaded logcat",
                    finished = true
                )
            )

            Result.success(
                workDataOf(
                    OUT_MODE to MODE_LOGCAT,
                    OUT_REMOTE_PATH to out.remotePath,
                    OUT_COMMIT_SHA to (out.commitSha ?: ""),
                    OUT_FILE_URL to (out.fileUrl ?: ""),
                    OUT_BYTES_RAW to out.bytesRaw,
                    OUT_BYTES_GZ to out.bytesGz,
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "doLogcatUpload: upload failed", t)

            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = currentPct(),
                    title = "Log upload failed",
                    error = true
                )
            )

            val failData = workDataOf(ERROR_MESSAGE to (t.message ?: "Unknown error"))

            if (shouldRetry(t) && runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure(failData)
        }
    }

    /**
     * Build a date-based remote path consistent with GitHubUploader:
     *   prefix + yyyy-MM-dd + fileName
     *
     * Uses UTC date to keep paths stable across timezones.
     */
    private fun buildDatedRemotePathUtc(prefix: String, fileName: String): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        return listOf(prefix.trim('/'), date, fileName.trim('/'))
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    /**
     * Determine if the throwable should be treated as transient.
     */
    private fun shouldRetry(t: Throwable): Boolean {
        val msg = t.message.orEmpty()

        // If uploader labeled it transient, allow retry even for 403 (secondary rate limit).
        if (msg.startsWith("Transient HTTP ", ignoreCase = true)) return true

        if (msg.contains("too large", ignoreCase = true)) return false
        if (msg.contains("invalid github configuration", ignoreCase = true)) return false

        // Avoid retrying obvious auth/permanent failures.
        if (msg.contains("401")) return false
        if (msg.contains("422")) return false
        if (msg.contains("bad credentials", ignoreCase = true)) return false
        if (msg.contains("requires authentication", ignoreCase = true)) return false

        // 403 is ambiguous; retry only if it was labeled transient above.
        if (msg.contains("403")) return false

        return t is IOException || msg.contains("timeout", ignoreCase = true)
    }

    /**
     * Build [ForegroundInfo] with an upload progress notification.
     */
    private fun foregroundInfo(
        notificationId: Int,
        pct: Int,
        title: String,
        finished: Boolean = false,
        error: Boolean = false
    ): ForegroundInfo {
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_upload)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setOngoing(!finished && !error)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (finished || error) {
            builder.setProgress(0, 0, false)
        } else {
            builder.setProgress(100, pct.coerceIn(0, 100), false)
        }

        val notification = builder.build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            @Suppress("DEPRECATION")
            ForegroundInfo(notificationId, notification)
        }
    }

    /**
     * Ensure the notification channel for upload progress exists.
     */
    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background Uploads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Displays progress for ongoing uploads to GitHub."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {

        const val TAG = "github_upload"

        private const val CHANNEL_ID = "uploads"
        private const val NOTIF_BASE = 3200
        private const val MAX_ATTEMPTS = 5

        /**
         * Soft guardrail for payload size before upload.
         *
         * For PCM_16BIT MONO WAV, rough size is:
         *   bytes = 44 + seconds * sampleRateHz * 2
         *
         * Examples:
         * - 16kHz, 180s: 44 + 180 * 16000 * 2 = 5,760,044 bytes (~5.49 MiB)
         * - 48kHz, 180s: 44 + 180 * 48000 * 2 = 17,280,044 bytes (~16.48 MiB)
         */
        private const val MAX_CONTENTS_API_BYTES_HINT = 20_000_000L

        /**
         * Default request (JSON) bytes hint.
         *
         * Rough estimate:
         * - Base64 expands by ~4/3.
         * - JSON overhead adds some extra bytes.
         */
        private const val DEFAULT_MAX_REQUEST_BYTES_HINT = 32_000_000

        private val TEXT_EXTENSIONS = setOf("json", "jsonl", "txt", "csv")

        // Modes
        private const val MODE_FILE = "file"
        private const val MODE_LOGCAT = "logcat"

        // Progress keys
        const val PROGRESS_PCT = "pct"
        const val PROGRESS_MODE = "mode"

        // Common input keys
        const val KEY_OWNER = "owner"
        const val KEY_REPO = "repo"
        const val KEY_TOKEN = "token"
        const val KEY_BRANCH = "branch"
        const val KEY_PATH_PREFIX = "pathPrefix"
        const val KEY_MODE = "mode"

        // File input keys
        const val KEY_FILE_PATH = "filePath"
        const val KEY_FILE_NAME = "fileName"

        /**
         * Optional per-request override for the raw file size hint.
         */
        const val KEY_FILE_MAX_BYTES_HINT = "file.maxBytesHint"

        /**
         * Optional per-request override for the estimated request bytes hint.
         *
         * If omitted, we estimate from [KEY_FILE_MAX_BYTES_HINT].
         */
        const val KEY_FILE_MAX_REQUEST_BYTES_HINT = "file.maxRequestBytesHint"

        // Logcat input keys
        const val KEY_LOG_REMOTE_DIR = "log.remoteDir"
        const val KEY_LOG_ADD_DATE = "log.addDate"
        const val KEY_LOG_INCLUDE_HEADER = "log.includeHeader"
        const val KEY_LOG_INCLUDE_CRASH = "log.includeCrash"
        const val KEY_LOG_MAX_UNCOMPRESSED = "log.maxUncompressed"

        // Output keys
        const val OUT_MODE = "out.mode"
        const val OUT_FILE_NAME = "out.fileName"
        const val OUT_REMOTE_PATH = "out.remotePath"
        const val OUT_COMMIT_SHA = "out.commitSha"
        const val OUT_FILE_URL = "out.fileUrl"
        const val OUT_BYTES_RAW = "out.bytesRaw"
        const val OUT_BYTES_GZ = "out.bytesGz"

        const val ERROR_MESSAGE = "error"

        /**
         * Estimate base64-encoded request bytes from raw bytes.
         *
         * Note: This is a coarse guardrail used to fail fast before attempting a large request.
         */
        private fun estimateBase64RequestBytes(rawBytes: Long): Long {
            val raw = rawBytes.coerceAtLeast(1L)
            val b64 = ((raw + 2L) / 3L) * 4L
            val overhead = 2_000_000L
            return b64 + overhead
        }

        /**
         * Estimate a reasonable request-bytes guard from raw-bytes hint.
         *
         * Base64 size (ceil(n/3)*4) + overhead, with a minimum floor.
         */
        private fun estimateRequestBytesHint(rawBytesHint: Int): Int {
            val est = estimateBase64RequestBytes(rawBytesHint.toLong())
            val floor = DEFAULT_MAX_REQUEST_BYTES_HINT.toLong()
            val out = maxOf(floor, est)
            return out.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        /**
         * Enqueue a work request to upload an existing file.
         */
        fun enqueueExistingPayload(
            context: Context,
            cfg: GitHubUploader.GitHubConfig,
            file: File,
            maxBytesHint: Long = MAX_CONTENTS_API_BYTES_HINT,
            maxRequestBytesHint: Int = estimateRequestBytesHint(
                maxBytesHint.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
        ) {
            val name = file.name

            val req: OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_MODE to MODE_FILE,
                            KEY_OWNER to cfg.owner,
                            KEY_REPO to cfg.repo,
                            KEY_TOKEN to cfg.token,
                            KEY_BRANCH to cfg.branch,
                            KEY_PATH_PREFIX to cfg.pathPrefix,
                            KEY_FILE_PATH to file.absolutePath,
                            KEY_FILE_NAME to name,
                            KEY_FILE_MAX_BYTES_HINT to maxBytesHint,
                            KEY_FILE_MAX_REQUEST_BYTES_HINT to maxRequestBytesHint
                        )
                    )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .addTag(TAG)
                    .addTag("$TAG:file:$name")
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork("upload_$name", ExistingWorkPolicy.KEEP, req)
        }

        /**
         * Enqueue a work request to collect and upload logcat.
         *
         * Note: We use a timestamped unique name so each request is distinct.
         */
        fun enqueueLogcatUpload(
            context: Context,
            cfg: GitHubUploader.GitHubConfig,
            remoteDir: String = "diagnostics/logs",
            addDateSubdir: Boolean = true,
            includeDeviceHeader: Boolean = true,
            includeCrashBuffer: Boolean = true,
            maxUncompressedBytes: Int = 850_000,
        ) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val uniqueName = "upload_logcat_$stamp"

            val req: OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_MODE to MODE_LOGCAT,
                            KEY_OWNER to cfg.owner,
                            KEY_REPO to cfg.repo,
                            KEY_TOKEN to cfg.token,
                            KEY_BRANCH to cfg.branch,
                            KEY_PATH_PREFIX to cfg.pathPrefix,

                            KEY_LOG_REMOTE_DIR to remoteDir,
                            KEY_LOG_ADD_DATE to addDateSubdir,
                            KEY_LOG_INCLUDE_HEADER to includeDeviceHeader,
                            KEY_LOG_INCLUDE_CRASH to includeCrashBuffer,
                            KEY_LOG_MAX_UNCOMPRESSED to maxUncompressedBytes,
                        )
                    )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .addTag(TAG)
                    .addTag("$TAG:logcat")
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, req)
        }
    }
}
