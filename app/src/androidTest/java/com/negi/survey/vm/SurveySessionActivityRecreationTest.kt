package com.negi.survey.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.negi.survey.MainActivity
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import com.negi.survey.slm.Repository
import com.negi.survey.slm.RepositoryTraceContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurveySessionActivityRecreationTest {

    @Test
    fun autoplayRejectsMismatchedPresentationSessionNodeIdAndNodeType() = runBlocking(Dispatchers.Main) {
        val survey = SurveyViewModel(NavBackStack<NavKey>(FlowHome), testConfig())
        val priorSession = survey.sessionId.value
        survey.resetToStart()
        survey.advanceToNext()
        val priorPresentation = survey.questionSpeechOwner()
        survey.advanceToNext()
        survey.backToPrevious()

        val current = survey.questionSpeechOwner()
        val target = QuestionPlaybackTarget("Question")
        assertEquals(priorPresentation.copy(presentation = current.presentation), current)
        val mismatches = listOf(
            "stale presentation" to priorPresentation,
            "stale session" to current.copy(session = priorSession),
            "wrong node ID" to current.copy(nodeId = "Done"),
            "wrong node type" to current.copy(nodeType = NodeType.NUMBER)
        )
        for ((reason, request) in mismatches) {
            assertFalse(reason, survey.ownsQuestionSpeech(request))
            assertFalse("$reason must not claim playback", survey.claimQuestionAutoRead(request, target))
        }
        assertTrue("Rejected requests must leave the valid claim available", survey.claimQuestionAutoRead(current, target))
    }

    @Test
    fun autoplayRejectsEntryRetainedBelowTheTopOfTheNavigationStack() = runBlocking(Dispatchers.Main) {
        val nav = NavBackStack<NavKey>(FlowHome)
        val survey = SurveyViewModel(nav, testConfig())
        survey.advanceToNext()
        val owner = survey.questionSpeechOwner()
        val target = QuestionPlaybackTarget("Question")
        assertEquals(FlowAI, nav.last())

        // Keep the request identity intact and move only the matching entry off the top.
        nav.add(FlowNumber)
        assertEquals(owner, survey.questionSpeechOwner())
        assertEquals(FlowAI, nav[nav.lastIndex - 1])
        assertFalse(survey.ownsQuestionSpeech(owner))
        assertFalse(survey.claimQuestionAutoRead(owner, target))

        nav.removeLastOrNull()
        assertTrue(survey.ownsQuestionSpeech(owner))
        assertTrue(survey.claimQuestionAutoRead(owner, target))
    }

    @Test
    fun active_survey_progress_survives_activity_recreation_and_true_session_end_clears_it() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val sessionKey = "rotation-test@1"
        lateinit var survey: SurveyViewModel
        lateinit var firstOwnerStore: androidx.lifecycle.ViewModelStore
        lateinit var target: QuestionPlaybackTarget
        lateinit var playbackOwner: QuestionSpeechOwner

        scenario.onActivity { activity ->
            val sessions = ViewModelProvider(activity)[SurveySessionStore::class.java]
            val owner = sessions.ownerFor(sessionKey)
            firstOwnerStore = owner.viewModelStore
            survey = ViewModelProvider(owner, surveyFactory(NavBackStack<NavKey>(FlowHome)))["survey", SurveyViewModel::class.java]

            survey.advanceToNext()
            survey.setAnswer("kept draft", "Q1")
            assertEquals("Q1", survey.currentNodeId)
            playbackOwner = survey.questionSpeechOwner()
            target = checkNotNull(resolveAiAutoReadTarget(
                survey.getQuestion("Q1"), "Q1" in survey.submittedAiOriginals.value,
                emptyList(), completed = false
            ))
            assertTrue(survey.claimQuestionAutoRead(playbackOwner, target))
        }

        scenario.recreate()

        scenario.onActivity { activity ->
            val sessions = ViewModelProvider(activity)[SurveySessionStore::class.java]
            val owner = sessions.ownerFor(sessionKey)
            val restoredSurvey = ViewModelProvider(owner, surveyFactory(NavBackStack<NavKey>(FlowHome)))["survey", SurveyViewModel::class.java]

            assertSame(firstOwnerStore, owner.viewModelStore)
            assertSame(survey, restoredSurvey)
            assertEquals("Q1", restoredSurvey.currentNodeId)
            assertEquals("kept draft", restoredSurvey.getAnswer("Q1"))

            val restoredNav = NavBackStack<NavKey>(FlowHome, FlowAI)
            restoredSurvey.attachNavigation(restoredNav)
            assertEquals(playbackOwner, restoredSurvey.questionSpeechOwner())
            assertFalse(restoredSurvey.claimQuestionAutoRead(playbackOwner, target))
            restoredSurvey.advanceToNext()
            assertEquals("Done", restoredSurvey.currentNodeId)
            assertEquals(3, restoredNav.size)

            sessions.clearSession(sessionKey)
            val nextOwner = sessions.ownerFor(sessionKey)
            val nextSurvey = ViewModelProvider(nextOwner, surveyFactory(NavBackStack<NavKey>(FlowHome)))["survey", SurveyViewModel::class.java]
            assertEquals("Start", nextSurvey.currentNodeId)
        }

        scenario.close()
    }

    @Test
    fun detailedOutcomeSurvivesActivityRecreationInTheSurveySession() {
        val aiFactory = viewModelFactory {
            initializer {
                AiViewModel(object : Repository {
                    override fun buildPrompt(userPrompt: String) = userPrompt
                    override suspend fun request(prompt: String, traceContext: RepositoryTraceContext?) =
                        flowOf("Invalid evaluation fixture")
                })
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val sessionKey = "outcome-rotation-test"
            lateinit var chain: Job
            lateinit var contextKey: String
            lateinit var ai: AiViewModel
            scenario.onActivity { activity ->
                val owner = ViewModelProvider(activity)[SurveySessionStore::class.java].ownerFor(sessionKey)
                val survey = ViewModelProvider(owner, surveyFactory(NavBackStack<NavKey>(FlowHome)))[SurveyViewModel::class.java]
                survey.advanceToNext()
                survey.setAnswer("Sample answer", "Q1")
                contextKey = "sid=${survey.sessionId.value}|nid=Q1"
                ai = ViewModelProvider(owner, aiFactory)[AiViewModel::class.java]
                ai.ensureConversationContext(contextKey, "Question", "")
                ai.appendUserMessage(contextKey, "Sample answer")
                chain = ai.evaluateSurveyTwoStepAsync(survey, "Q1", contextKey, "Question")
            }
            runBlocking { withTimeout(5_000) { chain.join() } }
            val outcomeRow = ai.chatHistoryFlow(contextKey).value.single { it.outcome != null }
            assertEquals(AiTurnOutcome.EVALUATION_FAILED, outcomeRow.outcome)

            scenario.recreate()

            scenario.onActivity { activity ->
                val sessions = ViewModelProvider(activity)[SurveySessionStore::class.java]
                val owner = sessions.ownerFor(sessionKey)
                val restored = ViewModelProvider(owner, aiFactory)[AiViewModel::class.java]
                assertSame(ai, restored)
                assertTrue(restored.conversationStateFlow(contextKey).value.validationFailed)
                assertEquals(outcomeRow, restored.chatHistoryFlow(contextKey).value.single { it.outcome != null })
                sessions.clearSession(sessionKey)
            }
        }
    }

    private fun surveyFactory(nav: NavBackStack<NavKey>) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SurveyViewModel(nav, testConfig()) as T
    }

    private fun testConfig() = SurveyConfig(
        graph = SurveyConfig.Graph(
            startId = "Start",
            nodes = listOf(
                NodeDTO("Start", "START", nextId = "Q1"),
                NodeDTO("Q1", "AI", question = "Question", nextId = "Done"),
                NodeDTO("Done", "DONE")
            )
        ),
        prompts = listOf(SurveyConfig.Prompt("Q1", evalPrompt = "{{ANSWER}}", followupPrompt = "{{QUESTION}}"))
    )
}
