/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SLM.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Compatibility facade over LiteRtLM (NO MediaPipe).
 *
 *  Why:
 *   - MediaPipe GenAI APIs are removed.
 *   - Keep existing call sites using SLM.* with minimal code changes.
 *
 *  Contract:
 *   - Delegates to LiteRtLM which already implements:
 *       • single-active-stream per key
 *       • runId late-callback suppression
 *       • logical done vs native termination separation
 *       • deferred cleanup after native termination
 *
 *  Strengthen (2026-01):
 *   • Avoid compile-time coupling to LiteRtLM method surface (reflection for non-suspend APIs).
 *   • Best-effort overload matching + argument coercion.
 *   • Supports signature drift by trying trimmed argument tails.
 *   • Caches method candidates to reduce reflection overhead.
 *   • Adds a suspend fallback for generateText via streaming when reflection suspend path fails.
 *
 *  Fix (2026-02):
 *   • StreamDeltaNormalizer: reduce false ACCUMULATED decisions on very short prefixes.
 *   • DEBUG_SLM follows BuildConfig.DEBUG (avoid noisy release logs).
 *   • Add isBusy(model) overload for compatibility with older call sites.
 *
 *  Fix (2026-02-19):
 *   • Reflective suspend invocation MUST treat "invoked" as success even if return value is null
 *     (Unit/void-like returns from Method.invoke()).
 *   • Prevent double-execution due to "null == fallback" misclassification.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.slm

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Message
import com.negi.survey.BuildConfig
import com.negi.survey.config.SurveyConfig
import com.negi.survey.net.RuntimeLogStore
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "SLM"

/** Toggle facade logs (safe to keep enabled in dev builds). */
private const val DEBUG_SLM: Boolean = BuildConfig.DEBUG

/**
 * Hardware accelerator options for inference (CPU or GPU).
 */
enum class Accelerator(val label: String) { CPU("CPU"), GPU("GPU") }

/**
 * Configuration keys for LLM inference.
 */
enum class ConfigKey { MAX_TOKENS, TOP_K, TOP_P, TEMPERATURE, ACCELERATOR }

/**
 * Callback to deliver partial or final inference results.
 *
 * @param partialResult Current partial text (delta chunks).
 * @param done True when logical completion is reached for this request.
 */
typealias ResultListener = (partialResult: String, done: Boolean) -> Unit

/**
 * Callback invoked ONLY after native termination (safe point for deferred cleanup).
 */
typealias CleanUpListener = () -> Unit

private const val DEFAULT_MAX_TOKENS = 4096
private const val DEFAULT_TOP_K = 40
private const val DEFAULT_TOP_P = 0.9f
private const val DEFAULT_TEMPERATURE = 0.7f

/** Keep aligned with LiteRtLM absolute bounds. */
private const val ABS_MAX_TOKENS = 4096

/** Conservative temperature bound to avoid weird sampler behavior. */
private const val ABS_MAX_TEMPERATURE = 2.0f

/** Defensive TOP_K bound (samplers can behave oddly with absurdly large values). */
private const val ABS_MAX_TOP_K = 2048

/* ───────────────────────────── Logging helpers ───────────────────────────── */

/**
 * Debug log with lazy message construction.
 */
private inline fun d(msg: () -> String) {
    if (DEBUG_SLM) RuntimeLogStore.d(TAG, msg())
}

/**
 * Warning log with lazy message construction.
 */
private inline fun w(t: Throwable? = null, msg: () -> String) {
    if (t != null) RuntimeLogStore.w(TAG, msg(), t) else RuntimeLogStore.w(TAG, msg())
}

/* ───────────────────────────── Model config ───────────────────────────── */

/**
 * Represents a model configuration.
 *
 * NOTE:
 * - LiteRtLM owns the runtime instance lifecycle internally (Engine/Conversation).
 * - This model class is just config + path holder.
 */
data class Model(
    val name: String,
    val taskPath: String,
    val config: Map<ConfigKey, Any> = emptyMap(),
) {
    /** Returns the raw model path used by LiteRtLM EngineConfig. */
    fun getPath(): String = taskPath

    /** Lookup an Int config value with a sane fallback. */
    fun getIntConfigValue(key: ConfigKey, default: Int): Int =
        (config[key] as? Number)?.toInt()
            ?: (config[key] as? String)?.toIntOrNull()
            ?: default

    /** Lookup a Float config value with a sane fallback. */
    fun getFloatConfigValue(key: ConfigKey, default: Float): Float =
        when (val v = config[key]) {
            is Number -> v.toFloat()
            is String -> v.toFloatOrNull() ?: default
            else -> default
        }

    /** Lookup a String config value with a sane fallback. */
    fun getStringConfigValue(key: ConfigKey, default: String): String =
        (config[key] as? String) ?: default
}

/**
 * Parse an accelerator string safely.
 */
