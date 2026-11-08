package com.negi.survey.utils

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val canonicalUuidV7 =
    Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-7[0-9A-Fa-f]{3}-[89ABab][0-9A-Fa-f]{3}-[0-9A-Fa-f]{12}")
private val filenameSegment = Regex("[A-Za-z0-9._-]+")
private val utcStampFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss'Z'", Locale.ROOT)
private const val UTC_DATE_LENGTH = 10
private const val MAX_BASENAME_UTF8_BYTES = 255

/** Pure naming context for one survey. All paths use the start time encoded in its UUIDv7. */
internal class V2ArtifactPaths(surveyId: String, deviceTag: DeviceUploadTag) {
    val surveyUuid: String = normalizeSurveyUuid(surveyId)
    private val device = requireFilenameSegment(deviceTag.value, "deviceTag")

    val startStampUtc: String = formatUtcStamp(requireNotNull(surveyIdTimestampMillis(surveyUuid)))
    val dateUtc: String = startStampUtc.take(UTC_DATE_LENGTH)

    /** The basename and its remote path, relative to the configured export root. */
    class ArtifactPath internal constructor(
        val basename: String,
        val relativePath: String
    ) {
        init {
            require(basename.toByteArray(Charsets.UTF_8).size <= MAX_BASENAME_UTF8_BYTES) {
                "Artifact basename must be at most $MAX_BASENAME_UTF8_BYTES UTF-8 bytes"
            }
        }
    }

    fun surveyJson(): ArtifactPath = artifactPath("survey", "${prefix("survey")}.json")

    fun voiceWav(questionId: String, recordingId: String): ArtifactPath {
        val question = requireFilenameSegment(questionId, "questionId")
        val recording = requireFilenameSegment(recordingId, "recordingId")
        return artifactPath("voice", "${prefix("voice")}_${question}_$recording.wav")
    }

    fun surveyTrace(traceId: String): ArtifactPath = trace("survey", traceId)

    fun whisperTrace(traceId: String): ArtifactPath = trace("whisper", traceId)

    fun slmTrace(questionId: String, operation: String, requestId: String): ArtifactPath {
        val question = requireFilenameSegment(questionId, "questionId")
        val request = requireFilenameSegment(requestId, "requestId")
        val normalizedOperation = operation.lowercase(Locale.ROOT)
        require(normalizedOperation == "eval" || normalizedOperation == "followup") {
            "SLM operation must be eval or followup"
        }
        return artifactPath(
            "diagnostics/slm",
            "${prefix("slm")}_${question}_${normalizedOperation}_$request.ndjson.gz"
        )
    }

    private fun trace(type: String, traceId: String): ArtifactPath {
        val trace = requireFilenameSegment(traceId, "traceId")
        return artifactPath("diagnostics/$type", "${prefix(type)}_$trace.ndjson.gz")
    }

    private fun prefix(type: String): String = "${startStampUtc}_${type}_${device}_$surveyUuid"

    private fun artifactPath(directory: String, basename: String): ArtifactPath =
        ArtifactPath(basename, "$dateUtc/$directory/$basename")

    enum class StandaloneDiagnostic(val pathSegment: String, val extension: String) {
        LOGCAT("logcat", "log.gz"),
        RUNTIME("runtime", "log"),
        CRASH("crash", "log")
    }

    companion object {
        /** Uses the explicit capture, segment, or crash time for diagnostics without a survey. */
        fun standaloneDiagnostic(
            kind: StandaloneDiagnostic,
            eventTimestamp: Instant,
            deviceTag: DeviceUploadTag,
            artifactId: String
        ): ArtifactPath {
            val stamp = formatUtcStamp(eventTimestamp)
            val date = stamp.take(UTC_DATE_LENGTH)
            val device = requireFilenameSegment(deviceTag.value, "deviceTag")
            val id = requireFilenameSegment(artifactId, "artifactId")
            val type = kind.pathSegment
            val basename = "${stamp}_${type}_${device}_no-survey_$id.${kind.extension}"
            return ArtifactPath(basename, "$date/diagnostics/$type/$basename")
        }
    }
}

private fun normalizeSurveyUuid(surveyId: String): String {
    require(canonicalUuidV7.matches(surveyId)) { "Survey ID must be a canonical RFC 9562 UUIDv7" }
    return surveyId.lowercase(Locale.ROOT)
}

private fun requireFilenameSegment(value: String, label: String): String {
    require(filenameSegment.matches(value) && value != "." && ".." !in value) {
        "$label must be a nonblank filename-safe segment"
    }
    return value
}

private fun formatUtcStamp(timestampMillis: Long): String =
    formatUtcStamp(Instant.ofEpochMilli(timestampMillis))

private fun formatUtcStamp(timestamp: Instant): String {
    // Keep the contract's four-digit year, including for standalone timestamps.
    require(timestamp >= minUtcTimestamp && timestamp < utcTimestampLimit) {
        "Timestamp must have a UTC year from 0001 through 9999"
    }
    return utcStampFormatter.format(timestamp.atOffset(ZoneOffset.UTC))
}

private val minUtcTimestamp = Instant.parse("0001-01-01T00:00:00Z")
private val utcTimestampLimit = Instant.parse("+10000-01-01T00:00:00Z")
