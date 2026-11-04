package com.negi.survey.net

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SurveyUploadExecutionGuardTest {
    @Test
    fun preLedgerTerminationSeamRunsAfterRemoteWriteAndCannotFallThroughToLedger() = runBlocking {
        val authority = SurveyUploadExecutionAuthority(Preferences().sharedPreferences)
        val events = mutableListOf<String>()
        var ledgerCommitted = false

        val failure = runCatching {
            SurveyUploadExecutionGuard.execute(
                "survey", SurveyUploadExecutionAuthority.Protocol.CANONICAL, authority,
                ledgerContains = { ledgerCommitted },
                remoteWrite = { events += "remote" },
                recordUploaded = { ledgerCommitted = true; events += "ledger" },
                afterRemoteWrite = { events += "checkpoint"; error("self termination returned") },
            )
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertEquals(listOf("remote", "checkpoint"), events)
        assertFalse(ledgerCommitted)
        assertTrue(authority.ownerFor("survey") is SurveyUploadExecutionAuthority.Owner.VALID)
    }

    @Test
    fun postLedgerTerminationSeamRunsOnlyAfterLedgerAndAuthorityRelease() = runBlocking {
        val authority = SurveyUploadExecutionAuthority(Preferences().sharedPreferences)
        val events = mutableListOf<String>()
        var ledgerCommitted = false

        val failure = runCatching {
            SurveyUploadExecutionGuard.execute(
                "survey", SurveyUploadExecutionAuthority.Protocol.CANONICAL, authority,
                ledgerContains = { ledgerCommitted },
                remoteWrite = { events += "remote" },
                recordUploaded = { ledgerCommitted = true; events += "ledger" },
                afterRecordedUpload = {
                    events += "checkpoint"
                    assertTrue(authority.ownerFor("survey") is SurveyUploadExecutionAuthority.Owner.NONE)
                    error("self termination returned")
                },
            )
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertEquals(listOf("remote", "ledger", "checkpoint"), events)
        assertTrue(ledgerCommitted)
    }

    @Test
    fun concurrentLegacyAndCanonicalGuardedWorkersAllowOnlyOneRemoteWrite() {
        val authority = SurveyUploadExecutionAuthority(Preferences().sharedPreferences)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val remoteEntered = CountDownLatch(1)
        val releaseRemote = CountDownLatch(1)
        val competingClaimFinished = CountDownLatch(1)
        val writes = AtomicInteger()
        val ledger = AtomicInteger()
        val outcomes = mutableListOf<SurveyUploadExecutionGuard.Outcome>()

        fun worker(protocol: SurveyUploadExecutionAuthority.Protocol) = Thread {
            ready.countDown(); start.await()
            val outcome = runBlocking {
                SurveyUploadExecutionGuard.execute("survey", protocol, authority,
                    { ledger.get() != 0 }, {
                        writes.incrementAndGet(); remoteEntered.countDown(); releaseRemote.await()
                    }, { ledger.incrementAndGet() })
            }
            synchronized(outcomes) { outcomes += outcome }
            competingClaimFinished.countDown()
        }
        val legacy = worker(SurveyUploadExecutionAuthority.Protocol.LEGACY)
        val canonical = worker(SurveyUploadExecutionAuthority.Protocol.CANONICAL)
        legacy.start(); canonical.start()
        assertTrue(ready.await(1, TimeUnit.SECONDS)); start.countDown()
        val entered: Boolean
        val competitorFinished: Boolean
        try {
            entered = remoteEntered.await(1, TimeUnit.SECONDS)
            competitorFinished = entered && competingClaimFinished.await(1, TimeUnit.SECONDS)
        } finally {
            releaseRemote.countDown()
            legacy.join(); canonical.join()
        }
        assertTrue(entered)
        assertTrue(competitorFinished)

        assertEquals(1, writes.get())
        assertEquals(1, ledger.get())
        assertTrue(outcomes.any { it is SurveyUploadExecutionGuard.Outcome.BLOCKED_BY_OTHER_PROTOCOL })
    }

    @Test
    fun corruptAuthorityNeverReachesRemoteOrLedgerForEitherProtocol() = runBlocking {
        val prefs = Preferences()
        prefs.put(authorityKey("survey"), "malformed")
        val authority = SurveyUploadExecutionAuthority(prefs.sharedPreferences)
        val writes = AtomicInteger()
        val ledger = AtomicInteger()
        val staged = Files.createTempFile("survey-authority", ".json").toFile().apply { writeText("staged") }

        SurveyUploadExecutionAuthority.Protocol.entries.forEach { protocol ->
            assertEquals(
                SurveyUploadExecutionGuard.Outcome.CORRUPT_OR_UNKNOWN_AUTHORITY,
                run(protocol, authority, writes, ledger),
            )
        }
        assertEquals(0, writes.get())
        assertEquals(0, ledger.get())
        assertEquals("malformed", prefs.string(authorityKey("survey")))
        assertTrue(staged.isFile)
        staged.delete()
        Unit
    }

    @Test
    fun ledgerFailureRetainsPinAndBlocksOppositeProtocol() = runBlocking {
        val prefs = Preferences()
        val authority = SurveyUploadExecutionAuthority(prefs.sharedPreferences)
        val writes = AtomicInteger()
        val staged = Files.createTempFile("survey-ledger", ".json").toFile().apply { writeText("staged") }
        val failure = runCatching {
            SurveyUploadExecutionGuard.execute("survey", SurveyUploadExecutionAuthority.Protocol.LEGACY, authority,
                ledgerContains = { false }, remoteWrite = { writes.incrementAndGet() },
                recordUploaded = { error("ledger commit failed") })
        }.exceptionOrNull()
        assertTrue(failure != null)
        assertEquals(1, writes.get())
        assertEquals(
            SurveyUploadExecutionGuard.Outcome.BLOCKED_BY_OTHER_PROTOCOL(SurveyUploadExecutionAuthority.Protocol.LEGACY),
            run(SurveyUploadExecutionAuthority.Protocol.CANONICAL, authority, writes, AtomicInteger()),
        )
        assertEquals(SurveyUploadExecutionAuthority.Claim.AUTHORIZED,
            authority.claim("survey", SurveyUploadExecutionAuthority.Protocol.LEGACY))
        assertTrue(staged.isFile)
        staged.delete()
        Unit
    }

    @Test
    fun recordedLedgerPreventsSecondProtocolWriteAfterRelease() = runBlocking {
        val prefs = Preferences()
        val authority = SurveyUploadExecutionAuthority(prefs.sharedPreferences)
        val writes = AtomicInteger()
        var recorded = false
        assertEquals(SurveyUploadExecutionGuard.Outcome.UPLOADED,
            SurveyUploadExecutionGuard.execute("survey", SurveyUploadExecutionAuthority.Protocol.LEGACY, authority,
                { recorded }, { writes.incrementAndGet() }, { recorded = true }))
        assertEquals(SurveyUploadExecutionGuard.Outcome.ALREADY_RECORDED,
            SurveyUploadExecutionGuard.execute("survey", SurveyUploadExecutionAuthority.Protocol.CANONICAL, authority,
                { recorded }, { writes.incrementAndGet() }, { recorded = true }))
        assertEquals(1, writes.get())
    }

    @Test
    fun persistenceFailureDoesNotAuthorizeRemoteWrite() = runBlocking {
        val authority = SurveyUploadExecutionAuthority(Preferences(commitSucceeds = false).sharedPreferences)
        assertEquals(SurveyUploadExecutionGuard.Outcome.AUTHORITY_PERSISTENCE_FAILURE,
            run(SurveyUploadExecutionAuthority.Protocol.LEGACY, authority, AtomicInteger(), AtomicInteger()))
    }

    @Test
    fun historicalMarkerlessSurveyUsesLegacyGuard() = runBlocking {
        val writes = AtomicInteger()
        val result = run(
            SurveyUploadExecutionAuthority.Protocol.LEGACY,
            SurveyUploadExecutionAuthority(Preferences().sharedPreferences),
            writes,
            AtomicInteger(),
        )
        assertEquals(SurveyUploadExecutionGuard.Outcome.UPLOADED, result)
        assertEquals(1, writes.get())
    }

    private suspend fun run(protocol: SurveyUploadExecutionAuthority.Protocol, authority: SurveyUploadExecutionAuthority,
                            writes: AtomicInteger, ledger: AtomicInteger): SurveyUploadExecutionGuard.Outcome =
        SurveyUploadExecutionGuard.execute("survey", protocol, authority, { ledger.get() != 0 },
            { writes.incrementAndGet() }, { ledger.incrementAndGet() })

    private fun authorityKey(id: String): String = "survey:" + MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class Preferences(private val commitSucceeds: Boolean = true) {
        private val values = linkedMapOf<String, String>()
        private val staged = linkedMapOf<String, String?>()
        val sharedPreferences: SharedPreferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args -> when (method.name) {
                "getString" -> values[args[0] as String] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected SharedPreferences call: ${method.name}")
            }} as SharedPreferences
        private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { _, method, args -> when (method.name) {
                "putString" -> { staged[args[0] as String] = args[1] as String; editor }
                "remove" -> { staged[args[0] as String] = null; editor }
                "commit" -> { if (commitSucceeds) staged.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }; staged.clear(); commitSucceeds }
                else -> error("Unexpected Editor call: ${method.name}")
            }} as SharedPreferences.Editor
        fun put(key: String, value: String) { values[key] = value }
        fun string(key: String): String? = values[key]
    }
}
