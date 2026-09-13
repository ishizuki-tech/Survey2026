package com.negi.survey

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation3.runtime.rememberNavBackStack
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import com.negi.survey.vm.FlowHome
import com.negi.survey.vm.NodeType
import com.negi.survey.vm.SurveyViewModel
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
    fun text_saved_value_restores_after_back_navigation_and_recomposition() {
        lateinit var viewModel: SurveyViewModel

        composeRule.setContent {
            val backStack = rememberNavBackStack(FlowHome)
            viewModel = remember { SurveyViewModel(backStack, textConfig()) }
            val node by viewModel.currentNode.collectAsState()
            val answers by viewModel.answers.collectAsState()

            if (node.type == NodeType.TEXT) {
                TextNodeScreen(
                    title = node.title,
                    question = node.question,
                    value = answers[node.id].orEmpty(),
                    onValueChange = { viewModel.setAnswer(it, node.id) },
                    onNext = { viewModel.advanceToNext() },
                    onBack = { viewModel.backToPrevious() }
                )
            }
        }

        composeRule.runOnIdle { viewModel.advanceToNext() }
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Existing answer")

        composeRule.runOnIdle { viewModel.advanceToNext() }
        composeRule.onNode(hasSetTextAction()).assertTextEquals("")
        composeRule.runOnIdle { viewModel.backToPrevious() }

        composeRule.onNode(hasSetTextAction()).assertTextEquals("Existing answer")
    }

    private fun textConfig(): SurveyConfig =
        SurveyConfig(
            graph = SurveyConfig.Graph(
                startId = "Start",
                nodes = listOf(
                    NodeDTO(id = "Start", type = "START", nextId = TEXT_NODE_ID),
                    NodeDTO(
                        id = TEXT_NODE_ID,
                        type = "TEXT",
                        question = "First text question",
                        nextId = SECOND_TEXT_NODE_ID
                    ),
                    NodeDTO(
                        id = SECOND_TEXT_NODE_ID,
                        type = "TEXT",
                        question = "Second text question",
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
