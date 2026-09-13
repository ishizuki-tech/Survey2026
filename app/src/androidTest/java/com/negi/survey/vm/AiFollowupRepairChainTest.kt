package com.negi.survey.vm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.negi.survey.slm.PromptPhase
import com.negi.survey.slm.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiFollowupRepairChainTest {

    @Test
    fun contradictory_one_step_runs_one_followup_repair_and_stops() = runBlocking {
        val repo = ScriptedRepository(
            listOf(
                Script.Text("{\"followup_question\":\"\",\"score\":20}"),
                Script.Text("Why was the crop affected?")
            )
        )

        val vm = AiViewModel(repo, defaultTimeoutMs = 1_000, ioDispatcher = Dispatchers.IO)
        runRepairChain(vm).join()

        assertEquals(listOf(PromptPhase.ONE_STEP, PromptPhase.FOLLOWUP), repo.phases)
        assertEquals(2, repo.requestCount)
        assertEquals(listOf(PromptPhase.ONE_STEP, PromptPhase.FOLLOWUP), vm.stepHistory.value.map { it.phase })
        assertEquals(1, vm.stepHistory.value.last().followups.size)
        assertFalse(vm.isRunning)
    }

    @Test
    fun valid_one_step_does_not_start_repair() = runBlocking {
        val repo = ScriptedRepository(listOf(Script.Text("{\"followup_question\":\"\",\"score\":95}")))
        val vm = AiViewModel(repo, defaultTimeoutMs = 1_000, ioDispatcher = Dispatchers.IO)

        runRepairChain(vm).join()

        assertEquals(listOf(PromptPhase.ONE_STEP), repo.phases)
        assertEquals(1, repo.requestCount)
        assertFalse(vm.isRunning)
    }

    @Test
    fun capacity_exhausted_low_score_empty_result_does_not_start_repair() = runBlocking {
        val repo = ScriptedRepository(listOf(Script.Text("{\"followup_question\":\"\",\"score\":20}")))
        val vm = AiViewModel(repo, defaultTimeoutMs = 1_000, ioDispatcher = Dispatchers.IO)

        vm.evaluateConditionalTwoStepAsync(
            firstPrompt = "Expected answer target: target\nQuestion: q\nAnswer: a",
            firstPhase = PromptPhase.ONE_STEP,
            proceedOnTimeout = false,
            shouldRunSecond = { result ->
                AiViewModel.needsFollowupRepair(
                    phase = PromptPhase.ONE_STEP,
                    score = result.score,
                    followups = result.followups,
                    timedOut = result.timedOut,
                    error = result.error,
                    capacityRemaining = 0,
                )
            },
            buildSecondPrompt = { "unused" }
        ).join()

        assertEquals(listOf(PromptPhase.ONE_STEP), repo.phases)
        assertEquals(1, repo.requestCount)
    }

    @Test
    fun empty_or_failed_repair_never_starts_a_third_request() = runBlocking {
        listOf(
            Script.Text(""),
            Script.Failure,
        ).forEach { repairOutcome ->
            val repo = ScriptedRepository(
                listOf(
                    Script.Text("{\"followup_question\":\"\",\"score\":20}"),
                    repairOutcome
                )
            )
            val vm = AiViewModel(repo, defaultTimeoutMs = 1_000, ioDispatcher = Dispatchers.IO)

            runRepairChain(vm).join()

            assertEquals(listOf(PromptPhase.ONE_STEP, PromptPhase.FOLLOWUP), repo.phases)
            assertEquals(2, repo.requestCount)
            assertFalse(vm.isRunning)
            assertTrue(vm.stepHistory.value.last().followups.isEmpty())
        }
    }

    @Test
    fun repaired_followup_enters_clean_followup_composer_without_starting_inference() {
        val repo = ScriptedRepository(emptyList())
        val vm = AiViewModel(repo, defaultTimeoutMs = 1_000, ioDispatcher = Dispatchers.IO)
        val contextKey = "survey:Q8"
        val repairedQuestion = "What information is missing?"

        vm.ensureConversationContext(
            contextKey = contextKey,
            rootQuestion = "Root question",
            initialDraft = "original main answer"
        )
        vm.setFollowupMode(contextKey, repairedQuestion)

        val followupState = vm.conversationStateFlow(contextKey).value
        assertEquals(AiViewModel.ComposerRole.FOLLOWUP, followupState.role)
        assertEquals(repairedQuestion, followupState.activePromptQuestion)
        assertEquals("", followupState.composerDraft)
        assertFalse(followupState.turnCompleted)

        // This is the state transition invoked after answerLastFollowup() in AiScreen.submit(),
        // before it starts the owned accumulated validation chain.
        vm.beginValidationTurn(contextKey)

        assertEquals(0, repo.requestCount)
        assertEquals(AiViewModel.ComposerRole.FOLLOWUP, vm.conversationStateFlow(contextKey).value.role)
        assertFalse(vm.conversationStateFlow(contextKey).value.turnCompleted)
    }

    private fun runRepairChain(vm: AiViewModel) =
        vm.evaluateConditionalTwoStepAsync(
            firstPrompt = "Expected answer target: target\nQuestion: q\nAnswer: a",
            firstPhase = PromptPhase.ONE_STEP,
            proceedOnTimeout = false,
            shouldRunSecond = { result ->
                AiViewModel.needsFollowupRepair(
                    phase = PromptPhase.ONE_STEP,
                    score = result.score,
                    followups = result.followups,
                    timedOut = result.timedOut,
                    error = result.error,
                )
            },
            buildSecondPrompt = { "Expected answer target: target\nQuestion: q\nAnswer: a" }
        )

    private sealed interface Script {
        data class Text(val value: String) : Script
        data object Failure : Script
    }

    private class ScriptedRepository(
        private val script: List<Script>
    ) : Repository {
        private var next = 0
        var requestCount = 0
            private set
        val phases = mutableListOf<PromptPhase>()

        override suspend fun request(prompt: String): Flow<String> {
            requestCount++
            val outcome = script[next++]
            return flow {
                when (outcome) {
                    is Script.Text -> emit(outcome.value)
                    Script.Failure -> error("repair failure")
                }
            }
        }

        override fun buildPrompt(userPrompt: String, phase: PromptPhase): String {
            phases += phase
            return userPrompt
        }

        override fun buildPrompt(userPrompt: String): String = userPrompt
    }
}
