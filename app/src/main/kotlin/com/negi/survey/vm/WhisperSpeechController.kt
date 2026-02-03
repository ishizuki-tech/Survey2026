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
 *  Responsibilities:
 *   • Manage recording lifecycle using Recorder.
 *   • Ensure Whisper model initialization via WhisperEngine.
 *   • Run transcription and publish the final text to partialText.
 *   • Export recorded WAV files to a persistent "exports" folder.
 *   • Emit an optional export callback so callers can register
 *     a logical audio manifest in SurveyViewModel.
 *
 *  Notes:
 *   • Uses a Mutex to serialize start/stop operations.
 *   • Uses a second Mutex to avoid repeated model initialization.
 *   • Computes optional SHA-256 checksum for exported WAV.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.vm

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.negi.survey.screens.SpeechController
import com.negi.survey.utils.ExportUtils
import com.negi.survey.whisper.WhisperEngine
import com.negi.survey.whisper.Recorder
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ViewModel-based [SpeechController] implementation backed by Whisper.cpp.
 *
 * UI contract:
 * - [isRecording] is true while audio capture is active.
 * - [isTranscribing] is true while transcription is running.
 * - When recording stops and transcription succeeds, [partialText] is updated
 *   with the final recognized text.
 *
 * Lifecycle policy:
 * - This controller does NOT hard-release Whisper native resources on
 *   ViewModel clearance to avoid expensive re-initialization during
 *   navigation/restart flows.
 * - Instead it calls [WhisperEngine.detach].
 */
