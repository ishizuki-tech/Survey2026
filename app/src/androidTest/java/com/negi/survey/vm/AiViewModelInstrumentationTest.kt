/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AiViewModelInstrumentationTest.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025-2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.vm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.ModelAssetRule
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.slm.Accelerator
import com.negi.survey.slm.ConfigKey
import com.negi.survey.slm.LiteRtRepository
import com.negi.survey.slm.Model
import com.negi.survey.slm.SLM
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * Real-device instrumentation tests for [AiViewModel] + [LiteRtRepository].
 *
 * Current contracts under test:
 * - Model initialization uses the current suspend SLM API.
 * - [AiViewModel.evaluateAsync] returns the active [Job].
 * - Successful evaluation commits raw output and clears loading.
 * - [AiViewModel.cancel] marks the run as "cancelled".
 * - Per-call timeout commits the "timeout" error before the test resets state.
 * - Single-flight behavior returns the already-active Job instead of starting a
 *   second evaluation concurrently.
 * - Repeated evaluations leave the LiteRT-LM backend reusable.
 *
 * Historical APIs intentionally removed from this test:
 * - SlmDirectRepository
 * - Model.instance
 * - SLM.resetSession()
 * - callback-style SLM.initialize(context, model) { ... }
 * - SLM.isBusy(model) as a repository/ViewModel lifecycle oracle
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AiViewModelInstrumentationTest {

    @get:Rule
    val modelRule =
        ModelAssetRule()

    @get:Rule
    val globalTimeout: Timeout =
        Timeout.seconds(GLOBAL_TEST_TIMEOUT_SEC)

    private lateinit var appCtx: Context
    private lateinit var repo: LiteRtRepository
    private lateinit var vm: AiViewModel
    private lateinit var config: SurveyConfig

    companion object {
        private const val TAG = "AiVmInstrTest"

        /** Whole-test watchdog. */
        private const val GLOBAL_TEST_TIMEOUT_SEC = 180L

        /** Default ViewModel request timeout. */
        private const val DEFAULT_VM_TIMEOUT_MS = 60_000L

        /** Hard timeout for model initialization. */
        private const val INIT_TIMEOUT_MS = 60_000L

        /** Hard timeout for a normal ViewModel evaluation. */
        private const val COMPLETE_TIMEOUT_MS = 120_000L

        /** Wait for a run to visibly enter loading state. */
        private const val START_TIMEOUT_MS = 15_000L

        /** Cancellation should settle well before this. */
        private const val CANCEL_TIMEOUT_MS = 30_000L

        /** Native cleanup callback grace for final class teardown. */
        private const val CLEANUP_TIMEOUT_MS = 20_000L

        /**
         * Keep instrumentation runs reasonably short and deterministic.
         *
         * 4096 output tokens is unnecessarily expensive for smoke tests.
         */
        private const val TEST_MAX_TOKENS = 512

        private lateinit var model: Model

        private val initialized =
            AtomicBoolean(false)

        private val initLock =
            Any()

        @AfterClass
        @JvmStatic
        fun afterClass() {
            if (!::model.isInitialized) {
                return
            }

            runCatching {
                forceCleanUpBlocking(
                    targetModel = model,
                    timeoutMs = CLEANUP_TIMEOUT_MS,
                )
            }.onFailure { error ->
                Log.w(
                    TAG,
                    "SLM force cleanup failed in afterClass: ${error.message}",
                    error,
                )
            }
        }

        private fun forceCleanUpBlocking(
            targetModel: Model,
            timeoutMs: Long,
        ): Boolean {
            val latch =
                CountDownLatch(1)

            SLM.forceCleanUp(targetModel) {
                latch.countDown()
            }

            return latch.await(
                timeoutMs,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    @Before
    fun setUp() {
        appCtx =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext
                .applicationContext

        SLM.setApplicationContext(appCtx)

        config =
            loadValidatedConfig(appCtx)

        ensureModelInitialized()

        repo =
            LiteRtRepository(
                model = model,
                config = config,
                appContext = appCtx,
            )

        vm =
            AiViewModel(
                repo = repo,
                defaultTimeoutMs = DEFAULT_VM_TIMEOUT_MS,
            )

        /**
         * Start every test from a known transient ViewModel state.
         *
         * resetStates() does not set the "cancelled" error, unlike cancel().
         */
        vm.resetStates(
            keepError = false
        )
    }

    @After
    fun tearDown() {
        /**
         * Do not call vm.cancel() unconditionally here.
         *
         * cancel() intentionally writes error="cancelled", which can overwrite
         * the terminal state a test is trying to inspect. resetStates() stops
         * any leftover run without introducing that false terminal result.
         */
        runCatching {
            vm.resetStates(
                keepError = false
            )
        }.onFailure { error ->
            Log.w(
                TAG,
                "resetStates failed in tearDown: ${error.message}",
                error,
            )
        }
    }

    // =====================================================================
    // Initialization
    // =====================================================================

    private fun loadValidatedConfig(
        context: Context,
    ): SurveyConfig {
        return try {
            SurveyConfigLoader
                .fromAssetsStrictValidated(
                    context,
                    "survey_config1.yaml",
                )
        } catch (error: Throwable) {
            throw AssertionError(
                "Failed to load/validate SurveyConfig: ${error.message}",
                error,
            )
        }
    }

    /**
     * Initialize once per test class.
     *
     * GPU is preferred unless ACCELERATOR=CPU is supplied through
     * instrumentation arguments/environment.
     *
     * If GPU initialization fails, explicitly tear down partial runtime state
     * before retrying the same model path on CPU.
     */
    private fun ensureModelInitialized() {
        if (initialized.get()) {
            return
        }

        synchronized(initLock) {
            if (initialized.get()) {
                return
            }

            val preferred =
                defaultAccelerator()

            var candidate =
                createModel(preferred)

            Log.i(
                TAG,
                "Initializing model accelerator=${preferred.label}"
            )

            try {
                initializeModelBlocking(candidate)
            } catch (firstError: Throwable) {
                if (preferred == Accelerator.CPU) {
                    throw firstError
                }

                Log.w(
                    TAG,
                    "GPU init failed: ${firstError.message}; falling back to CPU",
                    firstError,
                )

                runCatching {
                    forceCleanUpBlocking(
                        targetModel = candidate,
                        timeoutMs = CLEANUP_TIMEOUT_MS,
                    )
                }.onFailure { cleanupError ->
                    Log.w(
                        TAG,
                        "Cleanup before CPU fallback failed: ${cleanupError.message}",
                        cleanupError,
                    )
                }

                candidate =
                    createModel(
                        Accelerator.CPU
                    )

                try {
                    initializeModelBlocking(candidate)
                } catch (cpuError: Throwable) {
                    cpuError.addSuppressed(firstError)
                    throw cpuError
                }
            }

            model = candidate
            initialized.set(true)

            Log.i(
                TAG,
                "Model initialized successfully: " +
                        "name=${model.name} " +
                        "accelerator=${model.getStringConfigValue(ConfigKey.ACCELERATOR, "")}"
            )
        }
    }

    private fun createModel(
        accelerator: Accelerator,
    ): Model =
        Model(
            name = "gemma3-local-test",
            taskPath = modelRule.internalModel.absolutePath,
            config =
                mapOf(
                    ConfigKey.ACCELERATOR to accelerator.label,
                    ConfigKey.MAX_TOKENS to TEST_MAX_TOKENS,
                    ConfigKey.TOP_K to 1,
                    ConfigKey.TOP_P to 0.0f,
                    ConfigKey.TEMPERATURE to 0.0f,
                ),
        )

    private fun initializeModelBlocking(
        targetModel: Model,
    ) {
        runBlocking {
            withTimeout(INIT_TIMEOUT_MS) {
                SLM.initializeIfNeeded(
                    context = appCtx,
                    model = targetModel,
                    supportImage = false,
                    supportAudio = false,
                    systemMessage = null,
                    tools = emptyList(),
                )
            }
        }
    }

    private fun defaultAccelerator(): Accelerator {
        val args =
            InstrumentationRegistry.getArguments()

        val configured =
            (
                    args.getString("ACCELERATOR")
                        ?: System.getenv("ACCELERATOR")
                    )
                ?.trim()
                ?.uppercase()

        return if (
            configured == Accelerator.CPU.label
        ) {
            Accelerator.CPU
        } else {
            Accelerator.GPU
        }
    }

    // =====================================================================
    // Prompt / execution helpers
    // =====================================================================

    /**
     * Compact JSON-oriented smoke prompt.
     *
     * The extractor is intentionally tolerant, so this test validates the
     * ViewModel lifecycle and parsed-state sanity rather than demanding exact
     * model wording.
     */
    private fun jsonPrompt(
        question: String = "How many days to harvest?",
        answer: String = "About 90 days.",
    ): String =
        buildString {
            appendLine("Return one JSON object only.")
            appendLine("Use these keys:")
            appendLine("\"analysis\": short string")
            appendLine("\"followup_needed\": boolean")
            appendLine("\"followup_question\": short question or empty string")
            appendLine("\"score\": integer from 0 to 100")
            appendLine("No markdown. No text outside JSON.")
            append("Question: ")
            append(question)
            appendLine()
            append("Answer: ")
            append(answer)
        }.trim()

    /**
     * Prompt intended to keep generation alive long enough to exercise cancel
     * and single-flight paths.
     */
    private fun longPrompt(): String =
        "Write a detailed multi-paragraph explanation of Android " +
                "instrumentation testing, including runners, synchronization, " +
                "timeouts, cancellation, native resources, and test isolation."

    /**
     * Wait until the current job is complete.
     *
     * Joining the Job is stronger than relying on a transient StateFlow value:
     * it guarantees evaluateAsync's coroutine has actually exited.
     */
    private suspend fun awaitJob(
        job: Job,
        timeoutMs: Long = COMPLETE_TIMEOUT_MS,
    ) {
        withTimeout(timeoutMs) {
            job.join()
        }
    }

    private suspend fun waitUntilLoading(
        expected: Boolean,
        timeoutMs: Long,
    ): Boolean {
        if (vm.loading.value == expected) {
            return true
        }

        return withTimeoutOrNull(timeoutMs) {
            vm.loading
                .filter {
                    it == expected
                }
                .first()

            true
        } ?: false
    }

    /**
     * One successful evaluation.
     */
    private suspend fun runSuccessfulEvaluation(
        prompt: String = jsonPrompt(),
        timeoutMs: Long = COMPLETE_TIMEOUT_MS,
    ): String {
        vm.resetStates(
            keepError = false
        )

        val job =
            vm.evaluateAsync(
                prompt = prompt,
                timeoutMs = timeoutMs,
            )

        awaitJob(
            job = job,
            timeoutMs = timeoutMs + 10_000L,
        )

        assertFalse(
            "loading must be false after evaluateAsync Job completion",
            vm.loading.value,
        )

        assertNull(
            "successful evaluation should not leave an error",
            vm.error.value,
        )

        val raw =
            vm.raw.value
                ?: throw AssertionError(
                    "raw output was null after successful evaluation"
                )

        assertTrue(
            "raw output should not be blank",
            raw.isNotBlank(),
        )

        /**
         * Parsed fields are optional because malformed model JSON should not
         * turn a lifecycle smoke test into a false runtime failure.
         */
        vm.score.value?.let { score ->
            assertTrue(
                "score out of range: $score",
                score in 0..100,
            )
        }

        vm.followupQuestion.value?.let { followup ->
            assertTrue(
                "followupQuestion must be non-blank when present",
                followup.isNotBlank(),
            )
        }

        return raw
    }

    // =====================================================================
    // Happy path
    // =====================================================================

    @Test
    fun real_model_repeated_runs_succeed() =
        runBlocking {
            repeat(4) { index ->
                val raw =
                    runSuccessfulEvaluation(
                        prompt =
                            jsonPrompt(
                                question = "Harvest question #$index",
                                answer = "About 90 days.",
                            )
                    )

                Log.i(
                    TAG,
                    "repeat[$index] raw.len=${raw.length} " +
                            "score=${vm.score.value} " +
                            "followups=${vm.followups.value.size}"
                )
            }
        }

    @Test
    fun successful_run_commits_consistent_primary_state() =
        runBlocking {
            val raw =
                runSuccessfulEvaluation()

            assertEquals(
                "stream should contain the final committed output",
                raw,
                vm.stream.value,
            )

            assertTrue(
                "step history should contain the completed evaluation",
                vm.stepHistory.value.isNotEmpty(),
            )

            val last =
                vm.stepHistory.value.last()

            assertEquals(
                "step-history raw should match primary raw",
                raw,
                last.raw,
            )

            assertFalse(
                "successful step must not be timed out",
                last.timedOut,
            )

            assertNull(
                "successful step error should be null",
                last.error,
            )
        }

    // =====================================================================
    // Cancellation
    // =====================================================================

    @Test
    fun cancels_cleanly_and_backend_remains_usable() =
        runBlocking {
            vm.resetStates(
                keepError = false
            )

            val job =
                vm.evaluateAsync(
                    prompt = longPrompt(),
                    timeoutMs = COMPLETE_TIMEOUT_MS,
                )

            val started =
                waitUntilLoading(
                    expected = true,
                    timeoutMs = START_TIMEOUT_MS,
                )

            assertTrue(
                "evaluation should enter loading state before cancellation",
                started,
            )

            /**
             * Prefer to cancel after some stream activity, but do not make that
             * a hard precondition: startup latency varies by device/backend.
             */
            withTimeoutOrNull(10_000L) {
                vm.stream
                    .filter {
                        it.isNotEmpty()
                    }
                    .first()
            }

            val cancelAt =
                SystemClock.elapsedRealtime()

            vm.cancel()

            awaitJob(
                job = job,
                timeoutMs = CANCEL_TIMEOUT_MS,
            )

            val cancelElapsed =
                SystemClock.elapsedRealtime() -
                        cancelAt

            assertFalse(
                "loading must be false after cancel",
                vm.loading.value,
            )

            assertEquals(
                "cancel() should publish the current ViewModel contract",
                "cancelled",
                vm.error.value,
            )

            Log.i(
                TAG,
                "cancel settled in ${cancelElapsed}ms " +
                        "stream.len=${vm.stream.value.length}"
            )

            /**
             * Clear only after asserting the terminal cancel state.
             */
            vm.resetStates(
                keepError = false
            )

            val next =
                runSuccessfulEvaluation(
                    prompt =
                        jsonPrompt(
                            question = "Can the backend run after cancellation?",
                            answer = "Yes.",
                        )
                )

            assertTrue(
                "backend should remain usable after cancellation",
                next.isNotBlank(),
            )
        }

    // =====================================================================
    // Timeout
    // =====================================================================

    @Test
    fun times_out_without_cancel_overwriting_timeout_state() =
        runBlocking {
            vm.resetStates(
                keepError = false
            )

            val job =
                vm.evaluateAsync(
                    prompt = longPrompt(),
                    timeoutMs = 1L,
                )

            awaitJob(
                job = job,
                timeoutMs = CANCEL_TIMEOUT_MS,
            )

            assertFalse(
                "loading must be false after timeout Job completion",
                vm.loading.value,
            )

            /**
             * Important:
             * Do NOT call vm.cancel() before this assertion.
             * cancel() intentionally sets error=\"cancelled\".
             */
            assertEquals(
                "per-call timeout should publish timeout",
                "timeout",
                vm.error.value,
            )

            val last =
                vm.stepHistory.value.lastOrNull()

            assertTrue(
                "timeout should produce a timedOut step snapshot",
                last?.timedOut == true,
            )

            assertEquals(
                "timeout snapshot should preserve timeout error",
                "timeout",
                last?.error,
            )

            Log.i(
                TAG,
                "timeout observed raw.len=${vm.raw.value?.length ?: -1} " +
                        "stream.len=${vm.stream.value.length}"
            )

            /**
             * Reset after observing timeout, then verify a new normal run works.
             */
            vm.resetStates(
                keepError = false
            )

            val next =
                runSuccessfulEvaluation(
                    prompt =
                        jsonPrompt(
                            question = "Can the backend run after timeout?",
                            answer = "Yes.",
                        )
                )

            assertTrue(
                "backend should remain usable after timeout",
                next.isNotBlank(),
            )
        }

    // =====================================================================
    // Single-flight
    // =====================================================================

    @Test
    fun second_evaluate_async_while_running_returns_existing_job() =
        runBlocking {
            vm.resetStates(
                keepError = false
            )

            val firstJob =
                vm.evaluateAsync(
                    prompt = longPrompt(),
                    timeoutMs = COMPLETE_TIMEOUT_MS,
                )

            assertTrue(
                "first evaluation should enter loading state",
                waitUntilLoading(
                    expected = true,
                    timeoutMs = START_TIMEOUT_MS,
                ),
            )

            val secondJob =
                vm.evaluateAsync(
                    prompt =
                        jsonPrompt(
                            question = "This second request should not start concurrently.",
                            answer = "N/A",
                        ),
                    timeoutMs = COMPLETE_TIMEOUT_MS,
                )

            /**
             * Current AiViewModel single-flight contract:
             * if already running, evaluateAsync returns evalJob.
             */
            assertSame(
                "second evaluateAsync call should return the active Job",
                firstJob,
                secondJob,
            )

            vm.cancel()

            awaitJob(
                job = firstJob,
                timeoutMs = CANCEL_TIMEOUT_MS,
            )

            assertEquals(
                "single-flight test ends through explicit cancellation",
                "cancelled",
                vm.error.value,
            )
        }

    // =====================================================================
    // Empty input / reset behavior
    // =====================================================================

    @Test
    fun blank_prompt_resets_transient_state_without_starting_generation() =
        runBlocking {
            /**
             * Seed state with a successful run first.
             */
            runSuccessfulEvaluation()

            assertTrue(
                "precondition: raw should be populated",
                vm.raw.value != null,
            )

            val job =
                vm.evaluateAsync(
                    prompt = "   ",
                )

            awaitJob(
                job = job,
                timeoutMs = 5_000L,
            )

            assertFalse(
                "blank prompt should not leave loading=true",
                vm.loading.value,
            )

            assertNull(
                "blank prompt should clear raw via resetStates",
                vm.raw.value,
            )

            assertEquals(
                "blank prompt should clear stream",
                "",
                vm.stream.value,
            )

            assertNull(
                "blank prompt should clear error",
                vm.error.value,
            )

            assertTrue(
                "blank prompt should clear step history",
                vm.stepHistory.value.isEmpty(),
            )
        }

    // =====================================================================
    // Reinitialization / reset
    // =====================================================================

    @Test
    fun initialize_if_needed_is_idempotent_and_model_stays_usable() =
        runBlocking {
            withTimeout(INIT_TIMEOUT_MS) {
                SLM.initializeIfNeeded(
                    context = appCtx,
                    model = model,
                    supportImage = false,
                    supportAudio = false,
                    systemMessage = null,
                    tools = emptyList(),
                )
            }

            val raw =
                runSuccessfulEvaluation(
                    prompt =
                        jsonPrompt(
                            question = "Does idempotent reinitialization still work?",
                            answer = "Yes.",
                        )
                )

            assertTrue(
                "model should remain usable after initializeIfNeeded",
                raw.isNotBlank(),
            )
        }

    @Test
    fun explicit_conversation_reset_keeps_view_model_path_usable() =
        runBlocking {
            val before =
                runSuccessfulEvaluation(
                    prompt =
                        jsonPrompt(
                            question = "Before reset?",
                            answer = "Before.",
                        )
                )

            assertTrue(
                "pre-reset run should succeed",
                before.isNotBlank(),
            )

            /**
             * The successful ViewModel run has completed before this control
             * operation is issued.
             */
            SLM.resetConversation(
                model = model,
                supportImage = false,
                supportAudio = false,
                systemMessage = null,
                tools = emptyList(),
            )

            delay(300L)

            val after =
                runSuccessfulEvaluation(
                    prompt =
                        jsonPrompt(
                            question = "After reset?",
                            answer = "After.",
                        )
                )

            assertTrue(
                "post-reset run should succeed",
                after.isNotBlank(),
            )
        }
}
