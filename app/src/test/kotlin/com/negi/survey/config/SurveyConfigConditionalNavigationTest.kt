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
            (7..16).forEach { number ->
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
    fun kiambu_questionnaire_graphs_have_q1_through_q16_with_expected_input_contracts() {
        listOf(
            "survey_config10.yaml" to "Prefer not to say",
            "survey_config_sw_10.yaml" to "Sipendi kusema",
        ).forEach { (name, preferNotToSay) ->
            val config = SurveyConfigLoader.fromFileStrictValidated(assetFile(name).absolutePath)
            val nodes = config.graph.nodes.associateBy { it.id }

            (1..16).forEach { number -> assertTrue("$name is missing Q$number", "Q$number" in nodes) }
            assertTrue("$name must not retain Q17", "Q17" !in nodes)

            val q1 = checkNotNull(nodes["Q1"])
            assertEquals("SINGLE_CHOICE", q1.type)
            assertEquals(false, q1.readAloud)
            assertEquals(
                listOf(
                    "Githunguri – Githunguri",
                    "Gatundu South – Ndarugu",
                    "Gatundu South – Ngenda",
                    "Gatundu North – Githobokoni",
                    "Gatundu North – Gituamba",
                    "Other / Nyingine",
                ),
                q1.options,
            )
            assertEquals("Other / Nyingine", q1.otherTextOption)

            val q2 = checkNotNull(nodes["Q2"])
            assertEquals("SINGLE_CHOICE", q2.type)
            assertEquals(false, q2.readAloud)

            val q3 = checkNotNull(nodes["Q3"])
            assertEquals("NUMBER", q3.type)
            assertEquals(listOf(preferNotToSay), q3.specialOptions)

            assertEquals("TEXT", checkNotNull(nodes["Q4"]).type)
            assertEquals("TEXT", checkNotNull(nodes["Q5"]).type)

            val q6 = checkNotNull(nodes["Q6"])
            assertEquals("NUMBER", q6.type)
            assertEquals(listOf(preferNotToSay), q6.specialOptions)
            assertEquals(preferNotToSay, q6.nextIdByAnswer.keys.single())
            assertEquals("Done", q6.nextIdByAnswer[preferNotToSay])
            assertEquals(1, q6.numericRoutes.size)
            assertEquals(2, q6.numericRoutes.single().lessThanOrEqual)
            assertEquals("Done", q6.numericRoutes.single().nextId)
            assertEquals("Q7", q6.nextId)

            assertEquals("Review", checkNotNull(nodes["Q16"]).nextId)
            assertEquals("Done", checkNotNull(nodes["Review"]).nextId)

            val expectedQuestions = if (name.contains("_sw_")) {
                mapOf(
                    "Q4" to "Unalima mazao gani?",
                    "Q5" to "Unafuga wanyama gani?",
                    "Q7" to "Mahindi yako yamewahi kushambuliwa na funza jeshi (Fall Armyworm)? Ikiwa ndiyo, yalipata hasara gani?",
                    "Q8" to "Kama ungeweza kuvuna mahindi yako siku 20 mapema, lakini mavuno yangepungua, ungekubali kupoteza kiasi gani cha mavuno ili uvune mapema?",
                    "Q9" to "Ni kiwango gani cha uharibifu wa mahindi unaosababishwa na wadudu au magonjwa ambacho kingekufanya ubadilishe aina ya mahindi unayopanda?",
                    "Q10" to "Kama kungekuwa na aina mpya ya mahindi, ingehitaji kuwa na sifa gani ili uamue kuipanda badala ya aina unayopanda sasa?",
                    "Q11" to "Msimu ukiwa mbaya, ni mavuno ya chini kiasi gani ambayo bado yangekufanya uendelee kupanda aina hii ya mahindi?",
                    "Q12" to "Ukame huwa unasababisha hasara kubwa zaidi wakati gani katika ukuaji wa mahindi?",
                    "Q13" to "Unaamua aje kiasi cha mahindi cha kuweka kwa matumizi ya nyumbani, kulisha mifugo, kuuza, au matumizi mengine?",
                    "Q14" to "Kama unatumia mahindi meupe kulisha mifugo, unawalisha mifugo gani na mara ngapi?",
                    "Q15" to "Kwa kawaida unapata wapi mbegu za mahindi meupe unazopanda, na kwa nini unapendelea kununua au kupata mbegu huko?",
                    "Q16" to "Kwa kawaida baada ya kuvuna, unauza mahindi yako wapi, na kwa nini unapendelea kuuza huko au kwa mnunuzi huyo?",
                )
            } else {
                mapOf(
                    "Q4" to "Which crops do you grow?",
                    "Q5" to "Which animals do you keep?",
                    "Q7" to "Has fall armyworm ever affected your maize? If yes, what damage or losses did it cause?",
                    "Q8" to "If you could harvest your maize 20 days earlier but this reduced your yield, how much yield would you be willing to lose to harvest earlier?",
                    "Q9" to "What level of pest or disease damage would make you switch to a different maize variety?",
                    "Q10" to "If there were a new maize variety, what characteristics would it need to have for you to decide to plant it instead of your current variety?",
                    "Q11" to "In a bad season, what is the lowest maize yield that would still make you continue planting this variety?",
                    "Q12" to "At what stage of maize growth does drought cause the greatest losses?",
                    "Q13" to "How do you decide how much maize to use for household food, livestock feed, sale, or other purposes?",
                    "Q14" to "If you use white maize to feed livestock, which animals do you feed it to and how often?",
                    "Q15" to "Where do you usually obtain the white maize seed that you plant, and why do you prefer getting it there?",
                    "Q16" to "Where do you usually sell your maize after harvest, and why do you prefer selling there or to that buyer?",
                )
            }
            expectedQuestions.forEach { (id, question) ->
                assertEquals("$name $id", question, checkNotNull(nodes[id]).question)
            }
        }
    }

    @Test
    fun kiambu_english_prompts_and_component_catalogs_follow_the_shift_without_rewriting_content() {
        val config = SurveyConfigLoader.fromFileStrictValidated(assetFile("survey_config10.yaml").absolutePath)

        val expectedEvalTargetByNewId = mapOf(
            "Q7" to "Whether FAW affected the crop",
            "Q8" to "Maximum yield loss acceptable",
            "Q9" to "A clear pest/disease damage threshold",
            "Q10" to "Priority traits a new maize variety",
            "Q11" to "The lowest yield in a bad season",
            "Q12" to "The maize growth stage where drought",
            "Q13" to "The factors/criteria used to decide",
            "Q14" to "Which animals are fed white maize and how often.",
            "Q15" to "Where seed is usually obtained and the reason for preferring that source.",
            "Q16" to "Where maize is usually sold after harvest and the reason for choosing that market/buyer.",
        )
        expectedEvalTargetByNewId.forEach { (id, expectedTarget) ->
            assertTrue("$id must retain its prior prompt body", config.resolveEvalPrompt(id)!!.contains(expectedTarget))
            assertTrue(config.resolveFollowupPrompt(id)!!.contains("EVAL_JSON"))
        }

        assertEquals(
            listOf(
                RequiredComponent("animals", "If white maize is used for livestock: which animals receive it"),
                RequiredComponent("feeding_frequency", "If white maize is used for livestock: how often it is fed"),
            ),
            config.graph.nodes.single { it.id == "Q14" }.requiredComponentCatalog,
        )
        assertEquals(
            listOf(
                RequiredComponent("seed_source", "Usual source of white maize seed"),
                RequiredComponent("source_reason", "Reason for preferring that source"),
            ),
            config.graph.nodes.single { it.id == "Q15" }.requiredComponentCatalog,
        )
        assertEquals(
            listOf(
                RequiredComponent("sale_destination", "Usual market or buyer after harvest"),
                RequiredComponent("destination_reason", "Reason for choosing that market or buyer"),
            ),
            config.graph.nodes.single { it.id == "Q16" }.requiredComponentCatalog,
        )
    }

    @Test
    fun legacy_configs_without_kiambu_input_fields_remain_valid() {
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
                      options: [Continue]
                      nextId: Done
                    - id: Done
                      type: DONE
            """.trimIndent(),
            format = ConfigFormat.YAML,
        )

        val choice = config.graph.nodes.single { it.id == "Choice" }
        assertEquals(true, choice.readAloud)
        assertEquals(null, choice.otherTextOption)
        assertTrue(choice.specialOptions.isEmpty())
        assertTrue(choice.numericRoutes.isEmpty())
        assertEquals(emptyList<String>(), config.validate())
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

    private fun assetFile(fileName: String): File {
        val workingDirectory = File(checkNotNull(System.getProperty("user.dir")))
        return listOf(
            File(workingDirectory, "app/src/main/assets/$fileName"),
            File(workingDirectory, "src/main/assets/$fileName"),
        ).firstOrNull(File::isFile)
            ?: error("Unable to locate app asset '$fileName' from $workingDirectory")
    }

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
