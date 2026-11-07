package com.negi.survey.vm

internal data class QuestionSpeechOwner(
    val presentation: Long,
    val session: Long,
    val nodeId: String,
    val nodeType: NodeType
)

internal data class QuestionPlaybackTarget(
    val text: String,
    val followup: SurveyViewModel.FollowupEntry? = null
)

internal fun pendingAiFollowup(entries: List<SurveyViewModel.FollowupEntry>) =
    entries.lastOrNull { it.answer == null && it.question.isNotBlank() }

internal fun resolveAiAutoReadTarget(
    rootQuestion: String,
    originalAnswer: String,
    followups: List<SurveyViewModel.FollowupEntry>,
    completed: Boolean,
    reason: SurveyAiReason? = null
): QuestionPlaybackTarget? {
    if (completed || reason?.terminal == true || reason == SurveyAiReason.FAILURE) return null
    pendingAiFollowup(followups)?.let { return QuestionPlaybackTarget(it.question, it) }
    return rootQuestion.takeIf { it.isNotBlank() && originalAnswer.isBlank() && followups.isEmpty() }
        ?.let { QuestionPlaybackTarget(it) }
}
