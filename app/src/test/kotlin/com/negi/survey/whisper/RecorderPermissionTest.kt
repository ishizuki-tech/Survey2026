package com.negi.survey.whisper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.mockk.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercise the real Recorder permission boundary without constructing AudioRecord. */
class RecorderPermissionTest {
    @get:Rule val directory = TemporaryFolder()
    private val context = mockk<Context>()
    @Before fun setup() {
        val packages = mockk<PackageManager>()
        every { context.packageManager } returns packages
        every { packages.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) } returns true
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) } returns PackageManager.PERMISSION_DENIED
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }
    @After fun cleanup() { unmockkAll() }
    @Test fun deniedPermissionRejectsCaptureBeforeCreatingFilesAndReturnsIdle() = runBlocking {
        val error = CompletableDeferred<Exception>()
        val recorder = Recorder(context, { error("Denied capture must not allocate PCM") }) { error.complete(it) }
        val output = File(directory.root, "denied.wav")
        try {
            recorder.startRecording(output)
            assertTrue(withTimeout(5_000) { error.await() } is SecurityException)
            withTimeout(5_000) { while (recorder.isActive()) yield() }
            assertFalse(output.exists())
            assertTrue(directory.root.listFiles().orEmpty().isEmpty())
            verify(exactly = 0) { context.cacheDir }
        } finally { recorder.close() }
    }
}
