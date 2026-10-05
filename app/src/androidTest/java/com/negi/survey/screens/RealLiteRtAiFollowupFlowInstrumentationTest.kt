package com.negi.survey.screens

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.negi.survey.vm.parseStrictModelJsonObject
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device evidence runner for selected iterative follow-up fixtures.
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
        instrumentationString("configAsset") ?: "survey_config_sw_10.yaml"

    @Test
    fun q7_q16_real_model_fixture_runner() {
        val iterations = instrumentationInt("ITERATIONS")?.coerceAtLeast(1) ?: 1
        val report = ResultReporter(appCtx.filesDir)
        val classifications = linkedMapOf<ResultClassification, Int>()
        var stoppedAfterTimeout = false
        val fixture = hostAiScreen()

        fixtureIterations@ for (iteration in 1..iterations) {
            fixtureCases@ for (fixtureCase in FIXTURES) {
                val attempt = runCatching {
                    runFixture(
                        fixture = fixture,
                        fixtureCase = fixtureCase,
                        iteration = iteration,
                    )
                }
                val result = attempt.getOrElse { error ->
                    failedFixtureResult(fixtureCase, iteration, error)
                }
                report.append(result)
                classifications[result.classification] =
                    (classifications[result.classification] ?: 0) + 1

                attempt.exceptionOrNull()?.let { error ->
                    if (result.classification != ResultClassification.TIMEOUT) throw error
                }

                // Native timeouts can leave cleanup in progress; preserve the recorded result
                // but do not begin another real inference in this test method. Timeout is a
                // report-only model outcome, not an infrastructure failure.
                if (result.classification == ResultClassification.TIMEOUT) {
                    stoppedAfterTimeout = true
                    break@fixtureCases
                }
            }
            if (stoppedAfterTimeout) break@fixtureIterations
        }

        Log.i(
            TAG,
            "REAL_AI_FIXTURE_SUMMARY iterations=$iterations fixtures=${FIXTURES.map { it.nodeId }} " +
                "stoppedAfterTimeout=$stoppedAfterTimeout " +
                "classifications=$classifications report=${report.file.absolutePath}",
        )
    }

    private fun hostAiScreen(): Fixture {
        lateinit var vmSurvey: SurveyViewModel
        lateinit var activeNodeId: MutableState<String>

        composeRule.setContent {
            val backStack = rememberNavBackStack(FlowHome)
            vmSurvey = remember { SurveyViewModel(backStack, config) }
            activeNodeId = remember { mutableStateOf(FIXTURES.first().nodeId) }
            AiScreen(
                nodeId = activeNodeId.value,
                vmSurvey = vmSurvey,
                vmAI = vm,
                onNext = {},
                onBack = {},
            )
        }
        composeRule.waitForIdle()
        return Fixture(vmSurvey) { nodeId -> activeNodeId.value = nodeId }
    }

    /**
     * Each fixture enters a new survey/session context while intentionally reusing
     * the already-warm model runtime.
     */
    private fun runFixture(
        fixture: Fixture,
        fixtureCase: SemanticFixture,
        iteration: Int,
    ): FixtureResult {
        val startedAt = SystemClock.elapsedRealtime()
        val previousSessionId = fixture.survey.sessionId.value
        val previousSurveyUuid = fixture.survey.surveyUuid.value
        val nodeId = fixtureCase.nodeId

        composeRule.runOnIdle { fixture.selectNode(nodeId) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            fixture.survey.resetToStart()
            fixture.survey.goto(nodeId)
        }

        val sessionId = fixture.survey.sessionId.value
        val surveyUuid = fixture.survey.surveyUuid.value
        val contextKey = "sid=$sessionId|nid=$nodeId"
        waitForFreshContext(contextKey)

        assertTrue("sessionId must change for $nodeId iteration $iteration", sessionId != previousSessionId)
        assertTrue("surveyUuid must change for $nodeId iteration $iteration", surveyUuid != previousSurveyUuid)
        assertTrue("$nodeId must be configured as TWO_STEP", fixture.survey.hasTwoStepPrompt(nodeId))
        assertEquals("$nodeId must start empty", "", fixture.survey.getAnswer(nodeId))
        assertTrue("$nodeId follow-ups must start empty", fixture.survey.followups.value[nodeId].orEmpty().isEmpty())
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

        val submission = submitAndAwaitStable(fixtureCase.answer, contextKey, nodeId, fixture)
        val mainSteps = submission.steps
        val evaluation = mainSteps.first { it.phase == PromptPhase.EVAL }
        assertEquals(AiViewModel.EvalMode.EVAL_JSON, evaluation.mode)
        val requiredComponentIds = fixture.survey.requiredComponentCatalogFor(nodeId).map { it.id }
        val admission = SurveyAiPolicy.evaluateAdmission(
            raw = evaluation.raw,
            timedOut = evaluation.timedOut,
            error = evaluation.error,
            remaining = fixture.survey.maxFollowups,
            requiredComponents = fixture.survey.requiredComponentsFor(nodeId),
            requiredComponentIds = requiredComponentIds,
        )
        val parseSuccess = parseStrictModelJsonObject(evaluation.raw) != null
        val entries = fixture.survey.followups.value[nodeId].orEmpty()
        val generation = mainSteps.lastOrNull { it.phase == PromptPhase.FOLLOWUP }
        val conversation = vm.conversationStateFlow(contextKey).value
        val expectedIdsMatch = fixtureCase.expectedMissingIds?.let { it == admission.missingPoints }
        val requiredMissingIdsMatch = fixtureCase.requiredMissingIds.takeIf { it.isNotEmpty() }
            ?.let { requiredIds -> requiredIds.all { it in admission.missingPoints } }
        val allowedMissingIdsMatch = fixtureCase.allowedMissingIds?.let { allowedIds ->
            admission.missingPoints.all { it in allowedIds }
        }
        val policyAcceptedFollowup = generation?.followups?.firstOrNull()?.let { candidate ->
            SurveyAiPolicy.acceptQuestion(
                raw = candidate,
                timedOut = generation.timedOut,
                error = generation.error,
                existing = emptyList(),
            )
        }
        val acceptedFollowup = entries.singleOrNull()?.question
        val followupReasksSuppliedInformation = fixtureCase.rejectDirectSuppliedInformationReask
            .takeIf { it }
            ?.let { isDirectQ14SuppliedInformationReask(acceptedFollowup) }
        val deterministicExpectationMatch = listOfNotNull(
            expectedIdsMatch,
            requiredMissingIdsMatch,
            allowedMissingIdsMatch,
            followupReasksSuppliedInformation?.not(),
        ).takeIf { it.isNotEmpty() }?.all { it }
        val classification = classify(
            fixtureCase = fixtureCase,
            parseSuccess = parseSuccess,
            admission = admission.decision,
            deterministicExpectationMatch = deterministicExpectationMatch,
            generation = generation,
            policyAcceptedFollowup = policyAcceptedFollowup,
            acceptedFollowup = acceptedFollowup,
            steps = mainSteps,
        )

        return FixtureResult(
            nodeId = nodeId,
            iteration = iteration,
            answer = fixtureCase.answer,
            semanticUnresolvedTarget = fixtureCase.semanticUnresolvedTarget,
            suppliedInformation = fixtureCase.suppliedInformation,
            expectedDecision = fixtureCase.expectedDecision,
            expectedMissingIds = fixtureCase.expectedMissingIds,
            requiredMissingIds = fixtureCase.requiredMissingIds,
            allowedMissingIds = fixtureCase.allowedMissingIds,
            rawEval = evaluation.raw,
            parseSuccess = parseSuccess,
            policyDecision = admission.decision,
            canonicalMissingPoints = admission.missingPoints,
            expectedMissingIdsMatch = expectedIdsMatch,
            requiredMissingIdsMatch = requiredMissingIdsMatch,
            allowedMissingIdsMatch = allowedMissingIdsMatch,
            followupReasksSuppliedInformation = followupReasksSuppliedInformation,
            deterministicExpectationMatch = deterministicExpectationMatch,
            rawFollowup = generation?.raw,
            policyFollowupAccepted = policyAcceptedFollowup != null,
            followupAccepted = acceptedFollowup != null,
            acceptedFollowup = acceptedFollowup,
            evalTimedOut = evaluation.timedOut,
            followupTimedOut = generation?.timedOut ?: false,
            evalError = evaluation.error,
            followupError = generation?.error,
            evalObservedElapsedMs = submission.evalObservedElapsedMs,
            followupObservedElapsedMs = submission.followupObservedElapsedMs,
            overallElapsedMs = SystemClock.elapsedRealtime() - startedAt,
            composerRole = conversation.role.name,
            classification = classification,
        )
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
        nodeId: String,
        fixture: Fixture,
    ): SubmissionResult {
        val baselineRunId = vm.stepHistory.value.maxOfOrNull { it.runId } ?: 0L
        val startedAt = SystemClock.elapsedRealtime()
        var evalObservedElapsedMs: Long? = null
        var followupObservedElapsedMs: Long? = null
        awaitMainComposerInput(nodeId)
        composeRule.onNode(hasSetTextAction()).performTextReplacement(answer)
        composeRule.onNodeWithContentDescription("Send").performClick()
        Log.i(TAG, "REAL_AI_FIXTURE_SUBMITTED node=$nodeId context=$contextKey")

        awaitState("$nodeId inference") {
            val newSteps = vm.stepHistory.value.filter { it.runId > baselineRunId }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            if (evalObservedElapsedMs == null && newSteps.any { it.phase == PromptPhase.EVAL }) {
                evalObservedElapsedMs = elapsedMs
            }
            if (followupObservedElapsedMs == null && newSteps.any { it.phase == PromptPhase.FOLLOWUP }) {
                followupObservedElapsedMs = elapsedMs
            }
            val conversation = vm.conversationStateFlow(contextKey).value
            val hasUnansweredFollowup = fixture.survey.followups.value[nodeId].orEmpty().any { it.answer == null }
            val hasEvaluationFailure = newSteps.any { step ->
                    step.phase == PromptPhase.EVAL &&
                    SurveyAiPolicy.evaluateAdmission(
                        raw = step.raw,
                        timedOut = step.timedOut,
                        error = step.error,
                        remaining = fixture.survey.maxFollowups,
                        requiredComponents = fixture.survey.requiredComponentsFor(nodeId),
                        requiredComponentIds = fixture.survey.requiredComponentCatalogFor(nodeId).map { it.id },
                    ).decision == SurveyAiDecision.FAILURE
            }
            !vm.loading.value && !vm.isRunning &&
                (hasUnansweredFollowup || conversation.turnCompleted || hasEvaluationFailure)
        }

        val newSteps = vm.stepHistory.value.filter { it.runId > baselineRunId }
        assertTrue("$nodeId must complete at least one model step", newSteps.isNotEmpty())
        assertTrue(
            "$nodeId may contain at most one consistency-repair FOLLOWUP phase",
            newSteps.count { it.phase == PromptPhase.FOLLOWUP } <= 1,
        )
        return SubmissionResult(newSteps, evalObservedElapsedMs, followupObservedElapsedMs)
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
                "REAL_AI_FIXTURE_UI_READY label=$label state=$lastUiState " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "REAL_AI_FIXTURE_UI_UNAVAILABLE label=$label state=$lastUiState " +
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

    /**
     * Q14 already establishes that maize is fed to livestock. This deliberately
     * narrow check only catches a direct yes/no repetition of that supplied fact;
     * the adequacy of the remaining Swahili question stays semantic-review evidence.
     */
    private fun isDirectQ14SuppliedInformationReask(question: String?): Boolean {
        val normalized = question?.lowercase()?.replace(Regex("\\s+"), " ")?.trim() ?: return false
        return normalized.startsWith("je unawapa mifugo mahindi") ||
            normalized.startsWith("je unawalisha mifugo mahindi")
    }

    private fun instrumentationInt(key: String): Int? =
        InstrumentationRegistry.getArguments().getString(key)?.trim()?.toIntOrNull()

    private fun instrumentationString(key: String): String? =
        InstrumentationRegistry.getArguments().getString(key)?.trim()?.takeIf { it.isNotEmpty() }

    private data class Fixture(
        val survey: SurveyViewModel,
        val selectNode: (String) -> Unit,
    )

    private data class SubmissionResult(
        val steps: List<AiViewModel.StepSnapshot>,
        val evalObservedElapsedMs: Long?,
        val followupObservedElapsedMs: Long?,
    )

    private data class SemanticFixture(
        val nodeId: String,
        val answer: String,
        val expectedDecision: SurveyAiDecision,
        val expectedMissingIds: List<String>?,
        val semanticUnresolvedTarget: String,
        val suppliedInformation: String,
        val expectsFollowup: Boolean,
        val requiredMissingIds: List<String> = emptyList(),
        val allowedMissingIds: List<String>? = null,
        val rejectDirectSuppliedInformationReask: Boolean = false,
    )

    private enum class ResultClassification {
        PASS,
        EXPECTATION_MISMATCH,
        MALFORMED_EVAL,
        NO_FOLLOWUP,
        FOLLOWUP_REJECTED,
        TIMEOUT,
        RUNTIME_ERROR,
        SEMANTIC_REVIEW,
    }

    private data class FixtureResult(
        val nodeId: String,
        val iteration: Int,
        val answer: String,
        val semanticUnresolvedTarget: String,
        val suppliedInformation: String,
        val expectedDecision: SurveyAiDecision,
        val expectedMissingIds: List<String>?,
        val requiredMissingIds: List<String>,
        val allowedMissingIds: List<String>?,
        val rawEval: String,
        val parseSuccess: Boolean,
        val policyDecision: SurveyAiDecision,
        val canonicalMissingPoints: List<String>,
        val expectedMissingIdsMatch: Boolean?,
        val requiredMissingIdsMatch: Boolean?,
        val allowedMissingIdsMatch: Boolean?,
        val followupReasksSuppliedInformation: Boolean?,
        val deterministicExpectationMatch: Boolean?,
        val rawFollowup: String?,
        val policyFollowupAccepted: Boolean,
        val followupAccepted: Boolean,
        val acceptedFollowup: String?,
        val evalTimedOut: Boolean,
        val followupTimedOut: Boolean,
        val evalError: String?,
        val followupError: String?,
        val evalObservedElapsedMs: Long?,
        val followupObservedElapsedMs: Long?,
        val overallElapsedMs: Long,
        val composerRole: String,
        val classification: ResultClassification,
    )

    private fun classify(
        fixtureCase: SemanticFixture,
        parseSuccess: Boolean,
        admission: SurveyAiDecision,
        deterministicExpectationMatch: Boolean?,
        generation: AiViewModel.StepSnapshot?,
        policyAcceptedFollowup: String?,
        acceptedFollowup: String?,
        steps: List<AiViewModel.StepSnapshot>,
    ): ResultClassification = when {
        steps.any { it.timedOut } -> ResultClassification.TIMEOUT
        steps.any { it.error != null } -> ResultClassification.RUNTIME_ERROR
        !parseSuccess || admission == SurveyAiDecision.FAILURE -> ResultClassification.MALFORMED_EVAL
        admission != fixtureCase.expectedDecision || deterministicExpectationMatch == false ->
            ResultClassification.EXPECTATION_MISMATCH
        fixtureCase.expectsFollowup && generation == null -> ResultClassification.NO_FOLLOWUP
        fixtureCase.expectsFollowup && (policyAcceptedFollowup == null || acceptedFollowup == null) ->
            ResultClassification.FOLLOWUP_REJECTED
        fixtureCase.expectedMissingIds == null -> ResultClassification.SEMANTIC_REVIEW
        else -> ResultClassification.PASS
    }

    private fun failedFixtureResult(
        fixtureCase: SemanticFixture,
        iteration: Int,
        error: Throwable,
    ): FixtureResult {
        val timedOut = error.message?.contains("Timed out waiting for real LiteRT state") == true
        return FixtureResult(
            nodeId = fixtureCase.nodeId,
            iteration = iteration,
            answer = fixtureCase.answer,
            semanticUnresolvedTarget = fixtureCase.semanticUnresolvedTarget,
            suppliedInformation = fixtureCase.suppliedInformation,
            expectedDecision = fixtureCase.expectedDecision,
            expectedMissingIds = fixtureCase.expectedMissingIds,
            requiredMissingIds = fixtureCase.requiredMissingIds,
            allowedMissingIds = fixtureCase.allowedMissingIds,
            rawEval = vm.raw.value.orEmpty(),
            parseSuccess = false,
            policyDecision = SurveyAiDecision.FAILURE,
            canonicalMissingPoints = emptyList(),
            expectedMissingIdsMatch = null,
            requiredMissingIdsMatch = null,
            allowedMissingIdsMatch = null,
            followupReasksSuppliedInformation = null,
            deterministicExpectationMatch = null,
            rawFollowup = null,
            policyFollowupAccepted = false,
            followupAccepted = false,
            acceptedFollowup = null,
            evalTimedOut = timedOut,
            followupTimedOut = false,
            evalError = error.message ?: error.javaClass.simpleName,
            followupError = null,
            evalObservedElapsedMs = null,
            followupObservedElapsedMs = null,
            overallElapsedMs = 0L,
            composerRole = "UNKNOWN",
            classification = if (timedOut) ResultClassification.TIMEOUT else ResultClassification.RUNTIME_ERROR,
        )
    }

    /** Test-only NDJSON evidence sink; it is intentionally outside production trace directories. */
    private class ResultReporter(filesDir: File) {
        val file = File(
            File(filesDir, "real_model_test_results").apply { mkdirs() },
            "issue81_${System.currentTimeMillis()}.ndjson",
        )

        fun append(result: FixtureResult) {
            val record = JSONObject().apply {
                put("schemaVersion", 1)
                put("nodeId", result.nodeId)
                put("iteration", result.iteration)
                put("initialAnswer", result.answer)
                put("semanticUnresolvedTarget", result.semanticUnresolvedTarget)
                put("suppliedInformation", result.suppliedInformation)
                put("expectedPolicyDecision", result.expectedDecision.name)
                put("expectedMissingIds", result.expectedMissingIds?.let { JSONArray(it) } ?: JSONObject.NULL)
                put("requiredMissingIds", JSONArray(result.requiredMissingIds))
                put("allowedMissingIds", result.allowedMissingIds?.let { JSONArray(it) } ?: JSONObject.NULL)
                put("rawEval", result.rawEval)
                put("productionParseSuccess", result.parseSuccess)
                put("productionPolicyDecision", result.policyDecision.name)
                put("canonicalMissingPoints", JSONArray(result.canonicalMissingPoints))
                put("expectedMissingIdsMatch", result.expectedMissingIdsMatch ?: JSONObject.NULL)
                put("requiredMissingIdsMatch", result.requiredMissingIdsMatch ?: JSONObject.NULL)
                put("allowedMissingIdsMatch", result.allowedMissingIdsMatch ?: JSONObject.NULL)
                put("followupReasksSuppliedInformation", result.followupReasksSuppliedInformation ?: JSONObject.NULL)
                put("deterministicExpectationMatch", result.deterministicExpectationMatch ?: JSONObject.NULL)
                put("rawFollowup", result.rawFollowup ?: JSONObject.NULL)
                put("productionFollowupPolicyAccepted", result.policyFollowupAccepted)
                put("followupAccepted", result.followupAccepted)
                put("acceptedFollowup", result.acceptedFollowup ?: JSONObject.NULL)
                put("evalTimedOut", result.evalTimedOut)
                put("followupTimedOut", result.followupTimedOut)
                put("evalError", result.evalError ?: JSONObject.NULL)
                put("followupError", result.followupError ?: JSONObject.NULL)
                put("evalObservedElapsedMs", result.evalObservedElapsedMs ?: JSONObject.NULL)
                put("followupObservedElapsedMs", result.followupObservedElapsedMs ?: JSONObject.NULL)
                put("overallElapsedMs", result.overallElapsedMs)
                put("composerRole", result.composerRole)
                put("classification", result.classification.name)
            }
            file.appendText(record.toString() + "\n")
            Log.i(TAG, "REAL_AI_FIXTURE_NDJSON $record")
            Log.i(
                TAG,
                "REAL_AI_FIXTURE_RESULT node=${result.nodeId} iter=${result.iteration} " +
                    "classification=${result.classification} decision=${result.policyDecision} " +
                    "missing=${result.canonicalMissingPoints} report=${file.absolutePath}",
            )
        }
    }

    private companion object {
        const val TAG = "RealLiteRtAiFollowup"
        const val STATE_TIMEOUT_MS = 120_000L
        const val UI_TIMEOUT_MS = 15_000L

        val FIXTURES = listOf(
            SemanticFixture(
                nodeId = "Q7",
                answer = "Ndiyo, funza jeshi walikuja shambani kwangu mwaka jana. Ilikuwa mbaya kweli.",
                expectedDecision = SurveyAiDecision.GENERATE,
                expectedMissingIds = null,
                semanticUnresolvedTarget = "Concrete FAW damage or loss",
                suppliedInformation = "FAW occurrence is supplied; damage/loss is unresolved.",
                expectsFollowup = true,
            ),
            SemanticFixture(
                nodeId = "Q8",
                answer = "Ningekubali kupoteza kidogo tu, siyo mengi.",
                expectedDecision = SurveyAiDecision.GENERATE,
                expectedMissingIds = null,
                semanticUnresolvedTarget = "Measurable maximum acceptable yield loss",
                suppliedInformation = "Some loss is acceptable; the maximum measurable loss is unresolved.",
                expectsFollowup = true,
            ),
            SemanticFixture(
                nodeId = "Q9",
                answer = "Kama uharibifu ni mkubwa sana, ningebadilisha aina ya mbegu.",
                expectedDecision = SurveyAiDecision.GENERATE,
                expectedMissingIds = null,
                semanticUnresolvedTarget = "Clear damage threshold for switching variety",
                suppliedInformation = "Switching variety is supplied; a clear damage threshold is unresolved.",
                expectsFollowup = true,
            ),
            SemanticFixture(
                nodeId = "Q10",
                answer = "Ingehitaji tu kuwa nzuri kuliko hii ninayopanda sasa.",
                expectedDecision = SurveyAiDecision.GENERATE,
                expectedMissingIds = null,
                semanticUnresolvedTarget = "Specific priority trait required in the replacement variety",
                suppliedInformation = "A better replacement variety is desired; its priority trait is unresolved.",
                expectsFollowup = true,
            ),
            SemanticFixture(
                nodeId = "Q11",
                answer = "Hata kama mavuno ni machache, bado ningeendelea kupanda, kwa sababu nimeizoea.",
                expectedDecision = SurveyAiDecision.GENERATE,
                expectedMissingIds = null,
                semanticUnresolvedTarget = "Lowest acceptable yield in a bad season",
                suppliedInformation = "The farmer would continue planting; the lowest acceptable yield is unresolved.",
                expectsFollowup = true,
            ),
            SemanticFixture(
                nodeId = "Q12",
                answer = "Ukame ni mbaya wakati wowote, lakini hasa mvua zikikatika mapema.",
                expectedDecision = SurveyAiDecision.GENERATE,
                expectedMissingIds = null,
                semanticUnresolvedTarget = "Specific maize growth stage",
                suppliedInformation = "Early rain cessation is supplied; no maize growth stage is supplied.",
                expectsFollowup = true,
            ),
            SemanticFixture(
                nodeId = "Q13",
                answer = "Aah, tunagawa tu kama kawaida.",
                expectedDecision = SurveyAiDecision.GENERATE,
                expectedMissingIds = null,
                semanticUnresolvedTarget = "Factor or criterion used to decide maize allocation",
                suppliedInformation = "Usual allocation occurs; the deciding factor or criterion is unresolved.",
                expectsFollowup = true,
            ),
            SemanticFixture(
                nodeId = "Q14",
                answer = "Ndiyo, nawapa mifugo mahindi wakati mwingine.",
                expectedDecision = SurveyAiDecision.GENERATE,
                expectedMissingIds = null,
                requiredMissingIds = listOf("animals"),
                allowedMissingIds = listOf("animals", "feeding_frequency"),
                rejectDirectSuppliedInformationReask = true,
                semanticUnresolvedTarget = "Livestock species and, if needed, feeding frequency",
                suppliedInformation = "Feeding maize to livestock is supplied; animals are unresolved and frequency may be supplied by 'wakati mwingine'.",
                expectsFollowup = true,
            ),
            SemanticFixture(
                nodeId = "Q15",
                answer = "Mara nyingi nanunua dukani.",
                expectedDecision = SurveyAiDecision.GENERATE,
                expectedMissingIds = listOf("source_reason"),
                semanticUnresolvedTarget = "Reason for preferring the seed source",
                suppliedInformation = "seed_source is supplied; source_reason is unresolved.",
                expectsFollowup = true,
            ),
            SemanticFixture(
                nodeId = "Q16",
                answer = "Nauza kwa wanunuzi wanaokuja kijijini.",
                expectedDecision = SurveyAiDecision.GENERATE,
                expectedMissingIds = listOf("destination_reason"),
                semanticUnresolvedTarget = "Reason for preferring the sale destination",
                suppliedInformation = "sale_destination is supplied; destination_reason is unresolved.",
                expectsFollowup = true,
            ),
        )
    }
}
