package com.negi.survey.slm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.negi.survey.vm.SurveyAiPolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Android-runtime characterization of FOLLOWUP extraction and policy admission. */
@RunWith(AndroidJUnit4::class)
class FollowupExtractorInstrumentationTest {

    @Test
    fun followup_json_and_text_fixtures_preserve_extraction_and_admission() {
        data class Fixture(
            val id: String,
            val raw: String,
            val extracted: String?,
            val admitted: String?,
            val existing: List<String> = emptyList(),
        )

        val question = "How many bags did you harvest?"
        val fixtures = listOf(
            Fixture("plain-text", question, question, question),
            Fixture("followup-question", "{\"followup_question\":\"$question\"}", question, question),
            Fixture("follow-up-question", "{\"follow_up_question\":\"$question\"}", question, question),
            Fixture("fenced-json", "```json\n{\"followup_question\":\"$question\"}\n```", question, question),
            Fixture("unrelated-question", "{\"question\":\"Original survey question?\"}", "Original survey question?", "Original survey question?"),
            Fixture("empty-object", "{}", null, null),
            Fixture("empty-array", "[]", null, null),
            Fixture(
                "malformed-structured-output",
                "{\"followup_question\":\"$question\"",
                "{\"followup_question\":\"$question",
                null,
            ),
            Fixture(
                "duplicate-history",
                question,
                question,
                null,
                existing = listOf(" how MANY bags did you harvest？ "),
            ),
        )

        fixtures.forEach { fixture ->
            val extracted = FollowupExtractor.extractFollowupQuestion(fixture.raw)
            assertEquals(fixture.id, fixture.extracted, extracted)
            assertEquals(
                fixture.id,
                fixture.admitted,
                extracted?.let { SurveyAiPolicy.acceptQuestion(it, false, null, fixture.existing) },
            )
        }
    }
}
