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

import androidx.work.Data

/** Keeps initial survey upload and recovery work on the same logical chain. */
internal object SurveyUploadWork {
    private const val REMOTE_EXPORT_DIR = "exports"

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
}
