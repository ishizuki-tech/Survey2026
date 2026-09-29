package com.negi.survey.vm

import com.negi.survey.diagnostics.AnswerTraceTransactions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SurveyAnswerTraceLifecycleTest {
    @Test fun fresh_submit_replaces_trace_id_while_commit_reuses_active_transaction() {
        val transactions = AnswerTraceTransactions()
        val first = transactions.begin("Q14")
        assertEquals(first, transactions.commit("Q14"))
        assertEquals(first, transactions.current("Q14"))

        val second = transactions.begin("Q14")
        assertNotEquals(first, second)
        assertEquals(second, transactions.commit("Q14"))
    }
}
