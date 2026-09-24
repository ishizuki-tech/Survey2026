/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: UploadRescheduleRecoveryTest.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.net

import androidx.work.Data
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UploadRescheduleRecoveryTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("upload-recovery-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun validSurveyJson_isClassifiedBySharedPendingSurveyDiscovery() {
        val file = write(
            "survey_export.json",
            """{"survey_id":"  Survey-123  ","answer":"value"}"""
        )

        val discovery = PendingSurveyUploads.discoverPendingSurveys(tempDir)

        assertEquals("Survey-123", PendingSurveyUploads.surveyIdFromFile(file))
        assertEquals(1, discovery.candidates.size)
        assertEquals("survey-123", discovery.candidates.single().normalizedSurveyId)
        assertEquals(file, discovery.candidates.single().canonicalFile)
        assertTrue(discovery.unclassifiedFiles.isEmpty())
    }

    @Test
    fun genericOrMalformedJson_isNotClassifiedAsSurveyJson() {
        val generic = write("generic.json", """{"answer":"value"}""")
        val blankSurveyId = write("blank.json", """{"survey_id":"  "}""")
        val nestedSurveyId = write("nested.json", """{"meta":{"survey_id":"not-top-level"}}""")
        val malformed = write("broken.json", "{not-json")
        val discovery = PendingSurveyUploads.discoverPendingSurveys(tempDir)

        listOf(generic, blankSurveyId, nestedSurveyId, malformed).forEach { file ->
            assertNull(PendingSurveyUploads.surveyIdFromFile(file))
        }
        assertTrue(discovery.candidates.isEmpty())
        assertEquals(4, discovery.unclassifiedFiles.size)
    }

    @Test
    fun nonSurveyFiles_areNotClassifiedEvenWhenTheirContentLooksLikeSurveyJson() {
        listOf("voice.wav", "session.log", "crash.txt", "diagnostic.gz").forEach { name ->
            val file = write(name, """{"survey_id":"must-not-count"}""")
            assertNull(PendingSurveyUploads.surveyIdFromFile(file))
        }

        assertTrue(PendingSurveyUploads.discoverPendingSurveys(tempDir).candidates.isEmpty())
    }

    @Test
    fun surveyMetadataHelper_usesExplicitSurveyJsonMarker() {
        val data = Data.Builder().also { builder ->
            SurveyUploadWork.addSurveyJsonMetadata(builder, " normal-survey-id ")
        }.build()

        assertEquals(
            GitHubUploadWorker.UPLOAD_KIND_SURVEY_JSON,
            data.getString(GitHubUploadWorker.KEY_UPLOAD_KIND)
        )
        assertEquals("normal-survey-id", data.getString(GitHubUploadWorker.KEY_SURVEY_ID))
    }

    @Test
    fun sharedLogicalWorkIdentity_usesNormalizedSurveyIdInsteadOfFileName() {
        assertEquals(
            SurveyUploadWork.logicalWorkName("survey-123"),
            SurveyUploadWork.logicalWorkName(" Survey-123 "),
        )
        assertTrue(
            SurveyUploadWork.logicalWorkName("survey-123") !=
                SurveyUploadWork.logicalWorkName("survey-124"),
        )
    }

    private fun write(name: String, content: String): File =
        File(tempDir, name).apply { writeText(content) }
}
