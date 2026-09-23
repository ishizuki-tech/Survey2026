/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyFinalizationSnapshot.kt
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */
package com.negi.survey.vm

/** Immutable survey data captured before finalization/navigation can reset a run. */
data class SurveyFinalizationSnapshot(
    val surveyId: String,
    val questions: Map<String, String>,
    val answers: Map<String, String>,
    val followups: Map<String, List<SurveyViewModel.FollowupEntry>>,
    val audioRefs: List<SurveyViewModel.AudioRef>,
    val aiOutcomesJson: String,
    val extraMeta: Map<String, String>
)
