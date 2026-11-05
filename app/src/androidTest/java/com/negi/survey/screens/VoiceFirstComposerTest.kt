package com.negi.survey.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun recordingActionsWrapAtLargeFontScale() {
        var recordAgain = 0
        var sent = 0
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.width(170.dp)) {
                    VoiceFirstComposer("draft", true, false, false, null, false,
                        { recordAgain++ }, { sent++ }, {})
                }
            }
        }
        for (label in listOf("Record again", "Send")) {
            rule.onNodeWithText(label).assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            // The semantics action rebuilds the paragraph at max width. Check the glyph bounds.
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line))
                assertTrue("$label clips horizontally", layout.getLineRight(line) <= layout.size.width + 1)
                assertTrue("$label clips vertically", layout.getLineBottom(line) <= layout.size.height + 1)
            }
            assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            if (label == "Record again") assertTrue(layout.lineCount > 1)
        }
        rule.runOnIdle {
            assertEquals(1, recordAgain)
            assertEquals(1, sent)
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
