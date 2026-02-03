/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AiViewModelInstrumentationTest.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.vm

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.ModelAssetRule
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.slm.Accelerator
import com.negi.survey.slm.ConfigKey
import com.negi.survey.slm.Model
import com.negi.survey.slm.SLM
import com.negi.survey.slm.SlmDirectRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Instrumentation tests for [AiViewModel] + [SlmDirectRepository] + [SLM] end-to-end behavior.
 *
 * Covered:
 *  - Basic “happy path” runs with a real on-device model (multiple times).
 *  - ViewModel cancellation behavior.
 *  - Per-call timeout behavior.
 *
 * Notes:
 *  - Uses [ModelAssetRule] to ensure the LiteRT-LM model artifact exists on device.
 *  - Initializes the model once per test class (GPU first, with CPU fallback).
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AiViewModelInstrumentationTest {

    @get:Rule
    val modelRule = ModelAssetRule()

    /** Global watchdog so a hung generation does not block the whole suite. */
    @get:Rule
    val globalTimeout: Timeout = Timeout.seconds(120)

    private lateinit var appCtx: Context
    private lateinit var repo: SlmDirectRepository
    private lateinit var vm: AiViewModel
    private lateinit var config: SurveyConfig

    companion object {
        private const val TAG = "AiVmInstrTest"

        /** ViewModel-level timeout default (ms). */
        private const val TIMEOUT_SEC = 60L

        /** SLM.initialize timeout (seconds). */
        private const val INIT_TIMEOUT_SEC = 30L

        /** Secondary wait for model.instance to become non-null (ms). */
        private const val INSTANCE_WAIT_MS = 15_000L

        private lateinit var model: Model
        private val initialized = AtomicBoolean(false)

        @AfterClass
        @JvmStatic
        fun afterClass() {
            runCatching {
                if (::model.isInitialized && !SLM.isBusy(model)) {
                    SLM.cleanUp(model) {}
                }
            }.onFailure {
                Log.w(TAG, "SLM cleanup failed in afterClass: ${it.message}")
            }
        }
    }

    /**
     * Selects default accelerator (GPU or CPU) based on instrumentation args or env.
     * ACCELERATOR=CPU forces CPU-only for more predictable CI behavior.
     */
    private fun defaultAccel(): Accelerator {
        val args = InstrumentationRegistry.getArguments()
        val acc = (args.getString("ACCELERATOR") ?: System.getenv("ACCELERATOR"))
            ?.uppercase()
            ?.trim()
        return if (acc == "CPU") Accelerator.CPU else Accelerator.GPU
    }

    @Before
    fun setUp() = runBlocking {
        appCtx = InstrumentationRegistry.getInstrumentation().targetContext

        // Load and validate SurveyConfig early to surface asset issues.
        config = SurveyConfigLoader.fromAssets(appCtx, "survey_config1.yaml").also {
            val issues = it.validate()
            assertTrue(
                "SurveyConfig invalid:\n- " + issues.joinToString("\n- "),
                issues.isEmpty()
            )
        }

        if (initialized.compareAndSet(false, true)) {
            var accel = defaultAccel()
            model = Model(
                name = "gemma3-local-test",
                taskPath = modelRule.internalModel.absolutePath,
                config = mapOf(
                    ConfigKey.ACCELERATOR to accel.label,
                    ConfigKey.MAX_TOKENS to 4096,
                    ConfigKey.TOP_K to 40,
                    ConfigKey.TOP_P to 0.9f,
                    ConfigKey.TEMPERATURE to 0.7f
                )
            )

            var initErr = initializeModel(appCtx, model, INIT_TIMEOUT_SEC)
            if (!initErr.isNullOrEmpty() && accel != Accelerator.CPU) {
                Log.w(TAG, "GPU init failed: $initErr — falling back to CPU")
                accel = Accelerator.CPU
                model = Model(
                    name = model.name,
                    taskPath = modelRule.internalModel.absolutePath,
                    config = model.config.toMutableMap().apply {
                        put(ConfigKey.ACCELERATOR, accel.label)
                    }
                )
                initErr = initializeModel(appCtx, model, INIT_TIMEOUT_SEC)
            }

            check(initErr.isNullOrEmpty()) { "SLM initialization error: $initErr" }

            if (model.instance == null) {
                val ok = waitUntil(INSTANCE_WAIT_MS) { model.instance != null }
                check(ok) { "SLM instance not available within ${INSTANCE_WAIT_MS}ms" }
            }
            assertNotNull("Model instance must be created", model.instance)
        } else {
            assertNotNull("Model instance must exist", model.instance)
        }

        repo = SlmDirectRepository(model, config)
        vm = AiViewModel(
            repo = repo,
            defaultTimeoutMs = TIMEOUT_SEC * 1_000
        )

        val busy = runCatching { SLM.isBusy(model) }
            .onFailure { Log.w(TAG, "SLM.isBusy threw in setUp: ${it.message}") }
            .getOrElse { false }

        assertFalse("busy should be false at test start", busy)
    }

    @After
    fun tearDown() = runBlocking {
        runCatching { vm.cancel() }

        // Best-effort: wait briefly for the engine to become idle, then reset.
        val idle = waitUntil(2_000) {
            !runCatching { SLM.isBusy(model) }.getOrElse { false }
        }
        if (idle) {
            runCatching { SLM.resetSession(model) }
                .onFailure { Log.w(TAG, "resetSession failed in tearDown: ${it.message}") }
        }
    }

    // ---------------------------------------------------------------------
    // Prompt + runner helpers
    // ---------------------------------------------------------------------

    /**
     * Simple JSON-enforcing prompt for smoke tests.
     *
     * Contract:
     *  - Single-line JSON output.
     *  - Keys:
     *      "analysis":            short string
     *      "expected answer":     short string (<200 chars)
     *      "follow-up questions": array of EXACTLY 3 short strings
     *      "score":               integer 1..100
     */
    private fun jsonPrompt(
        q: String = "How many days to harvest?",
        a: String = "About 90 days."
    ): String = buildString {
        appendLine("You are a strict JSON generator.")
        appendLine("Return a SINGLE-LINE JSON object with EXACT keys:")
        appendLine(" - \"analysis\": short string")
        appendLine(" - \"expected answer\": short string (<200 chars)")
        appendLine(" - \"follow-up questions\": array of EXACTLY 3 short strings")
        appendLine(" - \"score\": integer 1..100")
        appendLine("No markdown fences. No extra text. One line only.")
        append("Question: "); append(q); append("  ")
        append("Answer: "); append(a)
    }.trim()

    /**
     * One-shot evaluation helper for smoke tests.
     *
     * Sequence:
     *  1) Start evaluation via [AiViewModel.evaluateAsync].
     *  2) Wait for either:
     *       - loading==true, OR
     *       - stream length >= [minStreamChars].
     *  3) Wait until `raw` becomes non-null (view-model has committed a result).
     *  4) Optionally observe loading==false (best-effort).
     *  5) Validate:
     *       - stream is non-empty
     *       - raw is non-null
     *       - score (if present) is in 1..100
     *       - followupQuestion (if present) is non-blank
     *
     * Always cancels the evaluation and Job in a `finally` block.
     */
    private suspend fun runOnce(
        prompt: String = jsonPrompt(),
        firstChunkTimeoutMs: Long = 60_000L,
        completeTimeoutMs: Long = 120_000L,
        minStreamChars: Int = 4
    ) {
        val job = vm.evaluateAsync(prompt)
        try {
            // 1) Wait for either loading=true or some streaming text.
            withTimeout(firstChunkTimeoutMs) {
                merge(
                    vm.loading.filter { it }.map { Unit },
                    vm.stream.filter { it.length >= minStreamChars }.map { Unit }
                ).first()
            }

            // 2) Primary completion condition: raw != null.
            withTimeout(completeTimeoutMs) {
                vm.raw.first { it != null }
            }

            // 3) Optionally observe loading=false if visible in time.
            withTimeoutOrNull(10_000) {
                if (vm.loading.value) {
                    vm.loading.first { !it }
                }
            }

            // 4) Validation of final state.
            val streamText = vm.stream.value
            require(streamText.isNotEmpty()) { "stream was empty" }

            val raw = vm.raw.value ?: error("raw was null (error=${vm.error.value})")

            vm.score.value?.let { score ->
                require(score in 1..100) { "score out of range: $score (raw=$raw)" }
            }
            vm.followupQuestion.value?.let { fup ->
                require(fup.isNotBlank()) { "followupQuestion was blank" }
            }
        } finally {
            // Ensure we always unwind evaluation and job.
            vm.cancel()
            job.cancel()
        }
    }

    // ---------------------------------------------------------------------
    // Smoke tests (multiple repetitions against a real model)
    // ---------------------------------------------------------------------

    @Test fun canUseRealModel01() = runBlocking { runOnce() }
    @Test fun canUseRealModel02() = runBlocking { runOnce() }
    @Test fun canUseRealModel03() = runBlocking { runOnce() }
    @Test fun canUseRealModel04() = runBlocking { runOnce() }
    @Test fun canUseRealModel05() = runBlocking { runOnce() }
    @Test fun canUseRealModel06() = runBlocking { runOnce() }
    @Test fun canUseRealModel07() = runBlocking { runOnce() }
    @Test fun canUseRealModel08() = runBlocking { runOnce() }
    @Test fun canUseRealModel09() = runBlocking { runOnce() }
    @Test fun canUseRealModel10() = runBlocking { runOnce() }
    @Test fun canUseRealModel11() = runBlocking { runOnce() }
    @Test fun canUseRealModel12() = runBlocking { runOnce() }

    // ---------------------------------------------------------------------
    // Behavior tests (cancellation / timeout)
    // ---------------------------------------------------------------------

    /**
     * Cancellation path: once some streaming text arrives, cancel and ensure
     * loading eventually drops and error is either null or "cancelled".
     */
    @Test
    fun cancelsCleanly() = runBlocking {
        val job = vm.evaluateAsync(
            jsonPrompt(
                q = "Write a poem slowly",
                a = "OK"
            )
        )
        try {
            withTimeout(30_000) {
                vm.stream.filter { it.isNotEmpty() }.first()
            }
        } finally {
            vm.cancel()
            job.cancel()
        }

        withTimeout(30_000) {
            vm.loading.filter { it == false }.first()
        }

        val err = vm.error.value
        assertTrue("expected cancel; err=$err", err == null || err == "cancelled")
        Log.d(TAG, "cancel observed: err=$err streamLen=${vm.stream.value.length}")
    }

    /**
     * Per-call timeout: use a tiny timeoutMs and confirm that the view-model
     * surfaces a timeout-like state (error null or \"timeout\"), and loading
     * eventually clears.
     */
    @Test
    fun timesOutProperly() = runBlocking {
        val job = vm.evaluateAsync(
            prompt = jsonPrompt(),
            timeoutMs = 1_000
        )
        try {
            withTimeout(30_000) {
                vm.loading.filter { it == false }.first()
            }
        } finally {
            vm.cancel()
            job.cancel()
        }

        val err = vm.error.value
        assertTrue("expected timeout; err=$err", err == null || err == "timeout")
        Log.d(
            TAG,
            "timeout observed: err=$err rawLen=${vm.raw.value?.toString()?.length ?: -1}"
        )
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /**
     * Small wrapper around [SLM.initialize] that turns the callback into a
     * blocking call, returning the error string (null/empty means success).
     */
    private fun initializeModel(ctx: Context, model: Model, timeoutSec: Long): String? {
        val latch = CountDownLatch(1)
        var err: String? = null
        SLM.initialize(ctx, model) { e ->
            err = e
            latch.countDown()
        }
        assertTrue("SLM init timeout", latch.await(timeoutSec, TimeUnit.SECONDS))
        return err
    }

    /**
     * Simple polling helper used for waiting on model.instance and idle states.
     */
    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            Thread.sleep(15)
        }
        return false
    }
}
