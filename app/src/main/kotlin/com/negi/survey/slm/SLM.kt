/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SLM.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Compatibility facade over the app's LiteRtLM wrapper.
 *
 *  Design:
 *   - Keep the existing SLM.* call surface for the rest of the app.
 *   - Delegate directly to LiteRtLM so signature drift becomes a compile-time
 *     error instead of a reflection-time runtime failure.
 *   - Keep StreamDeltaNormalizer as a shared compatibility helper for callers
 *     that still need to normalize legacy streaming callback styles.
 *
 *  Important:
 *   - This file does NOT call the Google LiteRT-LM Engine/Conversation APIs
 *     directly. LiteRtLM.kt owns that lifecycle.
 *   - LiteRtLM.kt is responsible for Engine/Conversation creation, native
 *     stream termination, deferred cleanup, cancellation, and late-callback
 *     suppression.
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
import java.util.Locale

private const val TAG = "SLM"

/** Toggle facade logs in development builds only. */
private val DEBUG_SLM: Boolean = BuildConfig.DEBUG

/**
 * Hardware accelerator options currently supported by the app's LiteRtLM
 * wrapper.
 *
 * Keep this aligned with LiteRtLM.preferredBackend().
 */
enum class Accelerator(val label: String) {
    CPU("CPU"),
    GPU("GPU"),
}

/**
 * Configuration keys consumed by the app's LiteRtLM wrapper.
 */
enum class ConfigKey {
    MAX_TOKENS,
    TOP_K,
    TOP_P,
    TEMPERATURE,
    ACCELERATOR,
}

/**
 * Streaming callback.
 *
 * Contract used by LiteRtLM:
 * - [partialResult] is a delta chunk.
 * - [done] marks logical generation completion.
 * - Native termination is reported separately through [CleanUpListener].
 */
typealias ResultListener = (partialResult: String, done: Boolean) -> Unit

/**
 * Callback invoked after LiteRtLM reaches its native stream termination safe
 * point, or after its own bounded hard-close fallback.
 */
typealias CleanUpListener = () -> Unit

private const val DEFAULT_MAX_TOKENS = 4096
private const val DEFAULT_TOP_K = 40
private const val DEFAULT_TOP_P = 0.9f
private const val DEFAULT_TEMPERATURE = 0.7f

/**
 * Project-level cap.
 *
 * This is intentionally kept aligned with the current LiteRtLM.kt wrapper.
 * It is NOT a fixed limit imposed by the upstream LiteRT-LM Kotlin API.
 */
private const val APP_MAX_TOKENS = 4096

/**
 * The app wrapper currently sanitizes temperature into this conservative band.
 */
private const val APP_MAX_TEMPERATURE = 2.0f

// =====================================================================
// Logging
// =====================================================================

private inline fun d(message: () -> String) {
    if (DEBUG_SLM) {
        RuntimeLogStore.d(TAG, message())
    }
}

private inline fun w(
    throwable: Throwable? = null,
    message: () -> String,
) {
    if (throwable != null) {
        RuntimeLogStore.w(TAG, message(), throwable)
    } else {
        RuntimeLogStore.w(TAG, message())
    }
}

// =====================================================================
// Model configuration
// =====================================================================

/**
 * Lightweight app-level model descriptor.
 *
 * LiteRtLM.kt owns the actual Engine / Conversation lifecycle.
 */
data class Model(
    val name: String,
    val taskPath: String,
    val config: Map<ConfigKey, Any> = emptyMap(),
) {
    fun getPath(): String = taskPath

    fun getIntConfigValue(
        key: ConfigKey,
        default: Int,
    ): Int =
        when (val value = config[key]) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: default
            else -> default
        }

    fun getFloatConfigValue(
        key: ConfigKey,
        default: Float,
    ): Float =
        when (val value = config[key]) {
            is Number -> value.toFloat()
            is String -> value.trim().toFloatOrNull() ?: default
            else -> default
        }

    fun getStringConfigValue(
        key: ConfigKey,
        default: String,
    ): String =
        when (val value = config[key]) {
            is String -> value
            is Accelerator -> value.label
            else -> default
        }
}

/**
 * Normalize accelerator configuration.
 *
 * The existing LiteRtLM wrapper currently supports CPU/GPU and defaults to GPU
 * for backward compatibility, so this facade preserves the same behavior.
 */
