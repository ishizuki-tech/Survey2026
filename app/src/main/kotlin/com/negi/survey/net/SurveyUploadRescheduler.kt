/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyUploadRescheduler.kt
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */
package com.negi.survey.net

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException

/** Reconciles the canonical artifact for every grouped pending survey. */
internal object SurveyUploadRescheduler {
    enum class RecoveryClassification {
        SUBMITTED,
        ACTIVE,
        SUBMITTED_TRACKER_UNCONFIRMED,
        ALREADY_UPLOADED,
        DEFERRED,
        INVALID,
        OPERATIONAL_FAILURE
    }

    data class CandidateRecovery(
        val normalizedSurveyId: String,
        val canonicalFile: File,
        val classification: RecoveryClassification,
        val reconciliation: SurveyUploadWork.SurveyWorkReconcileResult? = null,
        val operationalFailureMessage: String? = null
    )

    data class RecoverySummary(
        val discoveredSurveyCount: Int,
        val reconciledCandidateCount: Int,
        val duplicateFileCount: Int,
        val unclassifiedFileCount: Int,
        val operationalFailureCount: Int,
        val classificationCounts: Map<RecoveryClassification, Int>,
        val candidates: List<CandidateRecovery>
    )

    /** Narrow seam keeps grouped-recovery tests independent from Android WorkManager. */
    internal interface Operations {
        fun discover(): PendingSurveyUploads.PendingSurveyDiscovery
        fun reconcile(candidate: PendingSurveyUploads.PendingSurveyCandidate): SurveyUploadWork.SurveyWorkReconcileResult
    }

    fun recoverPendingSurveyUploads(
        context: Context,
        config: GitHubUploader.GitHubConfig
    ): RecoverySummary = recover(AndroidOperations(context, config))

    internal fun recoverForTesting(operations: Operations): RecoverySummary = recover(operations)

    private fun recover(operations: Operations): RecoverySummary {
        val discovery = operations.discover()
        val candidates = discovery.candidates.map { candidate ->
            try {
                val reconciliation = operations.reconcile(candidate)
                CandidateRecovery(
                    normalizedSurveyId = candidate.normalizedSurveyId,
                    canonicalFile = candidate.canonicalFile,
                    classification = classify(reconciliation.decision.action),
                    reconciliation = reconciliation
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                CandidateRecovery(
                    normalizedSurveyId = candidate.normalizedSurveyId,
                    canonicalFile = candidate.canonicalFile,
                    classification = RecoveryClassification.OPERATIONAL_FAILURE,
                    operationalFailureMessage = exception.message
                )
            }
        }
        return RecoverySummary(
            discoveredSurveyCount = discovery.candidates.size,
            reconciledCandidateCount = candidates.size,
            duplicateFileCount = discovery.candidates.sumOf { it.duplicateFiles.size },
            unclassifiedFileCount = discovery.unclassifiedFiles.size,
            operationalFailureCount = candidates.count {
                it.classification == RecoveryClassification.OPERATIONAL_FAILURE
            },
            classificationCounts = candidates.groupingBy { it.classification }.eachCount(),
            candidates = candidates
        )
    }

    private fun classify(action: SurveyUploadWork.SurveyWorkAction): RecoveryClassification = when (action) {
        SurveyUploadWork.SurveyWorkAction.ENQUEUE_NEW,
        SurveyUploadWork.SurveyWorkAction.RECOVER_NEW -> RecoveryClassification.SUBMITTED

        SurveyUploadWork.SurveyWorkAction.KEEP_TRACKED,
        SurveyUploadWork.SurveyWorkAction.KEEP_LEGACY,
        SurveyUploadWork.SurveyWorkAction.ADOPT_LEGACY -> RecoveryClassification.ACTIVE

        SurveyUploadWork.SurveyWorkAction.ENQUEUED_TRACKER_UNCONFIRMED ->
            RecoveryClassification.SUBMITTED_TRACKER_UNCONFIRMED

        SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED,
        SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED_TRACKER_CLEANUP_FAILED ->
            RecoveryClassification.ALREADY_UPLOADED

        SurveyUploadWork.SurveyWorkAction.INVALID_ARTIFACT -> RecoveryClassification.INVALID

        else -> RecoveryClassification.DEFERRED
    }

    private class AndroidOperations(
        context: Context,
        private val config: GitHubUploader.GitHubConfig
    ) : Operations {
        private val appContext = context.applicationContext ?: context

        override fun discover(): PendingSurveyUploads.PendingSurveyDiscovery =
            PendingSurveyUploads.discoverPendingSurveys(appContext)

        override fun reconcile(
            candidate: PendingSurveyUploads.PendingSurveyCandidate
        ): SurveyUploadWork.SurveyWorkReconcileResult =
            SurveyUploadWork.reconcile(appContext, config, candidate)
    }
}
