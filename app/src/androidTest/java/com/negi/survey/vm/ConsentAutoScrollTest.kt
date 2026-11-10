package com.negi.survey.vm

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.SingleChoiceNodeScreen
import com.negi.survey.SurveyNavHost
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.screens.rememberConsentScrollState
import com.negi.survey.slm.Repository
import com.negi.survey.slm.RepositoryTraceContext
import com.negi.survey.ui.theme.SurveyNavTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConsentAutoScrollTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val viewport = mutableIntStateOf(280)
    private val scrollable
        get() = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))

    private fun range() = scrollable.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
    private fun position() = range().value()
    private fun maxPosition() = range().maxValue()
    private fun advance(ms: Long) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun scrollToBottom() {
        val max = maxPosition()
        scrollable.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, max) }
        advance(700)
        assertEquals(max, position(), 0f)
    }

    @Test
    fun englishConsentScrollsAfterPauseAtReadingPaceAndStopsAtBottom() =
        verifyConsentAutoScroll("survey_config10.yaml")

    @Test
    fun swahiliConsentScrollsAfterPauseAtReadingPaceAndStopsAtBottom() =
        verifyConsentAutoScroll("survey_config_sw_10.yaml")

    private fun verifyConsentAutoScroll(asset: String) {
        val config = loadConfig(asset)
        val (vm, content) = surveyAt(config, "Q00")
        show(content)
        assertEquals(0f, position(), 0f)
        assertTrue(maxPosition() > 0f)
        advance(1_900)
        assertEquals(0f, position(), 0f)
        advance(600)
        val afterPause = position()
        assertTrue(afterPause > 0f)
        advance(1_000)
        assertEquals(30f, (position() - afterPause) / rule.density.density, 2f)

        val remainingMs = ((maxPosition() - position()) / rule.density.density / 30f * 1_000).toLong()
        advance(remainingMs + 500)
        assertEquals(maxPosition(), position(), 0f)
        rule.onNodeWithText(vm.currentNode.value.options.first()).assertIsDisplayed()
        rule.onNodeWithText("Back").assertIsDisplayed()
        rule.onNodeWithText("Next").assertIsDisplayed().assertIsNotEnabled()
        advance(2_000)
        assertEquals(maxPosition(), position(), 0f)
        rule.runOnIdle {
            assertEquals("Q00", vm.currentNodeId)
            assertNull(vm.single.value)
            assertEquals("", vm.getAnswer("Q00"))
        }
    }

    @Test
    fun consentHeaderAndReplayStayReadableAtNormalAndLargeFontScales() {
        val consent = loadConfig("survey_config10.yaml").graph.nodes.first { it.id == "Q00" }
        val speaking = mutableStateOf(false)
        val scale = mutableIntStateOf(1)
        var replayCalls = 0
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale.intValue.toFloat())) {
                SurveyNavTheme {
                    SingleChoiceNodeScreen(
                        title = consent.title.orEmpty(),
                        question = consent.question.orEmpty(),
                        options = consent.options.orEmpty(),
                        selected = null,
                        onSelect = {},
                        onSubmit = { _, _ -> },
                        onBack = {},
                        onReplay = { replayCalls++; speaking.value = !speaking.value },
                        isSpeaking = speaking.value,
                        consentScroll = rememberConsentScrollState("consent-visual-fixture")
                    )
                }
            }
        }
        val title = rule.onNodeWithText(consent.title.orEmpty())
        val speaker = rule.onNodeWithText("\uD83D\uDD0A")
        val stop = rule.onNodeWithText("\u23F9")
        title.assertIsDisplayed()
        val speakerColor = speaker.textLayout().layoutInput.style.color
        val normalTitleHeight = title.textLayout().size.height
        captureConsentFixture("consent-speaker")
        rule.runOnIdle { speaking.value = true }
        advance(32)
        stop.assertIsDisplayed()
        val stopPixels = stop.captureToImage().toPixelMap()
        assertEquals(Color.White, stopPixels[stopPixels.width / 2, stopPixels.height / 2])
        val cornerOffset = with(rule.density) { 14.dp.roundToPx() }
        assertEquals(
            Color(0xFFD65A4A),
            stopPixels[stopPixels.width / 2 + cornerOffset, stopPixels.height / 2 + cornerOffset]
        )
        captureConsentFixture("consent-stop")
        stop.performClick()
        advance(32)
        assertEquals(speakerColor, speaker.textLayout().layoutInput.style.color)
        speaker.performClick()
        advance(32)
        stop.assertIsDisplayed()
        stop.performClick()
        advance(32)
        assertEquals(3, replayCalls)
        rule.runOnIdle { scale.intValue = 2 }
        advance(32)
        title.assertIsDisplayed()
        speaker.assertIsDisplayed()
        assertTrue(title.textLayout().size.height > normalTitleHeight)
        assertTrue(!title.textLayout().hasVisualOverflow)
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun captureConsentFixture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureFieldUi") != "true") return
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

    @Test
    fun fittingContentDoesNotScroll() {
        viewport.intValue = 480
        show {
            SingleChoiceNodeScreen(
                title = "Consent",
                question = "Do you consent?",
                options = listOf("Yes", "No"),
                selected = null,
                onSelect = {},
                onSubmit = { _, _ -> },
                onBack = {},
                consentScroll = rememberConsentScrollState("run")
            )
        }
        assertEquals(0f, maxPosition(), 0f)
        advance(4_000)
        assertEquals(0f, position(), 0f)
        rule.onNodeWithText("Next").assertIsNotEnabled()
    }

    @Test
    fun touchDuringPauseCancelsEvenWithoutDragAndRecompositionDoesNotRestart() {
        val (_, content) = surveyAt(loadConfig("survey_config10.yaml"), "Q00")
        show(content)
        advance(700)
        scrollable.performTouchInput { down(center); up() }
        viewport.intValue = 300
        advance(4_000)
        assertEquals(0f, position(), 0f)
    }

    @Test
    fun dragTakesOverActiveScrollAndLeavesItStopped() {
        val (_, content) = surveyAt(loadConfig("survey_config10.yaml"), "Q00")
        show(content)
        advance(3_000)
        val beforeDrag = position()
        assertTrue(beforeDrag > 0f)
        scrollable.performTouchInput { down(center) }
        advance(500)
        assertEquals(beforeDrag, position(), 0f)
        scrollable.performTouchInput {
            advanceEventTime(100)
            moveBy(Offset(0f, -height / 3f))
            advanceEventTime(300)
            up()
        }
        advance(1_000)
        val draggedPosition = position()
        assertTrue(draggedPosition > beforeDrag && draggedPosition < maxPosition())
        advance(4_000)
        assertEquals(draggedPosition, position(), 0f)
    }

    @Test
    fun backRestoresPositionAndAnswerAndNewRunRearmsAutoScroll() {
        val (vm, content) = surveyAt(loadConfig("survey_config10.yaml"), "Q00")
        show(content)
        val yes = vm.currentNode.value.options.first()
        val bottomMs = (maxPosition() / rule.density.density / 30f * 1_000).toLong()
        advance(bottomMs + 2_500)
        val savedPosition = position()
        assertTrue(savedPosition > 0f)
        rule.onNodeWithText(yes).assertIsDisplayed().performClick()
        advance(32)
        rule.onNodeWithText("Next").performClick()
        advance(700)
        rule.runOnIdle { assertEquals("Q01", vm.currentNodeId) }
        scrollToBottom()
        rule.onNodeWithText("Back").assertIsDisplayed().performClick()
        advance(700)
        rule.runOnIdle { assertEquals("Q00", vm.currentNodeId) }
        assertEquals(savedPosition, position(), 0f)
        rule.onNodeWithText("Next").assertIsEnabled()
        rule.runOnIdle {
            assertEquals("Q00", vm.currentNodeId)
            assertEquals(yes, vm.single.value)
        }
        rule.onNodeWithText(yes).assertIsDisplayed().performClick()
        advance(32)
        rule.onNodeWithText("Next").assertIsNotEnabled()

        rule.runOnIdle {
            vm.resetToStart()
            vm.goto("Q00")
        }
        advance(700)
        assertEquals(0f, position(), 0f)
        advance(2_100)
        assertTrue(position() > 0f)
        rule.runOnIdle { assertNull(vm.single.value) }
    }

    @Test
    fun leavingDuringPauseCancelsAndBackDoesNotRearm() {
        val (vm, content) = surveyAt(loadConfig("survey_config10.yaml"), "Q00")
        show(content)
        advance(600)
        rule.runOnIdle { vm.backToPrevious() }
        advance(3_000)
        rule.runOnIdle { vm.goto("Q00") }
        advance(3_000)
        assertEquals(0f, position(), 0f)
    }

    @Test
    fun leavingDuringAnimationStopsAtTheLastPosition() {
        val (vm, content) = surveyAt(loadConfig("survey_config10.yaml"), "Q00")
        show(content)
        advance(3_000)
        val positionBeforeLeaving = position()
        assertTrue(positionBeforeLeaving > 0f)
        rule.runOnIdle { vm.goto("Q01") }
        advance(3_000)
        rule.runOnIdle { vm.backToPrevious() }
        advance(3_000)
        // Allow one frame for the navigation change to reach the composition.
        assertEquals(positionBeforeLeaving, position(), rule.density.density)
    }

    @Test
    fun recreationDuringPauseKeepsTheFirstVisitClaim() {
        val (_, content) = surveyAt(loadConfig("survey_config10.yaml"), "Q00")
        show(content)
        advance(700)
        assertEquals(0f, position(), 0f)
        rule.activityRule.scenario.recreate()
        rule.activityRule.scenario.onActivity { it.setContent(content = content) }
        advance(4_000)
        assertEquals(0f, position(), 0f)
    }

    @Test
    fun recreationPreservesActivePositionWithoutRearmingAndLargerViewportClampsIt() {
        val (_, content) = surveyAt(loadConfig("survey_config10.yaml"), "Q00")
        show(content)
        advance(3_500)
        val savedPosition = position()
        assertTrue(savedPosition > 0f && savedPosition < maxPosition())
        rule.activityRule.scenario.recreate()
        rule.activityRule.scenario.onActivity { it.setContent(content = content) }
        advance(4_000)
        assertEquals(savedPosition, position(), 0f)

        scrollToBottom()
        val bottomPosition = position()
        rule.runOnIdle { viewport.intValue = 860 }
        advance(4_000)
        assertTrue(maxPosition() < bottomPosition)
        assertEquals(maxPosition(), position(), 0f)
    }

    @Test
    fun otherSingleAndMultipleChoiceScreensStayStillEvenWhenTheyOverflow() {
        val config = loadConfig("survey_config10.yaml")
        val multi = NodeDTO("Multi", "MULTI_CHOICE", title = "Multiple choices", options = listOf("A", "B"))
        val ids = listOf("Pre5", "Q01", "Q02", "Q14a", "Multi")
        val longConfig = config.copy(graph = config.graph.copy(
            nodes = (config.graph.nodes + multi).map { node ->
                if (node.id in ids) node.copy(question = "A long question for scroll testing. ".repeat(50)) else node
            }
        ))
        val (vm, content) = surveyAt(longConfig, ids.first())
        show(content)
        for (id in ids) {
            rule.runOnIdle { if (vm.currentNodeId != id) vm.goto(id) }
            advance(4_000)
            assertTrue("Expected overflow for $id", maxPosition() > 0f)
            assertEquals(id, 0f, position(), 0f)
        }
    }

    private fun loadConfig(asset: String) = SurveyConfigLoader.fromAssets(rule.activity, asset)

    private fun show(content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.activityRule.scenario.onActivity { it.setContent(content = content) }
        advance(16)
    }

    private fun surveyAt(
        config: SurveyConfig,
        nodeId: String
    ): Pair<SurveyViewModel, @Composable () -> Unit> {
        val nav = NavBackStack<NavKey>(FlowHome)
        val vm = SurveyViewModel(nav, config)
        val ai = AiViewModel(object : Repository {
            override suspend fun request(prompt: String, traceContext: RepositoryTraceContext?) =
                error("Consent tests do not use AI inference")

            override fun buildPrompt(userPrompt: String) = userPrompt
        })
        vm.goto(nodeId)
        val content: @Composable () -> Unit = {
            MaterialTheme {
                Box(Modifier.height(viewport.intValue.dp)) {
                    SurveyNavHost(
                        vmSurvey = vm,
                        vmAI = ai,
                        backStack = nav,
                        whisperMeta = SurveyConfig.WhisperMeta(enabled = false),
                        ttsMeta = SurveyConfig.TtsMeta(enabled = false),
                        onResolveUploadStatusDetails = { UploadStatusDetails(emptyList(), emptyList()) },
                        onClearCompletedSurveys = {}
                    )
                }
            }
        }
        return vm to content
    }
}
