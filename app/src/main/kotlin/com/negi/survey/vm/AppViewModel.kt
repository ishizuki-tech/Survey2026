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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.negi.survey.BuildConfig
import com.negi.survey.utils.HeavyInitializer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/* ───────────────────────────── Download State ───────────────────────────── */

/**
 * Represents the current model download lifecycle state.
 *
 * This sealed state is intentionally minimal and UI-friendly:
 * - It can be observed directly by Compose.
 * - It does not expose transport details (HTTP, resumable chunks, etc.).
 * - It is suitable for gating progression into SLM initialization.
 */
sealed class DlState {

    /**
     * No download in progress and no confirmed model file.
     *
     * This state is also used as a "pre-flight" state when the ViewModel is
     * initialized but has not yet been asked to ensure the model.
     */
    data object Idle : DlState()

    /**
     * Model download is currently in progress.
     *
     * @property downloaded Number of bytes downloaded so far.
     * @property total Total content length in bytes if known, or null when
     * the server does not provide it.
     */
    data class Downloading(
        val downloaded: Long,
        val total: Long?
    ) : DlState()

    /**
     * Download successfully completed.
     *
     * @property file Final model file location on disk.
     */
    data class Done(
        val file: File
    ) : DlState()

    /**
     * Download failed or was cancelled.
     *
     * @property message Human-readable error message suitable for UI.
     */
    data class Error(
        val message: String
    ) : DlState()
}

/* ───────────────────────────── ViewModel ───────────────────────────── */

/**
 * ViewModel responsible for ensuring the on-device SLM model exists locally.
 *
 * Core responsibilities:
 * - Provide a single-flight, resume-capable download entry point via [HeavyInitializer].
 * - Expose a stable [StateFlow] of [DlState] for Compose UI gates.
 * - Apply progress throttling to prevent excessive recompositions.
 *
 * Architectural note:
 * This ViewModel is intentionally thin. It delegates:
 * - Network + resume + integrity checks to [HeavyInitializer].
 * - UI rendering to [DownloadGate].
 */
