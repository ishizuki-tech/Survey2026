/*
 * =====================================================================
 *  IshizukiTech LLC — Android App Shell
 *  ---------------------------------------------------------------------
 *  File: SupabaseCrashUploadWorker.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.survey.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
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
import com.negi.survey.BuildConfig
import com.negi.survey.R
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class SupabaseCrashUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val filePath = inputData.getString(KEY_FILE_PATH).orEmpty()
        val objectPrefix = inputData.getString(KEY_OBJECT_PREFIX).orEmpty()
        val addDate = inputData.getBoolean(KEY_ADD_DATE, true)
        val upsert = inputData.getBoolean(KEY_UPSERT, false)

        Log.d(
            TAG,
            "doWork start attempt=$runAttemptCount filePath=$filePath prefix=$objectPrefix addDate=$addDate upsert=$upsert"
        )

        if (filePath.isBlank()) {
            Log.w(TAG, "Missing file path.")
            return Result.failure(workDataOf(ERROR_MESSAGE to "Missing file path."))
        }

        if (!isConfigured()) {
            Log.w(
                TAG,
                "Supabase not configured. urlSet=${BuildConfig.SUPABASE_URL.isNotBlank()} " +
                        "keySet=${BuildConfig.SUPABASE_ANON_KEY.isNotBlank()} bucket=${BuildConfig.SUPABASE_LOG_BUCKET}"
            )
            return Result.failure(workDataOf(ERROR_MESSAGE to "Supabase is not configured."))
        }

        val file = File(filePath)
        if (!file.exists() || file.length() <= 0L) {
            Log.w(TAG, "Crash file missing or empty: $filePath")
            return Result.failure(workDataOf(ERROR_MESSAGE to "Crash file missing or empty: $filePath"))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureChannel()
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val notifId = NOTIF_BASE + (abs(("supabase_crash_$stamp").hashCode()) % 8000)

        // NOTE: setForegroundAsync is safe from non-suspend callbacks too.
        runCatching {
            setForegroundAsync(
                foregroundInfo(
                    notificationId = notifId,
                    pct = 0,
                    title = "Uploading crash log…"
                )
            )
        }.onFailure { t ->
            Log.w(TAG, "setForegroundAsync failed (continuing): ${t.message}", t)
        }

        val remotePath = buildRemotePath(
            prefix = objectPrefix.ifBlank { DEFAULT_PREFIX },
            fileName = file.name,
            addDateSubdir = addDate
        )

        val totalBytes = file.length()
        Log.d(TAG, "Uploading crash file: name=${file.name} bytes=$totalBytes remotePath=$remotePath")

        return try {
            uploadCrashFile(
                file = file,
                remotePath = remotePath,
                upsert = upsert,
                onProgress = { pct ->
                    // IMPORTANT: onProgress is not suspend; use Async APIs.
                    runCatching {
                        setProgressAsync(workDataOf(PROGRESS_PCT to pct, OUT_REMOTE_PATH to remotePath))
                    }
                    runCatching {
                        setForegroundAsync(
                            foregroundInfo(
                                notificationId = notifId,
                                pct = pct,
                                title = "Uploading crash log…"
                            )
                        )
                    }
                }
            )

            runCatching {
                setForegroundAsync(
                    foregroundInfo(
                        notificationId = notifId,
                        pct = 100,
                        title = "Uploaded crash log",
                        finished = true
                    )
                )
            }

            val bytesBeforeDelete = file.length()
            runCatching { file.delete() }
                .onFailure { Log.w(TAG, "Failed to delete local crash file: ${file.absolutePath}", it) }

            Log.d(TAG, "Upload success: remotePath=$remotePath bytes=$bytesBeforeDelete")

            Result.success(
                workDataOf(
                    OUT_REMOTE_PATH to remotePath,
                    OUT_BYTES to bytesBeforeDelete
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Supabase crash upload failed: remotePath=$remotePath file=$filePath", t)

            runCatching {
                setForegroundAsync(
                    foregroundInfo(
                        notificationId = notifId,
                        pct = 0,
                        title = "Crash upload failed",
                        error = true
                    )
                )
            }

            if (shouldRetry(t) && runAttemptCount < MAX_ATTEMPTS) {
                Log.w(TAG, "Retrying (attempt=$runAttemptCount/${MAX_ATTEMPTS - 1}) reason=${t.message}")
                Result.retry()
            } else {
                Result.failure(workDataOf(ERROR_MESSAGE to (t.message ?: "Unknown error")))
            }
        }
    }

    private fun uploadCrashFile(
        file: File,
        remotePath: String,
        upsert: Boolean,
        onProgress: (Int) -> Unit
    ) {
        require(remotePath.isNotBlank()) { "remotePath is blank." }

        val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
        val bucket = BuildConfig.SUPABASE_LOG_BUCKET.trim()
        val anonKey = BuildConfig.SUPABASE_ANON_KEY.trim()

        // Encode each path segment.
        val encodedRemotePath = remotePath
            .split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { seg -> Uri.encode(seg) }

        val url = URL("$baseUrl/storage/v1/object/$bucket/$encodedRemotePath")
        val total = file.length().coerceAtLeast(1L)

        Log.d(
            TAG,
            "Supabase upload: url=${redactUrl(url.toString())} bucket=$bucket " +
                    "path=$remotePath encodedPath=$encodedRemotePath bytes=${file.length()} upsert=$upsert"
        )

        try {
            uploadWithMethod(url, "POST", anonKey, file, total, upsert, onProgress)
        } catch (e: HttpStatusException) {
            if (e.code == 405 || e.code == 404) {
                Log.w(TAG, "POST not accepted (HTTP ${e.code}). Retrying with PUT… body=${truncate(e.body)}")
                uploadWithMethod(url, "PUT", anonKey, file, total, upsert, onProgress)
            } else {
                throw e
            }
        }
    }

    private fun uploadWithMethod(
        url: URL,
        method: String,
        anonKey: String,
        file: File,
        totalBytes: Long,
        upsert: Boolean,
        onProgress: (Int) -> Unit
    ) {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            doOutput = true
            doInput = true
            useCaches = false

            connectTimeout = 15_000
            readTimeout = 35_000

            // Supabase Storage REST auth headers
            setRequestProperty("Authorization", "Bearer $anonKey")
            setRequestProperty("apikey", anonKey)
            setRequestProperty("Accept", "application/json")

            setRequestProperty("Content-Type", "application/gzip")

            // IMPORTANT:
            // Upsert may require UPDATE policy in RLS; keep it OFF by default for crash logs.
            if (upsert) {
                setRequestProperty("x-upsert", "true")
            }

            // Make streaming deterministic (and avoid internal buffering surprises).
            if (totalBytes <= Int.MAX_VALUE.toLong()) {
                setFixedLengthStreamingMode(totalBytes.toInt())
            } else {
                setChunkedStreamingMode(256 * 1024)
            }
        }

        var sent = 0L
        try {
            BufferedInputStream(file.inputStream(), 256 * 1024).use { input ->
                conn.outputStream.use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        sent += n

                        val pct = ((sent * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        onProgress(pct)
                    }
                    output.flush()
                }
            }

            val code = conn.responseCode
            val body = readResponseBody(conn, code)

            Log.d(TAG, "HTTP $method -> code=$code sent=$sent/$totalBytes body=${truncate(body)}")

            if (code !in 200..299) {
                throw HttpStatusException(code = code, body = body)
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun buildRemotePath(prefix: String, fileName: String, addDateSubdir: Boolean): String {
        val base = prefix.trim('/').ifBlank { DEFAULT_PREFIX }
        val date = if (addDateSubdir) SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) else null
        return listOfNotNull(base, date, fileName.trim('/'))
            .filter { it.isNotBlank() }
            .joinToString("/")
    }

    private fun shouldRetry(t: Throwable): Boolean {
        // NOTE: Prefer code-based retry logic when we have it.
        val code = (t as? HttpStatusException)?.code
        if (code != null) {
            if (code == 401 || code == 403 || code == 422) return false
            if (code == 429) return true
            if (code in 500..599) return true
            return false
        }

        val msg = t.message.orEmpty()
        if (msg.contains("401")) return false
        if (msg.contains("403")) return false
        if (msg.contains("422")) return false
        if (msg.contains("bad credentials", ignoreCase = true)) return false

        return t is IOException || msg.contains("timeout", ignoreCase = true)
    }

    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background Uploads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Displays progress for ongoing uploads."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
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

    private fun readResponseBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        return runCatching { stream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
            .getOrElse { "" }
    }

    private fun truncate(s: String, max: Int = 600): String {
        if (s.length <= max) return s
        return s.take(max) + "…(truncated)"
    }

    private fun redactUrl(url: String): String {
        return url.substringBefore("?")
    }

    private class HttpStatusException(val code: Int, val body: String) :
        IOException("HTTP $code: ${body.take(200)}")

    companion object {
        private const val TAG = "SupabaseCrashUpload"
        private const val CHANNEL_ID = "uploads"
        private const val NOTIF_BASE = 5200
        private const val MAX_ATTEMPTS = 5

        // IMPORTANT:
        // This prefix already includes "surveyapp/" so it matches typical RLS patterns.
        private const val DEFAULT_PREFIX = "surveyapp/crash"

        const val PROGRESS_PCT = "pct"

        const val KEY_FILE_PATH = "filePath"
        const val KEY_OBJECT_PREFIX = "objectPrefix"
        const val KEY_ADD_DATE = "addDate"
        const val KEY_UPSERT = "upsert"

        const val OUT_REMOTE_PATH = "out.remotePath"
        const val OUT_BYTES = "out.bytes"
        const val ERROR_MESSAGE = "error"

        fun isConfigured(): Boolean {
            return BuildConfig.SUPABASE_URL.isNotBlank() &&
                    BuildConfig.SUPABASE_ANON_KEY.isNotBlank() &&
                    BuildConfig.SUPABASE_LOG_BUCKET.isNotBlank()
        }

        fun enqueueExistingCrash(
            context: Context,
            file: File,
            objectPrefix: String = DEFAULT_PREFIX,
            addDateSubdir: Boolean = true,
            upsert: Boolean = false
        ) {
            val name = file.name
            val uniqueName = "upload_supabase_crash_$name"

            val req: OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<SupabaseCrashUploadWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_FILE_PATH to file.absolutePath,
                            KEY_OBJECT_PREFIX to objectPrefix,
                            KEY_ADD_DATE to addDateSubdir,
                            KEY_UPSERT to upsert
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

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, req)

            Log.d(TAG, "Enqueued crash upload: unique=$uniqueName file=${file.absolutePath} bytes=${file.length()} upsert=$upsert")
        }
    }
}
