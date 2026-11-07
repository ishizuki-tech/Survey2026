package com.negi.survey.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import com.negi.survey.vm.FlowDone
import com.negi.survey.vm.FlowHome
import com.negi.survey.vm.FlowReview
import com.negi.survey.vm.SurveyFinalizationState
import com.negi.survey.vm.SurveyViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReviewScreenScrollTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val scrollable
        get() = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))

    private fun scrollRange() =
        scrollable.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    @Test
    fun longReviewShowsItsTailAndActionsOnInitialLayout() {
        rule.mainClock.autoAdvance = false
        showReview(survey())
        assertBottom()
        rule.onNodeWithText("Review").assertIsNotDisplayed()
    }

    @Test
    fun shortReviewFitsWithHeaderAtTopAndBothActionsVisible() {
        showReview(survey(long = false))
        val header = rule.onNodeWithText("Review").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(scrollable.getBoundsInRoot().top, header.top)
        assertActionsFullyVisible()
        assertEquals(0f, scrollRange().maxValue(), 0f)
    }

    @Test
    fun manualScrollSurvivesRecompositionAndAnswerAndFollowupChanges() {
        val vm = survey()
        val state = mutableStateOf<SurveyFinalizationState>(SurveyFinalizationState.Idle)
        showReview(vm, state)

        assertBottom()
        val bottom = scrollRange().value()
        scrollable.performTouchInput { swipeDown() }
        val manualPosition = scrollRange().value()
        assertTrue(manualPosition != bottom)
        rule.onNodeWithText("Finish").assertIsNotDisplayed()

        rule.runOnIdle { state.value = SurveyFinalizationState.Finishing }
        assertEquals(manualPosition, scrollRange().value(), 0f)
        rule.runOnIdle { vm.setAnswer("Updated original answer", "Q01") }
        assertEquals(manualPosition, scrollRange().value(), 0f)
        rule.runOnIdle { vm.addFollowupQuestion("Q24", "Added follow-up question") }
        assertEquals(manualPosition, scrollRange().value(), 0f)
        rule.onNodeWithText("Finishing...").assertIsNotDisplayed()
    }

    @Test
    fun returningThroughPoppedAndRetainedReviewEntriesStartsAtBottom() {
        val (vm, nav) = surveyWithNavigation()
        rule.setContent {
            MaterialTheme {
                NavDisplay(
                    backStack = nav,
                    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                    entryProvider = entryProvider {
                        entry<FlowHome> { Text("Survey start") }
                        entry<FlowReview> {
                            ReviewScreen(vm, SurveyFinalizationState.Idle, vm::advanceToNext, vm::backToPrevious)
                        }
                        entry<FlowDone> { Text("Survey complete") }
                    }
                )
            }
        }
        assertBottom()
        scrollable.performScrollToIndex(0)
        rule.onNodeWithText("Review").assertIsDisplayed()
        rule.runOnIdle { vm.backToPrevious() }
        rule.onNodeWithText("Survey start").assertIsDisplayed()
        rule.runOnIdle { vm.advanceToNext() }
        assertBottom()

        scrollable.performScrollToIndex(0)
        rule.onNodeWithText("Review").assertIsDisplayed()
        rule.runOnIdle { vm.advanceToNext() }
        rule.onNodeWithText("Survey complete").assertIsDisplayed()
        rule.runOnIdle { vm.backToPrevious() }
        assertBottom()
    }

    @Test
    fun restoringActiveVisitKeepsItsManualPosition() {
        val vm = survey()
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme {
                Box(Modifier.height(440.dp)) {
                    ReviewScreen(vm, SurveyFinalizationState.Idle, onFinish = {}, onBack = {})
                }
            }
        }
        assertBottom()
        val bottom = scrollRange().value()
        scrollable.performTouchInput { swipeDown() }
        val manualPosition = scrollRange().value()
        assertTrue(manualPosition != bottom)
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(manualPosition, scrollRange().value(), 0f)
        rule.onNodeWithText("Finish").assertIsNotDisplayed()
    }

    @Test
    fun finishingDisablesActionsAndErrorRestoresThem() {
        val state = mutableStateOf<SurveyFinalizationState>(SurveyFinalizationState.Finishing)
        showReview(survey(long = false), state)
        assertActionsFullyVisible(finishLabel = "Finishing...")
        rule.onNodeWithText("Back").assertIsNotEnabled()
        rule.onNodeWithText("Finishing...").assertIsNotEnabled()

        rule.runOnIdle { state.value = SurveyFinalizationState.Error("Try finishing again.") }
        rule.onNodeWithText("Try finishing again.").assertIsDisplayed()
        assertActionsFullyVisible()
        rule.onNodeWithText("Back").assertIsEnabled()
        rule.onNodeWithText("Finish").assertIsEnabled()
    }

    @Test
    fun oversizedErrorStartsWithActionsVisibleAndCanBeScrolled() {
        val error = (1..60).joinToString("\n") { "Error detail $it" }
        showReview(survey(), mutableStateOf(SurveyFinalizationState.Error(error)))
        assertActionsFullyVisible()
        val before = scrollRange().value()
        rule.onNodeWithText(error).assertIsDisplayed()
        scrollable.performTouchInput { swipeUp() }
        assertTrue(scrollRange().value() > before)
        rule.onNodeWithText("Finish").assertIsNotDisplayed()
    }

    private fun showReview(
        vm: SurveyViewModel,
        state: State<SurveyFinalizationState> =
            mutableStateOf(SurveyFinalizationState.Idle)
    ) {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.height(440.dp)) {
                    ReviewScreen(vm, state.value, onFinish = {}, onBack = {})
                }
            }
        }
    }

    private fun assertBottom() {
        assertActionsFullyVisible()
        rule.onNodeWithText("1. Q: Last follow-up").assertIsDisplayed()
        val range = scrollRange()
        assertTrue(range.value() > 0f)
        assertEquals(range.maxValue(), range.value(), 0f)
    }

    private fun assertActionsFullyVisible(finishLabel: String = "Finish") {
        val viewport = scrollable.getBoundsInRoot()

        for (label in listOf("Back", finishLabel)) {
            val action = rule.onNodeWithText(label).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$label must fit vertically in the list", action.top >= viewport.top && action.bottom <= viewport.bottom)
        }
    }

    private fun survey(long: Boolean = true) = surveyWithNavigation(long).first

    private fun surveyWithNavigation(long: Boolean = true): Pair<SurveyViewModel, NavBackStack<NavKey>> {
        val nav = NavBackStack<NavKey>(FlowHome)
        val config = SurveyConfig(
            graph = SurveyConfig.Graph(
                startId = "Start",
                nodes = listOf(
                    NodeDTO("Start", "START", nextId = "Review"),
                    NodeDTO("Review", "REVIEW", nextId = "Done"),
                    NodeDTO("Done", "DONE")
                )
            )
        )
        val vm = SurveyViewModel(nav, config)
        vm.advanceToNext()
        if (long) {
            repeat(24) { index ->
                val id = "Q" + (index + 1).toString().padStart(2, '0')
                vm.setQuestion("Original question ${index + 1}", id)
                vm.setAnswer("Original answer ${index + 1}", id)
            }
            vm.addFollowupQuestion("Q01", "First follow-up")
            vm.addFollowupQuestion("Q24", "Last follow-up")
        }
        return vm to nav
    }
}
