package com.negi.survey.issue86

import androidx.test.platform.app.InstrumentationRegistry
import com.negi.survey.net.Issue86ProcessDeathHarness
import com.negi.survey.net.SurveyUploadIdempotency
import com.negi.survey.net.SurveyUploadWork
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test

class Issue86FakeRemoteEvidenceTest {
    private val surveyId = "issue86-fake-remote-test"
    private val path = SurveyUploadWork.canonicalRemotePath(surveyId)
    private val content = "{\"survey_id\":\"$surveyId\",\"answer\":\"synthetic\"}"
    private val fingerprint = SurveyUploadIdempotency.payloadFingerprint(content.toByteArray(Charsets.UTF_8))

    private fun remote() = JSONObject().put("version", 1).put("path", path).put("content", content)
        .put("createCount", 1).put("lookupCount", 0).put("conflictCount", 0)

    private fun checkpoint(scenario: Issue86DeathScenario): JSONObject {
        val original = remote()
        return JSONObject().put("surveyId", surveyId).put("scenario", scenario.name)
            .put("boundary", scenario.boundary.name).put("canonicalPath", path)
            .put("payloadFingerprint", fingerprint).put("remote", original)
            .put("remoteDigest", SurveyUploadIdempotency.payloadFingerprint(original.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun validate(remote: JSONObject, scenario: Issue86DeathScenario = Issue86DeathScenario.REMOTE_CONFIRMED_BEFORE_LEDGER, ledger: String? = fingerprint) =
        validateFakeRemoteEvidence(checkpoint(scenario), remote, scenario, ledger)

    @Test fun checkpointARequiresOneReadAndOneCreate() {
        validate(remote().put("lookupCount", 1))
    }

    @Test fun checkpointBRequiresNoRecoveryRead() {
        validate(remote(), Issue86DeathScenario.LEDGER_COMMITTED_BEFORE_CLEANUP)
    }

    @Test fun changedPayloadFails() {
        assertThrows(IllegalStateException::class.java) { validate(remote().put("content", "changed").put("lookupCount", 1)) }
    }

    @Test fun changedOrDatedPathFails() {
        assertThrows(IllegalStateException::class.java) { validate(remote().put("path", "exports/2026-10-31/survey.json").put("lookupCount", 1)) }
    }

    @Test fun duplicateCreateOrConflictFails() {
        assertThrows(IllegalStateException::class.java) { validate(remote().put("createCount", 2).put("lookupCount", 1)) }
        assertThrows(IllegalStateException::class.java) { validate(remote().put("conflictCount", 1).put("lookupCount", 1)) }
    }

    @Test fun missingReadBackFails() {
        assertThrows(IllegalStateException::class.java) { validate(remote()) }
    }

    @Test fun ledgerMismatchFails() {
        assertThrows(IllegalStateException::class.java) { validate(remote().put("lookupCount", 1), ledger = "incorrect") }
    }

    @Test fun malformedRemoteFails() {
        assertThrows(Exception::class.java) { validate(JSONObject()) }
    }

    @Test fun phase1BoundaryMatchingRemainsStrict() {
        val remoteBoundary = Issue86ProcessDeathHarness.Boundary.REMOTE_CONFIRMED_BEFORE_LEDGER
        val ledgerBoundary = Issue86ProcessDeathHarness.Boundary.LEDGER_COMMITTED_BEFORE_CLEANUP

        assertTrue(shouldInjectTerminationAtBoundary(remoteBoundary, remoteBoundary, terminateAtBoundary = true))
        assertTrue(shouldInjectTerminationAtBoundary(ledgerBoundary, ledgerBoundary, terminateAtBoundary = true))
        assertThrows(IllegalStateException::class.java) {
            shouldInjectTerminationAtBoundary(ledgerBoundary, remoteBoundary, terminateAtBoundary = true)
        }
        assertThrows(IllegalStateException::class.java) {
            shouldInjectTerminationAtBoundary(remoteBoundary, ledgerBoundary, terminateAtBoundary = true)
        }
    }

    @Test fun phase2ObservationAcceptsBothWorkerBoundariesWithoutTermination() = runBlocking {
        assumeTrue(Issue86ProcessDeathHarness.isCompileTimeIsolatedBuild())
        val remoteBoundary = Issue86ProcessDeathHarness.Boundary.REMOTE_CONFIRMED_BEFORE_LEDGER
        val ledgerBoundary = Issue86ProcessDeathHarness.Boundary.LEDGER_COMMITTED_BEFORE_CLEANUP
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = "issue86-pd-observation-${UUID.randomUUID()}"
        val root = File(context.filesDir, "issue86_process_death/$session")
        var terminationCalled = false
        val fake = Issue86DurableFakeRemote(
            context = context,
            sessionId = session,
            scenario = Issue86DeathScenario.REMOTE_CONFIRMED_BEFORE_LEDGER,
            terminateAtBoundary = false,
            terminateSelf = { terminationCalled = true; error("Observation mode must not terminate.") },
        )
        try {
            fake.awaitBoundary(remoteBoundary)
            fake.awaitBoundary(ledgerBoundary)
            assertFalse(terminationCalled)
            assertNull(fake.checkpoint())
        } finally {
            root.deleteRecursively()
        }
    }
}
