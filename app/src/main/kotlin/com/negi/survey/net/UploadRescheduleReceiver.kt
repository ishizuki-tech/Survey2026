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
 *  after system reboot or app update.
 *
 *  - GitHub pending dir: /files/pending_uploads/
 *  - Supabase pending dirs (historical):
 *      /files/pending_uploads_supabase/
 *      /files/pending_uploads_sb/
 *      /files/pending_uploads/supabase/...
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.negi.survey.BuildConfig
import java.io.File
import java.util.Locale

class UploadRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!isRelevantAction(action)) return

        // For LOCKED_BOOT_COMPLETED, device-protected storage is available.
        // But pending files may live in credential-protected storage (normal context).
        // We scan both contexts and de-duplicate by absolute path.
        val ctxNormal = context
        val ctxDeviceProtected = runCatching { context.createDeviceProtectedStorageContext() }.getOrNull()

        val contexts = buildList {
            add(ctxNormal)
            if (ctxDeviceProtected != null) add(ctxDeviceProtected)
        }

        rescheduleGitHub(contexts, action)
        rescheduleSupabase(contexts, action)
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
            .distinctBy { it.absolutePath }
            .filter { it.isFile && it.length() > 0L }

        if (allFiles.isEmpty()) {
            Log.d(TAG, "No GitHub pending files for action=$action")
            return
        }

        Log.d(TAG, "Rescheduling ${allFiles.size} GitHub pending uploads for action=$action")

        allFiles.forEach { file ->
            runCatching {
                GitHubUploadWorker.enqueueExistingPayload(contexts.first().applicationContext, cfg, file)
            }.onFailure { t ->
                Log.w(TAG, "GitHub enqueue failed file=${file.name}: ${t.message}")
            }
        }
    }

    private fun rescheduleSupabase(contexts: List<Context>, action: String) {
        val sbUrl = BuildConfig.SUPABASE_URL.trim()
        val sbAnon = BuildConfig.SUPABASE_ANON_KEY.trim()
        val sbBucket = BuildConfig.SUPABASE_LOG_BUCKET.trim()
        val sbPrefix = BuildConfig.SUPABASE_LOG_PATH_PREFIX.trim()

        val cfg = SupabaseUploader.SupabaseConfig(
            supabaseUrl = sbUrl,
            anonKey = sbAnon,
            bucket = sbBucket,
            pathPrefix = sbPrefix.ifBlank { "surveyapp" },
            maxRawBytesHint = 20_000_000L
        )

        if (cfg.supabaseUrl.isBlank() || cfg.anonKey.isBlank() || cfg.bucket.isBlank()) {
            Log.d(TAG, "Skip Supabase reschedule: missing configuration.")
            return
        }

        // Scan multiple historical pending roots.
        val sbRoots = listOf(
            PENDING_DIR_SB_V2,          // "pending_uploads_supabase"
            PENDING_DIR_SB_V1,          // "pending_uploads_sb"
            PENDING_DIR_SB_NESTED_ROOT  // "pending_uploads/supabase"
        )

        val allFiles = sbRoots
            .flatMap { dirName ->
                contexts.flatMap { ctx -> listPendingFiles(ctx, dirName, walk = true) }
            }
            .distinctBy { it.absolutePath }
            .filter { it.isFile && it.length() > 0L }
            .filterNot { it.name.endsWith(".tmp", ignoreCase = true) }

        if (allFiles.isEmpty()) {
            Log.d(TAG, "No Supabase pending files for action=$action")
            return
        }

        Log.d(TAG, "Rescheduling ${allFiles.size} Supabase pending uploads for action=$action")

        allFiles.forEach { file ->
            val remoteDir = guessSupabaseRemoteDir(file)
            val contentType = guessContentType(file)

            runCatching {
                SupabaseUploadWorker.enqueueExistingPayload(
                    context = contexts.first().applicationContext,
                    cfg = cfg,
                    file = file,
                    remoteDir = remoteDir,
                    contentType = contentType,
                    upsert = false,
                    userJwt = null,
                    maxBytesHint = cfg.maxRawBytesHint
                )
            }.onFailure { t ->
                Log.w(TAG, "Supabase enqueue failed file=${file.name}: ${t.message}")
            }
        }
    }

    /**
     * List pending files under /files/{dirName}.
     *
     * @param walk If true, walkTopDown to include nested crash log dirs etc.
     */
    private fun listPendingFiles(context: Context, dirName: String, walk: Boolean): List<File> {
        val dir = File(context.filesDir, dirName)
        if (!dir.exists() || !dir.isDirectory) {
            return emptyList()
        }

        val files = if (walk) {
            dir.walkTopDown().filter { it.isFile }.toList()
        } else {
            dir.listFiles()?.filter { it.isFile } ?: emptyList()
        }

        if (files.isNotEmpty()) {
            Log.d(TAG, "Found pending: dir=${dir.absolutePath} files=${files.size}")
        }
        return files
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
            ACTION_LOCKED_BOOT_COMPLETED -> true
            else -> false
        }

    private companion object {
        private const val TAG = "UploadRescheduleRcvr"

        /** Directory under `/files/` containing pending GitHub upload payloads. */
        private const val PENDING_DIR_GH = "pending_uploads"

        /**
         * Directory under `/files/` containing pending Supabase upload payloads.
         * (Newer DoneScreen uses this.)
         */
        private const val PENDING_DIR_SB_V2 = "pending_uploads_supabase"

        /**
         * Directory under `/files/` containing pending Supabase upload payloads.
         * (Older variant.)
         */
        private const val PENDING_DIR_SB_V1 = "pending_uploads_sb"

        /**
         * Nested pending root used by crash/log stores:
         * e.g. /files/pending_uploads/supabase/crashlogs/...
         */
        private const val PENDING_DIR_SB_NESTED_ROOT = "pending_uploads/supabase"

        /** String constant for locked boot action to avoid API gated references. */
        private const val ACTION_LOCKED_BOOT_COMPLETED =
            "android.intent.action.LOCKED_BOOT_COMPLETED"
    }
}
