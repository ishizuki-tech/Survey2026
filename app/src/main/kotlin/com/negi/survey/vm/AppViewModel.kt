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
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.negi.survey.utils.ModelArtifactOperations
import com.negi.survey.utils.HeavyInitializer
import com.negi.survey.ui.theme.outdoorBackplate
import com.negi.survey.utils.PersistentModelStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    data class NeedsModel(val expectedFileName: String) : DlState()

    /**
     * Transient state while [AppViewModel.useManuallySelectedModel] copies
     * the user's picked file into private storage. Unlike [Downloading],
     * there's no byte-level progress to show here — the SAF file descriptor
     * doesn't expose a total up front the way the HTTP download response
     * does — so the UI just shows an indeterminate "working on it" state
     * instead of a percentage.
     */
    data object ImportingSelectedFile : DlState()

    data class Downloading(
        val downloaded: Long,
        val total: Long?
    ) : DlState()

    data class Done(
        val file: File,
        /**
         * True when [file] came from [AppViewModel.useManuallySelectedModel]
         * rather than a normal download or a reused cached/persisted copy.
         * Lets the UI offer a different recovery path (re-pick a file,
         * rather than a plain retry) if initializing this file fails.
         */
        val fromManualSelection: Boolean = false
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

    private var operation: ModelArtifactOperations.Lease? = null

    @Volatile
    private var completedInstallation: Pair<DlState.Done, ModelArtifactOperations.Lease>? = null

    /** Claims before launching, so callbacks from a previous request cannot win launch ordering. */
    private fun launchOperation(
        app: Context,
        block: suspend (ModelArtifactOperations.Lease) -> Unit,
    ) {
        operation?.cancel()
        downloadJob?.cancel(CancellationException("Model request replaced"))
        val destination = File(app.filesDir, suggestFileName(modelUrl, fileName))
        val lease = ModelArtifactOperations.begin(destination)
        operation = lease
        val job = viewModelScope.launch(Dispatchers.IO + lease, start = CoroutineStart.LAZY) {
            try {
                block(lease)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                publish(lease, DlState.Error(t.message ?: "Model preparation failed"))
            }
        }
        lease.attach(job)
        downloadJob = job
        job.start()
    }

    private fun publish(lease: ModelArtifactOperations.Lease, state: DlState) {
        try {
            lease.publish {
                if (state is DlState.Done) completedInstallation = state to lease
                _state.value = state
            }
        } catch (_: CancellationException) {
            // A canceled/replaced request has no authority over the current UI.
        }
    }

    /** Reuses private/MediaStore models; otherwise offers the manual-file prompt. */
    fun ensureModelDownloaded(appContext: Context, forceFresh: Boolean = false) {
        val app = appContext.applicationContext
        val current = _state.value
        if (!forceFresh && current is DlState.Done && current.file.exists()) return
        if (!forceFresh && downloadJob?.isActive == true) return
        if (forceFresh) {
            startDownload(app, true)
            return
        }
        launchOperation(app) { lease ->
            lease.locked {
                val existing = findExistingModelFile(app, lease.file.name)
                if (existing != null) {
                    publish(lease, DlState.Done(existing))
                } else {
                    val reused = PersistentModelStore.reuseOrReplace(
                        app, lease.file.name, lease.file,
                        onReused = { bytes -> Log.i("AppViewModel", "Reused persisted model ($bytes bytes)") },
                        onReplacing = { }
                    )
                    lease.checkCurrent()
                    publish(lease, if (reused) DlState.Done(lease.file) else DlState.NeedsModel(lease.file.name))
                }
            }
        }
    }

    fun proceedWithDownload(appContext: Context) {
        startDownload(appContext.applicationContext, false)
    }

    /** Copies to operation-owned staging before entering the shared installation boundary. */
    fun useManuallySelectedModel(appContext: Context, uri: Uri) {
        val app = appContext.applicationContext
        launchOperation(app) { lease ->
            publish(lease, DlState.ImportingSelectedFile)
            lease.import(open = { app.contentResolver.openInputStream(uri) }) { file ->
                PersistentModelStore.persistFromPrivate(app, file.name, file, replaceExisting = true)
                publish(lease, DlState.Done(file, fromManualSelection = true))
            }
        }
    }

    /** Rejects recovery callbacks from an installation replaced by another owner. */
    suspend fun discardBadManuallySelectedModel(appContext: Context, installation: DlState.Done) {
        val completed = completedInstallation?.takeIf { it.first === installation } ?: return
        val lease = completed.second
        val file = installation.file
        lease.recover { recovery ->
            recovery.publish {
                if (!file.delete() && file.exists()) throw java.io.IOException("Could not delete bad model")
            }
            PersistentModelStore.forget(appContext.applicationContext, file.name)
        }
    }

    private fun startDownload(app: Context, forceFresh: Boolean) {
        if (!forceFresh && downloadJob?.isActive == true) return
        launchOperation(app) { lease ->
            lease.locked {
                publish(lease, DlState.Downloading(0L, null))
                var lastEmitNs = System.nanoTime()
                var lastBytes = 0L
                val progress: (Long, Long?) -> Unit = { got, total ->
                    val now = System.nanoTime()
                    if ((now - lastEmitNs) / 1_000_000L >= uiThrottleMs ||
                        got - lastBytes >= uiMinDeltaBytes || (total != null && got >= total)) {
                        lastEmitNs = now
                        lastBytes = got
                        publish(lease, DlState.Downloading(got, total))
                    }
                }
                val result = HeavyInitializer.ensureInitialized(
                    context = app, modelUrl = modelUrl, fileName = lease.file.name,
                    timeoutMs = timeoutMs, forceFresh = forceFresh, onProgress = progress
                )
                lease.checkCurrent()
                val file = result.getOrThrow()
                PersistentModelStore.persistFromPrivate(app, file.name, file, replaceExisting = true)
                publish(lease, DlState.Done(file))
            }
        }
    }

    fun cancelDownload() {
        operation?.cancel()
        downloadJob?.cancel(CancellationException("canceled by user"))
        _state.value = DlState.Error("Canceled by user")
        downloadJob = null
    }

    fun resetForDebug() {
        operation?.cancel()
        downloadJob?.cancel(CancellationException("resetForDebug"))
        _state.value = DlState.Idle
        downloadJob = null
    }

    override fun onCleared() {
        operation?.cancel()
        downloadJob?.cancel(CancellationException("onCleared"))
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
            // and moves state on. This still paints the same dark backdrop as the
            // Intro/loading screens before it (rather than rendering nothing) so this
            // one frame never exposes the app theme's base background underneath —
            // on a device in light mode that base is white, which is what showed up
            // as a brief white flash here before reaching the (intentionally light)
            // survey screens.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(outdoorBackplate())
            )
        }

        is DlState.NeedsModel -> {
            ManualModelSelectionDialog(
                expectedFileName = state.expectedFileName,
                onFilePicked = onManualModelSelected,
                onSkip = onSkipManualSelection
            )
        }

        is DlState.ImportingSelectedFile -> {
            // The picked file can be a few GB, so copying it into private
            // storage (see useManuallySelectedModel) takes a visible moment.
            // Without this, the screen looked stuck for a couple of seconds
            // between picking the file and the model being ready.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(outdoorBackplate())
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Copying the selected file…")
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth()
                )
            }
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
                    .background(outdoorBackplate())
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Downloading the target SLM…")
                Spacer(Modifier.height(12.dp))

                if (pct != null) {
                    LinearProgressIndicator(
                        color = MaterialTheme.colorScheme.outline,
                        progress = { (pct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("$pct%  ($got / $total bytes)")
                } else {
                    LinearProgressIndicator(
                        color = MaterialTheme.colorScheme.outline,
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
                    .background(outdoorBackplate())
                    .verticalScroll(rememberScrollState())
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
 * Opens the system document picker already pointed at the device's
 * Downloads folder, since that's where a manually-saved model file almost
 * always ends up. [DocumentsContract.EXTRA_INITIAL_URI] is a best-effort
 * hint (API 26+, which this app's minSdk already requires) — some
 * device/provider combinations ignore it, in which case the picker just
 * opens to its normal default location instead.
 */
private class OpenDocumentAtDownloads : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val downloadsUri = Uri.parse(
                "content://com.android.externalstorage.documents/document/primary:Download"
            )
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsUri)
        }
        return intent
    }
}

/**
 * Lets the user pick a model file already saved on this device — e.g. by
 * another install of the app — instead of starting the network download.
 *
 * "Select File" opens the system document picker, hinted to open at
 * Downloads (see [OpenDocumentAtDownloads]), filtered to any file type so
 * the (often unrecognized) ".litertlm" extension is never hidden.
 * "Download New" dismisses this and starts the network download via
 * [onSkip]; back-press/outside-tap does the same, via the same callback.
 */
@Composable
private fun ManualModelSelectionDialog(
    expectedFileName: String,
    onFilePicked: (Uri) -> Unit,
    onSkip: () -> Unit
) {
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = remember { OpenDocumentAtDownloads() }
    ) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    AlertDialog(
        // Back-press/outside-tap behaves the same as the explicit "Download New"
        // button: the download proceeds rather than leaving the user stuck on
        // the dialog.
        onDismissRequest = onSkip,
        title = { Text("Model File Missing") },
        text = {
            Text(
                "We couldn't find the language model. If you downloaded it " +
                    "previously, you can locate it manually, or we can download " +
                    "a fresh copy.\n\n" +
                    "Look for a ~4.92 GB file named \"$expectedFileName\". It will " +
                    "be in the Download/Survey2026Models directory. Tap the file " +
                    "to load it.\n\n" +
                    "If you can't find it, select \"Download New\" to download a " +
                    "new copy of the file."
            )
        },
        confirmButton = {
            TextButton(
                onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
            ) {
                Text("Select File")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onSkip,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
            ) {
                Text("Download New")
            }
        }
    )
}
