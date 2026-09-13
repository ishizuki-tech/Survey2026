/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AiViewModelSurveyBaseTest.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025-2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.vm

import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.Logx
import com.negi.survey.ModelAssetRule
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.slm.Accelerator
import com.negi.survey.slm.ConfigKey
import com.negi.survey.slm.LiteRtRepository
import com.negi.survey.slm.Model
import com.negi.survey.slm.SLM
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert
import org.junit.Before
import org.junit.Rule

/**
 * Base real-device instrumentation harness for [AiViewModel] + [LiteRtRepository].
 *
 * Responsibilities:
 * - Load and strictly validate SurveyConfig from assets.
 * - Merge SLM runtime configuration from:
 *   1. hard defaults
 *   2. config.slm
 *   3. assets/slm_config.yml
 *   4. test overrides
 *   5. instrumentation args / environment variables
 * - Initialize a shared LiteRT-LM model with GPU -> CPU fallback.
 * - Wire [LiteRtRepository] + [AiViewModel].
 * - Provide reusable helpers for:
 *   - model configuration
 *   - strong sample-answer generation
 *   - one-shot ViewModel evaluation
 *
 * Current API assumptions:
 * - Runtime Engine/Conversation ownership lives inside LiteRtLM, not Model.
 * - Initialization uses [SLM.initializeIfNeeded].
 * - Repository work uses [LiteRtRepository].
 * - Conversation reset uses [SLM.resetConversation].
 * - Streaming/request completion is NOT inferred from SLM.isBusy(model).
 *
 * Subclasses may override [configAssetName] and call [runOnce] or
 * [generateAnswerWithSlm].
 */
open class AiViewModelSurveyBase {

    @get:Rule
    val modelRule =
        ModelAssetRule()

    protected lateinit var appCtx: Context
    protected lateinit var repo: LiteRtRepository
    protected lateinit var vm: AiViewModel
    protected lateinit var config: SurveyConfig

    companion object {
        protected const val TAG =
            "AiViewModelSurvey"

        // ================================================================
        // Instrumentation arguments / timeouts
        // ================================================================

        private fun argString(
            key: String,
        ): String? {
            val args =
                InstrumentationRegistry.getArguments()

            return args
                .getString(key)
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?: System
                    .getenv(key)
                    ?.trim()
                    ?.takeIf {
                        it.isNotEmpty()
                    }
        }

        protected fun argInt(
            key: String,
        ): Int? =
            argString(key)
                ?.toIntOrNull()

        protected fun argLong(
            key: String,
        ): Long? =
            argString(key)
                ?.toLongOrNull()

        protected fun argBool(
            key: String,
        ): Boolean? =
            when (
                argString(key)
                    ?.lowercase(Locale.US)
                    ?.trim()
            ) {
                "1",
                "true",
                "yes",
                "y",
                "on",
                    -> true

                "0",
                "false",
                "no",
                "n",
                "off",
                    -> false

                else -> null
            }

        /** Model initialization timeout. */
        @JvmStatic
        protected val INIT_TIMEOUT_MS: Long by lazy {
            argLong("INIT_TIMEOUT_MS")
                ?.coerceAtLeast(1_000L)
                ?: 60_000L
        }

        /**
         * Legacy seconds view retained for test source compatibility.
         *
         * New code should prefer [INIT_TIMEOUT_MS].
         */
        @JvmStatic
        protected val INIT_TIMEOUT_SEC: Long
            get() =
                (INIT_TIMEOUT_MS + 999L) /
                        1_000L

        /** Default AiViewModel request timeout. */
        @JvmStatic
        protected val VM_TIMEOUT_MS: Long by lazy {
            argLong("VM_TIMEOUT_MS")
                ?.coerceAtLeast(1L)
                ?: 60_000L
        }

        /**
         * Legacy seconds view retained for test source compatibility.
         */
        @JvmStatic
        protected val VM_TIMEOUT_SEC: Long
            get() =
                (VM_TIMEOUT_MS + 999L) /
                        1_000L

        /** First visible model-output signal timeout. */
        @JvmStatic
        protected val FIRST_CHUNK_TIMEOUT_MS: Long by lazy {
            argLong("FIRST_CHUNK_TIMEOUT_MS")
                ?.coerceAtLeast(1L)
                ?: 15_000L
        }

        /** Normal request completion timeout. */
        @JvmStatic
        protected val COMPLETE_TIMEOUT_MS: Long by lazy {
            argLong("COMPLETE_TIMEOUT_MS")
                ?.coerceAtLeast(1L)
                ?: 60_000L
        }

        /** Outer per-prompt safety guard. */
        @JvmStatic
        protected val PER_PROMPT_GUARD_MS: Long by lazy {
            argLong("PER_PROMPT_GUARD_MS")
                ?.coerceAtLeast(1L)
                ?: (
                        maxOf(
                            VM_TIMEOUT_MS,
                            COMPLETE_TIMEOUT_MS,
                        ) + 15_000L
                        )
        }

        /**
         * Retained for compatibility with older subclasses.
         *
         * Current tests do not use SLM.isBusy() as a lifecycle oracle.
         */
        @JvmStatic
        protected val BETWEEN_PROMPTS_IDLE_WAIT_MS: Long by lazy {
            argLong("IDLE_WAIT_MS")
                ?.coerceAtLeast(0L)
                ?: 2_000L
        }

        /** Optional cooldown between expensive real-device prompts. */
        @JvmStatic
        protected val BETWEEN_PROMPTS_COOLDOWN_MS: Long by lazy {
            argLong("COOLDOWN_MS")
                ?.coerceAtLeast(0L)
                ?: 300L
        }

        @JvmStatic
        protected val MIN_STREAM_CHARS: Int by lazy {
            argInt("MIN_STREAM_CHARS")
                ?.coerceAtLeast(0)
                ?: 1
        }

        @JvmStatic
        protected val MIN_FINAL_CHARS: Int by lazy {
            argInt("MIN_FINAL_CHARS")
                ?.coerceAtLeast(0)
                ?: 1
        }

        @JvmStatic
        protected val PROMPT_LIMIT: Int? by lazy {
            argInt("PROMPT_LIMIT")
        }

        @JvmStatic
        protected val TEST_BUDGET_MS: Long by lazy {
            argLong("TEST_BUDGET_MS")
                ?.coerceAtLeast(0L)
                ?: Long.MAX_VALUE
        }

        @JvmStatic
        protected val VERBOSE: Boolean by lazy {
            argBool("VERBOSE")
                ?: true
        }

        /**
         * Full prompt logging is opt-in.
         *
         * Instrumentation logs may leave the device or CI environment, so the
         * safer default is false.
         */
        @JvmStatic
        protected val LOG_FULL_PROMPT: Boolean by lazy {
            argBool("LOG_FULL_PROMPT")
                ?: false
        }

        // ================================================================
        // Shared model lifecycle
        // ================================================================

        @JvmStatic
        protected lateinit var model: Model

        /**
         * Set true only AFTER successful initialization.
         *
         * Do not set this before initialization starts; a failed first attempt
         * must not make later tests believe the runtime is ready.
         */
        @JvmStatic
        protected val initialized =
            AtomicBoolean(false)

        private val initLock =
            Any()

        /**
         * Key describing the model/config request that produced [model].
         *
         * The actual runtime may use CPU after a GPU fallback; this key still
         * records the original requested configuration so subsequent tests with
         * the same setup reuse the successful fallback instead of retrying GPU
         * on every @Before.
         */
        @Volatile
        private var initializedRequestKey: String? =
            null

        @AfterClass
        @JvmStatic
        fun afterClass() {
            if (!initialized.get()) {
                return
            }

            runCatching {
                forceCleanUpBlocking(
                    targetModel = model,
                    timeoutMs = 20_000L,
                )
            }.onFailure { error ->
                Logx.w(
                    TAG,
                    "SLM force cleanup failed in @AfterClass: ${error.message}"
                )
            }

            initialized.set(false)
            initializedRequestKey = null
        }

        /**
         * Bounded bridge around callback-style force cleanup.
         */
        private fun forceCleanUpBlocking(
            targetModel: Model,
            timeoutMs: Long,
        ): Boolean {
            val latch =
                CountDownLatch(1)

            SLM.forceCleanUp(
                model = targetModel,
                onDone = {
                    latch.countDown()
                },
            )

            return latch.await(
                timeoutMs.coerceAtLeast(1L),
                TimeUnit.MILLISECONDS,
            )
        }
    }

