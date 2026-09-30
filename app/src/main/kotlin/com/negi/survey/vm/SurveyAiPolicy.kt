package com.negi.survey.vm

import java.util.Locale
import kotlinx.serialization.json.*

/** Additive export values; absence in older surveys means unknown, never achieved. */
enum class SurveyAiReason(val wireValue: String, val terminal: Boolean) {
    ACHIEVED("achieved", true),
    LIMIT_REACHED("limit_reached", true),
    UNABLE_REFUSED("unable_refused", true),
    FAILURE("failure", false)
}

internal enum class SurveyAiDecision { ACHIEVED, GENERATE, LIMIT_REACHED, FAILURE }

internal data class SurveyAiEvaluation(
    val decision: SurveyAiDecision,
    val missingPoints: List<String> = emptyList(),
)

internal object SurveyAiPolicy {
    fun evaluate(
        raw: String,
        timedOut: Boolean,
        error: String?,
        remaining: Int,
        requiredComponents: List<String> = emptyList(),
        requiredComponentIds: List<String> = emptyList(),
    ): SurveyAiDecision = evaluateAdmission(
        raw, timedOut, error, remaining, requiredComponents, requiredComponentIds,
    ).decision

    fun evaluateAdmission(
        raw: String,
        timedOut: Boolean,
        error: String?,
        remaining: Int,
        requiredComponents: List<String> = emptyList(),
        requiredComponentIds: List<String> = emptyList(),
    ): SurveyAiEvaluation {
        if (timedOut || error != null) return SurveyAiEvaluation(SurveyAiDecision.FAILURE)
        val obj = runCatching { Json.parseToJsonElement(raw.trim()) as? JsonObject }.getOrNull()
            ?: return SurveyAiEvaluation(SurveyAiDecision.FAILURE)
        val scoreValue = obj["score"] as? JsonPrimitive ?: return SurveyAiEvaluation(SurveyAiDecision.FAILURE)
        val score = scoreValue.takeUnless { it.isString }?.intOrNull
            ?.takeIf { it in 1..100 } ?: return SurveyAiEvaluation(SurveyAiDecision.FAILURE)
        val missing = obj["missing_points"] as? JsonArray ?: return SurveyAiEvaluation(SurveyAiDecision.FAILURE)
        if (missing.any {
                val point = it as? JsonPrimitive
                point == null || !point.isString || point.content.isBlank()
            }) return SurveyAiEvaluation(SurveyAiDecision.FAILURE)
        val modelMissingPoints = missing.map { (it as JsonPrimitive).content }
        val missingPoints = when {
            requiredComponentIds.isNotEmpty() -> canonicalizeRequiredComponentIds(
                modelMissingPoints,
                requiredComponentIds,
            ) ?: return SurveyAiEvaluation(SurveyAiDecision.FAILURE)
            requiredComponents.isNotEmpty() -> {
                if (modelMissingPoints.any { it !in requiredComponents } ||
                    modelMissingPoints.distinct().size != modelMissingPoints.size
                ) return SurveyAiEvaluation(SurveyAiDecision.FAILURE)
                modelMissingPoints
            }
            else -> modelMissingPoints
        }
        val followupNeeded =
            if (!obj.containsKey("followup_needed")) {
                // A low score with valid unresolved points unambiguously requires the next step.
                if (score in 1..89 && missing.isNotEmpty()) true else return SurveyAiEvaluation(SurveyAiDecision.FAILURE)
            } else {
                val followupValue = obj["followup_needed"] as? JsonPrimitive
                    ?: return SurveyAiEvaluation(SurveyAiDecision.FAILURE)
                followupValue.takeUnless { it.isString }?.booleanOrNull
                    ?: return SurveyAiEvaluation(SurveyAiDecision.FAILURE)
            }

        if (score >= 90) {
            return if (missing.isEmpty() && !followupNeeded) {
                SurveyAiEvaluation(SurveyAiDecision.ACHIEVED, missingPoints)
            } else {
                SurveyAiEvaluation(SurveyAiDecision.FAILURE)
            }
        }

        if (missing.isEmpty() || !followupNeeded) return SurveyAiEvaluation(SurveyAiDecision.FAILURE)
        return SurveyAiEvaluation(
            if (remaining > 0) SurveyAiDecision.GENERATE else SurveyAiDecision.LIMIT_REACHED,
            missingPoints,
        )
    }

    fun normalize(question: String): String = question.trim().lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ").trimEnd('?', '？').trim()

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

    fun acceptQuestion(raw: String, timedOut: Boolean, error: String?, existing: List<String>): String? {
        if (timedOut || error != null) return null
        val question = raw.trim()
        // These survey assets request exactly one plain-text question. Fail closed on other formats.
        if (question.isEmpty() || question.contains('\n') || question.contains('\r') ||
            question.startsWith("{") || question.startsWith("[") || question.startsWith("```") ||
            question.count { it == '?' || it == '？' } != 1 ||
            question.last() !in listOf('?', '？')) return null
        return question.takeIf { q -> existing.none { normalize(it) == normalize(q) } }
    }
}
