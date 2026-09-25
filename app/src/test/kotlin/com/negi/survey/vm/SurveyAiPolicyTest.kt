package com.negi.survey.vm

import org.junit.Assert.*
import org.junit.Test

class SurveyAiPolicyTest {
    private fun eval(
        raw: String,
        remaining: Int = 2,
        timeout: Boolean = false,
        error: String? = null,
        requiredComponents: List<String> = emptyList(),
        requiredComponentIds: List<String> = emptyList(),
    ) = SurveyAiPolicy.evaluate(raw, timeout, error, remaining, requiredComponents, requiredComponentIds)

    @Test fun required_component_catalog_ids_are_exact_and_take_precedence_over_legacy_text() {
        fun raw(missing: List<String>) =
            """{"score":60,"missing_points":${missing.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},"followup_needed":true}"""
        val ids = listOf("animals", "feeding_frequency")

        assertEquals(SurveyAiDecision.GENERATE, eval(raw(listOf("animals")), requiredComponentIds = ids))
        assertEquals(SurveyAiDecision.GENERATE, eval(raw(ids), requiredComponentIds = ids))
        assertEquals(SurveyAiDecision.FAILURE, eval(raw(listOf("Animals")), requiredComponentIds = ids))
        assertEquals(SurveyAiDecision.FAILURE, eval(raw(listOf("animals", "animals")), requiredComponentIds = ids))
        assertEquals(
            SurveyAiDecision.FAILURE,
            eval(
                raw(listOf("If white maize is used for livestock: which animals receive it")),
                requiredComponents = listOf("If white maize is used for livestock: which animals receive it"),
                requiredComponentIds = ids,
            ),
        )
    }

    @Test fun required_components_accept_exact_members_and_reject_unknown_or_duplicate_missing_points() {
        val componentA = "Component A"
        val componentB = "Component B"
        val unexpected = "Unexpected component"
        fun raw(missing: List<String>) =
            """{"score":60,"missing_points":${missing.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},"followup_needed":true}"""

        assertEquals(
            SurveyAiDecision.GENERATE,
            eval(raw(listOf("Any valid missing point"))),
        )
        assertEquals(SurveyAiDecision.GENERATE, eval(raw(listOf(componentA)), requiredComponents = listOf(componentA, componentB)))
        assertEquals(SurveyAiDecision.GENERATE, eval(raw(listOf(componentB)), requiredComponents = listOf(componentA, componentB)))
        assertEquals(SurveyAiDecision.GENERATE, eval(raw(listOf(componentA, componentB)), requiredComponents = listOf(componentA, componentB)))
        assertEquals(
            SurveyAiDecision.ACHIEVED,
            eval(
                """{"score":95,"missing_points":[],"followup_needed":false}""",
                requiredComponents = listOf(componentA, componentB),
            ),
        )
        assertEquals(SurveyAiDecision.FAILURE, eval(raw(listOf(unexpected)), requiredComponents = listOf(componentA, componentB)))
        assertEquals(SurveyAiDecision.FAILURE, eval(raw(listOf(componentA, unexpected)), requiredComponents = listOf(componentA, componentB)))
        assertEquals(SurveyAiDecision.FAILURE, eval(raw(listOf(componentA, componentA)), requiredComponents = listOf(componentA, componentB)))
        assertEquals(SurveyAiDecision.FAILURE, eval(raw(listOf("component A")), requiredComponents = listOf(componentA)))
        assertEquals(SurveyAiDecision.FAILURE, eval(raw(listOf("Component A ")), requiredComponents = listOf(componentA)))
    }

    @Test fun q15_combined_missing_point_is_not_a_required_component() {
        val animals = "If white maize is used for livestock: which animals receive it"
        val frequency = "If white maize is used for livestock: how often it is fed"
        val combined = "Which livestock animals receive white maize and how often do you feed them"

        assertEquals(
            SurveyAiDecision.FAILURE,
            eval(
                """{"score":65,"missing_points":["$combined"],"followup_needed":true}""",
                requiredComponents = listOf(animals, frequency),
            ),
        )
    }

