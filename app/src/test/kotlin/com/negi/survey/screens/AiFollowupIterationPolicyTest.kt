package com.negi.survey.screens

import com.negi.survey.vm.SurveyViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFollowupIterationPolicyTest {

    @Test
    fun two_followups_exhaust_capacity_and_prevent_a_third() {
        assertTrue(followupCapacityRemaining(emptyList()) == 2)
        assertTrue(followupCapacityRemaining(listOf(entry("First?"))) == 1)
        assertTrue(followupCapacityRemaining(listOf(entry("First?"), entry("Second?"))) == 0)
    }

    @Test
    fun duplicate_candidate_is_rejected_against_all_previous_questions() {
        val existing = listOf(
            entry("How did FAW affect the crop?"),
            entry("What was the yield loss?")
        )

        assertFalse(isDistinctFollowupQuestion(" how did faw affect the crop？ ", existing))
        assertFalse(isDistinctFollowupQuestion("What was the yield loss", existing))
        assertTrue(isDistinctFollowupQuestion("Which crop stage was affected?", existing))
    }

    @Test
    fun capacity_exhaustion_rejects_a_distinct_third_candidate() {
        val existing = listOf(entry("First?"), entry("Second?"))

        assertFalse(canAcceptFollowupCandidate("Distinct third question?", existing))
    }

    private fun entry(question: String) = SurveyViewModel.FollowupEntry(question = question)
}
