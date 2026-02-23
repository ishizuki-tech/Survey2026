/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: UploadRescheduleReceiver.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
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
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.negi.survey.BuildConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
            } catch (t: Throwable) {
                Log.w(TAG, "Reschedule failed action=$action: ${t.message}", t)
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

        val allFiles = contexts
            .flatMap { ctx -> listPendingFiles(ctx, PENDING_DIR_GH, walk = false) }
            .distinctBy { stableKey(it) }
            .asSequence()
            .filter { it.isFile && it.length() > 0L }
            .filterNot { shouldIgnorePendingFile(it) }
            .take(MAX_SCAN_FILES)
            .toList()

        if (allFiles.isEmpty()) {
            Log.d(TAG, "No GitHub pending files for action=$action")
            return
        }

        Log.d(TAG, "Rescheduling ${allFiles.size} GitHub pending uploads for action=$action")

        val appCtx = contexts.first().applicationContext ?: contexts.first()

        allFiles.forEach { file ->
            runCatching {
                enqueueGitHubFileUpload(appCtx, cfg, file)
            }.onFailure { t ->
                Log.w(TAG, "GitHub enqueue failed file=${file.name}: ${t.message}", t)
            }
        }
    }

    /**
     * Enqueue a GitHubUploadWorker in MODE=file without relying on a companion convenience API.
     *
     * Rationale:
     * - GitHubUploadWorker.enqueueExistingPayload(...) may not exist depending on branch/version.
     * - This receiver must compile against the currently integrated Worker API.
     */
    private fun enqueueGitHubFileUpload(
        context: Context,
        cfg: GitHubUploader.GitHubConfig,
        file: File
    ) {
        val name = file.name
        val bytes = file.length().coerceAtLeast(0L)
        val mtime = file.lastModified()

        // Include size+mtime to avoid suppressing uploads for "same name but different content".
        val uniqueName = "upload_gh_${name}_${bytes}_${mtime}"

        val req: OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                .setInputData(
                    workDataOf(
                        GitHubUploadWorker.KEY_MODE to "file",
                        GitHubUploadWorker.KEY_OWNER to cfg.owner,
                        GitHubUploadWorker.KEY_REPO to cfg.repo,
                        GitHubUploadWorker.KEY_TOKEN to cfg.token,
                        GitHubUploadWorker.KEY_BRANCH to cfg.branch,
                        GitHubUploadWorker.KEY_PATH_PREFIX to cfg.pathPrefix,
                        GitHubUploadWorker.KEY_FILE_PATH to file.absolutePath,
                        GitHubUploadWorker.KEY_FILE_NAME to name,
                        GitHubUploadWorker.KEY_FILE_MAX_BYTES_HINT to cfg.maxRawBytesHint.toLong(),
                        GitHubUploadWorker.KEY_FILE_MAX_REQUEST_BYTES_HINT to cfg.maxRequestBytesHint
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(GitHubUploadWorker.TAG)
                .addTag("${GitHubUploadWorker.TAG}:file:$name")
                .build()

        val policy = choosePolicyForUniqueName(context, uniqueName)

        Log.d(TAG, "enqueueGitHubFileUpload: uniqueName=$uniqueName policy=$policy file=${file.absolutePath} bytes=$bytes mtime=$mtime")

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
        } catch (t: Throwable) {
            Log.w(TAG, "choosePolicy: fallback KEEP (query failed). uniqueName=$uniqueName err=${t.message}")
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
        runCatching { RuntimeLogStore.start(appCtx) }

        val reason = "receiver_" + action.lowercase(Locale.US).substringAfterLast(".").take(24)

        runCatching {
            GitHubUploadWorker.enqueueStartupRuntimeLogsUpload(
                context = appCtx,
                cfg = cfg,
                remoteDir = "diagnostics/runtime_logs",
                addDateSubdir = true,
                reason = reason,
                deleteZipAfter = true
            )
            Log.d(TAG, "Enqueued runtime logs upload for action=$action reason=$reason")
        }.onFailure { t ->
            Log.w(TAG, "Runtime logs enqueue failed action=$action: ${t.message}", t)
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
        } catch (t: Throwable) {
            Log.w(TAG, "listPendingFiles failed dir=${dir.absolutePath}: ${t.message}", t)
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
        return runCatching {
            if (walk) {
                dir.walkTopDown()
                    .filter { it.isFile }
                    .take(MAX_SCAN_FILES)
                    .count()
            } else {
                dir.listFiles()?.count { it.isFile } ?: 0
            }
        }.getOrDefault(0)
    }

    /**
     * Build a stable de-duplication key for a file.
     *
     * Prefer canonicalPath when available; fallback to absolutePath.
     */
    private fun stableKey(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

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
        return runCatching { context.createDeviceProtectedStorageContext() }.getOrNull()
    }

    private fun isUserUnlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 24) return true
        val um = runCatching { context.getSystemService(UserManager::class.java) }.getOrNull()
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