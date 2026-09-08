package com.negi.survey.whisper

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real-device smoke coverage for the microphone and AudioRecord lifecycle. */
@RunWith(AndroidJUnit4::class)
class RecorderInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val files = mutableListOf<File>()

    @Before
    fun grantMicrophonePermission() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO
        )
    }

    @Test
    fun microphone_record_stop_and_rerecord_finalizes_non_empty_wavs() = runBlocking {
        val errors = CopyOnWriteArrayList<Exception>()
        val recorder = Recorder(context) { errors += it }

        try {
            val first = recordShortClip(recorder)
            val second = recordShortClip(recorder)

            assertTrue("First WAV was not finalized", first.exists() && first.length() > WAV_HEADER_BYTES)
            assertTrue("Second WAV was not finalized", second.exists() && second.length() > WAV_HEADER_BYTES)
            assertFalse("Recorder remained active after the second stop", recorder.isActive())
            assertTrue("Recorder reported errors: $errors", errors.isEmpty())
        } finally {
            recorder.close()
        }
    }

    private suspend fun recordShortClip(recorder: Recorder): File {
        val output = File.createTempFile("recorder-device-smoke-", ".wav", context.cacheDir)
        files += output

        recorder.startRecording(output)
        withTimeout(ACTIVE_TIMEOUT_MS) {
            while (!recorder.isActive()) delay(POLL_INTERVAL_MS)
        }

        // This is capture duration, not a synchronization workaround.
        delay(CAPTURE_DURATION_MS)
        withTimeout(STOP_TIMEOUT_MS) { recorder.stopRecording() }
        return output
    }

    @After
    fun deleteTemporaryFiles() {
        files.forEach { file -> file.delete() }
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44L
        const val ACTIVE_TIMEOUT_MS = 3_000L
        const val STOP_TIMEOUT_MS = 6_000L
        const val CAPTURE_DURATION_MS = 500L
        const val POLL_INTERVAL_MS = 20L
    }
}
