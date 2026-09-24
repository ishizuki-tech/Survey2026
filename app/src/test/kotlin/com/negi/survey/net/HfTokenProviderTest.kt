package com.negi.survey.net

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class HfTokenProviderTest {

    @Test
    fun decrypt_returns_fixture_token_for_valid_material() {
        val material = encryptedFixture("token-fixture")

        assertEquals(
            "token-fixture",
            HfTokenProvider.decrypt(
                ciphertextB64 = material.ciphertextB64,
                nonceB64 = material.nonceB64,
                keyPartAB64 = material.keyPartAB64,
                keyPartBB64 = material.keyPartBB64,
            ),
        )
    }

    @Test
    fun decrypt_returns_null_for_missing_or_corrupt_material() {
        val material = encryptedFixture("token-fixture")

        assertNull(HfTokenProvider.decrypt("", material.nonceB64, material.keyPartAB64, material.keyPartBB64))
        assertNull(HfTokenProvider.decrypt(material.ciphertextB64.dropLast(2), material.nonceB64, material.keyPartAB64, material.keyPartBB64))
    }

    private fun encryptedFixture(plaintext: String): FixtureMaterial {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val keyPartA = ByteArray(32).also(SecureRandom()::nextBytes)
        val keyPartB = ByteArray(32) { index -> (key[index].toInt() xor keyPartA[index].toInt()).toByte() }
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(plaintext.toByteArray(Charsets.UTF_8))
        }
        val encoder = Base64.getEncoder()
        return FixtureMaterial(
            ciphertextB64 = encoder.encodeToString(ciphertext),
            nonceB64 = encoder.encodeToString(nonce),
            keyPartAB64 = encoder.encodeToString(keyPartA),
            keyPartBB64 = encoder.encodeToString(keyPartB),
        )
    }

    private data class FixtureMaterial(
        val ciphertextB64: String,
        val nonceB64: String,
        val keyPartAB64: String,
        val keyPartBB64: String,
    )
}
