/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AiRepository.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.slm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Message
import com.negi.survey.BuildConfig
import com.negi.survey.config.SurveyConfig
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Prompt building phase for two-step pipelines.
 *
 * - ONE_STEP: legacy single call
 * - EVAL: step-1 evaluation
 * - FOLLOWUP: step-2 follow-up generation
 */
enum class PromptPhase {
    ONE_STEP,
    EVAL,
    FOLLOWUP
}

/**
 * Repository that streams inference results from an on-device LLM backend.
 *
 * Contract:
 * - Returns a cold [Flow]. Collection actually runs the inference.
 * - Implementations may enforce process-wide serialization (e.g., via a semaphore).
 * - Callers are expected to collect in a coroutine scope they control and cancel
 *   collection to abort/cleanup the underlying engine call (best-effort).
 */
interface Repository {

    /** Execute a single streaming inference for the given [prompt]. */
    suspend fun request(prompt: String): Flow<String>

    /** Build the full model-ready prompt string from a user-level [userPrompt]. */
    fun buildPrompt(userPrompt: String): String

    /**
     * Build the full model-ready prompt string for a specific [phase].
     *
     * Note:
     * - Default implementation keeps backward compatibility by delegating to the legacy method.
     */
    fun buildPrompt(userPrompt: String, phase: PromptPhase): String = buildPrompt(userPrompt)

    /**
     * Warm up / pre-initialize the backend so the first real request doesn't pay cold-start.
     *
     * Default: no-op for backends that don't require it.
     */
    suspend fun warmUp() {}
}

/* ====================================================================== */
/*  Shared process-wide inference gate                                     */
/* ====================================================================== */

/**
 * Single process-wide gate used by all backends.
 *
 * Semantics:
 * - At most one active inference-related critical section may run at once.
 * - We also reuse this gate for warm-up to guarantee strict serialization.
 */
private val AI_INFERENCE_GATE = Semaphore(1)

/* ====================================================================== */
/*  Logging / Trace utilities                                              */
/* ====================================================================== */

private object AiTrace {

    private const val TAG = "AiTrace"

    /** Max chars kept in-memory for full output capture (safety cap). */
    private const val MAX_CAPTURE_CHARS: Int = 250_000

    /** Max chars we will attempt to print to logcat via chunked logging. */
    private const val MAX_LOGCAT_CHARS: Int = 120_000

    /** Chunk size per log line (keep below Logcat line limit). */
    private const val LOG_CHUNK: Int = 3_200

    @Volatile
    private var appContext: Context? = null

    /** Enables verbose prompt/output tracing. */
    private val ENABLED_DEFAULT: Boolean = BuildConfig.DEBUG

    @Volatile
    var enabled: Boolean = ENABLED_DEFAULT

    /**
     * Install an application context for optional file dumps.
     *
     * Call once early, e.g. MainActivity.onCreate():
     *   AiTrace.install(applicationContext)
     */
    fun install(context: Context) {
        appContext = context.applicationContext
        Log.d(TAG, "Installed (enabled=$enabled)")
    }

    /**
     * Append with hard cap; returns false when truncated.
     */
    fun capAppend(sb: StringBuilder, chunk: String): Boolean {
        if (sb.length >= MAX_CAPTURE_CHARS) return false
        val remaining = MAX_CAPTURE_CHARS - sb.length
        if (chunk.length <= remaining) {
            sb.append(chunk)
            return true
        }
        sb.append(chunk.substring(0, remaining))
        return false
    }

