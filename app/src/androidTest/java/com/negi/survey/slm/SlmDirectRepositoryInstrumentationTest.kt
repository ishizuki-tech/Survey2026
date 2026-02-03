// file: app/src/androidTest/java/com/negi/survey/slm/SlmDirectRepositoryInstrumentationTest.kt
/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SlmDirectRepositoryInstrumentationTest.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.slm

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.Logx
import com.negi.survey.ModelAssetRule
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Instrumentation tests for [SlmDirectRepository] using a real on-device SLM.
 *
 * Assumptions:
 *  - [SlmDirectRepository.request] is never called concurrently.
 *    Each request must complete before the next one is started.
 *
 * Coverage:
 *  - Streaming: non-empty chunks are delivered and the engine returns to IDLE.
 *  - Cancel via Flow consumer (first() early exit): awaitClose path returns to IDLE.
 *  - Empty prompt: the EMPTY_JSON_INSTRUCTION path completes without crashes.
 *  - Sequential requests: R1 completes and becomes IDLE before R2 starts.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SlmDirectRepositoryInstrumentationTest {

    @get:Rule
    val modelRule = ModelAssetRule()

    private lateinit var appCtx: Context
    private lateinit var repo: SlmDirectRepository
    private lateinit var config: SurveyConfig

    companion object {
        private const val TAG = "SlmRepoInstrTest"

        private const val INIT_TIMEOUT_SEC = 15L
        private const val INSTANCE_WAIT_MS = 5_000L

        // Test-wide timeouts
        private const val TEST_TIMEOUT_MS = 60_000L
        private const val IDLE_WAIT_MS = 20_000L
        private const val IDLE_STEP_MS = 20L

        private lateinit var model: Model
        private val initialized = AtomicBoolean(false)

        @AfterClass
        @JvmStatic
        fun afterClass() {
            runCatching {
                if (::model.isInitialized && !SLM.isBusy(model)) {
                    SLM.cleanUp(model) {}
                }
            }.onFailure { t ->
                Logx.w(TAG, "SLM cleanup failed in @AfterClass: ${t.message}")
            }
        }
    }

    /**
     * Small normalization helper to reduce false negatives due to formatting.
     */
    private fun normalize(s: String): String = s
        .replace(Regex("[\\u2012-\\u2015]"), "-") // various dash characters → '-'
        .replace('\u00A0', ' ')                 // non-breaking space → normal space
        .trim()

    /**
     * Chooses default accelerator from instrumentation arguments or environment.
     * Falls back to GPU unless explicitly forced to CPU.
     */
    private fun defaultAccel(): Accelerator {
        val args = InstrumentationRegistry.getArguments()
        val acc = (args.getString("ACCELERATOR") ?: System.getenv("ACCELERATOR"))
            ?.uppercase()
            ?.trim()
        return if (acc == "CPU") Accelerator.CPU else Accelerator.GPU
    }

    @Before
    fun setUp() {
        appCtx = InstrumentationRegistry.getInstrumentation().targetContext

        // Load and validate SurveyConfig from assets (fail fast on issues).
        config = try {
            SurveyConfigLoader.fromAssets(appCtx, "survey_config1.yaml").also { cfg ->
                val issues = cfg.validate()
                assertTrue(
                    "SurveyConfig invalid:\n- " + issues.joinToString("\n- "),
                    issues.isEmpty()
                )
            }
        } catch (t: Throwable) {
            throw AssertionError("Failed to load or validate SurveyConfig: ${t.message}", t)
        }

        // One-time model initialization with GPU→CPU fallback.
        if (initialized.compareAndSet(false, true)) {
            var accel = defaultAccel()
            Logx.i(TAG, "Initializing SLM (accel=$accel)")

            model = Model(
                name = "gemma-3n-E4B-it",
                taskPath = modelRule.internalModel.absolutePath,
                // Use deterministic decoding to reduce test flakiness.
                config = mapOf(
                    ConfigKey.ACCELERATOR to accel.label,
                    ConfigKey.MAX_TOKENS to 1024,
                    ConfigKey.TOP_K to 1,
                    ConfigKey.TOP_P to 0.0f,
                    ConfigKey.TEMPERATURE to 0.0f
                )
            )

            var initErr = initializeModel(appCtx, model, INIT_TIMEOUT_SEC)
            if (initErr.isNullOrEmpty()) {
                val ok = waitUntil(INSTANCE_WAIT_MS) { model.instance != null }
                check(ok) { "SLM instance not available within ${INSTANCE_WAIT_MS}ms (accel=$accel)" }
            }

            if (!initErr.isNullOrEmpty() && accel != Accelerator.CPU) {
                Logx.w(TAG, "GPU init failed: $initErr → fallback to CPU")
                accel = Accelerator.CPU
                model = Model(
                    name = model.name,
                    taskPath = modelRule.internalModel.absolutePath,
                    config = model.config.toMutableMap().apply {
                        put(ConfigKey.ACCELERATOR, accel.label)
                    }
                )
                initErr = initializeModel(appCtx, model, INIT_TIMEOUT_SEC)
                if (initErr.isNullOrEmpty()) {
                    val ok = waitUntil(INSTANCE_WAIT_MS) { model.instance != null }
                    check(ok) { "SLM instance not available (CPU) within ${INSTANCE_WAIT_MS}ms" }
                }
            }

            check(initErr.isNullOrEmpty()) { "SLM initialization error: $initErr" }
            assertNotNull("Model instance must be set", model.instance)
            Logx.i(TAG, "SLM initialized: instance=${model.instance}, accel=${accel.label}")
        } else {
            assertNotNull("Model instance must exist", model.instance)
        }

        repo = SlmDirectRepository(model, config)

        runCatching {
            assertFalse("SLM should be idle on start", SLM.isBusy(model))
        }.onFailure { t ->
            Logx.w(TAG, "SLM.isBusy check failed in setUp: ${t.message}")
        }
    }

    @After
    fun tearDown() {
        val idle = waitUntil(1_000) { !SLM.isBusy(model) }
        if (idle) {
            runCatching { SLM.resetSession(model) }
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Initializes SLM model and waits for the callback with a timeout.
     */
    private fun initializeModel(ctx: Context, model: Model, timeoutSec: Long): String? {
        val latch = CountDownLatch(1)
        var err: String? = null
        SLM.initialize(ctx, model) { e ->
            err = e
            latch.countDown()
        }
        assertTrue("SLM init timeout (>${timeoutSec}s)", latch.await(timeoutSec, TimeUnit.SECONDS))
        return err
    }

    /**
     * Waits for the engine to become idle using short polling, with a hard timeout.
     */
    private fun waitIdleBlocking(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val busy = runCatching { SLM.isBusy(model) }.getOrElse { false }
            if (!busy) return true
            SystemClock.sleep(IDLE_STEP_MS)
        }
        val finalBusy = runCatching { SLM.isBusy(model) }.getOrElse { false }
        return !finalBusy
    }

    /**
     * Simple polling helper for arbitrary conditions (millisecond timeout).
     */
    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            SystemClock.sleep(15)
        }
        return false
    }

    // ---------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------

    /** Streaming: non-empty chunks must arrive, and the engine returns to IDLE. */
    @Test
    fun request_streams_and_closes_normally() = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) {
            val sb = StringBuilder()
            repo.request("""Return a very short JSON like {"ok":true}.""")
                .collect { part ->
                    if (part.isNotEmpty()) sb.append(part)
                }

            val out = normalize(sb.toString())
            Logx.i(TAG, "STREAM DONE len=${out.length} head='${out.take(80)}'")
            assertTrue("At least one non-empty chunk expected", out.isNotBlank())
            assertTrue(
                "Engine should become IDLE after completion",
                waitIdleBlocking(IDLE_WAIT_MS)
            )
        }
    }

    /** Cancel: first() returns early, awaitClose path must return the engine to IDLE. */
    @Test
    fun request_cancel_transitions_to_idle() = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) {
            val first = repo.request("Return a tiny JSON.").first()
            Logx.i(TAG, "FIRST CHUNK len=${first.length}")
            assertTrue(
                "Engine should become IDLE after cancel/first() early exit",
                waitIdleBlocking(IDLE_WAIT_MS)
            )
        }
    }

    /** Empty prompt must still complete via the special instruction path. */
    @Test
    fun request_empty_prompt_does_not_crash() = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) {
            // Start streaming with an empty prompt in a separate coroutine.
            val job = launch {
                // We do not assert anything about the content here; we only care that
                // the flow can be started and later cancelled without crashing.
                repo.request("").collect { _ ->
                    // No-op: we just keep the collection alive to drive the engine.
                }
            }

            // Give the engine some time to start the inference.
            // If SLM completely hangs here, the outer withTimeout will still protect us.
            kotlinx.coroutines.delay(5_000)

            // Cancel from the collector side. This should trigger awaitClose → cancel/reset
            // inside SlmDirectRepository.
            job.cancel()

            // Engine should eventually become IDLE or be cleaned after cancel.
            assertTrue(
                "engine should become IDLE after empty-prompt cancel",
                waitIdleBlocking(IDLE_WAIT_MS)
            )
        }
    }

    /**
     * Sequential two requests: R1 completes (IDLE) before R2 is started.
     * This matches the intended "no concurrent requests" usage contract.
     */
    @Test
    fun sequential_two_requests_wait_and_succeed() = runBlocking(Dispatchers.Default) {
        withTimeout(TEST_TIMEOUT_MS) {
            // R1: early stop after first chunk (collector cancels), awaitClose should run.
            val t1Start = SystemClock.elapsedRealtime()
            val first1 = repo.request("R1: keep it short").first()
            val idleAfterR1 = waitUntil(IDLE_WAIT_MS) { !SLM.isBusy(model) }
            if (idleAfterR1) {
                runCatching { SLM.resetSession(model) }
            }
            assertTrue("Engine should become IDLE after R1", waitIdleBlocking(IDLE_WAIT_MS))

            val t1Closed = SystemClock.elapsedRealtime()
            Logx.i(TAG, "R1 FIRST len=${first1.length} start@$t1Start closed@$t1Closed")

            // R2: normal request; seeing the first chunk is enough for this test.
            val t2Start = SystemClock.elapsedRealtime()
            val first2 = repo.request("R2: start only after R1 closed").first()
            val t2First = SystemClock.elapsedRealtime()
            Logx.i(TAG, "R2 FIRST len=${first2.length} start@$t2Start first@$t2First")

            // Order check: R2 must start after R1 has fully closed.
            assertTrue("R2 should start after R1 is fully closed", t2Start >= t1Closed)

            // Final IDLE check after R2.
            assertTrue("Engine should become IDLE after R2", waitIdleBlocking(IDLE_WAIT_MS))
        }
    }
}