private fun parseAcceleratorLabel(raw: String?): String {
    val normalized =
        raw
            ?.trim()
            ?.uppercase(Locale.US)
            .orEmpty()

    return when (normalized) {
        Accelerator.CPU.label -> Accelerator.CPU.label
        Accelerator.GPU.label -> Accelerator.GPU.label
        "" -> Accelerator.GPU.label

        else -> {
            w {
                "Unknown accelerator '$raw'; falling back to ${Accelerator.GPU.label}."
            }
            Accelerator.GPU.label
        }
    }
}

private fun normalizeNumberTypes(
    config: MutableMap<ConfigKey, Any>,
) {
    config[ConfigKey.MAX_TOKENS] =
        when (val value = config[ConfigKey.MAX_TOKENS]) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: DEFAULT_MAX_TOKENS
            else -> DEFAULT_MAX_TOKENS
        }

    config[ConfigKey.TOP_K] =
        when (val value = config[ConfigKey.TOP_K]) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: DEFAULT_TOP_K
            else -> DEFAULT_TOP_K
        }

    config[ConfigKey.TOP_P] =
        when (val value = config[ConfigKey.TOP_P]) {
            is Number -> value.toFloat()
            is String -> value.trim().toFloatOrNull() ?: DEFAULT_TOP_P
            else -> DEFAULT_TOP_P
        }

    config[ConfigKey.TEMPERATURE] =
        when (val value = config[ConfigKey.TEMPERATURE]) {
            is Number -> value.toFloat()
            is String -> value.trim().toFloatOrNull() ?: DEFAULT_TEMPERATURE
            else -> DEFAULT_TEMPERATURE
        }

    config[ConfigKey.ACCELERATOR] =
        parseAcceleratorLabel(
            when (val value = config[ConfigKey.ACCELERATOR]) {
                is String -> value
                is Accelerator -> value.label
                else -> null
            }
        )
}

/**
 * Clamp values to the ranges expected by the current app wrapper.
 *
 * Notes:
 * - topK is required to be positive. Do not impose an arbitrary upper limit
 *   here; LiteRT-LM itself only requires a positive value.
 * - topP is constrained to [0, 1].
 * - temperature is kept in the app wrapper's current conservative [0, 2] band.
 * - max tokens stays aligned with the current LiteRtLM.kt project cap.
 */
private fun clampRanges(
    config: MutableMap<ConfigKey, Any>,
) {
    val maxTokens =
        (config[ConfigKey.MAX_TOKENS] as Number)
            .toInt()
            .coerceIn(1, APP_MAX_TOKENS)

    val topK =
        (config[ConfigKey.TOP_K] as Number)
            .toInt()
            .coerceAtLeast(1)

    val topP =
        (config[ConfigKey.TOP_P] as Number)
            .toFloat()
            .let { value ->
                if (value.isFinite()) {
                    value.coerceIn(0f, 1f)
                } else {
                    DEFAULT_TOP_P
                }
            }

    val temperature =
        (config[ConfigKey.TEMPERATURE] as Number)
            .toFloat()
            .let { value ->
                if (value.isFinite()) {
                    value.coerceIn(0f, APP_MAX_TEMPERATURE)
                } else {
                    DEFAULT_TEMPERATURE
                }
            }

    config[ConfigKey.MAX_TOKENS] = maxTokens
    config[ConfigKey.TOP_K] = topK
    config[ConfigKey.TOP_P] = topP
    config[ConfigKey.TEMPERATURE] = temperature
}

/**
 * Build the normalized app-level model configuration from survey metadata.
 */
fun buildModelConfig(
    slm: SurveyConfig.SlmMeta,
): MutableMap<ConfigKey, Any> {
    val config =
        mutableMapOf<ConfigKey, Any>(
            ConfigKey.ACCELERATOR to
                    parseAcceleratorLabel(
                        slm.accelerator ?: Accelerator.GPU.label
                    ),
            ConfigKey.MAX_TOKENS to
                    (slm.maxTokens ?: DEFAULT_MAX_TOKENS),
            ConfigKey.TOP_K to
                    (slm.topK ?: DEFAULT_TOP_K),
            ConfigKey.TOP_P to
                    (slm.topP ?: DEFAULT_TOP_P),
            ConfigKey.TEMPERATURE to
                    (slm.temperature ?: DEFAULT_TEMPERATURE),
        )

    normalizeNumberTypes(config)
    clampRanges(config)

    d {
        "buildModelConfig: " +
                "accel=${config[ConfigKey.ACCELERATOR]} " +
                "maxTokens=${config[ConfigKey.MAX_TOKENS]} " +
                "topK=${config[ConfigKey.TOP_K]} " +
                "topP=${config[ConfigKey.TOP_P]} " +
                "temperature=${config[ConfigKey.TEMPERATURE]}"
    }

    return config
}

