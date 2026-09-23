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

    /** Minimal in-memory SharedPreferences implementation for JVM store tests. */
    private class InMemoryPreferences {
        private val values = mutableMapOf<String, Set<String>>()

        val sharedPreferences: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getStringSet" -> {
                    val key = args[0] as String
                    values[key]?.toSet() ?: args[1]
                }

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
                    values[key] = ids.toSet()
                    editor
                }

                "commit" -> true
                else -> error("Unexpected SharedPreferences.Editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
    }
}
