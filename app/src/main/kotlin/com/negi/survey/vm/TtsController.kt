/*
 * =====================================================================
 *  File: TtsController.kt
 *  Summary:
 *  ---------------------------------------------------------------------
 *  ViewModel-based text-to-speech controller backed by the Android
 *  platform TextToSpeech engine (fully offline once a device's voice
 *  data for the target language is installed; no APK size increase,
 *  no new dependency).
 *
 *  Used to read survey questions aloud (auto-play on question change,
 *  plus a manual "replay" affordance from the UI).
 * =====================================================================
 */

package com.negi.survey.vm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal contract for reading survey question text aloud.
 *
 * Implementations are expected to be resilient: [speak] should never throw,
 * and should degrade to a no-op (surfacing [errorMessage]) when the engine
 * or the requested language/voice is unavailable on the device.
 */
interface QuestionSpeaker {

    /** True while audio is actively being played back. */
    val isSpeaking: StateFlow<Boolean>

    /** True once the underlying engine has initialized and a usable voice was selected. */
    val isReady: StateFlow<Boolean>

    /** Optional human-readable error (e.g., requested language/voice not installed). */
    val errorMessage: StateFlow<String?>

    /**
     * Speak [text] aloud, replacing any currently playing utterance.
     *
     * @param text Text to speak. No-op when blank.
     * @param utteranceId Stable id for this utterance (e.g., the node/question id),
     *   used for progress-listener correlation and future extension (e.g., analytics).
     */
    fun speak(text: String, utteranceId: String)

    /** Stop any in-progress playback immediately. */
    fun stop()
}

/**
 * Returns whether a question may begin automatic read-aloud.
 *
 * Voice capture and transcription take priority so a question is never played
 * through the speaker while the microphone flow is active.
 */
internal fun shouldAutoPlayQuestion(
    autoPlayEnabled: Boolean,
    speechRecording: Boolean = false,
    speechTranscribing: Boolean = false
): Boolean = autoPlayEnabled && !speechRecording && !speechTranscribing

/** Performs the action represented by the question speaker button. */
internal fun toggleQuestionSpeaker(
    speaker: QuestionSpeaker,
    text: String,
    utteranceId: String
) {
    if (speaker.isSpeaking.value) {
        speaker.stop()
    } else {
        speaker.speak(text, utteranceId)
    }
}

/**
 * True when [pkg] exposes a TextToSpeech service.
 * Requires the TTS_SERVICE `<queries>` entry in the manifest on Android 11+.
 */
internal fun isTtsEngineInstalled(context: Context, pkg: String): Boolean =
    runCatching {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(pkg)
        context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
    }.getOrDefault(false)

/** Result of [checkTtsOfflineVoice]. */
enum class TtsVoiceCheck { OK, ENGINE_MISSING, VOICE_MISSING, ERROR }

/**
 * Checks that [enginePkg] is installed and offers a voice for [languageCode] that works
 * offline: not network-only, and its data downloaded (no KEY_FEATURE_NOT_INSTALLED).
 * Briefly starts a TextToSpeech instance bound to that engine, then shuts it down.
 */
