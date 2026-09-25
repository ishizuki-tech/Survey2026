package com.negi.survey.vm

import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfigLoader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SurveyNavigationResolutionTest {

    @Test
    fun info_node_maps_to_info_and_uses_default_next_id() {
        val node = NodeDTO(
            id = "Introduction",
            type = "INFO",
            title = "Introduction",
            question = "Study information",
            nextId = "Consent",
        ).toVmNode()

        assertEquals(NodeType.INFO, node.type)
        assertEquals("Consent", node.resolveNextId(null))
    }

    @Test
    fun english_config_routes_start_through_introduction_and_consent() {
        val config = loadAsset("survey_config10.yaml")
        val start = config.graph.nodes.single { it.id == "Start" }.toVmNode()
        val introduction = config.graph.nodes.single { it.id == "Introduction" }.toVmNode()
        val consent = config.graph.nodes.single { it.id == "Consent" }.toVmNode()

        assertEquals("Introduction", start.resolveNextId(null))
        assertEquals(NodeType.INFO, introduction.type)
        assertEquals(true, introduction.readAloud)
        assertEquals("Consent", introduction.resolveNextId(null))
        assertEquals(true, introduction.question.startsWith("Hallo. Thank you for making time for us today."))

        assertEquals(NodeType.SINGLE_CHOICE, consent.type)
        assertEquals(true, consent.readAloud)
        assertEquals("Q1", consent.resolveNextId("Yes, and proceed"))
        assertEquals("ConsentDeclined", consent.resolveNextId("No, and stop the interview"))

        val declined = config.graph.nodes.single { it.id == "ConsentDeclined" }.toVmNode()
        assertEquals(NodeType.STOP, declined.type)
        assertEquals(null, declined.resolveNextId(null))
    }

    @Test
    fun swahili_config_routes_start_through_introduction_and_consent() {
        val config = loadAsset("survey_config_sw_10.yaml")
        val start = config.graph.nodes.single { it.id == "Start" }.toVmNode()
        val introduction = config.graph.nodes.single { it.id == "Introduction" }.toVmNode()
        val consent = config.graph.nodes.single { it.id == "Consent" }.toVmNode()

        assertEquals("Introduction", start.resolveNextId(null))
        assertEquals(NodeType.INFO, introduction.type)
        assertEquals(true, introduction.readAloud)
        assertEquals("Consent", introduction.resolveNextId(null))
        assertEquals(true, introduction.question.startsWith("Habari yako leo? Asante kwa kutupatia muda wako leo."))

        assertEquals(NodeType.SINGLE_CHOICE, consent.type)
        assertEquals(true, consent.readAloud)
        assertEquals("Q1", consent.resolveNextId("Ndiyo, nakubali kushiriki"))
        assertEquals("ConsentDeclined", consent.resolveNextId("Hapana, sikubali kushiriki"))

        val declined = config.graph.nodes.single { it.id == "ConsentDeclined" }.toVmNode()
        assertEquals(NodeType.STOP, declined.type)
        assertEquals(null, declined.resolveNextId(null))
    }

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
    fun english_q6_routes_numeric_screen_out_answers_and_falls_back_for_eligible_years() {
        val q6 = loadAsset("survey_config10.yaml")
            .graph.nodes
            .single { it.id == "Q6" }
            .toVmNode()

        assertEquals("Done", q6.resolveNextId("0"))
        assertEquals("Done", q6.resolveNextId("1"))
        assertEquals("Done", q6.resolveNextId("2"))
        assertEquals("Q7", q6.resolveNextId("3"))
        assertEquals("Q7", q6.resolveNextId("10"))
        assertEquals("Done", q6.resolveNextId("Prefer not to say"))
    }

    @Test
    fun swahili_q6_routes_numeric_screen_out_answers_and_falls_back_for_eligible_years() {
        val q6 = loadAsset("survey_config_sw_10.yaml")
            .graph.nodes
            .single { it.id == "Q6" }
            .toVmNode()

        assertEquals("Done", q6.resolveNextId("2"))
        assertEquals("Q7", q6.resolveNextId("3"))
        assertEquals("Done", q6.resolveNextId("Sipendi kusema"))
    }

    @Test
    fun generic_number_routes_do_not_depend_on_question_id() {
        val node = Node(
            id = "AnyNumber",
            type = NodeType.NUMBER,
            specialOptions = listOf("Decline"),
            nextId = "Continue",
            nextIdByAnswer = mapOf("Decline" to "Done"),
            numericRoutes = listOf(NumericRoute(lessThanOrEqual = 2, nextId = "Done")),
        )

        assertEquals("Done", node.resolveNextId("2"))
        assertEquals("Continue", node.resolveNextId("3"))
        assertEquals("Done", node.resolveNextId("Decline"))
    }

    @Test
    fun number_answer_larger_than_int_max_value_is_rejected() {
        val node = Node(
            id = "AnyNumber",
            type = NodeType.NUMBER,
            specialOptions = listOf("Decline"),
        )

        assertEquals(false, node.isValidNumberAnswer("2147483648"))
        assertEquals(true, node.isValidNumberAnswer(Int.MAX_VALUE.toString()))
        assertEquals(true, node.isValidNumberAnswer("Decline"))
    }

    @Test
    fun other_choice_requires_detail_and_preserves_choice_with_detail() {
        val node = Node(
            id = "Choice",
            type = NodeType.SINGLE_CHOICE,
            options = listOf("Known", "Other / Nyingine"),
            otherTextOption = "Other / Nyingine",
        )

        assertEquals(null, node.composeSingleChoiceAnswer("Other / Nyingine", "   "))
        assertEquals(
            "Other / Nyingine: Ruiru – Gitothua",
            node.composeSingleChoiceAnswer("Other / Nyingine", "Ruiru – Gitothua"),
        )
        assertEquals("Known", node.composeSingleChoiceAnswer("Known", null))
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
