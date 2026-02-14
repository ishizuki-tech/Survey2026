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
 *
 * @property requestId Monotonic id for correlating logs.
 * @property totalMs End-to-end time for transcribeWaveFile().
 * @property decodeMs Time spent in decodeWaveFile().
 * @property lockWaitMs Time waiting to acquire engineMutex.
 * @property lockHoldMs Time spent inside engineMutex critical section.
 * @property attempts Per-language attempt timings (transcribeData only).
 * @property selectedLang Language code that produced the returned transcript (or last tried).
 * @property audioSeconds Approx audio length in seconds based on PCM and sample rate.
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
    /**
     * Single attempt timing for one language code.
     *
     * @property lang Language code used for transcribeData().
     * @property transcribeMs Time spent in ctx.transcribeData().
     * @property textLen Length of trimmed transcript.
     * @property success True when transcript was non-empty.
     * @property rtf Real-time factor ~= transcribeSeconds / audioSeconds (lower is faster).
     */
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
 *
 * @property requestId Monotonic id for correlating logs.
 * @property totalMs End-to-end time for ensureInitializedFromFile/Asset().
 * @property lockWaitMs Time waiting to acquire engineMutex.
 * @property lockHoldMs Time spent inside engineMutex critical section.
 * @property assetCheckMs Time spent checking asset existence (asset init only).
 * @property releasePrevMs Time spent releasing a previous WhisperContext (if any).
 * @property createMs Time spent creating a new WhisperContext.
 * @property key Selected model key after init attempt.
 * @property previousKey Previous model key before init attempt.
 * @property source "file" or "asset".
 * @property modelBytes Model file size (file init) or extracted asset size if available (usually -1).
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
 * This object wraps [WhisperContext] and [decodeWaveFile] to provide a small,
 * suspend-friendly API.
 *
 * Key design:
 * - The engine is a process-wide singleton.
 * - Initialization is idempotent per model key.
 * - All operations touching the underlying [WhisperContext] are serialized
 *   through a single [engineMutex].
 *
 * Two-level cleanup:
 * - [detach] is a soft UI-level detach that keeps the native context alive.
 * - [release] is a hard cleanup that closes native resources.
 */
object WhisperEngine {

    @Volatile
    private var whisperContext: WhisperContext? = null

    @Volatile
    private var modelKey: String? = null

    private val engineMutex = Mutex()

    private val transcribeSeq = AtomicLong(0L)
    private val initSeq = AtomicLong(0L)

    fun currentModelKey(): String? = modelKey

    fun isInitialized(): Boolean = whisperContext != null

    fun isInitializedForFile(modelFile: File): Boolean =
        whisperContext != null && modelKey == modelFile.absolutePath

    fun isInitializedForAsset(assetPath: String): Boolean =
        whisperContext != null && modelKey == ASSET_KEY_PREFIX + assetPath

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

    suspend fun ensureInitializedFromFile(
        context: Context,
        modelFile: File,
        onTiming: (InitTiming) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.Default) {

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
        if (whisperContext != null && modelKey == key) {
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

            val previousKey = modelKey
            val current = whisperContext

            // Double-check inside the lock.
            if (current != null && modelKey == key) {
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

            whisperContext = null
            modelKey = null

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

            whisperContext = createdResult.getOrThrow()
            modelKey = key

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
    ): Result<Unit> = withContext(Dispatchers.Default) {

        val requestId = initSeq.incrementAndGet()
        val totalStart = SystemClock.elapsedRealtime()

        val a0 = SystemClock.elapsedRealtime()
        val assetOk = runCatching {
            context.assets.open(assetPath).use { }
            true
        }.getOrElse { false }
        val assetCheckMs = SystemClock.elapsedRealtime() - a0

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
                    key = ASSET_KEY_PREFIX + assetPath,
                    previousKey = modelKey,
                    source = "asset",
                    modelBytes = -1L,
                )
            )
            return@withContext Result.failure(
                IllegalArgumentException("Whisper asset model does not exist: assets/$assetPath")
            )
        }

        val key = ASSET_KEY_PREFIX + assetPath

