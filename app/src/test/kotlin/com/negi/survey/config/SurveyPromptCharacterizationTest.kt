package com.negi.survey.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Golden configuration fixtures for representative shipped EVAL and FOLLOWUP templates. */
class SurveyPromptCharacterizationTest {

    @Test
    fun swahili_q11_templates_and_phase_sampling_are_exactly_preserved() {
        val config = load("survey_config_sw_10.yaml")
        assertEquals(
            """
            Lengo la jibu: idadi ya magunia, ambayo chini yake wewe na wakulima wengine eneo lenu mkivuna mahindi kidogo sana, mngeamua kubadilisha mbegu.
            Ikiwa idadi ya magunia haijatajwa katika jibu au historia, missing_points iwe ["idadi ya magunia"] na followup_needed iwe true.
            Question: {{QUESTION}}
            Original answer: {{ANSWER}}
            Answered followup pairs: {{HISTORY}}
            """.trimIndent(),
            config.resolveEvalPrompt("Q11"),
        )
        assertEquals(
            """
            Lengo la jibu: idadi ya magunia, ambayo chini yake wewe na wakulima wengine eneo lenu mkivuna mahindi kidogo sana, mngeamua kubadilisha mbegu.
            Mwelekeze mhojiwa kwa nafsi ya pili; usinakili nafsi ya kwanza ya jibu la awali.
            Question: {{QUESTION}}
            Original answer: {{ANSWER}}
            Answered followup pairs: {{HISTORY}}
            EVAL_JSON: {{EVAL_JSON}}
            """.trimIndent(),
            config.resolveFollowupPrompt("Q11"),
        )
        assertEquals(0.0f, config.resolvePhaseSampling("Q11")!!.evalTemperature!!.toFloat())
        assertEquals(1.0f, config.resolvePhaseSampling("Q11")!!.followupTemperature!!.toFloat())
        assertEquals(null, config.resolvePhaseSampling("Q10"))
    }

    @Test
    fun english_and_swahili_catalogs_and_reserved_template_tokens_remain_model_ready() {
        listOf("survey_config10.yaml", "survey_config_sw_10.yaml").forEach { name ->
            val config = load(name)
            val q14 = config.graph.nodes.single { it.id == "Q14b" }
            assertEquals(listOf("animals", "feeding_frequency"), q14.requiredComponentCatalog.map { it.id })
            val eval = config.resolveEvalPrompt("Q14b")!!
            val followup = config.resolveFollowupPrompt("Q14b")!!
            assertTrue(eval.contains("{{QUESTION}}") && eval.contains("{{ANSWER}}") && eval.contains("{{HISTORY}}"))
            assertTrue(followup.contains("{{QUESTION}}") && followup.contains("{{ANSWER}}") && followup.contains("{{HISTORY}}") && followup.contains("{{EVAL_JSON}}"))
            assertEquals(null, config.resolvePhaseSampling("Q14b"))
        }
    }

    private fun load(name: String): SurveyConfig =
        SurveyConfigLoader.fromFileStrictValidated(assetFile(name).absolutePath)

    private fun assetFile(name: String): File {
        val root = File(checkNotNull(System.getProperty("user.dir")))
        return listOf(
            File(root, "app/src/main/assets/$name"),
            File(root, "src/main/assets/$name"),
        ).firstOrNull(File::isFile) ?: error("Missing $name")
    }
}
