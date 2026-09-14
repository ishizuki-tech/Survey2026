package com.negi.survey.screens

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    override fun configAssetName(): String =
        instrumentationString("configAsset") ?: "survey_config10.yaml"

    @Test
    fun q8_real_model_two_step_acceptance() {
        val fixture = hostAiScreen()
        runTwoStepAcceptance(
            fixture = fixture,
            answer = instrumentationString("answer") ?: MAIN_ANSWER,
            expected = expectedClassification(),
            iteration = 1,
        )
    }

    @Test
    fun q8_ui_only_composer_smoke() {
        val fixture = hostAiScreen()

        composeRule.runOnIdle {
            fixture.survey.resetToStart()
            fixture.survey.goto(NODE_ID)
        }
        val contextKey = "sid=${fixture.survey.sessionId.value}|nid=$NODE_ID"
        waitForFreshContext(contextKey)
        assertEquals(NODE_ID, fixture.survey.currentNode.value.id)

        awaitMainComposerInput("ui-only")
        val input = composeRule.onNode(hasSetTextAction())
        input.performTextReplacement("Test answer")
        input.assertTextEquals("Test answer")
        Log.i(TAG, "REAL_AI_Q8_UI_SMOKE input replacement completed context=$contextKey")
    }

    @Test
    fun q8_real_model_soak_reports_two_step_coverage_when_requested() {
        val iterations = instrumentationInt("ITERATIONS")?.coerceAtLeast(1) ?: return
        val fixture = hostAiScreen()
        val observations = linkedMapOf<String, Int>()

        repeat(iterations) { index ->
            val result = runTwoStepAcceptance(
                fixture = fixture,
                answer = instrumentationString("answer") ?: MAIN_ANSWER,
                expected = expectedClassification(),
                iteration = index + 1,
            )
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
    private fun runTwoStepAcceptance(
        fixture: Fixture,
        answer: String,
        expected: ExpectedClassification?,
        iteration: Int,
    ): IterationResult {
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

        val mainSteps = submitAndAwaitStable(answer, contextKey, "MAIN", fixture)
        val evaluation = mainSteps.first()
        assertEquals(AiViewModel.EvalMode.EVAL_JSON, evaluation.mode)
        val policy = SurveyAiPolicy.evaluate(
            raw = evaluation.raw,
            timedOut = evaluation.timedOut,
            error = evaluation.error,
            remaining = fixture.survey.maxFollowups,
        )
        val fields = evaluationFields(evaluation.raw)
        val entries = fixture.survey.followups.value[NODE_ID].orEmpty()
        val generation = mainSteps.lastOrNull { it.phase == PromptPhase.FOLLOWUP }
        val conversation = vm.conversationStateFlow(contextKey).value

        assertTrue("evaluation JSON must use the required schema", fields != null)
        Log.i(
            TAG,
            "REAL_AI_Q8_FIXTURE iter=$iteration expected=${expected?.wireValue ?: "<none>"} " +
                "answer=$answer raw=${evaluation.raw} score=${fields?.score} " +
                "missingPoints=${fields?.missingPoints} followupNeeded=${fields?.followupNeeded} " +
                "policy=$policy secondStepRan=${generation != null} " +
                "generatedFollowup=${entries.singleOrNull()?.question ?: "<none>"} " +
                "persistedFollowups=${entries.size} remaining=${fixture.survey.remainingFollowups(NODE_ID)} " +
                "composer=${conversation.role} turnCompleted=${conversation.turnCompleted} " +
                "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
        )

        when (expected) {
            ExpectedClassification.COMPLETE -> {
                assertEquals("complete answer must be accepted", SurveyAiDecision.ACHIEVED, policy)
                assertTrue("complete score must be 90..100", fields!!.score in 90..100)
                assertTrue("complete answer has no missing points", fields.missingPoints.isEmpty())
                assertFalse("complete answer needs no follow-up", fields.followupNeeded)
                assertEquals("complete answer only runs evaluation", listOf(PromptPhase.EVAL), mainSteps.map { it.phase })
                assertTrue("complete answer persists no follow-up", entries.isEmpty())
                assertEquals("complete answer consumes no capacity", fixture.survey.maxFollowups, fixture.survey.remainingFollowups(NODE_ID))
                assertEquals("complete answer returns composer to MAIN", AiViewModel.ComposerRole.MAIN, conversation.role)
                assertTrue("complete answer completes the turn", conversation.turnCompleted)
            }

            ExpectedClassification.INCOMPLETE -> {
                assertEquals("incomplete answer must generate a follow-up", SurveyAiDecision.GENERATE, policy)
                assertTrue("incomplete score must be 1..89", fields!!.score in 1..89)
                assertTrue("incomplete answer identifies missing information", fields.missingPoints.isNotEmpty())
                assertTrue("incomplete answer needs a follow-up", fields.followupNeeded)
                assertEquals("incomplete answer runs evaluation then follow-up", listOf(PromptPhase.EVAL, PromptPhase.FOLLOWUP), mainSteps.map { it.phase })
                assertEquals(AiViewModel.EvalMode.FOLLOWUP_JSON_OR_TEXT, generation!!.mode)
                assertEquals("one successful generation persists one follow-up", 1, entries.size)
                val generatedQuestion = entries.single().question
                assertTrue("generated follow-up must be non-blank", generatedQuestion.isNotBlank())
                assertTrue("generated follow-up must be distinct", isDistinctFollowupQuestion(generatedQuestion, emptyList()))
                assertEquals("one persisted follow-up consumes one slot", fixture.survey.maxFollowups - 1, fixture.survey.remainingFollowups(NODE_ID))
                assertNull("a pending follow-up is not terminal", fixture.survey.aiReasons.value[NODE_ID])
                assertEquals("persisted follow-up switches the composer role", AiViewModel.ComposerRole.FOLLOWUP, conversation.role)
                assertFalse("pending follow-up must not complete the turn", conversation.turnCompleted)
            }

            null -> Unit
        }

        val observations = linkedSetOf<String>()
        observations += if (generation == null) "EVAL_ONLY" else "EVAL_TO_FOLLOWUP"
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
        awaitMainComposerInput(label)
        composeRule.onNode(hasSetTextAction()).performTextReplacement(answer)
        composeRule.onNodeWithContentDescription("Send").performClick()
        Log.i(TAG, "REAL_AI_Q8_SUBMITTED label=$label context=$contextKey")

        awaitState("$label inference") {
            val conversation = vm.conversationStateFlow(contextKey).value
            val hasUnansweredFollowup = fixture.survey.followups.value[NODE_ID].orEmpty().any { it.answer == null }
            val hasEvaluationFailure = vm.stepHistory.value.any { step ->
                step.runId > baselineRunId &&
                    step.phase == PromptPhase.EVAL &&
                    SurveyAiPolicy.evaluate(
                        raw = step.raw,
                        timedOut = step.timedOut,
                        error = step.error,
                        remaining = fixture.survey.maxFollowups,
                    ) == SurveyAiDecision.FAILURE
            }
            !vm.loading.value && !vm.isRunning &&
                (hasUnansweredFollowup || conversation.turnCompleted || hasEvaluationFailure)
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

    private fun awaitMainComposerInput(label: String) {
        val startedAt = SystemClock.elapsedRealtime()
        var lastUiState = "not queried"
        composeRule.waitForIdle()
        try {
            composeRule.waitUntil(timeoutMillis = UI_TIMEOUT_MS) {
                runCatching {
                    composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().also {
                        lastUiState = "inputNodePresent"
                    }
                    true
                }.getOrElse { error ->
                    lastUiState = "composeRootUnavailable=${error.javaClass.simpleName}:${error.message}"
                    false
                }
            }
            composeRule.onNode(hasSetTextAction()).fetchSemanticsNode()
            Log.i(
                TAG,
                "REAL_AI_Q8_UI_READY label=$label state=$lastUiState " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "REAL_AI_Q8_UI_UNAVAILABLE label=$label state=$lastUiState " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                error,
            )
            throw error
        }
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

    private fun instrumentationString(key: String): String? =
        InstrumentationRegistry.getArguments().getString(key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun expectedClassification(): ExpectedClassification? =
        when (instrumentationString("expected")?.lowercase()) {
            null -> null
            "complete" -> ExpectedClassification.COMPLETE
            "incomplete" -> ExpectedClassification.INCOMPLETE
            else -> throw AssertionError("expected must be complete or incomplete")
        }

    private fun evaluationFields(raw: String): EvaluationFields? = runCatching {
        val json = Json.parseToJsonElement(raw.trim()) as JsonObject
        val score = (json["score"] as JsonPrimitive).content.toInt()
        val missing = (json["missing_points"] as JsonArray).map { (it as JsonPrimitive).content }
        val followupNeeded = (json["followup_needed"] as JsonPrimitive).content.toBooleanStrict()
        EvaluationFields(score, missing, followupNeeded)
    }.getOrNull()

    private data class Fixture(val survey: SurveyViewModel)

    private data class IterationResult(val observations: Set<String>)

    private data class EvaluationFields(
        val score: Int,
        val missingPoints: List<String>,
        val followupNeeded: Boolean,
    )

    private enum class ExpectedClassification(val wireValue: String) {
        COMPLETE("complete"),
        INCOMPLETE("incomplete"),
    }

    private companion object {
        const val TAG = "RealLiteRtAiFollowup"
        const val NODE_ID = "Q8"
        const val STATE_TIMEOUT_MS = 120_000L
        const val UI_TIMEOUT_MS = 15_000L

        const val MAIN_ANSWER = "Fall armyworm affected my maize."
    }
}
