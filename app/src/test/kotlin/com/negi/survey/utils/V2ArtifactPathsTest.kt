package com.negi.survey.utils

import com.negi.survey.utils.V2ArtifactPaths.StandaloneDiagnostic
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ArtifactPathsTest {
    // 2026-11-07T23:59:59.999Z, with fixed version, variant, and random bits.
    private val surveyId = "01a1b874-6bff-7abc-9def-0123456789ab"
    private val tag = DeviceUploadTag("Pixel_9a_A13F82C4D9E1")
    private val date = "2026-11-07"
    private val stamp = "2026-11-07_23-59-59Z"
    private val paths = V2ArtifactPaths(surveyId, tag)

    @Test
    fun decodesKnownUuidv7ToUtcDateAndStamp() {
        assertEquals(date, paths.dateUtc)
        assertEquals(stamp, paths.startStampUtc)
    }

    @Test
    fun buildsExactSurveyJsonNameAndPath() {
        assertArtifact(
            paths.surveyJson(), "$date/survey",
            "${stamp}_survey_Pixel_9a_A13F82C4D9E1_$surveyId.json"
        )
    }

    @Test
    fun buildsExactVoiceNameAndPath() {
        assertArtifact(
            paths.voiceWav("Q11", "rec-01"), "$date/voice",
            "${stamp}_voice_Pixel_9a_A13F82C4D9E1_${surveyId}_Q11_rec-01.wav"
        )
    }

    @Test
    fun buildsExactTraceNamesAndPaths() {
        assertArtifact(
            paths.surveyTrace("trace-01"), "$date/diagnostics/survey",
            "${stamp}_survey_Pixel_9a_A13F82C4D9E1_${surveyId}_trace-01.ndjson.gz"
        )
        assertArtifact(
            paths.whisperTrace("trace-02"), "$date/diagnostics/whisper",
            "${stamp}_whisper_Pixel_9a_A13F82C4D9E1_${surveyId}_trace-02.ndjson.gz"
        )
        assertArtifact(
            paths.slmTrace("Q11", "EVAL", "req-01"), "$date/diagnostics/slm",
            "${stamp}_slm_Pixel_9a_A13F82C4D9E1_${surveyId}_Q11_eval_req-01.ndjson.gz"
        )
        assertArtifact(
            paths.slmTrace("Q11", "FollowUp", "req-02"), "$date/diagnostics/slm",
            "${stamp}_slm_Pixel_9a_A13F82C4D9E1_${surveyId}_Q11_followup_req-02.ndjson.gz"
        )
    }

    @Test
    fun normalizesUppercaseUuid() {
        val uppercase = V2ArtifactPaths(surveyId.uppercase(Locale.ROOT), tag)
        assertEquals(surveyId, uppercase.surveyUuid)
        assertEquals(paths.surveyJson().relativePath, uppercase.surveyJson().relativePath)
    }

    @Test
    fun rejectsUuidv4() {
        assertThrows(IllegalArgumentException::class.java) {
            V2ArtifactPaths("01a1b874-6bff-4abc-9def-0123456789ab", tag)
        }
    }

    @Test
    fun rejectsMalformedTruncatedBlankAndWrongVariantUuids() {
        listOf(
            "not-a-uuid", surveyId.dropLast(1), "", " ", " $surveyId",
            "01a1b874-6bff-7abc-7def-0123456789ab",
            "01a1b874-6bff-7abc-cdef-0123456789ab",
            "01a1b874-6bff-7abc-9def-0123456789ag"
        ).forEach { invalid ->
            assertThrows(invalid, IllegalArgumentException::class.java) {
                V2ArtifactPaths(invalid, tag)
            }
        }
    }

    @Test
    fun rejectsUnsafeDeviceAndDetailSegments() {
        listOf("", " ", "a/b", "a\\b", ".", "..", "a..b", "a%2Fb", "a\u0000b", "a b").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                V2ArtifactPaths(surveyId, DeviceUploadTag(invalid))
            }
            assertThrows(IllegalArgumentException::class.java) { paths.voiceWav(invalid, "rec-01") }
            assertThrows(IllegalArgumentException::class.java) { paths.voiceWav("Q11", invalid) }
            assertThrows(IllegalArgumentException::class.java) { paths.surveyTrace(invalid) }
            assertThrows(IllegalArgumentException::class.java) { paths.whisperTrace(invalid) }
            assertThrows(IllegalArgumentException::class.java) { paths.slmTrace(invalid, "eval", "req-01") }
            assertThrows(IllegalArgumentException::class.java) { paths.slmTrace("Q11", "eval", invalid) }
            assertThrows(IllegalArgumentException::class.java) {
                standalone(StandaloneDiagnostic.LOGCAT, artifactId = invalid)
            }
            assertThrows(IllegalArgumentException::class.java) {
                standalone(StandaloneDiagnostic.LOGCAT, device = DeviceUploadTag(invalid))
            }
        }
    }

    @Test
    fun rejectsUnknownSlmOperations() {
        listOf("", " eval ", "evaluate", "follow-up", "../eval").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { paths.slmTrace("Q11", invalid, "req-01") }
        }
    }

    @Test
    fun allSurveyBoundArtifactsShareDateStampAndUuidAndAreRepeatable() {
        val first = surveyArtifacts(paths)
        val repeated = surveyArtifacts(V2ArtifactPaths(surveyId, tag))
        assertEquals(first.map { it.relativePath }, repeated.map { it.relativePath })
        first.forEach { artifact ->
            assertEquals(date, artifact.relativePath.substringBefore('/'))
            assertTrue(artifact.basename.startsWith("${stamp}_"))
            assertTrue(artifact.basename.contains("_${tag.value}_$surveyId"))
        }
    }

    @Test
    fun distinctRecordingTraceAndRequestIdsStayDistinctWithoutTruncation() {
        val prefix = "x".repeat(80)
        val firstId = "${prefix}1"
        val secondId = "${prefix}2"
        assertNotEquals(paths.voiceWav("Q11", firstId).basename, paths.voiceWav("Q11", secondId).basename)
        assertNotEquals(paths.surveyTrace(firstId).basename, paths.surveyTrace(secondId).basename)
        assertNotEquals(paths.whisperTrace(firstId).basename, paths.whisperTrace(secondId).basename)
        assertNotEquals(
            paths.slmTrace("Q11", "eval", firstId).basename,
            paths.slmTrace("Q11", "eval", secondId).basename
        )
    }

    @Test
    fun utcMidnightChangesTheDateOnlyForASurveyStartedOnTheNewDay() {
        val finalizedAt = Instant.parse("2026-11-08T00:00:00Z")
        val startedAtMidnight = V2ArtifactPaths("01a1b874-6c00-7abc-9def-0123456789ab", tag)
        assertEquals("2026-11-08", startedAtMidnight.dateUtc)
        assertEquals("2026-11-08_00-00-00Z", startedAtMidnight.startStampUtc)
        assertTrue(standalone(StandaloneDiagnostic.RUNTIME, finalizedAt).relativePath.startsWith("2026-11-08/"))

        // Finalization time is separate from the UUID used to derive survey names.
        val finalizedSurvey = V2ArtifactPaths(surveyId, tag)
        assertEquals(paths.surveyJson().relativePath, finalizedSurvey.surveyJson().relativePath)
        assertEquals(date, finalizedSurvey.dateUtc)
    }

    @Test
    fun timezoneLocaleAndDstDoNotAffectPaths() {
        val beforeDst = "019ccce3-ad18-7abc-9def-0123456789ab"
        val afterDst = "019ccce3-b100-7abc-9def-0123456789ab"
        val originalZone = TimeZone.getDefault()
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            listOf("America/Los_Angeles", "Pacific/Auckland").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals("2026-03-08_09-59-59Z", V2ArtifactPaths(beforeDst, tag).startStampUtc)
                assertEquals("2026-03-08_10-00-00Z", V2ArtifactPaths(afterDst, tag).startStampUtc)
                assertEquals(paths.surveyJson().relativePath, V2ArtifactPaths(surveyId, tag).surveyJson().relativePath)
            }
        } finally {
            TimeZone.setDefault(originalZone)
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun fullUuidDistinguishesMillisecondsWithinTheSameSecond() {
        val previousMillisId = "01a1b874-6bfe-7abc-9def-0123456789ab"
        val previousMillis = V2ArtifactPaths(previousMillisId, tag)
        assertEquals(paths.startStampUtc, previousMillis.startStampUtc)
        assertNotEquals(paths.surveyJson().basename, previousMillis.surveyJson().basename)
        assertTrue(previousMillis.surveyJson().basename.endsWith("_$previousMillisId.json"))
    }

    @Test
    fun rejectsTimestampsOutsideFourDigitYearRange() {
        val lastYear = V2ArtifactPaths("e677d21f-d818-7abc-9def-0123456789ab", tag)
        assertEquals("9999-12-31_23-59-59Z", lastYear.startStampUtc)
        assertThrows(IllegalArgumentException::class.java) {
            V2ArtifactPaths("ffffffff-ffff-7abc-9def-0123456789ab", tag)
        }
        listOf(Instant.MIN, Instant.MAX).forEach { timestamp ->
            assertThrows(IllegalArgumentException::class.java) {
                standalone(StandaloneDiagnostic.CRASH, timestamp)
            }
        }
    }

    @Test
    fun standaloneDiagnosticsUseExplicitEventTimeAndStableIdentity() {
        assertArtifact(
            standalone(StandaloneDiagnostic.LOGCAT, artifactId = "capture-01"),
            "2026-11-08/diagnostics/logcat",
            "2026-11-08_00-00-01Z_logcat_Pixel_9a_A13F82C4D9E1_no-survey_capture-01.log.gz"
        )
        assertArtifact(
            standalone(StandaloneDiagnostic.RUNTIME, artifactId = "segment-02"),
            "2026-11-08/diagnostics/runtime",
            "2026-11-08_00-00-01Z_runtime_Pixel_9a_A13F82C4D9E1_no-survey_segment-02.log"
        )
        assertArtifact(
            standalone(StandaloneDiagnostic.CRASH, artifactId = "crash-03"),
            "2026-11-08/diagnostics/crash",
            "2026-11-08_00-00-01Z_crash_Pixel_9a_A13F82C4D9E1_no-survey_crash-03.log"
        )
        assertNotEquals(
            standalone(StandaloneDiagnostic.LOGCAT, artifactId = "capture-01").basename,
            standalone(StandaloneDiagnostic.LOGCAT, artifactId = "capture-02").basename
        )
    }

    @Test
    fun surveyBoundBasenameEnforcesUtf8ByteLimit() {
        val prefix = "${stamp}_voice_${tag.value}_${surveyId}_Q11_"
        val extension = ".wav"
        val recordingId = "r".repeat(255 - (prefix + extension).toByteArray(Charsets.UTF_8).size)

        val artifact = paths.voiceWav("Q11", recordingId)
        assertEquals(255, artifact.basename.toByteArray(Charsets.UTF_8).size)
        assertArtifact(artifact, "$date/voice", "$prefix$recordingId$extension")
        assertThrows(IllegalArgumentException::class.java) {
            paths.voiceWav("Q11", recordingId + "r")
        }
    }

    @Test
    fun standaloneBasenameEnforcesUtf8ByteLimit() {
        val prefix = "2026-11-08_00-00-01Z_logcat_${tag.value}_no-survey_"
        val extension = ".log.gz"
        val captureId = "c".repeat(255 - (prefix + extension).toByteArray(Charsets.UTF_8).size)

        val artifact = standalone(StandaloneDiagnostic.LOGCAT, artifactId = captureId)
        assertEquals(255, artifact.basename.toByteArray(Charsets.UTF_8).size)
        assertArtifact(artifact, "2026-11-08/diagnostics/logcat", "$prefix$captureId$extension")
        assertThrows(IllegalArgumentException::class.java) {
            standalone(StandaloneDiagnostic.LOGCAT, artifactId = captureId + "c")
        }
    }

    private fun surveyArtifacts(survey: V2ArtifactPaths) = listOf(
        survey.surveyJson(),
        survey.voiceWav("Q11", "rec-01"),
        survey.surveyTrace("trace-01"),
        survey.whisperTrace("trace-01"),
        survey.slmTrace("Q11", "eval", "req-01")
    )

    private fun standalone(
        kind: StandaloneDiagnostic,
        timestamp: Instant = Instant.parse("2026-11-08T00:00:01.321Z"),
        artifactId: String = "event-01",
        device: DeviceUploadTag = tag
    ) = V2ArtifactPaths.standaloneDiagnostic(kind, timestamp, device, artifactId)

    private fun assertArtifact(artifact: V2ArtifactPaths.ArtifactPath, directory: String, basename: String) {
        assertEquals(basename, artifact.basename)
        assertEquals("$directory/$basename", artifact.relativePath)
    }
}
