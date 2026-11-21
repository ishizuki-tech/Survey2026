package com.negi.survey.net

import android.content.Context
import com.negi.survey.vm.SurveyCompletionStatus
import com.negi.survey.vm.SurveyFinalizationSnapshot
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One atomic route/revision commit, independent of immutable WAV packages and upload receipts. */
class SurveyBranchStateStore(
    root: File,
    private val writeRecord: (File, String) -> Unit = { file, text -> V2ArtifactStore.writeAtomically(file, text) },
) {
    private val root = root.canonicalFile

    constructor(context: Context) : this(File(context.noBackupFilesDir, "survey_branch_state"))

    @Serializable
    internal data class State(
        val surveyId: String,
        val choices: Map<String, String>,
        val revisions: Map<String, Long>,
        val inactive: Set<String>,
    )

    /** Call on an I/O dispatcher. No live route is acknowledged before this returns. */
    internal fun commit(state: State) = synchronized(lock) {
        val target = file(state.surveyId)
        check(state.revisions.values.all { it >= 0 })
        check(state.inactive.all { it in state.revisions })
        check(root.isDirectory || root.mkdirs()) { "Could not create branch state directory." }
        V2ArtifactStore.syncDirectory(root.parentFile!!)
        val encoded = Json.encodeToString(State.serializer(), state)
        writeRecord(target, "${V2ArtifactStore.stableId(encoded)}\n$encoded")
    }

    internal fun read(surveyId: String): State = synchronized(lock) {
        val target = file(surveyId)
        if (!target.exists()) {
            // Earlier installations have no proof that Q14b is still on the selected path.
            return@synchronized State(surveyId, emptyMap(), mapOf("Q14b" to 0L), setOf("Q14b"))
        }
        check(target.isFile && target.canonicalFile == target.absoluteFile && target.length() <= 1_048_576)
        val record = target.readText()
        val encoded = record.substringAfter('\n')
        check(record == "${V2ArtifactStore.stableId(encoded)}\n$encoded") { "Invalid branch state checksum." }
        Json.decodeFromString(State.serializer(), encoded).also {
            check(it.surveyId == surveyId && it.revisions.values.all { revision -> revision >= 0 } &&
                it.inactive.all { id -> id in it.revisions }) { "Invalid branch state ownership." }
        }
    }

    /** Uses exactly the same snapshot policy for abandoned recovery and deterministic tests. */
    internal fun recoverySnapshot(surveyId: String): SurveyFinalizationSnapshot {
        val state = read(surveyId)
        val revisions = state.revisions + ("Q14b" to (state.revisions["Q14b"] ?: 0L))
        val inactive = if ("Q14b" in state.revisions) state.inactive else state.inactive + "Q14b"
        return SurveyFinalizationSnapshot(
            surveyId, emptyMap(), emptyMap(), emptyMap(), emptyList(), "{}", emptyMap(),
            SurveyCompletionStatus.INCOMPLETE, revisions, inactive,
        )
    }

    private fun file(surveyId: String): File {
        require(UUID.fromString(surveyId).toString() == surveyId)
        return File(root, "$surveyId.json")
    }

    private companion object { val lock = Any() }
}