private fun parseAcceleratorLabel(raw: String?): String {
    val s = raw?.trim()?.uppercase(Locale.US).orEmpty()
    return when (s) {
        Accelerator.CPU.label -> Accelerator.CPU.label
        Accelerator.GPU.label -> Accelerator.GPU.label
        "" -> Accelerator.GPU.label
        else -> {
            /** Unknown label -> default GPU for compatibility. */
            Accelerator.GPU.label
        }
    }
}

/**
 * Normalize config value types so downstream reads are stable:
 * - MAX_TOKENS/TOP_K: Int
 * - TOP_P/TEMPERATURE: Float
 * - ACCELERATOR: String
 */
private fun normalizeNumberTypes(m: MutableMap<ConfigKey, Any>) {
    m[ConfigKey.MAX_TOKENS] = (m[ConfigKey.MAX_TOKENS] as? Number)?.toInt()
        ?: (m[ConfigKey.MAX_TOKENS] as? String)?.toIntOrNull()
                ?: DEFAULT_MAX_TOKENS

    m[ConfigKey.TOP_K] = (m[ConfigKey.TOP_K] as? Number)?.toInt()
        ?: (m[ConfigKey.TOP_K] as? String)?.toIntOrNull()
                ?: DEFAULT_TOP_K

    m[ConfigKey.TOP_P] = (m[ConfigKey.TOP_P] as? Number)?.toFloat()
        ?: (m[ConfigKey.TOP_P] as? String)?.toFloatOrNull()
                ?: DEFAULT_TOP_P

    m[ConfigKey.TEMPERATURE] = (m[ConfigKey.TEMPERATURE] as? Number)?.toFloat()
        ?: (m[ConfigKey.TEMPERATURE] as? String)?.toFloatOrNull()
                ?: DEFAULT_TEMPERATURE

    m[ConfigKey.ACCELERATOR] = parseAcceleratorLabel(m[ConfigKey.ACCELERATOR] as? String)
}

/**
 * Clamp config ranges defensively.
 */
private fun clampRanges(m: MutableMap<ConfigKey, Any>) {
    val maxTokens = (m[ConfigKey.MAX_TOKENS] as Number).toInt().coerceIn(1, ABS_MAX_TOKENS)
    val topK = (m[ConfigKey.TOP_K] as Number).toInt().coerceIn(1, ABS_MAX_TOP_K)
    val topP = (m[ConfigKey.TOP_P] as Number).toFloat().coerceIn(0f, 1f)
    val temp = (m[ConfigKey.TEMPERATURE] as Number).toFloat().coerceIn(0f, ABS_MAX_TEMPERATURE)

    m[ConfigKey.MAX_TOKENS] = maxTokens
    m[ConfigKey.TOP_K] = topK
    m[ConfigKey.TOP_P] = topP
    m[ConfigKey.TEMPERATURE] = temp
}

/**
 * Build a normalized config map from SurveyConfig.SlmMeta.
 */
fun buildModelConfig(slm: SurveyConfig.SlmMeta): MutableMap<ConfigKey, Any> {
    val out: MutableMap<ConfigKey, Any> = mutableMapOf(
        ConfigKey.ACCELERATOR to parseAcceleratorLabel(slm.accelerator ?: Accelerator.GPU.label),
        ConfigKey.MAX_TOKENS to (slm.maxTokens ?: DEFAULT_MAX_TOKENS),
        ConfigKey.TOP_K to (slm.topK ?: DEFAULT_TOP_K),
        ConfigKey.TOP_P to (slm.topP ?: DEFAULT_TOP_P),
        ConfigKey.TEMPERATURE to (slm.temperature ?: DEFAULT_TEMPERATURE),
    )

    normalizeNumberTypes(out)
    clampRanges(out)

    d {
        "buildModelConfig: accel=${out[ConfigKey.ACCELERATOR]} " +
                "maxTokens=${out[ConfigKey.MAX_TOKENS]} topK=${out[ConfigKey.TOP_K]} " +
                "topP=${out[ConfigKey.TOP_P]} temp=${out[ConfigKey.TEMPERATURE]}"
    }

    return out
}

/* ───────────────────────────── Stream delta normalizer ───────────────────────────── */

/**
 * Normalize streaming partials into delta chunks.
 *
 * Some backends may return:
 * - DELTA (new tokens only), or
 * - ACCUMULATED (full text so far)
 *
 * This helper detects accumulated behavior and converts to delta.
 *
 * IMPORTANT:
 * - This is `internal` because other files (e.g., AiRepository.kt) may use it.
 * - Keep the constructor compatible with existing call sites:
 *     StreamDeltaNormalizer(StreamDeltaNormalizer.PartialMode.AUTO)
 */
