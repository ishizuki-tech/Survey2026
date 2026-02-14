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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.io.IOException
import java.nio.charset.Charset
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "SupabaseDiagCfgStore"

/**
 * Stores and loads SupabaseUploader.SupabaseConfig for deferred uploads.
 *
 * Notes:
 * - anonKey is "client key" but still sensitive-ish; prefer encrypted storage when available.
 * - Avoid storing service_role keys in the app (never do that).
 * - This store respects the provided Context storage (device-protected vs credential-protected).
 *
 * Implementation detail:
 * - Avoids androidx.security.crypto (deprecated).
 * - Uses Android Keystore AES/GCM to encrypt values, stored in plain SharedPreferences as encoded strings.
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
    @Volatile private var cachedCrypto: CryptoBox? = null

    /**
     * Save Supabase diagnostics config.
     */
    fun save(context: Context, cfg: SupabaseUploader.SupabaseConfig) {
        val p = prefs(context)
        val crypto = cryptoOrNull()
        cachedIsEncrypted = crypto != null

        val url = crypto?.encryptToString(cfg.supabaseUrl) ?: cfg.supabaseUrl
        val anon = crypto?.encryptToString(cfg.anonKey) ?: cfg.anonKey
        val bucket = crypto?.encryptToString(cfg.bucket) ?: cfg.bucket
        val prefix = crypto?.encryptToString(cfg.pathPrefix) ?: cfg.pathPrefix
        val maxRaw = crypto?.encryptToString(cfg.maxRawBytesHint.toString()) ?: cfg.maxRawBytesHint.toString()

        p.edit {
            putString(KEY_URL, url)
            putString(KEY_ANON, anon)
            putString(KEY_BUCKET, bucket)
            putString(KEY_PREFIX, prefix)
            // Store as String to keep encrypted & avoid typed prefs collisions.
            putString(KEY_MAX_RAW, maxRaw)
        }

        Log.i(
            TAG,
            "Saved Supabase diagnostics config (bucket=${cfg.bucket}, prefix=${cfg.pathPrefix}, encrypted=$cachedIsEncrypted)."
        )
    }

    /**
     * Load Supabase diagnostics config.
     *
     * @return config if all required fields exist; otherwise null.
     */
    fun load(context: Context): SupabaseUploader.SupabaseConfig? {
        val p = prefs(context)
        val crypto = cryptoOrNull()
        cachedIsEncrypted = crypto != null

        val urlRaw = p.getString(KEY_URL, null)
        val anonRaw = p.getString(KEY_ANON, null)
        val bucketRaw = p.getString(KEY_BUCKET, null)
        val prefixRaw = p.getString(KEY_PREFIX, "surveyapp")
        val maxRawRaw = p.getString(KEY_MAX_RAW, null)

        val url = decodePossiblyEncrypted(crypto, urlRaw).orEmpty().trim()
        val anon = decodePossiblyEncrypted(crypto, anonRaw).orEmpty().trim()
        val bucket = decodePossiblyEncrypted(crypto, bucketRaw).orEmpty().trim()
        val prefix = decodePossiblyEncrypted(crypto, prefixRaw).orEmpty().trim()

        val maxRaw = runCatching {
            // Prefer String (new storage), but accept legacy Long if it exists.
            val s = decodePossiblyEncrypted(crypto, maxRawRaw)?.trim()
            when {
                !s.isNullOrBlank() -> s.toLong()
                p.contains(KEY_MAX_RAW) -> p.getLong(KEY_MAX_RAW, 20_000_000L)
                else -> 20_000_000L
            }
        }.getOrElse { 20_000_000L }.coerceAtLeast(1L)

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

            val created = baseContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            cachedPrefs = created
            return created
        }
    }

    private fun decodePossiblyEncrypted(crypto: CryptoBox?, value: String?): String? {
        if (value.isNullOrBlank()) return value
        if (crypto == null) return value
        return crypto.decryptFromString(value) ?: value
    }

    private fun cryptoOrNull(): CryptoBox? {
        cachedCrypto?.let { return it }

        synchronized(this) {
            cachedCrypto?.let { return it }

            val created = runCatching { CryptoBox(KEY_ALIAS) }.getOrNull()
            if (created == null) {
                Log.w(TAG, "Keystore crypto unavailable; falling back to plain prefs encryption=false")
            }
            cachedCrypto = created
            return created
        }
    }

    private const val KEY_ALIAS = "supabase_diag_cfg_aesgcm_v1"

    /**
     * Small helper that encrypts/decrypts Strings using Android Keystore AES/GCM.
     *
     * Storage format:
     *  "enc:v1:<b64(iv)>:<b64(ciphertext)>"
     *
     * Design goals:
     * - Deterministic parsing
     * - Safe to store as a single SharedPreferences string value
     * - Backward compatible: non-matching strings are treated as plaintext
     */
    private class CryptoBox(
        private val alias: String
    ) {
        private val charset: Charset = Charsets.UTF_8
        private val random = SecureRandom()

        fun encryptToString(plain: String): String {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }

            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            val ct = cipher.doFinal(plain.toByteArray(charset))

            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ctB64 = Base64.encodeToString(ct, Base64.NO_WRAP)
            return "$PREFIX$v1:$ivB64:$ctB64"
        }

        fun decryptFromString(stored: String): String? {
            val s = stored.trim()
            if (!s.startsWith(PREFIX)) return null

            // "enc:v1:<iv>:<ct>"
            val parts = s.split(':')
            if (parts.size != 4) return null
            val version = parts[1].trim()
            if (version != v1) return null

            val iv = runCatching { Base64.decode(parts[2], Base64.DEFAULT) }.getOrNull() ?: return null
            val ct = runCatching { Base64.decode(parts[3], Base64.DEFAULT) }.getOrNull() ?: return null
            if (iv.isEmpty() || ct.isEmpty()) return null

            return try {
                val key = getOrCreateKey()
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                val pt = cipher.doFinal(ct)
                pt.toString(charset)
            } catch (e: GeneralSecurityException) {
                null
            } catch (e: IOException) {
                null
            }
        }

        private fun getOrCreateKey(): SecretKey {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = (ks.getKey(alias, null) as? SecretKey)
            if (existing != null) return existing

            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                // Keep it usable in background without user auth prompts.
                .setUserAuthenticationRequired(false)
                .build()

            gen.init(spec)
            return gen.generateKey()
        }

        companion object {
            private const val ANDROID_KEYSTORE = "AndroidKeyStore"
            private const val TRANSFORMATION = "AES/GCM/NoPadding"

            private const val PREFIX = "enc:"
            private const val v1 = "v1"

            private const val KEY_BITS = 256
            private const val TAG_BITS = 128
            private const val IV_BYTES = 12
        }
    }
}
