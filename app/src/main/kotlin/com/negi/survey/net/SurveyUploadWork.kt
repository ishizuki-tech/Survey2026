/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyUploadWork.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Shared identity and WorkManager metadata for staged survey JSON uploads.
 * =====================================================================
 */

package com.negi.survey.net

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Keeps initial survey upload and recovery work on the same logical chain. */
internal object SurveyUploadWork {
    private const val REMOTE_EXPORT_DIR = "exports"
    private const val LOGICAL_WORK_PREFIX = "gh_survey_"
    private const val STATE_LOOKUP_TIMEOUT_MS = 350L
    private const val ENQUEUE_CONFIRMATION_TIMEOUT_MS = 350L

    /** Minimal WorkManager seam used by reconciliation and JVM tests. */
    internal interface WorkManagerOperations {
        fun getWorkInfoById(id: UUID): WorkInfo?
        fun getWorkInfosForUniqueWork(uniqueWorkName: String): List<WorkInfo>
        fun enqueueUniqueWork(
            uniqueWorkName: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest
        ): EnqueueOperation
    }

    internal interface EnqueueOperation {
        fun awaitCompletion(timeoutMs: Long): EnqueueCompletion
    }

    internal enum class EnqueueCompletion {
        SUCCEEDED,
        FAILED,
        TIMED_OUT
    }

    /** Narrow tracker seam for operational-failure tests. */
    internal interface WorkTrackerOperations {
        fun get(surveyId: String): SurveyUploadWorkTracker.TrackedSurveyWork?
        fun prepare(
            surveyId: String,
            requestId: UUID,
            identityKind: SurveyUploadWorkTracker.IdentityKind
        ): SurveyUploadWorkTracker.TrackedSurveyWork?
        fun markEnqueuedIfMatches(surveyId: String, requestId: UUID): Boolean
        fun clearForUploaded(surveyId: String): Boolean
    }

    /** Legacy migration inspection; terminal history deliberately has no ordering semantics. */
    data class LegacyWorkInspection(
        val activeWorkIds: List<UUID> = emptyList(),
        val hasAnyHistory: Boolean = false,
        val inspectionFailed: Boolean = false
    )

    enum class SurveyWorkAction {
        KEEP_TRACKED,
        KEEP_LEGACY,
        SKIP_UPLOADED,
        DEFER_SUCCEEDED_WITHOUT_LEDGER,
        DEFER_ENQUEUED_MISSING,
        DEFER_LEGACY_HISTORY,
        DEFER_UNKNOWN_STATE,
        DEFER_TRACKER_WRITE,
        DEFER_TRACKER_FAILURE,
        DEFER_LEDGER_FAILURE,
        DEFER_ARTIFACT_FAILURE,
        DEFER_BUILD_FAILURE,
        DEFER_ENQUEUE_FAILURE,
        DEFER_ENQUEUE_TIMEOUT,
        ENQUEUED_TRACKER_UNCONFIRMED,
        SKIP_UPLOADED_TRACKER_CLEANUP_FAILED,
        INVALID_ARTIFACT,
        ENQUEUE_NEW,
        RECOVER_NEW,
        ADOPT_LEGACY
    }

    data class SurveyWorkDecision(
        val action: SurveyWorkAction,
        val reason: String
    )

    sealed interface SurveyArtifactValidation {
        data class Valid(val trimmedSurveyId: String) : SurveyArtifactValidation
        data class Invalid(val reason: String) : SurveyArtifactValidation
    }

    data class SurveyWorkReconcileResult(
        val normalizedSurveyId: String?,
        val logicalWorkName: String?,
        val legacyWorkName: String?,
        val decision: SurveyWorkDecision,
        val enqueued: Boolean
    )

    fun remoteRelativePath(fileName: String): String =
        "$REMOTE_EXPORT_DIR/${fileName.trim().trimStart('/')}"

    fun safeWorkNameSegment(value: String): String =
        value.trim()
            .replace(Regex("""[^\w\-.]+"""), "_")
            .take(120)
            .ifBlank { "upload" }

    fun uniqueWorkName(remoteRelativePath: String): String =
        "gh_upload_${safeWorkNameSegment(remoteRelativePath)}"

