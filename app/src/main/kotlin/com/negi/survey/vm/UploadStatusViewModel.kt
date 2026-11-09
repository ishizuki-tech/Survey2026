/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: UploadStatusViewModel.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.negi.survey.net.CompletedSurveyVisibilityStore
import com.negi.survey.net.GitHubUploadWorker
import com.negi.survey.net.PendingSurveyUploads
import com.negi.survey.net.V2ArtifactStore
import com.negi.survey.net.V2ArtifactDelivery
import androidx.work.WorkInfo
import java.util.concurrent.TimeUnit
import com.negi.survey.utils.DeviceUploadTagProvider
import com.negi.survey.utils.formatSurveyExportStamp
import com.negi.survey.utils.formatSurveyTimestamp
import com.negi.survey.utils.surveyIdTimestampMillis
import com.negi.survey.utils.uuidFirstSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Privacy-safe upload status displayed on the configuration-selection screen. */
data class UploadStatus(
    val deviceTag: String = "",
    val uploadedCount: Int = 0,
    val pendingCount: Int = 0
)

/**
 * One entry in the upload status details list.
 *
 * [idPrefix] is only the first hyphen-delimited segment of the survey's
 * opaque ID (e.g. "01a1406f"), shown so entries can be told apart — never
 * the full ID, respondent data, or any other survey content, keeping this
 * detail view close to as privacy-safe as the summary counts above it.
 *
 * [exportedAt] is the survey's own recorded conclusion time, already
 * formatted for display; it is null when that was never captured (surveys
 * uploaded before this was tracked). [timestampMillis] is a fallback run-start
 * time decoded from the survey ID itself; it is null for a legacy, plain-random
 * ID with no embedded timestamp. [displayTime] prefers the real export time
 * and falls back to a "~"-prefixed decoded start time, then "Unknown time".
 */
data class UploadStatusEntry(
    val idPrefix: String,
    val exportedAt: String?,
    val timestampMillis: Long?
) {
    val displayTime: String
        get() = exportedAt
            ?: timestampMillis?.let { "~" + formatSurveyTimestamp(it) }
            ?: "Unknown time"
}

/** Non-sensitive, read-only legacy-recovery data suitable for the existing local status dialog. */
data class DeferredLegacyRecoveryEntry(
    val surveyId: String,
    val fileName: String,
    val reason: String,
    val localStatus: LegacyRecoveryLocalStatus,
    val operatorReviewRequired: Boolean,
    /** Null when no execution pin exists or it could not be read. */
    val executionAuthority: String? = null,
)

enum class LegacyRecoveryLocalStatus {
    ALREADY_UPLOADED,
    ACTIVE_WORK,
    MANUAL_REVIEW_REQUIRED,
    MISSING_ARTIFACT,
    INVALID_ARTIFACT,
    UNKNOWN_LOCAL_STATE,
}

/** Pending and completed entries shown when the upload status area is tapped, newest first. */
data class UploadStatusDetails(
    val pending: List<UploadStatusEntry>,
    val completed: List<UploadStatusEntry>,
    /** Read-only local snapshots; this list is intentionally independent of Clear List. */
    val deferredLegacyRecovery: List<DeferredLegacyRecoveryEntry> = emptyList(),
    /** Opaque IDs for the exact completed snapshot; passed back to Clear List, never rendered. */
    val completedSurveyIds: Set<String> = emptySet(),
    /** Counts by artifact category and local delivery state. */
    val artifactStatus: List<String> = emptyList(),
)

