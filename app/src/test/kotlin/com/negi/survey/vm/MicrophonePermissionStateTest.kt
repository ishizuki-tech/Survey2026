package com.negi.survey.vm

import com.negi.survey.MicrophonePermissionState
import org.junit.Assert.*
import org.junit.Test

class MicrophonePermissionStateTest {
    @Test fun refreshUsesAndroidAuthorityWithoutRequestingOrRecording() {
        var androidGranted = false
        var requests = 0
        var settings = 0
        val state = MicrophonePermissionState(false, { androidGranted }, { requests++ }, { settings++ })
        repeat(3) { assertFalse(state.refresh()) }
        assertEquals(0, requests)
        assertEquals(0, settings)
        state.request()
        assertEquals(1, requests)
        assertFalse(state.granted)
        androidGranted = true
        assertTrue(state.refresh())
        androidGranted = false
        assertFalse(state.refresh())
        state.openSettings()
        assertEquals(1, settings)
        assertEquals(1, requests)
    }
}
