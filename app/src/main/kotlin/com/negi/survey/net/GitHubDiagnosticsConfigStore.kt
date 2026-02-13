/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: GitHubDiagnosticsConfigStore.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Stores and loads GitHubUploader.GitHubConfig for deferred uploads.
 *  Uses EncryptedSharedPreferences when available; falls back to plain prefs.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val TAG = "GitHubDiagCfgStore"

object GitHubDiagnosticsConfigStore {

    private const val PREF_NAME_ENCRYPTED = "github_diag_cfg"
    private const val PREF_NAME_PLAIN = "github_diag_cfg_plain"

    private const val KEY_OWNER = "owner"
    private const val KEY_REPO = "repo"
    private const val KEY_TOKEN = "token"
    private const val KEY_BRANCH = "branch"
    private const val KEY_PREFIX = "prefix"

    @Volatile
    private var cachedEncryptedPrefs: SharedPreferences? = null

    @Volatile
    private var cachedPlainPrefs: SharedPreferences? = null

    /**
     * Persist GitHub config used for deferred crash/log uploads.
     *
     * WARNING:
     * - Token is sensitive. Prefer EncryptedSharedPreferences.
     * - Use a fine-grained token restricted to one repo/path whenever possible.
     */
    fun save(context: Context, cfg: GitHubUploader.GitHubConfig) {
        val appContext = context.applicationContext

        // Try encrypted first, then fallback to plain prefs if needed.
        val saved = runCatching {
            val prefs = encryptedPrefsOrNull(appContext) ?: throw IllegalStateException("Encrypted prefs unavailable")
            writeToPrefs(prefs, cfg)
            true
        }.getOrElse { e ->
            Log.w(TAG, "Encrypted save failed; falling back to plain prefs: ${e.message}")
            false
        }

        if (saved) {
            Log.i(TAG, "Saved GitHub diagnostics config (encrypted) (owner=${cfg.owner}, repo=${cfg.repo}).")
            return
        }

        runCatching {
            val prefs = plainPrefs(appContext)
            writeToPrefs(prefs, cfg)
            Log.i(TAG, "Saved GitHub diagnostics config (plain) (owner=${cfg.owner}, repo=${cfg.repo}).")
        }.onFailure { e ->
            Log.w(TAG, "Plain save failed: ${e.message}", e)
        }
    }

    /**
     * Load config or null if missing/invalid.
     *
     * Safety:
     * - Never throws. If encrypted prefs are corrupted/unreadable, it falls back to plain prefs.
     */
    fun load(context: Context): GitHubUploader.GitHubConfig? {
        val appContext = context.applicationContext

        // 1) Encrypted prefs first (preferred).
        val enc = runCatching {
            val prefs = encryptedPrefsOrNull(appContext) ?: return@runCatching null
            readFromPrefs(prefs)
        }.getOrElse { e ->
            Log.w(TAG, "Encrypted load failed; will try plain prefs: ${e.message}")
            null
        }

        if (enc != null) return enc

        // 2) Plain fallback (recovery path).
        val plain = runCatching {
            val prefs = plainPrefs(appContext)
            readFromPrefs(prefs)
        }.getOrElse { e ->
            Log.w(TAG, "Plain load failed: ${e.message}", e)
            null
        }

        return plain
    }

    /**
     * Read config from prefs. Returns null if missing/invalid.
     */
    private fun readFromPrefs(prefs: SharedPreferences): GitHubUploader.GitHubConfig? {
        val owner = prefs.getString(KEY_OWNER, null).orEmpty()
        val repo = prefs.getString(KEY_REPO, null).orEmpty()
        val token = prefs.getString(KEY_TOKEN, null).orEmpty()
        val branch = prefs.getString(KEY_BRANCH, "main").orEmpty()
        val prefix = prefs.getString(KEY_PREFIX, "").orEmpty()

        if (owner.isBlank() || repo.isBlank() || token.isBlank()) return null

        return GitHubUploader.GitHubConfig(
            owner = owner,
            repo = repo,
            token = token,
            branch = branch.ifBlank { "main" },
            pathPrefix = prefix
        )
    }

    /**
     * Write config to prefs.
     */
    private fun writeToPrefs(prefs: SharedPreferences, cfg: GitHubUploader.GitHubConfig) {
        prefs.edit()
            .putString(KEY_OWNER, cfg.owner)
            .putString(KEY_REPO, cfg.repo)
            .putString(KEY_TOKEN, cfg.token)
            .putString(KEY_BRANCH, cfg.branch)
            .putString(KEY_PREFIX, cfg.pathPrefix)
            .apply()
    }

    /**
     * Build encrypted preferences if available. Returns null on any failure.
     *
     * NOTE:
     * - We cache the SharedPreferences instance to avoid repeated MasterKey creation.
     * - We use applicationContext to avoid leaking short-lived contexts.
     */
    private fun encryptedPrefsOrNull(context: Context): SharedPreferences? {
        cachedEncryptedPrefs?.let { return it }

        synchronized(this) {
            cachedEncryptedPrefs?.let { return it }

            val created = runCatching {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    PREF_NAME_ENCRYPTED,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrElse { e ->
                Log.w(TAG, "Encrypted prefs unavailable: ${e.message}")
                null
            }

            cachedEncryptedPrefs = created
            return created
        }
    }

    /**
     * Build plain preferences (separate file name to avoid format conflicts).
     */
    private fun plainPrefs(context: Context): SharedPreferences {
        cachedPlainPrefs?.let { return it }

        synchronized(this) {
            cachedPlainPrefs?.let { return it }
            val created = context.getSharedPreferences(PREF_NAME_PLAIN, Context.MODE_PRIVATE)
            cachedPlainPrefs = created
            return created
        }
    }
}
