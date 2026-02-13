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

object VoiceUploadCompletionStore {

    private const val TAG = "VoiceUploadComplete"
    private const val PREF_NAME = "voice_upload_completion_v2"

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
     * If file is re-created under same name, reset flags safely.
     */
    fun ensureCurrent(context: Context, file: File) {
        if (!file.exists() || !file.isFile) return

        val prefs = prefs(context)
        val safe = safeKey(file.name)

        val curSize = file.length().coerceAtLeast(0L)
        val curMtime = file.lastModified().coerceAtLeast(0L)

        val storedSize = prefs.getLong(k(safe, "size"), -1L)
        val storedMtime = prefs.getLong(k(safe, "mtime"), -1L)

        if (storedSize == curSize && storedMtime == curMtime) return

        // Reset completion flags, but keep required flags as-is (they’ll be re-asserted by caller).
        prefs.edit {
            putLong(k(safe, "size"), curSize)
            putLong(k(safe, "mtime"), curMtime)
            putBoolean(k(safe, "ghDone"), false)
            putBoolean(k(safe, "sbDone"), false)
            putLong(k(safe, "updatedAt"), System.currentTimeMillis())
        }

        Log.d(TAG, "ensureCurrent: reset done flags name=${file.name} size=$curSize mtime=$curMtime")
    }

    /**
     * OR-in required destinations for this file.
     *
     * Use this before upload attempts so deletion logic is safe even if one backend runs first.
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
