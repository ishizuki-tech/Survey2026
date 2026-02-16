/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: WhisperSpeechController.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  ViewModel-based SpeechController implementation backed by Whisper.cpp.
 *
 *  Diagnostics upgrades:
 *   • Parse WAV header to log sampleRate/channels/bits/dataBytes/duration.
 *   • Compute lightweight PCM16 amplitude stats (RMS/Peak/non-silence ratio).
 *   • Wait briefly for the WAV file size to stabilize after stopRecording.
 *   • Set a user-visible error when transcription returns empty text.
 *
 *  Robustness upgrades:
 *   • Guard recorder.stopRecording() with a timeout.
 *   • On stop timeout, attempt to close + recreate Recorder instance.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.vm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.negi.survey.screens.SpeechController
import com.negi.survey.utils.ExportUtils
import com.negi.survey.whisper.Recorder
import com.negi.survey.whisper.WhisperEngine
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ViewModel-based [SpeechController] implementation backed by Whisper.cpp.
 */
class WhisperSpeechController(
    private val appContext: Context,
    private val assetModelPath: String = DEFAULT_ASSET_MODEL,
    languageCode: String = DEFAULT_LANGUAGE,
    private val onVoiceExported: ((ExportedVoice) -> Unit)? = null
) : ViewModel(), SpeechController {

    companion object {
        private const val TAG = "WhisperSpeechController"

        private const val DEFAULT_LANGUAGE = "auto"
        private const val DEFAULT_ASSET_MODEL = "models/ggml-model-q4_0.bin"

        private val RECORDER_RATE_CANDIDATES = intArrayOf(16_000, 48_000, 44_100)

        private const val MIN_WAV_BYTES = 44L
        private const val MIN_DURATION_SEC_HEURISTIC = 0.25

        private const val WAV_STABILIZE_MAX_MS = 900L
        private const val WAV_STABILIZE_STEP_MS = 60L

        private const val PCM16_SILENCE_ABS_THRESHOLD = 400

        /** Timeout for recorder.stopRecording() (must be > Recorder STOP_JOIN_TIMEOUT_MS). */
        private const val RECORDER_STOP_TIMEOUT_MS = 6_000L

        /** Best-effort timeout for recorder.close() during recovery. */
        private const val RECORDER_CLOSE_TIMEOUT_MS = 2_500L

        fun provideFactory(
            appContext: Context,
            assetModelPath: String = DEFAULT_ASSET_MODEL,
            languageCode: String = DEFAULT_LANGUAGE,
            onVoiceExported: ((ExportedVoice) -> Unit)? = null
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(WhisperSpeechController::class.java)) {
                        "Unknown ViewModel class $modelClass"
                    }
                    return WhisperSpeechController(
                        appContext = appContext.applicationContext,
                        assetModelPath = assetModelPath,
                        languageCode = languageCode,
                        onVoiceExported = onVoiceExported
                    ) as T
                }
            }
    }

    /**
     * Minimal export event payload.
     */
    data class ExportedVoice(
        val surveyId: String?,
        val questionId: String?,
        val fileName: String,
        val byteSize: Long,
        val checksum: String? = null
    )

    // ---------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------

    /**
     * Recorder instance (re-creatable on failure/hang).
     */
    private var recorder: Recorder = newRecorder()

    /**
     * Last output WAV file produced by [recorder].
     */
    private var outputFile: File? = null

    /**
     * Background job used for model init / recording / transcription.
     */
    private var workerJob: Job? = null

    private var currentSurveyId: String? = null
    private var currentQuestionId: String? = null

    private val recordingMutex = Mutex()
    private val modelInitMutex = Mutex()

    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    private val _isRecording = MutableStateFlow(false)
    private val _isTranscribing = MutableStateFlow(false)
    private val _partialText = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)

    override val isRecording: StateFlow<Boolean> = _isRecording
    override val isTranscribing: StateFlow<Boolean> = _isTranscribing
    override val partialText: StateFlow<String> = _partialText
    override val errorMessage: StateFlow<String?> = _error

    private val languageCodeRaw: String = languageCode

    private val normalizedLanguage: String =
        languageCodeRaw.trim()
            .lowercase(Locale.ROOT)
            .ifBlank { DEFAULT_LANGUAGE }

    override fun updateContext(surveyId: String?, questionId: String?) {
        currentSurveyId = surveyId
        currentQuestionId = questionId
        Log.d(TAG, "updateContext: surveyId=$surveyId, questionId=$questionId")
    }

    // ---------------------------------------------------------------------
    // Recording control
    // ---------------------------------------------------------------------

    override fun startRecording() {
        if (_isRecording.value || _isTranscribing.value) {
            Log.d(TAG, "startRecording: busy, ignoring")
            return
        }

        Log.d(TAG, "startRecording: requested")
        _error.value = null
        _partialText.value = ""
        _isRecording.value = true

        workerJob?.cancel()
        workerJob = null

        workerJob = viewModelScope.launch(Dispatchers.IO) {
            recordingMutex.withLock {
                try {
                    ensureActive()

                    val old = outputFile
                    outputFile = null
                    deleteTempFileQuietly(old, reason = "start_cleanup")

                    ensureModelInitializedFromAssetsOnce()
                    ensureActive()

                    val dir = File(appContext.cacheDir, "whisper_rec")
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw IllegalStateException("Failed to create cache dir: ${dir.path}")
                    }

                    val wav = File.createTempFile("survey_input_", ".wav", dir)
                    outputFile = wav

                    Log.d(TAG, "startRecording: recorder.startRecording -> ${wav.path}")
                    recorder.startRecording(output = wav, rates = RECORDER_RATE_CANDIDATES)
                    Log.d(TAG, "startRecording: started")
                } catch (ce: CancellationException) {
                    Log.d(TAG, "startRecording: cancelled")
                    _isRecording.value = false
                    val tmp = outputFile
                    outputFile = null
                    deleteTempFileQuietly(tmp, reason = "start_cancelled")
                } catch (t: Throwable) {
                    Log.e(TAG, "startRecording: failed", t)
                    _error.value = t.message ?: "Speech recognition start failed"
                    _isRecording.value = false

                    val tmp = outputFile
                    outputFile = null
                    deleteTempFileQuietly(tmp, reason = "start_failed")
                }
            }
        }
    }

    override fun stopRecording() {
        if (!_isRecording.value) {
            Log.d(TAG, "stopRecording: not recording, ignoring")
            return
        }

        Log.d(TAG, "stopRecording: requested")
        _isRecording.value = false

        workerJob?.cancel()
        workerJob = null

        workerJob = viewModelScope.launch(Dispatchers.IO) {
            recordingMutex.withLock {
                var localWav: File? = null
                try {
                    val t0 = SystemClock.elapsedRealtime()
                    Log.d(TAG, "stopRecording: awaiting recorder.stopRecording()")

                    val ok = withTimeoutOrNull(RECORDER_STOP_TIMEOUT_MS) {
                        recorder.stopRecording()
                        true
                    } ?: false

                    val dt = SystemClock.elapsedRealtime() - t0
                    if (!ok) {
                        Log.e(TAG, "recorder.stopRecording TIMEOUT after ${dt}ms (qid=$currentQuestionId)")
                        _error.value = "Recorder stop timeout (AudioRecord thread stuck)"
                        recoverRecorderAfterHang(reason = "stop_timeout")

                        val wav = outputFile
                        outputFile = null
                        localWav = wav
                        return@withLock
                    } else {
                        Log.d(TAG, "recorder.stopRecording OK in ${dt}ms")
                    }

                    val wav = outputFile
                    outputFile = null
                    localWav = wav

                    if (wav == null) {
                        Log.d(TAG, "stopRecording: no WAV yet (likely quick cancel)")
                        return@withLock
                    }
                    if (!wav.exists()) {
                        Log.d(TAG, "stopRecording: WAV missing (likely quick cancel) -> ${wav.path}")
                        return@withLock
                    }

                    val stableBytes = awaitFileSizeStabilized(wav)
                    Log.d(TAG, "stopRecording: wav.size(stable)=$stableBytes path=${wav.path}")

                    if (wav.length() <= MIN_WAV_BYTES) {
                        Log.d(TAG, "stopRecording: WAV too short (likely no speech) (${wav.length()} bytes)")
                        _error.value = "Recording too short or empty"
                        return@withLock
                    }

                    val info = readWavInfo(wav)
                    if (info == null) {
                        Log.w(TAG, "stopRecording: WAV header parse failed; proceeding anyway")
                    } else {
                        Log.d(
                            TAG,
                            "stopRecording: wav.info fmt=${info.audioFormat} ch=${info.channels} " +
                                    "sr=${info.sampleRate} bits=${info.bitsPerSample} " +
                                    "dataBytes=${info.dataBytes} durationSec=${"%.3f".format(info.durationSec())}"
                        )

                        if (info.durationSec() in 0.0..MIN_DURATION_SEC_HEURISTIC) {
                            Log.w(TAG, "stopRecording: WAV duration looks too short (${info.durationSec()} sec)")
                        }

                        val stats = computePcm16Stats(
                            file = wav,
                            info = info,
                            silenceAbsThreshold = PCM16_SILENCE_ABS_THRESHOLD
                        )
                        if (stats != null) {
                            Log.d(
                                TAG,
                                "stopRecording: wav.stats rms=${"%.5f".format(stats.rms)} " +
                                        "peak=${"%.5f".format(stats.peak)} " +
                                        "nonSilent=${"%.3f".format(stats.nonSilentRatio)} " +
                                        "samples=${stats.samples}"
                            )
                        }
                    }

                    val exported = exportRecordedVoiceSafely(wav)
                    if (exported != null) {
                        val checksum = runCatching { computeSha256(exported) }
                            .onFailure { e -> Log.w(TAG, "computeSha256 failed", e) }
                            .getOrNull()

                        onVoiceExported?.invoke(
                            ExportedVoice(
                                surveyId = currentSurveyId,
                                questionId = currentQuestionId,
                                fileName = exported.name,
                                byteSize = exported.length(),
                                checksum = checksum
                            )
                        )

                        Log.d(
                            TAG,
                            "onVoiceExported -> file=${exported.name}, " +
                                    "bytes=${exported.length()}, " +
                                    "qid=$currentQuestionId, sid=$currentSurveyId, " +
                                    "checksum=${checksum?.take(12)}..."
                        )
                    } else {
                        Log.w(TAG, "stopRecording: export skipped or failed")
                    }

                    _isTranscribing.value = true
                    Log.d(TAG, "stopRecording: transcribing -> ${wav.path}")

                    val result = WhisperEngine.transcribeWaveFile(
                        file = wav,
                        lang = normalizedLanguage,
                        translate = false,
                        printTimestamp = false,
                        targetSampleRate = 16_000
                    )

                    result
                        .onSuccess { text ->
                            val trimmed = text.trim()
                            if (trimmed.isEmpty()) {
                                Log.w(TAG, "Transcription produced empty text (qid=$currentQuestionId)")
                                _error.value = buildEmptyTranscriptionReason(wav)
                            } else {
                                Log.d(TAG, "Transcription success: ${trimmed.take(80)}")
                            }
                            updatePartialText(trimmed)
                        }
                        .onFailure { e ->
                            Log.e(TAG, "Transcription failed", e)
                            _error.value = e.message ?: "Transcription failed"
                        }
                } catch (ce: CancellationException) {
                    Log.d(TAG, "stopRecording: cancelled")
                } catch (t: Throwable) {
                    Log.e(TAG, "stopRecording: failed", t)
                    _error.value = t.message ?: "Speech recognition failed"
                } finally {
                    _isTranscribing.value = false
                    deleteTempFileQuietly(localWav, reason = "stop_finally")
                }
            }
        }
    }

    override fun toggleRecording() {
        if (_isRecording.value) stopRecording() else startRecording()
    }

    // ---------------------------------------------------------------------
    // Public helpers
    // ---------------------------------------------------------------------

    fun updatePartialText(text: String) {
        _partialText.value = text
        Log.d(TAG, "updatePartialText: len=${text.length}")
    }

    fun clearError() {
        _error.value = null
    }

    // ---------------------------------------------------------------------
    // Model init
    // ---------------------------------------------------------------------

    private suspend fun ensureModelInitializedFromAssetsOnce() {
        if (WhisperEngine.isInitializedForAsset(assetModelPath)) return

        modelInitMutex.withLock {
            if (WhisperEngine.isInitializedForAsset(assetModelPath)) {
                Log.d(TAG, "WhisperEngine already initialized for assets/$assetModelPath (locked)")
                return
            }

            val result = WhisperEngine.ensureInitializedFromAsset(
                context = appContext,
                assetPath = assetModelPath
            )

            result.onFailure { e ->
                Log.e(TAG, "ensureModelInitializedFromAssetsOnce failed: assets/$assetModelPath", e)
                throw IllegalStateException(
                    "Failed to initialize Whisper model from assets/$assetModelPath",
                    e
                )
            }

            Log.d(TAG, "WhisperEngine initialized: assets/$assetModelPath")
        }
    }

    // ---------------------------------------------------------------------
    // Export / checksum
    // ---------------------------------------------------------------------

    private suspend fun exportRecordedVoiceSafely(wav: File): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                ExportUtils.exportRecordedVoice(
                    context = appContext,
                    source = wav,
                    surveyId = currentSurveyId,
                    questionId = currentQuestionId
                )
            }.onFailure { e ->
                Log.w(TAG, "exportRecordedVoice failed", e)
            }.getOrNull()
        }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(1024 * 32)
            while (true) {
                val read = fis.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { b ->
            "%02x".format(b.toInt() and 0xff)
        }
    }

    private fun deleteTempFileQuietly(file: File?, reason: String) {
        if (file == null) return
        runCatching {
            if (file.exists()) {
                val ok = file.delete()
                Log.d(TAG, "temp delete=$ok reason=$reason -> ${file.path}")
            }
        }.onFailure { e ->
            Log.w(TAG, "temp delete failed reason=$reason -> ${file.path}", e)
        }
    }

    // ---------------------------------------------------------------------
    // Recorder lifecycle / recovery
    // ---------------------------------------------------------------------

    /**
     * Create a new Recorder instance with the standard error callback.
     */
    private fun newRecorder(): Recorder {
        return Recorder(appContext) { e ->
            Log.e(TAG, "Recorder error", e)

            val tmp = outputFile
            outputFile = null
            cleanupScope.launch {
                withContext(NonCancellable) {
                    deleteTempFileQuietly(tmp, reason = "recorder_error")
                }
            }

            _error.value = e.message ?: "Recording error"
            _isRecording.value = false
            _isTranscribing.value = false

            cleanupScope.launch {
                withContext(NonCancellable) {
                    recoverRecorderAfterHang(reason = "recorder_error")
                }
            }
        }
    }

    /**
     * Best-effort recovery path when stopRecording times out or recorder errors out.
     */
    private fun recoverRecorderAfterHang(reason: String) {
        Log.w(TAG, "Recovering Recorder (reason=$reason)")

        val old = recorder
        val closeRes = runBlockingWithThreadTimeout(
            name = "WSC-recorderClose",
            timeoutMs = RECORDER_CLOSE_TIMEOUT_MS
        ) {
            old.close()
        }

        when {
            closeRes == null -> Log.e(TAG, "recorder.close TIMEOUT (reason=$reason)")
            closeRes.isFailure -> Log.w(TAG, "recorder.close failed (reason=$reason)", closeRes.exceptionOrNull())
            else -> Log.d(TAG, "recorder.close OK (reason=$reason)")
        }

        recorder = newRecorder()
        Log.w(TAG, "Recorder recreated (reason=$reason)")
    }

    /**
     * Run a potentially blocking operation on a dedicated Thread and wait up to timeoutMs.
     *
     * Returns:
     * - null if timeout
     * - Result.success(Unit) if completed without exception
     * - Result.failure(e) if exception was thrown
     */
    private fun runBlockingWithThreadTimeout(
        name: String,
        timeoutMs: Long,
        block: () -> Unit
    ): Result<Unit>? {
        val err = AtomicReference<Throwable?>(null)
        val t = Thread(
            {
                try {
                    block()
                } catch (e: Throwable) {
                    err.set(e)
                }
            },
            name
        )
        t.start()
        runCatching { t.join(timeoutMs) }
        return if (t.isAlive) {
            null
        } else {
            val e = err.get()
            if (e != null) Result.failure(e) else Result.success(Unit)
        }
    }

    // ---------------------------------------------------------------------
    // WAV diagnostics
    // ---------------------------------------------------------------------

    private data class WavInfo(
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataBytes: Long,
        val headerBytes: Long
    ) {
        fun durationSec(): Double {
            if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return 0.0
            val bytesPerSample = bitsPerSample / 8.0
            if (bytesPerSample <= 0.0) return 0.0
            val bytesPerFrame = channels * bytesPerSample
            if (bytesPerFrame <= 0.0) return 0.0
            return dataBytes / (sampleRate * bytesPerFrame)
        }
    }

    private data class Pcm16Stats(
        val rms: Double,
        val peak: Double,
        val nonSilentRatio: Double,
        val samples: Long
    )

    private suspend fun awaitFileSizeStabilized(file: File): Long {
        var last = file.length()
        var waited = 0L
        while (waited < WAV_STABILIZE_MAX_MS) {
            delay(WAV_STABILIZE_STEP_MS)
            val cur = file.length()
            if (cur == last) return cur
            last = cur
            waited += WAV_STABILIZE_STEP_MS
        }
        return last
    }

    private fun readWavInfo(file: File): WavInfo? {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                fun readU8(): Int = raf.readUnsignedByte()
                fun readLE16(): Int {
                    val lo = readU8()
                    val hi = readU8()
                    return (hi shl 8) or lo
                }
                fun readLE32(): Long {
                    val b0 = readU8().toLong()
                    val b1 = readU8().toLong()
                    val b2 = readU8().toLong()
                    val b3 = readU8().toLong()
                    return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                }
                fun readFourCC(): String {
                    val buf = ByteArray(4)
                    raf.readFully(buf)
                    return String(buf, Charsets.US_ASCII)
                }

                val riff = readFourCC()
                if (riff != "RIFF") return@use null
                readLE32()
                val wave = readFourCC()
                if (wave != "WAVE") return@use null

                var audioFormat = -1
                var channels = -1
                var sampleRate = -1
                var bitsPerSample = -1
                var dataOffset = -1L
                var dataBytes = -1L

                while (raf.filePointer + 8 <= raf.length()) {
                    val chunkId = readFourCC()
                    val chunkSize = readLE32()
                    val chunkDataStart = raf.filePointer

                    when (chunkId) {
                        "fmt " -> {
                            if (chunkSize >= 16) {
                                audioFormat = readLE16()
                                channels = readLE16()
                                sampleRate = readLE32().toInt()
                                readLE32()
                                readLE16()
                                bitsPerSample = readLE16()
                            }
                        }
                        "data" -> {
                            dataOffset = raf.filePointer
                            dataBytes = chunkSize
                        }
                    }

                    raf.seek(chunkDataStart + chunkSize)
                    if (chunkSize % 2L == 1L) {
                        if (raf.filePointer < raf.length()) raf.seek(raf.filePointer + 1)
                    }

                    if (audioFormat != -1 && dataOffset >= 0 && dataBytes >= 0) break
                }

                if (audioFormat == -1 || dataOffset < 0 || dataBytes < 0) return@use null

                val headerBytes = dataOffset.coerceAtLeast(0L)
                WavInfo(
                    audioFormat = audioFormat,
                    channels = channels.coerceAtLeast(1),
                    sampleRate = sampleRate.coerceAtLeast(1),
                    bitsPerSample = bitsPerSample.coerceAtLeast(1),
                    dataOffset = dataOffset,
                    dataBytes = dataBytes,
                    headerBytes = headerBytes
                )
            }
        }.getOrNull()
    }

    private fun computePcm16Stats(
        file: File,
        info: WavInfo,
        silenceAbsThreshold: Int
    ): Pcm16Stats? {
        if (info.audioFormat != 1) return null
        if (info.bitsPerSample != 16) return null
        if (info.dataOffset < 0 || info.dataBytes <= 0) return null

        return runCatching {
            FileInputStream(file).use { fis ->
                val skipped = fis.skip(info.dataOffset)
                if (skipped < info.dataOffset) return@use null

                val buf = ByteArray(1024 * 16)
                var remaining = info.dataBytes
                var samples = 0L
                var nonSilent = 0L
                var peakAbs = 0
                var sumSq = 0.0

                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val read = fis.read(buf, 0, toRead)
                    if (read <= 0) break
                    remaining -= read.toLong()

                    var i = 0
                    while (i + 1 < read) {
                        val lo = buf[i].toInt() and 0xff
                        val hi = buf[i + 1].toInt()
                        val s = (hi shl 8) or lo
                        val v = s.toShort().toInt()
                        val a = abs(v)

                        if (a > peakAbs) peakAbs = a
                        if (a >= silenceAbsThreshold) nonSilent++

                        sumSq += (v.toLong() * v.toLong()).toDouble()
                        samples++

                        i += 2
                    }
                }

                if (samples <= 0) return@use null
                val rms = sqrt(sumSq / samples) / 32768.0
                val peak = peakAbs / 32768.0
                val nonSilentRatio = nonSilent.toDouble() / samples.toDouble()

                Pcm16Stats(
                    rms = rms,
                    peak = peak,
                    nonSilentRatio = nonSilentRatio,
                    samples = samples
                )
            }
        }.getOrNull()
    }

    private fun buildEmptyTranscriptionReason(wav: File): String {
        val info = readWavInfo(wav)
        val dur = info?.durationSec() ?: -1.0
        val stats = if (info != null) {
            computePcm16Stats(wav, info, silenceAbsThreshold = PCM16_SILENCE_ABS_THRESHOLD)
        } else null

        val silentHeuristic = stats?.let { it.nonSilentRatio < 0.005 && it.peak < 0.02 } ?: false

        return when {
            info == null ->
                "Transcription empty (WAV header parse failed)"
            dur in 0.0..MIN_DURATION_SEC_HEURISTIC ->
                "Transcription empty (audio too short: ${"%.2f".format(dur)}s)"
            silentHeuristic ->
                "Transcription empty (audio seems silent/very low volume)"
            else ->
                "Transcription empty (check Recorder format/sample rate)"
        }
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    override fun onCleared() {
        workerJob?.cancel()
        workerJob = null

        val job = cleanupScope.launch {
            withContext(NonCancellable) {
                runCatching { WhisperEngine.detach() }
                    .onFailure { e -> Log.w(TAG, "WhisperEngine.detach failed", e) }

                runCatching { recorder.close() }
                    .onFailure { e -> Log.w(TAG, "Recorder.close failed", e) }
            }
        }

        job.invokeOnCompletion { cleanupScope.cancel() }
        super.onCleared()
    }
}
