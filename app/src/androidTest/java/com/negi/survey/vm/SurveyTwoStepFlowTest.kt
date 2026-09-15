package com.negi.survey.vm

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import com.negi.survey.slm.PromptPhase
import com.negi.survey.slm.Repository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Deterministic repository tests: no native engine or model is loaded. */
@RunWith(AndroidJUnit4::class)
class SurveyTwoStepFlowTest {
    private val low = """{"score":30,"missing_points":["yield loss"],"followup_needed":true}"""
    private val high = """{"score":95,"missing_points":[],"followup_needed":false}"""

    @Test fun initial_success_and_zero_cap_evaluate_without_generation() = runBlocking {
        for ((response, reason) in listOf(high to SurveyAiReason.ACHIEVED, low to SurveyAiReason.LIMIT_REACHED)) {
            val (survey, ai, repo) = fixture(0, listOf(response))
            run(survey, ai)
            assertEquals(listOf(PromptPhase.EVAL), repo.phases)
            assertEquals(reason, survey.aiReasons.value["Q8"])
            assertTrue(ai.conversationStateFlow("context").value.turnCompleted)
            assertTrue(survey.followups.value.isEmpty())
        }
    }

    @Test fun initial_low_then_accumulated_success_uses_original_target_and_history() = runBlocking {
        val (survey, ai, repo) = fixture(3, listOf(low, "How much yield was lost?", high))
        run(survey, ai)
        assertEquals(1, survey.followups.value["Q8"]!!.size)
        assertFalse(ai.conversationStateFlow("context").value.turnCompleted)
        survey.answerLastFollowup("Q8", "Two bags per acre")
        run(survey, ai)
        assertEquals(listOf(PromptPhase.EVAL, PromptPhase.FOLLOWUP, PromptPhase.EVAL), repo.phases)
        assertTrue(repo.prompts[1].contains("EVAL_JSON: $low"))
        assertTrue(repo.prompts[2].contains("Original answer: FAW affected my crop"))
        assertTrue(repo.prompts[2].contains("Follow-up 1: How much yield was lost?"))
        assertTrue(repo.prompts[2].contains("Answer 1: Two bags per acre"))
        assertEquals(SurveyAiReason.ACHIEVED, survey.aiReasons.value["Q8"])
    }

    @Test fun caps_one_and_three_always_evaluate_the_last_answer_before_limit() = runBlocking {
        for (cap in listOf(1, 3)) {
            val script = buildList {
                repeat(cap) { add(low); add("What is missing detail ${it + 1}?") }
                add(low)
            }
            val (survey, ai, repo) = fixture(cap, script)
            repeat(cap) { index ->
                run(survey, ai)
                assertEquals(index + 1, survey.followups.value["Q8"]!!.size)
                assertNull(survey.aiReasons.value["Q8"])
                survey.answerLastFollowup("Q8", "Clarification ${index + 1}")
            }
            assertFalse(ai.conversationStateFlow("context").value.turnCompleted)
            run(survey, ai)
            assertEquals(2 * cap + 1, repo.prompts.size)
            assertEquals(PromptPhase.EVAL, repo.phases.last())
            assertTrue(repo.prompts.last().contains("Clarification $cap"))
            assertEquals(SurveyAiReason.LIMIT_REACHED, survey.aiReasons.value["Q8"])
            assertTrue(survey.aiReasonsJson().contains("\"Q8\":\"limit_reached\""))
        }
    }

    @Test fun final_allowed_answer_can_achieve_target() = runBlocking {
        val (survey, ai, repo) = fixture(1, listOf(low, "How much?", high))
        run(survey, ai)
        survey.answerLastFollowup("Q8", "Two bags")
        run(survey, ai)
        assertEquals(3, repo.prompts.size)
        assertEquals(SurveyAiReason.ACHIEVED, survey.aiReasons.value["Q8"])
    }

