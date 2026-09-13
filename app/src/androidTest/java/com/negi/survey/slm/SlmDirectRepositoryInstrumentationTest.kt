// file: app/src/androidTest/java/com/negi/survey/slm/SlmDirectRepositoryInstrumentationTest.kt
/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SlmDirectRepositoryInstrumentationTest.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025-2026 IshizukiTech LLC. All rights reserved.
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device instrumentation tests for [LiteRtRepository].
 *
 * Historical note:
 * - The filename/class keeps the old "SlmDirectRepository" name so existing
 *   Android Studio run configurations do not break.
 * - The production repository is now [LiteRtRepository].
 *
 * Current contract under test:
 * - [LiteRtRepository.request] returns a cold streaming Flow.
 * - Collection starts inference.
 * - Normal collection completes only after repository-side termination handling.
 * - Cancelling the collector must not poison the next request.
 * - Process-wide inference serialization is owned by [LiteRtRepository].
 *
 * We intentionally do NOT test:
 * - Model.instance: runtime Engine/Conversation ownership now lives in LiteRtLM.
 * - SLM.resetSession(): replaced by resetConversation().
 * - SLM.isBusy(model) for repository streaming: the public LiteRtLM busy flag is
 *   not the authoritative lifecycle signal for runInference-based repository work.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SlmDirectRepositoryInstrumentationTest {

    @get:Rule
    val modelRule = ModelAssetRule()

    private lateinit var appCtx: Context
    private lateinit var repo: LiteRtRepository
    private lateinit var config: SurveyConfig

    companion object {
        private const val TAG = "LiteRtRepoInstrTest"

        private const val INIT_TIMEOUT_MS = 60_000L
        private const val CLEANUP_TIMEOUT_MS = 15_000L

        private const val TEST_TIMEOUT_MS = 90_000L
        private const val CANCEL_SETTLE_MS = 250L

        /**
         * A relatively small deterministic test configuration.
         *
         * TOP_K=1 / temperature=0 reduces output variance. TOP_P is kept inside
         * the normal sampler range.
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
                    model = model,
                    timeoutMs = CLEANUP_TIMEOUT_MS,
                )
            }.onFailure { error ->
                Logx.w(
                    TAG,
                    "SLM force cleanup failed in @AfterClass: ${error.message}"
                )
            }
        }

        /**
         * Callback-style force cleanup converted to a bounded blocking helper.
         *
         * This is test-only code and is never called from the Android main UI.
         */
        private fun forceCleanUpBlocking(
            model: Model,
            timeoutMs: Long,
        ): Boolean {
            val latch =
                CountDownLatch(1)

            SLM.forceCleanUp(model) {
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
            loadValidatedSurveyConfig(appCtx)

        ensureModelInitialized()

        repo =
            LiteRtRepository(
                model = model,
                config = config,
                appContext = appCtx,
            )
    }

    // =====================================================================
    // Setup helpers
    // =====================================================================

    private fun loadValidatedSurveyConfig(
        context: Context,
    ): SurveyConfig {
        return try {
            SurveyConfigLoader
                .fromAssets(
                    context,
                    "survey_config1.yaml",
                )
                .also { loaded ->
                    val issues =
                        loaded.validate()

                    assertTrue(
                        "SurveyConfig invalid:\n- " +
                                issues.joinToString("\n- "),
                        issues.isEmpty(),
                    )
                }
        } catch (error: Throwable) {
            throw AssertionError(
                "Failed to load or validate SurveyConfig: ${error.message}",
                error,
            )
        }
    }

    /**
     * Initialize once for the whole test class.
     *
     * GPU is attempted first unless instrumentation arguments force CPU.
     * If GPU initialization fails:
     * 1. Clean up any partial runtime state.
     * 2. Rebuild Model with CPU.
     * 3. Retry initialization.
     *
     * We no longer poll Model.instance because that property no longer exists
     * in the production model contract.
     */
    private fun ensureModelInitialized() {
        if (initialized.get()) {
            return
        }

        synchronized(initLock) {
            if (initialized.get()) {
                return
            }

            val initialAccelerator =
                defaultAccelerator()

            var candidate =
                createModel(initialAccelerator)

            Logx.i(
                TAG,
                "Initializing SLM accelerator=${initialAccelerator.label}"
            )

            try {
                initializeModelBlocking(candidate)
            } catch (error: Throwable) {

                if (initialAccelerator == Accelerator.CPU) {
                    throw error
                }

                Logx.w(
                    TAG,
                    "GPU init failed: ${error.message}; retrying with CPU"
                )

                /**
                 * LiteRtLM runtime state is keyed by model name/path. The CPU
                 * retry uses the same logical model path, so explicitly tear
                 * down any partially-created GPU state before retrying.
                 */
                runCatching {
                    forceCleanUpBlocking(
                        model = candidate,
                        timeoutMs = CLEANUP_TIMEOUT_MS,
                    )
                }.onFailure { cleanupError ->
                    Logx.w(
                        TAG,
                        "Cleanup before CPU fallback failed: ${cleanupError.message}"
                    )
                }

                candidate =
                    createModel(Accelerator.CPU)

                try {
                    initializeModelBlocking(candidate)
                } catch (cpuError: Throwable) {
                    cpuError.addSuppressed(error)
                    throw cpuError
                }
            }

            model = candidate
            initialized.set(true)

            Logx.i(
                TAG,
                "SLM initialized successfully: " +
                        "model=${model.name} " +
                        "accelerator=${model.getStringConfigValue(ConfigKey.ACCELERATOR, "")}"
            )
        }
    }

    private fun createModel(
        accelerator: Accelerator,
    ): Model {
        return Model(
            name = "gemma-3n-E4B-it",
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
    }

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

    /**
     * Resolve test accelerator from instrumentation arguments/environment.
     */
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

    private fun normalize(
        value: String,
    ): String =
        value
            .replace(
                Regex("[\\u2012-\\u2015]"),
                "-",
            )
            .replace(
                '\u00A0',
                ' ',
            )
            .trim()

    /**
     * Read the first non-blank streaming chunk.
     *
     * Using a non-blank predicate avoids treating a terminal empty callback as
     * evidence that generation produced useful output.
     */
    private suspend fun firstNonBlank(
        prompt: String,
    ): String {
        return repo
            .request(prompt)
            .first {
                it.isNotBlank()
            }
    }

    // =====================================================================
    // Tests
    // =====================================================================

    /**
     * Normal completion:
     * - receive non-empty streamed output
     * - Flow completes normally
     */
    @Test
    fun request_streams_and_closes_normally() =
        runBlocking {
            withTimeout(TEST_TIMEOUT_MS) {
                val output =
                    StringBuilder()

                repo
                    .request(
                        """Return only a very short JSON object like {"ok":true}."""
                    )
                    .collect { part ->
                        if (part.isNotEmpty()) {
                            output.append(part)
                        }
                    }

                val normalized =
                    normalize(output.toString())

                Logx.i(
                    TAG,
                    "STREAM DONE len=${normalized.length} " +
                            "head='${normalized.take(120)}'"
                )

                assertTrue(
                    "At least one non-empty chunk expected",
                    normalized.isNotBlank(),
                )
            }
        }

    /**
     * Consumer cancellation:
     *
     * first() cancels collection after the first non-blank item. We then issue
     * a second request immediately. The second request is the useful lifecycle
     * assertion: it can proceed only after repository cleanup/gate handling has
     * made the backend usable again.
     */
    @Test
    fun request_cancel_allows_next_request() =
        runBlocking {
            withTimeout(TEST_TIMEOUT_MS) {
                val first =
                    firstNonBlank(
                        "Return a short JSON object with one field."
                    )

                Logx.i(
                    TAG,
                    "FIRST REQUEST firstChunk.len=${first.length}"
                )

                /**
                 * Give awaitClose/cancel signaling a tiny scheduling window.
                 * Correctness does not depend on this delay because the next
                 * request still has to pass the repository's process-wide gate.
                 */
                delay(CANCEL_SETTLE_MS)

                val second =
                    firstNonBlank(
                        "Return the word READY and nothing else."
                    )

                Logx.i(
                    TAG,
                    "SECOND REQUEST firstChunk.len=${second.length}"
                )

                assertTrue(
                    "Second request should produce output after first() cancellation",
                    second.isNotBlank(),
                )
            }
        }

    /**
     * Empty prompt:
     *
     * Start collection, let inference enter the repository path, cancel the
     * collector, and verify that another request still succeeds.
     */
    @Test
    fun request_empty_prompt_does_not_poison_next_request() =
        runBlocking {
            withTimeout(TEST_TIMEOUT_MS) {
                val job =
                    launch {
                        repo
                            .request("")
                            .collect {
                                // Intentionally ignore content.
                            }
                    }

                delay(2_000L)

                job.cancelAndJoin()

                val next =
                    firstNonBlank(
                        "Return the word OK and nothing else."
                    )

                assertTrue(
                    "Backend should remain usable after empty-prompt cancellation",
                    next.isNotBlank(),
                )
            }
        }

    /**
     * Sequential use:
     *
     * R1 is cancelled by first(), then R2 starts. R2 must still return output.
     * This validates the intended no-concurrent-use behavior without relying on
     * the obsolete Model.instance / isBusy test hooks.
     */
    @Test
    fun sequential_two_requests_wait_and_succeed() =
        runBlocking {
            withTimeout(TEST_TIMEOUT_MS) {
                val r1StartedAt =
                    SystemClock.elapsedRealtime()

                val first1 =
                    firstNonBlank(
                        "R1: answer with ONE."
                    )

                val r1FirstAt =
                    SystemClock.elapsedRealtime()

                Logx.i(
                    TAG,
                    "R1 first.len=${first1.length} " +
                            "started@$r1StartedAt first@$r1FirstAt"
                )

                val r2StartedAt =
                    SystemClock.elapsedRealtime()

                val first2 =
                    firstNonBlank(
                        "R2: answer with TWO."
                    )

                val r2FirstAt =
                    SystemClock.elapsedRealtime()

                Logx.i(
                    TAG,
                    "R2 first.len=${first2.length} " +
                            "started@$r2StartedAt first@$r2FirstAt"
                )

                assertTrue(
                    "R1 should produce output",
                    first1.isNotBlank(),
                )

                assertTrue(
                    "R2 should produce output",
                    first2.isNotBlank(),
                )

                assertTrue(
                    "R2 first output cannot precede R2 start",
                    r2FirstAt >= r2StartedAt,
                )
            }
        }

    /**
     * Concurrent collector smoke test:
     *
     * Start two callers close together. LiteRtRepository owns the process-wide
     * serialization gate, so both should eventually get a non-blank result
     * without overlapping native inference.
     *
     * This is intentionally a smoke test, not a timing assertion.
     */
    @Test
    fun concurrent_callers_are_serialized_and_both_complete() =
        runBlocking {
            withTimeout(TEST_TIMEOUT_MS) {
                var firstA = ""
                var firstB = ""

                val jobA =
                    launch(Dispatchers.Default) {
                        firstA =
                            firstNonBlank(
                                "Caller A: answer A."
                            )
                    }

                /**
                 * Start B shortly after A so both callers contend for the
                 * repository's process-wide gate.
                 */
                delay(50L)

                val jobB =
                    launch(Dispatchers.Default) {
                        firstB =
                            firstNonBlank(
                                "Caller B: answer B."
                            )
                    }

                jobA.join()
                jobB.join()

                assertTrue(
                    "Caller A should receive output",
                    firstA.isNotBlank(),
                )

                assertTrue(
                    "Caller B should receive output",
                    firstB.isNotBlank(),
                )
            }
        }

    /**
     * Explicit conversation reset smoke test.
     *
     * This exercises the current API name directly instead of the removed
     * resetSession compatibility surface.
     */
    @Test
    fun reset_conversation_keeps_repository_usable() =
        runBlocking {
            withTimeout(TEST_TIMEOUT_MS) {
                val before =
                    firstNonBlank(
                        "Before reset: answer YES."
                    )

                assertTrue(
                    "Pre-reset request should produce output",
                    before.isNotBlank(),
                )

                /**
                 * firstNonBlank() cancels collection after one chunk.
                 * The next request will naturally wait for repository/native
                 * cleanup. After it has become usable, perform an explicit reset.
                 */
                val settle =
                    firstNonBlank(
                        "Prepare for reset: answer READY."
                    )

                assertTrue(
                    "Settle request should produce output",
                    settle.isNotBlank(),
                )

                /**
                 * SLM.resetConversation is fire-and-forget. Issue it only after
                 * repository usage has been serialized by the preceding calls.
                 */
                withContext(Dispatchers.Default) {
                    SLM.resetConversation(
                        model = model,
                        supportImage = false,
                        supportAudio = false,
                        systemMessage = null,
                        tools = emptyList(),
                    )
                }

                delay(CANCEL_SETTLE_MS)

                val after =
                    firstNonBlank(
                        "After reset: answer YES."
                    )

                assertTrue(
                    "Post-reset request should produce output",
                    after.isNotBlank(),
                )
            }
        }
}
