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

    /** Minimal in-memory SharedPreferences implementation for JVM store tests. */
    private class InMemoryPreferences(private val commitSucceeds: Boolean = true) {
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
                    if (commitSucceeds) staged.forEach { (key, value) ->
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
