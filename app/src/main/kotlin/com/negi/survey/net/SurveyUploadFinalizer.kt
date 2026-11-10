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
import com.negi.survey.utils.DeviceUploadTagProvider
import com.negi.survey.diagnostics.SurveyTraceRegistry
import com.negi.survey.diagnostics.WhisperTraceRegistry
import com.negi.survey.vm.SurveyFinalizationSnapshot
import com.negi.survey.vm.SurveyCompletionStatus
import java.time.Instant
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SurveyFinalizationResult {
    data class Queued(val file: File, val reused: Boolean) : SurveyFinalizationResult
    data class StagedPending(val file: File, val reused: Boolean) : SurveyFinalizationResult
    data object AlreadyUploaded : SurveyFinalizationResult
    data class Failure(val message: String, val staged: Boolean = false) : SurveyFinalizationResult
}

/** Narrow boundary around Android storage and WorkManager for finalization tests. */
internal interface SurveyFinalizationOperations {
    fun acceptsSurveyId(surveyId: String): Boolean = surveyId.isNotBlank()
    suspend fun collectFinalizationDiagnostics(surveyId: String) = Unit
    fun isUploaded(surveyId: String): Boolean
    fun findPendingSurveyFile(surveyId: String): File?
    fun reserveCanonicalSurveyRoute(surveyId: String): Boolean
    fun hasCanonicalSurveyRoute(surveyId: String): Boolean
    fun stageSurveyJson(snapshot: SurveyFinalizationSnapshot, tag: DeviceUploadTag, stamp: String): File
    fun reconcileSurveyJson(
        config: GitHubUploader.GitHubConfig,
        file: File,
        surveyId: String
    ): SurveyUploadWork.SurveyWorkReconcileResult
    fun cleanupUploadedSurveyArtifact(
        surveyId: String,
        file: File
    ): SurveyUploadedArtifactCleanup.Outcome
    suspend fun scheduleVoiceArtifacts(config: GitHubUploader.GitHubConfig)
    suspend fun scheduleLogArtifact(
        config: GitHubUploader.GitHubConfig,
        surveyId: String,
        exportedAtStamp: String
    )
    suspend fun scheduleDiagnosticTraceArtifacts(
        config: GitHubUploader.GitHubConfig,
        surveyId: String
    )
}

