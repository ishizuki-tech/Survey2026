/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: LiteRtLM.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.slm

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.negi.survey.BuildConfig
import java.io.ByteArrayOutputStream
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "LiteRtLM"

/** Upper bound for error strings rendered in UI/log aggregation. */
private const val ERROR_MAX_CHARS = 280

/** Absolute cap for maxNumTokens. */
private const val ABS_MAX_NUM_TOKENS = 4096

private const val DEFAULT_TOPK = 40
private const val DEFAULT_TOPP = 0.9f
private const val DEFAULT_TEMPERATURE = 0.7f

/** Idle cleanup delay. */
private const val IDLE_CLEANUP_MS = 120_000L

/** Native close grace windows. */
private const val CLOSE_GRACE_MS = 5_000L
private const val RETIRED_CLOSE_GRACE_MS = 1_500L

/** Post-terminate cooldown to avoid rapid restart during native teardown. */
private const val POST_TERMINATE_COOLDOWN_MS = 250L

/** Init await timeout. */
private const val INIT_AWAIT_TIMEOUT_MS = 90_000L

/** Streaming watchdog. */
private const val STREAM_WATCHDOG_MS = 120_000L

/** Emergency hard-close watchdog. */
private const val HARD_CLOSE_TIMEOUT_MS = 15_000L
private const val HARD_CLOSE_POLL_MS = 750L
private const val HARD_CLOSE_ENABLE = true

/** Streaming debug toggles. */
private val DEBUG_STREAM: Boolean = BuildConfig.DEBUG
private const val DEBUG_STREAM_EVERY_N = 16
private const val DEBUG_PREFIX_CHARS = 24

/** Text extraction debug toggles. */
private val DEBUG_EXTRACT: Boolean = BuildConfig.DEBUG
private const val DEBUG_EXTRACT_EVERY_N = 64

/** Throwable debug toggles. */
private val DEBUG_ERROR_THROWABLE: Boolean = BuildConfig.DEBUG
private const val DEBUG_ERROR_STACK_LINES = 18

/**
 * Holder for a LiteRT-LM Engine and its active Conversation.
 *
 * IMPORTANT:
 * - Do not close engine/conversation while native stream may still be active.
 */
data class LiteRtLmInstance(
    val engine: Engine,
    @Volatile var conversation: Conversation,
    val supportImage: Boolean,
    val supportAudio: Boolean,
    val engineConfigSnapshot: EngineConfig,
)

/**
 * LiteRT-LM integration singleton.
 */
object LiteRtLM {

    /** Main thread handler for UI-safe callbacks. */
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    /** Dedicated IO scope for init/cleanup work. */
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Global lock for instance map + lifecycle transitions. */
    private val stateMutex: Mutex = Mutex()

    /** Runtime instances keyed by runtimeKey(model). */
    private val instances: MutableMap<String, LiteRtLmInstance> = ConcurrentHashMap()

    /** Pending actions to execute once the native stream terminates. */
    private val pendingAfterStream: MutableMap<String, MutableList<() -> Unit>> = ConcurrentHashMap()

    /** Prevent concurrent init for the same key. */
    private val initInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Per-key init completion signal.
     *
     * Contract:
     * - Completes with "" on success
     * - Completes with non-empty string on failure
     *
     * NOTE:
     * - We keep completed signals in the map (replaced on next init attempt) to avoid
     *   "initInFlight=true but signal missing" race windows.
     */
    private val initSignals: ConcurrentHashMap<String, CompletableDeferred<String>> = ConcurrentHashMap()

    /** Serialize initializeIfNeeded() and generateText(). */
    private val apiMutex: Mutex = Mutex()

    /** Busy flag used only by generateText() (suspend API). */
    private val busy: AtomicBoolean = AtomicBoolean(false)

    /** Scheduled idle cleanup jobs (per key). */
    private val cleanupJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

    /** Stored application context for best-effort auto re-init inside runInference(). */
    private val appContextRef: AtomicReference<Context?> = AtomicReference(null)

    /** Extractor debug counter. */
    private val extractDebugCounter: AtomicLong = AtomicLong(0L)

    /** Returns true when a generateText call is currently in progress. */
    fun isBusy(): Boolean = busy.get()

    /** Stable runtime key. */
    private fun runtimeKey(model: Model): String = "${model.name}|${model.taskPath}"

    /** Post work onto the main thread. */
    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** Allow host app to set context early. */
    fun setApplicationContext(context: Context) {
        appContextRef.set(context.applicationContext)
    }

    /**
     * Per-key run state (native lifecycle + logical completion + cancel).
     */
    private data class RunState(
        val active: AtomicBoolean = AtomicBoolean(false),
        val terminated: AtomicBoolean = AtomicBoolean(false),
        val logicalDone: AtomicBoolean = AtomicBoolean(false),
        val cancelRequested: AtomicBoolean = AtomicBoolean(false),
        val pendingCancel: AtomicBoolean = AtomicBoolean(false),
        val runId: AtomicLong = AtomicLong(0L),
        val lastTerminateAtMs: AtomicLong = AtomicLong(0L),
        val lastUseAtMs: AtomicLong = AtomicLong(0L),
        val lastMessageAtMs: AtomicLong = AtomicLong(0L),
        val cooldownUntilMs: AtomicLong = AtomicLong(0L),
        val logicalTerminator: AtomicReference<(() -> Unit)?> = AtomicReference(null),
        val hardCloseRunning: AtomicBoolean = AtomicBoolean(false),
        val cleanupToken: AtomicLong = AtomicLong(0L),

        /**
         * Hook invoked after native termination (onDone/onError), OR after hard-close watchdog.
         * Must be set per active run, and cleared after firing.
         */
        val nativeDoneHook: AtomicReference<(() -> Unit)?> = AtomicReference(null),
    )

    private val runStates: ConcurrentHashMap<String, RunState> = ConcurrentHashMap()

    /** Get or create per-key run state (thread-safe). */
    private fun getRunState(key: String): RunState {
        val existing = runStates[key]
        if (existing != null) return existing
        val created = RunState()
        val prev = runStates.putIfAbsent(key, created)
        return prev ?: created
    }

    /** Touch last-use time and invalidate any scheduled cleanup. */
    private fun markUsed(key: String) {
        val now = SystemClock.elapsedRealtime()
        val rs = getRunState(key)
        rs.lastUseAtMs.set(now)
        rs.cleanupToken.incrementAndGet()
    }

    /** Cancel any scheduled idle cleanup for this key. */
    private fun cancelScheduledCleanup(key: String, reason: String) {
        val job = cleanupJobs.remove(key)
        if (job != null) {
            if (job.isActive) {
                job.cancel()
                Log.d(TAG, "Idle cleanup cancelled: key='$key' reason='$reason'")
            } else {
                Log.d(TAG, "Idle cleanup cleared: key='$key' reason='$reason'")
            }
        }
    }

    /** Schedule an idle cleanup (debounced + token-guarded). */
    private fun scheduleIdleCleanup(key: String, delayMs: Long, reason: String) {
        cancelScheduledCleanup(key, "reschedule:$reason")
        val tokenAtSchedule = getRunState(key).cleanupToken.get()

        val job = ioScope.launch {
            try {
                Log.d(TAG, "Idle cleanup scheduled: key='$key' in ${delayMs}ms reason='$reason'")
                delay(delayMs)
                closeInstanceIfStillIdle(
                    key = key,
                    requiredIdleMs = delayMs,
                    requiredToken = tokenAtSchedule,
                    reason = "idle:$reason"
                )
            } finally {
                cleanupJobs.remove(key)
            }
        }
        cleanupJobs[key] = job
    }

