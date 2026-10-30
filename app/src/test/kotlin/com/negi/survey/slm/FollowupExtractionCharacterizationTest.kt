package com.negi.survey.slm

import com.negi.survey.vm.SurveyAiPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** Direct JVM characterization of the production FOLLOWUP extractor before policy admission. */
class FollowupExtractionCharacterizationTest {

    @Test
    fun plain_text_extraction_and_policy_admission_fixtures() {
        data class Fixture(val id: String, val raw: String, val extracted: String?, val admitted: String?)
        val fixtures = listOf(
            Fixture("followup-plain-text", "How many bags did you harvest?", "How many bags did you harvest?", "How many bags did you harvest?"),
            Fixture("followup-mixed-text", "I need one detail.\nHow many bags did you harvest?", "How many bags did you harvest?", "How many bags did you harvest?"),
            Fixture("followup-empty", "", null, null),
            Fixture("followup-multiple-lines", "First question?\nSecond question?", "First question?", "First question?"),
            Fixture("followup-duplicate-after-extraction", "How many bags did you harvest?", "How many bags did you harvest?", null),
        )

        fixtures.forEach { fixture ->
            val extracted = FollowupExtractor.extractFollowupQuestion(fixture.raw)
            assertEquals(fixture.id, fixture.extracted, extracted)
            val existing = if (fixture.id == "followup-duplicate-after-extraction") listOf(" how MANY bags did you harvest？ ") else emptyList()
            assertEquals(fixture.id, fixture.admitted, extracted?.let { SurveyAiPolicy.acceptQuestion(it, false, null, existing) })
        }
    }

}
