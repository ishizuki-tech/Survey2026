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
 *
 * This split prevents unnecessary re-initialization when Compose screens
 * are disposed and recreated during navigation or "restart" flows.
 */
object WhisperEngine {

    /**
     * Current active Whisper context.
     *
     * Access must be guarded by [engineMutex] for mutation.
     */
    @Volatile
    private var whisperContext: WhisperContext? = null

    /**
     * Identifier of the model used to create [whisperContext].
     *
     * - For file-based models: absolute file path.
     * - For asset-based models: synthetic key "asset:<path>".
     */
    @Volatile
    private var modelKey: String? = null

    /**
     * Single mutex to serialize init / transcribe / release.
     */
    private val engineMutex = Mutex()

    /** Monotonic id for correlating per-call timing logs. */
    private val transcribeSeq = AtomicLong(0L)

    /** Monotonic id for correlating init timing logs. */
    private val initSeq = AtomicLong(0L)

    /**
     * Returns the currently bound model key, if any.
     */
    fun currentModelKey(): String? = modelKey

    /**
     * Returns true if a Whisper context is currently alive.
     */
    fun isInitialized(): Boolean = whisperContext != null

    /**
     * Returns true if initialized with the given file path.
     */
    fun isInitializedForFile(modelFile: File): Boolean =
        whisperContext != null && modelKey == modelFile.absolutePath

    /**
     * Returns true if initialized with the given asset path.
     */
    fun isInitializedForAsset(assetPath: String): Boolean =
        whisperContext != null && modelKey == ASSET_KEY_PREFIX + assetPath

    /**
     * Estimate a PCM_16BIT MONO WAV file size in bytes.
     *
     * NOTE:
     * - This is an approximation that assumes a standard 44-byte WAV header.
     * - It does NOT account for extra chunks or non-PCM encodings.
     */
    fun estimatePcm16MonoWavBytes(seconds: Int, sampleRateHz: Int): Long {
        val s = seconds.coerceAtLeast(0).toLong()
        val sr = sampleRateHz.coerceAtLeast(1).toLong()
        return WAV_HEADER_BYTES_PCM16_MONO + (s * sr * PCM16_BYTES_PER_SAMPLE)
    }

    /**
     * Estimate audio duration (seconds) from a PCM_16BIT MONO WAV byte length.
     *
     * NOTE:
     * - This is an approximation that assumes a standard 44-byte WAV header.
     * - Returns 0.0 when bytes <= header size.
     */
    fun estimatePcm16MonoWavSeconds(bytes: Long, sampleRateHz: Int): Double {
        val sr = sampleRateHz.coerceAtLeast(1).toDouble()
        val payload = (bytes - WAV_HEADER_BYTES_PCM16_MONO).coerceAtLeast(0L).toDouble()
        return payload / (sr * PCM16_BYTES_PER_SAMPLE.toDouble())
    }

    /**
     * Ensure that a Whisper model is loaded from [modelFile].
     *
     * Behavior:
     * - If the engine is already initialized with the same file path,
     *   returns immediately with [Result.success].
     * - If a different model is active, the old context is released before
     *   creating a new one.
     *
     * Extra debug:
     * - Logs lock wait/hold time, release time, create time, and basic memory info.
     *
     * @param context Android [Context]. Currently unused but kept for API symmetry.
     * @param modelFile Local Whisper model file (GGML/GGUF). Must exist.
     * @param onTiming Optional callback receiving a timing breakdown for this init call.
     */
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

        /**
         * Fast path without locking.
         */
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

            /**
             * Double-check inside the lock.
             */
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

            /**
             * Release any previous context before switching models.
             */
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

            /**
             * Create new context.
             */
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

