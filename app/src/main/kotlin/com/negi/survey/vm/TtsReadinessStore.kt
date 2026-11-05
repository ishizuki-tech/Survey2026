package com.negi.survey.vm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.speech.tts.TextToSpeech
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@ConsistentCopyVisibility
internal data class TtsReadinessKey private constructor(
    val enginePackage: String,
    val language: String,
    val requiresOfflineVoice: Boolean,
    val requiresEngine: Boolean,
) {
    companion object {
        fun create(
            enginePackage: String,
            language: String,
            requiresOfflineVoice: Boolean,
            requiresEngine: Boolean,
        ): TtsReadinessKey = TtsReadinessKey(
            enginePackage.trim(),
            normalizeLanguage(language),
            requiresOfflineVoice,
            requiresEngine,
        )

        fun normalizeLanguage(language: String): String =
            language.trim().lowercase(Locale.ROOT).ifEmpty { TtsController.DEFAULT_LANGUAGE }
    }
}

/** Main-thread confined. A check belongs to its initiating caller's coroutine. */
@MainThread
internal class TtsReadinessStore {
    private class Attempt {
        val result = CompletableDeferred<TtsVoiceCheck?>()
        var invalidated = false
    }

    private val successes = mutableSetOf<TtsReadinessKey>()
    private val inFlight = mutableMapOf<TtsReadinessKey, Attempt>()

    fun isReady(key: TtsReadinessKey): Boolean = key in successes

    suspend fun check(key: TtsReadinessKey, validate: suspend () -> TtsVoiceCheck): TtsVoiceCheck {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (isReady(key)) return TtsVoiceCheck.OK

            val running = inFlight[key]
            if (running != null) {
                val result = running.result.await()
                // Read success again from the store in case it was invalidated while resuming.
                if (!running.invalidated && result != null && result != TtsVoiceCheck.OK) return result
                continue
            }

            val attempt = Attempt()
            inFlight[key] = attempt
            var result: TtsVoiceCheck? = null
            try {
                val checked = validate()
                currentCoroutineContext().ensureActive()
                if (!attempt.invalidated) {
                    if (checked == TtsVoiceCheck.OK) successes.add(key)
                    result = checked
                }
            } finally {
                inFlight.remove(key)
                attempt.result.complete(result)
            }
            if (result != null) return result
        }
    }

    fun invalidateVoiceData(engine: String? = null) {
        invalidate { it.requiresOfflineVoice && (engine == null || it.enginePackage == engine) }
    }

    fun invalidate(matches: (TtsReadinessKey) -> Boolean) {
        successes.removeAll(matches)
        // Keep ownership until the check exits so invalidation cannot start a second checker.
        for ((key, attempt) in inFlight) {
            if (matches(key)) attempt.invalidated = true
        }
    }
}

/** Only readiness results and attempts are shared. Playback is owned by each survey. */
@MainThread
internal object ProcessTtsReadiness {
    val store = TtsReadinessStore()
    private var observingChanges = false

    fun observeChanges(context: Context) {
        if (observingChanges) return
        val app = context.applicationContext
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_DATA_CLEARED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val engine = intent.data?.schemeSpecificPart ?: return
                    store.invalidate { it.enginePackage == engine }
                }
            },
            packages,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    // The sender may differ from the engine whose voice data changed.
                    store.invalidateVoiceData()
                }
            },
            IntentFilter(TextToSpeech.Engine.ACTION_TTS_DATA_INSTALLED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        observingChanges = true
    }

    fun invalidateVoiceData(engine: String?) {
        store.invalidateVoiceData(engine)
    }
}
