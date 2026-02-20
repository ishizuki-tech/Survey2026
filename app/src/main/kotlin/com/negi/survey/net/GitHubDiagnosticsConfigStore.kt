/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: GitHubDiagnosticsConfigStore.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Stores GitHub diagnostics upload configuration WITHOUT Jetpack Security Crypto.
 *
 *  Approach:
 *   - Persist non-sensitive fields as plain prefs.
 *   - Persist the GitHub token encrypted using Android Keystore (AES/GCM) when available.
 *   - Best-effort fallback to plaintext token on devices where Keystore crypto is unavailable.
 *
 *  Data format for encrypted token:
 *   - "v1:<base64(iv)>:<base64(ciphertext_with_tag)>"
 * =====================================================================
 */

@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import android.util.Log
import com.negi.survey.BuildConfig
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import androidx.core.content.edit

object GitHubDiagnosticsConfigStore {

    private const val TAG = "GitHubDiagCfgStore"

    private const val PREF_NAME = "github_diagnostics_cfg"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_OWNER = "owner"
    private const val KEY_REPO = "repo"
    private const val KEY_BRANCH = "branch"
    private const val KEY_PATH_PREFIX = "path_prefix"
    private const val KEY_TOKEN = "token"

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    /** Current alias (stable per applicationId). */
    private val KEY_ALIAS: String = "${BuildConfig.APPLICATION_ID}.GitHubDiagnostics.Token"

    /** Legacy alias fallback (safe to try; no-op if not present). */
    private const val LEGACY_KEY_ALIAS: String = "GitHubDiagnostics.Token"

    private const val ENC_PREFIX_V1 = "v1:"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    data class StoreConfig(
        val enabled: Boolean,
        val owner: String,
        val repo: String,
        val branch: String,
        val pathPrefix: String,
        val token: String
    ) {
        fun isUsable(): Boolean {
            if (!enabled) return false
            if (token.isBlank()) return false
            if (repo.isBlank()) return false

            if (owner.isNotBlank()) return true

            val hasOwnerInRepo = repo.contains("/") &&
                    repo.substringBefore("/").isNotBlank() &&
                    repo.substringAfterLast("/").isNotBlank()

            return hasOwnerInRepo
        }
    }

    private data class TokenLoadResult(
        val token: String,
        val migratedStored: String? = null
    )

    fun load(context: Context): StoreConfig {
        val p = prefs(context)

        val enabled = p.getBoolean(KEY_ENABLED, true)

        val owner = p.getString(KEY_OWNER, null)
            ?.trim()
            ?.ifBlank { null }
            ?: BuildConfig.GH_OWNER.trim()

        val repo = p.getString(KEY_REPO, null)
            ?.trim()
            ?.ifBlank { null }
            ?: BuildConfig.GH_REPO.trim()

        val branch = p.getString(KEY_BRANCH, null)
            ?.trim()
            ?.ifBlank { null }
            ?: BuildConfig.GH_BRANCH.trim().ifBlank { "main" }

        val pathPrefix = p.getString(KEY_PATH_PREFIX, null)
            ?.trim()
            ?.trim('/')
            ?.ifBlank { null }
            ?: BuildConfig.GH_PATH_PREFIX.trim().trim('/')

        val tokenStored = (p.getString(KEY_TOKEN, "") ?: "").trim()
        val tokenRes = decodeTokenBestEffort(tokenStored)

        val token = tokenRes.token.ifBlank { BuildConfig.GH_TOKEN.trim() }

        if (tokenRes.migratedStored != null && tokenRes.migratedStored != tokenStored) {
            runCatching { p.edit { putString(KEY_TOKEN, tokenRes.migratedStored) } }
        }

        return StoreConfig(
            enabled = enabled,
            owner = owner,
            repo = repo,
            branch = branch,
            pathPrefix = pathPrefix,
            token = token
        )
    }

    fun save(context: Context, cfg: StoreConfig) {
        val p = prefs(context)
        val tokenToStore = encryptTokenBestEffort(cfg.token)

        p.edit()
            .putBoolean(KEY_ENABLED, cfg.enabled)
            .putString(KEY_OWNER, cfg.owner.trim())
            .putString(KEY_REPO, cfg.repo.trim())
            .putString(KEY_BRANCH, cfg.branch.trim().ifBlank { "main" })
            .putString(KEY_PATH_PREFIX, cfg.pathPrefix.trim().trim('/'))
            .putString(KEY_TOKEN, tokenToStore)
            .apply()
    }

