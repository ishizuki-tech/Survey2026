package com.negi.survey.decision

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

enum class SurveyDecision {
    ACHIEVED,
    GENERATE,
    LIMIT_REACHED,
    FAILURE,
}

data class EvaluationExecution(
    val rawModelOutput: String,
    val timedOut: Boolean = false,
    val error: String? = null,
)

data class RequiredComponentConfiguration(
    val legacyRequiredComponents: List<String> = emptyList(),
    val requiredComponentIds: List<String> = emptyList(),
)

data class EvaluationInput(
    val execution: EvaluationExecution,
    val remainingFollowupCapacity: Int,
    val requiredComponents: RequiredComponentConfiguration = RequiredComponentConfiguration(),
)

data class EvaluationResult(
    val decision: SurveyDecision,
    val admittedMissingPoints: List<String> = emptyList(),
)

data class FollowupAdmissionInput(
    val candidate: String,
    val timedOut: Boolean = false,
    val error: String? = null,
    val existingQuestions: List<String> = emptyList(),
)

data class FollowupAdmission(
    val acceptedQuestion: String?,
)

/**
 * Pure admission policy for structured evaluation output and extracted follow-up candidates.
 * This engine does not execute inference, build prompts, or extract candidates from model output.
 */
object SurveyDecisionEngine {
    fun evaluate(input: EvaluationInput): EvaluationResult {
        val execution = input.execution
        if (execution.timedOut || execution.error != null) return EvaluationResult(SurveyDecision.FAILURE)

        val obj = parseStrictModelJsonObject(execution.rawModelOutput)
            ?: return EvaluationResult(SurveyDecision.FAILURE)
        val scoreValue = obj["score"] as? JsonPrimitive ?: return EvaluationResult(SurveyDecision.FAILURE)
        val score = scoreValue.takeUnless { it.isString }?.intOrNull
            ?.takeIf { it in 1..100 } ?: return EvaluationResult(SurveyDecision.FAILURE)
        val missing = obj["missing_points"] as? JsonArray ?: return EvaluationResult(SurveyDecision.FAILURE)
        if (missing.any {
                val point = it as? JsonPrimitive
                point == null || !point.isString || point.content.isBlank()
            }) return EvaluationResult(SurveyDecision.FAILURE)

        val modelMissingPoints = missing.map { (it as JsonPrimitive).content }
        val configuration = input.requiredComponents
        val missingPoints = when {
            configuration.requiredComponentIds.isNotEmpty() -> canonicalizeRequiredComponentIds(
                modelMissingPoints,
                configuration.requiredComponentIds,
            ) ?: return EvaluationResult(SurveyDecision.FAILURE)
            configuration.legacyRequiredComponents.isNotEmpty() -> {
                if (modelMissingPoints.any { it !in configuration.legacyRequiredComponents } ||
                    modelMissingPoints.distinct().size != modelMissingPoints.size
                ) return EvaluationResult(SurveyDecision.FAILURE)
                modelMissingPoints
            }
            else -> modelMissingPoints
        }

        val followupNeeded =
            if (!obj.containsKey("followup_needed")) {
                if (score in 1..89 && missing.isNotEmpty()) true else return EvaluationResult(SurveyDecision.FAILURE)
            } else {
                val followupValue = obj["followup_needed"] as? JsonPrimitive
                    ?: return EvaluationResult(SurveyDecision.FAILURE)
                followupValue.takeUnless { it.isString }?.booleanOrNull
                    ?: return EvaluationResult(SurveyDecision.FAILURE)
            }

        if (score >= 90) {
            return if (missing.isEmpty() && !followupNeeded) {
                EvaluationResult(SurveyDecision.ACHIEVED, missingPoints)
            } else {
                EvaluationResult(SurveyDecision.FAILURE)
            }
        }

        if (missing.isEmpty() || !followupNeeded) return EvaluationResult(SurveyDecision.FAILURE)
        return EvaluationResult(
            if (input.remainingFollowupCapacity > 0) SurveyDecision.GENERATE else SurveyDecision.LIMIT_REACHED,
            missingPoints,
        )
    }

    fun admitFollowup(input: FollowupAdmissionInput): FollowupAdmission {
        if (input.timedOut || input.error != null) return FollowupAdmission(null)
        val question = input.candidate.trim()
        if (question.isEmpty() || question.contains('\n') || question.contains('\r') ||
            question.startsWith("{") || question.startsWith("[") || question.startsWith("```") ||
            question.count { it == '?' || it == '？' } != 1 ||
            question.last() !in listOf('?', '？')) return FollowupAdmission(null)

        return FollowupAdmission(
            question.takeIf { candidate ->
                input.existingQuestions.none { normalizeQuestion(it) == normalizeQuestion(candidate) }
            },
        )
    }

    fun normalizeQuestion(question: String): String = question.trim().lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ").trimEnd('?', '？').trim()

    private fun parseStrictModelJsonObject(raw: String): JsonObject? {
        val trimmed = raw.trim()

        fun parseObject(text: String): JsonObject? =
            runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

        parseObject(trimmed)?.let { return it }

        val openingLineEnd = trimmed.indexOf('\n')
        if (openingLineEnd < 0) return null
        val openingLine = trimmed.substring(0, openingLineEnd).removeSuffix("\r")
        if (openingLine.length != 7 || !openingLine.startsWith("```") ||
            openingLine.substring(3).lowercase(Locale.ROOT) != "json"
        ) return null

        val closingLineStart = trimmed.lastIndexOf('\n') + 1
        if (closingLineStart <= openingLineEnd || trimmed.substring(closingLineStart) != "```") return null

        return parseObject(trimmed.substring(openingLineEnd + 1, closingLineStart - 1))
    }

    private fun canonicalizeRequiredComponentIds(
        modelIds: List<String>,
        configuredIds: List<String>,
    ): List<String>? {
        val configuredByFormat = configuredIds.groupBy(::normalizeRequiredComponentId)
        val canonicalIds = modelIds.map { modelId ->
            configuredByFormat[normalizeRequiredComponentId(modelId)]?.singleOrNull()
                ?: return null
        }
        return canonicalIds.takeIf { it.distinct().size == it.size }
    }

    private fun normalizeRequiredComponentId(id: String): String = id.trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\s_-]+"), "_")
}
