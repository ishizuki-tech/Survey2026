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
 *   - a collected logcat snapshot (gzip) using GitHubUploader, OR
 *   - app-owned runtime logs bundle (zip) using RuntimeLogStore + GitHubUploader.
 *
 *  Notes:
 *   - GitHubLogUploader is intentionally NOT used.
 *   - Logcat snapshot is collected inside this Worker and uploaded via GitHubUploader.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Process
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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.negi.survey.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * Coroutine-based [WorkManager] worker responsible for uploading either:
 *  - a local file, or
 *  - a logcat snapshot (collected here), or
 *  - app-owned runtime logs bundle (zip).
 */
class GitHubUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    /**
     * WorkManager may require ForegroundInfo *before* calling doWork() for expedited work.
     *
     * IMPORTANT:
     * - In CoroutineWorker, getForegroundInfoAsync() is a final bridge.
     * - Override this suspend function instead.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val mode = inputData.getString(KEY_MODE)?.lowercase(Locale.US) ?: MODE_FILE
        ensureChannel()

        val titleBase = notifTitleBase(mode)
        val notifId = stableNotificationId(mode = mode, key = id.toString())

        return foregroundInfo(
            notificationId = notifId,
            pct = 0,
            title = "$titleBase…"
        )
    }

    override suspend fun doWork(): Result {
        // Keep Worker-side hint and Uploader-side hint consistent.
        val maxFileBytesHint =
            inputData.getLong(KEY_FILE_MAX_BYTES_HINT, DEFAULT_MAX_RAW_BYTES_HINT)
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

        val notifTitleBase = notifTitleBase(mode)

        // Stable notification id for this Work (must match getForegroundInfo()).
        val notifId = stableNotificationId(mode = mode, key = id.toString())

        // Best-effort foreground. If the app lacks required FGS permissions/types,
        // we still try to continue as a normal background worker.
        runCatching {
            setForeground(
                foregroundInfo(
                    notificationId = notifId,
                    pct = 0,
                    title = "$notifTitleBase…"
                )
            )
        }.onFailure { e ->
            Log.w(TAG, "doWork: setForeground failed (continuing). err=${e.message}", e)
        }

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

            // Do not block progress callback; best-effort notification update.
            runCatching {
                setForegroundAsync(
                    foregroundInfo(
                        notificationId = notifId,
                        pct = clamped,
                        title = "$notifTitleBase…"
                    )
                )
            }
        }

        val currentPct: () -> Int = { lastPctRef[0].coerceAtLeast(0) }

        return when (mode) {
            MODE_LOGCAT -> doLogcatUpload(cfg, notifId, progressCallback, currentPct)
            MODE_RUNTIME_LOGS -> doRuntimeLogsUpload(cfg, notifId, progressCallback, currentPct)
            else -> doFileUpload(cfg, notifId, progressCallback, currentPct)
        }
    }

    private fun notifTitleBase(mode: String): String {
        return when (mode) {
            MODE_LOGCAT -> "Uploading logcat"
            MODE_RUNTIME_LOGS -> "Uploading runtime logs"
            else -> "Uploading payload"
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

        val maxBytesHint = inputData.getLong(KEY_FILE_MAX_BYTES_HINT, DEFAULT_MAX_RAW_BYTES_HINT)
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

            runCatching {
                setForegroundAsync(
                    foregroundInfo(
                        notificationId = notifId,
                        pct = 100,
                        title = "Uploaded $fileName",
                        finished = true
                    )
                )
            }

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

            runCatching {
                setForegroundAsync(
                    foregroundInfo(
                        notificationId = notifId,
                        pct = currentPct(),
                        title = "Upload failed: $fileName",
                        error = true
                    )
                )
            }

            val failData = workDataOf(ERROR_MESSAGE to (t.message ?: "Unknown error"))

            if (shouldRetry(t) && runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure(failData)
        }
    }

    /**
     * Execute logcat upload mode.
     *
     * GitHubLogUploader is NOT used. Collection happens here and we upload via GitHubUploader.
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
            // Phase A: collect + gzip (0..20)
            onProgress(3)

            val snap = collectLogcatSnapshotGz(
                context = applicationContext,
                includeDeviceHeader = includeHeader,
                includeCrashBuffer = includeCrash,
                maxUncompressedBytes = maxBytes
            )

            onProgress(20)

            // Remote path:
            //   pathPrefix / remoteDir / (yyyy-MM-dd)? / logcat_<stamp>_pid<pid>.log.gz
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val pid = Process.myPid()
            val dateSegment = if (addDate) utcDateFolder() else ""
            val remoteName = "logcat_${stamp}_pid${pid}.log.gz"
            val remotePath = listOf(
                cfg.pathPrefix.trim('/'),
                remoteDir.trim('/'),
                dateSegment,
                remoteName
            ).filter { it.isNotBlank() }.joinToString("/")

            // Phase B: upload (20..100) without regressing progress.
            val mappedProgress = mapProgressRange(start = 20, end = 100, sink = onProgress)

            val result = GitHubUploader.uploadBytesAtPath(
                cfg = cfg,
                path = remotePath,
                bytes = snap.gzBytes,
                message = "Upload logcat snapshot",
                onProgress = mappedProgress
            )

            runCatching {
                setForegroundAsync(
                    foregroundInfo(
                        notificationId = notifId,
                        pct = 100,
                        title = "Uploaded logcat",
                        finished = true
                    )
                )
            }

            Result.success(
                workDataOf(
                    OUT_MODE to MODE_LOGCAT,
                    OUT_REMOTE_PATH to remotePath,
                    OUT_COMMIT_SHA to (result.commitSha ?: ""),
                    OUT_FILE_URL to (result.fileUrl ?: ""),
                    OUT_BYTES_RAW to snap.rawBytes.toLong(),
                    OUT_BYTES_GZ to snap.gzBytes.size.toLong(),
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "doLogcatUpload: upload failed", t)

            runCatching {
                setForegroundAsync(
                    foregroundInfo(
                        notificationId = notifId,
                        pct = currentPct(),
                        title = "Log upload failed",
                        error = true
                    )
                )
            }

            val failData = workDataOf(ERROR_MESSAGE to (t.message ?: "Unknown error"))

            if (shouldRetry(t) && runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure(failData)
        }
    }

    /**
     * Execute runtime logs upload mode (app-owned logs, zipped).
     *
     * This uploads RuntimeLogStore rolling files as a single ZIP bundle.
     *
     * Important:
     * - GitHubUploader starts progress from 0. If we already advanced progress (zip phase),
     *   we must map uploader progress to a later progress range to avoid "progress going backwards".
     */
    private suspend fun doRuntimeLogsUpload(
        cfg: GitHubUploader.GitHubConfig,
        notifId: Int,
        onProgress: (Int) -> Unit,
        currentPct: () -> Int,
    ): Result {
        val remoteDir = inputData.getString(KEY_RTLOG_REMOTE_DIR) ?: "diagnostics/runtime_logs"
        val addDate = inputData.getBoolean(KEY_RTLOG_ADD_DATE, true)
        val reason = inputData.getString(KEY_RTLOG_REASON)?.takeIf { it.isNotBlank() } ?: "wm"
        val deleteZipAfter = inputData.getBoolean(KEY_RTLOG_DELETE_ZIP_AFTER, true)

        // Optional size guards.
        val maxZipBytes = inputData.getLong(KEY_RTLOG_MAX_ZIP_BYTES, DEFAULT_MAX_RAW_BYTES_HINT)

        return try {
            // Ensure store is started (idempotent).
            runCatching { RuntimeLogStore.start(applicationContext) }

            // Phase A: build snapshot zip (0..15)
            onProgress(3)

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val pid = Process.myPid()
            val safeReason = safeFileSegment(reason)
            val zip = RuntimeLogStore.zipForUpload(reason = safeReason)

            onProgress(15)

            val zipBytes = zip.length().coerceAtLeast(0L)
            if (zipBytes <= 0L) {
                return Result.failure(workDataOf(ERROR_MESSAGE to "Runtime log zip is empty."))
            }

            if (zipBytes > maxZipBytes) {
                val msg =
                    "Runtime log zip too large (size=$zipBytes, limit~$maxZipBytes). " +
                            "Consider lowering RuntimeLogStore retention or splitting bundles."
                return Result.failure(workDataOf(ERROR_MESSAGE to msg))
            }

            // Remote path:
            //   pathPrefix / remoteDir / (yyyy-MM-dd)? / runtime_logs_<stamp>_pid<pid>_<reason>.zip
            val dateSegment = if (addDate) utcDateFolder() else ""
            val remoteName = "runtime_logs_${stamp}_pid${pid}_${safeReason}.zip"
            val remotePath = listOf(
                cfg.pathPrefix.trim('/'),
                remoteDir.trim('/'),
                dateSegment,
                remoteName
            ).filter { it.isNotBlank() }.joinToString("/")

            Log.d(
                TAG,
                "doRuntimeLogsUpload: owner=${cfg.owner} repo=${cfg.repo} branch=${cfg.branch} " +
                        "remotePath=$remotePath zip=${zip.absolutePath} bytes=$zipBytes"
            )

            // Phase B: upload (15..100) without regressing progress.
            val mappedProgress = mapProgressRange(
                start = 15,
                end = 100,
                sink = onProgress
            )

            val result = GitHubUploader.uploadFileAtPath(
                cfg = cfg,
                path = remotePath,
                file = zip,
                message = "Upload runtime logs ($reason)",
                onProgress = mappedProgress
            )

            runCatching {
                setForegroundAsync(
                    foregroundInfo(
                        notificationId = notifId,
                        pct = 100,
                        title = "Uploaded runtime logs",
                        finished = true
                    )
                )
            }

            if (deleteZipAfter) runCatching { zip.delete() }

            Result.success(
                workDataOf(
                    OUT_MODE to MODE_RUNTIME_LOGS,
                    OUT_REMOTE_PATH to remotePath,
                    OUT_FILE_NAME to zip.name,
                    OUT_COMMIT_SHA to (result.commitSha ?: ""),
                    OUT_FILE_URL to (result.fileUrl ?: ""),
                    OUT_BYTES_RAW to zipBytes,
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "doRuntimeLogsUpload: upload failed", t)

            runCatching {
                setForegroundAsync(
                    foregroundInfo(
                        notificationId = notifId,
                        pct = currentPct(),
                        title = "Runtime log upload failed",
                        error = true
                    )
                )
            }

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
     * Returns UTC yyyy-MM-dd for stable storage paths across devices/timezones.
     */
    private fun utcDateFolder(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    /**
     * Map progress from 0..100 into [start]..[end] and keep it monotonic.
     */
    private fun mapProgressRange(
        start: Int,
        end: Int,
        sink: (Int) -> Unit
    ): (Int) -> Unit {
        val s = start.coerceIn(0, 100)
        val e = end.coerceIn(0, 100)
        val lo = minOf(s, e)
        val hi = maxOf(s, e)

        var last = lo
        return { p ->
            val clamped = p.coerceIn(0, 100)
            val mapped = lo + ((clamped / 100.0) * (hi - lo)).toInt()
            val mono = maxOf(last, mapped.coerceIn(lo, hi))
            last = mono
            sink(mono)
        }
    }

    /**
     * Sanitize a string to be safe as a repo path segment / filename fragment.
     */
    private fun safeFileSegment(s: String): String {
        val t = s.trim().ifBlank { "wm" }
        return t.replace(Regex("""[^A-Za-z0-9_\-\.]+"""), "_").take(24)
    }

    /**
     * Determine if the throwable should be treated as transient.
     */
    private fun shouldRetry(t: Throwable): Boolean {
        val msg = t.message.orEmpty()

        // If uploader labeled it transient, allow retry even for 403/429 (rate limit).
        if (msg.startsWith("Transient HTTP ", ignoreCase = true)) return true

        // Avoid retrying obvious auth/permanent failures.
        val httpCode = Regex("""\((\d{3})\)""").find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (httpCode != null) {
            // Retry only 429 in the 4xx family. Other 4xx are usually permanent for this request.
            if (httpCode in 400..499 && httpCode != 429) return false
        }

        if (msg.contains("too large", ignoreCase = true)) return false
        if (msg.contains("invalid github configuration", ignoreCase = true)) return false
        if (msg.contains("bad credentials", ignoreCase = true)) return false
        if (msg.contains("requires authentication", ignoreCase = true)) return false

        // Conservative: 403 is ambiguous, but if it wasn't labeled transient, treat as permanent.
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

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

    /**
     * Collect logcat snapshot and gzip it.
     *
     * Note:
     * - Some devices/ROMs restrict logcat. This returns best-effort output.
     * - PID-filtered logcat may be unsupported; fallback is used automatically.
     */
    private fun collectLogcatSnapshotGz(
        context: Context,
        includeDeviceHeader: Boolean,
        includeCrashBuffer: Boolean,
        maxUncompressedBytes: Int
    ): LogcatSnapshot {
        val pid = Process.myPid()

        val header = if (includeDeviceHeader) buildDeviceHeader(context, pid) else ""
        val main = collectLogcatTail(pid = pid, tailLines = LOGCAT_TAIL_LINES)
        val crash = if (includeCrashBuffer) collectLogcatCrashTail(tailLines = LOGCAT_CRASH_TAIL_LINES) else ""

        val combined = buildString {
            appendLine("=== Logcat Snapshot (Worker) ===")
            appendLine()
            if (header.isNotBlank()) {
                append(header)
                appendLine()
            }
            appendLine("=== Logcat (tail) ===")
            appendLine(main)
            if (includeCrashBuffer) {
                appendLine()
                appendLine("=== Logcat crash buffer (tail) ===")
                appendLine(crash)
            }
            appendLine()
        }.toByteArray(Charsets.UTF_8)

        val trimmed = trimToTail(combined, maxUncompressedBytes.coerceAtLeast(50_000))
        val gz = gzip(trimmed)

        return LogcatSnapshot(
            rawBytes = trimmed.size,
            gzBytes = gz
        )
    }

    private data class LogcatSnapshot(
        val rawBytes: Int,
        val gzBytes: ByteArray
    )

    private fun buildDeviceHeader(context: Context, pid: Int): String {
        val pkg = context.packageName
        val pm = context.packageManager
        val info = getPackageInfoCompat(pm, pkg)

        val versionName = info?.versionName ?: "unknown"
        val versionCode = getVersionCodeCompat(info)

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val utc = sdf.format(Date())

        return buildString {
            appendLine("time_utc=$utc")
            appendLine("package=$pkg")
            appendLine("versionName=$versionName")
            appendLine("versionCode=$versionCode")
            appendLine("pid=$pid")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        }
    }

    private fun getPackageInfoCompat(pm: PackageManager, pkg: String): PackageInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
        }.getOrNull()
    }

    private fun getVersionCodeCompat(pkgInfo: PackageInfo?): Long {
        if (pkgInfo == null) return -1L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }
    }

    private fun collectLogcatTail(pid: Int, tailLines: Int): String {
        val cmdPid = listOf(
            "logcat",
            "-d",
            "--pid=$pid",
            "-v", "threadtime",
            "-t", tailLines.toString()
        )
        val out = runCommand(cmdPid, timeoutMs = 1500L, maxStdoutBytes = COMMAND_STDOUT_MAX_BYTES)
        if (!looksLikePidUnsupported(out)) return out

        val fb = listOf(
            "logcat",
            "-d",
            "-v", "threadtime",
            "-t", tailLines.toString()
        )
        return buildString {
            appendLine("=== WARNING ===")
            appendLine("PID-filtered logcat is not available on this device/runtime.")
            appendLine("Fallback logcat dump may include other processes.")
            appendLine("================")
            appendLine()
            append(runCommand(fb, timeoutMs = 1500L, maxStdoutBytes = COMMAND_STDOUT_MAX_BYTES))
        }
    }

    private fun collectLogcatCrashTail(tailLines: Int): String {
        val cmd = listOf(
            "logcat",
            "-d",
            "-b", "crash",
            "-v", "threadtime",
            "-t", tailLines.toString()
        )
        return runCommand(cmd, timeoutMs = 1500L, maxStdoutBytes = COMMAND_STDOUT_MAX_BYTES)
    }

    private fun runCommand(cmd: List<String>, timeoutMs: Long, maxStdoutBytes: Int): String {
        return try {
            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()

            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { proc.destroy() }
                runCatching { proc.destroyForcibly() }
                return "(command timeout: ${cmd.joinToString(" ")})\n"
            }

            val out = proc.inputStream.use { readTextLimited(it, maxStdoutBytes) }
            runCatching { proc.destroy() }
            out.ifBlank { "(logcat empty or restricted)\n" }
        } catch (e: Throwable) {
            "(command failed: ${e.message})\n"
        }
    }

    private fun readTextLimited(input: InputStream, maxBytes: Int): String {
        return runCatching {
            val buf = ByteArray(8_192)
            val bos = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                val remain = maxBytes - total
                if (remain <= 0) break
                val toWrite = minOf(n, remain)
                bos.write(buf, 0, toWrite)
                total += toWrite
                if (total >= maxBytes) break
            }
            bos.toString(Charsets.UTF_8.name())
        }.getOrElse { e ->
            "(read failed: ${e.message})\n"
        }
    }

    private fun looksLikePidUnsupported(output: String): Boolean {
        val s = output.lowercase(Locale.US)
        val mentionsPid = s.contains("pid") || s.contains("--pid")
        val looksLikeOptionError =
            s.contains("unknown option") ||
                    s.contains("unrecognized option") ||
                    s.contains("invalid option") ||
                    s.contains("unknown argument") ||
                    (s.contains("unknown") && s.contains("--pid")) ||
                    (s.contains("usage:") && s.contains("logcat") && s.contains("pid"))
        return mentionsPid && looksLikeOptionError
    }

    private fun trimToTail(bytes: ByteArray, maxBytes: Int): ByteArray {
        if (bytes.size <= maxBytes) return bytes
        val start = bytes.size - maxBytes
        return bytes.copyOfRange(start, bytes.size)
    }

    private fun gzip(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    companion object {

        const val TAG = "github_upload"

        private const val CHANNEL_ID = "uploads"
        private const val NOTIF_BASE = 3200
        private const val MAX_ATTEMPTS = 5

        /**
         * Default (conservative) guardrail for Contents API workflows.
         *
         * GitHub docs note that the "Get repository content" endpoint fully supports features
         * for files 1 MB or smaller; larger sizes can be problematic for tooling and APIs.
         */
        private const val DEFAULT_MAX_RAW_BYTES_HINT = 1_000_000L

        /**
         * Soft guardrail for command output read.
         */
        private const val COMMAND_STDOUT_MAX_BYTES = 700_000

        private const val LOGCAT_TAIL_LINES = 1200
        private const val LOGCAT_CRASH_TAIL_LINES = 200

        private val TEXT_EXTENSIONS = setOf("json", "jsonl", "txt", "csv")

        // Modes
        private const val MODE_FILE = "file"
        private const val MODE_LOGCAT = "logcat"
        private const val MODE_RUNTIME_LOGS = "runtime_logs"

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

        // Runtime logs (app-owned) input keys
        const val KEY_RTLOG_REMOTE_DIR = "rtlog.remoteDir"
        const val KEY_RTLOG_ADD_DATE = "rtlog.addDate"
        const val KEY_RTLOG_REASON = "rtlog.reason"
        const val KEY_RTLOG_DELETE_ZIP_AFTER = "rtlog.deleteZipAfter"
        const val KEY_RTLOG_MAX_ZIP_BYTES = "rtlog.maxZipBytes"

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
            val overhead = 200_000L
            return b64 + overhead
        }

        /**
         * Estimate a reasonable request-bytes guard from raw-bytes hint.
         *
         * Base64 size (ceil(n/3)*4) + overhead, with a minimum floor.
         */
        private fun estimateRequestBytesHint(rawBytesHint: Int): Int {
            val est = estimateBase64RequestBytes(rawBytesHint.toLong())
            val floor = 2_800_000L
            val out = maxOf(floor, est)
            return out.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        /**
         * Deterministic notification id that never goes negative.
         *
         * Important:
         * - Must be stable for the lifetime of this Work instance so that
         *   getForegroundInfo() and doWork() refer to the same notification id.
         */
        private fun stableNotificationId(mode: String, key: String): Int {
            val h = (mode + ":" + key).hashCode().toLong()
            val nonNeg = h and 0x7fffffffL
            return NOTIF_BASE + (nonNeg % 8000L).toInt()
        }

        /**
         * Choose ExistingWorkPolicy based on current unique work state.
         *
         * Rationale:
         * - KEEP prevents duplicates while a work is in-flight.
         * - REPLACE allows re-enqueue after FAILED/SUCCEEDED/CANCELLED chains,
         *   which is critical when config (token) was fixed later.
         */
        private fun choosePolicyForUniqueName(context: Context, uniqueName: String): ExistingWorkPolicy {
            return try {
                val infos = WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(uniqueName)
                    .get(350, TimeUnit.MILLISECONDS)

                val states = infos.joinToString(",") { it.state.name }
                val inFlight = infos.any {
                    it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.BLOCKED
                }

                val policy = if (inFlight) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
                Log.d(TAG, "choosePolicy: uniqueName=$uniqueName policy=$policy states=[$states]")
                policy
            } catch (t: Throwable) {
                Log.w(TAG, "choosePolicy: fallback KEEP (query failed). uniqueName=$uniqueName err=${t.message}")
                ExistingWorkPolicy.KEEP
            }
        }

        /**
         * Enqueue a work request to upload an existing file.
         */
        fun enqueueExistingPayload(
            context: Context,
            cfg: GitHubUploader.GitHubConfig,
            file: File,
            maxBytesHint: Long = DEFAULT_MAX_RAW_BYTES_HINT,
            maxRequestBytesHint: Int = estimateRequestBytesHint(
                maxBytesHint.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
        ) {
            val name = file.name

            // Include size+mtime to avoid suppressing uploads for "same name but different content".
            val uniqueName = "upload_${name}_${file.length()}_${file.lastModified()}"

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

            val appCtx = context.applicationContext
            val policy = choosePolicyForUniqueName(appCtx, uniqueName)

            Log.d(
                TAG,
                "enqueueExistingPayload: uniqueName=$uniqueName policy=$policy file=${file.absolutePath} bytes=${file.length()} mtime=${file.lastModified()}"
            )

            WorkManager.getInstance(appCtx)
                .enqueueUniqueWork(uniqueName, policy, req)
        }

        /**
         * Enqueue a work request to collect and upload logcat (snapshot gzip).
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

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, req)
        }

        /**
         * Enqueue a work request to upload app-owned runtime logs bundle (zip).
         *
         * This uploads what RuntimeLogStore has collected during app execution.
         */
        fun enqueueRuntimeLogsUpload(
            context: Context,
            cfg: GitHubUploader.GitHubConfig,
            remoteDir: String = "diagnostics/runtime_logs",
            addDateSubdir: Boolean = true,
            reason: String = "manual",
            deleteZipAfter: Boolean = true,
            maxZipBytes: Long = DEFAULT_MAX_RAW_BYTES_HINT,
        ) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val uniqueName = "upload_runtime_logs_$stamp"

            val req: OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_MODE to MODE_RUNTIME_LOGS,
                            KEY_OWNER to cfg.owner,
                            KEY_REPO to cfg.repo,
                            KEY_TOKEN to cfg.token,
                            KEY_BRANCH to cfg.branch,
                            KEY_PATH_PREFIX to cfg.pathPrefix,

                            KEY_RTLOG_REMOTE_DIR to remoteDir,
                            KEY_RTLOG_ADD_DATE to addDateSubdir,
                            KEY_RTLOG_REASON to reason,
                            KEY_RTLOG_DELETE_ZIP_AFTER to deleteZipAfter,
                            KEY_RTLOG_MAX_ZIP_BYTES to maxZipBytes,
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
                    .addTag("$TAG:runtime_logs")
                    .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, req)
        }
    }
}