    fun saveToken(context: Context, token: String) {
        val p = prefs(context)
        val tokenToStore = encryptTokenBestEffort(token.trim())
        p.edit().putString(KEY_TOKEN, tokenToStore).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun buildGitHubConfigOrNull(context: Context): GitHubUploader.GitHubConfig? {
        val cfg = load(context)
        if (!cfg.isUsable()) return null

        val normalized = normalizeOwnerRepo(cfg.owner, cfg.repo)
        val owner = normalized.owner
        val repoName = normalized.repoName

        if (owner.isBlank() || repoName.isBlank() || cfg.token.isBlank()) return null

        return GitHubUploader.GitHubConfig(
            owner = owner,
            repo = repoName,
            branch = cfg.branch.ifBlank { "main" },
            pathPrefix = cfg.pathPrefix.trim().trim('/'),
            token = cfg.token
        )
    }

    @Deprecated("Use load(context) instead.", ReplaceWith("load(context)"))
    fun get(context: Context): StoreConfig = load(context)

    @Deprecated("Use save(context, cfg) instead.", ReplaceWith("save(context, cfg)"))
    fun put(context: Context, cfg: StoreConfig) = save(context, cfg)

    @Deprecated("Use buildGitHubConfigOrNull(context) instead.", ReplaceWith("buildGitHubConfigOrNull(context)"))
    fun getGitHubConfigOrNull(context: Context): GitHubUploader.GitHubConfig? = buildGitHubConfigOrNull(context)

    private data class OwnerRepoNormalized(
        val owner: String,
        val repoName: String
    )

    private fun normalizeOwnerRepo(ownerRaw: String, repoRaw: String): OwnerRepoNormalized {
        val owner = ownerRaw.trim()
        val repo = repoRaw.trim()

        if (repo.contains("/")) {
            val derivedOwner = repo.substringBefore("/").trim()
            val derivedRepoName = repo.substringAfterLast("/").trim()
            return OwnerRepoNormalized(
                owner = owner.ifBlank { derivedOwner },
                repoName = derivedRepoName
            )
        }

        return OwnerRepoNormalized(owner = owner, repoName = repo)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun encryptTokenBestEffort(tokenPlain: String): String {
        val t = tokenPlain.trim()
        if (t.isBlank()) return ""

        val encrypted = runCatching { encryptTokenKeystore(t, KEY_ALIAS) }
            .onFailure { e ->
                Log.w(TAG, "encryptTokenBestEffort: keystore encrypt failed -> plaintext fallback", e)
            }
            .getOrNull()

        return encrypted ?: t
    }

    private data class DecryptResult(
        val token: String,
        val usedAlias: String
    )

    private fun decodeTokenBestEffort(stored: String): TokenLoadResult {
        val s = stored.trim()
        if (s.isBlank()) return TokenLoadResult(token = "")

        // Plaintext token -> migrate to encrypted when possible.
        if (!s.startsWith(ENC_PREFIX_V1)) {
            val token = s
            val migratedEncrypted = runCatching { encryptTokenKeystore(token, KEY_ALIAS) }
                .onFailure { e ->
                    Log.w(TAG, "decodeTokenBestEffort: plaintext token encrypt failed -> keep plaintext", e)
                }
                .getOrNull()

            return TokenLoadResult(
                token = token,
                migratedStored = migratedEncrypted
            )
        }

        val aliases = listOf(KEY_ALIAS, LEGACY_KEY_ALIAS)

        val decrypted = runCatching { decryptTokenKeystoreWithAliases(s, aliases) }
            .onFailure { e ->
                Log.w(TAG, "decodeTokenBestEffort: keystore decrypt failed -> treat as blank", e)
            }
            .getOrNull()

        if (decrypted == null || decrypted.token.isBlank()) return TokenLoadResult(token = "")

        // Migrate only if legacy alias was used.
        val migratedStored = if (decrypted.usedAlias != KEY_ALIAS) {
            runCatching { encryptTokenKeystore(decrypted.token, KEY_ALIAS) }
                .onFailure { e ->
                    Log.w(TAG, "decodeTokenBestEffort: legacy->current migration encrypt failed", e)
                }
                .getOrNull()
        } else {
            null
        }

        return TokenLoadResult(
            token = decrypted.token,
            migratedStored = migratedStored
        )
    }

    private fun encryptTokenKeystore(tokenPlain: String, alias: String): String {
        if (Build.VERSION.SDK_INT < 23) {
            throw IllegalStateException("Android Keystore AES/GCM requires API 23+")
        }

        val key = getOrCreateAesKey(alias)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")

        val iv = ByteArray(GCM_IV_BYTES)
        SecureRandom().nextBytes(iv)

        val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)

        val ct = cipher.doFinal(tokenPlain.toByteArray(Charsets.UTF_8))

        val ivB64 = b64(iv)
        val ctB64 = b64(ct)

        return "$ENC_PREFIX_V1$ivB64:$ctB64"
    }

    private fun decryptTokenKeystoreWithAliases(stored: String, aliases: List<String>): DecryptResult {
        if (Build.VERSION.SDK_INT < 23) {
            throw IllegalStateException("Android Keystore AES/GCM requires API 23+")
        }

        val body = stored.removePrefix(ENC_PREFIX_V1)
        val parts = body.split(":", limit = 2)
        if (parts.size != 2) throw IllegalArgumentException("Bad token format")

        val iv = b64d(parts[0])
        val ct = b64d(parts[1])

        for (alias in aliases) {
            val key = getExistingAesKeyOrNull(alias) ?: continue

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_BITS, iv)

            val pt = runCatching {
                cipher.init(Cipher.DECRYPT_MODE, key, spec)
                val bytes = cipher.doFinal(ct)
                bytes.toString(Charsets.UTF_8)
            }.getOrNull()

            if (!pt.isNullOrBlank()) {
                return DecryptResult(token = pt, usedAlias = alias)
            }
        }

        throw IllegalStateException("No usable keystore key found for token decrypt.")
    }