    /** Adds the explicit marker required for successful survey upload recording. */
    fun addSurveyJsonMetadata(data: Data.Builder, surveyId: String) {
        val normalizedSurveyId = surveyId.trim().takeIf { it.isNotBlank() } ?: return
        data.putString(
            GitHubUploadWorker.KEY_UPLOAD_KIND,
            GitHubUploadWorker.UPLOAD_KIND_SURVEY_JSON
        )
        data.putString(GitHubUploadWorker.KEY_SURVEY_ID, normalizedSurveyId)
    }

    /** Returns the grouped-discovery and ledger-compatible logical survey ID. */
    fun normalizeLogicalSurveyId(surveyId: String): String? =
        surveyId.trim()
            .lowercase(Locale.US)
            .takeIf { it.isNotBlank() }

    /**
     * Collision-resistant unique-work identity for one normalized logical survey ID.
     *
     * Unlike [safeWorkNameSegment], this keeps arbitrary historical IDs distinct even when their
     * printable representations would sanitize or truncate to the same segment.
     */
    fun logicalWorkName(surveyId: String): String? =
        normalizeLogicalSurveyId(surveyId)?.let { normalizedSurveyId ->
            LOGICAL_WORK_PREFIX + sha256Hex(normalizedSurveyId)
        }

    /** Existing file/path-derived identity retained for migration lookup. */
    fun legacyWorkName(canonicalFile: File): String =
        uniqueWorkName(remoteRelativePath(canonicalFile.name))

    /**
     * Revalidates only the caller-selected canonical artifact; it never rescans or changes files.
     */
    fun validateCanonicalArtifact(
        canonicalFile: File,
        expectedNormalizedSurveyId: String
    ): SurveyArtifactValidation {
        val expected = normalizeLogicalSurveyId(expectedNormalizedSurveyId)
            ?: return SurveyArtifactValidation.Invalid("Expected survey ID is blank.")
        if (!canonicalFile.exists() || !canonicalFile.isFile) {
            return SurveyArtifactValidation.Invalid("Canonical pending file is missing.")
        }
        if (canonicalFile.length() <= 0L) {
            return SurveyArtifactValidation.Invalid("Canonical pending file is empty.")
        }
        val parsedSurveyId = PendingSurveyUploads.surveyIdFromFile(canonicalFile)
            ?: return SurveyArtifactValidation.Invalid("Canonical pending file is not valid survey JSON.")
        if (normalizeLogicalSurveyId(parsedSurveyId) != expected) {
            return SurveyArtifactValidation.Invalid("Canonical pending file survey ID changed.")
        }
        return SurveyArtifactValidation.Valid(parsedSurveyId)
    }

