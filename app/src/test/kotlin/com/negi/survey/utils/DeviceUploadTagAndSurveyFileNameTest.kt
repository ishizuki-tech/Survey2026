package com.negi.survey.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceUploadTagAndSurveyFileNameTest {
    private val surveyId = "1ab2b0d7-1005-4f13-a236-a9f361c72158"
    private val tag = DeviceUploadTag("Pixel_9a_A13F82C4D9E1")
    private val stamp = "2026-09-22_11-37-48"

    @Test
    fun deviceTag_isDeterministicSafeAndDoesNotExposeRawAndroidId() {
        val rawId = "raw-android-id-123"
        val first = DeviceUploadTagFormatter.format("Pixel 9a/Pro", rawId)
        val second = DeviceUploadTagFormatter.format("Pixel 9a/Pro", rawId)
        val different = DeviceUploadTagFormatter.format("Pixel 9a/Pro", "other-id")

        assertEquals(first, second)
        assertNotEquals(first, different)
        assertEquals("Pixel_9a_Pro_ADAEEDB9AAC3", first.value)
        assertTrue(first.value.startsWith("Pixel_9a_Pro_"))
        assertTrue(first.value.substringAfterLast('_').matches(Regex("[0-9A-F]{12}")))
        assertFalse(first.value.contains(rawId))
    }

    @Test
    fun deviceTag_usesUnknownForBlankIdAndSafeFallbackModel() {
        assertEquals("Pixel_9a_UNKNOWN", DeviceUploadTagFormatter.format(" Pixel 9a ", " ").value)
        assertEquals("unknown_UNKNOWN", DeviceUploadTagFormatter.format("///", null).value)
    }

    @Test
    fun tagAwareFilenames_areTimestampFirstAndDeterministic() {
        assertEquals(
            "2026-09-22_11-37-48_survey_Pixel_9a_A13F82C4D9E1_$surveyId.json",
            buildSurveyFileName(surveyId, tag, stamp = stamp)
        )
        assertEquals(
            "2026-09-22_11-37-48_voice_Pixel_9a_A13F82C4D9E1_${surveyId}_Q11.wav",
            buildVoiceFileName(surveyId, "Q11", tag, stamp = stamp)
        )
        assertEquals(
            "2026-09-22_11-37-48_survey_Pixel_9a_UNKNOWN_$surveyId.json",
            buildSurveyFileName(surveyId, DeviceUploadTag("Pixel_9a_UNKNOWN"), stamp = stamp)
        )
    }

    @Test
    fun legacyFilenameOverloads_remainTimestampLast() {
        assertEquals(
            "survey_${surveyId}_2026-09-22_11-37-48.json",
            buildSurveyFileName(surveyId, stamp = stamp)
        )
        assertEquals(
            "voice_${surveyId}_Q11_2026-09-22_11-37-48.wav",
            buildVoiceFileName(surveyId, "Q11", stamp = stamp)
        )
    }
}