    /** Get or create a per-key init signal (never returns a completed one). */
    private fun getOrCreateInitSignal(key: String): CompletableDeferred<String> {
        while (true) {
            val existing = initSignals[key]
            if (existing != null && !existing.isCompleted) return existing

            val created = CompletableDeferred<String>()
            val prev = initSignals.putIfAbsent(key, created)
            if (prev == null) return created

            if (prev.isCompleted) {
                val replaced = initSignals.replace(key, prev, created)
                if (replaced) return created
            } else {
                return prev
            }
        }
    }

    /** Complete an init signal safely (kept in map; replaced on next init attempt). */
    private fun completeInitSignal(signal: CompletableDeferred<String>, error: String) {
        if (!signal.isCompleted) signal.complete(error)
    }

    /** Normalize accelerator string for stable backend selection. */
    private fun normalizedAccelerator(model: Model): String {
        return model.getStringConfigValue(ConfigKey.ACCELERATOR, Accelerator.GPU.label)
            .trim()
            .uppercase(Locale.US)
            .ifBlank { Accelerator.GPU.label }
    }

    /** Resolve preferred backend from model config. */
    private fun preferredBackend(model: Model): Backend {
        return when (normalizedAccelerator(model)) {
            Accelerator.CPU.label -> Backend.CPU
            Accelerator.GPU.label -> Backend.GPU
            else -> Backend.GPU
        }
    }

    /** Sanitize TopK - must be >= 1. */
    private fun sanitizeTopK(k: Int): Int = k.coerceAtLeast(1)

    /** Sanitize TopP - must be in [0, 1]. */
    private fun sanitizeTopP(p: Float): Float = p.takeIf { it in 0f..1f } ?: DEFAULT_TOPP

    /** Sanitize Temperature - typical safe band [0, 2]. */
    private fun sanitizeTemperature(t: Float): Float = t.takeIf { it in 0f..2f } ?: DEFAULT_TEMPERATURE

    /** Clean and compress error messages for UI/logging. */
    private fun cleanError(msg: String?): String {
        return msg
            ?.replace("INTERNAL:", "", ignoreCase = true)
            ?.replace("\\s+".toRegex(), " ")
            ?.trim()
            ?.take(ERROR_MAX_CHARS)
            ?.takeIf { it.isNotEmpty() }
            ?: "Unknown error"
    }

    /** Build a short stack string for logs. */
    private fun shortStack(t: Throwable, maxLines: Int = DEBUG_ERROR_STACK_LINES): String {
        val lines = t.stackTrace.take(maxLines).joinToString(separator = "\n") { "  at $it" }
        val cause = t.cause
        val causeLine = if (cause != null) "\nCaused by: ${cause::class.java.name}: ${cause.message}" else ""
        return "${t::class.java.name}: ${t.message}\n$lines$causeLine"
    }

    /**
     * Try to extract a "status code" (or similar) from Throwable using reflection.
     *
     * This is intentionally defensive because SDK versions differ.
     */
    private fun extractStatusCodeBestEffort(t: Throwable): Int? {
        val methodNames = listOf(
            "getStatusCode",
            "statusCode",
            "getCode",
            "code",
            "getErrorCode",
            "errorCode",
        )
        for (name in methodNames) {
            val m = runCatching {
                t.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterCount == 0 &&
                            (it.returnType == Int::class.javaPrimitiveType || it.returnType == Int::class.javaObjectType)
                }
            }.getOrNull() ?: continue

            val v = runCatching { m.invoke(t) as? Int }.getOrNull()
            if (v != null) return v
        }

        val fieldNames = listOf("statusCode", "code", "errorCode")
        for (fn in fieldNames) {
            val f = runCatching { t.javaClass.getDeclaredField(fn) }.getOrNull() ?: continue
            runCatching { f.isAccessible = true }
            val v = runCatching { f.get(t) }.getOrNull()
            if (v is Int) return v
        }

        val c = t.cause
        if (c != null && c !== t) return extractStatusCodeBestEffort(c)

