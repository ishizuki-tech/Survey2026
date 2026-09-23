package com.negi.survey.vm

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadStatusTest {

    @Test
    fun stateCarriesOnlyThePrecomputedPrivacySafeDeviceTag() {
        val status = UploadStatus(
            deviceTag = "Pixel_9a_A13F82C4D9E1",
            uploadedCount = 2,
            pendingCount = 1
        )

        assertEquals("Pixel_9a_A13F82C4D9E1", status.deviceTag)
        assertEquals(2, status.uploadedCount)
        assertEquals(1, status.pendingCount)
    }
}
