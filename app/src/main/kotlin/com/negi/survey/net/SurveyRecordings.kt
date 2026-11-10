package com.negi.survey.net

import com.negi.survey.utils.V2ArtifactPaths
import com.negi.survey.vm.RecordingPrompt
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/** Serializes admission to capture and local JSON staging for the same survey. */
internal object SurveyRecordingGate {
    private val captures = mutableMapOf<String, String>()
    private val finalizing = mutableSetOf<String>()

    @Synchronized
    fun begin(recording: RecordingIdentity): Boolean {
        val surveyId = recording.prompt.surveyId
        if (surveyId in finalizing || surveyId in captures) return false
        captures[surveyId] = recording.id
        return true
    }

    @Synchronized
    fun end(recording: RecordingIdentity) {
        captures.remove(recording.prompt.surveyId, recording.id)
    }

    @Synchronized
    fun beginFinalization(surveyId: String): Boolean =
        surveyId !in captures && finalizing.add(surveyId)

    @Synchronized
    fun endFinalization(surveyId: String) {
        finalizing.remove(surveyId)
    }
}

@Serializable
internal data class RecordingIdentity(
    @SerialName("recording_id") val id: String,
    val prompt: RecordingPrompt,
    val process: String,
) {
    constructor(id: String, prompt: RecordingPrompt) : this(id, prompt, SurveyRecordings.processToken)
}

@Serializable
internal data class TranscriptionOutcome(val status: Status, val transcript: String? = null) {
    @Serializable
    enum class Status {
        @SerialName("success") SUCCESS,
        @SerialName("empty") EMPTY,
        @SerialName("failed") FAILED,
        @SerialName("interrupted") INTERRUPTED,
    }

    init {
        require(if (status == Status.SUCCESS) !transcript.isNullOrBlank() else transcript == null)
    }

    companion object {
        fun from(result: Result<String>): TranscriptionOutcome = result.fold(
            onSuccess = { if (it.isBlank()) TranscriptionOutcome(Status.EMPTY) else TranscriptionOutcome(Status.SUCCESS, it) },
            onFailure = { TranscriptionOutcome(Status.FAILED) },
        )
    }
}

internal data class SurveyRecording(
    val file: String,
    val identity: RecordingIdentity,
    val outcome: TranscriptionOutcome,
)

/** Uses V2's sealed voice package for membership and a separate atomic file for the outcome. */
internal class SurveyRecordings(private val store: V2ArtifactStore) {
    @Serializable
    private data class OutcomeRecord(val route: String, val recordingId: String, val outcome: TranscriptionOutcome)

    fun persistOutcome(entry: V2ArtifactStore.Entry, identity: RecordingIdentity, outcome: TranscriptionOutcome) =
        synchronized(outcomeLock) {
            check(readIdentity(entry) == identity) { "Recording outcome does not match the staged voice." }
            val target = File(entry.directory, OUTCOME)
            if (target.exists()) return@synchronized
            val encoded = Json.encodeToString(OutcomeRecord(entry.routeFingerprint, identity.id, outcome))
            V2ArtifactStore.writeAtomically(target, checkedRecord(encoded), OUTCOME_TEMP_PREFIX)
        }

    fun inventory(paths: V2ArtifactPaths, profile: V2ArtifactStore.Profile): List<SurveyRecording> =
        store.voiceEntries(paths.surveyUuid).map { entry ->
            val identity = readIdentity(entry)
            val expectedPath = paths.voiceWav(identity.prompt.questionId, identity.id)
            check(entry.route.profile == profile && entry.route.path == expectedPath.relativePath && entry.matchesPayload()) {
                "A recording is missing or differs from its immutable V2 route."
            }
            val target = File(entry.directory, OUTCOME)
            val outcome = if (!target.exists()) TranscriptionOutcome(TranscriptionOutcome.Status.INTERRUPTED) else {
                val record = Json.decodeFromString<OutcomeRecord>(readCheckedRecord(target))
                check(record.route == entry.routeFingerprint && record.recordingId == identity.id) {
                    "Recording outcome belongs to a different WAV."
                }
                record.outcome
            }
            SurveyRecording(entry.route.basename, identity, outcome)
        }

    /** Surveys are process-owned ViewModels. A different process token proves there is no resumable owner. */
    fun abandonedSurveyIds(currentProcess: String = processToken): Set<String> =
        store.voiceCandidates().groupBy { it.route.surveyId }.mapNotNull { (surveyId, entries) ->
            if (surveyId == null || store.hasReservedRoute(V2ArtifactStore.surveyKey(surveyId))) return@mapNotNull null
            val owners = entries.map { entry ->
                try {
                    readIdentity(entry).process
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val reason = when (failure) {
                        is IOException -> "recording metadata could not be read"
                        is IllegalArgumentException -> "recording metadata could not be decoded"
                        else -> "recording metadata failed validation"
                    }
                    RuntimeLogStore.w(
                        "SurveyRecordings",
                        "Abandoned survey recovery blocked for survey=$surveyId artifact=${entry.route.id}. $reason.",
                    )
                    null
                }
            }
            surveyId.takeIf { owners.all { it != null && it != currentProcess } }
        }.toSet()

    private fun readIdentity(entry: V2ArtifactStore.Entry): RecordingIdentity {
        val metadata = File(entry.directory, V2ArtifactStore.VOICE_METADATA)
        check(metadata.isFile && metadata.canonicalFile == metadata.absoluteFile) { "Recording metadata is missing." }
        val encoded = metadata.readText()
        val checksum = File(entry.directory, METADATA_CHECKSUM)
        check(checksum.isFile && checksum.readText() == V2ArtifactStore.stableId(encoded)) { "Invalid recording metadata." }
        val identity = Json.decodeFromString<RecordingIdentity>(encoded)
        check(identity.id == entry.route.id && identity.prompt.surveyId == entry.route.surveyId &&
            runCatching { UUID.fromString(identity.process).toString() == identity.process }.getOrDefault(false)
        ) { "Recording metadata does not match the WAV." }
        return identity
    }

    companion object {
        val processToken: String = V2ArtifactStore.newId()
        const val METADATA_CHECKSUM = "voice.meta.sha256"
        const val OUTCOME = "voice.outcome"
        const val OUTCOME_TEMP_PREFIX = ".voice-outcome-"
        private val outcomeLock = Any()

        private fun checkedRecord(encoded: String) = "${V2ArtifactStore.stableId(encoded)}\n$encoded"

        private fun readCheckedRecord(file: File): String {
            check(file.isFile && file.canonicalFile == file.absoluteFile) { "Invalid recording outcome file." }
            val record = file.readText()
            val encoded = record.substringAfter('\n')
            check(record == checkedRecord(encoded)) { "Invalid recording outcome." }
            return encoded
        }
    }
}
