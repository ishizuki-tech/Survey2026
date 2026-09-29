package com.negi.survey.vm

import com.negi.survey.diagnostics.AnswerTraceTransactions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyAnswerTraceLifecycleTest {
    @Test fun fresh_submit_replaces_trace_id_while_commit_reuses_active_transaction() {
        val transactions = AnswerTraceTransactions()
        val first = transactions.begin("Q14", "typed")
        assertEquals(first, transactions.commit("Q14")?.id)
        assertEquals(first, transactions.current("Q14"))

        val second = transactions.begin("Q14", "typed")
        assertNotEquals(first, second)
        assertEquals(second, transactions.commit("Q14")?.id)
    }

    @Test fun accepted_voice_handoff_is_one_shot_and_fresh_voice_supersedes_it() {
        val transactions = AnswerTraceTransactions()
        val voiceA = transactions.begin("Q14", "voice")
        assertEquals("voice", transactions.commit("Q14")?.inputSource)
        transactions.markVoiceAccepted("Q14")

        assertTrue(transactions.consumeVoiceAccepted("Q14"))
        assertEquals(voiceA, transactions.commit("Q14")?.id)
        assertFalse(transactions.consumeVoiceAccepted("Q14"))

        val typedB = transactions.begin("Q14", "typed")
        assertNotEquals(voiceA, typedB)
        assertEquals("typed", transactions.commit("Q14")?.inputSource)

        transactions.markVoiceAccepted("Q14")
        val voiceC = transactions.begin("Q14", "voice")
        assertEquals("voice", transactions.commit("Q14")?.inputSource)
        assertNotEquals(typedB, voiceC)
        assertFalse(transactions.consumeVoiceAccepted("Q14"))
        transactions.markVoiceAccepted("Q14")
        transactions.clear()
        assertFalse(transactions.consumeVoiceAccepted("Q14"))
    }
}