internal class StreamDeltaNormalizer(
    modeHint: PartialMode = PartialMode.AUTO,
    private val prefixSampleChars: Int = 128,
    private val boundarySampleChars: Int = 64,
) {
    enum class PartialMode { AUTO, DELTA, ACCUMULATED }

    companion object {
        /** Minimum prefix/tail sample size to be considered "strong evidence". */
        private const val MIN_STRONG_SAMPLE_CHARS = 16

        /** When previous length is tiny, require larger growth to reduce collisions. */
        private const val SMALL_PREV_FORCE_GROWTH_CHARS = 8

        /** General minimum growth required to consider ACCUMULATED. */
        private const val MIN_GROWTH_CHARS = 1

        /** If ACCUMULATED mode repeatedly mismatches, downgrade to DELTA mode. */
        private const val ACCUM_MISMATCH_TO_DELTA_THRESHOLD = 2
    }

    private var decided: PartialMode = modeHint

    /** Last observed length of the incoming string (when treating as accumulated). */
    private var lastLen: Int = 0

    /** Prefix sample of last observed text. */
    private var prefixSample: String = ""

    /** Tail/boundary sample of last observed text. */
    private var boundarySample: String = ""

    /** Used only during AUTO decision; avoid keeping huge strings. */
    private var firstChunk: String? = null
    private var firstChunkLen: Int = 0

    /** Consecutive mismatch counter when in ACCUMULATED mode. */
    private var accumMismatchCount: Int = 0

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

        val looksAccumulated = looksLikeAccumulated(incoming)
        decided = if (looksAccumulated) PartialMode.ACCUMULATED else PartialMode.DELTA

        firstChunk = null
        firstChunkLen = 0

        return if (decided == PartialMode.ACCUMULATED) {
            accumulatedDelta(incoming)
        } else {
            seed(incoming, allowFirstChunk = false)
            incoming
        }
    }

    private fun accumulatedDelta(incoming: String): String {
        if (lastLen == 0) {
            seed(incoming, allowFirstChunk = false)
            accumMismatchCount = 0
            return incoming
        }

        if (!looksLikeAccumulated(incoming)) {
            accumMismatchCount++
            if (accumMismatchCount >= ACCUM_MISMATCH_TO_DELTA_THRESHOLD) {
                /** Backend behavior changed (or decision was wrong). */
                decided = PartialMode.DELTA
                d { "StreamDeltaNormalizer: downgrade to DELTA after $accumMismatchCount mismatches" }
            }
            seed(incoming, allowFirstChunk = false)
            return incoming
        }

        accumMismatchCount = 0
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
            val canKeep = text.length in MIN_STRONG_SAMPLE_CHARS..cap
            firstChunk = if (canKeep) text else null
            firstChunkLen = text.length
        }
    }

    private fun looksLikeAccumulated(incoming: String): Boolean {
        /** ACCUMULATED must not shrink. */
        if (incoming.length < lastLen) return false

        /** No growth => treat as not-accumulated to avoid deleting text on repeats. */
        val growth = incoming.length - lastLen
        if (growth < MIN_GROWTH_CHARS) return false

        /** Fast reject: accumulated output should keep prior prefix. */
        if (prefixSample.isNotEmpty() && !incoming.startsWith(prefixSample)) return false

        /** Strong evidence using first-chunk full prefix (only when long enough). */
        val fc = firstChunk
        if (fc != null && firstChunkLen >= MIN_STRONG_SAMPLE_CHARS) {
            if (incoming.length >= firstChunkLen && incoming.startsWith(fc)) return true
        }

        /** When previous length is tiny, require larger growth to reduce collisions. */
        if (lastLen < MIN_STRONG_SAMPLE_CHARS && growth < SMALL_PREV_FORCE_GROWTH_CHARS) {
            return false
        }

        /** Strong prefix sample check (only when sample is long enough). */
        if (prefixSample.length >= MIN_STRONG_SAMPLE_CHARS && !incoming.startsWith(prefixSample)) {
            return false
        }

        /** Strong boundary alignment check (only when sample is long enough). */
        if (boundarySample.length >= MIN_STRONG_SAMPLE_CHARS) {
            val start = (lastLen - boundarySample.length).coerceAtLeast(0)
            val ok = incoming.regionMatches(
                thisOffset = start,
                other = boundarySample,
                otherOffset = 0,
                length = boundarySample.length,
                ignoreCase = false
            )
            if (!ok) return false
        }

        return true
    }
}

/* ───────────────────────────── Reflection Bridge ───────────────────────────── */

/**
 * Cache methods by (class/name/arity) to reduce reflection scan cost.
 */
private val methodBucketCache = ConcurrentHashMap<String, List<Method>>()

/**
 * Build a cache key for method buckets.
 */
private fun bucketKey(cls: Class<*>, methodName: String, argc: Int): String =
    "${cls.name}::$methodName/$argc"

/**
 * Get all methods (public + declared) matching name+arity, cached.
 *
 * Notes:
 * - We combine both `methods` and `declaredMethods` because Kotlin/JVM and proguarded builds
 *   can expose members differently.
 * - We de-duplicate by (name + parameter type list) to avoid double candidates.
 */
