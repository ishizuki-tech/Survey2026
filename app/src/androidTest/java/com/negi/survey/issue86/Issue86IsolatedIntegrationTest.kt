package com.negi.survey.issue86

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.negi.survey.net.GitHubUploader
import com.negi.survey.net.SurveyFinalizationOperations
import com.negi.survey.net.SurveyFinalizationResult
import com.negi.survey.net.SurveyUploadExecutionAuthority
import com.negi.survey.net.SurveyUploadFinalizer
import com.negi.survey.net.SurveyUploadIdempotency
import com.negi.survey.net.SurveyUploadRescheduler
import com.negi.survey.net.SurveyUploadRouteStore
import com.negi.survey.net.SurveyUploadWork
import com.negi.survey.net.SurveyUploadedArtifactCleanup
import com.negi.survey.net.UploadedSurveyStore
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.vm.SurveyFinalizationSnapshot
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Device-safe integration coverage: only this target package's private files and preferences. */
@RunWith(AndroidJUnit4::class)
class Issue86IsolatedIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var pendingDirectory: File
    private val files = mutableListOf<File>()
    private val testWorkNames = mutableListOf<String>()
    private val canonicalRoutes = mutableListOf<String>()

    @Before fun setUp() {
        pendingDirectory = File(context.filesDir, "pending_uploads").apply { mkdirs() }
    }

    @After fun tearDown() {
        files.forEach { file -> if (file.parentFile == pendingDirectory) file.delete() }
        testWorkNames.forEach { WorkManager.getInstance(context).cancelUniqueWork(it).result.get() }
        canonicalRoutes.forEach { SurveyUploadRouteStore(context).clearCanonical(it) }
    }

    @Test fun durableLedgerPersistsFingerprintAcrossStoreRecreation() {
        val id = uniqueId("ledger")
        val payload = payload(id, "a", "2026-10-31")
        val fingerprint = fingerprint(payload)
        UploadedSurveyStore(context).markConfirmedUploaded(id, "2026-10-31", fingerprint)

        val recreated = UploadedSurveyStore(context)
        assertTrue(recreated.isUploaded(id))
        assertEquals(fingerprint, recreated.payloadFingerprintFor(id))
        assertFalse(fingerprint == fingerprint(payload(id, "b", "2026-10-31")))
    }

    @Test fun fingerprintCleanupDeletesOnlyExactBytesInOwnedTargetStorage() {
        val id = uniqueId("cleanup")
        val delivered = staged(id, "a", "2026-10-31")
        val different = staged(id, "b", "2026-10-31")
        val exportedAtDifferent = staged(id, "a", "2026-11-01")
        val ledgerFingerprint = fingerprint(delivered.readText())

        assertEquals(SurveyUploadedArtifactCleanup.Outcome.CLEANED,
            SurveyUploadedArtifactCleanup.cleanup(id, delivered, CleanupOps(ledgerFingerprint)))
        assertEquals(SurveyUploadedArtifactCleanup.Outcome.DEFER_UNVERIFIED,
            SurveyUploadedArtifactCleanup.cleanup(id, different, CleanupOps(ledgerFingerprint)))
        assertEquals(SurveyUploadedArtifactCleanup.Outcome.DEFER_UNVERIFIED,
            SurveyUploadedArtifactCleanup.cleanup(id, exportedAtDifferent, CleanupOps(ledgerFingerprint)))
        assertFalse(delivered.exists())
        assertTrue(different.exists())
        assertTrue(exportedAtDifferent.exists())
    }

    @Test fun fingerprintlessLedgerAndInvalidJsonRemainStaged() {
        val id = uniqueId("historical")
        val historical = staged(id, "a", "2026-10-31")
        val invalid = File(pendingDirectory, "issue86-invalid-$id.json").apply { writeText("not-json"); files += this }
        assertEquals(SurveyUploadedArtifactCleanup.Outcome.DEFER_UNVERIFIED,
            SurveyUploadedArtifactCleanup.cleanup(id, historical, CleanupOps(null)))
        assertEquals(SurveyUploadedArtifactCleanup.Outcome.DEFER_UNVERIFIED,
            SurveyUploadedArtifactCleanup.cleanup(id, invalid, CleanupOps(fingerprint(historical.readText()))))
        assertTrue(historical.exists())
        assertTrue(invalid.exists())
    }

    @Test fun finalizerLateSkipUploadedRetainsDifferentPayloadWithoutEnqueue() = kotlinx.coroutines.runBlocking {
        val id = uniqueId("finalizer")
        val delivered = payload(id, "a", "2026-10-31")
        val staged = staged(id, "b", "2026-10-31")
        val ops = FinalizerOps(staged, CleanupOps(fingerprint(delivered)))
        val result = SurveyUploadFinalizer(ops, testOnly = true).finalize(snapshot(id), config(), DeviceUploadTag("issue86"), "2026-10-31")
        assertEquals(SurveyFinalizationResult.StagedPending(staged, reused = true), result)
        assertTrue(staged.exists())
        assertEquals(1, ops.cleanupCalls)
        assertEquals(0, ops.enqueueCalls)
    }

    @Test fun authorityPersistsAndBlocksOppositeProtocol() {
        val id = uniqueId("authority")
        val first = SurveyUploadExecutionAuthority(context)
        assertEquals(SurveyUploadExecutionAuthority.Claim.AUTHORIZED,
            first.claim(id, SurveyUploadExecutionAuthority.Protocol.LEGACY))
        val recreated = SurveyUploadExecutionAuthority(context)
        assertTrue(recreated.claim(id, SurveyUploadExecutionAuthority.Protocol.CANONICAL) is
            SurveyUploadExecutionAuthority.Claim.BLOCKED_BY_OTHER_PROTOCOL)
        assertEquals(SurveyUploadExecutionAuthority.Claim.AUTHORIZED,
            recreated.claim(id, SurveyUploadExecutionAuthority.Protocol.LEGACY))
        assertTrue(recreated.clearForRecordedUpload(id, SurveyUploadExecutionAuthority.Protocol.LEGACY))
    }

    @Test fun workManagerActiveLegacyAndCanonicalWorkDeferCleanupThenTerminalWorkAllowsIt() {
        val id = uniqueId("work")
        val file = staged(id, "a", "2026-10-31")
        UploadedSurveyStore(context).markConfirmedUploaded(id, "2026-10-31", fingerprint(file.readText()))
        val workManager = WorkManager.getInstance(context)

        val legacyName = SurveyUploadWork.legacyWorkName(file)
        val legacyRequest = enqueueControlled(workManager, legacyName)
        assertEquals(WorkInfo.State.ENQUEUED, requireNotNull(workManager.getWorkInfoById(legacyRequest.id).get()).state)
        val authority = SurveyUploadExecutionAuthority(context)
        assertEquals(SurveyUploadExecutionAuthority.Claim.AUTHORIZED,
            authority.claim(id, SurveyUploadExecutionAuthority.Protocol.LEGACY))
        assertTrue(authority.claim(id, SurveyUploadExecutionAuthority.Protocol.CANONICAL) is
            SurveyUploadExecutionAuthority.Claim.BLOCKED_BY_OTHER_PROTOCOL)
        assertEquals(SurveyUploadedArtifactCleanup.Outcome.DEFER_ACTIVE_WORK,
            SurveyUploadedArtifactCleanup.cleanup(context, id, file))
        assertTrue(file.exists())
        workManager.cancelWorkById(legacyRequest.id).result.get()
        assertEquals(WorkInfo.State.CANCELLED, requireNotNull(workManager.getWorkInfoById(legacyRequest.id).get()).state)

        val canonicalName = requireNotNull(SurveyUploadWork.logicalWorkName(id))
        val canonicalRequest = enqueueControlled(workManager, canonicalName)
        assertEquals(WorkInfo.State.ENQUEUED, requireNotNull(workManager.getWorkInfoById(canonicalRequest.id).get()).state)
        assertEquals(SurveyUploadedArtifactCleanup.Outcome.DEFER_ACTIVE_WORK,
            SurveyUploadedArtifactCleanup.cleanup(context, id, file))
        assertTrue(file.exists())
        workManager.cancelWorkById(canonicalRequest.id).result.get()
        assertEquals(WorkInfo.State.CANCELLED, requireNotNull(workManager.getWorkInfoById(canonicalRequest.id).get()).state)

        assertEquals(SurveyUploadedArtifactCleanup.Outcome.CLEANED,
            SurveyUploadedArtifactCleanup.cleanup(context, id, file))
        assertFalse(file.exists())
        assertEquals(1, workManager.getWorkInfosForUniqueWork(legacyName).get().size)
        assertEquals(1, workManager.getWorkInfosForUniqueWork(canonicalName).get().size)
    }

    @Test fun startupRecoveryCleansSingleMatchingArtifactAndClearsCanonicalRoute() {
        val id = uniqueId("recovery-single")
        val matching = staged(id, "a", "2026-10-31")
        UploadedSurveyStore(context).markConfirmedUploaded(id, "2026-10-31", fingerprint(matching.readText()))
        reserveRoute(id)

        val recovery = SurveyUploadRescheduler.recoverPendingSurveyUploads(context, disabledConfig())
        assertEquals(SurveyUploadRescheduler.RecoveryClassification.ALREADY_UPLOADED,
            recovery.candidates.single().classification)
        assertFalse(matching.exists())
        assertEquals(null, SurveyUploadRouteStore(context).protocolFor(id))
        assertNoSurveyUploadEnqueue(id, matching)
    }

    @Test fun startupRecoveryCleansOnlyFingerprintConfirmedArtifactsWithoutEnqueue() {
        val id = uniqueId("recovery")
        val matching = staged(id, "a", "2026-10-31")
        val different = staged(id, "b", "2026-10-31")
        UploadedSurveyStore(context).markConfirmedUploaded(id, "2026-10-31", fingerprint(matching.readText()))
        reserveRoute(id)

        val first = SurveyUploadRescheduler.recoverPendingSurveyUploads(context, disabledConfig())
        assertEquals(1, first.discoveredSurveyCount)
        assertFalse(matching.exists())
        assertTrue(different.exists())
        assertEquals(SurveyUploadRescheduler.RecoveryClassification.DEFERRED, first.candidates.single().classification)
        assertEquals(SurveyUploadWork.SurveyUploadProtocol.CANONICAL_IMMUTABLE_V1,
            SurveyUploadRouteStore(context).protocolFor(id))
        assertNoSurveyUploadEnqueue(id, different)

        val second = SurveyUploadRescheduler.recoverPendingSurveyUploads(context, disabledConfig())
        assertTrue(different.exists())
        assertEquals(SurveyUploadRescheduler.RecoveryClassification.DEFERRED, second.candidates.single().classification)
        assertNoSurveyUploadEnqueue(id, different)
    }

    @Test fun startupRecoveryRetainsFingerprintlessAndMismatchedLedgerArtifactsOffline() {
        val historicalId = uniqueId("historical-recovery")
        val historical = staged(historicalId, "a", "2026-10-31")
        UploadedSurveyStore(context).markUploaded(historicalId, "2026-10-31")
        reserveRoute(historicalId)

        val mismatchId = uniqueId("mismatch-recovery")
        val delivered = payload(mismatchId, "a", "2026-10-31")
        val mismatch = staged(mismatchId, "b", "2026-10-31")
        UploadedSurveyStore(context).markConfirmedUploaded(mismatchId, "2026-10-31", fingerprint(delivered))
        reserveRoute(mismatchId)

        val recovery = SurveyUploadRescheduler.recoverPendingSurveyUploads(context, disabledConfig())
        assertTrue(historical.exists())
        assertTrue(mismatch.exists())
        assertEquals(SurveyUploadWork.SurveyUploadProtocol.CANONICAL_IMMUTABLE_V1,
            SurveyUploadRouteStore(context).protocolFor(historicalId))
        assertEquals(SurveyUploadWork.SurveyUploadProtocol.CANONICAL_IMMUTABLE_V1,
            SurveyUploadRouteStore(context).protocolFor(mismatchId))
        assertEquals(2, recovery.candidates.count { it.classification == SurveyUploadRescheduler.RecoveryClassification.DEFERRED })
        assertNoSurveyUploadEnqueue(historicalId, historical)
        assertNoSurveyUploadEnqueue(mismatchId, mismatch)
    }

    private fun staged(id: String, answer: String, exportedAt: String): File =
        File(pendingDirectory, "issue86-$id-${files.size}.json").apply { writeText(payload(id, answer, exportedAt)); files += this }

    private fun payload(id: String, answer: String, exportedAt: String) =
        "{\"survey_id\":\"$id\",\"answer\":\"$answer\",\"exported_at\":\"$exportedAt\"}"

    private fun fingerprint(content: String) = SurveyUploadIdempotency.payloadFingerprint(content)
    private fun uniqueId(prefix: String) = "issue86-$prefix-" + System.nanoTime()
    private fun config() = GitHubUploader.GitHubConfig("owner", "repo", "token")
    private fun disabledConfig() = GitHubUploader.GitHubConfig("", "", "")
    private fun snapshot(id: String) = SurveyFinalizationSnapshot(id, emptyMap(), emptyMap(), emptyMap(), emptyList(), "{}", emptyMap())

    private fun reserveRoute(id: String) {
        assertTrue(SurveyUploadRouteStore(context).reserveCanonical(id))
        canonicalRoutes += id
    }

    private fun enqueueControlled(workManager: WorkManager, name: String) =
        OneTimeWorkRequestBuilder<Issue86ControlledWorker>()
            .setInitialDelay(1, TimeUnit.DAYS)
            .build()
            .also { request ->
                testWorkNames += name
                workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).result.get()
            }

    private fun assertNoSurveyUploadEnqueue(id: String, file: File) {
        val workManager = WorkManager.getInstance(context)
        assertTrue(workManager.getWorkInfosForUniqueWork(requireNotNull(SurveyUploadWork.logicalWorkName(id))).get().isEmpty())
        assertTrue(workManager.getWorkInfosForUniqueWork(SurveyUploadWork.legacyWorkName(file)).get().isEmpty())
    }

    private class CleanupOps(private val ledgerFingerprint: String?) : SurveyUploadedArtifactCleanup.Operations {
        override fun ledgerPayloadFingerprint(surveyId: String) = ledgerFingerprint
        override fun isOwnedPendingFile(file: File) = true
        override fun hasOtherActiveWork(surveyId: String, file: File) = false
        override fun payloadFingerprint(file: File, surveyId: String): String? = runCatching {
            val content = file.readText()
            SurveyUploadIdempotency.validateLocalPayload(surveyId, content)
                ?.let { SurveyUploadIdempotency.payloadFingerprint(content) }
        }.getOrNull()
        override fun delete(file: File) = file.delete()
    }

    private class FinalizerOps(
        private val file: File,
        private val cleanupOps: SurveyUploadedArtifactCleanup.Operations,
    ) : SurveyFinalizationOperations {
        var cleanupCalls = 0
        var enqueueCalls = 0
        override fun isUploaded(surveyId: String) = false
        override fun findPendingSurveyFile(surveyId: String) = file
        override fun reserveCanonicalSurveyRoute(surveyId: String) = true
        override fun hasCanonicalSurveyRoute(surveyId: String) = true
        override fun stageSurveyJson(snapshot: SurveyFinalizationSnapshot, tag: DeviceUploadTag, stamp: String) = error("must reuse staged file")
        override fun reconcileSurveyJson(config: GitHubUploader.GitHubConfig, file: File, surveyId: String) =
            SurveyUploadWork.SurveyWorkReconcileResult(surveyId, "logical", "legacy",
                SurveyUploadWork.SurveyWorkDecision(SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED, "recorded"), false)
        override fun cleanupUploadedSurveyArtifact(surveyId: String, file: File): SurveyUploadedArtifactCleanup.Outcome {
            cleanupCalls++
            return SurveyUploadedArtifactCleanup.cleanup(surveyId, file, cleanupOps)
        }
        override suspend fun scheduleVoiceArtifacts(config: GitHubUploader.GitHubConfig, surveyId: String, expectedVoiceFileNames: Set<String>) { enqueueCalls++ }
        override suspend fun scheduleLogArtifact(config: GitHubUploader.GitHubConfig, surveyId: String, exportedAtStamp: String) { enqueueCalls++ }
        override suspend fun scheduleDiagnosticTraceArtifacts(config: GitHubUploader.GitHubConfig, surveyId: String) { enqueueCalls++ }
    }
}

/** A deliberately delayed, no-network WorkManager fixture used only by the isolated E test. */
class Issue86ControlledWorker(appContext: android.content.Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result = Result.success()
}