    @Test fun malformed_empty_generation_and_timeout_preserve_saved_input_for_retry() = runBlocking {
        for (script in listOf(listOf("{}"), listOf(low, ""), listOf("TIMEOUT"), listOf(low, "FAIL"))) {
            val (survey, ai, repo) = fixture(3, script + listOf(low, "How much yield was lost?"))
            ai.updateComposerDraft("context", "FAW affected my crop")
            run(survey, ai)
            assertEquals(SurveyAiReason.FAILURE, survey.aiReasons.value["Q8"])
            assertFalse(ai.conversationStateFlow("context").value.turnCompleted)
            assertTrue(ai.conversationStateFlow("context").value.validationFailed)
            assertEquals("FAW affected my crop", ai.conversationStateFlow("context").value.composerDraft)
            assertEquals(3, survey.remainingFollowups("Q8"))
            val messages = ai.chatHistoryFlow("context").value.count { it.sender == AiViewModel.ChatSender.USER }
            ai.resetStates()
            ai.ensureConversationContext("context", "Original question", survey.getAnswer("Q8"))
            assertTrue(ai.conversationStateFlow("context").value.validationFailed)
            run(survey, ai)
            assertEquals(script.size + 2, repo.prompts.size)
            assertEquals(1, survey.followups.value["Q8"]!!.size)
            assertEquals(messages, ai.chatHistoryFlow("context").value.count { it.sender == AiViewModel.ChatSender.USER })
            assertEquals("FAW affected my crop", survey.getAnswer("Q8"))
        }
    }

    @Test fun contradictory_first_step_never_generates_or_consumes_capacity() = runBlocking {
        val contradictory = listOf(
            """{"score":95,"missing_points":[],"followup_needed":true}""",
            """{"score":95,"missing_points":["yield loss"],"followup_needed":false}""",
            """{"score":30,"missing_points":[],"followup_needed":true}""",
            """{"score":30,"missing_points":["yield loss"],"followup_needed":false}"""
        )
        for (response in contradictory) {
            val (survey, ai, repo) = fixture(2, listOf(response))
            ai.updateComposerDraft("context", "FAW affected my crop")
            run(survey, ai)
            assertEquals(listOf(PromptPhase.EVAL), repo.phases)
            assertEquals(SurveyAiReason.FAILURE, survey.aiReasons.value["Q8"])
            assertTrue(survey.followups.value["Q8"].isNullOrEmpty())
            assertEquals(2, survey.remainingFollowups("Q8"))
            assertTrue(ai.conversationStateFlow("context").value.validationFailed)
            assertEquals("FAW affected my crop", survey.getAnswer("Q8"))
        }
    }

    @Test fun duplicate_after_followup_answer_does_not_consume_capacity_or_duplicate_answer_on_retry() = runBlocking {
        val (survey, ai, repo) = fixture(3, listOf(low, "How much?", low, " how MUCH？ ", low, "Which unit?"))
        run(survey, ai)
        survey.answerLastFollowup("Q8", "Two")
        ai.updateComposerDraft("context", "Two")
        run(survey, ai)
        val saved = survey.followups.value["Q8"]!!.single()
        assertEquals(SurveyAiReason.FAILURE, survey.aiReasons.value["Q8"])
        assertEquals(2, survey.remainingFollowups("Q8"))
        ai.resetStates()
        ai.ensureConversationContext("context", "Original question", survey.getAnswer("Q8"))
        assertEquals("Two", ai.conversationStateFlow("context").value.composerDraft)
        run(survey, ai)
        assertEquals(saved, survey.followups.value["Q8"]!!.first())
        assertEquals(2, survey.followups.value["Q8"]!!.size)
        assertEquals(6, repo.prompts.size)
        assertTrue(repo.prompts.last().contains("Answer 1: Two"))
    }

