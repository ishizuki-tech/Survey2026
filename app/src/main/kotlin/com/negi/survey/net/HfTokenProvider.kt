package com.negi.survey.net

import com.negi.survey.BuildConfig
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Supplies a short-lived decrypted Hugging Face token for authenticated requests. */
object HfTokenProvider {

    fun token(): String? = decrypt(
        ciphertextB64 = BuildConfig.HF_TOKEN_CIPHERTEXT_B64,
        nonceB64 = BuildConfig.HF_TOKEN_NONCE_B64,
        keyPartAB64 = BuildConfig.HF_TOKEN_KEY_PART_A_B64,
        keyPartBB64 = BuildConfig.HF_TOKEN_KEY_PART_B_B64,
    )

    internal fun decrypt(
        ciphertextB64: String,
        nonceB64: String,
        keyPartAB64: String,
        keyPartBB64: String,
    ): String? {
        if (
            ciphertextB64.isBlank() ||
            nonceB64.isBlank() ||
            keyPartAB64.isBlank() ||
            keyPartBB64.isBlank()
        ) {
            return null
        }

        var ciphertext: ByteArray? = null
        var nonce: ByteArray? = null
        var keyPartA: ByteArray? = null
        var keyPartB: ByteArray? = null
        var key: ByteArray? = null
        var plaintext: ByteArray? = null

        return try {
            val decoder = Base64.getDecoder()
            ciphertext = decoder.decode(ciphertextB64)
            nonce = decoder.decode(nonceB64)
            keyPartA = decoder.decode(keyPartAB64)
            keyPartB = decoder.decode(keyPartBB64)

            if (
                ciphertext.size < 16 ||
                nonce.size != 12 ||
                keyPartA.size != 32 ||
                keyPartB.size != 32
            ) {
                return null
            }

            key = ByteArray(32) { index ->
                (keyPartA[index].toInt() xor keyPartB[index].toInt()).toByte()
            }
            plaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                doFinal(ciphertext)
            }
            plaintext.toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } finally {
            ciphertext?.fill(0)
            nonce?.fill(0)
            keyPartA?.fill(0)
            keyPartB?.fill(0)
            key?.fill(0)
            plaintext?.fill(0)
        }
    }
}
