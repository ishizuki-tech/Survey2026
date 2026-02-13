/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SupabaseDiagnosticsConfigStore.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val TAG = "SupabaseDiagCfgStore"

/**
 * Stores and loads SupabaseUploader.SupabaseConfig for deferred uploads.
 *
 * Notes:
 * - anonKey is "client key" but still sensitive-ish; prefer encrypted prefs when available.
 * - Avoid storing service_role keys in the app (never do that).
 * - This store respects the provided Context storage (device-protected vs credential-protected).
 */
object SupabaseDiagnosticsConfigStore {

    private const val PREF_NAME = "supabase_diag_cfg"

    private const val KEY_URL = "url"
    private const val KEY_ANON = "anonKey"
    private const val KEY_BUCKET = "bucket"
    private const val KEY_PREFIX = "prefix"
    private const val KEY_MAX_RAW = "maxRawBytesHint"

    @Volatile private var cachedPrefs: SharedPreferences? = null
    @Volatile private var cachedIsEncrypted: Boolean = false

    /**
     * Save Supabase diagnostics config.
     */
    fun save(context: Context, cfg: SupabaseUploader.SupabaseConfig) {
        val p = prefs(context)
        p.edit {
            putString(KEY_URL, cfg.supabaseUrl)
            putString(KEY_ANON, cfg.anonKey)
            putString(KEY_BUCKET, cfg.bucket)
            putString(KEY_PREFIX, cfg.pathPrefix)
            putLong(KEY_MAX_RAW, cfg.maxRawBytesHint)
        }

        Log.i(TAG, "Saved Supabase diagnostics config (bucket=${cfg.bucket}, prefix=${cfg.pathPrefix}, encrypted=$cachedIsEncrypted).")
    }

    /**
     * Load Supabase diagnostics config.
     *
     * @return config if all required fields exist; otherwise null.
     */
    fun load(context: Context): SupabaseUploader.SupabaseConfig? {
        val p = prefs(context)

        val url = p.getString(KEY_URL, null).orEmpty().trim()
        val anon = p.getString(KEY_ANON, null).orEmpty().trim()
        val bucket = p.getString(KEY_BUCKET, null).orEmpty().trim()
        val prefix = p.getString(KEY_PREFIX, "surveyapp").orEmpty().trim()
        val maxRaw = p.getLong(KEY_MAX_RAW, 20_000_000L).coerceAtLeast(1L)

        if (url.isBlank() || anon.isBlank() || bucket.isBlank()) return null

        return SupabaseUploader.SupabaseConfig(
            supabaseUrl = url,
            anonKey = anon,
            bucket = bucket,
            pathPrefix = prefix.ifBlank { "surveyapp" },
            maxRawBytesHint = maxRaw
        )
    }

    /**
     * Clear stored config (debug utility).
     */
    fun clear(context: Context) {
        val p = prefs(context)
        p.edit {
            remove(KEY_URL)
            remove(KEY_ANON)
            remove(KEY_BUCKET)
            remove(KEY_PREFIX)
            remove(KEY_MAX_RAW)
        }
        Log.i(TAG, "Cleared Supabase diagnostics config (encrypted=$cachedIsEncrypted).")
    }

    private fun prefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }

        synchronized(this) {
            cachedPrefs?.let { return it }

            // IMPORTANT: respect the passed context storage.
            // If caller provides a device-protected context, do NOT replace it with applicationContext.
            val baseContext = context

            val created = runCatching {
                val masterKey = MasterKey.Builder(baseContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    baseContext,
                    PREF_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also {
                    cachedIsEncrypted = true
                }
            }.getOrElse { e ->
                // Encrypted prefs may fail on some devices/ROMs/boot states.
                // Fall back to plain prefs for robustness.
                Log.w(TAG, "Encrypted prefs unavailable; falling back to plain prefs: ${e.message}")
                baseContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).also {
                    cachedIsEncrypted = false
                }
            }

            cachedPrefs = created
            return created
        }
    }
}
