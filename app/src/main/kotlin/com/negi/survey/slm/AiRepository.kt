/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AiRepository.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.slm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Message
import com.negi.survey.AppRingLogStore
import com.negi.survey.BuildConfig
import com.negi.survey.config.SurveyConfig
import com.negi.survey.net.RuntimeLogStore
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    /** Enables verbose prompt/output tracing (FULL prompt/output). */
    private val ENABLED_DEFAULT: Boolean = BuildConfig.DEBUG

    @Volatile
    var enabled: Boolean = ENABLED_DEFAULT

    /**
     * Ring "meta" logging is safe to keep enabled because we log only non-sensitive metadata.
     *
     * Notes:
     * - Do NOT log full prompt or full model output into ring.
     * - Ring is intended for crash-time postmortem and may be uploaded.
     */
    @Volatile
    var ringEnabled: Boolean = true

    /**
     * Install an application context for optional file dumps.
     *
     * Call once early, e.g. MainActivity.onCreate():
     *   AiTrace.install(applicationContext)
     */
    fun install(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx

        // Ensure the app-owned ring logger is installed (idempotent).
        runCatching { AppRingLogStore.install(ctx) }
            .onFailure { e -> RuntimeLogStore.w(TAG, "AppRingLogStore.install failed (ignored): ${e.message}", e) }

        RuntimeLogStore.d(TAG, "Installed (enabled=$enabled ringEnabled=$ringEnabled)")
        ringD(TAG, "Installed (enabled=$enabled ringEnabled=$ringEnabled)")
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
     * Chunked log printer to avoid line truncation.
     *
     * Notes:
     * - FULL payload printing (prompt/output) must be guarded by [enabled].
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
                Log.ERROR -> RuntimeLogStore.e(tag, prefix + slice)
                Log.WARN -> RuntimeLogStore.w(tag, prefix + slice)
                else -> RuntimeLogStore.d(tag, prefix + slice)
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
            RuntimeLogStore.w(TAG, "dumpToFile failed: ${e.message}", e)
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Ring-safe meta logging (NO full prompt/output).
    // ------------------------------------------------------------------

    private inline fun safeRing(block: () -> Unit) {
        if (!ringEnabled) return
        runCatching { block() }
            .onFailure { e ->
                // Never let ring logging crash the app.
                RuntimeLogStore.w(TAG, "ring logging failed (ignored): ${e.message}", e)
            }
    }

    fun ringD(tag: String, message: String) {
        safeRing { AppRingLogStore.log("D", tag, message) }
    }

    fun ringI(tag: String, message: String) {
        safeRing { AppRingLogStore.log("I", tag, message) }
    }

    fun ringW(tag: String, message: String, tr: Throwable? = null) {
        safeRing { AppRingLogStore.log("W", tag, message, tr) }
    }

    fun ringE(tag: String, message: String, tr: Throwable? = null) {
        safeRing { AppRingLogStore.log("E", tag, message, tr) }
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
        private const val PROMPT_KEEP_HEAD_CHARS: Int = 48_000
        private const val PROMPT_KEEP_TAIL_CHARS: Int = 24_000

        /**
         * Shared flag used to request a safe reset before the next inference.
         * This is used when we couldn't reach a native termination safepoint.
         */
        private val FORCE_REINIT = AtomicBoolean(false)

        /**
         * Single-thread dispatcher for core SLM/JNI calls.
         *
         * IMPORTANT:
         * - runInference can be a blocking call depending on backend.
         * - If runInference blocks this dispatcher, control calls (cancel) may be starved.
         */
        private val SLM_DISPATCHER by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "slm-jni").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        }

        /**
         * Dedicated dispatcher ONLY for cancellation.
         *
         * Rationale:
         * - If SLM.runInference blocks the main SLM dispatcher thread, cancel requests would never run.
         * - Using a separate single thread allows best-effort abort even during a blocked inference call.
         *
         * Notes:
         * - This intentionally trades some "single-thread JNI purity" for survivability.
         * - If backend requires same-thread cancellation, this can be rolled back easily.
         */
        private val SLM_CANCEL_DISPATCHER by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "slm-cancel").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        }
    }

    /**
     * A repository-lifetime scope that survives callbackFlow cancellation.
     *
     * Rationale:
     * - callbackFlow scope is cancelled immediately when collector cancels.
     * - We still want a best-effort SLM.cancel() / resetConversation() to run.
     */
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        appContext?.let { AiTrace.install(it) }
    }

    /**
     * Truncate prompt by preserving both head (system/contract) and tail (latest input).
     *
     * This prevents losing system instructions when the prompt grows too large.
     */
    private fun capPromptIfNeeded(prompt: String, maxChars: Int, keepHeadChars: Int, keepTailChars: Int): String {
        if (prompt.length <= maxChars) return prompt

        val headKeep = keepHeadChars.coerceIn(4_096, maxChars)
        val tailKeep = keepTailChars.coerceIn(4_096, maxChars)

        val head = prompt.take(headKeep)
        val tail = prompt.takeLast(tailKeep)

        val dropped = prompt.length - (head.length + tail.length)
        val marker = "\n[TRUNCATED: dropped≈${dropped.coerceAtLeast(0)} chars; kept_head=${head.length}; kept_tail=${tail.length}]\n"

        var out = head + marker + tail
        if (out.length <= maxChars) return out

        // If still too large, shrink tail first.
        val overflow = out.length - maxChars
        val newTailLen = (tail.length - overflow).coerceAtLeast(4_096)
        out = head + marker + tail.takeLast(newTailLen)

        // Last resort hard cap.
        return if (out.length <= maxChars) out else out.takeLast(maxChars)
    }

    /**
     * Escape reserved turn-control tokens ONLY in user-provided content.
     *
     * IMPORTANT:
     * - Do NOT apply this to system prompt or model-format scaffolding.
     * - The goal is to prevent user content from corrupting the prompt grammar.
     */
    private fun escapeReservedTurnTokensInUserContent(text: String): String {
        var out = text
        out = out.replace(PromptDefaults.USER_TURN_PREFIX, "< start_of_turn >user")
        out = out.replace(PromptDefaults.MODEL_TURN_PREFIX, "< start_of_turn >model")
        out = out.replace(PromptDefaults.TURN_END, "< end_of_turn >")
        return out
    }

    /**
     * Runs core SLM/JNI calls on a dedicated single thread.
     */
    private suspend fun <T> runOnSlmThread(block: suspend () -> T): T {
        return withContext(SLM_DISPATCHER) { block() }
    }

    /**
     * Runs ONLY cancellation on a separate thread so it can run even if the main SLM thread is blocked.
     */
    private suspend fun runCancelOnSlmThread(block: suspend () -> Unit) {
        withContext(SLM_CANCEL_DISPATCHER) { block() }
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
        val rawInput = if (userPrompt.isBlank()) emptyJson else normalize(userPrompt.trimIndent())

        // Escape ONLY user content.
        val safeInput = escapeReservedTurnTokensInUserContent(rawInput)

        val labeledInput = joinNonBlank("INPUT:", safeInput)

        val fullPrompt = joinNonBlank(
            systemPrompt,
            labeledInput,
        )

        return capPromptIfNeeded(fullPrompt, PROMPT_CHAR_CAP, PROMPT_KEEP_HEAD_CHARS, PROMPT_KEEP_TAIL_CHARS)
    }

    /**
     * Warm up the model initialization so the first real request doesn't hit cold-start.
     *
     * Notes:
     * - Uses the same process-wide gate as inference to guarantee strict serialization.
     * - Runs SLM calls on the dedicated SLM thread.
     */
    override suspend fun warmUp() {
        val ctx = appContext
        if (ctx == null) {
            RuntimeLogStore.w(TAG, "warmUp skipped: appContext=null")
            AiTrace.ringW(TAG, "warmUp skipped: appContext=null")
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
                    RuntimeLogStore.w(TAG, "warmUp: initializeIfNeeded timed out (${INIT_TIMEOUT_MS}ms) gateWaitMs=$gateMs initMs=$initMs")
                    AiTrace.ringW(TAG, "warmUp timeout initMs=$initMs gateWaitMs=$gateMs")
                }
                initAttempt.isFailure -> {
                    val e = initAttempt.exceptionOrNull()
                    RuntimeLogStore.w(TAG, "warmUp: initializeIfNeeded failed gateWaitMs=$gateMs initMs=$initMs err=${e?.message}", e)
                    AiTrace.ringW(TAG, "warmUp init failed initMs=$initMs gateWaitMs=$gateMs err=${e?.message}", e)
                }
                else -> {
                    RuntimeLogStore.d(TAG, "warmUp: initializeIfNeeded ok gateWaitMs=$gateMs initMs=$initMs")
                    AiTrace.ringD(TAG, "warmUp ok initMs=$initMs gateWaitMs=$gateMs")
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
             * Close coordination:
             * - internalClose: true when we close the channel (normal completion or internal error).
             * - gateActive: true only while inside the withPermit critical section.
             * - cancelIssued: ensures we send SLM.cancel() at most once for collector cancellation.
             * - cancelTag: set when cancellation is requested (for interpreting CANCELLED errors).
             */
            val internalClose = AtomicBoolean(false)
            val gateActive = AtomicBoolean(false)
            val cancelIssued = AtomicBoolean(false)
            val cancelTag = AtomicReference<String?>(null)

            val driverJob = launch(Dispatchers.Default) {
                AI_INFERENCE_GATE.withPermit {
                    gateActive.set(true)
                    try {
                        val gateWaitMs = SystemClock.elapsedRealtime() - gateReqAt

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

                        // IMPORTANT:
                        // Use the shared StreamDeltaNormalizer from SLM.kt to avoid diverging logic.
                        val normalizer = StreamDeltaNormalizer(StreamDeltaNormalizer.PartialMode.AUTO)

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

                            // Use repoScope so cancellation survives driverJob cancellation.
                            repoScope.launch {
                                runCatchingSuspend {
                                    runCancelOnSlmThread { SLM.cancel(model) }
                                }.onFailure { e ->
                                    RuntimeLogStore.w(TAG, "[$requestId] cancel failed ($tag): ${e.message}", e)
                                    AiTrace.ringW(TAG, "[$requestId] cancel failed tag='$tag' err=${e.message}", e)
                                }
                            }

                            RuntimeLogStore.w(TAG, "[$requestId] cancel requested: tag='$tag'")
                            AiTrace.ringW(TAG, "[$requestId] cancel requested tag='$tag'")
                        }

                        fun scheduleResetAfterSafepoint(tag: String) {
                            // IMPORTANT: run on repoScope so it survives collector cancellation.
                            repoScope.launch(Dispatchers.IO) {
                                RuntimeLogStore.d(TAG, "[$requestId] scheduleResetAfterSafepoint: begin (tag='$tag')")
                                AiTrace.ringD(TAG, "[$requestId] resetConversation begin tag='$tag'")

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
                                    RuntimeLogStore.d(TAG, "[$requestId] resetConversation ok (after safepoint, tag='$tag')")
                                    AiTrace.ringD(TAG, "[$requestId] resetConversation ok tag='$tag'")
                                }.onFailure { e ->
                                    if (e is CancellationException) {
                                        RuntimeLogStore.d(TAG, "[$requestId] resetConversation cancelled (after safepoint, tag='$tag'): ${e.message}")
                                        AiTrace.ringW(TAG, "[$requestId] resetConversation cancelled tag='$tag' msg=${e.message}")
                                    } else {
                                        RuntimeLogStore.w(TAG, "[$requestId] resetConversation failed (after safepoint, tag='$tag'): ${e.message}", e)
                                        AiTrace.ringW(TAG, "[$requestId] resetConversation failed tag='$tag' err=${e.message}", e)
                                    }
                                }

                                RuntimeLogStore.d(TAG, "[$requestId] scheduleResetAfterSafepoint: end (tag='$tag')")
                            }
                        }

                        fun markForceReinit(reason: String) {
                            // IMPORTANT:
                            // We set FORCE_REINIT when we cannot trust we reached native termination safepoint.
                            FORCE_REINIT.set(true)
                            RuntimeLogStore.w(TAG, "[$requestId] FORCE_REINIT=true (reason='$reason')")
                            AiTrace.ringW(TAG, "[$requestId] FORCE_REINIT=true reason='$reason'")
                        }

                        fun finalizeOnce(reason: String, cause: Throwable? = null) {
                            if (!finalized.compareAndSet(false, true)) return

                            // Use repoScope so logs can flush even if driverJob is cancelled.
                            repoScope.launch(Dispatchers.IO) {
                                val now = SystemClock.elapsedRealtime()
                                val elapsedMs = now - startAt.get()
                                val firstMs = firstTokenAt.get().let { if (it < 0L) -1L else (it - startAt.get()) }
                                val lastDeltaMsAgo = now - lastDeltaAt.get()
                                val lastEventMsAgo = now - lastEventAt.get()

                                val outLen = synchronized(outLock) { fullOut.length }
                                AiTrace.ringI(
                                    TAG,
                                    "[$requestId] finalize reason='$reason' elapsedMs=$elapsedMs firstTokenMs=$firstMs " +
                                            "chunks=${chunks.get()} out.len=$outLen capturedAll=${capturedAll.get()} " +
                                            "logicalDone=${logicalDone.get()} nativeTerminated=${nativeTerminated.get()} cancelTag='${cancelTag.get()}' " +
                                            "lastDeltaMsAgo=$lastDeltaMsAgo lastEventMsAgo=$lastEventMsAgo"
                                )

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
                            }
                        }

                        fun closeOnce(reason: String, cause: Throwable? = null) {
                            if (!closed.compareAndSet(false, true)) return

                            internalClose.set(true)
                            finalizeOnce(reason, cause)

                            runCatching {
                                if (cause != null) out.close(cause) else out.close()
                            }
                        }

                        if (FORCE_REINIT.getAndSet(false)) {
                            RuntimeLogStore.w(TAG, "[$requestId] FORCE_REINIT=true -> safe reset before inference")
                            AiTrace.ringW(TAG, "[$requestId] FORCE_REINIT=true -> pre-run reset+cleanup")

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
                                RuntimeLogStore.w(TAG, "[$requestId] pre-run resetConversation failed: ${e.message}", e)
                                AiTrace.ringW(TAG, "[$requestId] pre-run resetConversation failed err=${e.message}", e)
                            }

                            runCatchingSuspend {
                                runOnSlmThread {
                                    SLM.cleanUp(model) {
                                        RuntimeLogStore.d(TAG, "[$requestId] pre-run cleanUp done (force reinit)")
                                    }
                                }
                            }.onFailure { e ->
                                RuntimeLogStore.w(TAG, "[$requestId] pre-run cleanUp failed: ${e.message}", e)
                                AiTrace.ringW(TAG, "[$requestId] pre-run cleanUp failed err=${e.message}", e)
                            }
                        }

                        val normalized = prompt.normalizePrompt()
                        val cappedPrompt = capPromptIfNeeded(
                            normalized,
                            PROMPT_CHAR_CAP,
                            PROMPT_KEEP_HEAD_CHARS,
                            PROMPT_KEEP_TAIL_CHARS
                        )
                        val promptSha = AiTrace.sha256Short(cappedPrompt)

                        RuntimeLogStore.d(TAG, "[$requestId] request start: model='${model.name}', prompt.len=${cappedPrompt.length}, sha=$promptSha, gateWaitMs=$gateWaitMs")
                        AiTrace.ringD(TAG, "[$requestId] start model='${model.name}' prompt.len=${cappedPrompt.length} sha=$promptSha gateWaitMs=$gateWaitMs")

                        // FULL prompt only in debug tracing mode.
                        AiTrace.logLong(TAG, Log.DEBUG, "[$requestId] PROMPT (FULL) sha=$promptSha", cappedPrompt)

                        // Watchdog
                        launch {
                            while (isActive && !closed.get()) {
                                val now = SystemClock.elapsedRealtime()
                                val elapsed = now - startAt.get()
                                val hasAnyToken = firstTokenAt.get() >= 0L

                                if (elapsed >= HARD_WATCHDOG_MS) {
                                    val r = "hard-watchdog-timeout"
                                    RuntimeLogStore.w(TAG, "[$requestId] $r (${elapsed}ms) -> cancel-only + close + FORCE_REINIT")
                                    AiTrace.ringW(TAG, "[$requestId] $r elapsedMs=$elapsed -> cancel+close FORCE_REINIT")
                                    requestCancelOnly(r)
                                    markForceReinit(r)
                                    closeOnce(r)
                                    break
                                }

                                if (!hasAnyToken && elapsed >= FIRST_TOKEN_TIMEOUT_MS) {
                                    val r = "first-token-timeout"
                                    RuntimeLogStore.w(TAG, "[$requestId] $r (${elapsed}ms) -> cancel-only + close + FORCE_REINIT")
                                    AiTrace.ringW(TAG, "[$requestId] $r elapsedMs=$elapsed -> cancel+close FORCE_REINIT")
                                    requestCancelOnly(r)
                                    markForceReinit(r)
                                    closeOnce(r)
                                    break
                                }

                                if (hasAnyToken && !logicalDone.get()) {
                                    val stalled = now - lastEventAt.get()
                                    if (stalled >= EVENT_STALL_TIMEOUT_MS) {
                                        val r = "event-stall-timeout"
                                        RuntimeLogStore.w(TAG, "[$requestId] $r (${stalled}ms) -> cancel-only + close + FORCE_REINIT")
                                        AiTrace.ringW(TAG, "[$requestId] $r stalledMs=$stalled -> cancel+close FORCE_REINIT")
                                        requestCancelOnly(r)
                                        markForceReinit(r)
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
                                            RuntimeLogStore.w(TAG, "[$requestId] $r (${afterDone}ms) -> cancel-only + close + FORCE_REINIT")
                                            AiTrace.ringW(TAG, "[$requestId] $r afterDoneMs=$afterDone -> cancel+close FORCE_REINIT")
                                            requestCancelOnly(r)
                                            markForceReinit(r)
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
                                    RuntimeLogStore.e(TAG, "[$requestId] $msg")
                                    AiTrace.ringE(TAG, "[$requestId] init-timeout initMs=$initMs -> FORCE_REINIT")
                                    requestCancelOnly("init-timeout")
                                    markForceReinit("init-timeout")
                                    closeOnce("init-timeout", RuntimeException(msg))
                                }
                                initAttempt.isFailure -> {
                                    val e = initAttempt.exceptionOrNull()
                                    RuntimeLogStore.e(TAG, "[$requestId] initializeIfNeeded failed (initMs=$initMs): ${e?.message}", e)
                                    AiTrace.ringE(TAG, "[$requestId] init-error initMs=$initMs err=${e?.message} -> FORCE_REINIT", e)
                                    requestCancelOnly("init-error")
                                    markForceReinit("init-error")
                                    closeOnce("init-error", e ?: RuntimeException("init-error"))
                                }
                                else -> {
                                    RuntimeLogStore.d(TAG, "[$requestId] initializeIfNeeded ok (initMs=$initMs)")
                                    AiTrace.ringD(TAG, "[$requestId] init ok initMs=$initMs")
                                }
                            }
                        } else {
                            RuntimeLogStore.d(TAG, "[$requestId] appContext=null -> skip initializeIfNeeded (assume already initialized)")
                            AiTrace.ringW(TAG, "[$requestId] appContext=null -> skip initializeIfNeeded")
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

                                            lastEventAt.set(SystemClock.elapsedRealtime())
                                            msgCount++

                                            val delta = normalizer.toDelta(partial)
                                            if (delta.isNotEmpty()) {
                                                appendOutput(delta)
                                                val sent = out.trySend(delta)
                                                if (!sent.isSuccess) {
                                                    requestCancelOnly("channel-closed")
                                                    // Channel close means we may not see native termination safepoint.
                                                    markForceReinit("channel-closed")
                                                    closeOnce("channel-closed")
                                                    return@runInference
                                                }
                                            }

                                            if (DEBUG_STREAM && (msgCount == 1 || msgCount % DEBUG_STREAM_EVERY_N == 0)) {
                                                val dPreview = delta.take(DEBUG_PREFIX_CHARS).replace("\n", "\\n")
                                                val (outLen, outPreviewRaw) = snapshotForDebug(DEBUG_PREFIX_CHARS)
                                                val sPreview = outPreviewRaw.replace("\n", "\\n")

                                                RuntimeLogStore.d(
                                                    TAG,
                                                    "stream[rid=$requestId msg#$msgCount] done=$done " +
                                                            "deltaLen=${delta.length} outLen=$outLen " +
                                                            "outPreview='$sPreview' deltaPreview='$dPreview'"
                                                )
                                            }

                                            if (done) {
                                                if (logicalDone.compareAndSet(false, true)) {
                                                    logicalDoneAt.set(SystemClock.elapsedRealtime())
                                                    AiTrace.ringD(TAG, "[$requestId] logicalDone=true")
                                                }
                                                RuntimeLogStore.d(TAG, "[$requestId] logical done=true (waiting native termination safe point)")
                                            }
                                        },
                                        cleanUpListener = {
                                            nativeTerminated.set(true)
                                            lastEventAt.set(SystemClock.elapsedRealtime())
                                            RuntimeLogStore.d(TAG, "[$requestId] cleanUpListener (native termination safe point)")
                                            AiTrace.ringD(TAG, "[$requestId] native termination safe point -> reset+close")

                                            scheduleResetAfterSafepoint(tag = "native-terminated")
                                            closeOnce("native-terminated")
                                        },
                                        onError = { message ->
                                            if (closed.get()) return@runInference

                                            lastEventAt.set(SystemClock.elapsedRealtime())
                                            val msg = message.trim()
                                            val upper = msg.uppercase(Locale.US)

                                            val isCancelled =
                                                upper.contains("CANCELLED") ||
                                                        upper.contains("CANCELED") ||
                                                        msg.equals("Cancelled", ignoreCase = true)

                                            val tag = cancelTag.get()

                                            if (isCancelled && tag != null) {
                                                RuntimeLogStore.w(TAG, "[$requestId] onError(cancelled): '$msg' tag='$tag' -> close without exception")
                                                AiTrace.ringW(TAG, "[$requestId] onError(cancelled) tag='$tag'")
                                                closeOnce(tag)
                                                return@runInference
                                            }

                                            RuntimeLogStore.e(TAG, "[$requestId] onError: '$msg' -> FORCE_REINIT")
                                            AiTrace.ringE(TAG, "[$requestId] onError -> FORCE_REINIT msg='${msg.take(120)}'")
                                            markForceReinit("onError")
                                            closeOnce("error", RuntimeException(msg))
                                        }
                                    )
                                }
                            } catch (t: Throwable) {
                                RuntimeLogStore.e(TAG, "[$requestId] runInference threw: ${t.message}", t)
                                AiTrace.ringE(TAG, "[$requestId] runInference threw err=${t.message} -> FORCE_REINIT", t)
                                markForceReinit("exception")
                                closeOnce("exception", t)
                            }
                        }
                    } finally {
                        gateActive.set(false)
                    }
                }
            }

            awaitClose {
                // awaitClose is NOT suspend. Keep it coordination-only.

                if (!internalClose.get()) {
                    // Collector cancellation path.
                    cancelTag.compareAndSet(null, "collector-cancelled")

                    // Best-effort: even if gateActive is false (e.g., waiting), cancel is cheap and can prevent stuck runs.
                    if (cancelIssued.compareAndSet(false, true)) {
                        repoScope.launch {
                            runCatchingSuspend {
                                runCancelOnSlmThread { SLM.cancel(model) }
                            }.onFailure { e ->
                                RuntimeLogStore.w(TAG, "[$requestId] cancel failed (collector-cancelled): ${e.message}", e)
                                AiTrace.ringW(TAG, "[$requestId] cancel failed tag='collector-cancelled' err=${e.message}", e)
                            }
                        }
                        RuntimeLogStore.w(TAG, "[$requestId] awaitClose: collector cancelled -> SLM.cancel requested (gateActive=${gateActive.get()})")
                        AiTrace.ringW(TAG, "[$requestId] awaitClose: collector cancelled -> SLM.cancel requested (gateActive=${gateActive.get()})")
                    }

                    driverJob.cancel(CancellationException("callbackFlow closed"))
                }
            }
        }
            .buffer(Channel.BUFFERED)
            .flowOn(Dispatchers.Default)
    }
}