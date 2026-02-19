/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: VoiceUploadCompletionStore.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Persists per-voice-file upload completion flags for multiple backends.
 *
 *  Key rule:
 *   - Do NOT delete local WAV until all REQUIRED destinations succeed.
 *
 *  Required destinations are tracked per file:
 *   - requireGitHub
 *   - requireSupabase
 *
 *  This prevents "Supabase deletes first, GitHub can't upload" in both
 *  immediate and WorkManager flows.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.util.Locale
import kotlin.math.abs

object VoiceUploadCompletionStore {

    private const val TAG = "VoiceUploadComplete"
    private const val PREF_NAME = "voice_upload_completion_v2"

    /**
     * Reset protection: do not treat tiny mtime drift as "file replaced".
     *
     * Some devices/filesystems may adjust mtime slightly around close/rename.
     * We reset only when:
     *  - size changed, OR
     *  - mtime changed by more than this threshold
     */
    private const val MTIME_RESET_THRESHOLD_MS = 5_000L

    /**
     * Optional hygiene: cap per-scan work when purging.
     */
    private const val PURGE_MAX_ENTRIES = 5_000

    data class State(
        val fileName: String,
        val size: Long,
        val lastModified: Long,
        val requireGitHub: Boolean,
        val requireSupabase: Boolean,
        val githubUploaded: Boolean,
        val supabaseUploaded: Boolean,
        val updatedAtEpochMs: Long
    ) {
        /** True when all required destinations are satisfied. */
        val isComplete: Boolean get() =
            (!requireGitHub || githubUploaded) &&
                    (!requireSupabase || supabaseUploaded) &&
                    (requireGitHub || requireSupabase)
    }

    /**
     * Ensure the stored identity matches current file size/mtime.
     *
     * If the file is re-created under the same name, reset done flags safely.
     * Required flags are NOT cleared here (safety-first); callers can re-assert
     * requirements via [requireDestinations] or [setRequiredDestinationsExact].
     */
    fun ensureCurrent(context: Context, file: File) {
        if (!file.exists() || !file.isFile) return

        val prefs = prefs(context)
        val safe = safeKey(file.name)

        val curSize = file.length().coerceAtLeast(0L)
        val curMtime = file.lastModified().coerceAtLeast(0L)

        val storedSize = prefs.getLong(k(safe, "size"), -1L)
        val storedMtime = prefs.getLong(k(safe, "mtime"), -1L)

        // First-time init: record identity, ensure done flags are false.
        if (storedSize < 0L || storedMtime < 0L) {
            prefs.edit {
                putLong(k(safe, "size"), curSize)
                putLong(k(safe, "mtime"), curMtime)
                putBoolean(k(safe, "ghDone"), false)
                putBoolean(k(safe, "sbDone"), false)
                putLong(k(safe, "updatedAt"), System.currentTimeMillis())
            }
            Log.d(TAG, "ensureCurrent: init name=${file.name} size=$curSize mtime=$curMtime")
            return
        }

        val mtimeDrift = abs(curMtime - storedMtime)
        val shouldReset = (storedSize != curSize) || (mtimeDrift > MTIME_RESET_THRESHOLD_MS)

        if (!shouldReset) return

        // Reset completion flags, but keep required flags as-is (safety).
        prefs.edit {
            putLong(k(safe, "size"), curSize)
            putLong(k(safe, "mtime"), curMtime)
            putBoolean(k(safe, "ghDone"), false)
            putBoolean(k(safe, "sbDone"), false)
            putLong(k(safe, "updatedAt"), System.currentTimeMillis())
        }

        Log.d(
            TAG,
            "ensureCurrent: reset done flags name=${file.name} size=$storedSize->$curSize " +
                    "mtime=$storedMtime->$curMtime drift=$mtimeDrift"
        )
    }

    /**
     * OR-in required destinations for this file.
     *
     * Use this before upload attempts so deletion logic is safe even if one backend runs first.
     *
     * Safety behavior:
     * - Requirements only ever expand (OR). They do not shrink.
     */
    fun requireDestinations(
        context: Context,
        file: File,
        requireGitHub: Boolean,
        requireSupabase: Boolean
    ): State {
        ensureCurrent(context, file)

        val prefs = prefs(context)
        val safe = safeKey(file.name)

        val prevGhReq = prefs.getBoolean(k(safe, "ghReq"), false)
        val prevSbReq = prefs.getBoolean(k(safe, "sbReq"), false)

        val nextGhReq = prevGhReq || requireGitHub
        val nextSbReq = prevSbReq || requireSupabase

        prefs.edit {
            putBoolean(k(safe, "ghReq"), nextGhReq)
            putBoolean(k(safe, "sbReq"), nextSbReq)
            putLong(k(safe, "updatedAt"), System.currentTimeMillis())
        }

        return getState(context, file)
    }

