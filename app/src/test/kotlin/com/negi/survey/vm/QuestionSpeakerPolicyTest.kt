package com.negi.survey.vm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionSpeakerPolicyTest {

    @Test
    fun autoPlayIsBlockedWhileRecordingOrTranscribing() {
        assertTrue(shouldAutoPlayQuestion(autoPlayEnabled = true))
        assertFalse(shouldAutoPlayQuestion(autoPlayEnabled = false))
        assertFalse(shouldAutoPlayQuestion(autoPlayEnabled = true, speechRecording = true))
        assertFalse(shouldAutoPlayQuestion(autoPlayEnabled = true, speechTranscribing = true))
    }

    @Test
    fun speakerButtonSpeaksOnlyWhenNotAlreadySpeaking() {
        val speaker = FakeQuestionSpeaker(isSpeaking = false)

        toggleQuestionSpeaker(speaker, "Question", "Q1")

        assertEquals(listOf("Question" to "Q1"), speaker.spoken)
        assertEquals(0, speaker.stopCount)
    }

    @Test
    fun speakerButtonStopsInsteadOfReplayingWhenAlreadySpeaking() {
        val speaker = FakeQuestionSpeaker(isSpeaking = true)

        toggleQuestionSpeaker(speaker, "Question", "Q1")

        assertTrue(speaker.spoken.isEmpty())
        assertEquals(1, speaker.stopCount)
    }

    private class FakeQuestionSpeaker(isSpeaking: Boolean) : QuestionSpeaker {
        private val speaking = MutableStateFlow(isSpeaking)
        override val isSpeaking: StateFlow<Boolean> = speaking
        override val isReady: StateFlow<Boolean> = MutableStateFlow(true)
        override val errorMessage: StateFlow<String?> = MutableStateFlow(null)
        val spoken = mutableListOf<Pair<String, String>>()
        var stopCount = 0

        override fun speak(text: String, utteranceId: String) {
            spoken += text to utteranceId
        }

        override fun stop() {
            stopCount += 1
            speaking.value = false
        }
    }
}