    /**
     * Ensure that a Whisper model is loaded from app assets at [assetPath].
     *
     * Example:
     * - assetPath = "models/ggml-small-q5_1.bin"
     *
     * Behavior mirrors [ensureInitializedFromFile] but uses
     * [WhisperContext.createContextFromAsset].
     *
     * Extra debug:
     * - Logs asset existence check time, lock wait/hold time, release time, create time, and memory info.
     *
     * @param context Android [Context] used for asset access.
     * @param assetPath Relative asset path under the APK assets directory.
     * @param onTiming Optional callback receiving a timing breakdown for this init call.
     */
    suspend fun ensureInitializedFromAsset(
        context: Context,
        assetPath: String,
        onTiming: (InitTiming) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.Default) {

        val requestId = initSeq.incrementAndGet()
        val totalStart = SystemClock.elapsedRealtime()

        /**
         * Lightweight asset existence check (timed).
         */
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

        /**
         * Fast path without locking.
         */
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

            /**
             * Double-check inside the lock.
             */
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

            /**
             * Release any previous context before switching models.
             */
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

            /**
             * Create new context.
             */
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
        onTiming: (TranscribeTiming) -> Unit = {},
    ): Result<String> = withContext(Dispatchers.Default) {

        val requestId = transcribeSeq.incrementAndGet()
        val totalStart = SystemClock.elapsedRealtime()

        if (!file.exists() || !file.isFile) {
            return@withContext Result.failure(
                IllegalArgumentException("Input WAV file does not exist: ${file.path}")
            )
        }

        val fileBytes = runCatching { file.length() }.getOrDefault(-1L)
        val approxSecFromBytes = if (fileBytes >= 0L) estimatePcm16MonoWavSeconds(fileBytes, targetSampleRate) else -1.0

        val activeKeyAtStart = modelKey
        Log.d(
            LOG_TAG,
            "[$requestId][asr] Start. file=${file.name} bytes=${formatBytes(fileBytes)} " +
                    "approxSec(pcm16mono@${targetSampleRate}Hz)=${formatFixed2(approxSecFromBytes)} " +
                    "lang=$lang translate=$translate printTimestamp=$printTimestamp sr=$targetSampleRate modelKey=$activeKeyAtStart thread=${Thread.currentThread().name}"
        )
        logMemory("[$requestId][asr]")

        val decodeStart = SystemClock.elapsedRealtime()
        val pcmResult = runCatching {
            decodeWaveFile(
                file = file,
                targetSampleRate = targetSampleRate
            )
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

        val audioSeconds = pcm.size.toDouble() / targetSampleRate.toDouble()
        val audioSecondsStr = formatFixed2(audioSeconds)

        val stats = computePcmStats(pcm)
        Log.d(
            LOG_TAG,
            "[$requestId][asr] PCM stats: samples=${pcm.size} seconds=${audioSecondsStr} " +
                    "min=${stats.min} max=${stats.max} rms=${stats.rms} decodeMs=$decodeMs"
        )

        val languageAttempts: List<String> =
            if (lang.lowercase() == "auto") listOf("auto", "en", "ja", "sw") else listOf(lang)

        fun safeEmitTiming(timing: TranscribeTiming) {
            runCatching { onTiming(timing) }
                .onFailure { t -> Log.w(LOG_TAG, "[$requestId][asr] onTiming callback failed: ${t.message}", t) }
        }

        val lockRequestAt = SystemClock.elapsedRealtime()

        engineMutex.withLock {
            val lockAcquiredAt = SystemClock.elapsedRealtime()
            val lockWaitMs = lockAcquiredAt - lockRequestAt

            val ctx = whisperContext
                ?: run {
                    val totalMs = SystemClock.elapsedRealtime() - totalStart
                    val lockHoldMs = SystemClock.elapsedRealtime() - lockAcquiredAt
                    safeEmitTiming(
                        TranscribeTiming(
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

            var lastEmptySuccess: String? = null
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

                if (result.isFailure) {
                    lastFailure = result.exceptionOrNull()
                    attemptTimings += TranscribeTiming.AttemptTiming(
                        lang = code,
                        transcribeMs = transcribeMs,
                        textLen = 0,
                        success = false,
                        rtf = if (audioSeconds > 0.0) (transcribeMs / 1000.0) / audioSeconds else 0.0,
                    )
                    continue
                }

                val trimmed = result.getOrThrow().trim()
                val rtf = if (audioSeconds > 0.0) (transcribeMs / 1000.0) / audioSeconds else 0.0
                val rtfStr = formatFixed2(rtf)

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
                        TranscribeTiming(
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

                lastEmptySuccess = trimmed
                Log.w(
                    LOG_TAG,
                    "[$requestId][asr] Empty transcript: lang=$code transcribeMs=$transcribeMs (continuing)"
                )
            }

            val totalMs = SystemClock.elapsedRealtime() - totalStart
            val lockHoldMs = SystemClock.elapsedRealtime() - lockAcquiredAt

            safeEmitTiming(
                TranscribeTiming(
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

            when {
                lastEmptySuccess != null -> Result.success(lastEmptySuccess)
                lastFailure != null -> Result.failure(lastFailure)
                else -> Result.failure(IllegalStateException("Transcription produced no usable result."))
            }
        }
    }

    /**
     * Softly detach the engine from UI lifecycle without releasing native resources.
     */
    suspend fun detach() {
        engineMutex.withLock {
            Log.d(LOG_TAG, "Detach called. Keeping native context alive for key=$modelKey")
        }
    }

    /**
     * Release the active Whisper context, if any, and reset the engine.
     */
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

    /**
     * Alias of [release] for clarity when a hard cleanup is explicitly desired.
     */
    suspend fun cleanUp() {
        release()
    }

    /**
     * Simple statistics container for PCM buffers.
     */
    private data class PcmStats(
        val min: Float,
        val max: Float,
        val rms: Double
    )

    /**
     * Compute [PcmStats] for the given [pcm] buffer.
     */
    private fun computePcmStats(pcm: FloatArray): PcmStats {
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        var sumSq = 0.0

        for (v in pcm) {
            if (v < min) min = v
            if (v > max) max = v
            sumSq += v.toDouble() * v.toDouble()
        }

        val rms = if (pcm.isNotEmpty()) sqrt(sumSq / pcm.size) else 0.0

        return PcmStats(
            min = if (min.isFinite()) min else 0f,
            max = if (max.isFinite()) max else 0f,
            rms = rms
        )
    }

    /**
     * Emit init timing safely (never throw).
     */
    private fun safeEmitInitTiming(onTiming: (InitTiming) -> Unit, timing: InitTiming) {
        runCatching { onTiming(timing) }
            .onFailure { t -> Log.w(LOG_TAG, "[init] onTiming callback failed: ${t.message}", t) }
    }

    /**
     * Log Java heap and native heap in a compact format.
     */
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

    /**
     * Format bytes for logs.
     */
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

    /**
     * Format a double with 2 decimal places using a fixed locale.
     *
     * IMPORTANT: Only use this for numeric formatting; do not mix dynamic text into Formatter.
     */
    private fun formatFixed2(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }

    /**
     * Escape control chars to keep logs single-line and readable.
     */
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
