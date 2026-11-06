package com.negi.survey.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Executable baseline for the strict EVAL-admission and FOLLOWUP-admission contracts.
 *
 * These fixtures intentionally preserve the current behavior, including permissive
 * no-catalog missing-point handling and the low-score fallback for an omitted flag.
 */
class SurveyAiBehaviorCharacterizationTest {

    private data class EvalFixture(
        val id: String,
        val raw: String,
        val expectedDecision: SurveyAiDecision,
        val expectedMissingPoints: List<String> = emptyList(),
        val remaining: Int = 2,
        val requiredComponents: List<String> = emptyList(),
        val requiredComponentIds: List<String> = emptyList(),
    )

    @Test
    fun eval_transport_schema_and_decision_fixtures_preserve_current_admission() {
        val low = """{"score":75,"missing_points":["feeding frequency"],"followup_needed":true}"""
        val fixtures = listOf(
            EvalFixture("eval-plain-complete", low, SurveyAiDecision.GENERATE, listOf("feeding_frequency"), requiredComponentIds = ids),
            EvalFixture("eval-json-fence", "```json\n$low\n```", SurveyAiDecision.GENERATE, listOf("feeding_frequency"), requiredComponentIds = ids),
            EvalFixture("eval-uppercase-json-fence", "```JSON\n$low\n```", SurveyAiDecision.GENERATE, listOf("feeding_frequency"), requiredComponentIds = ids),
            EvalFixture("eval-untagged-fence", "```\n$low\n```", SurveyAiDecision.FAILURE, requiredComponentIds = ids),
            EvalFixture("eval-prose-before", "Explanation\n$low", SurveyAiDecision.FAILURE, requiredComponentIds = ids),
            EvalFixture("eval-prose-after", "$low\nExplanation", SurveyAiDecision.FAILURE, requiredComponentIds = ids),
            EvalFixture("eval-multiple-objects", "$low$low", SurveyAiDecision.FAILURE, requiredComponentIds = ids),
            EvalFixture("eval-invalid-json", "{not json}", SurveyAiDecision.FAILURE),
            EvalFixture("eval-missing-score", """{"missing_points":["x"],"followup_needed":true}""", SurveyAiDecision.FAILURE),
            EvalFixture("eval-quoted-score", """{"score":"75","missing_points":["x"],"followup_needed":true}""", SurveyAiDecision.FAILURE),
            EvalFixture("eval-floating-score", """{"score":75.0,"missing_points":["x"],"followup_needed":true}""", SurveyAiDecision.FAILURE),
            EvalFixture("eval-score-out-of-range", """{"score":101,"missing_points":["x"],"followup_needed":true}""", SurveyAiDecision.FAILURE),
            EvalFixture("eval-missing-points-not-array", """{"score":75,"missing_points":"x","followup_needed":true}""", SurveyAiDecision.FAILURE),
            EvalFixture("eval-blank-missing-point", """{"score":75,"missing_points":[" "],"followup_needed":true}""", SurveyAiDecision.FAILURE),
            EvalFixture("eval-non-string-missing-point", """{"score":75,"missing_points":[1],"followup_needed":true}""", SurveyAiDecision.FAILURE),
            EvalFixture("eval-low-score-omitted-flag", """{"score":75,"missing_points":["x"]}""", SurveyAiDecision.GENERATE, listOf("x")),
            EvalFixture("eval-high-score-omitted-flag", """{"score":95,"missing_points":[]}""", SurveyAiDecision.FAILURE),
            EvalFixture("eval-non-boolean-flag", """{"score":75,"missing_points":["x"],"followup_needed":"true"}""", SurveyAiDecision.FAILURE),
            EvalFixture("eval-high-score-with-missing", """{"score":95,"missing_points":["x"],"followup_needed":false}""", SurveyAiDecision.FAILURE),
            EvalFixture("eval-low-score-no-followup", """{"score":75,"missing_points":["x"],"followup_needed":false}""", SurveyAiDecision.FAILURE),
            EvalFixture("eval-extra-fields", """{"score":100,"missing_points":[],"followup_needed":false,"future":{"safe":true}}""", SurveyAiDecision.ACHIEVED),
            EvalFixture("eval-capacity-zero", """{"score":75,"missing_points":["x"],"followup_needed":true}""", SurveyAiDecision.LIMIT_REACHED, listOf("x"), remaining = 0),
            EvalFixture("eval-capacity-one", """{"score":75,"missing_points":["x"],"followup_needed":true}""", SurveyAiDecision.GENERATE, listOf("x"), remaining = 1),
            EvalFixture("eval-capacity-two", """{"score":75,"missing_points":["x"],"followup_needed":true}""", SurveyAiDecision.GENERATE, listOf("x"), remaining = 2),
        )

        fixtures.forEach { fixture ->
            val admission = SurveyAiPolicy.evaluateAdmission(
                raw = fixture.raw,
                timedOut = false,
                error = null,
                remaining = fixture.remaining,
                requiredComponents = fixture.requiredComponents,
                requiredComponentIds = fixture.requiredComponentIds,
            )
            assertEquals(fixture.id, fixture.expectedDecision, admission.decision)
            assertEquals(fixture.id, fixture.expectedMissingPoints, admission.missingPoints)
        }
    }

