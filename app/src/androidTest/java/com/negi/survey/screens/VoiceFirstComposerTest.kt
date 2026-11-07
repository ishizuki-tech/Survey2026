package com.negi.survey.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
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

    @Test fun recordAgainRemainsReachableAtLargeFontScale() {
        var recordAgain = 0
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.width(170.dp)) {
                    VoiceRecordingAction(recordAgain = true, enabled = true) { recordAgain++ }
                }
            }
        }
        val label = "Record again"
        rule.onNodeWithText(label).assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        for (line in 0 until layout.lineCount) {
            assertFalse(layout.isLineEllipsized(line))
            assertTrue(layout.getLineRight(line) <= layout.size.width + 1)
            assertTrue(layout.getLineBottom(line) <= layout.size.height + 1)
        }
        assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        rule.runOnIdle { assertEquals(1, recordAgain) }
    }

    @Test fun processingDisablesRecording() {
        rule.setContent {
            VoiceRecordingAction(recordAgain = false, enabled = false) { error("Recording started while busy") }
        }
        rule.onNodeWithContentDescription("Record answer").assertIsNotEnabled()
    }
}