        // Fast path without locking.
        if (whisperContext != null && modelKey == key) {
            val totalMs = SystemClock.elapsedRealtime() - totalStart
            Log.d(
                LOG_TAG,
                "[$requestId][init:asset] Fast path (already initialized). totalMs=$totalMs assetCheckMs=$assetCheckMs " +
                        "key=$key thread=${Thread.currentThread().name}"
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
                    modelBytes = -1L,
                )
            )
            return@withContext Result.success(Unit)
        }

        val lockRequestAt = SystemClock.elapsedRealtime()

        engineMutex.withLock {
            val lockAcquiredAt = SystemClock.elapsedRealtime()
            val lockWaitMs = lockAcquiredAt - lockRequestAt

            val previousKey = modelKey
            val current = whisperContext

            // Double-check inside the lock.
            if (current != null && modelKey == key) {
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
                        modelBytes = -1L,
                    )
                )
                return@withLock Result.success(Unit)
            }

            Log.i(
                LOG_TAG,
                "[$requestId][init:asset] Init start. assetCheckMs=$assetCheckMs lockWaitMs=$lockWaitMs prevKey=$previousKey newKey=$key " +
                        "thread=${Thread.currentThread().name}"
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

            whisperContext = null
            modelKey = null

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
                        modelBytes = -1L,
                    )
                )
                return@withLock Result.failure(createdResult.exceptionOrNull()!!)
            }

            whisperContext = createdResult.getOrThrow()
            modelKey = key

            val totalMs = SystemClock.elapsedRealtime() - totalStart
            val lockHoldMs = SystemClock.elapsedRealtime() - lockAcquiredAt

            Log.i(
                LOG_TAG,
                "[$requestId][init:asset] Init OK. totalMs=$totalMs assetCheckMs=$assetCheckMs lockWaitMs=$lockWaitMs lockHoldMs=$lockHoldMs " +
                        "releasePrevMs=$releasePrevMs createMs=$createMs key=$key"
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
                    modelBytes = -1L,
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

        val activeKeyAtStart = modelKey
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
            Log.w(
                LOG_TAG,
                "[$requestId][asr] SilenceGate: returning empty transcript. totalMs=$totalMs decodeMs=$decodeMs " +
                        "rms=${formatFixed2(stats.rms)} peak=${formatFixed2(max(kotlin.math.abs(stats.min.toDouble()), kotlin.math.abs(stats.max.toDouble())))}"
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

            val ctx = whisperContext
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
                        "[$requestId][asr] FAILED (not initialized). totalMs=$totalMs decodeMs=$decodeMs lockWaitMs=$lockWaitMs lockHoldMs=$lockHoldMs modelKey=$modelKey"
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
                        "audio=${audioSecondsStr}s modelKey=$modelKey"
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
                        "[$requestId][asr] OK. totalMs=$totalMs decodeMs=$decodeMs lockWaitMs=$lockWaitMs lockHoldMs=$lockHoldMs selectedLang=$code modelKey=$modelKey"
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
                "[$requestId][asr] END. totalMs=$totalMs decodeMs=$decodeMs lockWaitMs=$lockWaitMs lockHoldMs=$lockHoldMs selectedLang=$selectedLang modelKey=$modelKey"
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
            Log.d(LOG_TAG, "Detach called. Keeping native context alive for key=$modelKey")
        }
    }

    suspend fun release() {
        engineMutex.withLock {
            val ctx = whisperContext
            if (ctx == null) {
                modelKey = null
                return
            }

            whisperContext = null
            val oldKey = modelKey
            modelKey = null

            runCatching {
                Log.i(LOG_TAG, "Releasing WhisperContext for $oldKey")
                ctx.release()
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
     *
     * @param pcm PCM samples in [-1, 1]
     * @param maxSamples Maximum samples to inspect (sampling stride computed automatically)
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
     *
     * Note:
     * - We use both RMS and peak to avoid misclassifying brief impulses.
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
        val max = rt.maxMemory()
        val native = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(-1L)

        Log.d(
            LOG_TAG,
            "$prefix mem: javaUsed=${formatBytes(used)} javaTotal=${formatBytes(total)} javaMax=${formatBytes(max)} nativeAllocated=${formatBytes(native)}"
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
