package com.negi.survey.screens

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
            val ai = remember {
                AiViewModel(object : Repository {
                    override fun buildPrompt(userPrompt: String) = userPrompt
                    override suspend fun request(prompt: String, traceContext: RepositoryTraceContext?): Flow<String> =
                        error("This playback fixture does not run inference")
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

        fun assertFailureReentryStaysSilent(route: String, expectedStarts: Int) {
            rule.runOnIdle {
                assertTrue("$route requires a ready TTS engine", controller.isReady.value)
                assertEquals("Q07", survey.currentNodeId)
                assertEquals(SurveyAiReason.FAILURE, survey.aiReasons.value["Q07"])
            }
            // Keep Q07 in view while the real engine has time to start any queued utterance.
            val unexpectedStart = runBlocking {
                withTimeoutOrNull(3_000) { starts.first { it != expectedStarts } }
            }
            assertNull("$route started TTS during the three-second observation window", unexpectedStart)
            rule.runOnIdle {
                assertEquals("Q07", survey.currentNodeId)
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
        assertFailureReentryStaysSilent("Q06 → Q07", expectedStarts = 3)
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
        assertFailureReentryStaysSilent("Q08 → Q07", expectedStarts = 4)
        rule.onNodeWithText("🔊").performClick()
        awaitUtterance(5) // Manual Replay on Failure
        rule.runOnIdle {
            assertTrue(survey.addFollowupQuestion("Q07", "New follow-up after retry?"))
            survey.setAiReason("Q07", null)
        }
        awaitUtterance(6)
    }
}
