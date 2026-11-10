/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyExportJsonBuilder.kt
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */
package com.negi.survey.net

import com.negi.survey.BuildConfig
import com.negi.survey.vm.SurveyFinalizationSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The single authoritative serializer for the survey JSON export contract.
 *
 * The input is an immutable finalization snapshot so serialization cannot read
 * live UI or ViewModel state while an export is being staged.
 */
object SurveyExportJsonBuilder {
    internal fun build(
        snapshot: SurveyFinalizationSnapshot,
        exportedAt: String,
        recordings: List<SurveyRecording> = emptyList(),
    ): String = buildString {
        val audioByQuestion = recordings.groupBy { it.identity.prompt.questionId }
        val answerOwnerIds = linkedSetOf<String>().apply {
            addAll(snapshot.questions.keys)
            addAll(snapshot.answers.keys)
            addAll(audioByQuestion.keys)
        }.toList().sorted()

        append("{\n")
        append("  \"survey_id\": \"").append(escape(snapshot.surveyId)).append("\",\n")
        append("  \"status\": \"").append(snapshot.status.jsonValue).append("\",\n")
        append("  \"build\": \"").append(escape("${BuildConfig.GIT_COMMIT_SHA}")).append("\",\n")
        append("  \"exported_at\": \"").append(escape(exportedAt)).append("\",\n")

        append("  \"meta\": ")
        appendStringMap(snapshot.extraMeta, indent = "  ")
        append(",\n")

        append("  \"answers\": {\n")
        answerOwnerIds.forEachIndexed { index, id ->
            append("    \"").append(escape(id)).append("\": {\n")
            append("      \"question\": \"").append(escape(snapshot.questions[id].orEmpty())).append("\",\n")
            append("      \"answer\": \"").append(escape(snapshot.answers[id].orEmpty())).append("\"")
            val audioRefs = audioByQuestion[id].orEmpty()
            if (audioRefs.isNotEmpty()) {
                append(",\n      \"audio\": [\n")
                audioRefs.forEachIndexed { audioIndex, ref ->
                    append("        { \"file\": \"")
                        .append(escape(ref.file))
                        .append("\" }")
                    if (audioIndex != audioRefs.lastIndex) append(',')
                    append('\n')
                }
                append("      ]\n")
            } else {
                append('\n')
            }
            append("    }")
            if (index != answerOwnerIds.lastIndex) append(',')
            append('\n')
        }
        append("  },\n")

        append("  \"ai_outcomes\": ").append(snapshot.aiOutcomesJson).append(",\n")

        append("  \"followups\": {\n")
        val followups = snapshot.followups.toSortedMap().entries.toList()
        followups.forEachIndexed { index, (ownerId, entries) ->
            append("    \"").append(escape(ownerId)).append("\": [\n")
            entries.forEachIndexed { entryIndex, entry ->
                append("      { \"question\": \"").append(escape(entry.question))
                    .append("\", \"answer\": \"").append(escape(entry.answer.orEmpty())).append("\" }")
                if (entryIndex != entries.lastIndex) append(',')
                append('\n')
            }
            append("    ]")
            if (index != followups.lastIndex) append(',')
            append('\n')
        }
        append("  },\n")

        append("  \"voice_files\": [\n")
        recordings.forEachIndexed { index, recording ->
            val prompt = Json.encodeToJsonElement(
                com.negi.survey.vm.RecordingPrompt.serializer(), recording.identity.prompt,
            ) as JsonObject
            val entry = buildJsonObject {
                put("file", recording.file)
                put("recording_id", recording.identity.id)
                prompt.forEach { (field, value) -> put(field, value) }
                put("transcription_status", Json.encodeToJsonElement(
                    TranscriptionOutcome.Status.serializer(), recording.outcome.status,
                ))
                recording.outcome.transcript?.let { put("transcript", it) }
            }
            append("    ").append(entry)
            if (index != recordings.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n")
        append("}\n")
    }

    private fun StringBuilder.appendStringMap(values: Map<String, String>, indent: String) {
        if (values.isEmpty()) {
            append("{}")
            return
        }
        append("{\n")
        val entries = values.entries.toList()
        entries.forEachIndexed { index, (key, value) ->
            append(indent).append("  \"").append(escape(key)).append("\": \"")
                .append(escape(value)).append('"')
            if (index != entries.lastIndex) append(',')
            append('\n')
        }
        append(indent).append('}')
    }

    private fun escape(value: String): String = buildString(value.length + 8) {
        value.forEach { character ->
            when (character) {
                '\"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') {
                    append("\\u00").append(character.code.toString(16).padStart(2, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
}