// =====================================================================
// Stream delta compatibility helper
// =====================================================================

/**
 * Convert legacy streaming partials into delta chunks.
 *
 * Some older/adapted backends may emit:
 * - DELTA: only newly generated text
 * - ACCUMULATED: complete generated text-so-far
 *
 * The current LiteRtLM -> SLM contract is DELTA. Keep this helper for existing
 * callers and tests that still need AUTO/ACCUMULATED compatibility.
 */
internal class StreamDeltaNormalizer(
    modeHint: PartialMode = PartialMode.AUTO,
    private val prefixSampleChars: Int = 128,
    private val boundarySampleChars: Int = 64,
) {
    enum class PartialMode {
        AUTO,
        DELTA,
        ACCUMULATED,
    }

    companion object {
        private const val MIN_STRONG_SAMPLE_CHARS = 16
        private const val SMALL_PREV_FORCE_GROWTH_CHARS = 8
        private const val MIN_GROWTH_CHARS = 1

        /**
         * If an accumulated stream no longer resembles accumulated output,
         * downgrade after two consecutive mismatches.
         */
        private const val ACCUM_MISMATCH_TO_DELTA_THRESHOLD = 2
    }

    private var decided: PartialMode = modeHint

    private var lastLen: Int = 0
    private var prefixSample: String = ""
    private var boundarySample: String = ""

    private var firstChunk: String? = null
    private var firstChunkLen: Int = 0

    private var accumMismatchCount: Int = 0

    fun toDelta(incoming: String): String {
        if (incoming.isEmpty()) {
            return ""
        }

        return when (decided) {
            PartialMode.DELTA -> incoming
            PartialMode.ACCUMULATED -> accumulatedDelta(incoming)
            PartialMode.AUTO -> autoDelta(incoming)
        }
    }

    private fun autoDelta(incoming: String): String {
        if (lastLen == 0) {
            seed(
                text = incoming,
                allowFirstChunk = true,
            )
            return incoming
        }

        val looksAccumulated =
            looksLikeAccumulated(incoming)

        decided =
            if (looksAccumulated) {
                PartialMode.ACCUMULATED
            } else {
                PartialMode.DELTA
            }

        firstChunk = null
        firstChunkLen = 0

        return if (decided == PartialMode.ACCUMULATED) {
            accumulatedDelta(incoming)
        } else {
            seed(
                text = incoming,
                allowFirstChunk = false,
            )
            incoming
        }
    }

    private fun accumulatedDelta(
        incoming: String,
    ): String {
        if (lastLen == 0) {
            seed(
                text = incoming,
                allowFirstChunk = false,
            )
            accumMismatchCount = 0
            return incoming
        }

        if (!looksLikeAccumulated(incoming)) {
            accumMismatchCount++

            if (
                accumMismatchCount >=
                ACCUM_MISMATCH_TO_DELTA_THRESHOLD
            ) {
                decided = PartialMode.DELTA

                d {
                    "StreamDeltaNormalizer: " +
                            "downgrade to DELTA after " +
                            "$accumMismatchCount mismatches"
                }
            }

            seed(
                text = incoming,
                allowFirstChunk = false,
            )

            return incoming
        }

        accumMismatchCount = 0

        val delta =
            if (incoming.length >= lastLen) {
                incoming.substring(lastLen)
            } else {
                incoming
            }

        seed(
            text = incoming,
            allowFirstChunk = false,
        )

        return delta
    }

    private fun seed(
        text: String,
        allowFirstChunk: Boolean,
    ) {
        lastLen = text.length
        prefixSample = text.take(prefixSampleChars)
        boundarySample = text.takeLast(boundarySampleChars)

        if (allowFirstChunk) {
            val cap = 4_096
            val canKeep =
                text.length in
                        MIN_STRONG_SAMPLE_CHARS..cap

            firstChunk =
                if (canKeep) {
                    text
                } else {
                    null
                }

            firstChunkLen = text.length
        }
    }

    private fun looksLikeAccumulated(
        incoming: String,
    ): Boolean {
        if (incoming.length < lastLen) {
            return false
        }

        val growth =
            incoming.length - lastLen

        if (growth < MIN_GROWTH_CHARS) {
            return false
        }

        if (
            prefixSample.isNotEmpty() &&
            !incoming.startsWith(prefixSample)
        ) {
            return false
        }

        val first = firstChunk

        if (
            first != null &&
            firstChunkLen >= MIN_STRONG_SAMPLE_CHARS &&
            incoming.length >= firstChunkLen &&
            incoming.startsWith(first)
        ) {
            return true
        }

        if (
            lastLen < MIN_STRONG_SAMPLE_CHARS &&
            growth < SMALL_PREV_FORCE_GROWTH_CHARS
        ) {
            return false
        }

        if (
            prefixSample.length >= MIN_STRONG_SAMPLE_CHARS &&
            !incoming.startsWith(prefixSample)
        ) {
            return false
        }

        if (
            boundarySample.length >=
            MIN_STRONG_SAMPLE_CHARS
        ) {
            val start =
                (lastLen - boundarySample.length)
                    .coerceAtLeast(0)

            val boundaryMatches =
                incoming.regionMatches(
                    thisOffset = start,
                    other = boundarySample,
                    otherOffset = 0,
                    length = boundarySample.length,
                    ignoreCase = false,
                )

            if (!boundaryMatches) {
                return false
            }
        }

        return true
    }
}

