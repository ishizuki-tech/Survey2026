package com.negi.survey.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseSamplingConfigTest {
    @Test
    fun swahili_q11_resolves_only_its_configured_phase_temperatures() {
        val config = SurveyConfigLoader.fromFileStrictValidated(assetFile("survey_config_sw_10.yaml").absolutePath)

        assertEquals(0.0, config.resolvePhaseSampling("Q11")?.evalTemperature)
        assertEquals(1.0, config.resolvePhaseSampling("Q11")?.followupTemperature)
        assertNull(config.resolvePhaseSampling("Q10"))
    }

    @Test
    fun q11_prompt_requires_a_bag_count_and_uses_the_approved_control_instruction() {
        val config = SurveyConfigLoader.fromFileStrictValidated(assetFile("survey_config_sw_10.yaml").absolutePath)

        assertTrue(config.resolveEvalPrompt("Q11")!!.contains("idadi ya magunia"))
        assertTrue(config.resolveFollowupPrompt("Q11")!!.contains("nafsi ya pili"))
    }

    @Test
    fun incomplete_or_out_of_range_phase_sampling_is_rejected() {
        val incomplete = configWithSampling(SurveyConfig.PhaseSampling(evalTemperature = 0.0))
        val outOfRange = configWithSampling(
            SurveyConfig.PhaseSampling(evalTemperature = -0.1, followupTemperature = 1.0),
        )

        assertTrue(incomplete.validate().any { it.contains("requires both eval_temperature") })
        assertTrue(outOfRange.validate().any { it.contains("eval_temperature") && it.contains("[0.0,2.0]") })
    }

    private fun configWithSampling(sampling: SurveyConfig.PhaseSampling): SurveyConfig =
        SurveyConfig(
            graph = SurveyConfig.Graph(
                startId = "Start",
                nodes = listOf(
                    NodeDTO("Start", "START", nextId = "Q11"),
                    NodeDTO("Q11", "AI", nextId = "Done"),
                    NodeDTO("Done", "DONE"),
                ),
            ),
            slm = SurveyConfig.SlmMeta(
                keyContractEval = "eval",
                keyContractFollowup = "followup",
            ),
            prompts = listOf(
                SurveyConfig.Prompt(
                    nodeId = "Q11",
                    evalPrompt = "eval",
                    followupPrompt = "followup",
                    phaseSampling = sampling,
                ),
            ),
        )

    private fun assetFile(name: String): File {
        val root = File(checkNotNull(System.getProperty("user.dir")))
        return listOf(
            File(root, "app/src/main/assets/$name"),
            File(root, "src/main/assets/$name"),
        ).firstOrNull(File::isFile) ?: error("Missing asset $name")
    }
}
