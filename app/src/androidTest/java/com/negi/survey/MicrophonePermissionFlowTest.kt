package com.negi.survey

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.rememberNavBackStack
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import com.negi.survey.screens.SpeechController
import com.negi.survey.slm.Repository
import com.negi.survey.slm.RepositoryTraceContext
import com.negi.survey.ui.theme.SurveyNavTheme
import com.negi.survey.vm.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Controlled Android authority; no runtime permission or device settings mutations. */
class MicrophonePermissionFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var granted = false
    private var requests = 0
    private var settings = 0
    private lateinit var permission: MicrophonePermissionState
    private lateinit var survey: SurveyViewModel
    private lateinit var firstSurvey: SurveyViewModel
    private val speech = FakeSpeech()
    private var ttsDisabled = false

    private fun mount(voice: Boolean = true, androidPermission: Boolean = false) {
        rule.activityRule.scenario.onActivity { activity ->
            val sessions = ViewModelProvider(activity)[SurveySessionStore::class.java]
            val owner = sessions.ownerFor("mic-test@1")
            if (ttsDisabled) sessions.disableTts("mic-test@1")
            val config = SurveyConfig(graph = SurveyConfig.Graph("Start", listOf(
                NodeDTO("Start", "START", title = "Survey Note", question = "Add notes", nextId = "Q1"),
                NodeDTO("Q1", "TEXT", question = "Typed question", nextId = "Review"),
                NodeDTO("Review", "REVIEW", nextId = "Done"),
                NodeDTO("Done", "DONE"),
            )), whisper = SurveyConfig.WhisperMeta(enabled = voice))
            fun <T : ViewModel> retained(key: String, type: Class<T>, make: () -> T): T =
                ViewModelProvider(owner, object : ViewModelProvider.Factory {
                    override fun <V : ViewModel> create(modelClass: Class<V>): V {
                        @Suppress("UNCHECKED_CAST") return make() as V
                    }
                })[key, type]
            activity.setContent {
                val nav = rememberNavBackStack(FlowHome)
                survey = retained("survey", SurveyViewModel::class.java) { SurveyViewModel(nav, config) }
                val ai = retained("ai", AiViewModel::class.java) { AiViewModel(object : Repository {
                    override fun buildPrompt(userPrompt: String) = userPrompt
                    override suspend fun request(prompt: String, traceContext: RepositoryTraceContext?) = flowOf("")
                }) }
                permission = if (androidPermission) rememberMicrophonePermissionState() else remember {
                    MicrophonePermissionState(granted, { granted }, { requests++ }, { settings++ })
                }
                androidx.compose.runtime.SideEffect { survey.attachNavigation(nav) }
                SurveyNavTheme {
                    SurveyNavHost(survey, ai, nav,
                        sessionId = "mic-test@1", sessionVmOwner = owner,
                        whisperMeta = config.whisper, ttsMeta = SurveyConfig.TtsMeta(enabled = false),
                        sessionTtsDisabled = ttsDisabled,
                        speechControllerOverride = speech,
                        microphonePermission = permission.takeIf { voice },
                        onResolveUploadStatusDetails = { error("Unused") }, onClearCompletedSurveys = {})
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun denialKeepsHostAndTypedNavigationThroughReviewWithoutPermissionLoop() {
        mount()
        firstSurvey = survey
        val uuid = survey.surveyUuid.value
        rule.onNode(hasSetTextAction()).performTextReplacement("typed note")
        rule.onNodeWithContentDescription("Start recording").assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, requests); assertEquals(0, speech.starts) }
        rule.onNodeWithText("Allow microphone").performClick()
        rule.onNodeWithText("Open app settings").performClick()
        rule.onNodeWithText("Next").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("retained answer")
        rule.runOnIdle { granted = true; permission.refresh() }
        rule.runOnIdle { granted = false; permission.refresh() }
        rule.onNodeWithText("Back").performClick()
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Finish").assertIsEnabled()
        rule.runOnIdle {
            assertSame(firstSurvey, survey)
            assertEquals(uuid, survey.surveyUuid.value)
            assertEquals("retained answer", survey.getAnswer("Q1"))
            assertTrue(survey.getAudioRefsForRunFlat().isEmpty())
            assertEquals(1, requests)
            assertEquals(1, settings)
            assertEquals(0, speech.starts)
        }
    }

    @Test fun deniedAndRegrantedActivityRecreationPreservesSessionAnswersAndTtsPolicy() {
        ttsDisabled = true
        mount()
        firstSurvey = survey
        rule.runOnIdle { survey.advanceToNext(); survey.setAnswer("saved", "Q1") }
        val uuid = survey.surveyUuid.value
        rule.activityRule.scenario.recreate()
        mount()
        rule.runOnIdle {
            assertSame(firstSurvey, survey)
            assertEquals(uuid, survey.surveyUuid.value)
            assertEquals("Q1", survey.currentNodeId)
            assertEquals("saved", survey.getAnswer("Q1"))
            granted = true
            permission.refresh()
        }
        rule.activityRule.scenario.recreate()
        mount()
        rule.runOnIdle {
            assertSame(firstSurvey, survey)
            assertEquals(uuid, survey.surveyUuid.value)
            assertEquals("saved", survey.getAnswer("Q1"))
            assertTrue(permission.granted)
            assertTrue(ViewModelProvider(rule.activity)[SurveySessionStore::class.java]
                .ttsDisabledFor("mic-test@1").value)
            assertEquals(0, speech.starts)
            assertEquals(0, requests)
        }
    }

    @Test fun androidPermissionGrantRefreshesOnResumeAndSurvivesRecreationWithoutReset() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        requireIssue124PermissionMutation(packageName)
        org.junit.Assume.assumeTrue(androidx.core.content.ContextCompat.checkSelfPermission(
            instrumentation.targetContext, android.Manifest.permission.RECORD_AUDIO
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED)
        mount(androidPermission = true)
        firstSurvey = survey
        rule.runOnIdle {
            assertFalse(permission.granted)
            survey.advanceToNext()
            survey.setAnswer("kept across Android grant", "Q1")
        }
        val uuid = survey.surveyUuid.value
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        instrumentation.uiAutomation.grantRuntimePermission(packageName, android.Manifest.permission.RECORD_AUDIO)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        rule.waitUntil(5_000) { permission.granted }
        rule.activityRule.scenario.recreate()
        mount(androidPermission = true)
        rule.runOnIdle {
            assertTrue(permission.granted)
            assertSame(firstSurvey, survey)
            assertEquals(uuid, survey.surveyUuid.value)
            assertEquals("Q1", survey.currentNodeId)
            assertEquals("kept across Android grant", survey.getAnswer("Q1"))
            assertEquals(0, speech.starts)
        }
    }

    private fun requireIssue124PermissionMutation(packageName: String) {
        // This mutation test is opt-in through the dedicated package. Other variants
        // still execute the ordinary, non-mutating permission-flow tests in this class.
        org.junit.Assume.assumeTrue("Requires the isolated Issue #124 package",
            packageName == "com.negi.survey.local.issue124")
        check(packageName == "com.negi.survey.local.issue124") { "Requires the isolated Issue #124 package" }
    }

    /** Exercises the exact opt-in gate without installing or mutating a second package. */
    @Test fun unsupportedPackageReportsAssumptionSkipBeforePermissionMutation() {
        requireIssue124PermissionMutation("com.negi.survey.local.uitest")
        fail("Unsupported package must skip before any permission mutation")
    }

    @Test fun whisperDisabledNeverShowsPermissionActions() {
        mount(voice = false)
        rule.onNode(hasSetTextAction()).assertExists()
        rule.onNodeWithText("Allow microphone").assertDoesNotExist()
        rule.onNodeWithText("Open app settings").assertDoesNotExist()
        rule.runOnIdle { assertEquals(0, requests); assertEquals(0, speech.starts) }
    }

    private class FakeSpeech : SpeechController {
        override val isRecording = MutableStateFlow(false)
        override val isTranscribing = MutableStateFlow(false)
        override val partialText = MutableStateFlow("")
        override val errorMessage = MutableStateFlow<String?>(null)
        var starts = 0
        override fun startRecording() { starts++ }
        override fun stopRecording() = Unit
    }
}