    /** Builds the survey JSON request while preserving current finalizer worker metadata. */
    fun buildSurveyJsonRequest(
        config: GitHubUploader.GitHubConfig,
        canonicalFile: File,
        surveyId: String
    ): OneTimeWorkRequest {
        val remotePath = remoteRelativePath(canonicalFile.name)
        val data = Data.Builder()
            .putString(GitHubUploadWorker.KEY_MODE, "file")
            .putString(GitHubUploadWorker.KEY_OWNER, config.owner)
            .putString(GitHubUploadWorker.KEY_REPO, config.repo.substringAfterLast('/'))
            .putString(GitHubUploadWorker.KEY_TOKEN, config.token)
            .putString(GitHubUploadWorker.KEY_BRANCH, config.branch)
            .putString(GitHubUploadWorker.KEY_PATH_PREFIX, config.pathPrefix)
            .putString(GitHubUploadWorker.KEY_FILE_PATH, canonicalFile.absolutePath)
            .putString(GitHubUploadWorker.KEY_FILE_NAME, remotePath)
            .putLong(GitHubUploadWorker.KEY_FILE_MAX_BYTES_HINT, config.maxRawBytesHint.toLong())
            .putInt(GitHubUploadWorker.KEY_FILE_MAX_REQUEST_BYTES_HINT, config.maxRequestBytesHint)
        addSurveyJsonMetadata(data, surveyId)
        return OneTimeWorkRequestBuilder<GitHubUploadWorker>()
            .setInputData(data.build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(GitHubUploadWorker.TAG)
            .addTag(GitHubUploadWorker.TAG + ":file:" + safeWorkNameSegment(remotePath))
            .build()
    }

    /** Candidate overload deliberately builds from [PendingSurveyUploads.PendingSurveyCandidate.canonicalFile] only. */
    fun buildSurveyJsonRequest(
        config: GitHubUploader.GitHubConfig,
        candidate: PendingSurveyUploads.PendingSurveyCandidate
    ): OneTimeWorkRequest {
        val parsedSurveyId = requireNotNull(PendingSurveyUploads.surveyIdFromFile(candidate.canonicalFile)) {
            "Canonical pending file is not valid survey JSON."
        }
        require(normalizeLogicalSurveyId(parsedSurveyId) == normalizeLogicalSurveyId(candidate.normalizedSurveyId)) {
            "Canonical pending file survey ID changed."
        }
        return buildSurveyJsonRequest(config, candidate.canonicalFile, parsedSurveyId)
    }

    /** Pure tracked-state policy used by concrete reconciliation and JVM tests. */
    fun decideReconciliation(
        trackedWork: SurveyUploadWorkTracker.TrackedSurveyWork?,
        trackedState: WorkInfo.State?,
        trackedLookupFailed: Boolean,
        ledgerContainsSurveyId: Boolean,
        artifactIsValid: Boolean,
        legacyInspection: LegacyWorkInspection = LegacyWorkInspection()
    ): SurveyWorkDecision {
        if (ledgerContainsSurveyId) {
            return SurveyWorkDecision(SurveyWorkAction.SKIP_UPLOADED, "Survey is already recorded uploaded.")
        }
        if (trackedLookupFailed || legacyInspection.inspectionFailed) {
            return SurveyWorkDecision(
                SurveyWorkAction.DEFER_UNKNOWN_STATE,
                "Current work state could not be inspected."
            )
        }
        if (trackedWork != null) {
            if (trackedState == null) {
                return if (trackedWork.phase == SurveyUploadWorkTracker.Phase.PREPARED) {
                    if (artifactIsValid) {
                        SurveyWorkDecision(SurveyWorkAction.RECOVER_NEW, "Prepared work was not found.")
                    } else {
                        SurveyWorkDecision(SurveyWorkAction.INVALID_ARTIFACT, "Canonical pending artifact is invalid.")
                    }
                } else {
                    SurveyWorkDecision(
                        SurveyWorkAction.DEFER_ENQUEUED_MISSING,
                        "Enqueued work was not found; it may have completed outside this snapshot."
                    )
                }
            }
            if (isActive(trackedState)) {
                return SurveyWorkDecision(SurveyWorkAction.KEEP_TRACKED, "Tracked work is active.")
            }
            if (trackedState == WorkInfo.State.SUCCEEDED) {
                return SurveyWorkDecision(
                    SurveyWorkAction.DEFER_SUCCEEDED_WITHOUT_LEDGER,
                    "Successful tracked work is missing its uploaded-survey ledger record."
                )
            }
            if (isTerminalRetryable(trackedState)) {
                return if (artifactIsValid) {
                    SurveyWorkDecision(SurveyWorkAction.RECOVER_NEW, "Tracked work is terminal.")
                } else {
                    SurveyWorkDecision(SurveyWorkAction.INVALID_ARTIFACT, "Canonical pending artifact is invalid.")
                }
            }
        }

        return when {
            legacyInspection.activeWorkIds.size == 1 ->
                SurveyWorkDecision(SurveyWorkAction.ADOPT_LEGACY, "One legacy work record is active.")
            legacyInspection.activeWorkIds.size > 1 ->
                SurveyWorkDecision(SurveyWorkAction.DEFER_LEGACY_HISTORY, "Multiple legacy work records are active.")
            legacyInspection.hasAnyHistory ->
                SurveyWorkDecision(
                    SurveyWorkAction.DEFER_LEGACY_HISTORY,
                    "Legacy work history has no supported terminal ordering."
                )
            !artifactIsValid ->
                SurveyWorkDecision(SurveyWorkAction.INVALID_ARTIFACT, "Canonical pending artifact is invalid.")
            else -> SurveyWorkDecision(SurveyWorkAction.ENQUEUE_NEW, "No current work record.")
        }
    }

    /**
     * Inspects both work identities and schedules only the canonical artifact when policy allows.
     * Callers should invoke this from a background dispatcher because state lookup is bounded but
     * blocking.
     */
    fun reconcile(
        context: Context,
        config: GitHubUploader.GitHubConfig,
        candidate: PendingSurveyUploads.PendingSurveyCandidate
    ): SurveyWorkReconcileResult = reconcile(
        context = context,
        config = config,
        expectedSurveyId = candidate.normalizedSurveyId,
        canonicalFile = candidate.canonicalFile
    )

    /** Finalizer-oriented overload for one already-selected staged survey artifact. */
    fun reconcile(
        context: Context,
        config: GitHubUploader.GitHubConfig,
        expectedSurveyId: String,
        canonicalFile: File
    ): SurveyWorkReconcileResult {
        val normalizedSurveyId = normalizeLogicalSurveyId(expectedSurveyId)
        val logicalWorkName = normalizedSurveyId?.let(::logicalWorkName)
        val legacyWorkName = legacyWorkName(canonicalFile)
        if (normalizedSurveyId == null || logicalWorkName == null) {
            return SurveyWorkReconcileResult(
                normalizedSurveyId = null,
                logicalWorkName = null,
                legacyWorkName = legacyWorkName,
                decision = SurveyWorkDecision(SurveyWorkAction.INVALID_ARTIFACT, "Expected survey ID is blank."),
                enqueued = false
            )
        }

        val appContext = context.applicationContext ?: context
        val workManager = try {
            WorkManager.getInstance(appContext)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return resultFor(
                normalizedSurveyId,
                logicalWorkName,
                legacyWorkName,
                SurveyWorkDecision(SurveyWorkAction.DEFER_UNKNOWN_STATE, "WorkManager is unavailable.")
            )
        } catch (_: Exception) {
            return resultFor(
                normalizedSurveyId,
                logicalWorkName,
                legacyWorkName,
                SurveyWorkDecision(SurveyWorkAction.DEFER_UNKNOWN_STATE, "WorkManager is unavailable.")
            )
        }
        val tracker = TrackerOperations(SurveyUploadWorkTracker(appContext))
        return reconcileInternal(
            config = config,
            normalizedSurveyId = normalizedSurveyId,
            logicalWorkName = logicalWorkName,
            legacyWorkName = legacyWorkName,
            canonicalFile = canonicalFile,
            tracker = tracker,
            ledgerContainsSurveyId = { UploadedSurveyStore(appContext).isUploaded(normalizedSurveyId) },
            artifactValidator = ::validateCanonicalArtifact,
            requestBuilder = ::buildSurveyJsonRequest,
            operations = AndroidWorkManagerOperations(workManager)
        )
    }

    /** Internal seam keeps tracker lifecycle tests independent of a real WorkManager database. */
    internal fun reconcileForTesting(
        config: GitHubUploader.GitHubConfig,
        expectedSurveyId: String,
        canonicalFile: File,
        tracker: WorkTrackerOperations,
        ledgerContainsSurveyId: () -> Boolean,
        artifactValidator: (File, String) -> SurveyArtifactValidation = ::validateCanonicalArtifact,
        requestBuilder: (GitHubUploader.GitHubConfig, File, String) -> OneTimeWorkRequest = ::buildSurveyJsonRequest,
        operations: WorkManagerOperations
    ): SurveyWorkReconcileResult {
        val normalizedSurveyId = normalizeLogicalSurveyId(expectedSurveyId)
        val legacyWorkName = legacyWorkName(canonicalFile)
        val logicalWorkName = normalizedSurveyId?.let(::logicalWorkName)
        if (normalizedSurveyId == null || logicalWorkName == null) {
            return SurveyWorkReconcileResult(
                null, null, legacyWorkName,
                SurveyWorkDecision(SurveyWorkAction.INVALID_ARTIFACT, "Expected survey ID is blank."), false
            )
        }
        return reconcileInternal(
            config,
            normalizedSurveyId,
            logicalWorkName,
            legacyWorkName,
            canonicalFile,
            tracker,
            ledgerContainsSurveyId,
            artifactValidator,
            requestBuilder,
            operations
        )
    }

    private fun reconcileInternal(
        config: GitHubUploader.GitHubConfig,
        normalizedSurveyId: String,
        logicalWorkName: String,
        legacyWorkName: String,
        canonicalFile: File,
        tracker: WorkTrackerOperations,
        ledgerContainsSurveyId: () -> Boolean,
        artifactValidator: (File, String) -> SurveyArtifactValidation,
        requestBuilder: (GitHubUploader.GitHubConfig, File, String) -> OneTimeWorkRequest,
        operations: WorkManagerOperations
    ): SurveyWorkReconcileResult = locks.computeIfAbsent(normalizedSurveyId) { ReentrantLock() }.withLock {
        when (val ledger = readLedger(ledgerContainsSurveyId)) {
            is LedgerRead.Failure -> return@withLock resultFor(
                normalizedSurveyId, logicalWorkName, legacyWorkName,
                SurveyWorkDecision(SurveyWorkAction.DEFER_LEDGER_FAILURE, "Uploaded-survey ledger could not be read.")
            )
            LedgerRead.Uploaded -> return@withLock uploadedResult(
                normalizedSurveyId, logicalWorkName, legacyWorkName, tracker
            )
            LedgerRead.NotUploaded -> Unit
        }

        val trackedWork = try {
            tracker.get(normalizedSurveyId)
        } catch (_: Exception) {
            return@withLock resultFor(
                normalizedSurveyId, logicalWorkName, legacyWorkName,
                SurveyWorkDecision(SurveyWorkAction.DEFER_TRACKER_FAILURE, "Current work tracker could not be read.")
            )
        }
        val trackedInspection = trackedWork?.let { tracked ->
            inspectTrackedWork(operations, tracked.workRequestId)
        }
        val legacyInspection = if (trackedWork == null && trackedInspection == null) {
            inspectLegacy(operations, legacyWorkName)
        } else {
            LegacyWorkInspection()
        }
        val validation = try {
            artifactValidator(canonicalFile, normalizedSurveyId)
        } catch (_: Exception) {
            return@withLock resultFor(
                normalizedSurveyId, logicalWorkName, legacyWorkName,
                SurveyWorkDecision(SurveyWorkAction.DEFER_ARTIFACT_FAILURE, "Canonical pending artifact could not be validated.")
            )
        }
        val decision = decideReconciliation(
            trackedWork = trackedWork,
            trackedState = trackedInspection?.workInfo?.state,
            trackedLookupFailed = trackedInspection?.inspectionFailed == true,
            ledgerContainsSurveyId = false,
            artifactIsValid = validation is SurveyArtifactValidation.Valid,
            legacyInspection = legacyInspection
        )

        if (decision.action == SurveyWorkAction.ADOPT_LEGACY) {
            val legacyId = legacyInspection.activeWorkIds.single()
            val adopted = try {
                tracker.prepare(normalizedSurveyId, legacyId, SurveyUploadWorkTracker.IdentityKind.LEGACY) != null &&
                    tracker.markEnqueuedIfMatches(normalizedSurveyId, legacyId)
            } catch (_: Exception) {
                false
            }
            return@withLock resultFor(
                normalizedSurveyId,
                logicalWorkName,
                legacyWorkName,
                if (adopted) decision else SurveyWorkDecision(SurveyWorkAction.DEFER_TRACKER_WRITE, "Legacy work could not be tracked.")
            )
        }
        if (decision.action != SurveyWorkAction.ENQUEUE_NEW && decision.action != SurveyWorkAction.RECOVER_NEW) {
            return@withLock resultFor(normalizedSurveyId, logicalWorkName, legacyWorkName, decision)
        }

        // A second ledger guard closes the artifact-validation/build window before submission.
        when (val ledger = readLedger(ledgerContainsSurveyId)) {
            is LedgerRead.Failure -> return@withLock resultFor(
                normalizedSurveyId, logicalWorkName, legacyWorkName,
                SurveyWorkDecision(SurveyWorkAction.DEFER_LEDGER_FAILURE, "Uploaded-survey ledger could not be read.")
            )
            LedgerRead.Uploaded -> return@withLock uploadedResult(
                normalizedSurveyId, logicalWorkName, legacyWorkName, tracker
            )
            LedgerRead.NotUploaded -> Unit
        }
        val valid = validation as SurveyArtifactValidation.Valid
        val request = try {
            requestBuilder(config, canonicalFile, valid.trimmedSurveyId)
        } catch (_: Exception) {
            return@withLock resultFor(
                normalizedSurveyId, logicalWorkName, legacyWorkName,
                SurveyWorkDecision(SurveyWorkAction.DEFER_BUILD_FAILURE, "Upload work request could not be built.")
            )
        }
        val mayReplace = trackedWork != null &&
            trackedInspection?.workInfo?.state?.let(::isTerminalRetryable) == true
        val policy = if (mayReplace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        val prepared = try {
            tracker.prepare(normalizedSurveyId, request.id, SurveyUploadWorkTracker.IdentityKind.LOGICAL)
        } catch (_: Exception) {
            null
        }
        if (prepared == null) {
            return@withLock resultFor(
                normalizedSurveyId, logicalWorkName, legacyWorkName,
                SurveyWorkDecision(SurveyWorkAction.DEFER_TRACKER_FAILURE, "Work request could not be tracked before submission.")
            )
        }
        val enqueueOperation = try {
            operations.enqueueUniqueWork(logicalWorkName, policy, request)
        } catch (_: Exception) {
            return@withLock resultFor(
                normalizedSurveyId, logicalWorkName, legacyWorkName,
                SurveyWorkDecision(SurveyWorkAction.DEFER_ENQUEUE_FAILURE, "Work submission failed; the prepared record was retained.")
            )
        }
        when (try {
            enqueueOperation.awaitCompletion(ENQUEUE_CONFIRMATION_TIMEOUT_MS)
        } catch (_: Exception) {
            EnqueueCompletion.FAILED
        }) {
            EnqueueCompletion.FAILED -> return@withLock resultFor(
                normalizedSurveyId, logicalWorkName, legacyWorkName,
                SurveyWorkDecision(SurveyWorkAction.DEFER_ENQUEUE_FAILURE, "Work submission failed; the prepared record was retained.")
            )
            EnqueueCompletion.TIMED_OUT -> return@withLock resultFor(
                normalizedSurveyId, logicalWorkName, legacyWorkName,
                SurveyWorkDecision(SurveyWorkAction.DEFER_ENQUEUE_TIMEOUT, "Work submission confirmation timed out; the prepared record was retained.")
            )
            EnqueueCompletion.SUCCEEDED -> Unit
        }
        val markedEnqueued = try {
            tracker.markEnqueuedIfMatches(normalizedSurveyId, request.id)
        } catch (_: Exception) {
            false
        }
        resultFor(
            normalizedSurveyId,
            logicalWorkName,
            legacyWorkName,
            if (markedEnqueued) decision else SurveyWorkDecision(
                SurveyWorkAction.ENQUEUED_TRACKER_UNCONFIRMED,
                "Work was submitted but its tracker transition was not confirmed."
            ),
            enqueued = true
        )
    }

    private data class TrackedWorkInspection(
        val workInfo: WorkInfo?,
        val inspectionFailed: Boolean = false
    )

    private fun inspectTrackedWork(
        operations: WorkManagerOperations,
        workRequestId: UUID
    ): TrackedWorkInspection = try {
        TrackedWorkInspection(operations.getWorkInfoById(workRequestId))
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        TrackedWorkInspection(workInfo = null, inspectionFailed = true)
    } catch (_: Exception) {
        TrackedWorkInspection(workInfo = null, inspectionFailed = true)
    }

    private fun inspectLegacy(operations: WorkManagerOperations, legacyWorkName: String): LegacyWorkInspection =
        try {
            val history = operations.getWorkInfosForUniqueWork(legacyWorkName)
            LegacyWorkInspection(
                activeWorkIds = history.filter { isActive(it.state) }.map { it.id },
                hasAnyHistory = history.isNotEmpty()
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            LegacyWorkInspection(inspectionFailed = true)
        } catch (_: Exception) {
            LegacyWorkInspection(inspectionFailed = true)
        }

    private fun resultFor(
        normalizedSurveyId: String,
        logicalWorkName: String,
        legacyWorkName: String,
        decision: SurveyWorkDecision,
        enqueued: Boolean = false
    ) = SurveyWorkReconcileResult(normalizedSurveyId, logicalWorkName, legacyWorkName, decision, enqueued)

    private sealed interface LedgerRead {
        data object Uploaded : LedgerRead
        data object NotUploaded : LedgerRead
        data object Failure : LedgerRead
    }

    private fun readLedger(ledgerContainsSurveyId: () -> Boolean): LedgerRead = try {
        if (ledgerContainsSurveyId()) LedgerRead.Uploaded else LedgerRead.NotUploaded
    } catch (_: Exception) {
        LedgerRead.Failure
    }

    private fun uploadedResult(
        normalizedSurveyId: String,
        logicalWorkName: String,
        legacyWorkName: String,
        tracker: WorkTrackerOperations
    ): SurveyWorkReconcileResult {
        val cleared = try {
            tracker.clearForUploaded(normalizedSurveyId)
        } catch (_: Exception) {
            false
        }
        return resultFor(
            normalizedSurveyId,
            logicalWorkName,
            legacyWorkName,
            SurveyWorkDecision(
                if (cleared) SurveyWorkAction.SKIP_UPLOADED else SurveyWorkAction.SKIP_UPLOADED_TRACKER_CLEANUP_FAILED,
                if (cleared) "Survey is already recorded uploaded." else "Survey is uploaded but tracker cleanup failed."
            )
        )
    }

    private class AndroidWorkManagerOperations(private val workManager: WorkManager) : WorkManagerOperations {
        override fun getWorkInfoById(id: UUID): WorkInfo? =
            workManager.getWorkInfoById(id).get(STATE_LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        override fun getWorkInfosForUniqueWork(uniqueWorkName: String): List<WorkInfo> =
            workManager.getWorkInfosForUniqueWork(uniqueWorkName).get(STATE_LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        override fun enqueueUniqueWork(
            uniqueWorkName: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest
        ): EnqueueOperation = WorkManagerEnqueueOperation(
            workManager.enqueueUniqueWork(uniqueWorkName, policy, request)
        )
    }

    private class WorkManagerEnqueueOperation(private val operation: Operation) : EnqueueOperation {
        override fun awaitCompletion(timeoutMs: Long): EnqueueCompletion = try {
            operation.result.get(timeoutMs, TimeUnit.MILLISECONDS)
            EnqueueCompletion.SUCCEEDED
        } catch (_: TimeoutException) {
            EnqueueCompletion.TIMED_OUT
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            EnqueueCompletion.FAILED
        } catch (_: Exception) {
            EnqueueCompletion.FAILED
        }
    }

    private class TrackerOperations(private val tracker: SurveyUploadWorkTracker) : WorkTrackerOperations {
        override fun get(surveyId: String) = tracker.get(surveyId)
        override fun prepare(
            surveyId: String,
            requestId: UUID,
            identityKind: SurveyUploadWorkTracker.IdentityKind
        ) = tracker.prepare(surveyId, requestId, identityKind)
        override fun markEnqueuedIfMatches(surveyId: String, requestId: UUID) =
            tracker.markEnqueuedIfMatches(surveyId, requestId)
        override fun clearForUploaded(surveyId: String) = tracker.clearForUploaded(surveyId)
    }

    private fun isActive(state: WorkInfo.State): Boolean =
        state == WorkInfo.State.ENQUEUED ||
            state == WorkInfo.State.RUNNING ||
            state == WorkInfo.State.BLOCKED

    private fun isTerminalRetryable(state: WorkInfo.State): Boolean =
        state == WorkInfo.State.FAILED || state == WorkInfo.State.CANCELLED

    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
}