    private fun getExistingAesKeyOrNull(alias: String): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return runCatching {
            (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        }.getOrNull()
    }

    private fun getOrCreateAesKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        val existing = (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing

        val kg = KeyGenerator.getInstance("AES", KEYSTORE_PROVIDER)
        val spec = buildKeyGenSpecReflective(alias)
        kg.init(spec)
        return kg.generateKey()
    }

    /**
     * Build KeyGenParameterSpec reflectively to avoid hard references on class loading.
     */
    private fun buildKeyGenSpecReflective(alias: String): AlgorithmParameterSpec {
        val keyProperties = Class.forName("android.security.keystore.KeyProperties")

        val purposeEncrypt = keyProperties.getField("PURPOSE_ENCRYPT").getInt(null)
        val purposeDecrypt = keyProperties.getField("PURPOSE_DECRYPT").getInt(null)
        val blockModeGcm = keyProperties.getField("BLOCK_MODE_GCM").get(null) as String
        val encPaddingNone = keyProperties.getField("ENCRYPTION_PADDING_NONE").get(null) as String

        val builderCls = Class.forName("android.security.keystore.KeyGenParameterSpec\$Builder")
        val ctor = builderCls.getConstructor(String::class.java, Int::class.javaPrimitiveType)
        val builder = ctor.newInstance(alias, purposeEncrypt or purposeDecrypt)

        builderCls.getMethod("setBlockModes", Array<String>::class.java)
            .invoke(builder, arrayOf(blockModeGcm))

        builderCls.getMethod("setEncryptionPaddings", Array<String>::class.java)
            .invoke(builder, arrayOf(encPaddingNone))

        runCatching {
            builderCls.getMethod("setKeySize", Int::class.javaPrimitiveType)
                .invoke(builder, 256)
        }

        runCatching {
            builderCls.getMethod("setUserAuthenticationRequired", Boolean::class.javaPrimitiveType)
                .invoke(builder, false)
        }

        val build = builderCls.getMethod("build")
        return build.invoke(builder) as AlgorithmParameterSpec
    }

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun b64d(s: String): ByteArray =
        Base64.decode(s, Base64.NO_WRAP)
}