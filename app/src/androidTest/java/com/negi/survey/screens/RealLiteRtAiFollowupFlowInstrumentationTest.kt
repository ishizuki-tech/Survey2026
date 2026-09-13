package com.negi.survey.screens

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.remember
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.slm.PromptPhase
import com.negi.survey.vm.AiViewModel
import com.negi.survey.vm.AiViewModelSurveyBase
import com.negi.survey.vm.FlowHome
import com.negi.survey.vm.SurveyViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device coverage for the Q8 iterative follow-up flow.
 *
 * This test deliberately drives [AiScreen] through Compose semantics. It does
 * not replace the repository, model, or ViewModels with fakes: the base class
 * supplies ModelAssetRule, the shared real LiteRT/Gemma model, LiteRtRepository,
 * and AiViewModel.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RealLiteRtAiFollowupFlowInstrumentationTest : AiViewModelSurveyBase() {

    @get:Rule
    val composeRule = createComposeRule()

    override fun configAssetName(): String = "survey_config10.yaml"

    @Test
    fun q8_real_model_safety_invariants() {
        val fixture = hostAiScreen()
        runIteration(fixture, iteration = 1)
    }

    @Test
    fun q8_real_model_soak_reports_branch_coverage() {
        val fixture = hostAiScreen()
        val iterations = instrumentationInt("ITERATIONS")?.coerceAtLeast(1) ?: DEFAULT_ITERATIONS
        val observations = linkedMapOf<String, Int>()

        repeat(iterations) { index ->
            val result = runIteration(fixture, iteration = index + 1)
            result.observations.forEach { observation ->
                observations[observation] = (observations[observation] ?: 0) + 1
            }
        }

        Log.i(TAG, "REAL_AI_SOAK_SUMMARY iterations=$iterations observations=$observations")
    }

    private fun hostAiScreen(): Fixture {
        lateinit var vmSurvey: SurveyViewModel

        composeRule.setContent {
            val backStack = rememberNavBackStack(FlowHome)
            vmSurvey = remember { SurveyViewModel(backStack, config) }
            AiScreen(
                nodeId = NODE_ID,
                vmSurvey = vmSurvey,
                vmAI = vm,
                onNext = {},
                onBack = {},
            )
        }
        composeRule.waitForIdle()
        return Fixture(vmSurvey)
    }

    /**
     * A failure from this method aborts its caller. In particular, a timed-out
     * native inference is never followed by another iteration.
     */
    private fun runIteration(fixture: Fixture, iteration: Int): IterationResult {
        val startedAt = SystemClock.elapsedRealtime()
        val previousSessionId = fixture.survey.sessionId.value
        val previousSurveyUuid = fixture.survey.surveyUuid.value

        composeRule.runOnIdle {
            fixture.survey.resetToStart()
            fixture.survey.goto(NODE_ID)
        }

        val sessionId = fixture.survey.sessionId.value
        val surveyUuid = fixture.survey.surveyUuid.value
        val contextKey = "sid=$sessionId|nid=$NODE_ID"
        waitForFreshContext(contextKey)

        assertTrue("sessionId must change for iteration $iteration", sessionId != previousSessionId)
        assertTrue("surveyUuid must change for iteration $iteration", surveyUuid != previousSurveyUuid)
        assertEquals("Q8 must start empty", "", fixture.survey.getAnswer(NODE_ID))
        assertTrue("Q8 follow-ups must start empty", fixture.survey.followups.value[NODE_ID].orEmpty().isEmpty())
        assertEquals(
            "new context must be MAIN",
            AiViewModel.ComposerRole.MAIN,
            vm.conversationStateFlow(contextKey).value.role,
        )
        assertEquals("new context draft must be empty", "", vm.conversationStateFlow(contextKey).value.composerDraft)
        assertTrue(
            "previous iteration user state must not leak into the new context",
            vm.chatHistoryFlow(contextKey).value.none { it.sender == AiViewModel.ChatSender.USER },
        )

        val allSteps = mutableListOf<AiViewModel.StepSnapshot>()
        val mainSteps = submitAndAwaitStable(MAIN_ANSWER, contextKey, "MAIN", fixture)
        allSteps += mainSteps
        var lastChainSteps = mainSteps
        var duplicateCandidateObserved = false

        var answeredFollowups = 0
        while (fixture.survey.followups.value[NODE_ID].orEmpty().any { it.answer == null }) {
            val entriesBefore = fixture.survey.followups.value[NODE_ID].orEmpty()
            assertTrue("FU3 must never be persisted", entriesBefore.size <= MAX_FOLLOWUPS)
            assertTrue("follow-up loop cannot exceed capacity", answeredFollowups < MAX_FOLLOWUPS)

            val conversation = vm.conversationStateFlow(contextKey).value
            assertEquals("unanswered entry requires FOLLOWUP composer", AiViewModel.ComposerRole.FOLLOWUP, conversation.role)
            assertEquals("FOLLOWUP composer must be cleared before input", "", conversation.composerDraft)
            composeRule
                .onNode(hasSetTextAction())
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.EditableText,
                        AnnotatedString(""),
                    ),
                )

            val answer = if (answeredFollowups == 0) FOLLOWUP_1_ANSWER else FOLLOWUP_2_ANSWER
            val newSteps = submitAndAwaitStable(answer, contextKey, "FU${answeredFollowups + 1}", fixture)
            assertEquals(
                "each FOLLOWUP response owns exactly one ONE_STEP validation chain",
                1,
                newSteps.count { it.phase == PromptPhase.ONE_STEP },
            )
            assertTrue(
                "a validation chain may have at most one consistency-repair FOLLOWUP phase",
                newSteps.count { it.phase == PromptPhase.FOLLOWUP } <= 1,
            )
            assertFalse(
                "consistency repair must not recurse",
                newSteps.dropWhile { it.phase == PromptPhase.ONE_STEP }.drop(1)
                    .any { it.phase == PromptPhase.FOLLOWUP },
            )
            allSteps += newSteps
            duplicateCandidateObserved = duplicateCandidateObserved ||
                newSteps.flatMap { it.followups }.any { candidate ->
                    candidate.isNotBlank() && !isDistinctFollowupQuestion(candidate, entriesBefore)
                }
            lastChainSteps = newSteps
            answeredFollowups++
        }

        val entries = fixture.survey.followups.value[NODE_ID].orEmpty()
        val answeredCount = entries.count { it.answer != null }
        val remainingCapacity = followupCapacityRemaining(entries)
        assertTrue("persisted Q8 follow-up count must be <= 2", entries.size <= MAX_FOLLOWUPS)
        assertTrue("answered Q8 follow-ups cannot exceed persisted follow-ups", answeredCount <= entries.size)
        if (answeredCount == MAX_FOLLOWUPS) {
            assertEquals("two answered follow-ups exhaust capacity", 0, remainingCapacity)
        }
        assertFalse("terminal state must have no unanswered Q8 follow-up", entries.any { it.answer == null })
        assertTrue("terminal state must complete the conversation turn", vm.conversationStateFlow(contextKey).value.turnCompleted)

        val finalSteps = allSteps.takeLastWhile { it.phase != PromptPhase.ONE_STEP }.let { tail ->
            if (tail.isEmpty()) allSteps.takeLast(1) else tail
        }
        if (remainingCapacity == 0) {
            assertTrue(
                "capacity-zero final ONE_STEP result cannot request consistency repair",
                finalSteps.none { it.phase == PromptPhase.FOLLOWUP },
            )
        }
        composeRule.onNodeWithText("Next").assertIsEnabled()

        val observations = linkedSetOf<String>()
        when (entries.size) {
            0 -> observations += "MAIN_SUFFICIENT_OR_REJECTED"
            1 -> observations += if (answeredCount == 1) "FU1_TERMINAL" else "MAIN_TO_FU1"
            2 -> observations += "FU2_TERMINAL"
        }
        if (allSteps.any { it.phase == PromptPhase.FOLLOWUP }) observations += "REPAIR_OBSERVED"
        if (mainSteps.any { it.score != null && it.score >= 90 && it.followups.isEmpty() }) {
            observations += "MAIN_SUFFICIENT"
        }
        if (duplicateCandidateObserved) observations += "DUPLICATE_CANDIDATE_OBSERVED"
        if (
            remainingCapacity == 0 &&
                lastChainSteps.any { it.phase == PromptPhase.ONE_STEP && it.score != null && it.score < 90 && it.followups.isEmpty() }
        ) {
            observations += "CAPACITY_ZERO_LOW_SCORE_EMPTY_FU_OBSERVED"
        }
        if (entries.size >= 1) observations += "MAIN_TO_FU1"
        if (entries.size >= 2) observations += "FU1_TO_FU2"

        val outcome = when (entries.size) {
            0 -> "MAIN_TERMINAL"
            1 -> "FU1_TERMINAL"
            else -> "FU2_TERMINAL"
        }
        Log.i(
            TAG,
            "REAL_AI_SOAK iter=$iteration sessionId=$sessionId surveyUuid=$surveyUuid node=$NODE_ID " +
                "runs=${allSteps.joinToString { "${it.runId}:${it.phase}" }} " +
                "persistedFollowups=${entries.size} answeredFollowups=$answeredCount " +
                "capacity=$remainingCapacity repairObserved=${allSteps.any { it.phase == PromptPhase.FOLLOWUP }} " +
                "role=${vm.conversationStateFlow(contextKey).value.role} turnCompleted=true " +
                "elapsedMs=${SystemClock.elapsedRealtime() - startedAt} outcome=$outcome failureReason=none",
        )
        return IterationResult(observations)
    }

    private fun waitForFreshContext(contextKey: String) {
        awaitState("fresh context=$contextKey") {
            vm.conversationStateFlow(contextKey).value.activePromptQuestion.isNotBlank() &&
                !vm.loading.value &&
                !vm.isRunning
        }
    }

    private fun submitAndAwaitStable(
        answer: String,
        contextKey: String,
        label: String,
        fixture: Fixture,
    ): List<AiViewModel.StepSnapshot> {
        val baselineRunId = vm.stepHistory.value.maxOfOrNull { it.runId } ?: 0L
        composeRule.onNode(hasSetTextAction()).performTextReplacement(answer)
        composeRule.onNodeWithContentDescription("Send").performClick()

        awaitState("$label inference") {
            val conversation = vm.conversationStateFlow(contextKey).value
            val hasUnansweredFollowup = fixture.survey.followups.value[NODE_ID].orEmpty().any { it.answer == null }
            !vm.loading.value && !vm.isRunning && (hasUnansweredFollowup || conversation.turnCompleted)
        }

        val newSteps = vm.stepHistory.value.filter { it.runId > baselineRunId }
        assertTrue("$label must complete at least one model step", newSteps.isNotEmpty())
        assertFalse("$label model step timed out; aborting this test method", newSteps.any { it.timedOut })
        assertTrue(
            "$label may contain at most one consistency-repair FOLLOWUP phase",
            newSteps.count { it.phase == PromptPhase.FOLLOWUP } <= 1,
        )
        return newSteps
    }

    private fun awaitState(label: String, predicate: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = STATE_TIMEOUT_MS, condition = predicate)
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "REAL_AI_SOAK_TIMEOUT label=$label loading=${vm.loading.value} " +
                    "running=${vm.isRunning} history=${vm.stepHistory.value.map { "${it.runId}:${it.phase}:timeout=${it.timedOut}" }}",
                error,
            )
            throw AssertionError("Timed out waiting for real LiteRT state: $label", error)
        }
    }

    private fun instrumentationInt(key: String): Int? =
        InstrumentationRegistry.getArguments().getString(key)?.trim()?.toIntOrNull()

    private data class Fixture(val survey: SurveyViewModel)

    private data class IterationResult(val observations: Set<String>)

    private companion object {
        const val TAG = "RealLiteRtAiFollowup"
        const val NODE_ID = "Q8"
        const val MAX_FOLLOWUPS = 2
        const val DEFAULT_ITERATIONS = 10
        const val STATE_TIMEOUT_MS = 120_000L

        const val MAIN_ANSWER = "Not sure."
        const val FOLLOWUP_1_ANSWER = "I don't know."
        const val FOLLOWUP_2_ANSWER = "I still don't know."
    }
}
