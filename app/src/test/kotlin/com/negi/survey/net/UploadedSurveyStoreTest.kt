package com.negi.survey.net

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadedSurveyStoreTest {

    @Test
    fun recordsEachSurveyUuidOnlyOnce() {
        val store = UploadedSurveyStore(InMemoryPreferences().sharedPreferences)

        store.markUploaded("first-uuid")
        assertEquals(1, store.uploadedCount())
        assertTrue(store.isUploaded("first-uuid"))

        store.markUploaded(" FIRST-UUID ")
        assertEquals(1, store.uploadedCount())

        store.markUploaded("second-uuid")
        assertEquals(2, store.uploadedCount())
        assertTrue(store.isUploaded("second-uuid"))
    }

    @Test
    fun ignoresBlankSurveyUuid() {
        val store = UploadedSurveyStore(InMemoryPreferences().sharedPreferences)

        store.markUploaded("   ")

        assertEquals(0, store.uploadedCount())
        assertFalse(store.isUploaded(""))
    }

    @Test
    fun persistsAcrossStoreInstances() {
        val preferences = InMemoryPreferences()
        UploadedSurveyStore(preferences.sharedPreferences).markUploaded("persisted-uuid")

        val recreatedStore = UploadedSurveyStore(preferences.sharedPreferences)
        assertEquals(1, recreatedStore.uploadedCount())
        assertTrue(recreatedStore.isUploaded("persisted-uuid"))
    }

    @Test
    fun confirmedPayloadFingerprintPersistsAtomicallyAndHistoricalRecordsRemainFingerprintless() {
        val preferences = InMemoryPreferences()
        val store = UploadedSurveyStore(preferences.sharedPreferences)
        val fingerprint = SurveyUploadIdempotency.payloadFingerprint("{\"survey_id\":\"persisted-uuid\"}")

        store.markConfirmedUploaded("persisted-uuid", "2026-10-31", fingerprint)
        val recreated = UploadedSurveyStore(preferences.sharedPreferences)
        assertTrue(recreated.isUploaded("persisted-uuid"))
        assertEquals("2026-10-31", recreated.exportedAtFor("persisted-uuid"))
        assertEquals(fingerprint, recreated.payloadFingerprintFor("persisted-uuid"))

        recreated.markUploaded("historical")
        assertTrue(recreated.isUploaded("historical"))
        assertEquals(null, recreated.payloadFingerprintFor("historical"))
    }

    @Test
    fun failedConfirmedLedgerCommitDoesNotReportSuccess() {
        val preferences = InMemoryPreferences(commitSucceeds = false)
        val store = UploadedSurveyStore(preferences.sharedPreferences)
        val failure = runCatching {
            store.markConfirmedUploaded("survey", null, SurveyUploadIdempotency.payloadFingerprint("{}"))
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(store.isUploaded("survey"))
        assertEquals(null, store.payloadFingerprintFor("survey"))
    }

    @Test
    fun clearListHidesRecordsWithoutChangingAnyCanonicalEvidence() {
        val ledgerPrefs = InMemoryPreferences()
        val displayPrefs = InMemoryPreferences()
        val ledger = UploadedSurveyStore(ledgerPrefs.sharedPreferences)
        val visibility = CompletedSurveyVisibilityStore(displayPrefs.sharedPreferences)
        val fingerprint = SurveyUploadIdempotency.payloadFingerprint("confirmed bytes")
        ledger.markConfirmedUploaded("FIRST-ID", "2026-11-03", fingerprint)
        ledger.markConfirmedUploaded("second-id", "2026-11-03", fingerprint)
        val before = ledgerPrefs.sharedPreferences.all
        val displayed = visibility.visibleRecords(ledger.uploadedSurveyRecords()).mapTo(mutableSetOf()) { it.surveyId }

        visibility.hideCompleted(displayed, ledger.uploadedSurveyIds())

        assertTrue(visibility.visibleRecords(ledger.uploadedSurveyRecords()).isEmpty())
        assertEquals(before, ledgerPrefs.sharedPreferences.all)
        assertTrue(ledger.isUploaded(" FIRST-ID "))
        assertTrue(ledger.isUploaded("second-id"))
        assertEquals("2026-11-03", ledger.exportedAtFor("first-id"))
        assertEquals(fingerprint, ledger.payloadFingerprintFor("first-id"))
        assertEquals(2, ledger.uploadedCount())
    }

    @Test
    fun hiddenIdsPersistWhileNewUploadsWithIdenticalOrOlderTimesAndLegacyIdsStayVisible() {
        val ledgerPrefs = InMemoryPreferences()
        val displayPrefs = InMemoryPreferences()
        val ledger = UploadedSurveyStore(ledgerPrefs.sharedPreferences)
        ledger.markUploaded("historical-id", "2026-11-03")
        CompletedSurveyVisibilityStore(displayPrefs.sharedPreferences).hideCompleted(
            setOf("historical-id"), ledger.uploadedSurveyIds()
        )
        // Recreate both stores using persisted preferences, with no cached display state.
        val recreated = UploadedSurveyStore(ledgerPrefs.sharedPreferences)
        val visibility = CompletedSurveyVisibilityStore(displayPrefs.sharedPreferences)
        assertTrue(visibility.visibleRecords(recreated.uploadedSurveyRecords()).isEmpty())
        recreated.markUploaded("new-legacy-id", "2026-11-03")
        recreated.markUploaded("older-export-time", "2020-01-01")
        recreated.markUploaded("unknown-time")
        assertEquals(setOf("new-legacy-id", "older-export-time", "unknown-time"),
            visibility.visibleRecords(recreated.uploadedSurveyRecords()).map { it.surveyId }.toSet())
        assertTrue(recreated.isUploaded("historical-id"))
        assertEquals(4, recreated.uploadedCount())
    }

    @Test
    fun oldInstallationRepresentationIsReadWithoutRewritingOrUpgradingHistoricalEvidence() {
        val preferences = InMemoryPreferences()
        val fingerprint = "a".repeat(64)
        preferences.sharedPreferences.edit()
            .putStringSet("survey_ids", setOf("confirmed", "historical"))
            .putString("exported_at_by_id", """{"confirmed":"2026-11-03","historical":"2026-10-01"}""")
            .putString("payload_fingerprint_by_id", """{"confirmed":"$fingerprint"}""")
            .commit()
        val before = preferences.sharedPreferences.all
        val ledger = UploadedSurveyStore(preferences.sharedPreferences)
        val visibility = CompletedSurveyVisibilityStore(InMemoryPreferences().sharedPreferences)
        assertEquals(2, visibility.visibleRecords(ledger.uploadedSurveyRecords()).size)
        visibility.hideCompleted(ledger.uploadedSurveyIds(), ledger.uploadedSurveyIds())
        assertEquals(before, preferences.sharedPreferences.all)
        assertEquals(fingerprint, ledger.payloadFingerprintFor("confirmed"))
        assertEquals(null, ledger.payloadFingerprintFor("historical"))
        assertEquals("2026-10-01", ledger.exportedAtFor("historical"))
        assertTrue(ledger.isUploaded("historical"))
    }

    @Test
    fun completionDuringClearIsNotHiddenByAStaleDialogSnapshot() {
        val ledger = UploadedSurveyStore(InMemoryPreferences().sharedPreferences)
        val displayPrefs = InMemoryPreferences()
        val visibility = CompletedSurveyVisibilityStore(displayPrefs.sharedPreferences)
        ledger.markConfirmedUploaded("displayed", "same-time", "c".repeat(64))
        val displayed = ledger.uploadedSurveyIds()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        displayPrefs.beforeCommit = {
            entered.countDown()
            check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
        }
        val clearing = kotlin.concurrent.thread {
            try { visibility.hideCompleted(displayed, ledger.uploadedSurveyIds()) }
            catch (error: Throwable) { failure.set(error) }
        }
        try {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            ledger.markConfirmedUploaded("completed-during-clear", "same-time", "d".repeat(64))
        } finally {
            release.countDown()
            clearing.join(3000)
        }
        assertFalse(clearing.isAlive)
        assertEquals(null, failure.get())
        assertEquals(listOf("completed-during-clear"), visibility.visibleRecords(ledger.uploadedSurveyRecords()).map { it.surveyId })
        assertEquals(2, ledger.uploadedCount())
    }

    @Test
    fun pendingIdsCannotBeHiddenInAdvanceAndStaleClearCallsMergeVisibility() {
        val ledger = UploadedSurveyStore(InMemoryPreferences().sharedPreferences)
        val preferences = InMemoryPreferences()
        val first = CompletedSurveyVisibilityStore(preferences.sharedPreferences)
        val second = CompletedSurveyVisibilityStore(preferences.sharedPreferences)
        ledger.markUploaded("first")
        ledger.markUploaded("second")
        first.hideCompleted(setOf("first", "pending"), ledger.uploadedSurveyIds())
        second.hideCompleted(setOf("second"), ledger.uploadedSurveyIds())
        ledger.markUploaded("pending")
        assertEquals(listOf("pending"), first.visibleRecords(ledger.uploadedSurveyRecords()).map { it.surveyId })
        // Visibility IDs that are no longer in the supplied ledger snapshot can be pruned.
        first.hideCompleted(emptySet(), setOf("second", "pending"))
        assertEquals(setOf("second"), preferences.sharedPreferences.getStringSet("hidden_survey_ids", null))
    }

    @Test
    fun failedVisibilityCommitIsExplicitAndNeverChangesLedgerEvenWithAndroidMemoryUpdate() {
        for (updateMemory in listOf(false, true)) {
            val ledgerPrefs = InMemoryPreferences()
            val ledger = UploadedSurveyStore(ledgerPrefs.sharedPreferences)
            ledger.markConfirmedUploaded("confirmed", "2026-11-03", "b".repeat(64))
            val before = ledgerPrefs.sharedPreferences.all
            val displayPrefs = InMemoryPreferences(commitSucceeds = false, updateMemoryOnFailedCommit = updateMemory)
            val visibility = CompletedSurveyVisibilityStore(displayPrefs.sharedPreferences)
            repeat(2) {
                val error = runCatching { visibility.hideCompleted(setOf("confirmed"), ledger.uploadedSurveyIds()) }.exceptionOrNull()
                assertTrue(error is IllegalStateException)
                assertEquals(before, ledgerPrefs.sharedPreferences.all)
                assertEquals(1, ledger.uploadedCount())
            }
        }
    }

    /** Minimal in-memory SharedPreferences implementation for JVM store tests. */
    private class InMemoryPreferences(
        private val commitSucceeds: Boolean = true,
        private val updateMemoryOnFailedCommit: Boolean = false
    ) {
        var beforeCommit: (() -> Unit)? = null
        private val values = mutableMapOf<String, Any>()
        private val staged = mutableMapOf<String, Any?>()

        val sharedPreferences: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getStringSet" -> {
                    val key = args[0] as String
                    @Suppress("UNCHECKED_CAST")
                    (values[key] as? Set<String>)?.toSet() ?: args[1]
                }

                "getAll" -> values.toMap()
                "getString" -> values[args[0] as String] as? String ?: args[1]

                "edit" -> editor
                else -> error("Unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences

        private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { _, method, args ->
            when (method.name) {
                "putStringSet" -> {
                    val key = args[0] as String
                    @Suppress("UNCHECKED_CAST")
                    val ids = args[1] as Set<String>
                    staged[key] = ids.toSet()
                    editor
                }

                "putString" -> {
                    staged[args[0] as String] = args[1] as String
                    editor
                }

                "commit" -> {
                    beforeCommit?.invoke()
                    if (commitSucceeds || updateMemoryOnFailedCommit) staged.forEach { (key, value) ->
                        if (value == null) values.remove(key) else values[key] = value
                    }
                    staged.clear()
                    commitSucceeds
                }
                else -> error("Unexpected SharedPreferences.Editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
    }
}
