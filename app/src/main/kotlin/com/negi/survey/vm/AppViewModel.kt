/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AppViewModel.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.vm

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.negi.survey.utils.HeavyInitializer
import com.negi.survey.utils.PersistentModelStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/* ───────────────────────────── Download State ───────────────────────────── */

sealed class DlState {
    data object Idle : DlState()

    /**
     * No local or persisted copy of the model was found automatically. Before
     * starting the (potentially large) network download, the UI pauses here
     * so the user can point at a copy already saved on this device by another
     * install of the app (e.g. a different build/version that already
     * downloaded the same model), via the system file picker. Calling
     * [AppViewModel.proceedWithDownload] dismisses this and starts the normal
     * download; calling [AppViewModel.useManuallySelectedModel] with a picked
     * file skips the download entirely.
     */
    data object NeedsModel : DlState()

    data class Downloading(
        val downloaded: Long,
        val total: Long?
    ) : DlState()

    data class Done(
        val file: File
    ) : DlState()

    data class Error(
        val message: String
    ) : DlState()
}

/* ───────────────────────────── ViewModel ───────────────────────────── */

class AppViewModel(
    val modelUrl: String = DEFAULT_MODEL_URL,
    private val fileName: String = DEFAULT_FILE_NAME,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val uiThrottleMs: Long = DEFAULT_UI_THROTTLE_MS,
    private val uiMinDeltaBytes: Long = DEFAULT_UI_MIN_DELTA_BYTES
) : ViewModel() {

    private val _state = MutableStateFlow<DlState>(DlState.Idle)

    /** Exposes download state for Compose gating. */
    val state: StateFlow<DlState> = _state.asStateFlow()

    @Volatile
    private var downloadJob: Job? = null

    /**
     * Ensures the target model exists locally, downloading if needed.
     *
     * Behavioral rules:
     * - If already DONE and file exists, return (unless forceFresh=true).
     * - If a plausible cached file exists, emit DONE and return (unless forceFresh=true).
     * - Only one download job can run at a time.
     * - forceFresh cancels the current job and starts a new attempt.
     */
    fun ensureModelDownloaded(
        appContext: Context,
        forceFresh: Boolean = false
    ) {
        val app = appContext.applicationContext

        val currentState = _state.value
        if (!forceFresh && currentState is DlState.Done && currentState.file.exists()) {
            return
        }

        // Single job policy (Job-based, avoids AtomicBoolean races).
        val running = downloadJob
        if (running?.isActive == true) {
            if (!forceFresh) return
            running.cancel(CancellationException("forceFresh requested"))
            HeavyInitializer.cancel()
        }

        if (!forceFresh) {
            val safeName = suggestFileName(modelUrl, fileName)
            findExistingModelFile(app, safeName)?.let { existing ->
                Log.i("AppViewModel", "private model reused: name=$safeName bytes=${existing.length()}")
                _state.value = DlState.Done(existing)
                return
            }
        }

        // An explicit forced re-download (e.g. "Retry" after choosing to
        // redownload, or a debug reset) skips the manual-file prompt below
        // and goes straight to the network.
        if (forceFresh) {
            startDownload(app, forceFresh = true)
            return
        }

        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val nowState = _state.value
                if (nowState is DlState.Downloading || nowState is DlState.Done) {
                    return@launch
                }

                val safeName = suggestFileName(modelUrl, fileName)
                val privateDestination = File(app.filesDir, safeName)
                val reused = runCatching {
                    PersistentModelStore.reuseOrReplace(
                        context = app,
                        targetFileName = safeName,
                        privateDestination = privateDestination,
                        onReused = { bytes ->
                            Log.i("AppViewModel", "Reused persisted model '$safeName' ($bytes bytes)")
                        },
                        onReplacing = { oldFileName ->
                            Log.i("AppViewModel", "Removing stale persisted model '$oldFileName' before downloading '$safeName'")
                        }
                    )
                }.getOrElse { e ->
                    Log.w("AppViewModel", "Persisted model store check failed: ${e.message}")
                    false
                }

                if (reused && privateDestination.exists() && privateDestination.length() > 0L) {
                    _state.value = DlState.Done(privateDestination)
                    return@launch
                }

                // Nothing found automatically in this app's own storage or the
                // shared persisted-model folder. Pause here (instead of starting
                // the network download immediately) so the UI can offer the user
                // a chance to pick a copy saved elsewhere on the device — e.g. by
                // another install of this app. proceedWithDownload() or
                // useManuallySelectedModel() resumes from here.
                _state.value = DlState.NeedsModel
            } catch (ce: CancellationException) {
                if (_state.value !is DlState.Error) {
                    _state.value = DlState.Error("Canceled by user")
                }
            } catch (t: Throwable) {
                _state.value = DlState.Error(t.message ?: "Failed to check for an existing model")
            } finally {
                if (downloadJob === this.coroutineContext[Job]) {
                    downloadJob = null
                }
            }
        }
    }

    /**
     * Dismisses [DlState.NeedsModel] and starts the normal network download,
     * for when the user doesn't have (or can't find) a copy saved elsewhere
     * on the device to pick instead.
     */
    fun proceedWithDownload(appContext: Context) {
        startDownload(appContext.applicationContext, forceFresh = false)
    }

    /**
     * Copies a model file the user picked via the system file/document picker
     * (e.g. a copy saved by another install of this app) into private storage
     * and marks the model ready, skipping the network download entirely.
     *
     * On failure, surfaces a [DlState.Error] so the user can try picking again
     * (via "Retry", which re-enters [ensureModelDownloaded] and will offer the
     * prompt again) or fall back to downloading.
     */
    fun useManuallySelectedModel(appContext: Context, uri: Uri) {
        val app = appContext.applicationContext

        val running = downloadJob
        if (running?.isActive == true) {
            running.cancel(CancellationException("manual model file selected"))
            HeavyInitializer.cancel()
        }

        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val safeName = suggestFileName(modelUrl, fileName)
                val destination = File(app.filesDir, safeName)
                val tmp = File(app.filesDir, "$safeName.fromPicker.tmp")

                val copied = runCatching {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out, bufferSize = 1 shl 20) }
                        true
                    } ?: false
                }.getOrElse { e ->
                    Log.w("AppViewModel", "Failed to read manually selected model file: ${e.message}")
                    false
                }

                if (!copied || !tmp.exists() || tmp.length() <= 0L) {
                    tmp.delete()
                    _state.value = DlState.Error(
                        "Couldn't read the selected file. Choose another file, or retry to download the model instead."
                    )
                    return@launch
                }

                if (!tmp.renameTo(destination)) {
                    tmp.copyTo(destination, overwrite = true)
                    tmp.delete()
                }

                if (!isActive) return@launch

                runCatching { PersistentModelStore.persistFromPrivate(app, safeName, destination) }
                    .onFailure { e -> Log.w("AppViewModel", "Failed to persist manually selected model: ${e.message}") }

                Log.i("AppViewModel", "Using manually selected model file: name=$safeName bytes=${destination.length()}")
                _state.value = DlState.Done(destination)
            } catch (t: Throwable) {
                _state.value = DlState.Error(t.message ?: "Couldn't use the selected file.")
            } finally {
                if (downloadJob === this.coroutineContext[Job]) {
                    downloadJob = null
                }
            }
        }
    }

    /** Starts the actual network download; the only path that calls [HeavyInitializer.ensureInitialized]. */
    private fun startDownload(app: Context, forceFresh: Boolean) {
        val running = downloadJob
        if (running?.isActive == true) {
            if (!forceFresh) return
            running.cancel(CancellationException("forceFresh requested"))
            HeavyInitializer.cancel()
        }

        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            val job = coroutineContext[Job]
            try {
                val nowState = _state.value
                if (!forceFresh && (nowState is DlState.Downloading || nowState is DlState.Done)) {
                    return@launch
                }

                val safeName = suggestFileName(modelUrl, fileName)

                _state.value = DlState.Downloading(downloaded = 0L, total = null)

                var lastEmitNs = System.nanoTime()
                var lastBytes = 0L

                val progressBridge: (Long, Long?) -> Unit = progress@{ got, total ->
                    if (job?.isActive == false) return@progress

                    val now = System.nanoTime()
                    val elapsedMs = (now - lastEmitNs) / 1_000_000L
                    val deltaBytes = got - lastBytes

                    val shouldEmit =
                        elapsedMs >= uiThrottleMs ||
                                deltaBytes >= uiMinDeltaBytes ||
                                (total != null && got >= total)

                    if (shouldEmit) {
                        lastEmitNs = now
                        lastBytes = got
                        _state.value = DlState.Downloading(got, total)
                    }
                }

                val result = HeavyInitializer.ensureInitialized(
                    context = app,
                    modelUrl = modelUrl,
                    fileName = safeName,
                    timeoutMs = timeoutMs,
                    forceFresh = forceFresh,
                    onProgress = progressBridge
                )

                if (!isActive) return@launch

                _state.value = result.fold(
                    onSuccess = { file ->
                        runCatching { PersistentModelStore.persistFromPrivate(app, safeName, file) }
                            .onFailure { e -> Log.w("AppViewModel", "Failed to persist model to shared storage: ${e.message}") }
                        DlState.Done(file)
                    },
                    onFailure = { error -> DlState.Error(error.message ?: "Download failed") }
                )
            } catch (ce: CancellationException) {
                if (_state.value !is DlState.Error) {
                    _state.value = DlState.Error("Canceled by user")
                }
            } catch (t: Throwable) {
                if (!isActive) return@launch
                _state.value = DlState.Error(t.message ?: "Download failed")
            } finally {
                if (downloadJob === this.coroutineContext[Job]) {
                    downloadJob = null
                }
            }
        }
    }

    /**
     * Cancels the current download attempt.
     *
     * This cancels:
     * - the ViewModel job
     * - the HeavyInitializer owner job (best-effort)
     */
    fun cancelDownload() {
        downloadJob?.cancel(CancellationException("canceled by user"))
        HeavyInitializer.cancel()
        _state.value = DlState.Error("Canceled by user")
        downloadJob = null
    }

    fun resetForDebug() {
        downloadJob?.cancel(CancellationException("resetForDebug"))
        HeavyInitializer.resetForDebug()
        _state.value = DlState.Idle
        downloadJob = null
    }

    override fun onCleared() {
        downloadJob?.cancel(CancellationException("onCleared"))
        runCatching { HeavyInitializer.cancel() }
        downloadJob = null
        super.onCleared()
    }

    companion object {

        const val DEFAULT_MODEL_URL: String =
            "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm"

        private const val DEFAULT_FILE_NAME: String = "model.litertlm"
        private const val DEFAULT_TIMEOUT_MS: Long = 30L * 60L * 1000L
        private const val DEFAULT_UI_THROTTLE_MS: Long = 250L
        private const val DEFAULT_UI_MIN_DELTA_BYTES: Long = 1L * 1024L * 1024L

        fun factory(): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AppViewModel() as T
                }
            }

        fun factoryFromOverrides(
            modelUrlOverride: String? = null,
            fileNameOverride: String? = null,
            timeoutMsOverride: Long? = null,
            uiThrottleMsOverride: Long? = null,
            uiMinDeltaBytesOverride: Long? = null
        ): ViewModelProvider.Factory {
            val url = modelUrlOverride?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_URL
            val name = fileNameOverride?.takeIf { it.isNotBlank() } ?: DEFAULT_FILE_NAME
            val timeout = timeoutMsOverride?.takeIf { it > 0L } ?: DEFAULT_TIMEOUT_MS
            val throttle = uiThrottleMsOverride?.takeIf { it >= 0L } ?: DEFAULT_UI_THROTTLE_MS
            val minDelta = uiMinDeltaBytesOverride?.takeIf { it >= 0L } ?: DEFAULT_UI_MIN_DELTA_BYTES

            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AppViewModel(
                        modelUrl = url,
                        fileName = name,
                        timeoutMs = timeout,
                        uiThrottleMs = throttle,
                        uiMinDeltaBytes = minDelta
                    ) as T
                }
            }
        }

        private fun suggestFileName(url: String, fallback: String): String {
            val raw = url.substringAfterLast('/').ifBlank { fallback }
            val stripped = raw.substringBefore('?').ifBlank { fallback }
            return sanitizeFileName(stripped.ifBlank { fallback })
        }

        private fun sanitizeFileName(name: String): String {
            if (name.isBlank()) return DEFAULT_FILE_NAME
            val sb = StringBuilder(name.length)
            for (c in name) {
                val ok = (c in 'a'..'z') ||
                        (c in 'A'..'Z') ||
                        (c in '0'..'9') ||
                        c == '.' || c == '_' || c == '-'
                sb.append(if (ok) c else '_')
            }
            return sb.toString().take(160).ifBlank { DEFAULT_FILE_NAME }
        }

        private fun findExistingModelFile(context: Context, name: String): File? {
            val privateModelsDir = runCatching { context.getDir("models", Context.MODE_PRIVATE) }
                .getOrNull()

            val candidates = buildList {
                add(File(context.filesDir, name))
                add(File(context.filesDir, "models/$name"))
                if (privateModelsDir != null) add(File(privateModelsDir, name))
                add(File(context.cacheDir, name))
                add(File(context.cacheDir, "models/$name"))
            }

            return candidates.firstOrNull { f ->
                f.exists() && f.isFile && f.length() > 0L
            }
        }
    }
}

