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

sealed interface SurveyFinalizationState {
    data object Idle : SurveyFinalizationState
    data object Finishing : SurveyFinalizationState
    data object Queued : SurveyFinalizationState
    data class Error(val message: String) : SurveyFinalizationState
}

/** Session-owned state holder for Review Finish finalization. */
class SurveyFinalizationViewModel(app: Application) : AndroidViewModel(app) {
    private val finalizer = SurveyUploadFinalizer(app.applicationContext)
    private val _state = MutableStateFlow<SurveyFinalizationState>(SurveyFinalizationState.Idle)
    val state: StateFlow<SurveyFinalizationState> = _state.asStateFlow()

    fun finish(snapshot: SurveyFinalizationSnapshot, config: GitHubUploader.GitHubConfig?, tag: DeviceUploadTag, stamp: String) {
        if (!SurveyFinalizationStatePolicy.mayStart(_state.value)) return
        if (config == null) {
            _state.value = SurveyFinalizationState.Error("Survey upload is not configured.")
            return
        }
        _state.value = SurveyFinalizationState.Finishing
        viewModelScope.launch(Dispatchers.IO) {
            val result = finalizer.finalize(snapshot, config, tag, stamp)
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