private fun getMethodBucket(cls: Class<*>, methodName: String, argc: Int): List<Method> {
    val key = bucketKey(cls, methodName, argc)
    return methodBucketCache.getOrPut(key) {
        val all = ArrayList<Method>(64)
        all.addAll(cls.methods.filter { it.name == methodName && it.parameterTypes.size == argc })
        all.addAll(cls.declaredMethods.filter { it.name == methodName && it.parameterTypes.size == argc })

        all.asSequence()
            .filterNot { it.isBridge || it.isSynthetic }
            .distinctBy { m ->
                buildString {
                    append(m.name).append("(")
                    m.parameterTypes.forEachIndexed { i, p ->
                        if (i > 0) append(",")
                        append(p.name)
                    }
                    append(")")
                }
            }
            .toList()
    }
}

/**
 * Convert primitive parameter class to its boxed counterpart.
 */
private fun boxedOfPrimitive(p: Class<*>): Class<*>? {
    if (!p.isPrimitive) return null
    return when (p) {
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        Short::class.javaPrimitiveType -> Short::class.javaObjectType
        Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
        Char::class.javaPrimitiveType -> Char::class.javaObjectType
        else -> null
    }
}

/**
 * Try to coerce an argument to match the parameter type.
 *
 * Supports:
 * - Number widening/narrowing to required primitive/boxed types
 * - Function0 -> Runnable
 * - Function1 -> java.util.function.Consumer (best-effort)
 */
@Suppress("UNCHECKED_CAST")
private fun coerceArgForParam(param: Class<*>, arg: Any?): Any? {
    if (arg == null) return null

    /** Direct instance match. */
    if (param.isInstance(arg)) return arg

    /** Primitive params accept boxed instances. */
    val boxed = boxedOfPrimitive(param)
    if (boxed != null && boxed.isInstance(arg)) return arg

    /** Numeric coercions. */
    val intP = Int::class.javaPrimitiveType
    val longP = Long::class.javaPrimitiveType
    val floatP = Float::class.javaPrimitiveType
    val doubleP = Double::class.javaPrimitiveType
    val shortP = Short::class.javaPrimitiveType
    val byteP = Byte::class.javaPrimitiveType

    val intO = Int::class.javaObjectType
    val longO = Long::class.javaObjectType
    val floatO = Float::class.javaObjectType
    val doubleO = Double::class.javaObjectType
    val shortO = Short::class.javaObjectType
    val byteO = Byte::class.javaObjectType

    val wantsInt = (param == intP || param == intO)
    val wantsLong = (param == longP || param == longO)
    val wantsFloat = (param == floatP || param == floatO)
    val wantsDouble = (param == doubleP || param == doubleO)
    val wantsShort = (param == shortP || param == shortO)
    val wantsByte = (param == byteP || param == byteO)

    if (arg is Number) {
        return when {
            wantsInt -> arg.toInt()
            wantsLong -> arg.toLong()
            wantsFloat -> arg.toFloat()
            wantsDouble -> arg.toDouble()
            wantsShort -> arg.toShort()
            wantsByte -> arg.toByte()
            else -> arg
        }
    }

    /** Function0 -> Runnable. */
    if (param == Runnable::class.java && arg is Function0<*>) {
        return Runnable { arg.invoke() }
    }

    /** Function1 -> Consumer (best-effort). */
    if (param.name == "java.util.function.Consumer" && arg is Function1<*, *>) {
        return runCatching {
            val f = arg as Function1<Any?, Any?>
            val consumerCls = Class.forName("java.util.function.Consumer")
            java.lang.reflect.Proxy.newProxyInstance(
                consumerCls.classLoader,
                arrayOf(consumerCls)
            ) { _, method, args ->
                if (method.name == "accept") {
                    f.invoke(args?.getOrNull(0))
                    null
                } else {
                    null
                }
            }
        }.getOrElse { arg }
    }

    return arg
}

/**
 * Score a (param,arg) match for selecting the "best" overload.
 */
private fun scoreParamMatch(param: Class<*>, arg: Any?): Int {
    if (arg == null) return if (param.isPrimitive) -10_000 else 1
    val coerced = coerceArgForParam(param, arg) ?: return if (param.isPrimitive) -10_000 else 1

    if (param.isPrimitive) {
        val boxed = boxedOfPrimitive(param) ?: return -10_000
        return when {
            boxed == coerced.javaClass -> 8
            boxed.isAssignableFrom(coerced.javaClass) -> 6
            else -> -10_000
        }
    }

    return when {
        param == coerced.javaClass -> 10
        param.isAssignableFrom(coerced.javaClass) -> 7
        else -> -10_000
    }
}

/**
 * Find the best matching method by name + arity + parameter compatibility.
 *
 * Preference order:
 * 1) higher match score
 * 2) instance methods over static (Kotlin object style)
 */
