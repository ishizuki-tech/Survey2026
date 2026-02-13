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
import com.negi.survey.vm.AiViewModel
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
/**
 * Repository that streams inference results from an on-device LLM backend.
 */
interface Repository {

    /** Execute a single streaming inference for the given [prompt]. */
    suspend fun request(prompt: String): Flow<String>

    /** Build the full model-ready prompt string from a user-level [userPrompt]. */
    fun buildPrompt(userPrompt: String): String

    /**
     * Build the full model-ready prompt string for a specific [phase].
     *
     * NOTE:
     * - Default implementation keeps backward compatibility by delegating to the legacy method.
     */
    fun buildPrompt(userPrompt: String, phase: PromptPhase): String = buildPrompt(userPrompt)
}


/* ====================================================================== */
/*  Shared process-wide inference gate                                     */
/* ====================================================================== */

/**
 * Single process-wide gate used by all backends.
 *
 * Semantics:
 * - At most one active inference flow may run at once.
 * - The gate is held for the entire lifetime of the streaming Flow collection.
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

    /** Append with hard cap; returns false when truncated. */
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

    /** Short stable hash for prompt/output fingerprinting. */
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
            val end = minOf(lines.length, i + LOG_CHUNK)
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

private fun compactJoin(vararg parts: String): String {
    val list = buildList {
        parts.forEach { p ->
            val t = p.normalizePrompt()
            if (t.isNotBlank()) add(t)
        }
    }
    return list.joinToString("\n")
}

/* ====================================================================== */
/*  Stream chunk normalization                                             */
/* ====================================================================== */

/**
 * Some streaming APIs return either:
 * - DELTA chunks (new tokens)
 * - ACCUMULATED text (full text so far)
 *
 * This helper normalizes to DELTA output for Flow emission and capture.
 *
 * Notes:
 * - Avoids keeping the entire lastFull string to reduce memory pressure.
 * - Uses length + small samples to detect monotonic accumulated behavior.
 */
private class StreamDeltaNormalizer(
    private val modeHint: PartialMode = PartialMode.AUTO,
    private val prefixSampleChars: Int = 128,
    private val boundarySampleChars: Int = 64,
) {
    enum class PartialMode { AUTO, DELTA, ACCUMULATED }

    private var decided: PartialMode = modeHint

    private var lastLen: Int = 0
    private var prefixSample: String = ""
    private var boundarySample: String = ""

    // Used only in AUTO for the first decision; cap to avoid storing huge content.
    private var firstChunk: String? = null
    private var firstChunkLen: Int = 0

    fun toDelta(incoming: String): String {
        if (incoming.isEmpty()) return ""

        return when (decided) {
            PartialMode.DELTA -> incoming
            PartialMode.ACCUMULATED -> accumulatedDelta(incoming)
            PartialMode.AUTO -> autoDelta(incoming)
        }
    }

    private fun autoDelta(incoming: String): String {
        if (lastLen == 0) {
            seed(incoming, allowFirstChunk = true)
            return incoming
        }

        // Decide on second+ chunk.
        val looksAccumulated = looksLikeAccumulated(incoming)
        decided = if (looksAccumulated) PartialMode.ACCUMULATED else PartialMode.DELTA

        // Release AUTO helpers.
        firstChunk = null
        firstChunkLen = 0

        return if (decided == PartialMode.ACCUMULATED) {
            accumulatedDelta(incoming)
        } else {
            // DELTA: treat as raw delta.
            seed(incoming, allowFirstChunk = false)
            incoming
        }
    }

    private fun accumulatedDelta(incoming: String): String {
        if (lastLen == 0) {
            seed(incoming, allowFirstChunk = false)
            return incoming
        }

        // Basic monotonic assumption; verify with light samples.
        if (!looksLikeAccumulated(incoming)) {
            // Treat as reset.
            seed(incoming, allowFirstChunk = false)
            return incoming
        }

        val delta = if (incoming.length >= lastLen) incoming.substring(lastLen) else incoming
        seed(incoming, allowFirstChunk = false)
        return delta
    }

    private fun seed(text: String, allowFirstChunk: Boolean) {
        lastLen = text.length
        prefixSample = text.take(prefixSampleChars)
        boundarySample = text.takeLast(boundarySampleChars)

        if (allowFirstChunk) {
            val cap = 4_096
            firstChunk = if (text.length <= cap) text else null
            firstChunkLen = text.length
        }
    }

    private fun looksLikeAccumulated(incoming: String): Boolean {
        val fc = firstChunk
        if (fc != null && incoming.length >= firstChunkLen && incoming.startsWith(fc)) {
            return true
        }

        if (incoming.length < lastLen) return false
        if (prefixSample.isNotEmpty() && !incoming.startsWith(prefixSample)) return false

        if (boundarySample.isNotEmpty()) {
            val start = (lastLen - boundarySample.length).coerceAtLeast(0)
            val ok = incoming.regionMatches(start, boundarySample, 0, boundarySample.length, ignoreCase = false)
            if (!ok) return false
        }

        return true
    }
}

