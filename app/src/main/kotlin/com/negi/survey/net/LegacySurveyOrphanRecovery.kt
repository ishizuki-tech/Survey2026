package com.negi.survey.net

import android.content.Context

/** Explicit maintenance operation for a deferred legacy survey; it never changes its destination. */
internal object LegacySurveyOrphanRecovery {
    sealed interface Outcome {
        data object RESOLVED_UPLOADED : Outcome
        data object ACTIVE_WORK_EXISTS : Outcome
        data object MANUAL_REVIEW_REQUIRED : Outcome
        data object MISSING_OR_INVALID_ARTIFACT : Outcome
        data class DEFERRED(val reason: String) : Outcome
    }

    fun inspect(
        context: Context,
        config: GitHubUploader.GitHubConfig,
        surveyId: String
    ): Outcome {
        val appContext = context.applicationContext ?: context
        val normalized = SurveyUploadWork.normalizeLogicalSurveyId(surveyId)
            ?: return Outcome.MISSING_OR_INVALID_ARTIFACT
        val candidate = PendingSurveyUploads.discoverPendingSurveys(appContext).candidates
            .firstOrNull { it.normalizedSurveyId == normalized }
            ?: return Outcome.MISSING_OR_INVALID_ARTIFACT
        val result = SurveyUploadWork.reconcile(
            context = appContext,
            config = config,
            candidate = candidate,
            protocol = SurveyUploadWork.SurveyUploadProtocol.LEGACY_DATED_V0,
            allowNewEnqueue = false
        )
        return when (result.decision.action) {
            SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED,
            SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED_TRACKER_CLEANUP_FAILED -> {
                LegacySurveyRecoveryStore(appContext).clear(normalized)
                Outcome.RESOLVED_UPLOADED
            }
            SurveyUploadWork.SurveyWorkAction.KEEP_TRACKED,
            SurveyUploadWork.SurveyWorkAction.KEEP_LEGACY,
            SurveyUploadWork.SurveyWorkAction.ADOPT_LEGACY,
            SurveyUploadWork.SurveyWorkAction.DEFER_MIXED_ACTIVE_WORK -> Outcome.ACTIVE_WORK_EXISTS
            SurveyUploadWork.SurveyWorkAction.DEFER_LEGACY_ORPHAN -> Outcome.MANUAL_REVIEW_REQUIRED
            SurveyUploadWork.SurveyWorkAction.INVALID_ARTIFACT -> Outcome.MISSING_OR_INVALID_ARTIFACT
            else -> Outcome.DEFERRED(result.decision.reason)
        }
    }
}