    // =====================================================================
    // Public hooks
    // =====================================================================

    /**
     * Asset name of the survey config used by this test suite.
     */
    protected open fun configAssetName(): String =
        "survey_config1.yaml"

    /**
     * Optional flat SLM config asset.
     *
     * Return null to disable the extra overlay.
     */
    protected open fun slmConfigAssetName(): String? =
        "slm_config.yml"

    /**
     * Per-suite runtime overrides applied after config/slm asset values and
     * before instrumentation args/environment.
     */
    protected open fun slmTestOverrides(): TestOverrides =
        TestOverrides()

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @Before
    open fun setUp() {
        appCtx =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext
                .applicationContext

        SLM.setApplicationContext(
            appCtx
        )

        // 1. Load + validate survey configuration.
        config =
            try {
                SurveyConfigLoader
                    .fromAssetsStrictValidated(
                        appCtx,
                        configAssetName(),
                    )
            } catch (error: Throwable) {
                throw AssertionError(
                    "Failed to load/validate " +
                            "${configAssetName()}: ${error.message}",
                    error,
                )
            }

        // 2. Merge runtime SLM configuration.
        val mergedSlm =
            buildSlmRuntimeConfig(
                context = appCtx,
                configSlm = config.slm,
                assetYamlFile =
                    slmConfigAssetName(),
                hardDefaults =
                    SlmDefaults(
                        accelerator =
                            Accelerator.GPU.label,
                        maxTokens = 512,
                        topK = 1,
                        topP = 0.0,
                        temperature = 0.0,
                    ),
                testOverrides =
                    slmTestOverrides(),
            )

        if (VERBOSE) {
            Logx.block(
                TAG,
                "SLM MERGED CONFIG (pre-Model)",
                buildString {
                    mergedSlm.forEach { (key, value) ->
                        appendLine(
                            "${key.name}: $value"
                        )
                    }
                }.trimEnd(),
            )
        }

        // 3. Initialize or reuse the shared runtime.
        ensureSharedModelInitialized(
            mergedSlm
        )

        if (VERBOSE) {
            logModelConfig(
                model
            )
        }

        // 4. Wire repository + ViewModel.
        repo =
            LiteRtRepository(
                model = model,
                config = config,
                appContext = appCtx,
            )

        vm =
            AiViewModel(
                repo = repo,
                defaultTimeoutMs =
                    VM_TIMEOUT_MS,
            )

        /**
         * Do not call vm.cancel() here. cancel() intentionally writes
         * error="cancelled". resetStates gives every test a clean transient
         * state without fabricating a cancellation result.
         */
        vm.resetStates(
            keepError = false
        )
    }

