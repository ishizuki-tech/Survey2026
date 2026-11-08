package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import androidx.work.ListenableWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import com.negi.survey.net.V2SurveyJsonRecovery.Status
import com.negi.survey.net.V2SurveyJsonRouteStore.ReadResult
import com.negi.survey.net.V2SurveyJsonStager.Result.Ready
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.vm.SurveyFinalizationSnapshot
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class V2SurveyJsonRecoveryTest {
    @get:Rule val files = TemporaryFolder()

    private val id = "01a1b874-6bff-7abc-9def-0123456789ab"
    private val nextId = "01a1b874-6c00-7abc-9def-0123456789ab"
    private val destination = V2SurveyJsonDestination(
        V2SurveyJsonDestination.Identity.Development, "ishizuki-tech", "SurveyExports-Dev", "main", ""
    )
    private val config = GitHubUploader.GitHubConfig(destination.owner, destination.repository, "test", "main", "")
    private val work = FakeWork()
    private var historical = false
    private var owner: String? = null
    private val prefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val authority = SurveyUploadExecutionAuthority(prefs)
    private val store get() = V2SurveyJsonRouteStore(files.root)
    private val ledger get() = V2SurveyJsonLedger(files.root) { historical }

    init {
        every { prefs.getString(any(), null) } answers { owner }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers { owner = secondArg(); editor }
        every { editor.remove(any()) } answers { owner = null; editor }
        every { editor.commit() } returns true
    }

    @Test
    fun emptyScanIsReadOnlyAndDoesNotResolveCredentialsOrWorkManager() {
        assertTrue(store.scanRecovery().isEmpty())
        assertTrue(recover(resolve = { error("unexpected config lookup") }).isEmpty())
        assertTrue(files.root.list().orEmpty().isEmpty())
        assertTrue(work.lookups.isEmpty())
    }

    @Test
    fun discoveryIncludesPendingDeliveredAndOrphansWithoutChangingListPending() {
        val staged = stage()
        val delivered = stage(nextId)
        confirmAndRetire(delivered)
        val orphanId = "01a1b874-6c01-7abc-9def-0123456789ab"
        val orphan = File(files.root, "$V2_JSON_NAMESPACE/$orphanId").apply { mkdir() }
        File(orphan, "unexpected.tmp").writeText("retain")

        val scan = store.scanRecovery().associateBy { it.directoryName }
        assertEquals(ReadResult.Pending(staged.route), scan.getValue(id).route)
        assertEquals(ReadResult.Delivered(delivered.route), scan.getValue(nextId).route)
        assertEquals(ReadResult.Missing, scan.getValue(orphanId).route)
        assertEquals(setOf("unexpected.tmp"), scan.getValue(orphanId).filenames)
        assertFalse(nextId in store.listPending())

        val outcomes = recover().associateBy { it.directoryName }
        assertEquals(Status.ENQUEUED, outcomes.getValue(id).status)
        assertEquals(Status.ENQUEUED, outcomes.getValue(nextId).status)
        assertEquals(Status.BLOCKED, outcomes.getValue(orphanId).status)
        assertEquals("retain", File(orphan, "unexpected.tmp").readText())
    }

    @Test
    fun missingModifiedAndUnexpectedPayloadsStayBlocked() {
        val staged = stage()
        val original = staged.file.readBytes()
        assertTrue(staged.file.delete())
        assertEquals(Status.BLOCKED, status())
        staged.file.writeBytes(original)
        staged.file.appendText(" ")
        assertEquals(Status.BLOCKED, status())
        assertTrue(staged.file.readBytes().size > original.size)
        staged.file.writeBytes(original)
        val extra = File(staged.file.parentFile, "foreign.tmp").apply { writeText("retain") }
        assertEquals(Status.BLOCKED, status())
        assertEquals("retain", extra.readText())
        assertTrue(staged.file.isFile)
        assertTrue(work.requests.isEmpty())
    }

    @Test
    fun malformedDuplicatedAndMisnamedRouteEvidenceIsPreserved() {
        val staged = stage()
        val routeFile = File(staged.file.parentFile, V2_PENDING_ROUTE)
        val bytes = routeFile.readBytes()
        routeFile.writeText("{")
        assertEquals(Status.BLOCKED, status())
        assertEquals("{", routeFile.readText())
        routeFile.writeBytes(bytes)
        val copy = routeFile.copyTo(File(staged.file.parentFile, V2_DELIVERED_ROUTE))
        assertEquals(Status.BLOCKED, status())
        val misnamed = File(checkNotNull(staged.file.parentFile).parentFile, nextId.uppercase()).apply { mkdir() }
        File(misnamed, "orphan.tmp").writeText("retain")
        assertTrue(recover().all { it.status == Status.BLOCKED })
        assertTrue(copy.isFile)
        assertTrue(staged.file.isFile)
        assertTrue(work.requests.isEmpty())
    }

    @Test
    fun matchingTemporaryJsonRecoversButTruncatedWriteIsBlocked() {
        val interrupted = V2SurveyJsonRouteStore(files.root) {
            if (it == V2JsonStagingCheckpoint.ROUTE_PUBLISHED) throw IOException("stop")
        }
        assertThrows(IOException::class.java) {
            V2SurveyJsonStager(interrupted).stage(snapshot(id), DeviceUploadTag("device"), destination, "stamp")
        }
        val temp = File(files.root, "$V2_JSON_NAMESPACE/$id/$V2_PAYLOAD_TEMP")
        val bytes = temp.readBytes()
        temp.writeBytes(bytes.copyOf(5))
        assertEquals(Status.BLOCKED, status())
        assertTrue(temp.isFile)
        temp.writeBytes(bytes)
        assertEquals(Status.ENQUEUED, status())
        assertFalse(temp.exists())
    }

    @Test
    fun pendingConfirmedRecoveryDoesNotResolveCredentials() {
        val staged = stage()
        ledger.recordConfirmed(staged.route)
        assertEquals(Status.ENQUEUED, status(resolve = { error("Cleanup is local") }))
        assertEquals(V2SurveyJsonWork.metadata(staged.route), work.requests.single().workSpec.input.keyValueMap)
    }

    @Test
    fun confirmedPendingMatchingAuthorityResumesLocallyDespiteChangedConfiguration() = runBlocking {
        val staged = stage()
        ledger.recordConfirmed(staged.route)
        assertEquals(SurveyUploadExecutionAuthority.Claim.AUTHORIZED, authority.claimV2(staged.route))
        assertEquals(Status.ENQUEUED, status(resolve = { error("Recovery must not require credentials") }))
        val worker = worker {
            V2SurveyJsonDelivery(
                V2SurveyJsonPreflight(files.root, true), authority, ledger,
                { config.copy(repo = "elsewhere") },
                V2SurveyJsonTransport { _, _, _, _ -> error("Confirmed retry must not use HTTP") }
            )
        }
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        assertNull(owner)
        assertFalse(staged.file.exists())
        assertEquals(ReadResult.Delivered(staged.route), store.read(id))
    }

    @Test
    fun deliveredRemainingJsonResumesAfterRestartWithoutInvokingDelivery() = runBlocking {
        val staged = stage()
        confirmAndRetire(staged)
        val delivered = File(staged.file.parentFile, V2_DELIVERED_ROUTE)
        val routeBytes = delivered.readBytes()
        val ledgerFile = File(files.root, "${V2SurveyJsonLedger.NAMESPACE}/$id.json")
        val ledgerBytes = ledgerFile.readBytes()

        assertEquals(Status.ENQUEUED, status(resolve = { error("Cleanup is local") }))
        assertTrue(worker { error("Delivered cleanup must not invoke delivery") }.doWork()
            is ListenableWorker.Result.Success)
        assertFalse(staged.file.exists())
        assertEquals(Status.COMPLETE, status(resolve = { error("Completion is local") }))
        assertEquals(1, work.requests.size)
        assertArrayEquals(routeBytes, delivered.readBytes())
        assertArrayEquals(ledgerBytes, ledgerFile.readBytes())
    }

    @Test
    fun deliveredWithoutJsonOnlyCompletesWithMatchingDurableEvidence() {
        val staged = stage()
        confirmAndRetire(staged)
        assertTrue(staged.file.delete())
        assertEquals(Status.COMPLETE, status())
        assertTrue(work.lookups.isEmpty())
        historical = true
        assertEquals(Status.BLOCKED, status())
        assertTrue(work.requests.isEmpty())
    }

    @Test
    fun conflictingAuthorityAndLedgerBlockRecoveryWithoutTouchingFiles() {
        val staged = stage()
        for (pin in listOf("CANONICAL", "LEGACY", "corrupt")) {
            owner = pin
            assertEquals(Status.BLOCKED, status())
            assertEquals(pin, owner)
        }
        owner = null
        historical = true
        assertEquals(Status.BLOCKED, status())
        historical = false
        ledger.recordConfirmed(staged.route.copy(destination = destination.copy(branch = "different")))
        assertEquals(Status.BLOCKED, status())
        assertTrue(staged.file.isFile)
        assertTrue(work.requests.isEmpty())
    }

    @Test
    fun activeStatesArePreservedAndRepeatedStartupAndSystemScansUseTheSameRequest() {
        val staged = stage()
        assertEquals(Status.ENQUEUED, status())
        val request = work.requests.single()
        for (state in listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)) {
            work.history = listOf(WorkInfo(request.id, state, emptySet()))
            assertEquals(Status.ACTIVE, status())
        }
        assertEquals(1, work.requests.size)
        assertEquals(V2SurveyJsonWork.metadata(staged.route), request.workSpec.input.keyValueMap)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(work.lookups.all { it == "gh_survey_v2:$id" })
    }

    @Test
    fun failedCancelledAndSucceededHistoryDoNotSuppressNeededRecovery() {
        stage()
        for (state in listOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED, WorkInfo.State.SUCCEEDED)) {
            work.history = listOf(WorkInfo(UUID.randomUUID(), state, emptySet()))
            assertEquals(Status.ENQUEUED, status())
        }
        assertEquals(3, work.requests.size)
    }

    @Test
    fun workManagerLookupFailureDoesNotEnqueue() {
        stage()
        work.failLookup = true
        assertEquals(Status.DEFERRED, status())
        assertTrue(work.requests.isEmpty())
    }

    @Test
    fun missingCredentialsOrChangedDestinationNeverRedirectsUnconfirmedWork() {
        val staged = stage(target = destination.copy(pathPrefix = "frozen-prefix"))
        assertEquals(Status.DEFERRED, status(resolve = { null }))
        assertEquals(Status.DEFERRED, status())
        assertTrue(work.requests.isEmpty())
        assertEquals(Status.ENQUEUED, status(resolve = { config.copy(pathPrefix = "frozen-prefix") }))
        assertEquals(V2SurveyJsonWork.metadata(staged.route), work.requests.single().workSpec.input.keyValueMap)
    }

    @Test
    fun recoveryIsIsolatedFromV1AndLocalProductionIsProhibited() {
        val v1Dir = File(files.root, "pending_uploads").apply { mkdir() }
        val v1File = File(v1Dir, "historical.json").apply { writeText("""{"survey_id":"$id"}""") }
        val staged = stage(target = destination.copy(
            identity = V2SurveyJsonDestination.Identity.Production, repository = "SurveyExports"
        ))
        assertEquals(Status.BLOCKED, status())
        assertTrue(work.requests.isEmpty())
        assertTrue(staged.file.isFile)
        assertTrue(v1File.isFile)
        assertEquals(listOf(v1File), PendingSurveyUploads.discoverPendingSurveys(v1Dir)
            .candidates.map { it.canonicalFile })
    }

    private fun stage(surveyId: String = id, target: V2SurveyJsonDestination = destination) =
        V2SurveyJsonStager(store).stage(snapshot(surveyId), DeviceUploadTag("device"), target, "stamp") as Ready

    private fun snapshot(surveyId: String) =
        SurveyFinalizationSnapshot(surveyId, emptyMap(), emptyMap(), emptyMap(), emptyList(), "{}", emptyMap())

    private fun confirmAndRetire(staged: Ready) {
        ledger.recordConfirmed(staged.route)
        store.removeAfterConfirmedDelivery(staged.route)
    }

    private fun status(resolve: () -> GitHubUploader.GitHubConfig? = { config }) = recover(resolve).single().status

    private fun recover(resolve: () -> GitHubUploader.GitHubConfig? = { config }): List<V2SurveyJsonRecovery.Outcome> {
        val scheduler = V2SurveyJsonScheduler(files.root, work, localOrDebugBuild = true)
        return V2SurveyJsonRecovery(store, ledger, authority, true, resolve) { route, file ->
            scheduler.scheduleReady(route, file, recoverTerminalSuccess = true)
        }.reconcile()
    }

    private fun worker(delivery: (Context) -> V2SurveyJsonDelivery): GitHubUploadWorker {
        val request = work.requests.single()
        val params = mockk<WorkerParameters>()
        every { params.inputData } returns request.workSpec.input
        every { params.id } returns request.id
        return GitHubUploadWorker(mockk<Context>(), params, delivery,
            { _, _ -> V2SurveyJsonRetirement(store, ledger, authority, { false }) })
    }

    private class FakeWork : SurveyUploadWork.WorkManagerOperations {
        val requests = mutableListOf<OneTimeWorkRequest>()
        val lookups = mutableListOf<String>()
        var history: List<WorkInfo>? = null
        private val byName = mutableMapOf<String, List<WorkInfo>>()
        var failLookup = false

        override fun getWorkInfoById(id: UUID): WorkInfo? = error("Uses unique-work lookup")

        override fun getWorkInfosForUniqueWork(uniqueWorkName: String): List<WorkInfo> {
            lookups += uniqueWorkName
            if (failLookup) throw TimeoutException("lookup unavailable")
            return history ?: byName[uniqueWorkName].orEmpty()
        }

        override fun enqueueUniqueWork(
            uniqueWorkName: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest
        ): SurveyUploadWork.EnqueueOperation {
            assertEquals(ExistingWorkPolicy.KEEP, policy)
            requests += request
            val active = listOf(WorkInfo(request.id, WorkInfo.State.ENQUEUED, emptySet()))
            if (history != null) history = active else byName[uniqueWorkName] = active
            return object : SurveyUploadWork.EnqueueOperation {
                override fun awaitCompletion(timeoutMs: Long) = SurveyUploadWork.EnqueueCompletion.SUCCEEDED
            }
        }
    }
}