private fun findBestMethod(cls: Class<*>, methodName: String, args: Array<Any?>): Method? {
    val bucket = getMethodBucket(cls, methodName, args.size)
    if (bucket.isEmpty()) return null

    var best: Method? = null
    var bestScore = Int.MIN_VALUE

    for (m in bucket) {
        val params = m.parameterTypes
        var score = 0
        var ok = true

        for (i in params.indices) {
            val s = scoreParamMatch(params[i], args[i])
            if (s < -1000) {
                ok = false
                break
            }
            score += s
        }
        if (!ok) continue

        /** Prefer instance methods for Kotlin object APIs. */
        if (!Modifier.isStatic(m.modifiers)) score += 3

        if (score > bestScore) {
            bestScore = score
            best = m
        }
    }

    return best
}

/**
 * Produce argument candidates to survive signature drift by trimming optional tails.
 *
 * Strategy:
 * - Try full args
 * - If last is emptyList -> drop it
 * - If last is null -> drop it
 * - Also try dropping last N (1..4) always (best-effort)
 */
private fun buildArgCandidates(args: Array<Any?>): List<Array<Any?>> {
    if (args.isEmpty()) return listOf(args)

    val out = ArrayList<Array<Any?>>(8)
    out.add(args)

    fun dropLast(n: Int) {
        if (args.size > n) out.add(args.copyOf(args.size - n))
    }

    val last = args.last()
    if (last is List<*> && last.isEmpty()) dropLast(1)
    if (last == null) dropLast(1)

    /** Generic trims (works for optional tails like systemMessage/tools/onPartial). */
    val maxDrop = minOf(4, args.size - 1)
    for (n in 1..maxDrop) dropLast(n)

    /** De-dupe by arity. */
    return out.distinctBy { it.size }
}

/**
 * Format method signature for debugging.
 */
private fun Method.signatureString(): String {
    return buildString {
        append(name).append("(")
        parameterTypes.forEachIndexed { i, p ->
            if (i > 0) append(", ")
            append(p.simpleName)
        }
        append(")")
    }
}

/**
 * Call a LiteRtLM method by name using reflection (non-suspend), returning Any?.
 *
 * Returns null if no compatible method was found or invocation failed.
 */
private fun invokeLiteRtLmBestEffortReturn(
    methodName: String,
    args: Array<Any?>,
    onFailLog: String,
): Any? {
    val cls = LiteRtLM::class.java
    val candidates = buildArgCandidates(args)

    for (cand in candidates) {
        try {
            val m = findBestMethod(cls, methodName, cand)
            if (m == null) {
                d { "$onFailLog (method not found): name='$methodName' argc=${cand.size}" }
                continue
            }

            m.isAccessible = true
            val receiver: Any? = if (Modifier.isStatic(m.modifiers)) null else LiteRtLM

            /** Coerce args per param types. */
            val coercedArgs = Array<Any?>(cand.size) { i ->
                coerceArgForParam(m.parameterTypes[i], cand[i])
            }

            d { "invokeReturn: picked ${m.signatureString()} (argc=${cand.size})" }
            return m.invoke(receiver, *coercedArgs)
        } catch (ite: InvocationTargetException) {
            val root = ite.targetException ?: ite
            w(root) { "$onFailLog (target threw): name='$methodName' err=${root.message}" }
        } catch (t: Throwable) {
            w(t) { "$onFailLog (invoke failed): name='$methodName' err=${t.message}" }
        }
    }

    return null
}

/**
 * Call a LiteRtLM method by name using reflection (non-suspend), returning success boolean.
 *
 * IMPORTANT:
 * - Kotlin Unit/Java void methods return null from Method.invoke().
 * - So success must be "invoked without throwing", not "return != null".
 */
private fun invokeLiteRtLmBestEffortUnit(
    methodName: String,
    args: Array<Any?>,
    onFailLog: String,
): Boolean {
    val cls = LiteRtLM::class.java
    val candidates = buildArgCandidates(args)

    for (cand in candidates) {
        try {
            val m = findBestMethod(cls, methodName, cand)
            if (m == null) {
                d { "$onFailLog (method not found): name='$methodName' argc=${cand.size}" }
                continue
            }

            m.isAccessible = true
            val receiver: Any? = if (Modifier.isStatic(m.modifiers)) null else LiteRtLM

            /** Coerce args per param types. */
            val coercedArgs = Array<Any?>(cand.size) { i ->
                coerceArgForParam(m.parameterTypes[i], cand[i])
            }

            d { "invokeUnit: picked ${m.signatureString()} (argc=${cand.size})" }
            m.invoke(receiver, *coercedArgs)
            return true
        } catch (ite: InvocationTargetException) {
            val root = ite.targetException ?: ite
            w(root) { "$onFailLog (target threw): name='$methodName' err=${root.message}" }
        } catch (t: Throwable) {
            w(t) { "$onFailLog (invoke failed): name='$methodName' err=${t.message}" }
        }
    }

    return false
}

/**
 * Result for reflective suspend invocation.
 *
 * NOTE:
 * - invoked=true means we successfully located and invoked a compatible method.
 * - value may be null for Unit/void-like returns.
 */
