package com.negi.survey.vm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.*
import com.negi.survey.net.*
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.utils.DeviceUploadTagProvider
import com.negi.survey.utils.newTimeOrderedSurveyId
import com.negi.survey.whisper.RecorderBackend
import com.negi.survey.whisper.RecordingSpectrum
import io.mockk.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

/** Valid WAVs and real V2 staging/outcome persistence, with deterministic publication barriers. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordingPublicationRaceTest {
    @get:Rule val temp = TemporaryFolder()
    private val context = mockk<Context>()
    private val store = ViewModelStore()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = store }
    private val controllers = mutableListOf<WhisperSpeechController>()
    private val recorders = mutableListOf<WavRecorder>()
    private val exports = java.util.concurrent.CopyOnWriteArrayList<WhisperSpeechController.ExportedVoice>()
    private val prompt = RecordingPrompt(newTimeOrderedSurveyId(), "Q07", RecordingPrompt.Kind.ORIGINAL, "Question?")
    private val artifactStore get() = V2ArtifactStore(context)

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { context.noBackupFilesDir } returns temp.root
        every { context.cacheDir } returns temp.root
        every { context.getString(any()) } returns "Recording unavailable"
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        mockkObject(DeviceUploadTagProvider)
        every { DeviceUploadTagProvider.from(any()) } returns DeviceUploadTag("publication-test")
    }

    @After fun cleanup() = runBlocking {
        store.clear()
        val field = WhisperSpeechController::class.java.getDeclaredField("cleanupScope").apply { isAccessible = true }
        controllers.forEach { withTimeout(5_000) { (field.get(it) as CoroutineScope).coroutineContext[Job]!!.join() } }
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun controller(hooks: WhisperSpeechController.PublicationHooks? = null, beforeClose: () -> Unit = {}): WhisperSpeechController {
        val vm = WhisperSpeechController(context, microphonePermission = { true },
            publicationHooks = hooks,
            recorderFactory = { _, onError -> WavRecorder(onError, beforeClose).also { recorders += it } },
            modelInitializer = {}, transcriber = { _, _ -> Result.success("accepted transcript") },
            onVoiceExported = { exports += it }, whisperDetacher = {},
            recorderSessionMutex = kotlinx.coroutines.sync.Mutex(), whisperSessionGate = WhisperSessionGate())
        controllers += vm
        return ViewModelProvider(owner, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST") return vm as T
            }
        })["controller-${controllers.size}", WhisperSpeechController::class.java]
    }

    private suspend fun startAndStop(vm: WhisperSpeechController, p: RecordingPrompt = prompt): WavRecorder {
        vm.startRecording(p)
        val recorder = recorders.last()
        withTimeout(5_000) { recorder.started.await() }
        vm.stopRecording()
        return recorder
    }

    private suspend fun settled(vm: WhisperSpeechController) {
        val field = WhisperSpeechController::class.java.getDeclaredField("activeSession").apply { isAccessible = true }
        withTimeout(5_000) {
            while ((field.get(vm) as AtomicReference<*>).get() != null) yield()
            // Owner release and the final state publication are separate worker writes.
            vm.isTranscribing.first { !it }
        }
        assertFalse(vm.isRecording.value)
        assertFalse(vm.isTranscribing.value)
    }

    private fun inventory() = SurveyRecordings(artifactStore).inventory(
        artifactStore.paths(context, prompt.surveyId), V2ArtifactStore.profile)

    @Test fun failureBetweenStagingAndExportWinsAndLeavesOnlyInterruptedAudio() = runBlocking {
        val barrier = Barrier()
        val vm = controller(object : WhisperSpeechController.PublicationHooks {
            override fun afterAudioStaged() = barrier.block()
        })
        startAndStop(vm)
        withTimeout(5_000) { barrier.entered.await() }
        try {
            vm.onMicrophonePermissionLost()
            assertTrue(exports.isEmpty())
            assertEquals("", vm.partialText.value)
        } finally { barrier.release.countDown() }
        settled(vm)
        assertTrue(exports.isEmpty())
        assertEquals(TranscriptionOutcome.Status.INTERRUPTED, inventory().single().outcome.status)
        assertFalse(File(artifactStore.voiceEntries(prompt.surveyId).single().directory, SurveyRecordings.OUTCOME).exists())
    }

    @Test fun failureImmediatelyBeforeTerminalPublicationRejectsValidTranscript() = runBlocking {
        val barrier = Barrier()
        val vm = controller(object : WhisperSpeechController.PublicationHooks {
            override fun beforeDecision() = barrier.block()
        })
        startAndStop(vm)
        withTimeout(5_000) { barrier.entered.await() }
        try { vm.onMicrophonePermissionLost() } finally { barrier.release.countDown() }
        settled(vm)
        assertEquals("", vm.partialText.value)
        assertTrue(exports.isEmpty())
        assertEquals(TranscriptionOutcome.Status.INTERRUPTED, inventory().single().outcome.status)
    }

    @Test fun permissionLossDuringBlockedCommittedPersistenceCannotRetroactivelyFailResult() = runBlocking {
        val barrier = Barrier()
        val vm = controller(object : WhisperSpeechController.PublicationHooks {
            override fun persistOutcome(write: () -> Unit) { barrier.block(); write() }
        })
        startAndStop(vm)
        withTimeout(5_000) { barrier.entered.await() }
        try {
            // The accepted result's CAS precedes persistence; this event must lose.
            vm.onMicrophonePermissionLost()
            assertNull(vm.errorMessage.value)
            assertTrue(exports.isEmpty())
            assertEquals("", vm.partialText.value)
            assertFalse(File(artifactStore.voiceEntries(prompt.surveyId).single().directory, SurveyRecordings.OUTCOME).exists())
        } finally { barrier.release.countDown() }
        settled(vm)
        assertEquals("accepted transcript", vm.partialText.value)
        assertEquals(1, exports.size)
        assertEquals(TranscriptionOutcome.Status.SUCCESS, inventory().single().outcome.status)
    }

    @Test fun permissionLossImmediatelyBeforeCommittedTranscriptDoesNotReverseAcceptance() = runBlocking {
        val barrier = Barrier()
        val vm = controller(object : WhisperSpeechController.PublicationHooks {
            override fun beforeTranscriptPublication() = barrier.block()
        })
        startAndStop(vm)
        withTimeout(5_000) { barrier.entered.await() }
        try {
            vm.onMicrophonePermissionLost()
            assertNull(vm.errorMessage.value)
            assertEquals("", vm.partialText.value)
        } finally { barrier.release.countDown() }
        settled(vm)
        assertEquals("accepted transcript", vm.partialText.value)
        assertEquals(TranscriptionOutcome.Status.SUCCESS, inventory().single().outcome.status)
    }

    @Test fun cancellationAfterFailureKeepsOwnerUntilCleanupThenIgnoresLateCallbacks() = runBlocking {
        val barrier = Barrier()
        var first = true
        val vm = controller(object : WhisperSpeechController.PublicationHooks {
            override fun beforeDecision() { if (first) { first = false; barrier.block() } }
        })
        val old = startAndStop(vm)
        withTimeout(5_000) { barrier.entered.await() }
        try {
            vm.onMicrophonePermissionLost()
            vm.startRecording(prompt.copy(questionId = "Q08"))
            assertEquals(1, recorders.size)
            assertTrue(exports.isEmpty())
        } finally { barrier.release.countDown() }
        settled(vm)
        assertEquals("", vm.partialText.value)
        vm.startRecording(prompt.copy(questionId = "Q08", text = "Replacement?"))
        val next = recorders.last()
        withTimeout(5_000) { next.started.await() }
        old.capture()
        old.error(IllegalStateException("obsolete"))
        assertNull(vm.errorMessage.value)
        assertTrue(vm.isRecording.value)
        vm.stopRecording()
        settled(vm)
        assertEquals("Q08", exports.single().questionId)
        assertEquals("accepted transcript", vm.partialText.value)
        assertEquals(listOf(TranscriptionOutcome.Status.INTERRUPTED, TranscriptionOutcome.Status.SUCCESS).sortedBy { it.name },
            inventory().map { it.outcome.status }.sortedBy { it.name })
    }

    @Test fun normalSuccessPersistsExactTranscriptAndExportsOnce() = runBlocking {
        val vm = controller()
        startAndStop(vm)
        settled(vm)
        assertEquals("accepted transcript", vm.partialText.value)
        assertEquals(1, exports.size)
        val saved = inventory().single()
        assertEquals(prompt, saved.identity.prompt)
        assertEquals("accepted transcript", saved.outcome.transcript)
        assertTrue(artifactStore.voiceEntries(prompt.surveyId).single().matchesPayload())
    }

    @Test fun settledWaitsForTranscribingStateAfterOwnerIsReleased() = runBlocking {
        val vm = controller()
        val field = WhisperSpeechController::class.java.getDeclaredField("_isTranscribing").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val transcribing = field.get(vm) as MutableStateFlow<Boolean>
        // Model the valid interval between releasing activeSession and publishing false.
        // The terminal observer must suspend until both independently published states settle.
        transcribing.value = true
        supervisorScope {
            val observation = async(start = CoroutineStart.UNDISPATCHED) { settled(vm) }
            try {
                assertFalse("Owner release alone does not complete transcription", observation.isCompleted)
            } finally {
                transcribing.value = false
            }
            observation.await()
        }
    }

    @Test fun managedWavLeaseOutlivesRecorderClosureAndDecisionBarrier() = runBlocking {
        val barrier = Barrier()
        val closed = CompletableDeferred<Unit>()
        val vm = controller(object : WhisperSpeechController.PublicationHooks {
            override fun beforeDecision() = barrier.block()
        }, beforeClose = { closed.complete(Unit) })
        val recorder = startAndStop(vm)
        withTimeout(5_000) { barrier.entered.await(); closed.await() }
        val wav = requireNotNull(recorder.output)
        try {
            assertTrue(wav.exists())
            assertLeaseLocked(wav)
            // A different generation still cannot reclaim the entry during transcription/publication.
            com.negi.survey.whisper.CaptureCache(temp.root, java.util.UUID.randomUUID().toString()).begin().finish()
            assertTrue(wav.exists())
        } finally { barrier.release.countDown() }
        settled(vm)
        withTimeout(5_000) { while (wav.parentFile!!.exists()) yield() }
        assertEquals(1, exports.size)
    }

    @Test fun managedFilesStayLockedThroughPendingRecorderClose() = runBlocking {
        val closing = Barrier()
        val vm = controller(beforeClose = closing::block)
        val recorder = startAndStop(vm)
        withTimeout(5_000) { closing.entered.await() }
        val wav = requireNotNull(recorder.output)
        try { assertTrue(wav.exists()); assertLeaseLocked(wav) }
        finally { closing.release.countDown() }
        settled(vm)
        withTimeout(5_000) { while (wav.parentFile!!.exists()) yield() }
    }

    @Test fun clearedControllerKeepsManagedLeaseUntilBlockedWorkerReturns() = runBlocking {
        val barrier = Barrier()
        val vm = controller(object : WhisperSpeechController.PublicationHooks {
            override fun beforeDecision() = barrier.block()
        })
        val recorder = startAndStop(vm)
        withTimeout(5_000) { barrier.entered.await() }
        val wav = requireNotNull(recorder.output)
        store.clear()
        try { assertTrue(wav.exists()); assertLeaseLocked(wav) }
        finally { barrier.release.countDown() }
        withTimeout(5_000) { while (wav.parentFile!!.exists()) yield() }
        assertTrue(exports.isEmpty())
    }

    private fun assertLeaseLocked(wav: File) {
        java.nio.channels.FileChannel.open(File(wav.parentFile, "owner.lock").toPath(),
            java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            assertThrows(java.nio.channels.OverlappingFileLockException::class.java) { channel.tryLock() }
        }
    }

    private class Barrier {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        fun block() { entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)) }
    }

    private class WavRecorder(val error: (Exception) -> Unit, val beforeClose: () -> Unit = {}) : RecorderBackend {
        val started = CompletableDeferred<Unit>()
        var capture: () -> Unit = {}
        var output: File? = null
        override fun setOnCaptureStarted(callback: () -> Unit) { capture = callback }
        override fun isActive() = started.isCompleted
        override fun startRecording(output: File, rates: IntArray) {
            this.output = output; capture(); started.complete(Unit)
        }
        override suspend fun stopRecording() {
            File("src/androidTest/assets/whisper/jfk.wav").copyTo(requireNotNull(output), overwrite = true)
        }
        override fun close() = beforeClose()
    }
}