    /**
     * Short stable hash for prompt/output fingerprinting.
     */
    fun sha256Short(text: String): String {
        return runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
            bytes.take(8).joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        }.getOrElse { "sha256_err" }
    }

    /**
     * Chunked logcat printer to avoid line truncation.
     */
    fun logLong(tag: String, level: Int, header: String, body: String) {
        if (!enabled) return

        val full = if (body.length > MAX_LOGCAT_CHARS) {
            body.take(MAX_LOGCAT_CHARS) + "\n... (logcat truncated; consider file dump)"
        } else {
            body
        }

        val lines = buildString {
            if (header.isNotBlank()) appendLine(header)
            append(full)
        }

        var i = 0
        var part = 0
        while (i < lines.length) {
            val end = kotlin.math.min(lines.length, i + LOG_CHUNK)
            val slice = lines.substring(i, end)
            val prefix = "[part=${part.toString().padStart(3, '0')}] "
            when (level) {
                Log.ERROR -> Log.e(tag, prefix + slice)
                Log.WARN -> Log.w(tag, prefix + slice)
                else -> Log.d(tag, prefix + slice)
            }
            i = end
            part++
        }
    }

    /**
     * Best-effort dump into app-private storage:
     *   files/diagnostics/llm_trace/
     */
    fun dumpToFile(kind: String, requestId: Long, modelName: String, text: String): File? {
        if (!enabled) return null
        val ctx = appContext ?: return null

        return runCatching {
            val dir = File(ctx.filesDir, "diagnostics/llm_trace").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val safeModel = modelName.replace(Regex("""[^A-Za-z0-9_.-]"""), "_")
            val f = File(dir, "${kind}_${stamp}_rid${requestId}_${safeModel}.txt")
            f.writeText(text, Charsets.UTF_8)
            f
        }.onFailure { e ->
            Log.w(TAG, "dumpToFile failed: ${e.message}", e)
        }.getOrNull()
    }
}

/* ====================================================================== */
/*  Shared prompt utilities                                                */
/* ====================================================================== */

private fun String.normalizePrompt(): String =
    replace("\r\n", "\n")
        .replace("\r", "\n")
        .trimEnd('\n')

/* ====================================================================== */
/*  Shared defaults for prompt building                                    */
/* ====================================================================== */

private object PromptDefaults {
    const val USER_TURN_PREFIX = "<start_of_turn>user"
    const val MODEL_TURN_PREFIX = "<start_of_turn>model"
    const val TURN_END = "<end_of_turn>"
}

/* ====================================================================== */
/*  Stream normalization                                                   */
/* ====================================================================== */

/**
 * Normalizes a stream where callbacks may return:
 * - True deltas (only newly generated text)
 * - Snapshots (accumulated prefix, i.e., full text so far)
 *
 * AUTO strategy:
 * - If incoming starts with lastSnapshot => treat as snapshot and emit suffix delta.
 * - Else treat as delta and append.
 *
 * Safety:
 * - If snapshot "rewinds" (incoming shorter or unrelated), reset snapshot to incoming
 *   and emit incoming as delta (best-effort recovery).
 */
private class LocalDeltaNormalizer(
    private val mode: Mode = Mode.AUTO
) {

    enum class Mode {
        /** Always treat incoming as delta. */
        DELTA,
        /** Always treat incoming as snapshot (accumulated) and compute suffix. */
        SNAPSHOT,
        /** Detect snapshot vs delta at runtime. */
        AUTO
    }

    private val lock = Any()
    private var lastSnapshot: String = ""

    fun toDelta(incomingRaw: String): String {
        val incoming = incomingRaw
        if (incoming.isEmpty()) return ""

        return synchronized(lock) {
            when (mode) {
                Mode.DELTA -> {
                    lastSnapshot += incoming
                    incoming
                }

                Mode.SNAPSHOT -> {
                    val delta = if (incoming.startsWith(lastSnapshot)) {
                        incoming.substring(lastSnapshot.length)
                    } else {
                        incoming
                    }
                    lastSnapshot = incoming
                    delta
                }

                Mode.AUTO -> {
                    if (incoming.startsWith(lastSnapshot) && incoming.length >= lastSnapshot.length) {
                        val delta = incoming.substring(lastSnapshot.length)
                        lastSnapshot = incoming
                        delta
                    } else {
                        lastSnapshot += incoming
                        incoming
                    }
                }
            }
        }
    }
}

/* ====================================================================== */
/*  LiteRtLM backend                                                      */
/* ====================================================================== */

