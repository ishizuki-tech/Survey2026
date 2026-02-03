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
 *  Token is encrypted with Android Keystore (AES/GCM) and stored in prefs.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

private const val TAG = "GitHubDiagCfgStore"

object GitHubDiagnosticsConfigStore {

    private const val PREF_NAME = "github_diag_cfg"

    private const val KEY_OWNER = "owner"
    private const val KEY_REPO = "repo"
    private const val KEY_BRANCH = "branch"
    private const val KEY_PREFIX = "prefix"

    // Encrypted token blob: "v1:<b64(iv)>:<b64(ciphertext)>"
    private const val KEY_TOKEN_BLOB = "token_blob"

    private const val KEYSTORE_ALIAS = "github_diag_cfg_aes_key"
    private const val TOKEN_BLOB_VERSION = "v1"

    /**
     * Persist GitHub config used for deferred crash/log uploads.
     *
     * Notes:
     * - Token is encrypted using Android Keystore (AES/GCM).
     * - Prefer fine-grained tokens scoped to a single repo/path.
     */
    fun save(context: Context, cfg: GitHubUploader.GitHubConfig) {
        val owner = cfg.owner.trim()
        val repo = cfg.repo.trim()
        val branch = cfg.branch.trim().ifBlank { "main" }
        val prefix = normalizePrefix(cfg.pathPrefix)
        val token = cfg.token.trim()

        if (owner.isBlank() || repo.isBlank() || token.isBlank()) {
            Log.w(TAG, "Refusing to save invalid GitHub config (missing owner/repo/token).")
            return
        }

        val tokenBlob = runCatching { encryptToken(token) }
            .getOrElse { e ->
                Log.w(TAG, "Failed to encrypt token; refusing to save token.", e)
                ""
            }

        val prefs = prefs(context)
        prefs.edit()
            .putString(KEY_OWNER, owner)
            .putString(KEY_REPO, repo)
            .putString(KEY_BRANCH, branch)
            .putString(KEY_PREFIX, prefix)
            .apply()

        if (tokenBlob.isNotEmpty()) {
            prefs.edit().putString(KEY_TOKEN_BLOB, tokenBlob).apply()
        } else {
            prefs.edit().remove(KEY_TOKEN_BLOB).apply()
        }

        Log.i(TAG, "Saved GitHub diagnostics config (owner=$owner, repo=$repo, branch=$branch).")
    }

    /**
     * Load config or null if missing/invalid.
     *
     * Behavior:
     * - If token decryption fails, clears token blob and returns null.
     */
    fun load(context: Context): GitHubUploader.GitHubConfig? {
        val prefs = prefs(context)

        val owner = prefs.getString(KEY_OWNER, null)?.trim().orEmpty()
        val repo = prefs.getString(KEY_REPO, null)?.trim().orEmpty()
        val branch = prefs.getString(KEY_BRANCH, "main")?.trim().orEmpty().ifBlank { "main" }
        val prefix = normalizePrefix(prefs.getString(KEY_PREFIX, "") ?: "")

        if (owner.isBlank() || repo.isBlank()) return null

        val tokenBlob = prefs.getString(KEY_TOKEN_BLOB, null)?.trim().orEmpty()
        if (tokenBlob.isBlank()) return null

        val token = runCatching { decryptToken(tokenBlob) }
            .getOrElse { e ->
                Log.w(TAG, "Failed to decrypt token; clearing stored token blob.", e)
                prefs.edit().remove(KEY_TOKEN_BLOB).apply()
                ""
            }

        if (token.isBlank()) return null

        return GitHubUploader.GitHubConfig(
            owner = owner,
            repo = repo,
            token = token,
            branch = branch,
            pathPrefix = prefix
        )
    }

    /**
     * Clear stored config (useful when the token is rotated/revoked).
     */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        Log.i(TAG, "Cleared GitHub diagnostics config.")
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun prefs(context: Context): SharedPreferences {
        val appCtx = context.applicationContext
        return appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Encrypt token using an AES key stored in Android Keystore (AES/GCM/NoPadding).
     */
    private fun encryptToken(token: String): String {
        val key = getOrCreateAesKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val ct = cipher.doFinal(token.toByteArray(Charsets.UTF_8))

        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(ct, Base64.NO_WRAP)

        return "$TOKEN_BLOB_VERSION:$ivB64:$ctB64"
    }

    /**
     * Decrypt token blob formatted as "v1:<b64(iv)>:<b64(ciphertext)>".
     */
    private fun decryptToken(blob: String): String {
        val parts = blob.split(':')
        require(parts.size == 3) { "Invalid token blob format." }
        require(parts[0] == TOKEN_BLOB_VERSION) { "Unsupported token blob version: ${parts[0]}" }

        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ct = Base64.decode(parts[2], Base64.NO_WRAP)

        val key = getOrCreateAesKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        val pt = cipher.doFinal(ct)
        return pt.toString(Charsets.UTF_8)
    }

    /**
     * Get or create an AES-256 key in Android Keystore.
     */
    private fun getOrCreateAesKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        val existing = ks.getKey(KEYSTORE_ALIAS, null)
        if (existing is SecretKey) return existing

        val keyGen = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()

        keyGen.init(spec)
        return keyGen.generateKey()
    }

    /**
     * Normalize path prefix to a repo-relative form.
     *
     * Examples:
     * - "/diag/logs" -> "diag/logs"
     * - "diag/logs/" -> "diag/logs/"
     * - "" -> ""
     */
    private fun normalizePrefix(prefix: String): String {
        val p = prefix.trim()
        if (p.isEmpty()) return ""
        return p.trimStart('/')
    }
}