    @Test
    fun required_component_fixtures_preserve_catalog_legacy_and_no_catalog_rules() {
        fun raw(missing: List<String>) =
            """{"score":75,"missing_points":${missing.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},"followup_needed":true}"""

        fun admission(
            missing: List<String>,
            components: List<String> = emptyList(),
            ids: List<String> = emptyList(),
        ) = SurveyAiPolicy.evaluateAdmission(raw(missing), false, null, 2, components, ids)

        assertEquals(listOf("feeding_frequency"), admission(listOf(" Feeding-Frequency "), ids = ids).missingPoints)
        assertEquals(listOf("feeding_frequency"), admission(listOf("feeding__frequency"), ids = ids).missingPoints)
        assertEquals(SurveyAiDecision.FAILURE, admission(listOf("unknown"), ids = ids).decision)
        assertEquals(SurveyAiDecision.FAILURE, admission(listOf("feeding_frequency"), ids = listOf("feeding_frequency", "feeding-frequency")).decision)
        assertEquals(SurveyAiDecision.FAILURE, admission(listOf("feeding_frequency", "feeding frequency"), ids = ids).decision)

        val legacy = listOf("Exact component", "Other component")
        assertEquals(listOf("Exact component"), admission(listOf("Exact component"), components = legacy).missingPoints)
        assertEquals(SurveyAiDecision.FAILURE, admission(listOf("exact component"), components = legacy).decision)
        assertEquals(SurveyAiDecision.FAILURE, admission(listOf("Exact component", "Exact component"), components = legacy).decision)
        assertEquals(listOf("same", "same"), admission(listOf("same", "same")).missingPoints)
    }

    @Test
    fun followup_admission_fixtures_preserve_text_and_rejection_rules() {
        val existing = listOf("How many bags?", "Which field？")
        data class Fixture(val id: String, val raw: String, val expected: String?)
        listOf(
            Fixture("followup-ascii", "How many bags?", "How many bags?"),
            Fixture("followup-full-width", "Ni magunia mangapi？", "Ni magunia mangapi？"),
            Fixture("followup-outer-trim", "  How many bags?  ", "How many bags?"),
            Fixture("followup-blank", "   ", null),
            Fixture("followup-multiline", "How many bags?\nPlease answer", null),
            Fixture("followup-multiple-marks", "How many bags??", null),
            Fixture("followup-no-terminal-mark", "How many bags", null),
            Fixture("followup-json-like", "{\"question\":\"How many bags?\"}", null),
            Fixture("followup-markdown", "```\nHow many bags?\n```", null),
            Fixture("followup-exact-duplicate", "How many bags?", null),
            Fixture("followup-normalized-duplicate", " how MANY   bags？ ", null),
        ).forEach { fixture ->
            val prior = if (fixture.id.contains("duplicate")) existing else emptyList()
            assertEquals(fixture.id, fixture.expected, SurveyAiPolicy.acceptQuestion(fixture.raw, false, null, prior))
        }
        assertNull(SurveyAiPolicy.acceptQuestion("When?", true, null, emptyList()))
        assertNull(SurveyAiPolicy.acceptQuestion("When?", false, "inference error", emptyList()))
    }

    private companion object {
        val ids = listOf("animals", "feeding_frequency")
    }
}
