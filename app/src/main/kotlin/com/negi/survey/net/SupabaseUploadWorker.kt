/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SupabaseUploadWorker.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Foreground-capable WorkManager coroutine worker that uploads either:
 *   - one local payload file (streamed) to Supabase Storage, OR
 *   - a collected logcat snapshot (gzip) to Supabase Storage.
 *
 *  Important:
 *   - For voice WAV files, do NOT delete local file until all required
 *     destinations are satisfied (see VoiceUploadCompletionStore).
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
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class SupabaseUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val cfg = SupabaseUploader.SupabaseConfig(
            supabaseUrl = inputData.getString(KEY_URL).orEmpty(),
            anonKey = inputData.getString(KEY_ANON_KEY).orEmpty(),
            bucket = inputData.getString(KEY_BUCKET).orEmpty(),
            pathPrefix = inputData.getString(KEY_PATH_PREFIX).orEmpty().ifBlank { "surveyapp" },
            maxRawBytesHint = inputData.getLong(KEY_MAX_BYTES_HINT, DEFAULT_MAX_BYTES_HINT).coerceAtLeast(1L)
        )

        if (cfg.supabaseUrl.isBlank() || cfg.anonKey.isBlank() || cfg.bucket.isBlank()) {
            return Result.failure(
                workDataOf(ERROR_MESSAGE to "Invalid Supabase configuration (url/anonKey/bucket).")
            )
        }

        val mode = inputData.getString(KEY_MODE)?.lowercase(Locale.US) ?: MODE_FILE

        ensureChannel()

        val notifTitleBase = when (mode) {
            MODE_LOGCAT -> "Uploading logcat (Supabase)"
            else -> "Uploading payload (Supabase)"
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

            setProgressAsync(workDataOf(PROGRESS_PCT to clamped, PROGRESS_MODE to mode))
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

    private suspend fun doFileUpload(
        cfg: SupabaseUploader.SupabaseConfig,
        notifId: Int,
        onProgress: (Int) -> Unit,
        currentPct: () -> Int
    ): Result {

        val filePath = inputData.getString(KEY_FILE_PATH).orEmpty()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: File(filePath).name
        val contentType = inputData.getString(KEY_CONTENT_TYPE) ?: "application/octet-stream"

        if (filePath.isBlank()) return Result.failure(workDataOf(ERROR_MESSAGE to "Missing file path."))

        val f = File(filePath)
        if (!f.exists()) return Result.failure(workDataOf(ERROR_MESSAGE to "Pending file not found: $filePath"))

        val size = f.length()
        if (size <= 0L) return Result.failure(workDataOf(ERROR_MESSAGE to "Pending file is empty: $filePath"))

        val maxBytesHint = inputData.getLong(KEY_MAX_BYTES_HINT, DEFAULT_MAX_BYTES_HINT)
        if (size > maxBytesHint) {
            return Result.failure(
                workDataOf(
                    ERROR_MESSAGE to "File too large for this upload path (size=$size, limit~$maxBytesHint)."
                )
            )
        }

        val userJwt = inputData.getString(KEY_USER_JWT)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val remoteDir = inputData.getString(KEY_REMOTE_DIR)
            ?.trim()
            .orEmpty()
            .ifBlank { "regular" }

        val prefix = buildString {
            val p = cfg.pathPrefix.trim('/')
            val r = remoteDir.trim('/')
            if (p.isNotBlank()) append(p)
            if (r.isNotBlank()) {
                if (isNotEmpty()) append('/')
                append(r)
            }
        }

        val objectPath = SupabaseUploader.buildDatedObjectPath(prefix, fileName)

        val isVoice = isVoiceFile(f, contentType)

        Log.d(
            TAG,
            "doFileUpload: bucket=${cfg.bucket} objectPath=$objectPath file=$filePath size=$size " +
                    "contentType=$contentType isVoice=$isVoice upsert=${inputData.getBoolean(KEY_UPSERT, false)}"
        )

        return try {
            // For voice: assert required destination before upload.
            if (isVoice) {
                // Only require Supabase here; GitHub may be required by UI flow (stored already).
                VoiceUploadCompletionStore.requireDestinations(
                    context = applicationContext,
                    file = f,
                    requireGitHub = false,
                    requireSupabase = true
                )
            }

            val res = SupabaseUploader.uploadFile(
                cfg = cfg,
                objectPath = objectPath,
                file = f,
                contentType = contentType,
                upsert = inputData.getBoolean(KEY_UPSERT, false),
                tokenOverride = userJwt,
                onProgress = onProgress
            )

            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = 100,
                    title = "Uploaded $fileName (Supabase)",
                    finished = true
                )
            )

            // Delete policy:
            // - Voice WAV: delete only when all required destinations are satisfied.
            // - Others: delete immediately after successful upload.
            if (isVoice) {
                val st = VoiceUploadCompletionStore.markSupabaseUploaded(applicationContext, f)
                Log.d(
                    TAG,
                    "Voice flag update (SupabaseWorker): name=${f.name} reqGh=${st.requireGitHub} reqSb=${st.requireSupabase} " +
                            "ghDone=${st.githubUploaded} sbDone=${st.supabaseUploaded}"
                )

                if (VoiceUploadCompletionStore.shouldDeleteNow(applicationContext, f)) {
                    Log.d(TAG, "Voice delete eligible (SupabaseWorker): name=${f.name}")
                    runCatching { f.delete() }
                    VoiceUploadCompletionStore.clear(applicationContext, f)
                } else {
                    Log.d(TAG, "Voice kept (SupabaseWorker): waiting other required destination name=${f.name}")
                }
            } else {
                runCatching { f.delete() }
            }

            Result.success(
                workDataOf(
                    OUT_MODE to MODE_FILE,
                    OUT_FILE_NAME to fileName,
                    OUT_OBJECT_PATH to res.objectPath,
                    OUT_PUBLIC_URL to (res.publicUrl ?: ""),
                    OUT_ETAG to (res.etag ?: ""),
                    OUT_REQUEST_ID to (res.requestId ?: "")
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "doFileUpload: upload failed for $filePath (objectPath=$objectPath)", t)

            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = currentPct(),
                    title = "Upload failed: $fileName (Supabase)",
                    error = true
                )
            )

            val failData = workDataOf(ERROR_MESSAGE to (t.message ?: "Unknown error"))
            if (shouldRetry(t) && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(failData)
        }
    }

    private suspend fun doLogcatUpload(
        cfg: SupabaseUploader.SupabaseConfig,
        notifId: Int,
        onProgress: (Int) -> Unit,
        currentPct: () -> Int
    ): Result {
        val remoteDir = inputData.getString(KEY_REMOTE_DIR)?.ifBlank { "logcat" } ?: "logcat"
        val addDate = inputData.getBoolean(KEY_LOG_ADD_DATE, true)
        val includeHeader = inputData.getBoolean(KEY_LOG_INCLUDE_HEADER, true)
        val includeCrash = inputData.getBoolean(KEY_LOG_INCLUDE_CRASH, true)
        val maxBytes = inputData.getInt(KEY_LOG_MAX_UNCOMPRESSED, 850_000)

        val userJwt = inputData.getString(KEY_USER_JWT)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return try {
            val out = SupabaseLogUploader.collectAndUploadLogcat(
                context = applicationContext,
                cfg = cfg,
                remoteDir = remoteDir,
                addDateSubdir = addDate,
                includeDeviceHeader = includeHeader,
                maxUncompressedBytes = maxBytes,
                includeCrashBuffer = includeCrash,
                tokenOverride = userJwt,
                onProgress = onProgress
            )

            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = 100,
                    title = "Uploaded logcat (Supabase)",
                    finished = true
                )
            )

            Result.success(
                workDataOf(
                    OUT_MODE to MODE_LOGCAT,
                    OUT_OBJECT_PATH to out.objectPath,
                    OUT_PUBLIC_URL to (out.publicUrl ?: ""),
                    OUT_ETAG to (out.etag ?: ""),
                    OUT_REQUEST_ID to (out.requestId ?: ""),
                    OUT_BYTES_RAW to out.bytesRaw,
                    OUT_BYTES_GZ to out.bytesGz
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "doLogcatUpload: upload failed", t)

            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = currentPct(),
                    title = "Log upload failed (Supabase)",
                    error = true
                )
            )

            val failData = workDataOf(ERROR_MESSAGE to (t.message ?: "Unknown error"))
            if (shouldRetry(t) && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(failData)
        }
    }

    private fun shouldRetry(t: Throwable): Boolean {
        val msg = t.message.orEmpty()
        if (msg.contains("too large", ignoreCase = true)) return false
        if (msg.contains("invalid supabase configuration", ignoreCase = true)) return false

        if (msg.contains("401")) return false
        if (msg.contains("403")) return false
        if (msg.contains("row-level security", ignoreCase = true)) return false

        return t is IOException || msg.contains("timeout", ignoreCase = true)
    }

    private fun isVoiceFile(file: File, contentType: String): Boolean {
        val n = file.name.lowercase(Locale.US)
        if (n.endsWith(".wav")) return true
        return contentType.lowercase(Locale.US).contains("audio")
    }

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

        if (finished || error) builder.setProgress(0, 0, false)
        else builder.setProgress(100, pct.coerceIn(0, 100), false)

        val notification = builder.build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            @Suppress("DEPRECATION")
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background Uploads (Supabase)",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Displays progress for ongoing uploads to Supabase Storage."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val TAG = "supabase_upload"

        private const val CHANNEL_ID = "uploads_supabase"
        private const val NOTIF_BASE = 4200
        private const val MAX_ATTEMPTS = 5

        private const val MODE_FILE = "file"
        private const val MODE_LOGCAT = "logcat"

        const val PROGRESS_PCT = "pct"
        const val PROGRESS_MODE = "mode"

        // Config keys
        const val KEY_URL = "sb.url"
        const val KEY_ANON_KEY = "sb.anonKey"
        const val KEY_BUCKET = "sb.bucket"
        const val KEY_PATH_PREFIX = "sb.pathPrefix"
        const val KEY_MODE = "mode"
        const val KEY_USER_JWT = "sb.userJwt"

        // File keys
        const val KEY_FILE_PATH = "filePath"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_MAX_BYTES_HINT = "file.maxBytesHint"
        const val KEY_REMOTE_DIR = "remoteDir"
        const val KEY_CONTENT_TYPE = "contentType"
        const val KEY_UPSERT = "upsert"

        // Logcat keys
        const val KEY_LOG_ADD_DATE = "log.addDate"
        const val KEY_LOG_INCLUDE_HEADER = "log.includeHeader"
        const val KEY_LOG_INCLUDE_CRASH = "log.includeCrash"
        const val KEY_LOG_MAX_UNCOMPRESSED = "log.maxUncompressed"

        // Output keys
        const val OUT_MODE = "out.mode"
        const val OUT_FILE_NAME = "out.fileName"
        const val OUT_OBJECT_PATH = "out.objectPath"
        const val OUT_PUBLIC_URL = "out.publicUrl"
        const val OUT_ETAG = "out.etag"
        const val OUT_REQUEST_ID = "out.requestId"
        const val OUT_BYTES_RAW = "out.bytesRaw"
        const val OUT_BYTES_GZ = "out.bytesGz"

        const val ERROR_MESSAGE = "error"

        private const val DEFAULT_MAX_BYTES_HINT = 20_000_000L

        private fun sanitizeWorkName(value: String): String {
            return value
                .trim()
                .replace(Regex("""[^\w\-.]+"""), "_")
                .take(120)
        }

        fun enqueueExistingPayload(
            context: Context,
            cfg: SupabaseUploader.SupabaseConfig,
            file: File,
            remoteDir: String = "regular",
            contentType: String = "application/octet-stream",
            upsert: Boolean = false,
            userJwt: String? = null,
            maxBytesHint: Long = cfg.maxRawBytesHint
        ) {
            val name = file.name
            val safeName = sanitizeWorkName("$remoteDir-$name")

            val req: OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<SupabaseUploadWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_MODE to MODE_FILE,
                            KEY_URL to cfg.supabaseUrl,
                            KEY_ANON_KEY to cfg.anonKey,
                            KEY_BUCKET to cfg.bucket,
                            KEY_PATH_PREFIX to cfg.pathPrefix,
                            KEY_FILE_PATH to file.absolutePath,
                            KEY_FILE_NAME to name,
                            KEY_REMOTE_DIR to remoteDir,
                            KEY_CONTENT_TYPE to contentType,
                            KEY_UPSERT to upsert,
                            KEY_USER_JWT to (userJwt ?: ""),
                            KEY_MAX_BYTES_HINT to maxBytesHint
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
                    .addTag("$TAG:file:$safeName")
                    .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork("sb_upload_$safeName", ExistingWorkPolicy.KEEP, req)
        }

        fun enqueueLogcatUpload(
            context: Context,
            cfg: SupabaseUploader.SupabaseConfig,
            remoteDir: String = "logcat",
            addDateSubdir: Boolean = true,
            includeDeviceHeader: Boolean = true,
            includeCrashBuffer: Boolean = true,
            maxUncompressedBytes: Int = 850_000,
            userJwt: String? = null
        ) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val uniqueName = "sb_upload_logcat_$stamp"

            val req: OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<SupabaseUploadWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_MODE to MODE_LOGCAT,
                            KEY_URL to cfg.supabaseUrl,
                            KEY_ANON_KEY to cfg.anonKey,
                            KEY_BUCKET to cfg.bucket,
                            KEY_PATH_PREFIX to cfg.pathPrefix,
                            KEY_REMOTE_DIR to remoteDir,
                            KEY_LOG_ADD_DATE to addDateSubdir,
                            KEY_LOG_INCLUDE_HEADER to includeDeviceHeader,
                            KEY_LOG_INCLUDE_CRASH to includeCrashBuffer,
                            KEY_LOG_MAX_UNCOMPRESSED to maxUncompressedBytes,
                            KEY_USER_JWT to (userJwt ?: "")
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
