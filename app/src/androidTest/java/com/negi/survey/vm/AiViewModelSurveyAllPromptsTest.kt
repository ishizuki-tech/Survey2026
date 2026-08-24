/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AiViewModelSurveyAllPromptsTest.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025-2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.vm

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.negi.survey.Logx
import com.negi.survey.slm.SLM
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end survey test that walks every configured evaluation prompt.
 *
 * Pipeline under test:
 *
 * 1. Resolve the graph node question.
 * 2. Generate a strong synthetic/sample answer directly through [SLM].
 * 3. Fill the configured evaluation template with question + answer.
 * 4. Pass that USER-LEVEL evaluation text to [AiViewModel.evaluateAsync].
 * 5. Let AiViewModel / Repository build the model-ready prompt exactly once.
 * 6. Validate final output, score, follow-ups, and ViewModel lifecycle.
 *
 * Important:
 * - We intentionally do NOT pass repo.buildPrompt(...) output into
 *   AiViewModel.evaluateAsync(). AiViewModel already calls repo.buildPrompt(...)
 *   internally. Pre-building here would double-wrap the model prompt.
 * - We intentionally do NOT use SLM.isBusy(model) as a lifecycle oracle.
 * - We intentionally do NOT use the removed SLM.resetSession() API.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AiViewModelSurveyAllPromptsTest : AiViewModelSurveyBase() {

    /**
     * Default survey configuration for this suite.
     */
    override fun configAssetName(): String =
        "survey_config1.yaml"

    /**
     * Main integration test.
     *
     * PROMPT_LIMIT and TEST_BUDGET_MS are inherited from the base harness.
     */
    @Test
    fun evaluateAllPrompts() =
        runBlocking {
            if (VERBOSE) {
                logModelConfig(model)
            }

            val questionById =
                config.graph.nodes.associate {
                    it.id to it.question
                }

            val allPrompts =
                config.prompts

            val prompts =
                PROMPT_LIMIT
                    ?.takeIf {
                        it > 0
                    }
                    ?.let { limit ->
                        allPrompts.take(limit)
                    }
                    ?: allPrompts

            assertTrue(
                "Survey config contains no prompts",
                allPrompts.isNotEmpty(),
            )

            var tested = 0
            var skippedNoQuestion = 0

            val testStartedAt =
                SystemClock.elapsedRealtime()

            if (VERBOSE) {
                Logx.kv(
                    TAG,
                    "PROMPTS SUMMARY",
                    mapOf(
                        "TotalPrompts" to
                                config.prompts.size.toString(),
                        "EffectivePrompts" to
                                prompts.size.toString(),
                        "PROMPT_LIMIT" to
                                (
                                        PROMPT_LIMIT
                                            ?.toString()
                                            ?: "<none>"
                                        ),
                        "TEST_BUDGET_MS" to
                                TEST_BUDGET_MS.toString(),
                    ),
                )
            }

            for ((index, promptConfig) in prompts.withIndex()) {
                val totalElapsedMs =
                    SystemClock.elapsedRealtime() -
                            testStartedAt

                if (totalElapsedMs > TEST_BUDGET_MS) {
                    Logx.w(
                        TAG,
                        "Test budget exceeded: " +
                                "$totalElapsedMs ms > $TEST_BUDGET_MS ms; " +
                                "stopping at idx=$index"
                    )
                    break
                }

                val nodeId =
                    promptConfig.nodeId

                val originalQuestion =
                    questionById[nodeId]
                        ?.trim()
                        .orEmpty()

                if (originalQuestion.isBlank()) {
                    skippedNoQuestion++

                    Logx.w(
                        TAG,
                        "Skipping prompt idx=$index nodeId=$nodeId: " +
                                "no associated question in graph"
                    )

                    continue
                }

                /**
                 * SurveyConfig models prompt as nullable, so normalize it once.
                 *
                 * Missing evaluation templates are configuration failures, not
                 * something this end-to-end test should silently hide.
                 */
                val template =
                    promptConfig.prompt
                        ?.trim()
                        .orEmpty()

                assertTrue(
                    "Missing/blank evaluation template for " +
                            "nodeId=$nodeId promptIndex=$index",
                    template.isNotBlank(),
                )

                // ============================================================
                // Phase 1: Generate a strong sample answer
                // ============================================================

                val generatedAnswer =
                    try {
                        generateStrongSampleAnswer(
                            question = originalQuestion,
                        )
                    } catch (error: Throwable) {
                        throw AssertionError(
                            buildString {
                                append(
                                    "SLM sample-answer generation failed "
                                )
                                append(
                                    "for nodeId=$nodeId "
                                )
                                append(
                                    "(promptIndex=$index): "
                                )
                                append(
                                    "${error::class.simpleName}: " +
                                            "${error.message}"
                                )
                            },
                            error,
                        )
                    }

                Logx.kv(
                    TAG,
                    "Q&A",
                    mapOf(
                        "Original Question" to
                                originalQuestion,
                        "Generated Answer" to
                                generatedAnswer,
                    ),
                )

                // ============================================================
                // Phase 2: Fill evaluation template
                // ============================================================

                val answerText =
                    normalizeForModel(
                        generatedAnswer
                    )

                val filledPrompt =
                    fillPlaceholders(
                        tpl = template,
                        q = originalQuestion,
                        a = answerText,
                    )

                assertTrue(
                    "Filled prompt is blank for " +
                            "nodeId=$nodeId promptIndex=$index",
                    filledPrompt.isNotBlank(),
                )

                /**
                 * Build a model-ready preview ONLY for diagnostics.
                 *
                 * Do not pass this string to evaluateAsync(). The ViewModel will
                 * build the model-ready prompt internally.
                 */
                val modelReadyPreview =
                    if (VERBOSE) {
                        runCatching {
                            repo.buildPrompt(
                                filledPrompt
                            )
                        }.getOrElse {
                            filledPrompt
                        }
                    } else {
                        ""
                    }

                if (VERBOSE) {
                    Logx.kv(
                        TAG,
                        "PROMPT META",
                        mapOf(
                            "Index" to
                                    "${index + 1}/${prompts.size}",
                            "NodeId" to
                                    nodeId,
                            "Template.len" to
                                    template.length.toString(),
                            "Question.len" to
                                    originalQuestion.length.toString(),
                            "Answer.len" to
                                    answerText.length.toString(),
                            "FilledPrompt.len" to
                                    filledPrompt.length.toString(),
                            "ModelReadyPreview.len" to
                                    modelReadyPreview.length.toString(),
                        ),
                    )

                    if (LOG_FULL_PROMPT) {
                        Logx.block(
                            TAG,
                            "FULL MODEL-READY PROMPT PREVIEW",
                            modelReadyPreview,
                        )
                    }
                }

                // ============================================================
                // Phase 3: Evaluate through AiViewModel
                // ============================================================

                val evalStartedAt =
                    SystemClock.elapsedRealtime()

                val output =
                    try {
                        evaluateThroughViewModel(
                            prompt = filledPrompt,
                        )
                    } catch (error: Throwable) {
                        val errorState =
                            buildString {
                                append(
                                    "vm.error=${vm.error.value}"
                                )
                                append(
                                    ", loading=${vm.loading.value}"
                                )
                                append(
                                    ", stream.len=${vm.stream.value.length}"
                                )
                                append(
                                    ", raw.len=${vm.raw.value?.length ?: -1}"
                                )
                            }

                        /**
                         * Stop/reset only AFTER capturing diagnostics so cleanup
                         * does not overwrite the failure state we want to report.
                         */
                        runCatching {
                            vm.resetStates(
                                keepError = false
                            )
                        }

                        throw AssertionError(
                            buildString {
                                append(
                                    "Model evaluation failed "
                                )
                                append(
                                    "for nodeId=$nodeId "
                                )
                                append(
                                    "(promptIndex=$index): "
                                )
                                append(
                                    "${error::class.simpleName}: " +
                                            "${error.message} "
                                )
                                append(
                                    "($errorState)"
                                )
                            },
                            error,
                        )
                    }

                val durationMs =
                    SystemClock.elapsedRealtime() -
                            evalStartedAt

                assertTrue(
                    "Empty output for nodeId=$nodeId",
                    output.isNotBlank(),
                )

                assertFalse(
                    "loading must be false after completed evaluation " +
                            "for nodeId=$nodeId",
                    vm.loading.value,
                )

                assertNull(
                    "Successful evaluation should not leave error " +
                            "for nodeId=$nodeId",
                    vm.error.value,
                )

                val outputOneLine =
                    oneLine(output)

                /**
                 * followups is StateFlow<List<String>>, therefore .value is
                 * already non-null.
                 */
                val followupsCount =
                    vm.followups.value.size

                val score =
                    vm.score.value

                score?.let { value ->
                    assertTrue(
                        "score must be in 0..100 for " +
                                "nodeId=$nodeId, got=$value",
                        value in 0..100,
                    )
                }

                vm.followupQuestion.value
                    ?.let { followup ->
                        assertTrue(
                            "followupQuestion must be non-blank " +
                                    "when present for nodeId=$nodeId",
                            followup.isNotBlank(),
                        )
                    }

                if (VERBOSE) {
                    val questionLog =
                        oneLine(
                            originalQuestion
                        ).take(200)

                    val answerLog =
                        oneLine(
                            answerText
                        ).take(200)

                    val scoreLog =
                        score
                            ?.toString()
                            ?: "<none>"

                    Logx.kv(
                        TAG,
                        "EVAL DONE",
                        mapOf(
                            "Raw.Buf" to
                                    outputOneLine,
                            "Raw.len" to
                                    outputOneLine.length.toString(),
                            "Question" to
                                    questionLog,
                            "Answer" to
                                    answerLog,
                            "Score" to
                                    scoreLog,
                            "Followups.count" to
                                    followupsCount.toString(),
                            "Duration.ms" to
                                    durationMs.toString(),
                            "NodeId" to
                                    nodeId,
                            "PromptIndex" to
                                    index.toString(),
                        ),
                    )
                }

                // ============================================================
                // Phase 4: Diagnostics + transient-state reset
                // ============================================================

                dumpAllFollowups()

                /**
                 * No SLM.isBusy polling and no resetSession().
                 *
                 * evaluateThroughViewModel() waits for its Job to finish.
                 * LiteRtRepository owns inference serialization/native cleanup,
                 * and the repository resets conversation state at its native
                 * safe point.
                 *
                 * Clear ViewModel transient UI state only after all assertions
                 * and diagnostics for this prompt are complete.
                 */
                vm.resetStates(
                    keepError = false
                )

                if (BETWEEN_PROMPTS_COOLDOWN_MS > 0L) {
                    delay(
                        BETWEEN_PROMPTS_COOLDOWN_MS
                    )
                }

                tested++
            }

            assertTrue(
                buildString {
                    append(
                        "No prompts were tested "
                    )
                    append(
                        "(tested=$tested"
                    )
                    append(
                        ", skippedNoQuestion=$skippedNoQuestion"
                    )
                    append(
                        ", configured=${prompts.size})"
                    )
                },
                tested > 0,
            )
        }

    // =====================================================================
    // Local modern helpers
    // =====================================================================

    /**
     * Generate a synthetic "strong answer" directly through the current
     * suspend SLM API.
     *
     * This generation is only test-fixture creation; the evaluation itself
     * still goes through AiViewModel + LiteRtRepository below.
     */
    private suspend fun generateStrongSampleAnswer(
        question: String,
    ): String {
        val prompt =
            buildStrongAnswerPrompt(
                question
            )

        if (
            VERBOSE &&
            LOG_FULL_PROMPT
        ) {
            Logx.block(
                TAG,
                "SLM SAMPLE-ANSWER PROMPT",
                oneLine(prompt),
            )
        }

        val output =
            try {
                withTimeout(
                    COMPLETE_TIMEOUT_MS
                ) {
                    SLM.generateText(
                        model = model,
                        input = prompt,
                        images = emptyList(),
                        audioClips = emptyList(),
                        onPartial = {
                            /**
                             * The returned final text is authoritative here.
                             * Avoid logging each token/chunk and making the
                             * instrumentation test unnecessarily noisy.
                             */
                        },
                    )
                }
            } finally {
                /**
                 * Do not let sample-answer conversation state bleed into the
                 * evaluation request.
                 *
                 * generateText() has returned (or timeout cancellation has
                 * propagated) before this reset is requested.
                 */
                runCatching {
                    SLM.resetConversation(
                        model = model,
                        supportImage = false,
                        supportAudio = false,
                        systemMessage = null,
                        tools = emptyList(),
                    )
                }

                /**
                 * resetConversation is a control operation; this is only a
                 * scheduling grace, not the correctness mechanism.
                 */
                delay(200L)
            }

        val normalized =
            normalizeForModel(
                output
            )

        require(
            normalized.isNotBlank()
        ) {
            "empty sample answer from SLM for " +
                    "question='${oneLine(question).take(80)}'"
        }

        if (VERBOSE) {
            Logx.kv(
                TAG,
                "SLM SAMPLE ANSWER",
                mapOf(
                    "len" to
                            normalized.length.toString(),
                    "preview" to
                            oneLine(normalized)
                                .take(160),
                ),
            )
        }

        return normalized
    }

    /**
     * Evaluate one already-filled USER-LEVEL evaluation prompt.
     *
     * Important:
     * - This method does not call repo.buildPrompt().
     * - AiViewModel owns model-ready prompt construction.
     * - We wait on the returned Job instead of polling SLM.isBusy().
     */
    private suspend fun evaluateThroughViewModel(
        prompt: String,
    ): String {
        vm.resetStates(
            keepError = false
        )

        val job: Job =
            vm.evaluateAsync(
                prompt = prompt,
                timeoutMs = COMPLETE_TIMEOUT_MS,
            )

        withTimeout(
            PER_PROMPT_GUARD_MS
        ) {
            job.join()
        }

        if (vm.loading.value) {
            throw AssertionError(
                "AiViewModel Job completed but loading is still true"
            )
        }

        vm.error.value
            ?.let { error ->
                throw AssertionError(
                    "AiViewModel evaluation error: $error"
                )
            }

        val output =
            vm.raw.value
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: vm.stream.value

        require(
            output.length >=
                    MIN_FINAL_CHARS
        ) {
            "empty/short output @finalize: " +
                    "len=${output.length}, " +
                    "error=${vm.error.value}, " +
                    "loading=${vm.loading.value}, " +
                    "stream.len=${vm.stream.value.length}"
        }

        return output
    }
}
