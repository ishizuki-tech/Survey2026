package com.negi.survey.vm

import com.negi.survey.vm.SurveyViewModel.FollowupEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiQuestionPlaybackTest {
    private val first = FollowupEntry("First follow-up?")
    private val second = FollowupEntry("Second follow-up?")

    @Test fun originalPlaysOnlyWhenUnansweredAndThereIsNoFollowupHistory() {
        assertEquals("Original?", select()?.text)
        assertNull(select(answer = "Original answer"))
        assertNull(select(entries = listOf(first.copy(answer = "First answer"))))
        assertNull(resolveAiAutoReadTarget(" ", "", emptyList(), completed = false))
    }

    @Test fun latestUnansweredFollowupTakesPriorityOverOriginalAndAnsweredEntries() {
        assertEquals(first, select(entries = listOf(first))?.followup)
        assertEquals(second, select(entries = listOf(first, second))?.followup)
        assertEquals(second, select("Original answer", listOf(first.copy(answer = "First answer"), second))?.followup)
        assertEquals(first, select(entries = listOf(first, second.copy(answer = "Second answer")))?.followup)
    }

    @Test fun completionTakesPriorityOverAnyTarget() {
        assertNull(select(completed = true))
        assertNull(select(entries = listOf(first), completed = true))
    }

    private fun select(
        answer: String = "",
        entries: List<FollowupEntry> = emptyList(),
        completed: Boolean = false
    ) = resolveAiAutoReadTarget("Original?", answer, entries, completed)
}
