package com.negi.survey.vm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.whisper.RecorderBackend
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhisperSpeechControllerLifecycleTest {
    @Test
    fun normal_stop_finalizes_once_exports_original_context_and_transcribes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val recorder = FakeRecorder()
        val exports = mutableListOf<WhisperSpeechController.ExportedVoice>()
        val controller = WhisperSpeechController(
            appContext = context,
            onVoiceExported = { exports += it },
            recorderFactory = { _, _ -> recorder },
            modelInitializer = {},
            transcriber = { _, _ -> Result.success("recorded answer") }
        )

        controller.updateContext("survey-a", "Q8")
        controller.startRecording()
        waitUntil { recorder.started }
        controller.stopRecording()
        waitUntil { !controller.isTranscribing.value && controller.partialText.value == "recorded answer" }

        assertFalse(controller.isRecording.value)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, exports.size)
        assertEquals("survey-a", exports.single().surveyId)
        assertEquals("Q8", exports.single().questionId)
        assertTrue(exports.single().byteSize > 44L)
    }

    @Test
    fun stop_in_progress_rejects_a_replacement_recording() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val recorder = FakeRecorder(holdStop = true)
        val controller = WhisperSpeechController(
            appContext = context,
            recorderFactory = { _, _ -> recorder },
            modelInitializer = {},
            transcriber = { _, _ -> Result.success("unused") }
        )

        controller.startRecording()
        waitUntil { recorder.started }
        controller.stopRecording()
        waitUntil { recorder.stopEntered.isCompleted }
        controller.startRecording()

        assertFalse(controller.isRecording.value)
        assertEquals(1, recorder.startCalls)
        assertEquals("Previous recording is still finalizing", controller.errorMessage.value)

        recorder.releaseStop.complete(Unit)
        waitUntil { !recorder.isActive() }
    }

    @Test
    fun stale_recorder_error_cannot_mutate_a_replacement_session() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val recorders = mutableListOf<CallbackRecorder>()
        val exports = mutableListOf<WhisperSpeechController.ExportedVoice>()
        val controller = WhisperSpeechController(
            appContext = context,
            onVoiceExported = { exports += it },
            recorderFactory = { _, onError ->
                CallbackRecorder(onError).also { recorders += it }
            },
            modelInitializer = {},
            transcriber = { _, _ -> Result.success("recorded answer") }
        )

        controller.updateContext("survey-old", "Q-old")
        controller.startRecording()
        waitUntil { recorders.singleOrNull()?.started == true }
        controller.stopRecording()
        waitUntil { !controller.isTranscribing.value && controller.partialText.value == "recorded answer" }

        controller.updateContext("survey-new", "Q-new")
        controller.startRecording()
        waitUntil { recorders.size == 2 && recorders[1].started }

        recorders[0].emitError(IllegalStateException("old recorder failed late"))

        assertTrue(controller.isRecording.value)
        assertEquals(null, controller.errorMessage.value)
        assertEquals("", controller.partialText.value)

        controller.stopRecording()
        waitUntil { !controller.isTranscribing.value && controller.partialText.value == "recorded answer" }

        assertEquals(
            listOf("survey-old" to "Q-old", "survey-new" to "Q-new"),
            exports.map { it.surveyId to it.questionId }
        )
    }

    @Test
    fun stop_timeout_keeps_session_owned_until_recorder_close_returns() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val recorder = TimeoutRecorder()
        val controller = WhisperSpeechController(
            appContext = context,
            recorderFactory = { _, _ -> recorder },
            modelInitializer = {},
            transcriber = { _, _ -> Result.success("unused") },
            recorderStopTimeoutMs = 50L
        )

        controller.startRecording()
        waitUntil { recorder.started }
        controller.stopRecording()
        waitUntil { recorder.stopEntered.isCompleted && recorder.closeEntered.isCompleted }

        controller.startRecording()

        assertEquals(1, recorder.startCalls)
        assertFalse(controller.isRecording.value)
        assertEquals("Previous recording is still finalizing", controller.errorMessage.value)

        recorder.releaseClose.complete(Unit)
        waitUntil { recorder.closeFinished.isCompleted }
    }

    @Test
    fun stop_timeout_allows_a_new_recording_after_recorder_close_completes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val timedOutRecorder = TimeoutRecorder()
        val replacementRecorder = FakeRecorder()
        var factoryCalls = 0
        val controller = WhisperSpeechController(
            appContext = context,
            recorderFactory = { _, _ ->
                if (factoryCalls++ == 0) timedOutRecorder else replacementRecorder
            },
            modelInitializer = {},
            transcriber = { _, _ -> Result.success("recovered answer") },
            recorderStopTimeoutMs = 50L
        )

        controller.startRecording()
        waitUntil { timedOutRecorder.started }
        controller.stopRecording()
        waitUntil { timedOutRecorder.closeEntered.isCompleted }

        timedOutRecorder.releaseClose.complete(Unit)
        waitUntil {
            timedOutRecorder.closeFinished.isCompleted && !hasActiveSession(controller)
        }

        controller.startRecording()
        waitUntil { replacementRecorder.started }
        controller.stopRecording()
        waitUntil {
            !controller.isTranscribing.value && controller.partialText.value == "recovered answer"
        }

        assertEquals(2, factoryCalls)
        assertEquals(1, replacementRecorder.stopCalls)
        assertEquals(null, controller.errorMessage.value)
    }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            delay(50)
        }
        throw AssertionError("Condition was not met")
    }

    private fun hasActiveSession(controller: WhisperSpeechController): Boolean {
        val field = WhisperSpeechController::class.java.getDeclaredField("activeSession")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val sessionRef = field.get(controller) as AtomicReference<Any?>
        return sessionRef.get() != null
    }

    private class FakeRecorder(private val holdStop: Boolean = false) : RecorderBackend {
        var started = false
        var startCalls = 0
        var stopCalls = 0
        val stopEntered = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        private var output: File? = null

        override fun isActive(): Boolean = started

        override fun startRecording(output: File, rates: IntArray) {
            this.output = output
            startCalls += 1
            started = true
        }

        override suspend fun stopRecording() {
            stopCalls += 1
            stopEntered.complete(Unit)
            if (holdStop) releaseStop.await()
            started = false
            FileOutputStream(requireNotNull(output)).use { stream ->
                stream.write(ByteArray(64))
            }
        }

        override fun close() = Unit
    }

    private class CallbackRecorder(
        private val onError: (Exception) -> Unit
    ) : RecorderBackend {
        var started = false
        private var output: File? = null

        override fun isActive(): Boolean = started

        override fun startRecording(output: File, rates: IntArray) {
            this.output = output
            started = true
        }

        override suspend fun stopRecording() {
            started = false
            FileOutputStream(requireNotNull(output)).use { stream ->
                stream.write(ByteArray(64))
            }
        }

        fun emitError(error: Exception) = onError(error)

        override fun close() = Unit
    }

    private class TimeoutRecorder : RecorderBackend {
        var started = false
        var startCalls = 0
        val stopEntered = CompletableDeferred<Unit>()
        val closeEntered = CompletableDeferred<Unit>()
        val releaseClose = CompletableDeferred<Unit>()
        val closeFinished = CompletableDeferred<Unit>()

        override fun isActive(): Boolean = started

        override fun startRecording(output: File, rates: IntArray) {
            startCalls += 1
            started = true
        }

        override suspend fun stopRecording() {
            stopEntered.complete(Unit)
            CompletableDeferred<Unit>().await()
        }

        override fun close() {
            closeEntered.complete(Unit)
            runBlocking { releaseClose.await() }
            started = false
            closeFinished.complete(Unit)
        }
    }
}
