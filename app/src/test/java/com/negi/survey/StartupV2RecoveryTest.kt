package com.negi.survey

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupV2RecoveryTest {
    @Test
    fun successful_recovery_runs_all_operations_in_order() {
        val recovery = RecoveryProbe()

        recovery.run()

        assertEquals(listOf("traces", "diagnostics", "artifacts", "cleanup"), recovery.calls)
        assertTrue(recovery.warnings.isEmpty())
    }

    @Test
    fun ordinary_failure_logs_the_operation_and_continues_recovery() {
        val failure = IllegalStateException("staging failed")
        val recovery = RecoveryProbe(stageDiagnostics = { throw failure })

        recovery.run()

        assertEquals(
            listOf("traces", "diagnostics", "warning", "artifacts", "cleanup"),
            recovery.calls,
        )
        val (message, cause) = recovery.warnings.single()
        assertTrue(message.contains("CrashCapture.stagePreviousProcessDiagnostics"))
        assertSame(failure, cause)
    }

    @Test
    fun cancellation_propagates_without_logging_or_running_later_operations() {
        val cancellation = CancellationException("startup cancelled")
        val recovery = RecoveryProbe(stageDiagnostics = { throw cancellation })

        assertSame(cancellation, assertThrows(CancellationException::class.java) { recovery.run() })
        assertEquals(listOf("traces", "diagnostics"), recovery.calls)
        assertTrue(recovery.warnings.isEmpty())
    }

    @Test
    fun error_propagates_without_logging_or_running_later_operations() {
        val error = AssertionError("fatal failure")
        val recovery = RecoveryProbe(stageDiagnostics = { throw error })

        assertSame(error, assertThrows(AssertionError::class.java) { recovery.run() })
        assertEquals(listOf("traces", "diagnostics"), recovery.calls)
        assertTrue(recovery.warnings.isEmpty())
    }

    private class RecoveryProbe(private val stageDiagnostics: () -> Unit = {}) {
        val calls = mutableListOf<String>()
        val warnings = mutableListOf<Pair<String, Exception>>()

        fun run() = runStartupV2Recovery(
            recoverTraces = { calls += "traces" },
            stageDiagnostics = {
                calls += "diagnostics"
                stageDiagnostics()
            },
            recoverIndependentArtifacts = { calls += "artifacts" },
            cleanupConfirmedSurveys = { calls += "cleanup" },
            logWarning = { message, exception ->
                calls += "warning"
                warnings += message to exception
            },
        )
    }
}
