package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.negi.survey.net.GitHubUploader.SurveyCreateResult
import com.negi.survey.net.GitHubUploader.SurveyRemoteRecord
import com.negi.survey.net.SurveyUploadExecutionAuthority.Claim
import com.negi.survey.net.SurveyUploadExecutionAuthority.Owner
import com.negi.survey.net.SurveyUploadExecutionAuthority.Protocol
import com.negi.survey.net.V2SurveyDeliveryLedger.Evidence
import com.negi.survey.net.V2SurveyJsonDelivery.Result
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.vm.SurveyFinalizationSnapshot
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class V2SurveyJsonDeliveryTest {
    @get:Rule val files = TemporaryFolder()

    private val id = "01a1b874-6bff-7abc-9def-0123456789ab"
    private val destination = V2SurveyJsonDestination(
        V2SurveyJsonDestination.Identity.Development, "ishizuki-tech", "SurveyExports-Dev", "field/test", "exports"
    )
    private val tag = DeviceUploadTag("Pixel_9a_A13F82C4D9E1")
    private val config = GitHubUploader.GitHubConfig(
        destination.owner, destination.repository, "fake-credential", destination.branch, destination.pathPrefix
    )
    private val prefs = AuthorityPreferences()
    private var historicalUploaded = false
    private val ledger get() = V2SurveyJsonLedger(files.root) { historicalUploaded }
    private val authority get() = SurveyUploadExecutionAuthority(prefs.prefs) {
        V2SurveyJsonLedger.hasV2Evidence(files.root, it)
    }
    private val remote = FakeRemote()

    @Test
    fun createOnlyConfirmsExactUtf8PayloadAtOneFrozenTargetAndRetainsLocalEvidence() = runBlocking {
        val staged = stage()
        val json = staged.file.readBytes()
        val routeBytes = routeFile(staged).readBytes()
        remote.onCreate = { path, content ->
            assertEquals(Protocol.IMMUTABLE_V2, (authority.ownerFor(id) as Owner.VALID).protocol)
            assertTrue(prefs.durable.containsKey(authorityKey()))
            assertEquals(Evidence.MISSING, ledger.inspect(staged.route))
            remote.create(path, content)
        }

        assertEquals(Result.Confirmed(staged.route.remoteTargetPath), delivery().execute(input(staged)))
        assertEquals(staged.route.remoteTargetPath, remote.gets.single())
        assertEquals(staged.route.remoteTargetPath, remote.creates.single())
        assertEquals("exports/2026-11-07/survey/${staged.file.name}", remote.creates.single())
        assertArrayEquals(json, remote.records.values.single().content.toByteArray(Charsets.UTF_8))
        assertEquals(config, remote.configs.single())
        assertEquals(Evidence.CONFIRMED, ledger.inspect(staged.route))
        assertEquals(Owner.NONE, authority.ownerFor(id))
        assertFalse(prefs.durable.containsKey(authorityKey()))
        assertArrayEquals(json, staged.file.readBytes())
        assertArrayEquals(routeBytes, routeFile(staged).readBytes())
        assertTrue(V2SurveyJsonRouteStore(files.root).read(id) is V2SurveyJsonRouteStore.ReadResult.Pending)
    }

    @Test
    fun matchingExistingContentConfirmsWithoutCreate() = runBlocking {
        val staged = stage()
        remote.records[staged.route.remoteTargetPath] = SurveyRemoteRecord(staged.file.readText())
        assertTrue(delivery().execute(input(staged)) is Result.Confirmed)
        assertTrue(remote.creates.isEmpty())
        assertEquals(Evidence.CONFIRMED, ledger.inspect(staged.route))
    }

    @Test
    fun conflictingRemoteJsonFailsClosedAndRetainsPinAndPayload() = runBlocking {
        val staged = stage()
        val different = """{"survey_id":"$id","different":true}"""
        remote.records[staged.route.remoteTargetPath] = SurveyRemoteRecord(different)
        assertTrue(delivery().execute(input(staged)) is Result.Rejected)
        assertTrue(remote.creates.isEmpty())
        assertEquals(different, remote.records.values.single().content)
        assertUnconfirmedAndRetained(staged)
    }

    @Test
    fun conflict409WithMatchingReadbackConfirms() = runBlocking {
        val staged = stage()
        remote.onCreate = { path, content ->
            remote.records[path] = SurveyRemoteRecord(content)
            SurveyCreateResult.Reconcile(409)
        }
        assertTrue(delivery().execute(input(staged)) is Result.Confirmed)
        assertEquals(1, remote.creates.size)
        assertEquals(listOf(staged.route.remoteTargetPath, staged.route.remoteTargetPath), remote.gets)
    }

    @Test
    fun conflict409WithoutMatchingTargetRetriesTheSamePath() = runBlocking {
        val staged = stage()
        remote.onCreate = { _, _ -> SurveyCreateResult.Reconcile(409) }
        assertTrue(delivery().execute(input(staged)) is Result.Retry)
        assertTrue(remote.creates.size > 1)
        assertUnconfirmedAndRetained(staged)
        remote.onCreate = remote::create
        assertTrue(delivery().execute(input(staged)) is Result.Confirmed)
        assertEquals(setOf(staged.route.remoteTargetPath), (remote.creates + remote.gets).toSet())
        assertEquals(1, remote.records.size)
    }

    @Test
    fun http422WithoutMatchingTargetFailsClosed() = runBlocking {
        val staged = stage()
        remote.onCreate = { _, _ -> SurveyCreateResult.Reconcile(422) }
        assertTrue(delivery().execute(input(staged)) is Result.Rejected)
        assertEquals(1, remote.creates.size)
        assertEquals(2, remote.gets.size)
        assertUnconfirmedAndRetained(staged)
    }

    @Test
    fun ambiguousCreateRetriesWithReadbackBeforeAnyFurtherCreate() = runBlocking {
        val staged = stage()
        remote.onCreate = { _, _ -> SurveyCreateResult.Reconcile(null) }
        assertTrue(delivery().execute(input(staged)) is Result.Retry)
        assertUnconfirmedAndRetained(staged)
        remote.records[staged.route.remoteTargetPath] = SurveyRemoteRecord(staged.file.readText())
        assertTrue(delivery().execute(input(staged)) is Result.Confirmed)
        assertEquals(1, remote.creates.size)
        assertEquals(setOf(staged.route.remoteTargetPath), remote.gets.toSet())
    }

    @Test
    fun timeoutAndTransientHttpRetryWithoutChangingTarget() = runBlocking {
        val staged = stage()
        remote.onGet = { throw SocketTimeoutException("test timeout") }
        assertTrue(delivery().execute(input(staged)) is Result.Retry)
        remote.onGet = { remote.records[it] }
        remote.onCreate = { _, _ -> throw GitHubUploader.HttpFailureException(503, "") }
        assertTrue(delivery().execute(input(staged)) is Result.Retry)
        assertUnconfirmedAndRetained(staged)
        remote.onCreate = remote::create
        assertTrue(delivery().execute(input(staged)) is Result.Confirmed)
        assertEquals(setOf(staged.route.remoteTargetPath), (remote.creates + remote.gets).toSet())
    }

    @Test
    fun unavailableCredentialsDeferWithoutAuthorityOrHttp() = runBlocking {
        val staged = stage()
        assertTrue(delivery(resolve = { null }).execute(input(staged)) is Result.Retry)
        assertTrue(delivery(resolve = { config.copy(token = "") }).execute(input(staged)) is Result.Retry)
        assertNoTransportOrAuthority()
    }

    @Test
    fun changedDestinationNeverRedirectsAndLocalProductionIsRejected() = runBlocking {
        val staged = stage()
        listOf(config.copy(owner = "other"), config.copy(repo = "SurveyExports"),
            config.copy(branch = "main"), config.copy(pathPrefix = "other")).forEach { changed ->
            assertTrue(delivery(resolve = { changed }).execute(input(staged)) is Result.Rejected)
        }
        assertNoTransportOrAuthority()

        val production = stage(destination.copy(identity = V2SurveyJsonDestination.Identity.Production,
            repository = "SurveyExports"), id = "01a1b874-6c00-7abc-9def-0123456789ab")
        assertTrue(delivery(resolve = { error("Production must fail preflight") })
            .execute(input(production)) is Result.Rejected)
        assertTrue(remote.gets.isEmpty())
    }

    @Test
    fun invalidRouteAndPayloadChangesAfterPreflightNeverReachTransport() = runBlocking {
        val staged = stage()
        val route = routeFile(staged)
        val before = route.readBytes()
        route.writeText("{")
        assertTrue(delivery().execute(input(staged)) is Result.Rejected)
        route.writeBytes(before)
        val original = staged.file.readBytes()
        assertTrue(delivery(resolve = {
            staged.file.appendText(" ")
            config
        }).execute(input(staged)) is Result.Rejected)
        staged.file.writeBytes(original)
        assertTrue(delivery(resolve = {
            route.writeText("{")
            config
        }).execute(input(staged)) is Result.Rejected)
        assertNoTransportOrAuthority()
    }

    @Test
    fun canonicalAndLegacyPinsBlockV2AndV2PinsBlockBothOlderProtocols() = runBlocking {
        val staged = stage()
        for (protocol in listOf(Protocol.CANONICAL, Protocol.LEGACY)) {
            assertEquals(Claim.AUTHORIZED, authority.claim(id, protocol))
            assertTrue(delivery().execute(input(staged)) is Result.Rejected)
            assertTrue(authority.clearForRecordedUpload(id, protocol))
        }
        assertEquals(Claim.AUTHORIZED, authority.claimV2(staged.route))
        assertEquals(Claim.AUTHORIZED, authority.claimV2(staged.route))
        assertEquals(Claim.CORRUPT_OR_UNKNOWN_OWNER,
            authority.claimV2(staged.route.copy(payloadSha256 = "0".repeat(64))))
        for (protocol in listOf(Protocol.CANONICAL, Protocol.LEGACY)) {
            assertEquals(Claim.BLOCKED_BY_OTHER_PROTOCOL(Protocol.IMMUTABLE_V2), authority.claim(id, protocol))
        }
        assertTrue(remote.gets.isEmpty())
        assertTrue(remote.creates.isEmpty())
    }

    @Test
    fun corruptV2AuthorityFailsClosedAndIsRetained() = runBlocking {
        val staged = stage()
        prefs.visible[authorityKey()] = "IMMUTABLE_V2:corrupt"
        assertTrue(delivery().execute(input(staged)) is Result.Rejected)
        assertEquals("IMMUTABLE_V2:corrupt", prefs.visible[authorityKey()])
        assertTrue(remote.gets.isEmpty())
        assertTrue(remote.creates.isEmpty())
    }

    @Test
    fun failedAuthorityCommitDoesNotAuthorizeFromItsInMemoryValue() = runBlocking {
        val staged = stage()
        prefs.failCommit = true
        repeat(2) { assertTrue(delivery().execute(input(staged)) is Result.Retry) }
        assertTrue(prefs.visible.containsKey(authorityKey()))
        assertFalse(prefs.durable.containsKey(authorityKey()))
        assertTrue(remote.gets.isEmpty())
        prefs.failCommit = false
        assertTrue(delivery().execute(input(staged)) is Result.Confirmed)
        assertEquals(1, remote.creates.size)
    }

    @Test
    fun ledgerCommitFailureRetainsAuthorityAndRecoversThroughRemoteGet() = runBlocking {
        val staged = stage()
        val failingLedger = object : V2SurveyDeliveryLedger by ledger {
            override fun recordConfirmed(route: V2SurveyJsonRoute) { throw IOException("injected ledger failure") }
        }
        assertTrue(delivery(usingLedger = failingLedger).execute(input(staged)) is Result.Retry)
        assertUnconfirmedAndRetained(staged)
        assertTrue(delivery().execute(input(staged)) is Result.Confirmed)
        assertEquals(1, remote.creates.size)
        assertEquals(2, remote.gets.size)
    }

    @Test
    fun interruptionAfterRemoteConfirmationBeforeCommitReconcilesWithoutAnotherCreate() = runBlocking {
        val staged = stage()
        val interrupted = delivery(afterRemote = { throw IOException("injected stop before ledger") })
        assertTrue(interrupted.execute(input(staged)) is Result.Retry)
        assertUnconfirmedAndRetained(staged)
        assertTrue(delivery().execute(input(staged)) is Result.Confirmed)
        assertEquals(1, remote.creates.size)
    }

    @Test
    fun failureAfterLedgerCommitBeforeAuthorityReleaseRetriesLocallyEvenWithoutCredentials() = runBlocking {
        val staged = stage()
        prefs.failRelease = true
        assertTrue(delivery().execute(input(staged)) is Result.Retry)
        assertEquals(Evidence.CONFIRMED, ledger.inspect(staged.route))
        assertTrue(prefs.durable.containsKey(authorityKey()))
        assertFalse(prefs.visible.containsKey(authorityKey()))
        assertTrue(delivery(resolve = { null }).execute(input(staged)) is Result.Retry)
        assertEquals(1, remote.gets.size)
        assertEquals(1, remote.creates.size)
        prefs.failRelease = false
        assertTrue(delivery(resolve = { null }).execute(input(staged)) is Result.Confirmed)
        assertFalse(prefs.durable.containsKey(authorityKey()))
        assertEquals(1, remote.gets.size)
        assertEquals(1, remote.creates.size)
        assertTrue(staged.file.isFile)
        assertTrue(routeFile(staged).isFile)
    }

    @Test
    fun confirmedLedgerSuppressesAllFurtherHttpAndFencesOlderProtocolsAfterRelease() = runBlocking {
        val staged = stage()
        repeat(2) { assertTrue(delivery().execute(input(staged)) is Result.Confirmed) }
        assertEquals(1, remote.gets.size)
        assertEquals(1, remote.creates.size)
        assertEquals(Owner.NONE, authority.ownerFor(id))
        assertEquals(Claim.BLOCKED_BY_OTHER_PROTOCOL(Protocol.IMMUTABLE_V2), authority.claim(id, Protocol.CANONICAL))
        assertEquals(Claim.BLOCKED_BY_OTHER_PROTOCOL(Protocol.IMMUTABLE_V2), authority.claim(id, Protocol.LEGACY))
    }

    @Test
    fun historicalIdOnlyAndConflictingV2EvidenceNeverAuthorizeDelivery() = runBlocking {
        val staged = stage()
        historicalUploaded = true
        assertTrue(delivery().execute(input(staged)) is Result.Rejected)
        assertNoTransportOrAuthority()
        historicalUploaded = false

        ledger.recordConfirmed(staged.route.copy(destination = destination.copy(branch = "old")))
        assertEquals(Evidence.CONFLICT, ledger.inspect(staged.route))
        assertTrue(delivery().execute(input(staged)) is Result.Rejected)
        assertNoTransportOrAuthority()
        assertTrue(staged.file.isFile)
        assertTrue(routeFile(staged).isFile)
    }

    @Test
    fun corruptV2LedgerEvidenceFencesBothProtocolsAndCannotBeReplaced() = runBlocking {
        val staged = stage()
        val directory = File(files.root, V2SurveyJsonLedger.NAMESPACE).apply { mkdir() }
        val corrupt = File(directory, "$id.json").apply { writeText("{") }

        assertTrue(delivery().execute(input(staged)) is Result.Rejected)
        assertNoTransportOrAuthority()
        assertThrows(V2LedgerConflict::class.java) { ledger.recordConfirmed(staged.route) }
        assertEquals("{", corrupt.readText())
        assertEquals(Claim.BLOCKED_BY_OTHER_PROTOCOL(Protocol.IMMUTABLE_V2), authority.claim(id, Protocol.CANONICAL))
        assertTrue(staged.file.isFile)
        assertTrue(routeFile(staged).isFile)
    }

    @Test
    fun concurrentExplicitRequestsOnlyDeliverOnce() = runBlocking {
        val staged = stage()
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val transport = V2SurveyJsonTransport { cfg, path, survey, content ->
            started.complete(Unit)
            proceed.await()
            remote.upload(cfg, path, survey, content)
        }
        val first = async { delivery(usingTransport = transport).execute(input(staged)) }
        started.await()
        val second = async { delivery(usingTransport = transport).execute(input(staged)) }
        proceed.complete(Unit)
        assertTrue(first.await() is Result.Confirmed)
        assertTrue(second.await() is Result.Confirmed)
        assertEquals(1, remote.gets.size)
        assertEquals(1, remote.creates.size)
    }

    @Test
    fun explicitWorkerDispatchUsesFakeV2TransportAndSkipsNormalV1Branch() = runBlocking {
        val staged = stage()
        val context = mockk<Context>()
        val params = mockk<WorkerParameters>()
        val request = V2SurveyJsonWork.buildRequest(files.root, staged.route, staged.file)
        every { params.inputData } returns request.workSpec.input
        every { params.id } returns UUID.randomUUID()
        val worker = GitHubUploadWorker(
            context, params,
            v2Delivery = {
                assertSame(context, it)
                delivery()
            },
            v2Retirement = { _, _ ->
                V2SurveyJsonRetirement(V2SurveyJsonRouteStore(files.root), ledger, authority, { false })
            }
        )
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(staged.route.remoteTargetPath, result.outputData.getString(GitHubUploadWorker.OUT_REMOTE_PATH))
        verify(exactly = 1) { params.inputData }
        verify(exactly = 1) { params.id }
        confirmVerified(params, context)
        assertEquals(1, remote.creates.size)
        assertEquals(Evidence.CONFIRMED, ledger.inspect(staged.route))
        assertFalse(staged.file.exists())
        assertEquals(V2SurveyJsonRouteStore.ReadResult.Delivered(staged.route), V2SurveyJsonRouteStore(files.root).read(id))
    }

    private fun stage(destination: V2SurveyJsonDestination = this.destination, id: String = this.id) =
        V2SurveyJsonStager(V2SurveyJsonRouteStore(files.root)).stage(
            SurveyFinalizationSnapshot(id, emptyMap(), mapOf("Q11" to "Mavuno — café"),
                emptyMap(), emptyList(), "{}", emptyMap()),
            tag, destination, "2026-11-10_11-12-13"
        ) as V2SurveyJsonStager.Result.Ready

    private fun input(staged: V2SurveyJsonStager.Result.Ready) = V2SurveyJsonWork.metadata(staged.route)
    private fun routeFile(staged: V2SurveyJsonStager.Result.Ready) = File(staged.file.parentFile, V2_PENDING_ROUTE)
    private fun authorityKey() = "survey:" + SurveyUploadIdempotency.payloadFingerprint(id)

    private fun delivery(
        resolve: () -> GitHubUploader.GitHubConfig? = { config },
        usingLedger: V2SurveyDeliveryLedger = ledger,
        usingTransport: V2SurveyJsonTransport = remote,
        afterRemote: suspend () -> Unit = {}
    ) = V2SurveyJsonDelivery(V2SurveyJsonPreflight(files.root, localOrDebugBuild = true),
        authority, usingLedger, resolve, usingTransport, afterRemote)

    private fun assertNoTransportOrAuthority() {
        assertTrue(remote.gets.isEmpty())
        assertTrue(remote.creates.isEmpty())
        assertEquals(Owner.NONE, authority.ownerFor(id))
    }

    private fun assertUnconfirmedAndRetained(staged: V2SurveyJsonStager.Result.Ready) {
        assertEquals(Evidence.MISSING, ledger.inspect(staged.route))
        assertEquals(Protocol.IMMUTABLE_V2, (authority.ownerFor(id) as Owner.VALID).protocol)
        assertTrue(staged.file.isFile)
        assertTrue(routeFile(staged).isFile)
    }

    private class FakeRemote : V2SurveyJsonTransport {
        val records = linkedMapOf<String, SurveyRemoteRecord>()
        val gets = mutableListOf<String>()
        val creates = mutableListOf<String>()
        val configs = mutableListOf<GitHubUploader.GitHubConfig>()
        var onGet: (String) -> SurveyRemoteRecord? = { records[it] }
        var onCreate: (String, String) -> SurveyCreateResult = ::create

        fun create(path: String, content: String): SurveyCreateResult {
            check(records.putIfAbsent(path, SurveyRemoteRecord(content)) == null)
            return SurveyCreateResult.Created(GitHubUploader.UploadResult(null, "created"))
        }

        override suspend fun upload(config: GitHubUploader.GitHubConfig, path: String, surveyId: String, content: String) {
            configs += config
            GitHubUploader.reconcileSurveyJsonUpload(
                requireNotNull(SurveyUploadIdempotency.validateLocalPayload(surveyId, content)),
                content,
                object : GitHubUploader.SurveyContentsTransport {
                    override suspend fun getExisting(): SurveyRemoteRecord? {
                        gets += path
                        return onGet(path)
                    }

                    override suspend fun createOnly(): SurveyCreateResult {
                        creates += path
                        return onCreate(path, content)
                    }
                },
                waitForConflict = {}
            )
        }
    }

    /** Models Android's visible in-memory mutation even when a synchronous disk commit fails. */
    private class AuthorityPreferences {
        val visible = mutableMapOf<String, String>()
        val durable = mutableMapOf<String, String>()
        var failCommit = false
        var failRelease = false

        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> visible[args[0] as String] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, String?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString" -> { changes[args[0] as String] = args[1] as String; proxy }
                    "remove" -> { changes[args[0] as String] = null; proxy }
                    "commit" -> {
                        changes.forEach { (key, value) ->
                            if (value == null) visible.remove(key) else visible[key] = value
                        }
                        val success = !failCommit && !(failRelease && changes.containsValue(null))
                        if (success) {
                            durable.clear()
                            durable.putAll(visible)
                        }
                        success
                    }
                    else -> error("Unexpected preferences editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
