/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyUploadRescheduler.kt
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Re-enqueues any staged-but-not-yet-uploaded survey JSON (files under
 *  files/pending_uploads not yet recorded in UploadedSurveyStore) as a
 *  GitHubUploadWorker job. Used at app start and from the Done screen's
 *  Exit action, so a queued upload resumes without needing the original
 *  finalize() call. Safe to call repeatedly: ExistingWorkPolicy.KEEP means
 *  an already-enqueued or in-flight upload for the same file is left alone.
 * =====================================================================
 */
package com.negi.survey.net

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.io.File
import java.util.concurrent.TimeUnit

internal object SurveyUploadRescheduler {

    /** Re-enqueues every pending, not-yet-uploaded survey JSON. Returns how many were re-enqueued. */
    fun reenqueuePendingSurveyUploads(context: Context, config: GitHubUploader.GitHubConfig): Int {
        val appContext = context.applicationContext ?: context
        val uploadedSurveyStore = UploadedSurveyStore(appContext)

        val pendingIds = PendingSurveyUploads.pendingSurveyIds(appContext)
            .filterNot { uploadedSurveyStore.isUploaded(it) }

        var reenqueued = 0
        for (surveyId in pendingIds) {
            val file = PendingSurveyUploads.findPendingSurveyFile(appContext, surveyId) ?: continue
            enqueueSurveyJson(appContext, config, file, surveyId)
            reenqueued++
        }
        return reenqueued
    }

    private fun enqueueSurveyJson(
        context: Context,
        config: GitHubUploader.GitHubConfig,
        file: File,
        surveyId: String
    ) {
        val remotePath = SurveyUploadWork.remoteRelativePath(file.name)
        val data = Data.Builder()
            .putString(GitHubUploadWorker.KEY_MODE, "file")
            .putString(GitHubUploadWorker.KEY_OWNER, config.owner)
            .putString(GitHubUploadWorker.KEY_REPO, config.repo.substringAfterLast('/'))
            .putString(GitHubUploadWorker.KEY_TOKEN, config.token)
            .putString(GitHubUploadWorker.KEY_BRANCH, config.branch)
            .putString(GitHubUploadWorker.KEY_PATH_PREFIX, config.pathPrefix)
            .putString(GitHubUploadWorker.KEY_FILE_PATH, file.absolutePath)
            .putString(GitHubUploadWorker.KEY_FILE_NAME, remotePath)
            .putLong(GitHubUploadWorker.KEY_FILE_MAX_BYTES_HINT, config.maxRawBytesHint.toLong())
            .putInt(GitHubUploadWorker.KEY_FILE_MAX_REQUEST_BYTES_HINT, config.maxRequestBytesHint)
        SurveyUploadWork.addSurveyJsonMetadata(data, surveyId)

        val request = OneTimeWorkRequestBuilder<GitHubUploadWorker>()
            .setInputData(data.build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(GitHubUploadWorker.TAG)
            .addTag(GitHubUploadWorker.TAG + ":file:" + SurveyUploadWork.safeWorkNameSegment(remotePath))
            .build()

        // KEEP: leave an already-enqueued/running/blocked-on-network upload alone; only
        // insert a fresh request when none is pending (e.g. after a prior terminal FAILED).
        WorkManager.getInstance(context).enqueueUniqueWork(
            SurveyUploadWork.uniqueWorkName(remotePath), ExistingWorkPolicy.KEEP, request
        )
    }
}
