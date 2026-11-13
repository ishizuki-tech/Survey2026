package com.negi.survey.screens

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.File
import android.content.ClipData
import android.content.ClipboardManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowManager
import com.negi.survey.SurveyNavHost
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import com.negi.survey.slm.Repository
import com.negi.survey.slm.RepositoryTraceContext
import com.negi.survey.ui.theme.SurveyNavTheme
import com.negi.survey.vm.AiTurnOutcome
import com.negi.survey.vm.AiViewModel
import com.negi.survey.vm.FlowAI
import com.negi.survey.vm.FlowDone
import com.negi.survey.vm.FlowHome
import com.negi.survey.vm.FlowNumber
import com.negi.survey.vm.NodeType
import com.negi.survey.vm.QuestionSpeaker
import com.negi.survey.vm.StopQuestionSpeakerInBackground
import com.negi.survey.vm.SurveyAiReason
import com.negi.survey.vm.SurveyViewModel
import com.negi.survey.vm.toggleQuestionSpeaker
import com.negi.survey.whisper.RecordingSpectrum
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises the actual screen with deterministic speech and inference dependencies. */
class AiScreenInteractionTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var survey: SurveyViewModel
    private lateinit var ai: AiViewModel
    private lateinit var backDispatcher: OnBackPressedDispatcher
    private lateinit var surveyWindow: Window
    private lateinit var hostView: View
    private lateinit var playbackLifecycle: LifecycleRegistry
    private val speech = FakeSpeech()
    private val speaker = FakeSpeaker()
    private val repo = HoldingRepository()
    private var visible by mutableStateOf(true)
    private var autoplay by mutableStateOf(true)
    private var speakerAvailable by mutableStateOf(true)
    private var unrelated by mutableStateOf(false)
    private var retainedAiNodeId by mutableStateOf<String?>(null)
    private var constrained by mutableStateOf(false)
    private val contextKey get() = "sid=${survey.sessionId.value}|nid=${survey.currentNodeId}"

    private fun host(
        withSpeech: Boolean = true,
        twoStep: Boolean = false,
        readAloud: Boolean = true,
        playbackState: Lifecycle.State? = null,
        compact: Boolean = false,
        largeText: Boolean = compact,
        constrainedHeight: Dp = 580.dp,
        restoration: StateRestorationTester? = null,
        useNavDisplay: Boolean = false,
        useSurveyNavHost: Boolean = false,
        aiTimeoutMs: Long? = null,
        initialNode: String = "Q8",
        productionImeInsets: Boolean = false,
        darkTheme: Boolean? = null,
        onComposerColor: (Color) -> Unit = {},
        onKeyboardShow: (() -> Unit)? = null,
        seed: SurveyViewModel.() -> Unit = {}
    ) {
        val content: @Composable () -> Unit = content@{
            hostView = LocalView.current
            val keyboard = LocalSoftwareKeyboardController.current
            val observedKeyboard = remember(keyboard, onKeyboardShow) {
                if (onKeyboardShow == null) keyboard else {
                    val delegate = checkNotNull(keyboard)
                    object : SoftwareKeyboardController by delegate {
                        override fun show() {
                            onKeyboardShow()
                            delegate.show()
                        }
                    }
                }
            }
            if (productionImeInsets) {
                val window = checkNotNull(LocalActivity.current).window
                SideEffect {
                    WindowCompat.setDecorFitsSystemWindows(window, false)
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                }
            }
            val stack = rememberNavBackStack(FlowHome)
            survey = remember {
                if (restoration != null && ::survey.isInitialized) {
                    survey.apply { attachNavigation(stack) }
                } else {
                    SurveyViewModel(stack, config(twoStep, readAloud)).apply {
                        goto(initialNode)
                        seed()
                    }
                }
            }
            ai = remember {
                if (restoration != null && ::ai.isInitialized) ai
                else if (aiTimeoutMs != null) AiViewModel(repo, defaultTimeoutMs = aiTimeoutMs)
                else AiViewModel(repo)
            }
            if (useSurveyNavHost) {
                surveyWindow = checkNotNull(LocalActivity.current).window
                backDispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                SurveyNavTheme {
                    SurveyNavHost(
                        vmSurvey = survey,
                        vmAI = ai,
                        backStack = stack,
                        ttsMeta = SurveyConfig.TtsMeta(enabled = false),
                        onResolveUploadStatusDetails = { error("Unused") },
                        onClearCompletedSurveys = {},
                        speechControllerOverride = speech,
                    )
                }
                return@content
            }
            val node by survey.currentNode.collectAsState()
            // Read unrelated state to force an ordinary parent recomposition.
            if (unrelated) androidx.compose.material3.Text("unrelated")
            val owner = LocalLifecycleOwner.current
            val lifecycle = if (playbackState == null) owner.lifecycle else {
                val testOwner = remember {
                    object : LifecycleOwner {
                        override val lifecycle = LifecycleRegistry(this).apply {
                            currentState = playbackState
                        }
                    }
                }
                playbackLifecycle = testOwner.lifecycle
                StopQuestionSpeakerInBackground(speaker, playbackLifecycle)
                playbackLifecycle
            }
            if (visible) {
                SurveyQuestionAutoRead(
                    survey, ai, speaker.takeIf { speakerAvailable },
                    speech.takeIf { withSpeech }, autoplay, lifecycle
                )
            }
            val screen: @Composable (String) -> Unit = { nodeId ->
                val density = LocalDensity.current
                val contentDensity = if (largeText) Density(density.density, fontScale = 2f) else density
                val bounds = when {
                    compact -> Modifier.size(width = 280.dp, height = 260.dp)
                    constrained -> Modifier.heightIn(max = constrainedHeight)
                    else -> Modifier
                }
                Box(bounds) {
                    CompositionLocalProvider(
                        LocalDensity provides contentDensity,
                        LocalSoftwareKeyboardController provides observedKeyboard
                    ) {
                        SurveyNavTheme(darkTheme = darkTheme ?: isSystemInDarkTheme()) {
                            val composerColor = MaterialTheme.colorScheme.surfaceVariant
                            SideEffect { onComposerColor(composerColor) }
                            AiScreen(nodeId, survey, ai, survey::advanceToNext, survey::backToPrevious,
                                if (withSpeech) speech else null, speaker.takeIf { speakerAvailable },
                                playbackLifecycle = lifecycle)
                        }
                    }
                }
            }
            if (useNavDisplay) {
                NavDisplay(
                    backStack = stack,
                    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                    entryProvider = entryProvider {
                        entry<FlowHome> { Text("Home") }
                        entry<FlowNumber> { Text("Experience") }
                        entry<FlowAI> {
                            if (initialNode == "Q8") screen("Q8")
                            else if (node.type == NodeType.AI) screen(node.id)
                        }
                        entry<FlowDone> { Text("Away from AI") }
                    }
                )
            } else if (visible) {
                val aiNodeId = retainedAiNodeId ?: node.id.takeIf { node.type == NodeType.AI }
                if (aiNodeId != null) screen(aiNodeId)
            }
        }
        if (restoration != null) restoration.setContent(content) else rule.setContent(content)
        rule.waitForIdle()
    }

    private fun addFollowup(question: String) {
        survey.addFollowupQuestion("Q8", question)
        ai.setFollowupMode(contextKey, question)
    }

    @Test fun typeModeStartsAtEndAndKeepsUserSelectionDuringEditing() {
        host(productionImeInsets = true) { setAnswer("typed draft", "Q8") }
        val editor = rule.onNode(hasSetTextAction())
        val type = rule.onNodeWithText("Text")
        val speak = rule.onNodeWithText("Voice")

        type.performClick().assertIsSelected()
        waitForKeyboard(visible = true)
        rule.waitUntil(5_000) {
            val editorNode = editor.fetchSemanticsNode()
            val imeTop = rule.runOnUiThread {
                val root = hostView.rootView
                val position = IntArray(2)
                root.getLocationOnScreen(position)
                val inset = ViewCompat.getRootWindowInsets(hostView)
                    ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                position[1] + root.height - inset
            }
            editorNode.positionOnScreen.y + editorNode.size.height <= imeTop
        }
        editor.assertCaretAt(11)
        editor.tapAtTextOffset(5)
        editor.assertCaretAt(5)
        editor.performTextInput("!")
        editor.assertCaretAt(6)
        rule.runOnIdle { assertEquals("typed! draft", survey.getAnswer("Q8")) }

        editor.performSemanticsAction(SemanticsActions.SetSelection) { it(1, 4, false) }
        rule.runOnIdle { unrelated = true }
        editor.assertSelection(1, 4)
        rule.runOnIdle { ai.updateComposerDraft(contextKey, "typed! draft updated") }
        editor.assertSelection(1, 4)

        speak.assertIsDisplayed().performClick().assertIsSelected()
        waitForKeyboard(visible = false)
        type.performClick().assertIsSelected()
        waitForKeyboard(visible = true)
        editor.assertCaretAt("typed! draft updated".length)
    }

    private fun SemanticsNodeInteraction.tapAtTextOffset(offset: Int) {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val cursor = layouts.single().getCursorRect(offset)
        // Match the start content padding of the composer decoration.
        performTouchInput {
            // Each call represents an independent tap rather than a double-tap word selection.
            advanceEventTime(ViewConfiguration.getDoubleTapTimeout().toLong() + 1)
            click(Offset(8.dp.toPx() + cursor.left, center.y))
        }
    }

    private fun SemanticsNodeInteraction.assertCaretAt(offset: Int) = assertSelection(offset, offset)

    private fun SemanticsNodeInteraction.assertSelection(start: Int, end: Int) =
        assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(start, end)))

    @Test fun inputModeAndDraftSurviveBackAndNextWithinSurvey() {
        host(useNavDisplay = true, initialNode = "Q06", productionImeInsets = true) {
            setAnswer("Q08 saved answer", "Q08")
        }
        val type = rule.onNodeWithText("Text")
        val speak = rule.onNodeWithText("Voice")
        val editor = rule.onNode(hasSetTextAction())
        val back = rule.onNodeWithText("Back")
        val next = rule.onNodeWithText("Next")

        rule.runOnIdle { survey.advanceToNext() }
        assertQuestionFits("Q07 original question")
        speak.assertIsSelected()
        type.performClick().assertIsSelected()
        waitForKeyboard(visible = true)
        editor.performTextInput("Q07 draft")

        back.performScrollTo().performClick()
        rule.onNodeWithText("Experience").assertIsDisplayed()
        rule.runOnIdle { survey.advanceToNext() }
        assertQuestionFits("Q07 original question")
        type.assertIsSelected()
        editor.assertIsNotFocused()
        waitForKeyboard(visible = false)
        rule.onNodeWithText("Q07 draft").assertIsDisplayed()

        next.performScrollTo().performClick()
        assertQuestionFits("Q08 original question")
        type.assertIsSelected()
        editor.assertIsNotFocused()
        waitForKeyboard(visible = false)
        rule.onNodeWithText("Q07 draft").assertDoesNotExist()
        rule.onNodeWithText("Q08 saved answer").assertIsDisplayed()

        editor.tapAtTextOffset(3)
        editor.assertIsFocused().assertCaretAt(3)
        waitForKeyboard(visible = true)
        dismissKeyboardWithBack()
        // The activity delegates system Back directly to the survey ViewModel.
        rule.runOnIdle { survey.backToPrevious() }
        assertQuestionFits("Q07 original question")
        type.assertIsSelected()
        editor.assertIsNotFocused()
        editor.assertCaretAt(0)
        waitForKeyboard(visible = false)
        rule.onNodeWithText("Q07 draft").assertIsDisplayed()
        next.performScrollTo().performClick()
        assertQuestionFits("Q08 original question")
        type.assertIsSelected()
        editor.assertIsNotFocused()

        speak.performClick().assertIsSelected()
        back.performScrollTo().performClick()
        assertQuestionFits("Q07 original question")
        speak.assertIsSelected()
        rule.onNodeWithText("Tap to answer").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals("Q07 draft", ai.conversationStateFlow(contextKey).value.composerDraft)
            assertEquals("Q07 draft", survey.getAnswer("Q07"))
        }

        next.performScrollTo().performClick()
        assertQuestionFits("Q08 original question")
        speak.assertIsSelected()
        next.performScrollTo().performClick()
        assertQuestionFits("Q09 original question")
        speak.assertIsSelected()
        waitForKeyboard(visible = false)
    }

    @Test fun newSurveySessionResetsInputModeToVoice() {
        host(useNavDisplay = true, initialNode = "Q06", productionImeInsets = true)
        rule.runOnIdle { survey.advanceToNext() }
        rule.onNodeWithText("Text").performClick().assertIsSelected()
        waitForKeyboard(visible = true)

        rule.onNodeWithText("Back").performScrollTo().performClick()
        rule.onNodeWithText("Experience").assertIsDisplayed()
        rule.runOnIdle {
            val previousSession = survey.sessionId.value
            survey.resetToStart()
            assertTrue(survey.sessionId.value > previousSession)
        }
        rule.onNodeWithText("Home").assertIsDisplayed()
        rule.runOnIdle { survey.goto("Q07") }
        assertQuestionFits("Q07 original question")
        rule.onNodeWithText("Voice").assertIsSelected()
        rule.onNodeWithText("Tap to answer").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        waitForKeyboard(visible = false)
    }

    @Test fun conversationShowsBothQuestionAvatarsAndTheLatestAnswerInVoiceAndText() {
        host(twoStep = true, productionImeInsets = true)
        val voice = rule.onNodeWithText("Voice")
        val text = rule.onNodeWithText("Text")
        val radioButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        voice.assert(radioButton).assertIsSelected()
        text.assert(radioButton).assertIsNotSelected()
        rule.onNodeWithText("Tap to answer").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText("Back").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText("Next").assertIsDisplayed().assertIsEnabled()

        fun questionStyle(text: String, tag: String) = rule.onNode(
            hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true
        )

        questionStyle("Question", "Active AI question").assertIsDisplayed()
        captureFieldUi("active-original")
        rule.runOnIdle {
            ai.appendUserMessage(contextKey, "We set aside seed for next season.")
            ai.upsertChatItem(contextKey, AiViewModel.ChatItem(
                "first-review", AiViewModel.ChatSender.AI, json = NEEDS_FOLLOWUP,
                evaluation = AiViewModel.EvaluationPresentation.REVIEWED
            ))
            survey.addFollowupQuestion("Q8", FIRST_FOLLOWUP)
            ai.upsertChatItem(contextKey, AiViewModel.ChatItem(
                "published-followup", AiViewModel.ChatSender.AI, FIRST_FOLLOWUP, isFollowupQuestion = true
            ))
        }
        assertQuestionFits("Question")
        assertQuestionFits(FIRST_FOLLOWUP)
        assertQuestionFits("We set aside seed for next season.")
        rule.onAllNodes(hasTestTag("AI question avatar"), useUnmergedTree = true).assertCountEquals(2)
        rule.onNodeWithText("AI").assertDoesNotExist()
        questionStyle("Question", "Historical AI question").assertIsDisplayed()
        questionStyle(FIRST_FOLLOWUP, "Active AI question").assertIsDisplayed()
        val review = evaluationControl("first-review")
        val answer = rule.onNodeWithText("We set aside seed for next season.")
        val answerBounds = answer.fetchSemanticsNode().boundsInRoot
        review.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        val reviewBounds = review.fetchSemanticsNode().boundsInRoot
        assertPairedAnswer("first-review", "We set aside seed for next season.")
        rule.onAllNodesWithText(FIRST_FOLLOWUP).assertCountEquals(1)
        assertTrue(reviewBounds.right <= answerBounds.left)
        assertTrue(answerBounds.top < reviewBounds.bottom && reviewBounds.top < answerBounds.bottom)
        assertTrue(reviewBounds.bottom <= rule.onNodeWithText(FIRST_FOLLOWUP).fetchSemanticsNode().boundsInRoot.top)
        captureFieldUi("refined-voice")
        if (capturingFieldUi) {
            review.performClick()
            rule.onNodeWithText("AI score 30").assertIsDisplayed()
            captureFieldUi("expanded-evaluation")
            review.performClick()
        }

        text.performClick().assertIsSelected()
        voice.assertIsNotSelected()
        waitForKeyboard(visible = true)
        assertQuestionFits(FIRST_FOLLOWUP)
        assertQuestionFits("We set aside seed for next season.")
        rule.onNode(hasSetTextAction()).assertIsDisplayed().assertIsFocused()
        rule.onNodeWithContentDescription("Send").assertIsDisplayed()
        captureFieldUi("refined-text-ime")
        dismissKeyboardWithBack()
        rule.runOnIdle {
            survey.answerLastFollowup("Q8", "About two bags.")
            ai.appendUserMessage(contextKey, "About two bags.")
            survey.addFollowupQuestion("Q8", SECOND_FOLLOWUP)
        }
        questionStyle(FIRST_FOLLOWUP, "Historical AI question").assertIsDisplayed()
        questionStyle(SECOND_FOLLOWUP, "Active AI question").assertIsDisplayed()
        rule.runOnIdle { survey.setAiReason("Q8", SurveyAiReason.ACHIEVED) }
        rule.waitUntil(5_000) { rule.onNodeWithText("Answer input is closed").isDisplayed() }
        rule.onNodeWithTag("Active AI question", useUnmergedTree = true).assertDoesNotExist()
        questionStyle(SECOND_FOLLOWUP, "Historical AI question").assertIsDisplayed()
    }

    @Test fun reviewWithoutAnAdjacentAnswerRetainsStandaloneDetails() {
        val rows = listOf(
            AiViewModel.ChatItem("qroot-Q8", AiViewModel.ChatSender.AI, "Original question"),
            AiViewModel.ChatItem("answer", AiViewModel.ChatSender.USER, "Earlier answer"),
            AiViewModel.ChatItem("followup", AiViewModel.ChatSender.AI, FIRST_FOLLOWUP, isFollowupQuestion = true),
            AiViewModel.ChatItem(
                "orphan-review", AiViewModel.ChatSender.AI, json = NEEDS_FOLLOWUP,
                evaluation = AiViewModel.EvaluationPresentation.REVIEWED
            )
        )
        rule.setContent {
            SurveyNavTheme {
                AiConversation(
                    "Q8", 1L, rows, emptyList(), false, remember { SnackbarHostState() },
                    Modifier.fillMaxSize().statusBarsPadding()
                )
            }
        }
        rule.onAllNodesWithText("Earlier answer").assertCountEquals(1)
        evaluationControl("orphan-review").assertTextContains("Answer reviewed", substring = true).performClick()
        rule.onNodeWithText("AI score 30").assertIsDisplayed()
    }

    @Test fun historicalConversationWrapsEnglishAndSwahiliAtLargeFontScale() {
        val oldQuestion = "What changed in your maize harvest during the last growing season?"
        val oldAnswer = "Tulipata mavuno kidogo kwa sababu mvua ilichelewa kuanza."
        val activeQuestion = "Ulihifadhi mbegu kiasi gani?"
        val rows = listOf(
            AiViewModel.ChatItem("qroot-Q8", AiViewModel.ChatSender.AI, oldQuestion),
            AiViewModel.ChatItem("answer", AiViewModel.ChatSender.USER, oldAnswer),
            AiViewModel.ChatItem(
                "review", AiViewModel.ChatSender.AI, json = NEEDS_FOLLOWUP,
                evaluation = AiViewModel.EvaluationPresentation.REVIEWED
            ),
            AiViewModel.ChatItem("active", AiViewModel.ChatSender.AI, activeQuestion, isFollowupQuestion = true)
        )
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                SurveyNavTheme {
                    Surface(Modifier.fillMaxSize()) {
                        AiConversation(
                            "Q8", 1L, rows, emptyList(), false, remember { SnackbarHostState() },
                            Modifier.width(280.dp).statusBarsPadding().padding(horizontal = 10.dp),
                            visualActiveQuestionId = "active"
                        )
                    }
                }
            }
        }
        val questionLayout = rule.onNodeWithText(oldQuestion).also { it.assertIsDisplayed() }.let { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            layouts.single()
        }
        val answerLayouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(oldAnswer).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(answerLayouts) }
        assertPairedAnswer("review", oldAnswer)
        val answerBounds = rule.onNodeWithText(oldAnswer).fetchSemanticsNode().boundsInRoot
        val detailsBounds = evaluationControl("review").fetchSemanticsNode().boundsInRoot
        assertTrue(answerBounds.bottom <= detailsBounds.top)
        listOf(questionLayout, answerLayouts.single()).forEach { layout ->
            assertTrue(layout.lineCount > 1)
            assertTrue(!layout.hasVisualOverflow)
        }
        rule.onNodeWithText(activeQuestion).assertIsDisplayed()
        rule.onNodeWithTag("Active AI question", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("Historical AI question", useUnmergedTree = true).assertIsDisplayed()
        captureFieldUi("historical-bilingual-large")
    }

    @Test fun aiPlaybackShowsStopAndRestoresTheSpeaker() {
        autoplay = false
        speaker.holdPlayback = true
        host()
        val speakerButton = rule.onNodeWithText("🔊")
        val stopButton = rule.onNodeWithText("⏹")
        speakerButton.assertIsDisplayed()
        captureFieldUi("ai-tts-speaker")
        speakerButton.performClick()
        stopButton.assertIsDisplayed()
        rule.runOnIdle { assertEquals(listOf("Question"), speaker.spoken) }
        captureFieldUi("ai-tts-stop")
        stopButton.performClick()
        speakerButton.assertIsDisplayed()
        stopButton.assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, speaker.stopCalls) }
    }

    @Test fun lightComposerUsesSolidSurfaceWhenFocusedAndUnfocused() =
        verifyComposerSurface(darkTheme = false)

    @Test fun darkComposerUsesSolidSurfaceWhenFocusedAndUnfocused() =
        verifyComposerSurface(darkTheme = true)

    private fun verifyComposerSurface(darkTheme: Boolean) {
        var surface = Color.Unspecified
        host(productionImeInsets = true, darkTheme = darkTheme, onComposerColor = { surface = it })
        rule.onNodeWithText("Text").performClick()
        waitForKeyboard(visible = true)
        val field = rule.onNodeWithTag("ai-answer-field")
        val editor = rule.onNode(hasSetTextAction())
        editor.assertIsFocused()
        fun assertSolidSurface(state: String) {
            rule.waitUntil(conditionDescription = "solid composer surface in $state", timeoutMillis = 5_000) {
                val node = field.fetchSemanticsNode()
                val x = node.positionOnScreen.x.toInt() + node.size.width * 2 / 3
                val top = node.positionOnScreen.y.toInt()
                val samples = listOf(node.size.height / 5, node.size.height / 2, node.size.height * 4 / 5)
                val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                try {
                    x in 0 until screenshot.width && samples.all { offset ->
                        val y = top + offset
                        y in 0 until screenshot.height && screenshot.getPixel(x, y) == surface.toArgb()
                    }
                } finally {
                    screenshot.recycle()
                }
            }
            captureFieldUi("composer-${if (darkTheme) "dark" else "light"}-$state")
        }
        assertSolidSurface("focused")
        rule.onNodeWithText("Voice").performClick()
        waitForKeyboard(visible = false)
        rule.onNodeWithContentDescription("Record answer").performClick()
        rule.runOnIdle { speech.capture.value = true }
        rule.onNodeWithText("Stop").performClick()
        rule.runOnIdle { speech.transitionToTranscribing() }
        rule.onNodeWithText("Transcribing…").assertExists()
        rule.runOnIdle { speech.finish("Short answer") }
        editor.assertIsNotFocused()
        assertSolidSurface("unfocused")
    }

    @Test fun compactEditorKeepsItsTouchHeightAndGrowsForMultipleLines() =
        verifyCompactEditor(largeText = false)

    @Test fun compactEditorGrowsWithLargeText() =
        verifyCompactEditor(largeText = true)

    private fun verifyCompactEditor(largeText: Boolean) {
        host(largeText = largeText, productionImeInsets = true)
        rule.onNodeWithText("Text").performClick().assertIsSelected()
        waitForKeyboard(visible = true)
        val editor = rule.onNode(hasSetTextAction())
        rule.waitUntil(5_000) { editor.isDisplayed() }
        editor.assertIsFocused()
        // The placeholder itself can wrap at large font scale.
        if (largeText) editor.performTextInput("First line")
        val row = rule.onNodeWithTag("ai-answer-field")
        val oneLineHeight = editor.fetchSemanticsNode().size.height
        val oneLineRowHeight = row.fetchSemanticsNode().size.height
        val density = rule.density.density
        assertTrue(oneLineHeight >= (48 * density).toInt())
        if (!largeText) {
            assertEquals(48f, oneLineHeight / density, 1f)
            assertEquals(52f, oneLineRowHeight / density, 1f)
            captureFieldUi("compact-editor-single")
        }

        val answer = "First line\nSecond line"
        editor.performTextInput(if (largeText) "\nSecond line" else answer)
        editor.assertIsFocused().assertTextContains(answer)
        editor.assertCaretAt(answer.length)
        val multilineHeight = editor.fetchSemanticsNode().size.height
        assertTrue(multilineHeight > oneLineHeight)
        rule.onNodeWithContentDescription("Send").assertIsDisplayed().assertIsEnabled()
        android.util.Log.i(
            "SurveyCompactUi",
            "largeText=$largeText editor=${oneLineHeight / density}dp row=${oneLineRowHeight / density}dp " +
                "multiline=${multilineHeight / density}dp"
        )
        captureFieldUi(if (largeText) "compact-editor-large" else "compact-editor-multiline")
    }

    @Test fun q07VoiceComposerKeepsItsReadyHeightDuringReviewAndFollowup() {
        val evaluation = ControlledResponse()
        val followup = ControlledResponse()
        repo.streamedResponses = listOf(evaluation, followup)
        host(twoStep = true, initialNode = "Q07")

        val ready = rule.onNodeWithText("Tap to answer")
        val again = rule.onNodeWithText("Record again")
        val footer = rule.onNodeWithTag("AI footer")
        val back = rule.onNodeWithText("Back")
        val next = rule.onNodeWithText("Next")

        fun geometry(state: String, action: SemanticsNodeInteraction): List<Float> {
            val heights = listOf(
                action.fetchSemanticsNode().boundsInRoot.height,
                footer.fetchSemanticsNode().boundsInRoot.height,
                conversationViewport().height
            )
            if (capturingFieldUi) {
                android.util.Log.i("VoiceComposerLayout", "$state (action, footer, viewport px): $heights")
                captureFieldUi(state)
            }
            return heights
        }

        fun record(action: SemanticsNodeInteraction, question: String, expectedStarts: Int, transcript: String) {
            action.assertIsEnabled().performTouchInput { click() }
            rule.runOnIdle {
                assertEquals(expectedStarts, speech.startCalls)
                speech.recordingLevel.value = RecordingSpectrum(FloatArray(RecordingSpectrum.BAND_COUNT) { 0.5f })
                speech.capture.value = true
            }
            rule.onNode(hasText(question) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            rule.onNodeWithTag("live-recording-waveform").assertIsDisplayed()
            captureFieldUi("q07-recording-$expectedStarts")
            rule.onNodeWithText("Stop").performClick()
            rule.runOnIdle { speech.transitionToTranscribing() }
            rule.onNodeWithText("Transcribing…").assertIsDisplayed()
            ready.assertDoesNotExist()
            again.assertDoesNotExist()
            back.assertIsNotEnabled()
            next.assertIsNotEnabled()
            captureFieldUi("q07-transcribing-$expectedStarts")
            rule.runOnIdle { speech.finish(transcript) }
            rule.onNodeWithText(transcript).assertIsDisplayed()
            rule.onNodeWithContentDescription("Send").assertIsEnabled()
            again.assertIsDisplayed()
        }

        val readyHeights = geometry("q07-ready", ready.assertIsDisplayed())
        record(ready, "Q07 original question", 1, "Two bags of maize")
        captureFieldUi("q07-original-transcript")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.onNodeWithText("Reviewing your answer…").assertIsDisplayed()
        assertEquals(readyHeights, geometry("q07-reviewing", ready.assertIsNotEnabled()))
        ready.performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, speech.startCalls) }
        back.assertIsNotEnabled()
        next.assertIsNotEnabled()

        sendChunk(evaluation, NEEDS_FOLLOWUP, expected = NEEDS_FOLLOWUP)
        evaluation.finish()
        rule.waitUntil(5_000) { repo.requests == 2 }
        assertEquals(readyHeights, geometry("q07-preparing", ready.assertIsNotEnabled()))
        sendChunk(followup, FIRST_FOLLOWUP, expected = FIRST_FOLLOWUP)
        followup.finish()
        rule.waitUntil(5_000) {
            survey.followups.value["Q07"]?.lastOrNull()?.question == FIRST_FOLLOWUP &&
                !ai.blocksNavigation()
        }

        assertQuestionFits(FIRST_FOLLOWUP)
        assertEquals(readyHeights, geometry("q07-followup-ready", ready.assertIsEnabled()))
        record(ready, FIRST_FOLLOWUP, 2, "I harvested two bags after the rains.")
        geometry("q07-followup-transcript", again)
        record(again, FIRST_FOLLOWUP, 3, "I harvested three bags after the rains.")
        val editedTranscript = "Three bags of maize after the rains."
        rule.onNode(hasSetTextAction()).performTextReplacement(editedTranscript)
        rule.runOnIdle {
            assertEquals(editedTranscript, ai.conversationStateFlow(contextKey).value.composerDraft)
            assertEquals(listOf("Two bags of maize"), userMessages())
        }
        rule.onNodeWithContentDescription("Send").assertIsEnabled()
    }

    @Test fun voiceModeSwitchingPreservesDraftAndTranscriptCanBeEdited() {
        host(productionImeInsets = true)
        val speak = rule.onNodeWithText("Voice")
        val type = rule.onNodeWithText("Text")
        val editor = rule.onNode(hasSetTextAction())
        speak.assertIsSelected()
        rule.onNodeWithText("Tap to answer").assertIsDisplayed()
        editor.assertDoesNotExist()
        waitForKeyboard(visible = false)

        type.performClick().assertIsSelected()
        waitForKeyboard(visible = true)
        editor.assertIsFocused().performTextInput("typed draft")
        assertSharedAnswerField()
        rule.onNodeWithText("Record again").assertDoesNotExist()
        speak.assertIsDisplayed().performClick().assertIsSelected()
        waitForKeyboard(visible = false)
        editor.assertDoesNotExist()
        rule.onNodeWithText("Tap to answer").assertIsDisplayed()
        rule.runOnIdle { assertEquals("typed draft", ai.conversationStateFlow(contextKey).value.composerDraft) }

        type.performClick().assertIsSelected()
        editor.assertIsFocused()
        waitForKeyboard(visible = true)
        rule.onNodeWithText("typed draft").assertIsDisplayed()
        dismissKeyboardWithBack()
        type.assertIsSelected().performClick()
        waitForKeyboard(visible = true)
        // The first switch above covers touch. Invoke this one while the IME is still moving.
        speak.performSemanticsAction(SemanticsActions.OnClick).assertIsSelected()
        waitForKeyboard(visible = false)
        rule.onNodeWithText("Tap to answer").performClick()
        rule.runOnIdle { speech.capture.value = true }
        rule.onNodeWithText("Stop").performClick()
        rule.runOnIdle { speech.transitionToTranscribing() }
        rule.onNodeWithText("Transcribing…").assertExists()
        rule.runOnIdle { speech.finish("recognized answer") }
        speak.assertIsSelected()
        editor.assertIsNotFocused()
        waitForKeyboard(visible = false)
        assertSharedAnswerField()
        val field = rule.onNodeWithTag("ai-answer-field")
        val recordAgain = rule.onNodeWithText("Record again").assertIsDisplayed()
        assertTrue(recordAgain.fetchSemanticsNode().boundsInRoot.top >= field.fetchSemanticsNode().boundsInRoot.bottom)

        editor.performScrollTo().tapAtTextOffset(4)
        editor.assertIsFocused()
        waitForKeyboard(visible = true)
        editor.assertCaretAt("recognized answer".length)
        speak.assertIsSelected().assertIsDisplayed()
        assertSharedAnswerField()
        editor.tapAtTextOffset(4)
        editor.assertCaretAt(4)
        editor.performTextInput("!")
        editor.assertCaretAt(5)
        rule.runOnIdle { assertEquals("reco!gnized answer", survey.getAnswer("Q8")) }
        editor.performTextReplacement("")
        editor.assertIsFocused()
        rule.onNodeWithContentDescription("Send").assertIsNotEnabled()
        type.performClick().assertIsSelected()
        speak.performClick().assertIsSelected()
        waitForKeyboard(visible = false)
        assertSharedAnswerField()
        editor.performClick().performTextInput("edited transcript")
        rule.runOnIdle {
            assertEquals("edited transcript", ai.conversationStateFlow(contextKey).value.composerDraft)
            assertEquals("edited transcript", survey.getAnswer("Q8"))
        }
        dismissKeyboardWithBack()
        speak.assertIsSelected()
        rule.onNodeWithText("Record again").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Send").performScrollTo().performClick()
        rule.waitUntil(5_000) { repo.requests == 1 }
        assertEquals(listOf("edited transcript"), userMessages())
    }

    private fun assertSharedAnswerField() {
        val inAnswerField = hasAnyAncestor(hasTestTag("ai-answer-field"))
        val editor = rule.onNode(inAnswerField and hasSetTextAction()).assertIsDisplayed()
        val send = rule.onNode(inAnswerField and hasContentDescription("Send")).assertIsDisplayed()
        assertTrue(send.fetchSemanticsNode().boundsInRoot.left >= editor.fetchSemanticsNode().boundsInRoot.right)
        rule.onNode(inAnswerField and hasContentDescription("Start recording")).assertDoesNotExist()
        rule.onNode(inAnswerField and hasText("Tap to answer")).assertDoesNotExist()
        rule.onNode(inAnswerField and hasText("Record again")).assertDoesNotExist()
    }

    @Test fun textOnlyFallbackFocusesOnEntryWithoutReopeningTheDismissedKeyboard() {
        val restoration = StateRestorationTester(rule)
        host(withSpeech = false, productionImeInsets = true, restoration = restoration)
        val editor = rule.onNode(hasSetTextAction()).assertIsFocused()
        waitForKeyboard(visible = true)
        editor.performTextInput("answer")
        rule.onNodeWithText("Tap to answer").assertDoesNotExist()
        rule.onNodeWithText("Voice").assertDoesNotExist()
        assertSharedAnswerField()
        rule.runOnIdle { assertEquals("answer", survey.getAnswer("Q8")) }

        val keyboardViewport = conversationViewport()
        dismissKeyboardWithBack()
        rule.waitUntil(5_000) { conversationViewport().height > keyboardViewport.height }
        val viewport = conversationViewport()
        rule.runOnIdle {
            constrained = true
            unrelated = true
        }
        assertTrue(conversationViewport().height < viewport.height)
        editor.assertIsFocused()
        waitForKeyboard(visible = false)

        restoration.emulateSavedInstanceStateRestore()
        editor.assertIsDisplayed()
        waitForKeyboard(visible = false)
    }

    @Test fun textOnlyEntryDefersFocusUntilAvailableAndShowsKeyboardOnlyOnce() {
        val first = ControlledResponse()
        val second = ControlledResponse()
        val score = """{"score":95,"missing_points":[],"followup_needed":false}"""
        repo.streamedResponses = listOf(first, second)
        visible = false
        var showRequests = 0
        val restoration = StateRestorationTester(rule)
        host(withSpeech = false, restoration = restoration, onKeyboardShow = { showRequests++ })

        val initialRun = rule.runOnIdle {
            ai.evaluateAsync("already processing").also { visible = true }
        }
        val editor = rule.onNode(hasSetTextAction()).assertIsNotFocused()
        assertInactiveComposer()
        waitForKeyboard(visible = false)
        rule.runOnIdle { assertEquals(0, showRequests) }

        sendChunk(first, score, expected = score)
        first.finish()
        rule.waitUntil(5_000) { initialRun.isCompleted }
        editor.assertIsFocused()
        waitForKeyboard(visible = true)
        rule.runOnIdle { assertEquals(1, showRequests) }

        dismissKeyboardWithBack()
        val nextRun = rule.runOnIdle {
            unrelated = true
            ai.evaluateAsync("stream again")
        }
        sendChunk(second, score.take(16), expected = score.take(16))
        rule.onNodeWithText(score.take(16)).assertIsDisplayed()
        editor.assertIsFocused()
        waitForKeyboard(visible = false)
        rule.runOnIdle { assertEquals(1, showRequests) }
        sendChunk(second, score.drop(16), expected = score)
        second.finish()
        rule.waitUntil(5_000) { nextRun.isCompleted }
        editor.assertIsFocused()

        restoration.emulateSavedInstanceStateRestore()
        editor.assertIsDisplayed()
        waitForKeyboard(visible = false)
        rule.runOnIdle { assertEquals(1, showRequests) }
    }

    @Test fun sendOnceDisablesSubmissionAndPreservesKeyboardDuringInference() {
        host()
        rule.onNodeWithText("Next").assertIsEnabled()
        rule.onNodeWithText("Text").performClick()
        val send = rule.onNodeWithContentDescription("Send")
        send.assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("answer")
        send.assertIsEnabled().performClick()
        rule.waitUntil(5_000) { repo.requests > 0 }
        rule.onNodeWithText("Next").assertIsNotEnabled()
        send.assertIsNotEnabled()
        waitForKeyboard(visible = true)
        assertInactiveComposer()
        val editor = rule.onNode(hasSetTextAction()).assertIsFocused()
        editor.performTextInput("blocked typing")
        rule.runOnIdle {
            hostView.context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("test", "blocked paste"))
        }
        editor.performKeyInput { pressKey(Key.Paste) }
        editor.performImeAction()
        assertInactiveComposer()
        assertQuestionFits("answer")
        rule.runOnIdle {
            assertEquals(1, repo.requests)
            assertEquals("answer", survey.getAnswer("Q8"))
            assertEquals(listOf("answer"), userMessages())
        }
    }

    @Test fun manualReadingAndReturnPreserveHistoryWithoutAJumpControl() {
        host()
        appendConversationHistory()
        scrollManuallyTo(7)
        val manualPosition = conversationPosition()
        rule.runOnIdle { unrelated = true }
        assertEquals(manualPosition, conversationPosition(), 0f)

        rule.runOnIdle { ai.appendUserMessage(contextKey, "Latest answer") }
        conversation.performScrollToIndex(0)
        rule.onNodeWithText("Latest answer").assertIsDisplayed()
        rule.onNodeWithText("Jump to bottom").assertDoesNotExist()

        rule.runOnIdle {
            ai.upsertChatItem(contextKey, AiViewModel.ChatItem(
                id = "evaluation", sender = AiViewModel.ChatSender.AI, json = NEEDS_FOLLOWUP
            ))
        }
        conversation.performScrollToIndex(0)
        rule.onNodeWithText("tap to expand", substring = true).performClick()
        scrollToOriginalQuestion()
        conversation.performScrollToIndex(0)
        rule.onNodeWithText("tap to collapse", substring = true).assertIsDisplayed()
    }

    @Test fun initialEntryDisplaysTheSavedQuestionWithoutItsInMemoryChatRow() {
        host(twoStep = true) {
            setAnswer("Saved main answer", "Q8")
            addFollowupQuestion("Q8", "Saved follow-up?")
        }
        rule.onNodeWithText("Saved follow-up?").assertIsDisplayed()
        rule.onNodeWithText("Tap to answer").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun lateSavedFollowupBecomesVisibleAndAnswerUpdatesDoNotRepeatPositioning() {
        host(twoStep = true)
        appendConversationHistory()

        // Populate the retained survey flow after the screen has rendered its original question.
        rule.runOnIdle { survey.addFollowupQuestion("Q8", "Late saved follow-up?") }
        rule.onNodeWithText("Late saved follow-up?").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals("Late saved follow-up?", ai.conversationStateFlow(contextKey).value.activePromptQuestion)
            assertTrue(ai.chatHistoryFlow(contextKey).value.none { it.isFollowupQuestion })
        }

        scrollManuallyTo(7)
        val manualPosition = conversationPosition()
        rule.runOnIdle { survey.answerLastFollowup("Q8", "Answer restored separately") }
        repeat(3) {
            rule.runOnIdle { unrelated = !unrelated }
            assertEquals(manualPosition, conversationPosition(), 0f)
        }
        conversation.performScrollToNode(hasText("Late saved follow-up?"))
        rule.onNodeWithText("Late saved follow-up?").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, survey.followups.value.getValue("Q8").size) }
    }

    @Test fun lateSavedFollowupWhileReadingHistoryDoesNotInterruptTheReader() {
        host(twoStep = true)
        appendConversationHistory()
        scrollManuallyTo(7)
        val manualPosition = conversationPosition()

        rule.runOnIdle { survey.addFollowupQuestion("Q8", "Restored while reading?") }
        assertEquals(manualPosition, conversationPosition(), 0f)
        conversation.performScrollToNode(hasText("Restored while reading?"))
        rule.onNodeWithText("Restored while reading?").assertIsDisplayed()
    }

    @Test fun openingPositionWaitsForTheOriginalQuestion() {
        var rows by mutableStateOf(emptyList<AiViewModel.ChatItem>())
        rule.setContent {
            AiConversation(
                nodeId = "Q8", presentation = 1L, chatItems = rows, followups = emptyList(), loading = false,
                snack = remember { SnackbarHostState() }, modifier = Modifier.fillMaxSize()
            )
        }
        rule.onNodeWithText("Original question").assertDoesNotExist()
        rule.runOnIdle {
            rows = listOf(AiViewModel.ChatItem(
                id = "qroot-Q8", sender = AiViewModel.ChatSender.AI, text = "Original question"
            ))
        }
        assertQuestionFits("Original question")
    }

    @Test fun q07FailureNoticeIsFullyVisibleOnReturnFromBothAdjacentNodes() {
        val evaluation = ControlledResponse()
        val failed = ControlledResponse()
        repo.streamedResponses = listOf(evaluation, failed)
        host(twoStep = true, useNavDisplay = true, initialNode = "Q06")
        rule.onNodeWithText("Experience").assertIsDisplayed()
        rule.runOnIdle { survey.advanceToNext() }
        assertQuestionFits("Q07 original question")
        rule.runOnIdle {
            repeat(14) { ai.appendUserMessage(contextKey, "Earlier survey context. ".repeat(24)) }
        }
        enterAndSend("main answer")
        sendChunk(evaluation, NEEDS_FOLLOWUP, expected = NEEDS_FOLLOWUP)
        evaluation.finish()
        rule.waitUntil(5_000) { repo.requests == 2 }
        val score = chatRows().single { it.json == NEEDS_FOLLOWUP }
        val slot = assertScorePrecedesPreparation(score)
        rule.onNodeWithText("Back").performScrollTo().assertIsNotEnabled()
        failed.finish(IllegalStateException("generation failed"))
        assertUnavailableFollowup(score, slot)
        assertEquals("Q07", survey.currentNodeId)

        scrollManuallyTo(conversationIndex("qroot-Q07"))
        rule.onNodeWithText("Q07 original question").assertIsDisplayed()
        rule.onNodeWithText("Back").performScrollTo().performClick()
        rule.onNodeWithText("Experience").assertIsDisplayed()
        rule.runOnIdle { survey.advanceToNext() }
        assertLatestEdgeVisible(slot.id)
        rule.onNodeWithText("Follow-up generation failed").assertIsDisplayed()
        rule.onNodeWithText(PREPARING_FOLLOWUP).assertDoesNotExist()

        scrollManuallyTo(conversationIndex("qroot-Q07"))
        rule.onNodeWithText("Q07 original question").assertIsDisplayed()
        rule.onNodeWithText("Next").performScrollTo().assertIsEnabled().performClick()
        assertQuestionFits("Q08 original question")
        rule.runOnIdle { survey.backToPrevious() }
        assertLatestEdgeVisible(slot.id)
        assertOutcome("Follow-up generation failed", "We couldn't prepare another question.")
        rule.onNodeWithTag("restored-outcome-Q07").assertDoesNotExist()
    }

    @Test fun navigationIsRejectedDuringSubmissionReviewAndFollowupPreparation() {
        val reviewing = ControlledResponse()
        val preparing = ControlledResponse()
        repo.streamedResponses = listOf(reviewing, preparing)
        host(twoStep = true, useSurveyNavHost = true, initialNode = "Q06")
        rule.runOnIdle { survey.advanceToNext() }
        assertQuestionFits("Q07 original question")
        val key = contextKey
        val uuid = survey.surveyUuid.value
        val back = rule.onNodeWithText("Back")
        val next = rule.onNodeWithText("Next")
        val staleBack = clickAction(back.performScrollTo().assertIsEnabled())
        val staleNext = clickAction(next.performScrollTo().assertIsEnabled())

        fun rejectNavigation() {
            val stops = speech.stopCalls
            val answers = survey.answers.value
            val followups = survey.followups.value
            val conversation = ai.conversationStateFlow(key).value
            val rows = ai.chatHistoryFlow(key).value
            staleBack()
            staleNext()
            backDispatcher.onBackPressed()
            survey.backToPrevious()
            survey.advanceToNext()
            survey.goto("Q08")
            survey.replaceTo("Q08")
            survey.resetToStart()
            assertEquals("Q07", survey.currentNodeId)
            assertEquals(uuid, survey.surveyUuid.value)
            assertEquals(answers, survey.answers.value)
            assertEquals(followups, survey.followups.value)
            assertEquals(conversation, ai.conversationStateFlow(key).value)
            assertEquals(rows, ai.chatHistoryFlow(key).value)
            assertEquals(stops, speech.stopCalls)
        }

        fun startPredictiveBack() {
            backDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 250f, 0f, BackEventCompat.EDGE_LEFT))
            backDispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 250f, 0.5f, BackEventCompat.EDGE_LEFT))
        }

        fun assertActiveNavigationLocked() {
            back.performScrollTo().assertIsNotEnabled()
            next.performScrollTo().assertIsNotEnabled()
            rule.runOnIdle {
                rejectNavigation()
                assertTrue(ai.isRunning)
            }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.runOnIdle {
                assertEquals("Q07", survey.currentNodeId)
                assertTrue(ai.isRunning)
            }
        }

        rule.onNodeWithText("Text").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("main answer")
        val send = clickAction(rule.onNodeWithContentDescription("Send").assertIsEnabled())
        rule.runOnIdle {
            send()
            assertTrue(ai.submissionPending.value)
            assertTrue(!ai.isRunning)
            rejectNavigation()
            send()
            assertEquals("main answer", survey.getAnswer("Q07"))
            assertEquals(listOf("main answer"), userMessages())
            WindowCompat.getInsetsController(surveyWindow, hostView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        waitForKeyboard(visible = false)

        sendChunk(reviewing, NEEDS_FOLLOWUP, expected = NEEDS_FOLLOWUP)
        val scan = chatRows().last()
        assertEquals(AiViewModel.EvaluationPresentation.REVIEWING, scan.evaluation)
        assertLatestEdgeVisible(scan.id)
        assertActiveNavigationLocked()
        rule.runOnIdle {
            startPredictiveBack()
            backDispatcher.onBackPressed()
            assertEquals("Q07", survey.currentNodeId)
            assertTrue(ai.isRunning)
        }
        reviewing.finish()

        rule.waitUntil(5_000) { repo.requests == 2 }
        val score = chatRows().single { it.json == NEEDS_FOLLOWUP }
        val slot = assertScorePrecedesPreparation(score)
        assertLatestEdgeVisible(slot.id)
        assertActiveNavigationLocked()
        rule.runOnIdle { startPredictiveBack() }
        sendChunk(preparing, FIRST_FOLLOWUP, expected = FIRST_FOLLOWUP)
        preparing.finish()

        rule.waitUntil(5_000) { !ai.blocksNavigation() }
        next.performScrollTo().assertIsEnabled()
        back.performScrollTo().assertIsEnabled()
        rule.runOnIdle {
            backDispatcher.onBackPressed()
            assertEquals("Q07", survey.currentNodeId)
            assertEquals(uuid, survey.surveyUuid.value)
            assertEquals("main answer", survey.getAnswer("Q07"))
            assertEquals(listOf("main answer"), userMessages())
            assertEquals(2, repo.requests)
        }
        rule.onNodeWithText(FIRST_FOLLOWUP).assertExists()
        back.performClick()
        rule.runOnIdle { assertEquals("Q06", survey.currentNodeId) }
    }

    private fun clickAction(node: SemanticsNodeInteraction): () -> Boolean =
        checkNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)

    @Test fun evaluationTimeoutUnlocksWithoutNavigating() {
        repo.streamedResponses = listOf(ControlledResponse())
        host(twoStep = true, useSurveyNavHost = true, aiTimeoutMs = 3_000L)
        rule.onNodeWithText("Text").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("main answer")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitUntil(8_000) { repo.requests == 1 && !ai.blocksNavigation() }
        assertEquals(SurveyAiReason.FAILURE, survey.aiReasons.value["Q8"])
        assertEquals(setOf("Q8"), survey.submittedAiOriginals.value)
        assertEquals("Q8", survey.currentNodeId)
        rule.onNodeWithText("Next").performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals("Q9", survey.currentNodeId) }
    }

    @Test fun cancellingOneStepTypingUnlocksBackAndRetainsTheAnswer() {
        val typing = ControlledResponse()
        repo.streamedResponses = listOf(typing)
        host(useNavDisplay = true)
        appendConversationHistory()
        enterAndSend("main answer")
        sendChunk(typing, "Partial output!!", expected = "Partial output!!")
        rule.onNodeWithText("Partial output!!").assertIsDisplayed()
        val history = ai.chatHistoryFlow(contextKey)
        val typingRow = history.value.last()
        assertTrue(typingRow.isTyping)
        assertLatestEdgeVisible(typingRow.id)

        rule.onNodeWithText("Back").performScrollTo().assertIsNotEnabled()
        rule.runOnIdle { ai.cancel() }
        rule.waitUntil(5_000) { !ai.blocksNavigation() }
        assertEquals("Q8", survey.currentNodeId)
        rule.onNodeWithText("Back").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("Home").assertIsDisplayed()
        rule.waitUntil(5_000) { history.value.none { it.isTyping } }
        rule.runOnIdle {
            assertTrue(!ai.isRunning)
            survey.goto("Q8")
        }
        rule.onNodeWithTag(typingRow.id).assertDoesNotExist()
        assertLatestEdgeVisible(history.value.last { it.text == "main answer" }.id)
    }

    @Test fun reentryUsesRenderedRowOrderAcrossAiNodes() {
        val finalRows = listOf(
            AiViewModel.ChatItem("final-question", AiViewModel.ChatSender.AI, FIRST_FOLLOWUP,
                isFollowupQuestion = true),
            AiViewModel.ChatItem("final-answer", AiViewModel.ChatSender.USER, "Final answer"),
            AiViewModel.ChatItem("final-evaluation", AiViewModel.ChatSender.AI, json = NEEDS_FOLLOWUP,
                evaluation = AiViewModel.EvaluationPresentation.REVIEWED),
            AiViewModel.ChatItem("final-notice", AiViewModel.ChatSender.AI, "Survey response complete")
        )
        var nodeId by mutableStateOf("Q07")
        var presentation by mutableStateOf(1L)
        var finalRow by mutableStateOf(finalRows.first())
        var showHistory by mutableStateOf(false)
        val history = List(14) { index ->
            AiViewModel.ChatItem("history-$index", AiViewModel.ChatSender.USER,
                text = "Earlier survey context. ".repeat(24))
        }
        rule.setContent {
            key(nodeId) {
                val root = AiViewModel.ChatItem("qroot-$nodeId", AiViewModel.ChatSender.AI, "Original question")
                AiConversation(
                    nodeId = nodeId, presentation = presentation,
                    chatItems = if (showHistory) listOf(root) + history + finalRow else listOf(root),
                    followups = emptyList(), loading = false, fieldEvaluation = true,
                    snack = remember { SnackbarHostState() }, modifier = Modifier.fillMaxSize()
                )
            }
        }

        for (number in 7..16) {
            rule.runOnIdle {
                nodeId = "Q" + number.toString().padStart(2, '0')
                showHistory = false
                finalRow = finalRows[(number - 7) % finalRows.size]
            }
            assertQuestionFits("Original question")
            rule.runOnIdle { showHistory = true }
            scrollManuallyTo(conversationIndex("qroot-$nodeId"))
            rule.onNodeWithText("Original question").assertIsDisplayed()
            rule.runOnIdle { presentation++ }
            assertLatestEdgeVisible(finalRow.id)
        }
    }

    @Test fun fallbackBelongsToTheCurrentTurnAfterAnEarlierOutcome() {
        val root = AiViewModel.ChatItem("qroot-Q8", AiViewModel.ChatSender.AI, "Original question")
        val previousOutcome = AiViewModel.ChatItem(
            "previous-outcome", AiViewModel.ChatSender.AI, outcome = AiTurnOutcome.EVALUATION_FAILED
        )
        val newAnswer = AiViewModel.ChatItem("new-answer", AiViewModel.ChatSender.USER, "New answer")
        val acceptedFollowup = AiViewModel.ChatItem(
            "accepted-followup", AiViewModel.ChatSender.AI, FIRST_FOLLOWUP, isFollowupQuestion = true
        )
        var rows by mutableStateOf(listOf(root, previousOutcome, newAnswer))
        var fallback by mutableStateOf<AiTurnOutcome?>(AiTurnOutcome.UNKNOWN)
        rule.setContent {
            AiConversation(
                nodeId = "Q8", presentation = 1L, chatItems = rows, followups = emptyList(), loading = false,
                snack = remember { SnackbarHostState() }, modifier = Modifier.fillMaxSize(), fieldEvaluation = true,
                fallbackOutcome = fallback
            )
        }

        rule.onAllNodesWithText("AI review unavailable").assertCountEquals(1)
        rule.onNodeWithTag(previousOutcome.id).assertIsDisplayed()
        assertLatestEdgeVisible("restored-outcome-Q8")

        rule.runOnIdle {
            rows = listOf(root, previousOutcome, acceptedFollowup)
            fallback = null
        }
        assertQuestionFits(FIRST_FOLLOWUP)
        rule.onNodeWithText("AI review unavailable").assertDoesNotExist()
        rule.onNodeWithTag(previousOutcome.id).assertIsDisplayed()

        // A published follow-up also starts a new turn, even without a retained user row.
        rule.runOnIdle { fallback = AiTurnOutcome.UNKNOWN }
        conversation.performScrollToIndex(0)
        rule.onAllNodesWithText("AI review unavailable").assertCountEquals(1)
        rule.onNodeWithTag(previousOutcome.id).assertIsDisplayed()
        assertLatestEdgeVisible("restored-outcome-Q8")
    }

    @Test fun keyboardResizeKeepsTheReentryAnswerVisibleAboveTheComposer() {
        host(twoStep = true, useNavDisplay = true, productionImeInsets = true)
        appendConversationHistory()
        val answerId = rule.runOnIdle { ai.appendUserMessage(contextKey, "Latest answer") }
        rule.runOnIdle { survey.goto("Done") }
        rule.onNodeWithText("Away from AI").assertIsDisplayed()
        rule.runOnIdle { survey.backToPrevious() }
        assertLatestEdgeVisible(answerId)

        rule.onNodeWithText("Text").performClick()
        waitForKeyboard(visible = true)
        assertLatestEdgeVisible(answerId)
        val answerBottom = rule.onNodeWithTag(answerId).fetchSemanticsNode().boundsInRoot.bottom
        val composerTop = rule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.top
        assertTrue(answerBottom <= composerTop)
        rule.onNodeWithContentDescription("Send").assertIsDisplayed()

        scrollManuallyTo(8)
        val manualPosition = conversationPosition()
        dismissKeyboardWithBack()
        assertEquals(manualPosition, conversationPosition(), 0f)
    }

    @Test fun publicationWhileViewportIsUnavailableUsesTheRowsAtTheNextLayout() {
        var hasViewport by mutableStateOf(true)
        var rows by mutableStateOf(listOf(
            AiViewModel.ChatItem("qroot-Q8", AiViewModel.ChatSender.AI, text = "Original question")
        ))
        rule.setContent {
            AiConversation(
                nodeId = "Q8", presentation = 1L, chatItems = rows, followups = emptyList(),
                loading = false, snack = remember { SnackbarHostState() },
                modifier = if (hasViewport) Modifier.fillMaxSize() else Modifier.height(0.dp)
            )
        }
        rule.onNodeWithText("Original question").assertIsDisplayed()
        rule.runOnIdle {
            rows = rows + List(14) { index ->
                AiViewModel.ChatItem(
                    "history-$index", AiViewModel.ChatSender.USER, text = "Earlier context. ".repeat(24)
                )
            }
            hasViewport = false
        }
        rule.runOnIdle {
            rows = rows + AiViewModel.ChatItem(
                "accepted-followup", AiViewModel.ChatSender.AI, text = FIRST_FOLLOWUP,
                isFollowupQuestion = true
            )
        }
        rule.runOnIdle {
            // Shift the target's index before the pending request can use the viewport.
            rows = rows + AiViewModel.ChatItem(
                "trailing-row", AiViewModel.ChatSender.USER, text = "Trailing context. ".repeat(300)
            )
        }
        rule.runOnIdle { hasViewport = true }
        rule.onNodeWithText(FIRST_FOLLOWUP).assertIsDisplayed()
    }

    @Test fun questionStaysVisibleThroughVoiceTranscriptionAndKeyboardResizing() {
        host(twoStep = true)
        waitForKeyboard(visible = false)
        appendConversationHistory()
        assertQuestionFits("Question")

        rule.onNodeWithText("Tap to answer").performClick()
        rule.runOnIdle { speech.capture.value = true }
        rule.onNode(hasText("Question") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        rule.onNodeWithText("Stop").performClick()
        rule.runOnIdle { speech.transitionToTranscribing() }
        waitForKeyboard(visible = false)
        assertQuestionFits("Question")
        rule.runOnIdle { speech.finish("recognized answer") }
        waitForKeyboard(visible = false)
        assertQuestionFits("Question")

        rule.onNodeWithText("recognized answer").performClick()
        waitForKeyboard(visible = true)
        rule.waitUntil(5_000) { rule.onNodeWithText("Question").isDisplayed() }
        assertQuestionFits("Question")
        rule.runOnIdle { constrained = true }
        assertQuestionFits("Question")
        rule.onNode(hasSetTextAction()).assertIsDisplayed().assertIsFocused()
        dismissKeyboardWithBack()
        assertQuestionFits("Question")
        rule.runOnIdle { unrelated = true }
        waitForKeyboard(visible = false)
        rule.onNodeWithText("Voice").performClick()
        waitForKeyboard(visible = false)
        assertQuestionFits("Question")
    }

    @Test fun oversizedFollowupKeepsItsEndAndComposerVisibleWithTheIme() {
        val question = "How did drought affect your maize yield, the amount sold at the local market, " +
            "the household grain reserves, and the quantities you planned to save for seed before the next planting season?"
        repo.responses = listOf(NEEDS_FOLLOWUP, question)
        host(twoStep = true, largeText = true, constrainedHeight = 550.dp)
        enterAndSend("main answer")
        repo.release.complete(Unit)
        waitForFollowup(question)
        waitForKeyboard(visible = true)
        rule.runOnIdle { constrained = true }

        val bubble = rule.onNodeWithText(question)
        bubble.assertIsDisplayed()
        val node = bubble.fetchSemanticsNode()
        val viewport = conversation.fetchSemanticsNode().boundsInRoot
        val geometry = "question size=${node.size}, visible=${node.boundsInRoot}, viewport=$viewport"
        assertTrue(geometry, node.size.height > viewport.height)
        assertTrue(geometry, node.boundsInRoot.height >= viewport.height * 0.7f)
        assertTrue(geometry, kotlin.math.abs(node.boundsInRoot.bottom - viewport.bottom) < 30)
        rule.onNode(hasSetTextAction()).assertIsDisplayed().assertIsFocused()
        rule.onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    @Test fun onlyAcceptedFollowupsInterruptReadingHistoryAcrossTwoGenerations() {
        val streamedEvaluation = NEEDS_FOLLOWUP.replaceFirst("{", "{" + " ".repeat(128))
        repo.responses = listOf(streamedEvaluation, FIRST_FOLLOWUP, NEEDS_FOLLOWUP, SECOND_FOLLOWUP)
        val streamed = CompletableDeferred<Unit>()
        repo.pauseAfterFirstChunk = streamed
        val restoration = StateRestorationTester(rule)
        host(twoStep = true, restoration = restoration)
        appendConversationHistory()
        enterAndSend("main answer")
        rule.waitUntil(5_000) { repo.requests == 1 }
        scrollToOriginalQuestion()
        val originalTop = originalQuestionTop()

        repo.release.complete(Unit)
        rule.waitUntil(5_000) { ai.stream.value.startsWith("{") }
        assertEquals(originalTop, originalQuestionTop(), 1f)
        rule.runOnIdle {
            constrained = true
            streamed.complete(Unit)
        }

        fun selectSpeak() {
            val speak = rule.onNodeWithText("Voice").assertIsEnabled()
            // In a very short IME viewport the selector shares the scrollable footer.
            if (!speak.isDisplayed()) speak.performScrollTo()
            speak.performClick().assertIsSelected()
        }

        waitForFollowup(FIRST_FOLLOWUP)
        selectSpeak()
        waitForKeyboard(visible = false)
        // Measure away from either end, where changing the viewport can clamp the range.
        scrollManuallyTo(8)
        val manualPosition = conversationPosition()
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(manualPosition, conversationPosition(), 0f)
        rule.onNodeWithText("Text").performClick()
        waitForKeyboard(visible = true)
        rule.onNode(hasSetTextAction()).performTextInput("draft edit")
        assertEquals(manualPosition, conversationPosition(), 0f)
        selectSpeak()
        waitForKeyboard(visible = false)
        assertEquals(manualPosition, conversationPosition(), 0f)
        rule.runOnIdle { survey.setAnswer("Updated main answer", "Q8") }
        repeat(3) {
            rule.runOnIdle {
                val published = ai.chatHistoryFlow(contextKey).value.last { it.isFollowupQuestion }
                ai.upsertChatItem(contextKey, published.copy(createdAtUptimeMs = published.createdAtUptimeMs + 1))
                unrelated = !unrelated
            }
            assertEquals(manualPosition, conversationPosition(), 0f)
        }

        // A transcript changes the draft without creating a new conversation question.
        rule.onNodeWithText("Tap to answer").performScrollTo().performClick()
        rule.runOnIdle { speech.transitionToTranscribing() }
        rule.waitForIdle()
        rule.runOnIdle { speech.finish("two bags") }
        assertEquals(manualPosition, conversationPosition(), 0f)
        rule.onNodeWithContentDescription("Send").performClick()
        waitForFollowup(SECOND_FOLLOWUP)
        rule.onNodeWithText("Voice").assertIsSelected()
        waitForKeyboard(visible = false)
        rule.runOnIdle {
            val entries = survey.followups.value.getValue("Q8")
            assertEquals(listOf(FIRST_FOLLOWUP, SECOND_FOLLOWUP), entries.map { it.question })
            assertEquals("two bags", entries.first().answer)
        }
    }

    @Test fun savedScrollSurvivesRestorationAndFreshNavigationShowsTheLatestQuestion() {
        repo.responses = listOf(
            """{"score":30,"missing_points":["yield loss"],"followup_needed":true,"followup":"$FIRST_FOLLOWUP"}"""
        )
        val restoration = StateRestorationTester(rule)
        host(restoration = restoration, useNavDisplay = true)
        appendConversationHistory()
        enterAndSend("main answer")
        repo.release.complete(Unit)
        waitForFollowup(FIRST_FOLLOWUP)
        rule.onNodeWithText("Voice").performClick()
        waitForKeyboard(visible = false)
        scrollToOriginalQuestion()
        val manualPosition = conversationPosition()

        restoration.emulateSavedInstanceStateRestore()
        assertEquals(manualPosition, conversationPosition(), 0f)
        rule.onNodeWithText("Question").assertIsDisplayed()

        rule.onNodeWithText("Text").performClick()
        waitForKeyboard(visible = true)
        rule.runOnIdle { survey.goto("Done") }
        rule.onNodeWithText("Away from AI").assertIsDisplayed()
        waitForKeyboard(visible = false)
        rule.waitForIdle()
        rule.runOnIdle { survey.backToPrevious() }
        rule.onNodeWithText(FIRST_FOLLOWUP).assertIsDisplayed()

        scrollToOriginalQuestion()
        rule.runOnIdle { survey.backToPrevious() }
        rule.onNodeWithText("Home").assertIsDisplayed()
        rule.waitForIdle()
        rule.runOnIdle { survey.goto("Q8") }
        rule.onNodeWithText(FIRST_FOLLOWUP).assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(1, survey.followups.value.getValue("Q8").size)
            assertEquals(1, ai.chatHistoryFlow(contextKey).value.count { it.isFollowupQuestion })
        }
    }

    private val conversation get() = rule.onNodeWithTag("AI conversation")

    private fun conversationPosition(): Float =
        conversation.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun originalQuestionTop(): Float {
        val original = rule.onNodeWithText("Question")
        original.assertIsDisplayed()
        return original.fetchSemanticsNode().boundsInRoot.top
    }

    private fun appendConversationHistory() {
        rule.runOnIdle {
            repeat(14) { index ->
                ai.appendUserMessage(contextKey, "Answer ${index + 1}. " + "Earlier survey context. ".repeat(10))
            }
        }
        rule.waitForIdle()
        assertQuestionFits("Question")
    }

    private fun scrollManuallyTo(index: Int) {
        if (conversationPosition() == 0f) {
            conversation.performTouchInput { swipeDown() }
        } else {
            conversation.performTouchInput { swipeUp() }
        }
        conversation.performScrollToIndex(index)
    }

    private fun conversationIndex(key: String): Int =
        conversation.fetchSemanticsNode().config[SemanticsProperties.IndexForKey](key).also {
            assertTrue("Missing conversation row $key", it >= 0)
        }

    private fun scrollToOriginalQuestion() {
        scrollManuallyTo(conversationIndex("qroot-Q8"))
        rule.onNodeWithText("Question").assertIsDisplayed()
    }

    private fun enterAndSend(answer: String) {
        rule.onNodeWithText("Text").performClick()
        rule.onNode(hasSetTextAction()).performTextInput(answer)
        waitForKeyboard(visible = true)
        rule.onNodeWithContentDescription("Send").performClick()
        waitForKeyboard(visible = true)
    }

    private fun assertQuestionFits(question: String) {
        val target = rule.onNodeWithText(question)
        target.assertIsDisplayed()
        val node = target.fetchSemanticsNode()
        val viewport = conversation.fetchSemanticsNode().boundsInRoot
        assertEquals(node.size.height.toFloat(), node.boundsInRoot.height, 1f)
        assertTrue(node.boundsInRoot.top >= viewport.top && node.boundsInRoot.bottom <= viewport.bottom)
    }

    private fun assertInactiveComposer() {
        val editor = rule.onNode(hasSetTextAction())
        val semantics = editor.fetchSemanticsNode().config
        assertEquals("", semantics[SemanticsProperties.EditableText].text)
        editor.assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Answer input inactive"
        ))
        rule.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    private fun userMessages() = ai.chatHistoryFlow(contextKey).value
        .filter { it.sender == AiViewModel.ChatSender.USER }.map { it.text }

    private fun assertLatestEdgeVisible(rowId: String, oversized: Boolean = false) {
        val row = rule.onNodeWithTag(rowId).assertIsDisplayed().fetchSemanticsNode()
        val viewport = conversation.fetchSemanticsNode().boundsInRoot
        val unclippedBottom = row.positionInRoot.y + row.size.height
        assertTrue(unclippedBottom <= viewport.bottom + 1f)
        assertTrue(row.boundsInRoot.top >= viewport.top - 1f)
        assertTrue(row.boundsInRoot.bottom <= viewport.bottom + 1f)
        if (oversized) {
            assertTrue(row.size.height > viewport.height)
            assertEquals(viewport.height, row.boundsInRoot.height, 2f)
        } else {
            assertEquals(row.size.height.toFloat(), row.boundsInRoot.height, 1f)
        }
    }

    private fun dismissKeyboardWithBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitForKeyboard(visible = false)
    }

    private fun waitForFollowup(question: String) {
        rule.waitUntil(5_000) {
            survey.followups.value["Q8"]?.lastOrNull()?.question == question &&
                !ai.loading.value && !ai.isRunning
        }
        rule.onNodeWithText(question).assertIsDisplayed()
    }

    private fun waitForKeyboard(visible: Boolean) {
        rule.waitUntil(5_000) {
            rule.runOnUiThread {
                ViewCompat.getRootWindowInsets(hostView)?.isVisible(WindowInsetsCompat.Type.ime()) == visible
            }
        }
    }

    @Test fun twoStepMainAndFollowupSendKeepTheKeyboardAndPreserveAnswers() {
        repo.responses = listOf(
            NEEDS_FOLLOWUP.replaceFirst("{", "{" + " ".repeat(128)),
            "How much yield was lost?",
            """{"score":95,"missing_points":[],"followup_needed":false}""",
        )
        val finishStream = CompletableDeferred<Unit>()
        repo.pauseAfterFirstChunk = finishStream
        host(twoStep = true)
        rule.onNodeWithText("Text").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("main answer")
        waitForKeyboard(visible = true)
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitUntil(5_000) { repo.requests == 1 }
        waitForKeyboard(visible = true)
        assertInactiveComposer()
        rule.onNode(hasSetTextAction()).performTextInput("blocked during evaluation")
        assertInactiveComposer()
        assertLatestEdgeVisible("typing-Q8")
        rule.runOnIdle { assertEquals("main answer", survey.getAnswer("Q8")); repo.release.complete(Unit) }
        rule.waitUntil(5_000) { ai.stream.value.startsWith("{") }
        waitForKeyboard(visible = true)
        assertLatestEdgeVisible("typing-Q8")
        finishStream.complete(Unit)
        rule.waitUntil(5_000) {
            ai.conversationStateFlow(contextKey).value.role == AiViewModel.ComposerRole.FOLLOWUP
        }
        rule.waitForIdle()
        waitForKeyboard(visible = true)
        assertQuestionFits(FIRST_FOLLOWUP)
        rule.onNodeWithText("Text").assertIsSelected()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        rule.runOnIdle {
            assertEquals(2, repo.requests)
            assertEquals("main answer", survey.getAnswer("Q8"))
            assertEquals("How much yield was lost?", survey.followups.value["Q8"]?.single()?.question)
            assertEquals(listOf("Question", "How much yield was lost?"), speaker.spoken)
        }
        rule.onNode(hasSetTextAction()).performTextInput("two bags")
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitUntil(5_000) { ai.conversationStateFlow(contextKey).value.turnCompleted }
        rule.waitForIdle()
        assertOutcome("Answer complete", "Your answer meets the requirements. No follow-up question is needed.")
        assertReviewed(95)
        rule.runOnIdle {
            assertEquals(listOf("main answer", "two bags"), userMessages())
            assertEquals(3, repo.requests)
            assertEquals("main answer", survey.getAnswer("Q8"))
            assertEquals("two bags", survey.followups.value["Q8"]?.single()?.answer)
            assertEquals("Q8", survey.currentNode.value.id)
        }
        rule.onNodeWithText("Next").performClick()
        waitForKeyboard(visible = false)
        rule.runOnIdle { assertEquals("Q9", survey.currentNode.value.id) }
    }

    @Test fun oneStepFollowupSendKeepsTheImeAndExplicitDismissalPersists() {
        repo.responses = listOf(
            """{"score":30,"missing_points":["yield loss"],"followup_needed":true,"followup":"$FIRST_FOLLOWUP"}""",
            """{"score":95,"missing_points":[],"followup_needed":false}"""
        )
        val finishFollowup = CompletableDeferred<Unit>()
        repo.pauseSecondRequest = finishFollowup
        host()
        enterAndSend("main answer")
        repo.release.complete(Unit)
        waitForFollowup(FIRST_FOLLOWUP)
        waitForKeyboard(visible = true)
        assertQuestionFits(FIRST_FOLLOWUP)
        rule.onNode(hasSetTextAction()).assertIsFocused().performTextInput("two bags")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitUntil(5_000) { repo.requests == 2 }
        waitForKeyboard(visible = true)
        assertInactiveComposer()
        assertLatestEdgeVisible("typing-Q8")
        dismissKeyboardWithBack()
        finishFollowup.complete(Unit)
        rule.waitUntil(5_000) { !ai.loading.value && !ai.isRunning }
        rule.runOnIdle { unrelated = true }
        waitForKeyboard(visible = false)
        rule.onNodeWithText("Score 95 / 100", substring = true).assertIsDisplayed()
        rule.runOnIdle {
            assertEquals("two bags", survey.followups.value.getValue("Q8").single().answer)
            assertEquals(listOf("main answer", "two bags"), userMessages())
        }
    }

    @Test fun followupDoesNotReopenAnImeDismissedDuringInference() {
        repo.responses = listOf(NEEDS_FOLLOWUP, FIRST_FOLLOWUP)
        host(twoStep = true)
        enterAndSend("main answer")
        rule.waitUntil(5_000) { repo.requests == 1 }
        dismissKeyboardWithBack()

        repo.release.complete(Unit)
        waitForFollowup(FIRST_FOLLOWUP)
        waitForKeyboard(visible = false)
        assertQuestionFits(FIRST_FOLLOWUP)
        rule.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    @Test fun followLatestRevealsGrowingStreamAndExpandedJsonAboveTheIme() {
        host()
        appendConversationHistory()
        enterAndSend("submitted answer")
        rule.waitUntil(5_000) { repo.requests == 1 }
        assertLatestEdgeVisible("typing-Q8")

        rule.runOnIdle {
            ai.upsertTypingMessage(contextKey, "Q8", ("Stream line\n").repeat(120) + "Last streamed line")
        }
        assertLatestEdgeVisible("typing-Q8", oversized = true)

        fun publishJson(lines: Int) {
            rule.runOnIdle {
                ai.upsertChatItem(contextKey, AiViewModel.ChatItem(
                    id = "evaluation", sender = AiViewModel.ChatSender.AI,
                    json = "{\n\"score\": 76,\n" + "\"data\": [\n" +
                        "123,\n".repeat(lines) + "0]\n}"
                ))
            }
        }
        publishJson(1)
        assertLatestEdgeVisible("evaluation")
        rule.onNodeWithText("Score 76 / 100", substring = true).assertIsDisplayed().performClick()
        publishJson(130)
        assertLatestEdgeVisible("evaluation", oversized = true)
        waitForKeyboard(visible = true)
        assertInactiveComposer()

        scrollToOriginalQuestion()
        val originalTop = originalQuestionTop()
        publishJson(140)
        assertEquals(originalTop, originalQuestionTop(), 1f)
        rule.runOnIdle {
            ai.upsertChatItem(contextKey, AiViewModel.ChatItem(
                "accepted-followup", AiViewModel.ChatSender.AI, FIRST_FOLLOWUP,
                isFollowupQuestion = true
            ))
        }
        assertQuestionFits(FIRST_FOLLOWUP)
        conversation.performScrollToIndex(1)
        rule.onNodeWithTag("evaluation").assertIsDisplayed()
        rule.onNodeWithText("Jump to bottom", ignoreCase = true).assertDoesNotExist()
    }

    @Test fun incrementalStreamingTracksTheGrowingEndAcrossImeViewportResize() {
        host()
        appendConversationHistory()
        enterAndSend("submitted answer")
        rule.waitUntil(5_000) { repo.requests == 1 }

        val typingId = "typing-Q8"
        val initialViewport = conversationViewport()
        var previous = rowGeometry(typingId)
        var previousViewport = initialViewport
        var growingUpdates = 0
        repeat(36) { update ->
            if (update == 18) {
                rule.runOnIdle { constrained = true }
                waitForKeyboard(visible = true)
                assertTrue(conversationViewport().height < initialViewport.height)
            }
            rule.runOnIdle {
                ai.upsertTypingMessage(contextKey, "Q8", "Stream line\n".repeat(update + 1) + "Newest line")
            }

            val viewport = conversationViewport()
            val row = rowGeometry(typingId)
            val growth = row.height - previous.height
            val viewportChange = abs(viewport.top - previousViewport.top) +
                abs(viewport.bottom - previousViewport.bottom)
            // Measure the full row, including its clipped part, relative to the usable viewport.
            val previousTop = previous.top - previousViewport.bottom
            val top = row.top - viewport.bottom
            assertTrue("Update $update displaced the row beyond growth and resize",
                abs(top - previousTop) <= growth + viewportChange + 2f)
            assertTrue("Update $update reversed direction during growth",
                top <= previousTop + viewportChange + 1f)
            assertLatestEdgeVisible(typingId, oversized = row.height > viewport.height)
            if (growth > 0 && top < previousTop) growingUpdates++
            previous = row
            previousViewport = viewport
        }
        assertTrue("The row should move through the viewport during incremental growth", growingUpdates >= 30)
        assertRowSettles(typingId)
        assertInactiveComposer()
    }

    @Test fun scoreAndJsonFollowInStepsAndOnlyNewFollowupOverridesReading() {
        host()
        appendConversationHistory()
        enterAndSend("submitted answer")
        rule.waitUntil(5_000) { repo.requests == 1 }
        val typingId = "typing-Q8"
        val scoreId = "response-score"
        val jsonId = "evaluation"
        rule.runOnIdle {
            ai.upsertTypingMessage(contextKey, "Q8", "Stream line\n".repeat(80) + "Newest line")
        }
        assertLatestEdgeVisible(typingId, oversized = true)

        assertPublicationScrollsInSteps(typingId) {
            ai.upsertChatItem(contextKey, AiViewModel.ChatItem(
                scoreId, AiViewModel.ChatSender.AI, "Response Score\n76 points\nEvaluation complete"
            ))
        }
        assertLatestEdgeVisible(scoreId)
        assertPublicationScrollsInSteps(scoreId) { publishGrowingJson(jsonId, 3) }
        assertLatestEdgeVisible(jsonId)
        rule.onNodeWithText("Score 76 / 100", substring = true)
            .assertIsDisplayed().performClick()

        for (lines in listOf(12, 26, 48, 60)) {
            val before = rowGeometry(jsonId)
            rule.runOnIdle { publishGrowingJson(jsonId, lines) }
            val viewport = conversationViewport()
            val after = rowGeometry(jsonId)
            assertLatestEdgeVisible(jsonId, oversized = after.height > viewport.height)
            assertTrue("JSON should grow toward older content", after.top <= before.top + 1f)
            assertTrue("JSON growth should preserve its newest edge", abs(after.bottom - before.bottom) <= 1f)
        }
        rule.runOnIdle { publishGrowingJson(jsonId, 64, finalized = true) }
        assertLatestEdgeVisible(jsonId, oversized = true)
        assertRowSettles(jsonId)
        waitForKeyboard(visible = true)
        assertInactiveComposer()

        // A drag into the tall JSON reaches earlier content, then move deeper into history.
        val jsonEnd = rowGeometry(jsonId).bottom
        conversation.performTouchInput {
            swipeDown(startY = height * 0.35f, endY = height * 0.65f, durationMillis = 700)
        }
        assertTrue("Manual reading should expose earlier JSON", rowGeometry(jsonId).bottom > jsonEnd)
        scrollToOriginalQuestion()
        val originalTop = originalQuestionTop()
        rule.runOnIdle {
            publishGrowingJson(jsonId, 68, finalized = true)
            ai.upsertTypingMessage(contextKey, "Q8", "Stream line\n".repeat(90))
        }
        assertEquals(originalTop, originalQuestionTop(), 1f)

        val followup = AiViewModel.ChatItem(
            "new-followup", AiViewModel.ChatSender.AI, FIRST_FOLLOWUP, isFollowupQuestion = true
        )
        rule.mainClock.autoAdvance = false
        try {
            rule.runOnIdle { ai.upsertChatItem(contextKey, followup) }
            // Apply publication and its immediate position before an ordinary follow could
            // traverse this many history rows.
            repeat(3) { rule.mainClock.advanceTimeByFrame() }
            assertQuestionFits(FIRST_FOLLOWUP)
        } finally {
            rule.mainClock.autoAdvance = true
        }
        scrollToOriginalQuestion()
        val readingTop = originalQuestionTop()
        rule.runOnIdle {
            ai.upsertChatItem(contextKey, followup.copy(createdAtUptimeMs = followup.createdAtUptimeMs + 1))
        }
        assertEquals(readingTop, originalQuestionTop(), 1f)
    }

    private fun publishGrowingJson(id: String, lines: Int, finalized: Boolean = false) {
        val lastLine = if (finalized) "1" else "0"
        ai.upsertChatItem(contextKey, AiViewModel.ChatItem(
            id = id, sender = AiViewModel.ChatSender.AI,
            json = "{\n\"score\": 76,\n\"data\": [\n" + "123,\n".repeat(lines) + "$lastLine]\n}"
        ))
    }

    private fun conversationViewport(): Rect = conversation.fetchSemanticsNode().boundsInRoot

    private fun rowGeometry(id: String): Rect {
        val node = rule.onNodeWithTag(id).fetchSemanticsNode()
        return Rect(
            node.positionInRoot.x, node.positionInRoot.y,
            node.positionInRoot.x + node.size.width, node.positionInRoot.y + node.size.height
        )
    }

    private fun assertPublicationScrollsInSteps(anchorId: String, publish: () -> Unit) {
        val viewport = conversationViewport()
        var previous = rowGeometry(anchorId).bottom
        var movingFrames = 0
        rule.mainClock.autoAdvance = false
        try {
            rule.runOnIdle(publish)
            repeat(16) {
                rule.mainClock.advanceTimeByFrame()
                val next = rowGeometry(anchorId).bottom
                val movement = previous - next
                assertTrue("A new output reversed scrolling by $movement px", movement >= -1f)
                assertTrue("A new output moved $movement px in one frame of a ${viewport.height} px viewport",
                    movement <= viewport.height / 12f + 1f)
                if (movement > 1f) movingFrames++
                previous = next
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
        assertTrue("A newly inserted row should be revealed over multiple frames", movingFrames >= 2)
        assertRowSettles(anchorId)
    }

    private fun assertRowSettles(id: String) {
        val settled = rowGeometry(id)
        rule.mainClock.autoAdvance = false
        try {
            repeat(8) {
                rule.mainClock.advanceTimeByFrame()
                assertEquals(settled, rowGeometry(id))
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    @Test fun shortScoreStreamsAtEightCharactersAndRetainsFocusAfterImeSend() {
        val score = """{"score":95,"missing_points":[],"notes":"complete!"}"""
        assertEquals(52, score.length)
        val response = ControlledResponse()
        repo.streamedResponses = listOf(response)
        host(productionImeInsets = true)
        rule.onNodeWithText("Text").performClick()
        val editor = rule.onNode(hasSetTextAction())
        editor.performTextInput("main answer")
        waitForKeyboard(visible = true)
        editor.performImeAction()
        rule.waitUntil(5_000) { repo.requests == 1 }
        assertInactiveComposer()
        editor.assertIsFocused()
        assertEquals("main answer", survey.getAnswer("Q8"))
        val editorBounds = editor.fetchSemanticsNode().boundsInRoot
        val viewport = conversationViewport()

        sendChunk(response, score.take(7), expected = "")
        sendChunk(response, score.substring(7, 8), expected = score.take(8))
        assertStreamingEdge(score.take(8))
        for (end in 16..48 step 8) {
            sendChunk(response, score.substring(end - 8, end), expected = score.take(end))
            assertStreamingEdge(score.take(end))
            waitForKeyboard(visible = true)
            editor.assertIsFocused()
            assertEquals(editorBounds, editor.fetchSemanticsNode().boundsInRoot)
            assertEquals(viewport, conversationViewport())
        }
        dismissKeyboardWithBack()
        rule.waitUntil(5_000) { conversationViewport().height > viewport.height }
        editor.assertIsFocused()
        assertStreamingEdge(score.take(48))
        sendChunk(response, score.drop(48), expected = score.take(48))
        response.finish()
        rule.waitUntil(5_000) { !ai.isRunning && ai.stream.value == score }
        assertEquals(score, ai.stepHistory.value.single().raw)
        rule.onNodeWithText("Score 95 / 100", substring = true).assertIsDisplayed()
        waitForKeyboard(visible = false)
    }

    @Test fun completedTwoStepScoreIsVisibleWhileFollowupWaitsForItsFirstChunk() {
        val evaluation = ControlledResponse()
        val followup = ControlledResponse()
        repo.streamedResponses = listOf(evaluation, followup)
        host(twoStep = true)
        enterAndSend("main answer")
        sendChunk(evaluation, NEEDS_FOLLOWUP.take(8), expected = NEEDS_FOLLOWUP.take(8))
        assertScan(NEEDS_FOLLOWUP.take(8))
        sendChunk(evaluation, NEEDS_FOLLOWUP.drop(8), expected = NEEDS_FOLLOWUP)
        evaluation.finish()

        rule.waitUntil(5_000) { repo.requests == 2 }
        assertReviewed(30)
        val score = chatRows().single { it.json == NEEDS_FOLLOWUP }
        val slot = assertScorePrecedesPreparation(score)
        rule.onNodeWithText(PREPARING_FOLLOWUP).assertIsDisplayed()
        assertLatestEdgeVisible(slot.id)
        assertEquals(1, chatRows().count { it.json == NEEDS_FOLLOWUP || it.text == NEEDS_FOLLOWUP })
        sendChunk(followup, FIRST_FOLLOWUP, expected = FIRST_FOLLOWUP)
        rule.onNodeWithText(FIRST_FOLLOWUP).assertDoesNotExist()
        assertEquals(slot, assertScorePrecedesPreparation(score))

        followup.finish()
        waitForFollowup(FIRST_FOLLOWUP)
        assertEquals(slot.id, chatRows().single { it.isFollowupQuestion }.id)
        assertEquals(1, chatRows().count { it.text == FIRST_FOLLOWUP })
        rule.onNodeWithText(PREPARING_FOLLOWUP).assertDoesNotExist()
    }

    @Test fun longEvaluationStreamsAndFollowupPreparationRespectsManualReading() {
        val evaluation = NEEDS_FOLLOWUP.dropLast(1) + ",\"data\":[\n" + "123,\n".repeat(75) + "0]}"
        val question = "What part of the harvest was affected, and how much crop did your household lose total?"
        assertEquals(87, question.length)
        val eval = ControlledResponse()
        val followup = ControlledResponse()
        repo.streamedResponses = listOf(eval, followup)
        host(twoStep = true, productionImeInsets = true)
        appendConversationHistory()
        enterAndSend("main answer")
        val editor = rule.onNode(hasSetTextAction())
        val previous = rowGeometry("typing-Q8")
        var length = 0
        for (chunk in evaluation.chunked(24)) {
            length += chunk.length
            val expected = evaluation.take(if (chunk.length >= 8) length else length - chunk.length)
            sendChunk(eval, chunk, expected)
            assertScan(expected)
            val row = rowGeometry("typing-Q8")
            assertEquals("Model chunks must not resize or move the scan", previous, row)
        }
        assertLatestEdgeVisible("typing-Q8")
        assertRowSettles("typing-Q8")
        waitForKeyboard(visible = true)
        editor.assertIsFocused()
        assertInactiveComposer()

        eval.finish()
        rule.waitUntil(5_000) { repo.requests == 2 }
        assertEquals(evaluation, ai.stepHistory.value.single().raw)
        val scoreRow = chatRows().single { it.json == evaluation }
        val slot = assertScorePrecedesPreparation(scoreRow)
        val preparation = rowGeometry(slot.id)
        assertLatestEdgeVisible(slot.id)
        for (end in 8..24 step 8) {
            sendChunk(followup, question.substring(end - 8, end), question.take(end))
            rule.onNodeWithText(question.take(end)).assertDoesNotExist()
            assertEquals(slot, assertScorePrecedesPreparation(scoreRow))
            assertEquals(preparation, rowGeometry(slot.id))
            editor.assertIsFocused()
        }
        // Start the reading gesture on the preparation row.
        val preparationTop = preparation.top - conversationViewport().top
        conversation.performTouchInput {
            swipeDown(startY = preparationTop + 2f, endY = height - 2f, durationMillis = 350)
        }
        assertTrue(conversationPosition() > 0f)
        // The drag above has already disabled following. Position the reader without a second fling.
        conversation.performScrollToIndex(conversationIndex("qroot-Q8"))
        val readingTop = originalQuestionTop()
        for (end in 32..80 step 8) {
            sendChunk(followup, question.substring(end - 8, end), question.take(end))
            assertEquals(readingTop, originalQuestionTop(), 1f)
            assertEquals(slot, assertScorePrecedesPreparation(scoreRow))
        }
        sendChunk(followup, question.drop(80), expected = question.take(80))
        assertEquals(slot, assertScorePrecedesPreparation(scoreRow))
        followup.finish()
        waitForFollowup(question)
        assertEquals(question, ai.stream.value)
        assertEquals(question, ai.stepHistory.value.last().raw)
        assertEquals(listOf(scoreRow.id, slot.id), chatRows().takeLast(2).map { it.id })
        assertTrue(chatRows().none { it.isTyping })
        assertQuestionFits(question)
        waitForKeyboard(visible = true)
        editor.assertIsFocused().performTextInput("two bags")
        rule.onNodeWithContentDescription("Send").assertIsEnabled()

        scrollToOriginalQuestion()
        val afterPublication = originalQuestionTop()
        rule.runOnIdle {
            val accepted = ai.chatHistoryFlow(contextKey).value.last { it.isFollowupQuestion }
            ai.upsertChatItem(contextKey, accepted.copy(createdAtUptimeMs = accepted.createdAtUptimeMs + 1))
        }
        assertEquals(afterPublication, originalQuestionTop(), 1f)
    }

    @Test fun consecutiveTwoStepTurnsKeepEachScoreAndFollowupInOrder() {
        val firstEval = ControlledResponse()
        val firstFollowup = ControlledResponse()
        val secondEval = ControlledResponse()
        val secondFollowup = ControlledResponse()
        val secondScore = NEEDS_FOLLOWUP.replace("30", "45")
        repo.streamedResponses = listOf(firstEval, firstFollowup, secondEval, secondFollowup)
        host(twoStep = true)
        captureFieldUi("10-voice-composer")
        rule.onNodeWithText("Text").performClick()
        val editor = rule.onNode(hasSetTextAction())
        waitForKeyboard(visible = true)
        captureFieldUi("09-type-composer")
        editor.performTextInput("main answer")
        captureProcessing("01-evaluation-scan") {
            rule.onNodeWithContentDescription("Send").performClick()
        }
        assertScan()

        sendChunk(firstEval, NEEDS_FOLLOWUP, expected = NEEDS_FOLLOWUP)
        captureProcessing("05-followup-composing") {
            firstEval.finish()
            rule.waitUntil(5_000) { repo.requests == 2 }
        }
        val firstScoreRow = chatRows().single { it.json == NEEDS_FOLLOWUP }
        val firstSlot = assertScorePrecedesPreparation(firstScoreRow)
        captureFieldUi("02-collapsed-evaluation")
        dismissKeyboardWithBack()
        val firstDetails = evaluationControl(firstScoreRow.id)
        assertPairedAnswer(firstScoreRow.id, "main answer")
        firstDetails.performSemanticsAction(SemanticsActions.OnClick)
        val scoreText = rule.onNodeWithText("AI score 30")
        if (capturingFieldUi && !scoreText.isDisplayed()) scoreText.performScrollTo()
        scoreText.assertIsDisplayed()
        captureFieldUi("03-evaluation-details")
        val rawControl = rule.onNodeWithText("Raw JSON")
        if (capturingFieldUi && !rawControl.isDisplayed()) rawControl.performScrollTo()
        rawControl.performSemanticsAction(SemanticsActions.OnClick)
        val rawOutput = rule.onNodeWithTag("Evaluation raw output")
        if (capturingFieldUi && !rawOutput.isDisplayed()) rawOutput.performScrollTo()
        rawOutput.assertIsDisplayed()
        captureFieldUi("04-raw-json")
        firstDetails.performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("Evaluation raw output").assertDoesNotExist()
        firstDetails.performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("Evaluation raw output").assertDoesNotExist()
        firstDetails.performSemanticsAction(SemanticsActions.OnClick)
        sendChunk(firstFollowup, FIRST_FOLLOWUP, expected = FIRST_FOLLOWUP)
        firstFollowup.finish()
        waitForFollowup(FIRST_FOLLOWUP)
        rule.onNode(hasTestTag("AI message") and hasText(FIRST_FOLLOWUP)).assertIsDisplayed()
        captureFieldUi("06-first-accepted-followup")
        val firstTurn = chatRows().takeLast(2)
        assertEquals(firstSlot.id, firstTurn.last().id)
        assertEquals(listOf(NEEDS_FOLLOWUP, FIRST_FOLLOWUP), firstTurn.map { it.json ?: it.text })

        editor.performClick().performTextInput("two bags")
        waitForKeyboard(visible = true)
        rule.onNodeWithContentDescription("Send").performClick()
        sendChunk(secondEval, secondScore.take(16), expected = secondScore.take(16))
        assertScan(secondScore.take(16))
        assertEquals(firstTurn, chatRows().filter { it.json != null || it.isFollowupQuestion })
        sendChunk(secondEval, secondScore.drop(16), expected = secondScore)
        secondEval.finish()
        rule.waitUntil(5_000) { repo.requests == 4 }
        assertReviewed(45)
        captureFieldUi("07-second-evaluation")
        val secondScoreRow = chatRows().single { it.json == secondScore }
        assertPairedAnswer(secondScoreRow.id, "two bags")
        val secondSlot = assertScorePrecedesPreparation(secondScoreRow)
        sendChunk(secondFollowup, SECOND_FOLLOWUP.take(16), expected = SECOND_FOLLOWUP.take(16))
        assertEquals(secondSlot, assertScorePrecedesPreparation(secondScoreRow))
        assertEquals(firstTurn + secondScoreRow, chatRows().filter { it.json != null || it.isFollowupQuestion })
        sendChunk(secondFollowup, SECOND_FOLLOWUP.drop(16), expected = SECOND_FOLLOWUP)
        secondFollowup.finish()
        waitForFollowup(SECOND_FOLLOWUP)
        rule.onNode(hasTestTag("AI message") and hasText(SECOND_FOLLOWUP)).assertIsDisplayed()
        captureFieldUi("08-second-accepted-followup")

        val output = chatRows().filter { it.json != null || it.isFollowupQuestion }
        assertEquals(listOf(NEEDS_FOLLOWUP, FIRST_FOLLOWUP, secondScore, SECOND_FOLLOWUP), output.map { it.json ?: it.text })
        assertEquals(secondSlot.id, output.last().id)
        assertEquals(4, output.map { it.id }.distinct().size)
        assertTrue(chatRows().none { it.isTyping })
    }

    @Test fun evaluationDetailsExpandIndependentlyAndCopyTheExactModelOutput() {
        val original = "  ```json\n$NEEDS_FOLLOWUP\n```  "
        val second = """{"score":95,"missing_points":[],"followup_needed":false}"""
        rule.setContent {
            SurveyNavTheme {
                val snack = remember { SnackbarHostState() }
                Column {
                    Box(Modifier.testTag("first")) { FieldEvaluationDetails(true, original, snack) }
                    Box(Modifier.testTag("second")) { FieldEvaluationDetails(true, second, snack) }
                }
            }
        }
        val firstControl = evaluationControl("first")
        val secondControl = evaluationControl("second")
        val firstRaw = rule.onNode(hasAnyAncestor(hasTestTag("first")) and hasText("Raw JSON"))
        val secondRaw = rule.onNode(hasAnyAncestor(hasTestTag("second")) and hasText("Raw JSON"))
        firstRaw.assertDoesNotExist()
        secondRaw.assertDoesNotExist()
        firstControl.performClick()
        secondControl.performClick()
        rule.onNode(hasAnyAncestor(hasTestTag("first")) and hasText("AI score 30")).assertIsDisplayed()
        rule.onNode(hasAnyAncestor(hasTestTag("first")) and hasText("yield loss", substring = true)).assertIsDisplayed()
        rule.onNode(hasAnyAncestor(hasTestTag("second")) and hasText("AI score 95")).assertIsDisplayed()
        rule.onNode(hasAnyAncestor(hasTestTag("second")) and hasText("None listed")).assertIsDisplayed()
        firstRaw.performClick()
        rule.onNodeWithText(original).assertIsDisplayed()
        rule.onNodeWithText("Copy").performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.runOnIdle {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            assertEquals(original, clipboard.primaryClip?.getItemAt(0)?.text.toString())
        }
        firstControl.performClick()
        secondRaw.assertIsDisplayed()
        firstControl.performClick()
        rule.onNodeWithText(original).assertDoesNotExist()
        firstRaw.assertIsDisplayed()
    }

    @Test fun fourFollowupsKeepStableQuestionPresentationInConversationHistory() {
        val questions = (1..4).map { "Follow-up question $it?" }
        val rows = buildList {
            add(AiViewModel.ChatItem("qroot-Q8", AiViewModel.ChatSender.AI, "Original question"))
            questions.forEachIndexed { index, question ->
                add(AiViewModel.ChatItem("answer-$index", AiViewModel.ChatSender.USER, "Answer ${index + 1}"))
                add(AiViewModel.ChatItem(
                    "eval-$index", AiViewModel.ChatSender.AI, json = NEEDS_FOLLOWUP,
                    evaluation = AiViewModel.EvaluationPresentation.REVIEWED
                ))
                add(AiViewModel.ChatItem("question-$index", AiViewModel.ChatSender.AI, question, isFollowupQuestion = true))
            }
        }
        rule.setContent {
            SurveyNavTheme {
                AiConversation(
                    "Q8", 1L, rows, emptyList(), false, remember { SnackbarHostState() },
                    Modifier.fillMaxSize(), fieldEvaluation = true
                )
            }
        }
        rule.onNodeWithText(questions.last()).assertIsDisplayed()
        questions.forEachIndexed { index, question ->
            val key = "question-$index"
            conversation.performScrollToIndex(conversationIndex(key))
            rule.onNode(hasTestTag("AI message") and hasText(question) and hasAnyAncestor(hasTestTag(key)))
                .assertIsDisplayed()
            conversation.performScrollToIndex(conversationIndex("answer-$index"))
            assertPairedAnswer("eval-$index", "Answer ${index + 1}")
        }
    }

    @Test fun committedVoiceAnswerUsesTheSameScanAndEvaluationRows() {
        val response = ControlledResponse()
        repo.streamedResponses = listOf(response)
        host(twoStep = true)
        rule.onNodeWithText("Tap to answer").performClick()
        rule.runOnIdle { speech.capture.value = true }
        rule.onNodeWithText("Stop").performClick()
        rule.runOnIdle {
            assertEquals(1, speech.stopCalls)
            speech.transitionToTranscribing()
        }
        rule.onNodeWithText("Transcribing…").assertIsDisplayed()
        rule.runOnIdle { speech.finish("Two bags of maize") }
        rule.onNodeWithText("Two bags of maize").assertIsDisplayed()
        assertTrue(userMessages().isEmpty())
        rule.onNodeWithContentDescription("Send").performClick()
        assertScan()
        val result = """{"score":95,"missing_points":[],"followup_needed":false}"""
        sendChunk(response, result, result)
        response.finish()
        rule.waitUntil(5_000) { !ai.isRunning && ai.conversationStateFlow(contextKey).value.turnCompleted }
        assertReviewed(95)
        assertEquals(listOf("Two bags of maize"), userMessages())
    }

    @Test fun retryAfterCancelledFollowupDropsLateOutputAndPublishesOneScore() {
        val evaluation = ControlledResponse()
        val cancelledFollowup = ControlledResponse()
        val retryEvaluation = ControlledResponse()
        val retryFollowup = ControlledResponse()
        val releaseLate = CompletableDeferred<Unit>()
        val lateAttempt = CompletableDeferred<Unit>()
        repo.streamedResponses = listOf(evaluation, cancelledFollowup, retryEvaluation, retryFollowup)
        repo.cancelledResponseIndex = 1
        repo.onStreamCancelled = {
            releaseLate.await()
            lateAttempt.complete(Unit)
            emit("Stale follow-up question?")
        }
        host(twoStep = true)
        enterAndSend("main answer")
        sendChunk(evaluation, NEEDS_FOLLOWUP, expected = NEEDS_FOLLOWUP)
        evaluation.finish()
        rule.waitUntil(5_000) { repo.requests == 2 }
        sendChunk(cancelledFollowup, "Old follow-up", expected = "Old follow-up")
        val originalScore = chatRows().single { it.json == NEEDS_FOLLOWUP }
        val cancelledSlot = assertScorePrecedesPreparation(originalScore)

        try {
            rule.runOnIdle { visible = false }
            rule.waitUntil(5_000) { !ai.isRunning }
            assertTrue(chatRows().none { it.isTyping || it.followupNotice != null || it.id == cancelledSlot.id })
            assertTrue(originalScore in chatRows())
            rule.runOnIdle { visible = true }
            runSavedAnswerEvaluation()
        } finally {
            releaseLate.complete(Unit)
        }
        rule.waitUntil(5_000) { lateAttempt.isCompleted && repo.requests == 3 }
        assertTrue(chatRows().none { it.id == originalScore.id || it.isFollowupQuestion })
        sendChunk(retryEvaluation, NEEDS_FOLLOWUP, expected = NEEDS_FOLLOWUP)
        retryEvaluation.finish()
        rule.waitUntil(5_000) { repo.requests == 4 }
        val retrySlot = assertScorePrecedesPreparation(chatRows().single { it.json == NEEDS_FOLLOWUP })
        assertTrue(retrySlot.id != cancelledSlot.id)
        sendChunk(retryFollowup, FIRST_FOLLOWUP, expected = FIRST_FOLLOWUP)
        retryFollowup.finish()
        waitForFollowup(FIRST_FOLLOWUP)

        assertEquals(listOf("main answer"), userMessages())
        assertEquals(listOf(NEEDS_FOLLOWUP, FIRST_FOLLOWUP), chatRows().takeLast(2).map { it.json ?: it.text })
        assertTrue(chatRows().none {
            it.isTyping || it.followupNotice != null || it.id == cancelledSlot.id ||
                it.id == originalScore.id || it.text == "Stale follow-up question?"
        })
        assertEquals(listOf(FIRST_FOLLOWUP), survey.followups.value.getValue("Q8").map { it.question })
    }

    @Test fun rejectedDuplicateResolvesTheSlotAndKeepsTheAnsweredFollowup() {
        val firstEval = ControlledResponse()
        val firstFollowup = ControlledResponse()
        val secondEval = ControlledResponse()
        val duplicate = ControlledResponse()
        val secondScore = NEEDS_FOLLOWUP.replace("30", "45")
        repo.streamedResponses = listOf(firstEval, firstFollowup, secondEval, duplicate)
        val restoration = StateRestorationTester(rule)
        host(twoStep = true, restoration = restoration, useNavDisplay = true)
        enterAndSend("main answer")
        sendChunk(firstEval, NEEDS_FOLLOWUP, expected = NEEDS_FOLLOWUP)
        firstEval.finish()
        sendChunk(firstFollowup, FIRST_FOLLOWUP, expected = FIRST_FOLLOWUP)
        firstFollowup.finish()
        waitForFollowup(FIRST_FOLLOWUP)
        val firstTurn = chatRows().takeLast(2)

        rule.onNode(hasSetTextAction()).performTextInput("two bags")
        rule.onNodeWithContentDescription("Send").performClick()
        sendChunk(secondEval, secondScore, expected = secondScore)
        secondEval.finish()
        rule.waitUntil(5_000) { repo.requests == 4 }
        val score = chatRows().single { it.json == secondScore }
        val slot = assertScorePrecedesPreparation(score)
        sendChunk(duplicate, FIRST_FOLLOWUP, expected = FIRST_FOLLOWUP)
        duplicate.finish()

        assertUnavailableFollowup(score, slot, AiTurnOutcome.DUPLICATE_FOLLOWUP)
        assertEquals(firstTurn, chatRows().filter { it in firstTurn })
        assertEquals(listOf("main answer", "two bags"), userMessages())
        val followup = survey.followups.value.getValue("Q8").single()
        assertEquals(FIRST_FOLLOWUP, followup.question)
        assertEquals("two bags", followup.answer)
        assertEquals(4, repo.requests)

        rule.runOnIdle { survey.goto("Done") }
        rule.onNodeWithText("Away from AI").assertIsDisplayed()
        rule.runOnIdle { survey.backToPrevious() }
        assertDuplicateOutcome("second")
        restoration.emulateSavedInstanceStateRestore()
        assertDuplicateOutcome("second")
    }

    @Test fun duplicateMessageCountsTheNextAttemptFromAcceptedHistory() {
        val duplicate = AiViewModel.ChatItem(
            "duplicate", AiViewModel.ChatSender.AI, outcome = AiTurnOutcome.DUPLICATE_FOLLOWUP
        )
        var followups by mutableStateOf(emptyList<SurveyViewModel.FollowupEntry>())
        rule.setContent {
            AiConversation(
                nodeId = "Q8", presentation = 1L, chatItems = listOf(duplicate),
                followups = followups, loading = false, fieldEvaluation = true,
                snack = remember { SnackbarHostState() }, modifier = Modifier.fillMaxSize()
            )
        }
        for ((count, ordinal) in listOf(0 to "first", 1 to "second", 2 to "third")) {
            rule.runOnIdle {
                followups = List(count) { index ->
                    SurveyViewModel.FollowupEntry("Accepted question ${index + 1}?", answer = "Sample answer")
                }
            }
            rule.onNodeWithText("No new follow-up available").assertIsDisplayed()
            rule.onNodeWithText(duplicateExplanation(ordinal)).assertIsDisplayed()
        }
    }

    @Test fun generationErrorResolvesTheSlotAndRetryRetiresItWithoutASecondStep() {
        val evaluation = ControlledResponse()
        val failed = ControlledResponse()
        val retryEval = ControlledResponse()
        val passingScore = """{"score":95,"missing_points":[],"followup_needed":false}"""
        repo.streamedResponses = listOf(evaluation, failed, retryEval)
        host(twoStep = true)
        enterAndSend("main answer")
        sendChunk(evaluation, NEEDS_FOLLOWUP, expected = NEEDS_FOLLOWUP)
        evaluation.finish()
        rule.waitUntil(5_000) { repo.requests == 2 }
        val score = chatRows().single { it.json == NEEDS_FOLLOWUP }
        val slot = assertScorePrecedesPreparation(score)
        val provisional = "Discard this provisional question?"
        sendChunk(failed, provisional, expected = provisional)
        failed.finish(IllegalStateException("generation failed"))

        assertUnavailableFollowup(score, slot)
        rule.onNodeWithText(provisional).assertDoesNotExist()
        rule.onNodeWithText("answers were saved", substring = true).assertDoesNotExist()
        assertEquals("generation failed", ai.error.value)
        assertTrue(survey.followups.value["Q8"].isNullOrEmpty())
        assertEquals(2, repo.requests)

        runSavedAnswerEvaluation()
        sendChunk(retryEval, passingScore, expected = passingScore)
        retryEval.finish()
        rule.waitUntil(5_000) { ai.conversationStateFlow(contextKey).value.turnCompleted && !ai.isRunning }
        assertReviewed(95)
        assertOutcome("Answer complete", "Your answer meets the requirements. No follow-up question is needed.")
        assertTrue(chatRows().none { it.id == slot.id || it.followupNotice != null || it.isFollowupQuestion })
        assertEquals(listOf("main answer"), userMessages())
        assertEquals(3, repo.requests)
    }

    private fun runSavedAnswerEvaluation() = rule.runOnIdle {
        ai.evaluateSurveyTwoStepAsync(survey, survey.currentNodeId, contextKey, survey.getQuestion(survey.currentNodeId))
    }

    private fun assertClosedComposer() {
        rule.onNodeWithText("Answer input is closed").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        rule.onNodeWithContentDescription("Send").assertDoesNotExist()
        rule.onNodeWithText("Tap to answer").assertDoesNotExist()
        rule.onNodeWithText("Retry saved answers").assertDoesNotExist()
        rule.onNodeWithText("Text").assertDoesNotExist()
        rule.onNodeWithText("Voice").assertDoesNotExist()
        waitForKeyboard(visible = false)
    }

    private fun assertOutcome(heading: String, explanation: String) {
        assertClosedComposer()
        rule.onAllNodesWithText(heading).assertCountEquals(1)
        rule.onNodeWithText(heading).assertIsDisplayed()
        rule.onNodeWithText(explanation).assertIsDisplayed()
        rule.onNodeWithText(NO_FOLLOWUP).assertDoesNotExist()
        rule.onNodeWithText("Status:", substring = true).assertDoesNotExist()
    }

    private fun duplicateExplanation(ordinal: String) =
        "The $ordinal follow-up question matched a question already asked, so no additional question was shown."

    private fun assertDuplicateOutcome(ordinal: String) =
        assertOutcome("No new follow-up available", duplicateExplanation(ordinal))

    private fun assertUnavailableFollowup(
        score: AiViewModel.ChatItem,
        slot: AiViewModel.ChatItem,
        outcome: AiTurnOutcome = AiTurnOutcome.FOLLOWUP_GENERATION_FAILED
    ) {
        rule.waitUntil(5_000) { ai.conversationStateFlow(contextKey).value.validationFailed && !ai.isRunning }
        if (outcome == AiTurnOutcome.DUPLICATE_FOLLOWUP) {
            assertDuplicateOutcome("second")
            rule.onNodeWithText("Evaluation failed").assertDoesNotExist()
        } else {
            assertOutcome("Follow-up generation failed", "We couldn't prepare another question.")
        }
        rule.onNodeWithText(PREPARING_FOLLOWUP).assertDoesNotExist()
        assertEquals(SurveyAiReason.FAILURE, survey.aiReasons.value[survey.currentNodeId])
        val notice = chatRows().single { it.outcome != null }
        assertEquals(slot.copy(followupNotice = null, outcome = outcome), notice)
        assertEquals(listOf(score, notice), chatRows().takeLast(2))
    }

    private fun chatRows() = ai.chatHistoryFlow(contextKey).value

    private fun assertScorePrecedesPreparation(score: AiViewModel.ChatItem): AiViewModel.ChatItem =
        rule.runOnIdle {
            val rows = chatRows()
            val slot = rows.single { it.followupNotice == AiViewModel.FollowupNotice.PREPARING }
            assertEquals(listOf(score, slot), rows.takeLast(2))
            assertEquals(null, slot.text)
            assertTrue(!slot.isTyping && !slot.isFollowupQuestion)
            assertTrue(rows.none { it.isTyping })
            slot
        }

    @Test fun replacementStreamRejectsLateCancelledOutputAndFlushesAResultUnderEightCharacters() {
        val old = ControlledResponse()
        val replacement = ControlledResponse()
        val lateAttempt = CompletableDeferred<Unit>()
        val releaseLate = CompletableDeferred<Unit>()
        repo.streamedResponses = listOf(old, replacement)
        repo.onStreamCancelled = {
            releaseLate.await()
            lateAttempt.complete(Unit)
            emit("OLD GENERATION TAIL")
        }
        host()
        val cancelled = rule.runOnIdle { ai.evaluateAsync("old") }
        sendChunk(old, "old head", expected = "old head")
        val next = rule.runOnIdle {
            ai.cancel()
            ai.evaluateAsync("replacement")
        }
        try {
            assertEquals("", ai.stream.value)
        } finally {
            releaseLate.complete(Unit)
        }
        rule.waitUntil(5_000) { lateAttempt.isCompleted && cancelled.isCompleted && repo.requests == 2 }
        assertEquals("", ai.stream.value)
        assertTrue(ai.stepHistory.value.isEmpty())
        sendChunk(replacement, "Done?", expected = "")
        replacement.finish()
        rule.waitUntil(5_000) { next.isCompleted }
        assertEquals("Done?", ai.stream.value)
        assertEquals("Done?", ai.stepHistory.value.single().raw)
    }

    private fun sendChunk(response: ControlledResponse, text: String, expected: String) {
        val consumed = response.enqueue(text)
        rule.waitUntil(5_000) { consumed.isCompleted }
        assertEquals(expected, ai.stream.value)
    }

    private fun evaluationControl(id: String) = rule.onNode(
        hasAnyAncestor(hasTestTag(id)) and hasClickAction() and
            hasTestTag("Evaluation details toggle")
    )

    private fun assertPairedAnswer(reviewId: String, answer: String) {
        evaluationControl(reviewId).assertTextContains("Details")
        rule.onAllNodesWithText(answer).assertCountEquals(1)
        rule.onNode(hasText(answer) and hasAnyAncestor(hasTestTag(reviewId))).assertExists()
    }

    private fun assertReviewed(score: Int) {
        val row = chatRows().last { it.evaluation == AiViewModel.EvaluationPresentation.REVIEWED }
        val control = evaluationControl(row.id)
        rule.onNodeWithText("Reviewing your answer…").assertDoesNotExist()
        control.assertIsDisplayed().performClick()
        rule.onNode(hasAnyAncestor(hasTestTag(row.id)) and hasText("AI score $score")).assertIsDisplayed()
        control.performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun assertScan(expected: String? = null) {
        rule.onNodeWithText("Reviewing your answer…").assertIsDisplayed()
        rule.onNodeWithText("Checking for additional details").assertIsDisplayed()
        rule.onNodeWithText(PREPARING_FOLLOWUP).assertDoesNotExist()
        if (expected != null) {
            assertEquals(expected, chatRows().single { it.isTyping }.text)
            rule.onNodeWithText(expected).assertDoesNotExist()
        }
        assertLatestEdgeVisible("typing-Q8")
    }

    private val capturingFieldUi get() = InstrumentationRegistry.getArguments().getString("captureFieldUi") == "true"

    private fun captureProcessing(name: String, start: () -> Unit) {
        if (!capturingFieldUi) {
            start()
            return
        }
        // The test framework cancels infinite animations while its clock auto-advances.
        // Drive the clock explicitly for the optional emulator recording.
        rule.mainClock.autoAdvance = false
        try {
            start()
            repeat(5) {
                rule.mainClock.advanceTimeByFrame()
                rule.waitForIdle()
            }
            captureFieldUi(name)
            repeat(30) {
                rule.mainClock.advanceTimeBy(80)
                rule.waitForIdle()
                SystemClock.sleep(55)
            }
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    private fun captureFieldUi(name: String) {
        if (!capturingFieldUi) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        rule.waitForIdle()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(instrumentation.targetContext.cacheDir, "field-ai-captures")
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "$name.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            screenshot.recycle()
        }
    }

    private fun assertStreamingEdge(expected: String) {
        rule.onNodeWithText(expected).assertIsDisplayed()
        assertLatestEdgeVisible("typing-Q8", oversized = rowGeometry("typing-Q8").height > conversationViewport().height)
    }

    @Test fun blankImeSendKeepsDraftAndEvaluationFailureClosesTheComposer() {
        repo.responses = listOf("invalid evaluation")
        host(withSpeech = false, twoStep = true)
        waitForKeyboard(visible = true)
        val editor = rule.onNode(hasSetTextAction()).assertIsFocused()
        editor.performTextInput("   ")
        editor.performImeAction()
        rule.runOnIdle {
            assertEquals("   ", ai.conversationStateFlow(contextKey).value.composerDraft)
            assertEquals(0, repo.requests)
        }
        editor.performTextInput("accepted")
        editor.performImeAction()
        rule.waitUntil(5_000) { repo.requests == 1 }
        assertInactiveComposer()
        repo.release.complete(Unit)
        rule.waitUntil(5_000) { ai.conversationStateFlow(contextKey).value.validationFailed && !ai.loading.value }
        assertOutcome("Evaluation failed", "We couldn't evaluate your answer.")
        rule.onNodeWithText("Reviewing your answer…").assertDoesNotExist()
        rule.onNodeWithText("Answer reviewed", substring = true).assertDoesNotExist()
        val failed = chatRows().single { it.json == "invalid evaluation" }
        evaluationControl(failed.id).assertIsDisplayed().performClick()
        rule.onNodeWithText("Raw JSON").performClick()
        rule.onNodeWithText("invalid evaluation").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals("accepted", survey.getAnswer("Q8"))
            assertEquals(listOf("accepted"), userMessages())
            assertEquals(1, repo.requests)
        }
    }

    @Test fun limitOutcomeSurvivesRestorationWithoutReopeningTheKeyboard() {
        val evaluation = ControlledResponse()
        repo.streamedResponses = listOf(evaluation)
        val restoration = StateRestorationTester(rule)
        var showRequests = 0
        host(twoStep = true, withSpeech = false, restoration = restoration, onKeyboardShow = { showRequests++ }) {
            repeat(maxFollowups) { index ->
                check(addFollowupQuestion("Q8", "Sample question ${index + 1}?"))
                answerLastFollowup("Q8", "Sample clarification")
            }
        }
        waitForKeyboard(visible = true)
        rule.onNode(hasSetTextAction()).performTextInput("Sample answer")
        rule.onNodeWithContentDescription("Send").performClick()
        sendChunk(evaluation, NEEDS_FOLLOWUP, expected = NEEDS_FOLLOWUP)
        evaluation.finish()
        rule.waitUntil(5_000) { !ai.isRunning && survey.aiReasons.value["Q8"] == SurveyAiReason.LIMIT_REACHED }
        assertOutcome("Follow-up limit reached", "No more follow-up questions can be asked.")
        rule.onNodeWithText("Answer complete").assertDoesNotExist()
        val outcome = chatRows().single { it.outcome == AiTurnOutcome.LIMIT_REACHED }
        val trailingRow = AiViewModel.ChatItem("retained-note", AiViewModel.ChatSender.AI, "Retained context")
        rule.runOnIdle { ai.upsertChatItem(contextKey, trailingRow) }
        conversation.performScrollToIndex(0)
        repeat(3) {
            rule.runOnIdle { unrelated = !unrelated }
            assertOutcome("Follow-up limit reached", "No more follow-up questions can be asked.")
            rule.onNodeWithTag("restored-outcome-Q8").assertDoesNotExist()
            assertLatestEdgeVisible(trailingRow.id)
        }

        restoration.emulateSavedInstanceStateRestore()
        assertOutcome("Follow-up limit reached", "No more follow-up questions can be asked.")
        rule.onNodeWithTag("restored-outcome-Q8").assertDoesNotExist()
        assertEquals(listOf(outcome, trailingRow), chatRows().takeLast(2))
        assertEquals(1, showRequests)
        assertEquals(1, repo.requests)
    }

    @Test fun historicalFailureUsesTheGenericOutcomeAndDoesNotFocusTheComposer() {
        var showRequests = 0
        host(twoStep = true, withSpeech = false, onKeyboardShow = { showRequests++ }) {
            setAnswer("Sample answer", "Q8")
            setAiReason("Q8", SurveyAiReason.FAILURE)
        }
        assertOutcome("AI review unavailable", "We couldn't complete the AI review.")
        rule.onNodeWithText("Evaluation failed").assertDoesNotExist()
        rule.onNodeWithText("Follow-up generation failed").assertDoesNotExist()
        rule.onNodeWithText("No new follow-up available").assertDoesNotExist()
        rule.onNodeWithText("Answer complete").assertDoesNotExist()
        rule.onNodeWithText("Next").assertIsEnabled()
        assertEquals(0, showRequests)
        assertEquals(0, repo.requests)
        assertTrue(speaker.spoken.isEmpty())
    }

    @Test fun compatibleTerminalReasonUsesANeutralOutcome() {
        host(twoStep = true, withSpeech = false) {
            setAiReason("Q8", SurveyAiReason.UNABLE_REFUSED)
        }
        assertOutcome("AI review ended", "No further follow-up question is available.")
        rule.onNodeWithText("Answer complete").assertDoesNotExist()
        assertEquals(SurveyAiReason.UNABLE_REFUSED, survey.aiReasons.value["Q8"])
    }

    @Test fun finalizationWhileReadingHistoryDoesNotJumpToTheOutcome() {
        val evaluation = ControlledResponse()
        repo.streamedResponses = listOf(evaluation)
        host(twoStep = true, productionImeInsets = true)
        appendConversationHistory()
        enterAndSend("Sample answer")
        scrollToOriginalQuestion()
        evaluation.finish(IllegalStateException("scripted failure"))
        rule.waitUntil(5_000) { !ai.isRunning && ai.conversationStateFlow(contextKey).value.validationFailed }
        assertClosedComposer()
        rule.onNodeWithText("Question").assertIsDisplayed()
        assertTrue(conversationPosition() > 0f)
        conversation.performScrollToIndex(0)
        assertOutcome("Evaluation failed", "We couldn't evaluate your answer.")
    }

    @Test fun acceptedMainAnswerDoesNotReappearAsDraftOnNavigationReentry() {
        repo.responses = listOf("""{"score":95,"missing_points":[],"followup_needed":false}""")
        host(withSpeech = false, useNavDisplay = true)
        val editor = rule.onNode(hasSetTextAction()).performClick()
        editor.performTextInput("saved answer")
        rule.onNodeWithContentDescription("Send").performClick()
        repo.release.complete(Unit)
        rule.waitUntil(5_000) { ai.conversationStateFlow(contextKey).value.turnCompleted }
        rule.onNodeWithText("Score 95 / 100", substring = true).assertIsDisplayed()

        rule.runOnIdle { survey.goto("Done") }
        rule.onNodeWithText("Away from AI").assertIsDisplayed()
        waitForKeyboard(visible = false)
        rule.runOnIdle { survey.backToPrevious() }
        assertQuestionFits("Question")
        waitForKeyboard(visible = false)
        assertEquals("", editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        rule.runOnIdle {
            assertEquals("saved answer", survey.getAnswer("Q8"))
            assertEquals(listOf("saved answer"), userMessages())
            assertEquals(1, ai.chatHistoryFlow(contextKey).value.count { it.json != null })
        }
    }

    @Test fun finalizedInputKeepsExplicitStopAndIgnoresLateTranscription() {
        host(twoStep = true, useNavDisplay = true)
        rule.onNodeWithText("Tap to answer").performClick()
        rule.runOnIdle { speech.capture.value = true }
        val stop = rule.onNode(hasText("Stop") and hasAnyAncestor(isDialog()))
        stop.assertIsDisplayed().assertIsEnabled()

        // Publish a result while recording to exercise the exceptional overlap.
        rule.runOnIdle {
            survey.setAnswer("Accepted answer", "Q8")
            ai.appendUserMessage(contextKey, "Accepted answer")
            survey.setAiReason("Q8", SurveyAiReason.ACHIEVED)
        }
        stop.assertIsDisplayed().assertIsEnabled()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        rule.onNodeWithText("Tap to answer").assertDoesNotExist()
        rule.onNodeWithContentDescription("Send").assertDoesNotExist()
        rule.onNodeWithText("Next").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, speech.stopCalls) }

        stop.performClick()
        stop.assertIsDisplayed().assertIsNotEnabled()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.runOnIdle {
            assertEquals(1, speech.stopCalls)
            speech.transitionToTranscribing()
        }
        stop.assertDoesNotExist()
        assertOutcome("Answer complete", "Your answer meets the requirements. No follow-up question is needed.")
        rule.onNodeWithText("Next").assertIsNotEnabled()

        rule.runOnIdle { speech.finish("Late transcript") }
        assertOutcome("Answer complete", "Your answer meets the requirements. No follow-up question is needed.")
        rule.onNodeWithText("Late transcript").assertDoesNotExist()
        rule.onNodeWithText("Next").assertIsEnabled()

        rule.runOnIdle { survey.goto("Done") }
        rule.onNodeWithText("Away from AI").assertIsDisplayed()
        rule.runOnIdle { survey.backToPrevious() }
        assertOutcome("Answer complete", "Your answer meets the requirements. No follow-up question is needed.")
        rule.runOnIdle {
            assertEquals(1, speech.stopCalls)
            assertEquals("Accepted answer", survey.getAnswer("Q8"))
            assertEquals(listOf("Accepted answer"), userMessages())
            assertEquals(SurveyAiReason.ACHIEVED, survey.aiReasons.value["Q8"])
            assertEquals("", ai.conversationStateFlow(contextKey).value.composerDraft)
            assertEquals(0, repo.requests)
        }
    }

    @Test fun closedInputShowsSpeechErrorDuringCleanupAndAfterTranscriptionEnds() {
        host(twoStep = true) { setAiReason("Q8", SurveyAiReason.ACHIEVED) }
        assertClosedComposer()
        val error = rule.onNodeWithText("Whisper failed")
        val status = rule.onNodeWithText("Transcribing…")

        rule.runOnIdle {
            speech.transitionToTranscribing()
            speech.errorMessage.value = "Whisper failed"
        }
        status.assertIsDisplayed()
        rule.onAllNodesWithText("Transcribing…", useUnmergedTree = true).assertCountEquals(1)
        error.assertIsDisplayed()

        rule.runOnIdle { speech.finish("") }
        status.assertDoesNotExist()
        error.assertIsDisplayed()
        assertClosedComposer()
    }

    @Test fun stopAndBackAreGuardedUntilControllerReportsTransition() {
        host()
        val status = rule.onNodeWithText("Transcribing…")
        val waveform = rule.onNodeWithTag("live-recording-waveform")
        val details = rule.onNodeWithText("Converting speech to text")
        status.assertDoesNotExist()
        rule.onNodeWithText("Tap to answer").performClick()
        rule.onNode(hasText("Starting microphone…") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        rule.onNode(hasText("Question") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        rule.onNodeWithContentDescription("Send").assertDoesNotExist()
        rule.onNodeWithText("Text").assertIsNotEnabled()
        rule.onNodeWithText("Voice").assertIsSelected().assertIsNotEnabled()
        rule.onNodeWithText("0:00").assertExists()
        waveform.assertDoesNotExist()
        rule.runOnIdle {
            speech.recordingLevel.value = RecordingSpectrum(FloatArray(RecordingSpectrum.BAND_COUNT) { 0.5f })
            speech.capture.value = true
        }
        rule.onAllNodesWithText("Listening…").assertCountEquals(0)
        rule.onNode(hasText("Question") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        waveform.assertIsDisplayed()
            .assert(!SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
            .assert(!hasClickAction())
        rule.onNodeWithText("Stop").performClick()
        rule.onNodeWithText("Stop").assertIsNotEnabled()
        rule.runOnIdle { speech.recordingLevel.value = RecordingSpectrum(FloatArray(RecordingSpectrum.BAND_COUNT) { 0.9f }) }
        waveform.assertDoesNotExist()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        captureProcessing("transcribing-transition") {
            rule.runOnIdle { assertEquals(1, speech.stopCalls); speech.transitionToTranscribing() }
        }
        rule.onNode(isDialog()).assertDoesNotExist()
        waveform.assertDoesNotExist()
        rule.onNodeWithText("Question").assertIsDisplayed()
        rule.onAllNodesWithText("Transcribing…", useUnmergedTree = true).assertCountEquals(1)
        status.assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(!hasClickAction())
        details.assertIsDisplayed()
        rule.onNodeWithText("Tap to answer").assertDoesNotExist()
        rule.onNodeWithText("Record again").assertDoesNotExist()
        rule.onNodeWithText("Text").assertIsNotEnabled()
        rule.onNodeWithText("Voice").assertIsSelected().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Send").assertDoesNotExist()
        captureFieldUi("transcribing")
        rule.runOnIdle { speech.finish("first") }
        status.assertDoesNotExist()
        details.assertDoesNotExist()
        rule.onNodeWithText("first").assertIsDisplayed()
        rule.runOnIdle { assertEquals("first", survey.getAnswer("Q8")) }
        rule.onNodeWithText("Text").assertIsEnabled()
        rule.onNodeWithText("Record again").performScrollTo().performClick()
        rule.onNodeWithText("0:00").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.runOnIdle { assertEquals(2, speech.stopCalls) }
    }

    @Test fun recordingOverlayKeepsPromptTimerAndStopReachableAtLargeFontScale() {
        host(largeText = true)
        rule.onNodeWithText("Tap to answer").performScrollTo().performClick()
        rule.runOnIdle {
            speech.recordingLevel.value = RecordingSpectrum(FloatArray(RecordingSpectrum.BAND_COUNT) { 0.5f })
            speech.capture.value = true
        }
        rule.onNode(hasText("Question") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        rule.onNodeWithContentDescription("Recording for 0:00").assertIsDisplayed()
        rule.onNodeWithTag("live-recording-waveform").assertIsDisplayed()
        rule.onNodeWithText("Stop").performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    @Test fun leavingQuestionStopsAnActiveRecording() {
        host()
        rule.onNodeWithText("Tap to answer").performClick()
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

    @Test fun consecutiveAcceptedFollowupsEachAutoplayOnceInTheSamePresentation() {
        repo.responses = listOf(NEEDS_FOLLOWUP, FIRST_FOLLOWUP, NEEDS_FOLLOWUP, SECOND_FOLLOWUP)
        host(twoStep = true)
        val presentation = rule.runOnIdle { survey.questionSpeechOwner() }

        enterAndSend("main answer")
        repo.release.complete(Unit)
        waitForFollowup(FIRST_FOLLOWUP)
        rule.runOnIdle {
            assertEquals(listOf("Question", FIRST_FOLLOWUP), speaker.spoken)
            unrelated = true
        }

        rule.onNode(hasSetTextAction()).performTextInput("two bags")
        rule.onNodeWithContentDescription("Send").performClick()
        waitForFollowup(SECOND_FOLLOWUP)
        val expectedAutoplay = listOf("Question", FIRST_FOLLOWUP, SECOND_FOLLOWUP)
        rule.runOnIdle {
            assertEquals(presentation, survey.questionSpeechOwner())
            val entries = survey.followups.value.getValue(presentation.nodeId)
            assertEquals(listOf(FIRST_FOLLOWUP, SECOND_FOLLOWUP), entries.map { it.question })
            assertEquals(listOf("two bags", null), entries.map { it.answer })
            assertEquals(expectedAutoplay, speaker.spoken)
            unrelated = false
            autoplay = false
        }
        rule.runOnIdle { autoplay = true }
        rule.runOnIdle { assertEquals(expectedAutoplay, speaker.spoken) }

        rule.onNodeWithText("🔊").performClick()
        rule.runOnIdle { assertEquals(expectedAutoplay + SECOND_FOLLOWUP, speaker.spoken) }
    }

    @Test fun failureQ07StaysSilentFromQ06AndQ08AndCanBeReplayed() {
        host(initialNode = "Q06") { setAiReason("Q07", SurveyAiReason.FAILURE) }
        rule.runOnIdle {
            assertEquals(listOf("Number"), speaker.spoken)
            survey.advanceToNext()
        }
        rule.runOnIdle {
            assertEquals("Q07", survey.currentNodeId)
            assertEquals(listOf("Number"), speaker.spoken)
            survey.setAnswer("Saved", "Q07")
            survey.addFollowupQuestion("Q07", "Historical follow-up?")
            survey.advanceToNext()
        }
        rule.runOnIdle {
            assertEquals("Q08", survey.currentNodeId)
            assertEquals(listOf("Number", "Q08 original question"), speaker.spoken)
            survey.backToPrevious()
        }
        rule.runOnIdle {
            assertEquals("Q07", survey.currentNodeId)
            assertEquals(listOf("Number", "Q08 original question"), speaker.spoken)
        }
        rule.onNodeWithText("🔊").performClick()
        rule.runOnIdle {
            assertEquals(listOf("Number", "Q08 original question", "Historical follow-up?"), speaker.spoken)
        }
    }

    @Test fun newQ07AndFollowupAfterSuccessfulRetryEachSpeakOnce() {
        host(initialNode = "Q06")
        rule.runOnIdle { survey.advanceToNext() }
        rule.runOnIdle {
            assertEquals(listOf("Number", "Q07 original question"), speaker.spoken)
            survey.setAiReason("Q07", SurveyAiReason.FAILURE)
        }
        rule.runOnIdle {
            assertEquals(listOf("Number", "Q07 original question"), speaker.spoken)
            survey.setAnswer("Saved", "Q07")
            survey.addFollowupQuestion("Q07", "New follow-up?")
            survey.setAiReason("Q07", null)
        }
        rule.runOnIdle { unrelated = true }
        rule.runOnIdle {
            assertEquals(listOf("Number", "Q07 original question", "New follow-up?"), speaker.spoken)
        }
    }

    @Test fun ordinaryNodeTypesSpeakTheirOwnQuestion() {
        host(initialNode = "Info")
        val spoken = mutableListOf<String>()
        for ((id, prompt) in listOf(
            "Info" to "Info", "Text" to "Text", "Single" to "Single",
            "Multi" to "Multi", "Q06" to "Number"
        )) {
            spoken += prompt
            rule.runOnIdle {
                assertEquals(id, survey.currentNodeId)
                assertEquals(spoken, speaker.spoken)
                if (id != "Q06") survey.advanceToNext()
            }
        }
    }

    @Test fun queuedOrdinaryReplayTakesPrecedenceOverWaitingAutoplay() {
        speaker.isReady.value = false
        host(initialNode = "Q06")
        rule.runOnIdle {
            assertEquals(1, speaker.isReady.subscriptionCount.value)
            survey.noteQueuedOrdinaryReplay("Q06")
            toggleQuestionSpeaker(speaker, "Number", "manual")
            speaker.isReady.value = true
        }
        rule.runOnIdle { assertEquals(listOf("manual"), speaker.ids) }
    }

    @Test fun rapidForwardBackAndLateReadinessRejectOldNumberAndAiPresentations() {
        speaker.isReady.value = false
        host(initialNode = "Q06")
        rule.runOnIdle {
            assertEquals(1, speaker.isReady.subscriptionCount.value)
            survey.advanceToNext()
        }
        rule.runOnIdle {
            assertEquals(1, speaker.isReady.subscriptionCount.value)
            survey.backToPrevious()
            survey.advanceToNext()
            survey.advanceToNext()
            speaker.isReady.value = true
        }
        rule.runOnIdle { assertEquals(listOf("Q08 original question"), speaker.spoken) }
    }

    @Test fun disposingRetainedAiEntryDoesNotStopOrDuplicateNewOwnerPlayback() {
        speaker.holdPlayback = true
        host(initialNode = "Q06")
        rule.runOnIdle {
            assertEquals(listOf("Number"), speaker.spoken)
            survey.advanceToNext()
        }
        rule.runOnIdle {
            assertEquals(listOf("Number", "Q07 original question"), speaker.spoken)
            retainedAiNodeId = "Q07"
            survey.advanceToNext()
            survey.backToPrevious()
            survey.backToPrevious()
        }

        // The outgoing AI screen remains composed after the new owner has started.
        rule.onNodeWithText("Question • Q07").assertExists()
        rule.runOnIdle {
            assertEquals("Q06", survey.currentNodeId)
            assertEquals(listOf("Number", "Q07 original question", "Number"), speaker.spoken)
            assertEquals(2, speaker.stopCalls)
            assertTrue(speaker.isSpeaking.value)
            retainedAiNodeId = null
        }

        rule.onNodeWithText("Question • Q07").assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(listOf("Number", "Q07 original question", "Number"), speaker.spoken)
            assertEquals(2, speaker.stopCalls)
            assertTrue(speaker.isSpeaking.value)
        }
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

    @Test fun stoppingActivePlaybackKeepsSurveyProgressAndAllowsManualReplay() {
        host(playbackState = Lifecycle.State.RESUMED)
        rule.runOnIdle {
            assertEquals(listOf("Question"), speaker.spoken)
            survey.setAnswer("Saved", "Q8")
            speaker.isSpeaking.value = true
        }
        val session = rule.runOnIdle {
            playbackLifecycle.currentState = Lifecycle.State.CREATED
            survey.sessionId.value
        }
        rule.runOnIdle {
            assertEquals(1, speaker.stopCalls)
            assertTrue(!speaker.isSpeaking.value)
            playbackLifecycle.currentState = Lifecycle.State.RESUMED
        }
        rule.runOnIdle {
            assertEquals(listOf("Question"), speaker.spoken)
            assertEquals("Saved", survey.getAnswer("Q8"))
            assertEquals("Q8", survey.currentNodeId)
            assertEquals(session, survey.sessionId.value)
        }
        rule.onNodeWithText("🔊").performClick()
        rule.runOnIdle { assertEquals(listOf("Question", "Question"), speaker.spoken) }
    }

    @Test fun pendingFollowupIsCancelledAcrossStopEvenIfReadinessArrivesAfterResume() {
        speaker.isReady.value = false
        host(playbackState = Lifecycle.State.RESUMED) {
            setAnswer("Saved", "Q8")
            addFollowupQuestion("Q8", "Pending follow-up?")
        }
        rule.runOnIdle {
            assertEquals(1, speaker.isReady.subscriptionCount.value)
            playbackLifecycle.currentState = Lifecycle.State.CREATED
            playbackLifecycle.currentState = Lifecycle.State.RESUMED
            speaker.isReady.value = true
        }
        rule.runOnIdle { assertTrue(speaker.spoken.isEmpty()) }
        rule.onNodeWithText("🔊").performClick()
        rule.runOnIdle { assertEquals(listOf("Pending follow-up?"), speaker.spoken) }
    }

    @Test fun backgroundAttemptDoesNotConsumeAiAutoReadClaim() {
        host(playbackState = Lifecycle.State.CREATED)
        rule.runOnIdle {
            assertTrue(speaker.spoken.isEmpty())
            playbackLifecycle.currentState = Lifecycle.State.RESUMED
        }
        rule.runOnIdle {
            assertTrue(speaker.spoken.isEmpty())
            autoplay = false
        }
        rule.runOnIdle { autoplay = true }
        rule.runOnIdle { assertEquals(listOf("Question"), speaker.spoken) }
    }

    @Test fun shortNarrowChatKeepsComposerAndNavigationReachableAtLargeFontScale() {
        host(compact = true)
        // At this height the conversation and footer each have their own scrollable viewport.
        rule.onNodeWithText("Question").assertIsDisplayed()
        rule.onNodeWithText("Voice").assertIsSelected()
        rule.onNodeWithText("Text").assertIsNotSelected()
        rule.onNodeWithText("Tap to answer").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Next").performScrollTo().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText("Back").performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    @Test fun originalReadsOncePerNavigationPresentationWithOrWithoutDraft() {
        host(withSpeech = false, initialNode = "Q08")
        val editor = rule.onNode(hasSetTextAction())
        val original = "Q08 original question"
        val nextQuestion = "Q09 original question"

        rule.runOnIdle { assertEquals(listOf(original), speaker.spoken) }
        editor.performTextReplacement("unsent draft")
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Back").performClick()
        editor.assertTextContains("unsent draft")
        rule.runOnIdle {
            assertEquals(listOf(original, nextQuestion, original), speaker.spoken)
            assertEquals("unsent draft", survey.getAnswer("Q08"))
        }

        editor.performTextReplacement("edited draft")
        editor.performTextClearance()
        rule.runOnIdle { unrelated = true }
        rule.runOnIdle { assertEquals(listOf(original, nextQuestion, original), speaker.spoken) }

        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Back").performClick()
        rule.runOnIdle {
            assertEquals(listOf(original, nextQuestion, original, nextQuestion, original), speaker.spoken)
            assertEquals(3, speaker.ids.filter { "|nid=Q08|" in it }.distinct().size)
        }
    }

    @Test fun acceptedOriginalCancelsPendingReadAndStaysSilentOnReturnUntilNewSurvey() {
        speaker.isReady.value = false
        host(withSpeech = false)
        rule.runOnIdle { assertEquals(1, speaker.isReady.subscriptionCount.value) }
        val editor = rule.onNode(hasSetTextAction())
        editor.performTextReplacement("answer to send")
        rule.runOnIdle { assertEquals(1, speaker.isReady.subscriptionCount.value) }
        rule.onNodeWithContentDescription("Send").performClick()
        rule.runOnIdle {
            assertEquals(setOf("Q8"), survey.submittedAiOriginals.value)
            speaker.isReady.value = true
        }
        rule.waitUntil(5_000) { repo.requests == 1 }
        rule.runOnIdle { ai.cancel() }
        rule.waitUntil(5_000) { !ai.blocksNavigation() }
        rule.runOnIdle {
            assertTrue(survey.followups.value["Q8"].isNullOrEmpty())
            assertTrue(survey.aiReasons.value["Q8"] == null)
            assertTrue(!ai.conversationStateFlow(contextKey).value.turnCompleted)
            assertTrue(speaker.spoken.isEmpty())
        }
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Back").performClick()
        rule.runOnIdle {
            assertEquals(listOf("Other question"), speaker.spoken)
            assertEquals(setOf("Q8"), survey.submittedAiOriginals.value)
            survey.resetToStart()
            survey.goto("Q8")
        }
        rule.runOnIdle {
            assertTrue(survey.submittedAiOriginals.value.isEmpty())
            assertEquals(listOf("Other question", "Question"), speaker.spoken)
            assertTrue(speaker.ids.last().startsWith("sid=1|nid=Q8|"))
        }
    }

    @Test fun acceptingFollowupDoesNotRecordAnOriginalSubmission() {
        host(withSpeech = false) { addFollowupQuestion("Q8", "Pending follow-up?") }
        rule.onNode(hasSetTextAction()).performTextReplacement("follow-up answer")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.runOnIdle {
            assertEquals("follow-up answer", survey.followups.value["Q8"]?.single()?.answer)
            assertTrue(survey.submittedAiOriginals.value.isEmpty())
            assertEquals(listOf("Pending follow-up?"), speaker.spoken)
        }
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
        override val recordingLevel = MutableStateFlow<RecordingSpectrum?>(null)
        override val isTranscribing = transcribing
        override val partialText = partial
        override val errorMessage = MutableStateFlow<String?>(null)
        var startCalls = 0
        var stopCalls = 0
        override fun startRecording() {
            startCalls++
            recordingLevel.value = null
            recording.value = true
        }
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
        var holdPlayback = false
        var stopCalls = 0
        override fun speak(text: String, utteranceId: String) {
            spoken += text
            ids += utteranceId
            if (holdPlayback) isSpeaking.value = true
        }
        override fun stop() {
            stopCalls++
            isSpeaking.value = false
        }
    }
    private class ControlledResponse {
        private val chunks = Channel<Pair<String, CompletableDeferred<Unit>>>(Channel.UNLIMITED)

        fun enqueue(text: String): CompletableDeferred<Unit> =
            CompletableDeferred<Unit>().also { check(chunks.trySend(text to it).isSuccess) }

        fun finish(error: Throwable? = null) {
            chunks.close(error)
        }

        suspend fun collect(collector: FlowCollector<String>) {
            for ((text, consumed) in chunks) {
                collector.emit(text)
                consumed.complete(Unit)
            }
        }
    }

    private class HoldingRepository : Repository {
        @Volatile var requests = 0
        var responses: List<String>? = null
        var streamedResponses: List<ControlledResponse>? = null
        var onStreamCancelled: (suspend FlowCollector<String>.() -> Unit)? = null
        var cancelledResponseIndex = 0
        val release = CompletableDeferred<Unit>()
        var pauseAfterFirstChunk: CompletableDeferred<Unit>? = null
        var pauseSecondRequest: CompletableDeferred<Unit>? = null
        override fun buildPrompt(userPrompt: String) = userPrompt
        override suspend fun request(prompt: String, traceContext: RepositoryTraceContext?): Flow<String> {
            val controlled = streamedResponses
            if (controlled != null) {
                return object : Flow<String> {
                    override suspend fun collect(collector: FlowCollector<String>) {
                        val index = requests++
                        try {
                            controlled[index].collect(collector)
                        } catch (cancelled: CancellationException) {
                            if (index == cancelledResponseIndex) onStreamCancelled?.let { onCancel ->
                                // Deliberately simulate a late repository callback after cancellation.
                                withContext(NonCancellable) { collector.onCancel() }
                            }
                            throw cancelled
                        }
                    }
                }
            }
            return flow {
                val index = requests++
                val script = responses ?: awaitCancellation()
                val response = script[index]
                if (index == 0) {
                    release.await()
                    pauseAfterFirstChunk?.let { resume ->
                        emit(response.take(120))
                        resume.await()
                        emit(response.drop(120))
                        return@flow
                    }
                }
                if (index == 1) pauseSecondRequest?.await()
                emit(response)
            }
        }
    }

    private fun config(twoStep: Boolean, readAloud: Boolean) = SurveyConfig(
        graph = SurveyConfig.Graph(startId = "Start", nodes = listOf(
            NodeDTO(id = "Start", type = "START", nextId = "Q8"),
            NodeDTO(id = "Q8", type = "AI", question = "Question", nextId = "Q9", readAloud = readAloud),
            NodeDTO(id = "Q9", type = "AI", question = "Other question", nextId = "Done"),
            NodeDTO(id = "Info", type = "INFO", question = "Info", nextId = "Text"),
            NodeDTO(id = "Text", type = "TEXT", question = "Text", nextId = "Single"),
            NodeDTO(id = "Single", type = "SINGLE_CHOICE", question = "Single", options = listOf("One"), nextId = "Multi"),
            NodeDTO(id = "Multi", type = "MULTI_CHOICE", question = "Multi", options = listOf("One"), nextId = "Q06"),
            NodeDTO(id = "Q06", type = "NUMBER", question = "Number", nextId = "Q07"),
            NodeDTO(id = "Q07", type = "AI", question = "Q07 original question", nextId = "Q08"),
            NodeDTO(id = "Q08", type = "AI", question = "Q08 original question", nextId = "Q09"),
            NodeDTO(id = "Q09", type = "AI", question = "Q09 original question", nextId = "Done"),
            NodeDTO(id = "Done", type = "DONE"),
        )),
        prompts = listOf(if (twoStep) {
            SurveyConfig.Prompt(nodeId = "Q8", evalPrompt = "{{QUESTION}} {{ANSWER}}", followupPrompt = "{{QUESTION}} {{EVAL_JSON}}")
        } else SurveyConfig.Prompt(nodeId = "Q8", prompt = "{{QUESTION}} {{ANSWER}}")) +
            if (twoStep) listOf(SurveyConfig.Prompt(
                nodeId = "Q07", evalPrompt = "{{QUESTION}} {{ANSWER}}", followupPrompt = "{{QUESTION}} {{EVAL_JSON}}"
            )) else emptyList(),
    )

    private companion object {
        const val PREPARING_FOLLOWUP = "Preparing follow-up…"
        const val NO_FOLLOWUP = "No new follow-up question was available."
        const val NEEDS_FOLLOWUP = """{"score":30,"missing_points":["yield loss"],"followup_needed":true}"""
        const val FIRST_FOLLOWUP = "How much yield was lost?"
        const val SECOND_FOLLOWUP = "Which part of the harvest was affected?"
    }
}
