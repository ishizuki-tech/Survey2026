package com.negi.survey.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.platform.app.InstrumentationRegistry
import android.view.KeyEvent
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import com.negi.survey.slm.Repository
import com.negi.survey.slm.RepositoryTraceContext
import com.negi.survey.vm.AiViewModel
import com.negi.survey.vm.FlowHome
import com.negi.survey.vm.QuestionSpeaker
import com.negi.survey.vm.SurveyViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the actual screen with deterministic speech and inference dependencies. */
class AiScreenInteractionTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var survey: SurveyViewModel
    private lateinit var ai: AiViewModel
    private val speech = FakeSpeech()
    private val speaker = FakeSpeaker()
    private val repo = HoldingRepository()
    private var visible by mutableStateOf(true)
    private var node by mutableStateOf("Q8")
    private var autoplay by mutableStateOf(true)
    private var unrelated by mutableStateOf(false)
    private val contextKey get() = "sid=${survey.sessionId.value}|nid=$node"

    private fun host(withSpeech: Boolean = true, restoredFollowup: Boolean = false, twoStep: Boolean = false) {
        rule.setContent {
            val stack = rememberNavBackStack(FlowHome)
            survey = remember { SurveyViewModel(stack, config(restoredFollowup || twoStep)).also {
                it.goto("Q8")
                if (restoredFollowup) it.addFollowupQuestion("Q8", "Restored?")
            } }
            ai = remember { AiViewModel(repo) }
            // Read unrelated state to force an ordinary parent recomposition.
            if (unrelated) androidx.compose.material3.Text("unrelated")
            if (visible) AiScreen(node, survey, ai, { survey.advanceToNext() }, {},
                if (withSpeech) speech else null, speaker, autoplay)
        }
        rule.waitForIdle()
    }

    @Test fun voiceModeSwitchingPreservesDraftAndTranscriptCanBeEdited() {
        host()
        rule.onNodeWithContentDescription("Record answer").assertExists()
        rule.onNodeWithText("Type instead").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("typed draft")
        rule.onNodeWithText("Use voice instead").performClick()
        rule.onNodeWithText("typed draft").performClick()
        rule.onNode(hasSetTextAction()).assertExists()
        rule.runOnIdle { assertEquals("typed draft", ai.conversationStateFlow(contextKey).value.composerDraft) }
        rule.onNodeWithText("Use voice instead").performClick()
        rule.onNodeWithText("Record again").performClick()
        rule.runOnIdle { speech.capture.value = true }
        rule.onNodeWithText("Stop").performClick()
        rule.runOnIdle { speech.transitionToTranscribing() }
        rule.waitForIdle()
        rule.runOnIdle { speech.finish("recognized answer") }
        rule.onNodeWithText("recognized answer").performClick()
        rule.onNode(hasSetTextAction()).performTextInput(" edited")
        rule.runOnIdle { assertTrue(ai.conversationStateFlow(contextKey).value.composerDraft.contains("edited")) }
    }

    @Test fun textOnlyFallbackHasNoVoiceControl() {
        host(withSpeech = false)
        rule.onNode(hasSetTextAction()).performTextInput("answer")
        rule.onNodeWithContentDescription("Record answer").assertDoesNotExist()
        rule.runOnIdle { assertEquals("answer", survey.getAnswer("Q8")) }
    }

    @Test fun sendOnceDisablesComposerDuringInferenceAndReturnsToVoice() {
        host()
        rule.onNodeWithText("Type instead").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("answer")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitUntil(5_000) { repo.requests > 0 }
        rule.onNodeWithContentDescription("Record answer").assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(1, repo.requests)
            assertEquals("answer", survey.getAnswer("Q8"))
        }
    }

    @Test fun completedSubmissionPreservesAnswersAndReturnsToVoiceForFollowup() {
        repo.responses = listOf(
            """{"score":30,"missing_points":["yield loss"],"followup_needed":true}""",
            "How much yield was lost?",
            """{"score":95,"missing_points":[],"followup_needed":false}""",
        )
        host(twoStep = true)
        rule.onNodeWithText("Type instead").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("main answer")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitUntil(5_000) { repo.requests == 1 }
        rule.onNodeWithText("Send").assertIsNotEnabled()
        rule.runOnIdle { assertEquals("main answer", survey.getAnswer("Q8")); repo.release.complete(Unit) }
        rule.waitUntil(5_000) {
            ai.conversationStateFlow(contextKey).value.role == AiViewModel.ComposerRole.FOLLOWUP
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Record answer").assertExists()
        rule.runOnIdle {
            assertEquals(2, repo.requests)
            assertEquals("main answer", survey.getAnswer("Q8"))
            assertEquals("How much yield was lost?", survey.followups.value["Q8"]?.single()?.question)
        }
        rule.onNodeWithText("Type instead").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("two bags")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitUntil(5_000) { ai.conversationStateFlow(contextKey).value.turnCompleted }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Record answer").assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(3, repo.requests)
            assertEquals("main answer", survey.getAnswer("Q8"))
            assertEquals("two bags", survey.followups.value["Q8"]?.single()?.answer)
            assertEquals("Q8", survey.currentNode.value.id)
        }
        rule.onNodeWithText("Next").performClick()
        rule.runOnIdle { assertEquals("Q9", survey.currentNode.value.id) }
    }

    @Test fun stopAndBackAreGuardedUntilControllerReportsTransition() {
        host()
        rule.onNodeWithContentDescription("Record answer").performClick()
        rule.onNodeWithText("Starting microphone…").assertExists()
        rule.onNodeWithText("Send").assertDoesNotExist()
        rule.onNodeWithText("0:00").assertExists()
        rule.runOnIdle { speech.capture.value = true }
        rule.onNodeWithText("Listening…").assertExists()
        rule.onNodeWithText("Stop").performClick()
        rule.onNodeWithText("Stop").assertIsNotEnabled()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.runOnIdle { assertEquals(1, speech.stopCalls); speech.transitionToTranscribing() }
        rule.onNodeWithText("Stop").assertDoesNotExist()
        rule.onNodeWithText("Transcribing…").assertExists()
        rule.onNodeWithText("Send").assertDoesNotExist()
        rule.runOnIdle { speech.finish("first") }
        rule.onNodeWithText("Record again").performClick()
        rule.onNodeWithText("0:00").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.runOnIdle { assertEquals(2, speech.stopCalls) }
    }

    @Test fun leavingQuestionStopsAnActiveRecording() {
        host()
        rule.onNodeWithContentDescription("Record answer").performClick()
        rule.runOnIdle { visible = false }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1, speech.stopCalls) }
    }

    @Test fun replayUsesCurrentPromptAndAutoplayOccursOncePerNewFollowup() {
        host()
        rule.onNodeWithText("🔊").performClick()
        rule.runOnIdle { assertEquals(listOf("Question"), speaker.spoken); speaker.spoken.clear(); ai.setFollowupMode(contextKey, "Follow-up?") }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf("Follow-up?"), speaker.spoken); unrelated = true }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1, speaker.spoken.size) }
        rule.onNodeWithText("🔊").performClick()
        rule.runOnIdle { assertEquals(listOf("Follow-up?", "Follow-up?"), speaker.spoken) }
    }

    @Test fun autoplayRespectsPreferencesAndSpeechState() {
        autoplay = false
        host()
        rule.runOnIdle { ai.setFollowupMode(contextKey, "Disabled?") }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(speaker.spoken.isEmpty()); autoplay = true; speech.recording.value = true }
        rule.waitForIdle()
        rule.runOnIdle { ai.setFollowupMode(contextKey, "Recording?") }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(speaker.spoken.isEmpty()); speech.transitionToTranscribing() }
        rule.waitForIdle()
        rule.runOnIdle { ai.setFollowupMode(contextKey, "Transcribing?") }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(speaker.spoken.isEmpty()); speech.finish("") }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(speaker.spoken.isEmpty()); ai.setFollowupMode(contextKey, "Allowed?") }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf("Allowed?"), speaker.spoken) }
    }

    @Test fun alreadyActiveFollowupDoesNotReplayWhenScreenReturns() {
        host()
        rule.runOnIdle { ai.setFollowupMode(contextKey, "Existing?") }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf("Existing?"), speaker.spoken); speaker.spoken.clear(); visible = false }
        rule.waitForIdle()
        rule.runOnIdle { visible = true }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(speaker.spoken.isEmpty()) }
    }

    @Test fun persistedPendingFollowupIsNotAutoplayedWhenRestored() {
        host(restoredFollowup = true)
        rule.runOnIdle {
            assertEquals("Restored?", ai.conversationStateFlow(contextKey).value.activePromptQuestion)
            assertTrue(speaker.spoken.isEmpty())
        }
    }

    @Test fun oldQuestionFollowupCannotSpeakAfterNavigationOrSessionReset() {
        host()
        val oldKey = contextKey
        rule.runOnIdle {
            survey.goto("Q9")
            node = "Q9"
            ai.setFollowupMode(oldKey, "Old question?")
        }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(speaker.spoken.isEmpty()) }
        val previousKey = contextKey
        rule.runOnIdle {
            survey.resetToStart()
            survey.goto("Q9")
            ai.setFollowupMode(previousKey, "Old session?")
        }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(speaker.spoken.isEmpty()) }
    }

    private class FakeSpeech : SpeechController {
        val recording = MutableStateFlow(false)
        val capture = MutableStateFlow(false)
        val transcribing = MutableStateFlow(false)
        val partial = MutableStateFlow("")
        override val isRecording = recording
        override val isCaptureActive = capture
        override val isTranscribing = transcribing
        override val partialText = partial
        override val errorMessage = MutableStateFlow<String?>(null)
        var stopCalls = 0
        override fun startRecording() { recording.value = true }
        override fun stopRecording() { stopCalls++ }
        fun transitionToTranscribing() { recording.value = false; capture.value = false; transcribing.value = true }
        fun finish(text: String) { partial.value = text; transcribing.value = false }
    }
    private class FakeSpeaker : QuestionSpeaker {
        override val isSpeaking = MutableStateFlow(false)
        override val isReady = MutableStateFlow(true)
        override val errorMessage = MutableStateFlow<String?>(null)
        val spoken = mutableListOf<String>()
        override fun speak(text: String, utteranceId: String) { spoken += text }
        override fun stop() = Unit
    }
    private class HoldingRepository : Repository {
        @Volatile var requests = 0
        var responses: List<String>? = null
        val release = CompletableDeferred<Unit>()
        override fun buildPrompt(userPrompt: String) = userPrompt
        override suspend fun request(prompt: String, traceContext: RepositoryTraceContext?): Flow<String> = flow {
            val index = requests++
            val script = responses ?: awaitCancellation()
            if (index == 0) release.await()
            emit(script[index])
        }
    }
    private fun config(twoStep: Boolean) = SurveyConfig(
        graph = SurveyConfig.Graph(startId = "Start", nodes = listOf(
            NodeDTO(id = "Start", type = "START", nextId = "Q8"),
            NodeDTO(id = "Q8", type = "AI", question = "Question", nextId = "Q9"),
            NodeDTO(id = "Q9", type = "AI", question = "Other question", nextId = "Done"),
            NodeDTO(id = "Done", type = "DONE"),
        )),
        prompts = listOf(if (twoStep) {
            SurveyConfig.Prompt(nodeId = "Q8", evalPrompt = "{{QUESTION}} {{ANSWER}}", followupPrompt = "{{QUESTION}} {{EVAL_JSON}}")
        } else SurveyConfig.Prompt(nodeId = "Q8", prompt = "{{QUESTION}} {{ANSWER}}")),
    )
}