class WhisperSpeechController(
    private val appContext: Context,
    private val assetModelPath: String = DEFAULT_ASSET_MODEL,
    languageCode: String = DEFAULT_LANGUAGE,
    private val onVoiceExported: ((ExportedVoice) -> Unit)? = null
) : ViewModel(), SpeechController {

    companion object {
        private const val TAG = "WhisperSpeechController"

        /**
         * Default language for Whisper.
         *
         * Valid values:
         * - "auto" : auto-detect language
         * - "en"   : English
         * - "ja"   : Japanese
         * - "sw"   : Swahili
         */
        private const val DEFAULT_LANGUAGE = "auto"

        /**
         * Default model path inside assets.
         *
         * Place your model at:
         *   app/src/main/assets/models/ggml-model-q4_0.bin
         * and keep this as "models/ggml-model-q4_0.bin".
         */
        private const val DEFAULT_ASSET_MODEL = "models/ggml-model-q4_0.bin"

        /**
         * Preferred sample rates to attempt for the recorder backend.
         */
        private val RECORDER_RATE_CANDIDATES = intArrayOf(16_000, 48_000, 44_100)

        /**
         * Minimum WAV byte length heuristic.
         *
         * 44 bytes is the typical PCM header size.
         * Anything at or below this is effectively empty audio.
         */
        private const val MIN_WAV_BYTES = 44L

        /**
         * Factory for use with Compose `viewModel(factory = ...)`.
         */
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

    // ---------------------------------------------------------------------
    // Models
    // ---------------------------------------------------------------------

    /**
     * Minimal export event payload.
     *
     * This is intentionally file-system neutral so callers can decide
     * how to record the logical manifest.
     *
     * @property surveyId Optional survey UUID used in exported naming.
     * @property questionId Optional node ID used in exported naming.
     * @property fileName Exported WAV file name (no path).
     * @property byteSize Byte size at export time.
     * @property checksum Optional SHA-256 checksum for idempotent ingestion.
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
     * Dedicated WAV recorder implementation.
     *
     * Errors are forwarded into [errorMessage].
     */
    private val recorder: Recorder = Recorder(appContext) { e ->
        Log.e(TAG, "Recorder error", e)
        _error.value = e.message ?: "Recording error"
        _isRecording.value = false
        _isTranscribing.value = false
        outputFile = null
    }

    /**
     * Last output WAV file produced by [recorder].
     *
     * This file lives under cache/whisper_rec and is treated as temporary.
     */
    private var outputFile: File? = null

    /**
     * Background job used for model init / recording / transcription.
     */
    private var workerJob: Job? = null

    /**
     * Optional context for naming exported voice files.
     */
    private var currentSurveyId: String? = null
    private var currentQuestionId: String? = null

    /**
     * Serialize start/stop to avoid state races on rapid taps.
     */
    private val recordingMutex = Mutex()

    /**
     * Prevent repeated model initialization across multiple recordings.
     *
     * Note:
     * This mutex gates init attempts, while the actual truth of initialization
     * is derived from [WhisperEngine.isInitializedForAsset].
     */
    private val modelInitMutex = Mutex()

    /**
     * Cleanup scope not tied to [viewModelScope].
     *
     * This avoids the common pitfall where [viewModelScope] is already cancelled
     * when [onCleared] executes.
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------------------------------------------------------------------
    // State exposed to the UI
    // ---------------------------------------------------------------------

    private val _isRecording = MutableStateFlow(false)
    private val _isTranscribing = MutableStateFlow(false)
    private val _partialText = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)

    override val isRecording: StateFlow<Boolean> = _isRecording
    override val isTranscribing: StateFlow<Boolean> = _isTranscribing
    override val partialText: StateFlow<String> = _partialText
    override val errorMessage: StateFlow<String?> = _error

    // ---------------------------------------------------------------------
    // Config
    // ---------------------------------------------------------------------

    /**
     * Raw language code passed from callers.
     */
    private val languageCodeRaw: String = languageCode

    /**
     * Normalized language code for Whisper calls.
     */
    private val normalizedLanguage: String =
        languageCodeRaw.trim()
            .lowercase(Locale.ROOT)
            .ifBlank { DEFAULT_LANGUAGE }

    // ---------------------------------------------------------------------
    // Optional context setter
    // ---------------------------------------------------------------------

    /**
     * Update the survey/question context used when exporting voice.
     *
     * This is used only for file naming; can be null.
     */
    override fun updateContext(
        surveyId: String?,
        questionId: String?
    ) {
        currentSurveyId = surveyId
        currentQuestionId = questionId
        Log.d(TAG, "updateContext: surveyId=$surveyId, questionId=$questionId")
    }

    // ---------------------------------------------------------------------
    // Recording control
    // ---------------------------------------------------------------------

    /**
     * Start capturing audio and begin recognition.
     */
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

                    ensureModelInitializedFromAssetsOnce()
                    ensureActive()

                    val dir = File(appContext.cacheDir, "whisper_rec")
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw IllegalStateException("Failed to create cache dir: ${dir.path}")
                    }

                    val wav = File.createTempFile("survey_input_", ".wav", dir)
                    outputFile = wav

                    Log.d(TAG, "startRecording: recorder.startRecording -> ${wav.path}")
                    recorder.startRecording(
                        output = wav,
                        rates = RECORDER_RATE_CANDIDATES
                    )

                    Log.d(TAG, "startRecording: started")
                } catch (ce: CancellationException) {
                    Log.d(TAG, "startRecording: cancelled")
                    _isRecording.value = false
                    outputFile = null
                } catch (t: Throwable) {
                    Log.e(TAG, "startRecording: failed", t)
                    _error.value = t.message ?: "Speech recognition start failed"
                    _isRecording.value = false
                    outputFile = null
                }
            }
        }
    }

    /**
     * Stop capturing audio and finalize the current utterance.
     */
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
                    Log.d(TAG, "stopRecording: awaiting recorder.stopRecording()")
                    runCatching { recorder.stopRecording() }
                        .onFailure { e ->
                            Log.w(TAG, "recorder.stopRecording failed", e)
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
                    if (wav.length() <= MIN_WAV_BYTES) {
                        Log.d(TAG, "stopRecording: WAV too short (likely no speech) (${wav.length()} bytes)")
                        _error.value = "Recording too short or empty"
                        return@withLock
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
                                Log.w(TAG, "Transcription produced empty text")
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

                    runCatching {
                        localWav?.let { tmp ->
                            if (tmp.exists()) {
                                val ok = tmp.delete()
                                Log.d(TAG, "stopRecording: temp delete=$ok -> ${tmp.path}")
                            }
                        }
                    }.onFailure { e ->
                        Log.w(TAG, "stopRecording: temp WAV delete failed", e)
                    }
                }
            }
        }
    }

    /**
     * Convenience toggle used by the UI microphone button.
     */
    override fun toggleRecording() {
        if (_isRecording.value) stopRecording() else startRecording()
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Update the current partial or final transcript text.
     */
    fun updatePartialText(text: String) {
        _partialText.value = text
        Log.d(TAG, "updatePartialText: len=${text.length}")
    }

    /**
     * Clear any previously reported error.
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Ensure Whisper model is initialized via [WhisperEngine] using assets.
     *
     * This function is safe to call repeatedly.
     * It avoids repeated heavy initialization by:
     * - Checking [WhisperEngine.isInitializedForAsset] first.
     * - Using [modelInitMutex] to serialize init attempts.
     */
    private suspend fun ensureModelInitializedFromAssetsOnce() {
        if (WhisperEngine.isInitializedForAsset(assetModelPath)) {
            return
        }

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

    /**
     * Export the recorded WAV to persistent storage.
     */
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

    /**
     * Compute SHA-256 checksum for the given file.
     */
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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    override fun onCleared() {
        workerJob?.cancel()
        workerJob = null

        cleanupScope.launch {
            withContext(NonCancellable) {
                /**
                 * Soft detach to keep the heavy model alive across navigation
                 * and restart flows.
                 */
                runCatching { WhisperEngine.detach() }
                    .onFailure { e -> Log.w(TAG, "WhisperEngine.detach failed", e) }

                /**
                 * Recorder resources should be released with the ViewModel.
                 */
                runCatching { recorder.close() }
                    .onFailure { e -> Log.w(TAG, "Recorder.close failed", e) }
            }

            /**
             * Ensure the cleanup scope does not leak.
             */
            cleanupScope.cancel()
        }

        super.onCleared()
    }
}
