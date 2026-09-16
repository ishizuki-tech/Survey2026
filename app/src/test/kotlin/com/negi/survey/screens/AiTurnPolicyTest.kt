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
    fun blank_main_answer_can_advance_without_completed_turn() {
        assertTrue(
            nextEnabled(
                turnCompleted = false,
                mainAnswerBlank = true,
            )
        )
    }

    @Test
    fun typed_but_unsubmitted_main_turn_cannot_advance() {
        assertFalse(
            nextEnabled(
                turnCompleted = false,
                mainAnswerBlank = false,
            )
        )
    }

    @Test
    fun pending_submission_loading_or_speech_cannot_advance() {
        assertFalse(
            nextEnabled(
                turnCompleted = true,
                mainAnswerBlank = false,
                mainSubmissionPending = true,
            )
        )
        assertFalse(
            nextEnabled(
                turnCompleted = true,
                mainAnswerBlank = false,
                aiLoading = true,
            )
        )
        assertFalse(
            nextEnabled(
                turnCompleted = true,
                mainAnswerBlank = false,
                speechRecording = true,
            )
        )
        assertFalse(
            nextEnabled(
                turnCompleted = true,
                mainAnswerBlank = false,
                speechTranscribing = true,
            )
        )
    }

    @Test
    fun completed_main_turn_can_advance() {
        assertTrue(
            nextEnabled(
                turnCompleted = true,
                mainAnswerBlank = false,
            )
        )
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
        mainAnswerBlank: Boolean = false,
        aiLoading: Boolean = false,
        mainSubmissionPending: Boolean = false,
        speechRecording: Boolean = false,
        speechTranscribing: Boolean = false,
    ): Boolean =
        canAdvanceAiTurn(
            turnCompleted = turnCompleted,
            mainAnswerBlank = mainAnswerBlank,
            aiLoading = aiLoading,
            mainSubmissionPending = mainSubmissionPending,
            speechRecording = speechRecording,
            speechTranscribing = speechTranscribing,
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