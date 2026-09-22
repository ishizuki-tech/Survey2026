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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Privacy-safe upload status displayed on the configuration-selection screen. */
data class UploadStatus(
    val deviceTag: String = "",
    val uploadedCount: Int = 0,
    val pendingCount: Int = 0
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
    }

    companion object {
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