/* ───────────────────────────── UI Gate ───────────────────────────── */

@Composable
fun DownloadGate(
    state: DlState,
    onRetry: () -> Unit,
    onManualModelSelected: (Uri) -> Unit = {},
    onSkipManualSelection: () -> Unit = {},
    content: @Composable (modelFile: File) -> Unit
) {
    when (state) {
        is DlState.Idle -> {
            // Transient: ensureModelDownloaded() runs immediately after this frame
            // and moves state on, so nothing is rendered here to avoid a one-frame flash.
        }

        is DlState.NeedsModel -> {
            ManualModelSelectionDialog(
                onFilePicked = onManualModelSelected,
                onSkip = onSkipManualSelection
            )
        }

        is DlState.Downloading -> {
            val got = state.downloaded
            val total = state.total

            val pct: Int? = total?.let { t ->
                if (t > 0L) ((got * 100.0) / t.toDouble()).toInt().coerceIn(0, 100) else null
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Downloading the target SLM…")
                Spacer(Modifier.height(12.dp))

                if (pct != null) {
                    LinearProgressIndicator(
                        progress = { (pct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("$pct%  ($got / $total bytes)")
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("$got bytes")
                }
            }
        }

        is DlState.Error -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Failed to download model: ${state.message}")
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }

        is DlState.Done -> {
            content(state.file)
        }
    }
}

/**
 * Lets the user pick a model file already saved on this device — e.g. by
 * another install of the app — instead of starting the network download.
 *
 * "Select File" opens the system document picker (any location the user's
 * Files app/provider can browse, including another app's exported storage).
 * "Cancel" dismisses this and lets the download proceed normally via
 * [onSkip]; there is no outside-dismiss, so the user makes an explicit choice.
 */
@Composable
private fun ManualModelSelectionDialog(
    onFilePicked: (Uri) -> Unit,
    onSkip: () -> Unit
) {
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    AlertDialog(
        // Back-press/outside-tap behaves the same as the explicit Cancel button:
        // the download proceeds rather than leaving the user stuck on the dialog.
        onDismissRequest = onSkip,
        title = { Text("Use an existing model file?") },
        text = {
            Text(
                "The on-device AI model isn't on this device yet. If another " +
                    "install of this app already downloaded it, you can select " +
                    "that file instead of downloading it again. If you can't find " +
                    "it, cancel and the download will continue."
            )
        },
        confirmButton = {
            TextButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                Text("Select File")
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text("Cancel")
            }
        }
    )
}
