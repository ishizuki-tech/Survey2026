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
import com.negi.survey.net.GitHubUploadWorker
import com.negi.survey.net.PendingSurveyUploads
import com.negi.survey.net.UploadedSurveyStore
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

/** Pending and completed entries shown when the upload status area is tapped, newest first. */
data class UploadStatusDetails(
    val pending: List<UploadStatusEntry>,
    val completed: List<UploadStatusEntry>
)

/** Activity-scoped owner of upload-status reads and refresh triggers. */
class UploadStatusViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext = app.applicationContext
    private val uploadedSurveyStore = UploadedSurveyStore(appContext)
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
            val pendingCount = PendingSurveyUploads.pendingSurveyIds(appContext)
                .count { surveyId -> !uploadedSurveyStore.isUploaded(surveyId) }
            _status.value = UploadStatus(
                deviceTag = deviceTag,
                uploadedCount = uploadedCount,
                pendingCount = pendingCount
            )
        }
    }

    /**
     * Clears the completed-uploads ledger (local bookkeeping only; see
     * [UploadedSurveyStore.clear]) and refreshes the summary counts.
     */
    suspend fun clearCompletedSurveys() = withContext(Dispatchers.IO) {
        uploadedSurveyStore.clear()
        refreshNow()
    }

    /** Loads the pending/completed detail lists for the upload status dialog. */
    suspend fun loadDetails(): UploadStatusDetails = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val pending = PendingSurveyUploads.pendingSurveyIds(appContext)
                .filterNot { surveyId -> uploadedSurveyStore.isUploaded(surveyId) }
                .map { surveyId ->
                    val exportedAtRaw = PendingSurveyUploads.findPendingSurveyFile(appContext, surveyId)
                        ?.let(PendingSurveyUploads::exportedAtFromFile)
                    UploadStatusEntry(
                        idPrefix = uuidFirstSegment(surveyId),
                        exportedAt = exportedAtRaw?.let(::formatSurveyExportStamp),
                        timestampMillis = surveyIdTimestampMillis(surveyId)
                    )
                }
                .sortedWith(newestFirstComparator)

            val completed = uploadedSurveyStore.uploadedSurveyRecords()
                .map { record ->
                    UploadStatusEntry(
                        idPrefix = uuidFirstSegment(record.surveyId),
                        exportedAt = record.exportedAt?.let(::formatSurveyExportStamp),
                        timestampMillis = surveyIdTimestampMillis(record.surveyId)
                    )
                }
                .sortedWith(newestFirstComparator)

            UploadStatusDetails(pending = pending, completed = completed)
        }
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
