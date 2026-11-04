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
        fun protocolFor(surveyId: String): SurveyUploadWork.SurveyUploadProtocol?
        fun reconcile(
            candidate: PendingSurveyUploads.PendingSurveyCandidate,
            protocol: SurveyUploadWork.SurveyUploadProtocol?,
            allowNewEnqueue: Boolean
        ): SurveyUploadWork.SurveyWorkReconcileResult
        fun recordLegacyOrphan(candidate: PendingSurveyUploads.PendingSurveyCandidate, reason: String)
        fun clearLegacyOrphan(surveyId: String)
        fun cleanupRecordedArtifact(
            candidate: PendingSurveyUploads.PendingSurveyCandidate,
            protocol: SurveyUploadWork.SurveyUploadProtocol?
        ): SurveyUploadedArtifactCleanup.Outcome = SurveyUploadedArtifactCleanup.Outcome.CLEANED
    }

    fun recoverPendingSurveyUploads(
        context: Context,
        config: GitHubUploader.GitHubConfig
    ): RecoverySummary {
        try {
            SurveyArtifactUploadScheduler(context).recoverExhaustedConflicts(config)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("SurveyUploadRecovery", "Conflict recovery scan failed; artifacts retained", error)
        }
        return recover(AndroidOperations(context, config))
    }

    internal fun recoverForTesting(operations: Operations): RecoverySummary = recover(operations)

    private fun recover(operations: Operations): RecoverySummary {
        val discovery = operations.discover()
        val candidates = discovery.candidates.map { candidate ->
            try {
                val protocol = operations.protocolFor(candidate.normalizedSurveyId)
                val reconciliation = operations.reconcile(
                    candidate = candidate,
                    protocol = protocol,
                    allowNewEnqueue = protocol != null
                )
                if (reconciliation.decision.action == SurveyUploadWork.SurveyWorkAction.DEFER_LEGACY_ORPHAN) {
                    operations.recordLegacyOrphan(candidate, reconciliation.decision.reason)
                    CandidateRecovery(
                        candidate.normalizedSurveyId, candidate.canonicalFile,
                        classify(reconciliation.decision.action), reconciliation
                    )
                } else if (
                    reconciliation.decision.action == SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED ||
                    reconciliation.decision.action == SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED_TRACKER_CLEANUP_FAILED
                ) {
                    when (val cleanup = operations.cleanupRecordedArtifact(candidate, protocol)) {
                        SurveyUploadedArtifactCleanup.Outcome.CLEANED,
                        SurveyUploadedArtifactCleanup.Outcome.ALREADY_MISSING -> {
                            operations.clearLegacyOrphan(candidate.normalizedSurveyId)
                            CandidateRecovery(
                                candidate.normalizedSurveyId, candidate.canonicalFile,
                                classify(reconciliation.decision.action), reconciliation
                            )
                        }
                        else -> CandidateRecovery(
                            candidate.normalizedSurveyId, candidate.canonicalFile,
                            RecoveryClassification.DEFERRED, reconciliation,
                            "Recorded survey artifact retained: $cleanup"
                        )
                    }
                } else {
                    CandidateRecovery(
                        candidate.normalizedSurveyId, candidate.canonicalFile,
                        classify(reconciliation.decision.action), reconciliation
                    )
                }
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

        SurveyUploadWork.SurveyWorkAction.CAPTURED_FOR_ISOLATED_TEST -> RecoveryClassification.DEFERRED

        else -> RecoveryClassification.DEFERRED
    }

    private class AndroidOperations(
        context: Context,
        private val config: GitHubUploader.GitHubConfig
    ) : Operations {
        private val appContext = context.applicationContext ?: context

        override fun discover(): PendingSurveyUploads.PendingSurveyDiscovery =
            PendingSurveyUploads.discoverPendingSurveys(appContext)

        override fun protocolFor(surveyId: String): SurveyUploadWork.SurveyUploadProtocol? =
            SurveyUploadRouteStore(appContext).protocolFor(surveyId)

        override fun reconcile(
            candidate: PendingSurveyUploads.PendingSurveyCandidate,
            protocol: SurveyUploadWork.SurveyUploadProtocol?,
            allowNewEnqueue: Boolean
        ): SurveyUploadWork.SurveyWorkReconcileResult =
            SurveyUploadWork.reconcile(
                context = appContext,
                config = config,
                candidate = candidate,
                protocol = protocol ?: SurveyUploadWork.SurveyUploadProtocol.LEGACY_DATED_V0,
                allowNewEnqueue = allowNewEnqueue && !Issue86ProcessDeathHarness.suppressesRecoveryEnqueue()
            )

        override fun recordLegacyOrphan(
            candidate: PendingSurveyUploads.PendingSurveyCandidate,
            reason: String
        ) {
            check(
                LegacySurveyRecoveryStore(appContext).record(
                    candidate.normalizedSurveyId,
                    candidate.canonicalFile.name,
                    reason
                )
            ) { "Could not persist legacy survey recovery record." }
        }

        override fun clearLegacyOrphan(surveyId: String) {
            LegacySurveyRecoveryStore(appContext).clear(surveyId)
        }

        override fun cleanupRecordedArtifact(
            candidate: PendingSurveyUploads.PendingSurveyCandidate,
            protocol: SurveyUploadWork.SurveyUploadProtocol?
        ): SurveyUploadedArtifactCleanup.Outcome {
            val outcomes = (listOf(candidate.canonicalFile) + candidate.duplicateFiles).map { file ->
                SurveyUploadedArtifactCleanup.cleanup(appContext, candidate.normalizedSurveyId, file)
            }
            val failure = outcomes.firstOrNull {
                it != SurveyUploadedArtifactCleanup.Outcome.CLEANED &&
                    it != SurveyUploadedArtifactCleanup.Outcome.ALREADY_MISSING
            }
            if (failure != null) return failure
            if (protocol == SurveyUploadWork.SurveyUploadProtocol.CANONICAL_IMMUTABLE_V1) {
                if (!SurveyUploadRouteStore(appContext).clearCanonical(candidate.normalizedSurveyId)) {
                    return SurveyUploadedArtifactCleanup.Outcome.RETRY_CLEANUP
                }
            }
            return SurveyUploadedArtifactCleanup.Outcome.CLEANED
        }
    }
}
