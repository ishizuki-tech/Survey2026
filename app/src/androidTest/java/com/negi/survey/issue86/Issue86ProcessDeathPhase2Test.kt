package com.negi.survey.issue86

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.negi.survey.net.GitHubUploadWorker
import com.negi.survey.net.GitHubUploader
import com.negi.survey.net.Issue86ProcessDeathHarness
import com.negi.survey.net.SurveyUploadExecutionAuthority
import com.negi.survey.net.SurveyUploadIdempotency
import com.negi.survey.net.SurveyUploadRescheduler
import com.negi.survey.net.SurveyUploadRouteStore
import com.negi.survey.net.SurveyUploadWork
import com.negi.survey.net.UploadedSurveyStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** Runs only in a separate explicit invocation after Phase 1 self-terminated. */
@RunWith(AndroidJUnit4::class)
class Issue86ProcessDeathPhase2Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun recoverRemoteConfirmedBeforeLedger() = runPhase2(Issue86DeathScenario.REMOTE_CONFIRMED_BEFORE_LEDGER)
    @Test fun recoverLedgerCommittedBeforeCleanup() = runPhase2(Issue86DeathScenario.LEDGER_COMMITTED_BEFORE_CLEANUP)

    private fun runPhase2(expected: Issue86DeathScenario) = runBlocking {
        val session = InstrumentationRegistry.getArguments().getString(ARG_SESSION).orEmpty()
        val scenario = InstrumentationRegistry.getArguments().getString(ARG_SCENARIO).orEmpty()
        assumeTrue("Phase 2 requires an explicit matching scenario.", session.isNotBlank() && scenario == expected.name)
        val fake = Issue86DurableFakeRemote(context, session, expected, terminateAtBoundary = false)
        val checkpoint = requireNotNull(fake.checkpoint()) { "Missing Phase 1 checkpoint." }
        assertEquals(2, checkpoint.getInt("version"))
        assertEquals(session, checkpoint.getString("session"))
        assertEquals(expected.name, checkpoint.getString("scenario"))
        assertEquals("com.negi.survey.issue86.it", checkpoint.getString("applicationId"))
        assertEquals(expected.boundary.name, checkpoint.getString("boundary"))
        assertEquals("SELF_PROCESS_KILL", checkpoint.getString("terminationMode"))
        assertEquals("RECORDED", checkpoint.getString("terminationIntent"))
        assertTrue(checkpoint.getBoolean("ready"))
        assertTrue(checkpoint.getString("payloadFingerprint").matches(Regex("[0-9a-f]{64}")))
        val phase1Pid = checkpoint.getInt("readyPid")
        assertFalse("Phase 1 PID is still alive; do not run Phase 2.", File("/proc/$phase1Pid").exists())
        assertTrue("Phase 2 must be a new target process.", Process.myPid() != phase1Pid)
        fake.requireExpectedExit(checkpoint)
        val surveyId = checkpoint.getString("surveyId")
        val matching = File(context.filesDir, "pending_uploads/$session-a.json")
        val duplicate = File(context.filesDir, "pending_uploads/$session-b.json")
        assertTrue(matching.exists())
        assertTrue(duplicate.exists())
        assertEquals(checkpoint.getString("canonicalPath"), SurveyUploadWork.canonicalRemotePath(surveyId))
        val phase1Remote = requireNotNull(fake.remoteEvidence()) { "Durable fake remote evidence is required." }
        assertEquals(checkpoint.getString("canonicalPath"), phase1Remote.getString("path"))
        assertTrue(phase1Remote.getString("content").isNotBlank())
        assertNoResumedSurveyWorker(surveyId, listOf(matching, duplicate))
        Issue86ProcessDeathHarness.register(fake)
        try {
            if (expected == Issue86DeathScenario.REMOTE_CONFIRMED_BEFORE_LEDGER) {
                assertFalse(UploadedSurveyStore(context).isUploaded(surveyId))
                val owner = SurveyUploadExecutionAuthority(context).ownerFor(surveyId)
                assertTrue(owner is SurveyUploadExecutionAuthority.Owner.VALID)
                assertEquals(SurveyUploadExecutionAuthority.Protocol.CANONICAL,
                    (owner as SurveyUploadExecutionAuthority.Owner.VALID).protocol)
            } else {
                assertTrue(UploadedSurveyStore(context).isUploaded(surveyId))
                assertTrue(SurveyUploadExecutionAuthority(context).ownerFor(surveyId) is
                    SurveyUploadExecutionAuthority.Owner.NONE)
            }
            // This is the production grouped discovery/reconciliation path. Its session-bearing
            // request cannot use HTTP because the registration is mandatory in this isolated APK.
            val recovery = SurveyUploadRescheduler.recoverPendingSurveyUploads(
                context, GitHubUploader.GitHubConfig("issue86-test", "isolated", "synthetic")
            )
            if (expected == Issue86DeathScenario.REMOTE_CONFIRMED_BEFORE_LEDGER) {
                assertEquals(1, recovery.discoveredSurveyCount)
                val candidate = recovery.candidates.single()
                checkSameLogicalSurvey(surveyId, candidate.normalizedSurveyId)
                assertEquals(matching.canonicalFile, candidate.canonicalFile.canonicalFile)
                assertEquals(SurveyUploadWork.SurveyWorkAction.CAPTURED_FOR_ISOLATED_TEST,
                    candidate.reconciliation?.decision?.action)
                val captured = fake.takeCapturedRecoveryRequest()
                checkSameLogicalSurvey(surveyId, captured.surveyId)
                assertEquals(matching.canonicalFile, captured.canonicalFile.canonicalFile)
                assertEquals(SurveyUploadWork.logicalWorkName(surveyId), captured.logicalWorkName)
                assertEquals(session, captured.request.workSpec.input.getString(Issue86ProcessDeathHarness.KEY_SESSION_ID))
                assertEquals(matching.absolutePath, captured.request.workSpec.input.getString(GitHubUploadWorker.KEY_FILE_PATH))
                assertEquals(GitHubUploadWorker.SURVEY_UPLOAD_PROTOCOL_CANONICAL_IMMUTABLE_V1,
                    captured.request.workSpec.input.getString(GitHubUploadWorker.KEY_SURVEY_UPLOAD_PROTOCOL))
                val worker = TestListenableWorkerBuilder<GitHubUploadWorker>(context)
                    .setInputData(captured.request.workSpec.input)
                    .build()
                val result = worker.doWork()
                assertTrue("Controlled recovered Worker must succeed.", result is ListenableWorker.Result.Success)
            } else {
                assertEquals(1, recovery.discoveredSurveyCount)
                checkSameLogicalSurvey(surveyId, recovery.candidates.single().normalizedSurveyId)
                assertEquals(SurveyUploadRescheduler.RecoveryClassification.ALREADY_UPLOADED,
                    recovery.candidates.single().classification)
            }
            assertTrue(UploadedSurveyStore(context).isUploaded(surveyId))
            assertEquals(checkpoint.getString("payloadFingerprint"), UploadedSurveyStore(context).payloadFingerprintFor(surveyId))
            assertFalse(matching.exists())
            assertTrue(duplicate.exists())
            val remote = requireNotNull(fake.remoteEvidence()) { "Missing durable fake remote evidence." }
            validateFakeRemoteEvidence(
                checkpoint, remote, expected,
                UploadedSurveyStore(context).payloadFingerprintFor(surveyId),
            )
            assertTrue(SurveyUploadExecutionAuthority(context).ownerFor(surveyId) !is SurveyUploadExecutionAuthority.Owner.VALID)
            if (expected == Issue86DeathScenario.LEDGER_COMMITTED_BEFORE_CLEANUP) {
                assertEquals(null, SurveyUploadRouteStore(context).protocolFor(surveyId))
            }
            fake.recordFinal("PASS")
        } finally {
            Issue86ProcessDeathHarness.unregister(session)
        }
    }


    private fun input(session: String, surveyId: String, file: File) = workDataOf(
        GitHubUploadWorker.KEY_MODE to "file",
        GitHubUploadWorker.KEY_OWNER to "issue86-test",
        GitHubUploadWorker.KEY_REPO to "isolated",
        GitHubUploadWorker.KEY_TOKEN to "synthetic",
        GitHubUploadWorker.KEY_BRANCH to "main",
        GitHubUploadWorker.KEY_PATH_PREFIX to "",
        GitHubUploadWorker.KEY_FILE_PATH to file.absolutePath,
        GitHubUploadWorker.KEY_FILE_NAME to file.name,
        GitHubUploadWorker.KEY_UPLOAD_KIND to GitHubUploadWorker.UPLOAD_KIND_SURVEY_JSON,
        GitHubUploadWorker.KEY_SURVEY_ID to surveyId,
        GitHubUploadWorker.KEY_SURVEY_UPLOAD_PROTOCOL to GitHubUploadWorker.SURVEY_UPLOAD_PROTOCOL_CANONICAL_IMMUTABLE_V1,
        Issue86ProcessDeathHarness.KEY_SESSION_ID to session,
    )

    private fun assertNoResumedSurveyWorker(surveyId: String, files: List<File>) {
        val manager = WorkManager.getInstance(context)
        val names = listOf(
            requireNotNull(SurveyUploadWork.logicalWorkName(surveyId)),
            SurveyUploadWork.uniqueWorkName(SurveyUploadWork.canonicalRemotePath(surveyId)),
        ) + files.map(SurveyUploadWork::legacyWorkName)
        checkNoCompetingSurveyWork(names) { name ->
            manager.getWorkInfosForUniqueWork(name).get().map { it.state }
        }
    }

    private companion object {
        const val ARG_SESSION = "issue86ProcessDeathSession"
        const val ARG_SCENARIO = "issue86ProcessDeathScenario"
    }
}

