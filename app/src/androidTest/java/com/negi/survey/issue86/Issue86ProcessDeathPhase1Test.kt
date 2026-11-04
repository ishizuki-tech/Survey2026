package com.negi.survey.issue86

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.negi.survey.BuildConfig
import com.negi.survey.net.GitHubUploadWorker
import com.negi.survey.net.Issue86ProcessDeathHarness
import com.negi.survey.net.SurveyUploadExecutionAuthority
import com.negi.survey.net.SurveyUploadRouteStore
import com.negi.survey.net.SurveyUploadWork
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deliberately self-terminates only the isolated target process after its selected durable
 * checkpoint has been persisted. The host runner merely observes that event.
 */
@RunWith(AndroidJUnit4::class)
class Issue86ProcessDeathPhase1Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun remoteConfirmedBeforeLedger_selfTerminatesAfterCheckpoint() {
        runPhase1(Issue86DeathScenario.REMOTE_CONFIRMED_BEFORE_LEDGER)
    }

    @Test fun ledgerCommittedBeforeCleanup_selfTerminatesAfterCheckpoint() {
        runPhase1(Issue86DeathScenario.LEDGER_COMMITTED_BEFORE_CLEANUP)
    }

    private fun runPhase1(expected: Issue86DeathScenario) = runBlocking {
        val session = InstrumentationRegistry.getArguments().getString(ARG_SESSION).orEmpty()
        val scenario = InstrumentationRegistry.getArguments().getString(ARG_SCENARIO).orEmpty()
        assumeTrue("Phase 1 requires an explicit matching scenario.", scenario == expected.name)
        assumeTrue("Phase 1 requires an explicit session ID.", session.isNotBlank())
        require(session.matches(Regex("issue86-pd-[a-zA-Z0-9_-]{8,80}"))) { "Unsafe test session ID." }
        check(BuildConfig.ISSUE86_PROCESS_DEATH_HARNESS)
        check(BuildConfig.APPLICATION_ID == "com.negi.survey.issue86.it")
        check(BuildConfig.GH_UPLOAD_MODE == "local")
        check(Issue86ProcessDeathHarness.isCompileTimeIsolatedBuild())

        val surveyId = "issue86-process-death-$session"
        val pending = File(context.filesDir, "pending_uploads").apply { mkdirs() }
        val matching = File(pending, "$session-a.json")
        val duplicate = File(pending, "$session-b.json")
        val payload = "{\"survey_id\":\"$surveyId\",\"answer\":\"synthetic-a\",\"exported_at\":\"2026-10-31T00:00:00Z\"}"
        matching.writeText(payload, Charsets.UTF_8)
        duplicate.writeText(payload.replace("synthetic-a", "synthetic-b"), Charsets.UTF_8)
        check(SurveyUploadRouteStore(context).reserveCanonical(surveyId))
        check(SurveyUploadExecutionAuthority(context).claim(surveyId, SurveyUploadExecutionAuthority.Protocol.CANONICAL) ==
            SurveyUploadExecutionAuthority.Claim.AUTHORIZED)

        val fake = Issue86DurableFakeRemote(context, session, expected)
        fake.prepare(matching, surveyId, SurveyUploadWork.canonicalRemotePath(surveyId))
        Issue86ProcessDeathHarness.register(fake)
        val input = workDataOf(
            GitHubUploadWorker.KEY_MODE to "file",
            GitHubUploadWorker.KEY_OWNER to "issue86-test",
            GitHubUploadWorker.KEY_REPO to "isolated",
            GitHubUploadWorker.KEY_TOKEN to "synthetic",
            GitHubUploadWorker.KEY_BRANCH to "main",
            GitHubUploadWorker.KEY_PATH_PREFIX to "",
            GitHubUploadWorker.KEY_FILE_PATH to matching.absolutePath,
            GitHubUploadWorker.KEY_FILE_NAME to matching.name,
            GitHubUploadWorker.KEY_UPLOAD_KIND to GitHubUploadWorker.UPLOAD_KIND_SURVEY_JSON,
            GitHubUploadWorker.KEY_SURVEY_ID to surveyId,
            GitHubUploadWorker.KEY_SURVEY_UPLOAD_PROTOCOL to GitHubUploadWorker.SURVEY_UPLOAD_PROTOCOL_CANONICAL_IMMUTABLE_V1,
            Issue86ProcessDeathHarness.KEY_SESSION_ID to session,
        )
        // The call reaches the real Worker hook, persists evidence, then kills only this process.
        TestListenableWorkerBuilder<GitHubUploadWorker>(context).setInputData(input).build().doWork()
    }

    private companion object {
        const val ARG_SESSION = "issue86ProcessDeathSession"
        const val ARG_SCENARIO = "issue86ProcessDeathScenario"
    }
}
