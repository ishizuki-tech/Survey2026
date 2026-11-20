package com.negi.survey

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import com.negi.survey.screens.SpeechController
import com.negi.survey.screens.toggleSpeechRecordingWithTtsInterlock
import com.negi.survey.slm.Repository
import com.negi.survey.slm.RepositoryTraceContext
import com.negi.survey.ui.theme.SurveyNavTheme
import com.negi.survey.vm.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real gate button and production navigation wiring; engine/ASR/inference are deterministic. */
class SessionTtsDisableTest {
    @get:Rule val rule = createComposeRule()
    private val sessions = SurveySessionStore()
    private val readiness = TtsReadinessStore()
    private var sessionKey by mutableStateOf("survey@1")
    private var mount by mutableIntStateOf(0)
    private var checks = 0
    private var constructions = 0
    private val spoken = mutableListOf<String>()
    private lateinit var survey: SurveyViewModel
    private lateinit var ai: AiViewModel
    private lateinit var back: OnBackPressedDispatcher
    private val lifecycleOwner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
    private val speech = FakeSpeech()
    private val speaker = mockk<TtsController>(relaxed = true).also {
        every { it.isReady } returns MutableStateFlow(true)
        every { it.isSpeaking } returns MutableStateFlow(false)
        every { it.errorMessage } returns MutableStateFlow<String?>(null)
        every { it.speak(any(), any()) } answers { spoken += firstArg<String>() }
    }
    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            constructions++
            return speaker as T
        }
    }

    private fun host(result: TtsVoiceCheck, offline: Boolean = false, yamlEnabled: Boolean = true) {
        val config = config(offline, yamlEnabled)
        rule.runOnUiThread { lifecycleOwner.lifecycle.currentState = Lifecycle.State.RESUMED }
        rule.setContent {
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                SurveyNavTheme {
                    key(sessionKey, mount) {
                        if (SurveyTtsReadinessGate(
                                config, sessionKey, sessions, onExit = {}, readiness = readiness,
                                checkReadiness = { checks++; result }
                            )) {
                            val owner = sessions.ownerFor(sessionKey)
                            val stack = androidx.compose.runtime.remember { NavBackStack<NavKey>(FlowHome) }
                            survey = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                    SurveyViewModel(stack, config).apply { goto("Q00") } as T
                            })["survey", SurveyViewModel::class.java]
                            survey.attachNavigation(stack)
                            ai = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                    AiViewModel(object : Repository {
                                        override fun buildPrompt(userPrompt: String) = userPrompt
                                        override suspend fun request(prompt: String, traceContext: RepositoryTraceContext?) =
                                            flowOf("""{"score":100,"missing_points":[],"followup_needed":false}""")
                                    }) as T
                            })["ai", AiViewModel::class.java]
                            val disabled by sessions.ttsDisabledFor(sessionKey).collectAsStateWithLifecycle()
                            SurveyNavHost(
                                vmSurvey = survey, vmAI = ai, backStack = stack,
                                ttsMeta = config.tts, sessionId = sessionKey, sessionVmOwner = owner,
                                sessionTtsDisabled = disabled, ttsFactoryOverride = factory,
                                speechControllerOverride = speech,
                                onResetToSelector = {
                                    sessions.clearSession(sessionKey)
                                    sessionKey = "survey@2"
                                },
                                onResolveUploadStatusDetails = { error("Unused") },
                                onClearCompletedSurveys = {}
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun continueWithoutVoice() {
        rule.onNodeWithText("Continue without voice", substring = true).performScrollTo().performClick()
        rule.runOnIdle { assertTrue(sessions.ttsDisabledFor(sessionKey).value) }
    }

    private fun assertSilent() = rule.runOnIdle {
        assertEquals("Disabled session must never construct or bind an engine", 0, constructions)
        assertTrue(spoken.isEmpty())
    }

    @Test fun missingEngineContinueSuppressesEveryPlaybackBoundary() {
        host(TtsVoiceCheck.ENGINE_MISSING)
        continueWithoutVoice()
        for (node in listOf("Q00", "Info", "Text", "Single", "Multi", "Number", "AI")) {
            rule.runOnIdle { survey.goto(node); assertEquals(node, survey.currentNodeId) }
            rule.onNodeWithText("🔊").performClick()
            assertSilent()
        }
        rule.runOnIdle {
            assertTrue(survey.addFollowupQuestion("AI", "Pending follow-up?"))
            ai.setFollowupMode("sid=${survey.sessionId.value}|nid=AI", "Pending follow-up?")
        }
        rule.onNodeWithText("🔊").performClick()
        assertSilent()
        for (node in listOf("Start", "Stop", "Review", "Done")) {
            rule.runOnIdle { survey.goto(node); assertEquals(node, survey.currentNodeId) }
            assertSilent()
        }
    }

    @Test fun missingOfflineVoiceChoiceSurvivesNavigationSettingsAndCompositionRecreation() {
        host(TtsVoiceCheck.VOICE_MISSING, offline = true)
        continueWithoutVoice()
        rule.runOnIdle { survey.goto("Number"); survey.setAnswer("2", "Number") }
        rule.onNodeWithText("Next").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("AI", survey.currentNodeId) }
        rule.onNodeWithText("Back").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("Number", survey.currentNodeId); back.onBackPressed() }
        rule.runOnIdle { assertTrue(sessions.ttsDisabledFor(sessionKey).value) }
        val checksBeforeResume = checks
        rule.runOnIdle {
            // Models the lifecycle of returning from system settings after installing voice data.
            lifecycleOwner.lifecycle.currentState = Lifecycle.State.CREATED
            readiness.invalidateVoiceData()
        }
        rule.runOnIdle { lifecycleOwner.lifecycle.currentState = Lifecycle.State.RESUMED }
        rule.runOnIdle { mount++ }
        rule.runOnIdle {
            assertEquals(checksBeforeResume, checks)
            assertTrue(sessions.ttsDisabledFor(sessionKey).value)
            survey.goto("Start")
        }
        assertSilent()
        rule.runOnIdle { survey.goto("Stop") }
        rule.onNodeWithText("Start New Survey").performScrollTo().performClick()
        rule.onNodeWithText("Continue without voice", substring = true).assertExists()
        rule.runOnIdle {
            assertFalse(sessions.ttsDisabledFor(sessionKey).value)
            assertFalse(sessions.ttsDisabledFor("survey@1").value)
            assertTrue(checks > checksBeforeResume)
        }
    }

    @Test fun readinessErrorContinueStillAllowsTypedAnswersAndRecordingTranscription() {
        host(TtsVoiceCheck.ERROR)
        continueWithoutVoice()
        rule.runOnIdle { survey.goto("Text") }
        rule.onNode(hasSetTextAction()).performTextReplacement("Typed answer")
        rule.runOnIdle {
            assertEquals("Typed answer", survey.getAnswer("Text"))
            // Same nullable-speaker interlock used by the AI UI when playback is disabled.
            toggleSpeechRecordingWithTtsInterlock(speech, null)
            assertTrue(speech.isRecording.value)
            toggleSpeechRecordingWithTtsInterlock(speech, null)
            assertTrue(speech.isTranscribing.value)
            speech.partialText.value = "Transcribed answer"
            speech.isTranscribing.value = false
        }
        assertSilent()
    }

    @Test fun successfulSwahiliReadinessStillAutoplaysAndReplays() = assertNormalPlayback(false)

    @Test fun successfulEnglishOfflineReadinessStillAutoplaysAndReplays() = assertNormalPlayback(true)

    private fun assertNormalPlayback(offline: Boolean) {
        host(TtsVoiceCheck.OK, offline = offline)
        rule.runOnIdle { assertEquals(1, constructions); assertEquals(listOf("Consent?"), spoken) }
        rule.onNodeWithText("🔊").performClick()
        rule.runOnIdle { assertEquals(listOf("Consent?", "Consent?"), spoken) }
    }

    @Test fun replacingAnExistingSpeakerStopsItAndSuppressesFurtherRequests() {
        host(TtsVoiceCheck.OK)
        // Defensive host transition coverage; the gate offers Continue before the first host mount.
        rule.runOnIdle { sessions.disableTts(sessionKey) }
        rule.waitForIdle()
        io.mockk.verify(atLeast = 1) { speaker.stop() }
        rule.onNodeWithText("🔊").performClick()
        rule.runOnIdle { survey.goto("AI") }
        rule.onNodeWithText("🔊").performClick()
        rule.runOnIdle { assertEquals(1, constructions); assertEquals(listOf("Consent?"), spoken) }
    }

    @Test fun doneRestartClearsTheDecisionForTheNextSelection() {
        host(TtsVoiceCheck.ENGINE_MISSING)
        continueWithoutVoice()
        rule.runOnIdle { survey.goto("Done") }
        rule.onNodeWithText("Start New Survey").performScrollTo().performClick()
        rule.onNodeWithText("Continue without voice", substring = true).assertExists()
        rule.runOnIdle {
            assertEquals("survey@2", sessionKey)
            assertFalse(sessions.ttsDisabledFor(sessionKey).value)
        }
        assertSilent()
    }

    @Test fun yamlDisabledDoesNotCheckOrConstructTts() {
        host(TtsVoiceCheck.OK, yamlEnabled = false)
        rule.onNodeWithText("🔊").performClick()
        rule.runOnIdle { assertEquals(0, checks); assertFalse(sessions.ttsDisabledFor(sessionKey).value) }
        assertSilent()
    }

    private fun config(offline: Boolean, enabled: Boolean) = SurveyConfig(
        graph = SurveyConfig.Graph("Start", listOf(
            NodeDTO("Start", "START", nextId = "Q00"),
            NodeDTO("Q00", "SINGLE_CHOICE", question = "Consent?", options = listOf("Yes", "No"), nextId = "Info"),
            NodeDTO("Info", "INFO", question = "Information", nextId = "Text"),
            NodeDTO("Text", "TEXT", question = "Text question", nextId = "Single"),
            NodeDTO("Single", "SINGLE_CHOICE", question = "Single question", options = listOf("One", "Two"), nextId = "Multi"),
            NodeDTO("Multi", "MULTI_CHOICE", question = "Multi question", options = listOf("One", "Two"), nextId = "Number"),
            NodeDTO("Number", "NUMBER", question = "Number question", nextId = "AI"),
            NodeDTO("AI", "AI", question = "AI question", nextId = "Review"),
            NodeDTO("Stop", "STOP"), NodeDTO("Review", "REVIEW", nextId = "Done"), NodeDTO("Done", "DONE")
        )),
        prompts = listOf(SurveyConfig.Prompt("AI", evalPrompt = "{{ANSWER}}", followupPrompt = "{{QUESTION}}")),
        tts = SurveyConfig.TtsMeta(enabled = enabled, language = if (offline) "en" else "sw",
            engine = "test.missing.engine", requireEngine = !offline, requireOfflineVoice = offline)
    )

    private class FakeSpeech : SpeechController {
        override val isRecording = MutableStateFlow(false)
        override val isTranscribing = MutableStateFlow(false)
        override val partialText = MutableStateFlow("")
        override val errorMessage = MutableStateFlow<String?>(null)
        override fun startRecording() { isRecording.value = true }
        override fun stopRecording() {
            if (isRecording.value) {
                isRecording.value = false
                isTranscribing.value = true
            }
        }
    }
}
