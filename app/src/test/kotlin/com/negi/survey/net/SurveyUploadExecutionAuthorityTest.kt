package com.negi.survey.net

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyUploadExecutionAuthorityTest {
    @Test
    fun concurrentLegacyAndCanonicalClaimsAuthorizeAtMostOneRemoteTransport() {
        val prefs = InMemoryPreferences()
        val authority = SurveyUploadExecutionAuthority(prefs.sharedPreferences)
        val start = CountDownLatch(1)
        val ready = CountDownLatch(2)
        val remoteWrites = AtomicInteger()

        fun worker(protocol: SurveyUploadExecutionAuthority.Protocol) = Thread {
            ready.countDown()
            start.await()
            if (authority.claim(" Same-Survey ", protocol) == SurveyUploadExecutionAuthority.Claim.AUTHORIZED) {
                FakeRemoteTransport(remoteWrites).put()
            }
        }

        val legacy = worker(SurveyUploadExecutionAuthority.Protocol.LEGACY)
        val canonical = worker(SurveyUploadExecutionAuthority.Protocol.CANONICAL)
        legacy.start(); canonical.start()
        assertTrue(ready.await(1, java.util.concurrent.TimeUnit.SECONDS))
        start.countDown()
        legacy.join(); canonical.join()

        assertEquals(1, remoteWrites.get())
    }

    @Test
    fun authoritySurvivesRecreationAllowsSameProtocolRetryAndBlocksCrossProtocol() {
        val prefs = InMemoryPreferences()
        val first = SurveyUploadExecutionAuthority(prefs.sharedPreferences)
        assertEquals(
            SurveyUploadExecutionAuthority.Claim.AUTHORIZED,
            first.claim("survey", SurveyUploadExecutionAuthority.Protocol.LEGACY),
        )

        val recreated = SurveyUploadExecutionAuthority(prefs.sharedPreferences)
        assertEquals(
            SurveyUploadExecutionAuthority.Claim.AUTHORIZED,
            recreated.claim("survey", SurveyUploadExecutionAuthority.Protocol.LEGACY),
        )
        assertEquals(
            SurveyUploadExecutionAuthority.Claim.BLOCKED_BY_OTHER_PROTOCOL(
                SurveyUploadExecutionAuthority.Protocol.LEGACY,
            ),
            recreated.claim("survey", SurveyUploadExecutionAuthority.Protocol.CANONICAL),
        )
    }

    @Test
    fun ledgerConfirmationReleasesStaleProtocolPin() {
        val authority = SurveyUploadExecutionAuthority(InMemoryPreferences().sharedPreferences)
        authority.claim("survey", SurveyUploadExecutionAuthority.Protocol.CANONICAL)

        assertTrue(authority.clearForRecordedUpload("survey", SurveyUploadExecutionAuthority.Protocol.CANONICAL))
        assertEquals(
            SurveyUploadExecutionAuthority.Claim.AUTHORIZED,
            authority.claim("survey", SurveyUploadExecutionAuthority.Protocol.LEGACY),
        )
    }

    @Test
    fun persistenceFailureDoesNotAuthorizeRemoteTransport() {
        val authority = SurveyUploadExecutionAuthority(
            InMemoryPreferences(commitSucceeds = false).sharedPreferences,
        )
        val writes = AtomicInteger()

        if (authority.claim("survey", SurveyUploadExecutionAuthority.Protocol.LEGACY) ==
            SurveyUploadExecutionAuthority.Claim.AUTHORIZED
        ) {
            FakeRemoteTransport(writes).put()
        }

        assertEquals(0, writes.get())
    }

    @Test
    fun corruptValueIsRetainedAndFailsClosed() {
        val prefs = InMemoryPreferences()
        prefs.putString(authorityKey("survey"), "future-protocol")
        val authority = SurveyUploadExecutionAuthority(prefs.sharedPreferences)

        assertEquals(
            SurveyUploadExecutionAuthority.Claim.CORRUPT_OR_UNKNOWN_OWNER,
            authority.claim("survey", SurveyUploadExecutionAuthority.Protocol.LEGACY),
        )
        assertEquals("future-protocol", prefs.string(authorityKey("survey")))
    }

    private fun authorityKey(id: String): String = "survey:" + MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class FakeRemoteTransport(private val writes: AtomicInteger) {
        fun put() {
            writes.incrementAndGet()
        }
    }

    private class InMemoryPreferences(private val commitSucceeds: Boolean = true) {
        private val values = mutableMapOf<String, String>()
        private val pending = linkedMapOf<String, String?>()

        val sharedPreferences: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0] as String] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences

        private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString" -> {
                    pending[args[0] as String] = args[1] as String
                    editor
                }
                "remove" -> {
                    pending[args[0] as String] = null
                    editor
                }
                "commit" -> {
                    if (commitSucceeds) pending.forEach { (key, value) ->
                        if (value == null) values.remove(key) else values[key] = value
                    }
                    pending.clear()
                    commitSucceeds
                }
                else -> error("Unexpected SharedPreferences.Editor call: ${method.name}")
            }
        } as SharedPreferences.Editor

        fun putString(key: String, value: String) { values[key] = value }
        fun string(key: String): String? = values[key]
    }
}
