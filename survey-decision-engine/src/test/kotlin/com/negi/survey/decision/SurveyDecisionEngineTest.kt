package com.negi.survey.decision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurveyDecisionEngineTest {
    @Test
    fun evaluation_admits_valid_plain_and_json_fenced_objects() {
        val raw = """{"score":75,"missing_points":["feeding frequency"],"followup_needed":true}"""
        listOf(raw, "```json\n$raw\n```", "```JSON\r\n$raw\r\n```").forEach { output ->
            val result = evaluate(output, ids = listOf("animals", "feeding_frequency"))
            assertEquals(SurveyDecision.GENERATE, result.decision)
            assertEquals(listOf("feeding_frequency"), result.admittedMissingPoints)
        }
    }

    @Test
    fun evaluation_rejects_invalid_transport_schema_and_decisions() {
        val invalid = listOf(
            "", "{}", "not json", "```\n{}\n```", "Explanation\n{}", "{}\nExplanation",
            "{" + "\"score\":\"75\",\"missing_points\":[\"x\"],\"followup_needed\":true}",
            "{" + "\"score\":75.0,\"missing_points\":[\"x\"],\"followup_needed\":true}",
            "{" + "\"score\":0,\"missing_points\":[\"x\"],\"followup_needed\":true}",
            "{" + "\"score\":101,\"missing_points\":[\"x\"],\"followup_needed\":true}",
            "{" + "\"score\":75,\"missing_points\":\"x\",\"followup_needed\":true}",
            "{" + "\"score\":75,\"missing_points\":[\" \"],\"followup_needed\":true}",
            "{" + "\"score\":75,\"missing_points\":[1],\"followup_needed\":true}",
            "{" + "\"score\":75,\"missing_points\":[\"x\"],\"followup_needed\":false}",
            "{" + "\"score\":95,\"missing_points\":[],\"followup_needed\":true}",
            "{" + "\"score\":95,\"missing_points\":[\"x\"],\"followup_needed\":false}",
        )
        invalid.forEach { assertEquals(it, SurveyDecision.FAILURE, evaluate(it).decision) }
        assertEquals(SurveyDecision.FAILURE, evaluate("{}", timedOut = true).decision)
        assertEquals(SurveyDecision.FAILURE, evaluate("{}", error = "inference error").decision)
    }

    @Test
    fun evaluation_preserves_flag_fallback_thresholds_and_capacity() {
        val lowOmittedFlag = """{"score":89,"missing_points":["x"]}"""
        assertEquals(SurveyDecision.GENERATE, evaluate(lowOmittedFlag, remaining = 1).decision)
        assertEquals(SurveyDecision.LIMIT_REACHED, evaluate(lowOmittedFlag, remaining = 0).decision)
        assertEquals(SurveyDecision.FAILURE, evaluate("""{"score":90,"missing_points":[]}""").decision)
        assertEquals(SurveyDecision.ACHIEVED, evaluate("""{"score":90,"missing_points":[],"followup_needed":false}""", remaining = 0).decision)
    }

    @Test
    fun evaluation_preserves_catalog_legacy_and_no_catalog_rules() {
        val ids = listOf("animals", "feeding_frequency")
        assertEquals(
            listOf("feeding_frequency"),
            evaluate(raw(listOf(" Feeding-Frequency ")), ids = ids).admittedMissingPoints,
        )
        assertEquals(SurveyDecision.FAILURE, evaluate(raw(listOf("unknown")), ids = ids).decision)
        assertEquals(
            SurveyDecision.FAILURE,
            evaluate(raw(listOf("feeding_frequency", "feeding frequency")), ids = ids).decision,
        )
        assertEquals(
            SurveyDecision.FAILURE,
            evaluate(raw(listOf("feeding_frequency")), ids = listOf("feeding_frequency", "feeding-frequency")).decision,
        )
        assertEquals(
            listOf("Exact component"),
            evaluate(raw(listOf("Exact component")), legacy = listOf("Exact component")).admittedMissingPoints,
        )
        assertEquals(
            SurveyDecision.FAILURE,
            evaluate(raw(listOf("Exact component", "Exact component")), legacy = listOf("Exact component")).decision,
        )
        assertEquals(listOf("same", "same"), evaluate(raw(listOf("same", "same"))).admittedMissingPoints)
    }

    @Test
    fun followup_admission_preserves_text_normalization_and_rejection_rules() {
        val existing = listOf("How many bags?", "Which field？")
        assertEquals("When?", admit("  When?  ").acceptedQuestion)
        assertEquals("Ni magunia mangapi？", admit("Ni magunia mangapi？").acceptedQuestion)
        listOf("", "How? Why?", "How many bags", "How?\nWhy?", "{}", "[\"How?\"]", "```\nHow?\n```").forEach {
            assertNull(it, admit(it).acceptedQuestion)
        }
        assertNull(admit(" how MANY bags？ ", existing).acceptedQuestion)
        assertNull(admit("When?", timedOut = true).acceptedQuestion)
        assertNull(admit("When?", error = "inference error").acceptedQuestion)
    }

    private fun raw(missing: List<String>): String =
        """{"score":75,"missing_points":${missing.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},"followup_needed":true}"""

    private fun evaluate(
        raw: String,
        remaining: Int = 2,
        timedOut: Boolean = false,
        error: String? = null,
        legacy: List<String> = emptyList(),
        ids: List<String> = emptyList(),
    ): EvaluationResult = SurveyDecisionEngine.evaluate(
        EvaluationInput(
            execution = EvaluationExecution(raw, timedOut, error),
            remainingFollowupCapacity = remaining,
            requiredComponents = RequiredComponentConfiguration(legacy, ids),
        ),
    )

    private fun admit(
        candidate: String,
        existing: List<String> = emptyList(),
        timedOut: Boolean = false,
        error: String? = null,
    ): FollowupAdmission = SurveyDecisionEngine.admitFollowup(
        FollowupAdmissionInput(candidate, timedOut, error, existing),
    )
}