    @After
    open fun tearDown() {
        if (::vm.isInitialized) {
            runCatching {
                vm.resetStates(
                    keepError = false
                )
            }.onFailure { error ->
                Logx.w(
                    TAG,
                    "tearDown resetStates failed: ${error.message}"
                )
            }
        }

        if (initialized.get()) {
            /**
             * Defensive best-effort abort only.
             *
             * Normal repository runs should already terminate/reset at their
             * native safe point. This protects the next test only when the
             * current test aborted midway through an assertion/timeout.
             */
            runCatching {
                SLM.cancel(
                    model
                )
            }
        }
    }

    // =====================================================================
    // Shared model initialization
    // =====================================================================

    /**
     * Initialize the static/shared Model safely.
     *
     * Key improvements over the legacy harness:
     * - [initialized] is set only after success.
     * - No Model.instance polling.
     * - GPU fallback tears down partial runtime state before CPU retry.
     * - A different model/config request reinitializes instead of silently
     *   reusing stale state from another subclass.
     */
    private fun ensureSharedModelInitialized(
        mergedSlm: Map<ConfigKey, Any>,
    ) {
        val internalModelPath =
            modelRule
                .internalModel
                .absolutePath

        val requestedConfig =
            mergedSlm
                .toMutableMap()
                .apply {
                    normalizeNumberTypesInPlace(
                        this
                    )
                    normalizeRangesInPlace(
                        this
                    )
                }

        val requestedAccelerator =
            parseAccelerator(
                requestedConfig[
                    ConfigKey.ACCELERATOR
                ]
            )

        requestedConfig[
            ConfigKey.ACCELERATOR
        ] =
            requestedAccelerator.label

        val requestKey =
            modelRequestKey(
                modelPath =
                    internalModelPath,
                config =
                    requestedConfig,
            )

        if (
            initialized.get() &&
            initializedRequestKey == requestKey
        ) {
            return
        }

        synchronized(initLock) {
            if (
                initialized.get() &&
                initializedRequestKey == requestKey
            ) {
                return
            }

            /**
             * If another subclass/config initialized a different runtime in the
             * same instrumentation process, tear it down before replacing it.
             */
            if (
                initialized.get()
            ) {
                runCatching {
                    forceCleanUpBlocking(
                        targetModel = model,
                        timeoutMs = 20_000L,
                    )
                }.onFailure { error ->
                    Logx.w(
                        TAG,
                        "Cleanup before model/config switch failed: " +
                                "${error.message}"
                    )
                }

                initialized.set(false)
                initializedRequestKey = null
            }

            var candidate =
                Model(
                    name = "gemma-3n-E4B-it",
                    taskPath =
                        internalModelPath,
                    config =
                        requestedConfig
                            .toMap(),
                )

            Logx.w(
                TAG,
                "Initializing SLM " +
                        "accelerator=${requestedAccelerator.label}"
            )

            try {
                initializeModelBlocking(
                    candidate
                )
            } catch (firstError: Throwable) {
                if (
                    requestedAccelerator ==
                    Accelerator.CPU
                ) {
                    throw firstError
                }

                Logx.w(
                    TAG,
                    "GPU init failed: ${firstError.message} -> " +
                            "fallback to CPU"
                )

                /**
                 * Runtime identity is model/path based, so clean up any partial
                 * GPU runtime before retrying CPU for the same logical model.
                 */
                runCatching {
                    forceCleanUpBlocking(
                        targetModel = candidate,
                        timeoutMs = 20_000L,
                    )
                }.onFailure { cleanupError ->
                    Logx.w(
                        TAG,
                        "Cleanup before CPU fallback failed: " +
                                "${cleanupError.message}"
                    )
                }

                val cpuConfig =
                    requestedConfig
                        .toMutableMap()
                        .apply {
                            this[
                                ConfigKey.ACCELERATOR
                            ] =
                                Accelerator.CPU.label
                        }

                candidate =
                    Model(
                        name =
                            candidate.name,
                        taskPath =
                            internalModelPath,
                        config =
                            cpuConfig.toMap(),
                    )

                try {
                    initializeModelBlocking(
                        candidate
                    )
                } catch (cpuError: Throwable) {
                    cpuError.addSuppressed(
                        firstError
                    )
                    throw cpuError
                }
            }

            model = candidate
            initializedRequestKey =
                requestKey
            initialized.set(true)

            Logx.w(
                TAG,
                "SLM initialization complete: " +
                        "accelerator=" +
                        model.getStringConfigValue(
                            ConfigKey.ACCELERATOR,
                            "<unset>",
                        )
            )
        }
    }