        return null
    }

    /** Detect cancellation from throwable/message. */
    private fun isCancellationThrowable(t: Throwable, msg: String): Boolean {
        if (t is CancellationException) return true
        val lc = msg.lowercase(Locale.US)
        if (lc.contains("cancel")) return true
        if (lc.contains("canceled")) return true
        if (lc.contains("cancelled")) return true
        if (lc.contains("aborted") && lc.contains("user")) return true
        return false
    }

    /** Convert this Bitmap to PNG bytes. */
    private fun Bitmap.toPngByteArray(): ByteArray {
        return ByteArrayOutputStream().use { stream ->
            compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
    }

    /** Build Content list for a single message (multimodal first, then text). */
    private fun buildContentList(
        input: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
    ): List<Content> {
        val contents = mutableListOf<Content>()
        for (image in images) contents.add(Content.ImageBytes(image.toPngByteArray()))
        for (audio in audioClips) contents.add(Content.AudioBytes(audio))
        val t = input.trim()
        if (t.isNotEmpty()) contents.add(Content.Text(t))
        return contents
    }

    /**
     * Build a Contents object from a List<Content> with reflection.
     *
     * We avoid compile-time dependency on a specific Contents factory/ctor,
     * because LiteRT-LM SDK has changed APIs across versions.
     */
    private fun buildContentsObject(contents: List<Content>): Contents {
        val cls = Contents::class.java

        runCatching {
            val ctor = cls.constructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 1 && List::class.java.isAssignableFrom(p[0])
            } ?: return@runCatching null
            (ctor.newInstance(contents) as Contents)
        }.getOrNull()?.let { return it }

        runCatching {
            val ctor = cls.constructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 1 && p[0].isArray
            } ?: return@runCatching null
            val arr = contents.toTypedArray()
            (ctor.newInstance(arr) as Contents)
        }.getOrNull()?.let { return it }

        runCatching {
            val m = cls.methods.firstOrNull { m ->
                (m.name == "of" || m.name == "from" || m.name == "create") &&
                        Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 1 &&
                        (m.parameterTypes[0].isArray || List::class.java.isAssignableFrom(m.parameterTypes[0]))
            } ?: return@runCatching null

            val inst = if (m.parameterTypes[0].isArray) {
                m.invoke(null, contents.toTypedArray())
            } else {
                m.invoke(null, contents)
            }
            (inst as Contents)
        }.getOrNull()?.let { return it }

        runCatching {
            val companionField = cls.getDeclaredField("Companion")
            val companion = companionField.get(null) ?: return@runCatching null
            val m = companion.javaClass.methods.firstOrNull { m ->
                (m.name == "of" || m.name == "from" || m.name == "create") &&
                        m.parameterTypes.size == 1 &&
                        (m.parameterTypes[0].isArray || List::class.java.isAssignableFrom(m.parameterTypes[0]))
            } ?: return@runCatching null

            val inst = if (m.parameterTypes[0].isArray) {
                m.invoke(companion, contents.toTypedArray())
            } else {
                m.invoke(companion, contents)
            }
            (inst as Contents)
        }.getOrNull()?.let { return it }

        throw IllegalStateException("Unable to construct Contents for current LiteRT-LM SDK.")
    }

    /**
     * Best-effort parse for debug strings like:
     * - Text(text=...)
     * - Text(value=...)
     * - Text(content=...)
     * - Text("...")
     */
    private fun extractTextFromDebugString(debug: String): String {
        if (debug.isBlank()) return ""

        fun unquote(s: String): String {
            val t = s.trim()
            if (t.length >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
                return t.substring(1, t.length - 1)
            }
            return t
        }

        fun readQuoted(src: String, start: Int): Pair<String, Int> {
            if (start >= src.length) return "" to start
            val quote = src[start]
            if (quote != '"' && quote != '\'') return "" to start
            val sb = StringBuilder()
            var i = start + 1
            while (i < src.length) {
                val ch = src[i]
                if (ch == '\\' && i + 1 < src.length) {
                    sb.append(src[i + 1])
                    i += 2
                    continue
                }
                if (ch == quote) return sb.toString() to (i + 1)
                sb.append(ch)
                i++
            }
            return sb.toString() to i
        }

        fun readUntilDelim(src: String, start: Int): Pair<String, Int> {
            var i = start
            val sb = StringBuilder()
            while (i < src.length) {
                val ch = src[i]
                if (ch == ')' || ch == ',' || ch == ']' || ch == '}' || ch == '\n') break
                sb.append(ch)
                i++
            }
            return sb.toString() to i
        }

        val out = StringBuilder()
        var i = 0
        while (i < debug.length) {
            val idx = debug.indexOf("Text(", i)
            if (idx < 0) break
            var j = idx + "Text(".length
            while (j < debug.length && debug[j].isWhitespace()) j++

            if (j < debug.length && (debug[j] == '"' || debug[j] == '\'')) {
                val (q, next) = readQuoted(debug, j)
                if (q.isNotEmpty()) out.append(q)
                i = next
                continue
            }

            val keys = listOf("text=", "value=", "content=")
            var picked: String? = null
            var pickedEnd = j

            for (k in keys) {
                val kIdx = debug.indexOf(k, j)
                if (kIdx >= 0) {
                    var p = kIdx + k.length
                    while (p < debug.length && debug[p].isWhitespace()) p++

                    val (v, endPos) = if (p < debug.length && (debug[p] == '"' || debug[p] == '\'')) {
                        readQuoted(debug, p)
                    } else {
                        readUntilDelim(debug, p)
                    }

                    val vv = unquote(v)
                    if (vv.isNotBlank()) {
                        picked = vv
                        pickedEnd = endPos
                        break
                    }
                }
            }

            if (!picked.isNullOrBlank()) {
                out.append(picked)
                i = pickedEnd
            } else {
                i = idx + 4
            }
        }

        return out.toString()
    }

    /** Attempt to extract text from Message directly if such getter exists. */
    private fun extractTextFromMessageBestEffort(message: Message): String {
        val any = message as Any
        val candidates = listOf("getText", "text", "getValue", "value", "getContent", "content")
        for (name in candidates) {
            val m = runCatching {
                any.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterCount == 0 && it.returnType == String::class.java
                }
            }.getOrNull() ?: continue
            val v = runCatching { m.invoke(any) as? String }.getOrNull()
            if (!v.isNullOrBlank()) return v
        }
        return ""
    }

    /** Choose the more "human text" candidate. */
    private fun chooseMoreHumanText(a: String, b: String): String {
        if (a.isBlank()) return b
        if (b.isBlank()) return a

        fun score(s: String): Int {
            var x = 0
            val debugish = listOf("Message(", "contents=", "Content.", "Text(", "engine=", "Conversation")
            if (debugish.any { s.contains(it) }) x -= 50
            x += s.length / 8
            x += s.count { it == ' ' || it == '\n' || it == '\t' }.coerceAtMost(40)
            x -= s.count { it == '=' || it == '[' || it == ']' || it == '{' || it == '}' }.coerceAtMost(30)
            return x
        }

        val sa = score(a)
        val sb = score(b)
        return if (sb > sa) b else a
    }

    /** Extract best-effort visible text from a Message. */
    private fun extractRenderedText(message: Message): String {
        val direct = extractTextFromMessageBestEffort(message)
        if (direct.isNotBlank()) return direct

        val fromContents = runCatching {
            val contentsObj: Any = message.contents
            val s = contentsObj.toString()
            val parsed = extractTextFromDebugString(s)
            parsed.ifBlank { s }
        }.getOrElse { "" }

        val fromToString = runCatching { message.toString() }.getOrElse { "" }
        val parsedFromToString = extractTextFromDebugString(fromToString)
        val b = if (parsedFromToString.isNotBlank()) parsedFromToString else fromToString

        if (DEBUG_EXTRACT) {
            val n = extractDebugCounter.incrementAndGet()
            if (n == 1L || n % DEBUG_EXTRACT_EVERY_N == 0L) {
                Log.d(
                    TAG,
                    "extractRenderedText[#${n}] fromContents.len=${fromContents.length} " +
                            "msgToString.len=${fromToString.length} parsedToString.len=${parsedFromToString.length}"
                )
            }
        }

        return chooseMoreHumanText(fromContents, b)
    }

    /** Normalize tokenizer artifacts into plain text. */
    private fun normalizeDeltaText(s: String): String {
        if (s.isEmpty()) return s
        return s
            .replace('\u00A0', ' ')
            .replace('\uFEFF', ' ')
            .replace('\u2581', ' ')
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
    }

    /** Compute overlap length where suffix of a matches prefix of b. */
    private fun overlapSuffixPrefix(a: String, b: String, maxWindow: Int = 1024): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val start = maxOf(0, a.length - maxWindow)
        val aWin = a.substring(start)
        val maxK = min(aWin.length, b.length)

        for (k in maxK downTo 1) {
            val aPos = aWin.length - k
            if (aWin.regionMatches(aPos, b, 0, k, ignoreCase = false)) return k
        }
        return 0
    }

    /** Delta extractor that works for snapshots or deltas. */
    private fun computeDeltaSmart(emittedSoFar: String, newSnapshot: String): Pair<String, String> {
        if (newSnapshot.isEmpty()) return "" to emittedSoFar
        if (emittedSoFar.isEmpty()) return newSnapshot to newSnapshot

        if (newSnapshot.length >= emittedSoFar.length && newSnapshot.startsWith(emittedSoFar)) {
            val delta = newSnapshot.substring(emittedSoFar.length)
            return delta to newSnapshot
        }

        if (emittedSoFar.length > newSnapshot.length && emittedSoFar.startsWith(newSnapshot)) {
            return "" to emittedSoFar
        }

        val ov = overlapSuffixPrefix(emittedSoFar, newSnapshot)
        val delta = newSnapshot.substring(ov)
        return delta to (emittedSoFar + delta)
    }

    /** Heuristic default max tokens by model name. */
    private fun defaultMaxTokensForModel(modelName: String): Int {
        val n = modelName.lowercase(Locale.US)
        return if (n.contains("functiongemma") || n.contains("270m") || n.contains("tinygarden")) 1024 else 4096
    }

    /**
     * Best-effort "await initialized" that does NOT use apiMutex (deadlock-safe).
     */
    private suspend fun awaitInitializedInternal(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key = runtimeKey(model)

        val already = stateMutex.withLock { instances.containsKey(key) }
        if (already) return

        val signal = getOrCreateInitSignal(key)

        initialize(
            context = context,
            model = model,
            supportImage = supportImage,
            supportAudio = supportAudio,
            onDone = { /* ignored */ },
            systemMessage = systemMessage,
            tools = tools,
        )

        val err = withTimeoutOrNull(INIT_AWAIT_TIMEOUT_MS) { signal.await() }
            ?: "Initialization timed out after ${INIT_AWAIT_TIMEOUT_MS}ms."

        if (err.isNotEmpty()) throw IllegalStateException("LiteRT-LM initialization failed: $err")
    }

    /**
     * Delay when a post-terminate cooldown is active for this key.
     *
     * English comment:
     * - Hard-close and native teardown can overlap with re-init on some devices.
     * - This delay reduces the chance of "close vs init" races.
     */
    private suspend fun awaitCooldownIfNeeded(key: String, reason: String) {
        val rs = getRunState(key)
        val now = SystemClock.elapsedRealtime()
        val until = rs.cooldownUntilMs.get()
        val delayMs = max(0L, until - now)
        if (delayMs > 0) {
            Log.d(TAG, "Cooldown delay: key='$key' delay=${delayMs}ms reason='$reason'")
            delay(delayMs)
        }
    }

    /**
     * Upgrade (reinitialize) runtime capabilities if needed.
     *
     * English comment:
     * - If an instance was initialized with supportImage=false and a later request includes images,
     *   we re-init with supportImage=true (same for audio).
     * - This avoids "capability fixed at first call" failures in production flows.
     */
    private suspend fun upgradeCapabilitiesIfNeeded(
        context: Context,
        model: Model,
        wantImage: Boolean,
        wantAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        if (!wantImage && !wantAudio) return

        val key = runtimeKey(model)

        val plan = stateMutex.withLock {
            val inst = instances[key] ?: return@withLock null
            val needImage = wantImage && !inst.supportImage
            val needAudio = wantAudio && !inst.supportAudio
            if (!needImage && !needAudio) return@withLock null

            // English comment: Only upgrade to true; never downgrade existing capabilities.
            val nextImage = inst.supportImage || wantImage
            val nextAudio = inst.supportAudio || wantAudio
            Triple(nextImage, nextAudio, "needImage=$needImage needAudio=$needAudio have(image=${inst.supportImage},audio=${inst.supportAudio})")
        }

        if (plan == null) return

        val (nextImage, nextAudio, detail) = plan
        Log.w(TAG, "Capability upgrade requested: key='$key' -> image=$nextImage audio=$nextAudio ($detail)")

        awaitCooldownIfNeeded(key, reason = "capability-upgrade")

        val signal = getOrCreateInitSignal(key)

        initialize(
            context = context,
            model = model,
            supportImage = nextImage,
            supportAudio = nextAudio,
            onDone = { /* ignored */ },
            systemMessage = systemMessage,
            tools = tools,
        )

        val err = withTimeoutOrNull(INIT_AWAIT_TIMEOUT_MS) { signal.await() }
            ?: "Initialization timed out after ${INIT_AWAIT_TIMEOUT_MS}ms."

        if (err.isNotEmpty()) throw IllegalStateException("LiteRT-LM capability upgrade failed: $err")
    }

    /**
     * Initialize LiteRT-LM Engine + Conversation (async).
     */
    fun initialize(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        onDone: (String) -> Unit,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key = runtimeKey(model)

        setApplicationContext(context)
        markUsed(key)
        cancelScheduledCleanup(key, "initialize")

        val signal = getOrCreateInitSignal(key)

        val accepted = initInFlight.add(key)
        if (!accepted) {
            ioScope.launch {
                val err = withTimeoutOrNull(INIT_AWAIT_TIMEOUT_MS) { signal.await() }
                    ?: "Initialization timed out after ${INIT_AWAIT_TIMEOUT_MS}ms."
                postToMain { onDone(err) }
            }
            return
        }

        ioScope.launch {
            var engineToCloseOnFailure: Engine? = null
            var completed = false

            try {
                awaitCooldownIfNeeded(key, reason = "initialize")

                stateMutex.withLock {
                    val rs = getRunState(key)
                    if (rs.active.get()) {
                        throw IllegalStateException("Initialization rejected: active native stream in progress for key='$key'.")
                    }
                }

                val defaultMax = defaultMaxTokensForModel(model.name)
                val maxTokensRaw = model.getIntConfigValue(ConfigKey.MAX_TOKENS, defaultMax).coerceAtLeast(1)
                val maxTokens = maxTokensRaw.coerceIn(1, ABS_MAX_NUM_TOKENS)

                val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOPK))
                val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOPP))
                val temperature = sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))

                val backend = preferredBackend(model)

                Log.d(TAG, "Initializing LiteRT-LM: model='${model.name}', key='$key'")
                Log.d(TAG, "Capabilities: image=$supportImage audio=$supportAudio")
                Log.d(TAG, "Backend=$backend maxNumTokens=$maxTokens (raw=$maxTokensRaw) topK=$topK topP=$topP temp=$temperature")

                val modelPath = model.getPath()

                val cacheDirPath: String? = runCatching {
                    if (modelPath.startsWith("/data/local/tmp")) {
                        context.getExternalFilesDir(null)?.absolutePath
                    } else {
                        context.cacheDir?.absolutePath
                    }
                }.getOrNull()

                fun buildConfig(forBackend: Backend): EngineConfig {
                    return EngineConfig(
                        modelPath = modelPath,
                        backend = forBackend,
                        visionBackend = if (supportImage) Backend.GPU else null,
                        audioBackend = if (supportAudio) Backend.CPU else null,
                        maxNumTokens = maxTokens,
                        cacheDir = cacheDirPath,
                    )
                }

                var engineConfig = buildConfig(backend)

                val engine = runCatching {
                    Engine(engineConfig).also {
                        engineToCloseOnFailure = it
                        it.initialize()
                    }
                }.getOrElse { first ->
                    if (backend == Backend.GPU && !supportImage && !supportAudio) {
                        Log.w(TAG, "GPU init failed; falling back to CPU: ${first.message}")
                        engineConfig = buildConfig(Backend.CPU)
                        Engine(engineConfig).also {
                            engineToCloseOnFailure = it
                            it.initialize()
                        }
                    } else {
                        throw first
                    }
                }

                val conversationConfig = ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = topK,
                        topP = topP.toDouble(),
                        temperature = temperature.toDouble(),
                    ),
                    systemMessage = systemMessage,
                    tools = tools,
                )

                val conversation = engine.createConversation(conversationConfig)

                val retired: LiteRtLmInstance? = stateMutex.withLock {
                    val prev = instances.remove(key)
                    instances[key] = LiteRtLmInstance(
                        engine = engine,
                        conversation = conversation,
                        supportImage = supportImage,
                        supportAudio = supportAudio,
                        engineConfigSnapshot = engineConfig,
                    )
                    prev
                }

                if (retired != null) {
                    ioScope.launch {
                        delay(RETIRED_CLOSE_GRACE_MS)
                        runCatching { retired.conversation.close() }
                            .onFailure { Log.w(TAG, "Failed to close retired conversation: ${it.message}", it) }
                        runCatching { retired.engine.close() }
                            .onFailure { Log.w(TAG, "Failed to close retired engine: ${it.message}", it) }
                    }
                }

                Log.d(TAG, "LiteRT-LM initialization succeeded: model='${model.name}', key='$key'")
                postToMain { onDone("") }
                completeInitSignal(signal, "")
                completed = true
            } catch (e: Exception) {
                val err = cleanError(e.message)
                Log.e(TAG, "LiteRT-LM initialization failed: $err", e)
                runCatching { engineToCloseOnFailure?.close() }
                    .onFailure { Log.w(TAG, "Failed to close engine after init failure: ${it.message}", it) }

                postToMain { onDone(err) }
                completeInitSignal(signal, err)
                completed = true
            } finally {
                initInFlight.remove(key)
                if (!completed) {
                    completeInitSignal(signal, "Initialization aborted unexpectedly.")
                }
            }
        }
    }

    /**
     * Suspend-style initializer.
     */
    suspend fun initializeIfNeeded(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key = runtimeKey(model)

        setApplicationContext(context)
        markUsed(key)
        cancelScheduledCleanup(key, "initializeIfNeeded")

        val already = stateMutex.withLock { instances.containsKey(key) }
        if (already) return

        awaitCooldownIfNeeded(key, reason = "initializeIfNeeded")

        apiMutex.withLock {
            val stillAlready = stateMutex.withLock { instances.containsKey(key) }
            if (stillAlready) return@withLock

            val signal = getOrCreateInitSignal(key)

            initialize(
                context = context,
                model = model,
                supportImage = supportImage,
                supportAudio = supportAudio,
                onDone = { /* ignored */ },
                systemMessage = systemMessage,
                tools = tools,
            )

            val err = withTimeoutOrNull(INIT_AWAIT_TIMEOUT_MS) { signal.await() }
                ?: "Initialization timed out after ${INIT_AWAIT_TIMEOUT_MS}ms."

            if (err.isNotEmpty()) throw IllegalStateException("LiteRT-LM initialization failed: $err")
        }
    }

    /** Fire native done hook once (safe no-op if already cleared). */
    private fun fireNativeDoneHookOnce(key: String) {
        val rs = getRunState(key)
        val hook = rs.nativeDoneHook.getAndSet(null) ?: return
        runCatching { hook.invoke() }
            .onFailure { t -> Log.w(TAG, "nativeDoneHook failed: key='$key' err=${t.message}", t) }
    }

    /** Close and remove an instance NOW (best-effort). */
    private suspend fun closeInstanceNowBestEffort(key: String, reason: String) {
        cancelScheduledCleanup(key, "closeNow:$reason")

        val instance: LiteRtLmInstance? = stateMutex.withLock {
            val rs = getRunState(key)
            if (rs.active.get()) return@withLock null
            if (initInFlight.contains(key)) return@withLock null

            rs.cancelRequested.set(false)
            rs.pendingCancel.set(false)
            rs.logicalTerminator.set(null)
            rs.nativeDoneHook.set(null)
            rs.terminated.set(true)
            rs.logicalDone.set(true)

            pendingAfterStream.remove(key)
            instances.remove(key).also {
                initSignals.remove(key)
            }
        }

        if (instance == null) {
            Log.d(TAG, "closeInstanceNowBestEffort: nothing to close (or active/initInFlight): key='$key' reason='$reason'")
            return
        }

        val rs = getRunState(key)
        val now = SystemClock.elapsedRealtime()
        val sinceTerminate = now - rs.lastTerminateAtMs.get()
        val extraDelay = if (sinceTerminate in 0..CLOSE_GRACE_MS) (CLOSE_GRACE_MS - sinceTerminate) else 0L
        if (extraDelay > 0) delay(extraDelay)

        runCatching { instance.conversation.close() }
            .onFailure { Log.e(TAG, "Failed to close conversation: key='$key' reason='$reason' err=${it.message}", it) }
        runCatching { instance.engine.close() }
            .onFailure { Log.e(TAG, "Failed to close engine: key='$key' reason='$reason' err=${it.message}", it) }

        Log.d(TAG, "LiteRT-LM closed: key='$key' reason='$reason'")
    }

    private data class IdleClosePlan(
        val instance: LiteRtLmInstance,
        val idleForMs: Long,
        val tokenNow: Long,
        val nowMs: Long,
        val reason: String,
    )

    /** Token + idleness guarded closer for idle cleanup. */
    private suspend fun closeInstanceIfStillIdle(
        key: String,
        requiredIdleMs: Long,
        requiredToken: Long,
        reason: String,
    ) {
        val plan: IdleClosePlan? = stateMutex.withLock {
            val rs = getRunState(key)
            val nowInner = SystemClock.elapsedRealtime()
            val idleForInner = nowInner - rs.lastUseAtMs.get()
            val tokenInner = rs.cleanupToken.get()

            if (rs.active.get()) {
                Log.d(TAG, "Idle cleanup skipped (active native stream): key='$key'")
                return@withLock null
            }
            if (initInFlight.contains(key)) {
                Log.d(TAG, "Idle cleanup skipped (init in flight): key='$key'")
                return@withLock null
            }
            if (tokenInner != requiredToken) {
                Log.d(TAG, "Idle cleanup skipped (token changed): key='$key' required=$requiredToken now=$tokenInner")
                return@withLock null
            }
            if (idleForInner < requiredIdleMs) {
                Log.d(TAG, "Idle cleanup skipped (recent use): key='$key' idleFor=${idleForInner}ms < ${requiredIdleMs}ms")
                return@withLock null
            }

            rs.cancelRequested.set(false)
            rs.pendingCancel.set(false)
            rs.logicalTerminator.set(null)
            rs.nativeDoneHook.set(null)
            rs.terminated.set(true)
            rs.logicalDone.set(true)

            pendingAfterStream.remove(key)
            val inst = instances.remove(key)
            if (inst == null) {
                Log.d(TAG, "Idle cleanup: nothing to close: key='$key'")
                return@withLock null
            }

            initSignals.remove(key)

            IdleClosePlan(
                instance = inst,
                idleForMs = idleForInner,
                tokenNow = tokenInner,
                nowMs = nowInner,
                reason = reason,
            )
        }

        if (plan == null) return

        val rs = getRunState(key)
        val sinceTerminate = plan.nowMs - rs.lastTerminateAtMs.get()
        val extraDelay = if (sinceTerminate in 0..CLOSE_GRACE_MS) (CLOSE_GRACE_MS - sinceTerminate) else 0L
        if (extraDelay > 0) delay(extraDelay)

        runCatching { plan.instance.conversation.close() }
            .onFailure { Log.e(TAG, "Failed to close conversation: key='$key' reason='${plan.reason}' err=${it.message}", it) }
        runCatching { plan.instance.engine.close() }
            .onFailure { Log.e(TAG, "Failed to close engine: key='$key' reason='${plan.reason}' err=${it.message}", it) }

        Log.d(TAG, "LiteRT-LM closed: key='$key' reason='${plan.reason}' idleFor=${plan.idleForMs}ms token=${plan.tokenNow}")
    }

    /** Request a deferred idle cleanup. */
    fun cleanUp(model: Model, onDone: () -> Unit) {
        val key = runtimeKey(model)

        ioScope.launch {
            val action: () -> Unit = {
                scheduleIdleCleanup(key, IDLE_CLEANUP_MS, "explicit-cleanUp")
                postToMain { onDone() }
            }

            val defer = stateMutex.withLock { getRunState(key).active.get() }
            if (defer) {
                stateMutex.withLock { pendingAfterStream.getOrPut(key) { mutableListOf() }.add(action) }
                Log.w(TAG, "cleanUp deferred (will schedule after native termination): key='$key'")
                return@launch
            }

            action.invoke()
        }
    }

    /**
     * Reset conversation while reusing the existing Engine.
     *
     * Contract:
     * - If a native stream is active, defer the reset until after termination.
     * - supportImage/supportAudio must match the initialized capabilities.
     */
    fun resetConversation(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key = runtimeKey(model)

        ioScope.launch {
            markUsed(key)
            cancelScheduledCleanup(key, "resetConversation")

            val action: suspend () -> Unit = action@{
                val inst = stateMutex.withLock { instances[key] }
                if (inst == null) {
                    Log.w(TAG, "resetConversation skipped: not initialized key='$key'")
                    return@action
                }

                if (inst.supportImage != supportImage || inst.supportAudio != supportAudio) {
                    Log.w(
                        TAG,
                        "resetConversation rejected: capability mismatch key='$key' " +
                                "have(image=${inst.supportImage},audio=${inst.supportAudio}) " +
                                "want(image=$supportImage,audio=$supportAudio)"
                    )
                    return@action
                }

                val topK = sanitizeTopK(model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOPK))
                val topP = sanitizeTopP(model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOPP))
                val temperature = sanitizeTemperature(model.getFloatConfigValue(ConfigKey.TEMPERATURE, DEFAULT_TEMPERATURE))

                val cfg = ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = topK,
                        topP = topP.toDouble(),
                        temperature = temperature.toDouble(),
                    ),
                    systemMessage = systemMessage,
                    tools = tools,
                )

                val old = inst.conversation
                val fresh = inst.engine.createConversation(cfg)

                inst.conversation = fresh

                runCatching { old.close() }
                    .onFailure { Log.w(TAG, "resetConversation: failed to close old conversation: ${it.message}", it) }

                Log.d(TAG, "resetConversation done: key='$key'")
            }

            val defer = stateMutex.withLock { getRunState(key).active.get() }
            if (defer) {
                stateMutex.withLock {
                    pendingAfterStream.getOrPut(key) { mutableListOf() }.add {
                        ioScope.launch { runCatching { action() } }
                    }
                }
                Log.w(TAG, "resetConversation deferred (active stream): key='$key'")
                return@launch
            }

            runCatching { action() }
                .onFailure { Log.w(TAG, "resetConversation failed: key='$key' err=${it.message}", it) }
        }
    }

    /**
     * Force immediate teardown (best-effort).
     *
     * Contract:
     * - If a native stream is active, defer until after termination.
     */
    fun forceCleanUp(model: Model, onDone: () -> Unit) {
        val key = runtimeKey(model)

        ioScope.launch {
            markUsed(key)
            cancelScheduledCleanup(key, "forceCleanUp")

            val action: suspend () -> Unit = {
                closeInstanceNowBestEffort(key, reason = "forceCleanUp")
                postToMain { onDone() }
            }

            val defer = stateMutex.withLock { getRunState(key).active.get() }
            if (defer) {
                stateMutex.withLock {
                    pendingAfterStream.getOrPut(key) { mutableListOf() }.add {
                        ioScope.launch { runCatching { action() } }
                    }
                }
                Log.w(TAG, "forceCleanUp deferred (active stream): key='$key'")
                return@launch
            }

            runCatching { action() }
                .onFailure {
                    Log.w(TAG, "forceCleanUp failed: key='$key' err=${it.message}", it)
                    postToMain { onDone() }
                }
        }
    }

    /** Emergency watchdog that attempts to recover from "never terminates" streams. */
    private fun startHardCloseWatchdog(key: String, reason: String) {
        val rs = getRunState(key)
        if (!rs.hardCloseRunning.compareAndSet(false, true)) return

        ioScope.launch {
            try {
                val start = SystemClock.elapsedRealtime()
                Log.w(TAG, "Hard-close watchdog started: key='$key' reason='$reason' timeout=${HARD_CLOSE_TIMEOUT_MS}ms")

                while (true) {
                    delay(HARD_CLOSE_POLL_MS)

                    if (!rs.active.get() || rs.terminated.get()) {
                        Log.d(TAG, "Hard-close watchdog exit: key='$key' already terminated")
                        return@launch
                    }

                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - start
                    val sinceMsg = now - rs.lastMessageAtMs.get()

                    if (sinceMsg in 0..2_000L && elapsed < HARD_CLOSE_TIMEOUT_MS) continue

                    if (elapsed >= HARD_CLOSE_TIMEOUT_MS) {
                        val didTerminate = rs.terminated.compareAndSet(false, true)
                        if (!didTerminate) {
                            Log.d(TAG, "Hard-close watchdog abort (already terminated): key='$key'")
                            return@launch
                        }

                        Log.e(TAG, "Hard-close watchdog firing: key='$key' elapsed=${elapsed}ms sinceMsg=${sinceMsg}ms")

                        val inst: LiteRtLmInstance? = stateMutex.withLock {
                            if (!rs.active.get()) return@withLock null

                            // IMPORTANT:
                            // Do NOT clear pendingAfterStream here.
                            // It will be drained by nativeDoneHook via scheduleDeferredActions().
                            instances.remove(key)
                        }

                        if (inst != null) {
                            runCatching { inst.conversation.close() }
                                .onFailure { Log.e(TAG, "Hard-close: conversation.close failed: key='$key' err=${it.message}", it) }
                            runCatching { inst.engine.close() }
                                .onFailure { Log.e(TAG, "Hard-close: engine.close failed: key='$key' err=${it.message}", it) }
                        }

                        val tNow = SystemClock.elapsedRealtime()
                        rs.lastTerminateAtMs.set(tNow)
                        rs.cooldownUntilMs.set(tNow + POST_TERMINATE_COOLDOWN_MS)

                        rs.active.set(false)
                        rs.logicalDone.set(true)
                        rs.logicalTerminator.set(null)

                        fireNativeDoneHookOnce(key)

                        Log.e(TAG, "Hard-close completed: key='$key'")
                        return@launch
                    }
                }
            } finally {
                rs.hardCloseRunning.set(false)
            }
        }
    }

    /**
     * Low-level callback-based streaming API.
     *
     * Contract:
     * - resultListener(..., done=true) is "logical completion" (UI completion).
     * - cleanUpListener() is invoked ONLY after native termination (onDone/onError),
     *   or after hard-close watchdog if enabled.
     *
     * @param notifyCancelToOnError When true, cancellation will be forwarded to onError("Cancelled"),
     *        which is useful for suspend callers that want cancellation as an exception.
     *        For UI streaming, keep this false to avoid treating user cancel as an error.
     */
    fun runInference(
        model: Model,
        input: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        cleanUpListener: () -> Unit,
        onError: (message: String) -> Unit = {},
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        notifyCancelToOnError: Boolean = false,
    ) {
        val key = runtimeKey(model)

        ioScope.launch {
            markUsed(key)
            cancelScheduledCleanup(key, "runInference")

            val needAutoInit = stateMutex.withLock { instances[key] == null }
            if (needAutoInit) {
                val ctx = appContextRef.get()
                if (ctx == null) {
                    val msg = "LiteRT-LM model '${model.name}' is not initialized, and no application context is set. " +
                            "Call setApplicationContext() or initializeIfNeeded() first."
                    Log.e(TAG, msg)
                    postToMain {
                        onError(msg)
                        resultListener("", true)
                        runCatching { cleanUpListener.invoke() }
                    }
                    return@launch
                }

                val reqImage = images.isNotEmpty()
                val reqAudio = audioClips.isNotEmpty()
                runCatching {
                    awaitInitializedInternal(
                        context = ctx,
                        model = model,
                        supportImage = reqImage,
                        supportAudio = reqAudio,
                    )
                }.onFailure { t ->
                    val msg = "LiteRT-LM auto-init failed: ${cleanError(t.message)}"
                    Log.e(TAG, msg, t)
                    postToMain {
                        onError(msg)
                        resultListener("", true)
                        runCatching { cleanUpListener.invoke() }
                    }
                    return@launch
                }
            }

            // Capability upgrade path (text-only first, multimodal later).
            val wantImage = images.isNotEmpty()
            val wantAudio = audioClips.isNotEmpty()
            if (wantImage || wantAudio) {
                val ctx = appContextRef.get()
                if (ctx != null) {
                    runCatching {
                        upgradeCapabilitiesIfNeeded(
                            context = ctx,
                            model = model,
                            wantImage = wantImage,
                            wantAudio = wantAudio,
                        )
                    }.onFailure { t ->
                        val msg = "LiteRT-LM capability upgrade failed: ${cleanError(t.message)}"
                        Log.e(TAG, msg, t)
                        postToMain {
                            onError(msg)
                            resultListener("", true)
                            runCatching { cleanUpListener.invoke() }
                        }
                        return@launch
                    }
                }
            }

            var instNullable: LiteRtLmInstance? = null
            var rsNullable: RunState? = null
            var myRunId = 0L
            var convNullable: Conversation? = null
            var rejectMsg: String? = null
            var cooldownDelayMs = 0L

            stateMutex.withLock {
                val inst = instances[key]
                if (inst == null) {
                    rejectMsg = "LiteRT-LM model '${model.name}' is not initialized. Call initializeIfNeeded() first."
                    return@withLock
                }

                if (images.isNotEmpty() && !inst.supportImage) {
                    rejectMsg = "Vision input rejected: supportImage=false for key='$key'. Reinitialize with supportImage=true."
                    return@withLock
                }
                if (audioClips.isNotEmpty() && !inst.supportAudio) {
                    rejectMsg = "Audio input rejected: supportAudio=false for key='$key'. Reinitialize with supportAudio=true."
                    return@withLock
                }

                val rs = getRunState(key)

                val now = SystemClock.elapsedRealtime()
                val until = rs.cooldownUntilMs.get()
                cooldownDelayMs = max(0L, until - now)

                val acquired = rs.active.compareAndSet(false, true)
                if (!acquired) {
                    rejectMsg = "LiteRT-LM runInference rejected: another native stream is already active for key='$key'."
                    return@withLock
                }

                myRunId = rs.runId.incrementAndGet()
                rs.terminated.set(false)
                rs.logicalDone.set(false)
                rs.lastMessageAtMs.set(0L)

                val preCancelled = rs.pendingCancel.getAndSet(false)
                rs.cancelRequested.set(preCancelled)

                instNullable = inst
                rsNullable = rs
                convNullable = inst.conversation
            }

            val inst = instNullable
            val rsLocal = rsNullable
            val conversation = convNullable
            val reject = rejectMsg

            if (reject != null || inst == null || rsLocal == null || conversation == null) {
                val msg = reject ?: "LiteRT-LM start rejected: unknown reason."
                Log.w(TAG, msg)
                postToMain {
                    onError(msg)
                    resultListener("", true)
                    runCatching { cleanUpListener.invoke() }
                }
                return@launch
            }

            if (cooldownDelayMs > 0) {
                Log.d(TAG, "Post-terminate cooldown: delaying start ${cooldownDelayMs}ms for key='$key'")
                delay(cooldownDelayMs)
            }

            val trimmed = input.trim()
            val hasText = trimmed.isNotEmpty()
            val hasMm = images.isNotEmpty() || audioClips.isNotEmpty()

            if (!hasText && !hasMm) {
                val msg = "LiteRT-LM input rejected: empty message (no text/images/audio)."
                Log.w(TAG, msg)
                stateMutex.withLock {
                    rsLocal.active.set(false)
                    rsLocal.terminated.set(true)
                    rsLocal.logicalDone.set(true)
                    rsLocal.logicalTerminator.set(null)
                    rsLocal.nativeDoneHook.set(null)
                }
                postToMain {
                    onError(msg)
                    resultListener("", true)
                    runCatching { cleanUpListener.invoke() }
                }
                return@launch
            }

            var emittedSoFar = ""
            var msgCount = 0

            suspend fun runDeferredActions() {
                val deferred: List<() -> Unit> = stateMutex.withLock {
                    pendingAfterStream.remove(key)?.toList() ?: emptyList()
                }
                deferred.forEach { act ->
                    runCatching { act.invoke() }
                        .onFailure { t -> Log.w(TAG, "Deferred action failed for key='$key': ${t.message}", t) }
                }
            }

            fun scheduleDeferredActions() {
                ioScope.launch { runDeferredActions() }
            }

            fun scheduleCleanUpListener() {
                postToMain {
                    runCatching { cleanUpListener.invoke() }
                        .onFailure { t -> Log.w(TAG, "cleanUpListener failed: ${t.message}", t) }
                }
            }

            rsLocal.nativeDoneHook.set {
                if (rsLocal.runId.get() != myRunId) return@set
                scheduleCleanUpListener()
                scheduleDeferredActions()
            }

            var watchdog: Job? = null
            var nativeStarted = false

            fun deliverLogicalDoneOnce(errorMessage: String? = null, isCancel: Boolean = false) {
                if (!rsLocal.logicalDone.compareAndSet(false, true)) return

                postToMain {
                    val cancelled = isCancel || rsLocal.cancelRequested.get()
                    if (cancelled) {
                        if (notifyCancelToOnError && !errorMessage.isNullOrBlank()) {
                            onError(errorMessage)
                        }
                    } else if (!errorMessage.isNullOrBlank()) {
                        onError(errorMessage)
                    }
                    resultListener("", true)
                }
            }

            fun markNativeDoneOnce(errorMessage: String? = null, isCancel: Boolean = false) {
                if (!rsLocal.terminated.compareAndSet(false, true)) return

                watchdog?.cancel()
                watchdog = null

                val now = SystemClock.elapsedRealtime()
                rsLocal.lastTerminateAtMs.set(now)
                rsLocal.cooldownUntilMs.set(now + POST_TERMINATE_COOLDOWN_MS)

                rsLocal.active.set(false)
                rsLocal.logicalTerminator.set(null)

                deliverLogicalDoneOnce(errorMessage = errorMessage, isCancel = isCancel)

                // IMPORTANT: cleanup must run even if native termination was forced.
                fireNativeDoneHookOnce(key)
            }

            fun requestLogicalCancel(reason: String) {
                rsLocal.cancelRequested.set(true)

                deliverLogicalDoneOnce(errorMessage = reason, isCancel = true)

                runCatching { conversation.cancelProcess() }
                    .onFailure { t -> Log.w(TAG, "cancelProcess() failed: key='$key' err=${t.message}", t) }

                if (HARD_CLOSE_ENABLE) startHardCloseWatchdog(key, reason = "logicalCancel")
            }

            rsLocal.logicalTerminator.set { requestLogicalCancel("Cancelled") }

            if (rsLocal.cancelRequested.get()) {
                Log.i(TAG, "LiteRT-LM start cancelled before sendMessageAsync: key='$key'")
                markNativeDoneOnce(errorMessage = "Cancelled", isCancel = true)
                return@launch
            }

            watchdog = ioScope.launch {
                delay(STREAM_WATCHDOG_MS)
                if (rsLocal.runId.get() != myRunId) return@launch
                if (rsLocal.terminated.get()) return@launch

                Log.e(TAG, "Stream watchdog fired: key='$key' runId=$myRunId timeout=${STREAM_WATCHDOG_MS}ms")

                deliverLogicalDoneOnce("Timeout: inference did not complete in ${STREAM_WATCHDOG_MS}ms")

                runCatching { conversation.cancelProcess() }
                    .onFailure { t -> Log.w(TAG, "cancelProcess() failed on watchdog: key='$key' err=${t.message}", t) }

                if (HARD_CLOSE_ENABLE) startHardCloseWatchdog(key, reason = "watchdog")

                if (!nativeStarted) markNativeDoneOnce("Timeout before native start")
            }

            val callback = object : MessageCallback {

                override fun onMessage(message: Message) {
                    if (rsLocal.runId.get() != myRunId) return
                    if (rsLocal.terminated.get()) return
                    if (rsLocal.logicalDone.get() || rsLocal.cancelRequested.get()) return

                    rsLocal.lastMessageAtMs.set(SystemClock.elapsedRealtime())
                    msgCount++

                    val snapshotRaw = extractRenderedText(message)
                    if (snapshotRaw.isEmpty()) return

                    val (deltaRaw, nextEmitted) = computeDeltaSmart(emittedSoFar, snapshotRaw)
                    emittedSoFar = nextEmitted
                    if (deltaRaw.isEmpty()) return

                    val delta = normalizeDeltaText(deltaRaw)

                    if (DEBUG_STREAM && (msgCount == 1 || msgCount % DEBUG_STREAM_EVERY_N == 0)) {
                        val lead = deltaRaw.firstOrNull()
                        val leadInfo =
                            if (lead == null) "null"
                            else "U+${lead.code.toString(16).uppercase()} ws=${lead.isWhitespace()} ch='$lead'"

                        val dPreview = delta.take(DEBUG_PREFIX_CHARS).replace("\n", "\\n")
                        val sPreview = snapshotRaw.take(DEBUG_PREFIX_CHARS).replace("\n", "\\n")

                        Log.d(
                            TAG,
                            "stream[key=$key runId=$myRunId] msg#$msgCount " +
                                    "snapLen=${snapshotRaw.length} deltaLen=${delta.length} " +
                                    "lead=$leadInfo snapPreview='$sPreview' deltaPreview='$dPreview' emittedLen=${emittedSoFar.length}"
                        )
                    }

                    postToMain { resultListener(delta, false) }
                }

                override fun onDone() {
                    if (rsLocal.runId.get() != myRunId) return
                    markNativeDoneOnce(null)
                }

                override fun onError(throwable: Throwable) {
                    if (rsLocal.runId.get() != myRunId) return

                    val rawMsg = throwable.message ?: throwable.toString()
                    val msg = cleanError(rawMsg)
                    val code = extractStatusCodeBestEffort(throwable)

                    if (DEBUG_ERROR_THROWABLE) {
                        val cls = throwable::class.java.name
                        val codeStr = code?.toString() ?: "n/a"
                        Log.e(
                            TAG,
                            "LiteRT-LM onError(Throwable): key='$key' runId=$myRunId type=$cls code=$codeStr msg='$msg'\n" +
                                    shortStack(throwable),
                            throwable
                        )
                    }

                    val cancelled = rsLocal.cancelRequested.get() || isCancellationThrowable(throwable, msg)
                    if (cancelled) {
                        Log.i(TAG, "LiteRT-LM inference cancelled: key='$key' runId=$myRunId")
                        markNativeDoneOnce(errorMessage = "Cancelled", isCancel = true)
                        return
                    }

                    val decorated = if (code != null) "Error($code): $msg" else "Error: $msg"
                    Log.e(TAG, "LiteRT-LM inference error: key='$key' runId=$myRunId $decorated")
                    markNativeDoneOnce(decorated)
                }
            }

            try {
                if (!hasMm) {
                    conversation.sendMessageAsync(trimmed, callback)
                } else {
                    val contentList = buildContentList(input = trimmed, images = images, audioClips = audioClips)
                    val contentsObj = buildContentsObject(contentList)
                    conversation.sendMessageAsync(contentsObj, callback)
                }

                nativeStarted = true
            } catch (e: Exception) {
                Log.e(TAG, "LiteRT-LM sendMessageAsync failed: key='$key' msg=${e.message}", e)
                markNativeDoneOnce(cleanError(e.message))
            }
        }
    }

    /**
     * High-level suspend API:
     * - Serializes calls via apiMutex.
     * - Uses runInference internally and returns full aggregated text.
     */
    suspend fun generateText(
        model: Model,
        input: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        onPartial: (String) -> Unit = {},
    ): String = apiMutex.withLock {
        val key = runtimeKey(model)

        markUsed(key)
        cancelScheduledCleanup(key, "generateText")

        if (!busy.compareAndSet(false, true)) {
            throw IllegalStateException("LiteRT-LM is already busy with another request.")
        }

        try {
            val buffer = StringBuilder()
            val doneSignal = CompletableDeferred<String>()

            runInference(
                model = model,
                input = input,
                images = images,
                audioClips = audioClips,
                resultListener = { partial, done ->
                    if (partial.isNotEmpty()) {
                        buffer.append(partial)
                        runCatching { onPartial(partial) }
                            .onFailure { t -> Log.w(TAG, "onPartial failed: ${t.message}", t) }
                    }
                    if (done && !doneSignal.isCompleted) {
                        doneSignal.complete(buffer.toString())
                    }
                },
                cleanUpListener = { /* no-op */ },
                onError = { message ->
                    if (!doneSignal.isCompleted) {
                        if (message.equals("Cancelled", ignoreCase = true)) {
                            doneSignal.completeExceptionally(CancellationException("Cancelled"))
                        } else {
                            doneSignal.completeExceptionally(
                                IllegalStateException("LiteRT-LM generation error: $message")
                            )
                        }
                    }
                },
                notifyCancelToOnError = true,
            )

            try {
                doneSignal.await()
            } catch (e: CancellationException) {
                Log.i(TAG, "generateText cancelled: key='$key'")
                cancel(model)
                throw e
            }
        } finally {
            busy.set(false)
        }
    }

    /**
     * Best-effort cancellation.
     *
     * Behavior:
     * - If active stream exists: call cancelProcess() via logical terminator when available.
     * - Otherwise: mark pendingCancel so next run aborts early.
     */
    fun cancel(model: Model) {
        val key = runtimeKey(model)

        ioScope.launch {
            val rs = getRunState(key)
            rs.cancelRequested.set(true)

            if (rs.active.get()) {
                val terminator = rs.logicalTerminator.get()
                if (terminator != null) {
                    terminator.invoke()
                } else {
                    runCatching {
                        val conv = stateMutex.withLock { instances[key]?.conversation }
                        conv?.cancelProcess()
                    }.onFailure {
                        Log.w(TAG, "cancelProcess() failed in cancel(): key='$key' err=${it.message}", it)
                    }
                    if (HARD_CLOSE_ENABLE) startHardCloseWatchdog(key, reason = "cancel()")
                }
            } else {
                rs.pendingCancel.set(true)
            }
        }
    }
}
