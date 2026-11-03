package com.negi.survey.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingTimerFormatTest {

    @Test
    fun formats_elapsed_recording_time_as_minutes_and_seconds() {
        assertEquals("0:00", formatRecordingElapsed(0))
        assertEquals("0:07", formatRecordingElapsed(7))
        assertEquals("0:59", formatRecordingElapsed(59))
        assertEquals("1:00", formatRecordingElapsed(60))
        assertEquals("1:23", formatRecordingElapsed(83))
        assertEquals("12:05", formatRecordingElapsed(725))
    }

    @Test
    fun negative_elapsed_time_shows_zero() {
        assertEquals("0:00", formatRecordingElapsed(-3))
    }
}