/** Stages one logical survey JSON upload per survey UUID and queues it when configured. */
class SurveyUploadFinalizer private constructor(
    private val operations: SurveyFinalizationOperations
) {
    constructor(context: Context) : this(AndroidSurveyFinalizationOperations(context))

    internal constructor(operations: SurveyFinalizationOperations, testOnly: Boolean) : this(operations)

    suspend fun finalize(
        snapshot: SurveyFinalizationSnapshot,
        config: GitHubUploader.GitHubConfig?,
        deviceTag: DeviceUploadTag,
        exportedAtStamp: String,
    ): SurveyFinalizationResult = finalize(snapshot, config, deviceTag, exportedAtStamp, abandoned = false)

    private suspend fun finalize(
        snapshot: SurveyFinalizationSnapshot,
        config: GitHubUploader.GitHubConfig?,
        deviceTag: DeviceUploadTag,
        exportedAtStamp: String,
        abandoned: Boolean,
    ): SurveyFinalizationResult {
        val surveyId = snapshot.surveyId.trim()
        if (!operations.acceptsSurveyId(surveyId)) return SurveyFinalizationResult.Failure("Survey ID is invalid.")
        return locks.getOrPut(surveyId) { Mutex() }.withLock {
            var staged = false
            try {
                if (operations.isUploaded(surveyId)) {
                    return@withLock SurveyFinalizationResult.AlreadyUploaded
                }
                val existing = operations.findPendingSurveyFile(surveyId)
                staged = existing != null
                if (existing != null && !operations.hasCanonicalSurveyRoute(surveyId)) {
                    // An upgraded process must not relabel a historical staged artifact.
                    return@withLock SurveyFinalizationResult.StagedPending(existing, reused = true)
                }
                val pending = existing ?: run {
                    if (!SurveyRecordingGate.beginFinalization(surveyId)) {
                        return@withLock SurveyFinalizationResult.Failure("Wait for the recording to finish saving, then retry.")
                    }
                    try {
                        if (!operations.reserveCanonicalSurveyRoute(surveyId)) {
                            return@withLock SurveyFinalizationResult.Failure("Could not reserve survey upload route.")
                        }
                        operations.stageSurveyJson(snapshot, deviceTag, exportedAtStamp)
                    } finally {
                        SurveyRecordingGate.endFinalization(surveyId)
                    }
                }
                staged = true
                if (existing == null && !abandoned) {
                    SurveyTraceRegistry.finalizeAsync(surveyId)
                    WhisperTraceRegistry.finalizeAsync(surveyId)
                    operations.collectFinalizationDiagnostics(surveyId)
                }
                if (config == null) {
                    return@withLock SurveyFinalizationResult.StagedPending(pending, reused = existing != null)
                }
                val reconciliation = operations.reconcileSurveyJson(config, pending, surveyId)
                when (reconciliation.decision.action) {
                    SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED,
                    SurveyUploadWork.SurveyWorkAction.SKIP_UPLOADED_TRACKER_CLEANUP_FAILED -> {
                        when (operations.cleanupUploadedSurveyArtifact(surveyId, pending)) {
                            SurveyUploadedArtifactCleanup.Outcome.CLEANED,
                            SurveyUploadedArtifactCleanup.Outcome.ALREADY_MISSING ->
                                return@withLock SurveyFinalizationResult.AlreadyUploaded

                            SurveyUploadedArtifactCleanup.Outcome.DEFER_UNVERIFIED,
                            SurveyUploadedArtifactCleanup.Outcome.DEFER_ACTIVE_WORK,
                            SurveyUploadedArtifactCleanup.Outcome.RETRY_CLEANUP ->
                                return@withLock SurveyFinalizationResult.StagedPending(pending, reused = true)
                        }
                    }

                    SurveyUploadWork.SurveyWorkAction.ENQUEUE_NEW,
                    SurveyUploadWork.SurveyWorkAction.RECOVER_NEW,
                    SurveyUploadWork.SurveyWorkAction.ENQUEUED_TRACKER_UNCONFIRMED -> {
                        if (!reconciliation.enqueued) {
                            return@withLock SurveyFinalizationResult.Failure(reconciliation.decision.reason, staged = true)
                        }
                    }

                    SurveyUploadWork.SurveyWorkAction.KEEP_TRACKED,
                    SurveyUploadWork.SurveyWorkAction.KEEP_LEGACY,
                    SurveyUploadWork.SurveyWorkAction.ADOPT_LEGACY -> Unit

                    else -> return@withLock SurveyFinalizationResult.Failure(reconciliation.decision.reason, staged = true)
                }
                try {
                    operations.scheduleVoiceArtifacts(config)
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
                try {
                    operations.scheduleDiagnosticTraceArtifacts(config, surveyId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                }
                SurveyFinalizationResult.Queued(pending, reused = existing != null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                SurveyFinalizationResult.Failure(exception.message ?: "Could not queue survey upload.", staged)
            }
        }
    }

    /** Recover only voice packages owned by a prior main process. This path has no live answers. */
    internal suspend fun recoverAbandonedRecordings(context: Context) {
        val store = V2ArtifactStore(context)
        val abandoned = SurveyRecordings(store).abandonedSurveyIds()
        if (abandoned.isEmpty()) return
        val tag = DeviceUploadTagProvider.from(context)
        for (surveyId in abandoned) {
            val snapshot = SurveyFinalizationSnapshot(
                surveyId = surveyId, questions = emptyMap(), answers = emptyMap(),
                followups = emptyMap(), audioRefs = emptyList(), aiOutcomesJson = "{}",
                extraMeta = emptyMap(), status = SurveyCompletionStatus.INCOMPLETE,
            )
            val result = finalize(
                snapshot, null, tag,
                Instant.now().toString(), abandoned = true,
            )
            if (result is SurveyFinalizationResult.Failure) {
                RuntimeLogStore.w("SurveyFinalizer", "Prior-process recordings retained for $surveyId: ${result.message}")
            }
        }
    }

    private companion object {
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}

private class AndroidSurveyFinalizationOperations(context: Context) : SurveyFinalizationOperations {
    private val appContext = context.applicationContext
    private val artifacts = V2ArtifactStore(appContext)

    override fun acceptsSurveyId(surveyId: String): Boolean = runCatching {
        com.negi.survey.utils.V2ArtifactPaths(surveyId, DeviceUploadTag("validation"))
    }.isSuccess

    private fun entry(surveyId: String): V2ArtifactStore.Entry? =
        artifacts.recoverAndRead(V2ArtifactStore.surveyKey(surveyId))

    override fun isUploaded(surveyId: String): Boolean =
        entry(surveyId)?.let { V2ArtifactDelivery.confirmed(appContext, it) } == true

    override fun findPendingSurveyFile(surveyId: String): File? = entry(surveyId)?.let {
        check(it.matchesPayload()) { "The staged survey is missing or does not match its immutable route." }
        it.payload
    }

    override fun reserveCanonicalSurveyRoute(surveyId: String): Boolean =
        !artifacts.hasReservedRoute(V2ArtifactStore.surveyKey(surveyId)) &&
            !V2ArtifactDelivery.ledger(appContext).isUploaded(surveyId)

    override fun hasCanonicalSurveyRoute(surveyId: String): Boolean = entry(surveyId) != null

    override fun stageSurveyJson(
        snapshot: SurveyFinalizationSnapshot,
        tag: DeviceUploadTag,
        stamp: String
    ): File {
        val paths = artifacts.paths(appContext, snapshot.surveyId, tag)
        return artifacts.stage(
            V2ArtifactStore.surveyKey(paths.surveyUuid), V2ArtifactStore.Kind.SURVEY,
            paths.surveyUuid, V2ArtifactStore.profile, paths.surveyJson(),
        ) { output ->
            val recordings = try {
                SurveyRecordings(artifacts).inventory(paths, V2ArtifactStore.profile).also { inventory ->
                    val files = inventory.associateBy { it.file }
                    check(snapshot.audioRefs.all { ref ->
                        files[ref.fileName]?.identity?.prompt?.questionId == ref.questionId
                    })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw IllegalStateException("Recording data could not be verified. The survey has been kept for retry.", failure)
            }
            val json = SurveyExportJsonBuilder.build(snapshot, stamp, recordings)
            output.write(json.toByteArray(Charsets.UTF_8))
        }.payload
    }

    override fun reconcileSurveyJson(
        config: GitHubUploader.GitHubConfig,
        file: File,
        surveyId: String
    ): SurveyUploadWork.SurveyWorkReconcileResult = SurveyUploadWork.reconcile(
        context = appContext,
        config = config,
        expectedSurveyId = surveyId,
        canonicalFile = file,
        v2Entry = checkNotNull(entry(surveyId)),
    )

    override fun cleanupUploadedSurveyArtifact(
        surveyId: String,
        file: File
    ): SurveyUploadedArtifactCleanup.Outcome {
        val entry = entry(surveyId) ?: return SurveyUploadedArtifactCleanup.Outcome.DEFER_UNVERIFIED
        return if (V2ArtifactDelivery.cleanupSurvey(appContext, entry)) {
            SurveyUploadedArtifactCleanup.Outcome.CLEANED
        } else SurveyUploadedArtifactCleanup.Outcome.RETRY_CLEANUP
    }

    override suspend fun collectFinalizationDiagnostics(surveyId: String) {
        try {
            RuntimeLogStore.sealV2SurveySegment()
            SurveyArtifactUploadScheduler(appContext).stageV2Logcat(surveyId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            com.negi.survey.diagnostics.DiagnosticContentPolicy.logWarning("SurveyFinalizer", "Logcat staging failed", failure)
        } finally {
            com.negi.survey.diagnostics.SurveyDiagnosticAttribution.end(appContext, surveyId)
        }
    }

    override suspend fun scheduleVoiceArtifacts(config: GitHubUploader.GitHubConfig) =
        V2ArtifactDelivery.recoverIndependent(appContext, config)

    override suspend fun scheduleLogArtifact(
        config: GitHubUploader.GitHubConfig,
        surveyId: String,
        exportedAtStamp: String
    ) = Unit

    override suspend fun scheduleDiagnosticTraceArtifacts(
        config: GitHubUploader.GitHubConfig,
        surveyId: String
    ) = Unit
}
