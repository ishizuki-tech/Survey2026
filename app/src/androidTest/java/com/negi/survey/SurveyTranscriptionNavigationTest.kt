package com.negi.survey

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.net.V2ArtifactStore
import com.negi.survey.net.SurveyRecordings
import com.negi.survey.net.TranscriptionOutcome
import com.negi.survey.vm.RecordingPrompt
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import com.negi.survey.runtime.HeavyRuntimeHandoff
import com.negi.survey.runtime.NoOpHeavyRuntimeHandoff
import com.negi.survey.screens.SpeechController
import com.negi.survey.slm.Repository
import com.negi.survey.slm.RepositoryTraceContext
import com.negi.survey.ui.theme.SurveyNavTheme
import com.negi.survey.vm.AiViewModel
import com.negi.survey.vm.FlowHome
import com.negi.survey.vm.SurveyFinalizationStatePolicy
import com.negi.survey.vm.SurveyFinalizationViewModel
import com.negi.survey.vm.SurveySessionStore
import com.negi.survey.vm.SurveyViewModel
import com.negi.survey.vm.WhisperSessionGate
import com.negi.survey.vm.WhisperSpeechController
import com.negi.survey.whisper.RecorderBackend
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SurveyTranscriptionNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val speech = ControlledSpeech()
    private val fixtureVoiceExports = ConcurrentLinkedQueue<String>()
    private lateinit var survey: SurveyViewModel
    private var exits = 0
    private var rootBacks = 0

    @After
    fun removeFixtureExports() {
        val store = V2ArtifactStore(instrumentation.targetContext)
        store.artifactIds().mapNotNull(store::read)
            .filter { it.route.basename in fixtureVoiceExports }
            .forEach { it.directory.deleteRecursively() }
        if (::survey.isInitialized) {
            pendingExport()?.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun activeRecordingSurvivesRepeatedActivityRecreationUntilExplicitStop() {
        val sessionKey = "recording-recreation"
        val whisperMeta = SurveyConfig.WhisperMeta(assetModelPath = "rotation-fixture", language = "en")
        val controllerKey = "WhisperSpeechController_${sessionKey}_rotation-fixture_en"
        val scenario = rule.activityRule.scenario
        val recorders = ConcurrentLinkedQueue<FixtureWavRecorder>()
        val transcriptionCalls = AtomicInteger()
        val factory = viewModelFactory {
            initializer {
                WhisperSpeechController(
                    appContext = instrumentation.targetContext.applicationContext,
                    recorderFactory = { _, _ -> FixtureWavRecorder().also { recorders.add(it) } },
                    recorderSessionMutex = Mutex(),
                    whisperSessionGate = WhisperSessionGate(),
                    modelInitializer = {},
                    whisperDetacher = {},
                    onVoiceExported = { fixtureVoiceExports.add(it.fileName) },
                    transcriber = { _, _ ->
                        transcriptionCalls.incrementAndGet()
                        Result.success("Recorded across recreation")
                    }
                )
            }
        }
        val config = SurveyConfig(graph = SurveyConfig.Graph(
            startId = "Start",
            nodes = listOf(NodeDTO("Start", "START", title = "Survey Note", nextId = "Done"),
                NodeDTO("Done", "DONE"))
        ))

        fun mountHost(): WhisperSpeechController {
            lateinit var retainedController: WhisperSpeechController
            scenario.onActivity { activity ->
                val sessions = ViewModelProvider(activity)[SurveySessionStore::class.java]
                val owner = sessions.ownerFor(sessionKey)
                retainedController = ViewModelProvider(owner, factory)[controllerKey, WhisperSpeechController::class.java]
                activity.setContent {
                    val nav = rememberNavBackStack(FlowHome)
                    val survey = remember { SurveyViewModel(nav, config) }
                    val ai = remember { AiViewModel(UnusedRepository) }
                    SurveyNavTheme {
                        SurveyNavHost(
                            vmSurvey = survey,
                            vmAI = ai,
                            backStack = nav,
                            whisperMeta = whisperMeta,
                            ttsMeta = SurveyConfig.TtsMeta(enabled = false),
                            sessionId = sessionKey,
                            sessionVmOwner = owner,
                            onResolveUploadStatusDetails = { error("Unused") },
                            onClearCompletedSurveys = {}
                        )
                    }
                }
            }
            return retainedController
        }

        val controller = mountHost()
        rule.onNodeWithContentDescription("Start recording").performClick()
        rule.waitUntil(5_000L) { controller.isCaptureActive.value }
        val recorder = recorders.single()

        repeat(2) {
            scenario.recreate()
            assertSame(controller, mountHost())
            rule.onNodeWithContentDescription("Stop recording").assertIsEnabled()
            assertTrue(controller.isCaptureActive.value)
            assertFalse(controller.isTranscribing.value)
            assertSame(recorder, recorders.single())
            assertEquals(0, recorder.stopCalls.get())
            assertEquals(0, transcriptionCalls.get())
        }

        rule.onNodeWithContentDescription("Stop recording").performClick()
        rule.waitUntil(10_000L) {
            !controller.isTranscribing.value && controller.partialText.value == "Recorded across recreation"
        }
        assertEquals(1, recorder.stopCalls.get())
        assertEquals(1, transcriptionCalls.get())

        rule.onNodeWithContentDescription("Start recording").performClick()
        rule.waitUntil(5_000L) { controller.isCaptureActive.value }
        val recorderAtSessionEnd = recorders.last()
        scenario.onActivity { activity ->
            ViewModelProvider(activity)[SurveySessionStore::class.java].clearSession(sessionKey)
        }
        rule.waitUntil(5_000L) { recorderAtSessionEnd.closed.isCompleted }
        assertEquals(0, recorderAtSessionEnd.stopCalls.get())
        assertEquals(1, transcriptionCalls.get())
    }

    @Test
    fun surveyNoteDropsStaleButtonsImeAndRootBackUntilSuccessfulTranscription() {
        showSurvey()
        rule.onNode(hasSetTextAction()).performTextReplacement("Original note")
        rule.runOnIdle { speech.startRecording() }
        val next = clickAction("Next")
        val exit = clickAction("Exit")
        rule.runOnIdle {
            speech.stopRecording()
            next()
            exit()
            rule.activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(0, rootBacks)
        }
        assertDisabled("Next", "Exit")
        rule.onNodeWithText("Transcribing…").assertExists()
        rule.onNode(hasSetTextAction()).performImeAction()
        pressSystemBack()
        rule.runOnIdle {
            assertEquals("Start", survey.currentNodeId)
            assertEquals(0, rootBacks)
            assertNull(pendingExport())
            speech.complete("Dictated note")
        }
        assertEnabled("Next", "Exit")
        rule.onNodeWithText("Original note\nDictated note").assertExists()
        rule.runOnIdle {
            assertEquals("Start", survey.currentNodeId)
            assertEquals(1, speech.stops)
        }
        pressSystemBack()
        rule.runOnIdle { assertEquals(1, rootBacks) }
        rule.onNodeWithText("Next").performClick()
        rule.runOnIdle { assertEquals("Info", survey.currentNodeId) }
    }

    @Test
    fun ordinaryAndReviewActionsAreBlockedBeforeTheirSideEffects() {
        showSurvey(startAt = "Info")
        rule.runOnIdle { speech.isTranscribing.value = true }
        assertDisabled("Back", "Next")
        pressSystemBack()
        rule.runOnIdle {
            assertEquals("Info", survey.currentNodeId)
            speech.complete()
        }
        assertEnabled("Back", "Next")
        rule.onNodeWithText("Next").performClick()
        assertEnabled("Back", "Finish")
        val finish = clickAction("Finish")
        val back = clickAction("Back")
        rule.runOnIdle {
            speech.isTranscribing.value = true
            repeat(2) {
                finish()
                back()
            }
        }
        assertDisabled("Back", "Finish")
        pressSystemBack()
        rule.runOnIdle {
            assertEquals("Review", survey.currentNodeId)
            assertNull(pendingExport())
            speech.complete(error = "Whisper failed")
        }
        assertEnabled("Back", "Finish")
        rule.runOnIdle {
            assertEquals("Review", survey.currentNodeId)
            assertNull(pendingExport())
        }
        rule.onNodeWithText("Back").performClick()
        rule.runOnIdle { assertEquals("Info", survey.currentNodeId) }
    }

    @Test
    fun acceptedFinishWaitsForAFreshActionIfItsRouteTransitionWasBlocked() {
        showSurvey(startAt = "Review")
        val finish = clickAction("Finish")
        val finalization = rule.runOnIdle {
            val vm = ViewModelProvider(rule.activity)[
                "SurveyFinalizationViewModel_session", SurveyFinalizationViewModel::class.java
            ]
            finish()
            speech.isTranscribing.value = true
            vm
        }
        rule.waitUntil(10_000L) { SurveyFinalizationStatePolicy.isAccepted(finalization.state.value) }
        assertDisabled("Back", "Finish")
        val export = checkNotNull(pendingExport())
        val original = export.readText()
        rule.runOnIdle {
            assertEquals("Review", survey.currentNodeId)
            speech.complete()
        }
        assertEnabled("Finish")
        rule.runOnIdle { assertEquals("Review", survey.currentNodeId) }
        rule.onNodeWithText("Finish").performClick()
        rule.onNodeWithText("Start New Survey").assertExists()
        rule.runOnIdle {
            assertEquals("Done", survey.currentNodeId)
            assertEquals(export, pendingExport())
            assertEquals(original, export.readText())
        }
    }

    @Test
    fun predictiveBackStartedWhileLockedIsDiscardedEvenIfWhisperFinishesBeforeRelease() {
        showSurvey(startAt = "Info")
        rule.runOnIdle { speech.isTranscribing.value = true }
        assertDisabled("Back", "Next")
        val dispatcher = rule.activity.onBackPressedDispatcher
        rule.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 250f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 250f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        rule.runOnIdle { speech.complete() }
        assertEnabled("Back", "Next")
        rule.runOnIdle { dispatcher.onBackPressed() }
        rule.runOnIdle { assertEquals("Info", survey.currentNodeId) }
        pressSystemBack()
        rule.runOnIdle { assertEquals("Start", survey.currentNodeId) }
    }

    @Test
    fun aiSystemBackStopsRecordingAndFailureRestoresNavigationWithoutLeaving() {
        showSurvey(startAt = "AI")
        val next = clickAction("Next")
        val back = clickAction("Back")
        rule.runOnIdle { speech.startRecording() }
        pressSystemBack()
        rule.runOnIdle {
            assertTrue(speech.isTranscribing.value)
            assertEquals("AI", survey.currentNodeId)
            next()
            back()
        }
        assertDisabled("Back", "Next")
        val status = rule.onNodeWithText("Transcribing…")
        val details = rule.onNodeWithText("Converting speech to text")
        status.assertExists()
        details.assertExists()
        rule.onNodeWithContentDescription("Record answer").assertDoesNotExist()
        pressSystemBack()
        rule.runOnIdle {
            assertEquals("AI", survey.currentNodeId)
            assertEquals(1, speech.stops)
            speech.complete(error = "Whisper failed")
        }
        status.assertDoesNotExist()
        details.assertDoesNotExist()
        rule.onNodeWithText("Whisper failed").assertExists()
        rule.onNodeWithContentDescription("Record answer").assertIsEnabled()
        assertEnabled("Back", "Next")
        rule.runOnIdle { assertEquals("AI", survey.currentNodeId) }
        pressSystemBack()
        rule.runOnIdle { assertEquals("Start", survey.currentNodeId) }
    }

    @Test
    fun scriptedTranscriptionFailureUnlocksNavigationAfterCleanupAndAllowsAnotherRecording() {
        val failure = "scripted transcription failure"
        val transcriberEntered = CompletableDeferred<Unit>()
        val failTranscription = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val controller = WhisperSpeechController(
            appContext = instrumentation.targetContext.applicationContext,
            recorderFactory = { _, _ -> FixtureWavRecorder() },
            recorderSessionMutex = Mutex(),
            whisperSessionGate = WhisperSessionGate(),
            modelInitializer = {},
            onVoiceExported = { fixtureVoiceExports.add(it.fileName) },
            transcriber = { _, _ ->
                if (transcriberEntered.complete(Unit)) {
                    failTranscription.await()
                    Result.failure(IllegalStateException(failure))
                } else {
                    Result.success("Recovered answer")
                }
            },
            heavyRuntimeHandoff = object : HeavyRuntimeHandoff by NoOpHeavyRuntimeHandoff {
                override suspend fun releaseWhisper() {
                    cleanupEntered.complete(Unit)
                    finishCleanup.await()
                }
            },
        )

        try {
            showSurvey(startAt = "AI", speechController = controller)
            val staleBack = clickAction("Back")
            val staleNext = clickAction("Next")
            rule.runOnIdle {
                controller.startRecording(RecordingPrompt(
                    survey.surveyUuid.value, "AI", RecordingPrompt.Kind.ORIGINAL, "Tell me more",
                ))
            }
            rule.waitUntil(5_000L) { controller.isCaptureActive.value }
            rule.runOnIdle { controller.stopRecording() }
            rule.waitUntil(5_000L) { transcriberEntered.isCompleted }

            assertDisabled("Back", "Next")
            rule.onNodeWithText("Transcribing…").assertExists()
            rule.runOnIdle {
                assertTrue(controller.isTranscribing.value)
                staleBack()
                staleNext()
                survey.backToPrevious()
                survey.advanceToNext()
                assertEquals("AI", survey.currentNodeId)
            }
            pressSystemBack()
            rule.runOnIdle {
                assertEquals("AI", survey.currentNodeId)
                failTranscription.complete(Unit)
            }
            rule.waitUntil(5_000L) { cleanupEntered.isCompleted }

            rule.onNodeWithText(failure).assertExists()
            rule.onNodeWithText("Transcribing…").assertExists()
            assertDisabled("Back", "Next")
            rule.runOnIdle {
                assertEquals(failure, controller.errorMessage.value)
                assertTrue(controller.isTranscribing.value)
                assertEquals("AI", survey.currentNodeId)
                finishCleanup.complete(Unit)
            }
            rule.waitUntil(5_000L) { !controller.isTranscribing.value }

            assertEnabled("Back", "Next")
            rule.onNodeWithText(failure).assertExists()
            rule.runOnIdle {
                assertEquals("AI", survey.currentNodeId)
                assertTrue(controller.partialText.value.isEmpty())
            }
            rule.onNodeWithText("Next").performClick()
            rule.runOnIdle { assertEquals("Review", survey.currentNodeId) }
            rule.onNodeWithText("Back").performClick()
            rule.runOnIdle { assertEquals("AI", survey.currentNodeId) }
            rule.onNodeWithText("Back").performClick()
            rule.runOnIdle { assertEquals("Start", survey.currentNodeId) }

            rule.runOnIdle { survey.goto("AI") }
            assertEnabled("Next")
            rule.runOnIdle {
                controller.startRecording(RecordingPrompt(
                    survey.surveyUuid.value, "AI", RecordingPrompt.Kind.ORIGINAL, "Tell me more",
                ))
            }
            rule.waitUntil(5_000L) { controller.isCaptureActive.value }
            rule.runOnIdle {
                assertTrue(controller.isRecording.value)
                assertFalse(controller.isTranscribing.value)
                assertNull(controller.errorMessage.value)
                controller.stopRecording()
            }
            rule.waitUntil(5_000L) {
                !controller.isTranscribing.value && controller.partialText.value == "Recovered answer"
            }
            assertEnabled("Back", "Next")
            val id = survey.surveyUuid.value
            val store = V2ArtifactStore(instrumentation.targetContext)
            val recordings = SurveyRecordings(store).inventory(store.paths(instrumentation.targetContext, id), V2ArtifactStore.profile)
            assertEquals(2, recordings.size)
            assertEquals(setOf(TranscriptionOutcome.Status.FAILED, TranscriptionOutcome.Status.SUCCESS),
                recordings.map { it.outcome.status }.toSet())
            assertTrue(recordings.all { it.identity.prompt.text == "Tell me more" })
        } finally {
            failTranscription.complete(Unit)
            finishCleanup.complete(Unit)
        }
    }

    @Test
    fun aiSuccessfulTranscriptIsAppliedBeforeTheNextExplicitAction() {
        showSurvey(startAt = "AI")
        rule.runOnIdle {
            speech.startRecording()
            speech.stopRecording()
        }
        assertDisabled("Back", "Next")
        rule.runOnIdle { speech.complete("Dictated answer") }
        assertEnabled("Back", "Next")
        rule.runOnIdle {
            assertEquals("AI", survey.currentNodeId)
            assertEquals("Dictated answer", survey.getAnswer("AI"))
        }
        rule.onNodeWithText("Next").performClick()
        rule.runOnIdle { assertEquals("Review", survey.currentNodeId) }
    }

    @Test
    fun nextAcceptedDuringRecordingWaitsPastEightSecondsAndUsesSettledNote() {
        showSurvey()
        rule.onNode(hasSetTextAction()).performTextReplacement("Original note")
        rule.runOnIdle { speech.startRecording() }
        rule.onNodeWithText("Next").performClick()
        assertDisabled("Next", "Exit")
        rule.onNodeWithText("Finalizing voice note…").assertExists()

        // The production watchdog uses elapsed realtime. Let it fire while Whisper is active.
        val deadline = SystemClock.elapsedRealtime() + 8_500L
        rule.waitUntil(12_000L) { SystemClock.elapsedRealtime() >= deadline }
        assertDisabled("Next", "Exit")
        rule.runOnIdle {
            assertEquals("Start", survey.currentNodeId)
            assertNull(pendingExport())
            speech.complete("Settled note")
        }
        rule.onNodeWithText("Information").assertExists()
        rule.runOnIdle {
            assertEquals("Info", survey.currentNodeId)
            assertEquals("Original note\nSettled note", survey.exportExtraMeta()["session_free_text"])
            assertEquals(1, speech.stops)
        }
    }

    @Test
    fun exitAcceptedDuringRecordingSavesExistingNoteAfterTerminalFailure() {
        showSurvey()
        rule.onNode(hasSetTextAction()).performTextReplacement("Keep this note")
        rule.runOnIdle { speech.startRecording() }
        rule.onNodeWithText("Exit").performClick()
        assertDisabled("Next", "Exit")
        rule.runOnIdle {
            assertNull(pendingExport())
            assertEquals(0, exits)
            speech.complete(error = "Whisper failed")
        }
        rule.waitUntil(10_000L) { exits == 1 }
        rule.runOnIdle {
            val json = checkNotNull(pendingExport()).readText()
            assertTrue(json.contains("Keep this note"))
            assertTrue(json.contains("incomplete"))
            assertEquals(1, speech.stops)
        }
    }

    @Test
    fun acceptedExitRechecksTheLockAfterSuspendingAndCanBeRetried() {
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        showSurvey(onEndSession = {
            cleanupStarted.complete(Unit)
            allowCleanup.await()
        })
        rule.onNodeWithText("Exit").performClick()
        rule.waitUntil(10_000L) { cleanupStarted.isCompleted }
        rule.runOnIdle {
            speech.isTranscribing.value = true
            allowCleanup.complete(Unit)
        }
        rule.onNodeWithText("Could not safely end the survey. Press Exit to try again.").assertExists()
        assertDisabled("Exit")
        rule.runOnIdle {
            assertEquals(0, exits)
            assertTrue(pendingExport() != null)
            speech.complete()
        }
        assertEnabled("Exit")
        rule.runOnIdle { assertEquals(0, exits) }
        rule.onNodeWithText("Exit").performClick()
        rule.waitUntil(10_000L) { exits == 1 }
    }

    private fun showSurvey(
        startAt: String = "Start",
        onEndSession: (suspend () -> Unit)? = null,
        speechController: SpeechController = speech,
    ) {
        rule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { rootBacks++ }
            })
        }
        val config = SurveyConfig(graph = SurveyConfig.Graph(
            startId = "Start",
            nodes = listOf(
                NodeDTO("Start", "START", title = "Survey Note", nextId = "Info"),
                NodeDTO("Info", "INFO", title = "Information", nextId = "Review"),
                NodeDTO("AI", "AI", question = "Tell me more", nextId = "Review"),
                NodeDTO("Review", "REVIEW", nextId = "Done"),
                NodeDTO("Done", "DONE")
            )
        ))
        rule.setContent {
            val nav = rememberNavBackStack(FlowHome)
            survey = remember { SurveyViewModel(nav, config).apply { if (startAt != "Start") goto(startAt) } }
            val ai = remember { AiViewModel(UnusedRepository) }
            SurveyNavTheme {
                SurveyNavHost(
                    vmSurvey = survey,
                    vmAI = ai,
                    backStack = nav,
                    onResetToSelector = { exits++ },
                    onEndSurveySession = onEndSession,
                    ttsMeta = SurveyConfig.TtsMeta(enabled = false),
                    onResolveUploadStatusDetails = { error("Unused") },
                    onClearCompletedSurveys = {},
                    speechControllerOverride = speechController
                )
            }
        }
    }

    private fun clickAction(label: String): () -> Boolean =
        checkNotNull(rule.onNodeWithText(label).fetchSemanticsNode().config[SemanticsActions.OnClick].action)

    private fun assertEnabled(vararg labels: String) {
        labels.forEach { rule.onNodeWithText(it).assertIsEnabled() }
    }

    private fun assertDisabled(vararg labels: String) {
        labels.forEach { rule.onNodeWithText(it).assertIsNotEnabled() }
    }

    private fun pressSystemBack() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    private fun pendingExport() = V2ArtifactStore(instrumentation.targetContext)
        .read(V2ArtifactStore.surveyKey(survey.surveyUuid.value))?.payload

    private inner class FixtureWavRecorder : RecorderBackend {
        val stopCalls = AtomicInteger()
        val closed = CompletableDeferred<Unit>()
        private lateinit var output: File
        private var active = false
        private var onCaptureStarted: () -> Unit = {}

        override fun setOnCaptureStarted(callback: () -> Unit) {
            onCaptureStarted = callback
        }

        override fun isActive() = active

        override fun startRecording(output: File, rates: IntArray) {
            this.output = output
            active = true
            onCaptureStarted()
        }

        override suspend fun stopRecording() {
            stopCalls.incrementAndGet()
            active = false
            instrumentation.context.assets.open("whisper/jfk.wav").use { fixture ->
                output.outputStream().use { fixture.copyTo(it) }
            }
        }

        override fun close() {
            active = false
            closed.complete(Unit)
        }
    }

    private class ControlledSpeech : SpeechController {
        override val isRecording = MutableStateFlow(false)
        override val isTranscribing = MutableStateFlow(false)
        override val partialText = MutableStateFlow("")
        override val errorMessage = MutableStateFlow<String?>(null)
        var stops = 0
            private set

        override fun startRecording() {
            partialText.value = ""
            errorMessage.value = null
            isRecording.value = true
        }

        override fun stopRecording() {
            if (!isRecording.value) return
            stops++
            isTranscribing.value = true
            isRecording.value = false
        }

        fun complete(text: String = "", error: String? = null) {
            partialText.value = text
            errorMessage.value = error
            isTranscribing.value = false
        }
    }

    private object UnusedRepository : Repository {
        override fun buildPrompt(userPrompt: String) = userPrompt
        override suspend fun request(prompt: String, traceContext: RepositoryTraceContext?): Flow<String> =
            error("Unused")
    }
}
