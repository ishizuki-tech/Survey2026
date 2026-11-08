/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyFinalizationViewModel.kt
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */
package com.negi.survey.vm

import android.app.Application
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.negi.survey.net.GitHubUploader
import com.negi.survey.net.SurveyFinalizationResult
import com.negi.survey.net.SurveyUploadFinalizer
import com.negi.survey.utils.DeviceUploadTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SurveyFinalizationState {
    data object Idle : SurveyFinalizationState
    data object Finishing : SurveyFinalizationState
    data object Queued : SurveyFinalizationState
    data object PendingUpload : SurveyFinalizationState
    data class Error(val message: String, val staged: Boolean = false) : SurveyFinalizationState
}

/** Session-owned state holder for survey finalization. */
class SurveyFinalizationViewModel(app: Application) : AndroidViewModel(app) {
    private val finalizer = SurveyUploadFinalizer(app.applicationContext)
    private val _state = MutableStateFlow<SurveyFinalizationState>(SurveyFinalizationState.Idle)
    val state: StateFlow<SurveyFinalizationState> = _state.asStateFlow()

    // Once staging succeeds, retries must keep the snapshot and timestamp of the immutable export.
    private var stagedExit: Pair<SurveyFinalizationSnapshot, String>? = null

    @MainThread
    fun finish(snapshot: SurveyFinalizationSnapshot, config: GitHubUploader.GitHubConfig?, tag: DeviceUploadTag, stamp: String) {
        if (!SurveyFinalizationStatePolicy.mayStart(_state.value)) return
        _state.value = SurveyFinalizationState.Finishing
        val (export, exportedAt) = stagedExit ?: (snapshot to stamp)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { finalizer.finalize(export, config, tag, exportedAt) }
            if (export.status == SurveyCompletionStatus.INCOMPLETE &&
                result is SurveyFinalizationResult.Failure && result.staged
            ) {
                stagedExit = export to exportedAt
            }
            _state.value = SurveyFinalizationStatePolicy.complete(result)
        }
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SurveyFinalizationViewModel(app) as T
        }
    }
}
