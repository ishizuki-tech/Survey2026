package com.negi.survey.screens

import com.negi.survey.vm.QuestionSpeaker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
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

    @Test
    fun microphoneStartStopsTtsBeforeStartingCapture() {
        val events = mutableListOf<String>()
        val speech = FakeSpeechController(events)
        val tts = FakeQuestionSpeaker(events)

        toggleSpeechRecordingWithTtsInterlock(speech, tts)

        assertEquals(listOf("tts.stop", "speech.start"), events)
    }

    @Test
    fun microphoneToggleStopsCaptureWithoutRestartingTts() {
        val events = mutableListOf<String>()
        val speech = FakeSpeechController(events, recording = true)
        val tts = FakeQuestionSpeaker(events)

        toggleSpeechRecordingWithTtsInterlock(speech, tts)

        assertEquals(listOf("speech.stop"), events)
    }

    @Test
    fun microphoneDoesNotStartDuringTranscription() {
        val events = mutableListOf<String>()
        val speech = FakeSpeechController(events, transcribing = true)
        val tts = FakeQuestionSpeaker(events)

        toggleSpeechRecordingWithTtsInterlock(speech, tts)

        assertTrue(events.isEmpty())
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

    private class FakeSpeechController(
        private val events: MutableList<String>,
        recording: Boolean = false,
        transcribing: Boolean = false,
    ) : SpeechController {
        override val isRecording: StateFlow<Boolean> = MutableStateFlow(recording)
        override val isTranscribing: StateFlow<Boolean> = MutableStateFlow(transcribing)
        override val partialText: StateFlow<String> = MutableStateFlow("")
        override val errorMessage: StateFlow<String?> = MutableStateFlow(null)

        override fun startRecording() {
            events += "speech.start"
        }

        override fun stopRecording() {
            events += "speech.stop"
        }
    }

    private class FakeQuestionSpeaker(
        private val events: MutableList<String>,
    ) : QuestionSpeaker {
        override val isSpeaking: StateFlow<Boolean> = MutableStateFlow(false)
        override val isReady: StateFlow<Boolean> = MutableStateFlow(true)
        override val errorMessage: StateFlow<String?> = MutableStateFlow(null)

        override fun speak(text: String, utteranceId: String) = Unit

        override fun stop() {
            events += "tts.stop"
        }
    }
}
