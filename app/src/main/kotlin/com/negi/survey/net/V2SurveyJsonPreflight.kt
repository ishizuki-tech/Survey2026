package com.negi.survey.net

import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_SURVEY_ID
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_SURVEY_UPLOAD_PROTOCOL
import com.negi.survey.net.GitHubUploadWorker.Companion.SURVEY_UPLOAD_PROTOCOL_CANONICAL_IMMUTABLE_V1
import java.io.File
import java.io.IOException

internal enum class SurveyWorkerProtocol {
    EXISTING_UNMARKED, CANONICAL_V1, IMMUTABLE_V2, UNSUPPORTED
}

/** Historical requests have no marker. V2-specific keys may never enter a historical branch. */
internal fun surveyWorkerProtocol(input: Map<String, Any?>): SurveyWorkerProtocol {
    val marker = input[KEY_SURVEY_UPLOAD_PROTOCOL]
    if (marker == V2_JSON_PROTOCOL) return SurveyWorkerProtocol.IMMUTABLE_V2
    if (input.keys.any { it.startsWith("v2.") }) return SurveyWorkerProtocol.UNSUPPORTED
    return when {
        marker == SURVEY_UPLOAD_PROTOCOL_CANONICAL_IMMUTABLE_V1 -> SurveyWorkerProtocol.CANONICAL_V1
        (marker is String && marker.isBlank()) || KEY_SURVEY_UPLOAD_PROTOCOL !in input ->
            SurveyWorkerProtocol.EXISTING_UNMARKED
        else -> SurveyWorkerProtocol.UNSUPPORTED
    }
}

internal fun allowsV2Destination(destination: V2SurveyJsonDestination, localOrDebugBuild: Boolean): Boolean =
    !(localOrDebugBuild && destination.identity == V2SurveyJsonDestination.Identity.Production) &&
        GitHubUploadTargetPolicy.allowsCapturedRouting(localOrDebugBuild, destination.owner, destination.repository)

/** A generic request cannot bypass V2 ownership by supplying an app-private V2 staging path. */
internal fun isV2StagedFile(appFilesDir: File, file: File): Boolean {
    val root = appFilesDir.canonicalFile.toPath()
    val canonical = file.canonicalFile.toPath()
    val local = file.toPath().toAbsolutePath().normalize()
    return listOf(V2_JSON_NAMESPACE, V2_VOICE_NAMESPACE, V2_DIAGNOSTIC_NAMESPACE).any { namespace ->
        val staged = root.resolve(namespace)
        canonical.startsWith(staged) || local.startsWith(staged)
    }
}

internal class V2SurveyJsonPreflight(
    appFilesDir: File,
    private val localOrDebugBuild: Boolean
) {
    private val store = V2SurveyJsonRouteStore(appFilesDir)
    private val stager = V2SurveyJsonStager(store)

    sealed interface Result {
        data class Validated(val route: V2SurveyJsonRoute, val stagedFile: File) : Result {
            val remoteTargetPath: String get() = route.remoteTargetPath
            val payloadSha256: String get() = route.payloadSha256
            val destinationIdentity: V2SurveyJsonDestination.Identity get() = route.destination.identity
        }

        data class Rejected(val reason: String) : Result
    }

    /** Phase 3 entry point. The only path returned is the verified route's frozen target. */
    fun validate(input: Map<String, Any?>): Result {
        if (surveyWorkerProtocol(input) != SurveyWorkerProtocol.IMMUTABLE_V2) {
            return Result.Rejected("Unsupported V2 survey protocol")
        }
        val surveyId = input[KEY_SURVEY_ID] as? String
            ?: return Result.Rejected("Missing V2 survey UUID")
        val staged = try {
            stager.inspectReady(surveyId)
        } catch (_: IllegalArgumentException) {
            return Result.Rejected("Invalid V2 survey UUID")
        } catch (_: IOException) {
            return Result.Rejected("V2 route or payload could not be read")
        }
        return when (staged) {
            is V2SurveyJsonStager.Result.Unavailable ->
                Result.Rejected("V2 staged payload is unavailable: ${staged.state}")
            is V2SurveyJsonStager.Result.Ready -> when {
                input != V2SurveyJsonWork.metadata(staged.route) ->
                    Result.Rejected("V2 Worker metadata does not match the immutable route")
                !allowsV2Destination(staged.route.destination, localOrDebugBuild) ->
                    Result.Rejected("V2 destination is prohibited in this build")
                else -> Result.Validated(staged.route, staged.file)
            }
        }
    }

    /** Rechecks the route and snapshots the exact verified content under the staging lock. */
    fun readVerifiedPayload(checked: Result.Validated): String? = store.withSurvey(checked.route.surveyUuid) {
        val route = checked.route
        val file = directory.resolve(route.stagedFilename)
        if (read() != V2SurveyJsonRouteStore.ReadResult.Pending(route) ||
            names() != setOf(V2_PENDING_ROUTE, route.stagedFilename) ||
            file.toFile() != checked.stagedFile
        ) return@withSurvey null
        readVerifiedV2Json(file, route)
    }
}
