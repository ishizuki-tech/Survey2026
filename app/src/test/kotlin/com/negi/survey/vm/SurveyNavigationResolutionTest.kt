package com.negi.survey.vm

import com.negi.survey.config.SurveyConfigLoader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SurveyNavigationResolutionTest {

    @Test
    fun mapped_answer_overrides_next_id() {
        val node = choiceNode(mapOf("Stop" to "Done"))

        assertEquals("Done", node.resolveNextId("Stop"))
    }

    @Test
    fun unmapped_or_blank_answer_falls_back_to_next_id() {
        val node = choiceNode(mapOf("Stop" to "Done"))

        assertEquals("Q8", node.resolveNextId("Continue"))
        assertEquals("Q8", node.resolveNextId(""))
        assertEquals("Q8", node.resolveNextId(null))
    }

    @Test
    fun localized_answer_matching_is_exact_and_unicode_safe() {
        val localizedAnswer = "Ndiyo — miaka mitatu"
        val node = choiceNode(mapOf(localizedAnswer to "Done"))

        assertEquals("Done", node.resolveNextId(localizedAnswer))
        assertEquals("Q8", node.resolveNextId(localizedAnswer.lowercase()))
    }

    @Test
    fun english_q7_routes_screen_out_answer_and_falls_back_for_positive_answer() {
        val q7 = loadAsset("survey_config10.yaml")
            .graph.nodes
            .single { it.id == "Q7" }
            .toVmNode()

        assertEquals("Done", q7.resolveNextId("Less than 2 years"))
        assertEquals("Q8", q7.resolveNextId("More than 2 years"))
    }

    @Test
    fun swahili_q7_routes_screen_out_answer_and_falls_back_for_positive_answer() {
        val q7 = loadAsset("survey_config_sw_10.yaml")
            .graph.nodes
            .single { it.id == "Q7" }
            .toVmNode()

        assertEquals("Done", q7.resolveNextId("Chini ya miaka 2"))
        assertEquals("Q8", q7.resolveNextId("Zaidi ya miaka 2"))
    }

    private fun choiceNode(routes: Map<String, String>): Node =
        Node(
            id = "Choice",
            type = NodeType.SINGLE_CHOICE,
            options = listOf("Continue", "Stop"),
            nextId = "Q8",
            nextIdByAnswer = routes
        )

    private fun loadAsset(fileName: String) =
        SurveyConfigLoader.fromFileStrictValidated(
            assetFile(fileName).absolutePath
        )

    private fun assetFile(fileName: String): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir")))
        return listOf(
            File(workingDirectory, "app/src/main/assets/$fileName"),
            File(workingDirectory, "src/main/assets/$fileName")
        ).firstOrNull(File::isFile)
            ?: error("Unable to locate app asset '$fileName' from $workingDirectory")
    }
}