private data class SuspendInvokeResult(
    val invoked: Boolean,
    val value: Any?
)

/**
 * Call a LiteRtLM suspend function by name using reflection.
 *
 * Kotlin suspend is compiled as:
 *   fun foo(..., continuation: Continuation<T>): Any?
 *
 * IMPORTANT:
 * - "Invoked" is the success criterion, NOT "value != null".
 * - Method.invoke() may return null for Unit/void-like methods even on success.
 */
private suspend fun invokeLiteRtLmBestEffortSuspend(
    methodName: String,
    argsNoCont: Array<Any?>,
    onFailLog: String,
): SuspendInvokeResult {
    val cls = LiteRtLM::class.java
    val candidates = buildArgCandidates(argsNoCont)

    return suspendCancellableCoroutine { outer ->
        outer.invokeOnCancellation {
            d { "invokeSuspend cancelled: method='$methodName'" }
        }

        val cont = object : Continuation<Any?> {
            override val context = outer.context
            override fun resumeWith(result: Result<Any?>) {
                if (outer.isCompleted) return
                result.fold(
                    onSuccess = { v ->
                        if (!outer.isCompleted) outer.resume(SuspendInvokeResult(invoked = true, value = v))
                    },
                    onFailure = { e ->
                        if (!outer.isCompleted) outer.resumeWithException(e)
                    }
                )
            }
        }

        for (cand in candidates) {
            try {
                val args = arrayOfNulls<Any?>(cand.size + 1)
                for (i in cand.indices) args[i] = cand[i]
                args[args.lastIndex] = cont

                val m = findBestMethod(cls, methodName, args)
                if (m == null) {
                    d { "$onFailLog (suspend method not found): name='$methodName' argc=${args.size}" }
                    continue
                }

                m.isAccessible = true
                val receiver: Any? = if (Modifier.isStatic(m.modifiers)) null else LiteRtLM

                val coercedArgs = Array<Any?>(args.size) { i ->
                    coerceArgForParam(m.parameterTypes[i], args[i])
                }

                d { "invokeSuspend: picked ${m.signatureString()} (argc=${args.size})" }
                val ret = m.invoke(receiver, *coercedArgs)

                if (ret !== COROUTINE_SUSPENDED) {
                    if (!outer.isCompleted) {
                        outer.resume(SuspendInvokeResult(invoked = true, value = ret))
                    }
                }
                return@suspendCancellableCoroutine
            } catch (ite: InvocationTargetException) {
                val root = ite.targetException ?: ite
                w(root) { "$onFailLog (suspend target threw): name='$methodName' err=${root.message}" }
            } catch (t: Throwable) {
                w(t) { "$onFailLog (suspend invoke failed): name='$methodName' err=${t.message}" }
            }
        }

        if (!outer.isCompleted) outer.resume(SuspendInvokeResult(invoked = false, value = null))
    }
}

/* ───────────────────────────── Facade API ──────────────────────────────── */

object SLM {

    /**
     * True when a suspend generateText call is currently in progress.
     *
     * NOTE:
     * - Some older call sites may use isBusy(model). Keep both.
     */
    fun isBusy(): Boolean {
        return runCatching {
            val ret = invokeLiteRtLmBestEffortReturn(
                methodName = "isBusy",
                args = emptyArray(),
                onFailLog = "LiteRtLM.isBusy unavailable",
            )
            (ret as? Boolean) ?: false
        }.getOrDefault(false)
    }

    /**
     * Compatibility overload: isBusy(model).
     *
     * Strategy:
     * 1) Try isBusy(model) if present.
     * 2) Fallback to isBusy() if not present.
     */
    fun isBusy(model: Model): Boolean {
        return runCatching {
            val ret = invokeLiteRtLmBestEffortReturn(
                methodName = "isBusy",
                args = arrayOf(model),
                onFailLog = "LiteRtLM.isBusy(model) unavailable",
            )
            (ret as? Boolean) ?: isBusy()
        }.getOrDefault(false)
    }

    /** Allow host app to set context early (recommended). */
    fun setApplicationContext(context: Context) {
        val ok = invokeLiteRtLmBestEffortUnit(
            methodName = "setApplicationContext",
            args = arrayOf(context),
            onFailLog = "LiteRtLM.setApplicationContext unavailable",
        )
        if (!ok) {
            w { "setApplicationContext: skipped (LiteRtLM API not present)." }
        }
    }