class LiteRtRepository(
    private val model: Model,
    private val config: SurveyConfig,
    private val appContext: Context? = null,
    private val supportImage: Boolean = false,
    private val supportAudio: Boolean = false,
    private val systemMessage: Message? = null,
    private val tools: List<Any> = emptyList(),
) : Repository {

    companion object {
        private const val TAG = "LiteRtRepository"

        private val REQ_SEQ = AtomicLong(0L)

        private const val INIT_TIMEOUT_MS = 90_000L
        private const val HARD_WATCHDOG_MS = 120_000L
        private const val FIRST_TOKEN_TIMEOUT_MS = 45_000L
        private const val EVENT_STALL_TIMEOUT_MS = 12_000L
        private const val POST_DONE_TIMEOUT_MS = 30_000L
        private const val PROGRESS_POLL_MS = 250L

        private val DEBUG_STREAM: Boolean = BuildConfig.DEBUG
        private const val DEBUG_STREAM_EVERY_N = 8
        private const val DEBUG_PREFIX_CHARS = 180

        private const val PROMPT_CHAR_CAP: Int = 120_000
        private const val PROMPT_KEEP_TAIL_CHARS: Int = 24_000

        /**
         * Shared flag used to request a safe reset before the next inference.
         * This is used when we couldn't reach a native termination safepoint.
         */
        private val FORCE_REINIT = AtomicBoolean(false)

        /**
         * Single-thread dispatcher for ALL SLM/JNI calls.
         * This often reduces native instability caused by cross-thread entry.
         */
        private val SLM_DISPATCHER by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "slm-jni").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        }
    }

    /**
     * A repository-lifetime scope that survives callbackFlow cancellation.
     *
     * Rationale:
     * - callbackFlow scope is cancelled immediately when collector cancels.
     * - We still want a best-effort SLM.cancel() to run on the SLM thread.
     */
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        appContext?.let { AiTrace.install(it) }
    }

    private fun capPromptIfNeeded(prompt: String, maxChars: Int, keepTailChars: Int): String {
        if (prompt.length <= maxChars) return prompt

        val keep = keepTailChars.coerceIn(4_096, maxChars)
        val tail = prompt.takeLast(keep)
        val dropped = prompt.length - keep

        val marker = "[TRUNCATED: dropped=$dropped chars; kept_last=$keep]\n"
        val out = marker + tail
        return if (out.length <= maxChars) out else out.takeLast(maxChars)
    }

    /**
     * Escapes reserved turn-control tokens if they appear in user-provided content.
     *
     * This prevents prompt-grammar corruption and native crashes when the input contains
     * strings such as "<end_of_turn>" literally.
     */
    private fun escapeReservedTurnTokens(text: String): String {
        var out = text
        out = out.replace(PromptDefaults.USER_TURN_PREFIX, "< start_of_turn >user")
        out = out.replace(PromptDefaults.MODEL_TURN_PREFIX, "< start_of_turn >model")
        out = out.replace(PromptDefaults.TURN_END, "< end_of_turn >")
        return out
    }

    /**
     * Runs SLM/JNI calls on a dedicated single thread.
     *
     * NOTE:
     * - The lambda must be suspend-capable because upstream SLM APIs may be suspend.
     */
    private suspend fun <T> runOnSlmThread(block: suspend () -> T): T {
        return withContext(SLM_DISPATCHER) { block() }
    }

    /**
     * Suspended-safe equivalent of runCatching { }.
     */
    private suspend inline fun <T> runCatchingSuspend(
        crossinline block: suspend () -> T
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override fun buildPrompt(userPrompt: String): String =
        buildPrompt(userPrompt, PromptPhase.ONE_STEP)

    override fun buildPrompt(userPrompt: String, phase: PromptPhase): String {
        fun normalize(s: String): String =
            s.replace("\r\n", "\n")
                .replace("\r", "\n")
                .trimEnd('\n')

        fun joinNonBlank(vararg parts: String): String =
            parts.asSequence()
                .map { normalize(it) }
                .filter { it.isNotBlank() }
                .joinToString("\n")

        val slm = config.slm

        val systemPrompt = when (phase) {
            PromptPhase.ONE_STEP -> config.composeSystemPromptOneStep()
            PromptPhase.EVAL -> config.composeSystemPromptEval()
            PromptPhase.FOLLOWUP -> config.composeSystemPromptFollowup()
        }.let(::normalize)

        val emptyJson = normalize(slm.emptyJsonInstruction ?: "")
        val effectiveInput = if (userPrompt.isBlank()) emptyJson else normalize(userPrompt.trimIndent())

        val labeledInput = joinNonBlank("INPUT:", effectiveInput)

        val fullPrompt = joinNonBlank(
            systemPrompt,
            labeledInput,
        )

        val escaped = escapeReservedTurnTokens(fullPrompt)
        return capPromptIfNeeded(escaped, PROMPT_CHAR_CAP, PROMPT_KEEP_TAIL_CHARS)
    }

    /**
     * Warm up the model initialization so the first real request doesn't hit cold-start.
     *
     * Notes:
     * - Uses the same process-wide gate as inference to guarantee strict serialization.
     * - Does NOT run inference (avoids polluting conversation state).
     * - Runs SLM calls on the dedicated SLM thread.
     */
    override suspend fun warmUp() {
        val ctx = appContext
        if (ctx == null) {
            Log.w(TAG, "warmUp skipped: appContext=null")
            return
        }

        val startedAt = SystemClock.elapsedRealtime()
        AI_INFERENCE_GATE.withPermit {
            val gateMs = SystemClock.elapsedRealtime() - startedAt
            val t0 = SystemClock.elapsedRealtime()

            val initAttempt: Result<Unit>? = withTimeoutOrNull(INIT_TIMEOUT_MS) {
                runCatchingSuspend {
                    runOnSlmThread {
                        SLM.initializeIfNeeded(
                            context = ctx,
                            model = model,
                            supportImage = supportImage,
                            supportAudio = supportAudio,
                            systemMessage = systemMessage,
                            tools = tools,
                        )
                    }
                }
            }

            val initMs = SystemClock.elapsedRealtime() - t0

            when {
                initAttempt == null -> {
                    Log.w(
                        TAG,
                        "warmUp: initializeIfNeeded timed out (${INIT_TIMEOUT_MS}ms) gateWaitMs=$gateMs initMs=$initMs"
                    )
                }
                initAttempt.isFailure -> {
                    val e = initAttempt.exceptionOrNull()
                    Log.w(TAG, "warmUp: initializeIfNeeded failed gateWaitMs=$gateMs initMs=$initMs err=${e?.message}", e)
                }
                else -> {
                    Log.d(TAG, "warmUp: initializeIfNeeded ok gateWaitMs=$gateMs initMs=$initMs")
                }
            }
        }
    }

    override suspend fun request(prompt: String): Flow<String> {
        return callbackFlow {
            val out = this

            val requestId = REQ_SEQ.incrementAndGet()
            val gateReqAt = SystemClock.elapsedRealtime()

            /**
             * External cancel / internal close coordination:
             * - internalClose: set true when *we* close the channel (normal completion or internal error).
             * - gateActive: true only while inside the withPermit critical section (this request is the active one).
             * - cancelIssued: ensures we send SLM.cancel() at most once for collector cancellation.
             * - cancelTag: shared so awaitClose can mark "collector-cancelled", preventing CANCELLED from being treated as an error.
             */
            val internalClose = AtomicBoolean(false)
            val gateActive = AtomicBoolean(false)
            val cancelIssued = AtomicBoolean(false)
            val cancelTag = AtomicReference<String?>(null)

            // IMPORTANT: callbackFlow block is NOT suspend. Move all suspend work into a coroutine body.
            val driverJob = launch(Dispatchers.Default) {
                AI_INFERENCE_GATE.withPermit {
                    gateActive.set(true)
                    try {
                        val gateWaitMs = SystemClock.elapsedRealtime() - gateReqAt

                        val parentJob = coroutineContext[Job] ?: SupervisorJob()

                        val anchorJob = SupervisorJob(parentJob)
                        val anchorScope = CoroutineScope(Dispatchers.Default + anchorJob)

                        /**
                         * IMPORTANT:
                         * - Separate "logging/finalize" scope from "post-cleanup/reset" scope.
                         * - finalizeOnce() cancels logJob, and MUST NOT cancel resetConversation work.
                         * - All jobs are parented to driverJob, so cancellation will not leak.
                         */
                        val logJob = SupervisorJob(parentJob)
                        val logScope = CoroutineScope(Dispatchers.IO + logJob)

                        val postJob = SupervisorJob(parentJob)
                        val postScope = CoroutineScope(Dispatchers.IO + postJob)

                        val closed = AtomicBoolean(false)
                        val finalized = AtomicBoolean(false)

                        val startAt = AtomicLong(SystemClock.elapsedRealtime())
                        val firstTokenAt = AtomicLong(-1L)
                        val lastEventAt = AtomicLong(startAt.get())
                        val lastDeltaAt = AtomicLong(startAt.get())

                        val logicalDone = AtomicBoolean(false)
                        val logicalDoneAt = AtomicLong(-1L)

                        val nativeTerminated = AtomicBoolean(false)

                        val chunks = AtomicLong(0L)
                        val capturedAll = AtomicBoolean(true)

                        val outLock = Any()
                        val fullOut = StringBuilder(8 * 1024)

                        val normalizer = LocalDeltaNormalizer(LocalDeltaNormalizer.Mode.AUTO)

                        fun markEvent() {
                            lastEventAt.set(SystemClock.elapsedRealtime())
                        }

                        fun appendOutput(delta: String) {
                            if (delta.isEmpty()) return
                            val now = SystemClock.elapsedRealtime()
                            if (firstTokenAt.get() < 0L) firstTokenAt.compareAndSet(-1L, now)
                            lastDeltaAt.set(now)
                            chunks.incrementAndGet()

                            val ok = synchronized(outLock) { AiTrace.capAppend(fullOut, delta) }
                            if (!ok) capturedAll.set(false)
                        }

                        fun snapshotForDebug(prefixChars: Int): Pair<Int, String> {
                            return synchronized(outLock) {
                                val len = fullOut.length
                                if (len <= 0) return@synchronized (0 to "")
                                val end = kotlin.math.min(len, prefixChars.coerceAtLeast(0))
                                val preview =
                                    if (end == len && len <= prefixChars) fullOut.toString()
                                    else fullOut.substring(0, end)
                                len to preview
                            }
                        }

                        fun requestCancelOnly(tag: String) {
                            if (!cancelTag.compareAndSet(null, tag)) return

                            anchorScope.launch {
                                runCatchingSuspend {
                                    runOnSlmThread { SLM.cancel(model) }
                                }.onFailure { e ->
                                    Log.w(TAG, "[$requestId] cancel failed ($tag): ${e.message}", e)
                                }
                            }

                            Log.w(TAG, "[$requestId] cancel requested: tag='$tag'")
                        }

                        fun scheduleResetAfterSafepoint(tag: String) {
                            postScope.launch {
                                Log.d(TAG, "[$requestId] scheduleResetAfterSafepoint: begin (tag='$tag')")

                                try {
                                    runCatchingSuspend {
                                        runOnSlmThread {
                                            SLM.resetConversation(
                                                model = model,
                                                supportImage = supportImage,
                                                supportAudio = supportAudio,
                                                systemMessage = systemMessage,
                                                tools = tools,
                                            )
                                        }
                                    }.onSuccess {
                                        Log.d(TAG, "[$requestId] resetConversation ok (after safepoint, tag='$tag')")
                                    }.onFailure { e ->
                                        // NOTE:
                                        // Cancellation during shutdown/flow completion is not a functional failure.
                                        // Only warn for non-cancellation exceptions.
                                        if (e is CancellationException) {
                                            Log.d(TAG, "[$requestId] resetConversation cancelled (after safepoint, tag='$tag'): ${e.message}")
                                        } else {
                                            Log.w(TAG, "[$requestId] resetConversation failed (after safepoint, tag='$tag'): ${e.message}", e)
                                        }
                                    }
                                } finally {
                                    postJob.cancel()
                                    Log.d(TAG, "[$requestId] scheduleResetAfterSafepoint: end (tag='$tag')")
                                }
                            }
                        }

                        fun finalizeOnce(reason: String, cause: Throwable? = null) {
                            if (!finalized.compareAndSet(false, true)) return

                            logScope.launch {
                                val now = SystemClock.elapsedRealtime()
                                val elapsedMs = now - startAt.get()
                                val firstMs = firstTokenAt.get().let { if (it < 0L) -1L else (it - startAt.get()) }
                                val lastDeltaMsAgo = now - lastDeltaAt.get()
                                val lastEventMsAgo = now - lastEventAt.get()

                                val outText = synchronized(outLock) { fullOut.toString() }

                                val stats = buildString {
                                    appendLine("=== AI TRACE STATS (LiteRtRepository) ===")
                                    appendLine("rid=$requestId model='${model.name}' reason=$reason")
                                    appendLine("gateWaitMs=$gateWaitMs elapsedMs=$elapsedMs firstTokenMs=$firstMs")
                                    appendLine("logicalDone=${logicalDone.get()} logicalDoneAt=${logicalDoneAt.get()}")
                                    appendLine("nativeTerminated=${nativeTerminated.get()} cancelTag='${cancelTag.get()}'")
                                    appendLine("lastDeltaMsAgo=$lastDeltaMsAgo lastEventMsAgo=$lastEventMsAgo")
                                    appendLine("chunks=${chunks.get()} capturedAll=${capturedAll.get()} out.len=${outText.length}")
                                    if (cause != null) {
                                        appendLine("--- exception ---")
                                        appendLine(Log.getStackTraceString(cause))
                                    }
                                    appendLine("=== OUTPUT (FULL) ===")
                                    append(outText)
                                    if (!capturedAll.get()) appendLine("\n... (output capture truncated by MAX_CAPTURE_CHARS)")
                                }

                                AiTrace.logLong(TAG, if (cause != null) Log.WARN else Log.DEBUG, "[$requestId] FINALIZE: $reason", stats)
                                AiTrace.dumpToFile("litert", requestId, model.name, stats)
                            }.invokeOnCompletion {
                                logJob.cancel()
                                Log.d(TAG, "[$requestId] finalizeOnce: logJob cancelled (reason='$reason')")
                            }
                        }

                        fun closeOnce(reason: String, cause: Throwable? = null) {
                            if (!closed.compareAndSet(false, true)) return

                            internalClose.set(true)
                            finalizeOnce(reason, cause)

                            runCatching { anchorScope.cancel(CancellationException("closeOnce: $reason")) }

                            runCatching {
                                if (cause != null) out.close(cause) else out.close()
                            }
                        }

                        if (Companion.FORCE_REINIT.getAndSet(false)) {
                            Log.w(TAG, "[$requestId] FORCE_REINIT=true -> safe reset before inference")

                            runCatchingSuspend {
                                runOnSlmThread {
                                    SLM.resetConversation(
                                        model = model,
                                        supportImage = supportImage,
                                        supportAudio = supportAudio,
                                        systemMessage = systemMessage,
                                        tools = tools,
                                    )
                                }
                            }.onFailure { e ->
                                Log.w(TAG, "[$requestId] pre-run resetConversation failed: ${e.message}", e)
                            }

                            runCatchingSuspend {
                                runOnSlmThread {
                                    SLM.cleanUp(model) {
                                        Log.d(TAG, "[$requestId] pre-run cleanUp done (force reinit)")
                                    }
                                }
                            }.onFailure { e ->
                                Log.w(TAG, "[$requestId] pre-run cleanUp failed: ${e.message}", e)
                            }
                        }

                        val normalized = prompt.normalizePrompt()
                        val escaped = escapeReservedTurnTokens(normalized)
                        val cappedPrompt = capPromptIfNeeded(escaped, PROMPT_CHAR_CAP, PROMPT_KEEP_TAIL_CHARS)
                        val promptSha = AiTrace.sha256Short(cappedPrompt)

                        Log.d(TAG, "[$requestId] request start: model='${model.name}', prompt.len=${cappedPrompt.length}, sha=$promptSha, gateWaitMs=$gateWaitMs")
                        AiTrace.logLong(TAG, Log.DEBUG, "[$requestId] PROMPT (FULL) sha=$promptSha", cappedPrompt)

                        // Watchdog
                        anchorScope.launch {
                            while (isActive && !closed.get()) {
                                val now = SystemClock.elapsedRealtime()
                                val elapsed = now - startAt.get()
                                val hasAnyToken = firstTokenAt.get() >= 0L

                                if (elapsed >= HARD_WATCHDOG_MS) {
                                    val r = "hard-watchdog-timeout"
                                    Log.w(TAG, "[$requestId] $r (${elapsed}ms) -> cancel-only + close")
                                    requestCancelOnly(r)
                                    closeOnce(r)
                                    break
                                }

                                if (!hasAnyToken && elapsed >= FIRST_TOKEN_TIMEOUT_MS) {
                                    val r = "first-token-timeout"
                                    Log.w(TAG, "[$requestId] $r (${elapsed}ms) -> cancel-only + close")
                                    requestCancelOnly(r)
                                    closeOnce(r)
                                    break
                                }

                                if (hasAnyToken && !logicalDone.get()) {
                                    val stalled = now - lastEventAt.get()
                                    if (stalled >= EVENT_STALL_TIMEOUT_MS) {
                                        val r = "event-stall-timeout"
                                        Log.w(TAG, "[$requestId] $r (${stalled}ms) -> cancel-only + close")
                                        requestCancelOnly(r)
                                        closeOnce(r)
                                        break
                                    }
                                }

                                if (logicalDone.get()) {
                                    val doneAt = logicalDoneAt.get()
                                    if (doneAt > 0L) {
                                        val afterDone = now - doneAt
                                        if (afterDone >= POST_DONE_TIMEOUT_MS) {
                                            val r = "post-done-termination-timeout"
                                            Log.w(TAG, "[$requestId] $r (${afterDone}ms) -> cancel-only + close + force reinit")
                                            requestCancelOnly(r)
                                            Companion.FORCE_REINIT.set(true)
                                            closeOnce(r)
                                            break
                                        }
                                    }
                                }

                                delay(PROGRESS_POLL_MS)
                            }
                        }

                        // Initialize
                        val ctx = appContext
                        if (ctx != null) {
                            val initT0 = SystemClock.elapsedRealtime()
                            val initAttempt: Result<Unit>? = withTimeoutOrNull(INIT_TIMEOUT_MS) {
                                runCatchingSuspend {
                                    runOnSlmThread {
                                        SLM.initializeIfNeeded(
                                            context = ctx,
                                            model = model,
                                            supportImage = supportImage,
                                            supportAudio = supportAudio,
                                            systemMessage = systemMessage,
                                            tools = tools,
                                        )
                                    }
                                }
                            }
                            val initMs = SystemClock.elapsedRealtime() - initT0

                            when {
                                initAttempt == null -> {
                                    val msg = "SLM.initializeIfNeeded timed out after ${INIT_TIMEOUT_MS}ms (initMs=$initMs)"
                                    Log.e(TAG, "[$requestId] $msg")
                                    requestCancelOnly("init-timeout")
                                    Companion.FORCE_REINIT.set(true)
                                    closeOnce("init-timeout", RuntimeException(msg))
                                }
                                initAttempt.isFailure -> {
                                    val e = initAttempt.exceptionOrNull()
                                    Log.e(TAG, "[$requestId] initializeIfNeeded failed (initMs=$initMs): ${e?.message}", e)
                                    requestCancelOnly("init-error")
                                    Companion.FORCE_REINIT.set(true)
                                    closeOnce("init-error", e ?: RuntimeException("init-error"))
                                }
                                else -> {
                                    Log.d(TAG, "[$requestId] initializeIfNeeded ok (initMs=$initMs)")
                                }
                            }
                        } else {
                            Log.d(TAG, "[$requestId] appContext=null -> skip initializeIfNeeded (assume already initialized)")
                        }

                        // Inference
                        if (!closed.get()) {
                            var msgCount = 0
                            try {
                                runOnSlmThread {
                                    SLM.runInference(
                                        model = model,
                                        input = cappedPrompt,
                                        resultListener = { partial, done ->
                                            if (closed.get()) return@runInference

                                            markEvent()
                                            msgCount++

                                            val delta = normalizer.toDelta(partial)
                                            if (delta.isNotEmpty()) {
                                                appendOutput(delta)
                                                out.trySend(delta)
                                            }

                                            if (DEBUG_STREAM && (msgCount == 1 || msgCount % DEBUG_STREAM_EVERY_N == 0)) {
                                                val dPreview = delta.take(DEBUG_PREFIX_CHARS).replace("\n", "\\n")
                                                val (outLen, outPreviewRaw) = snapshotForDebug(DEBUG_PREFIX_CHARS)
                                                val sPreview = outPreviewRaw.replace("\n", "\\n")

                                                Log.d(
                                                    TAG,
                                                    "stream[rid=$requestId msg#$msgCount] done=$done " +
                                                            "deltaLen=${delta.length} outLen=$outLen " +
                                                            "outPreview='$sPreview' deltaPreview='$dPreview'"
                                                )
                                            }

                                            if (done) {
                                                if (logicalDone.compareAndSet(false, true)) {
                                                    logicalDoneAt.set(SystemClock.elapsedRealtime())
                                                }
                                                Log.d(TAG, "[$requestId] logical done=true (waiting native termination safe point)")
                                            }
                                        },
                                        cleanUpListener = {
                                            nativeTerminated.set(true)
                                            markEvent()
                                            Log.d(TAG, "[$requestId] cleanUpListener (native termination safe point)")

                                            scheduleResetAfterSafepoint(tag = "native-terminated")
                                            closeOnce("native-terminated")
                                        },
                                        onError = { message ->
                                            if (closed.get()) return@runInference

                                            markEvent()
                                            val msg = message.trim()
                                            val upper = msg.uppercase(Locale.US)

                                            val isCancelled =
                                                upper.contains("CANCELLED") ||
                                                        upper.contains("CANCELED") ||
                                                        msg.equals("Cancelled", ignoreCase = true)

                                            val tag = cancelTag.get()

                                            if (isCancelled && tag != null) {
                                                Log.w(TAG, "[$requestId] onError(cancelled): '$msg' tag='$tag' -> close without exception")
                                                closeOnce(tag)
                                                return@runInference
                                            }

                                            Log.e(TAG, "[$requestId] onError: '$msg' -> force reinit next time")
                                            Companion.FORCE_REINIT.set(true)
                                            closeOnce("error", RuntimeException(msg))
                                        }
                                    )
                                }
                            } catch (t: Throwable) {
                                Log.e(TAG, "[$requestId] runInference threw: ${t.message}", t)
                                Companion.FORCE_REINIT.set(true)
                                closeOnce("exception", t)
                            }
                        }
                    } finally {
                        gateActive.set(false)
                    }
                }
            }

            awaitClose {
                // callbackFlow awaitClose is NOT suspend.
                // Only do non-suspending coordination here.

                // If we are closing because *we* called out.close(), do NOT cancel the engine.
                if (!internalClose.get()) {
                    // Collector cancellation path.
                    cancelTag.compareAndSet(null, "collector-cancelled")

                    // Only cancel if this request is actually the active one (inside the gate).
                    if (gateActive.get() && cancelIssued.compareAndSet(false, true)) {
                        repoScope.launch {
                            runCatchingSuspend {
                                runOnSlmThread { SLM.cancel(model) }
                            }.onFailure { e ->
                                Log.w(TAG, "[$requestId] cancel failed (collector-cancelled): ${e.message}", e)
                            }
                        }
                        Log.w(TAG, "[$requestId] awaitClose: collector cancelled -> SLM.cancel requested")
                    }

                    // IMPORTANT:
                    // Only cancel the driver on collector-cancelled.
                    // If internalClose=true (normal completion), cancelling driverJob here would
                    // cancel post-safepoint cleanup (resetConversation) and produce noisy warnings.
                    driverJob.cancel(CancellationException("callbackFlow closed"))
                }
            }
        }
            .buffer(Channel.BUFFERED)
            .flowOn(Dispatchers.Default)
    }
}