class AppViewModel(
    val modelUrl: String = DEFAULT_MODEL_URL,
    private val fileName: String = DEFAULT_FILE_NAME,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val uiThrottleMs: Long = DEFAULT_UI_THROTTLE_MS,
    private val uiMinDeltaBytes: Long = DEFAULT_UI_MIN_DELTA_BYTES
) : ViewModel() {

    private val _state = MutableStateFlow<DlState>(DlState.Idle)

    /**
     * Exposes the current download state for observers.
     */
    val state: StateFlow<DlState> = _state.asStateFlow()

    /**
     * Basic guard to avoid launching redundant orchestration coroutines.
     *
     * HeavyInitializer is assumed to be single-flight internally, but this
     * ViewModel-level guard reduces noisy parallel attempts and state churn.
     */
    private val inFlight = AtomicBoolean(false)

    /**
     * Ensures that the model file is available on disk.
     *
     * Behavior summary:
     * - If [forceFresh] is false and any plausible existing model file is found,
     *   this method immediately emits [DlState.Done] and returns.
     * - Otherwise, [HeavyInitializer.ensureInitialized] is used to perform:
     *   - single-flight download
     *   - resume support
     *   - optional integrity checks (implementation-dependent)
     * - Progress is bridged into [DlState.Downloading] with throttling.
     *
     * Threading:
     * - The orchestration runs on [Dispatchers.IO].
     * - [MutableStateFlow] is thread-safe for background emissions.
     *
     * Idempotency:
     * - Safe to call from multiple sites (e.g., LaunchedEffect + Retry button).
     */
    fun ensureModelDownloaded(
        appContext: Context,
        forceFresh: Boolean = false
    ) {
        val app = appContext.applicationContext

        // Fast-path: already done and not forcing refresh.
        val currentState = _state.value
        if (!forceFresh && currentState is DlState.Done && currentState.file.exists()) {
            return
        }

        // Best-effort pre-check for an existing model file.
        if (!forceFresh) {
            val safeName = suggestFileName(modelUrl, fileName)
            findExistingModelFile(app, safeName)?.let { existing ->
                _state.value = DlState.Done(existing)
                return
            }
        }

        // Prevent redundant orchestration launches.
        if (!forceFresh && !inFlight.compareAndSet(false, true)) {
            return
        }
        if (forceFresh && !inFlight.compareAndSet(false, true)) {
            // Even for force refresh, avoid parallel refresh attempts.
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nowState = _state.value
                if (!forceFresh && (nowState is DlState.Downloading || nowState is DlState.Done)) {
                    return@launch
                }

                val safeName = suggestFileName(modelUrl, fileName)
                val token = BuildConfig.HF_TOKEN.takeIf { it.isNotBlank() }

                _state.value = DlState.Downloading(downloaded = 0L, total = null)

                /**
                 * Throttling state for progress-to-UI updates.
                 *
                 * The bridge emits when any of these are true:
                 * - time since last commit >= [uiThrottleMs]
                 * - bytes since last commit >= [uiMinDeltaBytes]
                 * - the download reaches total size
                 *
                 * This keeps UI smooth and avoids saturating the main thread.
                 */
                var lastEmitNs = System.nanoTime()
                var lastBytes = 0L

                val progressBridge: (Long, Long?) -> Unit = { got, total ->
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

                /**
                 * Heavy initializer contract:
                 * - Returns Result<File> representing the final local model.
                 * - Collapses concurrent calls across the app process.
                 * - May perform resume/integrity verification internally.
                 */
                val result = HeavyInitializer.ensureInitialized(
                    context = app,
                    modelUrl = modelUrl,
                    hfToken = token,
                    fileName = safeName,
                    timeoutMs = timeoutMs,
                    forceFresh = forceFresh,
                    onProgress = progressBridge
                )

                _state.value = result.fold(
                    onSuccess = { file -> DlState.Done(file) },
                    onFailure = { error ->
                        DlState.Error(error.message ?: "Download failed")
                    }
                )
            } finally {
                inFlight.set(false)
            }
        }
    }

    /**
     * Requests cancellation of any in-flight model initialization/download.
     *
     * This is a best-effort signal to [HeavyInitializer]. The underlying
     * implementation may:
     * - cancel active network work
     * - keep partially downloaded files for future resume
     */
    fun cancelDownload() {
        viewModelScope.launch {
            HeavyInitializer.cancel()
            _state.value = DlState.Error("Canceled by user")
            inFlight.set(false)
        }
    }

    /**
     * Debug-only reset entry point.
     *
     * This clears both:
     * - UI state in this ViewModel
     * - any internal single-flight book-keeping in [HeavyInitializer]
     *
     * This should not be exposed in production UI.
     */
    fun resetForDebug() {
        HeavyInitializer.resetForDebug()
        _state.value = DlState.Idle
        inFlight.set(false)
    }

    companion object {

        /**
         * Default hosted model URL.
         *
         * This can be overridden by YAML `model_defaults.default_model_url`.
         */
        const val DEFAULT_MODEL_URL: String =
            "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm"

        /**
         * Default local file name used when URL inference is unavailable.
         */
        private const val DEFAULT_FILE_NAME: String = "model.litertlm"

        /**
         * Default hard timeout for model acquisition.
         */
        private const val DEFAULT_TIMEOUT_MS: Long = 30L * 60L * 1000L

        /**
         * Minimum time interval between progress-to-UI emissions.
         */
        private const val DEFAULT_UI_THROTTLE_MS: Long = 250L

        /**
         * Minimum byte delta required to trigger a UI emission.
         */
        private const val DEFAULT_UI_MIN_DELTA_BYTES: Long = 1L * 1024L * 1024L

        /**
         * Compose-friendly factory using compiled defaults.
         */
        fun factory(): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AppViewModel() as T
                }
            }

        /**
         * Factory that accepts nullable overrides (e.g., from YAML model_defaults).
         *
         * This mirrors the usage in MainActivity:
         * ```kotlin
         * viewModel(factory = AppViewModel.factoryFromOverrides(...))
         * ```
         */
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
            val minDelta = uiMinDeltaBytesOverride?.takeIf { it >= 0L }
                ?: DEFAULT_UI_MIN_DELTA_BYTES

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

        /**
         * Suggest a stable file name for the model.
         *
         * Strategy:
         * - Use the last path segment of the URL.
         * - Strip query parameters.
         * - Fall back to [fallback] when URL inference is empty.
         */
        private fun suggestFileName(url: String, fallback: String): String {
            val raw = url.substringAfterLast('/').ifBlank { fallback }
            val stripped = raw.substringBefore('?').ifBlank { fallback }
            return stripped
        }

        /**
         * Best-effort search for an already-present model file.
         *
         * This does not guarantee correctness with future initializer revisions,
         * but it provides a pragmatic speed-up for common storage patterns.
         */
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

/**
 * UI gate that blocks entry into the SLM-dependent flow until
 * the model file is available locally.
 *
 * Design notes:
 * - [DlState.Idle] is rendered using a similar layout to downloading states
 *   to avoid UI flicker during short pre-flight checks.
 * - The UI deliberately avoids binding to transport details.
 */
@Composable
fun DownloadGate(
    state: DlState,
    onRetry: () -> Unit,
    content: @Composable (modelFile: File) -> Unit
) {
    when (state) {
        is DlState.Idle -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Checking local model cache…")
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
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
