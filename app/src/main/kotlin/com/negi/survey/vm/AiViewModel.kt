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
import com.negi.survey.AppRingLogStore
import com.negi.survey.BuildConfig
import com.negi.survey.net.RuntimeLogStore
import com.negi.survey.slm.FollowupExtractor
import com.negi.survey.slm.PromptPhase
import com.negi.survey.slm.Repository
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
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
 *
 * Persistent chat model (B plan, 2026-02):
 * - Chat history + conversation state are stored per contextKey:
 *   e.g. "sid=<surveySessionId>|nid=<nodeId>".
 * - Survives navigation/back while this ViewModel instance is alive.
 * - Does NOT survive process death (use DB/files if needed).
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

        /** Debug toggles (must NOT be const if referencing BuildConfig). */
        private val DEBUG_LOGS: Boolean = BuildConfig.DEBUG
        private val DEBUG_WHITESPACE: Boolean = BuildConfig.DEBUG
        private const val DEBUG_PREVIEW_CHARS = 240

        private const val DEFAULT_TIMEOUT_MS = 120_000L

        /** Prevent unbounded memory growth in UI history. */
        private const val MAX_STEP_HISTORY = 12

        /** Persisted chat max items per context key. */
        private const val MAX_CHAT_ITEMS = 260

        /** Upper bound for number of context keys to keep (best-effort LRU). */
        private const val MAX_CONTEXT_KEYS = 24

        /**
         * Reduce UI churn / O(n^2) string concatenation.
         * Stream is emitted in chunks of at least this many newly appended chars.
         */
        private const val STREAM_EMIT_MIN_DELTA_CHARS = 96

        /** Logcat chunk size to avoid line truncation. */
        private const val LOG_CHUNK = 3_200

        /** Hard cap for long logs to avoid gigantic spam. */
        private const val MAX_LONG_LOG_CHARS = 120_000
    }

    // ───────────────────────── Logging bridge ─────────────────────────

    /**
     * Emit logs both to RuntimeLogStore (existing pipeline) and AppRingLogStore (crash-uploadable ring).
     *
     * Notes:
     * - AppRingLogStore is a best-effort sink; if not installed yet, calls are no-ops.
     * - Full prompt / full raw output logs must be gated by BuildConfig.DEBUG.
     */
    private fun logD(tag: String, msg: String) {
        RuntimeLogStore.d(tag, msg)
        AppRingLogStore.log("D", tag, msg)
    }

    private fun logI(tag: String, msg: String) {
        RuntimeLogStore.i(tag, msg)
        AppRingLogStore.log("I", tag, msg)
    }

    private fun logW(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) RuntimeLogStore.w(tag, msg, tr) else RuntimeLogStore.w(tag, msg)
        AppRingLogStore.log("W", tag, msg, tr)
    }

    private fun logE(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) RuntimeLogStore.e(tag, msg, tr) else RuntimeLogStore.e(tag, msg)
        AppRingLogStore.log("E", tag, msg, tr)
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
     * - EVAL_JSON: expects JSON-like output with score + follow-up candidates.
     * - FOLLOWUP_JSON_OR_TEXT: expects either JSON (preferred) OR raw text as follow-up question.
     */
    enum class EvalMode {
        EVAL_JSON,
        FOLLOWUP_JSON_OR_TEXT
    }

    /**
     * Immutable record for a completed step to render both Step1 and Step2 in UI.
     *
     * @param runId Internal run id.
     * @param phase Prompt phase for this step (Repo-level PromptPhase).
     * @param mode Parse mode used.
     * @param raw Final raw output for this step (may be partial on timeout).
     * @param score Parsed score (only meaningful for EVAL_JSON).
     * @param followups Extracted follow-ups (top-3, or single follow-up for FOLLOWUP mode).
     * @param timedOut True if request timed out.
     * @param error Error string (if any).
     */
    data class StepSnapshot(
        val runId: Long,
        val phase: PromptPhase,
        val mode: EvalMode,
        val raw: String,
        val score: Int?,
        val followups: List<String>,
        val timedOut: Boolean,
        val error: String?
    )

    private val _stepHistory = MutableStateFlow<List<StepSnapshot>>(emptyList())

    /** Completed steps in order (keeps Step1 + Step2). */
    val stepHistory: StateFlow<List<StepSnapshot>> = _stepHistory.asStateFlow()

    /** Clear step history (typically at the start of a new independent run/chain). */
    private fun clearStepHistory() {
        _stepHistory.value = emptyList()
    }

    /** Append one snapshot to history with an upper bound to prevent memory bloat. */
    private fun appendStepSnapshot(s: StepSnapshot) {
        _stepHistory.update { cur ->
            val next = cur + s
            if (next.size <= MAX_STEP_HISTORY) next else next.takeLast(MAX_STEP_HISTORY)
        }
        if (DEBUG_LOGS) {
            val fu0 = s.followups.firstOrNull()?.let { preview(it) } ?: "<none>"
            logD(
                TAG,
                "stepHistory+ runId=${s.runId} phase=${s.phase} mode=${s.mode} " +
                        "raw.len=${s.raw.length} score=${s.score} FU=${s.followups.size} " +
                        "FU0='${debugVisible(fu0)}' timeout=${s.timedOut} err=${s.error}"
            )
        }
    }

    // ─────────────────────── Persistent chat (B plan) ───────────────────────

    /** Sender for persisted chat items. */
    enum class ChatSender {
        USER,
        AI
    }

    /**
     * Persisted chat item (UI-agnostic).
     *
     * @param id Stable id for upsert/dedup.
     * @param sender Sender type.
     * @param text Plain text payload.
     * @param json JSON payload (pretty string).
     * @param isTyping True when this is a streaming typing placeholder.
     * @param createdAtUptimeMs Monotonic timestamp for ordering/diagnostics.
     */
    data class ChatItem(
        val id: String,
        val sender: ChatSender,
        val text: String? = null,
        val json: String? = null,
        val isTyping: Boolean = false,
        val createdAtUptimeMs: Long = SystemClock.uptimeMillis()
    )

    /** Composer role per conversation context. */
    enum class ComposerRole {
        MAIN,
        FOLLOWUP
    }

    /**
     * Persisted conversation state per context key.
     *
     * @param role Current composer role.
     * @param activePromptQuestion The question that should be answered next.
     * @param composerDraft Draft text for restoration after navigation.
     */
    data class ConversationState(
        val role: ComposerRole,
        val activePromptQuestion: String,
        val composerDraft: String
    )

    private val chatStore = ConcurrentHashMap<String, MutableStateFlow<List<ChatItem>>>()
    private val conversationStore = ConcurrentHashMap<String, MutableStateFlow<ConversationState>>()
    private val followupSeen = ConcurrentHashMap<String, MutableSet<String>>()

    /** Best-effort LRU of context keys to prevent runaway memory usage. */
    private val contextKeyOrder = ArrayDeque<String>()

    private val msgSeq = AtomicLong(0L)

    /**
     * Return persisted chat history flow for [contextKey].
     */
    fun chatHistoryFlow(contextKey: String): StateFlow<List<ChatItem>> {
        touchContextKey(contextKey)
        return chatStore.getOrPut(contextKey) { MutableStateFlow(emptyList()) }.asStateFlow()
    }

    /**
     * Return persisted conversation state flow for [contextKey].
     */
    fun conversationStateFlow(contextKey: String): StateFlow<ConversationState> {
        touchContextKey(contextKey)
        return conversationStore.getOrPut(contextKey) {
            MutableStateFlow(
                ConversationState(
                    role = ComposerRole.MAIN,
                    activePromptQuestion = "",
                    composerDraft = ""
                )
            )
        }.asStateFlow()
    }

    /**
     * Ensure a conversation context exists and is initialized.
     *
     * Notes:
     * - This is safe to call multiple times.
     * - It only seeds missing fields; it won't clobber existing role/draft.
     */
    fun ensureConversationContext(
        contextKey: String,
        rootQuestion: String,
        initialDraft: String
    ) {
        touchContextKey(contextKey)

        conversationStore.computeIfAbsent(contextKey) {
            MutableStateFlow(
                ConversationState(
                    role = ComposerRole.MAIN,
                    activePromptQuestion = rootQuestion,
                    composerDraft = initialDraft
                )
            )
        }

        conversationStore[contextKey]?.update { cur ->
            val apq = cur.activePromptQuestion.ifBlank { rootQuestion }
            val draft = if (cur.composerDraft.isBlank() && initialDraft.isNotBlank()) initialDraft else cur.composerDraft
            cur.copy(activePromptQuestion = apq, composerDraft = draft)
        }
    }

    /**
     * If the role is MAIN, ensure activePromptQuestion points to [rootQuestion].
     */
    fun ensureActivePromptIfMain(contextKey: String, rootQuestion: String) {
        conversationStore[contextKey]?.update { cur ->
            if (cur.role != ComposerRole.MAIN) cur
            else cur.copy(activePromptQuestion = rootQuestion)
        }
    }

    /**
     * Update draft text for [contextKey].
     */
    fun updateComposerDraft(contextKey: String, draft: String) {
        conversationStore[contextKey]?.update { cur ->
            if (cur.composerDraft == draft) cur else cur.copy(composerDraft = draft)
        }
    }

    /**
     * Switch to FOLLOWUP mode and set active prompt question.
     */
    fun setFollowupMode(contextKey: String, followupQuestion: String) {
        val q = followupQuestion.trim()
        if (q.isBlank()) return
        conversationStore[contextKey]?.update { cur ->
            cur.copy(role = ComposerRole.FOLLOWUP, activePromptQuestion = q)
        }
    }

    /**
     * Ensure the root question message exists and stays current.
     */
    fun ensureRootQuestionMessage(contextKey: String, nodeId: String, rootQuestion: String) {
        val id = "qroot-$nodeId"
        upsertChatItem(
            contextKey = contextKey,
            item = ChatItem(
                id = id,
                sender = ChatSender.AI,
                text = rootQuestion
            )
        )
    }

    /**
     * Append a user message to the persisted chat.
     */
    fun appendUserMessage(contextKey: String, text: String): String {
        val id = "u-${msgSeq.incrementAndGet()}"
        appendChatItem(
            contextKey = contextKey,
            item = ChatItem(
                id = id,
                sender = ChatSender.USER,
                text = text
            )
        )
        return id
    }

    /**
     * Upsert the typing bubble for a node.
     */
    fun upsertTypingMessage(contextKey: String, nodeId: String, text: String) {
        val id = "typing-$nodeId"
        upsertChatItem(
            contextKey = contextKey,
            item = ChatItem(
                id = id,
                sender = ChatSender.AI,
                text = text,
                isTyping = true
            )
        )
    }

    /**
     * Remove the typing bubble for a node.
     */
    fun removeTypingMessage(contextKey: String, nodeId: String) {
        val id = "typing-$nodeId"
        removeChatItem(contextKey, id)
    }

    /**
     * Upsert a chat item by id.
     */
    fun upsertChatItem(contextKey: String, item: ChatItem) {
        touchContextKey(contextKey)
        val flow = chatStore.getOrPut(contextKey) { MutableStateFlow(emptyList()) }
        flow.update { cur ->
            val idx = cur.indexOfFirst { it.id == item.id }
            val next = if (idx == -1) {
                cur + item
            } else {
                cur.toMutableList().also { it[idx] = item }.toList()
            }
            next.takeLast(MAX_CHAT_ITEMS)
        }
    }

    /**
     * Append a chat item (always adds; no dedup).
     */
    fun appendChatItem(contextKey: String, item: ChatItem) {
        touchContextKey(contextKey)
        val flow = chatStore.getOrPut(contextKey) { MutableStateFlow(emptyList()) }
        flow.update { cur ->
            (cur + item).takeLast(MAX_CHAT_ITEMS)
        }
    }

    /**
     * Remove a chat item by id.
     */
    fun removeChatItem(contextKey: String, id: String) {
        val flow = chatStore[contextKey] ?: return
        flow.update { cur -> cur.filterNot { it.id == id } }
    }

    /**
     * Mark follow-up question as seen. Returns true if it is newly added.
     */
    fun markFollowupSeen(contextKey: String, followupQuestion: String): Boolean {
        val norm = followupQuestion.trim()
        if (norm.isBlank()) return false
        val set = followupSeen.getOrPut(contextKey) { Collections.synchronizedSet(mutableSetOf()) }
        return set.add(norm)
    }

    /**
     * Clear one persisted conversation (chat + state) for [contextKey].
     */
    fun clearConversation(contextKey: String) {
        chatStore.remove(contextKey)
        conversationStore.remove(contextKey)
        followupSeen.remove(contextKey)
        synchronized(contextKeyOrder) {
            contextKeyOrder.remove(contextKey)
        }
    }

    /**
     * Best-effort LRU maintenance of context keys.
     */
    private fun touchContextKey(contextKey: String) {
        synchronized(contextKeyOrder) {
            if (contextKeyOrder.contains(contextKey)) {
                contextKeyOrder.remove(contextKey)
            }
            contextKeyOrder.addLast(contextKey)

            while (contextKeyOrder.size > MAX_CONTEXT_KEYS) {
                val evict = if (contextKeyOrder.isEmpty()) null else contextKeyOrder.removeFirst()
                if (evict != null) {
                    chatStore.remove(evict)
                    conversationStore.remove(evict)
                    followupSeen.remove(evict)
                    if (DEBUG_LOGS) logD(TAG, "chatStore evicted contextKey='$evict'")
                }
            }
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
     *
     * @param runId Internal run identifier.
     * @param raw Final raw output (may be partial on timeout).
     * @param score Parsed score.
     * @param followups Extracted follow-ups (top-3).
     * @param timedOut True if the request timed out.
     */
    data class EvalResult(
        val runId: Long,
        val raw: String,
        val score: Int?,
        val followups: List<String>,
        val timedOut: Boolean
    )

    suspend fun evaluate(prompt: String, timeoutMs: Long = defaultTimeoutMs): Int? {
        if (prompt.isBlank()) {
            logI(TAG, "evaluate: blank prompt -> reset states and return null")
            resetStates(keepError = false)
            return null
        }

        if (!running.compareAndSet(false, true)) {
            logW(TAG, "evaluate: already running -> returning current score=${_score.value}")
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
            job.join()
        }

        logD(TAG, "evaluate: finished in ${elapsed}ms, score=${_score.value}, err=${_error.value}")
        return _score.value
    }

    fun evaluateAsync(prompt: String, timeoutMs: Long = defaultTimeoutMs): Job {
        if (prompt.isBlank()) {
            resetStates(keepError = false)
            return viewModelScope.launch { }
        }

        if (!running.compareAndSet(false, true)) {
            logW(TAG, "evaluateAsync: already running -> returning existing job")
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
            logW(TAG, "evaluateConditionalTwoStepAsync: already running -> returning existing job")
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
                    if (DEBUG_LOGS) logW(TAG, "chain2: step1 timed out -> skipping step2 (proceedOnTimeout=false)")
                    return@launch
                }

                val doStep2 = runCatching { shouldRunSecond(step1) }
                    .onFailure { t -> logE(TAG, "chain2: shouldRunSecond failed -> treat as false", t) }
                    .getOrElse { false }

                if (!doStep2) {
                    if (DEBUG_LOGS) {
                        logD(
                            TAG,
                            "chain2: step2 skipped (score=${step1.score}, followups=${step1.followups.size}, timedOut=${step1.timedOut}, rawPreview='${debugVisible(preview(step1.raw))}')"
                        )
                    }
                    return@launch
                }

                // --- step 2 (FOLLOWUP; JSON or raw text) ---
                val p2 = runCatching { buildSecondPrompt(step1).trim() }
                    .onFailure { t -> logE(TAG, "chain2: buildSecondPrompt failed", t) }
                    .getOrElse { "" }

                if (p2.isEmpty()) {
                    if (DEBUG_LOGS) logW(TAG, "chain2: step2 prompt is blank -> done")
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

    fun cancel() {
        logI(TAG, "cancel: invoked (isRunning=${running.get()}, loading=${_loading.value})")
        stopCurrentRunInternal(reason = "cancelled", emitCancelledEvent = true, setCancelledError = true)
    }

    /**
     * Reset transient AI-related states.
     *
     * Notes:
     * - Clears stepHistory/stream/raw/score/etc (inference UI state).
     * - DOES NOT clear persisted chat/conversation state (B plan).
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
        logI(TAG, "onCleared: ViewModel is being cleared -> stopCurrentRunInternal()")
        super.onCleared()
        stopCurrentRunInternal(reason = "cleared", emitCancelledEvent = false, setCancelledError = false)
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

    private suspend fun runEvaluationCore(
        runId: Long,
        userPrompt: String,
        timeoutMs: Long,
        mode: EvalMode,
        phase: PromptPhase,
        commitToPrimaryState: Boolean
    ): EvalResult {
        val buf = StringBuilder()
        var chunkCount = 0
        var totalChars = 0
        var timedOut = false
        var lastStreamEmitLen = 0

        val enableFullLogs = BuildConfig.DEBUG && DEBUG_LOGS

        fun isActiveRun(): Boolean = activeRunId.get() == runId

        fun maybeEmitStream(force: Boolean = false) {
            val delta = buf.length - lastStreamEmitLen
            if (!force && delta < STREAM_EMIT_MIN_DELTA_CHARS) return
            lastStreamEmitLen = buf.length
            _stream.value = buf.toString()
        }

        fun isEmptyJsonObject(text: String): Boolean {
            val t = text.trim()
            return t == "{}" || t == "{ }"
        }

        fun isJsonLike(text: String): Boolean {
            val t = text.trim()
            return t.startsWith("{") || t.startsWith("[")
        }

        fun sanitizeFollowups(list: List<String>): List<String> {
            return list
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .filterNot { isEmptyJsonObject(it) }
                .filterNot { isJsonLike(it) }
                .distinct()
                .take(3)
                .toList()
        }

        fun extractFollowupFromPlainText(rawText: String): String? {
            val t = rawText.trim()
            if (t.isBlank()) return null
            if (isEmptyJsonObject(t)) return null

            val unquoted = t.removePrefix("\"").removeSuffix("\"").trim()
            val lines = unquoted.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()

            val qLine = lines.firstOrNull { it.contains("?") || it.contains("？") }
            return qLine ?: lines.firstOrNull()
        }

        var stepError: String? = null

        try {
            val fullPrompt = runCatching { repo.buildPrompt(userPrompt, phase) }
                .onFailure { t ->
                    logE(TAG, "run[$runId]: repo.buildPrompt failed; falling back to userPrompt", t)
                }
                .getOrElse { userPrompt }

            if (DEBUG_LOGS) {
                logD(
                    TAG,
                    "run[$runId]: mode=$mode phase=$phase commit=$commitToPrimaryState " +
                            "prompt.len=${userPrompt.length}, fullPrompt.len=${fullPrompt.length}, timeoutMs=$timeoutMs"
                )
                logD(TAG, "run[$runId]: sha(prompt)=${sha256Hex(userPrompt)} sha(full)=${sha256Hex(fullPrompt)}")
            }

            if (enableFullLogs) {
                logLong(
                    tag = FULL_PROMPT_TAG,
                    header = "run[$runId]: phase=$phase FullPrompt",
                    body = fullPrompt,
                    level = Log.INFO
                )
            }

            try {
                withTimeout(timeoutMs) {
                    repo.request(fullPrompt).collect { part ->
                        if (!isActiveRun()) {
                            throw CancellationException("stale-run")
                        }

                        if (part.isNotEmpty()) {
                            chunkCount++
                            buf.append(part)
                            totalChars += part.length

                            maybeEmitStream(force = false)
                            _events.tryEmit(AiEvent.Stream(part))

                            if (DEBUG_LOGS) {
                                logD(TAG, "run[$runId] chunk[$chunkCount].preview='${debugVisible(preview(part))}'")
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                timedOut = true
                stepError = "timeout"
                if (DEBUG_LOGS) logW(TAG, "run[$runId]: timeout after ${timeoutMs}ms", e)
            } catch (e: CancellationException) {
                if (!isActiveRun()) {
                    if (DEBUG_LOGS) logW(TAG, "run[$runId]: cancelled (stale run) -> stop quietly")
                    return EvalResult(runId = runId, raw = "", score = null, followups = emptyList(), timedOut = false)
                }

                if (looksLikeTimeout(e)) {
                    timedOut = true
                    stepError = "timeout"
                    if (DEBUG_LOGS) logW(TAG, "run[$runId]: timeout-like cancellation (${e.javaClass.name})")
                } else {
                    throw e
                }
            }

            if (!isActiveRun()) {
                return EvalResult(runId = runId, raw = "", score = null, followups = emptyList(), timedOut = timedOut)
            }

            maybeEmitStream(force = true)

            val rawText = buf.toString().ifBlank { _stream.value }
            val rawTrim = rawText.trim()

            if (DEBUG_LOGS) {
                logD(TAG, "run[$runId] stats: chunks=$chunkCount, chars=$totalChars, raw.len=${rawText.length}")
                logD(TAG, "run[$runId] sha(raw)=${sha256Hex(rawText)}")
            }
            if (DEBUG_LOGS && DEBUG_WHITESPACE) {
                logD(TAG, "run[$runId] rawVisible='${debugVisible(preview(rawText))}'")
            }

            val parsedScore: Int?
            val top3: List<String>
            val q0: String?

            when (mode) {
                EvalMode.EVAL_JSON -> {
                    if (rawTrim.isBlank() || isEmptyJsonObject(rawTrim)) {
                        parsedScore = null
                        top3 = emptyList()
                        q0 = null
                        if (DEBUG_LOGS) {
                            logW(
                                TAG,
                                "run[$runId]: EVAL_JSON output is empty/trivial ('${debugVisible(preview(rawTrim))}') -> score=null, followups=0"
                            )
                        }
                    } else {
                        val s1 = clampScore(FollowupExtractor.extractScore(rawText))
                        val f1 = sanitizeFollowups(FollowupExtractor.fromRaw(rawText, max = 6))

                        parsedScore = s1
                        top3 = f1.take(3)
                        q0 = top3.firstOrNull()

                        if (DEBUG_LOGS) {
                            logD(
                                TAG,
                                "run[$runId]: EVAL_JSON parsed score=$parsedScore followups=${top3.size} fu0='${debugVisible(preview(q0.orEmpty()))}'"
                            )
                        }
                    }
                }

                EvalMode.FOLLOWUP_JSON_OR_TEXT -> {
                    val fromExtractor = FollowupExtractor.extractFollowupQuestion(rawText)
                    val fromText = extractFollowupFromPlainText(rawText)

                    val best = (fromExtractor ?: fromText)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.takeIf { !isEmptyJsonObject(it) }
                        ?.takeIf { !isJsonLike(it) }

                    parsedScore = null
                    top3 = best?.let { listOf(it) } ?: emptyList()
                    q0 = best

                    if (DEBUG_LOGS) {
                        logD(
                            TAG,
                            "run[$runId]: FOLLOWUP parse " +
                                    "extractorQ='${debugVisible(preview(fromExtractor.orEmpty()))}' " +
                                    "textQ='${debugVisible(preview(fromText.orEmpty()))}' " +
                                    "best='${debugVisible(preview(best.orEmpty()))}'"
                        )
                    }
                }
            }

            _error.value = stepError

            appendStepSnapshot(
                StepSnapshot(
                    runId = runId,
                    phase = phase,
                    mode = mode,
                    raw = rawText,
                    score = parsedScore,
                    followups = top3,
                    timedOut = timedOut,
                    error = stepError
                )
            )

            if (commitToPrimaryState) {
                _raw.value = rawText
                _score.value = parsedScore
                _followups.value = top3
                _followupQuestion.value = q0
            }

            _events.tryEmit(AiEvent.Final(rawText, parsedScore, top3))

            if (timedOut) {
                _events.tryEmit(AiEvent.Timeout)
            }

            logI(
                TAG,
                "run[$runId] done: phase=$phase mode=$mode score=$parsedScore FU[0]=${q0 ?: "<none>"} commit=$commitToPrimaryState err=${stepError ?: "<none>"}"
            )

            if (enableFullLogs) {
                logLong(
                    tag = FULL_TEXT_OUT_TAG,
                    header = "run[$runId]: RawTextOut",
                    body = rawText,
                    level = Log.INFO
                )
            }

            return EvalResult(runId = runId, raw = rawText, score = parsedScore, followups = top3, timedOut = timedOut)
        } catch (e: CancellationException) {
            if (isActiveRun() && _error.value == "cancelled") {
                _events.tryEmit(AiEvent.Cancelled)
            }
            if (DEBUG_LOGS) logW(TAG, "run[$runId]: cancelled", e)
            throw e
        } catch (t: Throwable) {
            if (!isActiveRun()) {
                return EvalResult(runId = runId, raw = "", score = null, followups = emptyList(), timedOut = false)
            }

            val msg = t.message ?: "error"
            _error.value = msg
            _events.tryEmit(AiEvent.Error(msg))
            logE(TAG, "run[$runId]: error", t)

            val rawText = _stream.value

            appendStepSnapshot(
                StepSnapshot(
                    runId = runId,
                    phase = phase,
                    mode = mode,
                    raw = rawText,
                    score = null,
                    followups = emptyList(),
                    timedOut = false,
                    error = msg
                )
            )

            _events.tryEmit(AiEvent.Final(rawText, _score.value, _followups.value))

            return EvalResult(
                runId = runId,
                raw = rawText,
                score = _score.value,
                followups = _followups.value,
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
        _error.value = null
        if (clearHistory) clearStepHistory()
    }

    private fun prepareUiForNextStep() {
        _loading.value = true
        _stream.value = ""
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
                .onFailure { t -> logW(TAG, "stopCurrentRunInternal: exception during cancel (ignored)", t) }
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
            .onFailure { t -> logW(TAG, "cancelDanglingJobIfAny: exception during cancel (ignored)", t) }
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

    private fun preview(s: String): String {
        if (s.isEmpty()) return ""
        val n = min(DEBUG_PREVIEW_CHARS, s.length)
        return s.take(n)
    }

    /**
     * Chunked log printer to reduce logcat truncation.
     *
     * Notes:
     * - Caller must gate sensitive payloads with BuildConfig.DEBUG.
     */
    private fun logLong(tag: String, header: String, body: String, level: Int) {
        val trimmed = if (body.length > MAX_LONG_LOG_CHARS) {
            body.take(MAX_LONG_LOG_CHARS) + "\n... (truncated)"
        } else {
            body
        }

        val full = buildString {
            if (header.isNotBlank()) appendLine(header)
            append(trimmed)
        }

        var i = 0
        var part = 0
        while (i < full.length) {
            val end = min(full.length, i + LOG_CHUNK)
            val slice = full.substring(i, end)
            val prefix = "[part=${part.toString().padStart(3, '0')}] "
            when (level) {
                Log.ERROR -> logE(tag, prefix + slice)
                Log.WARN -> logW(tag, prefix + slice)
                else -> logI(tag, prefix + slice)
            }
            i = end
            part++
        }
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