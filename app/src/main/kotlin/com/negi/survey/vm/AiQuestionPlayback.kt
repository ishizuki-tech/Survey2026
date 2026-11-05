package com.negi.survey.vm

internal data class AiQuestionPlaybackTarget(
    val text: String,
    val followup: SurveyViewModel.FollowupEntry? = null
)

internal fun pendingAiFollowup(entries: List<SurveyViewModel.FollowupEntry>) =
    entries.lastOrNull { it.answer == null && it.question.isNotBlank() }

internal fun resolveAiAutoReadTarget(
    rootQuestion: String,
    originalAnswer: String,
    followups: List<SurveyViewModel.FollowupEntry>,
    completed: Boolean
): AiQuestionPlaybackTarget? {
    if (completed) return null
    pendingAiFollowup(followups)?.let { return AiQuestionPlaybackTarget(it.question, it) }
    return rootQuestion.takeIf { it.isNotBlank() && originalAnswer.isBlank() && followups.isEmpty() }
        ?.let { AiQuestionPlaybackTarget(it) }
}
