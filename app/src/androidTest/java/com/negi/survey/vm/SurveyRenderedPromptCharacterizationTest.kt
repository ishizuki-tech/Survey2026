package com.negi.survey.vm

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.slm.LiteRtRepository
import com.negi.survey.slm.Model
import com.negi.survey.slm.PromptPhase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Golden rendering coverage for the production template and model-prompt seams. */
@RunWith(AndroidJUnit4::class)
class SurveyRenderedPromptCharacterizationTest {

    @Test
    fun english_catalog_eval_and_followup_render_all_runtime_fields_in_order() {
        val config = load("survey_config10.yaml")
        val survey = SurveyViewModel(NavBackStack<NavKey>(FlowHome), config)
        val question = "Which animals do you feed white maize, and how often?"
        val answer = "I feed it to cattle."
        val followup = "How often do you feed white maize to cattle?"
        val followupAnswer = "Every day."
        val evaluation = """{"score":65,"missing_points":["feeding_frequency"],"followup_needed":true}"""

        survey.addFollowupQuestion("Q14b", followup)
        survey.answerLastFollowup("Q14b", followupAnswer)

        assertEquals(
            """
            Expected answer target: Which animals are fed white maize and how often.
            Question: Which animals do you feed white maize, and how often?
            Original answer: I feed it to cattle.
            Answered followup pairs: Follow-up 1: How often do you feed white maize to cattle?
            Answer 1: Every day.
            Required component catalog:
            - animals: Which animals receive white maize
            - feeding_frequency: How often white maize is fed
            """.trimIndent(),
            survey.getEvalPrompt("Q14b", question, answer),
        )
        assertEquals(
            """
            Expected answer target: Which animals are fed white maize and how often.
            Question: Which animals do you feed white maize, and how often?
            Original answer: I feed it to cattle.
            Answered followup pairs: Follow-up 1: How often do you feed white maize to cattle?
            Answer 1: Every day.
            EVAL_JSON: {"score":65,"missing_points":["feeding_frequency"],"followup_needed":true}
            Resolved missing component:
            ID: feeding_frequency
            Description: How often white maize is fed
            """.trimIndent(),
            survey.getFollowupPrompt(
                "Q14b",
                question,
                answer,
                evaluation,
                survey.resolvedRequiredComponentsFor("Q14b", listOf("feeding_frequency")),
            ),
        )
    }

    @Test
    fun swahili_q11_rendering_and_model_ready_wrapping_preserve_phase_formatting() {
        val config = load("survey_config_sw_10.yaml")
        val survey = SurveyViewModel(NavBackStack<NavKey>(FlowHome), config)
        val question = "Kama wewe na wakulima wengine eneo lenu mmevuna mahindi kidogo sana, ukivuna chini ya magunia mangapi ndio utaamua kubadilisha mbegu?"
        val answer = "Ningebadilisha mbegu chini ya magunia 10."
        val followup = "Ni magunia mangapi kwa ekari?"
        val followupAnswer = "Magunia 10 kwa ekari."
        val evaluation = """{"score":75,"missing_points":["idadi ya magunia"],"followup_needed":true}"""

        survey.addFollowupQuestion("Q11", followup)
        survey.answerLastFollowup("Q11", followupAnswer)

        val eval = survey.getEvalPrompt("Q11", question, answer)
        assertEquals(
            """
            Lengo la jibu: idadi ya magunia, ambayo chini yake wewe na wakulima wengine eneo lenu mkivuna mahindi kidogo sana, mngeamua kubadilisha mbegu.
            Ikiwa idadi ya magunia haijatajwa katika jibu au historia, missing_points iwe ["idadi ya magunia"] na followup_needed iwe true.
            Question: $question
            Original answer: $answer
            Answered followup pairs: Follow-up 1: $followup
            Answer 1: $followupAnswer
            """.trimIndent(),
            eval,
        )
        val renderedFollowup = survey.getFollowupPrompt("Q11", question, answer, evaluation)
        assertEquals(
            """
            Lengo la jibu: idadi ya magunia, ambayo chini yake wewe na wakulima wengine eneo lenu mkivuna mahindi kidogo sana, mngeamua kubadilisha mbegu.
            Mwelekeze mhojiwa kwa nafsi ya pili; usinakili nafsi ya kwanza ya jibu la awali.
            Question: $question
            Original answer: $answer
            Answered followup pairs: Follow-up 1: $followup
            Answer 1: $followupAnswer
            EVAL_JSON: $evaluation
            """.trimIndent(),
            renderedFollowup,
        )

        val repository = LiteRtRepository(Model("unused", "/unused"), config)
        assertEquals(
            config.composeSystemPromptEval() + "\nINPUT:\n" + eval,
            repository.buildPrompt(eval, PromptPhase.EVAL),
        )
        assertEquals(
            config.composeSystemPromptFollowup() + "\nINPUT:\n" + renderedFollowup,
            repository.buildPrompt(renderedFollowup, PromptPhase.FOLLOWUP),
        )
    }

    private fun load(name: String): SurveyConfig =
        SurveyConfigLoader.fromAssetsStrictValidated(
            InstrumentationRegistry.getInstrumentation().targetContext,
            name,
        )
}
