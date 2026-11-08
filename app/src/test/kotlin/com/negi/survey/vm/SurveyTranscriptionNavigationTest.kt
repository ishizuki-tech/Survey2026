package com.negi.survey.vm

import android.util.Log
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import io.mockk.every
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class SurveyTranscriptionNavigationTest {
    @Test
    fun programmatic_navigation_is_dropped_until_a_new_action_after_transcription() = mockkStatic(Log::class) {
        every { Log.println(any(), any(), any()) } returns 0
        val nav = NavBackStack<NavKey>(FlowHome)
        val config = SurveyConfig(graph = SurveyConfig.Graph(
            startId = "Start",
            nodes = listOf(
                NodeDTO("Start", "START", nextId = "Question"),
                NodeDTO("Question", "TEXT", nextId = "Review"),
                NodeDTO("Review", "REVIEW")
            )
        ))
        val survey = SurveyViewModel(nav, config)
        val transcribing = MutableStateFlow(false)
        survey.bindNavigationToProcessing(transcribing)
        survey.advanceToNext()
        survey.setAnswer("Recorded answer", "Question")
        val id = survey.surveyUuid.value
        val stack = nav.toList()
        val presentation = survey.questionPresentation.value

        transcribing.value = true
        repeat(2) {
            survey.advanceToNext()
            survey.backToPrevious()
            survey.goto("Review")
            survey.replaceTo("Review")
            survey.resetToStart()
        }

        transcribing.value = false
        assertEquals("Question", survey.currentNodeId)
        assertEquals(stack, nav.toList())
        assertEquals(presentation, survey.questionPresentation.value)
        assertEquals(id, survey.surveyUuid.value)
        assertEquals("Recorded answer", survey.getAnswer("Question"))

        survey.advanceToNext()
        assertEquals("Review", survey.currentNodeId)
        assertEquals(stack + FlowReview, nav.toList())
    }
}
