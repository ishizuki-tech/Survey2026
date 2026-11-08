package com.negi.survey.net

import com.negi.survey.net.V2SurveyJsonRouteStore.ReadResult
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.utils.V2ArtifactPaths
import com.negi.survey.vm.SurveyFinalizationSnapshot
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class V2JsonRecoveryState {
    ABSENT,
    BLOCKED_MISSING_ROUTE,
    BLOCKED_INVALID_ROUTE,
    BLOCKED_MISSING_PAYLOAD,
    BLOCKED_VOICE_REFERENCES,
    CONFLICT_UNEXPECTED_FILES,
    CONFLICT_PAYLOAD,
    CONFLICT_ROUTE,
    PUBLISH_TEMPORARY,
    READY,
    DELIVERED
}

/** Pure policy for classifying persisted evidence before taking any publication action. */
internal fun classifyV2JsonRecovery(
    route: ReadResult,
    filenames: Set<String>,
    payloadMatches: Boolean
): V2JsonRecoveryState = when (route) {
    ReadResult.Missing -> if (filenames.isEmpty()) V2JsonRecoveryState.ABSENT
    else V2JsonRecoveryState.BLOCKED_MISSING_ROUTE
    is ReadResult.Invalid -> V2JsonRecoveryState.BLOCKED_INVALID_ROUTE
    is ReadResult.Delivered -> V2JsonRecoveryState.DELIVERED
    is ReadResult.Pending -> when (filenames) {
        setOf(V2_PENDING_ROUTE) -> V2JsonRecoveryState.BLOCKED_MISSING_PAYLOAD
        setOf(V2_PENDING_ROUTE, V2_PAYLOAD_TEMP) ->
            if (payloadMatches) V2JsonRecoveryState.PUBLISH_TEMPORARY else V2JsonRecoveryState.CONFLICT_PAYLOAD
        setOf(V2_PENDING_ROUTE, route.route.stagedFilename) ->
            if (payloadMatches) V2JsonRecoveryState.READY else V2JsonRecoveryState.CONFLICT_PAYLOAD
        else -> V2JsonRecoveryState.CONFLICT_UNEXPECTED_FILES
    }
}

