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

internal object SurveyAiPolicy {
    fun evaluate(raw: String, timedOut: Boolean, error: String?, remaining: Int): SurveyAiDecision {
        if (timedOut || error != null) return SurveyAiDecision.FAILURE
        val obj = runCatching { Json.parseToJsonElement(raw.trim()) as? JsonObject }.getOrNull()
            ?: return SurveyAiDecision.FAILURE
        val scoreValue = obj["score"] as? JsonPrimitive ?: return SurveyAiDecision.FAILURE
        val score = scoreValue.takeUnless { it.isString }?.intOrNull
            ?.takeIf { it in 1..100 } ?: return SurveyAiDecision.FAILURE
        val missing = obj["missing_points"] as? JsonArray ?: return SurveyAiDecision.FAILURE
        if (missing.any {
                val point = it as? JsonPrimitive
                point == null || !point.isString || point.content.isBlank()
            }) return SurveyAiDecision.FAILURE
        val followupValue = obj["followup_needed"] as? JsonPrimitive
            ?: return SurveyAiDecision.FAILURE
        val followupNeeded = followupValue.takeUnless { it.isString }?.booleanOrNull
            ?: return SurveyAiDecision.FAILURE

        if (score >= 90) {
            return if (missing.isEmpty() && !followupNeeded) {
                SurveyAiDecision.ACHIEVED
            } else {
                SurveyAiDecision.FAILURE
            }
        }

        if (missing.isEmpty() || !followupNeeded) return SurveyAiDecision.FAILURE
        return if (remaining > 0) SurveyAiDecision.GENERATE else SurveyAiDecision.LIMIT_REACHED
    }

    fun normalize(question: String): String = question.trim().lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ").trimEnd('?', '？').trim()

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
