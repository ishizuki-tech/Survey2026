/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AiViewModelSurveyAllPromptsTest.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.vm

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.negi.survey.Logx
import com.negi.survey.slm.SLM
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end test that walks through all configured prompts in the survey
 * and validates that the AiViewModel + SLM pipeline:
 *
 * - Generates a strong sample answer for each node-level question.
 * - Fills the evaluation prompt template with question + answer.
 * - Runs AiViewModel.evaluateAsync() to completion and produces output.
 * - Emits a score within [1, 100] when present.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AiViewModelSurveyAllPromptsTest : AiViewModelSurveyBase() {

    /**
     * Uses the default survey config file for this suite.
     * Override if you want to test another survey YAML.
     */
    override fun configAssetName(): String = "survey_config1.yaml"

    /**
     * Main integration test that evaluates all configured prompts.
     *
     * Flow:
     * - Iterates over SurveyConfig.prompts (or a limited prefix when PROMPT_LIMIT is set).
     * - For each prompt:
     *   1) Resolve the node's question.
     *   2) Generate a "strong" sample answer with the low-level SLM helper.
     *   3) Fill the evaluation prompt (template + question + answer).
     *   4) Run AiViewModel.evaluateAsync() via [runOnce].
     *   5) Validate output, score range, and follow-ups.
     * - Stops early if TEST_BUDGET_MS is exceeded.
     * - Resets the SLM session between prompts.
     */
    @Test
    fun evaluateAllPrompts() = runBlocking {
        if (VERBOSE) {
            logModelConfig(model)
        }

        // Build a quick lookup from nodeId → question text.
        val questionById = config.graph.nodes.associate { it.id to it.question }

        val allPrompts = config.prompts
        val prompts = PROMPT_LIMIT
            ?.takeIf { it > 0 }
            ?.let { limit -> allPrompts.take(limit) }
            ?: allPrompts

        var tested = 0
        val testStart = System.currentTimeMillis()

        if (VERBOSE) {
            Logx.kv(
                TAG,
                "PROMPTS SUMMARY",
                mapOf(
                    "TotalPrompts" to "${config.prompts.size}",
                    "EffectivePrompts" to "${prompts.size}",
                    "PROMPT_LIMIT" to (PROMPT_LIMIT?.toString() ?: "<none>"),
                    "TEST_BUDGET_MS" to TEST_BUDGET_MS.toString()
                )
            )
        }

        for ((idx, p) in prompts.withIndex()) {
            val elapsed = System.currentTimeMillis() - testStart
            if (elapsed > TEST_BUDGET_MS) {
                Logx.w(
                    TAG,
                    "Test budget exceeded: $elapsed ms > $TEST_BUDGET_MS ms; stopping at idx=$idx"
                )
                break
            }

            val originalQuestion = questionById[p.nodeId]
            if (originalQuestion.isNullOrBlank()) {
                // Skip this prompt only, but keep the test running for others.
                Logx.w(
                    TAG,
                    "Skipping prompt idx=$idx nodeId=${p.nodeId}: no associated question in graph"
                )
                continue
            }

            // ----------------------------------------------------------------
            // Phase 1: Generate a strong sample answer for the node's question.
            // ----------------------------------------------------------------
            val generatedAnswer: String = try {
                generateAnswerWithSlm(
                    model = model,
                    question = originalQuestion,
                    firstChunkTimeoutMs = FIRST_CHUNK_TIMEOUT_MS,
                    completeTimeoutMs = COMPLETE_TIMEOUT_MS,
                    quietMs = 250L,
                    enforceWordCap = true
                )
            } catch (t: Throwable) {
                val msg = buildString {
                    append("SLM generation failed for nodeId=${p.nodeId} (promptIndex=$idx): ")
                    append("${t::class.simpleName}: ${t.message}")
                }
                throw AssertionError(msg, t)
            }

            Logx.kv(
                TAG,
                "Q&A",
                mapOf(
                    "Original Question" to originalQuestion,
                    "Generated Answer" to generatedAnswer
                )
            )

            // ----------------------------------------------------------------
            // Phase 2: Build evaluation prompt (template + question + answer).
            // ----------------------------------------------------------------
            val answerText = normalizeForModel(generatedAnswer)
            val filledPrompt = fillPlaceholders(p.prompt, originalQuestion, answerText)
            val fullPrompt = repo.buildPrompt(filledPrompt)

            if (VERBOSE) {
                Logx.kv(
                    TAG,
                    "PROMPT META",
                    mapOf(
                        "Index" to "${idx + 1}/${config.prompts.size}",
                        "NodeId" to p.nodeId,
                        "Template.len" to "${p.prompt.length}",
                        "Question.len" to "${originalQuestion.length}",
                        "Answer.len" to "${answerText.length}",
                        "FullPrompt.len" to "${fullPrompt.length}"
                    )
                )
                if (LOG_FULL_PROMPT) {
                    Logx.block(TAG, "FULL PROMPT", fullPrompt)
                }
            }

            // ----------------------------------------------------------------
            // Phase 3: Evaluate the full prompt via AiViewModel (view-model path).
            // ----------------------------------------------------------------
            val t0 = System.currentTimeMillis()
            val out: String = try {
                withTimeout(PER_PROMPT_GUARD_MS) {
                    runOnce(
                        prompt = fullPrompt,
                        firstChunkTimeoutMs = FIRST_CHUNK_TIMEOUT_MS,
                        completeTimeoutMs = COMPLETE_TIMEOUT_MS,
                        minStreamChars = MIN_STREAM_CHARS,
                        tailGraceMs = 300L,
                        minFinalChars = MIN_FINAL_CHARS
                    )
                }
            } catch (t: Throwable) {
                // Make sure VM and SLM are not left busy.
                runCatching { vm.cancel() }
                runCatching { if (SLM.isBusy(model)) SLM.cancel(model) }

                val errState =
                    "vm.error=${vm.error.value}, loading=${vm.loading.value}, stream.len=${vm.stream.value.length}"
                val msg = buildString {
                    append("Model error (phase=await) for nodeId=${p.nodeId} (promptIndex=$idx): ")
                    append("${t::class.simpleName}: ${t.message} ($errState)")
                }
                throw AssertionError(msg, t)
            }

            val dur = System.currentTimeMillis() - t0
            assertTrue("Empty output for nodeId=${p.nodeId}", out.isNotBlank())

            val outOne = oneLine(out)
            val followupsCount = vm.followups.value?.size ?: 0
            val scoreVal = vm.score.value
            scoreVal?.let {
                assertTrue(
                    "score must be in 1..100 for nodeId=${p.nodeId}, got=$it",
                    it in 1..100
                )
            }

            if (VERBOSE) {
                val qLog = originalQuestion.replace("\r", " ").replace("\n", " ").take(200)
                val aLog = answerText.replace("\r", " ").replace("\n", " ").take(200)
                val scoreLog = scoreVal?.toString() ?: "<none>"

                Logx.kv(
                    TAG,
                    "EVAL DONE",
                    mapOf(
                        "Raw.Buf" to outOne,
                        "Raw.len" to "${outOne.length}",
                        "Question" to qLog,
                        "Answer" to aLog,
                        "Score" to scoreLog,
                        "Followups.count" to "$followupsCount",
                        "Duration.ms" to "$dur",
                        "NodeId" to p.nodeId,
                        "PromptIndex" to "$idx"
                    )
                )
            }

            // ----------------------------------------------------------------
            // Phase 4: Follow-ups dump and engine cooldown.
            // ----------------------------------------------------------------
            dumpAllFollowups()

            val becameIdle =
                waitUntil(BETWEEN_PROMPTS_IDLE_WAIT_MS) { !SLM.isBusy(model) }
            assertTrue(
                "Engine did not become idle after nodeId=${p.nodeId} within ${BETWEEN_PROMPTS_IDLE_WAIT_MS}ms",
                becameIdle
            )
            if (becameIdle) {
                runCatching { SLM.resetSession(model) }
            }

            // Small cooldown between prompts to avoid hammering the engine.
            SystemClock.sleep(BETWEEN_PROMPTS_COOLDOWN_MS)
            tested++
        }

        assertTrue("No prompts were tested (tested=$tested)", tested > 0)
    }
}
