package com.negi.survey.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.negi.survey.MainActivity
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurveySessionActivityRecreationTest {

    @Test
    fun active_survey_progress_survives_activity_recreation_and_true_session_end_clears_it() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val sessionKey = "rotation-test@1"
        lateinit var survey: SurveyViewModel
        lateinit var firstOwnerStore: androidx.lifecycle.ViewModelStore

        scenario.onActivity { activity ->
            val sessions = ViewModelProvider(activity)[SurveySessionStore::class.java]
            val owner = sessions.ownerFor(sessionKey)
            firstOwnerStore = owner.viewModelStore
            survey = ViewModelProvider(owner, surveyFactory(NavBackStack<NavKey>(FlowHome)))["survey", SurveyViewModel::class.java]

            survey.advanceToNext()
            survey.setAnswer("kept answer", "Q1")
            assertEquals("Q1", survey.currentNodeId)
        }

        scenario.recreate()

        scenario.onActivity { activity ->
            val sessions = ViewModelProvider(activity)[SurveySessionStore::class.java]
            val owner = sessions.ownerFor(sessionKey)
            val restoredSurvey = ViewModelProvider(owner, surveyFactory(NavBackStack<NavKey>(FlowHome)))["survey", SurveyViewModel::class.java]

            assertSame(firstOwnerStore, owner.viewModelStore)
            assertSame(survey, restoredSurvey)
            assertEquals("Q1", restoredSurvey.currentNodeId)
            assertEquals("kept answer", restoredSurvey.getAnswer("Q1"))

            val restoredNav = NavBackStack<NavKey>(FlowHome, FlowText)
            restoredSurvey.attachNavigation(restoredNav)
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
                NodeDTO("Q1", "TEXT", question = "Question", nextId = "Done"),
                NodeDTO("Done", "DONE")
            )
        )
    )
}
