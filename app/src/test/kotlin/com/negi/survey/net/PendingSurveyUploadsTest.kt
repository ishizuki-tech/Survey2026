package com.negi.survey.net

import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PendingSurveyUploadsTest {
    private lateinit var pendingDir: File

    @Before
    fun setUp() {
        pendingDir = Files.createTempDirectory("pending-surveys-test").toFile()
    }

    @After
    fun tearDown() {
        pendingDir.deleteRecursively()
    }

    @Test
    fun pendingSurveyIds_returnsUniqueValidTopLevelSurveyIdsOnly() {
        write("old_survey.json", """{"survey_id":" UUID-A "}""")
        write("timestamp_survey_1.json", """{"survey_id":"UUID-A"}""")
        write("timestamp_survey_2.json", """{"survey_id":"UUID-B"}""")
        write("nested.json", """{"meta":{"survey_id":"nested"}}""")
        write("number.json", """{"survey_id":7}""")
        write("null.json", """{"survey_id":null}""")
        write("blank.json", """{"survey_id":"  "}""")
        write("broken.json", "{broken")
        write("generic.json", """{"answer":"value"}""")
        listOf("voice.wav", "session.log", "crash.txt", "diagnostic.gz", "unknown.bin").forEach {
            write(it, """{"survey_id":"must-not-count"}""")
        }

        assertEquals(setOf("UUID-A", "UUID-B"), PendingSurveyUploads.pendingSurveyIds(pendingDir))
    }

    @Test
    fun pendingCount_excludesAlreadyUploadedSurveyIds() {
        write("first.json", """{"survey_id":"uploaded-id"}""")
        write("first_1.json", """{"survey_id":"uploaded-id"}""")
        write("second.json", """{"survey_id":"pending-id"}""")
        val store = UploadedSurveyStore(InMemoryPreferences().sharedPreferences)
        store.markUploaded("uploaded-id")

        val pending = PendingSurveyUploads.pendingSurveyIds(pendingDir)
            .count { id -> !store.isUploaded(id) }

        assertEquals(1, pending)
    }

    @Test
    fun surveyIdFromFile_doesNotUseFilenameAsIdentity() {
        val old = write("survey_legacy.json", """{"survey_id":"old-format"}""")
        val timestampFirst = write(
            "2026-09-22_11-37-48_survey_Pixel_9a_CODE_new-format.json",
            """{"survey_id":"new-format"}"""
        )
        val nameOnly = write("survey_filename-only.json", """{"answer":"value"}""")

        assertEquals("old-format", PendingSurveyUploads.surveyIdFromFile(old))
        assertEquals("new-format", PendingSurveyUploads.surveyIdFromFile(timestampFirst))
        assertNull(PendingSurveyUploads.surveyIdFromFile(nameOnly))
    }

    private fun write(name: String, content: String): File =
        File(pendingDir, name).apply { writeText(content) }

    private class InMemoryPreferences {
        private val values = mutableMapOf<String, Set<String>>()

        val sharedPreferences: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getStringSet" -> values[args[0] as String]?.toSet() ?: args[1]
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
                    @Suppress("UNCHECKED_CAST")
                    values[args[0] as String] = (args[1] as Set<String>).toSet()
                    editor
                }
                "commit" -> true
                else -> error("Unexpected SharedPreferences.Editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
    }
}