/* ====================================================================== */
/*  Shared defaults for prompt building                                    */
/* ====================================================================== */

private object PromptDefaults {

    const val USER_TURN_PREFIX = "<start_of_turn>user"
    const val MODEL_TURN_PREFIX = "<start_of_turn>model"
    const val TURN_END = "<end_of_turn>"
//    const val EMPTY_JSON_INSTRUCTION = "Respond with an empty JSON object: {}"
//    const val PREAMBLE =
//        "You are a well-known farmer survey expert. Read the Question and the Answer."
//    const val KEY_CONTRACT =
//        "OUTPUT FORMAT:\n" +
//                "- In English.\n" +
//                "- Keys:\n" +
//                "  • \"analysis\": short string\n" +
//                "  • \"expected answer\": short string\n" +
//                "  • \"follow-up question\": a single short confirm/validate question\n" +
//                "  • \"score\": integer 1–100\n" +
//                "FOLLOW-UP INTENT:\n" +
//                "- The follow-up must confirm or clarify the respondent's original answer to the SAME question.\n" +
//                "- Target the biggest uncertainty (unit/scale, missing number, time window, baseline, method).\n" +
//                "- Keep it single-scope and answerable immediately."
//    const val LENGTH_BUDGET =
//        "LENGTH LIMITS:\n" +
//                "- analysis<=80 chars\n" +
//                "- expected answer<=60 chars\n" +
//                "- follow-up question<=90 chars"
//    const val SCORING_RULE =
//        "SCORING RULE:\n" +
//                "- Judge ONLY content relevance/completeness/accuracy.\n" +
//                "- Do NOT penalize style or formatting."
//    const val STRICT_OUTPUT =
//        "STRICT OUTPUT (NO MARKDOWN):\n" +
//                "- RAW JSON only.\n" +
//                "- No extra text.\n" +
//                "- Prefer compact JSON.\n" +
//                "- Entire output should be short and machine-parseable."
}

/* ====================================================================== */
/*  LiteRtLM backend                                                      */
/* ====================================================================== */

/**
 * Primary repository implementation for LiteRtLM via [SLM] facade.
 *
 * This repository assumes:
 * - [SLM.setApplicationContext] is called early OR [appContext] is provided here.
 * - [SLM.initializeIfNeeded] is safe to call repeatedly (no-op when ready).
 */
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

        /** Overall safety cap for a single request (GPU cold start can be slow). */
        private const val HARD_WATCHDOG_MS = 120_000L

        /** Before first token arrives, tolerate a long warmup period. */
        private const val FIRST_TOKEN_TIMEOUT_MS = 45_000L

        /** After streaming begins, tolerate silence between callback events. */
        private const val EVENT_STALL_TIMEOUT_MS = 12_000L

        /** After logical done=true, wait for native termination callback. */
        private const val POST_DONE_TIMEOUT_MS = 30_000L

        private const val PROGRESS_POLL_MS = 250L

        private val DEBUG_STREAM: Boolean = BuildConfig.DEBUG
        private const val DEBUG_STREAM_EVERY_N = 8
        private const val DEBUG_PREFIX_CHARS = 180

        /** Prompt size safety caps. */
        private const val PROMPT_CHAR_CAP: Int = 120_000
        private const val PROMPT_KEEP_TAIL_CHARS: Int = 24_000
    }

    init {
        appContext?.let { AiTrace.install(it) }
    }

    /**
     * Sanitize turn tokens: keep them single-line and non-empty.
     *
     * - Prevents accidental "\n" inside tokens breaking the prompt format.
     * - Trims whitespace and collapses internal whitespace.
     */
    private fun sanitizeTurnToken(value: String?, fallback: String): String {
        val raw = (value ?: fallback)
        val cleaned = raw
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .lines()
            .joinToString(" ") { it.trim() }
            .trim()
            .replace(Regex("\\s+"), " ")
        return if (cleaned.isBlank()) fallback else cleaned
    }

    /**
     * Cap prompt size defensively.
     *
     * Strategy:
     * - Keep the tail (most recent/user content typically lives near the end).
     * - Prefix a short truncation marker so logs/debugging are honest.
     */
    private fun capPromptIfNeeded(prompt: String, maxChars: Int, keepTailChars: Int): String {
        if (prompt.length <= maxChars) return prompt

        val keep = keepTailChars.coerceIn(4_096, maxChars)
        val tail = prompt.takeLast(keep)
        val dropped = prompt.length - keep

        // English-only marker to keep LLM instruction language consistent.
        val marker = "[TRUNCATED: dropped=$dropped chars; kept_last=$keep]\n"

        val out = marker + tail
        return if (out.length <= maxChars) out else out.takeLast(maxChars)
    }

