package com.negi.survey.vm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.negi.survey.net.SurveyRecordingGate
import com.negi.survey.whisper.RecorderBackend
import com.negi.survey.whisper.RecordingSpectrum
import io.mockk.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real controller admission/cleanup with a controlled permission authority and capture backend. */
@OptIn(ExperimentalCoroutinesApi::class)
class MicrophoneRecordingPermissionTest {
    @get:Rule val directory = TemporaryFolder()
    private val context = mockk<Context>()
    private val granted = AtomicBoolean(true)
    private val store = ViewModelStore()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = store }
    private val created = AtomicInteger()
    private val initialized = AtomicInteger()
    private val exports = AtomicInteger()
    private val controllers = mutableListOf<WhisperSpeechController>()
    private val recorders = mutableListOf<ControlledRecorder>()
    private val prompt = RecordingPrompt("019a0000-0000-7000-8000-000000000124", "Q07", RecordingPrompt.Kind.ORIGINAL, "Question?")

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { context.getString(any()) } returns "Recording unavailable"
        every { context.noBackupFilesDir } returns directory.root
        every { context.cacheDir } returns directory.root
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
    }

    @After fun cleanup() {
        store.clear()
        runBlocking {
            recorders.forEach { withTimeout(5_000) { it.closed.await() } }
            val field = WhisperSpeechController::class.java.getDeclaredField("cleanupScope").apply { isAccessible = true }
            controllers.forEach {
                val scope = field.get(it) as CoroutineScope
                withTimeout(5_000) { scope.coroutineContext[Job]!!.join() }
            }
        }
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun controller(factory: (() -> ControlledRecorder)? = null): WhisperSpeechController {
        val controller = WhisperSpeechController(
            context, microphonePermission = granted::get,
            recorderFactory = { _, error ->
                created.incrementAndGet()
                (factory?.invoke() ?: ControlledRecorder()).also {
                    it.error = error
                    recorders += it
                }
            },
            modelInitializer = { initialized.incrementAndGet() },
            transcriber = { _, _ -> error("Failed capture must not transcribe") },
            onVoiceExported = { exports.incrementAndGet() },
            whisperDetacher = {}, recorderSessionMutex = kotlinx.coroutines.sync.Mutex(),
            whisperSessionGate = WhisperSessionGate(),
        )
        controllers += controller
        return ViewModelProvider(owner, object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST") return controller as T
            }
        })["permission-${System.identityHashCode(controller)}", WhisperSpeechController::class.java]
    }

    @Test fun deniedAdmissionAllocatesNothingAndDoesNotChangePromptOrInitializeWhisper() {
        val controller = controller()
        granted.set(false)
        controller.startRecording(prompt)
        assertFalse(controller.isRecording.value)
        assertFalse(controller.isCaptureActive.value)
        assertFalse(controller.isTranscribing.value)
        assertNull(controller.recordingPrompt)
        assertEquals(0, created.get())
        assertEquals(0, initialized.get())
        assertEquals(0, exports.get())
        assertTrue(directory.root.listFiles().orEmpty().isEmpty())
        assertEquals("Recording unavailable", controller.errorMessage.value)
    }

    @Test fun revocationBetweenAdmissionAndStartupNeverStartsCapture() = runBlocking {
        val controller = controller { granted.set(false); ControlledRecorder() }
        controller.startRecording(prompt)
        val recorder = recorders.single()
        withTimeout(5_000) { recorder.closed.await() }
        assertEquals(0, recorder.starts.get())
        assertEquals(0, initialized.get())
        assertFalse(controller.isRecording.value)
        assertEquals(0, exports.get())
        withTimeout(5_000) { while (!SurveyRecordingGate.beginFinalization(prompt.surveyId)) yield() }
        SurveyRecordingGate.endFinalization(prompt.surveyId)
    }

    @Test fun revokedBeforeCaptureConfirmationDiscardsAndAllowsRetryOnlyAfterClose() = runBlocking {
        val controller = controller()
        controller.startRecording(prompt)
        val recorder = recorders.single()
        withTimeout(5_000) { recorder.started.await() }
        granted.set(false)
        recorder.capture()
        assertFalse(controller.isRecording.value)
        assertFalse(controller.isCaptureActive.value)
        withTimeout(5_000) { recorder.closed.await() }
        awaitNoSession(controller)
        granted.set(true)
        val replacementPrompt = prompt.copy(questionId = "Q08", text = "Another question?")
        controller.startRecording(replacementPrompt)
        val replacement = recorders.last()
        withTimeout(5_000) { replacement.started.await() }
        replacement.capture()
        recorder.capture() // Late callback from the obsolete recording.
        recorder.error(IllegalStateException("old error"))
        assertTrue(controller.isCaptureActive.value)
        assertNull(controller.errorMessage.value)
        assertEquals(replacementPrompt, controller.recordingPrompt)
        assertEquals(0, exports.get())
        controller.onMicrophonePermissionLost()
        withTimeout(5_000) { replacement.closed.await() }
        awaitNoSession(controller)
    }

    @Test fun revocationDuringCaptureSettlesSilentBackendWithoutAcceptingWav() = runBlocking {
        val controller = controller()
        controller.startRecording(prompt)
        val recorder = recorders.single()
        withTimeout(5_000) { recorder.started.await() }
        recorder.capture()
        assertTrue(controller.isCaptureActive.value)
        granted.set(false)
        controller.onMicrophonePermissionLost()
        assertFalse(controller.isRecording.value)
        assertFalse(controller.isCaptureActive.value)
        withTimeout(5_000) { recorder.closed.await() }
        awaitNoSession(controller)
        assertEquals(0, exports.get())
        assertEquals("", controller.partialText.value)
        withTimeout(5_000) { while (!SurveyRecordingGate.beginFinalization(prompt.surveyId)) yield() }
        SurveyRecordingGate.endFinalization(prompt.surveyId)
    }

    @Test fun unexpectedWriterExitSettlesZeroOrNegativeReadAndRejectsInvalidWav() = runBlocking {
        val controller = controller()
        controller.startRecording(prompt)
        val recorder = recorders.single()
        withTimeout(5_000) { recorder.started.await() }
        recorder.capture()
        recorder.level(null) // Actual Recorder writer-finally contract for both read <= 0 cases.
        assertFalse(controller.isRecording.value)
        withTimeout(5_000) { recorder.closed.await() }
        awaitNoSession(controller)
        assertEquals(0, exports.get())
        assertEquals("", controller.partialText.value)
        assertTrue(controller.errorMessage.value!!.contains("ended unexpectedly"))
    }

    @Test fun asynchronousStartupErrorCleansUpAndDoesNotPublishTranscriptOrAudio() = runBlocking {
        val controller = controller()
        controller.startRecording(prompt)
        val recorder = recorders.single()
        withTimeout(5_000) { recorder.started.await() }
        recorder.error(SecurityException("revoked at platform start"))
        withTimeout(5_000) { recorder.closed.await() }
        awaitNoSession(controller)
        assertFalse(controller.isRecording.value)
        assertFalse(controller.isTranscribing.value)
        assertEquals(0, exports.get())
        assertEquals("", controller.partialText.value)
    }

    @Test fun recorderFactoryFailureLeavesNoOwnerAndCanRetry() = runBlocking {
        var fail = true
        val controller = controller {
            if (fail) throw IllegalStateException("Recorder initialization failed")
            ControlledRecorder()
        }
        controller.startRecording(prompt)
        assertFalse(controller.isRecording.value)
        assertNull(controller.recordingPrompt)
        assertEquals(0, initialized.get())
        assertEquals(0, exports.get())
        fail = false
        controller.startRecording(prompt)
        val recorder = recorders.single()
        withTimeout(5_000) { recorder.started.await() }
        recorder.error(IllegalStateException("capture error"))
        withTimeout(5_000) { recorder.closed.await() }
        awaitNoSession(controller)
        assertFalse(controller.isRecording.value)
        assertEquals(0, exports.get())
    }

    @Test fun regrantCannotReplaceRecorderWhilePreviousCloseIsOutstanding() = runBlocking {
        val release = java.util.concurrent.CountDownLatch(1)
        val closeEntered = CompletableDeferred<Unit>()
        val controller = controller {
            ControlledRecorder().apply { beforeClose = { closeEntered.complete(Unit); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) } }
        }
        controller.startRecording(prompt)
        val recorder = recorders.single()
        withTimeout(5_000) { recorder.started.await() }
        recorder.capture()
        granted.set(false)
        controller.onMicrophonePermissionLost()
        withTimeout(5_000) { closeEntered.await() }
        try {
            granted.set(true)
            controller.startRecording(prompt.copy(questionId = "Q08"))
            assertEquals(1, created.get())
            assertFalse(controller.isRecording.value)
            assertEquals(prompt, controller.recordingPrompt)
        } finally { release.countDown() }
        withTimeout(5_000) { recorder.closed.await() }
        awaitNoSession(controller)
        controller.startRecording(prompt.copy(questionId = "Q08"))
        val next = recorders.last()
        withTimeout(5_000) { next.started.await() }
        assertEquals(2, created.get())
        // Permission is denied again: an obsolete callback must not settle the new owner.
        granted.set(false)
        recorder.capture()
        assertTrue(controller.isRecording.value)
        controller.onMicrophonePermissionLost()
        withTimeout(5_000) { next.closed.await() }
        awaitNoSession(controller)
    }

    private suspend fun awaitNoSession(controller: WhisperSpeechController) {
        val field = WhisperSpeechController::class.java.getDeclaredField("activeSession").apply { isAccessible = true }
        withTimeout(5_000) {
            while ((field.get(controller) as java.util.concurrent.atomic.AtomicReference<*>).get() != null) yield()
        }
    }

    private class ControlledRecorder : RecorderBackend {
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val starts = AtomicInteger()
        var capture: () -> Unit = {}
        var level: (RecordingSpectrum?) -> Unit = {}
        lateinit var error: (Exception) -> Unit
        var beforeClose: () -> Unit = {}
        override fun setOnCaptureStarted(callback: () -> Unit) { capture = callback }
        override fun setOnCaptureLevel(callback: (RecordingSpectrum?) -> Unit) { level = callback }
        override fun isActive() = started.isCompleted && !closed.isCompleted
        override fun startRecording(output: File, rates: IntArray) {
            starts.incrementAndGet()
            output.writeText("invalid unfinished WAV")
            started.complete(Unit)
        }
        override suspend fun stopRecording() = Unit
        override fun close() { beforeClose(); closed.complete(Unit) }
    }
}
