// file: app/src/androidTest/java/com/negi/survey/slm/SlmHelperInstrumentationTest.kt
/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SlmHelperInstrumentationTest.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
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
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Instrumentation tests for SLM helper behavior.
 *
 * Guarantees to assert:
 *  - Robust initialize with GPU->CPU fallback; wait for model.instance.
 *  - Busy flag toggling: true during generation, false after completion/cancel+cleanup.
 *  - Cancellation: after cancel, wait for a terminal signal (finished or onClean),
 *    then reset the session before the next run.
 *  - Stream reconstruction: tolerate finish-time behaviors using overlap-safe append.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SlmHelperInstrumentationTest {

    companion object {
        private const val TAG = "SlmHelperInstrTest"
        private const val TIMEOUT_SEC = 180L

        private lateinit var appCtx: Context
        private lateinit var model: Model
        private val initialized = AtomicBoolean(false)

        @BeforeClass
        @JvmStatic
        fun beforeClass() {
            appCtx = InstrumentationRegistry.getInstrumentation().targetContext
            Log.i(TAG, "targetContext=${appCtx.packageName}")
        }

        @AfterClass
        @JvmStatic
        fun afterClass() {
            // Best-effort cleanup (only if model was actually initialized).
            runCatching {
                if (this::model.isInitialized) {
                    SLM.cleanUp(model) {}
                }
            }.onFailure {
                Log.w(TAG, "cleanUp failed in @AfterClass: ${it.message}")
            }
        }
    }

    @get:Rule
    val modelRule = ModelAssetRule()

    // Global watchdog for hangs (CI safety).
    // Align with TIMEOUT_SEC so askMeta() and rule agree.
    @get:Rule
    val globalTimeout: Timeout = Timeout.seconds(TIMEOUT_SEC)

    @Before
    fun setUp() {
        if (initialized.compareAndSet(false, true)) {
            Log.i(TAG, "Initial model setup (first test)")

            // Try GPU first.
            model = Model(
                name = "gemma3-local-test",
                taskPath = modelRule.internalModel.absolutePath,
                config = mapOf(
                    ConfigKey.ACCELERATOR to Accelerator.GPU.label,
                    ConfigKey.MAX_TOKENS to 512,
                    ConfigKey.TOP_K to 40,
                    ConfigKey.TOP_P to 0.9f,
                    ConfigKey.TEMPERATURE to 0.7f
                )
            )

            var initErr = initModel(model)
            if (!initErr.isNullOrEmpty()) {
                Log.w(TAG, "GPU init failed: $initErr — retrying with CPU")

                // CPU fallback.
                model = Model(
                    name = "gemma3-local-test",
                    taskPath = modelRule.internalModel.absolutePath,
                    config = mapOf(
                        ConfigKey.ACCELERATOR to Accelerator.CPU.label,
                        ConfigKey.MAX_TOKENS to 512,
                        ConfigKey.TOP_K to 40,
                        ConfigKey.TOP_P to 0.9f,
                        ConfigKey.TEMPERATURE to 0.7f
                    )
                )
                initErr = initModel(model)
            }

            assertTrue("Init error: $initErr", initErr.isNullOrEmpty())

            // model.instance may be set asynchronously; poll briefly.
            assertTrue(
                "Model instance must be created",
                waitUntil(timeoutMs = 15_000) { model.instance != null }
            )
            Log.i(TAG, "Model initialized: instance=${model.instance}")
        } else {
            assertNotNull("Model instance must exist", model.instance)
        }

        // Ensure each test starts from a non-busy state.
        assertFalse("busy should be false at test start", SLM.isBusy(model))
    }

    @After
    fun tearDown() {
        // Defensive: cancel any leftover generation and reset the session when idle.
        runCatching { SLM.cancel(model) }
        if (!SLM.isBusy(model)) {
            runCatching { SLM.resetSession(model) }
        }
    }

    // ---------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------

    private fun initModel(m: Model): String? {
        val initDone = CountDownLatch(1)
        var initErr: String? = null
        SLM.initialize(appCtx, m) { err ->
            initErr = err
            initDone.countDown()
        }
        assertTrue(
            "initialize did not return within 12s",
            initDone.await(12, TimeUnit.SECONDS)
        )
        return initErr
    }

    /**
     * Polls until [cond] becomes true or the timeout elapses.
     * Uses a small sleep to avoid busy-waiting.
     */
    private fun waitUntil(timeoutMs: Long = 2_000, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (cond()) return true
            SystemClock.sleep(15)
        }
        return false
    }

    /**
     * Waits until SLM.isBusy(model) equals [expectBusy], or the timeout elapses.
     * NOTE: "busy=false" alone does NOT guarantee that the underlying session is
     * fully cleaned up. For cancellation, always wait for a terminal signal too.
     */
    private fun waitUntilBusy(expectBusy: Boolean, timeoutMs: Long = 5_000): Boolean {
        return waitUntil(timeoutMs) { SLM.isBusy(model) == expectBusy }
    }

    /**
     * Computes the maximum overlap length where the suffix of [base] matches
     * the prefix of [next]. Capped to keep worst-case from exploding on
     * very long strings.
     */
    private fun computeOverlap(base: CharSequence, next: CharSequence, cap: Int = 2048): Int {
        if (base.isEmpty() || next.isEmpty()) return 0
        val maxCheck = min(cap, min(base.length, next.length))
        for (len in maxCheck downTo 1) {
            var match = true
            val startBase = base.length - len
            for (i in 0 until len) {
                if (base[startBase + i] != next[i]) {
                    match = false
                    break
                }
            }
            if (match) return len
        }
        return 0
    }

    /**
     * Overlap-safe append at finish-time:
     *  - If [maybeFull] looks like a complete final text (and starts with current buffer),
     *    replace the buffer with it (robust "full text at finish" handling).
     *  - Otherwise append only the non-overlapping suffix.
     *  - If empty, do nothing.
     */
    private fun appendFinishChunkSafely(sb: StringBuilder, maybeFull: String) {
        if (maybeFull.isEmpty()) return
        val current = sb.toString()
        if (maybeFull.length >= current.length && maybeFull.startsWith(current)) {
            sb.clear()
            sb.append(maybeFull)
            return
        }
        val overlap = computeOverlap(sb, maybeFull)
        sb.append(maybeFull.substring(overlap))
    }

    /**
     * A longer prompt to keep the stream alive for a while.
     */
    private fun longPrompt(): String =
        "Write a very long, multi-paragraph explanation about Android instrumentation testing, " +
                "including test runners, rules, IdlingResource, synchronization pitfalls, best practices, " +
                "and code snippets. Make it detailed and comprehensive."

    private data class AskResult(
        val text: String,
        val partials: Int,
        val onClean: Boolean,
        val durationMs: Long
    )

    /**
     * Collects streaming output until completion, reconstructing the final text robustly.
     * Returns meta info, used by some tests.
     *
     * English comment:
     * - Best-effort checks busy=true/false, but does not hard-fail if the model
     *   short-circuits too quickly (e.g., immediate error or synchronous finish).
     * - Dedicated tests (busy_flag_toggles_correctly, cancel_*) still assert
     *   strict busy semantics where appropriate.
     */
    private fun askMeta(
        prompt: String,
        timeoutSec: Long = TIMEOUT_SEC,
        requireNotBlank: Boolean = true,
        logPrefix: String = ""
    ): AskResult {
        val done = CountDownLatch(1)
        val sb = StringBuilder()
        var partialCount = 0
        var onCleanCalled = false
        val t0 = SystemClock.elapsedRealtime()

        SLM.runInference(
            model = model,
            input = prompt,
            listener = { partial, finished ->
                if (partial.isNotEmpty()) {
                    partialCount++
                    Log.i(
                        TAG,
                        "${logPrefix}partial[$partialCount](${partial.length})=" +
                                "${partial.take(160)} ..."
                    )
                    if (!finished) {
                        val overlap = computeOverlap(sb, partial)
                        sb.append(partial.substring(overlap))
                    } else {
                        appendFinishChunkSafely(sb, partial)
                    }
                }
                if (finished) done.countDown()
            },
            onClean = {
                onCleanCalled = true
                done.countDown()
            }
        )

        // Best-effort: we expect busy to turn true, but do not fail if it does not.
        val becameBusy = waitUntilBusy(true)
        if (!becameBusy) {
            Log.w(
                TAG,
                "${logPrefix}busy never observed as true after start; " +
                        "proceeding (possible immediate finish or error short-circuit)."
            )
        }

        assertTrue(
            "generation did not finish within $timeoutSec sec",
            done.await(timeoutSec, TimeUnit.SECONDS)
        )

        // Best-effort wait for idle; do not assert here because some error paths
        // may already be idle or flip quickly.
        val backToIdle = waitUntilBusy(false)
        if (!backToIdle) {
            Log.w(TAG, "${logPrefix}busy did not converge to false within wait window")
        }

        val out = sb.toString().trim()
        val dur = SystemClock.elapsedRealtime() - t0
        Log.i(
            TAG,
            "${logPrefix}final(len=${out.length}, partials=$partialCount, " +
                    "onClean=$onCleanCalled, dur=${dur}ms) :: ${out.take(200)}"
        )

        if (requireNotBlank) {
            assertTrue("output should not be blank", out.isNotBlank())
        }

        // Reset session eagerly when possible to avoid cross-test interference.
        if (!SLM.isBusy(model)) {
            SLM.resetSession(model)
        }
        return AskResult(out, partialCount, onCleanCalled, dur)
    }

    private fun ask(
        prompt: String,
        timeoutSec: Long = TIMEOUT_SEC,
        requireNotBlank: Boolean = true,
        logPrefix: String = ""
    ): String = askMeta(prompt, timeoutSec, requireNotBlank, logPrefix).text

    // ---------------------------------------------------------------------
    // Test cases (existing + additional)
    // ---------------------------------------------------------------------

    @Test
    fun generate_short_prompt_multiple_times() {
        repeat(4) { i ->
            val out = ask(
                prompt = "100文字でラーメンの作り方を教えて下さい",
                logPrefix = "[run#$i] "
            )
            assertTrue("[$i] output should not be blank", out.isNotBlank())
        }
    }

    @Test
    fun cancel_stops_generation_and_allows_next() {
        val terminated = CountDownLatch(1)   // finished OR onClean
        val firstPartial = CountDownLatch(1) // wait for an actual streaming start
        var onCleanCalled = false
        var finishedSeen = false
        var partialSeen = false

        SLM.runInference(
            model = model,
            input = longPrompt(),
            listener = { partial, finished ->
                if (partial.isNotEmpty()) {
                    partialSeen = true
                    firstPartial.countDown()
                }
                if (finished) {
                    finishedSeen = true
                    terminated.countDown()
                }
            },
            onClean = {
                onCleanCalled = true
                terminated.countDown()
            }
        )

        assertTrue("busy should become true", waitUntilBusy(true))
        firstPartial.await(3, TimeUnit.SECONDS) // best-effort

        val tCancel = SystemClock.elapsedRealtime()
        SLM.cancel(model = model)

        assertTrue(
            "previous invocation did not terminate after cancel",
            terminated.await(5, TimeUnit.SECONDS)
        )
        assertTrue("busy should drop after cancel/termination", waitUntilBusy(false))

        val tDone = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "cancel → terminated+idle elapsed = ${tDone - tCancel} ms " +
                    "(partialSeen=$partialSeen, finishedSeen=$finishedSeen, onCleanCalled=$onCleanCalled)"
        )
        assertTrue("Either finished or onClean must be seen", finishedSeen || onCleanCalled)

        runCatching { SLM.resetSession(model) }
        assertTrue("still idle after reset", waitUntilBusy(false))

        val out2 = ask(
            prompt = "Confirm you can respond after a cancel.",
            logPrefix = "[post-cancel] "
        )
        assertTrue("output after cancel should not be blank", out2.isNotBlank())
    }

    @Test
    fun busy_flag_toggles_correctly() {
        val done = CountDownLatch(1)
        SLM.runInference(
            model = model,
            input = longPrompt(),
            listener = { _, finished -> if (finished) done.countDown() },
            onClean = { done.countDown() }
        )

        assertTrue("busy must turn true soon after start", waitUntilBusy(true))
        assertTrue(
            "generation must finish within $TIMEOUT_SEC sec",
            done.await(TIMEOUT_SEC, TimeUnit.SECONDS)
        )
        assertTrue("busy must return to false after finish", waitUntilBusy(false))
    }

    @Test
    fun overlap_and_append_safety_smoke() {
        val sb = StringBuilder()

        // Case A: partial-only stream
        listOf("Hel", "llo", ", w", "orld").forEach { part ->
            val overlap = computeOverlap(sb, part)
            sb.append(part.substring(overlap))
        }
        assertEquals("Hello, world", sb.toString())

        // Case C-like: finish delivers full text
        appendFinishChunkSafely(sb, "Hello, world! This is final.")
        assertEquals("Hello, world! This is final.", sb.toString())

        // Case B: small tail-delta at finish
        val sb2 = StringBuilder("ABCDEF")
        appendFinishChunkSafely(sb2, "DEFGH")
        assertEquals("ABCDEFGH", sb2.toString())
    }

    /** Empty prompt is allowed when requireNotBlank=false, and busy toggles correctly. */
    @Test
    fun empty_prompt_allows_blank_when_flag_false() {
        val res = askMeta(
            prompt = "",
            requireNotBlank = false,
            logPrefix = "[empty] "
        )
        assertNotNull(res.text) // may be blank
        assertTrue("busy should be false now", waitUntilBusy(false))
    }

    /** Cancel immediately before any partial arrives: must still observe terminal (finished|onClean). */
    @Test
    fun cancel_before_first_partial() {
        val terminated = CountDownLatch(1)
        var partialSeen = false
        var finishedSeen = false
        var onCleanCalled = false

        SLM.runInference(
            model = model,
            input = longPrompt(),
            listener = { partial, finished ->
                if (partial.isNotEmpty()) partialSeen = true
                if (finished) {
                    finishedSeen = true
                    terminated.countDown()
                }
            },
            onClean = {
                onCleanCalled = true
                terminated.countDown()
            }
        )

        assertTrue("busy should become true", waitUntilBusy(true))

        // Cancel ASAP (before first partial if possible).
        SLM.cancel(model)

        assertTrue(
            "previous invocation did not terminate after immediate cancel",
            terminated.await(5, TimeUnit.SECONDS)
        )
        assertTrue("busy should drop after cancel/termination", waitUntilBusy(false))
        assertTrue("Either finished or onClean must be seen", finishedSeen || onCleanCalled)

        // Subsequent run should work, but may short-circuit quickly;
        // busy is checked in askMeta only as best-effort.
        val res = askMeta(
            prompt = "Ping after immediate cancel.",
            logPrefix = "[after-immediate-cancel] "
        )
        assertTrue(res.text.isNotBlank())
        Log.i(TAG, "cancel_before_first_partial: partialSeen=$partialSeen")
    }

    /** Calling cancel after completion should be a no-op and keep idle state. */
    @Test
    fun cancel_after_finish_is_noop() {
        val txt = ask("Short answer please.", logPrefix = "[finish-then-cancel] ")
        assertTrue(txt.isNotBlank())
        assertTrue("should be idle", waitUntilBusy(false))

        // Cancel after finish: should not throw nor flip busy=true.
        runCatching { SLM.cancel(model) }
        assertTrue("still idle after post-finish cancel", waitUntilBusy(false))
    }

    /** Re-initialization should be idempotent (no error, instance remains valid). */
    @Test
    fun reinitialize_is_idempotent() {
        val err = initModel(model)
        assertTrue("Second initialize should not error: $err", err.isNullOrEmpty())
        assertNotNull("Model instance should still exist", model.instance)
    }

    /** Repeated runs with explicit resets in between to catch lingering state issues. */
    @Test
    fun repeated_runs_with_intermediate_resets() {
        repeat(5) { i ->
            val res = askMeta(
                prompt = "Run #$i: say hello briefly.",
                logPrefix = "[repeat-$i] "
            )
            assertTrue("[$i] non-blank", res.text.isNotBlank())
            assertTrue("[$i] idle before reset", waitUntilBusy(false))
            runCatching { SLM.resetSession(model) }
            assertTrue("[$i] idle after reset", waitUntilBusy(false))
        }
    }

    /** Very long prompt smoke: should complete within timeout and produce non-empty output. */
    @Test
    fun long_prompt_completes_and_non_empty() {
        val res = askMeta(
            prompt = longPrompt(),
            timeoutSec = TIMEOUT_SEC,
            logPrefix = "[long] "
        )
        assertTrue("long prompt output should not be blank", res.text.isNotBlank())
        assertTrue("should be idle after long", waitUntilBusy(false))
        assertTrue("should have streamed >0 partials", res.partials > 0)
    }

    /** Overlap function edge cases. */
    @Test
    fun compute_overlap_edge_cases() {
        assertEquals(0, computeOverlap("", "abc"))
        assertEquals(0, computeOverlap("abc", ""))
        assertEquals(3, computeOverlap("abc", "abc"))
        assertEquals(2, computeOverlap("zzab", "abxx"))
        assertEquals(1, computeOverlap("xy", "y"))
        assertEquals(0, computeOverlap("abcd", "efgh"))
    }

    /** appendFinishChunkSafely edge cases (non-prefix full, partial tail). */
    @Test
    fun append_finish_chunk_edge_cases() {
        val sb1 = StringBuilder("Hello")
        appendFinishChunkSafely(sb1, "") // no-op
        assertEquals("Hello", sb1.toString())

        // Full text that does NOT start with current buffer: should append tail-only via overlap.
        val sb2 = StringBuilder("Hello, ")
        appendFinishChunkSafely(sb2, "llo, world!") // overlaps "llo, "
        assertEquals("Hello, world!", sb2.toString())
    }
}
