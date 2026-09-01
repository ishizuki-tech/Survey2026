// file: app/src/androidTest/java/com/negi/survey/slm/SlmHelperInstrumentationTest.kt
/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SlmHelperInstrumentationTest.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025-2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.slm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.ModelAssetRule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * Real-device instrumentation tests for the current [SLM] facade.
 *
 * Current contracts under test:
 * - Initialization is idempotent and supports GPU -> CPU fallback.
 * - [SLM.runInference] emits DELTA chunks.
 * - resultListener(done=true) is logical generation completion.
 * - cleanUpListener is the native termination / safe-point signal.
 * - Cancellation eventually reaches native termination and does not poison the
 *   next request.
 * - Conversation reset keeps the model reusable.
 *
 * Historical APIs intentionally no longer tested:
 * - Model.instance
 * - SLM.resetSession()
 * - listener=/onClean= legacy named parameters
 * - SLM.isBusy(model) as a runInference lifecycle oracle
 *
 * Why SLM.isBusy(model) is not used here:
 * The current LiteRtLM public busy flag is not the authoritative lifecycle
 * state for callback-based runInference work. Native completion is represented
 * by cleanUpListener, which is the correct signal for these tests.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SlmHelperInstrumentationTest {

    companion object {
        private const val TAG = "SlmHelperInstrTest"

        /** Global JUnit watchdog. */
        private const val TIMEOUT_SEC = 180L

        /** Initialization timeout. */
        private const val INIT_TIMEOUT_MS = 60_000L

        /** Normal generation logical-completion timeout. */
        private const val GENERATION_TIMEOUT_MS = 120_000L

        /** Native cleanup grace after logical completion. */
        private const val NATIVE_CLEANUP_TIMEOUT_MS = 20_000L

        /** Cancellation must reach native termination within this window. */
        private const val CANCEL_TIMEOUT_MS = 15_000L

        /** Best-effort wait for a first partial before a cancellation test. */
        private const val FIRST_PARTIAL_WAIT_MS = 5_000L

        /** Tiny scheduling grace used only after fire-and-forget control calls. */
        private const val CONTROL_SETTLE_MS = 300L

        /** Keep instrumentation output bounded and reasonably fast. */
        private const val TEST_MAX_TOKENS = 512

        private lateinit var appCtx: Context
        private lateinit var model: Model

        private val initialized =
            AtomicBoolean(false)

        private val initLock =
            Any()

        @BeforeClass
        @JvmStatic
        fun beforeClass() {
            appCtx =
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext
                    .applicationContext

            SLM.setApplicationContext(appCtx)

            Log.i(
                TAG,
                "targetContext=${appCtx.packageName}"
            )
        }

        @AfterClass
        @JvmStatic
        fun afterClass() {
            if (!::model.isInitialized) {
                return
            }

            runCatching {
                forceCleanUpBlocking(
                    targetModel = model,
                    timeoutMs = NATIVE_CLEANUP_TIMEOUT_MS,
                )
            }.onFailure { error ->
                Log.w(
                    TAG,
                    "forceCleanUp failed in @AfterClass: ${error.message}",
                    error,
                )
            }
        }

        /**
         * Convert callback-style force cleanup into a bounded blocking helper.
         *
         * Instrumentation tests run off the app UI thread, so a bounded latch is
         * appropriate here.
         */
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

    @get:Rule
    val modelRule =
        ModelAssetRule()

    @get:Rule
    val globalTimeout: Timeout =
        Timeout.seconds(TIMEOUT_SEC)

    @Before
    fun setUp() {
        ensureModelInitialized()
    }

    @After
    fun tearDown() {
        LiteRtLM.runControlTestHooks = null

        /**
         * Best-effort defensive cancellation.
         *
         * A correctly written test should already have observed native cleanup.
         * This call only protects the following test if an assertion aborted
         * midway through a generation.
         */
        runCatching {
            SLM.cancel(model)
        }
    }

    // =====================================================================
    // Initialization helpers
    // =====================================================================

    /**
     * Initialize once per test class.
     *
     * GPU is preferred unless instrumentation args explicitly request CPU.
     * If GPU initialization fails, tear down partial state and retry on CPU.
     *
     * We intentionally do not poll Model.instance; runtime ownership now lives
     * inside LiteRtLM rather than Model.
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
                "Initial model setup accelerator=${preferred.label}"
            )

            try {
                initializeModelBlocking(candidate)
            } catch (firstError: Throwable) {
                if (preferred == Accelerator.CPU) {
                    throw firstError
                }

                Log.w(
                    TAG,
                    "GPU init failed: ${firstError.message}; retrying with CPU",
                    firstError,
                )

                /**
                 * Runtime identity is model-name/path based, so remove any
                 * partially-created GPU runtime before CPU retry.
                 */
                runCatching {
                    forceCleanUpBlocking(
                        targetModel = candidate,
                        timeoutMs = NATIVE_CLEANUP_TIMEOUT_MS,
                    )
                }.onFailure { cleanupError ->
                    Log.w(
                        TAG,
                        "Cleanup before CPU fallback failed: ${cleanupError.message}",
                        cleanupError,
                    )
                }

                candidate =
                    createModel(Accelerator.CPU)

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
                "Model initialized: " +
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
    // Streaming helpers
    // =====================================================================

    private data class AskResult(
        val text: String,
        val partials: Int,
        val logicalDone: Boolean,
        val nativeClean: Boolean,
        val durationMs: Long,
    )

    private data class ObservedRun(
        val runId: AtomicLong = AtomicLong(0L),
        val started: CountDownLatch = CountDownLatch(1),
        val logicalDone: CountDownLatch = CountDownLatch(1),
        val nativeClean: CountDownLatch = CountDownLatch(1),
        val outputSeen: AtomicBoolean = AtomicBoolean(false),
        val error: AtomicReference<String?> = AtomicReference(null),
    )

    private fun startObservedRun(
        prompt: String,
        releaseRunStart: CountDownLatch? = null,
    ): ObservedRun {
        val observed = ObservedRun()

        SLM.runInference(
            model = model,
            input = prompt,
            resultListener = { delta, done ->
                if (delta.isNotBlank()) observed.outputSeen.set(true)
                if (done) observed.logicalDone.countDown()
            },
            cleanUpListener = {
                observed.nativeClean.countDown()
            },
            onError = { message ->
                observed.error.compareAndSet(null, message)
            },
            onRunStarted = { runId ->
                observed.runId.set(runId)
                observed.started.countDown()
                releaseRunStart?.await(
                    GENERATION_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS,
                )
            },
        )

        return observed
    }

    private fun assertObservedRunCompleted(
        label: String,
        run: ObservedRun,
    ) {
        assertTrue(
            "$label did not reach logical completion",
            run.logicalDone.await(GENERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )
        assertTrue(
            "$label did not reach native cleanup",
            run.nativeClean.await(NATIVE_CLEANUP_TIMEOUT_MS, TimeUnit.MILLISECONDS),
        )
        assertTrue(
            "$label should emit non-blank output",
            run.outputSeen.get(),
        )
        assertEquals(
            "$label should not report an error",
            null,
            run.error.get(),
        )
    }

    /**
     * Run one normal inference and wait for BOTH lifecycle milestones:
     *
     * 1. resultListener(done=true): logical generation complete.
     * 2. cleanUpListener: native stream termination/safe point reached.
     *
     * DELTA contract:
     * Every non-empty partial is appended exactly once in encounter order.
     * We intentionally do not apply overlap suppression because doing so could
     * hide duplicate-delta bugs in the runtime.
     */
    private fun askMeta(
        prompt: String,
        timeoutMs: Long = GENERATION_TIMEOUT_MS,
        requireNotBlank: Boolean = true,
        logPrefix: String = "",
    ): AskResult {
        val logicalDoneLatch =
            CountDownLatch(1)

        val nativeCleanLatch =
            CountDownLatch(1)

        val errorLatch =
            CountDownLatch(1)

        val output =
            StringBuilder()

        val outputLock =
            Any()

        val partialCount =
            AtomicInteger(0)

        val logicalDoneSeen =
            AtomicBoolean(false)

        val nativeCleanSeen =
            AtomicBoolean(false)

        val errorRef =
            AtomicReference<String?>(null)

        val startedAt =
            SystemClock.elapsedRealtime()

        SLM.runInference(
            model = model,
            input = prompt,
            resultListener = { delta, done ->
                if (delta.isNotEmpty()) {
                    val index =
                        partialCount.incrementAndGet()

                    synchronized(outputLock) {
                        output.append(delta)
                    }

                    Log.i(
                        TAG,
                        "${logPrefix}delta[$index](${delta.length})=" +
                                "${delta.take(160)}"
                    )
                }

                if (done) {
                    logicalDoneSeen.set(true)
                    logicalDoneLatch.countDown()
                }
            },
            cleanUpListener = {
                nativeCleanSeen.set(true)
                nativeCleanLatch.countDown()
            },
            onError = { message ->
                errorRef.compareAndSet(
                    null,
                    message.ifBlank { "Unknown LiteRT-LM error" },
                )
                errorLatch.countDown()

                /**
                 * Release the logical waiter. The test will inspect errorRef and
                 * fail with the backend message below.
                 */
                logicalDoneLatch.countDown()
            },
            images = emptyList(),
            audioClips = emptyList(),
        )

        val logicalOrError =
            logicalDoneLatch.await(
                timeoutMs,
                TimeUnit.MILLISECONDS,
            )

        assertTrue(
            "generation did not reach logical completion/error within ${timeoutMs}ms",
            logicalOrError,
        )

        val backendError =
            errorRef.get()

        if (backendError != null) {
            /**
             * Give native cleanup a short chance before failing the assertion so
             * the following test is less likely to inherit an active run.
             */
            nativeCleanLatch.await(
                NATIVE_CLEANUP_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )

            throw AssertionError(
                "SLM generation failed: $backendError"
            )
        }

        assertTrue(
            "resultListener(done=true) was not observed",
            logicalDoneSeen.get(),
        )

        val nativeClean =
            nativeCleanLatch.await(
                NATIVE_CLEANUP_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )

        assertTrue(
            "native cleanup/safe-point callback not observed within " +
                    "${NATIVE_CLEANUP_TIMEOUT_MS}ms after logical completion",
            nativeClean,
        )

        /**
         * If onError raced with successful logical completion, surface it.
         */
        if (errorLatch.count == 0L) {
            val lateError =
                errorRef.get()

            if (lateError != null) {
                throw AssertionError(
                    "SLM reported an error after logical completion: $lateError"
                )
            }
        }

        val finalText =
            synchronized(outputLock) {
                output.toString().trim()
            }

        val durationMs =
            SystemClock.elapsedRealtime() -
                    startedAt

        Log.i(
            TAG,
            "${logPrefix}final(" +
                    "len=${finalText.length}, " +
                    "partials=${partialCount.get()}, " +
                    "logicalDone=${logicalDoneSeen.get()}, " +
                    "nativeClean=${nativeCleanSeen.get()}, " +
                    "dur=${durationMs}ms" +
                    ") :: ${finalText.take(200)}"
        )

        if (requireNotBlank) {
            assertTrue(
                "output should not be blank",
                finalText.isNotBlank(),
            )
        }

        return AskResult(
            text = finalText,
            partials = partialCount.get(),
            logicalDone = logicalDoneSeen.get(),
            nativeClean = nativeCleanSeen.get(),
            durationMs = durationMs,
        )
    }

    private fun ask(
        prompt: String,
        timeoutMs: Long = GENERATION_TIMEOUT_MS,
        requireNotBlank: Boolean = true,
        logPrefix: String = "",
    ): String =
        askMeta(
            prompt = prompt,
            timeoutMs = timeoutMs,
            requireNotBlank = requireNotBlank,
            logPrefix = logPrefix,
        ).text

    /**
     * Start inference, optionally wait for the first partial, cancel, and then
     * wait for native cleanup.
     */
    private fun cancelRunAndAwaitNativeCleanup(
        prompt: String,
        waitForFirstPartial: Boolean,
    ) {
        val firstPartialLatch =
            CountDownLatch(1)

        val nativeCleanLatch =
            CountDownLatch(1)

        val errorRef =
            AtomicReference<String?>(null)

        SLM.runInference(
            model = model,
            input = prompt,
            resultListener = { delta, _ ->
                if (delta.isNotEmpty()) {
                    firstPartialLatch.countDown()
                }
            },
            cleanUpListener = {
                nativeCleanLatch.countDown()
            },
            onError = { message ->
                errorRef.compareAndSet(
                    null,
                    message,
                )
            },
            images = emptyList(),
            audioClips = emptyList(),
        )

        if (waitForFirstPartial) {
            firstPartialLatch.await(
                FIRST_PARTIAL_WAIT_MS,
                TimeUnit.MILLISECONDS,
            )
        }

        val cancelAt =
            SystemClock.elapsedRealtime()

        SLM.cancel(model)

        val cleaned =
            nativeCleanLatch.await(
                CANCEL_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )

        val elapsed =
            SystemClock.elapsedRealtime() -
                    cancelAt

        Log.i(
            TAG,
            "cancel -> native cleanup elapsed=${elapsed}ms " +
                    "firstPartial=${firstPartialLatch.count == 0L} " +
                    "error=${errorRef.get()}"
        )

        assertTrue(
            "native cleanup was not observed after cancel within ${CANCEL_TIMEOUT_MS}ms",
            cleaned,
        )
    }

    private fun longPrompt(): String =
        "Write a detailed multi-paragraph explanation about Android " +
                "instrumentation testing, including test runners, rules, " +
                "synchronization pitfalls, best practices, and code examples."

    // =====================================================================
    // Tests
    // =====================================================================

    @Test
    fun generate_short_prompt_multiple_times() {
        repeat(4) { index ->
            val result =
                askMeta(
                    prompt = "100文字でラーメンの作り方を教えて下さい",
                    logPrefix = "[run#$index] ",
                )

            assertTrue(
                "[$index] output should not be blank",
                result.text.isNotBlank(),
            )

            assertTrue(
                "[$index] logical completion expected",
                result.logicalDone,
            )

            assertTrue(
                "[$index] native cleanup expected",
                result.nativeClean,
            )
        }
    }

    @Test
    fun cancel_stops_generation_and_allows_next() {
        cancelRunAndAwaitNativeCleanup(
            prompt = longPrompt(),
            waitForFirstPartial = true,
        )

        val next =
            ask(
                prompt = "Confirm you can respond after a cancel.",
                logPrefix = "[post-cancel] ",
            )

        assertTrue(
            "output after cancel should not be blank",
            next.isNotBlank(),
        )
    }

    @Test
    fun stale_scoped_cancel_does_not_cancel_newer_run() =
        runBlocking {
            withTimeout(GENERATION_TIMEOUT_MS) {
                val cancelPaused = CompletableDeferred<Unit>()
                val releaseCancel = CompletableDeferred<Unit>()
                val releaseFirstRunStart = CountDownLatch(1)

                try {
                    val first =
                        startObservedRun(
                            prompt = "R1: answer ONE briefly.",
                            releaseRunStart = releaseFirstRunStart,
                        )
                    assertTrue(
                        "R1 did not publish its run ID",
                        first.started.await(GENERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    )

                    val firstRunId = first.runId.get()
                    assertTrue("R1 run ID must be positive", firstRunId > 0L)

                    LiteRtLM.runControlTestHooks =
                        LiteRtLM.RunControlTestHooks(
                            beforeScopedCancelValidation = { expectedRunId ->
                                if (expectedRunId == firstRunId) {
                                    cancelPaused.complete(Unit)
                                    releaseCancel.await()
                                }
                            },
                        )

                    SLM.cancel(
                        model = model,
                        expectedRunId = firstRunId,
                    )

                    cancelPaused.await()
                    releaseFirstRunStart.countDown()
                    assertObservedRunCompleted("R1", first)

                    SLM.resetConversationAndWait(
                        model = model,
                        supportImage = false,
                        supportAudio = false,
                    )

                    val second = startObservedRun("R2: answer TWO briefly.")
                    assertTrue(
                        "R2 did not publish its run ID",
                        second.started.await(GENERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    )
                    assertTrue(
                        "R2 must have a different run ID",
                        second.runId.get() != firstRunId,
                    )

                    releaseCancel.complete(Unit)
                    assertObservedRunCompleted("R2", second)
                } finally {
                    releaseFirstRunStart.countDown()
                    releaseCancel.complete(Unit)
                    LiteRtLM.runControlTestHooks = null
                }
            }
        }

    @Test
    fun stale_hard_close_does_not_close_newer_run() =
        runBlocking {
            withTimeout(GENERATION_TIMEOUT_MS) {
                val hardClosePaused = CompletableDeferred<Unit>()
                val releaseHardClose = CompletableDeferred<Unit>()
                val secondHardClosePaused = CompletableDeferred<Unit>()
                val releaseSecondHardClose = CompletableDeferred<Unit>()

                try {
                    val firstRunId = AtomicLong(0L)
                    val secondRunId = AtomicLong(0L)

                    LiteRtLM.runControlTestHooks =
                        LiteRtLM.RunControlTestHooks(
                            armHardCloseOnRunStart = { runId ->
                                when {
                                    firstRunId.compareAndSet(0L, runId) -> true
                                    secondRunId.compareAndSet(0L, runId) -> true
                                    else -> false
                                }
                            },
                            awaitHardCloseAction = { expectedRunId ->
                                when (expectedRunId) {
                                    firstRunId.get() -> {
                                        hardClosePaused.complete(Unit)
                                        releaseHardClose.await()
                                    }

                                    secondRunId.get() -> {
                                        secondHardClosePaused.complete(Unit)
                                        releaseSecondHardClose.await()
                                    }
                                }
                            },
                        )

                    val first =
                        startObservedRun(
                            "R1 watchdog: answer ONE briefly.",
                        )
                    assertTrue(
                        "R1 did not publish its run ID",
                        first.started.await(GENERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    )
                    assertTrue("R1 run ID must be positive", firstRunId.get() > 0L)

                    hardClosePaused.await()
                    assertObservedRunCompleted("R1 watchdog owner", first)

                    SLM.resetConversationAndWait(
                        model = model,
                        supportImage = false,
                        supportAudio = false,
                    )

                    val second = startObservedRun("R2 watchdog: answer TWO briefly.")
                    assertTrue(
                        "R2 did not publish its run ID",
                        second.started.await(GENERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    )
                    assertTrue(
                        "R2 must have a different run ID",
                        second.runId.get() != firstRunId.get(),
                    )
                    assertEquals(
                        "R2 watchdog must own R2's run ID",
                        second.runId.get(),
                        secondRunId.get(),
                    )

                    secondHardClosePaused.await()

                    releaseHardClose.complete(Unit)
                    assertObservedRunCompleted("R2 watchdog successor", second)
                    releaseSecondHardClose.complete(Unit)
                } finally {
                    releaseHardClose.complete(Unit)
                    releaseSecondHardClose.complete(Unit)
                    LiteRtLM.runControlTestHooks = null
                }
            }
        }

    /**
     * Replacement for the old busy_flag_toggles_correctly test.
     *
     * For runInference, the meaningful lifecycle contract is:
     * logical completion -> native cleanup.
     */
    @Test
    fun logical_completion_is_followed_by_native_cleanup() {
        val result =
            askMeta(
                prompt = "Return a short answer.",
                logPrefix = "[lifecycle] ",
            )

        assertTrue(
            "logical completion callback expected",
            result.logicalDone,
        )

        assertTrue(
            "native cleanup callback expected",
            result.nativeClean,
        )
    }

    /**
     * Validate the current DELTA contract.
     *
     * We do not attempt to deduplicate/overlap-correct chunks. The reconstructed
     * text is the direct concatenation of exactly what SLM emitted.
     */
    @Test
    fun delta_stream_reconstructs_non_empty_output() {
        val result =
            askMeta(
                prompt = "Explain in two short sentences why local AI can work offline.",
                logPrefix = "[delta] ",
            )

        assertTrue(
            "at least one non-empty delta expected",
            result.partials > 0,
        )

        assertTrue(
            "reconstructed output should not be blank",
            result.text.isNotBlank(),
        )
    }

    @Test
    fun empty_prompt_allows_blank_when_flag_false() {
        val result =
            askMeta(
                prompt = "",
                requireNotBlank = false,
                logPrefix = "[empty] ",
            )

        assertTrue(
            "logical completion expected for empty prompt path",
            result.logicalDone,
        )

        assertTrue(
            "native cleanup expected for empty prompt path",
            result.nativeClean,
        )
    }

    @Test
    fun cancel_before_first_partial() {
        cancelRunAndAwaitNativeCleanup(
            prompt = longPrompt(),
            waitForFirstPartial = false,
        )

        val next =
            ask(
                prompt = "Ping after immediate cancel.",
                logPrefix = "[after-immediate-cancel] ",
            )

        assertTrue(
            "model should remain usable after immediate cancel",
            next.isNotBlank(),
        )
    }

    @Test
    fun cancel_after_finish_is_noop() {
        val first =
            ask(
                prompt = "Short answer please.",
                logPrefix = "[finish-then-cancel] ",
            )

        assertTrue(
            "first output should not be blank",
            first.isNotBlank(),
        )

        runCatching {
            SLM.cancel(model)
        }.getOrElse { error ->
            throw AssertionError(
                "Cancel after completion should not throw",
                error,
            )
        }

        /**
         * Behavioral assertion:
         * the backend must still accept another request.
         */
        val second =
            ask(
                prompt = "Answer OK.",
                logPrefix = "[after-post-finish-cancel] ",
            )

        assertTrue(
            "backend should remain usable after post-finish cancel",
            second.isNotBlank(),
        )
    }

    @Test
    fun reinitialize_is_idempotent() {
        runCatching {
            initializeModelBlocking(model)
        }.getOrElse { error ->
            throw AssertionError(
                "Second initializeIfNeeded should succeed",
                error,
            )
        }

        /**
         * Verify real usability rather than checking the removed Model.instance.
         */
        val output =
            ask(
                prompt = "Answer READY.",
                logPrefix = "[reinit] ",
            )

        assertTrue(
            "model should remain usable after idempotent initialize",
            output.isNotBlank(),
        )
    }

    @Test
    fun repeated_runs_with_intermediate_resets() {
        repeat(5) { index ->
            val result =
                askMeta(
                    prompt = "Run #$index: say hello briefly.",
                    logPrefix = "[repeat-$index] ",
                )

            assertTrue(
                "[$index] non-blank output expected",
                result.text.isNotBlank(),
            )

            /**
             * askMeta() has already observed native cleanup, so it is now safe
             * to reset the conversation.
             */
            SLM.resetConversation(
                model = model,
                supportImage = false,
                supportAudio = false,
                systemMessage = null,
                tools = emptyList(),
            )

            /**
             * resetConversation is a control operation whose implementation may
             * schedule internal work. Give it a small scheduling grace. The
             * next inference is still the real correctness check.
             */
            SystemClock.sleep(CONTROL_SETTLE_MS)
        }

        val after =
            ask(
                prompt = "Final request after repeated resets: answer OK.",
                logPrefix = "[after-resets] ",
            )

        assertTrue(
            "backend should remain usable after repeated resets",
            after.isNotBlank(),
        )
    }

    @Test
    fun explicit_reset_keeps_model_usable() {
        val before =
            ask(
                prompt = "Before reset: answer BEFORE.",
                logPrefix = "[before-reset] ",
            )

        assertTrue(
            "pre-reset output should not be blank",
            before.isNotBlank(),
        )

        SLM.resetConversation(
            model = model,
            supportImage = false,
            supportAudio = false,
            systemMessage = null,
            tools = emptyList(),
        )

        SystemClock.sleep(CONTROL_SETTLE_MS)

        val after =
            ask(
                prompt = "After reset: answer AFTER.",
                logPrefix = "[after-reset] ",
            )

        assertTrue(
            "post-reset output should not be blank",
            after.isNotBlank(),
        )
    }

    @Test
    fun long_prompt_completes_and_non_empty() {
        val result =
            askMeta(
                prompt = longPrompt(),
                timeoutMs = GENERATION_TIMEOUT_MS,
                logPrefix = "[long] ",
            )

        assertTrue(
            "long prompt output should not be blank",
            result.text.isNotBlank(),
        )

        assertTrue(
            "long prompt should stream at least one delta",
            result.partials > 0,
        )

        assertTrue(
            "long prompt must reach logical completion",
            result.logicalDone,
        )

        assertTrue(
            "long prompt must reach native cleanup",
            result.nativeClean,
        )
    }

    /**
     * Control-only smoke test: force cleanup then initialize again.
     *
     * This exercises the strongest teardown path and verifies reusability.
     */
    @Test
    fun force_cleanup_then_reinitialize_succeeds() {
        val cleaned =
            forceCleanUpBlocking(
                targetModel = model,
                timeoutMs = NATIVE_CLEANUP_TIMEOUT_MS,
            )

        assertTrue(
            "forceCleanUp callback expected",
            cleaned,
        )

        initializeModelBlocking(model)

        val output =
            ask(
                prompt = "After force cleanup and reinitialize, answer OK.",
                logPrefix = "[force-reinit] ",
            )

        assertTrue(
            "model should be usable after force cleanup + reinitialize",
            output.isNotBlank(),
        )
    }

    /**
     * Sanity check for test assumptions.
     *
     * The current deterministic test configuration should remain stable.
     */
    @Test
    fun test_model_configuration_is_deterministic() {
        assertEquals(
            1,
            model.getIntConfigValue(
                ConfigKey.TOP_K,
                -1,
            ),
        )

        assertEquals(
            0.0f,
            model.getFloatConfigValue(
                ConfigKey.TOP_P,
                -1f,
            ),
            0.0001f,
        )

        assertEquals(
            0.0f,
            model.getFloatConfigValue(
                ConfigKey.TEMPERATURE,
                -1f,
            ),
            0.0001f,
        )

        assertFalse(
            "model path must not be blank",
            model.taskPath.isBlank(),
        )

    }
}
