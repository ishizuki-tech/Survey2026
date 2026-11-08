package com.negi.survey.net

import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.utils.V2ArtifactPaths
import java.util.UUID
import kotlinx.serialization.Serializable

internal const val V2_DIAGNOSTIC_NAMESPACE = "v2_diagnostics"

@JvmInline
@Serializable
internal value class V2TraceId(val value: String) {
    init { require(value.matches(Regex("[0-9a-f]{32}"))) { "Invalid V2 trace ID" } }

    companion object {
        fun new(): V2TraceId = V2TraceId(UUID.randomUUID().toString().replace("-", ""))
    }
}

@Serializable
internal enum class V2DiagnosticStream { SURVEY, WHISPER, SLM }

@Serializable
internal data class V2SlmCorrelation(
    val questionId: String,
    val operation: String,
    val repositoryRequestId: Long
) {
    init {
        require(operation == "eval" || operation == "followup")
        require(repositoryRequestId > 0)
    }
}

/** Identity is persisted before accepting events. Trace ID also scopes process-local SLM IDs. */
@Serializable
internal data class V2DiagnosticCapture(
    val formatVersion: Int,
    val traceId: V2TraceId,
    val stream: V2DiagnosticStream,
    val surveyUuid: String,
    val deviceTag: String,
    val destination: V2SurveyJsonDestination,
    val slm: V2SlmCorrelation?
) {
    fun path(): V2ArtifactPaths.ArtifactPath {
        require(formatVersion == 1 && destination.pathPrefix.isEmpty()) { "V2 diagnostics require a repository-root route" }
        require((stream == V2DiagnosticStream.SLM) == (slm != null))
        val naming = V2ArtifactPaths(surveyUuid, DeviceUploadTag(deviceTag))
        require(naming.surveyUuid == surveyUuid)
        return when (stream) {
            V2DiagnosticStream.SURVEY -> naming.surveyTrace(traceId.value)
            V2DiagnosticStream.WHISPER -> naming.whisperTrace(traceId.value)
            V2DiagnosticStream.SLM -> {
                val request = checkNotNull(slm)
                naming.slmTrace(request.questionId, request.operation, "${traceId.value}-r${request.repositoryRequestId}")
            }
        }
    }

    companion object {
        fun create(
            surveyId: String,
            deviceTag: DeviceUploadTag,
            destination: V2SurveyJsonDestination,
            stream: V2DiagnosticStream,
            traceId: V2TraceId = V2TraceId.new(),
            slm: V2SlmCorrelation? = null
        ): V2DiagnosticCapture = V2DiagnosticCapture(
            1, traceId, stream, V2ArtifactPaths(surveyId, deviceTag).surveyUuid, deviceTag.value, destination, slm
        ).also { it.path() }
    }
}

@Serializable
internal data class V2DiagnosticRoute(
    val formatVersion: Int,
    val capture: V2DiagnosticCapture,
    val basename: String,
    val remoteRelativePath: String,
    val archiveSize: Long,
    val archiveSha256: String
) {
    fun validate(expected: V2DiagnosticCapture) {
        val path = capture.path()
        require(formatVersion == 1 && capture == expected)
        require(basename == path.basename && remoteRelativePath == path.relativePath)
        require(archiveSize > 0 && SurveyUploadIdempotency.isPayloadFingerprint(archiveSha256))
    }
}
