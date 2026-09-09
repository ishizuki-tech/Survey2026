package com.negi.survey.screens

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.remember
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.negi.survey.vm.SurveyAiDecision
import com.negi.survey.vm.SurveyAiPolicy
import com.negi.survey.vm.SurveyViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun q8_real_model_two_step_acceptance() {
        val fixture = hostAiScreen()
        runTwoStepAcceptance(fixture, iteration = 1)
    }

    @Test
    fun q8_real_model_soak_reports_two_step_coverage_when_requested() {
        val iterations = instrumentationInt("ITERATIONS")?.coerceAtLeast(1) ?: return
        val fixture = hostAiScreen()
        val observations = linkedMapOf<String, Int>()

        repeat(iterations) { index ->
            val result = runTwoStepAcceptance(fixture, iteration = index + 1)
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
    private fun runTwoStepAcceptance(fixture: Fixture, iteration: Int): IterationResult {
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
        assertTrue("shipped Q8 must be configured as TWO_STEP", fixture.survey.hasTwoStepPrompt(NODE_ID))
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

        val mainSteps = submitAndAwaitStable(MAIN_ANSWER, contextKey, "MAIN", fixture)
        assertEquals(
            "an incomplete Q8 answer must run evaluation before follow-up generation",
            listOf(PromptPhase.EVAL, PromptPhase.FOLLOWUP),
            mainSteps.map { it.phase },
        )
        val evaluation = mainSteps.first()
        assertEquals(AiViewModel.EvalMode.EVAL_JSON, evaluation.mode)
        assertEquals(
            "only a valid incomplete evaluation may start follow-up generation",
            SurveyAiDecision.GENERATE,
            SurveyAiPolicy.evaluate(
                raw = evaluation.raw,
                timedOut = evaluation.timedOut,
                error = evaluation.error,
                remaining = fixture.survey.maxFollowups,
            ),
        )
        val generation = mainSteps.last()
        assertEquals(AiViewModel.EvalMode.FOLLOWUP_JSON_OR_TEXT, generation.mode)

        val entries = fixture.survey.followups.value[NODE_ID].orEmpty()
        assertEquals("one successful generation must persist one follow-up", 1, entries.size)
        val generatedQuestion = entries.single().question
        assertTrue("generated follow-up must be non-blank", generatedQuestion.isNotBlank())
        assertTrue("generated follow-up must be distinct", isDistinctFollowupQuestion(generatedQuestion, emptyList()))
        assertEquals("one persisted follow-up consumes one slot", fixture.survey.maxFollowups - 1, fixture.survey.remainingFollowups(NODE_ID))
        assertNull("a pending follow-up is not terminal", fixture.survey.aiReasons.value[NODE_ID])

        val conversation = vm.conversationStateFlow(contextKey).value
        assertEquals("persisted follow-up switches the composer role", AiViewModel.ComposerRole.FOLLOWUP, conversation.role)
        assertEquals("FOLLOWUP composer must be cleared before input", "", conversation.composerDraft)
        assertFalse("pending follow-up must not complete the turn", conversation.turnCompleted)
        composeRule
            .onNode(hasSetTextAction())
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString(""),
                ),
            )

        val rootQuestion = fixture.survey.currentNode.value.question
        val evalPrompt = fixture.survey.getEvalPrompt(NODE_ID, rootQuestion, MAIN_ANSWER)
        val followupPrompt = fixture.survey.getFollowupPrompt(NODE_ID, rootQuestion, MAIN_ANSWER, evaluation.raw)
        assertTrue("evaluation prompt includes original question", evalPrompt.contains(rootQuestion))
        assertTrue("evaluation prompt includes original answer", evalPrompt.contains(MAIN_ANSWER))
        assertTrue("follow-up prompt includes original question", followupPrompt.contains(rootQuestion))
        assertTrue("follow-up prompt includes original answer", followupPrompt.contains(MAIN_ANSWER))
        assertTrue("follow-up prompt includes accepted evaluation JSON", followupPrompt.contains(evaluation.raw))
        assertTrue("evaluation JSON supplies missing-point grounding", evaluation.raw.contains("missing_points"))

        fixture.survey.answerLastFollowup(NODE_ID, FOLLOWUP_1_ANSWER)
        val reevalPrompt = fixture.survey.getEvalPrompt(NODE_ID, rootQuestion, MAIN_ANSWER)
        assertTrue("re-evaluation prompt includes answered follow-up question", reevalPrompt.contains(generatedQuestion))
        assertTrue("re-evaluation prompt includes answered follow-up answer", reevalPrompt.contains(FOLLOWUP_1_ANSWER))

        val observations = linkedSetOf<String>()
        observations += "EVAL_TO_FOLLOWUP"
        Log.i(
            TAG,
            "REAL_AI_TWO_STEP iter=$iteration node=$NODE_ID " +
                "runs=${mainSteps.joinToString { "${it.runId}:${it.phase}" }} " +
                "evaluationAccepted=true secondStepRan=true persistedFollowups=${entries.size} " +
                "generatedFollowup=$generatedQuestion elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
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
        const val STATE_TIMEOUT_MS = 120_000L

        const val MAIN_ANSWER = "Fall armyworm affected my maize."
        const val FOLLOWUP_1_ANSWER = "It reduced my harvest by half."
    }
}
