package com.negi.survey.vm

import android.speech.tts.TextToSpeech
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Rule
import org.junit.Test

class TtsControllerLifecycleTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun stopClearsEngineQueueBeforeInitializationCompletes() {
        val engine = mockk<TextToSpeech>(relaxed = true)
        every { engine.setLanguage(any()) } returns TextToSpeech.LANG_COUNTRY_AVAILABLE
        every { engine.voice } returns null
        lateinit var init: TextToSpeech.OnInitListener
        lateinit var controller: TtsController
        lateinit var registry: LifecycleRegistry

        rule.setContent {
            val context = LocalContext.current.applicationContext
            val owner = remember {
                object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply {
                        currentState = Lifecycle.State.RESUMED
                    }
                }
            }
            registry = owner.lifecycle
            controller = remember {
                TtsController(context, createEngine = { _, listener, _ ->
                    init = listener
                    engine
                })
            }
            StopQuestionSpeakerInBackground(controller, registry)
        }

        rule.runOnIdle {
            controller.speak("Queued before initialization", "queued")
            registry.currentState = Lifecycle.State.CREATED
        }
        verify(exactly = 1) { engine.stop() }

        init.onInit(TextToSpeech.SUCCESS)
        rule.waitUntil(5_000) { controller.isReady.value }
        rule.runOnIdle { registry.currentState = Lifecycle.State.RESUMED }
        verify(exactly = 0) { engine.speak(any(), any(), any<android.os.Bundle>(), any()) }

        rule.runOnIdle { toggleQuestionSpeaker(controller, "Manual replay", "replay") }
        verify(exactly = 1) {
            engine.speak("Manual replay", TextToSpeech.QUEUE_FLUSH, any<android.os.Bundle>(), "replay")
        }
    }
}