//    override fun buildPrompt(userPrompt: String): String {
//        fun normalize(s: String): String =
//            s.replace("\r\n", "\n")
//                .replace("\r", "\n")
//                .trimEnd('\n')
//
//        fun joinNonBlank(vararg parts: String): String =
//            parts.asSequence()
//                .map { normalize(it) }
//                .filter { it.isNotBlank() }
//                .joinToString("\n")
//
//        val slm = config.slm
//
//        val userTurn = sanitizeTurnToken(slm.userTurnPrefix, PromptDefaults.USER_TURN_PREFIX)
//        val modelTurn = sanitizeTurnToken(slm.modelTurnPrefix, PromptDefaults.MODEL_TURN_PREFIX)
//        val turnEnd = sanitizeTurnToken(slm.turnEnd, PromptDefaults.TURN_END)
//
////        val emptyJson = normalize(slm.emptyJsonInstruction ?: PromptDefaults.EMPTY_JSON_INSTRUCTION)
////        val preamble = normalize(slm.preamble ?: PromptDefaults.PREAMBLE)
////        val keyContract = normalize(slm.keyContract ?: PromptDefaults.KEY_CONTRACT)
////        val lengthBudget = normalize(slm.lengthBudget ?: PromptDefaults.LENGTH_BUDGET)
////        val scoringRule = normalize(slm.scoringRule ?: PromptDefaults.SCORING_RULE)
////        val strictOutput = normalize(slm.strictOutput ?: PromptDefaults.STRICT_OUTPUT)
//
//        val emptyJson = normalize(slm.emptyJsonInstruction ?: "")
//        val preamble = normalize(slm.preamble ?: "")
//        val keyContract = normalize(slm.keyContract ?: "")
//        val lengthBudget = normalize(slm.lengthBudget ?: "")
//        val scoringRule = normalize(slm.scoringRule ?: "")
//        val strictOutput = normalize(slm.strictOutput ?: "")
//        val effectiveInput = if (userPrompt.isBlank()) emptyJson else normalize(userPrompt.trimIndent())
//        val labeledInput = joinNonBlank("INPUT:", effectiveInput)
//        val userBlock = joinNonBlank(
//            preamble,
//            keyContract,
//            lengthBudget,
//            scoringRule,
//            strictOutput,
//            labeledInput
//        )
//        val fullPrompt = joinNonBlank(
//            userTurn,
//            userBlock,
//            turnEnd,
//            modelTurn
//        )
//
//        val capped = capPromptIfNeeded(fullPrompt, PROMPT_CHAR_CAP, PROMPT_KEEP_TAIL_CHARS)
//
////        val sha = AiTrace.sha256Short(capped)
////        Log.d(TAG, "buildPrompt: in.len=${userPrompt.length}, out.len=${capped.length}, sha=$sha (capped=${capped.length != fullPrompt.length})")
////
////        if (BuildConfig.DEBUG && AiTrace.enabled) {
////            AiTrace.logLong(TAG, Log.DEBUG, "[buildPrompt] PROMPT (FULL) sha=$sha", capped)
////        } else if (BuildConfig.DEBUG) {
////            val head = capped.take(420).replace("\n", "\\n")
////            val tail = capped.takeLast(260).replace("\n", "\\n")
////            Log.d(TAG, "buildPrompt preview(head): '$head' ...")
////            Log.d(TAG, "buildPrompt preview(tail): ... '$tail'")
////        }
//
//        return capped
//    }

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

        val userTurn = sanitizeTurnToken(slm.userTurnPrefix, PromptDefaults.USER_TURN_PREFIX)
        val modelTurn = sanitizeTurnToken(slm.modelTurnPrefix, PromptDefaults.MODEL_TURN_PREFIX)
        val turnEnd = sanitizeTurnToken(slm.turnEnd, PromptDefaults.TURN_END)

        // Phase-specific system prompt.
        val systemPrompt = when (phase) {
            PromptPhase.ONE_STEP -> config.composeSystemPromptOneStep()
            PromptPhase.EVAL -> config.composeSystemPromptEval()
            PromptPhase.FOLLOWUP -> config.composeSystemPromptFollowup()
        }.let(::normalize)

        // Keep legacy behavior: if user prompt is blank, inject empty-json instruction.
        // (Typically not used for FOLLOWUP because you always pass EVAL_JSON there.)
        val emptyJson = normalize(slm.emptyJsonInstruction ?: "")
        val effectiveInput = if (userPrompt.isBlank()) emptyJson else normalize(userPrompt.trimIndent())

        val labeledInput = joinNonBlank("INPUT:", effectiveInput)

        val fullPrompt = joinNonBlank(
            userTurn,
            systemPrompt,
            labeledInput,
            turnEnd,
            modelTurn
        )

        return capPromptIfNeeded(fullPrompt, PROMPT_CHAR_CAP, PROMPT_KEEP_TAIL_CHARS)
    }


    @OptIn(DelicateCoroutinesApi::class)
    override suspend fun request(prompt: String): Flow<String> {
        return callbackFlow {
            val out = this
            val requestId = REQ_SEQ.incrementAndGet()
            val gateReqAt = SystemClock.elapsedRealtime()

            AI_INFERENCE_GATE.withPermit {
                val gateWaitMs = SystemClock.elapsedRealtime() - gateReqAt

                val anchorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                val closed = AtomicBoolean(false)
                val finalized = AtomicBoolean(false)

                val startAt = AtomicLong(SystemClock.elapsedRealtime())

                /** First real emitted delta timestamp. -1 means "no token yet". */
                val firstTokenAt = AtomicLong(-1L)

                /** Updated when any callback event arrives (including empty deltas). */
                val lastEventAt = AtomicLong(startAt.get())

                /** Updated only when a non-empty delta is produced. */
                val lastDeltaAt = AtomicLong(startAt.get())

                /** Logical completion flag (done=true). Still wait for native termination if possible. */
                val logicalDone = AtomicBoolean(false)
                val logicalDoneAt = AtomicLong(-1L)

                /** Set when cancellation/recovery is initiated by us. */
                val cancelTag = AtomicReference<String?>(null)

                val chunks = AtomicLong(0L)
                val capturedAll = AtomicBoolean(true)

                // IMPORTANT: StringBuilder is NOT thread-safe.
                // Callbacks and watchdog may arrive on different threads.
                val outLock = Any()
                val fullOut = StringBuilder(8 * 1024)

                val normalizer = StreamDeltaNormalizer(StreamDeltaNormalizer.PartialMode.AUTO)

                fun markEvent() {
                    lastEventAt.set(SystemClock.elapsedRealtime())
                }

                val emitCh = Channel<String>(capacity = Channel.BUFFERED)
                val emitterJob = anchorScope.launch {
                    for (chunk in emitCh) {
                        if (chunk.isNotEmpty() && !out.isClosedForSend) {
                            out.trySend(chunk)
                        }
                    }
                }

                fun appendOutput(delta: String) {
                    if (delta.isEmpty()) return
                    val now = SystemClock.elapsedRealtime()
                    if (firstTokenAt.get() < 0L) firstTokenAt.compareAndSet(-1L, now)
                    lastDeltaAt.set(now)

                    chunks.incrementAndGet()

                    val ok = synchronized(outLock) {
                        AiTrace.capAppend(fullOut, delta)
                    }
                    if (!ok) capturedAll.set(false)
                }

                fun snapshotOutput(): String {
                    return synchronized(outLock) { fullOut.toString() }
                }

                fun finalizeOnce(reason: String, cause: Throwable? = null) {
                    if (!finalized.compareAndSet(false, true)) return

                    val now = SystemClock.elapsedRealtime()
                    val elapsedMs = now - startAt.get()
                    val firstMs = firstTokenAt.get().let { if (it < 0L) -1L else (it - startAt.get()) }
                    val lastDeltaMsAgo = now - lastDeltaAt.get()
                    val lastEventMsAgo = now - lastEventAt.get()

                    val outText = snapshotOutput()

                    val stats = buildString {
                        appendLine("=== AI TRACE STATS (LiteRtRepository) ===")
                        appendLine("rid=$requestId model='${model.name}' reason=$reason")
                        appendLine("gateWaitMs=$gateWaitMs elapsedMs=$elapsedMs firstTokenMs=$firstMs")
                        appendLine("logicalDone=${logicalDone.get()} logicalDoneAt=${logicalDoneAt.get()}")
                        appendLine("lastDeltaMsAgo=$lastDeltaMsAgo lastEventMsAgo=$lastEventMsAgo cancelTag='${cancelTag.get()}'")
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

                fun closeOnce(reason: String, cause: Throwable? = null) {
                    if (!closed.compareAndSet(false, true)) return

                    finalizeOnce(reason, cause)

                    runCatching { emitCh.close() }
                    runCatching { emitterJob.cancel() }
                    runCatching { anchorScope.cancel(CancellationException("closeOnce: $reason")) }

                    runCatching {
                        if (cause != null) out.close(cause) else out.close()
                    }
                }

                fun bestEffortRecover(tag: String, aggressive: Boolean) {
                    cancelTag.compareAndSet(null, tag)

                    runCatching { SLM.cancel(model) }
                        .onFailure { Log.w(TAG, "[$requestId] cancel failed ($tag): ${it.message}", it) }

                    runCatching {
                        SLM.resetConversation(
                            model = model,
                            supportImage = supportImage,
                            supportAudio = supportAudio,
                            systemMessage = systemMessage,
                            tools = tools,
                        )
                    }.onFailure { Log.w(TAG, "[$requestId] resetConversation failed ($tag): ${it.message}", it) }

                    if (aggressive) {
                        runCatching {
                            SLM.forceCleanUp(model) {
                                Log.d(TAG, "[$requestId] forceCleanUp done ($tag)")
                            }
                        }.onFailure { Log.w(TAG, "[$requestId] forceCleanUp failed ($tag): ${it.message}", it) }
                    } else {
                        runCatching {
                            SLM.cleanUp(model) {
                                Log.d(TAG, "[$requestId] cleanUp scheduled ($tag)")
                            }
                        }.onFailure { Log.w(TAG, "[$requestId] cleanUp failed ($tag): ${it.message}", it) }
                    }
                }

                // Normalize + cap prompt used for inference (defensive).
                val normalized = prompt.normalizePrompt()
                val cappedPrompt = capPromptIfNeeded(normalized, PROMPT_CHAR_CAP, PROMPT_KEEP_TAIL_CHARS)
                val promptSha = AiTrace.sha256Short(cappedPrompt)

                Log.d(
                    TAG,
                    "[$requestId] request start: model='${model.name}', prompt.len=${cappedPrompt.length}, sha=$promptSha, gateWaitMs=$gateWaitMs"
                )
                AiTrace.logLong(TAG, Log.DEBUG, "[$requestId] PROMPT (FULL) sha=$promptSha", cappedPrompt)

                // Watchdog: warmup timeout / event stall / missing termination callback.
                anchorScope.launch {
                    while (isActive && !closed.get()) {
                        val now = SystemClock.elapsedRealtime()
                        val elapsed = now - startAt.get()
                        val hasAnyToken = firstTokenAt.get() >= 0L

                        if (elapsed >= HARD_WATCHDOG_MS) {
                            val reason = "hard-watchdog-timeout"
                            Log.w(TAG, "[$requestId] $reason (${elapsed}ms) → recover/close")
                            bestEffortRecover(reason, aggressive = true)
                            closeOnce(reason)
                            break
                        }

                        if (!hasAnyToken && elapsed >= FIRST_TOKEN_TIMEOUT_MS) {
                            val reason = "first-token-timeout"
                            Log.w(TAG, "[$requestId] $reason (${elapsed}ms) → recover/close")
                            bestEffortRecover(reason, aggressive = true)
                            closeOnce(reason)
                            break
                        }

                        if (hasAnyToken && !logicalDone.get()) {
                            val stalled = now - lastEventAt.get()
                            if (stalled >= EVENT_STALL_TIMEOUT_MS) {
                                val reason = "event-stall-timeout"
                                Log.w(TAG, "[$requestId] $reason (${stalled}ms) → recover/close")
                                bestEffortRecover(reason, aggressive = true)
                                closeOnce(reason)
                                break
                            }
                        }

                        if (logicalDone.get()) {
                            val doneAt = logicalDoneAt.get()
                            if (doneAt > 0L) {
                                val afterDone = now - doneAt
                                if (afterDone >= POST_DONE_TIMEOUT_MS) {
                                    val reason = "post-done-termination-timeout"
                                    Log.w(TAG, "[$requestId] $reason (${afterDone}ms) → recover/close")
                                    bestEffortRecover(reason, aggressive = true)
                                    closeOnce(reason)
                                    break
                                }
                            }
                        }

                        delay(PROGRESS_POLL_MS)
                    }
                }

                val ctx = appContext
                if (ctx != null) {
                    val initAttempt = withTimeoutOrNull(INIT_TIMEOUT_MS) {
                        runCatching {
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

                    when {
                        initAttempt == null -> {
                            val msg = "SLM.initializeIfNeeded timed out after ${INIT_TIMEOUT_MS}ms"
                            Log.e(TAG, "[$requestId] $msg")
                            bestEffortRecover("init-timeout", aggressive = true)
                            closeOnce("init-timeout", RuntimeException(msg))
                            return@withPermit
                        }

                        initAttempt.isFailure -> {
                            val e = initAttempt.exceptionOrNull()
                            Log.e(TAG, "[$requestId] initializeIfNeeded failed: ${e?.message}", e)
                            bestEffortRecover("init-error", aggressive = true)
                            closeOnce("init-error", e ?: RuntimeException("init-error"))
                            return@withPermit
                        }

                        else -> {
                            Log.d(TAG, "[$requestId] initializeIfNeeded ok")
                        }
                    }
                } else {
                    Log.d(TAG, "[$requestId] appContext=null → skip initializeIfNeeded (assume already initialized)")
                }

                var msgCount = 0

                try {
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
                                val r = emitCh.trySend(delta)
                                if (r.isFailure && DEBUG_STREAM) {
                                    Log.w(TAG, "[$requestId] emitCh.trySend failed: ${r.exceptionOrNull()?.message}")
                                }
                            }

                            if (DEBUG_STREAM && (msgCount == 1 || msgCount % DEBUG_STREAM_EVERY_N == 0)) {
                                val dPreview = delta.take(DEBUG_PREFIX_CHARS).replace("\n", "\\n")
                                val sPreview = snapshotOutput().take(DEBUG_PREFIX_CHARS).replace("\n", "\\n")
                                Log.d(
                                    TAG,
                                    "stream[rid=$requestId msg#$msgCount] done=$done " +
                                            "deltaLen=${delta.length} outLen=${snapshotOutput().length} " +
                                            "outPreview='$sPreview' deltaPreview='$dPreview'"
                                )
                            }

                            if (done) {
                                if (logicalDone.compareAndSet(false, true)) {
                                    logicalDoneAt.set(SystemClock.elapsedRealtime())
                                }
                                Log.d(TAG, "[$requestId] logical done=true (waiting cleanUpListener)")
                            }
                        },
                        cleanUpListener = {
                            if (closed.get()) return@runInference
                            markEvent()
                            Log.d(TAG, "[$requestId] cleanUpListener (native termination safe point)")
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
                                Log.w(TAG, "[$requestId] onError(cancelled): '$msg' tag='$tag' → close without exception")
                                closeOnce(tag)
                                return@runInference
                            }

                            Log.e(TAG, "[$requestId] onError: '$msg'")
                            bestEffortRecover("onError", aggressive = true)
                            closeOnce("error", RuntimeException(msg))
                        }
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "[$requestId] runInference threw: ${t.message}", t)
                    bestEffortRecover("exception", aggressive = true)
                    closeOnce("exception", t)
                }

                // IMPORTANT: awaitClose must always run to clean up resources.
                awaitClose {
                    if (!closed.get()) {
                        val reason = "collector-cancel"
                        Log.d(TAG, "[$requestId] awaitClose: collector-cancel → recover/close")
                        cancelTag.compareAndSet(null, reason)
                        bestEffortRecover(reason, aggressive = true)
                        closeOnce(reason)
                    }
                }
            }
        }
            .buffer(Channel.BUFFERED)
            .flowOn(Dispatchers.Default)
    }
}