internal fun validateFakeRemoteEvidence(
    checkpoint: JSONObject,
    remote: JSONObject,
    scenario: Issue86DeathScenario,
    ledgerFingerprint: String?,
) {
    val original = checkpoint.getJSONObject("remote")
    val expectedPath = SurveyUploadWork.canonicalRemotePath(checkpoint.getString("surveyId"))
    val fingerprint = checkpoint.getString("payloadFingerprint")
    check(checkpoint.getString("scenario") == scenario.name)
    check(checkpoint.getString("boundary") == scenario.boundary.name)
    check(fingerprint.matches(Regex("[0-9a-f]{64}")))
    check(checkpoint.getString("canonicalPath") == expectedPath)
    check(checkpoint.getString("remoteDigest") ==
        SurveyUploadIdempotency.payloadFingerprint(original.toString().toByteArray(Charsets.UTF_8)))
    check(original.getInt("version") == 1 && remote.getInt("version") == 1)
    check(original.getString("path") == expectedPath && remote.getString("path") == expectedPath)
    val originalContent = original.getString("content")
    val remoteContent = remote.getString("content")
    check(originalContent.isNotBlank() && originalContent == remoteContent)
    check(SurveyUploadIdempotency.payloadFingerprint(originalContent.toByteArray(Charsets.UTF_8)) == fingerprint)
    check(SurveyUploadIdempotency.payloadFingerprint(remoteContent.toByteArray(Charsets.UTF_8)) == fingerprint)
    check(ledgerFingerprint == fingerprint)
    check(original.getInt("createCount") == 1 && remote.getInt("createCount") == 1)
    check(original.getInt("conflictCount") == 0 && remote.getInt("conflictCount") == 0)
    check(original.getInt("lookupCount") == 0)
    val expectedLookups = if (scenario == Issue86DeathScenario.REMOTE_CONFIRMED_BEFORE_LEDGER) 1 else 0
    check(remote.getInt("lookupCount") == expectedLookups)
}

internal fun checkNoCompetingSurveyWork(names: List<String>, query: (String) -> List<androidx.work.WorkInfo.State>) {
    val active = names.distinct().flatMap(query).filter { !it.isFinished }
    check(active.isEmpty()) { "A resumed WorkManager survey Worker would race controlled recovery: $active" }
}
