package com.negi.survey.vm

import com.negi.survey.decision.EvaluationExecution
import com.negi.survey.decision.EvaluationInput
import com.negi.survey.decision.SurveyDecision
import com.negi.survey.decision.SurveyDecisionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Characterizes the boundary between the app's diagnostic JSON parser and the decision engine's
 * stricter EVAL admission. These assertions intentionally use production APIs only.
 */
class SurveyJsonParserContractTest {
    @Test
    fun schema_valid_eval_transport_matches_diagnostic_parser_and_engine_admission() {
        val valid = """{"score":75,"missing_points":["needed detail"],"followup_needed":true}"""
        listOf(
            TransportFixture("plain-object", valid, diagnosticParses = true, SurveyDecision.GENERATE),
            TransportFixture("lowercase-json-fence", "```json\n$valid\n```", true, SurveyDecision.GENERATE),
            TransportFixture("uppercase-json-fence", "```JSON\n$valid\n```", true, SurveyDecision.GENERATE),
            TransportFixture("surrounding-whitespace", " \n\t```json\n$valid\n```\r\n  ", true, SurveyDecision.GENERATE),
            TransportFixture("untagged-fence", "```\n$valid\n```", false, SurveyDecision.FAILURE),
            TransportFixture("wrong-fence-language", "```yaml\n$valid\n```", false, SurveyDecision.FAILURE),
            TransportFixture("prose-surrounding-json", "Here is the result:\n$valid", false, SurveyDecision.FAILURE),
            TransportFixture("multiple-json-objects", "$valid\n$valid", false, SurveyDecision.FAILURE),
            TransportFixture("multiple-fences", "```json\n$valid\n```\n```json\n$valid\n```", false, SurveyDecision.FAILURE),
            TransportFixture("json-array", "[$valid]", false, SurveyDecision.FAILURE),
            TransportFixture("json-primitive", "true", false, SurveyDecision.FAILURE),
            TransportFixture("malformed-json", "{not json}", false, SurveyDecision.FAILURE),
            TransportFixture("missing-closing-fence", "```json\n$valid", false, SurveyDecision.FAILURE),
            TransportFixture("characters-after-closing-fence", "```json\n$valid\n```\ntrailing", false, SurveyDecision.FAILURE),
        ).forEach { fixture ->
            val diagnostic = parseStrictModelJsonObject(fixture.raw)
            if (fixture.diagnosticParses) {
                assertNotNull(fixture.id, diagnostic)
            } else {
                assertNull(fixture.id, diagnostic)
            }
            assertEquals(fixture.id, fixture.expectedDecision, evaluate(fixture.raw))
        }
    }

    @Test
    fun schema_invalid_eval_payloads_remain_diagnostic_json_but_fail_engine_admission() {
        listOf(
            SchemaFixture("missing-score", """{"missing_points":["needed detail"],"followup_needed":true}"""),
            SchemaFixture("string-score", """{"score":"75","missing_points":["needed detail"],"followup_needed":true}"""),
            SchemaFixture("missing-missing-points", """{"score":75,"followup_needed":true}"""),
            SchemaFixture("contradictory-followup-needed", """{"score":75,"missing_points":["needed detail"],"followup_needed":false}"""),
        ).forEach { fixture ->
            assertNotNull(fixture.id, parseStrictModelJsonObject(fixture.raw))
            assertEquals(fixture.id, SurveyDecision.FAILURE, evaluate(fixture.raw))
        }
    }

    private fun evaluate(raw: String): SurveyDecision = SurveyDecisionEngine.evaluate(
        EvaluationInput(
            execution = EvaluationExecution(raw),
            remainingFollowupCapacity = 1,
        ),
    ).decision

    private data class TransportFixture(
        val id: String,
        val raw: String,
        val diagnosticParses: Boolean,
        val expectedDecision: SurveyDecision,
    )

    private data class SchemaFixture(val id: String, val raw: String)
}
