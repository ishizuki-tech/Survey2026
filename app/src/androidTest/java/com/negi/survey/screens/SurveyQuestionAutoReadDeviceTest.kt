package com.negi.survey.screens

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.SurveyNavHost
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.slm.Repository
import com.negi.survey.slm.RepositoryTraceContext
import com.negi.survey.ui.theme.SurveyNavTheme
import com.negi.survey.vm.AiViewModel
import com.negi.survey.vm.FlowHome
import com.negi.survey.vm.SurveyAiReason
import com.negi.survey.vm.SurveyViewModel
import com.negi.survey.vm.TtsController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in check for a physical device with Google's offline English voice installed. */
class SurveyQuestionAutoReadDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun realTtsWithProductionNavHostAndEnglishQuestionnaire() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("issue160RealTts") == "true")
        val starts = MutableStateFlow(0)
        lateinit var survey: SurveyViewModel
        lateinit var controller: TtsController
        lateinit var ai: AiViewModel

        rule.setContent {
            val context = LocalContext.current
            val owner = checkNotNull(LocalViewModelStoreOwner.current)
            val config = remember { SurveyConfigLoader.fromAssets(context, "survey_config10.yaml") }
            val stack = rememberNavBackStack(FlowHome)
            survey = remember {
                SurveyViewModel(stack, config).apply {
                    goto("Q06")
                    setAnswer("3", "Q06")
                }
            }
            ai = remember {
                AiViewModel(object : Repository {
                    override fun buildPrompt(userPrompt: String) = userPrompt
                    override suspend fun request(prompt: String, traceContext: RepositoryTraceContext?): Flow<String> =
                        flowOf("""{"score":95,"missing_points":[],"followup_needed":false}""")
                })
            }
            SurveyNavTheme {
                SurveyNavHost(
                    vmSurvey = survey,
                    vmAI = ai,
                    backStack = stack,
                    whisperMeta = SurveyConfig.WhisperMeta(enabled = false),
                    ttsMeta = config.tts,
                    sessionId = "issue160",
                    onResolveUploadStatusDetails = { error("This fixture does not open upload details") },
                    onClearCompletedSurveys = {}
                )
            }
            LaunchedEffect(owner) {
                controller = ViewModelProvider(owner)[
                    "TtsController_issue160_en_com.google.android.tts_true", TtsController::class.java
                ]
                controller.isSpeaking.collect { if (it) starts.value += 1 }
            }
        }

        fun awaitUtterance(expected: Int) {
            rule.waitUntil(15_000) { starts.value >= expected && !controller.isSpeaking.value }
            assertEquals(expected, starts.value)
        }

        fun assertStaysSilent(route: String, nodeId: String, expectedStarts: Int) {
            rule.runOnIdle {
                assertTrue("$route requires a ready TTS engine", controller.isReady.value)
                assertEquals(nodeId, survey.currentNodeId)
            }
            // Allow the real engine time to start any queued utterance.
            val unexpectedStart = runBlocking {
                withTimeoutOrNull(3_000) { starts.first { it != expectedStarts } }
            }
            assertNull("$route started TTS during the three-second observation window", unexpectedStart)
            rule.runOnIdle {
                assertEquals(nodeId, survey.currentNodeId)
                assertEquals("$route must remain silent", expectedStarts, starts.value)
            }
        }

        awaitUtterance(1) // Q06
        rule.onNodeWithText("Next").performClick()
        awaitUtterance(2) // New Q07
        rule.runOnIdle {
            assertEquals("Q07", survey.currentNodeId)
            survey.setAiReason("Q07", SurveyAiReason.FAILURE)
            survey.backToPrevious()
        }
        awaitUtterance(3) // Q06 again
        rule.onNodeWithText("Next").performClick()
        assertStaysSilent("Q06 → Q07 with FAILURE", "Q07", expectedStarts = 3)
        rule.runOnIdle {
            survey.setAnswer("Saved answer", "Q07")
            assertTrue(survey.addFollowupQuestion("Q07", "Historical follow-up?"))
            survey.advanceToNext()
        }
        awaitUtterance(4) // Q08
        rule.runOnIdle {
            assertEquals("Q08", survey.currentNodeId)
            survey.backToPrevious()
        }
        assertStaysSilent("Q08 → Q07 with FAILURE", "Q07", expectedStarts = 4)
        rule.onNodeWithText("🔊").performClick()
        awaitUtterance(5) // Manual Replay on Failure
        rule.runOnIdle {
            ai.beginValidationTurn("sid=${survey.sessionId.value}|nid=Q07")
            assertTrue(survey.addFollowupQuestion("Q07", "New follow-up after retry?"))
            survey.setAiReason("Q07", null)
        }
        awaitUtterance(6)

        val editor = rule.onNode(hasSetTextAction())
        val next = rule.onNodeWithText("Next")
        val back = rule.onNodeWithText("Back")
        editor.performTextReplacement("Unsent follow-up draft")
        next.performScrollTo().performClick()
        awaitUtterance(7) // Q08
        back.performScrollTo().performClick()
        awaitUtterance(8) // Q07 follow-up with draft
        editor.assertTextContains("Unsent follow-up draft")
        next.performScrollTo().performClick()
        awaitUtterance(9) // Q08

        editor.performTextReplacement("Three bags")
        next.performScrollTo().performClick()
        awaitUtterance(10) // Q09
        back.performScrollTo().performClick()
        awaitUtterance(11) // Q08 original with draft
        editor.assertTextContains("Three bags")
        editor.performTextReplacement("Four bags")
        editor.performTextClearance()
        assertStaysSilent("Editing and clearing the Q08 draft", "Q08", expectedStarts = 11)

        editor.performTextReplacement("Three bags")
        rule.onNodeWithContentDescription("Send").performClick()
        rule.waitUntil(5_000) { "Q08" in survey.submittedAiOriginals.value && !ai.blocksNavigation() }
        rule.runOnIdle {
            assertEquals("Three bags", survey.getAnswer("Q08"))
            assertTrue(survey.followups.value["Q08"].isNullOrEmpty())
        }
        next.performScrollTo().performClick()
        awaitUtterance(12) // Q09
        back.performScrollTo().performClick()
        assertStaysSilent("Q09 → Q08 after Send", "Q08", expectedStarts = 12)
    }
}
