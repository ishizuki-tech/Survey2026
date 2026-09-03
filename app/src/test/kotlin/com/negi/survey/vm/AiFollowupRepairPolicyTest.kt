package com.negi.survey.vm

import com.negi.survey.slm.PromptPhase
import com.negi.survey.slm.Repository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFollowupRepairPolicyTest {

    @Test
    fun low_score_one_step_without_followup_repairs_only_after_normal_completion() {
        assertTrue(needs(score = 20, followups = emptyList()))
        assertFalse(needs(score = 95, followups = emptyList()))
        assertFalse(needs(score = 20, followups = listOf("question")))
        assertFalse(needs(score = 20, followups = emptyList(), timedOut = true))
        assertFalse(needs(score = 20, followups = emptyList(), error = "error"))
        assertFalse(needs(score = 20, followups = emptyList(), phase = PromptPhase.FOLLOWUP))
        assertFalse(needs(score = 20, followups = emptyList(), capacityRemaining = 0))
    }

    @Test
    fun repair_success_uses_a_clean_followup_composer_and_revalidation_starts_no_inference_itself() {
        val repository = CountingRepository()
        val vm = AiViewModel(repository)
        val contextKey = "survey:Q8"
        val repairedQuestion = "What information is missing?"

        vm.ensureConversationContext(contextKey, "Root question", "original main answer")
        vm.setFollowupMode(contextKey, repairedQuestion)

        val followup = vm.conversationStateFlow(contextKey).value
        assertTrue(followup.role == AiViewModel.ComposerRole.FOLLOWUP)
        assertTrue(followup.activePromptQuestion == repairedQuestion)
        assertTrue(followup.composerDraft.isEmpty())
        assertFalse(followup.turnCompleted)

        // AiScreen invokes this after SurveyViewModel.answerLastFollowup(), before it starts
        // the owned validation chain.
        vm.beginValidationTurn(contextKey)

        assertTrue(repository.requestCount == 0)
        assertFalse(vm.conversationStateFlow(contextKey).value.turnCompleted)
    }

    @Test
    fun same_context_reentry_does_not_restore_main_draft_while_followup_is_pending() {
        val vm = AiViewModel(CountingRepository())
        val contextKey = "survey:Q8"
        val rootQuestion = "Root question"
        val persistedMainAnswer = "original main answer"
        val followupQuestion = "What information is missing?"

        vm.ensureConversationContext(contextKey, rootQuestion, persistedMainAnswer)
        vm.setFollowupMode(contextKey, followupQuestion)
        assertTrue(vm.conversationStateFlow(contextKey).value.composerDraft.isEmpty())

        vm.ensureConversationContext(contextKey, rootQuestion, persistedMainAnswer)

        val state = vm.conversationStateFlow(contextKey).value
        assertEquals(AiViewModel.ComposerRole.FOLLOWUP, state.role)
        assertEquals(followupQuestion, state.activePromptQuestion)
        assertEquals("", state.composerDraft)
        assertFalse(state.turnCompleted)
    }

    private fun needs(
        score: Int?,
        followups: List<String>,
        timedOut: Boolean = false,
        error: String? = null,
        phase: PromptPhase = PromptPhase.ONE_STEP,
        capacityRemaining: Int = 1,
    ): Boolean =
        AiViewModel.needsFollowupRepair(
            phase,
            score,
            followups,
            timedOut,
            error,
            capacityRemaining
        )

    private class CountingRepository : Repository {
        var requestCount = 0
            private set

        override suspend fun request(prompt: String): Flow<String> {
            requestCount++
            return emptyFlow()
        }

        override fun buildPrompt(userPrompt: String, phase: PromptPhase): String = userPrompt

        override fun buildPrompt(userPrompt: String): String = userPrompt
    }
}
