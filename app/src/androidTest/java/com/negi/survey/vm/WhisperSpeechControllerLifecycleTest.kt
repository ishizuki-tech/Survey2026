package com.negi.survey.vm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.whisper.RecorderBackend
import com.negi.survey.runtime.HeavyRuntimeHandoff
import com.negi.survey.runtime.NoOpHeavyRuntimeHandoff
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
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
        val lockedWhenCaptureEnded = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            controller.isRecording.first { !it }
            controller.isTranscribing.value
        }
        controller.stopRecording()
        assertTrue(withTimeout(5000) { lockedWhenCaptureEnded.await() })
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
        assertEquals(null, controller.errorMessage.value)

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
        withTimeout(5000) { controller.isTranscribing.first { !it } }
        assertTrue(hasActiveSession(controller))

        controller.startRecording()

        assertEquals(1, recorder.startCalls)
        assertFalse(controller.isRecording.value)
        assertEquals("Recorder stop timeout (AudioRecord thread stuck)", controller.errorMessage.value)

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

    @Test
    fun recording_starts_while_model_initialization_is_blocked_and_stop_waits_for_it() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val recorder = FakeRecorder()
        val initEntered = CompletableDeferred<Unit>()
        val finishInit = CompletableDeferred<Unit>()
        val transcribed = CompletableDeferred<String>()
        var initCalls = 0
        val controller = WhisperSpeechController(
            appContext = context,
            languageCode = "sw",
            recorderFactory = { _, _ -> recorder },
            modelInitializer = { initCalls++; initEntered.complete(Unit); finishInit.await() },
            transcriber = { _, language -> transcribed.complete(language); Result.success("answer") }
        )
        controller.startRecording()
        withTimeout(5000) { initEntered.await() }
        assertTrue(recorder.started)
        assertTrue(controller.isCaptureActive.value)
        controller.startRecording()
        assertEquals(1, recorder.startCalls)
        controller.stopRecording()
        withTimeout(5000) { recorder.stopEntered.await() }
        assertFalse(transcribed.isCompleted)
        finishInit.complete(Unit)
        assertEquals("sw", withTimeout(5000) { transcribed.await() })
        assertEquals(1, initCalls)
    }

    @Test
    fun failed_initialization_preserves_export_and_never_transcribes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val recorder = FakeRecorder()
        val initEntered = CompletableDeferred<Unit>()
        val finishInit = CompletableDeferred<Unit>()
        val exported = CompletableDeferred<Unit>()
        var transcribed = false
        val controller = WhisperSpeechController(
            appContext = context,
            onVoiceExported = { exported.complete(Unit) },
            recorderFactory = { _, _ -> recorder },
            modelInitializer = { initEntered.complete(Unit); finishInit.await(); error("init failed") },
            transcriber = { _, _ -> transcribed = true; Result.success("unused") }
        )
        controller.startRecording()
        withTimeout(5000) { initEntered.await() }
        controller.stopRecording()
        withTimeout(5000) { exported.await() }
        finishInit.complete(Unit)
        withTimeout(5000) { controller.errorMessage.first { it == "init failed" } }
        assertFalse(transcribed)
    }

    @Test
    fun immediate_stop_waits_for_start_and_finalizes_once() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val recorder = FakeRecorder()
        val transcribed = CompletableDeferred<Unit>()
        val controller = WhisperSpeechController(
            appContext = context,
            recorderFactory = { _, _ -> recorder },
            modelInitializer = {},
            transcriber = { _, _ -> transcribed.complete(Unit); Result.success("answer") }
        )
        controller.startRecording()
        controller.stopRecording()
        withTimeout(5000) { transcribed.await() }
        assertEquals(1, recorder.startCalls)
        assertEquals(1, recorder.stopCalls)
        assertFalse(controller.isCaptureActive.value)
    }

    @Test
    fun recorder_failure_waits_for_inflight_initialization_before_release() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val entered = CompletableDeferred<Unit>()
        val allowInitToExit = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var releaseCalls = 0
        lateinit var recorder: CallbackRecorder
        val handoff = object : HeavyRuntimeHandoff by NoOpHeavyRuntimeHandoff {
            override suspend fun releaseWhisper() { releaseCalls++; released.complete(Unit) }
        }
        val controller = WhisperSpeechController(
            appContext = context,
            heavyRuntimeHandoff = handoff,
            recorderFactory = { _, onError -> CallbackRecorder(onError).also { recorder = it } },
            modelInitializer = {
                entered.complete(Unit)
                withContext(NonCancellable) { allowInitToExit.await() }
            }
        )
        controller.startRecording()
        withTimeout(5000) { entered.await() }
        recorder.emitError(IllegalStateException("recorder failed"))
        assertFalse(released.isCompleted)
        allowInitToExit.complete(Unit)
        withTimeout(5000) { released.await() }
        assertEquals(1, releaseCalls)
        assertEquals("recorder failed", controller.errorMessage.value)
    }

    @Test
    fun replacement_records_while_old_native_initialization_finishes_before_release() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val oldEntered = CompletableDeferred<Unit>()
        val allowOldExit = CompletableDeferred<Unit>()
        val oldReleased = CompletableDeferred<Unit>()
        val newEntered = CompletableDeferred<Unit>()
        val bothReleased = CompletableDeferred<Unit>()
        var releases = 0
        val handoff = object : HeavyRuntimeHandoff by NoOpHeavyRuntimeHandoff {
            override suspend fun releaseWhisper() {
                releases++
                if (releases == 1) oldReleased.complete(Unit) else bothReleased.complete(Unit)
            }
        }
        val oldRecorder = FakeRecorder()
        val recorderGate = Mutex()
        val old = WhisperSpeechController(
            appContext = context,
            heavyRuntimeHandoff = handoff,
            recorderFactory = { _, _ -> oldRecorder },
            recorderSessionMutex = recorderGate,
            modelInitializer = {
                oldEntered.complete(Unit)
                withContext(NonCancellable) { allowOldExit.await() }
            }
        )
        old.startRecording()
        withTimeout(5000) { oldEntered.await() }
        WhisperSpeechController::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(old)
        withTimeout(5000) { oldRecorder.closed.await(); recorderGate.lock() }
        recorderGate.unlock()

        val recorder = FakeRecorder()
        val replacement = WhisperSpeechController(
            appContext = context,
            heavyRuntimeHandoff = handoff,
            recorderFactory = { _, _ -> recorder },
            recorderSessionMutex = recorderGate,
            modelInitializer = { assertTrue(oldReleased.isCompleted); newEntered.complete(Unit) },
            transcriber = { _, _ -> Result.success("answer") }
        )
        replacement.startRecording()
        withTimeout(5000) { recorder.startedSignal.await() }
        assertFalse(newEntered.isCompleted)
        assertFalse(oldReleased.isCompleted)
        allowOldExit.complete(Unit)
        withTimeout(5000) { newEntered.await() }
        replacement.stopRecording()
        withTimeout(5000) { bothReleased.await() }
        assertEquals(2, releases)
    }

    @Test
    fun readiness_is_not_reported_until_recorder_confirms_capture(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val recorder = FakeRecorder(autoReady = false)
        val controller = WhisperSpeechController(
            appContext = context,
            recorderFactory = { _, _ -> recorder },
            modelInitializer = {},
            transcriber = { _, _ -> Result.success("answer") }
        )
        controller.startRecording()
        withTimeout(5000) { recorder.startedSignal.await() }
        assertTrue(controller.isRecording.value)
        assertFalse(controller.isCaptureActive.value)
        recorder.reportCapture()
        assertTrue(controller.isCaptureActive.value)
        controller.stopRecording()
        assertFalse(controller.isCaptureActive.value)
        recorder.reportCapture()
        assertFalse(controller.isCaptureActive.value)
        withTimeout(5000) { controller.partialText.first { it == "answer" } }
    }

    @Test
    fun replacement_captures_while_old_detach_holds_whisper_gate(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val detachEntered = CompletableDeferred<Unit>()
        val allowDetach = CompletableDeferred<Unit>()
        val initEntered = CompletableDeferred<Unit>()
        val old = WhisperSpeechController(appContext = context, whisperDetacher = {
            detachEntered.complete(Unit)
            allowDetach.await()
        })
        WhisperSpeechController::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(old)
        withTimeout(5000) { detachEntered.await() }
        val recorder = FakeRecorder()
        val replacement = WhisperSpeechController(
            appContext = context,
            recorderFactory = { _, _ -> recorder },
            modelInitializer = { initEntered.complete(Unit) },
            transcriber = { _, _ -> Result.success("answer") }
        )
        replacement.startRecording()
        withTimeout(5000) { recorder.startedSignal.await() }
        assertFalse(initEntered.isCompleted)
        allowDetach.complete(Unit)
        withTimeout(5000) { initEntered.await() }
        replacement.stopRecording()
        withTimeout(5000) { replacement.partialText.first { it == "answer" } }
    }

    @Test
    fun recorder_close_failure_keeps_session_reserved() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val recorder = ClosingRecorder(fail = true)
        val recorderGate = Mutex()
        val controller = WhisperSpeechController(
            appContext = context,
            recorderFactory = { _, _ -> recorder },
            recorderSessionMutex = recorderGate,
            modelInitializer = {},
            transcriber = { _, _ -> Result.success("answer") }
        )
        controller.startRecording()
        withTimeout(5000) { recorder.started.await() }
        controller.stopRecording()
        withTimeout(5000) { controller.errorMessage.first { it == "Recorder cleanup failed; restart the app before recording again" } }
        withTimeout(5000) { controller.isTranscribing.first { !it } }
        controller.startRecording()
        assertEquals(1, recorder.closeCalls.get())
        assertTrue(hasActiveSession(controller))
        assertEquals("Recorder cleanup failed; restart the app before recording again", controller.errorMessage.value)
        WhisperSpeechController::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(controller)
        val replacementRecorder = FakeRecorder()
        val replacement = WhisperSpeechController(
            appContext = context,
            recorderFactory = { _, _ -> replacementRecorder },
            recorderSessionMutex = recorderGate,
            modelInitializer = {}
        )
        replacement.startRecording()
        withTimeout(5000) { replacement.errorMessage.first { it?.contains("Previous recorder") == true } }
        assertFalse(replacementRecorder.started)
    }

    @Test
    fun delayed_recorder_close_is_shared_with_cleared_controller() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val recorder = ClosingRecorder(fail = false)
        val controller = WhisperSpeechController(
            appContext = context,
            recorderFactory = { _, _ -> recorder },
            modelInitializer = {},
            transcriber = { _, _ -> Result.success("answer") }
        )
        controller.startRecording()
        withTimeout(5000) { recorder.started.await() }
        controller.stopRecording()
        withTimeout(5000) { recorder.closeEntered.await() }
        WhisperSpeechController::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(controller)
        assertEquals(1, recorder.closeCalls.get())
        recorder.allowClose.complete(Unit)
        withTimeout(5000) { recorder.closeReturned.await() }
        assertEquals(1, recorder.closeCalls.get())
    }

    @Test
    fun late_successful_close_releases_reservation_for_next_recording() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val recorder = ClosingRecorder(fail = false)
        val gate = Mutex()
        val controller = WhisperSpeechController(
            appContext = context,
            recorderFactory = { _, _ -> recorder },
            recorderSessionMutex = gate,
            recorderCloseTimeoutMs = 50L,
            modelInitializer = {},
            transcriber = { _, _ -> Result.success("answer") }
        )
        controller.startRecording()
        withTimeout(5000) { recorder.started.await() }
        controller.stopRecording()
        withTimeout(5000) { recorder.closeEntered.await() }
        withTimeout(5000) { controller.errorMessage.first { it == "Recorder close is still in progress" } }
        assertFalse(gate.tryLock())
        recorder.allowClose.complete(Unit)
        withTimeout(5000) { gate.lock() }
        gate.unlock()
        withTimeout(5000) { controller.errorMessage.first { it == null } }
        assertFalse(controller.isTranscribing.value)
        val nextRecorder = FakeRecorder()
        val next = WhisperSpeechController(
            appContext = context,
            recorderFactory = { _, _ -> nextRecorder },
            recorderSessionMutex = gate,
            modelInitializer = {},
            transcriber = { _, _ -> Result.success("next") }
        )
        next.startRecording()
        withTimeout(5000) { nextRecorder.startedSignal.await() }
        next.stopRecording()
        withTimeout(5000) { next.partialText.first { it == "next" } }
        assertEquals(1, recorder.closeCalls.get())
    }

    @Test
    fun failed_whisper_release_blocks_replacement_initialization_but_not_capture() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val gate = WhisperSessionGate()
        val recorderGate = Mutex()
        val firstRecorder = FakeRecorder()
        var releaseCalls = 0
        val handoff = object : HeavyRuntimeHandoff by NoOpHeavyRuntimeHandoff {
            override suspend fun releaseWhisper() { releaseCalls++; error("native release failed") }
        }
        val first = WhisperSpeechController(
            appContext = context,
            recorderFactory = { _, _ -> firstRecorder },
            recorderSessionMutex = recorderGate,
            whisperSessionGate = gate,
            heavyRuntimeHandoff = handoff,
            modelInitializer = {},
            transcriber = { _, _ -> Result.success("first") }
        )
        first.startRecording()
        withTimeout(5000) { firstRecorder.startedSignal.await() }
        first.stopRecording()
        withTimeout(5000) { first.errorMessage.first { it == "Speech recognition cleanup failed; restart required" } }
        withTimeout(5000) { recorderGate.lock() }
        recorderGate.unlock()

        val nextRecorder = FakeRecorder()
        var nextInitCalls = 0
        val next = WhisperSpeechController(
            appContext = context,
            recorderFactory = { _, _ -> nextRecorder },
            recorderSessionMutex = recorderGate,
            whisperSessionGate = gate,
            heavyRuntimeHandoff = handoff,
            modelInitializer = { nextInitCalls++ },
            transcriber = { _, _ -> Result.success("unexpected") }
        )
        next.startRecording()
        withTimeout(5000) { nextRecorder.startedSignal.await() }
        next.stopRecording()
        withTimeout(5000) { next.errorMessage.first { it?.contains("restart the app") == true } }
        assertEquals(0, nextInitCalls)
        assertEquals(1, releaseCalls)
    }

    private class ClosingRecorder(private val fail: Boolean) : RecorderBackend {
        val started = CompletableDeferred<Unit>()
        val closeEntered = CompletableDeferred<Unit>()
        val allowClose = CompletableDeferred<Unit>()
        val closeReturned = CompletableDeferred<Unit>()
        val closeCalls = AtomicInteger()
        private var output: File? = null
        override fun isActive() = true
        override fun startRecording(output: File, rates: IntArray) {
            this.output = output
            started.complete(Unit)
        }
        override suspend fun stopRecording() {
            FileOutputStream(requireNotNull(output)).use { it.write(ByteArray(64)) }
        }
        override fun close() {
            closeCalls.incrementAndGet()
            closeEntered.complete(Unit)
            if (fail) throw IllegalStateException("close failed")
            runBlocking { allowClose.await() }
            closeReturned.complete(Unit)
        }
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

    private class FakeRecorder(private val holdStop: Boolean = false, private val autoReady: Boolean = true) : RecorderBackend {
        var started = false
        var startCalls = 0
        var stopCalls = 0
        val stopEntered = CompletableDeferred<Unit>()
        val startedSignal = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        private var output: File? = null
        private var onCaptureStarted: () -> Unit = {}

        override fun setOnCaptureStarted(callback: () -> Unit) { onCaptureStarted = callback }

        override fun isActive(): Boolean = started

        override fun startRecording(output: File, rates: IntArray) {
            this.output = output
            startCalls += 1
            started = true
            if (autoReady) onCaptureStarted()
            startedSignal.complete(Unit)
        }

        fun reportCapture() = onCaptureStarted()

        override suspend fun stopRecording() {
            stopCalls += 1
            stopEntered.complete(Unit)
            if (holdStop) releaseStop.await()
            started = false
            FileOutputStream(requireNotNull(output)).use { stream ->
                stream.write(ByteArray(64))
            }
        }

        override fun close() { closed.complete(Unit) }
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
