/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: GitHubUploadWorker.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  WorkManager coroutine worker that uploads:
 *   - payload file, OR
 *   - logcat snapshot (gzip), OR
 *   - RuntimeLogStore plain .log files (deduped) + optional device cleanup, OR
 *   - AppRingLogStore ring segments (seg_XX.log) as full ring body (no deletion).
 *
 *  Key behavior:
 *   - Dedupe via local SHA-256 ledger (runtime logs).
 *   - After successful upload (or dedupe-skip), optionally deletes SOURCE .log on device.
 *   - Active (currently written) log is never deleted.
 *   - Ring segments are never deleted (ring is a retention buffer).
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
import com.negi.survey.AppRingLogStore
import com.negi.survey.BuildConfig
import com.negi.survey.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

class GitHubUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val ledgerLock = Any()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val mode = inputData.getString(KEY_MODE)?.lowercase(Locale.US) ?: MODE_FILE
        ensureChannel()

        val notifTitleBase = when (mode) {
            MODE_LOGCAT -> "Uploading logcat"
            MODE_RUNTIME_LOGS -> "Uploading runtime logs"
            MODE_STARTUP_RUNTIME_LOGS -> "Uploading startup runtime logs"
            MODE_RING_LOGS -> "Uploading ring logs"
            else -> "Uploading payload"
        }

        val notifId = stableNotificationId(mode, id.toString())

        return foregroundInfo(
            notificationId = notifId,
            pct = 0,
            title = "$notifTitleBase…"
        )
    }

    override suspend fun doWork(): Result {
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

        val notifTitleBase = when (mode) {
            MODE_LOGCAT -> "Uploading logcat"
            MODE_RUNTIME_LOGS -> "Uploading runtime logs"
            MODE_STARTUP_RUNTIME_LOGS -> "Uploading startup runtime logs"
            MODE_RING_LOGS -> "Uploading ring logs"
            else -> "Uploading payload"
        }

        val notifId = stableNotificationId(mode, id.toString())

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
            MODE_RUNTIME_LOGS, MODE_STARTUP_RUNTIME_LOGS ->
                doRuntimeLogsUpload(mode, cfg, notifId, progressCallback, currentPct)
            MODE_RING_LOGS, MODE_STARTUP_RING_LOGS ->
                doRingLogsUpload(mode, cfg, notifId, progressCallback, currentPct)
            else -> doFileUpload(cfg, notifId, progressCallback, currentPct)
        }
    }

    // ---------------------------------------------------------------------
    // File upload mode
    // ---------------------------------------------------------------------

    private suspend fun doFileUpload(
        cfg: GitHubUploader.GitHubConfig,
        notifId: Int,
        onProgress: (Int) -> Unit,
        currentPct: () -> Int,
    ): Result {

        val filePath = inputData.getString(KEY_FILE_PATH).orEmpty()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: File(filePath).name

        if (filePath.isBlank()) return Result.failure(workDataOf(ERROR_MESSAGE to "Missing file path."))

        val pendingFile = File(filePath)
        if (!pendingFile.exists()) return Result.failure(workDataOf(ERROR_MESSAGE to "Pending file not found: $filePath"))

        val fileSize = pendingFile.length()
        if (fileSize <= 0L) return Result.failure(workDataOf(ERROR_MESSAGE to "Pending file is empty: $filePath"))

        val maxBytesHint = inputData.getLong(KEY_FILE_MAX_BYTES_HINT, DEFAULT_MAX_RAW_BYTES_HINT)
        if (fileSize > maxBytesHint) {
            val msg =
                "File too large for this upload path (size=$fileSize, limit~$maxBytesHint). " +
                        "For PCM_16BIT MONO WAV: bytes = 44 + seconds * sampleRateHz * 2."
            return Result.failure(workDataOf(ERROR_MESSAGE to msg))
        }

        val estRequestBytes = estimateBase64RequestBytes(fileSize)
        if (estRequestBytes > cfg.maxRequestBytesHint.toLong()) {
            val msg =
                "Request too large for this upload path (raw=$fileSize, request~$estRequestBytes, " +
                        "limit~${cfg.maxRequestBytesHint}). Base64 expands by ~4/3."
            return Result.failure(workDataOf(ERROR_MESSAGE to msg))
        }

        val remotePathForUi = buildDatedRemotePathUtc(cfg.pathPrefix, fileName)

        return try {
            val extension = pendingFile.extension.lowercase(Locale.US)
            val isText = TEXT_EXTENSIONS.contains(extension)

            val result = if (isText) {
                val text = runCatching { pendingFile.readText(Charsets.UTF_8) }.getOrElse {
                    return Result.failure(workDataOf(ERROR_MESSAGE to "Failed to read text file: ${it.message}"))
                }

                GitHubUploader.uploadJson(
                    cfg = cfg,
                    relativePath = fileName,
                    content = text,
                    message = "Upload $fileName (deferred)",
                    onProgress = onProgress
                )
            } else {
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

    // ---------------------------------------------------------------------
    // Logcat upload mode
    // ---------------------------------------------------------------------

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
            onProgress(3)

            val snap = collectLogcatSnapshotGz(
                context = applicationContext,
                includeDeviceHeader = includeHeader,
                includeCrashBuffer = includeCrash,
                maxUncompressedBytes = maxBytes
            )

            onProgress(20)

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

    // ---------------------------------------------------------------------
    // Runtime logs (plain) upload mode + device cleanup
    // ---------------------------------------------------------------------

    private suspend fun doRuntimeLogsUpload(
        mode: String,
        cfg: GitHubUploader.GitHubConfig,
        notifId: Int,
        onProgress: (Int) -> Unit,
        currentPct: () -> Int,
    ): Result {
        val remoteDir = inputData.getString(KEY_RTLOG_REMOTE_DIR) ?: "diagnostics/runtime_logs"
        val addDate = inputData.getBoolean(KEY_RTLOG_ADD_DATE, true)
        val reason = inputData.getString(KEY_RTLOG_REASON)?.takeIf { it.isNotBlank() } ?: "wm"

        val deletePreparedAfter = inputData.getBoolean(KEY_RTLOG_DELETE_ZIP_AFTER, true)
        val dedupeEnabled = inputData.getBoolean(KEY_RTLOG_DEDUPE_ENABLE, true)

        // NEW: delete SOURCE logs from device after upload/dedupe-skip
        val deleteSourceAfterUpload = inputData.getBoolean(
            KEY_RTLOG_DELETE_SOURCE_AFTER_UPLOAD,
            !BuildConfig.DEBUG
        )

        val isRelease = !BuildConfig.DEBUG
        val limitFiles = inputData.getInt(
            KEY_RTLOG_PLAIN_LIMIT_FILES,
            if (isRelease) 200 else DEFAULT_RTLOG_PLAIN_LIMIT_FILES
        ).coerceIn(1, 200)

        val includeActive = inputData.getBoolean(KEY_RTLOG_PLAIN_INCLUDE_ACTIVE, false)
        val rotateSnapshot = inputData.getBoolean(KEY_RTLOG_PLAIN_ROTATE_SNAPSHOT, !includeActive)
        val writeManifest = inputData.getBoolean(KEY_RTLOG_PLAIN_WRITE_MANIFEST, isRelease)

        // Legacy: treated as per-file max bytes hint for plain logs.
        val legacyMaxBytesHint = inputData.getLong(KEY_RTLOG_MAX_ZIP_BYTES, cfg.maxRawBytesHint.toLong())
        val maxPerFileBytes = minOf(cfg.maxRawBytesHint.toLong(), legacyMaxBytesHint).coerceAtLeast(50_000L)

        // Optional: attach logcat snapshot into the same session folder (Release default true)
        val includeLogcat = inputData.getBoolean(KEY_RTLOG_INCLUDE_LOGCAT, isRelease)
        val logcatIncludeHeader = inputData.getBoolean(KEY_RTLOG_LOGCAT_INCLUDE_HEADER, true)
        val logcatIncludeCrash = inputData.getBoolean(KEY_RTLOG_LOGCAT_INCLUDE_CRASH, true)
        val logcatMaxUncompressed = inputData.getInt(KEY_RTLOG_LOGCAT_MAX_UNCOMPRESSED, 850_000)

        onProgress(3)

        if (mode == MODE_STARTUP_RUNTIME_LOGS) {
            runCatching { delay(STARTUP_RTLOG_PRE_DELAY_MS) }
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val pid = Process.myPid()
        val safeReason = safeFileSegment(reason)
        val appCtx = applicationContext.applicationContext ?: applicationContext
        val outDir = File(appCtx.cacheDir, "diagnostics_upload").apply { mkdirs() }

        val preparedLogs: List<File> = runCatching {
            RuntimeLogStore.start(appCtx)
            RuntimeLogStore.prepareLogsForUploadPlain(
                reason = safeReason,
                limitFiles = limitFiles,
                includeActive = includeActive,
                rotateSnapshot = rotateSnapshot,
                writeManifest = writeManifest
            )
        }.getOrElse { t ->
            RuntimeLogStore.w(TAG, "doRuntimeLogsUpload: prepareLogsForUploadPlain failed: ${t.message}", t)
            emptyList()
        }

        val manifest: File? = if (writeManifest) findLatestPlainManifest(outDir, safeReason) else null

        val candidates = ArrayList<File>()
        if (manifest != null && manifest.exists() && manifest.isFile) candidates.add(manifest)
        candidates.addAll(preparedLogs.filter { it.exists() && it.isFile })

        if (candidates.isEmpty() && !includeLogcat) {
            RuntimeLogStore.d(TAG, "doRuntimeLogsUpload: no files to upload; no-op success.")
            return Result.success(
                workDataOf(
                    OUT_MODE to MODE_RUNTIME_LOGS,
                    OUT_REMOTE_PATH to "",
                    OUT_FILE_NAME to "",
                    OUT_COMMIT_SHA to "",
                    OUT_FILE_URL to "",
                    OUT_PLAIN_FILE_COUNT to 0,
                    OUT_PLAIN_TOTAL_BYTES to 0L,
                    OUT_PLAIN_PREVIEW to "",
                    OUT_PLAIN_SOURCE_DELETED_COUNT to 0
                )
            )
        }

        // Enforce per-file size guard using tail truncation.
        val sizedFiles = candidates.mapNotNull { f ->
            val len = runCatching { f.length() }.getOrNull() ?: 0L
            if (len <= 0L) return@mapNotNull null
            if (len <= maxPerFileBytes) return@mapNotNull f

            val trimmed = File(f.parentFile ?: outDir, "trimmed_${f.name}")
            runCatching { copyTailOrFull(src = f, dst = trimmed, maxBytes = maxPerFileBytes) }
                .getOrElse {
                    RuntimeLogStore.w(TAG, "tail trim failed; skipping. file=${f.name} err=${it.message}", it)
                    return@mapNotNull null
                }
            trimmed
        }

        val ledger: LinkedHashSet<String> = if (dedupeEnabled) loadLedger(appCtx) else LinkedHashSet()

        val toUpload = ArrayList<File>()
        val dupPrepared = ArrayList<File>()
        if (dedupeEnabled) {
            for (f in sizedFiles) {
                val fp = runCatching { "sha256:" + sha256HexOfFile(f) }.getOrNull()
                if (fp == null) {
                    toUpload.add(f)
                    continue
                }
                if (ledger.contains(fp)) dupPrepared.add(f) else toUpload.add(f)
            }
        } else {
            toUpload.addAll(sizedFiles)
        }

        // If dupPrepared exists, optionally delete their SOURCE logs immediately.
        var deletedSourceCount = 0
        if (deleteSourceAfterUpload && dupPrepared.isNotEmpty()) {
            val srcNames = dupPrepared.mapNotNull { extractSourceLogNameFromPrepared(it.name) }.distinct()
            if (srcNames.isNotEmpty()) {
                deletedSourceCount += RuntimeLogStore.deleteRolledLogFiles(srcNames, excludeActive = true)
            }
        }

        if (toUpload.isEmpty() && !includeLogcat) {
            RuntimeLogStore.d(TAG, "doRuntimeLogsUpload: all candidates already uploaded; no-op. deletedSource=$deletedSourceCount")
            if (deletePreparedAfter) {
                runCatching { manifest?.delete() }
                preparedLogs.forEach { runCatching { it.delete() } }
                sizedFiles.forEach { f -> if (f.name.startsWith("trimmed_")) runCatching { f.delete() } }
            }
            return Result.success(
                workDataOf(
                    OUT_MODE to MODE_RUNTIME_LOGS,
                    OUT_REMOTE_PATH to "",
                    OUT_FILE_NAME to "",
                    OUT_COMMIT_SHA to "",
                    OUT_FILE_URL to "",
                    OUT_PLAIN_FILE_COUNT to 0,
                    OUT_PLAIN_TOTAL_BYTES to 0L,
                    OUT_PLAIN_PREVIEW to "",
                    OUT_PLAIN_SOURCE_DELETED_COUNT to deletedSourceCount
                )
            )
        }

        val dateSegment = if (addDate) utcDateFolder() else ""
        val sessionDirName = "runtime_logs_${stamp}_pid${pid}_${safeReason}"
        val remoteBaseDir = listOf(
            cfg.pathPrefix.trim('/'),
            remoteDir.trim('/'),
            dateSegment,
            sessionDirName
        ).filter { it.isNotBlank() }.joinToString("/")

        val totalBytes = toUpload.sumOf { runCatching { it.length() }.getOrNull() ?: 0L }
        val preview = toUpload.take(10).joinToString("|") { it.name }

        Log.d(
            TAG,
            "doRuntimeLogsUpload: remoteBaseDir=$remoteBaseDir toUpload=${toUpload.size} bytes=$totalBytes " +
                    "dedupe=$dedupeEnabled deleteSource=$deleteSourceAfterUpload deletedSourcePre=$deletedSourceCount " +
                    "preview='$preview'"
        )

        return try {
            val startPct = 10
            val endPctLogs = if (includeLogcat) 90 else 100
            val n = toUpload.size.coerceAtLeast(1)
            var lastResult: GitHubUploader.UploadResult? = null

            for ((i, file) in toUpload.withIndex()) {
                val fileStart = startPct + ((endPctLogs - startPct) * i) / n
                val fileEnd = startPct + ((endPctLogs - startPct) * (i + 1)) / n
                val mapped = mapProgressRange(start = fileStart, end = fileEnd, sink = onProgress)

                val remotePath = "$remoteBaseDir/${file.name}"

                lastResult = GitHubUploader.uploadFileAtPath(
                    cfg = cfg,
                    path = remotePath,
                    file = file,
                    message = "Upload runtime logs (plain) ($reason)",
                    onProgress = mapped
                )

                if (dedupeEnabled) {
                    val fp = runCatching { "sha256:" + sha256HexOfFile(file) }.getOrNull()
                    if (fp != null) {
                        ledger.add(fp)
                        trimLedgerInPlace(ledger, LEDGER_MAX_ITEMS)
                        saveLedger(appCtx, ledger)
                    }
                }

                if (deleteSourceAfterUpload) {
                    val srcName = extractSourceLogNameFromPrepared(file.name)
                    if (srcName != null) {
                        deletedSourceCount += RuntimeLogStore.deleteRolledLogFiles(listOf(srcName), excludeActive = true)
                    }
                }
            }

            // Optional attached logcat snapshot
            var logcatResult: GitHubUploader.UploadResult? = null
            if (includeLogcat) {
                val snap = runCatching {
                    collectLogcatSnapshotGz(
                        context = applicationContext,
                        includeDeviceHeader = logcatIncludeHeader,
                        includeCrashBuffer = logcatIncludeCrash,
                        maxUncompressedBytes = logcatMaxUncompressed
                    )
                }.getOrNull()

                if (snap != null && snap.gzBytes.isNotEmpty()) {
                    val remotePath = "$remoteBaseDir/logcat_${stamp}_pid${pid}.log.gz"
                    val mapped = mapProgressRange(start = 90, end = 100, sink = onProgress)

                    logcatResult = GitHubUploader.uploadBytesAtPath(
                        cfg = cfg,
                        path = remotePath,
                        bytes = snap.gzBytes,
                        message = "Upload logcat (attached) ($reason)",
                        onProgress = mapped
                    )
                } else {
                    onProgress(100)
                }
            }

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

            if (deletePreparedAfter) {
                runCatching { manifest?.delete() }
                preparedLogs.forEach { runCatching { it.delete() } }
                sizedFiles.forEach { f -> if (f.name.startsWith("trimmed_")) runCatching { f.delete() } }
            }

            Result.success(
                workDataOf(
                    OUT_MODE to MODE_RUNTIME_LOGS,
                    OUT_REMOTE_PATH to remoteBaseDir,
                    OUT_FILE_NAME to sessionDirName,
                    OUT_COMMIT_SHA to (logcatResult?.commitSha ?: lastResult?.commitSha ?: ""),
                    OUT_FILE_URL to (logcatResult?.fileUrl ?: lastResult?.fileUrl ?: ""),
                    OUT_PLAIN_FILE_COUNT to toUpload.size,
                    OUT_PLAIN_TOTAL_BYTES to totalBytes,
                    OUT_PLAIN_PREVIEW to preview,
                    OUT_PLAIN_SOURCE_DELETED_COUNT to deletedSourceCount
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

    // ---------------------------------------------------------------------
    // Ring logs (AppRingLogStore segments) upload mode
    // ---------------------------------------------------------------------

    private suspend fun doRingLogsUpload(
        mode: String,
        cfg: GitHubUploader.GitHubConfig,
        notifId: Int,
        onProgress: (Int) -> Unit,
        currentPct: () -> Int,
    ): Result {
        val remoteDir = inputData.getString(KEY_RING_REMOTE_DIR) ?: "diagnostics/applog_ring"
        val addDate = inputData.getBoolean(KEY_RING_ADD_DATE, true)
        val reason = inputData.getString(KEY_RING_REASON)?.takeIf { it.isNotBlank() } ?: "wm"

        onProgress(3)

        if (mode == MODE_STARTUP_RING_LOGS) {
            runCatching { delay(STARTUP_RING_PRE_DELAY_MS) }
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val pid = Process.myPid()
        val safeReason = safeFileSegment(reason)

        val dir = runCatching { AppRingLogStore.ringDir(applicationContext) }.getOrNull()
        if (dir == null || !dir.exists()) {
            Log.d(TAG, "doRingLogsUpload: ringDir not available; no-op success.")
            return Result.success(
                workDataOf(
                    OUT_MODE to MODE_RING_LOGS,
                    OUT_REMOTE_PATH to "",
                    OUT_RING_FILE_COUNT to 0,
                    OUT_RING_TOTAL_BYTES to 0L
                )
            )
        }

        val segs = dir.listFiles()?.filter { f ->
            f.isFile && f.name.startsWith("seg_", ignoreCase = true) && f.name.endsWith(".log", ignoreCase = true)
        }.orEmpty().sortedBy { it.name }

        if (segs.isEmpty()) {
            Log.d(TAG, "doRingLogsUpload: no ring segments found; no-op success. dir=${dir.absolutePath}")
            return Result.success(
                workDataOf(
                    OUT_MODE to MODE_RING_LOGS,
                    OUT_REMOTE_PATH to "",
                    OUT_RING_FILE_COUNT to 0,
                    OUT_RING_TOTAL_BYTES to 0L
                )
            )
        }

        val dateSegment = if (addDate) utcDateFolder() else ""
        val sessionDirName = "applog_ring_${stamp}_pid${pid}_${safeReason}"
        val remoteBaseDir = listOf(
            cfg.pathPrefix.trim('/'),
            remoteDir.trim('/'),
            dateSegment,
            sessionDirName
        ).filter { it.isNotBlank() }.joinToString("/")

        val totalBytes = segs.sumOf { runCatching { it.length() }.getOrNull() ?: 0L }

        Log.d(TAG, "doRingLogsUpload: remoteBaseDir=$remoteBaseDir segs=${segs.size} bytes=$totalBytes dir=${dir.absolutePath}")

        return try {
            val startPct = 10
            val endPct = 100
            val n = segs.size.coerceAtLeast(1)

            for ((i, file) in segs.withIndex()) {
                val fileStart = startPct + ((endPct - startPct) * i) / n
                val fileEnd = startPct + ((endPct - startPct) * (i + 1)) / n
                val mapped = mapProgressRange(start = fileStart, end = fileEnd, sink = onProgress)

                val remotePath = "$remoteBaseDir/${file.name}"

                GitHubUploader.uploadFileAtPath(
                    cfg = cfg,
                    path = remotePath,
                    file = file,
                    message = "Upload ring logs (segments) ($reason)",
                    onProgress = mapped
                )
            }

            runCatching {
                setForegroundAsync(
                    foregroundInfo(
                        notificationId = notifId,
                        pct = 100,
                        title = "Uploaded ring logs",
                        finished = true
                    )
                )
            }

            Result.success(
                workDataOf(
                    OUT_MODE to MODE_RING_LOGS,
                    OUT_REMOTE_PATH to remoteBaseDir,
                    OUT_RING_FILE_COUNT to segs.size,
                    OUT_RING_TOTAL_BYTES to totalBytes
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "doRingLogsUpload: upload failed", t)

            runCatching {
                setForegroundAsync(
                    foregroundInfo(
                        notificationId = notifId,
                        pct = currentPct(),
                        title = "Ring log upload failed",
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
     * Extract original runtime log file name from prepared cache file name.
     *
     * Expected patterns:
     * - runtime_log_...__rtlog_....log
     * - trimmed_runtime_log_...__rtlog_....log
     *
     * Returns null for manifest/session files (no "__" marker).
     */
    private fun extractSourceLogNameFromPrepared(preparedName: String): String? {
        val base = preparedName.removePrefix("trimmed_")
        val marker = "__"
        if (!base.contains(marker)) return null
        val tail = base.substringAfterLast(marker).trim()
        if (tail.isBlank()) return null
        if (!tail.endsWith(".log", ignoreCase = true)) return null
        return tail
    }

    // ---------------------------------------------------------------------
    // Ledger + hashing
    // ---------------------------------------------------------------------

    private fun ledgerFile(ctx: Context): File {
        val dir = File(ctx.filesDir, "diagnostics/runtime_logs").apply { mkdirs() }
        return File(dir, "upload_ledger.json")
    }

    private fun loadLedger(ctx: Context): LinkedHashSet<String> = synchronized(ledgerLock) {
        val f = ledgerFile(ctx)
        if (!f.exists()) return@synchronized LinkedHashSet()

        return@synchronized runCatching {
            val obj = JSONObject(f.readText(Charsets.UTF_8))
            val arr = obj.optJSONArray("items") ?: JSONArray()
            val out = LinkedHashSet<String>(arr.length().coerceAtLeast(16))
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).takeIf { it.isNotBlank() } ?: continue
                out.add(s)
            }
            out
        }.getOrElse { LinkedHashSet() }
    }

    private fun saveLedger(ctx: Context, set: LinkedHashSet<String>) = synchronized(ledgerLock) {
        val f = ledgerFile(ctx)
        val obj = JSONObject()
        obj.put("v", 1)
        obj.put("updated_utc", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date()))
        obj.put("max", LEDGER_MAX_ITEMS)

        val arr = JSONArray()
        set.forEach { arr.put(it) }
        obj.put("items", arr)

        runCatching { f.writeText(obj.toString(2), Charsets.UTF_8) }
    }

    private fun trimLedgerInPlace(set: LinkedHashSet<String>, maxItems: Int) {
        while (set.size > maxItems) {
            val it = set.iterator()
            if (!it.hasNext()) return
            it.next()
            it.remove()
        }
    }

    private fun sha256HexOfFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { ins ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    // ---------------------------------------------------------------------
    // Manifest helper (plain)
    // ---------------------------------------------------------------------

    private fun findLatestPlainManifest(outDir: File, safeReason: String): File? {
        val list = outDir.listFiles()?.filter {
            it.isFile &&
                    it.name.startsWith("plain_logs_manifest_", ignoreCase = true) &&
                    it.name.contains("_${safeReason}.json")
        }.orEmpty()
        return list.maxByOrNull { it.lastModified() }
    }

    // ---------------------------------------------------------------------
    // Tail copy
    // ---------------------------------------------------------------------

    private fun copyTailOrFull(src: File, dst: File, maxBytes: Long) {
        dst.parentFile?.mkdirs()
        val limit = maxBytes.coerceAtLeast(50_000L)
        val len = runCatching { src.length() }.getOrNull() ?: 0L

        if (len <= 0L) {
            FileOutputStream(dst).use { it.fd.sync() }
            return
        }

        if (len <= limit) {
            FileInputStream(src).use { ins ->
                FileOutputStream(dst).use { outs ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        outs.write(buf, 0, n)
                    }
                    outs.fd.sync()
                }
            }
            return
        }

        RandomAccessFile(src, "r").use { raf ->
            raf.seek((len - limit).coerceAtLeast(0L))
            FileOutputStream(dst).use { outs ->
                val header = "=== TRUNCATED TAIL COPY ===\noriginal_bytes=$len copied_tail_bytes=$limit\n\n"
                outs.write(header.toByteArray(Charsets.UTF_8))

                val buf = ByteArray(16 * 1024)
                var remaining = limit
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = raf.read(buf, 0, toRead)
                    if (n <= 0) break
                    outs.write(buf, 0, n)
                    remaining -= n.toLong()
                }
                outs.fd.sync()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Logcat snapshot utils
    // ---------------------------------------------------------------------

    private data class LogcatSnapshot(val rawBytes: Int, val gzBytes: ByteArray)

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
        return LogcatSnapshot(rawBytes = trimmed.size, gzBytes = gz)
    }

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

    // ---------------------------------------------------------------------
    // Foreground + helpers
    // ---------------------------------------------------------------------

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
            "Background Uploads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Displays progress for ongoing uploads to GitHub."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun utcDateFolder(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun buildDatedRemotePathUtc(prefix: String, fileName: String): String {
        val date = utcDateFolder()
        return listOf(prefix.trim('/'), date, fileName.trim('/'))
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    private fun mapProgressRange(start: Int, end: Int, sink: (Int) -> Unit): (Int) -> Unit {
        val lo = minOf(start.coerceIn(0, 100), end.coerceIn(0, 100))
        val hi = maxOf(start.coerceIn(0, 100), end.coerceIn(0, 100))
        var last = lo
        return { p ->
            val clamped = p.coerceIn(0, 100)
            val mapped = lo + ((clamped / 100.0) * (hi - lo)).toInt()
            val mono = maxOf(last, mapped.coerceIn(lo, hi))
            last = mono
            sink(mono)
        }
    }

    private fun safeFileSegment(s: String): String =
        s.trim().ifBlank { "wm" }
            .replace(Regex("""[^A-Za-z0-9_\-\.]+"""), "_")
            .take(24)

    private fun shouldRetry(t: Throwable): Boolean {
        val msg = t.message.orEmpty()

        if (msg.startsWith("Transient HTTP ", ignoreCase = true)) return true

        val httpCode = Regex("""\((\d{3})\)""").find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (httpCode != null && httpCode in 400..499 && httpCode != 429) return false

        if (msg.contains("too large", ignoreCase = true)) return false
        if (msg.contains("bad credentials", ignoreCase = true)) return false
        if (msg.contains("requires authentication", ignoreCase = true)) return false
        if (msg.contains("403")) return false

        return t is IOException || msg.contains("timeout", ignoreCase = true)
    }

    companion object {
        const val TAG = "github_upload"

        private const val CHANNEL_ID = "uploads"
        private const val NOTIF_BASE = 3200
        private const val MAX_ATTEMPTS = 5

        private const val DEFAULT_MAX_RAW_BYTES_HINT = 1_000_000L
        private const val COMMAND_STDOUT_MAX_BYTES = 700_000

        private const val LOGCAT_TAIL_LINES = 1200
        private const val LOGCAT_CRASH_TAIL_LINES = 200

        private val TEXT_EXTENSIONS = setOf("json", "jsonl", "txt", "csv")

        private const val STARTUP_RTLOG_PRE_DELAY_MS = 1200L
        private const val DEFAULT_RTLOG_PLAIN_LIMIT_FILES = 12

        private const val STARTUP_RING_PRE_DELAY_MS = 700L

        private const val LEDGER_MAX_ITEMS = 4000

        // Modes
        private const val MODE_FILE = "file"
        private const val MODE_LOGCAT = "logcat"
        private const val MODE_RUNTIME_LOGS = "runtime_logs"
        private const val MODE_STARTUP_RUNTIME_LOGS = "startup_runtime_logs"
        private const val MODE_RING_LOGS = "ring_logs"
        private const val MODE_STARTUP_RING_LOGS = "startup_ring_logs"

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
        const val KEY_FILE_MAX_BYTES_HINT = "file.maxBytesHint"
        const val KEY_FILE_MAX_REQUEST_BYTES_HINT = "file.maxRequestBytesHint"

        // Logcat input keys
        const val KEY_LOG_REMOTE_DIR = "log.remoteDir"
        const val KEY_LOG_ADD_DATE = "log.addDate"
        const val KEY_LOG_INCLUDE_HEADER = "log.includeHeader"
        const val KEY_LOG_INCLUDE_CRASH = "log.includeCrash"
        const val KEY_LOG_MAX_UNCOMPRESSED = "log.maxUncompressed"

        // Runtime logs input keys
        const val KEY_RTLOG_REMOTE_DIR = "rtlog.remoteDir"
        const val KEY_RTLOG_ADD_DATE = "rtlog.addDate"
        const val KEY_RTLOG_REASON = "rtlog.reason"

        // Legacy keys (kept)
        const val KEY_RTLOG_DELETE_ZIP_AFTER = "rtlog.deleteZipAfter"
        const val KEY_RTLOG_MAX_ZIP_BYTES = "rtlog.maxZipBytes"

        // Plain runtime logs controls
        const val KEY_RTLOG_PLAIN_LIMIT_FILES = "rtlog.plain.limitFiles"
        const val KEY_RTLOG_PLAIN_INCLUDE_ACTIVE = "rtlog.plain.includeActive"
        const val KEY_RTLOG_PLAIN_ROTATE_SNAPSHOT = "rtlog.plain.rotateSnapshot"
        const val KEY_RTLOG_PLAIN_WRITE_MANIFEST = "rtlog.plain.writeManifest"

        // Dedupe control
        const val KEY_RTLOG_DEDUPE_ENABLE = "rtlog.dedupe.enable"

        // NEW: delete uploaded source logs from device
        const val KEY_RTLOG_DELETE_SOURCE_AFTER_UPLOAD = "rtlog.deleteSourceAfterUpload"

        // Attach logcat to runtime logs session
        const val KEY_RTLOG_INCLUDE_LOGCAT = "rtlog.includeLogcat"
        const val KEY_RTLOG_LOGCAT_INCLUDE_HEADER = "rtlog.logcat.includeHeader"
        const val KEY_RTLOG_LOGCAT_INCLUDE_CRASH = "rtlog.logcat.includeCrash"
        const val KEY_RTLOG_LOGCAT_MAX_UNCOMPRESSED = "rtlog.logcat.maxUncompressed"

        // Ring logs input keys
        const val KEY_RING_REMOTE_DIR = "ring.remoteDir"
        const val KEY_RING_ADD_DATE = "ring.addDate"
        const val KEY_RING_REASON = "ring.reason"

        // Output keys
        const val OUT_MODE = "out.mode"
        const val OUT_FILE_NAME = "out.fileName"
        const val OUT_REMOTE_PATH = "out.remotePath"
        const val OUT_COMMIT_SHA = "out.commitSha"
        const val OUT_FILE_URL = "out.fileUrl"
        const val OUT_BYTES_RAW = "out.bytesRaw"
        const val OUT_BYTES_GZ = "out.bytesGz"

        // Plain runtime logs outputs
        const val OUT_PLAIN_FILE_COUNT = "out.plainFileCount"
        const val OUT_PLAIN_TOTAL_BYTES = "out.plainTotalBytes"
        const val OUT_PLAIN_PREVIEW = "out.plainPreview"
        const val OUT_PLAIN_SOURCE_DELETED_COUNT = "out.plainSourceDeletedCount"

        // Ring logs outputs
        const val OUT_RING_FILE_COUNT = "out.ringFileCount"
        const val OUT_RING_TOTAL_BYTES = "out.ringTotalBytes"

        const val ERROR_MESSAGE = "error"

        private fun estimateBase64RequestBytes(rawBytes: Long): Long {
            val raw = rawBytes.coerceAtLeast(1L)
            val b64 = ((raw + 2L) / 3L) * 4L
            val overhead = 200_000L
            return b64 + overhead
        }

        private fun estimateRequestBytesHint(rawBytesHint: Int): Int {
            val est = estimateBase64RequestBytes(rawBytesHint.toLong())
            val floor = 2_800_000L
            val out = maxOf(floor, est)
            return out.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        private fun stableNotificationId(mode: String, key: String): Int {
            val h = (mode + key).hashCode().toLong()
            val nonNeg = h and 0x7fffffffL
            return NOTIF_BASE + (nonNeg % 8000L).toInt()
        }

        /**
         * Enqueue a work request to upload app-owned runtime logs (plain).
         *
         * Note:
         * - maxZipBytes is kept for backward compatibility, now treated as per-file max bytes hint.
         */
        fun enqueueRuntimeLogsUpload(
            context: Context,
            cfg: GitHubUploader.GitHubConfig,
            remoteDir: String = "diagnostics/runtime_logs",
            addDateSubdir: Boolean = true,
            reason: String = "manual",
            deleteZipAfter: Boolean = true,
            maxZipBytes: Long = DEFAULT_MAX_RAW_BYTES_HINT,
            deleteSourceAfterUpload: Boolean = true
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

                            KEY_RTLOG_DEDUPE_ENABLE to true,
                            KEY_RTLOG_DELETE_SOURCE_AFTER_UPLOAD to deleteSourceAfterUpload,
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

        /**
         * Enqueue a stable startup runtime logs upload.
         */
        fun enqueueStartupRuntimeLogsUpload(
            context: Context,
            cfg: GitHubUploader.GitHubConfig,
            remoteDir: String = "diagnostics/runtime_logs",
            addDateSubdir: Boolean = true,
            reason: String = "app_start",
            deleteZipAfter: Boolean = true,
            maxZipBytes: Long = DEFAULT_MAX_RAW_BYTES_HINT,
            deleteSourceAfterUpload: Boolean = true
        ) {
            val uniqueName = "upload_runtime_logs_startup"

            val req: OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_MODE to MODE_STARTUP_RUNTIME_LOGS,
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

                            KEY_RTLOG_DEDUPE_ENABLE to true,
                            KEY_RTLOG_DELETE_SOURCE_AFTER_UPLOAD to deleteSourceAfterUpload,
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
                    .addTag("$TAG:startup_runtime_logs")
                    .build()

            val appCtx = context.applicationContext
            val policy = choosePolicyForUniqueName(appCtx, uniqueName)

            Log.d(TAG, "enqueueStartupRuntimeLogsUpload: uniqueName=$uniqueName policy=$policy")

            WorkManager.getInstance(appCtx)
                .enqueueUniqueWork(uniqueName, policy, req)
        }

        /**
         * Enqueue a stable startup ring logs upload (AppRingLogStore segments).
         *
         * Notes:
         * - Ring segments are uploaded as-is (seg_XX.log).
         * - Segments are never deleted on device.
         */
        fun enqueueStartupRingLogsUpload(
            context: Context,
            cfg: GitHubUploader.GitHubConfig,
            remoteDir: String = "diagnostics/applog_ring",
            addDateSubdir: Boolean = true,
            reason: String = "app_start",
        ) {
            val uniqueName = "upload_ring_logs_startup"

            val req: OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_MODE to MODE_STARTUP_RING_LOGS,
                            KEY_OWNER to cfg.owner,
                            KEY_REPO to cfg.repo,
                            KEY_TOKEN to cfg.token,
                            KEY_BRANCH to cfg.branch,
                            KEY_PATH_PREFIX to cfg.pathPrefix,

                            KEY_RING_REMOTE_DIR to remoteDir,
                            KEY_RING_ADD_DATE to addDateSubdir,
                            KEY_RING_REASON to reason,
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
                    .addTag("$TAG:ring_logs")
                    .build()

            val appCtx = context.applicationContext
            val policy = choosePolicyForUniqueName(appCtx, uniqueName)

            Log.d(TAG, "enqueueStartupRingLogsUpload: uniqueName=$uniqueName policy=$policy")

            WorkManager.getInstance(appCtx)
                .enqueueUniqueWork(uniqueName, policy, req)
        }

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
    }
}