package com.negi.survey.vm

import org.junit.Assert.*
import org.junit.Test

class SurveyAiPolicyTest {
    private fun eval(raw: String, remaining: Int = 2, timeout: Boolean = false, error: String? = null) =
        SurveyAiPolicy.evaluate(raw, timeout, error, remaining)

    @Test fun valid_evaluations_respect_score_and_capacity() {
        for (cap in listOf(0, 1, 2, 3)) {
            assertEquals(SurveyAiDecision.ACHIEVED, eval("""{"score":90,"missing_points":[],"followup_needed":false}""", cap))
            assertEquals(if (cap == 0) SurveyAiDecision.LIMIT_REACHED else SurveyAiDecision.GENERATE,
                eval("""{"score":89,"missing_points":["unit"],"followup_needed":true}""", cap))
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