internal suspend fun checkTtsOfflineVoice(
    context: Context,
    enginePkg: String,
    languageCode: String,
    timeoutMs: Long = 8_000L
): TtsVoiceCheck {
    if (!isTtsEngineInstalled(context, enginePkg)) return TtsVoiceCheck.ENGINE_MISSING
    val wantIso3 = runCatching { Locale.forLanguageTag(languageCode).isO3Language }
        .getOrDefault(languageCode)

    return withContext(Dispatchers.Main) {
        val initStatus = CompletableDeferred<Int>()
        val tts = TextToSpeech(
            context.applicationContext,
            { status -> initStatus.complete(status) },
            enginePkg
        )
        try {
            val status = withTimeoutOrNull(timeoutMs) { initStatus.await() }
            if (status != TextToSpeech.SUCCESS) {
                Log.w("TtsController", "Offline voice check: engine=$enginePkg init status=$status")
                return@withContext TtsVoiceCheck.ERROR
            }
            val voices = runCatching { tts.voices }.getOrNull().orEmpty()
            val offline = voices.filter { v ->
                val iso3 = runCatching { v.locale.isO3Language }.getOrDefault(v.locale.language)
                iso3.equals(wantIso3, ignoreCase = true) &&
                    !v.isNetworkConnectionRequired &&
                    !v.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }
            Log.d(
                "TtsController",
                "Offline voice check: engine=$enginePkg lang=$languageCode voices=${voices.size} " +
                    "offline=${offline.map { it.name }}"
            )
            if (offline.isNotEmpty()) TtsVoiceCheck.OK else TtsVoiceCheck.VOICE_MISSING
        } finally {
            runCatching { tts.shutdown() }
        }
    }
}

/**
 * [QuestionSpeaker] backed by [android.speech.tts.TextToSpeech].
 *
 * Notes:
 * - Requires no runtime permission (unlike microphone capture).
 * - Fully offline as long as the requested language's voice data is already
 *   installed on the device (Settings > Languages & input > Text-to-speech).
 *   If it is not installed, [errorMessage] is set and playback is skipped;
 *   this does not block the rest of the survey flow.
 * - A single [speak] request queues if called before the engine finishes
 *   initializing, and plays automatically once ready (covers the common
 *   "auto-play as soon as the question appears" race).
 */
