package com.negi.survey.vm

import java.util.Locale
import com.negi.survey.decision.EvaluationExecution
import com.negi.survey.decision.EvaluationInput
import com.negi.survey.decision.FollowupAdmissionInput
import com.negi.survey.decision.RequiredComponentConfiguration
import com.negi.survey.decision.SurveyDecision
import com.negi.survey.decision.SurveyDecisionEngine
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

/** Parses only a complete JSON object or one complete JSON-tagged Markdown code fence. */
internal fun parseStrictModelJsonObject(raw: String): JsonObject? {
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
        val result = SurveyDecisionEngine.evaluate(
            EvaluationInput(
                execution = EvaluationExecution(raw, timedOut, error),
                remainingFollowupCapacity = remaining,
                requiredComponents = RequiredComponentConfiguration(
                    legacyRequiredComponents = requiredComponents,
                    requiredComponentIds = requiredComponentIds,
                ),
            ),
        )
        return SurveyAiEvaluation(result.decision.toSurveyAiDecision(), result.admittedMissingPoints)
    }

    fun normalize(question: String): String = SurveyDecisionEngine.normalizeQuestion(question)

    fun acceptQuestion(raw: String, timedOut: Boolean, error: String?, existing: List<String>): String? =
        SurveyDecisionEngine.admitFollowup(
            FollowupAdmissionInput(raw, timedOut, error, existing),
        ).acceptedQuestion
}

private fun SurveyDecision.toSurveyAiDecision(): SurveyAiDecision = when (this) {
    SurveyDecision.ACHIEVED -> SurveyAiDecision.ACHIEVED
    SurveyDecision.GENERATE -> SurveyAiDecision.GENERATE
    SurveyDecision.LIMIT_REACHED -> SurveyAiDecision.LIMIT_REACHED
    SurveyDecision.FAILURE -> SurveyAiDecision.FAILURE
}