    /**
     * Set required destinations EXACTLY (overwrites previous requirements).
     *
     * This is useful when app settings change and you need requirements to shrink safely.
     * Do not call this from independent backend workers unless you fully control ordering.
     */
    fun setRequiredDestinationsExact(
        context: Context,
        file: File,
        requireGitHub: Boolean,
        requireSupabase: Boolean
    ): State {
        ensureCurrent(context, file)

        val prefs = prefs(context)
        val safe = safeKey(file.name)

        prefs.edit {
            putBoolean(k(safe, "ghReq"), requireGitHub)
            putBoolean(k(safe, "sbReq"), requireSupabase)
            putLong(k(safe, "updatedAt"), System.currentTimeMillis())
        }

        return getState(context, file)
    }

    fun markGitHubUploaded(context: Context, file: File): State {
        ensureCurrent(context, file)
        val prefs = prefs(context)
        val safe = safeKey(file.name)

        prefs.edit {
            putBoolean(k(safe, "ghDone"), true)
            putLong(k(safe, "updatedAt"), System.currentTimeMillis())
        }
        return getState(context, file)
    }

    fun markSupabaseUploaded(context: Context, file: File): State {
        ensureCurrent(context, file)
        val prefs = prefs(context)
        val safe = safeKey(file.name)

        prefs.edit {
            putBoolean(k(safe, "sbDone"), true)
            putLong(k(safe, "updatedAt"), System.currentTimeMillis())
        }
        return getState(context, file)
    }

    fun getState(context: Context, file: File): State {
        val prefs = prefs(context)
        val safe = safeKey(file.name)

        val size = prefs.getLong(k(safe, "size"), file.length().coerceAtLeast(0L))
        val mtime = prefs.getLong(k(safe, "mtime"), file.lastModified().coerceAtLeast(0L))

        val ghReq = prefs.getBoolean(k(safe, "ghReq"), false)
        val sbReq = prefs.getBoolean(k(safe, "sbReq"), false)

        val ghDone = prefs.getBoolean(k(safe, "ghDone"), false)
        val sbDone = prefs.getBoolean(k(safe, "sbDone"), false)

        val updatedAt = prefs.getLong(k(safe, "updatedAt"), 0L)

        return State(
            fileName = file.name,
            size = size,
            lastModified = mtime,
            requireGitHub = ghReq,
            requireSupabase = sbReq,
            githubUploaded = ghDone,
            supabaseUploaded = sbDone,
            updatedAtEpochMs = updatedAt
        )
    }

    /**
     * True if the file should be deleted now (all required destinations satisfied).
     */
    fun shouldDeleteNow(context: Context, file: File): Boolean {
        ensureCurrent(context, file)
        return getState(context, file).isComplete
    }

    /**
     * Clear stored flags for this file after deletion.
     */
    fun clear(context: Context, file: File) {
        val prefs = prefs(context)
        val safe = safeKey(file.name)
        prefs.edit {
            remove(k(safe, "size"))
            remove(k(safe, "mtime"))
            remove(k(safe, "ghReq"))
            remove(k(safe, "sbReq"))
            remove(k(safe, "ghDone"))
            remove(k(safe, "sbDone"))
            remove(k(safe, "updatedAt"))
        }
        Log.d(TAG, "clear: removed entry name=${file.name}")
    }

    /**
     * Purge stale entries to prevent SharedPreferences bloat.
     *
     * This scans keys with prefix "voice:" and removes entries whose updatedAt is older than [olderThanMs].
     * Best-effort and bounded; safe to call at app startup or periodically.
     */
    fun purgeStaleEntries(
        context: Context,
        olderThanMs: Long,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Int {
        val prefs = prefs(context)
        val all = runCatching { prefs.all }.getOrNull() ?: return 0

        val updatedAtKeys = all.keys
            .asSequence()
            .filter { it.startsWith("voice:") && it.endsWith(":updatedAt") }
            .take(PURGE_MAX_ENTRIES)
            .toList()

        var removed = 0

        prefs.edit {
            updatedAtKeys.forEach { key ->
                val v = (all[key] as? Long) ?: return@forEach
                if (nowEpochMs - v < olderThanMs) return@forEach

                // key format: voice:<safeName>:updatedAt
                val parts = key.split(":")
                if (parts.size < 3) return@forEach
                val safeName = parts[1]

                remove(k(safeName, "size"))
                remove(k(safeName, "mtime"))
                remove(k(safeName, "ghReq"))
                remove(k(safeName, "sbReq"))
                remove(k(safeName, "ghDone"))
                remove(k(safeName, "sbDone"))
                remove(k(safeName, "updatedAt"))

                removed++
            }
        }

        if (removed > 0) {
            Log.d(TAG, "purgeStaleEntries: removed=$removed olderThanMs=$olderThanMs")
        }
        return removed
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun k(safeName: String, suffix: String): String = "voice:$safeName:$suffix"

    private fun safeKey(fileName: String): String {
        val s = fileName.trim()
        if (s.isBlank()) return "unknown"
        return s.lowercase(Locale.US)
            .replace(Regex("""[^\w\-.]+"""), "_")
            .take(180)
    }
}
