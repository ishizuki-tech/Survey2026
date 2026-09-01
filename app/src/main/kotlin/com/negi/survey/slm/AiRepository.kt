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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
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
     * Full prompt/output tracing is intentionally restricted to debug builds.
     *
     * Even if [enabled] is changed at runtime, release builds must not dump
     * potentially sensitive survey content to logcat or app-private trace files.
     */
    private fun payloadTracingEnabled(): Boolean =
        BuildConfig.DEBUG && enabled

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
        if (!payloadTracingEnabled()) return

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
        if (!payloadTracingEnabled()) return null
        val ctx = appContext ?: return null

        return runCatching {
            val dir = File(ctx.filesDir, "diagnostics/llm_trace")
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                throw IllegalStateException("Failed to create trace directory: ${dir.absolutePath}")
            }

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date())
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

        /**
         * Run a synthetic generation during startup warm-up.
         *
         * Keep disabled by default. Emulator measurements showed that the synthetic
         * request paid a full first inference without improving the following TTFT,
         * and it could delay the first real request while Conversation repair held
         * the process-wide inference gate.
         *
         * Re-enable only for controlled A/B measurements on a target device.
         */
        private const val REPRESENTATIVE_INFERENCE_WARMUP_ENABLED = false

        /**
         * A/B switch for a prefill-focused first-inference warm-up.
         *
         * The previous two-character micro warm-up did not improve the first real
         * Survey request. This variant intentionally sends a neutral prompt whose
         * character length is close to the real Survey input while keeping decode
         * capped to one token. The goal is to warm the native prefill path without
         * paying for a full representative generation.
         *
         * Keep this enabled only for the current A/B measurement.
         */
        private const val PREFILL_INFERENCE_WARMUP_AB_TEST = false
        private const val PREFILL_WARMUP_INPUT =
            "Read this neutral context and reply with OK only. " +
                    "This text exists only to warm the model prefill path before the first survey request. " +
                    "Do not summarize it, analyze it, or infer anything from it. " +
                    "Neutral context: crops, soil, weather, irrigation, harvest, storage, transport, markets, " +
                    "equipment, schedules, records, planning, maintenance, safety, quality, seasons, fields, " +
                    "inputs, outputs, observations, measurements, and routine farm operations. " +
                    "Ignore the context content and reply with OK only."
        private const val PREFILL_WARMUP_MAX_OUTPUT_TOKENS = 1
        private const val PREFILL_WARMUP_TIMEOUT_MS = 20_000L

        private const val HARD_WATCHDOG_MS = 120_000L
        private const val FIRST_TOKEN_TIMEOUT_MS = 45_000L
        private const val EVENT_STALL_TIMEOUT_MS = 12_000L
        private const val POST_DONE_TIMEOUT_MS = 30_000L
        private const val CANCEL_GRACE_TIMEOUT_MS = 5_000L
        private const val PROGRESS_POLL_MS = 250L

        /**
         * Upper bound for the representative synthetic warm-up inference.
         *
         * The warm-up deliberately runs through the normal request() pipeline so it
         * exercises the same native inference, watchdog, cancellation, cleanup, and
         * Conversation-reset behavior as a user-visible request.
         */
        private const val WARMUP_INFERENCE_TIMEOUT_MS = 30_000L

        /**
         * Maximum number of characters retained from the synthetic warm-up response
         * for bounded diagnostic previewing.
         *
         * This cap is intentionally small. The warm-up response is not application data,
         * and retaining only a short prefix prevents accidental large allocations or
         * unbounded Logcat output if generation becomes pathological.
         */
        private const val WARMUP_OUTPUT_PREVIEW_MAX_CHARS = 256

        /**
         * Explicit opt-in for release-visible synthetic warm-up output diagnostics.
         *
         * IMPORTANT:
         * - This applies ONLY to the fixed application-owned warm-up request.
         * - Real survey prompts, answers, and model outputs must never use this bypass.
         * - Keep the switch explicit so release previewing can be disabled without
         *   changing the warm-up or inference lifecycle.
         *
         * While validating startup behavior we keep this enabled so Release builds can
         * expose the synthetic response in Logcat. Disable it after the warm-up output
         * contract is confirmed if release-visible payload text is no longer required.
         */
        private const val WARMUP_SYNTHETIC_OUTPUT_LOGCAT_ENABLED = false

        /**
         * Representative synthetic survey input used only for startup warm-up.
         *
         * This payload intentionally mirrors the exact structural pattern used by
         * production one-step survey prompts:
         *
         *   Expected answer target
         *   Question
         *   Answer
         *
         * Matching the production structure is important because the system contract
         * asks the model to identify the largest remaining uncertainty relative to the
         * expected answer. A warm-up prompt without an explicit expected target can
         * encourage the model to invent an unnecessary clarification even when the
         * synthetic answer is otherwise complete.
         *
         * The content is static application-owned test data. It contains no respondent
         * data, identifiers, or other user-provided information.
         */
        private const val WARMUP_SYNTHETIC_INPUT =
            "Expected answer target: average FAW yield loss over the last 3 seasons " +
                    "(% or bags/acre).\n" +
                    "Question: How much yield do you lose because of fall armyworm? " +
                    "Please think back over the last 3 seasons. Percent or bags per acre are fine.\n" +
                    "Answer: Over the last 3 seasons, fall armyworm reduced my maize yield " +
                    "by an average of 18 percent compared with my normal yield without FAW damage."
        /**
         * Safety stop for pathological generation loops.
         *
         * Normal survey JSON/follow-up responses should be far below this size.
         */
        private const val OUTPUT_CHAR_HARD_CAP = 120_000L

        /**
         * Detect a backend/model loop that emits the exact same small non-blank
         * delta repeatedly. This catches patterns such as "a", "a", "a", ...
         * long before the hard request watchdog expires.
         */
        private const val REPEATED_DELTA_LIMIT = 48L
        private const val REPEATED_DELTA_MAX_CHARS = 32

        private val DEBUG_STREAM: Boolean = BuildConfig.DEBUG
        private const val DEBUG_STREAM_EVERY_N = 8
        private const val DEBUG_PREFIX_CHARS = 180

        private const val PROMPT_CHAR_CAP: Int = 120_000
        private const val PROMPT_KEEP_HEAD_CHARS: Int = 48_000
        private const val PROMPT_KEEP_TAIL_CHARS: Int = 24_000

        /**
         * Shared recovery flag.
         *
         * When set, Conversation reuse is not trusted. The next safe recovery
         * path performs a full Engine/Conversation teardown before allowing a
         * fresh initialization.
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

    /**
     * Prevent repeated synthetic warm-up requests for the same repository instance.
     *
     * The flag is claimed before warm-up starts and cleared again on failure so a
     * later call can retry.
     */
    private val inferenceWarmUpClaimed = AtomicBoolean(false)

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
     *
     * CancellationException is deliberately rethrown because coroutine cancellation is
     * control flow, not an ordinary operation failure. Converting it into Result.failure
     * would make lifecycle cancellation look like a recoverable backend error.
     */
    private suspend inline fun <T> runCatchingSuspend(
        crossinline block: suspend () -> T
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Convert a bounded diagnostic payload into one physical Logcat line.
     *
     * Newline, carriage-return, tab, and any remaining ISO control characters are escaped
     * so model text cannot inject extra Logcat lines or terminal control sequences. The
     * caller must still enforce the privacy boundary: this helper only makes text safe for
     * a log line; it does not make arbitrary user/model payloads safe to log.
     */
    private fun sanitizeSingleLineLogText(
        text: String,
        maxChars: Int,
    ): String {
        val capped =
            text.take(maxChars.coerceAtLeast(0))

        return buildString(capped.length) {
            capped.forEach { ch ->
                when (ch) {
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (ch.isISOControl()) {
                            append("\\u")
                            append(
                                ch.code
                                    .toString(16)
                                    .padStart(4, '0')
                            )
                        } else {
                            append(ch)
                        }
                    }
                }
            }
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
     * Warm up both the LiteRT-LM Engine and a representative first-inference path.
     *
     * Engine initialization alone is not sufficient on some devices. The first real
     * generation can still pay lazy model/runtime costs that only appear when a prompt
     * is prefetched and tokens are decoded. Therefore the second stage performs one
     * synthetic one-step survey evaluation and discards every generated chunk.
     *
     * Important concurrency and privacy invariants:
     * - The initialization gate MUST be released before request() is collected.
     *   request() acquires the same process-wide gate, so collecting it from inside
     *   AI_INFERENCE_GATE.withPermit would self-deadlock.
     * - The synthetic input is converted with buildPrompt(..., ONE_STEP) before it is
     *   passed to request(). This makes warm-up exercise the current production system
     *   prompt/contract instead of an unrelated raw text prompt.
     * - Using request() reuses production watchdog, cancellation, native cleanup, and
     *   Conversation-reset behavior instead of creating a second lifecycle path.
     * - Generated warm-up content is never persisted, written to the crash ring, dumped
     *   to trace files, or forwarded to survey UI. A bounded direct-Logcat preview may be
     *   enabled explicitly for this fixed synthetic request while validating Release builds.
     * - Normal survey/model payloads remain behind AiTrace's debug-only payload boundary.
     * - If the caller cancels warm-up, the per-repository claim is released before the
     *   CancellationException is rethrown so a later lifecycle attempt may retry.
     */
    override suspend fun warmUp() {
        val ctx = appContext
        if (ctx == null) {
            RuntimeLogStore.w(TAG, "warmUp skipped: appContext=null")
            AiTrace.ringW(TAG, "warmUp skipped: appContext=null")
            return
        }

        val startedAt = SystemClock.elapsedRealtime()

        val initialized =
            AI_INFERENCE_GATE.withPermit {
                val gateMs =
                    SystemClock.elapsedRealtime() -
                            startedAt

                val t0 =
                    SystemClock.elapsedRealtime()

                val initAttempt: Result<Unit>? =
                    withTimeoutOrNull(INIT_TIMEOUT_MS) {
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

                val initMs =
                    SystemClock.elapsedRealtime() -
                            t0

                when {
                    initAttempt == null -> {
                        RuntimeLogStore.w(
                            TAG,
                            "warmUp: initializeIfNeeded timed out " +
                                    "(${INIT_TIMEOUT_MS}ms) gateWaitMs=$gateMs initMs=$initMs"
                        )
                        AiTrace.ringW(
                            TAG,
                            "warmUp timeout initMs=$initMs gateWaitMs=$gateMs"
                        )
                        false
                    }

                    initAttempt.isFailure -> {
                        val error =
                            initAttempt.exceptionOrNull()

                        RuntimeLogStore.w(
                            TAG,
                            "warmUp: initializeIfNeeded failed " +
                                    "gateWaitMs=$gateMs initMs=$initMs err=${error?.message}",
                            error
                        )
                        AiTrace.ringW(
                            TAG,
                            "warmUp init failed initMs=$initMs " +
                                    "gateWaitMs=$gateMs err=${error?.message}",
                            error
                        )
                        false
                    }

                    else -> {
                        RuntimeLogStore.d(
                            TAG,
                            "warmUp: initializeIfNeeded ok " +
                                    "gateWaitMs=$gateMs initMs=$initMs"
                        )
                        AiTrace.ringD(
                            TAG,
                            "warmUp init ok initMs=$initMs gateWaitMs=$gateMs"
                        )
                        true
                    }
                }
            }

        if (!initialized) {
            return
        }

        if (PREFILL_INFERENCE_WARMUP_AB_TEST) {
            if (!inferenceWarmUpClaimed.compareAndSet(false, true)) {
                RuntimeLogStore.d(
                    TAG,
                    "warmUp: prefill inference already claimed/completed; skipping"
                )
                AiTrace.ringD(
                    TAG,
                    "warmUp prefill inference skipped: already claimed/completed"
                )
                return
            }

            val prefillStartedAt =
                SystemClock.elapsedRealtime()

            RuntimeLogStore.w(
                TAG,
                "warmUp: prefill inference start " +
                        "inputLen=${PREFILL_WARMUP_INPUT.length} " +
                        "maxOutputTokens=$PREFILL_WARMUP_MAX_OUTPUT_TOKENS"
            )
            AiTrace.ringD(
                TAG,
                "warmUp prefill inference start " +
                        "inputLen=${PREFILL_WARMUP_INPUT.length} " +
                        "maxOutputTokens=$PREFILL_WARMUP_MAX_OUTPUT_TOKENS"
            )

            val prefillAttempt: Result<Int> =
                try {
                    val outputChars =
                        withTimeoutOrNull(
                            PREFILL_WARMUP_TIMEOUT_MS
                        ) {
                            AI_INFERENCE_GATE.withPermit {
                                val output =
                                    runOnSlmThread {
                                        SLM.generateText(
                                            model = model,
                                            input = PREFILL_WARMUP_INPUT,
                                            maxOutputTokens =
                                                PREFILL_WARMUP_MAX_OUTPUT_TOKENS,
                                        )
                                    }

                                /*
                                 * The prefill warm-up mutates Conversation history.
                                 * Reset immediately while retaining the initialized Engine,
                                 * so the first real Survey request starts from a clean session.
                                 */
                                runOnSlmThread {
                                    SLM.resetConversationAndWait(
                                        model = model,
                                        supportImage = supportImage,
                                        supportAudio = supportAudio,
                                        systemMessage = systemMessage,
                                        tools = tools,
                                    )
                                }

                                output.length
                            }
                        }

                    if (outputChars == null) {
                        Result.failure(
                            IllegalStateException(
                                "Prefill inference warm-up timed out after " +
                                        "${PREFILL_WARMUP_TIMEOUT_MS}ms"
                            )
                        )
                    } else {
                        Result.success(outputChars)
                    }
                } catch (ce: CancellationException) {
                    inferenceWarmUpClaimed.set(false)
                    throw ce
                } catch (t: Throwable) {
                    Result.failure(t)
                }

            val prefillMs =
                SystemClock.elapsedRealtime() -
                        prefillStartedAt

            if (prefillAttempt.isSuccess) {
                val outputChars =
                    prefillAttempt.getOrThrow()

                RuntimeLogStore.w(
                    TAG,
                    "warmUp: prefill inference completed " +
                            "prefillMs=$prefillMs outputChars=$outputChars " +
                            "maxOutputTokens=$PREFILL_WARMUP_MAX_OUTPUT_TOKENS"
                )
                AiTrace.ringD(
                    TAG,
                    "warmUp prefill inference ok " +
                            "prefillMs=$prefillMs outputChars=$outputChars"
                )
            } else {
                inferenceWarmUpClaimed.set(false)

                val error =
                    prefillAttempt.exceptionOrNull()

                RuntimeLogStore.w(
                    TAG,
                    "warmUp: prefill inference failed " +
                            "prefillMs=$prefillMs err=${error?.message}",
                    error,
                )
                AiTrace.ringW(
                    TAG,
                    "warmUp prefill inference failed " +
                            "prefillMs=$prefillMs err=${error?.message}",
                    error,
                )

                /*
                 * A timeout/cancelled prefill warm-up may leave the native session
                 * unusable. Tear it down so the next real request can initialize
                 * from a known-clean state instead of inheriting the A/B failure.
                 */
                val cleanupAttempt: Result<Unit> =
                    runCatchingSuspend {
                        AI_INFERENCE_GATE.withPermit {
                            runOnSlmThread {
                                SLM.forceCleanUpAndWait(
                                    model
                                )
                            }
                        }
                        Unit
                    }

                cleanupAttempt.onFailure { cleanupError ->
                    RuntimeLogStore.w(
                        TAG,
                        "warmUp: prefill failure cleanup failed: " +
                                "${cleanupError.message}",
                        cleanupError,
                    )
                }
            }

            return
        }

        if (!REPRESENTATIVE_INFERENCE_WARMUP_ENABLED) {
            val totalMs =
                SystemClock.elapsedRealtime() -
                        startedAt

            RuntimeLogStore.d(
                TAG,
                "warmUp: Engine ready; representative inference disabled " +
                        "totalMs=$totalMs"
            )
            AiTrace.ringD(
                TAG,
                "warmUp Engine ready representativeInference=false totalMs=$totalMs"
            )
            return
        }

        /**
         * Build the warm-up prompt before claiming the inference slot.
         *
         * Prompt construction is deterministic/local and should not fail, but keeping it
         * outside the claimed region guarantees that an unexpected prompt-building error
         * can never leave inferenceWarmUpClaimed permanently stuck at true.
         */
        val warmUpPrompt =
            buildPrompt(
                userPrompt = WARMUP_SYNTHETIC_INPUT,
                phase = PromptPhase.ONE_STEP,
            )

        if (!inferenceWarmUpClaimed.compareAndSet(false, true)) {
            RuntimeLogStore.d(
                TAG,
                "warmUp: representative inference already claimed/completed; skipping"
            )
            AiTrace.ringD(
                TAG,
                "warmUp representative inference skipped: already claimed/completed"
            )
            return
        }

        val inferenceT0 =
            SystemClock.elapsedRealtime()

        val promptSha =
            AiTrace.sha256Short(warmUpPrompt)

        var outputChars = 0L

        /**
         * Retain only a bounded prefix of the synthetic response for diagnostics.
         *
         * The response is generated from WARMUP_SYNTHETIC_INPUT only. No real survey
         * prompt or answer is inserted into this request. The bounded buffer is separate
         * from AiTrace's full-payload capture and is never written to AppRingLogStore.
         */
        val warmUpOutputPreview =
            StringBuilder(WARMUP_OUTPUT_PREVIEW_MAX_CHARS)

        RuntimeLogStore.d(
            TAG,
            "warmUp: representative inference start " +
                    "prompt.len=${warmUpPrompt.length} sha=$promptSha"
        )
        AiTrace.ringD(
            TAG,
            "warmUp representative inference start " +
                    "prompt.len=${warmUpPrompt.length} sha=$promptSha"
        )

        val warmUpAttempt =
            try {
                runCatchingSuspend {
                    val completed =
                        withTimeoutOrNull(
                            WARMUP_INFERENCE_TIMEOUT_MS
                        ) {
                            request(
                                warmUpPrompt
                            ).collect { delta ->
                                /**
                                 * The warm-up response is not forwarded to any app state.
                                 * Count the complete stream for timing diagnostics and retain
                                 * only a short prefix for the optional synthetic Logcat probe.
                                 */
                                outputChars += delta.length.toLong()

                                if (
                                    warmUpOutputPreview.length <
                                    WARMUP_OUTPUT_PREVIEW_MAX_CHARS
                                ) {
                                    val remaining =
                                        WARMUP_OUTPUT_PREVIEW_MAX_CHARS -
                                                warmUpOutputPreview.length

                                    warmUpOutputPreview.append(
                                        delta.take(remaining)
                                    )
                                }
                            }

                            true
                        } ?: false

                    check(completed) {
                        "Representative inference warm-up timed out after " +
                                "${WARMUP_INFERENCE_TIMEOUT_MS}ms"
                    }
                }
            } catch (ce: CancellationException) {
                /**
                 * External lifecycle cancellation must not permanently consume the
                 * warm-up claim. The request() pipeline performs its own best-effort
                 * backend cancellation/cleanup; releasing this flag only controls whether
                 * a later warmUp() invocation is allowed to try again.
                 */
                inferenceWarmUpClaimed.set(false)

                val inferenceMs =
                    SystemClock.elapsedRealtime() -
                            inferenceT0

                RuntimeLogStore.d(
                    TAG,
                    "warmUp: representative inference cancelled " +
                            "inferenceMs=$inferenceMs outputChars=$outputChars"
                )
                AiTrace.ringD(
                    TAG,
                    "warmUp representative inference cancelled " +
                            "inferenceMs=$inferenceMs outputChars=$outputChars"
                )

                throw ce
            }

        val inferenceMs =
            SystemClock.elapsedRealtime() -
                    inferenceT0

        if (warmUpAttempt.isSuccess) {
            val previewRaw =
                warmUpOutputPreview.toString()

            val previewSha =
                AiTrace.sha256Short(previewRaw)

            val previewTruncated =
                outputChars > previewRaw.length.toLong()

            /**
             * Optional Release-visible diagnostic for the fixed synthetic warm-up only.
             *
             * This intentionally bypasses RuntimeLogStore because its Logcat mirror is
             * disabled in Release. The preview is bounded, single-line sanitized, and is
             * never copied into the crash ring or diagnostic files by this code path.
             *
             * Do not reuse this direct Log.i pattern for real survey/model payloads.
             */
            if (WARMUP_SYNTHETIC_OUTPUT_LOGCAT_ENABLED) {
                val outputPreview =
                    sanitizeSingleLineLogText(
                        text = previewRaw,
                        maxChars = WARMUP_OUTPUT_PREVIEW_MAX_CHARS,
                    )

                Log.i(
                    TAG,
                    "Warm-up synthetic output: chars=$outputChars " +
                            "previewChars=${previewRaw.length} " +
                            "truncated=$previewTruncated sha=$previewSha " +
                            "preview='$outputPreview'"
                )
            }

            RuntimeLogStore.d(
                TAG,
                "warmUp: representative inference completed " +
                        "inferenceMs=$inferenceMs outputChars=$outputChars"
            )
            AiTrace.ringD(
                TAG,
                "warmUp representative inference ok " +
                        "inferenceMs=$inferenceMs outputChars=$outputChars"
            )
        } else {
            /**
             * A failed or internally timed-out warm-up is retryable. request() owns
             * native cancellation/recovery; this flag only controls repository-level
             * duplicate suppression.
             */
            inferenceWarmUpClaimed.set(false)

            val error =
                warmUpAttempt.exceptionOrNull()

            RuntimeLogStore.w(
                TAG,
                "warmUp: representative inference failed " +
                        "inferenceMs=$inferenceMs outputChars=$outputChars " +
                        "err=${error?.message}",
                error
            )
            AiTrace.ringW(
                TAG,
                "warmUp representative inference failed " +
                        "inferenceMs=$inferenceMs outputChars=$outputChars " +
                        "err=${error?.message}",
                error
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun request(prompt: String): Flow<String> {
        return callbackFlow {
            val out = this

            val requestId = REQ_SEQ.incrementAndGet()
            val gateRequestedAt = SystemClock.elapsedRealtime()

            val internalClose = AtomicBoolean(false)
            val responseStreamClosed = AtomicBoolean(false)
            val collectorClosed = AtomicBoolean(false)
            val gateActive = AtomicBoolean(false)
            val inferenceStarted = AtomicBoolean(false)
            val cancelIssued = AtomicBoolean(false)
            val scopedCancelDispatched = AtomicBoolean(false)
            val liteRtRunId = AtomicLong(0L)
            val cancelTag = AtomicReference<String?>(null)

            /**
             * Installed after gate acquisition so awaitClose can arm a bounded
             * termination grace period without reaching into driver-local state.
             */
            val cancelGraceHook =
                AtomicReference<((String, Throwable?) -> Unit)?>(null)

            fun markForceReinit(reason: String) {
                if (LiteRtLM.nativeRuntimePoisonErrorOrNull() != null) {
                    RuntimeLogStore.w(TAG, "[$requestId] runtime poisoned; skipping FORCE_REINIT reason='$reason'")
                    return
                }
                FORCE_REINIT.set(true)
                RuntimeLogStore.w(TAG, "[$requestId] FORCE_REINIT=true reason='$reason'")
                AiTrace.ringW(TAG, "[$requestId] FORCE_REINIT=true reason='$reason'")
            }

            fun dispatchScopedCancelIfReady() {
                if (!cancelIssued.get()) return

                val expectedRunId = liteRtRunId.get()
                if (expectedRunId <= 0L) return
                if (!scopedCancelDispatched.compareAndSet(false, true)) return

                repoScope.launch {
                    runCatchingSuspend {
                        runCancelOnSlmThread {
                            SLM.cancel(
                                model = model,
                                expectedRunId = expectedRunId,
                            )
                        }
                    }.onFailure { e ->
                        val tag = cancelTag.get()
                        RuntimeLogStore.w(TAG, "[$requestId] cancel failed tag='$tag': ${e.message}", e)
                        AiTrace.ringW(TAG, "[$requestId] cancel failed tag='$tag' err=${e.message}", e)
                    }
                }
            }

            fun issueBackendCancel(tag: String) {
                cancelTag.compareAndSet(null, tag)
                if (!cancelIssued.compareAndSet(false, true)) return

                dispatchScopedCancelIfReady()

                RuntimeLogStore.w(TAG, "[$requestId] cancel requested tag='$tag'")
                AiTrace.ringW(TAG, "[$requestId] cancel requested tag='$tag'")
            }

            /**
             * Driver is intentionally repository-scoped.
             *
             * If the collector disappears while this request owns the native engine,
             * callbackFlow cancellation must NOT immediately release AI_INFERENCE_GATE.
             * We keep the gate until native cleanup or a bounded cancellation grace.
             */
            val driverJob = repoScope.launch(Dispatchers.Default) {
                try {
                    AI_INFERENCE_GATE.withPermit {
                        gateActive.set(true)

                        val completion = CompletableDeferred<String>()
                        val closed = AtomicBoolean(false)
                        val finalized = AtomicBoolean(false)
                        val nativeFinishScheduled = AtomicBoolean(false)
                        val cancelGraceScheduled = AtomicBoolean(false)

                        val terminalFailure = AtomicReference<Throwable?>(null)
                        val terminalFailureReason = AtomicReference<String?>(null)

                        val requestStartedAt = SystemClock.elapsedRealtime()
                        val inferenceStartedAt = AtomicLong(-1L)
                        val firstTokenAt = AtomicLong(-1L)
                        val lastEventAt = AtomicLong(-1L)
                        val lastDeltaAt = AtomicLong(-1L)
                        val logicalDoneAt = AtomicLong(-1L)

                        val logicalDone = AtomicBoolean(false)
                        val nativeTerminated = AtomicBoolean(false)
                        val capturedAll = AtomicBoolean(true)

                        val chunks = AtomicLong(0L)
                        val emittedChars = AtomicLong(0L)

                        val repeatedDelta = AtomicReference<String?>(null)
                        val repeatedDeltaCount = AtomicLong(0L)

                        val outLock = Any()
                        val fullOut = StringBuilder(8 * 1024)

                        val normalizer =
                            StreamDeltaNormalizer(
                                StreamDeltaNormalizer.PartialMode.DELTA
                            )

                        fun appendOutput(delta: String) {
                            if (delta.isEmpty()) return

                            val now = SystemClock.elapsedRealtime()
                            if (firstTokenAt.get() < 0L) {
                                firstTokenAt.compareAndSet(-1L, now)
                            }

                            lastDeltaAt.set(now)
                            chunks.incrementAndGet()

                            val captured =
                                synchronized(outLock) {
                                    AiTrace.capAppend(fullOut, delta)
                                }

                            if (!captured) capturedAll.set(false)
                        }

                        fun debugSnapshot(maxChars: Int): Pair<Int, String> =
                            synchronized(outLock) {
                                val len = fullOut.length
                                val preview =
                                    if (len <= maxChars) {
                                        fullOut.toString()
                                    } else {
                                        fullOut.substring(0, maxChars.coerceAtLeast(0))
                                    }
                                len to preview
                            }

                        fun repeatedLoopDetected(delta: String): Boolean {
                            if (
                                delta.isBlank() ||
                                delta.length > REPEATED_DELTA_MAX_CHARS
                            ) {
                                repeatedDelta.set(null)
                                repeatedDeltaCount.set(0L)
                                return false
                            }

                            val count =
                                if (repeatedDelta.get() == delta) {
                                    repeatedDeltaCount.incrementAndGet()
                                } else {
                                    repeatedDelta.set(delta)
                                    repeatedDeltaCount.set(1L)
                                    1L
                                }

                            return count >= REPEATED_DELTA_LIMIT
                        }

                        fun recordTerminalFailure(reason: String, cause: Throwable) {
                            terminalFailure.compareAndSet(null, cause)
                            terminalFailureReason.compareAndSet(null, reason)
                        }

                        fun finalizeOnce(reason: String, cause: Throwable? = null) {
                            if (!finalized.compareAndSet(false, true)) return

                            repoScope.launch(Dispatchers.IO) {
                                val now = SystemClock.elapsedRealtime()
                                val inferenceStart = inferenceStartedAt.get()

                                val totalMs = now - requestStartedAt
                                val inferenceMs =
                                    if (inferenceStart > 0L) now - inferenceStart else -1L
                                val firstMs =
                                    firstTokenAt.get().let {
                                        if (it >= 0L && inferenceStart > 0L) it - inferenceStart else -1L
                                    }
                                val lastDeltaAgo =
                                    lastDeltaAt.get().let { if (it >= 0L) now - it else -1L }
                                val lastEventAgo =
                                    lastEventAt.get().let { if (it >= 0L) now - it else -1L }

                                val outText =
                                    synchronized(outLock) { fullOut.toString() }

                                AiTrace.ringI(
                                    TAG,
                                    "[$requestId] finalize reason='$reason' totalMs=$totalMs inferenceMs=$inferenceMs " +
                                            "firstTokenMs=$firstMs chunks=${chunks.get()} emittedChars=${emittedChars.get()} " +
                                            "out.len=${outText.length} capturedAll=${capturedAll.get()} " +
                                            "logicalDone=${logicalDone.get()} nativeTerminated=${nativeTerminated.get()} " +
                                            "cancelTag='${cancelTag.get()}' lastDeltaMsAgo=$lastDeltaAgo " +
                                            "lastEventMsAgo=$lastEventAgo"
                                )

                                val stats = buildString {
                                    appendLine("=== AI TRACE STATS (LiteRtRepository) ===")
                                    appendLine("rid=$requestId model='${model.name}' reason=$reason")
                                    appendLine("totalMs=$totalMs inferenceMs=$inferenceMs firstTokenMs=$firstMs")
                                    appendLine("logicalDone=${logicalDone.get()} logicalDoneAt=${logicalDoneAt.get()}")
                                    appendLine("nativeTerminated=${nativeTerminated.get()} cancelTag='${cancelTag.get()}'")
                                    appendLine("lastDeltaMsAgo=$lastDeltaAgo lastEventMsAgo=$lastEventAgo")
                                    appendLine(
                                        "chunks=${chunks.get()} emittedChars=${emittedChars.get()} " +
                                                "capturedAll=${capturedAll.get()} out.len=${outText.length}"
                                    )
                                    if (cause != null) {
                                        appendLine("--- exception ---")
                                        appendLine(Log.getStackTraceString(cause))
                                    }
                                    appendLine("=== OUTPUT (FULL) ===")
                                    append(outText)
                                    if (!capturedAll.get()) {
                                        appendLine("\n... (output capture truncated by MAX_CAPTURE_CHARS)")
                                    }
                                }

                                AiTrace.logLong(
                                    TAG,
                                    if (cause != null) Log.WARN else Log.DEBUG,
                                    "[$requestId] FINALIZE: $reason",
                                    stats
                                )
                                AiTrace.dumpToFile("litert", requestId, model.name, stats)
                            }
                        }

                        /**
                         * Close the caller-visible response stream without releasing
                         * AI_INFERENCE_GATE. Normal native completion may still need a
                         * Conversation replacement before the next request can run.
                         */
                        fun closeResponseStreamOnce() {
                            if (!responseStreamClosed.compareAndSet(false, true)) return

                            internalClose.set(true)
                            runCatching { out.close() }
                        }

                        fun closeOnce(reason: String, cause: Throwable? = null) {
                            if (!closed.compareAndSet(false, true)) return

                            internalClose.set(true)
                            finalizeOnce(reason, cause)

                            if (!completion.isCompleted) {
                                completion.complete(reason)
                            }

                            if (responseStreamClosed.compareAndSet(false, true)) {
                                runCatching {
                                    if (cause != null) out.close(cause) else out.close()
                                }
                            }
                        }

                        fun scheduleCancelGrace(
                            reason: String,
                            cause: Throwable? = null
                        ) {
                            if (cause != null) {
                                recordTerminalFailure(reason, cause)
                            }

                            if (!cancelGraceScheduled.compareAndSet(false, true)) return

                            repoScope.launch cancelGrace@{
                                delay(CANCEL_GRACE_TIMEOUT_MS)

                                if (closed.get() || nativeTerminated.get()) {
                                    return@cancelGrace
                                }

                                /*
                                 * Do not release AI_INFERENCE_GATE while the
                                 * native runtime may still be alive. After the
                                 * short grace period, escalate to the bounded
                                 * synchronous recovery teardown. This waits for
                                 * normal cancellation or LiteRtLM's hard-close
                                 * watchdog before returning.
                                 */
                                markForceReinit(
                                    "$reason-cancel-grace-expired"
                                )

                                val forceResult =
                                    runCatchingSuspend {
                                        runOnSlmThread {
                                            SLM.forceCleanUpAndWait(
                                                model = model,
                                            )
                                        }
                                    }

                                if (nativeFinishScheduled.get()) {
                                    /*
                                     * The native safepoint callback won the
                                     * race and owns final reset/close.
                                     */
                                    return@cancelGrace
                                }

                                val forceFailure =
                                    forceResult.exceptionOrNull()

                                if (forceFailure == null) {
                                    nativeTerminated.set(true)
                                    FORCE_REINIT.set(false)

                                    RuntimeLogStore.w(
                                        TAG,
                                        "[$requestId] forced recovery teardown " +
                                                "completed after grace timeout"
                                    )
                                    AiTrace.ringW(
                                        TAG,
                                        "[$requestId] forced recovery teardown " +
                                                "completed after grace timeout"
                                    )
                                } else {
                                    RuntimeLogStore.e(
                                        TAG,
                                        "[$requestId] forced recovery teardown " +
                                                "failed: ${forceFailure.message}",
                                        forceFailure
                                    )
                                    AiTrace.ringE(
                                        TAG,
                                        "[$requestId] forced recovery teardown " +
                                                "failed err=${forceFailure.message}",
                                        forceFailure
                                    )

                                    if (terminalFailure.get() == null) {
                                        recordTerminalFailure(
                                            "$reason-force-cleanup-failed",
                                            forceFailure
                                        )
                                    }
                                }

                                closeOnce(
                                    terminalFailureReason.get()
                                        ?: "$reason-cancel-grace-expired",
                                    terminalFailure.get()
                                )
                            }
                        }

                        cancelGraceHook.set(::scheduleCancelGrace)

                        /**
                         * Native cleanup is our preferred serialization safepoint.
                         * Reset while still holding the global gate, then release it.
                         */
                        fun finishAfterNativeSafepoint() {
                            if (
                                !nativeFinishScheduled.compareAndSet(
                                    false,
                                    true
                                )
                            ) {
                                return
                            }

                            nativeTerminated.set(true)

                            repoScope.launch {
                                val fullRecoveryRequired =
                                    FORCE_REINIT.get()

                                var repairFailed = false

                                try {
                                    if (fullRecoveryRequired) {
                                        /*
                                         * Cancellation and native errors can
                                         * poison a LiteRT-LM Conversation.
                                         * Close the entire runtime instead of
                                         * trying to reuse its Engine.
                                         */
                                        runOnSlmThread {
                                            SLM.forceCleanUpAndWait(
                                                model = model,
                                            )
                                        }

                                        FORCE_REINIT.set(false)

                                        RuntimeLogStore.d(
                                            TAG,
                                            "[$requestId] full runtime teardown " +
                                                    "completed after safepoint"
                                        )
                                        AiTrace.ringD(
                                            TAG,
                                            "[$requestId] full runtime teardown " +
                                                    "completed after safepoint"
                                        )
                                    } else {
                                        /*
                                         * Normal successful completion keeps
                                         * the warm Engine but recreates the
                                         * Conversation. The await variant is
                                         * mandatory here: the global inference
                                         * gate must remain held until the new
                                         * session is actually ready.
                                         */
                                        runOnSlmThread {
                                            SLM.resetConversationAndWait(
                                                model = model,
                                                supportImage = supportImage,
                                                supportAudio = supportAudio,
                                                systemMessage = systemMessage,
                                                tools = tools,
                                            )
                                        }

                                        RuntimeLogStore.d(
                                            TAG,
                                            "[$requestId] Conversation reset " +
                                                    "completed after safepoint"
                                        )
                                        AiTrace.ringD(
                                            TAG,
                                            "[$requestId] Conversation reset " +
                                                    "completed after safepoint"
                                        )
                                    }
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (t: Throwable) {
                                    repairFailed = true
                                    markForceReinit(
                                        "post-safepoint-repair-failed"
                                    )

                                    RuntimeLogStore.w(
                                        TAG,
                                        "[$requestId] post-safepoint repair " +
                                                "failed: ${t.message}",
                                        t
                                    )
                                    AiTrace.ringW(
                                        TAG,
                                        "[$requestId] post-safepoint repair " +
                                                "failed err=${t.message}",
                                        t
                                    )
                                } finally {
                                    closeOnce(
                                        terminalFailureReason.get()
                                            ?: if (repairFailed) {
                                                "native-terminated-repair-failed"
                                            } else {
                                                "native-terminated"
                                            },
                                        /*
                                         * A post-response repair failure does
                                         * not invalidate an already completed
                                         * response. Real inference failures are
                                         * preserved in terminalFailure.
                                         */
                                        terminalFailure.get()
                                    )
                                }
                            }
                        }

                        /**
                         * Repair a backend that previously failed before a trusted native
                         * termination point.
                         */
                        if (FORCE_REINIT.getAndSet(false)) {
                            /*
                             * The previous request failed before a trusted
                             * native safepoint. A Conversation-only reset is
                             * not sufficient because cancelProcess() may leave
                             * the session poisoned. Fully tear down the runtime;
                             * the normal initializeIfNeeded() path below will
                             * create a fresh Engine/Conversation.
                             */
                            val repairResult =
                                runCatchingSuspend {
                                    runOnSlmThread {
                                        SLM.forceCleanUpAndWait(
                                            model = model,
                                        )
                                    }
                                }

                            val repairFailure =
                                repairResult.exceptionOrNull()

                            if (repairFailure != null) {
                                markForceReinit(
                                    "pre-run-repair-failed"
                                )
                                closeOnce(
                                    "pre-run-repair-failed",
                                    repairFailure
                                )
                            } else {
                                RuntimeLogStore.d(
                                    TAG,
                                    "[$requestId] pre-run full teardown completed"
                                )
                                AiTrace.ringD(
                                    TAG,
                                    "[$requestId] pre-run full teardown completed"
                                )
                            }
                        }

                        if (collectorClosed.get() && !closed.get()) {
                            closeOnce("collector-cancelled-before-init")
                        }

                        val normalized = prompt.normalizePrompt()
                        val cappedPrompt =
                            capPromptIfNeeded(
                                normalized,
                                PROMPT_CHAR_CAP,
                                PROMPT_KEEP_HEAD_CHARS,
                                PROMPT_KEEP_TAIL_CHARS
                            )

                        val promptSha = AiTrace.sha256Short(cappedPrompt)
                        val gateWaitMs =
                            SystemClock.elapsedRealtime() - gateRequestedAt

                        RuntimeLogStore.d(
                            TAG,
                            "[$requestId] request start model='${model.name}' " +
                                    "prompt.len=${cappedPrompt.length} sha=$promptSha gateWaitMs=$gateWaitMs"
                        )
                        AiTrace.ringD(
                            TAG,
                            "[$requestId] start model='${model.name}' " +
                                    "prompt.len=${cappedPrompt.length} sha=$promptSha gateWaitMs=$gateWaitMs"
                        )
                        AiTrace.logLong(
                            TAG,
                            Log.DEBUG,
                            "[$requestId] PROMPT (FULL) sha=$promptSha",
                            cappedPrompt
                        )

                        if (!closed.get()) {
                            val ctx = appContext

                            if (ctx != null) {
                                val initT0 = SystemClock.elapsedRealtime()

                                val initAttempt: Result<Unit>? =
                                    withTimeoutOrNull(INIT_TIMEOUT_MS) {
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

                                val initMs =
                                    SystemClock.elapsedRealtime() - initT0

                                when {
                                    initAttempt == null -> {
                                        val e =
                                            RuntimeException(
                                                "SLM.initializeIfNeeded timed out after ${INIT_TIMEOUT_MS}ms"
                                            )
                                        markForceReinit("init-timeout")
                                        closeOnce("init-timeout", e)
                                    }

                                    initAttempt.isFailure -> {
                                        val e =
                                            initAttempt.exceptionOrNull()
                                                ?: RuntimeException("init-error")
                                        markForceReinit("init-error")
                                        closeOnce("init-error", e)
                                    }

                                    else -> {
                                        RuntimeLogStore.d(
                                            TAG,
                                            "[$requestId] initializeIfNeeded ok initMs=$initMs"
                                        )
                                        AiTrace.ringD(
                                            TAG,
                                            "[$requestId] init ok initMs=$initMs"
                                        )
                                    }
                                }
                            } else {
                                RuntimeLogStore.w(
                                    TAG,
                                    "[$requestId] appContext=null; assuming SLM already initialized"
                                )
                                AiTrace.ringW(
                                    TAG,
                                    "[$requestId] appContext=null; init skipped"
                                )
                            }
                        }

                        if (collectorClosed.get() && !closed.get()) {
                            markForceReinit("collector-cancelled-during-setup")
                            closeOnce("collector-cancelled-before-inference")
                        }

                        if (!closed.get()) {
                            val start = SystemClock.elapsedRealtime()
                            inferenceStartedAt.set(start)
                            firstTokenAt.set(-1L)
                            lastEventAt.set(start)
                            lastDeltaAt.set(start)
                            inferenceStarted.set(true)

                            /**
                             * Watchdogs start AFTER initialization. Otherwise a slow cold
                             * initialization can incorrectly trip first-token timeout.
                             */
                            repoScope.launch {
                                while (isActive && !closed.get()) {
                                    val now = SystemClock.elapsedRealtime()
                                    val elapsed = now - inferenceStartedAt.get()
                                    val hasToken = firstTokenAt.get() >= 0L

                                    when {
                                        elapsed >= HARD_WATCHDOG_MS -> {
                                            val r = "hard-watchdog-timeout"
                                            val e =
                                                RuntimeException(
                                                    "Inference exceeded ${HARD_WATCHDOG_MS}ms"
                                                )
                                            issueBackendCancel(r)
                                            markForceReinit(r)
                                            scheduleCancelGrace(r, e)
                                            break
                                        }

                                        !hasToken &&
                                                elapsed >= FIRST_TOKEN_TIMEOUT_MS -> {
                                            val r = "first-token-timeout"
                                            val e =
                                                RuntimeException(
                                                    "No first token within ${FIRST_TOKEN_TIMEOUT_MS}ms"
                                                )
                                            issueBackendCancel(r)
                                            markForceReinit(r)
                                            scheduleCancelGrace(r, e)
                                            break
                                        }

                                        hasToken &&
                                                !logicalDone.get() &&
                                                now - lastEventAt.get() >= EVENT_STALL_TIMEOUT_MS -> {
                                            val r = "event-stall-timeout"
                                            val e =
                                                RuntimeException(
                                                    "Inference stream stalled for ${EVENT_STALL_TIMEOUT_MS}ms"
                                                )
                                            issueBackendCancel(r)
                                            markForceReinit(r)
                                            scheduleCancelGrace(r, e)
                                            break
                                        }

                                        logicalDone.get() &&
                                                logicalDoneAt.get() > 0L &&
                                                now - logicalDoneAt.get() >= POST_DONE_TIMEOUT_MS -> {
                                            val r = "post-done-termination-timeout"
                                            issueBackendCancel(r)
                                            markForceReinit(r)
                                            scheduleCancelGrace(r)
                                            break
                                        }
                                    }

                                    delay(PROGRESS_POLL_MS)
                                }
                            }

                            var messageCount = 0L

                            try {
                                runOnSlmThread {
                                    SLM.runInference(
                                        model = model,
                                        input = cappedPrompt,
                                        resultListener = { partial, done ->
                                            if (
                                                closed.get() ||
                                                collectorClosed.get() ||
                                                logicalDone.get()
                                            ) {
                                                return@runInference
                                            }

                                            lastEventAt.set(SystemClock.elapsedRealtime())
                                            messageCount++

                                            val delta = normalizer.toDelta(partial)

                                            if (delta.isNotEmpty()) {
                                                if (repeatedLoopDetected(delta)) {
                                                    val r = "repeated-delta-loop"
                                                    val e =
                                                        IllegalStateException(
                                                            "Repeated model delta detected " +
                                                                    "${repeatedDeltaCount.get()} times"
                                                        )
                                                    issueBackendCancel(r)
                                                    markForceReinit(r)
                                                    scheduleCancelGrace(r, e)
                                                    return@runInference
                                                }

                                                val remaining =
                                                    OUTPUT_CHAR_HARD_CAP -
                                                            emittedChars.get()

                                                if (remaining <= 0L) {
                                                    val r = "output-char-limit"
                                                    val e =
                                                        IllegalStateException(
                                                            "Model output exceeded " +
                                                                    "$OUTPUT_CHAR_HARD_CAP characters"
                                                        )
                                                    issueBackendCancel(r)
                                                    markForceReinit(r)
                                                    scheduleCancelGrace(r, e)
                                                    return@runInference
                                                }

                                                val accepted =
                                                    if (delta.length.toLong() <= remaining) {
                                                        delta
                                                    } else {
                                                        delta.take(remaining.toInt())
                                                    }

                                                appendOutput(accepted)
                                                emittedChars.addAndGet(
                                                    accepted.length.toLong()
                                                )

                                                if (!out.trySend(accepted).isSuccess) {
                                                    collectorClosed.set(true)
                                                    issueBackendCancel("channel-closed")
                                                    markForceReinit("channel-closed")
                                                    scheduleCancelGrace("channel-closed")
                                                    return@runInference
                                                }

                                                if (accepted.length != delta.length) {
                                                    val r = "output-char-limit"
                                                    val e =
                                                        IllegalStateException(
                                                            "Model output exceeded " +
                                                                    "$OUTPUT_CHAR_HARD_CAP characters"
                                                        )
                                                    issueBackendCancel(r)
                                                    markForceReinit(r)
                                                    scheduleCancelGrace(r, e)
                                                    return@runInference
                                                }
                                            }

                                            if (
                                                DEBUG_STREAM &&
                                                (
                                                        messageCount == 1L ||
                                                                messageCount % DEBUG_STREAM_EVERY_N == 0L
                                                        )
                                            ) {
                                                val dPreview =
                                                    delta.take(DEBUG_PREFIX_CHARS)
                                                        .replace("\n", "\\n")
                                                val (outLen, previewRaw) =
                                                    debugSnapshot(DEBUG_PREFIX_CHARS)
                                                val preview =
                                                    previewRaw.replace("\n", "\\n")

                                                RuntimeLogStore.d(
                                                    TAG,
                                                    "stream[rid=$requestId msg#$messageCount] " +
                                                            "done=$done deltaLen=${delta.length} " +
                                                            "outLen=$outLen outPreview='$preview' " +
                                                            "deltaPreview='$dPreview'"
                                                )
                                            }

                                            /**
                                             * Process the final delta first, then latch done.
                                             * Any later result callback is ignored.
                                             */
                                            if (done) {
                                                if (
                                                    logicalDone.compareAndSet(
                                                        false,
                                                        true
                                                    )
                                                ) {
                                                    logicalDoneAt.set(
                                                        SystemClock.elapsedRealtime()
                                                    )
                                                }

                                                /*
                                                 * LiteRtLM emits done=true from its native
                                                 * terminal callback. For a normal response,
                                                 * finish the caller-visible Flow immediately
                                                 * instead of making UI completion wait for
                                                 * post-safepoint Conversation recreation. The
                                                 * repository-scoped driver keeps the global gate
                                                 * until repair is actually complete.
                                                 */
                                                if (
                                                    terminalFailure.get() == null &&
                                                    cancelTag.get() == null
                                                ) {
                                                    closeResponseStreamOnce()
                                                }

                                                RuntimeLogStore.d(
                                                    TAG,
                                                    "[$requestId] logical done=true; " +
                                                            "responseClosed=${responseStreamClosed.get()}; " +
                                                            "waiting native cleanup"
                                                )
                                                AiTrace.ringD(
                                                    TAG,
                                                    "[$requestId] logicalDone=true"
                                                )
                                            }
                                        },
                                        cleanUpListener = {
                                            lastEventAt.set(
                                                SystemClock.elapsedRealtime()
                                            )
                                            RuntimeLogStore.d(
                                                TAG,
                                                "[$requestId] native cleanup safepoint"
                                            )
                                            AiTrace.ringD(
                                                TAG,
                                                "[$requestId] native cleanup safepoint"
                                            )
                                            finishAfterNativeSafepoint()
                                        },
                                        onError = { message ->
                                            if (closed.get()) {
                                                return@runInference
                                            }

                                            lastEventAt.set(
                                                SystemClock.elapsedRealtime()
                                            )

                                            val msg =
                                                message.trim()

                                            val upper =
                                                msg.uppercase(Locale.US)

                                            val cancelled =
                                                upper.contains("CANCELLED") ||
                                                        upper.contains("CANCELED")

                                            val tag =
                                                cancelTag.get()

                                            if (cancelled && tag != null) {
                                                RuntimeLogStore.w(
                                                    TAG,
                                                    "[$requestId] onError(cancelled) " +
                                                            "tag='$tag' msg='$msg'"
                                                )

                                                scheduleCancelGrace(
                                                    reason = tag,
                                                )
                                                return@runInference
                                            }

                                            val error =
                                                RuntimeException(
                                                    msg.ifBlank {
                                                        "LiteRT-LM inference error"
                                                    }
                                                )

                                            /*
                                             * Do not release the global gate
                                             * directly from onError. The native
                                             * cleanup callback is the trusted
                                             * serialization safepoint. Record
                                             * the failure, request full runtime
                                             * repair, and wait for cleanup (or
                                             * the bounded grace fallback).
                                             */
                                            recordTerminalFailure(
                                                reason = "error",
                                                cause = error,
                                            )
                                            markForceReinit("onError")

                                            if (
                                                logicalDone.compareAndSet(
                                                    false,
                                                    true
                                                )
                                            ) {
                                                logicalDoneAt.set(
                                                    SystemClock.elapsedRealtime()
                                                )
                                            }

                                            scheduleCancelGrace(
                                                reason = "error",
                                                cause = error,
                                            )
                                        },
                                        onRunStarted = { runId ->
                                            val published =
                                                liteRtRunId.compareAndSet(0L, runId)

                                            if (!published && liteRtRunId.get() != runId) {
                                                RuntimeLogStore.e(
                                                    TAG,
                                                    "[$requestId] conflicting LiteRtLM run publication: " +
                                                            "current=${liteRtRunId.get()} received=$runId",
                                                )
                                                return@runInference
                                            }

                                            dispatchScopedCancelIfReady()
                                        },
                                    )
                                }
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (t: Throwable) {
                                RuntimeLogStore.e(
                                    TAG,
                                    "[$requestId] runInference threw: ${t.message}",
                                    t
                                )
                                AiTrace.ringE(
                                    TAG,
                                    "[$requestId] runInference threw err=${t.message}",
                                    t
                                )
                                markForceReinit("exception")
                                closeOnce("exception", t)
                            }
                        }

                        /**
                         * Keep the global permit until normal native cleanup, error, or
                         * bounded cancellation fallback calls closeOnce().
                         */
                        completion.await()
                    }
                } catch (ce: CancellationException) {
                    if (!collectorClosed.get()) {
                        runCatching { out.close(ce) }
                    }
                    throw ce
                } catch (t: Throwable) {
                    RuntimeLogStore.e(
                        TAG,
                        "[$requestId] inference driver failed: ${t.message}",
                        t
                    )
                    AiTrace.ringE(
                        TAG,
                        "[$requestId] inference driver failed err=${t.message}",
                        t
                    )
                    runCatching { out.close(t) }
                } finally {
                    gateActive.set(false)
                    inferenceStarted.set(false)
                    cancelGraceHook.set(null)
                }
            }

            awaitClose {
                /**
                 * Driver-initiated response close (normal logical completion or
                 * final close): no collector cancellation work is needed.
                 */
                if (internalClose.get()) {
                    return@awaitClose
                }

                collectorClosed.set(true)

                when {
                    /**
                     * Still waiting for the global gate. Cancel this driver only.
                     * Calling SLM.cancel here could kill another active request.
                     */
                    !gateActive.get() -> {
                        driverJob.cancel(
                            CancellationException(
                                "collector closed before AI gate acquisition"
                            )
                        )

                        RuntimeLogStore.d(
                            TAG,
                            "[$requestId] collector cancelled while waiting for AI gate"
                        )
                        AiTrace.ringD(
                            TAG,
                            "[$requestId] collector cancelled while waiting for AI gate; " +
                                    "backend untouched"
                        )
                    }

                    /**
                     * This request owns the engine and inference is running.
                     * Keep driverJob alive so the global gate remains held.
                     */
                    inferenceStarted.get() -> {
                        issueBackendCancel("collector-cancelled")
                        markForceReinit("collector-cancelled")
                        cancelGraceHook.get()?.invoke(
                            "collector-cancelled",
                            null
                        )

                        RuntimeLogStore.w(
                            TAG,
                            "[$requestId] collector cancelled; backend cancel requested " +
                                    "and gate retained until safepoint/grace timeout"
                        )
                    }

                    /**
                     * Gate owned but still in reset/init. Do not cancel the backend from
                     * another thread; the driver checks collectorClosed before inference.
                     */
                    else -> {
                        markForceReinit("collector-cancelled-during-setup")

                        RuntimeLogStore.w(
                            TAG,
                            "[$requestId] collector cancelled during setup; " +
                                    "waiting for safe setup exit"
                        )
                    }
                }
            }
        }
            .buffer(Channel.BUFFERED)
            .flowOn(Dispatchers.Default)
    }

}