    /**
     * Initialize LiteRtLM Engine + Conversation (async).
     *
     * NOTE:
     * - supportImage/supportAudio must match your actual requests later.
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
        d {
            "initialize: model='${model.name}' path='${model.taskPath}' image=$supportImage audio=$supportAudio"
        }

        val ok = invokeLiteRtLmBestEffortUnit(
            methodName = "initialize",
            args = arrayOf(context, model, supportImage, supportAudio, onDone, systemMessage, tools),
            onFailLog = "LiteRtLM.initialize unavailable",
        )

        if (!ok) {
            w { "initialize: failed (LiteRtLM API not present / signature mismatch)." }
            onDone("error: initialize unavailable")
        }
    }

    /**
     * Suspend-style initializer.
     *
     * Best-effort reflection call. If unavailable, falls back to initialize() and waits for onDone.
     */
    suspend fun initializeIfNeeded(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        d { "initializeIfNeeded: model='${model.name}' image=$supportImage audio=$supportAudio" }

        try {
            val res = invokeLiteRtLmBestEffortSuspend(
                methodName = "initializeIfNeeded",
                argsNoCont = arrayOf(context, model, supportImage, supportAudio, systemMessage, tools),
                onFailLog = "LiteRtLM.initializeIfNeeded unavailable",
            )
            if (res.invoked) return
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            w(t) { "initializeIfNeeded: reflection failed err=${t.message}" }
        }

        /** Fallback: call async initialize and suspend until onDone fires (and honor errors). */
        suspendCancellableCoroutine<Unit> { cont ->
            initialize(
                context = context,
                model = model,
                supportImage = supportImage,
                supportAudio = supportAudio,
                onDone = { err ->
                    if (cont.isCompleted) {
                        // ignore late callback
                    } else if (err.isBlank()) {
                        cont.resume(Unit)
                    } else {
                        cont.resumeWithException(IllegalStateException("LiteRtLM init failed: $err"))
                    }
                },
                systemMessage = systemMessage,
                tools = tools
            )
            cont.invokeOnCancellation {
                d { "initializeIfNeeded fallback cancelled: model='${model.name}'" }
                cancel(model)
            }
        }
    }

    /**
     * Reset conversation (reuse engine) safely.
     *
     * If LiteRtLM.resetConversation does not exist in the current build,
     * we fall back to a no-op and log a warning (avoids build/runtime crash).
     */
    fun resetConversation(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        d { "resetConversation: model='${model.name}' image=$supportImage audio=$supportAudio" }

        val ok = invokeLiteRtLmBestEffortUnit(
            methodName = "resetConversation",
            args = arrayOf(model, supportImage, supportAudio, systemMessage, tools),
            onFailLog = "LiteRtLM.resetConversation unavailable",
        )

        if (!ok) {
            w { "resetConversation: skipped (LiteRtLM API not present)." }
        }
    }

    /**
     * Request a deferred idle cleanup.
     */
    fun cleanUp(model: Model, onDone: () -> Unit) {
        d { "cleanUp: model='${model.name}'" }

        val ok = invokeLiteRtLmBestEffortUnit(
            methodName = "cleanUp",
            args = arrayOf(model, onDone),
            onFailLog = "LiteRtLM.cleanUp unavailable",
        )

        if (!ok) {
            w { "cleanUp: skipped (LiteRtLM API not present)." }
            onDone()
        }
    }

    /**
     * Force immediate teardown (use sparingly).
     *
     * If LiteRtLM.forceCleanUp does not exist, falls back to cleanUp().
     */
    fun forceCleanUp(model: Model, onDone: () -> Unit) {
        d { "forceCleanUp: model='${model.name}'" }

        val ok = invokeLiteRtLmBestEffortUnit(
            methodName = "forceCleanUp",
            args = arrayOf(model, onDone),
            onFailLog = "LiteRtLM.forceCleanUp unavailable",
        )

        if (!ok) {
            w { "forceCleanUp: falling back to cleanUp() (deferred)." }
            cleanUp(model = model, onDone = onDone)
        }
    }

    /**
     * Low-level callback-based streaming API.
     *
     * Contract (aligned with LiteRtLM):
     * - resultListener(done=true) is logical completion for UI.
     * - cleanUpListener() is invoked ONLY after native termination.
     */
    fun runInference(
        model: Model,
        input: String,
        resultListener: ResultListener,
        cleanUpListener: CleanUpListener,
        onError: (message: String) -> Unit = {},
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
    ) {
        d {
            "runInference: model='${model.name}' textLen=${input.length} images=${images.size} audio=${audioClips.size}"
        }

        val ok = invokeLiteRtLmBestEffortUnit(
            methodName = "runInference",
            args = arrayOf(model, input, resultListener, cleanUpListener, onError, images, audioClips, false),
            onFailLog = "LiteRtLM.runInference unavailable",
        )

        if (!ok) {
            w { "runInference: failed (LiteRtLM API not present / signature mismatch)." }
            onError("runInference unavailable")
            cleanUpListener()
        }
    }