    @Test fun valid_evaluations_respect_score_and_capacity() {
        for (cap in listOf(0, 1, 2, 3)) {
            assertEquals(SurveyAiDecision.ACHIEVED, eval("""{"score":90,"missing_points":[],"followup_needed":false}""", cap))
            assertEquals(if (cap == 0) SurveyAiDecision.LIMIT_REACHED else SurveyAiDecision.GENERATE,
                eval("""{"score":89,"missing_points":["unit"],"followup_needed":true}""", cap))
        }
    }

    @Test fun missing_followup_needed_is_inferred_only_for_valid_low_score_with_missing_points() {
        val missingFollowupNeeded =
            """{"score":45,"missing_points":["yield loss or crop damage description"]}"""
        val explicitTrue = """{"score":45,"missing_points":["yield loss"],"followup_needed":true}"""

        assertEquals(SurveyAiDecision.GENERATE, eval(missingFollowupNeeded))
        assertEquals(SurveyAiDecision.LIMIT_REACHED, eval(missingFollowupNeeded, remaining = 0))
        assertEquals(SurveyAiDecision.GENERATE, eval(explicitTrue))

        for (raw in listOf(
            """{"score":45,"missing_points":["yield loss"],"followup_needed":false}""",
            """{"score":90,"missing_points":[]}""",
            """{"missing_points":["yield loss"]}""",
            """{"score":"45","missing_points":["yield loss"]}""",
            """{"score":45}""",
            """{"score":45,"missing_points":[]}""",
            """{"score":45,"missing_points":[""]}""",
            """{"score":45,"missing_points":[2]}""",
            """{"score":45,"missing_points":["yield loss"],"followup_needed":null}""",
        )) {
            assertEquals(raw, SurveyAiDecision.FAILURE, eval(raw))
        }
    }

    @Test fun invalid_evaluations_never_complete_even_at_limit() {
        val invalid = listOf("", "{}", "not json", """{"score":95.5}""", """{"score":"95"}""",
            """{"score":0}""", """{"score":101}""", """{"score":-1}""", """{"score":null}""",
            """{"score":20}""", """{"score":20,"missing_points":[]}""",
            """{"score":20,"missing_points":[""]}""", """{"score":20,"missing_points":[2]}""",
            """{"score":20,"missing_points":["unit"],"followup_needed":"true"}""",
            """{"score":20,"missing_points":["unit"],"followup_needed":false}""",
            """{"score":95,"missing_points":[],"followup_needed":true}""",
            """{"score":95,"missing_points":["unit"],"followup_needed":false}""")
        for (raw in invalid) for (cap in listOf(0, 3)) {
            assertEquals(raw, SurveyAiDecision.FAILURE, eval(raw, cap))
        }
        assertEquals(SurveyAiDecision.FAILURE, eval("""{"score":100}""", timeout = true))
        assertEquals(SurveyAiDecision.FAILURE, eval("""{"score":100}""", error = "failure"))
    }

    @Test fun stale_q11_yield_missing_point_cannot_complete_after_answered_followup() {
        val staleMissingPoint =
            """{"score":95,"missing_points":["Specific yield target"],"followup_needed":false}"""

        assertEquals(SurveyAiDecision.FAILURE, eval(staleMissingPoint))
    }

    @Test fun unknown_evaluation_fields_are_accepted() {
        assertEquals(
            SurveyAiDecision.ACHIEVED,
            eval("""{"score":100,"missing_points":[],"followup_needed":false,"future_field":"ok"}""")
        )
    }

    @Test fun generation_failure_empty_duplicate_or_multiple_questions_are_retryable() {
        val previous = listOf("How many bags?", "Which field?")
        for (raw in listOf("", " ", "How many bags?", " how MANY  bags？ ", "How? Why?",
            "Question without punctuation", "How?\nWhy?", "{}", "[\"How?\"]")) {
            assertNull(raw, SurveyAiPolicy.acceptQuestion(raw, false, null, previous))
        }
        assertNull(SurveyAiPolicy.acceptQuestion("When?", true, null, previous))
        assertNull(SurveyAiPolicy.acceptQuestion("When?", false, "failure", previous))
        assertEquals("When?", SurveyAiPolicy.acceptQuestion("When?", false, null, previous))
        assertEquals("Ni magunia mangapi?", SurveyAiPolicy.acceptQuestion("Ni magunia mangapi?", false, null, previous))
        assertFalse(SurveyAiReason.FAILURE.terminal)
        assertTrue(SurveyAiReason.UNABLE_REFUSED.terminal)
    }
}
