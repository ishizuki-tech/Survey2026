/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: WhisperEngine.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.whisper

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.whispercpp.whisper.WhisperContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val LOG_TAG = "WhisperEngine"
private const val DEFAULT_TARGET_SAMPLE_RATE = 16_000
private const val ASSET_KEY_PREFIX = "asset:"
private const val WAV_HEADER_BYTES_PCM16_MONO = 44L
private const val PCM16_BYTES_PER_SAMPLE = 2L

/**
 * Silence gate thresholds.
 *
 * Note:
 * - These values are conservative and meant to avoid wasting CPU on clearly silent clips.
 * - If your microphones are very quiet, lower these slightly.
 */
private const val SILENCE_RMS_THRESHOLD = 0.0025
private const val SILENCE_PEAK_THRESHOLD = 0.02

/**
 * Timing breakdown for a single transcription run.
 */
data class TranscribeTiming(
    val requestId: Long,
    val totalMs: Long,
    val decodeMs: Long,
    val lockWaitMs: Long,
    val lockHoldMs: Long,
    val attempts: List<AttemptTiming>,
    val selectedLang: String,
    val audioSeconds: Double,
) {
    data class AttemptTiming(
        val lang: String,
        val transcribeMs: Long,
        val textLen: Int,
        val success: Boolean,
        val rtf: Double,
    )
}

/**
 * Timing breakdown for a single initialization call.
 */
data class InitTiming(
    val requestId: Long,
    val totalMs: Long,
    val lockWaitMs: Long,
    val lockHoldMs: Long,
    val assetCheckMs: Long,
    val releasePrevMs: Long,
    val createMs: Long,
    val key: String,
    val previousKey: String?,
    val source: String,
    val modelBytes: Long,
)

/**
 * Thin facade for integrating Whisper.cpp into the SurveyNav app.
 *
 * Key design:
 * - The engine is a process-wide singleton.
 * - Initialization is idempotent per model key.
 * - All operations touching the underlying [WhisperContext] are serialized
 *   through a single [engineMutex].
 */
object WhisperEngine {

    /**
     * Keep context + key in a single volatile snapshot to avoid transient inconsistency
     * (ctx != null but key == null, etc.).
     */
    private data class EngineState(
        val ctx: WhisperContext,
        val key: String
    )

    @Volatile
    private var engineState: EngineState? = null

    private val engineMutex = Mutex()

    private val transcribeSeq = AtomicLong(0L)
    private val initSeq = AtomicLong(0L)

    fun currentModelKey(): String? = engineState?.key

    fun isInitialized(): Boolean = engineState != null

    fun isInitializedForFile(modelFile: File): Boolean =
        engineState?.key == modelFile.absolutePath

    fun isInitializedForAsset(assetPath: String): Boolean =
        engineState?.key == ASSET_KEY_PREFIX + assetPath

    /**
     * Estimate a PCM_16BIT MONO WAV file size in bytes.
     */
    fun estimatePcm16MonoWavBytes(seconds: Int, sampleRateHz: Int): Long {
        val s = seconds.coerceAtLeast(0).toLong()
        val sr = sampleRateHz.coerceAtLeast(1).toLong()
        return WAV_HEADER_BYTES_PCM16_MONO + (s * sr * PCM16_BYTES_PER_SAMPLE)
    }

    /**
     * Estimate audio duration (seconds) from a PCM_16BIT MONO WAV byte length.
     */
    fun estimatePcm16MonoWavSeconds(bytes: Long, sampleRateHz: Int): Double {
        val sr = sampleRateHz.coerceAtLeast(1).toDouble()
        val payload = (bytes - WAV_HEADER_BYTES_PCM16_MONO).coerceAtLeast(0L).toDouble()
        return payload / (sr * PCM16_BYTES_PER_SAMPLE.toDouble())
    }