class TtsController(
    private val appContext: Context,
    private val languageCode: String = DEFAULT_LANGUAGE,
    /** Null = don't set; the TTS engine's own speech-rate setting applies. */
    private val speechRate: Float? = null,
    /** Null = don't set; the TTS engine's own pitch setting applies. */
    private val pitch: Float? = null,
    private val enginePackage: String? = null,
    private val requireEngine: Boolean = false
) : ViewModel(), QuestionSpeaker {

    companion object {
        private const val TAG = "TtsController"
        const val DEFAULT_LANGUAGE = "en"

        fun provideFactory(
            appContext: Context,
            languageCode: String = DEFAULT_LANGUAGE,
            speechRate: Float? = null,
            pitch: Float? = null,
            enginePackage: String? = null,
            requireEngine: Boolean = false
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(TtsController::class.java)) {
                        "Unknown ViewModel class $modelClass"
                    }
                    return TtsController(
                        appContext = appContext.applicationContext,
                        languageCode = languageCode,
                        speechRate = speechRate,
                        pitch = pitch,
                        enginePackage = enginePackage,
                        requireEngine = requireEngine
                    ) as T
                }
            }
    }

    private val _isSpeaking = MutableStateFlow(false)
    private val _isReady = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    override val isSpeaking: StateFlow<Boolean> = _isSpeaking
    override val isReady: StateFlow<Boolean> = _isReady
    override val errorMessage: StateFlow<String?> = _error

    /** Most recent speak() request received before init completed; replayed once ready. */
    private var pendingText: String? = null
    private var pendingUtteranceId: String? = null

    private var engine: TextToSpeech? = null

    init {
        val requested = enginePackage?.trim()?.ifBlank { null }
        val installed = requested != null && isEngineInstalled(requested)
        engine = if (installed) {
            Log.d(TAG, "Binding to TTS engine app: $requested")
            TextToSpeech(appContext, { status -> onEngineInit(status) }, requested)
        } else if (requested != null && requireEngine) {
            // Forced engine missing: never read questions with a different voice.
            Log.e(TAG, "Required TTS engine '$requested' is not installed; read-aloud disabled")
            _error.value = "Read-aloud needs the voice app $requested for '$languageCode'. Please install it."
            _isReady.value = false
            null
        } else {
            if (requested != null) {
                Log.w(TAG, "TTS engine '$requested' not installed; using device default engine")
            }
            TextToSpeech(appContext) { status -> onEngineInit(status) }
        }
    }

    private fun isEngineInstalled(pkg: String): Boolean = isTtsEngineInstalled(appContext, pkg)

    private fun onEngineInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TextToSpeech init failed: status=$status")
            _error.value = "Text-to-speech engine failed to initialize"
            _isReady.value = false
            return
        }

        val tts = engine ?: return
        val locale = resolveLocale(languageCode)
        Log.d(
            TAG,
            "TextToSpeech initialized: defaultEngine=${tts.defaultEngine} " +
                "requestedLocale=$locale"
        )
        val result = tts.setLanguage(locale)

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(
                TAG,
                "TTS language unavailable: defaultEngine=${tts.defaultEngine} " +
                    "locale=$locale result=$result"
            )
            _error.value = "Text-to-speech voice for '$languageCode' isn't installed on this device"
            _isReady.value = false
            return
        }

        // Only override the engine's own rate/pitch when the survey YAML sets them.
        speechRate?.let { r -> runCatching { tts.setSpeechRate(r) } }
        pitch?.let { p -> runCatching { tts.setPitch(p) } }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
            }

            @Deprecated("Deprecated in Java", ReplaceWith(""))
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                _error.value = "Text-to-speech playback failed"
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _isSpeaking.value = false
                _error.value = "Text-to-speech playback failed (code=$errorCode)"
            }
        })

        _isReady.value = true
        Log.d(
            TAG,
            "TextToSpeech ready: requestedEngine=${enginePackage ?: "default"} " +
                "defaultEngine=${tts.defaultEngine} requestedLocale=$locale " +
                "languageResult=$result activeVoice=${tts.voice} " +
                "rate=${speechRate ?: "engine"} pitch=${pitch ?: "engine"}"
        )

        val text = pendingText
        val uid = pendingUtteranceId
        if (!text.isNullOrBlank() && uid != null) {
            pendingText = null
            pendingUtteranceId = null
            speak(text, uid)
        }
    }

    override fun speak(text: String, utteranceId: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val tts = engine
        if (tts == null || !_isReady.value) {
            // Engine still initializing: remember only the latest request.
            pendingText = trimmed
            pendingUtteranceId = utteranceId
            return
        }

        _error.value = null
        val result = tts.speak(trimmed, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "speak() returned ERROR for utteranceId=$utteranceId")
            _error.value = "Text-to-speech failed to start"
            _isSpeaking.value = false
        }
    }

    override fun stop() {
        // A stop before initialization must also cancel the deferred auto-play.
        pendingText = null
        pendingUtteranceId = null
        runCatching { engine?.stop() }
        _isSpeaking.value = false
    }

    fun clearError() {
        _error.value = null
    }

    private fun resolveLocale(code: String): Locale {
        val norm = code.trim().lowercase(Locale.US)
        return when (norm) {
            "", "auto" -> Locale.getDefault()
            "en" -> Locale.US
            "ja" -> Locale.JAPANESE
            "sw" -> Locale.forLanguageTag("sw")
            else -> Locale.forLanguageTag(norm)
        }
    }

    override fun onCleared() {
        pendingText = null
        pendingUtteranceId = null
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        super.onCleared()
    }
}

/**
 * No-op [QuestionSpeaker] used when TTS is disabled by configuration
 * (`tts.enabled: false` in the survey YAML).
 */
class NoOpQuestionSpeaker(
    private val disabledReason: String = "Text-to-speech is disabled by configuration."
) : QuestionSpeaker {

    private val _isSpeaking = MutableStateFlow(false)
    private val _isReady = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(disabledReason)

    override val isSpeaking: StateFlow<Boolean> = _isSpeaking
    override val isReady: StateFlow<Boolean> = _isReady
    override val errorMessage: StateFlow<String?> = _error

    override fun speak(text: String, utteranceId: String) {
        // No-op
    }

    override fun stop() {
        // No-op
    }
}