    @Test fun cancelled_old_chain_cannot_commit_over_replacement() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val survey = survey(2)
        val repo = object : Repository {
            var requests = 0
            override suspend fun request(prompt: String): Flow<String> = flow {
                if (requests++ == 0) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    emit(low)
                } else emit(high)
            }
            override fun buildPrompt(userPrompt: String) = userPrompt
        }
        val ai = AiViewModel(repo, defaultTimeoutMs = 2_000, ioDispatcher = Dispatchers.IO)
        ai.ensureConversationContext("context", "Original question", "draft")
        val old = ai.evaluateSurveyTwoStepAsync(survey, "Q8", "context", "Original question")
        withTimeout(2_000) { entered.await() }
        ai.resetStates()
        val replacement = ai.evaluateSurveyTwoStepAsync(survey, "Q8", "context", "Original question")
        assertTrue(replacement.isActive)
        release.complete(Unit)
        withTimeout(5_000) { replacement.join() }
        old.join()
        assertEquals(SurveyAiReason.ACHIEVED, survey.aiReasons.value["Q8"])
        assertTrue(survey.followups.value.isEmpty())
        assertEquals(2, survey.remainingFollowups("Q8"))
        assertFalse(ai.conversationStateFlow("context").value.validationFailed)
        assertTrue(ai.conversationStateFlow("context").value.turnCompleted)
    }

    @Test fun explicit_refusal_and_legacy_absence_have_distinct_export_values() {
        val survey = survey(2)
        assertEquals("{}", survey.aiReasonsJson())
        survey.setAnswer("No", "Q8")
        assertNull(survey.aiReasons.value["Q8"])
        survey.setAiReason("Q8", SurveyAiReason.UNABLE_REFUSED)
        assertEquals("{\"Q8\":\"unable_refused\"}", survey.aiReasonsJson())
        survey.resetToStart()
        assertEquals("{}", survey.aiReasonsJson())
    }

    private suspend fun run(survey: SurveyViewModel, ai: AiViewModel) {
        withTimeout(5_000) { ai.evaluateSurveyTwoStepAsync(survey, "Q8", "context", "Original question").join() }
    }

    private fun fixture(cap: Int, script: List<String>): Triple<SurveyViewModel, AiViewModel, ScriptedRepository> {
        val survey = survey(cap)
        val repo = ScriptedRepository(script)
        val ai = AiViewModel(repo, defaultTimeoutMs = 50, ioDispatcher = Dispatchers.IO)
        ai.ensureConversationContext("context", "Original question", survey.getAnswer("Q8"))
        return Triple(survey, ai, repo)
    }

    private fun survey(cap: Int) = SurveyViewModel(NavBackStack<NavKey>(FlowHome), SurveyConfig(
        aiInteraction = SurveyConfig.AiInteraction(cap),
        graph = SurveyConfig.Graph("Start", listOf(
            NodeDTO("Start", "START", nextId = "Q8"),
            NodeDTO("Q8", "AI", question = "Original question", nextId = "Done"),
            NodeDTO("Done", "DONE")
        )),
        prompts = listOf(SurveyConfig.Prompt("Q8",
            evalPrompt = "Target: original target\nQuestion: {{QUESTION}}\nOriginal answer: {{ANSWER}}\n{{HISTORY}}",
            followupPrompt = "Target: original target\nQuestion: {{QUESTION}}\nOriginal answer: {{ANSWER}}\n{{HISTORY}}\nEVAL_JSON: {{EVAL_JSON}}"))
    )).also { it.setAnswer("FAW affected my crop", "Q8") }

    private class ScriptedRepository(private val script: List<String>) : Repository {
        val phases = mutableListOf<PromptPhase>()
        val prompts = mutableListOf<String>()
        override fun buildPrompt(userPrompt: String, phase: PromptPhase): String {
            phases += phase
            return userPrompt
        }
        override fun buildPrompt(userPrompt: String) = userPrompt
        override suspend fun request(prompt: String): Flow<String> {
            val result = script[prompts.size]
            prompts += prompt
            return flow {
                when (result) {
                    "TIMEOUT" -> awaitCancellation()
                    "FAIL" -> error("scripted failure")
                    else -> emit(result)
                }
            }
        }
    }
}
