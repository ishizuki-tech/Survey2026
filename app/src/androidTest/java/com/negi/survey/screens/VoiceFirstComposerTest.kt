package com.negi.survey.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.negi.survey.ui.theme.SurveyNavTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class VoiceFirstComposerTest {
    @get:Rule val rule = createComposeRule()

    @Test fun tapToAnswerFitsAtNormalFontScale() {
        verifyAction(fontScale = 1f, recordAgain = false)
        val bounds = rule.onNodeWithText("Tap to answer").getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("Action height: $height", height <= 72.dp)
    }

    @Test fun tapToAnswerFitsAtMediumFontScaleInDarkTheme() =
        verifyAction(fontScale = 1.5f, recordAgain = false, darkTheme = true)

    @Test fun recordAgainRemainsReachableAtLargeFontScale() =
        verifyAction(fontScale = 2f, recordAgain = true)

    private fun verifyAction(fontScale: Float, recordAgain: Boolean, darkTheme: Boolean = false) {
        var starts = 0
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                SurveyNavTheme(darkTheme = darkTheme) {
                    Surface {
                        Box(Modifier.width(170.dp)) {
                            VoiceRecordingAction(recordAgain = recordAgain, enabled = true) { starts++ }
                        }
                    }
                }
            }
        }

        val label = if (recordAgain) "Record again" else "Tap to answer"
        rule.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithText(label)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(64.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val text = rule.onNodeWithText(label, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse(layout.hasVisualOverflow)
        for (line in 0 until layout.lineCount) {
            assertFalse(layout.isLineEllipsized(line))
        }
        assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))

        text.performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun processingDisablesRecording() {
        var starts = 0
        rule.setContent {
            SurveyNavTheme(darkTheme = true) {
                VoiceRecordingAction(recordAgain = false, enabled = false) { starts++ }
            }
        }
        rule.onNodeWithText("Tap to answer")
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performTouchInput { click() }
        rule.runOnIdle { assertEquals(0, starts) }
    }
}
