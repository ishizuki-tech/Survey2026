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
    fun can_advance_regardless_of_answer_completeness() {
        assertTrue(nextEnabled())
    }

    @Test
    fun pending_submission_loading_or_speech_cannot_advance() {
        assertFalse(nextEnabled(mainSubmissionPending = true))
        assertFalse(nextEnabled(aiLoading = true))
        assertFalse(nextEnabled(speechRecording = true))
        assertFalse(nextEnabled(speechTranscribing = true))
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
        aiLoading: Boolean = false,
        mainSubmissionPending: Boolean = false,
        speechRecording: Boolean = false,
        speechTranscribing: Boolean = false,
    ): Boolean =
        canAdvanceAiTurn(
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