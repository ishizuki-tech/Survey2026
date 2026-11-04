package com.negi.survey.screens

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTimerFormatTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun timerRunsOnlyDuringCaptureAndResetsForTheNextRecording() = runTest {
        val updates = mutableListOf<Int>()
        val clock = { testScheduler.currentTime }
        runRecordingTimer(recording = true, captureActive = false, clock, updates::add)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(0), updates)

        var job = launch { runRecordingTimer(true, true, clock, updates::add) }
        runCurrent()
        advanceTimeBy(2_500)
        runCurrent()
        assertEquals(2, updates.last())
        job.cancelAndJoin()
        runRecordingTimer(recording = false, captureActive = false, clock, updates::add)
        val stoppedUpdates = updates.toList()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(stoppedUpdates, updates)

        job = launch { runRecordingTimer(true, true, clock, updates::add) }
        runCurrent()
        assertEquals(0, updates.last())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, updates.last())
        job.cancelAndJoin()
    }

    @Test
    fun formatsElapsedTime() {
        assertEquals("0:00", formatRecordingElapsed(0))
        assertEquals("0:07", formatRecordingElapsed(7))
        assertEquals("0:59", formatRecordingElapsed(59))
        assertEquals("1:00", formatRecordingElapsed(60))
        assertEquals("12:05", formatRecordingElapsed(725))
        assertEquals("0:00", formatRecordingElapsed(-1))
    }
}