    /**
     * Check asset existence with a fast path (openFd) and fallback (open).
     *
     * Returns:
     * - ok: true if asset is accessible
     * - bytes: asset length if known (openFd), otherwise -1
     */
    private fun checkAssetExistsAndLength(context: Context, assetPath: String): Pair<Boolean, Long> {
        val fdRes = runCatching {
            context.assets.openFd(assetPath).use { afd ->
                // afd.length can be UNKNOWN_LENGTH (-1) for some cases
                afd.length
            }
        }
        if (fdRes.isSuccess) {
            return true to fdRes.getOrDefault(-1L)
        }

        val ok = runCatching {
            context.assets.open(assetPath).use { }
            true
        }.getOrElse { false }

        return ok to -1L
    }

    suspend fun ensureInitializedFromFile(
        context: Context,
        modelFile: File,
        onTiming: (InitTiming) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {

        val requestId = initSeq.incrementAndGet()
        val totalStart = SystemClock.elapsedRealtime()

        if (!modelFile.exists() || !modelFile.isFile) {
            return@withContext Result.failure(
                IllegalArgumentException("Whisper model file does not exist: ${modelFile.path}")
            )
        }

        val key = modelFile.absolutePath
        val bytes = runCatching { modelFile.length() }.getOrDefault(-1L)
        val lastMod = runCatching { modelFile.lastModified() }.getOrDefault(0L)

        // Fast path without locking.
        val st0 = engineState
        if (st0 != null && st0.key == key) {
            val totalMs = SystemClock.elapsedRealtime() - totalStart
            Log.d(
                LOG_TAG,
                "[$requestId][init:file] Fast path (already initialized). " +
                        "totalMs=$totalMs key=$key bytes=${formatBytes(bytes)} lastMod=$lastMod thread=${Thread.currentThread().name}"
            )
            safeEmitInitTiming(
                onTiming = onTiming,
                timing = InitTiming(
                    requestId = requestId,
                    totalMs = totalMs,
                    lockWaitMs = 0L,
                    lockHoldMs = 0L,
                    assetCheckMs = 0L,
                    releasePrevMs = 0L,
                    createMs = 0L,
                    key = key,
                    previousKey = key,
                    source = "file",
                    modelBytes = bytes,
                )
            )
            return@withContext Result.success(Unit)
        }

        val lockRequestAt = SystemClock.elapsedRealtime()

        engineMutex.withLock {
            val lockAcquiredAt = SystemClock.elapsedRealtime()
            val lockWaitMs = lockAcquiredAt - lockRequestAt

            val previous = engineState
            val previousKey = previous?.key
            val current = previous?.ctx

            // Double-check inside the lock.
            val st1 = engineState
            if (st1 != null && st1.key == key) {
                val totalMs = SystemClock.elapsedRealtime() - totalStart
                val lockHoldMs = SystemClock.elapsedRealtime() - lockAcquiredAt
                Log.d(
                    LOG_TAG,
                    "[$requestId][init:file] Already initialized (locked). " +
                            "totalMs=$totalMs lockWaitMs=$lockWaitMs lockHoldMs=$lockHoldMs key=$key thread=${Thread.currentThread().name}"
                )
                safeEmitInitTiming(
                    onTiming = onTiming,
                    timing = InitTiming(
                        requestId = requestId,
                        totalMs = totalMs,
                        lockWaitMs = lockWaitMs,
                        lockHoldMs = lockHoldMs,
                        assetCheckMs = 0L,
                        releasePrevMs = 0L,
                        createMs = 0L,
                        key = key,
                        previousKey = previousKey,
                        source = "file",
                        modelBytes = bytes,
                    )
                )
                return@withLock Result.success(Unit)
            }

            Log.i(
                LOG_TAG,
                "[$requestId][init:file] Init start. lockWaitMs=$lockWaitMs prevKey=$previousKey newKey=$key " +
                        "bytes=${formatBytes(bytes)} lastMod=$lastMod thread=${Thread.currentThread().name}"
            )
            logMemory("[$requestId][init:file]")

            var releasePrevMs = 0L
            if (current != null) {
                val r0 = SystemClock.elapsedRealtime()
                runCatching {
                    Log.i(LOG_TAG, "[$requestId][init:file] Releasing previous WhisperContext for key=$previousKey")
                    current.release()
                }.onFailure { e ->
                    Log.w(LOG_TAG, "[$requestId][init:file] Error while releasing previous WhisperContext", e)
                }
                releasePrevMs = SystemClock.elapsedRealtime() - r0
                Log.i(LOG_TAG, "[$requestId][init:file] Released previous context. releasePrevMs=$releasePrevMs")
            }

            engineState = null

            val c0 = SystemClock.elapsedRealtime()
            val createdResult = runCatching {
                Log.i(LOG_TAG, "[$requestId][init:file] Creating WhisperContext from file=$key")
                WhisperContext.createContextFromFile(key)
            }.onFailure { e ->
                Log.e(LOG_TAG, "[$requestId][init:file] Failed to create WhisperContext from $key", e)
            }
            val createMs = SystemClock.elapsedRealtime() - c0

            if (createdResult.isFailure) {
                val totalMs = SystemClock.elapsedRealtime() - totalStart
                val lockHoldMs = SystemClock.elapsedRealtime() - lockAcquiredAt
                Log.e(
                    LOG_TAG,
                    "[$requestId][init:file] Init FAILED. totalMs=$totalMs lockWaitMs=$lockWaitMs lockHoldMs=$lockHoldMs " +
                            "releasePrevMs=$releasePrevMs createMs=$createMs key=$key"
                )
                safeEmitInitTiming(
                    onTiming = onTiming,
                    timing = InitTiming(
                        requestId = requestId,
                        totalMs = totalMs,
                        lockWaitMs = lockWaitMs,
                        lockHoldMs = lockHoldMs,
                        assetCheckMs = 0L,
                        releasePrevMs = releasePrevMs,
                        createMs = createMs,
                        key = key,
                        previousKey = previousKey,
                        source = "file",
                        modelBytes = bytes,
                    )
                )
                return@withLock Result.failure(createdResult.exceptionOrNull()!!)
            }

            val ctx = createdResult.getOrThrow()
            engineState = EngineState(ctx = ctx, key = key)

            val totalMs = SystemClock.elapsedRealtime() - totalStart
            val lockHoldMs = SystemClock.elapsedRealtime() - lockAcquiredAt

            Log.i(
                LOG_TAG,
                "[$requestId][init:file] Init OK. totalMs=$totalMs lockWaitMs=$lockWaitMs lockHoldMs=$lockHoldMs " +
                        "releasePrevMs=$releasePrevMs createMs=$createMs key=$key"
            )
            logMemory("[$requestId][init:file]")

            safeEmitInitTiming(
                onTiming = onTiming,
                timing = InitTiming(
                    requestId = requestId,
                    totalMs = totalMs,
                    lockWaitMs = lockWaitMs,
                    lockHoldMs = lockHoldMs,
                    assetCheckMs = 0L,
                    releasePrevMs = releasePrevMs,
                    createMs = createMs,
                    key = key,
                    previousKey = previousKey,
                    source = "file",
                    modelBytes = bytes,
                )
            )

            Result.success(Unit)
        }
    }

    suspend fun ensureInitializedFromAsset(
        context: Context,
        assetPath: String,
        onTiming: (InitTiming) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {

        val requestId = initSeq.incrementAndGet()
        val totalStart = SystemClock.elapsedRealtime()

        val a0 = SystemClock.elapsedRealtime()
        val (assetOk, assetBytes) = checkAssetExistsAndLength(context, assetPath)
        val assetCheckMs = SystemClock.elapsedRealtime() - a0

        val key = ASSET_KEY_PREFIX + assetPath

        if (!assetOk) {
            val totalMs = SystemClock.elapsedRealtime() - totalStart
            Log.e(
                LOG_TAG,
                "[$requestId][init:asset] Asset missing. totalMs=$totalMs assetCheckMs=$assetCheckMs " +
                        "path=assets/$assetPath thread=${Thread.currentThread().name}"
            )
            safeEmitInitTiming(
                onTiming = onTiming,
                timing = InitTiming(
                    requestId = requestId,
                    totalMs = totalMs,
                    lockWaitMs = 0L,
                    lockHoldMs = 0L,
                    assetCheckMs = assetCheckMs,
                    releasePrevMs = 0L,
                    createMs = 0L,
                    key = key,
                    previousKey = engineState?.key,
                    source = "asset",
                    modelBytes = assetBytes,
                )
            )
            return@withContext Result.failure(
                IllegalArgumentException("Whisper asset model does not exist: assets/$assetPath")
            )
        }

        // Fast path without locking.
        val st0 = engineState
        if (st0 != null && st0.key == key) {
            val totalMs = SystemClock.elapsedRealtime() - totalStart
            Log.d(
                LOG_TAG,
                "[$requestId][init:asset] Fast path (already initialized). totalMs=$totalMs assetCheckMs=$assetCheckMs " +
                        "key=$key bytes=${formatBytes(assetBytes)} thread=${Thread.currentThread().name}"
            )
            safeEmitInitTiming(
                onTiming = onTiming,
                timing = InitTiming(
                    requestId = requestId,
                    totalMs = totalMs,
                    lockWaitMs = 0L,
                    lockHoldMs = 0L,
                    assetCheckMs = assetCheckMs,
                    releasePrevMs = 0L,
                    createMs = 0L,
                    key = key,
                    previousKey = key,
                    source = "asset",
                    modelBytes = assetBytes,
                )
            )
            return@withContext Result.success(Unit)
        }

        val lockRequestAt = SystemClock.elapsedRealtime()

        engineMutex.withLock {
            val lockAcquiredAt = SystemClock.elapsedRealtime()
            val lockWaitMs = lockAcquiredAt - lockRequestAt

            val previous = engineState
            val previousKey = previous?.key
            val current = previous?.ctx

            // Double-check inside the lock.
            val st1 = engineState
            if (st1 != null && st1.key == key) {
                val totalMs = SystemClock.elapsedRealtime() - totalStart
                val lockHoldMs = SystemClock.elapsedRealtime() - lockAcquiredAt
                Log.d(
                    LOG_TAG,
                    "[$requestId][init:asset] Already initialized (locked). totalMs=$totalMs assetCheckMs=$assetCheckMs " +
                            "lockWaitMs=$lockWaitMs lockHoldMs=$lockHoldMs key=$key"
                )
                safeEmitInitTiming(
                    onTiming = onTiming,
                    timing = InitTiming(
                        requestId = requestId,
                        totalMs = totalMs,
                        lockWaitMs = lockWaitMs,
                        lockHoldMs = lockHoldMs,
                        assetCheckMs = assetCheckMs,
                        releasePrevMs = 0L,
                        createMs = 0L,
                        key = key,
                        previousKey = previousKey,
                        source = "asset",
                        modelBytes = assetBytes,
                    )
                )
                return@withLock Result.success(Unit)
            }

            Log.i(
                LOG_TAG,
                "[$requestId][init:asset] Init start. assetCheckMs=$assetCheckMs lockWaitMs=$lockWaitMs prevKey=$previousKey newKey=$key " +
                        "bytes=${formatBytes(assetBytes)} thread=${Thread.currentThread().name}"
            )
            logMemory("[$requestId][init:asset]")

            var releasePrevMs = 0L
            if (current != null) {
                val r0 = SystemClock.elapsedRealtime()
                runCatching {
                    Log.i(LOG_TAG, "[$requestId][init:asset] Releasing previous WhisperContext for key=$previousKey")
                    current.release()
                }.onFailure { e ->
                    Log.w(LOG_TAG, "[$requestId][init:asset] Error while releasing previous WhisperContext", e)
                }
                releasePrevMs = SystemClock.elapsedRealtime() - r0
                Log.i(LOG_TAG, "[$requestId][init:asset] Released previous context. releasePrevMs=$releasePrevMs")
            }

            engineState = null

            val c0 = SystemClock.elapsedRealtime()
            val createdResult = runCatching {
                Log.i(LOG_TAG, "[$requestId][init:asset] Creating WhisperContext from assets/$assetPath")
                WhisperContext.createContextFromAsset(context.assets, assetPath)
            }.onFailure { e ->
                Log.e(LOG_TAG, "[$requestId][init:asset] Failed to create WhisperContext from assets/$assetPath", e)
            }
            val createMs = SystemClock.elapsedRealtime() - c0

            if (createdResult.isFailure) {
                val totalMs = SystemClock.elapsedRealtime() - totalStart
                val lockHoldMs = SystemClock.elapsedRealtime() - lockAcquiredAt
                Log.e(
                    LOG_TAG,
                    "[$requestId][init:asset] Init FAILED. totalMs=$totalMs assetCheckMs=$assetCheckMs lockWaitMs=$lockWaitMs lockHoldMs=$lockHoldMs " +
                            "releasePrevMs=$releasePrevMs createMs=$createMs key=$key"
                )
                safeEmitInitTiming(
                    onTiming = onTiming,
                    timing = InitTiming(
                        requestId = requestId,
                        totalMs = totalMs,
                        lockWaitMs = lockWaitMs,
                        lockHoldMs = lockHoldMs,
                        assetCheckMs = assetCheckMs,
                        releasePrevMs = releasePrevMs,
                        createMs = createMs,
                        key = key,
                        previousKey = previousKey,
                        source = "asset",
                        modelBytes = assetBytes,
                    )
                )
                return@withLock Result.failure(createdResult.exceptionOrNull()!!)
            }

            val ctx = createdResult.getOrThrow()
            engineState = EngineState(ctx = ctx, key = key)

            val totalMs = SystemClock.elapsedRealtime() - totalStart
            val lockHoldMs = SystemClock.elapsedRealtime() - lockAcquiredAt

            Log.i(
                LOG_TAG,
                "[$requestId][init:asset] Init OK. totalMs=$totalMs assetCheckMs=$assetCheckMs lockWaitMs=$lockWaitMs lockHoldMs=$lockHoldMs " +
                        "releasePrevMs=$releasePrevMs createMs=$createMs key=$key bytes=${formatBytes(assetBytes)}"
            )
            logMemory("[$requestId][init:asset]")

            safeEmitInitTiming(
                onTiming = onTiming,
                timing = InitTiming(
                    requestId = requestId,
                    totalMs = totalMs,
                    lockWaitMs = lockWaitMs,
                    lockHoldMs = lockHoldMs,
                    assetCheckMs = assetCheckMs,
                    releasePrevMs = releasePrevMs,
                    createMs = createMs,
                    key = key,
                    previousKey = previousKey,
                    source = "asset",
                    modelBytes = assetBytes,
                )
            )

            Result.success(Unit)
        }
    }

    /**
     * Transcribe the given WAV [file] and return plain-text output.
     *
     * IMPORTANT (format safety):
     * - Do NOT call String.format / "…".format() on strings that already include
     *   dynamic text (e.g., transcript previews). '%' inside the transcript can
     *   crash Formatter with UnknownFormatConversionException.
     */
    suspend fun transcribeWaveFile(
        file: File,
        lang: String,
        translate: Boolean = false,
        printTimestamp: Boolean = false,
        targetSampleRate: Int = DEFAULT_TARGET_SAMPLE_RATE,
        enableSilenceGate: Boolean = true,
        onTiming: (TranscribeTiming) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.Default) {

        val requestId = transcribeSeq.incrementAndGet()
        val totalStart = SystemClock.elapsedRealtime()

        if (!file.exists() || !file.isFile) {
            return@withContext Result.failure(
                IllegalArgumentException("Input WAV file does not exist: ${file.path}")
            )
        }

        val sr = targetSampleRate.coerceAtLeast(1)
        val fileBytes = runCatching { file.length() }.getOrDefault(-1L)
        val approxSecFromBytes = if (fileBytes >= 0L) estimatePcm16MonoWavSeconds(fileBytes, sr) else -1.0

        val activeKeyAtStart = engineState?.key
        Log.d(
            LOG_TAG,
            "[$requestId][asr] Start. file=${file.name} bytes=${formatBytes(fileBytes)} " +
                    "approxSec(pcm16mono@${sr}Hz)=${formatFixed2(approxSecFromBytes)} " +
                    "lang=$lang translate=$translate printTimestamp=$printTimestamp sr=$sr modelKey=$activeKeyAtStart thread=${Thread.currentThread().name}"
        )
        logMemory("[$requestId][asr]")

        val decodeStart = SystemClock.elapsedRealtime()
        val pcmResult = withContext(Dispatchers.IO) {
            runCatching { decodeWaveFile(file = file, targetSampleRate = sr) }
        }.onFailure { e ->
            Log.e(LOG_TAG, "[$requestId][asr] Failed to decode WAV file=${file.path}", e)
        }
        val decodeMs = SystemClock.elapsedRealtime() - decodeStart

        if (pcmResult.isFailure) {
            val totalMs = SystemClock.elapsedRealtime() - totalStart
            Log.w(LOG_TAG, "[$requestId][asr] FAILED (decode). totalMs=$totalMs decodeMs=$decodeMs")
            return@withContext Result.failure(pcmResult.exceptionOrNull()!!)
        }

        val pcm = pcmResult.getOrThrow()

        if (pcm.isEmpty()) {
            val totalMs = SystemClock.elapsedRealtime() - totalStart
            Log.w(LOG_TAG, "[$requestId][asr] PCM empty. totalMs=$totalMs decodeMs=$decodeMs file=${file.name}")
            return@withContext Result.failure(
                IllegalStateException("Decoded PCM buffer is empty for file: ${file.name}")
            )
        }

        val audioSeconds = pcm.size.toDouble() / sr.toDouble()
        val audioSecondsStr = formatFixed2(audioSeconds)

        val stats = computePcmStatsSampled(pcm)
        Log.d(
            LOG_TAG,
            "[$requestId][asr] PCM stats(sampled): samples=${pcm.size} seconds=${audioSecondsStr} " +
                    "min=${stats.min} max=${stats.max} rms=${formatFixed2(stats.rms)} decodeMs=$decodeMs"
        )

        if (enableSilenceGate && isProbablySilent(stats)) {
            val totalMs = SystemClock.elapsedRealtime() - totalStart
            val peak = max(kotlin.math.abs(stats.min.toDouble()), kotlin.math.abs(stats.max.toDouble()))
            Log.w(
                LOG_TAG,
                "[$requestId][asr] SilenceGate: returning empty transcript. totalMs=$totalMs decodeMs=$decodeMs " +
                        "rms=${formatFixed2(stats.rms)} peak=${formatFixed2(peak)}"
            )
            safeEmitTiming(
                requestId = requestId,
                onTiming = onTiming,
                timing = TranscribeTiming(
                    requestId = requestId,
                    totalMs = totalMs,
                    decodeMs = decodeMs,
                    lockWaitMs = 0L,
                    lockHoldMs = 0L,
                    attempts = emptyList(),
                    selectedLang = lang,
                    audioSeconds = audioSeconds,
                )
            )
            return@withContext Result.success("")
        }

        val langNorm = lang.lowercase(Locale.US)
        val languageAttempts: List<String> =
            if (langNorm == "auto") listOf("auto", "en", "ja", "sw") else listOf(lang)

        val lockRequestAt = SystemClock.elapsedRealtime()

        engineMutex.withLock {
            val lockAcquiredAt = SystemClock.elapsedRealtime()
            val lockWaitMs = lockAcquiredAt - lockRequestAt

            val st = engineState
            val ctx = st?.ctx
                ?: run {
                    val totalMs = SystemClock.elapsedRealtime() - totalStart
                    val lockHoldMs = SystemClock.elapsedRealtime() - lockAcquiredAt
                    safeEmitTiming(
                        requestId = requestId,
                        onTiming = onTiming,
                        timing = TranscribeTiming(
                            requestId = requestId,
                            totalMs = totalMs,
                            decodeMs = decodeMs,
                            lockWaitMs = lockWaitMs,
                            lockHoldMs = lockHoldMs,
                            attempts = emptyList(),
                            selectedLang = lang,
                            audioSeconds = audioSeconds,
                        )
                    )
                    Log.e(
                        LOG_TAG,
                        "[$requestId][asr] FAILED (not initialized). totalMs=$totalMs decodeMs=$decodeMs lockWaitMs=$lockWaitMs lockHoldMs=$lockHoldMs modelKey=${engineState?.key}"
                    )
                    return@withLock Result.failure(
                        IllegalStateException(
                            "WhisperEngine is not initialized. " +
                                    "Call ensureInitializedFromFile() or ensureInitializedFromAsset() first."
                        )
                    )
                }

            val attemptTimings = mutableListOf<TranscribeTiming.AttemptTiming>()

            var lastFailure: Throwable? = null
            var selectedLang = languageAttempts.lastOrNull() ?: lang

            Log.i(
                LOG_TAG,
                "[$requestId][asr] Mutex acquired. lockWaitMs=$lockWaitMs attempts=${languageAttempts.joinToString(",")} " +
                        "audio=${audioSecondsStr}s modelKey=${st.key}"
            )

            for (code in languageAttempts) {
                selectedLang = code

                val tStart = SystemClock.elapsedRealtime()
                val result = runCatching {
                    Log.i(
                        LOG_TAG,
                        "[$requestId][asr] Transcribing: lang=$code translate=$translate ts=$printTimestamp samples=${pcm.size} audio=${audioSecondsStr}s"
                    )
                    ctx.transcribeData(
                        data = pcm,
                        lang = code,
                        translate = translate,
                        printTimestamp = printTimestamp
                    )
                }.onFailure { e ->
                    Log.e(
                        LOG_TAG,
                        "[$requestId][asr] Whisper transcription failed. file=${file.path} lang=$code",
                        e
                    )
                }
                val transcribeMs = SystemClock.elapsedRealtime() - tStart
                val rtf = if (audioSeconds > 0.0) (transcribeMs / 1000.0) / audioSeconds else 0.0
                val rtfStr = formatFixed2(rtf)

                if (result.isFailure) {
                    lastFailure = result.exceptionOrNull()
                    attemptTimings += TranscribeTiming.AttemptTiming(
                        lang = code,
                        transcribeMs = transcribeMs,
                        textLen = 0,
                        success = false,
                        rtf = rtf,
                    )
                    continue
                }

                val trimmed = result.getOrThrow().trim()

                attemptTimings += TranscribeTiming.AttemptTiming(
                    lang = code,
                    transcribeMs = transcribeMs,
                    textLen = trimmed.length,
                    success = trimmed.isNotEmpty(),
                    rtf = rtf,
                )

                val preview =
                    if (trimmed.length > 160) trimmed.take(110) + " … " + trimmed.takeLast(25)
                    else trimmed

                // IMPORTANT: preview may contain '%' (e.g., "10%"). Never feed it into Formatter.
                Log.d(
                    LOG_TAG,
                    "[$requestId][asr] Attempt done: lang=$code transcribeMs=$transcribeMs rtf=$rtfStr " +
                            "textLen=${trimmed.length} preview=\"${escapeForLog(preview)}\""
                )

                if (trimmed.isNotEmpty()) {
                    val totalMs = SystemClock.elapsedRealtime() - totalStart
                    val lockHoldMs = SystemClock.elapsedRealtime() - lockAcquiredAt

                    safeEmitTiming(
                        requestId = requestId,
                        onTiming = onTiming,
                        timing = TranscribeTiming(
                            requestId = requestId,
                            totalMs = totalMs,
                            decodeMs = decodeMs,
                            lockWaitMs = lockWaitMs,
                            lockHoldMs = lockHoldMs,
                            attempts = attemptTimings.toList(),
                            selectedLang = code,
                            audioSeconds = audioSeconds,
                        )
                    )

                    Log.i(
                        LOG_TAG,
                        "[$requestId][asr] OK. totalMs=$totalMs decodeMs=$decodeMs lockWaitMs=$lockWaitMs lockHoldMs=$lockHoldMs selectedLang=$code modelKey=${engineState?.key}"
                    )
                    logMemory("[$requestId][asr]")

                    return@withLock Result.success(trimmed)
                }

                Log.w(
                    LOG_TAG,
                    "[$requestId][asr] Empty transcript: lang=$code transcribeMs=$transcribeMs rtf=$rtfStr (continuing)"
                )
            }

            val totalMs = SystemClock.elapsedRealtime() - totalStart
            val lockHoldMs = SystemClock.elapsedRealtime() - lockAcquiredAt

            safeEmitTiming(
                requestId = requestId,
                onTiming = onTiming,
                timing = TranscribeTiming(
                    requestId = requestId,
                    totalMs = totalMs,
                    decodeMs = decodeMs,
                    lockWaitMs = lockWaitMs,
                    lockHoldMs = lockHoldMs,
                    attempts = attemptTimings.toList(),
                    selectedLang = selectedLang,
                    audioSeconds = audioSeconds,
                )
            )

            Log.i(
                LOG_TAG,
                "[$requestId][asr] END. totalMs=$totalMs decodeMs=$decodeMs lockWaitMs=$lockWaitMs lockHoldMs=$lockHoldMs selectedLang=$selectedLang modelKey=${engineState?.key}"
            )
            logMemory("[$requestId][asr]")

            return@withLock when {
                lastFailure != null -> Result.failure(lastFailure)
                else -> Result.failure(IllegalStateException("Transcription produced no usable result."))
            }
        }
    }

    suspend fun detach() {
        engineMutex.withLock {
            Log.d(LOG_TAG, "Detach called. Keeping native context alive for key=${engineState?.key}")
        }
    }

    suspend fun release() {
        engineMutex.withLock {
            val st = engineState
            if (st == null) return

            engineState = null

            runCatching {
                Log.i(LOG_TAG, "Releasing WhisperContext for ${st.key}")
                st.ctx.release()
            }.onFailure { e ->
                Log.w(LOG_TAG, "Error while releasing WhisperContext", e)
            }
        }
    }

    suspend fun cleanUp() {
        release()
    }

    private data class PcmStats(
        val min: Float,
        val max: Float,
        val rms: Double
    )

    /**
     * Computes PCM stats using sampling to avoid O(N) cost on very large buffers.
     */
    private fun computePcmStatsSampled(pcm: FloatArray, maxSamples: Int = 200_000): PcmStats {
        if (pcm.isEmpty()) return PcmStats(0f, 0f, 0.0)

        val n = pcm.size
        val step = max(1, n / max(1, maxSamples))

        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        var sumSq = 0.0
        var count = 0

        var i = 0
        while (i < n) {
            val v = pcm[i]
            if (v < minV) minV = v
            if (v > maxV) maxV = v
            sumSq += v.toDouble() * v.toDouble()
            count++
            i += step
        }

        val rms = if (count > 0) sqrt(sumSq / count.toDouble()) else 0.0

        return PcmStats(
            min = if (minV.isFinite()) minV else 0f,
            max = if (maxV.isFinite()) maxV else 0f,
            rms = rms
        )
    }

    /**
     * Returns true when the signal looks like silence.
     */
    private fun isProbablySilent(stats: PcmStats): Boolean {
        val peak = max(kotlin.math.abs(stats.min.toDouble()), kotlin.math.abs(stats.max.toDouble()))
        return stats.rms < SILENCE_RMS_THRESHOLD && peak < SILENCE_PEAK_THRESHOLD
    }

    private fun safeEmitInitTiming(onTiming: (InitTiming) -> Unit, timing: InitTiming) {
        runCatching { onTiming(timing) }
            .onFailure { t -> Log.w(LOG_TAG, "[init] onTiming callback failed: ${t.message}", t) }
    }

    private fun safeEmitTiming(requestId: Long, onTiming: (TranscribeTiming) -> Unit, timing: TranscribeTiming) {
        runCatching { onTiming(timing) }
            .onFailure { t -> Log.w(LOG_TAG, "[$requestId][asr] onTiming callback failed: ${t.message}", t) }
    }

    private fun logMemory(prefix: String) {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory())
        val total = rt.totalMemory()
        val maxMem = rt.maxMemory()
        val native = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(-1L)

        Log.d(
            LOG_TAG,
            "$prefix mem: javaUsed=${formatBytes(used)} javaTotal=${formatBytes(total)} javaMax=${formatBytes(maxMem)} nativeAllocated=${formatBytes(native)}"
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "n/a"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        val b = bytes.toDouble()
        return when {
            b >= gb -> String.format(Locale.US, "%.2fGB", b / gb)
            b >= mb -> String.format(Locale.US, "%.2fMB", b / mb)
            b >= kb -> String.format(Locale.US, "%.2fKB", b / kb)
            else -> "${bytes}B"
        }
    }

    private fun formatFixed2(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }

    private fun escapeForLog(text: String): String {
        if (text.isEmpty()) return text
        return buildString(text.length) {
            for (ch in text) {
                when (ch) {
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
    }
}
