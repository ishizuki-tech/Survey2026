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

import com.negi.survey.net.RecordingIdentity
import com.negi.survey.net.SurveyRecordingGate
import com.negi.survey.net.SurveyRecordings
import com.negi.survey.net.TranscriptionOutcome
import com.negi.survey.net.V2ArtifactStore
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.negi.survey.diagnostics.DiagnosticContentPolicy
import com.negi.survey.diagnostics.WhisperTraceRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.negi.survey.screens.SpeechController
import com.negi.survey.runtime.HeavyRuntimeHandoff
import com.negi.survey.runtime.NoOpHeavyRuntimeHandoff
import com.negi.survey.utils.ExportUtils
import com.negi.survey.whisper.Recorder
import com.negi.survey.whisper.RecorderBackend
import com.negi.survey.whisper.WhisperEngine
import com.negi.survey.whisper.InitTiming
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
    private val onVoiceExported: ((ExportedVoice) -> Unit)? = null,
    private val heavyRuntimeHandoff: HeavyRuntimeHandoff = NoOpHeavyRuntimeHandoff,
    private val recorderFactory: ((Context, (Exception) -> Unit) -> RecorderBackend)? = null,
    private val modelInitializer: (suspend () -> Unit)? = null,
    private val transcriber: (suspend (File, String) -> Result<String>)? = null,
    private val recorderStopTimeoutMs: Long = RECORDER_STOP_TIMEOUT_MS,
    private val recorderCloseTimeoutMs: Long = RECORDER_CLOSE_TIMEOUT_MS,
    private val whisperDetacher: suspend () -> Unit = { WhisperEngine.detach() },
    private val recorderSessionMutex: Mutex = globalRecorderSessionMutex,
    private val whisperSessionGate: WhisperSessionGate = globalWhisperSessionGate
) : ViewModel(), SpeechController {

    companion object {
        private const val TAG = "WhisperSpeechController"

        // Covers a controller replacement from acquisition through native release.
        private val globalWhisperSessionGate = WhisperSessionGate()
        private val globalRecorderSessionMutex = Mutex()

        private const val DEFAULT_LANGUAGE = "auto"
        private const val DEFAULT_ASSET_MODEL = "models/ggml-small-q5_1.bin"

        private val RECORDER_RATE_CANDIDATES = intArrayOf(16_000, 48_000, 44_100)

        private const val MIN_WAV_BYTES = 44L
        private const val MIN_DURATION_SEC_HEURISTIC = 0.25

        private const val WAV_STABILIZE_MAX_MS = 900L
        private const val WAV_STABILIZE_STEP_MS = 60L

        private const val PCM16_SILENCE_ABS_THRESHOLD = 400

        /** Timeout for recorder.stopRecording() (must be > Recorder STOP_JOIN_TIMEOUT_MS). */
        private const val RECORDER_STOP_TIMEOUT_MS = 6_000L
        private const val RECORDER_CLOSE_TIMEOUT_MS = 4_500L
        private const val RECORDER_CLOSE_PENDING = "Recorder close is still in progress"
        private const val RECORDER_CLOSE_FAILED = "Recorder cleanup failed; restart the app before recording again"

        fun provideFactory(
            appContext: Context,
            assetModelPath: String = DEFAULT_ASSET_MODEL,
            languageCode: String = DEFAULT_LANGUAGE,
            onVoiceExported: ((ExportedVoice) -> Unit)? = null,
            heavyRuntimeHandoff: HeavyRuntimeHandoff = NoOpHeavyRuntimeHandoff,
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
                        onVoiceExported = onVoiceExported,
                        heavyRuntimeHandoff = heavyRuntimeHandoff,
                    ) as T
                }
            }
    }

    /**
     * Minimal export event payload.
     */
    data class ExportedVoice(
        val surveyId: String,
        val questionId: String,
        val fileName: String,
        val byteSize: Long,
        val checksum: String? = null
    )

    // ---------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------

    @Volatile
    override var recordingPrompt: RecordingPrompt? = null
        private set

    /** All mutable recording resources belong to exactly one recording session. */
    private class RecordingSession(
        val id: Long,
        val recording: RecordingIdentity,
    ) {
        val surveyId get() = recording.prompt.surveyId
        val questionId get() = recording.prompt.questionId
        var recorder: RecorderBackend? = null
        var outputFile: File? = null
        var workerJob: Job? = null
        var startupJob: Job? = null
        var initJob: Deferred<Unit>? = null
        var ownsWhisperSession = false
        @Volatile var ownsRecorderSession = false
        val whisperCleanup = OnceCleanup()
        lateinit var recorderClose: SharedClose
        val closeMonitorStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var recorderFailed = false
        @Volatile var poisoned = false
    }

    private val nextSessionId = AtomicLong(0L)
    private val activeSession = AtomicReference<RecordingSession?>(null)

    private val recordingMutex = Mutex()
    private val modelInitMutex = Mutex()

    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    private val _isRecording = MutableStateFlow(false)
    private val _isCaptureActive = MutableStateFlow(false)
    private val _isTranscribing = MutableStateFlow(false)
    private val _partialText = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)

    override val isRecording: StateFlow<Boolean> = _isRecording
    override val isCaptureActive: StateFlow<Boolean> = _isCaptureActive
    override val isTranscribing: StateFlow<Boolean> = _isTranscribing
    override val partialText: StateFlow<String> = _partialText
    override val errorMessage: StateFlow<String?> = _error

    private val languageCodeRaw: String = languageCode

    private val normalizedLanguage: String =
        languageCodeRaw.trim()
            .lowercase(Locale.ROOT)
            .ifBlank { DEFAULT_LANGUAGE }

    override fun updateContext(surveyId: String?, questionId: String?) {
        Log.d(TAG, "updateContext: surveyId=$surveyId, questionId=$questionId")
        WhisperTraceRegistry.event(surveyId, questionId, "WHISPER_CONTEXT_UPDATED")
    }

    // ---------------------------------------------------------------------
    // Recording control
    // ---------------------------------------------------------------------

    override fun startRecording() {
        _error.value = "The recording needs a survey prompt."
    }

    override fun startRecording(prompt: RecordingPrompt) {
        if (_isRecording.value || _isTranscribing.value) {
            Log.d(TAG, "startRecording: busy, ignoring")
            return
        }
        val active = activeSession.get()
        if (active != null) {
            if (!active.poisoned) _error.value = "Previous recording is still finalizing"
            Log.w(TAG, "startRecording: active session is still finalizing, ignoring")
            return
        }

        val recording = RecordingIdentity(V2ArtifactStore.newId(), prompt)
        if (!SurveyRecordingGate.begin(recording)) {
            _error.value = "Wait for the survey export or previous recording to finish."
            return
        }
        val session = RecordingSession(
            id = nextSessionId.incrementAndGet(),
            recording = recording,
        )
        if (!activeSession.compareAndSet(null, session)) {
            SurveyRecordingGate.end(recording)
            _error.value = "Previous recording is still finalizing"
            Log.w(TAG, "startRecording: active session changed, ignoring")
            return
        }
        try {
            session.recorder = newRecorder(session)
        } catch (failure: Exception) {
            activeSession.compareAndSet(session, null)
            SurveyRecordingGate.end(recording)
            if (failure is CancellationException) throw failure
            DiagnosticContentPolicy.logError(TAG, "Recorder creation failed", failure)
            _error.value = "Could not start the microphone."
            return
        }
        session.recorderClose = SharedClose(cleanupScope) {
            session.recorder?.close()
            if (session.ownsRecorderSession) {
                session.ownsRecorderSession = false
                recorderSessionMutex.unlock()
            }
        }

        recordingPrompt = prompt
        Log.d(TAG, "microphone requested elapsedMs=${SystemClock.elapsedRealtime()} session=${session.id}")
        WhisperTraceRegistry.event(session.surveyId, session.questionId, "RECORDING_START_REQUESTED", mapOf("whisperRecordingId" to session.id))
        _error.value = null
        _partialText.value = ""
        _isRecording.value = true

        session.initJob = viewModelScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            whisperSessionGate.acquire()
            session.ownsWhisperSession = true
            val start = SystemClock.elapsedRealtime()
            Log.d(TAG, "Whisper init started elapsedMs=$start session=${session.id}")
            try { ensureModelInitializedFromAssetsOnce(session) } finally {
                Log.d(TAG, "Whisper init finished elapsedMs=${SystemClock.elapsedRealtime()} session=${session.id}")
            }
        }
        session.startupJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            recordingMutex.withLock {
                try {
                    ensureActive()
                    check(!V2ArtifactStore(appContext).hasReservedRoute(V2ArtifactStore.surveyKey(session.surveyId))) {
                        "The survey already has an immutable export."
                    }
                    check(recorderSessionMutex.tryLock()) { "Previous recorder is still closing; restart required if cleanup failed" }
                    session.ownsRecorderSession = true

                    val old = session.outputFile
                    session.outputFile = null
                    deleteTempFileQuietly(old, reason = "start_cleanup")

                    val dir = File(appContext.cacheDir, "whisper_rec")
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw IllegalStateException("Failed to create cache dir: ${dir.path}")
                    }

                    val wav = File.createTempFile("survey_input_", ".wav", dir)
                    session.outputFile = wav

                    session.recorder?.setOnCaptureStarted {
                        synchronized(session) {
                            if (owns(session) && _isRecording.value) {
                                _isCaptureActive.value = true
                                WhisperTraceRegistry.event(session.surveyId, session.questionId, "RECORDING_STARTED", mapOf("whisperRecordingId" to session.id))
                            }
                        }
                    }
                    Log.d(TAG, "startRecording: recorder.startRecording -> ${wav.path}")
                    session.recorder?.startRecording(output = wav, rates = RECORDER_RATE_CANDIDATES)
                        ?: error("Recorder session was not initialized")
                    session.initJob?.start()
                    Log.d(TAG, "startRecording: recorder requested")
                } catch (ce: CancellationException) {
                    Log.d(TAG, "startRecording: cancelled")
                    if (!owns(session)) return@withLock
                    _isRecording.value = false
                    val tmp = session.outputFile
                    session.outputFile = null
                    deleteTempFileQuietly(tmp, reason = "start_cancelled")
                    finishWhisperSession(session)
                    if (!session.recorderFailed) activeSession.compareAndSet(session, null)
                    SurveyRecordingGate.end(recording)
                } catch (t: Throwable) {
                    DiagnosticContentPolicy.logError(TAG, "startRecording: failed", t)
                    if (!owns(session)) return@withLock
                    _error.value = t.message ?: "Speech recognition start failed"
                    _isRecording.value = false
                    _isTranscribing.value = false

                    val tmp = session.outputFile
                    session.outputFile = null
                    deleteTempFileQuietly(tmp, reason = "start_failed")
                    finishWhisperSession(session)
                    if (closeRecorderOrMonitor(session)) activeSession.compareAndSet(session, null)
                    SurveyRecordingGate.end(recording)
                }
            }
        }
        session.startupJob?.start()
    }

    override fun stopRecording() {
        val session = activeSession.get()
        if (session == null) {
            Log.d(TAG, "stopRecording: not recording, ignoring")
            return
        }

        synchronized(session) {
            if (!owns(session) || !_isRecording.value) return
            Log.d(TAG, "recording stop requested elapsedMs=${SystemClock.elapsedRealtime()} session=${session.id}")
            // Publish the lock before ending capture so observers never see an idle gap.
            _isTranscribing.value = true
            _isRecording.value = false
            _isCaptureActive.value = false
        }

        session.workerJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            session.startupJob?.join()
            recordingMutex.withLock {
                if (!owns(session) || session.poisoned || session.recorderFailed) return@withLock
                var localWav: File? = null
                try {
                    val t0 = SystemClock.elapsedRealtime()
                    Log.d(TAG, "stopRecording: awaiting recorder.stopRecording()")

                    val ok = withTimeoutOrNull(recorderStopTimeoutMs) {
                        session.recorder?.stopRecording() ?: error("Recorder session was not initialized")
                        true
                    } ?: false

                    val dt = SystemClock.elapsedRealtime() - t0
                    if (!ok) {
                        Log.e(TAG, "recorder.stopRecording TIMEOUT after ${dt}ms (qid=${session.questionId})")
                        if (owns(session)) {
                            session.poisoned = true
                            _error.value = "Recorder stop timeout (AudioRecord thread stuck)"
                            closePoisonedSessionAsync(session)
                        }

                        return@withLock
                    } else {
                        Log.d(TAG, "recorder.stopRecording OK in ${dt}ms")
                        session.recorderClose.request()
                    }

                    val wav = session.outputFile
                    session.outputFile = null
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
                    WhisperTraceRegistry.event(session.surveyId, session.questionId, "RECORDING_STOPPED", mapOf("whisperRecordingId" to session.id, "byteSize" to stableBytes))

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

                    val recording = session.recording
                    val staged = try {
                        check(info != null && info.dataBytes > 0 && info.dataOffset + info.dataBytes <= wav.length()) {
                            "Recording has an invalid WAV header or incomplete audio."
                        }
                        ExportUtils.exportRecordedVoice(appContext, wav, recording)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        DiagnosticContentPolicy.logWarning(TAG, "WAV staging failed", failure)
                        if (owns(session)) _error.value = "Could not save this recording."
                        null
                    }
                    if (staged != null && owns(session)) {
                        val route = staged.route
                        try {
                            onVoiceExported?.invoke(ExportedVoice(
                                surveyId = session.surveyId,
                                questionId = session.questionId,
                                fileName = route.basename,
                                byteSize = route.size,
                                checksum = route.fingerprint,
                            ))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            DiagnosticContentPolicy.logWarning(TAG, "WAV export observer failed", failure)
                        }
                        WhisperTraceRegistry.event(session.surveyId, session.questionId, "AUDIO_EXPORTED",
                            mapOf("fileName" to route.basename, "byteSize" to route.size))
                    }

                    if (!owns(session)) return@withLock
                    val result = try {
                        session.initJob?.await() ?: error("Whisper initialization was not scheduled")
                        if (!owns(session)) return@withLock
                        Log.d(TAG, "transcription started elapsedMs=${SystemClock.elapsedRealtime()} session=${session.id}")
                        WhisperTraceRegistry.event(session.surveyId, session.questionId, "TRANSCRIPTION_STARTED",
                            mapOf("language" to normalizedLanguage))

                        transcriber?.invoke(wav, normalizedLanguage) ?: WhisperEngine.transcribeWaveFile(
                            file = wav,
                            lang = normalizedLanguage,
                            translate = false,
                            printTimestamp = false,
                            targetSampleRate = 16_000,
                            onTiming = { timing ->
                                WhisperTraceRegistry.event(session.surveyId, session.questionId, "TRANSCRIPTION_FINISHED", mapOf(
                                    "whisperRequestId" to timing.requestId, "totalMs" to timing.totalMs,
                                    "decodeMs" to timing.decodeMs, "lockWaitMs" to timing.lockWaitMs,
                                    "selectedLanguage" to timing.selectedLang, "audioSeconds" to timing.audioSeconds
                                ))
                            }
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        Result.failure(failure)
                    }
                    (result.exceptionOrNull() as? CancellationException)?.let { throw it }
                    ensureActive()
                    if (!owns(session)) return@withLock
                    if (staged != null) {
                        SurveyRecordings(V2ArtifactStore(appContext)).persistOutcome(
                            staged, recording, TranscriptionOutcome.from(result),
                        )
                    }

                    result
                        .onSuccess { text ->
                            val trimmed = text.trim()
                            if (!owns(session)) return@onSuccess
                            if (trimmed.isEmpty()) {
                                Log.w(TAG, "Transcription produced empty text (qid=${session.questionId})")
                                _error.value = buildEmptyTranscriptionReason(wav)
                            } else {
                                Log.d(TAG, "Transcription success: transcriptLength=${trimmed.length}")
                                WhisperTraceRegistry.event(session.surveyId, session.questionId, "TRANSCRIPTION_FINISHED", mapOf(
                                    "transcript" to DiagnosticContentPolicy.rawOrNull(trimmed),
                                    "transcriptLength" to trimmed.length,
                                    "success" to true,
                                    "language" to normalizedLanguage,
                                ))
                            }
                            updatePartialText(trimmed)
                        }
                        .onFailure { e ->
                            if (!owns(session)) return@onFailure
                            DiagnosticContentPolicy.logError(TAG, "Transcription failed", e)
                            WhisperTraceRegistry.event(session.surveyId, session.questionId, "TRANSCRIPTION_FAILED", mapOf(
                                "error" to DiagnosticContentPolicy.rawOrNull(e.message),
                                "errorClass" to e.javaClass.simpleName,
                            ))
                            _error.value = e.message ?: "Transcription failed"
                        }
                } catch (ce: CancellationException) {
                    Log.d(TAG, "stopRecording: cancelled")
                    throw ce
                } catch (t: Throwable) {
                    DiagnosticContentPolicy.logError(TAG, "stopRecording: failed", t)
                    if (owns(session)) _error.value = t.message ?: "Speech recognition failed"
                } finally {
                    withContext(NonCancellable) {
                        runCatching { finishWhisperSession(session) }
                            .onFailure { e ->
                                DiagnosticContentPolicy.logWarning(TAG, "WhisperEngine.release failed", e)
                                if (owns(session)) {
                                    _error.value = "Speech recognition cleanup failed; restart required"
                                }
                            }
                    }
                    if (!session.poisoned) closeRecorderOrMonitor(session)
                    SurveyRecordingGate.end(session.recording)
                    if (owns(session)) {
                        // Transcription has ended even if a failed recorder must stay reserved.
                        if (!session.poisoned) activeSession.compareAndSet(session, null)
                        _isTranscribing.value = false
                    }
                    deleteTempFileQuietly(localWav, reason = "stop_finally")
                }
            }
        }
        session.workerJob?.invokeOnCompletion {
            SurveyRecordingGate.end(session.recording)
        }
        session.workerJob?.start()
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

    /** Wait for native initialization to stop before releasing its published context. */
    private suspend fun finishWhisperSession(session: RecordingSession) = session.whisperCleanup.run {
        session.initJob?.cancelAndJoin()
        if (session.ownsWhisperSession) {
            try {
                whisperSessionGate.release { heavyRuntimeHandoff.releaseWhisper() }
            } finally {
                session.ownsWhisperSession = false
            }
        }
    }

    // ---------------------------------------------------------------------
    // Model init
    // ---------------------------------------------------------------------

    private suspend fun ensureModelInitializedFromAssetsOnce(session: RecordingSession) {
        modelInitializer?.let {
            it()
            return
        }
        if (WhisperEngine.isInitializedForAsset(assetModelPath)) return

        modelInitMutex.withLock {
            if (WhisperEngine.isInitializedForAsset(assetModelPath)) {
                Log.d(TAG, "WhisperEngine already initialized for assets/$assetModelPath (locked)")
                return
            }

            var initTiming: InitTiming? = null
            val result = try {
                val handoff = WhisperEngine.WhisperPublicationHandoff()
                Result.success(try {
                    heavyRuntimeHandoff.withWhisper {
                        WhisperTraceRegistry.event(session.surveyId, session.questionId, "MODEL_INIT_STARTED", mapOf("modelKey" to assetModelPath, "language" to normalizedLanguage))
                        WhisperEngine.ensureInitializedFromAsset(
                            context = appContext,
                            assetPath = assetModelPath,
                            onTiming = { timing -> initTiming = timing },
                            publicationHandoff = handoff,
                        ).getOrThrow()
                    }
                } catch (error: Throwable) {
                    handoff.abandon()?.let { lease ->
                        withContext(NonCancellable) {
                            WhisperEngine.releaseGenerationIfCurrent(lease)
                        }
                    }
                    throw error
                })
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Result.failure(t)
            }

            result.onFailure { e ->
                WhisperTraceRegistry.event(session.surveyId, session.questionId, "MODEL_INIT_FINISHED", mapOf(
                    "success" to false,
                    "modelKey" to assetModelPath,
                    "error" to DiagnosticContentPolicy.rawOrNull(e.message),
                    "errorClass" to e.javaClass.simpleName,
                ))
                DiagnosticContentPolicy.logError(TAG, "ensureModelInitializedFromAssetsOnce failed: assets/$assetModelPath", e)
                throw IllegalStateException(
                    "Failed to initialize Whisper model from assets/$assetModelPath",
                    e
                )
            }

            result.onSuccess {
                initTiming?.let { timing -> WhisperTraceRegistry.event(session.surveyId, session.questionId, "MODEL_INIT_FINISHED", mapOf("success" to true, "modelInitRequestId" to timing.requestId, "modelKey" to timing.key, "totalMs" to timing.totalMs, "lockWaitMs" to timing.lockWaitMs, "modelBytes" to timing.modelBytes)) }
            }

            Log.d(TAG, "WhisperEngine initialized: assets/$assetModelPath")
        }
    }

    // ---------------------------------------------------------------------
    // Export / checksum
    // ---------------------------------------------------------------------

    private fun deleteTempFileQuietly(file: File?, reason: String) {
        if (file == null) return
        runCatching {
            if (file.exists()) {
                val ok = file.delete()
                Log.d(TAG, "temp delete=$ok reason=$reason -> ${file.path}")
            }
        }.onFailure { e ->
            DiagnosticContentPolicy.logWarning(TAG, "temp delete failed reason=$reason -> ${file.path}", e)
        }
    }

    // ---------------------------------------------------------------------
    // Recorder lifecycle / recovery
    // ---------------------------------------------------------------------

    /**
     * Create a new Recorder instance with the standard error callback.
     */
    private fun owns(session: RecordingSession): Boolean = activeSession.get() === session

    private fun newRecorder(session: RecordingSession): RecorderBackend {
        val onError: (Exception) -> Unit = onError@{ e ->
            DiagnosticContentPolicy.logError(TAG, "Recorder error", e)
            if (!owns(session)) {
                Log.d(TAG, "Ignoring stale recorder error for session=${session.id}")
                return@onError
            }

            session.recorderFailed = true
            synchronized(session) {
                _error.value = e.message ?: "Recording error"
                _isRecording.value = false
                _isCaptureActive.value = false
            }

            cleanupScope.launch {
                withContext(NonCancellable) {
                    if (owns(session)) {
                        session.startupJob?.cancelAndJoin()
                        session.workerJob?.cancelAndJoin()
                        recordingMutex.withLock {
                            if (!owns(session)) return@withLock
                            session.poisoned = true
                            closePoisonedSessionAsync(session)
                            runCatching { finishWhisperSession(session) }
                            SurveyRecordingGate.end(session.recording)
                            _isTranscribing.value = false
                        }
                    }
                }
            }
        }
        return recorderFactory?.invoke(appContext, onError) ?: Recorder(appContext, onError)
    }

    /** A close that times out or fails keeps the session reserved until proven closed. */
    private suspend fun closeRecorderOrMonitor(session: RecordingSession): Boolean = withContext(NonCancellable) {
        val result = withTimeoutOrNull(recorderCloseTimeoutMs) { session.recorderClose.request().await() }
        if (result != null && result.isSuccess) return@withContext true
        session.poisoned = true
        if (owns(session)) {
            _error.value = if (result == null) RECORDER_CLOSE_PENDING else RECORDER_CLOSE_FAILED
        }
        closePoisonedSessionAsync(session)
        false
    }

    /** Keep ownership until the one shared close actually succeeds. */
    private fun closePoisonedSessionAsync(session: RecordingSession) {
        if (!session.closeMonitorStarted.compareAndSet(false, true)) return
        val close = session.recorderClose.request()
        cleanupScope.launch {
            val result = close.await()
            recordingMutex.withLock {
                if (!owns(session) || !session.poisoned) return@withLock
                result.onFailure { e ->
                    DiagnosticContentPolicy.logError(TAG, "Recorder.close failed", e)
                    _error.value = RECORDER_CLOSE_FAILED
                }.onSuccess {
                    val tmp = session.outputFile
                    session.outputFile = null
                    deleteTempFileQuietly(tmp, reason = "recorder_close")
                    if (_error.value == RECORDER_CLOSE_PENDING) _error.value = null
                    activeSession.compareAndSet(session, null)
                    _isTranscribing.value = false
                }
            }
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
        val session = activeSession.getAndSet(null)
        session?.startupJob?.cancel()
        session?.workerJob?.cancel()

        val job = cleanupScope.launch {
            withContext(NonCancellable) {
                session?.startupJob?.join()
                session?.workerJob?.join()
                session?.let { SurveyRecordingGate.end(it.recording) }
                val close = session?.recorderClose?.request()
                session?.let { runCatching { finishWhisperSession(it) }
                    .onFailure { e -> DiagnosticContentPolicy.logWarning(TAG, "WhisperEngine.release failed", e) } }
                runCatching { whisperSessionGate.serialized { whisperDetacher() } }
                    .onFailure { e -> DiagnosticContentPolicy.logWarning(TAG, "WhisperEngine.detach failed", e) }

                session?.let { active ->
                    val closeResult = checkNotNull(close).await()
                    closeResult.onFailure { e -> DiagnosticContentPolicy.logWarning(TAG, "Recorder.close failed", e) }
                        .onSuccess { deleteTempFileQuietly(active.outputFile, reason = "view_model_cleared") }
                }
            }
        }

        job.invokeOnCompletion { cleanupScope.cancel() }
        super.onCleared()
    }
}
