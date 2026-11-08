package com.negi.survey.net

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_BRANCH
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_FILE_NAME
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_FILE_PATH
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_MODE
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_OWNER
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_PATH_PREFIX
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_REPO
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_SURVEY_ID
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_SURVEY_UPLOAD_PROTOCOL
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_TOKEN
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_UPLOAD_KIND
import com.negi.survey.net.GitHubUploadWorker.Companion.SURVEY_UPLOAD_PROTOCOL_CANONICAL_IMMUTABLE_V1
import com.negi.survey.net.GitHubUploadWorker.Companion.UPLOAD_KIND_SURVEY_JSON
import com.negi.survey.net.V2SurveyJsonDestination.Identity
import com.negi.survey.net.V2SurveyJsonPreflight.Result.Rejected
import com.negi.survey.net.V2SurveyJsonPreflight.Result.Validated
import com.negi.survey.net.V2SurveyJsonScheduler.Result.Deferred
import com.negi.survey.net.V2SurveyJsonScheduler.Result.Enqueued
import com.negi.survey.net.V2SurveyJsonScheduler.Result.Kept
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.vm.SurveyFinalizationSnapshot
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class V2SurveyJsonWorkTest {
    @get:Rule val files = TemporaryFolder()

    private val surveyId = "01a1b874-6bff-7abc-9def-0123456789ab"
    private val tag = DeviceUploadTag("Pixel_9a_A13F82C4D9E1")
    private val dev = V2SurveyJsonDestination(
        Identity.Development, "ishizuki-tech", "SurveyExports-Dev", "field/test", "exports/surveys"
    )
    private val production = dev.copy(identity = Identity.Production, repository = "SurveyExports")
    private val snapshot = SurveyFinalizationSnapshot(
        surveyId, emptyMap(), mapOf("Q11" to "Mavuno — café"), emptyMap(), emptyList(), "{}", emptyMap()
    )

    @Test
    fun requestCarriesOnlyExactNonSecretRouteMetadataAndStandardConstraints() {
        val staged = stage()
        val request = request(staged)
        assertEquals(GitHubUploadWorker::class.java.name, request.workSpec.workerClassName)
        assertEquals(
            mapOf(
                KEY_MODE to "file",
                KEY_UPLOAD_KIND to "survey_json",
                KEY_SURVEY_ID to surveyId,
                KEY_SURVEY_UPLOAD_PROTOCOL to "immutable_v2",
                V2SurveyJsonWork.KEY_ROUTE_KEY to surveyId,
                V2SurveyJsonWork.KEY_FILE_IDENTITY to staged.route.localFileIdentity,
                V2SurveyJsonWork.KEY_FINGERPRINT to staged.route.payloadSha256,
                KEY_OWNER to dev.owner,
                KEY_REPO to dev.repository,
                KEY_BRANCH to dev.branch,
                KEY_PATH_PREFIX to dev.pathPrefix,
                V2SurveyJsonWork.KEY_DESTINATION to "Development"
            ),
            request.workSpec.input.keyValueMap
        )
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
        val wrongFile = staged.file.copyTo(File(files.root, staged.file.name))
        assertThrows(IllegalArgumentException::class.java) {
            V2SurveyJsonWork.buildRequest(files.root, staged.route, wrongFile)
        }
    }

    @Test
    fun uniqueWorkNameNormalizesUuidAndIsSeparateFromV1AndLegacy() {
        val staged = stage()
        val name = V2SurveyJsonWork.uniqueWorkName(surveyId)
        assertEquals("gh_survey_v2:$surveyId", name)
        assertEquals(name, V2SurveyJsonWork.uniqueWorkName(surveyId.uppercase()))
        assertNotEquals(name, SurveyUploadWork.logicalWorkName(surveyId))
        assertNotEquals(name, SurveyUploadWork.legacyWorkName(staged.file))
        assertTrue(request(staged).tags.contains(name))
    }

    @Test
    fun repeatedSchedulingAcrossInstancesKeepsOneDurablyObservedRequest() {
        stage()
        val work = FakeWorkManager()
        val first = scheduler(work).schedule(surveyId) as Enqueued
        val second = scheduler(work).schedule(surveyId.uppercase())
        assertEquals(Kept(first.requestId), second)
        assertEquals(1, work.enqueued.size)
        assertEquals(ExistingWorkPolicy.KEEP, work.enqueued.single().first)
        assertEquals(V2SurveyJsonWork.uniqueWorkName(surveyId), work.lookups.distinct().single())
    }

    @Test
    fun preservesRunningAndBlockedRequestsWithoutSubmitting() {
        stage()
        val work = FakeWorkManager()
        val id = UUID.randomUUID()
        for (state in listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)) {
            work.history = listOf(WorkInfo(id, state, emptySet()))
            assertEquals(Kept(id), scheduler(work).schedule(surveyId))
        }
        assertTrue(work.enqueued.isEmpty())
    }

    @Test
    fun unknownOrConflictingWorkManagerStateDefersWithoutSubmitting() {
        stage()
        val work = FakeWorkManager().apply { lookupFailure = TimeoutException("lookup unavailable") }
        assertTrue(scheduler(work).schedule(surveyId) is Deferred)
        work.lookupFailure = null
        work.history = List(2) { WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, emptySet()) }
        assertTrue(scheduler(work).schedule(surveyId) is Deferred)
        assertTrue(work.enqueued.isEmpty())
    }

    @Test
    fun timedOutEnqueueIsDeferredUntilObservedWithoutSubmittingAgain() {
        stage()
        val work = FakeWorkManager().apply {
            completion = SurveyUploadWork.EnqueueCompletion.TIMED_OUT
            publishEnqueued = false
        }
        assertTrue(scheduler(work).schedule(surveyId) is Deferred)
        assertTrue(scheduler(work).schedule(surveyId) is Deferred)
        val request = work.enqueued.single().second
        work.history = listOf(WorkInfo(request.id, WorkInfo.State.ENQUEUED, emptySet()))
        work.completion = SurveyUploadWork.EnqueueCompletion.SUCCEEDED
        assertEquals(Kept(request.id), scheduler(work).schedule(surveyId))
        assertEquals(1, work.enqueued.size)
    }

    @Test
    fun acknowledgedButUnobservableEnqueueDefersWithoutSubmittingAgain() {
        stage()
        val work = FakeWorkManager().apply { publishEnqueued = false }
        assertTrue(scheduler(work).schedule(surveyId) is Deferred)
        assertTrue(scheduler(work).schedule(surveyId) is Deferred)
        val id = work.enqueued.single().second.id
        work.history = listOf(WorkInfo(id, WorkInfo.State.ENQUEUED, emptySet()))
        assertEquals(Kept(id), scheduler(work).schedule(surveyId))
        assertEquals(1, work.enqueued.size)
    }

    @Test
    fun keepRaceReturnsObservedWinnerAndDoesNotClaimLosingRequest() {
        stage()
        val winner = UUID.randomUUID()
        val work = FakeWorkManager().apply { keepWinner = winner }
        assertEquals(Kept(winner), scheduler(work).schedule(surveyId))
        assertEquals(1, work.enqueued.size)
        assertNotEquals(winner, work.enqueued.single().second.id)
    }

    @Test
    fun schedulerRecoversMatchingTemporaryPayloadButDefersMismatchedPayload() {
        val interrupted = interruptedStage()
        val work = FakeWorkManager()
        val input = V2SurveyJsonWork.metadata(interrupted)
        assertTrue(preflight().validate(input) is Rejected)
        val final = File(files.root, interrupted.localFileIdentity)
        val temporary = File(final.parentFile, V2_PAYLOAD_TEMP)
        assertTrue(temporary.isFile)
        assertFalse(final.exists())
        assertTrue(scheduler(work).schedule(surveyId) is Enqueued)
        assertTrue(final.isFile)
        assertFalse(temporary.exists())
        final.appendText(" ")
        assertTrue(scheduler(work).schedule(surveyId) is Deferred)
        assertEquals(1, work.enqueued.size)
    }

    @Test
    fun preflightReturnsExactFrozenTargetWithOneUtcDateSegment() {
        val staged = stage()
        val valid = preflight().validate(input(staged)) as Validated
        assertEquals(staged.route, valid.route)
        assertEquals(staged.file, valid.stagedFile)
        assertEquals("exports/surveys/2026-11-07/survey/${staged.file.name}", valid.remoteTargetPath)
        assertEquals(1, valid.remoteTargetPath.split('/').count { it == "2026-11-07" })
        assertEquals(staged.route.payloadSha256, valid.payloadSha256)
        assertEquals(Identity.Development, valid.destinationIdentity)
    }

    @Test
    fun dispatchRejectsUnknownOrDowngradedV2MarkersAndPreservesHistoricalDispatch() {
        val staged = stage()
        val v2 = input(staged)
        val historical = mapOf(KEY_UPLOAD_KIND to UPLOAD_KIND_SURVEY_JSON, KEY_SURVEY_ID to surveyId)
        assertEquals(SurveyWorkerProtocol.EXISTING_UNMARKED, surveyWorkerProtocol(historical))
        assertEquals(SurveyWorkerProtocol.EXISTING_UNMARKED, surveyWorkerProtocol(mapOf(KEY_MODE to "logcat")))
        assertEquals(SurveyWorkerProtocol.CANONICAL_V1, surveyWorkerProtocol(
            historical + (KEY_SURVEY_UPLOAD_PROTOCOL to SURVEY_UPLOAD_PROTOCOL_CANONICAL_IMMUTABLE_V1)
        ))
        assertEquals(SurveyWorkerProtocol.IMMUTABLE_V2, surveyWorkerProtocol(v2))

        for (marker in listOf("other", "immutable_v3", " immutable_v2 ", 2, null)) {
            assertEquals(SurveyWorkerProtocol.UNSUPPORTED, surveyWorkerProtocol(
                historical + (KEY_SURVEY_UPLOAD_PROTOCOL to marker)
            ))
            assertTrue(preflight().validate(v2 + (KEY_SURVEY_UPLOAD_PROTOCOL to marker)) is Rejected)
        }
        for (downgraded in listOf(
            v2 - KEY_SURVEY_UPLOAD_PROTOCOL,
            v2 + (KEY_SURVEY_UPLOAD_PROTOCOL to SURVEY_UPLOAD_PROTOCOL_CANONICAL_IMMUTABLE_V1),
            v2 + (KEY_SURVEY_UPLOAD_PROTOCOL to "")
        )) {
            assertEquals(SurveyWorkerProtocol.UNSUPPORTED, surveyWorkerProtocol(downgraded))
        }
        assertTrue(isV2StagedFile(files.root, staged.file))
        val legacy = File(files.root, "pending_uploads/${staged.file.name}")
        assertFalse(isV2StagedFile(files.root, legacy))
    }

    @Test
    fun invalidMissingAndNonCanonicalSurveyUuidAreRejected() {
        val v2 = input(stage())
        for (survey in listOf("", "not-a-uuid", "01a1b874-6bff-4abc-9def-0123456789ab", surveyId.uppercase())) {
            assertTrue(preflight().validate(v2 + (KEY_SURVEY_ID to survey)) is Rejected)
        }
        assertTrue(preflight().validate(v2 - KEY_SURVEY_ID) is Rejected)
    }

    @Test
    fun missingCorruptAndDeliveredRouteAreRejectedWithPayloadRetained() {
        val staged = stage()
        val v2 = input(staged)
        val file = File(staged.file.parentFile, V2_PENDING_ROUTE)
        val metadata = file.readBytes()
        val payload = staged.file.readBytes()
        assertTrue(file.delete())
        assertTrue(preflight().validate(v2) is Rejected)
        file.writeText("{")
        assertTrue(preflight().validate(v2) is Rejected)
        assertEquals("{", file.readText())
        file.writeBytes(metadata)
        store().removeAfterConfirmedDelivery(staged.route)
        assertTrue(preflight().validate(v2) is Rejected)
        assertTrue(store().read(surveyId) is V2SurveyJsonRouteStore.ReadResult.Delivered)
        assertArrayEquals(payload, staged.file.readBytes())
    }

    @Test
    fun missingPayloadAndFingerprintMismatchAreRejectedWithoutRepair() {
        val staged = stage()
        val v2 = input(staged)
        assertTrue(staged.file.delete())
        assertTrue(preflight().validate(v2) is Rejected)
        assertFalse(staged.file.exists())
        staged.file.writeText("""{"survey_id":"$surveyId","answer":"changed"}""")
        val bytes = staged.file.readBytes()
        assertTrue(preflight().validate(v2) is Rejected)
        assertArrayEquals(bytes, staged.file.readBytes())
        assertEquals(V2SurveyJsonRouteStore.ReadResult.Pending(staged.route), store().read(surveyId))
    }

    @Test
    fun differentSurveyIdIsRejectedEvenWhenBytesMatchFingerprint() {
        val content = """{"survey_id":"01a1b874-6c00-7abc-9def-0123456789ab"}"""
        val route = V2SurveyJsonRoute.create(surveyId, tag, dev, SurveyUploadIdempotency.payloadFingerprint(content))
        store().createIfAbsent(route)
        val file = File(files.root, route.localFileIdentity).apply { writeText(content) }
        assertTrue(preflight().validate(V2SurveyJsonWork.metadata(route)) is Rejected)
        assertEquals(content, file.readText())
    }

    @Test
    fun nonCanonicalJsonUuidIsRejectedEvenWhenBytesMatchFingerprint() {
        val content = """{"survey_id":" $surveyId "}"""
        val route = V2SurveyJsonRoute.create(surveyId, tag, dev, SurveyUploadIdempotency.payloadFingerprint(content))
        store().createIfAbsent(route)
        val file = File(files.root, route.localFileIdentity).apply { writeText(content) }
        assertTrue(preflight().validate(V2SurveyJsonWork.metadata(route)) is Rejected)
        assertEquals(content, file.readText())
    }

    @Test
    fun tamperedRouteMetadataAndUnexpectedKeysAreRejected() {
        val staged = stage()
        val v2 = input(staged)
        val tampered = mapOf(
            V2SurveyJsonWork.KEY_FILE_IDENTITY to "../other.json",
            V2SurveyJsonWork.KEY_ROUTE_KEY to "other",
            V2SurveyJsonWork.KEY_FINGERPRINT to "0".repeat(64),
            V2SurveyJsonWork.KEY_DESTINATION to "Production",
            KEY_OWNER to "other-owner",
            KEY_REPO to "SurveyExports",
            KEY_BRANCH to "other",
            KEY_PATH_PREFIX to "elsewhere",
            KEY_MODE to "logcat",
            KEY_UPLOAD_KIND to "other",
            KEY_FILE_NAME to "untrusted/path.json",
            KEY_FILE_PATH to staged.file.absolutePath,
            KEY_TOKEN to "injected-test-token"
        )
        val original = staged.file.readBytes()
        val routeFile = File(staged.file.parentFile, V2_PENDING_ROUTE)
        val routeBytes = routeFile.readBytes()
        (tampered.map { v2 + (it.key to it.value) } + listOf(v2 - KEY_PATH_PREFIX)).forEach { invalid ->
            assertTrue(preflight().validate(invalid) is Rejected)
        }
        assertArrayEquals(original, staged.file.readBytes())
        assertArrayEquals(routeBytes, routeFile.readBytes())
    }

    @Test
    fun localOrDebugBuildRejectsProductionEvenWithAnAlternateOwner() {
        val staged = stage(production.copy(owner = "other-owner"))
        val v2 = input(staged)
        assertTrue(preflight(localOrDebug = false).validate(v2) is Validated)
        assertTrue(preflight(localOrDebug = true).validate(v2) is Rejected)
        val work = FakeWorkManager()
        assertTrue(scheduler(work, localOrDebug = true).schedule(surveyId) is Deferred)
        assertTrue(work.enqueued.isEmpty())
    }

    private fun stage(destination: V2SurveyJsonDestination = dev) =
        V2SurveyJsonStager(store()).stage(snapshot, tag, destination, "2026-11-10_01-02-03") as
            V2SurveyJsonStager.Result.Ready

    private fun interruptedStage(): V2SurveyJsonRoute {
        val store = V2SurveyJsonRouteStore(files.root) {
            if (it == V2JsonStagingCheckpoint.ROUTE_PUBLISHED) throw IOException("interrupted")
        }
        assertThrows(IOException::class.java) {
            V2SurveyJsonStager(store).stage(snapshot, tag, dev, "2026-11-10_01-02-03")
        }
        return (store.read(surveyId) as V2SurveyJsonRouteStore.ReadResult.Pending).route
    }

    private fun input(staged: V2SurveyJsonStager.Result.Ready) = request(staged).workSpec.input.keyValueMap
    private fun request(staged: V2SurveyJsonStager.Result.Ready) =
        V2SurveyJsonWork.buildRequest(files.root, staged.route, staged.file)

    private fun store() = V2SurveyJsonRouteStore(files.root)
    private fun preflight(localOrDebug: Boolean = true) = V2SurveyJsonPreflight(files.root, localOrDebug)
    private fun scheduler(work: FakeWorkManager, localOrDebug: Boolean = true) =
        V2SurveyJsonScheduler(files.root, work, localOrDebug)

    private class FakeWorkManager : SurveyUploadWork.WorkManagerOperations {
        val lookups = mutableListOf<String>()
        val enqueued = mutableListOf<Pair<ExistingWorkPolicy, OneTimeWorkRequest>>()
        var history = emptyList<WorkInfo>()
        var lookupFailure: Exception? = null
        var publishEnqueued = true
        var keepWinner: UUID? = null
        var completion = SurveyUploadWork.EnqueueCompletion.SUCCEEDED

        override fun getWorkInfoById(id: UUID): WorkInfo? = error("V2 uses durable unique-work lookup")

        override fun getWorkInfosForUniqueWork(uniqueWorkName: String): List<WorkInfo> {
            lookups += uniqueWorkName
            lookupFailure?.let { throw it }
            return history
        }

        override fun enqueueUniqueWork(
            uniqueWorkName: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest
        ): SurveyUploadWork.EnqueueOperation {
            enqueued += policy to request
            if (publishEnqueued) {
                history = listOf(WorkInfo(keepWinner ?: request.id, WorkInfo.State.ENQUEUED, emptySet()))
            }
            return object : SurveyUploadWork.EnqueueOperation {
                override fun awaitCompletion(timeoutMs: Long) = completion
            }
        }
    }
}
