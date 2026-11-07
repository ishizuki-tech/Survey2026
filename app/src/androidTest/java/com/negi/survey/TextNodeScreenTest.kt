package com.negi.survey

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.vm.FlowHome
import com.negi.survey.vm.NodeType
import com.negi.survey.vm.SurveyViewModel
import com.negi.survey.vm.isValidNumberAnswer
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TextNodeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun text_field_edits_update_view_model_answer() {
        lateinit var viewModel: SurveyViewModel

        composeRule.setContent {
            val backStack = rememberNavBackStack(FlowHome)
            viewModel = remember { SurveyViewModel(backStack, textConfig()) }
            val answers by viewModel.answers.collectAsState()

            TextNodeScreen(
                title = "Text question",
                question = "Enter an answer",
                value = answers[TEXT_NODE_ID].orEmpty(),
                onValueChange = { viewModel.setAnswer(it, TEXT_NODE_ID) },
                onNext = {},
                onBack = {}
            )
        }

        composeRule.onNode(hasSetTextAction()).performTextReplacement("Saved answer")

        composeRule.runOnIdle {
            assertEquals("Saved answer", viewModel.getAnswer(TEXT_NODE_ID))
        }
    }

    @Test
    fun text_starts_at_end_and_keeps_selection_and_composition_during_edits() {
        lateinit var viewModel: SurveyViewModel
        lateinit var connection: InputConnection
        val testIme = PlatformTextInputInterceptor { request, _ ->
            connection = request.createInputConnection(EditorInfo())
            awaitCancellation()
        }

        composeRule.setContent {
            val backStack = rememberNavBackStack(FlowHome)
            viewModel = remember {
                SurveyViewModel(backStack, textConfig()).apply { setAnswer("Saved answer", TEXT_NODE_ID) }
            }
            val answers by viewModel.answers.collectAsState()

            InterceptPlatformTextInput(testIme) {
                TextNodeScreen(
                    questionId = TEXT_NODE_ID,
                    title = "Text question",
                    question = "Enter an answer",
                    value = answers[TEXT_NODE_ID].orEmpty(),
                    onValueChange = { viewModel.setAnswer(it, TEXT_NODE_ID) },
                    onNext = {},
                    onBack = {}
                )
            }
        }

        val editor = composeRule.onNode(hasSetTextAction()).assertIsFocused()
        editor.assertSelection("Saved answer".length)
        val layouts = mutableListOf<TextLayoutResult>()
        editor.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val cursor = layouts.single().getCursorRect(5)
        editor.performTouchInput {
            // The unlabeled Material 3 field insets its text by 16 dp.
            val padding = 16.dp.toPx()
            click(Offset(padding + cursor.left, padding + cursor.center.y))
        }
        editor.assertSelection(5)
        editor.performTextInput("!")
        editor.assertTextEquals("Saved! answer").assertSelection(6)
        editor.performSemanticsAction(SemanticsActions.SetSelection) { it(1, 4, false) }
        val draft = "Saved! answer updated"
        composeRule.runOnIdle { viewModel.setAnswer(draft, TEXT_NODE_ID) }
        editor.assertTextEquals(draft).assertSelection(1, 4)

        editor.performSemanticsAction(SemanticsActions.SetSelection) { it(draft.length, draft.length, false) }
        composeRule.runOnIdle { connection.setComposingText(" n", 1) }
        editor.assertTextEquals("$draft n").assertSelection(draft.length + 2)
        composeRule.runOnIdle { connection.setComposingText(" na", 1) }
        editor.assertTextEquals("$draft na").assertSelection(draft.length + 3)
        composeRule.runOnIdle { connection.commitText(" name", 1) }
        editor.assertTextEquals("$draft name").assertSelection(draft.length + 5)
    }

    @Test
    fun text_saved_value_and_caret_restore_after_question_navigation() {
        lateinit var viewModel: SurveyViewModel

        composeRule.setContent {
            val backStack = rememberNavBackStack(FlowHome)
            viewModel = remember {
                SurveyViewModel(backStack, textConfig()).apply {
                    setAnswer("First saved answer", TEXT_NODE_ID)
                    setAnswer("Second saved answer", SECOND_TEXT_NODE_ID)
                }
            }
            val node by viewModel.currentNode.collectAsState()
            val answers by viewModel.answers.collectAsState()

            if (node.type == NodeType.TEXT) {
                TextNodeScreen(
                    questionId = node.id,
                    title = node.title,
                    question = node.question,
                    value = answers[node.id].orEmpty(),
                    onValueChange = { viewModel.setAnswer(it, node.id) },
                    onNext = { viewModel.advanceToNext() },
                    onBack = { viewModel.backToPrevious() }
                )
            }
        }

        val editor = composeRule.onNode(hasSetTextAction())
        composeRule.runOnIdle { viewModel.advanceToNext() }
        editor.assertIsFocused().assertTextEquals("First saved answer").assertSelection(18)
        editor.performSemanticsAction(SemanticsActions.SetSelection) { it(3, 5, false) }

        composeRule.runOnIdle { viewModel.advanceToNext() }
        editor.assertIsFocused().assertTextEquals("Second saved answer").assertSelection(19)
        editor.performSemanticsAction(SemanticsActions.SetSelection) { it(2, 2, false) }

        composeRule.runOnIdle { viewModel.backToPrevious() }
        editor.assertIsFocused().assertTextEquals("First saved answer").assertSelection(18)
    }

    @Test
    fun q06_prefilled_number_starts_at_end_and_keeps_numeric_input() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = SurveyConfigLoader.fromAssets(context, "survey_config10.yaml")
        lateinit var viewModel: SurveyViewModel
        lateinit var editorView: View

        composeRule.setContent {
            editorView = LocalView.current
            val backStack = rememberNavBackStack(FlowHome)
            viewModel = remember {
                SurveyViewModel(backStack, config).apply {
                    goto("Q06")
                    setAnswer("123", "Q06")
                }
            }
            val node by viewModel.currentNode.collectAsState()
            val answers by viewModel.answers.collectAsState()
            val answer = answers[node.id].orEmpty()

            NumberNodeScreen(
                questionId = node.id,
                title = node.title,
                question = node.question,
                value = answer,
                specialOptions = node.specialOptions,
                onValueChange = { viewModel.setAnswer(it.filter(Char::isDigit), node.id) },
                onSpecialSelect = { viewModel.setAnswer(it, node.id) },
                canSubmit = node.isValidNumberAnswer(answer),
                onNext = {},
                onBack = {}
            )
        }

        val editor = composeRule.onNode(hasSetTextAction()).assertIsFocused().assertSelection(3)
        composeRule.runOnIdle {
            val info = EditorInfo()
            checkNotNull(editorView.onCreateInputConnection(info))
            assertEquals(InputType.TYPE_CLASS_NUMBER, info.inputType and InputType.TYPE_MASK_CLASS)
        }
        editor.performSemanticsAction(SemanticsActions.SetSelection) { it(1, 1, false) }
        editor.performTextInput("9")
        editor.assertSelection(2)
        editor.performTextInput("x")
        composeRule.runOnIdle { assertEquals("1923", viewModel.getAnswer("Q06")) }
    }

    private fun SemanticsNodeInteraction.assertSelection(start: Int, end: Int = start) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(start, end)))

    private fun textConfig(): SurveyConfig =
        SurveyConfig(
            graph = SurveyConfig.Graph(
                startId = "Start",
                nodes = listOf(
                    NodeDTO(id = "Start", type = "START", nextId = TEXT_NODE_ID),
                    NodeDTO(
                        id = TEXT_NODE_ID,
                        type = "TEXT",
                        question = "Enter an answer",
                        nextId = SECOND_TEXT_NODE_ID
                    ),
                    NodeDTO(
                        id = SECOND_TEXT_NODE_ID,
                        type = "TEXT",
                        question = "Enter an answer",
                        nextId = "Done"
                    ),
                    NodeDTO(id = "Done", type = "DONE")
                )
            )
        )

    private companion object {
        const val TEXT_NODE_ID = "Text"
        const val SECOND_TEXT_NODE_ID = "TextTwo"
    }
}
