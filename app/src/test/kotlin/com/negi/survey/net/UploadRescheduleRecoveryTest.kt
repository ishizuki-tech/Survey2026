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
    fun validSurveyJson_restoresMarkerUuidAndOriginalWorkIdentity() {
        val file = write(
            "survey_export.json",
            """{"survey_id":"  survey-123  ","answer":"value"}"""
        )

        val recovery = PendingGitHubUploadRecovery.from(file)
        val data = Data.Builder().also { builder ->
            recovery.surveyId?.let { SurveyUploadWork.addSurveyJsonMetadata(builder, it) }
        }.build()
        val originalRemotePath = SurveyUploadWork.remoteRelativePath(file.name)

        assertEquals("survey-123", recovery.surveyId)
        assertEquals(originalRemotePath, recovery.remoteRelativePath)
        assertEquals(
            SurveyUploadWork.uniqueWorkName(originalRemotePath),
            recovery.uniqueWorkName(file)
        )
        assertEquals(
            GitHubUploadWorker.UPLOAD_KIND_SURVEY_JSON,
            data.getString(GitHubUploadWorker.KEY_UPLOAD_KIND)
        )
        assertEquals("survey-123", data.getString(GitHubUploadWorker.KEY_SURVEY_ID))
    }

    @Test
    fun genericOrMalformedJson_remainsUnmarked() {
        val generic = PendingGitHubUploadRecovery.from(write("generic.json", """{"answer":"value"}"""))
        val blankSurveyId = PendingGitHubUploadRecovery.from(write("blank.json", """{"survey_id":"  "}"""))
        val nestedSurveyId = PendingGitHubUploadRecovery.from(
            write("nested.json", """{"meta":{"survey_id":"not-top-level"}}""")
        )
        val malformed = PendingGitHubUploadRecovery.from(write("broken.json", "{not-json"))

        assertUnmarked(generic)
        assertUnmarked(blankSurveyId)
        assertUnmarked(nestedSurveyId)
        assertUnmarked(malformed)
    }

    @Test
    fun nonSurveyFiles_remainUnmarkedEvenWhenTheirContentLooksLikeSurveyJson() {
        listOf("voice.wav", "session.log", "crash.txt", "diagnostic.gz").forEach { name ->
            val recovery = PendingGitHubUploadRecovery.from(
                write(name, """{"survey_id":"must-not-count"}""")
            )

            assertUnmarked(recovery)
        }
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
    fun timestampFirstSurveyFile_usesSameNormalAndRecoveryWorkIdentity() {
        val file = write(
            "2026-09-22_11-37-48_survey_Pixel_9a_A13F82C4D9E1_survey-123_1.json",
            """{"survey_id":"survey-123"}"""
        )

        val recovery = PendingGitHubUploadRecovery.from(file)
        val normalRemotePath = SurveyUploadWork.remoteRelativePath(file.name)

        assertEquals("survey-123", recovery.surveyId)
        assertEquals(normalRemotePath, recovery.remoteRelativePath)
        assertEquals(
            SurveyUploadWork.uniqueWorkName(normalRemotePath),
            recovery.uniqueWorkName(file)
        )
    }

    private fun assertUnmarked(recovery: PendingGitHubUploadRecovery) {
        val data = Data.Builder().also { builder ->
            recovery.surveyId?.let { SurveyUploadWork.addSurveyJsonMetadata(builder, it) }
        }.build()

        assertNull(recovery.surveyId)
        assertNull(data.getString(GitHubUploadWorker.KEY_UPLOAD_KIND))
        assertNull(data.getString(GitHubUploadWorker.KEY_SURVEY_ID))
        assertTrue(recovery.remoteRelativePath.isNotBlank())
    }

    private fun write(name: String, content: String): File =
        File(tempDir, name).apply { writeText(content) }
}
