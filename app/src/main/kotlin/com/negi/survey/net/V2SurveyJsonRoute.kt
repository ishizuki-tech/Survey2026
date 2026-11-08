package com.negi.survey.net

import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.utils.V2ArtifactPaths
import kotlinx.serialization.Serializable

internal const val V2_JSON_NAMESPACE = "v2_survey_json"
internal const val V2_JSON_PROTOCOL = "immutable_v2"
private const val ROUTE_FORMAT_VERSION = 1
private val safePrefixSegment = Regex("[A-Za-z0-9._-]+")

@Serializable
internal data class V2SurveyJsonDestination(
    val identity: Identity,
    val owner: String,
    val repository: String,
    val branch: String,
    val pathPrefix: String
) {
    @Serializable
    enum class Identity(val repositoryName: String) {
        Production("SurveyExports"),
        Development("SurveyExports-Dev")
    }

    init {
        require(owner.matches(Regex("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*"))) { "Invalid GitHub owner" }
        require(repository.equals(identity.repositoryName, ignoreCase = true)) {
            "GitHub repository conflicts with the destination identity"
        }
        require(
            branch.isNotEmpty() && branch != "@" && branch != "HEAD" && !branch.startsWith('-') &&
                !branch.endsWith('.') && ".." !in branch && "@{" !in branch &&
                branch.none { it.isWhitespace() || it.isISOControl() || it in "~^:?*[\\" } &&
                branch.split('/').all { it.isNotEmpty() && !it.startsWith('.') && !it.endsWith(".lock") }
        ) { "Invalid GitHub branch" }
        require(pathPrefix.isEmpty() || pathPrefix.split('/').all {
            safePrefixSegment.matches(it) && it != "." && it != ".."
        }) { "Invalid GitHub path prefix" }
    }
}

/** The on-disk route binds exact JSON bytes and an app-files-relative identity to one GitHub target. */
@Serializable
internal data class V2SurveyJsonRoute(
    val formatVersion: Int,
    val protocol: String,
    val surveyUuid: String,
    val deviceTag: String,
    val stagedFilename: String,
    val localFileIdentity: String,
    val remoteRelativePath: String,
    val destination: V2SurveyJsonDestination,
    val payloadSha256: String
) {
    val remoteTargetPath: String
        get() = if (destination.pathPrefix.isEmpty()) remoteRelativePath
        else "${destination.pathPrefix}/$remoteRelativePath"

    /** Validates all derived fields, including the identity of the containing directory. */
    fun validate(expectedSurveyUuid: String = surveyUuid) {
        require(protocol == V2_JSON_PROTOCOL && formatVersion == ROUTE_FORMAT_VERSION) {
            "Unsupported V2 JSON route protocol or format"
        }
        val naming = V2ArtifactPaths(surveyUuid, DeviceUploadTag(deviceTag))
        val path = naming.surveyJson()
        require(surveyUuid == naming.surveyUuid && surveyUuid == expectedSurveyUuid)
        require(stagedFilename == path.basename && remoteRelativePath == path.relativePath)
        require(localFileIdentity == "$V2_JSON_NAMESPACE/$surveyUuid/$stagedFilename")
        require(SurveyUploadIdempotency.isPayloadFingerprint(payloadSha256))
    }

    companion object {
        fun create(
            surveyId: String,
            deviceTag: DeviceUploadTag,
            destination: V2SurveyJsonDestination,
            payloadSha256: String
        ): V2SurveyJsonRoute {
            val naming = V2ArtifactPaths(surveyId, deviceTag)
            val path = naming.surveyJson()
            return V2SurveyJsonRoute(
                formatVersion = ROUTE_FORMAT_VERSION,
                protocol = V2_JSON_PROTOCOL,
                surveyUuid = naming.surveyUuid,
                deviceTag = deviceTag.value,
                stagedFilename = path.basename,
                localFileIdentity = "$V2_JSON_NAMESPACE/${naming.surveyUuid}/${path.basename}",
                remoteRelativePath = path.relativePath,
                destination = destination,
                payloadSha256 = payloadSha256
            ).also { it.validate() }
        }
    }
}