/** Phase 2A's local staging boundary. Call on an I/O thread. Storage failures propagate to the caller. */
internal class V2SurveyJsonStager(
    private val store: V2SurveyJsonRouteStore,
    private val serialize: (SurveyFinalizationSnapshot, String) -> String = SurveyExportJsonBuilder::build
) {
    sealed interface Result {
        data class Ready(val route: V2SurveyJsonRoute, val file: File, val reused: Boolean) : Result
        data class Unavailable(val state: V2JsonRecoveryState) : Result
    }

    /**
     * An existing route makes this a retry. Only the original bytes may be recovered, even when
     * the snapshot or exportedAtStamp has since changed.
     */
    fun stage(
        snapshot: SurveyFinalizationSnapshot,
        deviceTag: DeviceUploadTag,
        destination: V2SurveyJsonDestination,
        exportedAtStamp: String
    ): Result {
        val naming = V2ArtifactPaths(snapshot.surveyId, deviceTag)
        naming.surveyJson()
        return store.withSurvey(naming.surveyUuid) {
            val current = read()
            if (current is ReadResult.Pending &&
                (current.route.deviceTag != deviceTag.value || current.route.destination != destination)
            ) {
                return@withSurvey Result.Unavailable(V2JsonRecoveryState.CONFLICT_ROUTE)
            }
            if (current != ReadResult.Missing || names().isNotEmpty()) {
                val result = recoverEntry(current, reused = true)
                return@withSurvey if (result is Result.Ready && !verifyPersistedVoiceReferences(result)) {
                    Result.Unavailable(V2JsonRecoveryState.BLOCKED_VOICE_REFERENCES)
                } else result
            }

            val voiceStore = V2VoiceRouteStore(store.appFilesDir)
            if (snapshot.audioRefs.any { ref ->
                    !ref.surveyId.equals(surveyUuid, ignoreCase = true) ||
                        !voiceStore.verifyReference(
                            surveyUuid, ref.questionId, ref.fileName, destination, ref.byteSize, ref.checksum
                        )
                }
            ) return@withSurvey Result.Unavailable(V2JsonRecoveryState.BLOCKED_VOICE_REFERENCES)

            val bytes = serialize(snapshot, exportedAtStamp).toByteArray(Charsets.UTF_8)
            require(decodeV2Payload(bytes, surveyUuid) != null) { "Invalid finalized V2 survey JSON" }
            val route = V2SurveyJsonRoute.create(
                surveyUuid, deviceTag, destination, SurveyUploadIdempotency.payloadFingerprint(bytes)
            )
            writeSynced(V2_PAYLOAD_TEMP, bytes, V2JsonStagingCheckpoint.PAYLOAD_WRITTEN_BEFORE_SYNC)
            createIfAbsent(route, preparedPayload = true)
            recoverEntry(read(), reused = false)
        }
    }

    private fun verifyPersistedVoiceReferences(staged: Result.Ready): Boolean {
        val route = staged.route
        val content = readVerifiedV2Json(staged.file.toPath(), route) ?: return false
        val json = Json.parseToJsonElement(content) as? JsonObject ?: return false
        val references = json["voice_files"] as? JsonArray ?: return false
        val voiceStore = V2VoiceRouteStore(store.appFilesDir)
        return references.all { reference ->
            val ref = reference as? JsonObject ?: return@all false
            val questionId = ref.string("question_id") ?: return@all false
            val basename = ref.string("file") ?: return@all false
            ref.string("survey_id").equals(route.surveyUuid, ignoreCase = true) &&
                voiceStore.verifyReference(route.surveyUuid, questionId, basename, route.destination)
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Recovers only from on-disk evidence, without a snapshot or current destination. */
    fun recover(surveyId: String): Result = store.withSurvey(surveyId) {
        recoverEntry(read(), reused = true)
    }

    /** Worker inspection requires a finalized file and never publishes a temporary payload. */
    fun inspectReady(surveyId: String): Result = store.withSurvey(surveyId) {
        recoverEntry(read(), reused = true, publishTemporary = false)
    }

    private fun V2SurveyJsonRouteStore.Entry.recoverEntry(
        current: ReadResult,
        reused: Boolean,
        publishTemporary: Boolean = true
    ): Result {
        val filenames = if (current is ReadResult.Invalid) emptySet() else names()
        val pending = (current as? ReadResult.Pending)?.route
        val payloadMatches = pending != null && when (filenames) {
            setOf(V2_PENDING_ROUTE, V2_PAYLOAD_TEMP) -> matches(directory.resolve(V2_PAYLOAD_TEMP), pending)
            setOf(V2_PENDING_ROUTE, pending.stagedFilename) -> matches(directory.resolve(pending.stagedFilename), pending)
            else -> false
        }
        return when (val state = classifyV2JsonRecovery(current, filenames, payloadMatches)) {
            V2JsonRecoveryState.PUBLISH_TEMPORARY -> {
                if (!publishTemporary) return Result.Unavailable(state)
                val route = checkNotNull(pending)
                // A failed directory sync may have left a visible route. Make it durable before
                // publishing the payload, and repeat the payload sync before recovery publication.
                syncDirectory(directory)
                FileChannel.open(directory.resolve(V2_PAYLOAD_TEMP), WRITE).use { it.force(true) }
                publish(V2_PAYLOAD_TEMP, route.stagedFilename)
                payloadPublished()
                recoverEntry(read(), reused)
            }
            V2JsonRecoveryState.READY -> {
                val route = checkNotNull(pending)
                syncDirectory(directory)
                Result.Ready(route, directory.resolve(route.stagedFilename).toFile(), reused)
            }
            else -> Result.Unavailable(state)
        }
    }

    private fun matches(file: Path, route: V2SurveyJsonRoute): Boolean =
        readVerifiedV2Json(file, route) != null
}

/** Re-read and verify the exact UTF-8 content handed to transport. */
internal fun readVerifiedV2Json(file: Path, route: V2SurveyJsonRoute): String? {
    if (file.attributesOrNull()?.isRegularFile != true) return null
    val bytes = Files.readAllBytes(file)
    if (SurveyUploadIdempotency.payloadFingerprint(bytes) != route.payloadSha256) return null
    return decodeV2Payload(bytes, route.surveyUuid)
}

private fun decodeV2Payload(bytes: ByteArray, surveyUuid: String): String? {
    val content = try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        return null
    }
    val json = try {
        Json.parseToJsonElement(content) as? JsonObject
    } catch (_: IllegalArgumentException) {
        return null
    }
    val id = json?.get("survey_id") as? JsonPrimitive ?: return null
    // The expected ID is already a canonical UUIDv7. Case may differ in the original JSON.
    return content.takeIf { id.isString && id.content.equals(surveyUuid, ignoreCase = true) }
}
