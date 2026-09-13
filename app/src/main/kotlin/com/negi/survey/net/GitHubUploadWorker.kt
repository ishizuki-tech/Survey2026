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
 *   - Dedupe via an atomic local SHA-256 ledger for runtime logs.
 *   - After successful upload (or dedupe-skip), optionally deletes SOURCE .log on device.
 *   - Active (currently written) runtime log is never deleted.
 *   - Ring segments are copied to a stable cache snapshot before upload and are never deleted.
 *   - Upload session ids remain stable across WorkManager retries.
 *   - GitHub credentials are resolved at execution time when possible instead of being persisted in WorkManager Data.
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
import androidx.work.Data
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
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
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class GitHubUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val mode = inputData.getString(KEY_MODE)?.lowercase(Locale.US) ?: MODE_FILE
        ensureChannel()

        val notifTitleBase =
            notificationTitleForMode(mode)

        val notifId =
            stableNotificationId(
                mode,
                id.toString()
            )

        return foregroundInfo(
            notificationId = notifId,
            pct = 0,
            title = "$notifTitleBase…"
        )
    }

    override suspend fun doWork(): Result {
        val mode =
            inputData.getString(KEY_MODE)
                ?.lowercase(Locale.US)
                ?: MODE_FILE

        val maxFileBytesHint =
            inputData.getLong(
                KEY_FILE_MAX_BYTES_HINT,
                DEFAULT_MAX_RAW_BYTES_HINT
            ).coerceAtLeast(1L)

        val maxRawBytesHint =
            maxFileBytesHint
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()

        val defaultRequestBytesHint =
            estimateRequestBytesHint(maxRawBytesHint)

        val maxRequestBytesHint =
            inputData.getInt(
                KEY_FILE_MAX_REQUEST_BYTES_HINT,
                defaultRequestBytesHint
            ).coerceAtLeast(1)

        val cfg =
            resolveGitHubConfig(
                maxRawBytesHint = maxRawBytesHint,
                maxRequestBytesHint = maxRequestBytesHint
            )

        if (cfg == null) {
            return Result.failure(
                workDataOf(
                    ERROR_MESSAGE to
                            "Invalid GitHub configuration. " +
                            "No usable owner/repo/token could be resolved."
                )
            )
        }

        ensureChannel()

        val notifTitleBase = notificationTitleForMode(mode)
        val notifId = stableNotificationId(mode, id.toString())

        try {
            setForeground(
                foregroundInfo(
                    notificationId = notifId,
                    pct = 0,
                    title = "$notifTitleBase…"
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "doWork: setForeground failed; continuing best-effort. err=${t.message}",
                t
            )
        }

        val lastPctRef = intArrayOf(-1)

        val progressCallback: (Int) -> Unit =
            progressCallback@{ pct ->
                val clamped = pct.coerceIn(0, 100)

                if (clamped == lastPctRef[0]) {
                    return@progressCallback
                }

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

        val currentPct: () -> Int = {
            lastPctRef[0].coerceAtLeast(0)
        }

        /**
         * CoroutineWorker defaults to Dispatchers.Default.
         *
         * This worker is dominated by network and file IO, so keep blocking
         * uploader/file/process operations off the Default dispatcher.
         */
        return withContext(Dispatchers.IO) {
            when (mode) {
                MODE_LOGCAT ->
                    doLogcatUpload(
                        cfg,
                        notifId,
                        progressCallback,
                        currentPct
                    )

                MODE_RUNTIME_LOGS,
                MODE_STARTUP_RUNTIME_LOGS ->
                    doRuntimeLogsUpload(
                        mode,
                        cfg,
                        notifId,
                        progressCallback,
                        currentPct
                    )

                MODE_RING_LOGS,
                MODE_STARTUP_RING_LOGS ->
                    doRingLogsUpload(
                        mode,
                        cfg,
                        notifId,
                        progressCallback,
                        currentPct
                    )

                else ->
                    doFileUpload(
                        cfg,
                        notifId,
                        progressCallback,
                        currentPct
                    )
            }
        }
    }

    private fun notificationTitleForMode(mode: String): String =
        when (mode) {
            MODE_LOGCAT -> "Uploading logcat"
            MODE_RUNTIME_LOGS -> "Uploading runtime logs"
            MODE_STARTUP_RUNTIME_LOGS -> "Uploading startup runtime logs"
            MODE_RING_LOGS -> "Uploading ring logs"
            MODE_STARTUP_RING_LOGS -> "Uploading startup ring logs"
            else -> "Uploading payload"
        }

    /**
     * Resolve upload configuration without requiring a token to be persisted
     * in WorkManager input Data.
     *
     * Priority:
     *  1) Legacy token from input Data, if present.
     *  2) GitHubDiagnosticsConfigStore.
     *  3) BuildConfig fallback.
     *
     * Non-secret routing fields supplied in input Data remain authoritative.
     */
    private fun resolveGitHubConfig(
        maxRawBytesHint: Int,
        maxRequestBytesHint: Int
    ): GitHubUploader.GitHubConfig? {
        val appCtx =
            applicationContext.applicationContext
                ?: applicationContext

        val stored =
            runCatching {
                GitHubDiagnosticsConfigStore
                    .buildGitHubConfigOrNull(appCtx)
            }.getOrNull()

        val buildCfg =
            buildGitHubConfigFromBuildConfig()

        val inputOwner =
            inputData.getString(KEY_OWNER)
                ?.trim()
                .orEmpty()

        val inputRepo =
            inputData.getString(KEY_REPO)
                ?.trim()
                .orEmpty()

        val legacyInputToken =
            inputData.getString(KEY_TOKEN)
                ?.trim()
                .orEmpty()

        val inputBranch =
            inputData.getString(KEY_BRANCH)
                ?.trim()

        val inputPrefix =
            inputData.getString(KEY_PATH_PREFIX)

        var owner =
            inputOwner.ifBlank {
                stored?.owner?.trim()
                    .orEmpty()
                    .ifBlank {
                        buildCfg?.owner?.trim().orEmpty()
                    }
            }

        var repo =
            inputRepo.ifBlank {
                stored?.repo?.trim()
                    .orEmpty()
                    .ifBlank {
                        buildCfg?.repo?.trim().orEmpty()
                    }
            }

        val token =
            legacyInputToken.ifBlank {
                stored?.token?.trim()
                    .orEmpty()
                    .ifBlank {
                        buildCfg?.token?.trim().orEmpty()
                    }
            }

        val branch =
            inputBranch
                ?.takeIf { it.isNotBlank() }
                ?: stored?.branch
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: buildCfg?.branch
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: "main"

        val pathPrefix =
            if (inputPrefix != null) {
                inputPrefix.trim().trim('/')
            } else {
                stored?.pathPrefix
                    ?.trim()
                    ?.trim('/')
                    ?: buildCfg?.pathPrefix
                        ?.trim()
                        ?.trim('/')
                        .orEmpty()
            }

        if (repo.contains('/')) {
            val inferredOwner =
                repo.substringBefore('/').trim()

            val inferredRepo =
                repo.substringAfterLast('/').trim()

            if (owner.isBlank()) {
                owner = inferredOwner
            }

            repo = inferredRepo
        }

        if (
            owner.isBlank() ||
            repo.isBlank() ||
            token.isBlank()
        ) {
            return null
        }

        if (
            owner.any(Char::isWhitespace) ||
            repo.any(Char::isWhitespace)
        ) {
            return null
        }

        return GitHubUploader.GitHubConfig(
            owner = owner,
            repo = repo,
            token = token,
            branch = branch,
            pathPrefix = pathPrefix,
            maxRawBytesHint = maxRawBytesHint,
            maxRequestBytesHint = maxRequestBytesHint
        )
    }

    private fun buildGitHubConfigFromBuildConfig(): GitHubUploader.GitHubConfig? {
        val token = BuildConfig.GH_TOKEN.trim()
        val rawRepo = BuildConfig.GH_REPO.trim()

        if (
            token.isBlank() ||
            rawRepo.isBlank()
        ) {
            return null
        }

        var owner = BuildConfig.GH_OWNER.trim()
        var repo = rawRepo

        if (rawRepo.contains('/')) {
            if (owner.isBlank()) {
                owner =
                    rawRepo.substringBefore('/').trim()
            }

            repo =
                rawRepo.substringAfterLast('/').trim()
        }

        if (
            owner.isBlank() ||
            repo.isBlank()
        ) {
            return null
        }

        return GitHubUploader.GitHubConfig(
            owner = owner,
            repo = repo,
            token = token,
            branch =
                BuildConfig.GH_BRANCH
                    .trim()
                    .ifBlank { "main" },
            pathPrefix =
                BuildConfig.GH_PATH_PREFIX
                    .trim()
                    .trim('/')
        )
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
        } catch (ce: CancellationException) {
            throw ce
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

            retryOrFailure(
                t = t,
                errorMessage =
                    t.message ?: "Unknown error"
            )
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

            val sessionId = resolvedSessionId("logcat")
            val dateSegment = if (addDate) resolvedSessionDateFolderUtc() else ""
            val remoteName = "logcat_${sessionId}.log.gz"
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
        } catch (ce: CancellationException) {
            throw ce
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

            retryOrFailure(
                t = t,
                errorMessage =
                    t.message ?: "Unknown error"
            )
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
            delay(STARTUP_RTLOG_PRE_DELAY_MS)
        }

        val sessionId = resolvedSessionId("runtime")
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

        val dateSegment = if (addDate) resolvedSessionDateFolderUtc() else ""
        val sessionDirName = "runtime_logs_${sessionId}_${safeReason}"
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
                        trimLedgerInPlace(
                            ledger,
                            LEDGER_MAX_ITEMS
                        )
                        recordLedgerFingerprint(
                            appCtx,
                            fp
                        )
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
                    val remotePath = "$remoteBaseDir/logcat_${sessionId}.log.gz"
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
        } catch (ce: CancellationException) {
            throw ce
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

            retryOrFailure(
                t = t,
                errorMessage =
                    t.message ?: "Unknown error"
            )
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
        val remoteDir =
            inputData.getString(KEY_RING_REMOTE_DIR)
                ?: "diagnostics/applog_ring"

        val addDate =
            inputData.getBoolean(
                KEY_RING_ADD_DATE,
                true
            )

        val reason =
            inputData.getString(KEY_RING_REASON)
                ?.takeIf { it.isNotBlank() }
                ?: "wm"

        onProgress(3)

        if (mode == MODE_STARTUP_RING_LOGS) {
            delay(STARTUP_RING_PRE_DELAY_MS)
        }

        val sessionId =
            resolvedSessionId("ring")

        val safeReason =
            safeFileSegment(reason)

        val ringDir =
            runCatching {
                AppRingLogStore.ringDir(applicationContext)
            }.getOrNull()

        if (
            ringDir == null ||
            !ringDir.isDirectory
        ) {
            Log.d(
                TAG,
                "doRingLogsUpload: ringDir not available; no-op success."
            )

            return Result.success(
                workDataOf(
                    OUT_MODE to MODE_RING_LOGS,
                    OUT_REMOTE_PATH to "",
                    OUT_RING_FILE_COUNT to 0,
                    OUT_RING_TOTAL_BYTES to 0L
                )
            )
        }

        val snapshotDir =
            File(
                applicationContext.cacheDir,
                "diagnostics_ring_upload/$sessionId"
            )

        val snapshots =
            runCatching {
                prepareRingSnapshot(
                    ringDir = ringDir,
                    snapshotDir = snapshotDir
                )
            }.getOrElse { t ->
                Log.w(
                    TAG,
                    "doRingLogsUpload: failed to prepare stable ring snapshot: ${t.message}",
                    t
                )

                return retryOrFailure(
                    t = t,
                    errorMessage =
                        "Failed to prepare ring snapshot: ${t.message}"
                )
            }

        if (snapshots.isEmpty()) {
            Log.d(
                TAG,
                "doRingLogsUpload: no ring segments found; no-op success. dir=${ringDir.absolutePath}"
            )

            runCatching {
                snapshotDir.deleteRecursively()
            }

            return Result.success(
                workDataOf(
                    OUT_MODE to MODE_RING_LOGS,
                    OUT_REMOTE_PATH to "",
                    OUT_RING_FILE_COUNT to 0,
                    OUT_RING_TOTAL_BYTES to 0L
                )
            )
        }

        val dateSegment =
            if (addDate) resolvedSessionDateFolderUtc() else ""

        val sessionDirName =
            "applog_ring_${sessionId}_${safeReason}"

        val remoteBaseDir =
            listOf(
                cfg.pathPrefix.trim('/'),
                remoteDir.trim('/'),
                dateSegment,
                sessionDirName
            ).filter { it.isNotBlank() }
                .joinToString("/")

        val totalBytes =
            snapshots.sumOf { file ->
                runCatching {
                    file.length()
                }.getOrDefault(0L)
            }

        Log.d(
            TAG,
            "doRingLogsUpload: remoteBaseDir=$remoteBaseDir " +
                    "segs=${snapshots.size} bytes=$totalBytes " +
                    "source=${ringDir.absolutePath} snapshot=${snapshotDir.absolutePath}"
        )

        return try {
            val startPct = 10
            val endPct = 100
            val count =
                snapshots.size.coerceAtLeast(1)

            for ((index, file) in snapshots.withIndex()) {
                val fileStart =
                    startPct +
                            ((endPct - startPct) * index) / count

                val fileEnd =
                    startPct +
                            ((endPct - startPct) * (index + 1)) / count

                val mapped =
                    mapProgressRange(
                        start = fileStart,
                        end = fileEnd,
                        sink = onProgress
                    )

                val remotePath =
                    "$remoteBaseDir/${file.name}"

                GitHubUploader.uploadFileAtPath(
                    cfg = cfg,
                    path = remotePath,
                    file = file,
                    message =
                        "Upload ring logs (stable snapshot) ($reason)",
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

            /**
             * Keep the stable snapshot across retries, but remove it only after
             * the whole upload batch has succeeded.
             */
            runCatching {
                snapshotDir.deleteRecursively()
            }

            Result.success(
                workDataOf(
                    OUT_MODE to MODE_RING_LOGS,
                    OUT_REMOTE_PATH to remoteBaseDir,
                    OUT_RING_FILE_COUNT to snapshots.size,
                    OUT_RING_TOTAL_BYTES to totalBytes
                )
            )
        } catch (ce: CancellationException) {
            /**
             * Do not delete snapshotDir here. WorkManager may retry this same
             * request later, and the stable files are intentionally reusable.
             */
            throw ce
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "doRingLogsUpload: upload failed",
                t
            )

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

            retryOrFailure(
                t = t,
                errorMessage =
                    t.message ?: "Unknown ring upload error"
            )
        }
    }

    /**
     * Prepare a stable cache snapshot of ring segments.
     *
     * WorkManager may retry the same request after the live ring has changed.
     * Reusing a completed snapshot keeps the remote payload stable across
     * attempts and avoids reading a segment while AppRingLogStore is appending.
     */
    private fun prepareRingSnapshot(
        ringDir: File,
        snapshotDir: File
    ): List<File> {
        val completeMarker =
            File(
                snapshotDir,
                ".complete"
            )

        if (completeMarker.isFile) {
            val existing =
                listRingSnapshotFiles(snapshotDir)

            if (existing.isNotEmpty()) {
                return existing
            }

            runCatching {
                completeMarker.delete()
            }
        }

        if (snapshotDir.exists()) {
            snapshotDir.deleteRecursively()
        }

        if (
            !snapshotDir.mkdirs() &&
            !snapshotDir.isDirectory
        ) {
            throw IOException(
                "Failed to create ring snapshot directory: ${snapshotDir.absolutePath}"
            )
        }

        val sources =
            ringDir.listFiles()
                ?.asSequence()
                ?.filter { file ->
                    file.isFile &&
                            file.length() > 0L &&
                            file.name.startsWith(
                                "seg_",
                                ignoreCase = true
                            ) &&
                            file.name.endsWith(
                                ".log",
                                ignoreCase = true
                            )
                }
                ?.sortedBy { it.name }
                ?.toList()
                .orEmpty()

        if (sources.isEmpty()) {
            return emptyList()
        }

        val snapshots =
            ArrayList<File>(
                sources.size
            )

        for (source in sources) {
            val target =
                File(
                    snapshotDir,
                    source.name
                )

            copyStableSnapshotFile(
                source = source,
                target = target
            )

            if (
                target.isFile &&
                target.length() > 0L
            ) {
                snapshots += target
            }
        }

        if (snapshots.isNotEmpty()) {
            FileOutputStream(
                completeMarker,
                false
            ).use { output ->
                output.write(
                    (
                            "session=${resolvedSessionId("ring")}\n" +
                                    "files=${snapshots.size}\n" +
                                    "created_utc=${utcIsoTimestamp()}\n"
                            ).toByteArray(Charsets.UTF_8)
                )

                output.flush()

                runCatching {
                    output.fd.sync()
                }
            }
        }

        return snapshots
    }

    private fun listRingSnapshotFiles(
        snapshotDir: File
    ): List<File> =
        snapshotDir.listFiles()
            ?.asSequence()
            ?.filter { file ->
                file.isFile &&
                        file.length() > 0L &&
                        file.name.startsWith(
                            "seg_",
                            ignoreCase = true
                        ) &&
                        file.name.endsWith(
                            ".log",
                            ignoreCase = true
                        )
            }
            ?.sortedBy { it.name }
            ?.toList()
            .orEmpty()

    /**
     * Copy a live ring segment into a temporary file and verify that the source
     * did not change during the copy. Retry a few times for the active segment.
     */
    private fun copyStableSnapshotFile(
        source: File,
        target: File
    ) {
        val temp =
            File(
                target.parentFile,
                "${target.name}.part"
            )

        var lastError: Throwable? = null

        repeat(RING_SNAPSHOT_COPY_ATTEMPTS) { attempt ->
            try {
                val beforeLength =
                    source.length()

                val beforeModified =
                    source.lastModified()

                FileInputStream(source).use { input ->
                    FileOutputStream(
                        temp,
                        false
                    ).use { output ->
                        input.copyTo(
                            output,
                            bufferSize = 32 * 1024
                        )

                        output.flush()
                    }
                }

                val afterLength =
                    source.length()

                val afterModified =
                    source.lastModified()

                val stable =
                    beforeLength == afterLength &&
                            beforeModified == afterModified &&
                            temp.length() == beforeLength

                if (stable) {
                    if (target.exists()) {
                        target.delete()
                    }

                    if (!temp.renameTo(target)) {
                        temp.copyTo(
                            target,
                            overwrite = true
                        )
                        temp.delete()
                    }

                    target.setLastModified(
                        beforeModified
                    )

                    return
                }

                Log.d(
                    TAG,
                    "Ring segment changed during snapshot; retrying. " +
                            "file=${source.name} attempt=${attempt + 1}/$RING_SNAPSHOT_COPY_ATTEMPTS"
                )
            } catch (t: Throwable) {
                lastError = t
            }
        }

        if (
            temp.isFile &&
            temp.length() > 0L
        ) {
            Log.w(
                TAG,
                "Using best-effort ring snapshot after repeated live-file changes. file=${source.name}"
            )

            if (target.exists()) {
                target.delete()
            }

            if (!temp.renameTo(target)) {
                temp.copyTo(
                    target,
                    overwrite = true
                )
                temp.delete()
            }

            return
        }

        runCatching {
            temp.delete()
        }

        throw IOException(
            "Could not snapshot ring segment: ${source.name}",
            lastError
        )
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
        val dir =
            File(
                ctx.filesDir,
                "diagnostics/runtime_logs"
            )

        if (!dir.isDirectory) {
            dir.mkdirs()
        }

        return File(
            dir,
            "upload_ledger.json"
        )
    }

    private fun loadLedger(
        ctx: Context
    ): LinkedHashSet<String> =
        synchronized(LEDGER_LOCK) {
            readLedgerUnlocked(ctx)
        }

    /**
     * Record one successful fingerprint without losing entries that may have
     * been written by another Worker instance in the same process.
     */
    private fun recordLedgerFingerprint(
        ctx: Context,
        fingerprint: String
    ) {
        if (fingerprint.isBlank()) {
            return
        }

        synchronized(LEDGER_LOCK) {
            val current =
                readLedgerUnlocked(ctx)

            current.add(fingerprint)
            trimLedgerInPlace(
                current,
                LEDGER_MAX_ITEMS
            )

            writeLedgerAtomicUnlocked(
                ctx,
                current
            )
        }
    }

    private fun readLedgerUnlocked(
        ctx: Context
    ): LinkedHashSet<String> {
        val file = ledgerFile(ctx)

        if (!file.isFile) {
            return LinkedHashSet()
        }

        return runCatching {
            val obj =
                JSONObject(
                    file.readText(Charsets.UTF_8)
                )

            val arr =
                obj.optJSONArray("items")
                    ?: JSONArray()

            val out =
                LinkedHashSet<String>(
                    arr.length()
                        .coerceAtLeast(16)
                )

            for (index in 0 until arr.length()) {
                val value =
                    arr.optString(index)
                        .takeIf { it.isNotBlank() }
                        ?: continue

                out.add(value)
            }

            out
        }.getOrElse { t ->
            Log.w(
                TAG,
                "loadLedger: invalid ledger; treating as empty. err=${t.message}",
                t
            )
            LinkedHashSet()
        }
    }

    /**
     * Write through a same-directory temporary file and rename it into place.
     *
     * A damaged ledger should cause duplicate uploads, not source-log loss.
     */
    private fun writeLedgerAtomicUnlocked(
        ctx: Context,
        set: LinkedHashSet<String>
    ) {
        val file = ledgerFile(ctx)
        val parent =
            file.parentFile
                ?: throw IOException(
                    "Ledger parent directory is unavailable."
                )

        if (
            !parent.isDirectory &&
            !parent.mkdirs() &&
            !parent.isDirectory
        ) {
            throw IOException(
                "Failed to create ledger directory: ${parent.absolutePath}"
            )
        }

        val obj = JSONObject()
        obj.put("v", 2)
        obj.put(
            "updated_utc",
            utcIsoTimestamp()
        )
        obj.put(
            "max",
            LEDGER_MAX_ITEMS
        )

        val arr = JSONArray()
        set.forEach { arr.put(it) }
        obj.put("items", arr)

        val temp =
            File(
                parent,
                "${file.name}.tmp"
            )

        FileOutputStream(temp, false).use { output ->
            output.write(
                obj.toString(2)
                    .toByteArray(Charsets.UTF_8)
            )
            output.flush()
            runCatching {
                output.fd.sync()
            }
        }

        if (file.exists() && !file.delete()) {
            /**
             * renameTo() cannot replace an existing file consistently across
             * all Android filesystems, so remove the old destination first.
             */
            runCatching {
                temp.delete()
            }

            throw IOException(
                "Failed to replace ledger: ${file.absolutePath}"
            )
        }

        if (!temp.renameTo(file)) {
            /**
             * Same-filesystem rename should normally succeed. Fall back to a
             * direct copy so ledger maintenance remains best-effort.
             */
            temp.copyTo(
                target = file,
                overwrite = true
            )

            runCatching {
                temp.delete()
            }
        }
    }

    private fun trimLedgerInPlace(
        set: LinkedHashSet<String>,
        maxItems: Int
    ) {
        while (set.size > maxItems) {
            val iterator = set.iterator()

            if (!iterator.hasNext()) {
                return
            }

            iterator.next()
            iterator.remove()
        }
    }

    private fun sha256HexOfFile(
        file: File
    ): String {
        val digest =
            MessageDigest.getInstance("SHA-256")

        FileInputStream(file).use { input ->
            val buffer =
                ByteArray(32 * 1024)

            while (true) {
                val count =
                    input.read(buffer)

                if (count <= 0) {
                    break
                }

                digest.update(
                    buffer,
                    0,
                    count
                )
            }
        }

        return digest.digest()
            .joinToString("") { byte ->
                (byte.toInt() and 0xff)
                    .toString(16)
                    .padStart(2, '0')
            }
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

    private fun copyTailOrFull(
        src: File,
        dst: File,
        maxBytes: Long
    ) {
        dst.parentFile?.let { parent ->
            if (
                !parent.isDirectory &&
                !parent.mkdirs() &&
                !parent.isDirectory
            ) {
                throw IOException(
                    "Failed to create directory: ${parent.absolutePath}"
                )
            }
        }

        val limit =
            maxBytes.coerceAtLeast(50_000L)

        val length =
            runCatching {
                src.length()
            }.getOrDefault(0L)

        if (length <= 0L) {
            FileOutputStream(dst, false).use { output ->
                output.flush()
                runCatching {
                    output.fd.sync()
                }
            }
            return
        }

        if (length <= limit) {
            FileInputStream(src).use { input ->
                FileOutputStream(dst, false).use { output ->
                    input.copyTo(
                        output,
                        bufferSize = 16 * 1024
                    )
                    output.flush()
                    runCatching {
                        output.fd.sync()
                    }
                }
            }
            return
        }

        RandomAccessFile(src, "r").use { raf ->
            var start =
                (length - limit)
                    .coerceAtLeast(0L)

            raf.seek(start)

            /**
             * A tail cut can begin in the middle of a UTF-8 sequence or log
             * line. Skip through the next newline so uploaded diagnostics
             * begin at a clean line boundary when possible.
             */
            if (start > 0L) {
                while (
                    raf.filePointer < length
                ) {
                    val byte =
                        raf.read()

                    if (byte < 0) {
                        break
                    }

                    if (byte == '\n'.code) {
                        start =
                            raf.filePointer
                        break
                    }
                }
            }

            FileOutputStream(dst, false).use { output ->
                val copiedLimit =
                    (length - start)
                        .coerceAtLeast(0L)

                val header =
                    "=== TRUNCATED TAIL COPY ===\n" +
                            "original_bytes=$length " +
                            "copied_tail_bytes=$copiedLimit\n\n"

                output.write(
                    header.toByteArray(
                        Charsets.UTF_8
                    )
                )

                val buffer =
                    ByteArray(16 * 1024)

                var remaining =
                    copiedLimit

                while (remaining > 0L) {
                    val toRead =
                        minOf(
                            buffer.size.toLong(),
                            remaining
                        ).toInt()

                    val count =
                        raf.read(
                            buffer,
                            0,
                            toRead
                        )

                    if (count <= 0) {
                        break
                    }

                    output.write(
                        buffer,
                        0,
                        count
                    )

                    remaining -=
                        count.toLong()
                }

                output.flush()

                runCatching {
                    output.fd.sync()
                }
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

    private fun runCommand(
        cmd: List<String>,
        timeoutMs: Long,
        maxStdoutBytes: Int
    ): String {
        val safeLimit =
            maxStdoutBytes.coerceAtLeast(1)

        val process =
            try {
                ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()
            } catch (t: Throwable) {
                return "(command failed: ${t.message})\n"
            }

        val collector =
            CappedByteCollector(safeLimit)

        val readerDone =
            CountDownLatch(1)

        val readerError =
            AtomicReference<Throwable?>(null)

        val reader =
            Thread(
                {
                    try {
                        process.inputStream.use { input ->
                            val buffer =
                                ByteArray(8 * 1024)

                            while (collector.remaining() > 0) {
                                val count =
                                    input.read(
                                        buffer,
                                        0,
                                        minOf(
                                            buffer.size,
                                            collector.remaining()
                                        )
                                    )

                                if (count <= 0) {
                                    break
                                }

                                collector.write(
                                    buffer = buffer,
                                    offset = 0,
                                    count = count
                                )
                            }
                        }
                    } catch (t: Throwable) {
                        readerError.set(t)
                    } finally {
                        readerDone.countDown()
                    }
                },
                "GitHubUploadWorker-CommandReader"
            ).apply {
                isDaemon = true
            }

        reader.start()

        val completed =
            runCatching {
                readerDone.await(
                    timeoutMs.coerceAtLeast(1L),
                    TimeUnit.MILLISECONDS
                )
            }.getOrDefault(false)

        if (!completed) {
            runCatching {
                process.destroy()
            }

            runCatching {
                process.waitFor(
                    COMMAND_KILL_GRACE_MS,
                    TimeUnit.MILLISECONDS
                )
            }

            if (process.isAlive) {
                runCatching {
                    process.destroyForcibly()
                }
            }

            runCatching {
                readerDone.await(
                    COMMAND_KILL_GRACE_MS,
                    TimeUnit.MILLISECONDS
                )
            }

            collector.appendUtf8(
                "\n(command timeout: ${cmd.joinToString(" ")})\n"
            )
        } else {
            runCatching {
                process.waitFor(
                    COMMAND_KILL_GRACE_MS,
                    TimeUnit.MILLISECONDS
                )
            }
        }

        runCatching {
            if (process.isAlive) {
                process.destroy()
            }
        }

        runCatching {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }

        val error =
            readerError.get()

        if (
            error != null &&
            collector.size() == 0
        ) {
            return "(read failed: ${error.message})\n"
        }

        val output =
            collector.toByteArray()
                .toString(Charsets.UTF_8)

        return output.ifBlank {
            "(logcat empty or restricted)\n"
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

    private fun resolvedSessionId(
        prefix: String
    ): String {
        val supplied =
            inputData.getString(KEY_SESSION_ID)
                ?.let(::safeFileSegmentLong)
                ?.takeIf { it.isNotBlank() }

        if (supplied != null) {
            return supplied
        }

        return safeFileSegmentLong(
            "${prefix}_work_${id}"
        )
    }

    private fun resolvedSessionDateFolderUtc(): String {
        val sessionId =
            inputData.getString(KEY_SESSION_ID)
                .orEmpty()

        val compactDate =
            Regex("""(?:^|_)(\d{8})(?:_|$)""")
                .find(sessionId)
                ?.groupValues
                ?.getOrNull(1)

        if (
            compactDate != null &&
            compactDate.length == 8
        ) {
            return buildString(10) {
                append(compactDate, 0, 4)
                append('-')
                append(compactDate, 4, 6)
                append('-')
                append(compactDate, 6, 8)
            }
        }

        return utcDateFolder()
    }

    private fun utcIsoTimestamp(): String =
        SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            Locale.US
        ).apply {
            timeZone =
                TimeZone.getTimeZone("UTC")
        }.format(Date())

    private fun retryOrFailure(
        t: Throwable,
        errorMessage: String
    ): Result {
        val attemptsUsed =
            runAttemptCount + 1

        return if (
            shouldRetry(t) &&
            attemptsUsed < MAX_ATTEMPTS
        ) {
            Result.retry()
        } else {
            Result.failure(
                workDataOf(
                    ERROR_MESSAGE to errorMessage
                )
            )
        }
    }

    private class CappedByteCollector(
        cap: Int
    ) {
        private val buffer =
            ByteArray(
                cap.coerceAtLeast(0)
            )

        private var size = 0

        @Synchronized
        fun remaining(): Int =
            buffer.size - size

        @Synchronized
        fun size(): Int =
            size

        @Synchronized
        fun write(
            buffer: ByteArray,
            offset: Int,
            count: Int
        ) {
            if (
                count <= 0 ||
                size >= this.buffer.size
            ) {
                return
            }

            val safeCount =
                minOf(
                    count,
                    this.buffer.size - size
                )

            System.arraycopy(
                buffer,
                offset,
                this.buffer,
                size,
                safeCount
            )

            size += safeCount
        }

        @Synchronized
        fun appendUtf8(
            value: String
        ) {
            val bytes =
                value.toByteArray(
                    Charsets.UTF_8
                )

            write(
                buffer = bytes,
                offset = 0,
                count = bytes.size
            )
        }

        @Synchronized
        fun toByteArray(): ByteArray =
            buffer.copyOfRange(
                0,
                size
            )
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
        // English comments only.
        // Notification channels exist only on API 26+.
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

    private fun safeFileSegmentLong(s: String): String =
        s.trim()
            .replace(Regex("""[^A-Za-z0-9_\-\.]+"""), "_")
            .trim('_')
            .take(96)

    private fun shouldRetry(t: Throwable): Boolean {
        val message =
            t.message.orEmpty()

        if (
            message.startsWith(
                "Transient HTTP ",
                ignoreCase = true
            )
        ) {
            return true
        }

        if (
            message.contains(
                "rate limit",
                ignoreCase = true
            ) ||
            message.contains(
                "secondary rate",
                ignoreCase = true
            ) ||
            message.contains(
                "abuse detection",
                ignoreCase = true
            )
        ) {
            return true
        }

        val httpCode =
            Regex("""\((\d{3})\)""")
                .find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        if (httpCode != null) {
            if (
                httpCode in 500..599 ||
                httpCode == 408 ||
                httpCode == 425 ||
                httpCode == 429
            ) {
                return true
            }

            if (httpCode in 400..499) {
                return false
            }
        }

        if (
            message.contains(
                "too large",
                ignoreCase = true
            ) ||
            message.contains(
                "bad credentials",
                ignoreCase = true
            ) ||
            message.contains(
                "requires authentication",
                ignoreCase = true
            )
        ) {
            return false
        }

        return t is IOException ||
                message.contains(
                    "timeout",
                    ignoreCase = true
                )
    }

    companion object {
        const val TAG = "github_upload"

        private const val CHANNEL_ID = "uploads"
        private const val NOTIF_BASE = 3200
        private const val MAX_ATTEMPTS = 5

        private const val DEFAULT_MAX_RAW_BYTES_HINT = 1_000_000L
        private const val COMMAND_STDOUT_MAX_BYTES = 700_000
        private const val COMMAND_KILL_GRACE_MS = 120L

        private const val LOGCAT_TAIL_LINES = 1200
        private const val LOGCAT_CRASH_TAIL_LINES = 200

        private val TEXT_EXTENSIONS = setOf("json", "jsonl", "txt", "csv")

        private const val STARTUP_RTLOG_PRE_DELAY_MS = 1200L
        private const val DEFAULT_RTLOG_PLAIN_LIMIT_FILES = 12

        private const val STARTUP_RING_PRE_DELAY_MS = 700L

        private const val LEDGER_MAX_ITEMS = 4000
        private const val RING_SNAPSHOT_COPY_ATTEMPTS = 3

        /** Shared across every Worker instance in this app process. */
        private val LEDGER_LOCK = Any()

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

        /** Stable across WorkManager retries for one logical upload session. */
        const val KEY_SESSION_ID = "sessionId"

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
         * Enqueue app-owned runtime logs.
         *
         * A stable session id is placed in input Data so retries keep writing
         * to the same remote folder.
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
            val appCtx =
                context.applicationContext
                    ?: context

            val sessionId =
                newSessionId("runtime")

            val uniqueName =
                "upload_runtime_logs_$sessionId"

            val input =
                buildWorkerInputData(
                    context = appCtx,
                    cfg = cfg
                ) {
                    putString(
                        KEY_MODE,
                        MODE_RUNTIME_LOGS
                    )
                    putString(
                        KEY_SESSION_ID,
                        sessionId
                    )
                    putString(
                        KEY_RTLOG_REMOTE_DIR,
                        remoteDir
                    )
                    putBoolean(
                        KEY_RTLOG_ADD_DATE,
                        addDateSubdir
                    )
                    putString(
                        KEY_RTLOG_REASON,
                        reason
                    )
                    putBoolean(
                        KEY_RTLOG_DELETE_ZIP_AFTER,
                        deleteZipAfter
                    )
                    putLong(
                        KEY_RTLOG_MAX_ZIP_BYTES,
                        maxZipBytes
                    )
                    putBoolean(
                        KEY_RTLOG_DEDUPE_ENABLE,
                        true
                    )
                    putBoolean(
                        KEY_RTLOG_DELETE_SOURCE_AFTER_UPLOAD,
                        deleteSourceAfterUpload
                    )
                }

            val request =
                OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                    .setInputData(input)
                    .setConstraints(networkConstraints())
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30,
                        TimeUnit.SECONDS
                    )
                    .setExpedited(
                        OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
                    )
                    .addTag(TAG)
                    .addTag("$TAG:runtime_logs")
                    .build()

            WorkManager.getInstance(appCtx)
                .enqueueUniqueWork(
                    uniqueName,
                    ExistingWorkPolicy.KEEP,
                    request
                )
        }

        /**
         * Enqueue startup runtime-log upload.
         *
         * KEEP is sufficient here: WorkManager's unique-work conflict policy is
         * only relevant when an unfinished chain with this name already exists.
         * There is no need to synchronously query WorkManager first.
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
            val appCtx =
                context.applicationContext
                    ?: context

            val uniqueName =
                "upload_runtime_logs_startup"

            val sessionId =
                newSessionId("startup_runtime")

            val input =
                buildWorkerInputData(
                    context = appCtx,
                    cfg = cfg
                ) {
                    putString(
                        KEY_MODE,
                        MODE_STARTUP_RUNTIME_LOGS
                    )
                    putString(
                        KEY_SESSION_ID,
                        sessionId
                    )
                    putString(
                        KEY_RTLOG_REMOTE_DIR,
                        remoteDir
                    )
                    putBoolean(
                        KEY_RTLOG_ADD_DATE,
                        addDateSubdir
                    )
                    putString(
                        KEY_RTLOG_REASON,
                        reason
                    )
                    putBoolean(
                        KEY_RTLOG_DELETE_ZIP_AFTER,
                        deleteZipAfter
                    )
                    putLong(
                        KEY_RTLOG_MAX_ZIP_BYTES,
                        maxZipBytes
                    )
                    putBoolean(
                        KEY_RTLOG_DEDUPE_ENABLE,
                        true
                    )
                    putBoolean(
                        KEY_RTLOG_DELETE_SOURCE_AFTER_UPLOAD,
                        deleteSourceAfterUpload
                    )
                }

            val request =
                OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                    .setInputData(input)
                    .setConstraints(networkConstraints())
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30,
                        TimeUnit.SECONDS
                    )
                    .setExpedited(
                        OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
                    )
                    .addTag(TAG)
                    .addTag("$TAG:startup_runtime_logs")
                    .build()

            Log.d(
                TAG,
                "enqueueStartupRuntimeLogsUpload: uniqueName=$uniqueName policy=KEEP session=$sessionId"
            )

            WorkManager.getInstance(appCtx)
                .enqueueUniqueWork(
                    uniqueName,
                    ExistingWorkPolicy.KEEP,
                    request
                )
        }

        /**
         * Enqueue startup AppRingLogStore upload.
         *
         * The Worker copies live segments into a stable cache snapshot before
         * network upload. The ring itself is never deleted.
         */
        fun enqueueStartupRingLogsUpload(
            context: Context,
            cfg: GitHubUploader.GitHubConfig,
            remoteDir: String = "diagnostics/applog_ring",
            addDateSubdir: Boolean = true,
            reason: String = "app_start",
        ) {
            val appCtx =
                context.applicationContext
                    ?: context

            val uniqueName =
                "upload_ring_logs_startup"

            val sessionId =
                newSessionId("startup_ring")

            val input =
                buildWorkerInputData(
                    context = appCtx,
                    cfg = cfg
                ) {
                    putString(
                        KEY_MODE,
                        MODE_STARTUP_RING_LOGS
                    )
                    putString(
                        KEY_SESSION_ID,
                        sessionId
                    )
                    putString(
                        KEY_RING_REMOTE_DIR,
                        remoteDir
                    )
                    putBoolean(
                        KEY_RING_ADD_DATE,
                        addDateSubdir
                    )
                    putString(
                        KEY_RING_REASON,
                        reason
                    )
                }

            val request =
                OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                    .setInputData(input)
                    .setConstraints(networkConstraints())
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        30,
                        TimeUnit.SECONDS
                    )
                    .setExpedited(
                        OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
                    )
                    .addTag(TAG)
                    .addTag("$TAG:ring_logs")
                    .build()

            Log.d(
                TAG,
                "enqueueStartupRingLogsUpload: uniqueName=$uniqueName policy=KEEP session=$sessionId"
            )

            WorkManager.getInstance(appCtx)
                .enqueueUniqueWork(
                    uniqueName,
                    ExistingWorkPolicy.KEEP,
                    request
                )
        }

        /**
         * Build common WorkManager input.
         *
         * KEY_TOKEN remains supported for compatibility with existing callers,
         * but these helpers avoid persisting it whenever the same credential is
         * already available from GitHubDiagnosticsConfigStore or BuildConfig.
         */
        private fun buildWorkerInputData(
            context: Context,
            cfg: GitHubUploader.GitHubConfig,
            extras: Data.Builder.() -> Unit
        ): Data {
            val builder =
                Data.Builder()
                    .putString(
                        KEY_OWNER,
                        cfg.owner
                    )
                    .putString(
                        KEY_REPO,
                        cfg.repo
                    )
                    .putString(
                        KEY_BRANCH,
                        cfg.branch
                    )
                    .putString(
                        KEY_PATH_PREFIX,
                        cfg.pathPrefix
                    )

            if (
                shouldEmbedLegacyToken(
                    context = context,
                    cfg = cfg
                )
            ) {
                /**
                 * Compatibility fallback for callers that provide an ephemeral
                 * credential not available from the app's credential store.
                 */
                builder.putString(
                    KEY_TOKEN,
                    cfg.token
                )

                Log.w(
                    TAG,
                    "Work request is using legacy embedded credential fallback. " +
                            "Persist this credential in GitHubDiagnosticsConfigStore to avoid placing it in WorkManager Data."
                )
            }

            builder.extras()
            return builder.build()
        }

        private fun shouldEmbedLegacyToken(
            context: Context,
            cfg: GitHubUploader.GitHubConfig
        ): Boolean {
            val requested =
                cfg.token.trim()

            if (requested.isBlank()) {
                return false
            }

            val stored =
                runCatching {
                    GitHubDiagnosticsConfigStore
                        .buildGitHubConfigOrNull(context)
                        ?.token
                        ?.trim()
                }.getOrNull()
                    .orEmpty()

            if (
                stored.isNotBlank() &&
                stored == requested
            ) {
                return false
            }

            val builtIn =
                BuildConfig.GH_TOKEN.trim()

            if (
                builtIn.isNotBlank() &&
                builtIn == requested
            ) {
                return false
            }

            return true
        }

        private fun networkConstraints(): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(
                    NetworkType.CONNECTED
                )
                .build()

        private fun newSessionId(
            prefix: String
        ): String {
            val timestamp =
                SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    Locale.US
                ).apply {
                    timeZone =
                        TimeZone.getTimeZone("UTC")
                }.format(Date())

            val random =
                UUID.randomUUID()
                    .toString()
                    .substring(0, 8)

            val safePrefix =
                prefix.trim()
                    .replace(
                        Regex("""[^A-Za-z0-9_\-\.]+"""),
                        "_"
                    )
                    .trim('_')
                    .ifBlank { "session" }
                    .take(24)

            return "${safePrefix}_${timestamp}_$random"
        }
    }
}
