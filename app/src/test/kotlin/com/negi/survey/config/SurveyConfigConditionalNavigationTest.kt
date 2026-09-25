package com.negi.survey.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyConfigConditionalNavigationTest {

    @Test
    fun legacy_next_id_only_config_remains_valid() {
        val config = surveyConfig(
            node("Start", "START", nextId = "Choice"),
            node(
                id = "Choice",
                type = "SINGLE_CHOICE",
                options = listOf("Continue", "Stop"),
                nextId = "Done"
            ),
            node("Done", "DONE")
        )

        assertEquals(emptyList<String>(), config.validate())
    }

    @Test
    fun all_shipped_survey_configs_remain_valid() {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir")))
        val assetDirectory = listOf(
            File(workingDirectory, "app/src/main/assets"),
            File(workingDirectory, "src/main/assets")
        ).firstOrNull(File::isDirectory)
            ?: error("Unable to locate app assets from $workingDirectory")
        val configs = assetDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("survey_config") && it.extension == "yaml" }

        assertTrue("Expected at least one shipped survey config", configs.isNotEmpty())
        configs.forEach { config ->
            SurveyConfigLoader.fromFileStrictValidated(config.absolutePath)
        }
    }

    @Test
    fun shipped_ai_nodes_resolve_two_step_prompt_pairs() {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir")))
        val assetDirectory = listOf(
            File(workingDirectory, "app/src/main/assets"),
            File(workingDirectory, "src/main/assets")
        ).firstOrNull(File::isDirectory)
            ?: error("Unable to locate app assets from $workingDirectory")
        val configNames = listOf("survey_config10.yaml", "survey_config_sw_10.yaml")

        configNames.forEach { name ->
            val config = SurveyConfigLoader.fromFileStrictValidated(File(assetDirectory, name).absolutePath)
            (8..17).forEach { number ->
                val nodeId = "Q$number"
                val hasTwoStepPair =
                    !config.resolveEvalPrompt(nodeId).isNullOrBlank() &&
                            !config.resolveFollowupPrompt(nodeId).isNullOrBlank()
                assertTrue("$name $nodeId must use TWO_STEP", hasTwoStepPair)
                assertEquals(null, config.resolveOneStepPrompt(nodeId))
                assertTrue(config.resolveEvalPrompt(nodeId)!!.contains("{{HISTORY}}"))
                assertTrue(config.resolveFollowupPrompt(nodeId)!!.contains("{{EVAL_JSON}}"))
                assertTrue(config.composeSystemPromptEval().contains("missing_points"))
                assertTrue(config.composeSystemPromptFollowup().contains(if (name.contains("_sw_")) "Swahili" else "English"))
            }
        }
    }

    @Test
    fun required_components_are_optional_and_blank_entries_fail_validation() {
        val configured = SurveyConfigLoader.fromStringStrictValidated(
            text = componentConfig(
                """
                    required_components:
                      - Which livestock animals receive white maize
                      - How often white maize is fed
                """.trimIndent()
            ),
            format = ConfigFormat.YAML,
        )
        assertEquals(
            listOf(
                "Which livestock animals receive white maize",
                "How often white maize is fed",
            ),
            configured.graph.nodes.single { it.id == "Q15" }.requiredComponents,
        )

        val absent = SurveyConfigLoader.fromStringStrictValidated(
            text = componentConfig(),
            format = ConfigFormat.YAML,
        )
        assertEquals(emptyList<String>(), absent.graph.nodes.single { it.id == "Q15" }.requiredComponents)

        assertTrue(
            runCatching {
                SurveyConfigLoader.fromStringStrictValidated(
                    text = componentConfig(
                        """
                            required_components:
                              - ""
                        """.trimIndent()
                    ),
                    format = ConfigFormat.YAML,
                )
            }.isFailure
        )
    }

    @Test
    fun required_component_catalog_is_optional_ordered_and_strictly_validated() {
        val configured = SurveyConfigLoader.fromStringStrictValidated(
            text = componentConfig(
                """
                    required_component_catalog:
                      - id: animals
                        text: Which livestock animals receive white maize
                      - id: feeding_frequency
                        text: How often white maize is fed
                """.trimIndent(),
            ),
            format = ConfigFormat.YAML,
        )
        assertEquals(
            listOf(
                RequiredComponent("animals", "Which livestock animals receive white maize"),
                RequiredComponent("feeding_frequency", "How often white maize is fed"),
            ),
            configured.graph.nodes.single { it.id == "Q15" }.requiredComponentCatalog,
        )

        assertEquals(
            emptyList<RequiredComponent>(),
            SurveyConfigLoader.fromStringStrictValidated(componentConfig(), ConfigFormat.YAML)
                .graph.nodes.single { it.id == "Q15" }.requiredComponentCatalog,
        )
        for (catalog in listOf(
            """
                required_component_catalog:
                  - id: ""
                    text: Component
            """.trimIndent(),
            """
                required_component_catalog:
                  - id: animals
                    text: ""
            """.trimIndent(),
            """
                required_component_catalog:
                  - id: animals
                    text: A
                  - id: animals
                    text: B
            """.trimIndent(),
        )) {
            assertTrue(
                runCatching {
                    SurveyConfigLoader.fromStringStrictValidated(componentConfig(catalog), ConfigFormat.YAML)
                }.isFailure,
            )
        }
        val nonAiCatalog = surveyConfig(
            node("Start", "START", nextId = "Text"),
            NodeDTO(
                id = "Text",
                type = "TEXT",
                requiredComponentCatalog = listOf(RequiredComponent("animals", "Animals")),
                nextId = "Done",
            ),
            node("Done", "DONE"),
        )
        assertTrue(nonAiCatalog.validate().any { it.contains("required_component_catalog") })
    }

    @Test
    fun valid_next_id_by_answer_config_parses_and_validates() {
        val config = SurveyConfigLoader.fromStringStrictValidated(
            text = """
                graph:
                  startId: Start
                  nodes:
                    - id: Start
                      type: START
                      nextId: Choice
                    - id: Choice
                      type: SINGLE_CHOICE
                      options:
                        - Continue
                        - Stop
                      nextId: ContinueNode
                      nextIdByAnswer:
                        "Stop": Done
                    - id: ContinueNode
                      type: TEXT
                      nextId: Done
                    - id: Done
                      type: DONE
            """.trimIndent(),
            format = ConfigFormat.YAML,
            fileNameHint = "conditional.yaml"
        )

        val choice = config.graph.nodes.single { it.id == "Choice" }
        assertEquals(mapOf("Stop" to "Done"), choice.nextIdByAnswer)
        assertEquals(emptyList<String>(), config.validate())
    }

    @Test
    fun answer_route_option_must_exist() {
        val issues = conditionalConfig(
            routes = mapOf("Unknown option" to "Done")
        ).validate()

        assertTrue(issues.any { "does not exactly match an option" in it })
    }

    @Test
    fun answer_route_key_must_not_be_blank() {
        val issues = conditionalConfig(
            routes = mapOf("   " to "Done")
        ).validate()

        assertTrue(issues.any { "blank answer key" in it })
    }

    @Test
    fun answer_route_destination_must_not_be_blank() {
        val issues = conditionalConfig(
            routes = mapOf("Stop" to "   ")
        ).validate()

        assertTrue(issues.any { "blank destination" in it })
    }

    @Test
    fun answer_route_destination_must_exist() {
        val issues = conditionalConfig(
            routes = mapOf("Stop" to "Missing")
        ).validate()

        assertTrue(issues.any { "references unknown destination 'Missing'" in it })
    }

    @Test
    fun answer_routes_are_only_valid_on_single_choice() {
        val config = surveyConfig(
            node("Start", "START", nextId = "Text"),
            node(
                id = "Text",
                type = "TEXT",
                options = listOf("Stop"),
                nextId = "Done",
                routes = mapOf("Stop" to "Done")
            ),
            node("Done", "DONE")
        )

        assertTrue(config.validate().any { "is not SINGLE_CHOICE" in it })
    }

    @Test
    fun conditional_edge_participates_in_reachability() {
        val config = surveyConfig(
            node("Start", "START", nextId = "Choice"),
            node(
                id = "Choice",
                type = "SINGLE_CHOICE",
                options = listOf("Continue", "Branch"),
                nextId = "Done",
                routes = mapOf("Branch" to "ConditionalNode")
            ),
            node("ConditionalNode", "TEXT", nextId = "Done"),
            node("Done", "DONE")
        )

        assertEquals(emptyList<String>(), config.validate())
    }

    @Test
    fun conditional_edge_participates_in_cycle_detection() {
        val config = surveyConfig(
            node("Start", "START", nextId = "Choice"),
            node(
                id = "Choice",
                type = "SINGLE_CHOICE",
                options = listOf("Finish", "Loop"),
                nextId = "Done",
                routes = mapOf("Loop" to "LoopNode")
            ),
            node("LoopNode", "TEXT", nextId = "Choice"),
            node("Done", "DONE")
        )

        assertTrue(config.validate().any { "cycle detected in survey graph" in it })
    }

    private fun conditionalConfig(routes: Map<String, String>): SurveyConfig =
        surveyConfig(
            node("Start", "START", nextId = "Choice"),
            node(
                id = "Choice",
                type = "SINGLE_CHOICE",
                options = listOf("Continue", "Stop"),
                nextId = "Done",
                routes = routes
            ),
            node("Done", "DONE")
        )

    private fun componentConfig(requiredComponents: String = ""): String =
        buildString {
            appendLine("graph:")
            appendLine("  startId: Start")
            appendLine("  nodes:")
            appendLine("    - id: Start")
            appendLine("      type: START")
            appendLine("      nextId: Q15")
            appendLine("    - id: Q15")
            appendLine("      type: AI")
            appendLine("      question: Question")
            requiredComponents.lineSequence().forEach { appendLine("      $it") }
            appendLine("      nextId: Done")
            appendLine("    - id: Done")
            appendLine("      type: DONE")
            appendLine("slm:")
            appendLine("  key_contract_eval: eval")
            appendLine("  key_contract_followup: follow")
            appendLine("prompts:")
            appendLine("  - nodeId: Q15")
            appendLine("    eval_prompt: eval")
            append("    followup_prompt: follow")
        }

    private fun surveyConfig(vararg nodes: NodeDTO): SurveyConfig =
        SurveyConfig(
            graph = SurveyConfig.Graph(
                startId = "Start",
                nodes = nodes.toList()
            )
        )

    private fun node(
        id: String,
        type: String,
        options: List<String> = emptyList(),
        nextId: String? = null,
        routes: Map<String, String> = emptyMap()
    ): NodeDTO =
        NodeDTO(
            id = id,
            type = type,
            question = if (type == "AI") "Question" else "",
            options = options,
            nextId = nextId,
            nextIdByAnswer = routes
        )
}
