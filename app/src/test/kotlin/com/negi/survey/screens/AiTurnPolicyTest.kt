package com.negi.survey.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTurnPolicyTest {

    @Test
    fun fresh_or_typed_but_unsubmitted_main_turn_cannot_advance() {
        assertFalse(nextEnabled(turnCompleted = false))
        assertFalse(nextEnabled(turnCompleted = false))
    }

    @Test
    fun pending_submission_loading_or_speech_cannot_advance() {
        assertFalse(nextEnabled(turnCompleted = true, mainSubmissionPending = true))
        assertFalse(nextEnabled(turnCompleted = true, aiLoading = true))
        assertFalse(nextEnabled(turnCompleted = true, speechRecording = true))
        assertFalse(nextEnabled(turnCompleted = true, speechTranscribing = true))
    }

    @Test
    fun generated_or_typed_followup_cannot_advance_until_persisted_answer_completes_turn() {
        assertFalse(nextEnabled(turnCompleted = false, hasUnansweredFollowup = true))
        assertFalse(nextEnabled(turnCompleted = false, hasUnansweredFollowup = true))
        assertTrue(nextEnabled(turnCompleted = true, hasUnansweredFollowup = false))
    }

    @Test
    fun completed_main_turn_without_followup_or_other_node_followup_can_advance() {
        assertTrue(nextEnabled(turnCompleted = true))
        assertTrue(nextEnabled(turnCompleted = true, hasUnansweredFollowup = false))
    }

    private fun nextEnabled(
        turnCompleted: Boolean = false,
        aiLoading: Boolean = false,
        mainSubmissionPending: Boolean = false,
        speechRecording: Boolean = false,
        speechTranscribing: Boolean = false,
        hasUnansweredFollowup: Boolean = false,
    ): Boolean =
        canAdvanceAiTurn(
            turnCompleted = turnCompleted,
            aiLoading = aiLoading,
            mainSubmissionPending = mainSubmissionPending,
            speechRecording = speechRecording,
            speechTranscribing = speechTranscribing,
            hasUnansweredFollowup = hasUnansweredFollowup,
        )
}
