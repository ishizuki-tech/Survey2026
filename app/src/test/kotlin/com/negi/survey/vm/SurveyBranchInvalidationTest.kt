package com.negi.survey.vm

import android.util.Log
import android.os.SystemClock
import android.os.Process
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.net.RecordingIdentity
import com.negi.survey.net.SurveyExportJsonBuilder
import com.negi.survey.net.SurveyRecording
import com.negi.survey.net.TranscriptionOutcome
import com.negi.survey.net.SurveyBranchStateStore
import com.negi.survey.net.SurveyRecordings
import com.negi.survey.net.V2ArtifactStore
import com.negi.survey.slm.Repository
import com.negi.survey.slm.RepositoryTraceContext
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.utils.V2ArtifactPaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import com.negi.survey.slm.FollowupExtractor
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class SurveyBranchInvalidationTest(private val asset: String, private val yes: String, private val no: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun languages() = listOf(
            arrayOf("survey_config10.yaml", "Yes", "No"),
            arrayOf("survey_config_sw_10.yaml", "Ndiyo", "Hapana"),
        )
    }

    private fun scenario(block: (SurveyViewModel) -> Unit) = mockkStatic(Log::class, SystemClock::class, Process::class) {
        every { Log.println(any(), any(), any()) } returns 0
        every { Log.getStackTraceString(any()) } answers { firstArg<Throwable>().stackTraceToString() }
        every { Process.myPid() } returns 1
        every { Process.myTid() } returns 1
        every { SystemClock.uptimeMillis() } returns 0L
        every { SystemClock.elapsedRealtime() } returns 0L
        val file = listOf(File("src/main/assets/$asset"), File("app/src/main/assets/$asset")).first { it.isFile }
        val vm = SurveyViewModel(NavBackStack<NavKey>(FlowHome), SurveyConfigLoader.fromFileStrictValidated(file.path))
        vm.goto("Q14a")
        vm.setAnswer(yes, "Q14a")
        vm.advanceToNext()
        assertEquals("Q14b", vm.currentNodeId)
        vm.acceptAiOriginalAnswer("Q14b", "Cows")
        vm.addFollowupQuestion("Q14b", "How often?")
        vm.answerLastFollowup("Q14b", "Daily")
        vm.setAiReason("Q14b", SurveyAiReason.ACHIEVED)
        vm.addAudioRef("Q14b", "old.wav")
        vm.setAnswer("Keep downstream", "Q15")
        block(vm)
    }

    @Test fun yesToNoInvalidatesAllBranchStateAndFinalizedJson() = scenario { vm ->
        val oldSnapshot = vm.createFinalizationSnapshot()
        vm.backToPrevious()
        vm.setAnswer(no, "Q14a")
        assertClean(vm)
        vm.advanceToNext()
        assertEquals("Q15", vm.currentNodeId)
        assertEquals("Keep downstream", vm.getAnswer("Q15"))
        assertEquals("Cows", oldSnapshot.answers["Q14b"])
        for (status in SurveyCompletionStatus.entries) {
            val snapshot = vm.createFinalizationSnapshot(status)
            assertFalse(snapshot.questions.containsKey("Q14b"))
            assertFalse(snapshot.answers.containsKey("Q14b"))
            assertFalse(snapshot.followups.containsKey("Q14b"))
            assertTrue(snapshot.audioRefs.none { it.questionId == "Q14b" })
            val json = SurveyExportJsonBuilder.build(snapshot, "stamp", listOf(recording(vm, "old.wav")))
            assertFalse(json, json.contains("Q14b"))
            assertFalse(json, json.contains("old.wav"))
        }
    }

    @Test fun yesNoYesStartsCleanAndOnlyNewAudioIsExported() = scenario { vm ->
        vm.backToPrevious()
        vm.setAnswer(no, "Q14a")
        vm.setAnswer(yes, "Q14a")
        vm.advanceToNext()
        assertEquals("Q14b", vm.currentNodeId)
        assertClean(vm)
        vm.acceptAiOriginalAnswer("Q14b", "Chickens")
        vm.addAudioRef("Q14b", "new.wav")
        val json = SurveyExportJsonBuilder.build(vm.createFinalizationSnapshot(), "stamp",
            listOf(recording(vm, "old.wav"), recording(vm, "new.wav", vm.nodeStateRevision("Q14b"))))
        assertFalse(json.contains("old.wav"))
        assertTrue(json.contains("new.wav"))
        assertTrue(json.contains("Chickens"))
    }

    @Test fun freshInterruptedRecordingAfterReentrySurvivesWithoutObserverReference() = scenario { vm ->
        vm.backToPrevious()
        vm.setAnswer(no, "Q14a")
        vm.setAnswer(yes, "Q14a")
        vm.advanceToNext()
        // A staged recording whose transcription is interrupted never calls onVoiceExported.
        val fresh = recording(vm, "fresh-interrupted.wav", vm.nodeStateRevision("Q14b")).copy(
            outcome = TranscriptionOutcome(TranscriptionOutcome.Status.INTERRUPTED))
        val json = SurveyExportJsonBuilder.build(vm.createFinalizationSnapshot(), "stamp", listOf(fresh))
        assertTrue(json, json.contains("fresh-interrupted.wav"))
    }

    @Test fun lateAudioCallbackAfterYesNoYesCannotAttachOldRecording() = scenario { vm ->
        val capturedRevision = vm.nodeStateRevision("Q14b")
        vm.backToPrevious()
        vm.setAnswer(no, "Q14a")
        vm.setAnswer(yes, "Q14a")
        vm.advanceToNext()
        vm.addAudioRef("Q14b", "late-old.wav", expectedNodeRevision = capturedRevision)
        assertTrue(vm.getAudioRefs("Q14b").isEmpty())
        vm.addAudioRef("Q14b", "fresh.wav", expectedNodeRevision = vm.nodeStateRevision("Q14b"))
        assertEquals("fresh.wav", vm.getAudioRefs("Q14b").single().fileName)
    }

    @Test fun lateEvaluationAfterInvalidationCannotRestoreState() = lateAiResult(followupInFlight = false)

    @Test fun lateGeneratedFollowupAfterReentryCannotRestoreState() = lateAiResult(followupInFlight = true)

    @Test fun cancelledEvaluationCannotRestoreReenteredBranch() = lateAiResult(followupInFlight = false, cancelFirst = true)

    private fun lateAiResult(followupInFlight: Boolean, cancelFirst: Boolean = false) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            // Initialize static normalization tables before installing the parser boundary.
            FollowupExtractor.hashCode()
            // The Android org.json extractor is outside this ownership test's boundary.
            mockkStatic(FollowupExtractor::class) {
                every { FollowupExtractor.extractScore(any()) } returns 50
                every { FollowupExtractor.fromRaw(any(), any()) } returns emptyList()
                every { FollowupExtractor.extractFollowupQuestion(any()) } answers { firstArg<String>() }
                scenario { vm ->
                    val eval = CompletableDeferred<String>()
                    val followup = CompletableDeferred<String>()
                    var requests = 0
                    val repo = object : Repository {
                        override suspend fun request(prompt: String, traceContext: RepositoryTraceContext?): Flow<String> = flow {
                            requests++
                            emit(withContext(NonCancellable) { if (requests == 1) eval.await() else followup.await() })
                        }
                        override fun buildPrompt(userPrompt: String) = userPrompt
                    }
                    val ai = AiViewModel(repo, ioDispatcher = dispatcher)
                    val context = "old-Q14b"
                    val rootQuestion = vm.getQuestion("Q14b")
                    ai.ensureConversationContext(context, rootQuestion, "Cows")
                    val job = ai.evaluateSurveyTwoStepAsync(vm, "Q14b", context, rootQuestion)
                    runCurrent()
                    assertEquals("AI error: ${ai.error.value}", 1, requests)
                    if (followupInFlight) {
                        eval.complete("""{"score":50,"missing_points":["feeding_frequency"],"followup_needed":true}""")
                        runCurrent()
                        assertEquals("AI error: ${ai.error.value}", 2, requests)
                    }
                    if (cancelFirst) {
                        ai.resetStates()
                        runCurrent()
                    }
                    vm.backToPrevious()
                    vm.setAnswer(no, "Q14a")
                    if (followupInFlight || cancelFirst) {
                        vm.setAnswer(yes, "Q14a")
                        vm.advanceToNext()
                        // Navigation3 Activity recreation attaches a new stack to the same owner.
                        vm.attachNavigation(NavBackStack<NavKey>(FlowHome, FlowSingle, FlowAI))
                        vm.acceptAiOriginalAnswer("Q14b", "Fresh chickens")
                    }
                    val before = vm.createFinalizationSnapshot()
                    if (followupInFlight) followup.complete("How many days per week do you feed the animals?")
                    else eval.complete("""{"score":100,"missing_points":[],"followup_needed":false}""")
                    runCurrent()
                    assertTrue(job.isCompleted)
                    val after = vm.createFinalizationSnapshot()
                    assertEquals(before, after)
                    assertTrue(ai.chatHistoryFlow(context).value.none { it.isFollowupQuestion })
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun persistedInventoryPreservesFreshAndUnrelatedAudioWhileDetachingHistoricalWav() = scenario { vm ->
        val directory = java.nio.file.Files.createTempDirectory("issue119-inventory-").toFile()
        try {
            val store = V2ArtifactStore(directory)
            val paths = V2ArtifactPaths(vm.surveyUuid.value, DeviceUploadTag("review"))
            fun persist(id: String, questionId: String, revision: Long): V2ArtifactStore.Entry {
                val identity = RecordingIdentity(id, RecordingPrompt(vm.surveyUuid.value, questionId,
                    RecordingPrompt.Kind.ORIGINAL, "Question?", branchRevision = revision))
                return store.stage(id, V2ArtifactStore.Kind.VOICE, vm.surveyUuid.value,
                    V2ArtifactStore.profile, paths.voiceWav(questionId, id),
                    Json.encodeToString(RecordingIdentity.serializer(), identity)) { it.write(byteArrayOf(1, 2, 3)) }
            }
            val old = persist("old", "Q14b", 0)
            val unrelated = persist("unrelated", "Q15", 0)
            vm.backToPrevious()
            vm.setAnswer(no, "Q14a")
            vm.setAnswer(yes, "Q14a")
            vm.advanceToNext()
            val fresh = persist("fresh", "Q14b", vm.nodeStateRevision("Q14b"))
            val inventory = SurveyRecordings(V2ArtifactStore(directory)).inventory(paths, V2ArtifactStore.profile)
            val json = SurveyExportJsonBuilder.build(vm.createFinalizationSnapshot(), "stamp", inventory)
            assertFalse(json.contains(old.route.basename))
            assertTrue(json.contains(fresh.route.basename))
            assertTrue(json.contains(unrelated.route.basename))
            assertFalse("Internal ownership metadata must not alter survey JSON format", json.contains("branch_revision"))
            val survey = store.stage(V2ArtifactStore.surveyKey(vm.surveyUuid.value),
                V2ArtifactStore.Kind.SURVEY, vm.surveyUuid.value, V2ArtifactStore.profile,
                paths.surveyJson()) { it.write(json.toByteArray()) }
            assertFalse(store.isVoiceReferenced(old.route))
            assertTrue(store.isVoiceReferenced(fresh.route))
            assertTrue(store.isVoiceReferenced(unrelated.route))
            // The finalized reference index survives survey payload cleanup.
            survey.recordReceipt()
            assertTrue(survey.cleanup())
            assertFalse(V2ArtifactStore(directory).isVoiceReferenced(old.route))
            assertTrue(V2ArtifactStore(directory).isVoiceReferenced(fresh.route))
            assertFalse(old.cleanup())
            assertTrue(old.payload.isFile)
            assertTrue(fresh.payload.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun abandonedRecoveryMustNotRestoreInvalidatedAudio() = durableScenario { vm, branches, directory ->
        val old = persistRecording(directory, vm, "old-recording", "Q14b", 0)
        vm.setAnswer("Keep downstream", "Q15")
        val unrelated = persistRecording(directory, vm, "unrelated", "Q15", null)
        vm.backToPrevious()
        vm.submitSingleChoiceAnswer(no, "Q14a", advance = false)!!.join()
        val reopened = V2ArtifactStore(File(directory, "artifacts"))
        assertTrue(vm.surveyUuid.value in SurveyRecordings(reopened)
            .abandonedSurveyIds(currentProcess = java.util.UUID.randomUUID().toString()))
        val recovered = SurveyBranchStateStore(File(directory, "branches")).recoverySnapshot(vm.surveyUuid.value)
        val json = recoveredJson(directory, recovered)
        assertFalse("Abandoned recovery resurrected invalidated Q14b: $json", json.contains("Q14b"))
        assertFalse(json.contains(old.route.basename))
        assertTrue(json.contains(unrelated.route.basename))
        assertEquals("Keep downstream", vm.getAnswer("Q15"))
        assertTrue(old.payload.isFile)
        assertEquals(no, branches.read(vm.surveyUuid.value).choices["Q14a"])
        val survey = reopened.stage(V2ArtifactStore.surveyKey(vm.surveyUuid.value), V2ArtifactStore.Kind.SURVEY,
            vm.surveyUuid.value, V2ArtifactStore.profile, paths(vm.surveyUuid.value).surveyJson()) { it.write(json.toByteArray()) }
        assertFalse(reopened.isVoiceReferenced(old.route))
        assertTrue(reopened.isVoiceReferenced(unrelated.route))
        assertTrue(survey.matchesPayload())
    }

    @Test fun recoveryAfterYesNoYesKeepsOnlyCurrentRevisionAndUnrelatedAudio() = durableScenario { vm, _, directory ->
        val old = persistRecording(directory, vm, "old", "Q14b", 0)
        val unrelated = persistRecording(directory, vm, "unrelated", "Q15", null)
        vm.backToPrevious()
        vm.submitSingleChoiceAnswer(no, "Q14a", advance = false)!!.join()
        vm.submitSingleChoiceAnswer(yes, "Q14a")!!.join()
        val fresh = persistRecording(directory, vm, "fresh", "Q14b", vm.nodeStateRevision("Q14b"))
        val recovered = SurveyBranchStateStore(File(directory, "branches")).recoverySnapshot(vm.surveyUuid.value)
        val json = recoveredJson(directory, recovered)
        assertFalse(json.contains(old.route.basename))
        assertTrue(json.contains(fresh.route.basename))
        assertTrue(json.contains(unrelated.route.basename))
        assertEquals(1L, recovered.nodeStateRevisions["Q14b"])
        assertFalse("Q14b" in recovered.excludedNodeIds)
    }

    @Test fun legacyBranchRecordingWithoutExplicitRevisionIsConservativelyExcluded() = durableScenario { vm, _, directory ->
        val legacy = persistRecording(directory, vm, "legacy", "Q14b", null)
        val current = persistRecording(directory, vm, "current", "Q14b", 0)
        val recovered = SurveyBranchStateStore(File(directory, "branches")).recoverySnapshot(vm.surveyUuid.value)
        val json = recoveredJson(directory, recovered)
        assertFalse(json.contains(legacy.route.basename))
        assertTrue(json.contains(current.route.basename))
        // Missing journal is also legacy: retain unrelated audio, never infer Q14b eligibility.
        val unrelated = persistRecording(directory, vm, "unrelated", "Q15", null)
        val legacySnapshot = SurveyBranchStateStore(File(directory, "absent-legacy-journal"))
            .recoverySnapshot(vm.surveyUuid.value)
        val legacyJson = recoveredJson(directory, legacySnapshot)
        assertFalse(legacyJson.contains("Q14b"))
        assertTrue(legacyJson.contains(unrelated.route.basename))
    }

    @Test fun pendingCommitSurvivesNavigationReattachmentAndCannotExportOrAdvanceEarly() {
        var pause = false
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        durableScenario(writer = { target, text ->
            if (pause) {
                entered.complete(Unit)
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
            V2ArtifactStore.writeAtomically(target, text)
        }) { vm, branches, _ ->
            vm.backToPrevious()
            pause = true
            val commit = vm.submitSingleChoiceAnswer(no, "Q14a")!!
            entered.await()
            try {
                assertTrue(vm.navigationLocked())
                assertEquals(yes, vm.getAnswer("Q14a"))
                assertThrows(IllegalStateException::class.java) { vm.createFinalizationSnapshot() }
                vm.attachNavigation(NavBackStack<NavKey>(FlowHome, FlowSingle))
                vm.advanceToNext()
                vm.backToPrevious()
                assertEquals("Q14a", vm.currentNodeId)
                assertNull(vm.submitSingleChoiceAnswer(no, "Q14a"))
            } finally {
                release.countDown()
            }
            commit.join()
            assertEquals("Q15", vm.currentNodeId)
            assertEquals(no, vm.getAnswer("Q14a"))
            assertFalse(vm.navigationLocked())
            assertEquals(vm.createFinalizationSnapshot().nodeStateRevisions,
                branches.read(vm.surveyUuid.value).revisions)
        }
    }

    @Test fun failedInvalidationBlocksNavigationAndExportUntilSuccessfulRetry() {
        var fail = false
        durableScenario(writer = { target, text ->
            if (fail) throw IOException("disk failure")
            V2ArtifactStore.writeAtomically(target, text)
        }) { vm, branches, directory ->
            val old = persistRecording(directory, vm, "old", "Q14b", 0)
            vm.backToPrevious()
            fail = true
            vm.submitSingleChoiceAnswer(no, "Q14a")!!.join()
            assertEquals(yes, vm.getAnswer("Q14a"))
            assertEquals("Q14a", vm.currentNodeId)
            assertTrue(vm.navigationLocked())
            assertThrows(IllegalStateException::class.java) { vm.createFinalizationSnapshot() }
            vm.advanceToNext()
            vm.backToPrevious()
            assertEquals("Q14a", vm.currentNodeId)
            assertEquals(yes, branches.read(vm.surveyUuid.value).choices["Q14a"])
            fail = false
            vm.submitSingleChoiceAnswer(no, "Q14a")!!.join()
            assertFalse(vm.navigationLocked())
            assertEquals("Q15", vm.currentNodeId)
            assertEquals(no, vm.getAnswer("Q14a"))
            assertFalse(recoveredJson(directory, branches.recoverySnapshot(vm.surveyUuid.value)).contains("Q14b"))
            assertTrue(old.payload.isFile)
        }
    }

    @Test fun failureAfterDiskCommitBeforeLivePublicationRecoversConservatively() {
        var failAfterCommit = false
        durableScenario(writer = { target, text ->
            V2ArtifactStore.writeAtomically(target, text)
            if (failAfterCommit) throw IOException("interruption after durable commit")
        }) { vm, branches, directory ->
            persistRecording(directory, vm, "old", "Q14b", 0)
            vm.backToPrevious()
            failAfterCommit = true
            vm.submitSingleChoiceAnswer(no, "Q14a")!!.join()
            assertEquals(yes, vm.getAnswer("Q14a"))
            assertTrue(vm.navigationLocked())
            assertThrows(IllegalStateException::class.java) { vm.createFinalizationSnapshot() }
            assertEquals(no, branches.read(vm.surveyUuid.value).choices["Q14a"])
            assertFalse(recoveredJson(directory, branches.recoverySnapshot(vm.surveyUuid.value)).contains("Q14b"))
            assertNull(vm.submitSingleChoiceAnswer(yes, "Q14a"))
            assertEquals(1L, branches.read(vm.surveyUuid.value).revisions["Q14b"])
            failAfterCommit = false
            vm.submitSingleChoiceAnswer(no, "Q14a")!!.join()
            assertFalse(vm.navigationLocked())
            assertEquals(1L, vm.nodeStateRevision("Q14b"))
        }
    }

    private fun durableScenario(
        writer: (File, String) -> Unit = { target, text -> V2ArtifactStore.writeAtomically(target, text) },
        block: suspend (SurveyViewModel, SurveyBranchStateStore, File) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val directory = java.nio.file.Files.createTempDirectory("issue119-durable-").toFile()
        mockkStatic(Log::class)
        every { Log.println(any(), any(), any()) } returns 0
        try {
            val assetFile = listOf(File("src/main/assets/$asset"), File("app/src/main/assets/$asset")).first { it.isFile }
            val branches = SurveyBranchStateStore(File(directory, "branches"), writer)
            val vm = SurveyViewModel(NavBackStack<NavKey>(FlowHome),
                SurveyConfigLoader.fromFileStrictValidated(assetFile.path), branchStateStore = branches)
            vm.goto("Q14a")
            vm.submitSingleChoiceAnswer(yes, "Q14a")!!.join()
            assertEquals("Q14b", vm.currentNodeId)
            vm.acceptAiOriginalAnswer("Q14b", "Cows")
            block(vm, branches, directory)
        } finally {
            unmockkStatic(Log::class)
            Dispatchers.resetMain()
            directory.deleteRecursively()
        }
    }

    private fun paths(surveyId: String) = V2ArtifactPaths(surveyId, DeviceUploadTag("review"))

    private fun persistRecording(directory: File, vm: SurveyViewModel, id: String, nodeId: String, revision: Long?): V2ArtifactStore.Entry {
        val identity = RecordingIdentity(id, RecordingPrompt(vm.surveyUuid.value, nodeId,
            RecordingPrompt.Kind.ORIGINAL, "Question?", branchRevision = revision))
        return V2ArtifactStore(File(directory, "artifacts")).stage(id, V2ArtifactStore.Kind.VOICE,
            vm.surveyUuid.value, V2ArtifactStore.profile, paths(vm.surveyUuid.value).voiceWav(nodeId, id),
            Json.encodeToString(RecordingIdentity.serializer(), identity)) { it.write(byteArrayOf(1, 2, 3)) }
    }

    private fun recoveredJson(directory: File, snapshot: SurveyFinalizationSnapshot) = SurveyExportJsonBuilder.build(
        snapshot, "stamp", SurveyRecordings(V2ArtifactStore(File(directory, "artifacts")))
            .inventory(paths(snapshot.surveyId), V2ArtifactStore.profile))

    @Test fun obsoleteInventoryIsExcludedEvenWithoutAnAudioObserverReference() = scenario { vm ->
        vm.backToPrevious()
        vm.setAnswer(no, "Q14a")
        vm.clearAudioRefs("Q14b")
        val json = SurveyExportJsonBuilder.build(vm.createFinalizationSnapshot(), "stamp",
            listOf(recording(vm, "unobserved.wav")))
        assertFalse(json, json.contains("Q14b"))
    }

    @Test fun invalidationRejectsLateStateAndClearsTransactionIdentity() = scenario { vm ->
        vm.beginAnswerTransaction("Q14b", "voice")
        vm.acceptVoiceTranscript("Q14b", "Old voice")
        val revision = vm.nodeStateRevision("Q14b")
        vm.backToPrevious()
        vm.setAnswer(no, "Q14a")
        assertEquals(revision + 1, vm.nodeStateRevision("Q14b"))
        assertNull(vm.answerTraceId("Q14b"))
        assertFalse(vm.consumeAcceptedVoiceTransaction("Q14b"))
        vm.setQuestion("Late question", "Q14b")
        vm.acceptAiOriginalAnswer("Q14b", "Late accepted answer")
        vm.acceptVoiceTranscript("Q14b", "Late voice")
        assertFalse(vm.consumeAcceptedVoiceTransaction("Q14b"))
        vm.setAnswer("Late answer", "Q14b")
        vm.setAiReason("Q14b", SurveyAiReason.ACHIEVED)
        assertFalse(vm.addFollowupQuestion("Q14b", "Late followup?"))
        vm.addAudioRef("Q14b", "late.wav")
        vm.replaceAudioRef("Q14b", "late-replacement.wav")
        assertClean(vm)
        vm.setAnswer(yes, "Q14a")
        vm.advanceToNext()
        vm.setAnswer("Fresh answer", "Q14b")
        assertEquals("Fresh answer", vm.getAnswer("Q14b"))
        vm.backToPrevious()
        vm.setAnswer(no, "Q14a")
        assertEquals(revision + 2, vm.nodeStateRevision("Q14b"))
        vm.resetToStart()
        assertEquals(0L, vm.nodeStateRevision("Q14b"))
        assertTrue(vm.createFinalizationSnapshot().nodeStateRevisions.isEmpty())
    }

    @Test fun processingLockPreventsBranchEditUntilCaptureHasFinished() = scenario { vm ->
        vm.backToPrevious()
        val processing = MutableStateFlow(true)
        vm.bindNavigationToProcessing(processing)
        vm.setAnswer(no, "Q14a")
        assertEquals(yes, vm.getAnswer("Q14a"))
        assertEquals("Cows", vm.getAnswer("Q14b"))
        processing.value = false
        vm.setAnswer(no, "Q14a")
        assertClean(vm)
    }

    @Test fun detachingAudioDoesNotDeleteOrAlterPersistedFiles() = scenario { vm ->
        val file = File.createTempFile("issue119-retained-", ".wav")
        try {
            file.writeText("Existing immutable recording payload")
            vm.addAudioRef("Q14b", file.absolutePath)
            vm.addAudioRef("Q15", file.absolutePath)
            vm.backToPrevious()
            vm.setAnswer(no, "Q14a")
            assertClean(vm)
            assertEquals(file.absolutePath, vm.getAudioRefs("Q15").single().fileName)
            assertEquals("Existing immutable recording payload", file.readText())
        } finally {
            file.delete()
        }
    }

    @Test fun ordinaryBackNextAndUnchangedYesPreserveValidState() = scenario { vm ->
        vm.backToPrevious()
        vm.setAnswer(yes, "Q14a")
        vm.advanceToNext()
        assertEquals("Cows", vm.getAnswer("Q14b"))
        assertEquals("Daily", vm.followups.value["Q14b"]?.single()?.answer)
        assertEquals(SurveyAiReason.ACHIEVED, vm.aiReasons.value["Q14b"])
        assertEquals(1, vm.getAudioRefs("Q14b").size)
        val root = Json.parseToJsonElement(SurveyExportJsonBuilder.build(vm.createFinalizationSnapshot(), "stamp",
            listOf(recording(vm, "old.wav")))).jsonObject
        assertTrue(root.getValue("answers").jsonObject.containsKey("Q14b"))
        assertEquals(1, root.getValue("voice_files").jsonArray.size)
    }

    private fun assertClean(vm: SurveyViewModel) {
        assertEquals("", vm.getAnswer("Q14b"))
        assertFalse(vm.followups.value.containsKey("Q14b"))
        assertFalse(vm.aiReasons.value.containsKey("Q14b"))
        assertFalse("Q14b" in vm.submittedAiOriginals.value)
        assertTrue(vm.getAudioRefs("Q14b").isEmpty())
    }

    private fun recording(vm: SurveyViewModel, file: String, revision: Long = 0) = SurveyRecording(file,
        RecordingIdentity(file, RecordingPrompt(vm.surveyUuid.value, "Q14b", RecordingPrompt.Kind.ORIGINAL, "Animals?", branchRevision = revision)),
        TranscriptionOutcome.from(Result.success("Cows")))
}
