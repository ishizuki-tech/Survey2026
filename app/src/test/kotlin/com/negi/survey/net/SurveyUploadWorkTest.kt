/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyUploadWorkTest.kt
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */
package com.negi.survey.net

import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SurveyUploadWorkTest {
    private lateinit var pendingDir: File

    @Before
    fun setUp() {
        pendingDir = Files.createTempDirectory("survey-upload-work-test").toFile()
    }

    @After
    fun tearDown() {
        pendingDir.deleteRecursively()
    }

    @Test
    fun surveyJsonMetadata_marksOnlyNonblankSurveyIdsAndKeepsActualFileIdentity() {
        val data = Data.Builder()
        SurveyUploadWork.addSurveyJsonMetadata(data, " survey-uuid ")
        val built = data.build()

        assertEquals(
            GitHubUploadWorker.UPLOAD_KIND_SURVEY_JSON,
            built.getString(GitHubUploadWorker.KEY_UPLOAD_KIND)
        )
        assertEquals("survey-uuid", built.getString(GitHubUploadWorker.KEY_SURVEY_ID))
        assertEquals(
            "exports/timestamp_survey_Device_survey-uuid.json",
            SurveyUploadWork.remoteRelativePath("timestamp_survey_Device_survey-uuid.json")
        )
        assertEquals(
            SurveyUploadWork.uniqueWorkName(
                SurveyUploadWork.remoteRelativePath("timestamp_survey_Device_survey-uuid.json")
            ),
            SurveyUploadWork.uniqueWorkName(
                SurveyUploadWork.remoteRelativePath("timestamp_survey_Device_survey-uuid.json")
            )
        )
    }

    @Test
    fun surveyJsonMetadata_ignoresBlankSurveyId() {
        val data = Data.Builder()
        SurveyUploadWork.addSurveyJsonMetadata(data, "   ")
        val built = data.build()

        assertNull(built.getString(GitHubUploadWorker.KEY_UPLOAD_KIND))
        assertNull(built.getString(GitHubUploadWorker.KEY_SURVEY_ID))
    }

    @Test
    fun logicalWorkName_normalizesCaseWhitespaceAndAcceptsHistoricalIds() {
        val uppercase = SurveyUploadWork.logicalWorkName(" ABC ")
        val lowercase = SurveyUploadWork.logicalWorkName("abc")
        val historical = SurveyUploadWork.logicalWorkName("legacy survey/id")

        assertEquals("abc", SurveyUploadWork.normalizeLogicalSurveyId(" ABC "))
        assertEquals(uppercase, lowercase)
        assertTrue(historical!!.startsWith("gh_survey_"))
        assertEquals("gh_survey_".length + 64, historical.length)
        assertNull(SurveyUploadWork.logicalWorkName("  "))
    }

    @Test
    fun logicalWorkName_isDeterministicDistinctAndMatchesKnownSha256Vector() {
        assertEquals(
            SurveyUploadWork.logicalWorkName("first"),
            SurveyUploadWork.logicalWorkName("first")
        )
        assertTrue(SurveyUploadWork.logicalWorkName("first") != SurveyUploadWork.logicalWorkName("second"))
        assertEquals(
            "gh_survey_ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SurveyUploadWork.logicalWorkName(" ABC ")
        )
    }

    @Test
    fun legacyWorkIdentityAndRemotePathRemainFileBased() {
        val file = File(pendingDir, "timestamp_survey_Device_survey-uuid.json")
        val remotePath = SurveyUploadWork.remoteRelativePath(file.name)

        assertEquals("exports/timestamp_survey_Device_survey-uuid.json", remotePath)
        assertEquals(SurveyUploadWork.uniqueWorkName(remotePath), SurveyUploadWork.legacyWorkName(file))
    }

    @Test
    fun buildSurveyJsonRequest_preservesFinalizerMetadataAndConstraints() {
        val file = write("canonical.json", """{"survey_id":" Survey-Id "}""")
        val request = SurveyUploadWork.buildSurveyJsonRequest(
            GitHubUploader.GitHubConfig(
                owner = "owner",
                repo = "owner/repo",
                token = "token",
                branch = "branch",
                pathPrefix = "prefix",
                maxRawBytesHint = 123,
                maxRequestBytesHint = 456
            ),
            file,
            " Survey-Id "
        )
        val data = request.workSpec.input

        assertEquals("file", data.getString(GitHubUploadWorker.KEY_MODE))
        assertEquals("owner", data.getString(GitHubUploadWorker.KEY_OWNER))
        assertEquals("repo", data.getString(GitHubUploadWorker.KEY_REPO))
        assertEquals("token", data.getString(GitHubUploadWorker.KEY_TOKEN))
        assertEquals("branch", data.getString(GitHubUploadWorker.KEY_BRANCH))
        assertEquals("prefix", data.getString(GitHubUploadWorker.KEY_PATH_PREFIX))
        assertEquals(file.absolutePath, data.getString(GitHubUploadWorker.KEY_FILE_PATH))
        assertEquals("exports/canonical.json", data.getString(GitHubUploadWorker.KEY_FILE_NAME))
        assertEquals(123L, data.getLong(GitHubUploadWorker.KEY_FILE_MAX_BYTES_HINT, -1L))
        assertEquals(456, data.getInt(GitHubUploadWorker.KEY_FILE_MAX_REQUEST_BYTES_HINT, -1))
        assertEquals(GitHubUploadWorker.UPLOAD_KIND_SURVEY_JSON, data.getString(GitHubUploadWorker.KEY_UPLOAD_KIND))
        assertEquals("Survey-Id", data.getString(GitHubUploadWorker.KEY_SURVEY_ID))
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(30_000L, request.workSpec.backoffDelayDuration)
        assertEquals(androidx.work.BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertTrue(request.workSpec.expedited)
        assertTrue(request.tags.contains(GitHubUploadWorker.TAG))
        assertTrue(request.tags.contains("${GitHubUploadWorker.TAG}:file:exports_canonical.json"))
    }

    @Test
    fun buildSurveyJsonRequest_candidateUsesCanonicalFileNotDuplicates() {
        val canonical = write("a.json", """{"survey_id":" Same "}""")
        val duplicate = write("z.json", """{"survey_id":"same"}""")
        val candidate = PendingSurveyUploads.PendingSurveyCandidate(
            normalizedSurveyId = "same",
            canonicalFile = canonical,
            duplicateFiles = listOf(duplicate)
        )

        val request = SurveyUploadWork.buildSurveyJsonRequest(config(), candidate)

        assertEquals(canonical.absolutePath, request.workSpec.input.getString(GitHubUploadWorker.KEY_FILE_PATH))
        assertEquals("exports/a.json", request.workSpec.input.getString(GitHubUploadWorker.KEY_FILE_NAME))
        assertEquals("Same", request.workSpec.input.getString(GitHubUploadWorker.KEY_SURVEY_ID))
    }

    @Test
    fun validateCanonicalArtifact_acceptsCaseEquivalentCandidateId() {
        val file = write("survey.json", """{"survey_id":" ABC "}""")

        val validation = SurveyUploadWork.validateCanonicalArtifact(file, "abc")

        assertEquals(SurveyUploadWork.SurveyArtifactValidation.Valid("ABC"), validation)
    }

    @Test
    fun validateCanonicalArtifact_rejectsMissingEmptyChangedAndMalformedFiles() {
        val missing = File(pendingDir, "missing.json")
        val empty = write("empty.json", "")
        val changed = write("changed.json", """{"survey_id":"other"}""")
        val malformed = write("malformed.json", "{bad")

        listOf(missing, empty, changed, malformed).forEach { file ->
            assertTrue(
                SurveyUploadWork.validateCanonicalArtifact(file, "expected")
                    is SurveyUploadWork.SurveyArtifactValidation.Invalid
            )
        }
    }

    @Test
    fun decideReconciliation_keepsEveryActiveTrackedState() {
        val tracked = trackedRecord(SurveyUploadWorkTracker.Phase.ENQUEUED)
        listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED).forEach { state ->
            assertEquals(
                SurveyUploadWork.SurveyWorkAction.KEEP_TRACKED,
                decide(tracked = tracked, state = state).action
            )
        }
    }

    @Test
    fun decideReconciliation_ledgerWinsAndSucceededWithoutLedgerDefers() {
        val tracked = trackedRecord(SurveyUploadWorkTracker.Phase.ENQUEUED)
        assertEquals(
            SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED,
            decide(tracked, WorkInfo.State.SUCCEEDED, ledger = true).action
        )
        assertEquals(
            SurveyUploadWork.SurveyWorkAction.DEFER_SUCCEEDED_WITHOUT_LEDGER,
            decide(tracked, WorkInfo.State.SUCCEEDED).action
        )
    }

    @Test
    fun decideReconciliation_recoversOnlyValidTrackedTerminalOrPreparedMissingWork() {
        listOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED).forEach { state ->
            assertEquals(
                SurveyUploadWork.SurveyWorkAction.RECOVER_NEW,
                decide(trackedRecord(SurveyUploadWorkTracker.Phase.ENQUEUED), state).action
            )
        }
        assertEquals(
            SurveyUploadWork.SurveyWorkAction.RECOVER_NEW,
            decide(trackedRecord(SurveyUploadWorkTracker.Phase.PREPARED), null).action
        )
        assertEquals(
            SurveyUploadWork.SurveyWorkAction.DEFER_ENQUEUED_MISSING,
            decide(trackedRecord(SurveyUploadWorkTracker.Phase.ENQUEUED), null).action
        )
        assertEquals(
            SurveyUploadWork.SurveyWorkAction.INVALID_ARTIFACT,
            decide(trackedRecord(SurveyUploadWorkTracker.Phase.ENQUEUED), WorkInfo.State.FAILED, artifactValid = false).action
        )
    }

    @Test
    fun decideReconciliation_defersLookupFailureAndUnorderedLegacyTerminalHistory() {
        assertEquals(SurveyUploadWork.SurveyWorkAction.ENQUEUE_NEW, decide().action)
        assertEquals(
            SurveyUploadWork.SurveyWorkAction.DEFER_UNKNOWN_STATE,
            decide(lookupFailed = true).action
        )
        assertEquals(
            SurveyUploadWork.SurveyWorkAction.DEFER_LEGACY_HISTORY,
            decide(
                legacy = SurveyUploadWork.LegacyWorkInspection(hasAnyHistory = true)
            ).action
        )
    }

    @Test
    fun reconcile_recoversTerminalTrackedWorkWithReplaceAndTransitionsNewRequestToEnqueued() {
        val tracker = tracker()
        val staleId = UUID.randomUUID()
        tracker.prepare("survey", staleId, SurveyUploadWorkTracker.IdentityKind.LOGICAL)
        tracker.markEnqueuedIfMatches("survey", staleId)
        val operations = FakeOperations(states = mutableMapOf(staleId to workInfo(staleId, WorkInfo.State.FAILED)))

        val result = reconcile(tracker, operations)

        assertEquals(SurveyUploadWork.SurveyWorkAction.RECOVER_NEW, result.decision.action)
        assertTrue(result.enqueued)
        assertEquals(ExistingWorkPolicy.REPLACE, operations.enqueuedPolicies.single())
        assertEquals(SurveyUploadWorkTracker.Phase.ENQUEUED, tracker.get("survey")!!.phase)
        assertTrue(tracker.get("survey")!!.workRequestId != staleId)
    }

    @Test
    fun reconcile_preparedMissingRecoversButEnqueuedMissingAndQueryFailureDeferWithoutMutation() {
        val prepared = tracker()
        val preparedId = UUID.randomUUID()
        prepared.prepare("survey", preparedId, SurveyUploadWorkTracker.IdentityKind.LOGICAL)
        assertTrue(reconcile(prepared, FakeOperations()).enqueued)

        val enqueued = tracker()
        val staleId = UUID.randomUUID()
        enqueued.prepare("survey", staleId, SurveyUploadWorkTracker.IdentityKind.LOGICAL)
        enqueued.markEnqueuedIfMatches("survey", staleId)
        val missing = reconcile(enqueued, FakeOperations())
        assertEquals(SurveyUploadWork.SurveyWorkAction.DEFER_ENQUEUED_MISSING, missing.decision.action)
        assertEquals(staleId, enqueued.get("survey")!!.workRequestId)

        val failedQuery = reconcile(enqueued, FakeOperations(queryFailure = true))
        assertEquals(SurveyUploadWork.SurveyWorkAction.DEFER_UNKNOWN_STATE, failedQuery.decision.action)
        assertEquals(staleId, enqueued.get("survey")!!.workRequestId)
    }

    @Test
    fun reconcile_uploadedLedgerClearsTrackerAndInvalidArtifactPreventsRecovery() {
        val tracker = tracker()
        val id = UUID.randomUUID()
        tracker.prepare("survey", id, SurveyUploadWorkTracker.IdentityKind.LOGICAL)
        tracker.markEnqueuedIfMatches("survey", id)
        val uploaded = SurveyUploadWork.reconcileForTesting(
            config(), "survey", validFile(), tracker, { true }, operations =
            FakeOperations(states = mutableMapOf(id to workInfo(id, WorkInfo.State.FAILED)))
        )
        assertEquals(SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED, uploaded.decision.action)
        assertNull(tracker.get("survey"))

        val invalidTracker = tracker()
        val invalidId = UUID.randomUUID()
        invalidTracker.prepare("survey", invalidId, SurveyUploadWorkTracker.IdentityKind.LOGICAL)
        invalidTracker.markEnqueuedIfMatches("survey", invalidId)
        val invalid = SurveyUploadWork.reconcileForTesting(
            config(), "survey", File(pendingDir, "missing.json"), invalidTracker, { false }, operations =
                FakeOperations(states = mutableMapOf(invalidId to workInfo(invalidId, WorkInfo.State.FAILED)))
        )
        assertEquals(SurveyUploadWork.SurveyWorkAction.INVALID_ARTIFACT, invalid.decision.action)
        assertEquals(invalidId, invalidTracker.get("survey")!!.workRequestId)
    }

    @Test
    fun reconcile_persistsPreparedBeforeEnqueueAndRetainsItWhenEnqueueFails() {
        val tracker = tracker()
        var sawPrepared = false
        val operations = FakeOperations(onEnqueue = { request ->
            sawPrepared = tracker.get("survey")?.let {
                it.workRequestId == request.id && it.phase == SurveyUploadWorkTracker.Phase.PREPARED
            } == true
        })
        assertTrue(reconcile(tracker, operations).enqueued)
        assertTrue(sawPrepared)
        assertEquals(SurveyUploadWorkTracker.Phase.ENQUEUED, tracker.get("survey")!!.phase)

        val failingTracker = tracker()
        val failed = reconcile(failingTracker, FakeOperations(enqueueFailure = true))
        assertEquals(SurveyUploadWork.SurveyWorkAction.DEFER_ENQUEUE_FAILURE, failed.decision.action)
        assertEquals(SurveyUploadWorkTracker.Phase.PREPARED, failingTracker.get("survey")!!.phase)
    }

    @Test
    fun reconcile_confirmsAsyncSuccessBeforeMarkingEnqueuedAndRetainsPreparedOnAsyncFailureOrTimeout() {
        val tracker = tracker()
        var confirmationObserved = false
        tracker.onMark = { assertTrue(confirmationObserved) }
        val success = reconcile(
            tracker,
            FakeOperations(
                onAwait = { confirmationObserved = true }
            )
        )
        assertTrue(success.enqueued)
        assertEquals(SurveyUploadWorkTracker.Phase.ENQUEUED, tracker.get("survey")!!.phase)

        val asyncFailure = tracker()
        val failed = reconcile(asyncFailure, FakeOperations(completion = SurveyUploadWork.EnqueueCompletion.FAILED))
        assertEquals(SurveyUploadWork.SurveyWorkAction.DEFER_ENQUEUE_FAILURE, failed.decision.action)
        assertEquals(SurveyUploadWorkTracker.Phase.PREPARED, asyncFailure.get("survey")!!.phase)

        val timedOut = tracker()
        val timeout = reconcile(timedOut, FakeOperations(completion = SurveyUploadWork.EnqueueCompletion.TIMED_OUT))
        assertEquals(SurveyUploadWork.SurveyWorkAction.DEFER_ENQUEUE_TIMEOUT, timeout.decision.action)
        assertEquals(SurveyUploadWorkTracker.Phase.PREPARED, timedOut.get("survey")!!.phase)
    }

    @Test
    fun reconcile_timeoutThenEventualActiveWorkKeepsTheSameTrackedUuidWithoutRescheduling() {
        val tracker = tracker()
        val operations = FakeOperations(completion = SurveyUploadWork.EnqueueCompletion.TIMED_OUT)

        val timedOut = reconcile(tracker, operations)
        val trackedId = tracker.get("survey")!!.workRequestId
        assertEquals(SurveyUploadWork.SurveyWorkAction.DEFER_ENQUEUE_TIMEOUT, timedOut.decision.action)
        assertEquals(SurveyUploadWorkTracker.Phase.PREPARED, tracker.get("survey")!!.phase)
        assertEquals(1, tracker.prepareIds.size)
        assertEquals(1, operations.enqueuedPolicies.size)

        operations.legacyInspectionCalls = 0
        val kept = reconcile(tracker, operations)

        assertEquals(SurveyUploadWork.SurveyWorkAction.KEEP_TRACKED, kept.decision.action)
        assertEquals(trackedId, tracker.get("survey")!!.workRequestId)
        assertEquals(SurveyUploadWorkTracker.Phase.PREPARED, tracker.get("survey")!!.phase)
        assertEquals(1, tracker.prepareIds.size)
        assertEquals(1, operations.enqueuedPolicies.size)
        assertEquals(0, operations.legacyInspectionCalls)
    }

    @Test
    fun reconcile_convertsTrackerLedgerArtifactBuildAndCleanupFailuresToSafeResults() {
        val getFailure = tracker().apply { failGet = true }
        assertEquals(
            SurveyUploadWork.SurveyWorkAction.DEFER_TRACKER_FAILURE,
            reconcile(getFailure, FakeOperations()).decision.action
        )
        assertEquals(
            SurveyUploadWork.SurveyWorkAction.DEFER_LEDGER_FAILURE,
            SurveyUploadWork.reconcileForTesting(config(), "survey", validFile(), tracker(), { error("ledger") }, operations = FakeOperations()).decision.action
        )
        assertEquals(
            SurveyUploadWork.SurveyWorkAction.DEFER_ARTIFACT_FAILURE,
            reconcile(tracker(), FakeOperations(), artifactValidator = { _, _ -> error("artifact") }).decision.action
        )
        assertEquals(
            SurveyUploadWork.SurveyWorkAction.DEFER_BUILD_FAILURE,
            reconcile(tracker(), FakeOperations(), requestBuilder = { _, _, _ -> error("build") }).decision.action
        )

        val prepareFailure = tracker().apply { failPrepare = true }
        val prepare = reconcile(prepareFailure, FakeOperations())
        assertEquals(SurveyUploadWork.SurveyWorkAction.DEFER_TRACKER_FAILURE, prepare.decision.action)
        assertTrue(prepareFailure.enqueuedIds.isEmpty())

        val markFailure = tracker().apply { failMark = true }
        val markOperations = FakeOperations()
        val marked = reconcile(markFailure, markOperations)
        assertEquals(SurveyUploadWork.SurveyWorkAction.ENQUEUED_TRACKER_UNCONFIRMED, marked.decision.action)
        assertEquals(1, markOperations.enqueuedPolicies.size)
        assertEquals(SurveyUploadWorkTracker.Phase.PREPARED, markFailure.get("survey")!!.phase)

        val cleanupFailure = tracker().apply { failClear = true }
        val cleanup = SurveyUploadWork.reconcileForTesting(
            config(), "survey", validFile(), cleanupFailure, { true }, operations = FakeOperations()
        )
        assertEquals(SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED_TRACKER_CLEANUP_FAILED, cleanup.decision.action)
    }

    @Test
    fun reconcile_adoptsOneActiveLegacyButDefersMultipleOrTerminalLegacyHistory() {
        val legacyId = UUID.randomUUID()
        val tracker = tracker()
        val adopted = reconcile(tracker, FakeOperations(legacy = listOf(workInfo(legacyId, WorkInfo.State.RUNNING))))
        assertEquals(SurveyUploadWork.SurveyWorkAction.ADOPT_LEGACY, adopted.decision.action)
        assertEquals(legacyId, tracker.get("survey")!!.workRequestId)
        assertEquals(SurveyUploadWorkTracker.IdentityKind.LEGACY, tracker.get("survey")!!.identityKind)

        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val multiple = reconcile(tracker(), FakeOperations(legacy = listOf(
            workInfo(first, WorkInfo.State.ENQUEUED), workInfo(second, WorkInfo.State.RUNNING)
        )))
        assertEquals(SurveyUploadWork.SurveyWorkAction.DEFER_LEGACY_HISTORY, multiple.decision.action)
        val terminals = reconcile(tracker(), FakeOperations(legacy = listOf(workInfo(first, WorkInfo.State.FAILED))))
        assertEquals(SurveyUploadWork.SurveyWorkAction.DEFER_LEGACY_HISTORY, terminals.decision.action)
    }

    @Test
    fun reconcile_sameIdSerializesReplacementWhileDifferentIdsDoNotShareTheLock() {
        val tracker = tracker()
        val staleId = UUID.randomUUID()
        tracker.prepare("survey", staleId, SurveyUploadWorkTracker.IdentityKind.LOGICAL)
        tracker.markEnqueuedIfMatches("survey", staleId)
        val operations = FakeOperations(states = mutableMapOf(staleId to workInfo(staleId, WorkInfo.State.FAILED)))
        val threads = List(2) { Thread { reconcile(tracker, operations) } }
        threads.forEach(Thread::start)
        threads.forEach { it.join() }
        assertEquals(1, operations.enqueuedPolicies.count { it == ExistingWorkPolicy.REPLACE })

        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val parallel = FakeOperations(onEnqueue = {
            entered.countDown()
            release.await(1, TimeUnit.SECONDS)
        })
        val first = Thread { reconcile(tracker(), parallel, "one", validFile("one.json", "one")) }
        val second = Thread { reconcile(tracker(), parallel, "two", validFile("two.json", "two")) }
        first.start(); second.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        release.countDown(); first.join(); second.join()
    }

    private fun decide(
        tracked: SurveyUploadWorkTracker.TrackedSurveyWork? = null,
        state: WorkInfo.State? = null,
        ledger: Boolean = false,
        artifactValid: Boolean = true,
        lookupFailed: Boolean = false,
        legacy: SurveyUploadWork.LegacyWorkInspection = SurveyUploadWork.LegacyWorkInspection()
    ): SurveyUploadWork.SurveyWorkDecision = SurveyUploadWork.decideReconciliation(
        tracked, state, lookupFailed, ledger, artifactValid, legacy
    )

    private fun config() = GitHubUploader.GitHubConfig("owner", "repo", "token")

    private fun reconcile(
        tracker: TestTracker,
        operations: FakeOperations,
        surveyId: String = "survey",
        file: File = validFile(),
        artifactValidator: (File, String) -> SurveyUploadWork.SurveyArtifactValidation = SurveyUploadWork::validateCanonicalArtifact,
        requestBuilder: (GitHubUploader.GitHubConfig, File, String) -> OneTimeWorkRequest = { config, file, surveyId ->
            SurveyUploadWork.buildSurveyJsonRequest(config, file, surveyId)
        }
    ) = SurveyUploadWork.reconcileForTesting(
        config(), surveyId, file, tracker, { false }, artifactValidator, requestBuilder, operations
    )

    private fun validFile(name: String = "survey.json", surveyId: String = "survey") =
        write(name, "{\"survey_id\":\"$surveyId\"}")

    private fun trackedRecord(phase: SurveyUploadWorkTracker.Phase): SurveyUploadWorkTracker.TrackedSurveyWork {
        val tracker = tracker()
        val id = UUID.randomUUID()
        tracker.prepare("survey", id, SurveyUploadWorkTracker.IdentityKind.LOGICAL)
        if (phase == SurveyUploadWorkTracker.Phase.ENQUEUED) tracker.markEnqueuedIfMatches("survey", id)
        return tracker.get("survey")!!
    }

    private fun workInfo(id: UUID, state: WorkInfo.State) = WorkInfo(id, state, emptySet())

    private fun tracker(): TestTracker = TestTracker()

    private class FakeOperations(
        val states: MutableMap<UUID, WorkInfo?> = mutableMapOf(),
        private val legacy: List<WorkInfo> = emptyList(),
        private val queryFailure: Boolean = false,
        private val enqueueFailure: Boolean = false,
        private val completion: SurveyUploadWork.EnqueueCompletion = SurveyUploadWork.EnqueueCompletion.SUCCEEDED,
        private val onEnqueue: (OneTimeWorkRequest) -> Unit = {},
        private val onAwait: () -> Unit = {}
    ) : SurveyUploadWork.WorkManagerOperations {
        val enqueuedPolicies = mutableListOf<ExistingWorkPolicy>()
        var legacyInspectionCalls = 0

        override fun getWorkInfoById(id: UUID): WorkInfo? {
            if (queryFailure) error("query failed")
            return states[id]
        }

        override fun getWorkInfosForUniqueWork(uniqueWorkName: String): List<WorkInfo> {
            legacyInspectionCalls += 1
            return legacy
        }

        override fun enqueueUniqueWork(
            uniqueWorkName: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest
        ): SurveyUploadWork.EnqueueOperation {
            enqueuedPolicies += policy
            onEnqueue(request)
            if (enqueueFailure) error("enqueue failed")
            states[request.id] = WorkInfo(request.id, WorkInfo.State.ENQUEUED, emptySet())
            return object : SurveyUploadWork.EnqueueOperation {
                override fun awaitCompletion(timeoutMs: Long): SurveyUploadWork.EnqueueCompletion {
                    onAwait()
                    return completion
                }
            }
        }
    }

    private class TestTracker : SurveyUploadWork.WorkTrackerOperations {
        private val delegate = SurveyUploadWorkTracker(InMemoryPreferences().sharedPreferences) { 1L }
        var failGet = false
        var failPrepare = false
        var failMark = false
        var failClear = false
        var onMark: () -> Unit = {}
        val enqueuedIds = mutableListOf<UUID>()
        val prepareIds = mutableListOf<UUID>()

        override fun get(surveyId: String): SurveyUploadWorkTracker.TrackedSurveyWork? {
            if (failGet) error("tracker get")
            return delegate.get(surveyId)
        }

        override fun prepare(
            surveyId: String,
            requestId: UUID,
            identityKind: SurveyUploadWorkTracker.IdentityKind
        ): SurveyUploadWorkTracker.TrackedSurveyWork? {
            if (failPrepare) error("tracker prepare")
            prepareIds += requestId
            return delegate.prepare(surveyId, requestId, identityKind)
        }

        override fun markEnqueuedIfMatches(surveyId: String, requestId: UUID): Boolean {
            if (failMark) error("tracker mark")
            onMark()
            enqueuedIds += requestId
            return delegate.markEnqueuedIfMatches(surveyId, requestId)
        }

        override fun clearForUploaded(surveyId: String): Boolean {
            if (failClear) error("tracker clear")
            return delegate.clearForUploaded(surveyId)
        }
    }

    private class InMemoryPreferences {
        private val values = mutableMapOf<String, Any>()
        val sharedPreferences: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0] as String] as? String ?: args[1]
                "getLong" -> values[args[0] as String] as? Long ?: args[1]
                "edit" -> editor
                else -> error("Unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences
        private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)
        ) { _, method, args ->
            when (method.name) {
                "putString", "putLong" -> { values[args[0] as String] = args[1]; editor }
                "remove" -> { values.remove(args[0] as String); editor }
                "commit" -> true
                else -> error("Unexpected SharedPreferences.Editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
    }

    private fun write(name: String, content: String): File =
        File(pendingDir, name).apply { writeText(content) }
}