// =====================================================================
// Direct LiteRtLM facade
// =====================================================================

/**
 * Stable compatibility facade used by the rest of the application.
 *
 * Why direct delegation:
 * - LiteRtLM is part of this same application module.
 * - Compile-time signature checks are safer than reflection.
 * - A LiteRtLM API change should fail the build instead of silently selecting
 *   a shorter or wrong overload at runtime.
 * - Removing reflection also removes the need for R8 keep rules solely for
 *   these method names.
 */
object SLM {

    /**
     * Global high-level busy indicator exposed by LiteRtLM.
     */
    fun isBusy(): Boolean =
        LiteRtLM.isBusy()

    /**
     * Backward-compatible overload.
     *
     * LiteRtLM currently exposes a global busy state rather than a model-keyed
     * public busy state.
     */
    fun isBusy(
        @Suppress("UNUSED_PARAMETER")
        model: Model,
    ): Boolean =
        LiteRtLM.isBusy()

    /**
     * Install application context used by LiteRtLM for best-effort re-init.
     */
    fun setApplicationContext(
        context: Context,
    ) {
        val appContext =
            context.applicationContext ?: context

        LiteRtLM.setApplicationContext(appContext)
    }

    /**
     * Callback-style initialization.
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
        val appContext =
            context.applicationContext ?: context

        d {
            "initialize: model='${model.name}' " +
                    "path='${model.taskPath}' " +
                    "image=$supportImage audio=$supportAudio"
        }

        try {
            LiteRtLM.initialize(
                context = appContext,
                model = model,
                supportImage = supportImage,
                supportAudio = supportAudio,
                onDone = onDone,
                systemMessage = systemMessage,
                tools = tools,
            )
        } catch (t: Throwable) {
            w(t) {
                "initialize failed synchronously: " +
                        "model='${model.name}' err=${t.message}"
            }

            onDone(
                "LiteRtLM initialization failed: " +
                        (t.message ?: t.javaClass.simpleName)
            )
        }
    }

    /**
     * Suspend-style initialization.
     *
     * Cancellation propagates directly to LiteRtLM.
     */
    suspend fun initializeIfNeeded(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val appContext =
            context.applicationContext ?: context

        d {
            "initializeIfNeeded: model='${model.name}' " +
                    "image=$supportImage audio=$supportAudio"
        }

        LiteRtLM.initializeIfNeeded(
            context = appContext,
            model = model,
            supportImage = supportImage,
            supportAudio = supportAudio,
            systemMessage = systemMessage,
            tools = tools,
        )
    }

    /**
     * Reset the model conversation while reusing the Engine.
     */
    fun resetConversation(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        d {
            "resetConversation: model='${model.name}' " +
                    "image=$supportImage audio=$supportAudio"
        }

        LiteRtLM.resetConversation(
            model = model,
            supportImage = supportImage,
            supportAudio = supportAudio,
            systemMessage = systemMessage,
            tools = tools,
        )
    }

    /**
     * Reset the model Conversation and suspend until the replacement session is
     * fully created.
     *
     * Use this variant when the caller owns a serialization gate that must not
     * be released before LiteRT-LM session repair is complete.
     */
    suspend fun resetConversationAndWait(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        d {
            "resetConversationAndWait: model='${model.name}' " +
                    "image=$supportImage audio=$supportAudio"
        }

        LiteRtLM.resetConversationAndWait(
            model = model,
            supportImage = supportImage,
            supportAudio = supportAudio,
            systemMessage = systemMessage,
            tools = tools,
        )
    }

