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
 *  BroadcastReceiver that automatically re-enqueues any pending GitHub
 *  uploads after system reboot or app update. Ensures reliability of
 *  background data delivery even after lifecycle disruptions.
 *
 *  Triggered by:
 *   • BOOT_COMPLETED — when the device finishes booting
 *   • LOCKED_BOOT_COMPLETED — for direct-boot aware apps (API 24+)
 *   • MY_PACKAGE_REPLACED — after app reinstall or update
 *
 *  For each payload file in `/files/pending_uploads/`, the receiver enqueues
 *  a [GitHubUploadWorker] to handle upload with WorkManager.
 *
 *  Notes:
 *   • Worker deduplication is handled via `enqueueUniqueWork(..., KEEP)`.
 *   • For security, prefer android:exported="false" unless you have a
 *     specific reason to expose this receiver.
 *   • If you truly need Direct Boot rescheduling, consider storing pending
 *     payloads under device-protected storage as well.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.negi.survey.BuildConfig
import java.io.File

/**
 * Receives system-level broadcasts related to app restarts or device reboots,
 * and automatically reschedules all pending upload tasks.
 *
 * Responsibilities:
 * - Detect BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, and MY_PACKAGE_REPLACED.
 * - Load persistent payloads from app-internal storage.
 * - Re-enqueue each pending upload through [com.negi.survey.net.GitHubUploadWorker].
 *
 * Behavior:
 * - Gracefully no-ops on invalid credentials.
 * - Handles files independently to avoid single-point failures.
 * - Safe under Direct Boot broadcast timing.
 *
 * @see com.negi.survey.net.GitHubUploadWorker
 */
class UploadRescheduleReceiver : BroadcastReceiver() {

    /**
     * Called when the system sends a matching broadcast.
     *
     * @param context Application context supplied by the system.
     * @param intent Intent describing the received system broadcast.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!isRelevantAction(action)) return

        // Choose storage context based on Direct Boot state.
        // Pending files are currently written under credential-protected storage
        // by GitHubUploadWorker.enqueue(...). Using device-protected context here
        // prevents crashes during LOCKED_BOOT_COMPLETED, even though it may not
        // find the files unless you also store them there.
        val storageContext = when {
            action == ACTION_LOCKED_BOOT_COMPLETED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ->
                context.createDeviceProtectedStorageContext()
            else -> context
        }

        val cfg = GitHubUploader.GitHubConfig(
            owner = BuildConfig.GH_OWNER,
            repo = BuildConfig.GH_REPO,
            token = BuildConfig.GH_TOKEN,
            branch = BuildConfig.GH_BRANCH,
            pathPrefix = BuildConfig.GH_PATH_PREFIX
        )

        // Skip if credentials are invalid.
        if (cfg.owner.isBlank() || cfg.repo.isBlank() || cfg.token.isBlank()) {
            Log.d(TAG, "Skip reschedule: missing GitHub credentials.")
            return
        }

        val dir = File(storageContext.filesDir, PENDING_DIR)

        if (!dir.exists() || !dir.isDirectory) {
            Log.d(TAG, "No pending dir found for action=$action path=${dir.absolutePath}")
            return
        }

        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) {
            Log.d(TAG, "No pending files for action=$action")
            return
        }

        Log.d(TAG, "Rescheduling ${files.size} pending uploads for action=$action")

        files.forEach { file ->
            runCatching {
                GitHubUploadWorker.enqueueExistingPayload(context, cfg, file)
            }.onFailure { t ->
                Log.w(TAG, "Failed to enqueue pending file=${file.name}: ${t.message}")
            }
        }
    }

    /**
     * Returns true if the action is one of the supported reschedule triggers.
     */
    private fun isRelevantAction(action: String): Boolean =
        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> true
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            ACTION_LOCKED_BOOT_COMPLETED -> true
            else -> false
        }

    private companion object {
        private const val TAG = "UploadRescheduleRcvr"

        /** Directory under `/files/` containing pending upload payloads. */
        private const val PENDING_DIR = "pending_uploads"

        /** String constant for locked boot action to avoid API gated references. */
        private const val ACTION_LOCKED_BOOT_COMPLETED =
            "android.intent.action.LOCKED_BOOT_COMPLETED"
    }
}
