package com.negi.survey.net

import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.utils.V2ArtifactPaths
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val V2_VOICE_NAMESPACE = "v2_voice"
private const val VOICE_ROUTE_FORMAT = 1

/** Allocate once when a recording is finalized. Retries reuse the value stored with its route. */
@Serializable
@JvmInline
internal value class V2RecordingId(val value: String) {
    init {
        require(canonical.matches(value)) { "Invalid V2 RecordingID" }
    }

    companion object {
        private val canonical = Regex("[0-9a-f]{12}4[0-9a-f]{3}[89ab][0-9a-f]{15}")

        fun newForRecording(): V2RecordingId = V2RecordingId(UUID.randomUUID().toString().replace("-", ""))
    }
}

@Serializable
internal data class V2VoiceRoute(
    val formatVersion: Int,
    val surveyUuid: String,
    val questionId: String,
    val recordingId: V2RecordingId,
    val deviceTag: String,
    val basename: String,
    val localFileIdentity: String,
    val remoteRelativePath: String,
    val destination: V2SurveyJsonDestination,
    val wavSize: Long,
    val wavSha256: String
) {
    fun identitySha256(): String = SurveyUploadIdempotency.payloadFingerprint(Json.encodeToString(this))

    fun validate(expectedId: V2RecordingId = recordingId) {
        require(formatVersion == VOICE_ROUTE_FORMAT && recordingId == expectedId)
        require(destination.pathPrefix.isEmpty()) { "V2 WAV destinations must use the repository root" }
        require(wavSize > 44 && SurveyUploadIdempotency.isPayloadFingerprint(wavSha256))
        val naming = V2ArtifactPaths(surveyUuid, DeviceUploadTag(deviceTag))
        val path = naming.voiceWav(questionId, recordingId.value)
        require(surveyUuid == naming.surveyUuid)
        require(basename == path.basename && remoteRelativePath == path.relativePath)
        require(localFileIdentity == "$V2_VOICE_NAMESPACE/${recordingId.value}/$basename")
    }

    companion object {
        fun create(
            surveyId: String,
            questionId: String,
            recordingId: V2RecordingId,
            deviceTag: DeviceUploadTag,
            destination: V2SurveyJsonDestination,
            wav: V2WavFingerprint
        ): V2VoiceRoute {
            val naming = V2ArtifactPaths(surveyId, deviceTag)
            val path = naming.voiceWav(questionId, recordingId.value)
            return V2VoiceRoute(
                VOICE_ROUTE_FORMAT, naming.surveyUuid, questionId, recordingId, deviceTag.value,
                path.basename, "$V2_VOICE_NAMESPACE/${recordingId.value}/${path.basename}",
                path.relativePath, destination, wav.size, wav.sha256
            ).also { it.validate() }
        }
    }
}

internal data class V2WavFingerprint(val size: Long, val sha256: String) {
    fun matches(route: V2VoiceRoute): Boolean = size == route.wavSize && sha256 == route.wavSha256
}
