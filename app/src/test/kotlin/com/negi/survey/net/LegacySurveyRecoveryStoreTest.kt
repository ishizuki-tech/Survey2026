package com.negi.survey.net

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySurveyRecoveryStoreTest {
    @Test
    fun records_enumeratesMultipleRecordsWithoutDuplicatesAndSurvivesRecreation() {
        val prefs = InMemoryPreferences()
        val store = LegacySurveyRecoveryStore(prefs.sharedPreferences)

        assertTrue(store.record(" Survey-B ", "/ignored/b.json", "legacy route missing"))
        assertTrue(store.record("survey-a", "a.json", "legacy route missing"))
        assertTrue(store.record("survey-a", "a.json", "legacy route missing"))

        val recreated = LegacySurveyRecoveryStore(prefs.sharedPreferences)
        assertEquals(
            listOf("survey-a", "survey-b"),
            recreated.records().map { it.normalizedSurveyId },
        )
        assertEquals("b.json", recreated.get("survey-b")!!.fileName)
        assertTrue(recreated.clear("survey-a"))
        assertEquals(listOf("survey-b"), recreated.records().map { it.normalizedSurveyId })
    }

    @Test
    fun records_migratesPreIndexRecordsAndIgnoresCorruptionWithoutHidingValidRecord() {
        val prefs = InMemoryPreferences()
        prefs.putString(recordKey("legacy-visible"), "legacy-visible\nlegacy.json\nroute missing")
        prefs.putString("legacy:" + "0".repeat(64), "not\na\nrecord")
        prefs.putStringSet("legacy_record_keys_v1", setOf("legacy:" + "0".repeat(64)))
        prefs.putString("unrelated", "respondent answer must never be read")

        val before = prefs.values()
        val writesBefore = prefs.editCalls
        val store = LegacySurveyRecoveryStore(prefs.sharedPreferences)
        val records = store.records()

        assertEquals(1, records.size)
        assertEquals("legacy-visible", records.single().normalizedSurveyId)
        assertTrue(prefs.hasStringSet("legacy_record_keys_v1"))
        assertFalse(prefs.values().values.any { it.toString().contains("respondent answer") && it != "respondent answer must never be read" })
        assertEquals(before, prefs.values())
        assertEquals(writesBefore, prefs.editCalls)
        assertEquals(records, store.records())
        assertEquals(before, prefs.values())
    }

    @Test
    fun records_discoversUnindexedHistoricalRecordsWithoutPersistingAnIndex() {
        val prefs = InMemoryPreferences()
        prefs.putString(recordKey("legacy-a"), "legacy-a\na.json\nroute missing")
        prefs.putString(recordKey("legacy-b"), "legacy-b\nb.json\nroute missing")
        val before = prefs.values()
        val writesBefore = prefs.editCalls

        val store = LegacySurveyRecoveryStore(prefs.sharedPreferences)

        assertEquals(listOf("legacy-a", "legacy-b"), store.records().map { it.normalizedSurveyId })
        assertEquals(listOf("legacy-a", "legacy-b"), store.records().map { it.normalizedSurveyId })
        assertEquals(before, prefs.values())
        assertEquals(writesBefore, prefs.editCalls)
        assertFalse(prefs.hasStringSet("legacy_record_keys_v1"))
    }

    @Test
    fun records_readsValidIndexWithoutWriting() {
        val prefs = InMemoryPreferences()
        val store = LegacySurveyRecoveryStore(prefs.sharedPreferences)
        assertTrue(store.record("indexed", "indexed.json", "route missing"))
        val before = prefs.values()
        val writesBefore = prefs.editCalls

        assertEquals(listOf("indexed"), store.records().map { it.normalizedSurveyId })

        assertEquals(before, prefs.values())
        assertEquals(writesBefore, prefs.editCalls)
    }

    private fun recordKey(id: String): String = "legacy:" + MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class InMemoryPreferences {
        private val values = linkedMapOf<String, Any>()
        var editCalls = 0
            private set

        val sharedPreferences: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0] as String] as? String ?: args[1]
                "getStringSet" -> (values[args[0] as String] as? Set<*>)
                    ?.filterIsInstance<String>()
                    ?.toSet()
                    ?: args[1]
                "getAll" -> values.toMap()
                "edit" -> {
                    editCalls += 1
                    editor
                }
                else -> error("Unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences

        private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString" -> {
                    values[args[0] as String] = args[1] as String
                    editor
                }
                "putStringSet" -> {
                    val entries = (args[1] as Set<*>).filterIsInstance<String>().toSet()
                    values[args[0] as String] = entries
                    editor
                }
                "remove" -> {
                    values.remove(args[0] as String)
                    editor
                }
                "commit" -> true
                else -> error("Unexpected SharedPreferences.Editor call: ${method.name}")
            }
        } as SharedPreferences.Editor

        fun putString(key: String, value: String) {
            values[key] = value
        }

        fun putStringSet(key: String, value: Set<String>) {
            values[key] = value.toSet()
        }

        fun hasStringSet(key: String): Boolean = values[key] is Set<*>
        fun values(): Map<String, Any> = values.toMap()
    }
}
