/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: UploadRescheduleReceiver.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  BroadcastReceiver that automatically re-enqueues any pending uploads
 *  after system reboot, user unlock, or app update.
 *
 *  - GitHub pending dir: /files/pending_uploads/
 *  - Supabase pending dirs (historical):
 *      /files/pending_uploads_supabase/
 *      /files/pending_uploads_sb/
 *      /files/pending_uploads/supabase/...
 *
 *  Notes:
 *  - onReceive must return quickly. Heavy I/O is moved to goAsync + IO dispatcher.
 *  - For LOCKED_BOOT_COMPLETED (Direct Boot), WorkManager enqueue may be unreliable
 *    if its database lives in credential-protected storage. We defer enqueue until
 *    USER_UNLOCKED / BOOT_COMPLETED whenever possible.
 *
 *  Added:
 *  - Enqueue startup runtime logs upload (files/diagnostics/runtime_logs) on relevant actions.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.negi.survey.BuildConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class UploadRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!isRelevantAction(action)) return

        // Avoid concurrent heavy scans from multiple broadcasts.
        if (!IS_RUNNING.compareAndSet(false, true)) {
            Log.d(TAG, "Reschedule already running; skip action=$action")
            return
        }

        val pending = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Ensure we never hold the broadcast too long.
                withTimeout(RECEIVER_WORK_TIMEOUT_MS) {
                    val unlocked = isUserUnlocked(context)

                    val ctxNormal = context
                    val ctxDeviceProtected = createDeviceProtectedContextOrNull(context)

                    // For locked boot, we defer enqueue because WorkManager may not be ready.
                    // Still, we may scan device-protected storage if it exists.
                    val contextsForScan = buildList {
                        if (unlocked) {
                            add(ctxNormal)
                            if (ctxDeviceProtected != null) add(ctxDeviceProtected)
                        } else {
                            // Locked: only device-protected storage is safely available.
                            if (ctxDeviceProtected != null) add(ctxDeviceProtected)
                        }
                    }

                    if (contextsForScan.isEmpty()) {
                        Log.d(TAG, "No accessible storage contexts for action=$action (unlocked=$unlocked)")
                        return@withTimeout
                    }

                    if (!unlocked && action == ACTION_LOCKED_BOOT_COMPLETED) {
                        Log.d(TAG, "User locked (Direct Boot). Deferring enqueue until USER_UNLOCKED/BOOT_COMPLETED.")
                        // Best-effort: just log counts (optional) without enqueue.
                        val ghCount = contextsForScan.sumOf { countPendingFiles(it, PENDING_DIR_GH, walk = false) }
                        val sbCount = listOf(PENDING_DIR_SB_V2, PENDING_DIR_SB_V1, PENDING_DIR_SB_NESTED_ROOT)
                            .sumOf { dir -> contextsForScan.sumOf { countPendingFiles(it, dir, walk = true) } }
                        Log.d(TAG, "DirectBoot pending summary: github=$ghCount supabase=$sbCount")
                        return@withTimeout
                    }

                    rescheduleGitHub(contextsForScan, action)
                    // Also enqueue runtime logs bundle upload (best-effort).
                    rescheduleRuntimeLogsUpload(contextsForScan, action)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                Log.w(TAG, "Reschedule failed action=$action: ${exception.message}", exception)
            } finally {
                IS_RUNNING.set(false)
                pending.finish()
            }
        }
    }

    private fun rescheduleGitHub(contexts: List<Context>, action: String) {
        val cfg = GitHubUploader.GitHubConfig(
            owner = BuildConfig.GH_OWNER,
            repo = BuildConfig.GH_REPO,
            token = BuildConfig.GH_TOKEN,
            branch = BuildConfig.GH_BRANCH,
            pathPrefix = BuildConfig.GH_PATH_PREFIX
        )

        if (cfg.owner.isBlank() || cfg.repo.isBlank() || cfg.token.isBlank()) {
            Log.d(TAG, "Skip GitHub reschedule: missing credentials.")
            return
        }

        val appCtx = contexts.first().applicationContext ?: contexts.first()
        try {
            val summary = SurveyUploadRescheduler.recoverPendingSurveyUploads(appCtx, cfg)
            Log.i(
                TAG,
                "Survey recovery action=$action " +
                    "discovered=${summary.discoveredSurveyCount} " +
                    "reconciled=${summary.reconciledCandidateCount} " +
                    "duplicates=${summary.duplicateFileCount} " +
                    "unclassified=${summary.unclassifiedFileCount} " +
                    "operationalFailures=${summary.operationalFailureCount} " +
                    "classifications=${summary.classificationCounts}",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Log.w(TAG, "Survey recovery failed action=$action: ${exception.message}", exception)
        }

        val genericFiles = contexts
            .flatMap { ctx -> listPendingFiles(ctx, PENDING_DIR_GH, walk = false) }
            .distinctBy { stableKey(it) }
            .asSequence()
            .filter { it.isFile && it.length() > 0L }
            .filterNot { shouldIgnorePendingFile(it) }
            .filter { PendingSurveyUploads.surveyIdFromFile(it) == null }
            .take(MAX_SCAN_FILES)
            .toList()

        if (genericFiles.isEmpty()) {
            Log.d(TAG, "No generic GitHub pending files for action=$action")
            return
        }

        Log.d(TAG, "Rescheduling ${genericFiles.size} generic GitHub pending uploads for action=$action")

        genericFiles.forEach { file ->
            try {
                enqueueGenericGitHubFileUpload(appCtx, cfg, file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                Log.w(TAG, "Generic GitHub enqueue failed file=${file.name}: ${exception.message}", exception)
            }
        }
    }

    /**
     * Enqueue an unclassified GitHubUploadWorker in MODE=file without relying on a companion convenience API.
     *
     * Rationale:
     * - GitHubUploadWorker.enqueueExistingPayload(...) may not exist depending on branch/version.
     * - Survey JSON recovery is owned by SurveyUploadWork.reconcile().
     */
    private fun enqueueGenericGitHubFileUpload(
        context: Context,
        cfg: GitHubUploader.GitHubConfig,
        file: File
    ) {
        val remoteRelativePath = file.name
        val bytes = file.length().coerceAtLeast(0L)
        val mtime = file.lastModified()
        val uniqueName = "upload_gh_${file.name}_${bytes}_${mtime}"

        val input = Data.Builder()
            .putString(GitHubUploadWorker.KEY_MODE, "file")
            .putString(GitHubUploadWorker.KEY_OWNER, cfg.owner)
            .putString(GitHubUploadWorker.KEY_REPO, cfg.repo)
            .putString(GitHubUploadWorker.KEY_TOKEN, cfg.token)
            .putString(GitHubUploadWorker.KEY_BRANCH, cfg.branch)
            .putString(GitHubUploadWorker.KEY_PATH_PREFIX, cfg.pathPrefix)
            .putString(GitHubUploadWorker.KEY_FILE_PATH, file.absolutePath)
            .putString(GitHubUploadWorker.KEY_FILE_NAME, remoteRelativePath)
            .putLong(GitHubUploadWorker.KEY_FILE_MAX_BYTES_HINT, cfg.maxRawBytesHint.toLong())
            .putInt(GitHubUploadWorker.KEY_FILE_MAX_REQUEST_BYTES_HINT, cfg.maxRequestBytesHint)
            .build()

        val req: OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                .setInputData(input)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(GitHubUploadWorker.TAG)
                .addTag("${GitHubUploadWorker.TAG}:file:${file.name}")
                .build()

        val policy = choosePolicyForUniqueName(context, uniqueName)

        Log.d(TAG, "enqueueGenericGitHubFileUpload: uniqueName=$uniqueName policy=$policy file=${file.absolutePath} bytes=$bytes mtime=$mtime")

        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueName, policy, req)
    }

    /**
     * Choose ExistingWorkPolicy based on current unique work state.
     *
     * Rationale:
     * - KEEP prevents duplicates while a work is in-flight.
     * - REPLACE allows re-enqueue after FAILED/SUCCEEDED/CANCELLED chains,
     *   which is critical when config was fixed later.
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Log.w(TAG, "choosePolicy: fallback KEEP (query failed). uniqueName=$uniqueName err=${exception.message}")
            ExistingWorkPolicy.KEEP
        }
    }

    private fun rescheduleRuntimeLogsUpload(contexts: List<Context>, action: String) {
        val cfg = GitHubUploader.GitHubConfig(
            owner = BuildConfig.GH_OWNER,
            repo = BuildConfig.GH_REPO,
            token = BuildConfig.GH_TOKEN,
            branch = BuildConfig.GH_BRANCH,
            pathPrefix = BuildConfig.GH_PATH_PREFIX
        )

        if (cfg.owner.isBlank() || cfg.repo.isBlank() || cfg.token.isBlank()) {
            Log.d(TAG, "Skip runtime logs upload: missing GitHub credentials.")
            return
        }

        val appCtx = contexts.first().applicationContext ?: contexts.first()

        // Make sure store can start even from restricted contexts (best-effort).
        try {
            RuntimeLogStore.start(appCtx)
        } catch (exception: Exception) {
            Log.w(TAG, "Runtime log store start failed: ${exception.message}", exception)
        }

        val reason = "receiver_" + action.lowercase(Locale.US).substringAfterLast(".").take(24)

        try {
            GitHubUploadWorker.enqueueStartupRuntimeLogsUpload(
                context = appCtx,
                cfg = cfg,
                remoteDir = "diagnostics/runtime_logs",
                addDateSubdir = true,
                reason = reason,
                deleteZipAfter = true
            )
            Log.d(TAG, "Enqueued runtime logs upload for action=$action reason=$reason")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Log.w(TAG, "Runtime logs enqueue failed action=$action: ${exception.message}", exception)
        }
    }

    /**
     * List pending files under /files/{dirName}.
     *
     * @param walk If true, walkTopDown to include nested crash log dirs etc.
     */
    private fun listPendingFiles(context: Context, dirName: String, walk: Boolean): List<File> {
        val dir = File(context.filesDir, dirName)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val files = try {
            if (walk) {
                // Bound scan to avoid worst-case explosion.
                dir.walkTopDown()
                    .onEnter { it.isDirectory }
                    .filter { it.isFile }
                    .take(MAX_SCAN_FILES)
                    .toList()
            } else {
                dir.listFiles()?.asSequence()
                    ?.filter { it.isFile }
                    ?.take(MAX_SCAN_FILES)
                    ?.toList()
                    ?: emptyList()
            }
        } catch (exception: Exception) {
            Log.w(TAG, "listPendingFiles failed dir=${dir.absolutePath}: ${exception.message}", exception)
            emptyList()
        }

        if (files.isNotEmpty()) {
            Log.d(TAG, "Found pending: dir=${dir.absolutePath} files=${files.size}")
        }
        return files
    }

    /**
     * Count pending files quickly without building a list (best-effort).
     */
    private fun countPendingFiles(context: Context, dirName: String, walk: Boolean): Int {
        val dir = File(context.filesDir, dirName)
        if (!dir.exists() || !dir.isDirectory) return 0
        return try {
            if (walk) {
                dir.walkTopDown()
                    .filter { it.isFile }
                    .take(MAX_SCAN_FILES)
                    .count()
            } else {
                dir.listFiles()?.count { it.isFile } ?: 0
            }
        } catch (exception: Exception) {
            Log.w(TAG, "countPendingFiles failed dir=${dir.absolutePath}: ${exception.message}", exception)
            0
        }
    }

    /**
     * Build a stable de-duplication key for a file.
     *
     * Prefer canonicalPath when available; fallback to absolutePath.
     */
    private fun stableKey(file: File): String =
        try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }

    /**
     * Ignore transient/metadata files to avoid enqueuing junk.
     */
    private fun shouldIgnorePendingFile(file: File): Boolean {
        val n = file.name.lowercase(Locale.US)
        return n.endsWith(".tmp") || n.endsWith(".meta") || n.endsWith(".part")
    }

    /**
     * Guess Supabase remoteDir from file name and parent directories.
     *
     * Important: SupabaseUploadWorker builds:
     *   objectPath = dated(prefix + "/" + remoteDir, fileName)
     */
    private fun guessSupabaseRemoteDir(file: File): String {
        val name = file.name.lowercase(Locale.US)
        val path = file.absolutePath.lowercase(Locale.US)

        return when {
            // Crash bundles (various formats/locations)
            path.contains("/crash") || name.startsWith("crash_") -> "crash"

            // Logcat snapshots
            name.startsWith("logcat_") || name.endsWith(".log.gz") || name.endsWith(".gz") ->
                "diagnostics/logcat"

            // Voice WAVs
            name.endsWith(".wav") -> "voice"

            // Default
            else -> "regular"
        }
    }

    /**
     * Guess contentType by extension.
     */
    private fun guessContentType(file: File): String {
        val name = file.name.lowercase(Locale.US)
        return when {
            name.endsWith(".json") -> "application/json; charset=utf-8"
            name.endsWith(".wav") -> "audio/wav"
            name.endsWith(".gz") -> "application/gzip"
            else -> "application/octet-stream"
        }
    }

    private fun isRelevantAction(action: String): Boolean =
        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> true
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            Intent.ACTION_USER_UNLOCKED -> true
            ACTION_LOCKED_BOOT_COMPLETED -> true
            else -> false
        }

    private fun createDeviceProtectedContextOrNull(context: Context): Context? {
        if (Build.VERSION.SDK_INT < 24) return null
        return try {
            context.createDeviceProtectedStorageContext()
        } catch (_: Exception) {
            null
        }
    }

    private fun isUserUnlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 24) return true
        val um = try {
            context.getSystemService(UserManager::class.java)
        } catch (_: Exception) {
            null
        }
        return um?.isUserUnlocked == true
    }

    private companion object {
        private const val TAG = "UploadRescheduleRcvr"

        /** Hard timeout to finish receiver work (avoid ANR). */
        private const val RECEIVER_WORK_TIMEOUT_MS = 9_000L

        /** Upper bound for scanned files to avoid worst-case explosion. */
        private const val MAX_SCAN_FILES = 2_000

        /** Directory under `/files/` containing pending GitHub upload payloads. */
        private const val PENDING_DIR_GH = "pending_uploads"

        /** Directory under `/files/` containing pending Supabase upload payloads. */
        private const val PENDING_DIR_SB_V2 = "pending_uploads_supabase"

        /** Directory under `/files/` containing pending Supabase upload payloads. */
        private const val PENDING_DIR_SB_V1 = "pending_uploads_sb"

        /** Nested pending root used by crash/log stores. */
        private const val PENDING_DIR_SB_NESTED_ROOT = "pending_uploads/supabase"

        /** String constant for locked boot action to avoid API gated references. */
        private const val ACTION_LOCKED_BOOT_COMPLETED =
            "android.intent.action.LOCKED_BOOT_COMPLETED"

        /** Guard against concurrent runs. */
        private val IS_RUNNING = AtomicBoolean(false)
    }
}
