/*
 * =====================================================================
 *  IshizukiTech LLC — Android App Shell
 *  ---------------------------------------------------------------------
 *  File: SurveyAppStartupRecoveryTest.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey

import com.negi.survey.net.GitHubUploader
import com.negi.survey.net.SurveyUploadRescheduler
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SurveyAppStartupRecoveryTest {

    @Test
    fun initial_schedule_is_deferred_once_and_recovery_runs_through_io_seam() {
        val state = StartupSurveyRecoveryState()
        val operations = FakeOperations()
        val coordinator = coordinator(state, operations)

        coordinator.scheduleInitial()
        coordinator.scheduleInitial()

        assertEquals(1, operations.delayed.size)
        assertEquals(INITIAL_DELAY_MS, operations.delayed.single().delayMs)
        assertEquals(0, operations.ioLaunchCount)
        assertEquals(0, operations.recoverCallCount)

        operations.runNextDelayed()

        assertEquals(1, operations.ioLaunchCount)
        assertEquals(1, operations.recoverCallCount)
        assertTrue(state.completed.get())
        assertFalse(state.running.get())
    }

    @Test
    fun config_unavailable_gets_exactly_one_retry_without_marking_completion() {
        val state = StartupSurveyRecoveryState()
        val operations = FakeOperations().apply {
            config = null
        }
        val coordinator = coordinator(state, operations)

        coordinator.scheduleInitial()
        operations.runNextDelayed()

        assertFalse(state.completed.get())
        assertTrue(state.retryScheduled.get())
        assertEquals(1, operations.delayed.size)
        assertEquals(RETRY_DELAY_MS, operations.delayed.single().delayMs)
        assertEquals(0, operations.recoverCallCount)

        operations.runNextDelayed()

        assertFalse(state.completed.get())
        assertEquals(0, operations.delayed.size)
        assertEquals(0, operations.recoverCallCount)
    }

    @Test
    fun workmanager_unavailable_gets_exactly_one_retry() {
        val state = StartupSurveyRecoveryState()
        val operations = FakeOperations().apply {
            workManagerAvailable = false
        }
        val coordinator = coordinator(state, operations)

        coordinator.scheduleInitial()
        operations.runNextDelayed()

        assertTrue(state.retryScheduled.get())
        assertFalse(state.completed.get())
        assertEquals(1, operations.delayed.size)

        operations.runNextDelayed()

        assertFalse(state.completed.get())
        assertEquals(0, operations.delayed.size)
        assertEquals(0, operations.recoverCallCount)
    }

    @Test
    fun normal_summary_marks_completion_and_prevents_same_process_rerun() {
        val state = StartupSurveyRecoveryState()
        val operations = FakeOperations().apply {
            summary = summary(
                discovered = 2,
                attempted = 2,
                classifications = mapOf(
                    SurveyUploadRescheduler.RecoveryClassification.SUBMITTED to 1,
                    SurveyUploadRescheduler.RecoveryClassification.DEFERRED to 1,
                ),
            )
        }
        val coordinator = coordinator(state, operations)

        coordinator.scheduleInitial()
        operations.runNextDelayed()

        assertTrue(state.completed.get())
        assertEquals(1, operations.recoverCallCount)
        assertEquals(0, operations.delayed.size)

        coordinator.scheduleInitial()

        assertEquals(1, operations.recoverCallCount)
        assertEquals(0, operations.delayed.size)
    }

    @Test
    fun deferred_invalid_and_operational_failure_summary_do_not_schedule_whole_recovery_retry() {
        val classifications = listOf(
            SurveyUploadRescheduler.RecoveryClassification.DEFERRED,
            SurveyUploadRescheduler.RecoveryClassification.INVALID,
            SurveyUploadRescheduler.RecoveryClassification.OPERATIONAL_FAILURE,
        )

        classifications.forEach { classification ->
            val state = StartupSurveyRecoveryState()
            val operations = FakeOperations().apply {
                summary = summary(
                    discovered = 1,
                    attempted = 1,
                    operationalFailures =
                        if (classification == SurveyUploadRescheduler.RecoveryClassification.OPERATIONAL_FAILURE) 1 else 0,
                    classifications = mapOf(classification to 1),
                )
            }
            val coordinator = coordinator(state, operations)

            coordinator.scheduleInitial()
            operations.runNextDelayed()

            assertTrue("classification=$classification", state.completed.get())
            assertFalse("classification=$classification", state.retryScheduled.get())
            assertEquals("classification=$classification", 0, operations.delayed.size)
        }
    }

    @Test
    fun top_level_recovery_exception_gets_one_retry_then_stops() {
        val state = StartupSurveyRecoveryState()
        val operations = FakeOperations().apply {
            recoverException = IllegalStateException("discovery failed")
        }
        val coordinator = coordinator(state, operations)

        coordinator.scheduleInitial()
        operations.runNextDelayed()

        assertFalse(state.completed.get())
        assertTrue(state.retryScheduled.get())
        assertEquals(1, operations.delayed.size)

        operations.runNextDelayed()

        assertFalse(state.completed.get())
        assertEquals(0, operations.delayed.size)
        assertEquals(2, operations.recoverCallCount)
    }

    @Test
    fun config_lookup_exception_gets_one_retry_then_stops() {
        val state = StartupSurveyRecoveryState()
        val operations = FakeOperations().apply {
            configException = IllegalStateException("config failed")
        }
        val coordinator = coordinator(state, operations)

        coordinator.scheduleInitial()
        operations.runNextDelayed()

        assertFalse(state.completed.get())
        assertTrue(state.retryScheduled.get())
        assertEquals(1, operations.delayed.size)
        assertEquals(0, operations.recoverCallCount)

        operations.runNextDelayed()

        assertFalse(state.completed.get())
        assertEquals(0, operations.delayed.size)
        assertEquals(0, operations.recoverCallCount)
    }

    @Test
    fun running_guard_prevents_concurrent_attempt() {
        val state = StartupSurveyRecoveryState().apply {
            running.set(true)
        }
        val operations = FakeOperations()
        val coordinator = coordinator(state, operations)

        coordinator.scheduleInitial()
        operations.runNextDelayed()

        assertEquals(0, operations.recoverCallCount)
        assertEquals(0, operations.delayed.size)
        assertTrue(state.running.get())
        assertFalse(state.completed.get())
    }

    @Test
    fun cancellation_from_recovery_propagates_and_does_not_complete() {
        val state = StartupSurveyRecoveryState()
        val operations = FakeOperations().apply {
            recoverException = CancellationException("cancel")
        }
        val coordinator = coordinator(state, operations)

        coordinator.scheduleInitial()

        var thrown = false
        try {
            operations.runNextDelayed()
        } catch (_: CancellationException) {
            thrown = true
        }

        assertTrue(thrown)
        assertFalse(state.completed.get())
        assertFalse(state.running.get())
        assertFalse(state.retryScheduled.get())
    }

    @Test
    fun error_from_recovery_propagates_and_is_not_swallowed() {
        val state = StartupSurveyRecoveryState()
        val operations = FakeOperations().apply {
            recoverError = TestError("boom")
        }
        val coordinator = coordinator(state, operations)

        coordinator.scheduleInitial()

        var thrown: TestError? = null
        try {
            operations.runNextDelayed()
            fail("Expected TestError")
        } catch (error: TestError) {
            thrown = error
        }

        assertEquals("boom", thrown?.message)
        assertFalse(state.completed.get())
        assertFalse(state.running.get())
        assertFalse(state.retryScheduled.get())
    }

    @Test
    fun summary_logging_uses_aggregate_counts_only() {
        val state = StartupSurveyRecoveryState()
        val operations = FakeOperations().apply {
            summary = summary(
                discovered = 3,
                attempted = 3,
                duplicates = 2,
                unclassified = 4,
                operationalFailures = 1,
                classifications = mapOf(
                    SurveyUploadRescheduler.RecoveryClassification.SUBMITTED to 1,
                    SurveyUploadRescheduler.RecoveryClassification.ACTIVE to 1,
                    SurveyUploadRescheduler.RecoveryClassification.OPERATIONAL_FAILURE to 1,
                ),
            )
        }
        val coordinator = coordinator(state, operations)

        coordinator.scheduleInitial()
        operations.runNextDelayed()

        val log = operations.warningLogs.lastOrNull().orEmpty()
        assertTrue(log.contains("discovered=3"))
        assertTrue(log.contains("attempted=3"))
        assertTrue(log.contains("submitted=1"))
        assertTrue(log.contains("active=1"))
        assertTrue(log.contains("operationalFailures=1"))
        assertTrue(log.contains("duplicates=2"))
        assertTrue(log.contains("unclassified=4"))
        assertFalse(log.contains("survey_id"))
        assertFalse(log.contains("token"))
        assertFalse(log.contains(".json"))
    }

    private fun coordinator(
        state: StartupSurveyRecoveryState,
        operations: FakeOperations,
    ): StartupSurveyRecoveryCoordinator =
        StartupSurveyRecoveryCoordinator(
            state = state,
            operations = operations,
            initialDelayMs = INITIAL_DELAY_MS,
            retryDelayMs = RETRY_DELAY_MS,
        )

    private fun summary(
        discovered: Int = 0,
        attempted: Int = 0,
        duplicates: Int = 0,
        unclassified: Int = 0,
        operationalFailures: Int = 0,
        classifications: Map<SurveyUploadRescheduler.RecoveryClassification, Int> = emptyMap(),
    ): SurveyUploadRescheduler.RecoverySummary =
        SurveyUploadRescheduler.RecoverySummary(
            discoveredSurveyCount = discovered,
            reconciledCandidateCount = attempted,
            duplicateFileCount = duplicates,
            unclassifiedFileCount = unclassified,
            operationalFailureCount = operationalFailures,
            classificationCounts = classifications,
            candidates = emptyList(),
        )

    private class FakeOperations : StartupSurveyRecoveryCoordinator.Operations {
        data class Delayed(
            val delayMs: Long,
            val block: () -> Unit,
        )

        val delayed = mutableListOf<Delayed>()
        val debugLogs = mutableListOf<String>()
        val warningLogs = mutableListOf<String>()

        var ioLaunchCount = 0
        var recoverCallCount = 0
        var workManagerAvailable = true
        var config: GitHubUploader.GitHubConfig? = validConfig()
        var configException: Exception? = null
        var recoverException: Exception? = null
        var recoverError: Error? = null
        var summary: SurveyUploadRescheduler.RecoverySummary = summary()

        override fun postDelayed(delayMs: Long, block: () -> Unit) {
            delayed += Delayed(delayMs, block)
        }

        override fun launchIo(block: () -> Unit) {
            ioLaunchCount += 1
            block()
        }

        override fun isWorkManagerAvailable(attempt: String): Boolean = workManagerAvailable

        override fun resolveConfig(): GitHubUploader.GitHubConfig? {
            configException?.let { throw it }
            return config
        }

        override fun recover(
            config: GitHubUploader.GitHubConfig
        ): SurveyUploadRescheduler.RecoverySummary {
            recoverCallCount += 1
            recoverError?.let { throw it }
            recoverException?.let { throw it }
            return summary
        }

        override fun logDebug(message: String) {
            debugLogs += message
        }

        override fun logWarning(message: String, throwable: Throwable?) {
            warningLogs += message
        }

        fun runNextDelayed() {
            if (delayed.isEmpty()) {
                fail("No delayed callback is queued")
            }
            delayed.removeAt(0).block()
        }

        companion object {
            private fun validConfig(): GitHubUploader.GitHubConfig =
                GitHubUploader.GitHubConfig(
                    owner = "owner",
                    repo = "repo",
                    token = "token",
                    branch = "main",
                    pathPrefix = "",
                )

            private fun summary(): SurveyUploadRescheduler.RecoverySummary =
                SurveyUploadRescheduler.RecoverySummary(
                    discoveredSurveyCount = 0,
                    reconciledCandidateCount = 0,
                    duplicateFileCount = 0,
                    unclassifiedFileCount = 0,
                    operationalFailureCount = 0,
                    classificationCounts = emptyMap(),
                    candidates = emptyList(),
                )
        }
    }

    private class TestError(message: String) : Error(message)

    private companion object {
        const val INITIAL_DELAY_MS = 2_500L
        const val RETRY_DELAY_MS = 1_600L
    }
}
