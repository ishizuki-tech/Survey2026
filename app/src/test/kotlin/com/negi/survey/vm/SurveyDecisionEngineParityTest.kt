package com.negi.survey.vm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fixed compatibility checks for the app-local facade.
 *
 * Before the facade delegated to the engine, this suite compared the independent
 * policy implementation with the engine. The fixed expected values here prevent
 * that comparison from becoming tautological after delegation.
 */
class SurveyDecisionEngineParityTest {
    @Test
    fun eval_admission_preserves_fixed_compatibility_fixtures() {
        val ids = listOf("animals", "feeding_frequency")
        listOf(
            EvalFixture(
                "catalog-id-canonicalization",
                """{"score":75,"missing_points":[" Feeding-Frequency "],"followup_needed":true}""",
                SurveyAiDecision.GENERATE,
                listOf("feeding_frequency"),
                ids = ids,
            ),
            EvalFixture(
                "legacy-is-exact",
                """{"score":75,"missing_points":["exact component"],"followup_needed":true}""",
                SurveyAiDecision.FAILURE,
                legacy = listOf("Exact component"),
            ),
            EvalFixture(
                "catalog-precedes-legacy",
                """{"score":75,"missing_points":["feeding frequency"],"followup_needed":true}""",
                SurveyAiDecision.GENERATE,
                listOf("feeding_frequency"),
                legacy = listOf("different legacy text"),
                ids = ids,
            ),
            EvalFixture(
                "low-score-omitted-flag",
                """{"score":75,"missing_points":["x"]}""",
                SurveyAiDecision.GENERATE,
                listOf("x"),
            ),
            EvalFixture(
                "limit-reached",
                """{"score":75,"missing_points":["x"],"followup_needed":true}""",
                SurveyAiDecision.LIMIT_REACHED,
                listOf("x"),
                remaining = 0,
            ),
            EvalFixture("invalid-json", "{not json}", SurveyAiDecision.FAILURE),
            EvalFixture(
                "timed-out",
                """{"score":75,"missing_points":["x"],"followup_needed":true}""",
                SurveyAiDecision.FAILURE,
                timedOut = true,
            ),
        ).forEach { fixture ->
            val actual = SurveyAiPolicy.evaluateAdmission(
                fixture.raw,
                fixture.timedOut,
                fixture.error,
                fixture.remaining,
                fixture.legacy,
                fixture.ids,
            )
            assertEquals(fixture.id, fixture.expectedDecision, actual.decision)
            assertEquals(fixture.id, fixture.expectedMissingPoints, actual.missingPoints)
        }
    }

    @Test
    fun followup_admission_preserves_fixed_compatibility_fixtures() {
        val existing = listOf("How many bags?")
        listOf(
            FollowupFixture("accepted", "  When?  ", "When?"),
            FollowupFixture("duplicate", " how MANY bags？ ", null, existing = existing),
            FollowupFixture("multiple-questions", "How? Why?", null),
            FollowupFixture("timeout", "When?", null, timedOut = true),
            FollowupFixture("error", "When?", null, error = "inference error"),
        ).forEach { fixture ->
            assertEquals(
                fixture.id,
                fixture.expected,
                SurveyAiPolicy.acceptQuestion(
                    fixture.candidate,
                    fixture.timedOut,
                    fixture.error,
                    fixture.existing,
                ),
            )
        }
    }

    private data class EvalFixture(
        val id: String,
        val raw: String,
        val expectedDecision: SurveyAiDecision,
        val expectedMissingPoints: List<String> = emptyList(),
        val remaining: Int = 2,
        val timedOut: Boolean = false,
        val error: String? = null,
        val legacy: List<String> = emptyList(),
        val ids: List<String> = emptyList(),
    )

    private data class FollowupFixture(
        val id: String,
        val candidate: String,
        val expected: String?,
        val timedOut: Boolean = false,
        val error: String? = null,
        val existing: List<String> = emptyList(),
    )
}
