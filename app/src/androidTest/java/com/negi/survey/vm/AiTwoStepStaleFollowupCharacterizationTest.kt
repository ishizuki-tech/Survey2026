package com.negi.survey.vm

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import com.negi.survey.slm.PromptPhase
import com.negi.survey.slm.Repository
import com.negi.survey.slm.RepositoryTraceContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Confirms a cancelled FOLLOWUP callback cannot persist over a replacement EVAL chain. */
@RunWith(AndroidJUnit4::class)
class AiTwoStepStaleFollowupCharacterizationTest {

    @Test
    fun cancelled_old_followup_cannot_commit_over_replacement() = runBlocking {
        val enteredFollowup = CompletableDeferred<Unit>()
        val releaseFollowup = CompletableDeferred<Unit>()
        val survey = survey()
        val low = """{"score":30,"missing_points":["yield_loss"],"followup_needed":true}"""
        val high = """{"score":95,"missing_points":[],"followup_needed":false}"""
        val repository = object : Repository {
            var requestCount = 0

            override fun buildPrompt(userPrompt: String, phase: PromptPhase): String = userPrompt

            override fun buildPrompt(userPrompt: String): String = userPrompt

            override suspend fun request(
                prompt: String,
                traceContext: RepositoryTraceContext?,
            ): Flow<String> = flow {
                when (requestCount++) {
                    0 -> emit(low)
                    1 -> {
                        enteredFollowup.complete(Unit)
                        withContext(NonCancellable) { releaseFollowup.await() }
                        emit("How much yield was lost?")
                    }
                    else -> emit(high)
                }
            }
        }
        val ai = AiViewModel(repository, defaultTimeoutMs = 2_000, ioDispatcher = Dispatchers.IO)
        ai.ensureConversationContext("Q8", "Original question", survey.getAnswer("Q8"))

        val old = ai.evaluateSurveyTwoStepAsync(survey, "Q8", "Q8", "Original question")
        withTimeout(2_000) { enteredFollowup.await() }

        ai.resetStates()
        val replacement = ai.evaluateSurveyTwoStepAsync(survey, "Q8", "Q8", "Original question")
        releaseFollowup.complete(Unit)

        withTimeout(5_000) { replacement.join() }
        old.join()

        assertEquals(SurveyAiReason.ACHIEVED, survey.aiReasons.value["Q8"])
        assertTrue(survey.followups.value["Q8"].isNullOrEmpty())
        assertEquals(2, survey.remainingFollowups("Q8"))
        assertFalse(ai.conversationStateFlow("Q8").value.validationFailed)
        assertTrue(ai.conversationStateFlow("Q8").value.turnCompleted)
    }

    private fun survey(): SurveyViewModel = SurveyViewModel(
        NavBackStack<NavKey>(FlowHome),
        SurveyConfig(
            aiInteraction = SurveyConfig.AiInteraction(maxFollowups = 2),
            graph = SurveyConfig.Graph(
                startId = "Start",
                nodes = listOf(
                    NodeDTO("Start", "START", nextId = "Q8"),
                    NodeDTO("Q8", "AI", question = "Original question", nextId = "Done"),
                    NodeDTO("Done", "DONE"),
                ),
            ),
            prompts = listOf(
                SurveyConfig.Prompt(
                    nodeId = "Q8",
                    evalPrompt = "Question: {{QUESTION}}\nAnswer: {{ANSWER}}\nHistory: {{HISTORY}}",
                    followupPrompt = "Question: {{QUESTION}}\nAnswer: {{ANSWER}}\nEVAL_JSON: {{EVAL_JSON}}",
                ),
            ),
        ),
    ).also { it.setAnswer("Fall armyworm affected my crop.", "Q8") }
}
