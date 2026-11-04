package com.negi.survey.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VoiceFirstComposerTest {
    @get:Rule val rule = createComposeRule()

    @Test fun voiceActionsAndTranscriptUseCallbacks() {
        var draft by mutableStateOf("")
        var recording = 0
        var sent = 0
        var editing = 0
        rule.setContent {
            VoiceFirstComposer(draft, true, false, false, null, false,
                { recording++ }, { sent++ }, { editing++ })
        }
        rule.onNodeWithContentDescription("Record answer").performClick()
        rule.onNodeWithText("Type instead").performClick()
        rule.runOnIdle { draft = "Recognized answer" }
        rule.onNodeWithText("Recognized answer").performClick()
        rule.onNodeWithText("Record again").performClick()
        rule.onNodeWithText("Send").performClick()
        rule.runOnIdle {
            assertEquals(2, recording)
            assertEquals(1, sent)
            assertEquals(2, editing)
        }
    }

    @Test fun processingDisablesRecordingAndSubmission() {
        rule.setContent {
            VoiceFirstComposer("draft", false, false, false, null, false, {}, {}, {})
        }
        rule.onNodeWithText("Record again").assertIsNotEnabled()
        rule.onNodeWithText("Send").assertIsNotEnabled()
    }
}
