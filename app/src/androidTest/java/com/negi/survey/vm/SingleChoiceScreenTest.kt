package com.negi.survey.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import com.negi.survey.SingleChoiceNodeScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SingleChoiceScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun restored_other_is_shown_and_next_submits_detail() {
        var selected by mutableStateOf<String?>("Other")
        var submitted: Pair<String?, String?>? = null
        composeRule.setContent {
            SingleChoiceNodeScreen(title = "Choice", question = "Question", options = listOf("Known", "Other"),
                selected = selected, onSelect = { selected = it }, otherTextOption = "Other",
                initialOtherText = "saved detail", onSubmit = { choice, detail -> submitted = choice to detail },
                onBack = {})
        }
        composeRule.onNodeWithText("saved detail").assertExists()
        val editor = composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
        editor.performClick().performSemanticsAction(SemanticsActions.SetSelection) { it(5, 5, false) }
        editor.performTextInput("!")
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(6)))
        composeRule.onNodeWithText("Next").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("Other" to "saved! detail", submitted) }
        val other = composeRule.onNodeWithText("Other")
        val known = composeRule.onNodeWithText("Known")
        val next = composeRule.onNodeWithText("Next")
        other.assertIsSelected()
        known.assertIsNotSelected()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("")
        next.assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("edited detail")

        known.performClick().assertIsSelected()
        other.assertIsNotSelected()
        composeRule.onNodeWithText("Other details").assertDoesNotExist()
        next.assertIsEnabled()

        other.performClick().assertIsSelected()
        known.assertIsNotSelected()
        composeRule.onNodeWithText("edited detail").assertExists()
        other.performClick().assertIsNotSelected()
        composeRule.onNodeWithText("Other details").assertDoesNotExist()
        next.assertIsNotEnabled()
    }
}
