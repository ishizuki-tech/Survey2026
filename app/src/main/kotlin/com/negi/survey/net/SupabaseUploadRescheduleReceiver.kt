/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SupabaseUploadRescheduleReceiver.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Reschedules pending Supabase uploads on:
 *   - BOOT_COMPLETED / LOCKED_BOOT_COMPLETED
 *   - MY_PACKAGE_REPLACED
 *
 *  Robustness upgrades:
 *   - Scan multiple pending roots (legacy + structured).
 *   - Route remoteDir/contentType by relative path first, then filename fallback.
 *   - Pass maxBytesHint ~= file size to avoid mismatched guard for large WAV.
 *   - Run heavy I/O via goAsync() to avoid receiver ANR risks.
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
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max

class SupabaseUploadRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!isRelevantAction(action)) return

        val pending = goAsync()

        thread(name = "SupabaseRescheduleReceiver") {
            try {
                val storageContext = when {
                    action == ACTION_LOCKED_BOOT_COMPLETED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ->
                        context.createDeviceProtectedStorageContext()
                    else -> context
                }

                val prefix = BuildConfig.SUPABASE_LOG_PATH_PREFIX
                    .trim()
                    .trim('/')
                    .ifBlank { DEFAULT_PREFIX }

                val cfg = SupabaseUploader.SupabaseConfig(
                    supabaseUrl = BuildConfig.SUPABASE_URL.trim(),
                    anonKey = BuildConfig.SUPABASE_ANON_KEY.trim(),
                    bucket = BuildConfig.SUPABASE_LOG_BUCKET.trim(),
                    pathPrefix = prefix,
                    maxRawBytesHint = DEFAULT_MAX_BYTES_HINT
                )

                if (cfg.supabaseUrl.isBlank() || cfg.anonKey.isBlank() || cfg.bucket.isBlank()) {
                    Log.d(TAG, "Skip reschedule: missing Supabase credentials.")
                    return@thread
                }

                val roots = pendingRoots(storageContext)
                if (roots.isEmpty()) {
                    Log.d(TAG, "No pending roots found for action=$action")
                    return@thread
                }

                val entries: List<Pair<File, File>> =
                    roots.flatMap { root ->
                        if (!root.exists() || !root.isDirectory) emptyList()
                        else root.walkTopDown()
                            .filter { it.isFile && it.length() > 0L }
                            .map { f -> f to root }
                            .toList()
                    }.distinctBy { it.first.absolutePath }

                if (entries.isEmpty()) {
                    Log.d(TAG, "No pending files for action=$action roots=${roots.joinToString { it.name }}")
                    return@thread
                }

                Log.d(
                    TAG,
                    "Rescheduling ${entries.size} pending upload(s) for action=$action " +
                            "roots=${roots.joinToString { it.absolutePath }}"
                )

                entries
                    .sortedBy { (f, _) -> f.lastModified() }
                    .forEach { (file, root) ->
                        runCatching {
                            val rel = runCatching { file.relativeTo(root).invariantSeparatorsPath }
                                .getOrDefault("")
                                .trim('/')

                            val route = classify(file = file, relativePath = rel)

                            // IMPORTANT: pass a maxBytesHint that can accommodate large WAV.
                            val maxBytesHint = max(DEFAULT_MAX_BYTES_HINT, file.length() + 1024L)

                            Log.i(
                                TAG,
                                "Enqueue pending: name=${file.name} bytes=${file.length()} " +
                                        "rel=$rel remoteDir=${route.remoteDir} contentType=${route.contentType} " +
                                        "root=${root.absolutePath}"
                            )

                            SupabaseUploadWorker.enqueueExistingPayload(
                                context = storageContext,
                                cfg = cfg,
                                file = file,
                                remoteDir = route.remoteDir,
                                contentType = route.contentType,
                                upsert = false,
                                userJwt = null,
                                maxBytesHint = maxBytesHint
                            )
                        }.onFailure { t ->
                            Log.w(TAG, "Failed to enqueue pending file=${file.absolutePath}: ${t.message}", t)
                        }
                    }
            } catch (t: Throwable) {
                Log.e(TAG, "Reschedule failed: ${t.message}", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun isRelevantAction(action: String): Boolean =
        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> true
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            ACTION_LOCKED_BOOT_COMPLETED -> true
            else -> false
        }

    /**
     * Pending roots we support:
     * - Legacy DoneScreen staging: filesDir/pending_uploads_supabase
     * - Structured staging: filesDir/pending_uploads/supabase
     */
    private fun pendingRoots(context: Context): List<File> {
        val roots = ArrayList<File>(2)
        roots.add(File(context.filesDir, LEGACY_PENDING_ROOT))
        roots.add(File(context.filesDir, STRUCTURED_PENDING_ROOT))
        return roots.filter { it.exists() && it.isDirectory }
    }

    /**
     * Classify pending file -> remoteDir + contentType.
     *
     * Primary rule:
     * - Use relativePath from pending root if available:
     *     exports/{surveyUuid}/x.json      -> exports
     *     voice/{surveyUuid}/x.wav        -> voice
     *     diagnostics/logcat/{uuid}/x.gz  -> diagnostics/logcat
     *
     * Fallback rule:
     * - Use filename/extension heuristics.
     */
    private fun classify(file: File, relativePath: String): Route {
        val rel = relativePath.lowercase(Locale.US)

        if (rel.startsWith("voice/")) {
            return Route(remoteDir = "voice", contentType = "audio/wav")
        }
        if (rel.startsWith("exports/")) {
            return Route(remoteDir = "exports", contentType = "application/json; charset=utf-8")
        }
        if (rel.startsWith("diagnostics/logcat/") || rel.contains("/logcat/")) {
            return Route(remoteDir = "diagnostics/logcat", contentType = "application/gzip")
        }
        if (rel.startsWith("crash/") || rel.contains("/crash/")) {
            return Route(remoteDir = "crash", contentType = "application/gzip")
        }

        // Fallback: filename-based
        val name = file.name.lowercase(Locale.US)
        val parent = file.parentFile?.name.orEmpty().lowercase(Locale.US)

        if (name.endsWith(".wav")) {
            return Route(remoteDir = "voice", contentType = "audio/wav")
        }
        if (name.endsWith(".json")) {
            return Route(remoteDir = "exports", contentType = "application/json; charset=utf-8")
        }

        val isGz = name.endsWith(".gz")
        val looksLogcat = name.startsWith("logcat_") || name.endsWith(".log.gz") || parent.contains("logcat")
        if (isGz && looksLogcat) {
            return Route(remoteDir = "diagnostics/logcat", contentType = "application/gzip")
        }

        val looksCrash = name.startsWith("crash_") || parent.contains("crash") || parent.contains("crashlogs")
        if (isGz && looksCrash) {
            return Route(remoteDir = "crash", contentType = "application/gzip")
        }

        if (isGz) {
            return Route(remoteDir = "diagnostics", contentType = "application/gzip")
        }

        return Route(remoteDir = "regular", contentType = "application/octet-stream")
    }

    private data class Route(
        val remoteDir: String,
        val contentType: String
    )

    private companion object {
        private const val TAG = "SupabaseUploadRcvr"

        private const val DEFAULT_PREFIX = "surveyapp"
        private const val DEFAULT_MAX_BYTES_HINT = 20_000_000L

        private const val LEGACY_PENDING_ROOT = "pending_uploads_supabase"
        private const val STRUCTURED_PENDING_ROOT = "pending_uploads/supabase"

        private const val ACTION_LOCKED_BOOT_COMPLETED =
            "android.intent.action.LOCKED_BOOT_COMPLETED"
    }
}
