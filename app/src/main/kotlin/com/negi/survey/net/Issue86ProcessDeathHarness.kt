package com.negi.survey.net

import com.negi.survey.BuildConfig
import androidx.work.OneTimeWorkRequest
import java.io.File

/**
 * Non-persistent bridge used exclusively by the package-isolated Issue #86 process-death tests.
 *
 * This object deliberately has no activation path outside the dedicated APK: a runtime argument,
 * preference, or WorkManager input value cannot enable it in production or ordinary local builds.
 * A session named in worker input must have an in-memory registration in the current process; a
 * missing registration fails closed instead of falling back to HTTP.
 */
internal object Issue86ProcessDeathHarness {
    const val KEY_SESSION_ID = "issue86_process_death_session"
    private const val ISOLATED_APPLICATION_ID = "com.negi.survey.issue86.it"

    enum class Boundary {
        REMOTE_CONFIRMED_BEFORE_LEDGER,
        LEDGER_COMMITTED_BEFORE_CLEANUP,
    }

    interface Registration {
        val sessionId: String

        /** The one crash boundary this in-memory registration is permitted to reach. */
        val expectedBoundary: Boundary

        suspend fun uploadCanonical(
            config: GitHubUploader.GitHubConfig,
            canonicalPath: String,
            surveyId: String,
            content: String,
            message: String,
            onProgress: (Int) -> Unit,
        ): GitHubUploader.UploadResult

        /** Persists test evidence, then terminates only the registered isolated process. */
        suspend fun awaitBoundary(boundary: Boundary)

        /** Captures the exact production recovery request instead of submitting it to WorkManager. */
        fun captureRecoveryRequest(
            request: OneTimeWorkRequest,
            logicalWorkName: String,
            surveyId: String,
            canonicalFile: File,
        )
    }

    @Volatile private var registration: Registration? = null

    fun register(candidate: Registration) {
        check(isCompileTimeIsolatedBuild()) { "Issue #86 process-death harness is unavailable in this build." }
        require(candidate.sessionId.isNotBlank()) { "Issue #86 process-death session is blank." }
        check(registration == null) { "Issue #86 process-death harness is already registered." }
        registration = candidate
    }

    fun unregister(sessionId: String) {
        if (registration?.sessionId == sessionId) registration = null
    }

    fun isCompileTimeIsolatedBuild(): Boolean =
        BuildConfig.ISSUE86_PROCESS_DEATH_HARNESS &&
            BuildConfig.APPLICATION_ID == ISOLATED_APPLICATION_ID &&
            BuildConfig.GH_UPLOAD_MODE == "local"

    /** Returns null for normal Worker requests; throws rather than allowing a configured test to use HTTP. */
    fun registrationForWorker(sessionId: String?): Registration? {
        val requested = sessionId?.trim().orEmpty()
        if (requested.isEmpty()) return null
        check(isCompileTimeIsolatedBuild()) { "Issue #86 process-death session was supplied outside the isolated build." }
        return requireNotNull(registration?.takeIf { it.sessionId == requested }) {
            "Issue #86 process-death session is not registered in this process."
        }
    }

    /** Adds the session marker only while an explicitly registered isolated test is building work. */
    fun activeSessionForWorkRequest(): String? =
        registration?.takeIf { isCompileTimeIsolatedBuild() }?.sessionId

    /** Only a currently registered isolated session may bypass the diagnostic test skip. */
    fun permitsWorkerThroughInstrumentationGate(sessionId: String?): Boolean =
        registrationForWorker(sessionId) != null

    /** Phase 2 runs the one controlled Worker directly after real discovery/reconciliation. */
    fun suppressesRecoveryEnqueue(): Boolean =
        registration != null && isCompileTimeIsolatedBuild()

    fun captureRecoveryRequest(
        request: OneTimeWorkRequest,
        logicalWorkName: String,
        surveyId: String,
        canonicalFile: File,
    ) {
        check(isCompileTimeIsolatedBuild()) { "Issue #86 recovery capture is unavailable in this build." }
        requireNotNull(registration) { "Issue #86 recovery capture requires a registration." }
            .captureRecoveryRequest(request, logicalWorkName, surveyId, canonicalFile)
    }
}
