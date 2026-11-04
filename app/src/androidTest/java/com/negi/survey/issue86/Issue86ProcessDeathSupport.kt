package com.negi.survey.issue86

import android.content.Context
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Process
import android.util.Log
import com.negi.survey.net.GitHubUploader
import com.negi.survey.net.Issue86ProcessDeathHarness
import com.negi.survey.net.SurveyUploadIdempotency
import com.negi.survey.net.SurveyUploadWork
import androidx.work.OneTimeWorkRequest
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

internal enum class Issue86DeathScenario(val boundary: Issue86ProcessDeathHarness.Boundary) {
    REMOTE_CONFIRMED_BEFORE_LEDGER(Issue86ProcessDeathHarness.Boundary.REMOTE_CONFIRMED_BEFORE_LEDGER),
    LEDGER_COMMITTED_BEFORE_CLEANUP(Issue86ProcessDeathHarness.Boundary.LEDGER_COMMITTED_BEFORE_CLEANUP),
}

/** Durable, app-private fake remote. It delegates matching/reconciliation to GitHubUploader. */
internal class Issue86DurableFakeRemote(
    private val context: Context,
    override val sessionId: String,
    private val scenario: Issue86DeathScenario,
    private val terminateAtBoundary: Boolean = true,
    private val verifyExitEvidence: (Context, JSONObject) -> Unit = ::verifyHistoricalSelfKill,
    private val terminateSelf: () -> Unit = {
        Process.killProcess(Process.myPid())
        error("Issue #86 self-termination unexpectedly returned.")
    },
) : Issue86ProcessDeathHarness.Registration {
    data class CapturedRecoveryRequest(
        val request: OneTimeWorkRequest,
        val logicalWorkName: String,
        val surveyId: String,
        val canonicalFile: File,
    )

    private val root = File(context.filesDir, "issue86_process_death/$sessionId")
    private val stateFile = File(root, "remote-v1.json")
    private val checkpointFile = File(root, "checkpoint-v1.json")
    private var capturedRecoveryRequest: CapturedRecoveryRequest? = null
    override val expectedBoundary: Issue86ProcessDeathHarness.Boundary =
        scenario.boundary

    init {
        require(sessionId.matches(Regex("issue86-pd-[a-zA-Z0-9_-]{8,80}"))) { "Unsafe test session ID." }
        check(root.exists() || root.mkdirs()) { "Could not create Issue #86 test directory." }
    }

    fun checkpoint(): JSONObject? = state(checkpointFile)
    fun remoteEvidence(): JSONObject? = remoteState()

    fun takeCapturedRecoveryRequest(): CapturedRecoveryRequest =
        requireNotNull(capturedRecoveryRequest) { "Recovery did not capture a controlled Worker request." }
            .also { capturedRecoveryRequest = null }

    override fun captureRecoveryRequest(
        request: OneTimeWorkRequest,
        logicalWorkName: String,
        surveyId: String,
        canonicalFile: File,
    ) {
        check(capturedRecoveryRequest == null) { "Recovery captured more than one Worker request." }
        checkSameLogicalSurvey(checkpoint()?.optString("surveyId"), surveyId)
        check(canonicalFile.canonicalFile == File(context.filesDir, "pending_uploads/$sessionId-a.json").canonicalFile) {
            "Captured artifact differs from checkpoint session."
        }
        capturedRecoveryRequest = CapturedRecoveryRequest(request, logicalWorkName, surveyId, canonicalFile)
    }

    fun recordFinal(result: String) {
        val checkpoint = requireNotNull(checkpoint())
        checkpoint.put("phase2Pid", Process.myPid()).put("final", result).put("remote", remoteState())
        writeAtomically(checkpointFile, checkpoint)
    }

    fun prepare(stagedFile: File, surveyId: String, canonicalPath: String) {
        require(context.packageName == ISOLATED_APPLICATION_ID) { "Wrong isolated application ID." }
        require(SurveyUploadIdempotency.validateLocalPayload(surveyId, stagedFile.readText(Charsets.UTF_8)) != null) {
            "Invalid synthetic survey payload."
        }
        require(canonicalPath == SurveyUploadWork.canonicalRemotePath(surveyId)) {
            "Unexpected canonical path."
        }
        writeAtomically(
            checkpointFile,
            JSONObject()
                .put("version", CHECKPOINT_VERSION)
                .put("session", sessionId)
                .put("scenario", scenario.name)
                .put("applicationId", context.packageName)
                .put("surveyId", surveyId)
                .put("canonicalPath", canonicalPath)
                .put("payloadFingerprint", SurveyUploadIdempotency.payloadFingerprint(stagedFile.readBytes()))
                .put("phase1Pid", Process.myPid())
                .put("processName", currentProcessName())
                .put("terminationMode", "SELF_PROCESS_KILL")
                .put("checkpointEpochMs", System.currentTimeMillis())
                .put("ready", false)
                .put("boundary", "PREPARED")
        )
    }

    override suspend fun uploadCanonical(
        config: GitHubUploader.GitHubConfig,
        canonicalPath: String,
        surveyId: String,
        content: String,
        message: String,
        onProgress: (Int) -> Unit,
    ): GitHubUploader.UploadResult {
        val identity = requireNotNull(SurveyUploadIdempotency.validateLocalPayload(surveyId, content))
        return GitHubUploader.reconcileSurveyJsonUpload(identity, content, object : GitHubUploader.SurveyContentsTransport {
            override suspend fun getExisting(): GitHubUploader.SurveyRemoteRecord? {
                val current = remoteState()
                increment("lookupCount")
                return current?.takeIf { it.optString("path") == canonicalPath }?.let {
                    GitHubUploader.SurveyRemoteRecord(it.getString("content"), "fake://$sessionId/$canonicalPath")
                }
            }

            override suspend fun createOnly(): GitHubUploader.SurveyCreateResult {
                val current = remoteState()
                if (current != null) {
                    increment("conflictCount")
                    return GitHubUploader.SurveyCreateResult.Reconcile(409)
                }
                writeAtomically(stateFile, JSONObject().put("version", 1).put("path", canonicalPath).put("content", content).put("createCount", 1).put("lookupCount", 0).put("conflictCount", 0))
                return GitHubUploader.SurveyCreateResult.Created(GitHubUploader.UploadResult("fake://$sessionId/$canonicalPath", "fake"))
            }
        })
    }

    override suspend fun awaitBoundary(boundary: Issue86ProcessDeathHarness.Boundary) {
        if (!shouldInjectTerminationAtBoundary(boundary, expectedBoundary, terminateAtBoundary)) return
        check(context.packageName == ISOLATED_APPLICATION_ID) { "Wrong isolated application ID." }
        check(currentProcessName() == context.applicationInfo.processName) { "Not executing in the isolated target process." }
        val remote = requireNotNull(remoteState()) { "Missing durable fake remote state." }
        val checkpoint = requireNotNull(checkpoint())
            .also { validatePreparedCheckpoint(it, boundary, remote) }
            .put("boundary", boundary.name)
            .put("readyPid", Process.myPid())
            .put("remote", remote)
            .put("remoteDigest", SurveyUploadIdempotency.payloadFingerprint(remote.toString().toByteArray()))
            .put("ready", true)
            .put("terminationIntent", "RECORDED")
        writeAtomically(checkpointFile, checkpoint)
        Log.i(TAG, "READY_FOR_SELF_TERMINATION session=$sessionId boundary=${boundary.name} pid=${Process.myPid()}")
        // The seam is intentionally injected only by this androidTest-only fake. If it returns,
        // fail closed before the Worker can commit the ledger or clean staged artifacts.
        terminateSelf()
        error("Issue #86 self-termination callback unexpectedly returned.")
    }

    private fun remoteState(): JSONObject? = state(stateFile)

    private fun validatePreparedCheckpoint(
        checkpoint: JSONObject,
        boundary: Issue86ProcessDeathHarness.Boundary,
        remote: JSONObject,
    ) {
        check(checkpoint.optInt("version") == CHECKPOINT_VERSION) { "Unexpected checkpoint schema." }
        check(checkpoint.optString("session") == sessionId) { "Checkpoint session mismatch." }
        check(checkpoint.optString("scenario") == scenario.name) { "Checkpoint scenario mismatch." }
        check(checkpoint.optString("applicationId") == ISOLATED_APPLICATION_ID) { "Checkpoint application mismatch." }
        check(checkpoint.optString("boundary") == "PREPARED") { "Checkpoint was already consumed." }
        check(checkpoint.optString("payloadFingerprint").matches(Regex("[0-9a-f]{64}"))) { "Invalid payload fingerprint." }
        check(remote.optString("path") == checkpoint.optString("canonicalPath")) { "Fake remote path mismatch." }
        check(remote.optString("content").isNotBlank()) { "Fake remote content is missing." }
        check(boundary == expectedBoundary) { "Checkpoint boundary mismatch." }
    }

    fun requireExpectedExit(checkpoint: JSONObject) {
        verifyExitEvidence(context, checkpoint)
    }

    private companion object {
        const val TAG = "Issue86DeathHarness"
        const val CHECKPOINT_VERSION = 2
        const val ISOLATED_APPLICATION_ID = "com.negi.survey.issue86.it"
        const val SIGNAL_KILL = 9

        fun verifyHistoricalSelfKill(context: Context, checkpoint: JSONObject) {
        val phase1Pid = checkpoint.getInt("phase1Pid")
        check(phase1Pid == checkpoint.getInt("readyPid")) { "Checkpoint PIDs differ." }
        check(checkpoint.getString("processName") == ISOLATED_APPLICATION_ID) { "Checkpoint process mismatch." }
        val cutoff = checkpoint.getLong("checkpointEpochMs")
        val manager = context.getSystemService(ActivityManager::class.java)
        val matching = manager.getHistoricalProcessExitReasons(null, 0, 32).filter {
            it.pid == phase1Pid && it.processName == ISOLATED_APPLICATION_ID && it.timestamp >= cutoff
        }
        check(matching.size == 1) { "Expected exactly one historical exit record for Phase 1." }
        val exit = matching.single()
        check(exit.reason == ApplicationExitInfo.REASON_SIGNALED && exit.status == SIGNAL_KILL) {
            "Phase 1 did not end with the expected self-kill signal."
        }
        }
    }

    private fun currentProcessName(): String =
        File("/proc/self/cmdline").readText(Charsets.UTF_8).trimEnd('\u0000').also {
            check(it.isNotBlank()) { "Could not determine current process name." }
        }

    private fun increment(key: String) {
        val current = remoteState() ?: return
        current.put(key, current.optInt(key) + 1)
        writeAtomically(stateFile, current)
    }

    private fun state(file: File): JSONObject? = runCatching {
        if (file.isFile) JSONObject(file.readText(Charsets.UTF_8)) else null
    }.getOrNull()

    private fun writeAtomically(file: File, value: JSONObject) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(value.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(temporary.renameTo(file)) { "Could not persist Issue #86 fake state." }
    }

}

/**
 * Applies strict target matching for Phase 1 fault injection and accepts all known Worker
 * boundaries during Phase 2 observation. The exhaustive enum branch requires review if a new
 * boundary is added.
 */
internal fun shouldInjectTerminationAtBoundary(
    boundary: Issue86ProcessDeathHarness.Boundary,
    expectedBoundary: Issue86ProcessDeathHarness.Boundary,
    terminateAtBoundary: Boolean,
): Boolean {
    if (!terminateAtBoundary) {
        return when (boundary) {
            Issue86ProcessDeathHarness.Boundary.REMOTE_CONFIRMED_BEFORE_LEDGER,
            Issue86ProcessDeathHarness.Boundary.LEDGER_COMMITTED_BEFORE_CLEANUP -> false
        }
    }
    check(boundary == expectedBoundary) { "Unexpected Issue #86 process-death boundary." }
    return true
}

internal fun checkSameLogicalSurvey(expected: String?, actual: String?) {
    val expectedId = expected?.let(SurveyUploadWork::normalizeLogicalSurveyId)
    val actualId = actual?.let(SurveyUploadWork::normalizeLogicalSurveyId)
    check(expectedId != null && actualId != null && expectedId == actualId) {
        "Survey identity differs from checkpoint."
    }
}
