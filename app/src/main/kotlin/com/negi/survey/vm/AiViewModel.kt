/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AiViewModel.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.vm

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.negi.survey.slm.FollowupExtractor
import com.negi.survey.slm.Repository
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * ViewModel dedicated to AI-related operations and chat persistence.
 *
 * Concurrency model:
 * - Single-flight: at most one evaluation/chain at a time.
 * - [activeRunId] guards against stale emissions.
 *
 * Step history model:
 * - Step1 (EVAL) remains in primary UI state flows: raw/score/followups.
 * - Step2 (FOLLOWUP) is appended to [stepHistory] without overwriting Step1.
 * - UI can render both Step1 + Step2 from [stepHistory] while keeping Step1 pinned.
 */
class AiViewModel(
    private val repo: Repository,
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    companion object {
        private const val TAG = "AiViewModel"
        private const val FULL_PROMPT_TAG = "FullPromptReview"
        private const val FULL_TEXT_OUT_TAG = "FullTextOut"

        private const val DEBUG_LOGS = true
        private const val DEBUG_WHITESPACE = true
        private const val DEBUG_PREVIEW_CHARS = 240
        private const val DEBUG_PARSE_FALLBACK = true

        private const val DEFAULT_TIMEOUT_MS = 120_000L

        /**
         * Stream emission throttling:
         * - Reduce StateFlow churn under token streaming.
         * - UI already throttles typing bubble, but VM-side throttling prevents excessive recompositions.
         */
        private const val STREAM_EMIT_THROTTLE_MS = 45L
        private const val STREAM_EMIT_MIN_CHARS = 64
    }

    // ───────────────────────── UI state ─────────────────────────

    private val _loading = MutableStateFlow(false)

    /** True while an evaluation is in progress. */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _score = MutableStateFlow<Int?>(null)

    /** Parsed evaluation score (0..100) or null when unavailable. */
    val score: StateFlow<Int?> = _score.asStateFlow()

    private val _stream = MutableStateFlow("")

    /** Live concatenation of streamed tokens from the model (for the currently running step). */
    val stream: StateFlow<String> = _stream.asStateFlow()

    private val _raw = MutableStateFlow<String?>(null)

    /** Primary raw output (kept as Step1 by default). */
    val raw: StateFlow<String?> = _raw.asStateFlow()

    private val _followupQuestion = MutableStateFlow<String?>(null)

    /** Primary follow-up question extracted from the model output (kept as Step1 by default). */
    val followupQuestion: StateFlow<String?> = _followupQuestion.asStateFlow()

    private val _followups = MutableStateFlow<List<String>>(emptyList())

    /** Primary extracted follow-up questions (top-3, kept as Step1 by default). */
    val followups: StateFlow<List<String>> = _followups.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** Last error string or null. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _events = MutableSharedFlow<AiEvent>(extraBufferCapacity = 32)

    /** Event stream for fine-grained UI reactions. */
    val events: SharedFlow<AiEvent> = _events.asSharedFlow()

    // ─────────────────────── Step history (Step1 + Step2) ───────────────────────

    /**
     * Evaluation output mode.
     *
     * - EVAL_JSON: expects JSON with score + follow-up candidates.
     * - FOLLOWUP_JSON_OR_TEXT: expects either JSON (preferred) OR raw text as follow-up question.
     */
    enum class EvalMode {
        EVAL_JSON,
        FOLLOWUP_JSON_OR_TEXT
    }

    /**
     * Prompt building phase.
     *
     * ONE_STEP: single-call evaluation prompt.
     * EVAL: two-step phase 1 (returns EVAL JSON).
     * FOLLOWUP: two-step phase 2 (returns follow-up question; may be text-only).
     */
    enum class PromptPhase {
        ONE_STEP,
        EVAL,
        FOLLOWUP
    }

    /**
     * Immutable record for a completed step to render both Step1 and Step2 in UI.
     */
    data class StepSnapshot(
        val runId: Long,
        val phase: PromptPhase,
        val mode: EvalMode,
        val raw: String,
        val score: Int?,
        val followups: List<String>,
        val timedOut: Boolean,
        val error: String?,
        val durationMs: Long
    )

    private val _stepHistory = MutableStateFlow<List<StepSnapshot>>(emptyList())

    /** Completed steps in order (keeps Step1 + Step2). */
    val stepHistory: StateFlow<List<StepSnapshot>> = _stepHistory.asStateFlow()

    /** Clear step history (typically at the start of a new independent run/chain). */
    private fun clearStepHistory() {
        _stepHistory.value = emptyList()
    }

    /** Append one snapshot to history. */
    private fun appendStepSnapshot(s: StepSnapshot) {
        _stepHistory.update { it + s }
        if (DEBUG_LOGS) {
            val fu0 = s.followups.firstOrNull()?.let { preview(it) } ?: "<none>"
            Log.d(
                TAG,
                "stepHistory+ runId=${s.runId} phase=${s.phase} mode=${s.mode} " +
                        "raw.len=${s.raw.length} score=${s.score} FU=${s.followups.size} " +
                        "FU0='${debugVisible(fu0)}' timeout=${s.timedOut} err=${s.error} durMs=${s.durationMs}"
            )
        }
    }

    // ─────────────────────── Execution control ───────────────────────

    private var evalJob: Job? = null
    private val running = AtomicBoolean(false)

    private val runSeq = AtomicLong(0L)
    private val activeRunId = AtomicLong(0L)

    /** True when an evaluation coroutine is currently running. */
    val isRunning: Boolean
        get() = running.get()

    /**
     * Run-local immutable result for chaining.
     */
    data class EvalResult(
        val runId: Long,
        val raw: String,
        val score: Int?,
        val followups: List<String>,
        val timedOut: Boolean
    )

    /**
     * Evaluate the given [prompt] and return the parsed score (0..100) or null.
     *
     * Single-flight:
     * - If already running, returns the current score.
     */
    suspend fun evaluate(prompt: String, timeoutMs: Long = defaultTimeoutMs): Int? {
        if (prompt.isBlank()) {
            Log.i(TAG, "evaluate: blank prompt -> reset states and return null")
            resetStates(keepError = false)
            return null
        }

        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "evaluate: already running -> returning current score=${_score.value}")
            return _score.value
        }

        cancelDanglingJobIfAny(reason = "dangling_before_new_run")
        prepareUiForNewChain(clearHistory = true)

        val runId = runSeq.incrementAndGet()
        activeRunId.set(runId)

        val elapsed = measureTimeMillis {
            val job = startEvaluationInternal(
                runId = runId,
                userPrompt = prompt,
                timeoutMs = timeoutMs,
                mode = EvalMode.EVAL_JSON,
                phase = PromptPhase.ONE_STEP,
                commitToPrimaryState = true
            )
            evalJob = job
            runCatching { job.join() }
                .onFailure { t ->
                    if (DEBUG_LOGS) {
                        Log.w(TAG, "evaluate: job.join() failed (likely cancelled) err=${t::class.java.simpleName}:${t.message}")
                    }
                }
        }

        Log.d(TAG, "evaluate: finished in ${elapsed}ms, score=${_score.value}, err=${_error.value}")
        return _score.value
    }

    /**
     * Fire-and-forget variant of [evaluate].
     */
    fun evaluateAsync(prompt: String, timeoutMs: Long = defaultTimeoutMs): Job {
        if (prompt.isBlank()) {
            resetStates(keepError = false)
            return viewModelScope.launch { }
        }

        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "evaluateAsync: already running -> returning existing job")
            return evalJob ?: viewModelScope.launch { }
        }

        cancelDanglingJobIfAny(reason = "dangling_before_new_run")
        prepareUiForNewChain(clearHistory = true)

        val runId = runSeq.incrementAndGet()
        activeRunId.set(runId)

        val job = startEvaluationInternal(
            runId = runId,
            userPrompt = prompt,
            timeoutMs = timeoutMs,
            mode = EvalMode.EVAL_JSON,
            phase = PromptPhase.ONE_STEP,
            commitToPrimaryState = true
        )
        evalJob = job
        return job
    }

    /**
     * Two-step chaining:
     * 1) Evaluate [firstPrompt].
     * 2) Build prompt2 from step1 result via [buildSecondPrompt], then evaluate it.
     *
     * This keeps both steps in [stepHistory].
     */
    fun evaluateTwoStepFromFirstAsync(
        firstPrompt: String,
        timeoutMs: Long = defaultTimeoutMs,
        proceedOnTimeout: Boolean = true,
        buildSecondPrompt: (EvalResult) -> String
    ): Job {
        val p1 = firstPrompt.trim()
        if (p1.isEmpty()) {
            resetStates(keepError = false)
            return viewModelScope.launch { }
        }

        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "evaluateTwoStepFromFirstAsync: already running -> returning existing job")
            return evalJob ?: viewModelScope.launch { }
        }

        cancelDanglingJobIfAny(reason = "dangling_before_new_chain")
        prepareUiForNewChain(clearHistory = true)

        val chainJob = viewModelScope.launch(ioDispatcher) {
            try {
                if (DEBUG_LOGS) Log.d(TAG, "chain2: timeoutMs=$timeoutMs")

                // --- step 1 ---
                val runId1 = runSeq.incrementAndGet()
                activeRunId.set(runId1)

                val r1 = runEvaluationCore(
                    runId = runId1,
                    userPrompt = p1,
                    timeoutMs = timeoutMs,
                    mode = EvalMode.EVAL_JSON,
                    phase = PromptPhase.EVAL,
                    commitToPrimaryState = true
                )

                if (!proceedOnTimeout && r1.timedOut) {
                    if (DEBUG_LOGS) Log.w(TAG, "chain2: step1 timed out -> skipping step2 (proceedOnTimeout=false)")
                    return@launch
                }

                // --- step 2 (derived) ---
                val p2 = runCatching { buildSecondPrompt(r1).trim() }
                    .onFailure { t -> Log.e(TAG, "chain2: buildSecondPrompt failed", t) }
                    .getOrElse { "" }

                if (p2.isEmpty()) {
                    if (DEBUG_LOGS) Log.w(TAG, "chain2: step2 prompt is blank -> done")
                    return@launch
                }

                prepareUiForNextStep()

                val runId2 = runSeq.incrementAndGet()
                activeRunId.set(runId2)

                runEvaluationCore(
                    runId = runId2,
                    userPrompt = p2,
                    timeoutMs = timeoutMs,
                    mode = EvalMode.FOLLOWUP_JSON_OR_TEXT,
                    phase = PromptPhase.FOLLOWUP,
                    commitToPrimaryState = false
                )
            } finally {
                finalizeChainFlags()
            }
        }

        evalJob = chainJob
        return chainJob
    }

    /**
     * Conditional two-step:
     * 1) Run a short EVAL prompt (step1).
     * 2) Only if [shouldRunSecond] returns true, build prompt2 from step1 result and run step2.
     */
    fun evaluateConditionalTwoStepAsync(
        firstPrompt: String,
        timeoutMs: Long = defaultTimeoutMs,
        proceedOnTimeout: Boolean = true,
        shouldRunSecond: (EvalResult) -> Boolean,
        buildSecondPrompt: (EvalResult) -> String
    ): Job {
        val p1 = firstPrompt.trim()
        if (p1.isEmpty()) {
            resetStates(keepError = false)
            return viewModelScope.launch { }
        }

        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "evaluateConditionalTwoStepAsync: already running -> returning existing job")
            return evalJob ?: viewModelScope.launch { }
        }

        cancelDanglingJobIfAny(reason = "dangling_before_new_chain")
        prepareUiForNewChain(clearHistory = true)

        val chainJob = viewModelScope.launch(ioDispatcher) {
            try {
                // --- step 1 (EVAL JSON) ---
                val runId1 = runSeq.incrementAndGet()
                activeRunId.set(runId1)

                val step1 = runEvaluationCore(
                    runId = runId1,
                    userPrompt = p1,
                    timeoutMs = timeoutMs,
                    mode = EvalMode.EVAL_JSON,
                    phase = PromptPhase.EVAL,
                    commitToPrimaryState = true
                )

                if (step1.timedOut && !proceedOnTimeout) {
                    if (DEBUG_LOGS) Log.w(TAG, "chain2: step1 timed out -> skipping step2 (proceedOnTimeout=false)")
                    return@launch
                }

                val doStep2 = runCatching { shouldRunSecond(step1) }
                    .onFailure { t -> Log.e(TAG, "chain2: shouldRunSecond failed -> treat as false", t) }
                    .getOrElse { false }

                if (!doStep2) {
                    if (DEBUG_LOGS) {
                        Log.d(
                            TAG,
                            "chain2: step2 skipped (score=${step1.score}, followups=${step1.followups.size}, timedOut=${step1.timedOut}, rawPreview='${debugVisible(preview(step1.raw))}')"
                        )
                    }
                    return@launch
                }

                // --- step 2 (FOLLOWUP; JSON or raw text) ---
                val p2 = runCatching { buildSecondPrompt(step1).trim() }
                    .onFailure { t -> Log.e(TAG, "chain2: buildSecondPrompt failed", t) }
                    .getOrElse { "" }

                if (p2.isEmpty()) {
                    if (DEBUG_LOGS) Log.w(TAG, "chain2: step2 prompt is blank -> done")
                    return@launch
                }

                prepareUiForNextStep()

                val runId2 = runSeq.incrementAndGet()
                activeRunId.set(runId2)

                runEvaluationCore(
                    runId = runId2,
                    userPrompt = p2,
                    timeoutMs = timeoutMs,
                    mode = EvalMode.FOLLOWUP_JSON_OR_TEXT,
                    phase = PromptPhase.FOLLOWUP,
                    commitToPrimaryState = false
                )
            } finally {
                finalizeChainFlags()
            }
        }

        evalJob = chainJob
        return chainJob
    }

    /**
     * Cancel the ongoing evaluation if any.
     *
     * This is a user-driven cancellation path.
     */
    fun cancel() {
        Log.i(TAG, "cancel: invoked (isRunning=${running.get()}, loading=${_loading.value})")
        stopCurrentRunInternal(reason = "cancelled", emitCancelledEvent = true, setCancelledError = true)
    }

    /**
     * Reset transient AI-related states.
     *
     * NOTE:
     * - Also clears [stepHistory] because the UI expects a clean slate.
     */
    fun resetStates(keepError: Boolean = false) {
        stopCurrentRunInternal(reason = "reset", emitCancelledEvent = false, setCancelledError = false)

        clearStepHistory()

        _score.value = null
        _stream.value = ""
        _raw.value = null
        _followupQuestion.value = null
        _followups.value = emptyList()
        _loading.value = false
        if (!keepError) _error.value = null
    }

    override fun onCleared() {
        Log.i(TAG, "onCleared: ViewModel is being cleared -> stopCurrentRunInternal()")
        super.onCleared()
        stopCurrentRunInternal(reason = "cleared", emitCancelledEvent = false, setCancelledError = false)
    }

    /**
     * Backward-compatible alias for older call sites.
     *
     * Prefer [resetStates] for new code.
     */
    @Deprecated(
        message = "Use resetStates(keepError) instead.",
        replaceWith = ReplaceWith("resetStates(keepError = keepError)")
    )
    fun resetAll(keepError: Boolean = false) {
        resetStates(keepError = keepError)
    }

    // ───────────────────────── Internal evaluation core ─────────────────────────

    private fun startEvaluationInternal(
        runId: Long,
        userPrompt: String,
        timeoutMs: Long,
        mode: EvalMode,
        phase: PromptPhase,
        commitToPrimaryState: Boolean
    ): Job = viewModelScope.launch(ioDispatcher) {
        try {
            runEvaluationCore(
                runId = runId,
                userPrompt = userPrompt,
                timeoutMs = timeoutMs,
                mode = mode,
                phase = phase,
                commitToPrimaryState = commitToPrimaryState
            )
        } finally {
            finalizeRunFlagsIfActive(runId)
        }
    }

    // ───────────────────────── Prompt reflection compat ─────────────────────────

    private val trySetAccessibleMethod: java.lang.reflect.Method? by lazy {
        runCatching {
            java.lang.reflect.AccessibleObject::class.java.getMethod("trySetAccessible")
        }.getOrNull()
    }

    /** True if the parameter expects String. */
    private fun isStringParam(c: Class<*>): Boolean = (c == String::class.java)

    /** True if the parameter expects Int (primitive or boxed). */
    private fun isIntParam(c: Class<*>): Boolean =
        c == Int::class.javaPrimitiveType || c == Int::class.javaObjectType

    /** True if the parameter expects Long (primitive or boxed). */
    private fun isLongParam(c: Class<*>): Boolean =
        c == Long::class.javaPrimitiveType || c == Long::class.javaObjectType

    /** True if the parameter expects Short (primitive or boxed). */
    private fun isShortParam(c: Class<*>): Boolean =
        c == Short::class.javaPrimitiveType || c == Short::class.javaObjectType

    /** True if the parameter expects Byte (primitive or boxed). */
    private fun isByteParam(c: Class<*>): Boolean =
        c == Byte::class.javaPrimitiveType || c == Byte::class.javaObjectType

    /** True if the parameter expects an integral numeric type (primitive or boxed). */
    private fun isIntegralParam(c: Class<*>): Boolean =
        isIntParam(c) || isLongParam(c) || isShortParam(c) || isByteParam(c)

    /** Best-effort accessibility enabling across Android/JDK variants. */
    @Suppress("DEPRECATION")
    private fun ensureAccessible(m: java.lang.reflect.Method) {
        runCatching {
            val meth = trySetAccessibleMethod ?: return@runCatching
            val ok = meth.invoke(m) as? Boolean
            if (ok == true) return
        }
        runCatching { m.isAccessible = true }
    }

    /** Convert reflection result to a String safely. */
    private fun Any?.toPromptString(): String {
        return when (this) {
            null -> ""
            is String -> this
            is CharSequence -> this.toString()
            else -> this.toString()
        }
    }

    /**
     * Compute "inheritance distance" from [from] to [to].
     *
     * - 0 means same class.
     * - Larger means further in the superclass chain.
     * - Int.MAX_VALUE means unrelated.
     */
    private fun classDistance(from: Class<*>, to: Class<*>): Int {
        if (from == to) return 0
        var d = 0
        var c: Class<*>? = from
        while (c != null) {
            if (c == to) return d
            c = c.superclass
            d++
        }
        return Int.MAX_VALUE
    }

    /**
     * Build prompt via reflection with maximum compatibility across overload variants.
     *
     * Supported overload shapes:
     *  - buildPrompt(input: String, phase: PromptPhase): String
     *  - buildPrompt(input: String, phaseName: String): String
     *  - buildPrompt(input: String, phaseOrdinal: Int/Long/Short/Byte): String
     *  - buildPrompt(input: String, phaseEnum: <any enum with matching names>): String
     *  - buildPrompt(input: String): String
     */
    private fun buildPromptCompat(input: String, p: PromptPhase): String {
        return runCatching {
            val cls = repo.javaClass

            // Collect public + declared methods, then de-duplicate by signature.
            val methods = (cls.methods.asList() + cls.declaredMethods.asList())
                .asSequence()
                .filter { it.name == "buildPrompt" }
                .distinctBy { m ->
                    val params = m.parameterTypes.joinToString(",") { it.name }
                    "${m.name}($params):${m.returnType.name}"
                }
                .toList()

            if (DEBUG_LOGS) {
                Log.d(TAG, "buildPromptCompat: found=${methods.size} inputLen=${input.length} phase=${p.name}/${p.ordinal}")
            }

            // Prefer 2-arg overloads first: (String, X)
            val twoArg = methods
                .filter { it.parameterTypes.size == 2 && it.parameterTypes[0] == String::class.java }
                .sortedWith(
                    compareBy<java.lang.reflect.Method> { m ->
                        // Lower = higher priority for param type
                        val c = m.parameterTypes[1]
                        when {
                            c == PromptPhase::class.java -> 0
                            c.isEnum -> 1
                            isStringParam(c) -> 2
                            isIntegralParam(c) -> 3
                            else -> 9
                        }
                    }.thenBy { m ->
                        // Prefer methods declared closer to repo's concrete class
                        classDistance(cls, m.declaringClass)
                    }
                )

            for (m in twoArg) {
                val param1 = m.parameterTypes[1]
                ensureAccessible(m)

                val (arg1, argKind) = buildPhaseArgument(param1, p)

                try {
                    if (DEBUG_LOGS) {
                        Log.d(
                            TAG,
                            "buildPromptCompat: try2 decl=${m.declaringClass.simpleName} param1=${param1.name} argKind=$argKind"
                        )
                    }

                    val result = m.invoke(repo, input, arg1)
                    val out = result.toPromptString()

                    if (DEBUG_LOGS) {
                        Log.d(
                            TAG,
                            "buildPromptCompat: ok2 decl=${m.declaringClass.simpleName} param1=${param1.simpleName} outLen=${out.length}"
                        )
                    }
                    return@runCatching out
                } catch (t: Throwable) {
                    if (DEBUG_LOGS) {
                        Log.w(
                            TAG,
                            "buildPromptCompat: fail2 decl=${m.declaringClass.simpleName} param1=${param1.name} err=${t::class.java.simpleName}:${t.message}"
                        )
                    }
                }
            }

            // Fallback to 1-arg overload: (String)
            val oneArg = methods
                .asSequence()
                .filter { it.parameterTypes.size == 1 && it.parameterTypes[0] == String::class.java }
                .sortedBy { m -> classDistance(cls, m.declaringClass) }
                .firstOrNull()

            if (oneArg != null) {
                ensureAccessible(oneArg)
                try {
                    if (DEBUG_LOGS) {
                        Log.d(TAG, "buildPromptCompat: try1 decl=${oneArg.declaringClass.simpleName} return=${oneArg.returnType.name}")
                    }
                    val result = oneArg.invoke(repo, input)
                    val out = result.toPromptString()
                    if (DEBUG_LOGS) Log.d(TAG, "buildPromptCompat: ok1 outLen=${out.length}")
                    return@runCatching out
                } catch (t: Throwable) {
                    if (DEBUG_LOGS) {
                        Log.w(
                            TAG,
                            "buildPromptCompat: fail1 decl=${oneArg.declaringClass.simpleName} err=${t::class.java.simpleName}:${t.message}"
                        )
                    }
                }
            }

            // Absolute fallback.
            if (DEBUG_LOGS) Log.w(TAG, "buildPromptCompat: fallback -> input (no usable overload)")
            input
        }.getOrElse { t ->
            if (DEBUG_LOGS) {
                Log.w(TAG, "buildPromptCompat: exception -> fallback input err=${t::class.java.simpleName}:${t.message}")
            }
            input
        }
    }

    /**
     * Build a compatible phase argument for the given parameter type.
     *
     * Returns: Pair(argument, debugKind)
     */
    private fun buildPhaseArgument(paramType: Class<*>, p: PromptPhase): Pair<Any, String> {
        // Exact match: our PromptPhase.
        if (paramType == PromptPhase::class.java) return p to "PromptPhase"

        // Any enum: try to map by name.
        if (paramType.isEnum) {
            val enumValue = runCatching {
                @Suppress("UNCHECKED_CAST")
                val enumClass = paramType.asSubclass(Enum::class.java) as Class<out Enum<*>>
                java.lang.Enum.valueOf(enumClass, p.name)
            }.getOrNull()

            if (enumValue != null) return enumValue to "Enum(${paramType.simpleName}).name"
            // Fall through to string if mapping fails.
        }

        // String: pass name.
        if (isStringParam(paramType)) return p.name to "String(name)"

        // Integral types: pass ordinal in the requested numeric width.
        if (isIntegralParam(paramType)) {
            val ord = p.ordinal
            return when {
                isIntParam(paramType) -> ord to "Int(ordinal)"
                isLongParam(paramType) -> ord.toLong() to "Long(ordinal)"
                isShortParam(paramType) -> ord.toShort() to "Short(ordinal)"
                isByteParam(paramType) -> ord.toByte() to "Byte(ordinal)"
                else -> ord to "Int(ordinal)"
            }
        }

        // Default fallback: name.
        return p.name to "Fallback(String)"
    }

    // ───────────────────────── Evaluation core ─────────────────────────

    private suspend fun runEvaluationCore(
        runId: Long,
        userPrompt: String,
        timeoutMs: Long,
        mode: EvalMode,
        phase: PromptPhase,
        commitToPrimaryState: Boolean
    ): EvalResult {
        val tStart = SystemClock.uptimeMillis()

        val buf = StringBuilder()
        var chunkCount = 0
        var totalChars = 0
        var timedOut = false
        var stepError: String? = null

        var lastEmitMs = 0L
        var lastEmitChars = 0

        fun isActiveRun(): Boolean = activeRunId.get() == runId

        try {
            val fullPrompt = runCatching { buildPromptCompat(userPrompt, phase) }
                .onFailure { t ->
                    Log.e(TAG, "run[$runId]: buildPromptCompat failed; falling back to userPrompt", t)
                }
                .getOrElse { userPrompt }

            if (DEBUG_LOGS) {
                Log.d(
                    TAG,
                    "run[$runId]: mode=$mode phase=$phase commit=$commitToPrimaryState " +
                            "prompt.len=${userPrompt.length}, fullPrompt.len=${fullPrompt.length}, timeoutMs=$timeoutMs"
                )
                Log.d(TAG, "run[$runId]: sha(prompt)=${sha256Hex(userPrompt)} sha(full)=${sha256Hex(fullPrompt)}")
            }

            if (DEBUG_LOGS) {
                Log.i(FULL_PROMPT_TAG, "run[$runId]: FullPrompt=\n$fullPrompt")
            }

            try {
                withTimeout(timeoutMs) {
                    repo.request(fullPrompt).collect { part ->
                        if (!isActiveRun()) return@collect

                        if (part.isNotEmpty()) {
                            chunkCount++
                            buf.append(part)
                            totalChars += part.length

                            // Emit chunk event immediately (lightweight).
                            _events.tryEmit(AiEvent.Stream(part))

                            // Throttle the heavy StateFlow string rebuild.
                            val now = SystemClock.uptimeMillis()
                            val shouldEmit =
                                (now - lastEmitMs) >= STREAM_EMIT_THROTTLE_MS ||
                                        (totalChars - lastEmitChars) >= STREAM_EMIT_MIN_CHARS

                            if (shouldEmit) {
                                lastEmitMs = now
                                lastEmitChars = totalChars
                                _stream.value = buf.toString()
                            }

                            if (DEBUG_LOGS) {
                                Log.d(TAG, "run[$runId] chunk[$chunkCount].preview='${debugVisible(preview(part))}'")
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                timedOut = true
                stepError = "timeout"
                if (DEBUG_LOGS) Log.w(TAG, "run[$runId]: timeout after ${timeoutMs}ms", e)
            } catch (e: CancellationException) {
                if (!isActiveRun()) throw e
                if (looksLikeTimeout(e)) {
                    timedOut = true
                    stepError = "timeout"
                    if (DEBUG_LOGS) Log.w(TAG, "run[$runId]: timeout-like cancellation (${e.javaClass.name})")
                } else {
                    throw e
                }
            }

            if (!isActiveRun()) {
                return EvalResult(runId = runId, raw = "", score = null, followups = emptyList(), timedOut = timedOut)
            }

            // Ensure final stream is up-to-date even if throttled.
            if (buf.isNotEmpty()) _stream.value = buf.toString()

            val rawText = buf.toString().ifBlank { _stream.value }

            if (DEBUG_LOGS) {
                Log.d(TAG, "run[$runId] stats: chunks=$chunkCount, chars=$totalChars, raw.len=${rawText.length}")
                Log.d(TAG, "run[$runId] sha(raw)=${sha256Hex(rawText)}")
            }
            if (DEBUG_LOGS && DEBUG_WHITESPACE) {
                Log.d(TAG, "run[$runId] rawVisible='${debugVisible(preview(rawText))}'")
            }

            val durationMs = SystemClock.uptimeMillis() - tStart

            val parsedScore: Int?
            val top3: List<String>
            val q0: String?

            when (mode) {
                EvalMode.EVAL_JSON -> {
                    val parseBase = normalizeForJsonParsing(rawText)

                    if (parseBase.isBlank() || isEmptyJsonObject(parseBase)) {
                        parsedScore = null
                        top3 = emptyList()
                        q0 = null
                        if (DEBUG_LOGS) {
                            Log.w(
                                TAG,
                                "run[$runId]: EVAL_JSON output is empty/trivial ('${debugVisible(preview(parseBase))}') -> score=null, followups=0"
                            )
                        }
                    } else {
                        val (s, f, usedPath) = parseEvalJsonRobust(parseBase)

                        parsedScore = clampScore(s)
                        top3 = sanitizeFollowups(f)
                        q0 = top3.firstOrNull()

                        if (DEBUG_LOGS) {
                            Log.d(
                                TAG,
                                "run[$runId]: EVAL_JSON parsed score=$parsedScore followups=${top3.size} " +
                                        "fu0='${debugVisible(preview(q0.orEmpty()))}' via=$usedPath"
                            )
                        }
                    }
                }

                EvalMode.FOLLOWUP_JSON_OR_TEXT -> {
                    /**
                     * Step2 may be:
                     * - JSON object containing follow-up question(s)
                     * - Plain text follow-up question
                     * - Meta text ("analysis:", "weakness", etc.)
                     *
                     * FIX:
                     * - If meta prefixes exist, strip them and retry prompt detection.
                     * - Expand Swahili imperative vocabulary (e.g., "tathmini").
                     */
                    val base = stripCodeFenceAtHead(rawText).trim()

                    val jsonSlice = sliceLikelyJsonObject(base)
                    val jsonQ = jsonSlice?.let { extractFollowupFromJsonObject(it) }
                    val textQ = extractFollowupFromPlainText(base)

                    val best0 = (jsonQ ?: textQ)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.takeIf { !isEmptyJsonObject(it) }
                        ?.takeIf { !isJsonLike(it) }

                    val best = best0
                        ?.let { normalizeLine(it) }
                        ?.let { stripMetaPrefixIfAny(it) ?: it }
                        ?.takeIf { isLikelyPromptLine(it) }

                    parsedScore = null
                    top3 = best?.let { listOf(it) } ?: emptyList()
                    q0 = best

                    if (DEBUG_LOGS) {
                        Log.d(
                            TAG,
                            "run[$runId]: FOLLOWUP parse jsonSlice=${jsonSlice != null} " +
                                    "jsonQ='${debugVisible(preview(jsonQ.orEmpty()))}' " +
                                    "textQ='${debugVisible(preview(textQ.orEmpty()))}' " +
                                    "best='${debugVisible(preview(best.orEmpty()))}'"
                        )
                    }
                }
            }

            // Reflect step-local error state to the global UI error flow.
            if (stepError != null) {
                _error.value = stepError
            } else {
                _error.value = null
            }

            appendStepSnapshot(
                StepSnapshot(
                    runId = runId,
                    phase = phase,
                    mode = mode,
                    raw = rawText,
                    score = parsedScore,
                    followups = top3,
                    timedOut = timedOut,
                    error = stepError,
                    durationMs = durationMs
                )
            )

            if (commitToPrimaryState) {
                _raw.value = rawText
                _score.value = parsedScore
                _followups.value = top3
                _followupQuestion.value = q0
            }

            // Emit step-local final (do not accidentally reuse Step1 pinned values).
            _events.tryEmit(AiEvent.Final(rawText, parsedScore, top3))

            if (timedOut) {
                _events.tryEmit(AiEvent.Timeout)
            }

            Log.i(
                TAG,
                "run[$runId] done: phase=$phase mode=$mode score=$parsedScore FU[0]=${q0 ?: "<none>"} commit=$commitToPrimaryState err=${stepError ?: "<none>"} durMs=$durationMs"
            )

            if (DEBUG_LOGS) {
                Log.i(FULL_TEXT_OUT_TAG, "run[$runId]: RawTextOut=\n$rawText")
            }

            return EvalResult(runId = runId, raw = rawText, score = parsedScore, followups = top3, timedOut = timedOut)
        } catch (e: CancellationException) {
            if (DEBUG_LOGS) Log.w(TAG, "run[$runId]: cancelled", e)
            throw e
        } catch (t: Throwable) {
            if (!isActiveRun()) {
                return EvalResult(runId = runId, raw = "", score = null, followups = emptyList(), timedOut = false)
            }

            val msg = t.message ?: "error"
            _error.value = msg
            _events.tryEmit(AiEvent.Error(msg))
            Log.e(TAG, "run[$runId]: error", t)

            val rawText = _stream.value
            val durationMs = SystemClock.uptimeMillis() - tStart

            appendStepSnapshot(
                StepSnapshot(
                    runId = runId,
                    phase = phase,
                    mode = mode,
                    raw = rawText,
                    score = null,
                    followups = emptyList(),
                    timedOut = false,
                    error = msg,
                    durationMs = durationMs
                )
            )

            // Emit step-local final for this failed step.
            _events.tryEmit(AiEvent.Final(rawText, null, emptyList()))

            return EvalResult(
                runId = runId,
                raw = rawText,
                score = null,
                followups = emptyList(),
                timedOut = false
            )
        }
    }

    // ───────────────────────── UI preparation ─────────────────────────

    private fun prepareUiForNewChain(clearHistory: Boolean) {
        _loading.value = true
        _score.value = null
        _stream.value = ""
        _raw.value = null
        _followupQuestion.value = null
        _followups.value = emptyList()

        // Always clear error at the start of a new chain.
        _error.value = null

        if (clearHistory) clearStepHistory()
    }

    private fun prepareUiForNextStep() {
        _loading.value = true
        _stream.value = ""

        // Always clear error at the start of the next step.
        _error.value = null
    }

    private fun finalizeRunFlagsIfActive(runId: Long) {
        if (activeRunId.get() != runId) return
        _loading.value = false
        running.set(false)
        evalJob = null
        activeRunId.set(0L)
    }

    private fun finalizeChainFlags() {
        _loading.value = false
        running.set(false)
        evalJob = null
        activeRunId.set(0L)
    }

    private fun stopCurrentRunInternal(
        reason: String,
        emitCancelledEvent: Boolean,
        setCancelledError: Boolean
    ) {
        val job = evalJob
        evalJob = null

        if (setCancelledError) _error.value = "cancelled"

        activeRunId.set(-1L)

        if (job != null) {
            runCatching { job.cancel(CancellationException(reason)) }
                .onFailure { t -> Log.w(TAG, "stopCurrentRunInternal: exception during cancel (ignored)", t) }
        }

        _loading.value = false
        running.set(false)

        if (emitCancelledEvent) {
            _events.tryEmit(AiEvent.Cancelled)
        }
    }

    private fun cancelDanglingJobIfAny(reason: String) {
        val job = evalJob ?: return
        evalJob = null
        activeRunId.set(-1L)

        runCatching { job.cancel(CancellationException(reason)) }
            .onFailure { t -> Log.w(TAG, "cancelDanglingJobIfAny: exception during cancel (ignored)", t) }
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun clampScore(s: Int?): Int? = s?.coerceIn(0, 100)

    private fun looksLikeTimeout(e: CancellationException): Boolean {
        val n = e.javaClass.name
        val m = e.message ?: ""
        return n.endsWith("TimeoutCancellationException") ||
                n.contains("Timeout", ignoreCase = true) ||
                m.contains("timeout", ignoreCase = true)
    }

    private fun sha256Hex(input: String): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        bytes.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
    }.getOrElse { "sha256_error" }

    /** Convert whitespace/newlines/tabs to visible markers for logs. */
    private fun debugVisible(s: String): String {
        if (s.isEmpty()) return ""
        return buildString(s.length) {
            for (ch in s) {
                append(
                    when (ch) {
                        ' ' -> '␠'
                        '\n' -> '↩'
                        '\t' -> '⇥'
                        '\r' -> '␍'
                        else -> ch
                    }
                )
            }
        }
    }

    /** Safe preview for logs (avoid huge lines). */
    private fun preview(s: String): String {
        if (s.isEmpty()) return ""
        val n = min(DEBUG_PREVIEW_CHARS, s.length)
        return s.take(n)
    }

    /**
     * Return true if the output is a trivial empty JSON object (optionally with whitespace).
     */
    private fun isEmptyJsonObject(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val compact = t.replace(Regex("\\s+"), "")
        return compact == "{}"
    }

    /** Return true if the string starts like JSON. Used for filtering follow-up candidates. */
    private fun isJsonLike(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("{") || t.startsWith("[")
    }

    /**
     * Normalize a single-line prompt candidate:
     * - Strip surrounding quotes
     * - Collapse whitespace
     */
    private fun normalizeLine(s: String): String {
        return s
            .trim()
            .trim('"')
            .lines()
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Strip meta prefixes like:
     * - "analysis: ..."
     * - "weakness ..."
     * - "strength - ..."
     *
     * Returns the stripped payload if matched; otherwise null.
     */
    private fun stripMetaPrefixIfAny(s: String): String? {
        val t = normalizeLine(s)
        if (t.isBlank()) return null

        val r1 = Regex(
            pattern = "^(analysis|weakness|strength|note|notes|reason|explanation)\\s*[:\\-]\\s*(.+)$",
            options = setOf(RegexOption.IGNORE_CASE)
        )
        r1.matchEntire(t)?.let { m ->
            val payload = normalizeLine(m.groupValues[2])
            return payload.takeIf { it.isNotBlank() }
        }

        val r2 = Regex(
            pattern = "^(analysis|weakness|strength|note|notes|reason|explanation)\\s+(.+)$",
            options = setOf(RegexOption.IGNORE_CASE)
        )
        r2.matchEntire(t)?.let { m ->
            val payload = normalizeLine(m.groupValues[2])
            return payload.takeIf { it.isNotBlank() }
        }

        return null
    }

    /**
     * Return true if the string looks like a meta/debug line rather than a prompt.
     */
    private fun looksLikeMetaLine(s: String): Boolean {
        val t = s.trim().lowercase()
        return t.startsWith("analysis:") ||
                t.startsWith("analysis ") ||
                t.startsWith("weakness") ||
                t.startsWith("strength") ||
                t.startsWith("notes:") ||
                t.startsWith("note:") ||
                t.startsWith("reason:") ||
                t.startsWith("explanation:") ||
                t.startsWith("score:")
    }

    /**
     * Heuristic: accept only lines that look like a question OR an instruction-style prompt.
     */
    private fun isLikelyPromptLine(line: String): Boolean {
        val s0 = normalizeLine(line)
        if (s0.length !in 3..260) return false

        // If it is meta, try stripping and re-check.
        val stripped = stripMetaPrefixIfAny(s0)
        val base = stripped ?: s0
        if (looksLikeMetaLine(base)) return false

        // Direct question punctuation.
        if (base.contains('?') || base.contains('？')) return true

        val s = base.trimStart('-', '•', '*', ' ', '\t')
        val lower = s.lowercase()

        // Japanese question ending heuristic (no punctuation).
        if (s.endsWith("か") || s.endsWith("か。")) return true

        // English interrogatives.
        val enQ = listOf(
            "is ", "are ", "do ", "does ", "did ",
            "what ", "why ", "how ", "when ", "where ", "which ", "who ", "whom ", "whose "
        )
        if (enQ.any { lower.startsWith(it) }) return true

        // Swahili interrogatives.
        if (lower.startsWith("kwa nini")) return true
        val swQ = listOf("je ", "vipi ", "nini ", "lini ", "wapi ", "gani ")
        if (swQ.any { lower.startsWith(it) }) return true

        // English directive prompts (imperative).
        val enCmd = listOf(
            "describe ", "explain ", "specify ", "provide ", "tell ",
            "list ", "share ", "clarify ", "elaborate ", "identify ",
            "estimate ", "confirm ", "compare ", "assess ", "evaluate "
        )
        if (enCmd.any { lower.startsWith(it) }) return true
        if (lower.startsWith("please ")) return true

        // Swahili directive prompts (imperative).
        val swCmd = listOf(
            "eleza ", "elezea ", "fafanua ", "taja ", "bainisha ",
            "toa ", "orodhesha ", "sema ", "andika ", "onyesha ",
            "tathmini ", "kadiria ", "thibitisha ", "linganisha "
        )
        if (swCmd.any { lower.startsWith(it) }) return true

        return false
    }

    /**
     * Filter out garbage follow-up candidates.
     */
    private fun sanitizeFollowups(list: List<String>): List<String> {
        return list
            .asSequence()
            .map { normalizeLine(it) }
            .map { stripMetaPrefixIfAny(it) ?: it }
            .filter { it.isNotBlank() }
            .filterNot { isEmptyJsonObject(it) }
            .filterNot { isJsonLike(it) }
            .filterNot { looksLikeMetaLine(it) }
            .filter { isLikelyPromptLine(it) }
            .distinct()
            .take(3)
            .toList()
    }

    /** Extract a plausible follow-up prompt from raw text output (non-JSON fallback). */
    private fun extractFollowupFromPlainText(raw: String): String? {
        val t0 = stripCodeFenceAtHead(raw).trim()
        if (t0.isBlank()) return null
        if (isEmptyJsonObject(t0)) return null
        if (isJsonLike(t0)) return null

        val unquoted = t0.removePrefix("\"").removeSuffix("\"").trim()

        val lines = unquoted
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        // Prefer a line that looks like a question/prompt.
        for (ln in lines) {
            val n = normalizeLine(ln)
            val cand = stripMetaPrefixIfAny(n) ?: n
            if (isLikelyPromptLine(cand)) return cand
        }

        return null
    }

    /**
     * Normalize model output for JSON parsing robustness:
     * - If there is a code fence anywhere, extract the first fence body.
     * - Else, try slicing the first JSON object from the text.
     * - Else, fall back to head-stripped text.
     */
    private fun normalizeForJsonParsing(raw: String): String {
        val fenceBody = extractFirstCodeFenceBody(raw)
        if (fenceBody != null) return fenceBody.trim()

        val headStripped = stripCodeFenceAtHead(raw).trim()
        val sliced = sliceLikelyJsonObject(headStripped)
        if (sliced != null) return sliced.trim()

        return headStripped
    }

    /**
     * Extract the body of the first Markdown code fence found in the text.
     *
     * Supports:
     * - ```json\n{...}\n```
     * - ```\n...\n```
     */
    private fun extractFirstCodeFenceBody(text: String): String? {
        val i0 = text.indexOf("```")
        if (i0 < 0) return null
        val i1 = text.indexOf('\n', startIndex = i0 + 3)
        if (i1 < 0) return null
        val i2 = text.indexOf("```", startIndex = i1 + 1)
        if (i2 < 0) return null
        return text.substring(i1 + 1, i2)
    }

    /**
     * Best-effort JSON object slicing from possibly noisy text.
     *
     * Strategy:
     * - Strip head code fences.
     * - Scan for a '{' that can form a valid matching '}' boundary.
     * - Return the first non-trivial object slice.
     */
    private fun sliceLikelyJsonObject(text: String): String? {
        val t = stripCodeFenceAtHead(text).trim()
        if (t.isEmpty()) return null

        var i = 0
        while (i < t.length) {
            if (t[i] == '{') {
                val end = findMatchingJsonBoundary(t, i)
                if (end != -1) {
                    val s = t.substring(i, end + 1)
                    if (!isEmptyJsonObject(s)) return s
                    i = end
                }
            }
            i++
        }
        return null
    }

    /**
     * Parse EVAL JSON output robustly.
     *
     * Priority:
     * 1) Existing FollowupExtractor on the base text
     * 2) Slice JSON object and parse via org.json
     *
     * Returns: Triple(score, followups, usedPathTag)
     */
    private fun parseEvalJsonRobust(text: String): Triple<Int?, List<String>, String> {
        // 1) Primary: existing extractor
        runCatching {
            val s = FollowupExtractor.extractScore(text)
            val f = FollowupExtractor.fromRaw(text, max = 3)
            if (s != null || f.isNotEmpty()) {
                return Triple(s, f, "FollowupExtractor(base)")
            }
        }.onFailure { t ->
            if (DEBUG_PARSE_FALLBACK) Log.w(TAG, "parseEvalJsonRobust: FollowupExtractor(base) failed: ${t::class.java.simpleName}:${t.message}")
        }

        // 2) Fallback: slice JSON and parse with JSONObject
        val slice = sliceLikelyJsonObject(text)
        if (slice != null) {
            val (s2, f2) = extractEvalFromJsonObject(slice)
            if (s2 != null || f2.isNotEmpty()) {
                return Triple(s2, f2, "JSONObject(slice)")
            }
        }

        // 3) Last attempt: try JSONObject on the whole text (if it is exactly JSON)
        val (s3, f3) = extractEvalFromJsonObject(text)
        return Triple(s3, f3, "JSONObject(base)")
    }

    /**
     * Extract (score, followups) from an EVAL JSON object using multiple key spellings.
     */
    private fun extractEvalFromJsonObject(jsonText: String): Pair<Int?, List<String>> {
        return runCatching {
            val obj = JSONObject(jsonText)

            fun optIntFlexible(vararg keys: String): Int? {
                for (k in keys) {
                    if (!obj.has(k)) continue
                    val v = obj.opt(k) ?: continue
                    when (v) {
                        is Number -> return v.toInt()
                        is String -> v.trim().toIntOrNull()?.let { return it }
                    }
                }
                return null
            }

            fun optStringListFlexible(vararg keys: String): List<String> {
                for (k in keys) {
                    if (!obj.has(k)) continue
                    val v = obj.opt(k) ?: continue
                    when (v) {
                        is JSONArray -> {
                            val out = mutableListOf<String>()
                            for (i in 0 until v.length()) {
                                val s = v.optString(i, "").trim()
                                if (s.isNotBlank()) out.add(s)
                            }
                            if (out.isNotEmpty()) return out
                        }
                        is String -> {
                            val s = v.trim()
                            if (s.isNotBlank()) return listOf(s)
                        }
                        else -> {
                            val s = v.toString().trim()
                            if (s.isNotBlank()) return listOf(s)
                        }
                    }
                }
                return emptyList()
            }

            val score = optIntFlexible(
                "score",
                "Score",
                "evaluation_score",
                "eval_score",
                "final_score",
                "rating"
            )

            val followups = optStringListFlexible(
                "followups",
                "follow_up_questions",
                "follow_up",
                "follow_up_question",
                "followup_question",
                "questions",
                "next_questions"
            )

            score to followups
        }.getOrElse { t ->
            if (DEBUG_PARSE_FALLBACK) Log.w(TAG, "extractEvalFromJsonObject failed: ${t::class.java.simpleName}:${t.message}")
            null to emptyList()
        }
    }

    /** Extract follow-up question from JSON using multiple key spellings (supports arrays too). */
    private fun extractFollowupFromJsonObject(jsonText: String): String? {
        return runCatching {
            val obj = JSONObject(jsonText)

            fun pickStringOrFirstArrayString(vararg keys: String): String? {
                for (k in keys) {
                    if (!obj.has(k)) continue
                    val vAny = obj.opt(k) ?: continue
                    when (vAny) {
                        is String -> {
                            val v = vAny.trim()
                            if (v.isNotBlank()) return v
                        }
                        is JSONArray -> {
                            for (i in 0 until vAny.length()) {
                                val s = vAny.optString(i, "").trim()
                                if (s.isNotBlank()) return s
                            }
                        }
                        else -> {
                            val s = vAny.toString().trim()
                            if (s.isNotBlank()) return s
                        }
                    }
                }
                return null
            }

            val raw = pickStringOrFirstArrayString(
                "follow_up_question",
                "followup_question",
                "follow-up question",
                "follow-up_question",
                "followUpQuestion",
                "followups",
                "follow_up",
                "followup",
                "question"
            ) ?: return@runCatching null

            val normalized = normalizeLine(raw)
            val cand = stripMetaPrefixIfAny(normalized) ?: normalized
            cand.takeIf { isLikelyPromptLine(it) }
        }.getOrNull()
    }

    /**
     * Strip Markdown code fences from the response ONLY when the text starts with "```".
     */
    private fun stripCodeFenceAtHead(text: String): String {
        val t = text.trim()
        if (!t.startsWith("```")) return t
        val last = t.lastIndexOf("```")
        if (last <= 3) return t
        val firstNewline = t.indexOf('\n', startIndex = 3)
        val contentStart = if (firstNewline == -1) 3 else firstNewline + 1
        return t.substring(contentStart, last).trim()
    }

    /**
     * Find the matching JSON boundary for an object starting at [start].
     *
     * This is a minimal brace matcher that respects string literals and escapes.
     */
    private fun findMatchingJsonBoundary(text: String, start: Int): Int {
        if (start !in text.indices) return -1
        if (text[start] != '{') return -1

        var depth = 1
        var i = start + 1
        var inString = false

        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\' && i + 1 < text.length) {
                    i += 2
                    continue
                }
                if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }
}

/* ───────────────────────── Events ───────────────────────── */

sealed interface AiEvent {
    data class Stream(val chunk: String) : AiEvent

    data class Final(
        val raw: String,
        val score: Int?,
        val followups: List<String>
    ) : AiEvent

    data object Cancelled : AiEvent
    data object Timeout : AiEvent
    data class Error(val message: String) : AiEvent
}
