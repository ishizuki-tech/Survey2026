package com.negi.survey.net

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import com.negi.survey.net.V2VoiceDelivery.Result
import com.negi.survey.net.V2VoiceDeliveryLedger.Evidence
import com.negi.survey.net.V2VoiceRecovery.Status
import com.negi.survey.net.V2VoiceStageResult.Ready
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.vm.SurveyFinalizationSnapshot
import com.negi.survey.vm.SurveyViewModel
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class V2VoiceDeliveryTest {
    @get:Rule val files = TemporaryFolder()
    private val surveyId = "01a1b874-6bff-7abc-9def-0123456789ab"
    private val id = V2RecordingId("00000000000040008000000000000001")
    private val secondId = V2RecordingId("00000000000040008000000000000002")
    private val tag = DeviceUploadTag("Pixel_Test")
    private val target = V2SurveyJsonDestination(
        V2SurveyJsonDestination.Identity.Development, "ishizuki-tech", "SurveyExports-Dev", "main", ""
    )
    private val config = GitHubUploader.GitHubConfig(target.owner, target.repository, "fake-token")
    private val store get() = V2VoiceRouteStore(files.root)
    private val ledger get() = V2VoiceLedger(files.root)
    private val execution get() = V2VoiceFileExecution(files.root)
    private val remote = FakeRemote()
    private val work = FakeWork()

    @Test
    fun workerCreatesExactVerifiedBlobAtFrozenPathAndRetainsLedgerAndDeliveredRoute() = runBlocking {
        val ready = stage()
        val routeBytes = pending(ready).readBytes()
        val original = ready.file.readBytes()
        val request = V2VoiceWork.request(ready.route)
        assertTrue(isV2StagedFile(files.root, ready.file))
        assertEquals(V2VoiceWorker::class.java.name, request.workSpec.workerClassName)
        assertEquals(setOf("protocol", "recordingId", "routeSha256"), request.workSpec.input.keyValueMap.keys)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
        val params = mockk<WorkerParameters>()
        every { params.inputData } returns request.workSpec.input
        var deletes = 0
        val worker = V2VoiceWorker(mockk<Context>(), params) {
            delivery(delete = {
                assertEquals(ready.file.toPath(), it)
                assertFalse(pending(ready).exists())
                assertArrayEquals(routeBytes, delivered(ready).readBytes())
                assertEquals(Evidence.CONFIRMED, ledger.inspect(ready.route))
                Files.delete(it)
                deletes++
            })
        }
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(ready.route.remoteRelativePath, result.outputData.getString("remotePath"))
        assertTrue(ready.route.remoteRelativePath.startsWith("2026-11-07/voice/2026-11-07_23-59-59Z_"))
        assertEquals(listOf(ready.route.remoteRelativePath), remote.creates)
        assertArrayEquals(original, remote.contents.values.single())
        assertEquals(Evidence.CONFIRMED, ledger.inspect(ready.route))
        assertEquals(1, deletes)
        assertFalse(ready.file.exists())
        val confirmation = ledgerFile().readBytes()
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        assertEquals(1, deletes)
        assertEquals(1, remote.gets.size)
        assertArrayEquals(confirmation, ledgerFile().readBytes())
        assertArrayEquals(routeBytes, delivered(ready).readBytes())
    }

    @Test
    fun existingMatchingBlobConfirmsWithoutPutAndConflictingBlobNeverOverwrites() = runBlocking {
        val matching = stage()
        remote.contents[matching.route.remoteRelativePath] = matching.file.readBytes()
        assertTrue(delivery().execute(input(matching)) is Result.Completed)
        assertTrue(remote.creates.isEmpty())

        val conflict = stage(secondId)
        val other = byteArrayOf(1, 2, 3)
        remote.contents[conflict.route.remoteRelativePath] = other
        assertTrue(delivery().execute(input(conflict)) is Result.Blocked)
        assertTrue(remote.creates.isEmpty())
        assertArrayEquals(other, remote.contents.getValue(conflict.route.remoteRelativePath))
        assertEquals(Evidence.MISSING, ledger.inspect(conflict.route))
        assertTrue(conflict.file.isFile)
    }

    @Test
    fun http409And422ReconcileMatchingBlobAtTheSameTarget() = runBlocking {
        for ((recording, code) in listOf(id to 409, secondId to 422)) {
            val ready = stage(recording)
            remote.onCreate = { path, bytes ->
                remote.contents[path] = bytes.copyOf()
                throw GitHubUploader.HttpFailureException(code, "test conflict")
            }
            assertTrue(delivery().execute(input(ready)) is Result.Completed)
            assertEquals(2, remote.gets.count { it == ready.route.remoteRelativePath })
            assertEquals(1, remote.creates.count { it == ready.route.remoteRelativePath })
        }
    }

    @Test
    fun unmatched409RetriesAndUnmatched422BlocksWithoutCleanup() = runBlocking {
        val ready = stage()
        remote.onCreate = { _, _ -> throw GitHubUploader.HttpFailureException(409, "test conflict") }
        assertTrue(delivery().execute(input(ready)) is Result.Retry)
        assertTrue(ready.file.isFile)
        remote.onCreate = { _, _ -> throw GitHubUploader.HttpFailureException(422, "test rejection") }
        assertTrue(delivery().execute(input(ready)) is Result.Blocked)
        assertEquals(setOf(ready.route.remoteRelativePath), (remote.gets + remote.creates).toSet())
        assertEquals(Evidence.MISSING, ledger.inspect(ready.route))
        assertTrue(pending(ready).isFile)
    }

    @Test
    fun ambiguousCreateIsVerifiedOnRestartAtTheSamePathWithoutAnotherPut() = runBlocking {
        val ready = stage()
        remote.onCreate = { path, bytes ->
            remote.contents[path] = bytes.copyOf()
            throw IOException("connection closed after commit")
        }
        assertTrue(delivery().execute(input(ready)) is Result.Retry)
        assertEquals(Evidence.MISSING, ledger.inspect(ready.route))
        assertTrue(ready.file.isFile)
        assertTrue(delivery().execute(input(ready)) is Result.Completed)
        assertEquals(listOf(ready.route.remoteRelativePath, ready.route.remoteRelativePath), remote.gets)
        assertEquals(listOf(ready.route.remoteRelativePath), remote.creates)
    }

    @Test
    fun strictRequestAndMutationBeforeTransportAreRejectedWithoutLedgerOrHttp() = runBlocking {
        val ready = stage()
        assertTrue(delivery().execute(input(ready) + ("filePath" to ready.file.path)) is Result.Blocked)
        assertTrue(delivery().execute(input(ready) + ("routeSha256" to "0".repeat(64))) is Result.Blocked)
        assertTrue(delivery(resolve = {
            alterWav(ready)
            config
        }).execute(input(ready)) is Result.Blocked)
        assertTrue(remote.gets.isEmpty())
        assertEquals(Evidence.MISSING, ledger.inspect(ready.route))
        assertTrue(pending(ready).isFile)
        assertTrue(ready.file.isFile)
    }

    @Test
    fun mutationDuringTransportCannotChangeSnapshotOrCauseDeletion() = runBlocking {
        val ready = stage()
        val exact = ready.file.readBytes()
        val transport = V2VoiceTransport { cfg, path, bytes ->
            alterWav(ready)
            assertArrayEquals(exact, bytes)
            remote.upload(cfg, path, bytes)
        }
        assertTrue(delivery(transport = transport).execute(input(ready)) is Result.Blocked)
        assertArrayEquals(exact, remote.contents.values.single())
        assertEquals(Evidence.CONFIRMED, ledger.inspect(ready.route))
        assertTrue(pending(ready).isFile)
        assertTrue(ready.file.isFile)
    }

    @Test
    fun missingCredentialsDestinationMismatchAndOversizedPayloadRemainPending() = runBlocking {
        val ready = stage()
        assertTrue(delivery(resolve = { null }).execute(input(ready)) is Result.Retry)
        assertTrue(delivery(resolve = { config.copy(repo = "elsewhere") }).execute(input(ready)) is Result.Blocked)
        assertTrue(delivery(resolve = { config.copy(maxRawBytesHint = 50) }).execute(input(ready)) is Result.Blocked)
        assertTrue(delivery(resolve = { config.copy(maxRequestBytesHint = 100) }).execute(input(ready)) is Result.Blocked)
        assertTrue(remote.gets.isEmpty())
        assertTrue(delivery(transport = V2VoiceTransport { _, _, _ ->
            throw GitHubUploader.UploadSizeLimitException("exact envelope limit")
        }).execute(input(ready)) is Result.Blocked)
        assertTrue(ready.file.isFile)
        assertEquals(Evidence.MISSING, ledger.inspect(ready.route))
    }

    @Test
    fun localBuildCannotDeliverProduction() = runBlocking {
        val production = target.copy(identity = V2SurveyJsonDestination.Identity.Production, repository = "SurveyExports")
        val ready = stage(destination = production, local = false)
        assertTrue(delivery(resolve = { error("Must reject before resolving credentials") })
            .execute(input(ready)) is Result.Blocked)
        assertEquals(Status.BLOCKED, recover().single().status)
        assertTrue(remote.gets.isEmpty())
        assertTrue(work.requests.isEmpty())
        assertTrue(ready.file.isFile)
    }

    @Test
    fun failedLedgerCommitRetainsWavAndRetryReconcilesExistingBlob() = runBlocking {
        val ready = stage()
        val broken = object : V2VoiceDeliveryLedger by ledger {
            override fun recordConfirmed(route: V2VoiceRoute) { throw IOException("disk error") }
        }
        assertTrue(delivery(usingLedger = broken).execute(input(ready)) is Result.Retry)
        assertEquals(Evidence.MISSING, ledger.inspect(ready.route))
        assertTrue(ready.file.isFile)
        assertTrue(pending(ready).isFile)
        assertTrue(delivery().execute(input(ready)) is Result.Completed)
        assertEquals(1, remote.creates.size)
        assertEquals(2, remote.gets.size)
    }

    @Test
    fun failedTransportLeaseReleaseLeavesConfirmedPendingAndLocalRetryUsesNoHttp() = runBlocking {
        val ready = stage()
        val failedRelease = object : V2VoiceExecution {
            override suspend fun <T> own(recordingId: V2RecordingId, action: suspend () -> T): T {
                execution.own(recordingId, action)
                throw IOException("injected release failure")
            }
        }
        assertTrue(delivery(usingExecution = failedRelease).execute(input(ready)) is Result.Retry)
        assertEquals(Evidence.CONFIRMED, ledger.inspect(ready.route))
        assertTrue(pending(ready).isFile)
        assertTrue(ready.file.isFile)
        assertTrue(delivery(resolve = { error("Cleanup needs no credentials") }).execute(input(ready)) is Result.Completed)
        assertEquals(1, remote.gets.size)
    }

    @Test
    fun corruptLedgerAndUnexpectedFilesBlockDestructiveCleanup() = runBlocking {
        val ready = stage()
        ledger.recordConfirmed(ready.route)
        val original = ledgerFile().readBytes()
        ledgerFile().writeText("{")
        assertTrue(delivery().execute(input(ready)) is Result.Blocked)
        assertThrows(V2VoiceEvidenceConflict::class.java) { ledger.recordConfirmed(ready.route) }
        assertEquals("{", ledgerFile().readText())
        ledgerFile().writeBytes(original)
        val extra = File(ready.file.parentFile, "unexpected.txt").apply { writeText("retain") }
        assertTrue(delivery().execute(input(ready)) is Result.Blocked)
        assertEquals("retain", extra.readText())
        assertTrue(pending(ready).isFile)
        assertTrue(ready.file.isFile)
        assertTrue(remote.gets.isEmpty())
    }

    @Test
    fun competingWorkOrQueryFailureDefersRetirement() = runBlocking {
        val ready = stage()
        ledger.recordConfirmed(ready.route)
        assertTrue(delivery(competing = { true }).execute(input(ready)) is Result.Retry)
        assertTrue(delivery(competing = { throw TimeoutException("lookup") }).execute(input(ready)) is Result.Retry)
        assertTrue(pending(ready).isFile)
        assertTrue(ready.file.isFile)
        assertTrue(remote.gets.isEmpty())
    }

    @Test
    fun restartAfterDeliveredPublicationFinishesLocallyWithoutRewritingEvidence() = runBlocking {
        val ready = stage()
        assertTrue(delivery(afterRetire = { throw IOException("stop after publish") }).execute(input(ready)) is Result.Retry)
        assertTrue(delivered(ready).isFile)
        assertTrue(ready.file.isFile)
        val bytes = delivered(ready).readBytes()
        val evidence = ledgerFile().readBytes()
        assertEquals(Status.ENQUEUED, recover { error("Delivered cleanup needs no credentials") }.single().status)
        assertTrue(delivery(resolve = { error("Delivered cleanup is local") }).execute(input(ready)) is Result.Completed)
        assertEquals(Status.COMPLETE, recover().single().status)
        assertEquals(1, work.requests.size)
        assertEquals(1, remote.gets.size)
        assertArrayEquals(bytes, delivered(ready).readBytes())
        assertArrayEquals(evidence, ledgerFile().readBytes())
    }

    @Test
    fun failedDeleteAndSyncAfterUnlinkRetryWithoutSecondDeleteOrHttp() = runBlocking {
        val ready = stage()
        assertTrue(delivery(delete = { throw IOException("unlink denied") }).execute(input(ready)) is Result.Retry)
        assertTrue(ready.file.isFile)
        assertTrue(delivered(ready).isFile)
        val interrupted = delivery(sync = {
            if (!ready.file.exists()) throw IOException("sync failed after unlink")
            syncDirectory(it)
        })
        assertTrue(interrupted.execute(input(ready)) is Result.Retry)
        assertFalse(ready.file.exists())
        assertTrue(delivery(delete = { error("No second deletion") }).execute(input(ready)) is Result.Completed)
        assertEquals(1, remote.gets.size)
        assertEquals(Evidence.CONFIRMED, ledger.inspect(ready.route))
    }

    @Test
    fun failedSyncAndExternalSymlinkAfterRetirementNeverDeleteUnverifiedBytes() = runBlocking {
        val ready = stage()
        val external = source(secondId)
        assertTrue(delivery(sync = { throw IOException("directory sync failed") })
            .execute(input(ready)) is Result.Retry)
        assertTrue(delivered(ready).isFile)
        assertTrue(ready.file.isFile)
        assertTrue(ready.file.delete())
        Files.createSymbolicLink(ready.file.toPath(), external.toPath())
        assertTrue(delivery().execute(input(ready)) is Result.Blocked)
        assertTrue(Files.isSymbolicLink(ready.file.toPath()))
        assertTrue(external.isFile)
        assertEquals(Evidence.CONFIRMED, ledger.inspect(ready.route))
        assertEquals(1, remote.gets.size)
    }

    @Test
    fun concurrentInvocationsUseOneArtifactLeaseAndOnlyOneTransport() = runBlocking {
        val ready = stage()
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val transport = V2VoiceTransport { cfg, path, bytes ->
            entered.complete(Unit)
            proceed.await()
            remote.upload(cfg, path, bytes)
        }
        val first = async { delivery(transport = transport).execute(input(ready)) }
        entered.await()
        try {
            assertTrue(delivery().execute(input(ready)) is Result.Retry)
            assertTrue(ready.file.isFile)
            assertTrue(remote.creates.isEmpty())
        } finally {
            proceed.complete(Unit)
        }
        assertTrue(first.await() is Result.Completed)
        assertEquals(1, remote.creates.size)
    }

    @Test
    fun recoveryPreservesActiveAndReenqueuesMissingOrTerminalWorkUsingKeep() {
        val ready = stage()
        assertEquals(Status.ENQUEUED, recover().single().status)
        val name = V2VoiceWork.uniqueName(id)
        for (state in listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)) {
            work.histories[name] = listOf(WorkInfo(work.requests.single().id, state, emptySet()))
            assertEquals(Status.ACTIVE, recover().single().status)
        }
        for (state in listOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED, WorkInfo.State.SUCCEEDED)) {
            work.histories[name] = listOf(WorkInfo(UUID.randomUUID(), state, emptySet()))
            assertEquals(Status.ENQUEUED, recover().single().status)
        }
        assertEquals(4, work.requests.size)
        assertTrue(work.requests.all { it.workSpec.input.keyValueMap == input(ready) })
        assertEquals(Evidence.MISSING, ledger.inspect(ready.route))
        work.failLookup = true
        assertEquals(Status.DEFERRED, recover().single().status)
        assertEquals(4, work.requests.size)
    }

    @Test
    fun unconfirmedEnqueueDefersRepeatedScansAndOrphansArePreserved() {
        val ready = stage()
        val orphan = File(files.root, "$V2_VOICE_NAMESPACE/${secondId.value}").apply { mkdir() }
        val unexpected = File(orphan, "orphan.wav").apply { writeText("retain") }
        work.publish = false
        work.completion = SurveyUploadWork.EnqueueCompletion.TIMED_OUT
        repeat(2) {
            val results = recover().associateBy { it.recordingName }
            assertEquals(Status.DEFERRED, results.getValue(id.value).status)
            assertEquals(Status.BLOCKED, results.getValue(secondId.value).status)
        }
        assertEquals(1, work.requests.size)
        assertEquals("retain", unexpected.readText())
        work.histories[V2VoiceWork.uniqueName(id)] =
            listOf(WorkInfo(work.requests.single().id, WorkInfo.State.ENQUEUED, emptySet()))
        assertEquals(Status.ACTIVE, recover().first { it.recordingName == id.value }.status)
        assertTrue(ready.file.isFile)
    }

    @Test
    fun exactInterruptedPhase4aPublicationIsRecoveredOnlyWithFrozenCredentials() {
        assertThrows(IOException::class.java) { stage(failAt = V2VoiceStagingCheckpoint.ROUTE_WRITTEN_BEFORE_SYNC) }
        assertEquals(Status.DEFERRED, recover { null }.single().status)
        assertTrue(work.requests.isEmpty())
        assertEquals(Status.ENQUEUED, recover().single().status)
        val staged = store.inspect(id) as Ready
        assertEquals(input(staged), work.requests.single().workSpec.input.keyValueMap)
        assertEquals(Status.ACTIVE, recover().single().status)
    }

    @Test
    fun jsonUsesBothDistinctBasenamesAfterOneWavIsCleanedAndBlocksCorruptEvidence() = runBlocking {
        val first = stage()
        val second = stage(secondId)
        val refs = listOf(first.audioRef(1), second.audioRef(2))
        assertTrue(delivery().execute(input(first)) is Result.Completed)
        assertFalse(first.file.exists())
        assertTrue(second.file.isFile)
        val json = stageJson(refs) as V2SurveyJsonStager.Result.Ready
        val bytes = json.file.readBytes()
        val document = Json.parseToJsonElement(json.file.readText()).jsonObject
        val answer = document.getValue("answers").jsonObject.getValue("Q11").jsonObject
        val expected = listOf(first.route.basename, second.route.basename)
        assertEquals(expected, answer.getValue("audio").jsonArray.map { it.jsonObject.getValue("file").jsonPrimitive.content })
        assertEquals(expected, document.getValue("voice_files").jsonArray.map { it.jsonObject.getValue("file").jsonPrimitive.content })
        assertTrue(stageJson(refs) is V2SurveyJsonStager.Result.Ready)
        ledgerFile().writeText("{")
        assertEquals(V2SurveyJsonStager.Result.Unavailable(V2JsonRecoveryState.BLOCKED_VOICE_REFERENCES), stageJson(refs))
        assertArrayEquals(bytes, json.file.readBytes())
    }

    @Test
    fun jsonLedgerCannotAuthorizeVoiceCleanupOrMissingReference() = runBlocking {
        val ready = stage()
        val json = stageJson(listOf(ready.audioRef(1))) as V2SurveyJsonStager.Result.Ready
        V2SurveyJsonLedger(files.root) { false }.recordConfirmed(json.route)
        assertEquals(Evidence.MISSING, ledger.inspect(ready.route))
        assertTrue(delivery(resolve = { null }).execute(input(ready)) is Result.Retry)
        assertTrue(ready.file.isFile)
        Files.move(pending(ready).toPath(), delivered(ready).toPath())
        assertTrue(ready.file.delete())
        assertEquals(V2SurveyJsonStager.Result.Unavailable(V2JsonRecoveryState.BLOCKED_VOICE_REFERENCES),
            stageJson(listOf(ready.audioRef(1))))
        assertEquals(Status.BLOCKED, recover().single().status)
        assertTrue(remote.gets.isEmpty())
    }

    private fun stage(
        recording: V2RecordingId = id,
        destination: V2SurveyJsonDestination = target,
        local: Boolean = true,
        failAt: V2VoiceStagingCheckpoint? = null
    ) = V2VoiceStager(store, local) {
        if (it == failAt) throw IOException("stage interruption")
    }.stage(source(recording), surveyId, "Q11", recording, tag, destination) as Ready

    private fun source(recording: V2RecordingId): File {
        val size = 8
        val bytes = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        bytes.put("data".toByteArray()).putInt(size)
        repeat(size / 2) { bytes.putShort(7) }
        return files.newFile("source-${recording.value}.wav").apply { writeBytes(bytes.array()) }
    }

    private fun alterWav(ready: Ready) = ready.file.writeBytes(ready.file.readBytes().apply { this[lastIndex] = 8 })
    private fun pending(ready: Ready) = File(ready.file.parentFile, V2_VOICE_ROUTE)
    private fun delivered(ready: Ready) = File(ready.file.parentFile, V2_VOICE_DELIVERED)
    private fun ledgerFile() = File(files.root, "${V2VoiceLedger.NAMESPACE}/${id.value}.json")
    private fun input(ready: Ready) = V2VoiceWork.metadata(ready.route)

    private fun stageJson(refs: List<SurveyViewModel.AudioRef>) =
        V2SurveyJsonStager(V2SurveyJsonRouteStore(files.root)).stage(
            SurveyFinalizationSnapshot(surveyId, emptyMap(), emptyMap(), emptyMap(), refs, "{}", emptyMap()),
            tag, target, "stamp"
        )

    private fun delivery(
        resolve: () -> GitHubUploader.GitHubConfig? = { config },
        usingLedger: V2VoiceDeliveryLedger = ledger,
        usingExecution: V2VoiceExecution = execution,
        competing: (V2RecordingId) -> Boolean = { false },
        transport: V2VoiceTransport = remote,
        delete: (Path) -> Unit = { Files.delete(it) },
        sync: (Path) -> Unit = ::syncDirectory,
        afterRetire: () -> Unit = {}
    ) = V2VoiceDelivery(store, usingLedger, usingExecution, true, resolve, competing, transport, delete, sync, afterRetire)

    private fun recover(resolve: () -> GitHubUploader.GitHubConfig? = { config }) =
        V2VoiceRecovery(store, ledger, true, resolve, V2VoiceScheduler(files.root, work, true)::schedule).reconcile()

    private class FakeRemote : V2VoiceTransport {
        val contents = mutableMapOf<String, ByteArray>()
        val creates = mutableListOf<String>()
        val gets = mutableListOf<String>()
        var onCreate: (String, ByteArray) -> Unit = { path, bytes ->
            check(contents.putIfAbsent(path, bytes.copyOf()) == null)
        }

        override suspend fun upload(config: GitHubUploader.GitHubConfig, path: String, bytes: ByteArray) {
            GitHubUploader.createArtifactWithConflictRetry(
                localSha = gitBlobSha(bytes),
                getExistingSha = { gets += path; contents[path]?.let(::gitBlobSha) },
                create = {
                    creates += path
                    onCreate(path, bytes)
                    GitHubUploader.UploadResult(null, "created")
                },
                waitForConflict = {}
            )
        }

        private fun gitBlobSha(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update("blob ${bytes.size}\u0000".toByteArray())
            return digest.digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        }
    }

    private class FakeWork : SurveyUploadWork.WorkManagerOperations {
        val requests = mutableListOf<OneTimeWorkRequest>()
        val histories = mutableMapOf<String, List<WorkInfo>>()
        var failLookup = false
        var publish = true
        var completion = SurveyUploadWork.EnqueueCompletion.SUCCEEDED
        override fun getWorkInfoById(id: UUID): WorkInfo? = error("Uses unique-work lookup")

        override fun getWorkInfosForUniqueWork(uniqueWorkName: String): List<WorkInfo> {
            if (failLookup) throw TimeoutException("lookup")
            return histories[uniqueWorkName].orEmpty()
        }

        override fun enqueueUniqueWork(
            uniqueWorkName: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest
        ): SurveyUploadWork.EnqueueOperation {
            assertEquals(ExistingWorkPolicy.KEEP, policy)
            requests += request
            if (publish) histories[uniqueWorkName] = listOf(WorkInfo(request.id, WorkInfo.State.ENQUEUED, emptySet()))
            return object : SurveyUploadWork.EnqueueOperation {
                override fun awaitCompletion(timeoutMs: Long) = completion
            }
        }
    }
}
