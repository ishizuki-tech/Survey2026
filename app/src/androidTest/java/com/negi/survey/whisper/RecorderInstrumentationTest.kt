package com.negi.survey.whisper

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
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

        val levelCount = AtomicInteger()
        val invalidCount = AtomicInteger()
        val writerEnds = AtomicInteger()
        recorder.setOnCaptureLevel { level ->
            when {
                level == null -> writerEnds.incrementAndGet()
                (0 until RecordingSpectrum.BAND_COUNT).all { level[it] in 0f..1f } -> levelCount.incrementAndGet()
                else -> invalidCount.incrementAndGet()
            }
        }
        recorder.startRecording(output)
        withTimeout(ACTIVE_TIMEOUT_MS) {
            while (levelCount.get() < 15) delay(POLL_INTERVAL_MS)
        }
        withTimeout(STOP_TIMEOUT_MS) { recorder.stopRecording() }
        assertTrue("Capture levels were out of range", invalidCount.get() == 0)
        assertTrue("Meter did not terminate with the writer", writerEnds.get() == 1)
        val decodedSampleRate = 16_000
        val pcm = decodeWaveFile(output, targetSampleRate = decodedSampleRate)
        assertTrue("Recorded WAV could not be decoded", pcm.isNotEmpty())
        val readingsPerSecond = levelCount.get() * decodedSampleRate.toFloat() / pcm.size
        assertTrue("Unexpected meter cadence: $readingsPerSecond readings/s", readingsPerSecond in 26f..31f)
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
        const val POLL_INTERVAL_MS = 20L
    }
}
