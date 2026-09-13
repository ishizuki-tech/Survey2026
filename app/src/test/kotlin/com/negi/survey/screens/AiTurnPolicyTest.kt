package com.negi.survey.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTurnPolicyTest {

    @Test
    fun unsubmitted_main_turn_or_unanswered_followup_can_still_advance() {
        assertTrue(nextEnabled(turnCompleted = false))
        assertTrue(nextEnabled(turnCompleted = false, hasUnansweredFollowup = true))
        assertTrue(nextEnabled(turnCompleted = true, hasUnansweredFollowup = true))
    }

    @Test
    fun pending_submission_loading_or_speech_cannot_advance_regardless_of_turn_state() {
        assertFalse(nextEnabled(turnCompleted = true, mainSubmissionPending = true))
        assertFalse(nextEnabled(turnCompleted = true, aiLoading = true))
        assertFalse(nextEnabled(turnCompleted = true, speechRecording = true))
        assertFalse(nextEnabled(turnCompleted = true, speechTranscribing = true))
        assertFalse(nextEnabled(turnCompleted = false, mainSubmissionPending = true))
        assertFalse(nextEnabled(turnCompleted = false, aiLoading = true))
        assertFalse(nextEnabled(turnCompleted = false, speechRecording = true))
        assertFalse(nextEnabled(turnCompleted = false, speechTranscribing = true))
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
