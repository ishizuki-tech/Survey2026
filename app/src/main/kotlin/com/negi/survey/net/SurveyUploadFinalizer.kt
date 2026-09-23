/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyUploadFinalizer.kt
 *  © 2026 IshizukiTech LLC. All rights reserved.
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
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.utils.buildSurveyFileName
import com.negi.survey.vm.SurveyFinalizationSnapshot
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SurveyFinalizationResult {
    data class Queued(val file: File, val reused: Boolean) : SurveyFinalizationResult
    data object AlreadyUploaded : SurveyFinalizationResult
    data class Failure(val message: String) : SurveyFinalizationResult
}

/** Narrow boundary around Android storage and WorkManager for finalization tests. */
internal interface SurveyFinalizationOperations {
    fun isUploaded(surveyId: String): Boolean
    fun findPendingSurveyFile(surveyId: String): File?
    fun stageSurveyJson(snapshot: SurveyFinalizationSnapshot, tag: DeviceUploadTag, stamp: String): File
    fun enqueueSurveyJson(config: GitHubUploader.GitHubConfig, file: File, surveyId: String)
    suspend fun scheduleVoiceArtifacts(
        config: GitHubUploader.GitHubConfig,
        surveyId: String,
        expectedVoiceFileNames: Set<String>
    )
    suspend fun scheduleLogArtifact(
        config: GitHubUploader.GitHubConfig,
        surveyId: String,
        exportedAtStamp: String
    )
}

/** Stages and queues one logical survey JSON upload per survey UUID. */
class SurveyUploadFinalizer private constructor(
    private val operations: SurveyFinalizationOperations
) {
    constructor(context: Context) : this(AndroidSurveyFinalizationOperations(context))

    internal constructor(operations: SurveyFinalizationOperations, testOnly: Boolean) : this(operations)

    suspend fun finalize(
        snapshot: SurveyFinalizationSnapshot,
        config: GitHubUploader.GitHubConfig,
        deviceTag: DeviceUploadTag,
        exportedAtStamp: String
    ): SurveyFinalizationResult {
        val surveyId = snapshot.surveyId.trim()
        if (surveyId.isBlank()) return SurveyFinalizationResult.Failure("Survey ID is missing.")
        return locks.getOrPut(surveyId) { Mutex() }.withLock {
            if (operations.isUploaded(surveyId)) {
                return@withLock SurveyFinalizationResult.AlreadyUploaded
            }
            try {
                val existing = operations.findPendingSurveyFile(surveyId)
                val pending = existing ?: operations.stageSurveyJson(snapshot, deviceTag, exportedAtStamp)
                operations.enqueueSurveyJson(config, pending, surveyId)
                runCatching {
                    operations.scheduleVoiceArtifacts(config, surveyId, expectedVoiceFileNames(snapshot))
                }
                runCatching {
                    operations.scheduleLogArtifact(config, surveyId, exportedAtStamp)
                }
                SurveyFinalizationResult.Queued(pending, reused = existing != null)
            } catch (t: Throwable) {
                SurveyFinalizationResult.Failure(t.message ?: "Could not queue survey upload.")
            }
        }
    }

    private fun expectedVoiceFileNames(snapshot: SurveyFinalizationSnapshot): Set<String> =
        snapshot.audioRefs
            .map { it.fileName.substringAfterLast('/') }
            .filter { it.isNotBlank() }
            .toSet()

    private companion object {
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}

private class AndroidSurveyFinalizationOperations(context: Context) : SurveyFinalizationOperations {
    private val appContext = context.applicationContext
    private val artifactScheduler = SurveyArtifactUploadScheduler(appContext)

    override fun isUploaded(surveyId: String): Boolean =
        UploadedSurveyStore(appContext).isUploaded(surveyId)

    override fun findPendingSurveyFile(surveyId: String): File? =
        PendingSurveyUploads.findPendingSurveyFile(appContext, surveyId)

    override fun stageSurveyJson(
        snapshot: SurveyFinalizationSnapshot,
        tag: DeviceUploadTag,
        stamp: String
    ): File {
        val fileName = buildSurveyFileName(snapshot.surveyId, tag, stamp = stamp)
        val directory = File(appContext.filesDir, PENDING_DIR).apply {
            check(exists() || mkdirs()) { "Could not create pending upload directory." }
        }
        val target = File(directory, fileName)
        check(!target.exists()) { "A non-survey pending file already uses the final survey filename." }
        target.writeText(SurveyExportJsonBuilder.build(snapshot, stamp), Charsets.UTF_8)
        return target
    }

    override fun enqueueSurveyJson(config: GitHubUploader.GitHubConfig, file: File, surveyId: String) {
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
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            SurveyUploadWork.uniqueWorkName(remotePath), ExistingWorkPolicy.REPLACE, request
        )
    }

    override suspend fun scheduleVoiceArtifacts(
        config: GitHubUploader.GitHubConfig,
        surveyId: String,
        expectedVoiceFileNames: Set<String>
    ) {
        artifactScheduler.scheduleVoice(config, surveyId, expectedVoiceFileNames)
    }

    override suspend fun scheduleLogArtifact(
        config: GitHubUploader.GitHubConfig,
        surveyId: String,
        exportedAtStamp: String
    ) {
        artifactScheduler.scheduleLog(config, surveyId, exportedAtStamp)
    }

    private companion object {
        const val PENDING_DIR = "pending_uploads"
    }
}