    private fun initializeModelBlocking(
        targetModel: Model,
    ) {
        runBlocking {
            withTimeout(
                INIT_TIMEOUT_MS
            ) {
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
     * Compatibility helper retained for older subclasses.
     *
     * Returns null on success, otherwise a concise error string.
     */
    protected fun initialize(
        timeoutSec: Long,
    ): String? {
        val timeoutMs =
            timeoutSec
                .coerceAtLeast(1L)
                .times(1_000L)

        return runCatching {
            runBlocking {
                withTimeout(
                    timeoutMs
                ) {
                    SLM.initializeIfNeeded(
                        context = appCtx,
                        model = model,
                        supportImage = false,
                        supportAudio = false,
                        systemMessage = null,
                        tools = emptyList(),
                    )
                }
            }
        }.exceptionOrNull()
            ?.let { error ->
                error.message
                    ?: error.javaClass.simpleName
            }
    }

    private fun modelRequestKey(
        modelPath: String,
        config: Map<ConfigKey, Any>,
    ): String =
        buildString {
            append(
                modelPath
            )

            ConfigKey
                .entries
                .forEach { key ->
                    append('|')
                    append(
                        key.name
                    )
                    append('=')
                    append(
                        config[key]
                    )
                }
        }

    // =====================================================================
    // Basic helpers
    // =====================================================================

    protected fun defaultAccel(): Accelerator {
        val configured =
            (
                    InstrumentationRegistry
                        .getArguments()
                        .getString("ACCELERATOR")
                        ?: System.getenv(
                            "ACCELERATOR"
                        )
                    )
                ?.trim()
                ?.uppercase(Locale.US)

        return if (
            configured ==
            Accelerator.CPU.label
        ) {
            Accelerator.CPU
        } else {
            Accelerator.GPU
        }
    }

    protected fun normalizeForModel(
        source: String,
    ): String =
        source
            .replace(
                Regex(
                    "[\\u2012-\\u2015]"
                ),
                "-",
            )
            .replace(
                '\u00A0',
                ' ',
            )
            .trim()

    /**
     * Fill {{QUESTION}} / {{ANSWER}} placeholders.
     *
     * If a placeholder was present but the resulting template has no explicit
     * Question:/Answer: label, append the corresponding labeled line.
     */
    protected fun fillPlaceholders(
        tpl: String,
        q: String,
        a: String,
    ): String {
        val hadQuestionPlaceholder =
            "{{QUESTION}}" in tpl

        val hadAnswerPlaceholder =
            "{{ANSWER}}" in tpl

        val base =
            tpl
                .replace(
                    "{{QUESTION}}",
                    q,
                )
                .replace(
                    "{{ANSWER}}",
                    a,
                )
                .trim()

        return buildString {
            if (base.isNotEmpty()) {
                append(
                    base
                )
            }

            if (
                hadQuestionPlaceholder &&
                !base.contains(
                    "Question:",
                    ignoreCase = true,
                )
            ) {
                if (isNotEmpty()) {
                    appendLine()
                }

                append(
                    "Question: $q"
                )
            }

            if (
                hadAnswerPlaceholder &&
                !base.contains(
                    "Answer:",
                    ignoreCase = true,
                )
            ) {
                if (isNotEmpty()) {
                    appendLine()
                }

                append(
                    "Answer: $a"
                )
            }
        }.trim()
    }

    /**
     * Generic monotonic polling helper retained for subclasses.
     */
    protected fun waitUntil(
        timeoutMs: Long,
        cond: () -> Boolean,
    ): Boolean {
        val deadline =
            SystemClock.elapsedRealtime() +
                    timeoutMs.coerceAtLeast(0L)

        while (
            SystemClock.elapsedRealtime() <
            deadline
        ) {
            if (cond()) {
                return true
            }

            SystemClock.sleep(
                15L
            )
        }

        return cond()
    }

    // =====================================================================
    // Strong-answer prompt helpers
    // =====================================================================

    protected data class StrongAnswerStyle(
        val persona: String =
            "Kenyan smallholder maize farmer",
        val wordsMin: Int = 25,
        val wordsMax: Int = 35,
        val requireNumbers: Boolean = true,
        val requireUnits: Boolean = true,
        val mentionSeasonOrMonthIfImplied: Boolean = true,
        val forbidHedging: Boolean = true,
        val oneSentence: Boolean = true,
        val plainAscii: Boolean = true,
    )

    protected fun buildStrongAnswerPrompt(
        question: String,
        style: StrongAnswerStyle =
            StrongAnswerStyle(),
    ): String {
        val normalizedMin =
            style.wordsMin
                .coerceAtLeast(1)

        val normalizedMax =
            style.wordsMax
                .coerceAtLeast(
                    normalizedMin
                )

        val rules =
            mutableListOf<String>()

        if (style.oneSentence) {
            rules +=
                "one sentence"
        }

        rules +=
            "between $normalizedMin-$normalizedMax words"
        rules +=
            "plain text only"
        rules +=
            "single line only"
        rules +=
            "no bullet points"
        rules +=
            "no quotes"
        rules +=
            "no follow-up questions"
        rules +=
            "no preamble"

        if (style.plainAscii) {
            rules +=
                "ASCII punctuation only"
        }

        if (style.requireNumbers) {
            rules +=
                "include at least one specific number or range"
        }

        if (style.requireUnits) {
            rules +=
                "use clear units when applicable"
        }

        if (
            style
                .mentionSeasonOrMonthIfImplied
        ) {
            rules +=
                "mention season or month if relevant"
        }

        if (style.forbidHedging) {
            rules +=
                "avoid hedging words"
        }

        return buildString {
            appendLine(
                "ROLE: ${style.persona}."
            )
            appendLine(
                "TASK: Answer the question below as a definitive, " +
                        "exemplary response from your own perspective."
            )
            appendLine(
                "OUTPUT RULES: " +
                        rules.joinToString("; ") +
                        "."
            )
            appendLine(
                "TONE: practical, concise, first-person farmer voice " +
                        "(I/we), field-tested advice."
            )
            appendLine(
                "CONSTRAIN: Do not restate the question. " +
                        "Do not add explanations."
            )
            appendLine()
            appendLine(
                "Question: " +
                        question
                            .replace(
                                '\n',
                                ' ',
                            )
                            .trim()
            )
            append(
                "Answer:"
            )
        }
            .trim()
            .replace(
                Regex("\\s+"),
                " ",
            )
    }

    /**
     * Generate one synthetic strong answer through the current suspend SLM API.
     *
     * Lifecycle:
     * - Start SLM.generateText in a child coroutine.
     * - Require either first non-blank partial OR full completion within
     *   [firstChunkTimeoutMs].
     * - Require full completion within [completeTimeoutMs].
     * - Cancel best-effort on failure.
     * - Reset the conversation after the generation has unwound.
     *
     * [quietMs] is retained as a post-reset scheduling grace.
     * [enforceWordCap] performs a diagnostic warning rather than a hard failure
     * because real-device generative tests should not be flaky solely due to a
     * small word-count miss.
     */
    protected suspend fun generateAnswerWithSlm(
        model: Model,
        question: String,
        firstChunkTimeoutMs: Long =
            FIRST_CHUNK_TIMEOUT_MS,
        completeTimeoutMs: Long =
            COMPLETE_TIMEOUT_MS,
        quietMs: Long = 250L,
        enforceWordCap: Boolean = true,
    ): String =
        coroutineScope {
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
                    "SLM PROMPT",
                    oneLine(
                        prompt
                    ),
                )
            }

            val firstNonBlank =
                CompletableDeferred<Unit>()

            val generation =
                async {
                    SLM.generateText(
                        model = model,
                        input = prompt,
                        images = emptyList(),
                        audioClips = emptyList(),
                        onPartial = { partial ->
                            if (
                                !firstNonBlank
                                    .isCompleted &&
                                partial.any {
                                    !it.isWhitespace()
                                }
                            ) {
                                firstNonBlank
                                    .complete(Unit)
                            }
                        },
                    )
                }

            try {
                withTimeout(
                    firstChunkTimeoutMs
                        .coerceAtLeast(1L)
                ) {
                    select<Unit> {
                        firstNonBlank
                            .onAwait {
                                Unit
                            }

                        generation
                            .onAwait {
                                Unit
                            }
                    }
                }

                val output =
                    withTimeout(
                        completeTimeoutMs
                            .coerceAtLeast(1L)
                    ) {
                        generation.await()
                    }

                val normalized =
                    normalizeForModel(
                        output
                    )

                require(
                    normalized.isNotBlank()
                ) {
                    "empty answer from SLM for " +
                            "q='${oneLine(question).take(80)}'"
                }

                if (enforceWordCap) {
                    val style =
                        StrongAnswerStyle()

                    val wordCount =
                        normalized
                            .split(
                                Regex("\\s+")
                            )
                            .count {
                                it.isNotBlank()
                            }

                    if (
                        wordCount !in
                        style.wordsMin..
                        style.wordsMax
                    ) {
                        Logx.w(
                            TAG,
                            "Strong-answer word count outside requested " +
                                    "range: $wordCount not in " +
                                    "${style.wordsMin}..${style.wordsMax}"
                        )
                    }
                }

                if (VERBOSE) {
                    Logx.kv(
                        TAG,
                        "SLM ANSWER",
                        mapOf(
                            "len" to
                                    normalized.length
                                        .toString(),
                            "preview" to
                                    oneLine(
                                        normalized
                                    ).take(120),
                        ),
                    )
                }

                normalized
            } catch (error: Throwable) {
                if (generation.isActive) {
                    runCatching {
                        SLM.cancel(
                            model
                        )
                    }
                }

                throw error
            } finally {
                if (generation.isActive) {
                    generation.cancel()

                    runCatching {
                        SLM.cancel(
                            model
                        )
                    }
                }

                runCatching {
                    generation.join()
                }

                runCatching {
                    SLM.resetConversation(
                        model = model,
                        supportImage = false,
                        supportAudio = false,
                        systemMessage = null,
                        tools = emptyList(),
                    )
                }.onFailure { resetError ->
                    Logx.w(
                        TAG,
                        "resetConversation after sample generation failed: " +
                                "${resetError.message}"
                    )
                }

                val graceMs =
                    quietMs
                        .coerceIn(
                            0L,
                            1_000L,
                        )

                if (graceMs > 0L) {
                    delay(
                        graceMs
                    )
                }
            }
        }

    protected fun oneLine(
        source: String?,
    ): String =
        source
            ?.replace(
                "\r",
                " ",
            )
            ?.replace(
                "\n",
                " ",
            )
            ?.trim()
            .orEmpty()

    // =====================================================================
    // SLM config merge
    // =====================================================================

    protected data class SlmDefaults(
        val accelerator: String =
            Accelerator.GPU.label,
        val maxTokens: Int = 512,
        val topK: Int = 1,
        val topP: Double = 0.0,
        val temperature: Double = 0.0,
    )

    protected data class TestOverrides(
        val accelerator: String? = null,
        val maxTokens: Int? = null,
        val topK: Int? = null,
        val topP: Double? = null,
        val temperature: Double? = null,
    )

    /**
     * Build the runtime Model config.
     *
     * Priority from lowest to highest:
     * 1. hard defaults
     * 2. SurveyConfig.slm
     * 3. optional flat YAML asset
     * 4. test overrides
     * 5. instrumentation args / environment
     */
    protected fun buildSlmRuntimeConfig(
        context: Context,
        configSlm: SurveyConfig.SlmMeta?,
        assetYamlFile: String?,
        hardDefaults: SlmDefaults,
        testOverrides: TestOverrides,
    ): Map<ConfigKey, Any> {
        val config =
            mutableMapOf<ConfigKey, Any>(
                ConfigKey.ACCELERATOR to
                        hardDefaults.accelerator,
                ConfigKey.MAX_TOKENS to
                        hardDefaults.maxTokens,
                ConfigKey.TOP_K to
                        hardDefaults.topK,
                ConfigKey.TOP_P to
                        hardDefaults.topP,
                ConfigKey.TEMPERATURE to
                        hardDefaults.temperature,
            )

        fun putIfNotNull(
            key: ConfigKey,
            value: Any?,
        ) {
            if (value != null) {
                config[key] =
                    value
            }
        }

        // 1. SurveyConfig.slm
        configSlm
            ?.let { slm ->
                putIfNotNull(
                    ConfigKey.ACCELERATOR,
                    slm.accelerator
                        ?.trim()
                        ?.takeIf {
                            it.isNotEmpty()
                        },
                )
                putIfNotNull(
                    ConfigKey.MAX_TOKENS,
                    slm.maxTokens,
                )
                putIfNotNull(
                    ConfigKey.TOP_K,
                    slm.topK,
                )
                putIfNotNull(
                    ConfigKey.TOP_P,
                    slm.topP,
                )
                putIfNotNull(
                    ConfigKey.TEMPERATURE,
                    slm.temperature,
                )
            }

        // 2. Optional flat YAML asset.
        assetYamlFile
            ?.trim()
            ?.takeIf {
                it.isNotEmpty()
            }
            ?.let { fileName ->
                runCatching {
                    val yamlText =
                        context.assets
                            .open(fileName)
                            .bufferedReader()
                            .use {
                                it.readText()
                            }

                    parseSimpleYamlSlmMap(
                        yamlText
                    )
                }.onSuccess { parsed ->
                    parsed[
                        "accelerator"
                    ]?.let {
                        config[
                            ConfigKey.ACCELERATOR
                        ] =
                            it
                    }

                    (
                            parsed[
                                "max_tokens"
                            ] as? Number
                            )
                        ?.toInt()
                        ?.let {
                            config[
                                ConfigKey.MAX_TOKENS
                            ] =
                                it
                        }

                    (
                            parsed[
                                "top_k"
                            ] as? Number
                            )
                        ?.toInt()
                        ?.let {
                            config[
                                ConfigKey.TOP_K
                            ] =
                                it
                        }

                    (
                            parsed[
                                "top_p"
                            ] as? Number
                            )
                        ?.toDouble()
                        ?.let {
                            config[
                                ConfigKey.TOP_P
                            ] =
                                it
                        }

                    (
                            parsed[
                                "temperature"
                            ] as? Number
                            )
                        ?.toDouble()
                        ?.let {
                            config[
                                ConfigKey.TEMPERATURE
                            ] =
                                it
                        }

                    if (
                        VERBOSE &&
                        parsed.isNotEmpty()
                    ) {
                        Logx.w(
                            TAG,
                            "assets/$fileName applied keys: " +
                                    parsed.keys.joinToString()
                        )
                    }
                }.onFailure { error ->
                    if (VERBOSE) {
                        Logx.w(
                            TAG,
                            "assets/$fileName not applied: " +
                                    "${error.message}"
                        )
                    }
                }
            }

        // 3. Test overrides.
        putIfNotNull(
            ConfigKey.ACCELERATOR,
            testOverrides.accelerator,
        )
        putIfNotNull(
            ConfigKey.MAX_TOKENS,
            testOverrides.maxTokens,
        )
        putIfNotNull(
            ConfigKey.TOP_K,
            testOverrides.topK,
        )
        putIfNotNull(
            ConfigKey.TOP_P,
            testOverrides.topP,
        )
        putIfNotNull(
            ConfigKey.TEMPERATURE,
            testOverrides.temperature,
        )

        // 4. Instrumentation args / environment.
        argString(
            "ACCELERATOR"
        )
            ?.let {
                config[
                    ConfigKey.ACCELERATOR
                ] =
                    it
            }

        argString(
            "MAX_TOKENS"
        )
            ?.toIntOrNull()
            ?.let {
                config[
                    ConfigKey.MAX_TOKENS
                ] =
                    it
            }

        argString(
            "TOP_K"
        )
            ?.toIntOrNull()
            ?.let {
                config[
                    ConfigKey.TOP_K
                ] =
                    it
            }

        argString(
            "TOP_P"
        )
            ?.toDoubleOrNull()
            ?.let {
                config[
                    ConfigKey.TOP_P
                ] =
                    it
            }

        argString(
            "TEMPERATURE"
        )
            ?.toDoubleOrNull()
            ?.let {
                config[
                    ConfigKey.TEMPERATURE
                ] =
                    it
            }

        normalizeNumberTypesInPlace(
            config
        )
        normalizeRangesInPlace(
            config
        )

        return config.toMap()
    }

    /**
     * Very small flat-YAML parser for test-only SLM overrides.
     *
     * Recognized keys:
     * - accelerator
     * - max_tokens
     * - top_k
     * - top_p
     * - temperature
     */
    protected fun parseSimpleYamlSlmMap(
        yaml: String,
    ): Map<String, Any> {
        val map =
            linkedMapOf<String, Any>()

        yaml.lineSequence()
            .map {
                it.trim()
            }
            .filter {
                it.isNotEmpty() &&
                        !it.startsWith("#")
            }
            .forEach { line ->
                val separator =
                    line.indexOf(':')

                if (separator <= 0) {
                    return@forEach
                }

                val key =
                    line
                        .substring(
                            0,
                            separator,
                        )
                        .trim()

                var raw =
                    line
                        .substring(
                            separator + 1
                        )
                        .trim()

                /**
                 * Values in this test file are scalar. Strip a trailing comment
                 * after parsing off simple wrapping quotes.
                 */
                if (
                    raw.startsWith("\"") &&
                    raw.endsWith("\"") &&
                    raw.length >= 2
                ) {
                    raw =
                        raw.substring(
                            1,
                            raw.length - 1,
                        )
                } else {
                    raw =
                        raw
                            .substringBefore("#")
                            .trim()
                            .trim(
                                '"',
                                '\'',
                            )
                }

                when (key) {
                    "accelerator" -> {
                        if (raw.isNotEmpty()) {
                            map[key] =
                                raw
                        }
                    }

                    "max_tokens",
                    "top_k",
                        -> {
                        raw.toIntOrNull()
                            ?.let {
                                map[key] =
                                    it
                            }
                    }

                    "top_p",
                    "temperature",
                        -> {
                        raw.toDoubleOrNull()
                            ?.let {
                                map[key] =
                                    it
                            }
                    }
                }
            }

        return map
    }

    /**
     * Normalize number/string values into stable primitives.
     */
    protected fun normalizeNumberTypesInPlace(
        config: MutableMap<ConfigKey, Any>,
    ) {
        config[
            ConfigKey.MAX_TOKENS
        ] =
            numericInt(
                config[
                    ConfigKey.MAX_TOKENS
                ],
                fallback = 512,
            )

        config[
            ConfigKey.TOP_K
        ] =
            numericInt(
                config[
                    ConfigKey.TOP_K
                ],
                fallback = 1,
            )

        config[
            ConfigKey.TOP_P
        ] =
            numericDouble(
                config[
                    ConfigKey.TOP_P
                ],
                fallback = 0.0,
            )

        config[
            ConfigKey.TEMPERATURE
        ] =
            numericDouble(
                config[
                    ConfigKey.TEMPERATURE
                ],
                fallback = 0.0,
            )

        config[
            ConfigKey.ACCELERATOR
        ] =
            parseAccelerator(
                config[
                    ConfigKey.ACCELERATOR
                ]
            ).label
    }

    /**
     * Align test config with the current app wrapper's accepted ranges.
     *
     * 4096 is a project-wrapper cap, not an upstream LiteRT-LM fixed limit.
     */
    private fun normalizeRangesInPlace(
        config: MutableMap<ConfigKey, Any>,
    ) {
        config[
            ConfigKey.MAX_TOKENS
        ] =
            (
                    config[
                        ConfigKey.MAX_TOKENS
                    ] as Number
                    )
                .toInt()
                .coerceIn(
                    1,
                    4_096,
                )

        config[
            ConfigKey.TOP_K
        ] =
            (
                    config[
                        ConfigKey.TOP_K
                    ] as Number
                    )
                .toInt()
                .coerceAtLeast(1)

        val topP =
            (
                    config[
                        ConfigKey.TOP_P
                    ] as Number
                    )
                .toDouble()

        config[
            ConfigKey.TOP_P
        ] =
            if (topP.isFinite()) {
                topP.coerceIn(
                    0.0,
                    1.0,
                )
            } else {
                0.0
            }

        val temperature =
            (
                    config[
                        ConfigKey.TEMPERATURE
                    ] as Number
                    )
                .toDouble()

        config[
            ConfigKey.TEMPERATURE
        ] =
            if (
                temperature.isFinite()
            ) {
                temperature.coerceIn(
                    0.0,
                    2.0,
                )
            } else {
                0.0
            }
    }

    private fun numericInt(
        value: Any?,
        fallback: Int,
    ): Int =
        when (value) {
            is Number ->
                value.toInt()

            is String ->
                value
                    .trim()
                    .toIntOrNull()
                    ?: fallback

            else ->
                fallback
        }

    private fun numericDouble(
        value: Any?,
        fallback: Double,
    ): Double =
        when (value) {
            is Number ->
                value.toDouble()

            is String ->
                value
                    .trim()
                    .toDoubleOrNull()
                    ?: fallback

            else ->
                fallback
        }

    private fun parseAccelerator(
        value: Any?,
    ): Accelerator {
        val normalized =
            when (value) {
                is Accelerator ->
                    value.label

                else ->
                    value
                        ?.toString()
                        .orEmpty()
            }
                .trim()
                .uppercase(Locale.US)

        return when (normalized) {
            Accelerator.CPU.label ->
                Accelerator.CPU

            Accelerator.GPU.label ->
                Accelerator.GPU

            else ->
                Accelerator.GPU
        }
    }

    // =====================================================================
    // Diagnostics
    // =====================================================================

    protected fun dumpAllFollowups() {
        val followups =
            vm.followups.value

        if (followups.isEmpty()) {
            if (VERBOSE) {
                Logx.block(
                    TAG,
                    "FOLLOWUPS (0)",
                    "<none>",
                )
            }

            return
        }

        val body =
            buildString {
                followups
                    .forEachIndexed { index, value ->
                        append(
                            index + 1
                        )
                        append(
                            ". "
                        )
                        append(
                            oneLine(
                                value
                            )
                        )
                        appendLine()
                    }
            }.trimEnd()

        if (VERBOSE) {
            Logx.block(
                TAG,
                "FOLLOWUPS (${followups.size})",
                body,
            )
        }
    }

    protected fun logModelConfig(
        model: Model,
    ) {
        Logx.kv(
            TAG,
            "SLM MODEL CONFIG",
            mapOf(
                "name" to
                        model.name,
                "taskPath" to
                        model.taskPath,
                "ACCELERATOR" to
                        model.getStringConfigValue(
                            ConfigKey.ACCELERATOR,
                            "<unset>",
                        ),
                "MAX_TOKENS" to
                        model.getIntConfigValue(
                            ConfigKey.MAX_TOKENS,
                            -1,
                        ).toString(),
                "TOP_K" to
                        model.getIntConfigValue(
                            ConfigKey.TOP_K,
                            -1,
                        ).toString(),
                "TOP_P" to
                        model.getFloatConfigValue(
                            ConfigKey.TOP_P,
                            Float.NaN,
                        ).toString(),
                "TEMPERATURE" to
                        model.getFloatConfigValue(
                            ConfigKey.TEMPERATURE,
                            Float.NaN,
                        ).toString(),
            ),
        )
    }

    // =====================================================================
    // AiViewModel one-shot helper
    // =====================================================================

    /**
     * Run one user-level evaluation through [AiViewModel].
     *
     * Unlike the legacy helper:
     * - The returned Job is the authoritative coroutine completion signal.
     * - SLM.isBusy(model) is not consulted.
     * - Success does not call vm.cancel(), so terminal result/error state is
     *   preserved for assertions in the subclass.
     * - On failure, active work is stopped via resetStates(keepError=true),
     *   which avoids replacing a useful error with "cancelled".
     */
    protected suspend fun runOnce(
        prompt: String,
        firstChunkTimeoutMs: Long =
            FIRST_CHUNK_TIMEOUT_MS,
        completeTimeoutMs: Long =
            COMPLETE_TIMEOUT_MS,
        minStreamChars: Int =
            MIN_STREAM_CHARS,
        tailGraceMs: Long = 300L,
        minFinalChars: Int =
            MIN_FINAL_CHARS,
    ): String {
        require(
            prompt.isNotBlank()
        ) {
            "runOnce requires a non-blank prompt"
        }

        vm.resetStates(
            keepError = false
        )

        val job =
            vm.evaluateAsync(
                prompt = prompt,
                timeoutMs =
                    completeTimeoutMs
                        .coerceAtLeast(1L),
            )

        try {
            /**
             * First-signal guard.
             *
             * Polling current StateFlow values avoids races where a very fast
             * run emits before collectors are attached.
             */
            withTimeout(
                firstChunkTimeoutMs
                    .coerceAtLeast(1L)
            ) {
                while (
                    job.isActive &&
                    vm.error.value == null &&
                    vm.raw.value == null &&
                    vm.stream.value.length <
                    minStreamChars
                        .coerceAtLeast(0)
                ) {
                    delay(
                        20L
                    )
                }
            }

            /**
             * Job completion is stronger than "loading became false" or
             * "raw became non-null": it means evaluateAsync's coroutine exited.
             */
            withTimeout(
                completeTimeoutMs
                    .coerceAtLeast(1L) +
                        5_000L
            ) {
                job.join()
            }

            vm.error.value
                ?.let { error ->
                    throw AssertionError(
                        "AiViewModel evaluation error: $error"
                    )
                }

            Assert.assertFalse(
                "AiViewModel Job completed but loading=true",
                vm.loading.value,
            )

            /**
             * A completed Job should have committed final state already.
             * Keep a very small compatibility grace only when raw is absent.
             */
            if (
                vm.raw.value.isNullOrBlank() &&
                tailGraceMs > 0L
            ) {
                delay(
                    tailGraceMs.coerceIn(
                        0L,
                        150L,
                    )
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
                        minFinalChars
                            .coerceAtLeast(0)
            ) {
                "empty/short output @finalize: " +
                        "len=${output.length}, " +
                        "error=${vm.error.value}, " +
                        "loading=${vm.loading.value}, " +
                        "stream.len=${vm.stream.value.length}"
            }

            if (VERBOSE) {
                Logx.kv(
                    TAG,
                    "VM ANSWER",
                    mapOf(
                        "len" to
                                output.length
                                    .toString(),
                        "preview" to
                                oneLine(
                                    output
                                ).take(120),
                    ),
                )
            }

            return output
        } catch (error: Throwable) {
            val snapshot =
                "error=${vm.error.value}, " +
                        "loading=${vm.loading.value}, " +
                        "raw.len=${vm.raw.value?.length ?: -1}, " +
                        "stream.len=${vm.stream.value.length}"

            if (
                job.isActive ||
                vm.loading.value
            ) {
                runCatching {
                    vm.resetStates(
                        keepError = true
                    )
                }
            }

            throw AssertionError(
                "runOnce failed: $snapshot; " +
                        "${error::class.simpleName}: ${error.message}",
                error,
            )
        }
    }
}
