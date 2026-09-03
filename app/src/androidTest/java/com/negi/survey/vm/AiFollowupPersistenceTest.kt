package com.negi.survey.vm

import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation3.runtime.rememberNavBackStack
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AiFollowupPersistenceTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun answering_followup_preserves_main_answer_and_updates_existing_entry() {
        lateinit var vm: SurveyViewModel

        composeRule.setContent {
            val backStack = rememberNavBackStack(FlowHome)
            vm = remember { SurveyViewModel(backStack, aiConfig()) }
        }

        composeRule.runOnIdle {
            vm.setAnswer("main answer", AI_NODE_ID)
            vm.addFollowupQuestion(AI_NODE_ID, "follow-up question")
            vm.answerLastFollowup(AI_NODE_ID, "follow-up answer")

            assertEquals("main answer", vm.getAnswer(AI_NODE_ID))
            val entry = vm.followups.value[AI_NODE_ID]?.singleOrNull()
            assertNotNull(entry)
            assertEquals("follow-up question", entry?.question)
            assertEquals("follow-up answer", entry?.answer)
            assertNotNull(entry?.answeredAt)
        }
    }

    @Test
    fun accumulated_prompt_keeps_main_answer_and_only_includes_answered_followups() {
        lateinit var vm: SurveyViewModel

        composeRule.setContent {
            val backStack = rememberNavBackStack(FlowHome)
            vm = remember { SurveyViewModel(backStack, aiConfig()) }
        }

        composeRule.runOnIdle {
            vm.setAnswer("original main answer", AI_NODE_ID)
            vm.addFollowupQuestion(AI_NODE_ID, "First follow-up?")
            vm.answerLastFollowup(AI_NODE_ID, "first clarification")
            vm.addFollowupQuestion(AI_NODE_ID, "Second follow-up?")

            val prompt = vm.getAccumulatedPrompt(
                nodeId = AI_NODE_ID,
                question = "Question",
                mainAnswer = vm.getAnswer(AI_NODE_ID)
            )

            assertTrue(prompt.contains("Answer: original main answer"))
            assertTrue(prompt.contains("Follow-up 1: First follow-up?"))
            assertTrue(prompt.contains("Answer 1: first clarification"))
            assertFalse(prompt.contains("Follow-up 2: Second follow-up?"))

            vm.answerLastFollowup(AI_NODE_ID, "second clarification")
            val completedPrompt = vm.getAccumulatedPrompt(
                nodeId = AI_NODE_ID,
                question = "Question",
                mainAnswer = vm.getAnswer(AI_NODE_ID)
            )
            assertTrue(completedPrompt.contains("Follow-up 2: Second follow-up?"))
            assertTrue(completedPrompt.contains("Answer 2: second clarification"))
        }
    }

    private fun aiConfig() = SurveyConfig(
        graph = SurveyConfig.Graph(
            startId = "Start",
            nodes = listOf(
                NodeDTO(id = "Start", type = "START", nextId = AI_NODE_ID),
                NodeDTO(id = AI_NODE_ID, type = "AI", question = "Question", nextId = "Done"),
                NodeDTO(id = "Done", type = "DONE"),
            ),
        ),
        prompts = listOf(
            SurveyConfig.Prompt(nodeId = AI_NODE_ID, prompt = "Question: {{QUESTION}} Answer: {{ANSWER}}"),
        ),
    )

    private companion object {
        const val AI_NODE_ID = "Q8"
    }
}