/** Activity-scoped owner of upload-status reads and refresh triggers. */
class UploadStatusViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext = app.applicationContext
    private val artifacts = V2ArtifactStore(appContext)
    private val uploadedSurveyStore = V2ArtifactDelivery.ledger(appContext)
    private val completedVisibilityStore = CompletedSurveyVisibilityStore(appContext)
    private val deviceTag = DeviceUploadTagProvider.from(appContext).value
    private val refreshMutex = Mutex()
    private val _status = MutableStateFlow(UploadStatus(deviceTag = deviceTag))
    val status: StateFlow<UploadStatus> = _status.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            WorkManager.getInstance(appContext)
                .getWorkInfosByTagLiveData(GitHubUploadWorker.TAG)
                .asFlow()
                .collectLatest { refresh() }
        }
    }

    /** Re-reads durable state; WorkManager only triggers this read. */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshNow()
        }
    }

    private suspend fun refreshNow() {
        refreshMutex.withLock {
            val uploadedCount = uploadedSurveyStore.uploadedCount()
            val pendingCount = pendingSurveys().size
            _status.value = UploadStatus(
                deviceTag = deviceTag,
                uploadedCount = uploadedCount,
                pendingCount = pendingCount
            )
        }
    }

    /** Persists display-only hiding for the dialog snapshot; canonical delivery evidence is read-only. */
    suspend fun clearCompletedSurveys(displayedIds: Set<String>) = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            completedVisibilityStore.hideCompleted(displayedIds, uploadedSurveyStore.uploadedSurveyIds())
        }
        refreshNow()
    }

    /** Loads the pending/completed detail lists for the upload status dialog. */
    suspend fun loadDetails(): UploadStatusDetails = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val pending = pendingSurveys().map { entry ->
                val surveyId = checkNotNull(entry.route.surveyId)
                UploadStatusEntry(
                    idPrefix = uuidFirstSegment(surveyId),
                    exportedAt = PendingSurveyUploads.exportedAtFromFile(entry.payload)?.let(::formatSurveyExportStamp),
                    timestampMillis = surveyIdTimestampMillis(surveyId)
                )
            }.sortedWith(newestFirstComparator)

            val visibleCompleted = completedVisibilityStore.visibleRecords(uploadedSurveyStore.uploadedSurveyRecords())
            val completed = visibleCompleted
                .map { record ->
                    UploadStatusEntry(
                        idPrefix = uuidFirstSegment(record.surveyId),
                        exportedAt = record.exportedAt?.let(::formatSurveyExportStamp),
                        timestampMillis = surveyIdTimestampMillis(record.surveyId)
                    )
                }
                .sortedWith(newestFirstComparator)

            UploadStatusDetails(
                pending = pending,
                completed = completed,
                completedSurveyIds = visibleCompleted.mapTo(mutableSetOf()) { it.surveyId },
                artifactStatus = artifactStatus(),
            )
        }
    }

    private fun pendingSurveys() = artifacts.artifactIds()
        .filter { it.startsWith("survey-") }
        .mapNotNull(artifacts::read)
        .filterNot { V2ArtifactDelivery.confirmed(appContext, it) }

    private fun artifactStatus(): List<String> {
        val config = V2ArtifactDelivery.config(appContext)
        val work = runCatching {
            WorkManager.getInstance(appContext).getWorkInfosByTag(GitHubUploadWorker.TAG)
                .get(350, TimeUnit.MILLISECONDS)
        }.getOrDefault(emptyList()).flatMap { info ->
            info.tags.filter { it.startsWith("v2-artifact:") }.map { it.substringAfter(':') to info }
        }.groupBy({ it.first }, { it.second })
        val counts = artifacts.artifactIds().map { id ->
            val entry = artifacts.read(id)
            if (entry == null) return@map "Unknown artifact · invalid route"
            val label = entry.route.kind.name.lowercase().replace('_', ' ')
            val states = work[id].orEmpty()
            val conflict = runCatching { entry.hasRemoteConflict() }.getOrNull()
            val confirmed = runCatching { V2ArtifactDelivery.confirmed(appContext, entry) }.getOrNull()
            val state = when {
                confirmed == null -> "invalid delivery evidence"
                confirmed && entry.payload.exists() -> "delivered, cleanup pending"
                confirmed -> "delivered"
                !entry.payload.isFile -> "missing staged payload"
                conflict == null -> "invalid conflict evidence"
                conflict -> "remote conflict, payload retained"
                !runCatching { entry.matchesPayload() }.getOrDefault(false) -> "payload does not match route"
                entry.route.profile != V2ArtifactStore.profile -> "held for original destination"
                entry.route.size > V2ArtifactDelivery.MAX_RAW_BYTES -> "over upload size limit"
                !runCatching { V2ArtifactDelivery.isEligible(appContext, entry) }.getOrDefault(false) ->
                    "held without finalized survey reference"
                config == null -> "upload disabled or credential unavailable"
                states.any { it.state == WorkInfo.State.RUNNING } -> "uploading"
                states.any { !it.state.isFinished && it.runAttemptCount > 0 } -> "retry pending"
                states.any { !it.state.isFinished } -> "queued"
                states.any { it.state == WorkInfo.State.FAILED } -> "failed, payload retained"
                else -> "staged"
            }
            "$label · $state"
        }.groupingBy { it }.eachCount()
        val interrupted = artifacts.interruptedIds().size
        return counts.toSortedMap().map { (state, count) -> "$state ($count)" } +
            listOfNotNull("Interrupted staging ($interrupted)".takeIf { interrupted > 0 })
    }

    companion object {
        /** Entries with no recoverable timestamp (legacy IDs) sort last. */
        private val newestFirstComparator: Comparator<UploadStatusEntry> =
            compareByDescending { entry -> entry.timestampMillis ?: Long.MIN_VALUE }

        fun factory(app: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(UploadStatusViewModel::class.java)) {
                        "Unsupported ViewModel: ${modelClass.name}"
                    }
                    return UploadStatusViewModel(app) as T
                }
            }
    }
}
