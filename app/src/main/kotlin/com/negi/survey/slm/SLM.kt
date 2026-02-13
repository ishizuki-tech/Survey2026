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
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.slm

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Message
import com.negi.survey.config.SurveyConfig
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "SLM"

/** Toggle facade logs (safe to keep enabled in dev builds). */
private const val DEBUG_SLM = true

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
    if (DEBUG_SLM) Log.d(TAG, msg())
}

/**
 * Warning log with lazy message construction.
 */
private inline fun w(t: Throwable? = null, msg: () -> String) {
    if (t != null) Log.w(TAG, msg(), t) else Log.w(TAG, msg())
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

/* ───────────────────────────── Reflection Bridge ───────────────────────────── */

/**
 * Cache methods by (name/arity) to reduce reflection scan cost.
 */
private val methodBucketCache = ConcurrentHashMap<String, List<Method>>()

/**
 * Build a cache key for method buckets.
 */
private fun bucketKey(methodName: String, argc: Int): String = "$methodName/$argc"

/**
 * Get all methods (public + declared) matching name+arity, cached.
 */
private fun getMethodBucket(cls: Class<*>, methodName: String, argc: Int): List<Method> {
    val key = bucketKey(methodName, argc)
    return methodBucketCache.getOrPut(key) {
        val all = ArrayList<Method>(64)
        all.addAll(cls.methods.filter { it.name == methodName && it.parameterTypes.size == argc })
        all.addAll(cls.declaredMethods.filter { it.name == methodName && it.parameterTypes.size == argc })
        all.distinctBy { m ->
            val sb = StringBuilder()
            sb.append(m.name).append("(")
            m.parameterTypes.forEachIndexed { i, p ->
                if (i > 0) sb.append(",")
                sb.append(p.name)
            }
            sb.append(")")
            sb.toString()
        }
    }
}

/**
 * Convert primitive parameter class to its boxed counterpart.
 */
private fun boxedOfPrimitive(p: Class<*>): Class<*>? {
    if (!p.isPrimitive) return null
    return when (p) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
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
    val wantsInt = (param == java.lang.Integer.TYPE || param == java.lang.Integer::class.java)
    val wantsLong = (param == java.lang.Long.TYPE || param == java.lang.Long::class.java)
    val wantsFloat = (param == java.lang.Float.TYPE || param == java.lang.Float::class.java)
    val wantsDouble = (param == java.lang.Double.TYPE || param == java.lang.Double::class.java)
    val wantsShort = (param == java.lang.Short.TYPE || param == java.lang.Short::class.java)
    val wantsByte = (param == java.lang.Byte.TYPE || param == java.lang.Byte::class.java)

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
    if (param == java.lang.Runnable::class.java && arg is Function0<*>) {
        return Runnable { arg.invoke() }
    }

    /** Function1 -> Consumer (best-effort). */
    if (param.name == "java.util.function.Consumer" && arg is Function1<*, *>) {
        val f = arg as Function1<Any?, Any?>
        val consumerCls = Class.forName("java.util.function.Consumer")
        /** Create a proxy via lambda adaptation (Consumer is SAM). */
        return java.lang.reflect.Proxy.newProxyInstance(
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
    }

    return arg
}

/**
 * Check if parameter can accept an arg after coercion.
 */
private fun isParamCompatible(param: Class<*>, arg: Any?): Boolean {
    if (arg == null) return !param.isPrimitive
    val coerced = coerceArgForParam(param, arg) ?: return !param.isPrimitive

    if (param.isPrimitive) {
        val boxed = boxedOfPrimitive(param) ?: return false
        return boxed.isInstance(coerced)
    }
    return param.isAssignableFrom(coerced.javaClass)
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
 * - Also try dropping last 1 / last 2 always (best-effort)
 */
private fun buildArgCandidates(args: Array<Any?>): List<Array<Any?>> {
    if (args.isEmpty()) return listOf(args)

    val out = ArrayList<Array<Any?>>(6)
    out.add(args)

    fun dropLast(n: Int) {
        if (args.size > n) out.add(args.copyOf(args.size - n))
    }

    val last = args.last()
    if (last is List<*> && last.isEmpty()) dropLast(1)
    if (last == null) dropLast(1)

    /** Generic trims (works for optional tails like systemMessage/tools). */
    dropLast(1)
    dropLast(2)

    /** De-dupe by arity. */
    return out.distinctBy { it.size }
}

/**
 * Call a LiteRtLM method by name using reflection (non-suspend).
 *
 * Returns true if invoked successfully.
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
 * Call a LiteRtLM suspend function by name using reflection.
 *
 * The Kotlin suspend method is compiled as:
 *   fun foo(..., continuation: Continuation<T>): Any?
 *
 * Returns null if method not found (caller can fallback).
 */
private suspend fun invokeLiteRtLmBestEffortSuspend(
    methodName: String,
    argsNoCont: Array<Any?>,
    onFailLog: String,
): Any? {
    val cls = LiteRtLM::class.java
    val candidates = buildArgCandidates(argsNoCont)

    return suspendCancellableCoroutine { outer ->
        /** Propagate cancellation to LiteRtLM if possible. */
        outer.invokeOnCancellation {
            d { "invokeSuspend cancelled: method='$methodName'" }
        }

        val cont = object : Continuation<Any?> {
            override val context = outer.context
            override fun resumeWith(result: Result<Any?>) {
                if (outer.isCompleted) return
                outer.resumeWith(result)
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

                val ret = m.invoke(receiver, *coercedArgs)

                if (ret !== COROUTINE_SUSPENDED) {
                    /** Completed synchronously. */
                    if (!outer.isCompleted) outer.resume(ret)
                }
                return@suspendCancellableCoroutine
            } catch (ite: InvocationTargetException) {
                val root = ite.targetException ?: ite
                w(root) { "$onFailLog (suspend target threw): name='$methodName' err=${root.message}" }
                if (!outer.isCompleted) outer.resume(null)
                return@suspendCancellableCoroutine
            } catch (t: Throwable) {
                w(t) { "$onFailLog (suspend invoke failed): name='$methodName' err=${t.message}" }
            }
        }

        /** Method not found. */
        if (!outer.isCompleted) outer.resume(null)
    }
}

/* ───────────────────────────── Facade API ──────────────────────────────── */

object SLM {

    /** True when a suspend generateText call is currently in progress. */
    fun isBusy(): Boolean {
        val ok = invokeLiteRtLmBestEffortUnit(
            methodName = "isBusy",
            args = emptyArray(),
            onFailLog = "LiteRtLM.isBusy unavailable",
        )
        if (!ok) return false

        /** If invoked, prefer reading via reflection return is hard; fallback to safe default. */
        return try {
            /** Try direct reflection-return path (secondary). */
            val cls = LiteRtLM::class.java
            val m = findBestMethod(cls, "isBusy", emptyArray()) ?: return false
            val receiver: Any? = if (Modifier.isStatic(m.modifiers)) null else LiteRtLM
            val ret = m.invoke(receiver)
            ret as? Boolean ?: false
        } catch (t: Throwable) {
            false
        }
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
     * Best-effort reflection call. If unavailable, falls back to initialize() and returns.
     */
    suspend fun initializeIfNeeded(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        d {
            "initializeIfNeeded: model='${model.name}' image=$supportImage audio=$supportAudio"
        }

        try {
            val ret = invokeLiteRtLmBestEffortSuspend(
                methodName = "initializeIfNeeded",
                argsNoCont = arrayOf(context, model, supportImage, supportAudio, systemMessage, tools),
                onFailLog = "LiteRtLM.initializeIfNeeded unavailable",
            )
            if (ret != null) return
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            w(t) { "initializeIfNeeded: reflection failed err=${t.message}" }
        }

        /** Fallback: call async initialize and return once onDone fires. */
        suspendCancellableCoroutine<Unit> { cont ->
            initialize(
                context = context,
                model = model,
                supportImage = supportImage,
                supportAudio = supportAudio,
                onDone = { _ ->
                    if (!cont.isCompleted) cont.resume(Unit)
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
            val ret = invokeLiteRtLmBestEffortSuspend(
                methodName = "generateText",
                argsNoCont = arrayOf(model, input, images, audioClips, onPartial),
                onFailLog = "LiteRtLM.generateText unavailable",
            )
            val s = ret as? String
            if (s != null) return s
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            w(t) { "generateText: reflection failed err=${t.message}" }
        }

        /** Fallback: aggregate via streaming. */
        return suspendCancellableCoroutine { cont ->
            var lastText = ""
            var doneOnce = false

            runInference(
                model = model,
                input = input,
                images = images,
                audioClips = audioClips,
                resultListener = { partial, done ->
                    lastText = partial
                    onPartial(partial)
                    if (done && !doneOnce) {
                        doneOnce = true
                        if (!cont.isCompleted) cont.resume(partial)
                    }
                },
                cleanUpListener = {
                    /** No-op; LiteRtLM owns native termination. */
                },
                onError = { msg ->
                    if (!cont.isCompleted) cont.resume("error: $msg")
                }
            )

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

/* ────────────────────────── TestTag Sanitizer (unchanged) ────────────────────────── */

/**
 * Sanitize strings for use in test tags.
 *
 * Notes:
 * - Allow only [A-Za-z0-9_.-]
 * - Replace other characters with underscore.
 * - Truncate to [maxLen].
 */
private fun String.safeTestTagToken(maxLen: Int): String {
    val cleaned = buildString(length) {
        for (ch in this@safeTestTagToken) {
            val ok = ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.'
            append(if (ok) ch else '_')
        }
    }
    return if (cleaned.length <= maxLen) cleaned else cleaned.take(maxLen)
}
