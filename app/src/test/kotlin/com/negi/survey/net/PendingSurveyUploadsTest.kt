package com.negi.survey.net

import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun discoverPendingSurveys_singleValidSurveyHasNoDuplicates() {
        val file = write("survey.json", """{"survey_id":"Survey-123"}""")

        val discovery = PendingSurveyUploads.discoverPendingSurveys(pendingDir)

        assertEquals(1, discovery.candidates.size)
        assertEquals("survey-123", discovery.candidates.single().normalizedSurveyId)
        assertEquals(file, discovery.candidates.single().canonicalFile)
        assertEquals(emptyList<File>(), discovery.candidates.single().duplicateFiles)
        assertEquals(emptyList<File>(), discovery.unclassifiedFiles)
    }

    @Test
    fun discoverPendingSurveys_twoDifferentIdsReturnsTwoCandidates() {
        write("z.json", """{"survey_id":"Beta"}""")
        write("a.json", """{"survey_id":"Alpha"}""")

        val discovery = PendingSurveyUploads.discoverPendingSurveys(pendingDir)

        assertEquals(listOf("alpha", "beta"), discovery.candidates.map { it.normalizedSurveyId })
        assertEquals(listOf("a.json", "z.json"), discovery.candidates.map { it.canonicalFile.name })
    }

    @Test
    fun discoverPendingSurveys_exactDuplicateIdExposesOrderedExtras() {
        val first = write("a-old.json", """{"survey_id":"survey-uuid"}""")
        val second = write("z-new.json", """{"survey_id":"survey-uuid"}""")

        val candidate = PendingSurveyUploads.discoverPendingSurveys(pendingDir).candidates.single()

        assertEquals(first, candidate.canonicalFile)
        assertEquals(listOf(second), candidate.duplicateFiles)
    }

    @Test
    fun discoverPendingSurveys_groupsCaseAndWhitespaceVariantsByNormalizedId() {
        val first = write("a.json", """{"survey_id":" UUID-A "}""")
        val second = write("b.json", """{"survey_id":"uuid-a"}""")

        val candidate = PendingSurveyUploads.discoverPendingSurveys(pendingDir).candidates.single()

        assertEquals("uuid-a", candidate.normalizedSurveyId)
        assertEquals(first, candidate.canonicalFile)
        assertEquals(listOf(second), candidate.duplicateFiles)
    }

    @Test
    fun pendingSurveyIds_preservesLegacyCaseDistinctBehavior() {
        write("upper.json", """{"survey_id":"UUID-A"}""")
        write("lower.json", """{"survey_id":"uuid-a"}""")

        assertEquals(setOf("UUID-A", "uuid-a"), PendingSurveyUploads.pendingSurveyIds(pendingDir))
    }

    @Test
    fun discoverPendingSurveys_malformedJsonIsUnclassified() {
        val file = write("broken.json", "{broken")

        val discovery = PendingSurveyUploads.discoverPendingSurveys(pendingDir)

        assertEquals(emptyList<PendingSurveyUploads.PendingSurveyCandidate>(), discovery.candidates)
        assertEquals(listOf(file), discovery.unclassifiedFiles)
    }

    @Test
    fun discoverPendingSurveys_missingBlankAndNonStringIdsAreUnclassified() {
        val blank = write("blank.json", """{"survey_id":"  "}""")
        val missing = write("missing.json", """{"answer":"value"}""")
        val number = write("number.json", """{"survey_id":7}""")

        val discovery = PendingSurveyUploads.discoverPendingSurveys(pendingDir)

        assertEquals(emptyList<PendingSurveyUploads.PendingSurveyCandidate>(), discovery.candidates)
        assertEquals(listOf(blank, missing, number), discovery.unclassifiedFiles)
    }

    @Test
    fun discoverPendingSurveys_otherNonStringAndNonObjectJsonAreUnclassified() {
        val array = write("array.json", "[]")
        val boolean = write("boolean.json", """{"survey_id":true}""")
        val nullValue = write("null.json", """{"survey_id":null}""")
        val objectValue = write("object.json", """{"survey_id":{"value":"id"}}""")

        val discovery = PendingSurveyUploads.discoverPendingSurveys(pendingDir)

        assertEquals(emptyList<PendingSurveyUploads.PendingSurveyCandidate>(), discovery.candidates)
        assertEquals(listOf(array, boolean, nullValue, objectValue), discovery.unclassifiedFiles)
    }

    @Test
    fun discoverPendingSurveys_nonJsonAndTemporaryFilesAreUnclassified() {
        val jsonTemporary = write("writing.part", """{"survey_id":"not-a-survey"}""")
        val metadata = write("survey.meta", "metadata")
        val voice = write("voice.wav", "not-json")

        val discovery = PendingSurveyUploads.discoverPendingSurveys(pendingDir)

        assertEquals(emptyList<PendingSurveyUploads.PendingSurveyCandidate>(), discovery.candidates)
        assertEquals(listOf(metadata, voice, jsonTemporary), discovery.unclassifiedFiles)
    }

    @Test
    fun discoverPendingSurveys_usesLexicalFileNameForCanonicalSelection() {
        val first = write("a.json", """{"survey_id":"same"}""")
        write("m.json", """{"survey_id":"same"}""")
        write("z.json", """{"survey_id":"same"}""")

        val candidate = PendingSurveyUploads.discoverPendingSurveys(pendingDir).candidates.single()

        assertEquals(first, candidate.canonicalFile)
        assertEquals(listOf("m.json", "z.json"), candidate.duplicateFiles.map { it.name })
    }

    @Test
    fun discoverPendingSurveys_fileRemovedThenRediscoveredIsAbsent() {
        val file = write("survey.json", """{"survey_id":"survey-id"}""")

        assertEquals(1, PendingSurveyUploads.discoverPendingSurveys(pendingDir).candidates.size)
        check(file.delete())

        val rediscovery = PendingSurveyUploads.discoverPendingSurveys(pendingDir)
        assertEquals(emptyList<PendingSurveyUploads.PendingSurveyCandidate>(), rediscovery.candidates)
        assertEquals(emptyList<File>(), rediscovery.unclassifiedFiles)
    }

    @Test
    fun discoverPendingSurveys_usesDirectFilesOnlyAndDoesNotMutateArtifacts() {
        val direct = write("direct.json", """{"survey_id":"direct-id"}""")
        val temporary = write("writing.tmp", "temporary")
        val nestedDirectory = File(pendingDir, "nested").apply { check(mkdir()) }
        val nested = File(nestedDirectory, "nested.json").apply {
            writeText("""{"survey_id":"nested-id"}""")
        }
        val directContent = direct.readText()
        val temporaryContent = temporary.readText()
        val nestedContent = nested.readText()

        val discovery = PendingSurveyUploads.discoverPendingSurveys(pendingDir)

        assertEquals(listOf("direct-id"), discovery.candidates.map { it.normalizedSurveyId })
        assertEquals(listOf(temporary), discovery.unclassifiedFiles)
        assertTrue(direct.isFile)
        assertTrue(temporary.isFile)
        assertTrue(nestedDirectory.isDirectory)
        assertTrue(nested.isFile)
        assertEquals(directContent, direct.readText())
        assertEquals(temporaryContent, temporary.readText())
        assertEquals(nestedContent, nested.readText())
    }

    @Test
    fun findPendingSurveyFile_preservesTrimmedExactCaseAndLexicalSelection() {
        val uppercase = write("a.json", """{"survey_id":"UUID-A"}""")
        write("b.json", """{"survey_id":"UUID-A"}""")
        write("c.json", """{"survey_id":"uuid-a"}""")

        assertEquals(uppercase, PendingSurveyUploads.findPendingSurveyFile(pendingDir, " UUID-A "))
        assertNull(PendingSurveyUploads.findPendingSurveyFile(pendingDir, "uuid-A"))
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
