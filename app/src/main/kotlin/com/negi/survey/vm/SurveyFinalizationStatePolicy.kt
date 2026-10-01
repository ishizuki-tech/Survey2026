/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyFinalizationStatePolicy.kt
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */
package com.negi.survey.vm

import com.negi.survey.net.SurveyFinalizationResult

/** Pure state decisions for the session-owned Review Finish flow. */
internal object SurveyFinalizationStatePolicy {
    fun mayStart(state: SurveyFinalizationState): Boolean =
        state !is SurveyFinalizationState.Finishing &&
            state !is SurveyFinalizationState.Queued &&
            state !is SurveyFinalizationState.PendingUpload

    fun shouldAdvanceToDone(state: SurveyFinalizationState): Boolean =
        state is SurveyFinalizationState.Queued || state is SurveyFinalizationState.PendingUpload

    fun complete(result: SurveyFinalizationResult): SurveyFinalizationState =
        when (result) {
            is SurveyFinalizationResult.Queued,
            SurveyFinalizationResult.AlreadyUploaded -> SurveyFinalizationState.Queued
            is SurveyFinalizationResult.StagedPending -> SurveyFinalizationState.PendingUpload
            is SurveyFinalizationResult.Failure -> SurveyFinalizationState.Error(result.message)
        }
}
