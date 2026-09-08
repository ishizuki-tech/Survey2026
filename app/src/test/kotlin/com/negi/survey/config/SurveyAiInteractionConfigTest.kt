package com.negi.survey.config

import org.junit.Assert.*
import org.junit.Test

class SurveyAiInteractionConfigTest {
    private val graph = """
        graph:
          startId: Start
          nodes:
            - id: Start
              type: START
              nextId: Done
            - id: Done
              type: DONE
    """.trimIndent()

    @Test fun omitted_zero_one_three_and_integer_boundary() {
        assertEquals(2, SurveyConfigLoader.fromStringStrictValidated(graph, ConfigFormat.YAML).aiInteraction.maxFollowups)
        for (cap in listOf(0, 1, 2, 3, Int.MAX_VALUE)) {
            val yaml = "ai_interaction:\n  max_followups: $cap\n$graph"
            assertEquals(cap, SurveyConfigLoader.fromStringStrictValidated(yaml, ConfigFormat.YAML).aiInteraction.maxFollowups)
            assertEquals(cap, SurveyConfigLoader.fromString(yaml, ConfigFormat.YAML).aiInteraction.maxFollowups)
        }
    }

    @Test fun invalid_caps_rejected_by_both_loaders_with_field_name() {
        for (value in listOf("-1", "1.5", "\"2\"", "'2'", "2147483648", "true", "null", "[]", "!!str 2")) {
            val yaml = "ai_interaction:\n  max_followups: $value\n$graph"
            for (strict in listOf(false, true)) {
                val failure = runCatching {
                    if (strict) SurveyConfigLoader.fromStringStrictValidated(yaml, ConfigFormat.YAML)
                    else SurveyConfigLoader.fromString(yaml, ConfigFormat.YAML)
                }.exceptionOrNull()
                assertNotNull("accepted $value strict=$strict", failure)
                assertTrue(failure!!.message.orEmpty().contains("max_followups"))
            }
        }
    }

    @Test fun json_strings_and_out_of_range_values_rejected() {
        for (value in listOf("\"2\"", "-1", "1.1", "2147483648", "null")) {
            val text = """{"ai_interaction":{"max_followups":$value},"graph":{"startId":"Done","nodes":[{"id":"Done","type":"DONE"}]}}"""
            assertTrue(runCatching { SurveyConfigLoader.fromString(text, ConfigFormat.JSON) }.isFailure)
        }
    }
}
