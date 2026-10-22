package com.negi.survey.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun swahili_whisper_config_selects_the_pinned_cv14_model_without_changing_english() {
        val swahili = SurveyConfigLoader.fromFileStrictValidated(assetFile("survey_config_sw_10.yaml").absolutePath)
        val english = SurveyConfigLoader.fromFileStrictValidated(assetFile("survey_config10.yaml").absolutePath)

        assertEquals("models/ggml-small-sw-cv14-q5_0.bin", swahili.whisper.assetModelPath)
        assertEquals("sw", swahili.whisper.language)
        assertEquals(false, swahili.whisper.translate)
        assertEquals("models/ggml-small-q5_1.bin", english.whisper.assetModelPath)
        assertEquals("en", english.whisper.language)
        assertEquals(false, english.whisper.translate)
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

        // Q14 was split into a Q14a yes/no gate and a Q14b AI follow-up; the
        // two-step prompt pair now lives under Q14b.
        val nodeIds = listOf("Q07", "Q08", "Q09", "Q10", "Q11", "Q12", "Q13", "Q14b", "Q15", "Q16")
        configNames.forEach { name ->
            val config = SurveyConfigLoader.fromFileStrictValidated(File(assetDirectory, name).absolutePath)
            nodeIds.forEach { nodeId ->
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

            /**
             * Node ids 1-9 are zero-padded ("Q01".."Q09"); 10-16 are already two
             * digits. Q14 was split into a Q14a yes/no gate and a Q14b AI
             * follow-up, so it expands to two ids instead of one.
             */
            (1..16).map { "Q" + it.toString().padStart(2, '0') }
                .flatMap { if (it == "Q14") listOf("Q14a", "Q14b") else listOf(it) }
                .forEach { id -> assertTrue("$name is missing $id", id in nodes) }
            /** Q17 ("End of Interview") now sits between Q16 and Review; Q18 does not exist. */
            assertTrue("$name must not retain Q18", "Q18" !in nodes)

            val q1 = checkNotNull(nodes["Q01"])
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

            val consent = checkNotNull(nodes["Q00"])
            assertEquals("SINGLE_CHOICE", consent.type)
            assertEquals(null, consent.otherTextOption)

            val q2 = checkNotNull(nodes["Q02"])
            assertEquals("SINGLE_CHOICE", q2.type)
            assertEquals(false, q2.readAloud)
            assertEquals(null, q2.otherTextOption)

            val q3 = checkNotNull(nodes["Q03"])
            assertEquals("NUMBER", q3.type)
            assertEquals(listOf(preferNotToSay), q3.specialOptions)

            assertEquals("TEXT", checkNotNull(nodes["Q04"]).type)
            assertEquals("TEXT", checkNotNull(nodes["Q05"]).type)

            val q6 = checkNotNull(nodes["Q06"])
            assertEquals("NUMBER", q6.type)
            assertEquals(listOf(preferNotToSay), q6.specialOptions)
            assertEquals(preferNotToSay, q6.nextIdByAnswer.keys.single())
            assertEquals("Done", q6.nextIdByAnswer[preferNotToSay])
            assertEquals(1, q6.numericRoutes.size)
            assertEquals(2, q6.numericRoutes.single().lessThanOrEqual)
            assertEquals("Done", q6.numericRoutes.single().nextId)
            assertEquals("Q07", q6.nextId)

            /** Q16 is followed by the Q17 "End of Interview" node, then Review. */
            assertEquals("Q17", checkNotNull(nodes["Q16"]).nextId)
            assertEquals("Review", checkNotNull(nodes["Q17"]).nextId)
            assertEquals("Done", checkNotNull(nodes["Review"]).nextId)

            val expectedQuestions = if (name.contains("_sw_")) {
                mapOf(
                    "Q04" to "Unalima mimea gani?",
                    "Q05" to "Unafuga mifugo au wanyama gani?",
                    "Q07" to "Je, wadudu wanaoitwa Fall Armyworm wamewahi kushambulia mahindi yako? Kama ndiyo, ulipata hasara gani?",
                    "Q08" to "Tafadhali sikiza mfano huu: ikiwa ungeweza kuvuna mahindi siku 20 mapema, lakini mavuno yatapungua kidogo, ungekubali kupoteza magunia mangapi ya mavuno?",
                    "Q09" to "Kama wadudu au magonjwa wanaharibu mahindi yako, ukipoteza magunia mangapi ndipo utaamua kubadilisha mbegu?",
                    "Q10" to "Kama kuna mbegu mpya ya mahindi, ingekuwa na mambo gani ili uache mbegu unayotumia sasa na utumie hiyo mpya?",
                    "Q11" to "Kama wewe na wakulima wengine eneo lenu mmevuna mahindi kidogo sana, ukivuna chini ya magunia mangapi ndio utaamua kubadilisha mbegu?",
                    "Q12" to "Ukame huharibu mahindi yako zaidi wakati gani? Kwa mfano, yakiwa madogo, yakitoa maua, yakianza kuzaa, au wakati gani mwingine?",
                    "Q13" to "Baada ya kuvuna, unagawanya mahindi yako vipi? Kwa mfano unaamuaje kiasi cha kutumia kwa chakula, mifugo, kuuza, au matumizi mengine?",
                    "Q14a" to "Je, unatumia mahindi meupe kulisha wanyama?",
                    "Q14b" to "Ni wanyama gani unawalisha mahindi meupe, na mara ngapi?",
                    "Q15" to "Kwa kawaida unapata mbegu za mahindi meupe wapi, na kwa nini unapenda kupata mbegu hapo?",
                    "Q16" to "Kwa kawaida unauza mahindi yako wapi baada ya kuvuna, na kwa nini unachagua kuuza hapo?",
                )
            } else {
                mapOf(
                    "Q04" to "Which crops do you grow?",
                    "Q05" to "Which livestock do you keep?",
                    "Q07" to "Has fall armyworm ever attacked your maize? If yes, what loss did you get?",
                    "Q08" to "Please listen to this example: if you could harvest your maize 20 days early, but your harvest would be smaller, how many bags of harvest would you accept to lose?",
                    "Q09" to "If pests or diseases damage your maize, how many bags would you need to lose before deciding to change your seed?",
                    "Q10" to "If there is a new maize seed, what characteristics would make you stop using your current seed and use the new one?",
                    "Q11" to "If you and other farmers in your area harvested very little maize, below how many bags would you decide to change your seed?",
                    "Q12" to "When does drought damage your maize the most: when the plants are young, when they are flowering, when they have started producing cobs, or at what other time?",
                    "Q13" to "After harvest, how do you divide your maize? How do you decide the amount for each use, for example food, animals, selling, or other uses?",
                    "Q14a" to "Do you use white maize to feed animals?",
                    "Q14b" to "Which animals do you feed white maize, and how often?",
                    "Q15" to "Where do you usually get your white maize seed, and why do you like getting it from there?",
                    "Q16" to "Where do you usually sell your maize after harvesting, and why do you choose to sell there?",
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
            "Q07" to "Whether FAW affected the crop",
            "Q08" to "Maximum number of bags of harvest the respondent would accept to lose",
            "Q09" to "The number of bags of maize lost to pests or disease",
            "Q10" to "Characteristics a new maize seed must have",
            "Q11" to "The harvest threshold, in bags, below which",
            "Q12" to "The maize growth stage (e.g. young plants, flowering, cob formation, or another stage)",
            "Q13" to "The factors/criteria used to decide",
            "Q14b" to "Which animals are fed white maize and how often.",
            "Q15" to "Where seed is usually obtained and the reason for preferring that source.",
            "Q16" to "Where maize is usually sold after harvest and the reason for choosing that market/buyer.",
        )
        expectedEvalTargetByNewId.forEach { (id, expectedTarget) ->
            assertTrue("$id must retain its prior prompt body", config.resolveEvalPrompt(id)!!.contains(expectedTarget))
            assertTrue(config.resolveFollowupPrompt(id)!!.contains("EVAL_JSON"))
        }

        assertEquals(
            listOf(
                RequiredComponent("animals", "Which animals receive white maize"),
                RequiredComponent("feeding_frequency", "How often white maize is fed"),
            ),
            config.graph.nodes.single { it.id == "Q14b" }.requiredComponentCatalog,
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
    fun kiambu_swahili_component_catalogs_use_canonical_ids_and_the_catalog_eval_contract() {
        val config = SurveyConfigLoader.fromFileStrictValidated(assetFile("survey_config_sw_10.yaml").absolutePath)

        assertEquals(
            listOf(
                RequiredComponent("animals", "Mifugo inayolishwa mahindi meupe"),
                RequiredComponent("feeding_frequency", "Mara ngapi mahindi meupe hulishwa"),
            ),
            config.graph.nodes.single { it.id == "Q14b" }.requiredComponentCatalog,
        )
        assertEquals(
            listOf(
                RequiredComponent("seed_source", "Chanzo cha kawaida cha mbegu za mahindi meupe"),
                RequiredComponent("source_reason", "Sababu ya kupendelea chanzo hicho"),
            ),
            config.graph.nodes.single { it.id == "Q15" }.requiredComponentCatalog,
        )
        assertEquals(
            listOf(
                RequiredComponent("sale_destination", "Soko au mnunuzi wa kawaida baada ya mavuno"),
                RequiredComponent("destination_reason", "Sababu ya kuchagua soko au mnunuzi huyo"),
            ),
            config.graph.nodes.single { it.id == "Q16" }.requiredComponentCatalog,
        )

        val evalSystemPrompt = config.composeSystemPromptEval()
        assertTrue(evalSystemPrompt.contains("ID za vipengele vilivyosanidiwa pekee"))
        assertTrue(evalSystemPrompt.contains("maelezo ya kipengele au maswali"))
        assertTrue(evalSystemPrompt.contains("Wakati Required components block ya zamani ipo"))
        assertTrue(evalSystemPrompt.contains("Wakati hakuna catalog wala Required components block"))
        assertFalse(evalSystemPrompt.contains("Write missing_points in Swahili."))
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
