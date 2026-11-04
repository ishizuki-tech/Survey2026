package com.negi.survey.vm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        composeRule.onNodeWithText("Next").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("Other" to "saved detail", submitted) }
        composeRule.onNodeWithText("Other").performClick()
        composeRule.onNodeWithText("Next").assertIsNotEnabled()
    }
}
