package com.negi.survey.whisper

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperReleaseSafetyTest {
    @Test
    fun failed_previous_release_records_uncertainty_and_stops_replacement() = runTest {
        val nativeFailure = IllegalStateException("native free failed")
        var uncertain: Throwable? = null
        var replacementCreated = false
        var calls = 0
        val error = runCatching {
            releaseWhisperContextForReplacement(
                release = { calls++; throw nativeFailure },
                markUncertain = { uncertain = it },
            )
            replacementCreated = true
        }.exceptionOrNull()
        assertSame(nativeFailure, uncertain)
        assertTrue(error?.message?.contains("restart") == true)
        assertFalse(replacementCreated)
        assertEquals(1, calls)
    }

    @Test
    fun cancelled_release_records_uncertainty_and_preserves_cancellation() = runTest {
        val cancelled = CancellationException("release cancelled")
        var uncertain: Throwable? = null
        val error = runCatching {
            releaseWhisperContextForReplacement(
                release = { throw cancelled },
                markUncertain = { uncertain = it },
            )
        }.exceptionOrNull()
        assertSame(cancelled, uncertain)
        assertTrue(error is CancellationException)
    }

    @Test
    fun successful_previous_release_allows_replacement() = runTest {
        var uncertain = false
        var released = false
        releaseWhisperContextForReplacement(
            release = { released = true },
            markUncertain = { uncertain = true },
        )
        assertTrue(released)
        assertFalse(uncertain)
    }
}
