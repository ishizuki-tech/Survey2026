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
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.utils.buildSurveyFileName
import com.negi.survey.vm.SurveyFinalizationSnapshot
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
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
    fun reconcileSurveyJson(
        config: GitHubUploader.GitHubConfig,
        file: File,
        surveyId: String
    ): SurveyUploadWork.SurveyWorkReconcileResult
    fun deletePendingSurveyFile(file: File): Boolean
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
            try {
                if (operations.isUploaded(surveyId)) {
                    return@withLock SurveyFinalizationResult.AlreadyUploaded
                }
                val existing = operations.findPendingSurveyFile(surveyId)
                val pending = existing ?: operations.stageSurveyJson(snapshot, deviceTag, exportedAtStamp)
                val reconciliation = operations.reconcileSurveyJson(config, pending, surveyId)
                when (reconciliation.decision.action) {
                    SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED,
                    SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED_TRACKER_CLEANUP_FAILED -> {
                        try {
                            operations.deletePendingSurveyFile(pending)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                        }
                        return@withLock SurveyFinalizationResult.AlreadyUploaded
                    }

                    SurveyUploadWork.SurveyWorkAction.ENQUEUE_NEW,
                    SurveyUploadWork.SurveyWorkAction.RECOVER_NEW,
                    SurveyUploadWork.SurveyWorkAction.ENQUEUED_TRACKER_UNCONFIRMED -> {
                        if (!reconciliation.enqueued) {
                            return@withLock SurveyFinalizationResult.Failure(reconciliation.decision.reason)
                        }
                    }

                    SurveyUploadWork.SurveyWorkAction.KEEP_TRACKED,
                    SurveyUploadWork.SurveyWorkAction.KEEP_LEGACY,
                    SurveyUploadWork.SurveyWorkAction.ADOPT_LEGACY -> Unit

                    else -> return@withLock SurveyFinalizationResult.Failure(reconciliation.decision.reason)
                }
                try {
                    operations.scheduleVoiceArtifacts(config, surveyId, expectedVoiceFileNames(snapshot))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                }
                try {
                    operations.scheduleLogArtifact(config, surveyId, exportedAtStamp)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                }
                SurveyFinalizationResult.Queued(pending, reused = existing != null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                SurveyFinalizationResult.Failure(exception.message ?: "Could not queue survey upload.")
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

    override fun reconcileSurveyJson(
        config: GitHubUploader.GitHubConfig,
        file: File,
        surveyId: String
    ): SurveyUploadWork.SurveyWorkReconcileResult = SurveyUploadWork.reconcile(
        context = appContext,
        config = config,
        expectedSurveyId = surveyId,
        canonicalFile = file
    )

    override fun deletePendingSurveyFile(file: File): Boolean = file.delete()

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