    /**
     * Request LiteRtLM's deferred idle cleanup.
     */
    fun cleanUp(
        model: Model,
        onDone: () -> Unit,
    ) {
        d {
            "cleanUp: model='${model.name}'"
        }

        LiteRtLM.cleanUp(
            model = model,
            onDone = onDone,
        )
    }

    /**
     * Request immediate/best-effort teardown.
     *
     * LiteRtLM itself defers destruction if a native stream is still active.
     */
    fun forceCleanUp(
        model: Model,
        onDone: () -> Unit,
    ) {
        d {
            "forceCleanUp: model='${model.name}'"
        }

        LiteRtLM.forceCleanUp(
            model = model,
            onDone = onDone,
        )
    }

    /**
     * Recovery teardown that suspends until the runtime is no longer active and
     * any remaining Engine/Conversation has been closed.
     *
     * This is intentionally stronger than [forceCleanUp], which preserves the
     * legacy callback-style deferred teardown contract.
     */
    suspend fun forceCleanUpAndWait(
        model: Model,
    ) {
        d {
            "forceCleanUpAndWait: model='${model.name}'"
        }

        LiteRtLM.forceCleanUpAndWait(
            model = model,
        )
    }

    /**
     * Start streaming inference.
     *
     * Contract:
     * - resultListener receives DELTA chunks.
     * - done=true is logical completion.
     * - cleanUpListener is the native termination safe-point callback.
     */
    fun runInference(
        model: Model,
        input: String,
        resultListener: ResultListener,
        cleanUpListener: CleanUpListener,
        onError: (message: String) -> Unit = {},
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        onRunStarted: (Long) -> Unit = {},
    ) {
        d {
            "runInference: model='${model.name}' " +
                    "textLen=${input.length} " +
                    "images=${images.size} " +
                    "audio=${audioClips.size}"
        }

        /**
         * AiRepository owns cancellation interpretation, so keep
         * notifyCancelToOnError=false here.
         */
        LiteRtLM.runInference(
            model = model,
            input = input,
            resultListener = resultListener,
            cleanUpListener = cleanUpListener,
            onError = onError,
            images = images,
            audioClips = audioClips,
            notifyCancelToOnError = false,
            onRunStarted = onRunStarted,
        )
    }

    /**
     * High-level suspend generation API.
     *
     * No callback/reflection fallback is needed because LiteRtLM currently
     * exposes generateText directly.
     */
    suspend fun generateText(
        model: Model,
        input: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        onPartial: (String) -> Unit = {},
        maxOutputTokens: Int? = null,
    ): String {
        d {
            "generateText: model='${model.name}' " +
                    "textLen=${input.length} " +
                    "images=${images.size} " +
                    "audio=${audioClips.size}"
        }

        return if (maxOutputTokens == null) {
            LiteRtLM.generateText(
                model = model,
                input = input,
                images = images,
                audioClips = audioClips,
                onPartial = onPartial,
            )
        } else {
            LiteRtLM.generateText(
                model = model,
                input = input,
                images = images,
                audioClips = audioClips,
                onPartial = onPartial,
                maxOutputTokens = maxOutputTokens,
            )
        }
    }

    /**
     * Best-effort cancellation for the active run belonging to [model].
     */
    fun cancel(
        model: Model,
    ) {
        d {
            "cancel: model='${model.name}'"
        }

        LiteRtLM.cancel(model)
    }

    /** Cancel only the specific LiteRtLM run owned by the caller. */
    fun cancel(
        model: Model,
        expectedRunId: Long,
    ) {
        d {
            "cancel: model='${model.name}' expectedRunId=$expectedRunId"
        }

        LiteRtLM.cancel(
            model = model,
            expectedRunId = expectedRunId,
        )
    }
}

// =====================================================================
// Test-tag sanitizer
// =====================================================================

private fun safeTestTagTokenInternal(
    source: String,
    maxLen: Int,
): String {
    val limit =
        maxLen.coerceAtLeast(0)

    val cleaned =
        buildString(source.length) {
            for (character in source) {
                val allowed =
                    character.isLetterOrDigit() ||
                            character == '_' ||
                            character == '-' ||
                            character == '.'

                append(
                    if (allowed) {
                        character
                    } else {
                        '_'
                    }
                )
            }
        }

    return cleaned.take(limit)
}

private fun String.safeTestTagToken(
    maxLen: Int,
): String =
    safeTestTagTokenInternal(
        source = this,
        maxLen = maxLen,
    )
