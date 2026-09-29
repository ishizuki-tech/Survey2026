package com.negi.survey.net

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticTraceArtifactsTest {
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("diagnostic-trace-artifacts-test").toFile()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun finalizedSurveyAndWhisperTracesAreEligibleAndKeepTheirNamesInExports() {
        val survey = write(
            "diagnostics/survey_traces/20260929-174437_Device_c7a928c2_survey.ndjson.gz"
        )
        val whisper = write(
            "diagnostics/whisper_trace/20260929-174455_Device_c7a928c2_whisper.ndjson.gz"
        )

        val candidates = DiagnosticTraceArtifacts.discover(filesDir, "c7a928c2-full-survey-id")

        assertEquals(setOf(survey, whisper), candidates.map { it.file }.toSet())
        assertEquals(
            "exports/${survey.name}",
            DiagnosticTraceArtifacts.remoteRelativePath(candidates.single { it.file == survey })
        )
        assertEquals(
            "exports/${whisper.name}",
            DiagnosticTraceArtifacts.remoteRelativePath(candidates.single { it.file == whisper })
        )
    }

    @Test
    fun activeOpenTraceIsNeverAnUploadCandidate() {
        val open = write("diagnostics/survey_traces/20260929_Device_c7a928c2_survey.ndjson.open")
        val candidates = DiagnosticTraceArtifacts.discover(filesDir)

        assertTrue(candidates.isEmpty())
        assertTrue(open.exists())
    }

    @Test
    fun recoveryDiscoversFinalizedTracesForDifferentSurveysAndKeepsStreamsIndependent() {
        val firstSurvey = write("diagnostics/survey_traces/a_Device_aaaaaaaa_survey.ndjson.gz")
        val firstWhisper = write("diagnostics/whisper_trace/b_Device_aaaaaaaa_whisper.ndjson.gz")
        val secondSurvey = write("diagnostics/survey_traces/c_Device_bbbbbbbb_survey.ndjson.gz")

        val all = DiagnosticTraceArtifacts.discover(filesDir)
        val firstOnly = DiagnosticTraceArtifacts.discover(filesDir, "aaaaaaaa-restored-survey")

        assertEquals(setOf(firstSurvey, firstWhisper, secondSurvey), all.map { it.file }.toSet())
        assertEquals(setOf(firstSurvey, firstWhisper), firstOnly.map { it.file }.toSet())

        // GitHubUploadWorker deletes the local file only after confirmed success. Its absence is
        // therefore the existing acknowledgement that prevents a later recovery from re-uploading.
        assertTrue(firstSurvey.delete())
        assertFalse(DiagnosticTraceArtifacts.discover(filesDir).any { it.file.name == firstSurvey.name })
        assertTrue(DiagnosticTraceArtifacts.discover(filesDir).any { it.file == firstWhisper })
    }

    private fun write(relativePath: String): File = File(filesDir, relativePath).apply {
        parentFile!!.mkdirs()
        writeText("trace")
    }
}