    /**
     * High-level suspend API:
     * - Returns full aggregated text.
     *
     * Strategy:
     * 1) Try reflection suspend call to LiteRtLM.generateText.
     * 2) If unavailable, fallback to streaming runInference() aggregation.
     */
    suspend fun generateText(
        model: Model,
        input: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        onPartial: (String) -> Unit = {},
    ): String {
        d {
            "generateText: model='${model.name}' textLen=${input.length} images=${images.size} audio=${audioClips.size}"
        }

        /** Try reflection suspend call first. */
        try {
            val res = invokeLiteRtLmBestEffortSuspend(
                methodName = "generateText",
                argsNoCont = arrayOf(model, input, images, audioClips, onPartial),
                onFailLog = "LiteRtLM.generateText unavailable",
            )
            if (res.invoked) {
                val s = res.value as? String
                if (s != null) return s
                // invoked=true but no String => signature drift; fallback to streaming.
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            w(t) { "generateText: reflection failed err=${t.message}" }
        }

        /** Fallback: aggregate via streaming with delta normalization. */
        return suspendCancellableCoroutine { cont ->
            val buffer = StringBuilder()
            val normalizer = StreamDeltaNormalizer(StreamDeltaNormalizer.PartialMode.AUTO)

            /** Terminal guard for done/error/invoke-failure races. */
            val terminal = AtomicBoolean(false)

            val resultListener: ResultListener = result@{ partial, done ->
                if (terminal.get()) return@result

                val delta = normalizer.toDelta(partial)
                if (delta.isNotEmpty()) {
                    buffer.append(delta)
                    runCatching { onPartial(delta) }
                        .onFailure { t -> w(t) { "generateText fallback onPartial failed: ${t.message}" } }
                }

                if (done) {
                    if (terminal.compareAndSet(false, true)) {
                        runCatching {
                            if (!cont.isCompleted) cont.resume(buffer.toString())
                        }.onFailure { t ->
                            w(t) { "generateText fallback resume failed (likely double-finish): ${t.message}" }
                        }
                    }
                }
            }

            val onError: (String) -> Unit = onError@{ msg ->
                if (!terminal.compareAndSet(false, true)) return@onError

                val lc = msg.lowercase(Locale.US)
                val ex = if (lc.contains("cancel")) {
                    CancellationException("Cancelled")
                } else {
                    IllegalStateException("LiteRtLM generation error: $msg")
                }

                runCatching {
                    if (!cont.isCompleted) cont.resumeWithException(ex)
                }.onFailure { t ->
                    w(t) { "generateText fallback resumeWithException failed (likely double-finish): ${t.message}" }
                }
            }

            /** Prefer notifying cancel as error for suspend semantics. */
            val ok = invokeLiteRtLmBestEffortUnit(
                methodName = "runInference",
                args = arrayOf(model, input, resultListener, { /* no-op */ }, onError, images, audioClips, true),
                onFailLog = "LiteRtLM.runInference unavailable",
            )

            if (!ok) {
                if (terminal.compareAndSet(false, true)) {
                    runCatching {
                        if (!cont.isCompleted) cont.resumeWithException(IllegalStateException("runInference unavailable"))
                    }.onFailure { t ->
                        w(t) { "generateText fallback immediate failure resumeWithException failed: ${t.message}" }
                    }
                }
            }

            cont.invokeOnCancellation {
                d { "generateText fallback cancelled: model='${model.name}'" }
                cancel(model)
            }
        }
    }

    /**
     * Best-effort logical cancellation.
     */
    fun cancel(model: Model) {
        d { "cancel: model='${model.name}'" }

        val ok = invokeLiteRtLmBestEffortUnit(
            methodName = "cancel",
            args = arrayOf(model),
            onFailLog = "LiteRtLM.cancel unavailable",
        )

        if (!ok) {
            w { "cancel: skipped (LiteRtLM API not present)." }
        }
    }
}

/* ───────────────────────────── R8 / Proguard NOTE ─────────────────────────────
 *
 * If you enable minify/obfuscation, reflection may fail to find methods.
 * Add keep rules for LiteRtLM methods that SLM uses, e.g.:
 *
 * -keep class com.negi.survey.slm.LiteRtLM { *; }
 *
 * Or annotate LiteRtLM with @Keep.
 *
 * ───────────────────────────────────────────────────────────────────────────── */

/* ────────────────────────── TestTag Sanitizer ────────────────────────── */

/**
 * Sanitize strings for use in test tags.
 *
 * Notes:
 * - Allow only [A-Za-z0-9_.-]
 * - Replace other characters with underscore.
 * - Truncate to [maxLen].
 */
private fun safeTestTagTokenInternal(src: String, maxLen: Int): String {
    val cleaned = buildString(src.length) {
        for (ch in src) {
            val ok = ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.'
            append(if (ok) ch else '_')
        }
    }
    return if (cleaned.length <= maxLen) cleaned else cleaned.take(maxLen)
}

/**
 * Sanitize strings for use in test tags.
 *
 * NOTE:
 * - This wrapper preserves the original extension-style call sites.
 * - The internal implementation does not reference `this` at all.
 */
private fun String.safeTestTagToken(maxLen: Int): String {
    return safeTestTagTokenInternal(src = this, maxLen = maxLen)
}