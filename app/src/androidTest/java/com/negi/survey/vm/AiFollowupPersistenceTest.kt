package com.negi.survey.vm

import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation3.runtime.rememberNavBackStack
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.RequiredComponent
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
    fun blank_followup_is_rejected_at_the_persistence_boundary() {
        lateinit var vm: SurveyViewModel

        composeRule.setContent {
            val backStack = rememberNavBackStack(FlowHome)
            vm = remember { SurveyViewModel(backStack, aiConfig()) }
        }

        composeRule.runOnIdle {
            assertFalse(vm.addFollowupQuestion(AI_NODE_ID, " \n\t "))
            assertTrue(vm.followups.value[AI_NODE_ID].isNullOrEmpty())
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

    @Test
    fun q11_final_evaluation_prompt_keeps_both_answered_followups() {
        lateinit var vm: SurveyViewModel

        composeRule.setContent {
            val backStack = rememberNavBackStack(FlowHome)
            vm = remember { SurveyViewModel(backStack, q11Config()) }
        }

        composeRule.runOnIdle {
            vm.setAnswer(Q11_MAIN_ANSWER, Q11_NODE_ID)
            vm.addFollowupQuestion(Q11_NODE_ID, Q11_YIELD_QUESTION)
            vm.answerLastFollowup(Q11_NODE_ID, Q11_YIELD_ANSWER)
            vm.addFollowupQuestion(Q11_NODE_ID, Q11_PEST_QUESTION)
            vm.answerLastFollowup(Q11_NODE_ID, Q11_PEST_ANSWER)

            val prompt = vm.getEvalPrompt(
                nodeId = Q11_NODE_ID,
                question = Q11_QUESTION,
                answer = Q11_MAIN_ANSWER,
            )

            assertTrue(prompt.contains(Q11_MAIN_ANSWER))
            assertTrue(prompt.contains("Follow-up 1: $Q11_YIELD_QUESTION"))
            assertTrue(prompt.contains("Answer 1: $Q11_YIELD_ANSWER"))
            assertTrue(prompt.contains("Follow-up 2: $Q11_PEST_QUESTION"))
            assertTrue(prompt.contains("Answer 2: $Q11_PEST_ANSWER"))
        }
    }

    @Test
    fun eval_prompt_renders_required_components_once_and_preserves_history() {
        lateinit var vm: SurveyViewModel

        composeRule.setContent {
            val backStack = rememberNavBackStack(FlowHome)
            vm = remember { SurveyViewModel(backStack, q15ComponentsConfig()) }
        }

        composeRule.runOnIdle {
            vm.setAnswer(Q15_MAIN_ANSWER, Q15_NODE_ID)

            val initial = vm.getEvalPrompt(Q15_NODE_ID, Q15_QUESTION, Q15_MAIN_ANSWER)
            assertEquals(
                """
                    Expected answer target: Which animals are fed white maize and how often.
                    Question: $Q15_QUESTION
                    Original answer: $Q15_MAIN_ANSWER
                    Answered followup pairs:${' '}
                    Required components:
                    1. $Q15_ANIMALS_COMPONENT
                    2. $Q15_FREQUENCY_COMPONENT
                """.trimIndent(),
                initial,
            )

            vm.addFollowupQuestion(Q15_NODE_ID, Q15_FOLLOWUP)
            vm.answerLastFollowup(Q15_NODE_ID, "Cattle.")
            val accumulated = vm.getEvalPrompt(Q15_NODE_ID, Q15_QUESTION, Q15_MAIN_ANSWER)

            assertEquals(1, Regex("Required components:").findAll(accumulated).count())
            assertTrue(accumulated.contains("Expected answer target: Which animals are fed white maize and how often."))
            assertTrue(accumulated.contains("Question: $Q15_QUESTION"))
            assertTrue(accumulated.contains("Original answer: $Q15_MAIN_ANSWER"))
            assertTrue(accumulated.contains("Answered followup pairs: Follow-up 1: $Q15_FOLLOWUP\nAnswer 1: Cattle."))
            assertTrue(accumulated.contains("1. $Q15_ANIMALS_COMPONENT"))
            assertTrue(accumulated.contains("2. $Q15_FREQUENCY_COMPONENT"))
        }
    }

    @Test
    fun eval_prompt_without_required_components_preserves_existing_rendering() {
        lateinit var vm: SurveyViewModel

        composeRule.setContent {
            val backStack = rememberNavBackStack(FlowHome)
            vm = remember { SurveyViewModel(backStack, q15ComponentsConfig(requiredComponents = emptyList())) }
        }

        composeRule.runOnIdle {
            vm.setAnswer(Q15_MAIN_ANSWER, Q15_NODE_ID)
            assertEquals(
                """
                    Expected answer target: Which animals are fed white maize and how often.
                    Question: $Q15_QUESTION
                    Original answer: $Q15_MAIN_ANSWER
                    Answered followup pairs:${' '}
                """.trimIndent(),
                vm.getEvalPrompt(Q15_NODE_ID, Q15_QUESTION, Q15_MAIN_ANSWER),
            )
        }
    }

    @Test
    fun catalog_eval_and_followup_prompts_include_resolved_component_once() {
        lateinit var vm: SurveyViewModel
        val evaluation = """{"score":65,"missing_points":["feeding_frequency"],"followup_needed":true}"""

        composeRule.setContent {
            val backStack = rememberNavBackStack(FlowHome)
            vm = remember { SurveyViewModel(backStack, q15CatalogConfig()) }
        }

        composeRule.runOnIdle {
            vm.setAnswer(Q15_MAIN_ANSWER, Q15_NODE_ID)
            val evalPrompt = vm.getEvalPrompt(Q15_NODE_ID, Q15_QUESTION, Q15_MAIN_ANSWER)
            assertTrue(evalPrompt.contains("Required component catalog:\n- animals: $Q15_ANIMALS_COMPONENT\n- feeding_frequency: $Q15_FREQUENCY_COMPONENT"))

            val followupPrompt = vm.getFollowupPrompt(
                Q15_NODE_ID,
                Q15_QUESTION,
                Q15_MAIN_ANSWER,
                evaluation,
                vm.resolvedRequiredComponentsFor(Q15_NODE_ID, listOf("feeding_frequency")),
            )
            assertTrue(followupPrompt.contains(evaluation))
            assertEquals(1, Regex("Resolved missing component:").findAll(followupPrompt).count())
            assertTrue(followupPrompt.contains("ID: feeding_frequency"))
            assertTrue(followupPrompt.contains("Description: $Q15_FREQUENCY_COMPONENT"))
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

    private fun q11Config() = SurveyConfig(
        aiInteraction = SurveyConfig.AiInteraction(maxFollowups = 2),
        graph = SurveyConfig.Graph(
            startId = "Start",
            nodes = listOf(
                NodeDTO(id = "Start", type = "START", nextId = Q11_NODE_ID),
                NodeDTO(id = Q11_NODE_ID, type = "AI", question = Q11_QUESTION, nextId = "Done"),
                NodeDTO(id = "Done", type = "DONE"),
            ),
        ),
        prompts = listOf(
            SurveyConfig.Prompt(
                nodeId = Q11_NODE_ID,
                evalPrompt = """
                    Expected answer target: Priority traits a new maize variety must have to replace the current one, ideally with a measurable target.
                    Question: {{QUESTION}}
                    Original answer: {{ANSWER}}
                    Answered followup pairs: {{HISTORY}}
                """.trimIndent(),
                followupPrompt = "Follow-up: {{EVAL_JSON}}",
            ),
        ),
    )

    private fun q15ComponentsConfig(requiredComponents: List<String> = listOf(
        Q15_ANIMALS_COMPONENT,
        Q15_FREQUENCY_COMPONENT,
    )) = SurveyConfig(
        graph = SurveyConfig.Graph(
            startId = "Start",
            nodes = listOf(
                NodeDTO(id = "Start", type = "START", nextId = Q15_NODE_ID),
                NodeDTO(
                    id = Q15_NODE_ID,
                    type = "AI",
                    question = Q15_QUESTION,
                    nextId = "Done",
                    requiredComponents = requiredComponents,
                ),
                NodeDTO(id = "Done", type = "DONE"),
            ),
        ),
        prompts = listOf(
            SurveyConfig.Prompt(
                nodeId = Q15_NODE_ID,
                evalPrompt = """
                    Expected answer target: Which animals are fed white maize and how often.
                    Question: {{QUESTION}}
                    Original answer: {{ANSWER}}
                    Answered followup pairs: {{HISTORY}}
                """.trimIndent(),
                followupPrompt = "Follow-up: {{EVAL_JSON}}",
            ),
        ),
    )

    private fun q15CatalogConfig() = q15ComponentsConfig(requiredComponents = emptyList()).let { config ->
        config.copy(
            graph = config.graph.copy(
                nodes = config.graph.nodes.map { node ->
                    if (node.id == Q15_NODE_ID) {
                        node.copy(
                            requiredComponentCatalog = listOf(
                                RequiredComponent("animals", Q15_ANIMALS_COMPONENT),
                                RequiredComponent("feeding_frequency", Q15_FREQUENCY_COMPONENT),
                            ),
                        )
                    } else node
                },
            ),
        )
    }

    private companion object {
        const val AI_NODE_ID = "Q8"
        const val Q11_NODE_ID = "Q11"
        const val Q11_QUESTION = "What traits would a new maize variety need to have for you to plant it instead of your current one?"
        const val Q11_MAIN_ANSWER = "It should resist pests and give high yields."
        const val Q11_YIELD_QUESTION = "What specific yield target would make a new maize variety preferable to your current one?"
        const val Q11_YIELD_ANSWER = "At least twenty percent higher yield."
        const val Q11_PEST_QUESTION = "What specific pests should the new maize variety resist?"
        const val Q11_PEST_ANSWER = "Fall armyworm and maize stalk borer."
        const val Q15_NODE_ID = "Q15"
        const val Q15_QUESTION = "If you use white maize to feed livestock, which animals receive it and how often?"
        const val Q15_MAIN_ANSWER = "Yes, I feed white maize to livestock."
        const val Q15_FOLLOWUP = "Which livestock animals receive white maize?"
        const val Q15_ANIMALS_COMPONENT = "If white maize is used for livestock: which animals receive it"
        const val Q15_FREQUENCY_COMPONENT = "If white maize is used for livestock: how often it is fed"
    }
}
