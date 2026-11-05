package com.negi.survey.screens

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
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
import com.negi.survey.vm.NodeType
import com.negi.survey.vm.QuestionSpeaker
import com.negi.survey.vm.SurveyAiReason
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
    private var autoplay by mutableStateOf(true)
    private var speakerAvailable by mutableStateOf(true)
    private var unrelated by mutableStateOf(false)
    private val contextKey get() = "sid=${survey.sessionId.value}|nid=${survey.currentNodeId}"

    private fun host(
        withSpeech: Boolean = true,
        twoStep: Boolean = false,
        readAloud: Boolean = true,
        seed: SurveyViewModel.() -> Unit = {}
    ) {
        rule.setContent {
            val stack = rememberNavBackStack(FlowHome)
            survey = remember { SurveyViewModel(stack, config(twoStep, readAloud)).apply {
                goto("Q8")
                seed()
            } }
            ai = remember { AiViewModel(repo) }
            val node by survey.currentNode.collectAsState()
            // Read unrelated state to force an ordinary parent recomposition.
            if (unrelated) androidx.compose.material3.Text("unrelated")
            if (visible && node.type == NodeType.AI) {
                AiScreen(node.id, survey, ai, survey::advanceToNext, survey::backToPrevious,
                    if (withSpeech) speech else null, speaker.takeIf { speakerAvailable }, autoplay)
            }
        }
        rule.waitForIdle()
    }

    private fun addFollowup(question: String) {
        survey.addFollowupQuestion("Q8", question)
        ai.setFollowupMode(contextKey, question)
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
            assertEquals(listOf("Question", "How much yield was lost?"), speaker.spoken)
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
        rule.onNode(hasText("Starting microphone…") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        rule.onNodeWithText("Send").assertDoesNotExist()
        rule.onNodeWithText("0:00").assertExists()
        rule.runOnIdle { speech.capture.value = true }
        rule.onNode(hasText("Listening…") and hasAnyAncestor(isDialog())).assertIsDisplayed()
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

    @Test fun firstVisitAndNewFollowupEachPlayOnceAndManualReplayUsesSavedPendingPrompt() {
        host()
        rule.runOnIdle {
            assertEquals(listOf("Question"), speaker.spoken)
            survey.setAnswer("Main answer", "Q8")
            addFollowup("Follow-up?")
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf("Question", "Follow-up?"), speaker.spoken)
            ai.setFollowupMode(contextKey, "Stale composer prompt?")
            unrelated = true
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(2, speaker.spoken.size) }
        rule.onNodeWithText("🔊").performClick()
        rule.runOnIdle {
            assertEquals(listOf("Question", "Follow-up?", "Follow-up?"), speaker.spoken)
            survey.answerLastFollowup("Q8", "Follow-up answer")
        }
        rule.onNodeWithText("🔊").performClick()
        rule.runOnIdle { assertEquals("Question", speaker.spoken.last()) }
    }

    @Test fun autoplayRespectsPreferencesAndSpeechState() {
        autoplay = false
        host()
        rule.runOnIdle {
            assertTrue(speaker.spoken.isEmpty())
            autoplay = true
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf("Question"), speaker.spoken)
            speech.recording.value = true
            addFollowup("Deferred follow-up?")
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf("Question"), speaker.spoken)
            speech.transitionToTranscribing()
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf("Question"), speaker.spoken)
            speech.finish("")
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf("Question", "Deferred follow-up?"), speaker.spoken) }
    }

    @Test fun disabledTtsSuppressesOriginalAndFollowupPlayback() {
        speakerAvailable = false
        host()
        rule.runOnIdle {
            assertTrue(speaker.spoken.isEmpty())
            addFollowup("Follow-up?")
        }
        rule.waitForIdle()
        rule.onNodeWithText("🔊").performClick()
        rule.runOnIdle {
            assertTrue(speaker.spoken.isEmpty())
            speakerAvailable = true
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf("Follow-up?"), speaker.spoken) }
    }

    @Test fun readinessPlaysOnceAndLaterReadinessOrEligibilityChangesDoNotReplay() {
        speaker.isReady.value = false
        host()
        rule.runOnIdle {
            assertTrue(speaker.spoken.isEmpty())
            assertEquals(1, speaker.isReady.subscriptionCount.value)
            speaker.isReady.value = true
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf("Question"), speaker.spoken)
            speaker.isReady.value = false
            autoplay = false
        }
        rule.waitForIdle()
        rule.runOnIdle { autoplay = true }
        rule.waitForIdle()
        rule.runOnIdle { speaker.isReady.value = true }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf("Question"), speaker.spoken) }
    }

    @Test fun recordingCancelsReadinessWaitAndPlaybackCanResumeAfterTranscription() {
        speaker.isReady.value = false
        host { addFollowupQuestion("Q8", "Deferred follow-up?") }
        rule.runOnIdle {
            assertEquals(1, speaker.isReady.subscriptionCount.value)
            speech.recording.value = true
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(0, speaker.isReady.subscriptionCount.value)
            speaker.isReady.value = true
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(speaker.spoken.isEmpty())
            speech.transitionToTranscribing()
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(speaker.spoken.isEmpty())
            speech.finish("")
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf("Deferred follow-up?"), speaker.spoken) }
    }

    @Test fun answeredFollowupCannotSpeakWhenReadinessArrives() {
        speaker.isReady.value = false
        host { addFollowupQuestion("Q8", "Answered before readiness?") }
        rule.runOnIdle {
            assertEquals(1, speaker.isReady.subscriptionCount.value)
            survey.answerLastFollowup("Q8", "Answer")
            speaker.isReady.value = true
        }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(speaker.spoken.isEmpty()) }
    }

    @Test fun completedQuestionCannotSpeakWhenReadinessArrives() {
        speaker.isReady.value = false
        host(twoStep = true) { addFollowupQuestion("Q8", "Completed before readiness?") }
        rule.runOnIdle {
            assertEquals(1, speaker.isReady.subscriptionCount.value)
            survey.setAiReason("Q8", SurveyAiReason.ACHIEVED)
            speaker.isReady.value = true
        }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(speaker.spoken.isEmpty()) }
    }

    @Test fun newFollowupReplacesOriginalWhileWaitingForReadiness() {
        speaker.isReady.value = false
        host()
        rule.runOnIdle {
            assertEquals(1, speaker.isReady.subscriptionCount.value)
            survey.setAnswer("Original answer", "Q8")
            addFollowup("Replacement follow-up?")
            speaker.isReady.value = true
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf("Replacement follow-up?"), speaker.spoken) }
    }

    @Test fun readAloudPolicySuppressesOriginalAndFollowupAutoplay() {
        host(readAloud = false)
        rule.runOnIdle { addFollowup("Follow-up?") }
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(speaker.spoken.isEmpty()) }
    }

    @Test fun reattachingScreenWithoutNavigationDoesNotReplayPendingFollowup() {
        host { addFollowupQuestion("Q8", "Existing?") }
        rule.runOnIdle {
            assertEquals(listOf("Existing?"), speaker.spoken)
            visible = false
        }
        rule.waitForIdle()
        rule.runOnIdle { visible = true }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf("Existing?"), speaker.spoken) }
    }

    @Test fun oneStepReturnRestoresAndReadsOnlyLatestPendingFollowup() =
        verifyPendingFollowupReturn(twoStep = false)

    @Test fun twoStepReturnRestoresAndReadsOnlyLatestPendingFollowup() =
        verifyPendingFollowupReturn(twoStep = true)

    private fun verifyPendingFollowupReturn(twoStep: Boolean) {
        host(twoStep = twoStep) {
            setAnswer("Main answer", "Q8")
            addFollowupQuestion("Q8", "First?")
            answerLastFollowup("Q8", "First answer")
            addFollowupQuestion("Q8", "Latest?")
        }
        val key = contextKey
        val history = survey.followups.value.getValue("Q8")
        rule.runOnIdle {
            assertEquals(listOf("Latest?"), speaker.spoken)
            assertEquals("Latest?", ai.conversationStateFlow(key).value.activePromptQuestion)
            ai.appendUserMessage(key, "Kept conversation")
            // A mismatched composer prompt must not override the persisted pending question.
            ai.setFollowupMode(key, "Earlier composer prompt?")
            ai.updateComposerDraft(key, "Unfinished follow-up answer")
        }
        val chat = ai.chatHistoryFlow(key).value
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Back").performClick()
        rule.runOnIdle {
            assertEquals(listOf("Latest?", "Other question", "Latest?"), speaker.spoken)
            assertEquals("Q8", survey.currentNodeId)
            assertEquals("Main answer", survey.getAnswer("Q8"))
            assertEquals(history, survey.followups.value["Q8"])
            assertEquals(chat, ai.chatHistoryFlow(key).value)
            assertEquals("Latest?", ai.conversationStateFlow(key).value.activePromptQuestion)
            assertEquals("Unfinished follow-up answer", ai.conversationStateFlow(key).value.composerDraft)
            assertEquals(0, repo.requests)
        }
        rule.runOnIdle { survey.answerLastFollowup("Q8", "Latest answer") }
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Back").performClick()
        rule.runOnIdle {
            assertEquals(listOf("Latest?", "Other question", "Latest?", "Other question"), speaker.spoken)
        }
    }

    @Test fun completedQuestionSuppressesAutoplayEvenWithPendingHistory() {
        host(twoStep = true) {
            addFollowupQuestion("Q8", "Pending?")
            setAiReason("Q8", SurveyAiReason.ACHIEVED)
        }
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Back").performClick()
        rule.runOnIdle { assertEquals(listOf("Other question"), speaker.spoken) }
    }

    @Test fun oldQuestionFollowupCannotSpeakAfterNavigationOrSessionReset() {
        speaker.isReady.value = false
        host { addFollowupQuestion("Q8", "Old question?") }
        rule.runOnIdle {
            assertEquals(1, speaker.isReady.subscriptionCount.value)
            survey.goto("Q9")
        }
        rule.waitForIdle()
        rule.runOnIdle { speaker.isReady.value = true }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf("Other question"), speaker.spoken)
            assertTrue(speaker.ids.single().startsWith("sid=0|nid=Q9|"))
            speaker.isReady.value = false
            survey.backToPrevious()
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(1, speaker.isReady.subscriptionCount.value)
            survey.resetToStart()
            survey.goto("Q8")
            speaker.isReady.value = true
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf("Other question", "Question"), speaker.spoken)
            assertTrue(speaker.ids.last().startsWith("sid=1|nid=Q8|"))
            assertTrue(speaker.stopCalls > 0)
        }
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
        val ids = mutableListOf<String>()
        var stopCalls = 0
        override fun speak(text: String, utteranceId: String) {
            spoken += text
            ids += utteranceId
        }
        override fun stop() { stopCalls++ }
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
    private fun config(twoStep: Boolean, readAloud: Boolean) = SurveyConfig(
        graph = SurveyConfig.Graph(startId = "Start", nodes = listOf(
            NodeDTO(id = "Start", type = "START", nextId = "Q8"),
            NodeDTO(id = "Q8", type = "AI", question = "Question", nextId = "Q9", readAloud = readAloud),
            NodeDTO(id = "Q9", type = "AI", question = "Other question", nextId = "Done"),
            NodeDTO(id = "Done", type = "DONE"),
        )),
        prompts = listOf(if (twoStep) {
            SurveyConfig.Prompt(nodeId = "Q8", evalPrompt = "{{QUESTION}} {{ANSWER}}", followupPrompt = "{{QUESTION}} {{EVAL_JSON}}")
        } else SurveyConfig.Prompt(nodeId = "Q8", prompt = "{{QUESTION}} {{ANSWER}}")),
    )
}
