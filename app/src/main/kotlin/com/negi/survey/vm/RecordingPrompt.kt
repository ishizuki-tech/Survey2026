package com.negi.survey.vm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The prompt actually being answered when microphone capture is requested. */
@Serializable
data class RecordingPrompt(
    @SerialName("survey_id") val surveyId: String,
    @SerialName("question_id") val questionId: String,
    @SerialName("prompt_kind") val kind: Kind,
    @SerialName("prompt_text") val text: String,
    @SerialName("followup_position") val followupPosition: Int? = null,
) {
    @Serializable
    enum class Kind {
        @SerialName("original") ORIGINAL,
        @SerialName("followup") FOLLOWUP,
        @SerialName("survey_note") SURVEY_NOTE,
    }

    init {
        require(surveyId.isNotBlank() && questionId.isNotBlank() && text.isNotBlank())
        require(if (kind == Kind.FOLLOWUP) (followupPosition ?: 0) > 0 else followupPosition == null)
        require((kind == Kind.SURVEY_NOTE) == (questionId == HOME_FREE_TEXT_QID))
    }
}

/** Dedicated pseudo question id for Survey Note dictation. */
internal const val HOME_FREE_TEXT_QID = "__home_free_text__"

internal fun aiRecordingPrompt(
    surveyId: String,
    questionId: String,
    original: String,
    conversation: AiViewModel.ConversationState,
    followups: List<SurveyViewModel.FollowupEntry>,
): RecordingPrompt? {
    if (conversation.role == AiViewModel.ComposerRole.MAIN) {
        return original.takeIf(String::isNotBlank)?.let {
            RecordingPrompt(surveyId, questionId, RecordingPrompt.Kind.ORIGINAL, it)
        }
    }
    val pending = followups.indexOfLast { it.answer == null && it.question.isNotBlank() }
    if (pending < 0 || followups[pending].question != conversation.activePromptQuestion) return null
    return RecordingPrompt(
        surveyId, questionId, RecordingPrompt.Kind.FOLLOWUP, conversation.activePromptQuestion,
        followupPosition = pending + 1,
    )
}
