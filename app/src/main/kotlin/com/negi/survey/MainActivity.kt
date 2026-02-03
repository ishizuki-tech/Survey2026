/*
 * =====================================================================
 *  IshizukiTech LLC — Android App Shell
 *  ---------------------------------------------------------------------
 *  File: MainActivity.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.survey

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import com.negi.survey.net.GitHubUploader
import com.negi.survey.screens.AiScreen
import com.negi.survey.screens.ConfigOptionUi
import com.negi.survey.screens.DoneScreen
import com.negi.survey.screens.IntroScreen
import com.negi.survey.screens.ReviewScreen
import com.negi.survey.screens.SpeechController
import com.negi.survey.slm.LiteRtLM
import com.negi.survey.slm.LiteRtRepository
import com.negi.survey.slm.Model
import com.negi.survey.slm.Repository
import com.negi.survey.slm.buildModelConfig
import com.negi.survey.ui.theme.SurveyNavTheme
import com.negi.survey.vm.AiViewModel
import com.negi.survey.vm.AppViewModel
import com.negi.survey.vm.DlState
import com.negi.survey.vm.DownloadGate
import com.negi.survey.vm.FlowAI
import com.negi.survey.vm.FlowDone
import com.negi.survey.vm.FlowHome
import com.negi.survey.vm.FlowReview
import com.negi.survey.vm.SurveyViewModel
import com.negi.survey.vm.WhisperSpeechController
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** IMPORTANT: Alias to avoid accidental collision with local types. */
import com.negi.survey.screens.ConfigDetails as ScreenConfigDetails

// -----------------------------------------------------------------------------
// Defaults
// -----------------------------------------------------------------------------

/** Default Whisper model path in assets when config omits it. */
private const val DEFAULT_WHISPER_ASSET_MODEL: String = "models/ggml-small-q5_1.bin"

/** Default Whisper language when config omits it. */
private const val DEFAULT_WHISPER_LANGUAGE: String = "en"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LiteRtLM.setApplicationContext(this)

        installCrashCapture()
        enqueuePendingCrashUploads()
        configureEdgeToEdge()

        setContent {
            SurveyNavTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        // Keep the whole app above the IME when it shows.
                        .imePadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot()
                }
            }
        }
    }

    /** Install crash capture as early as possible. */
    private fun installCrashCapture() {
        runCatching { CrashCapture.install(applicationContext) }
            .onFailure { Log.w(TAG, "CrashCapture.install failed: ${it.message}", it) }
    }

    /** Enqueue pending crash files for upload if configuration exists. */
    private fun enqueuePendingCrashUploads() {
        runCatching { CrashCapture.enqueuePendingCrashUploadsIfPossible(applicationContext) }
            .onFailure { Log.w(TAG, "CrashCapture.enqueuePendingCrashUploads failed: ${it.message}", it) }
    }

    /**
     * Configure edge-to-edge system bars.
     *
     * Preferred path:
     *  - enableEdgeToEdge() with explicit dark styles on a black background.
     *
     * Fallback path:
     *  - Use decorFitsSystemWindows(false) + icon appearance controls.
     */
    private fun configureEdgeToEdge() {
        val black = "#000000".toColorInt()

        // Force edge-to-edge in both paths for consistency.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        runCatching {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(black),
                navigationBarStyle = SystemBarStyle.dark(black),
            )
        }.onFailure { t ->
            Log.w(TAG, "enableEdgeToEdge failed; using legacy insets path: ${t.message}", t)

            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { window.isNavigationBarContrastEnforced = false }
            }
        }
    }

    companion object {
        const val TAG = "MainActivity"
    }
}

/* ───────────────────────────── Visual Utilities ───────────────────────────── */

@Composable
private fun appBackplate(): Brush =
    Brush.verticalGradient(
        0f to Color(0xFF202020),
        1f to Color(0xFF040404)
    )

@Composable
private fun Modifier.neonEdgeThin(
    color: Color = MaterialTheme.colorScheme.primary,
    intensity: Float = 0.035f,
    corner: Dp = 20.dp
): Modifier = this.then(
    Modifier.drawBehind {
        val radius = size.minDimension * 0.45f
        val cr = corner.toPx()
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = intensity), Color.Transparent),
                center = center,
                radius = radius
            ),
            cornerRadius = CornerRadius(cr, cr)
        )
    }
)

/* ───────────────────────────── Init Gate ───────────────────────────── */

/**
 * A simple init gate composable that runs a suspend init and shows:
 * - Loading UI while running
 * - Error UI with retry on failure
 * - Content UI when init succeeds
 */
@Composable
fun InitGate(
    modifier: Modifier = Modifier,
    key: Any? = Unit,
    init: suspend () -> Unit,
    progressText: String = "Initializing…",
    subText: String = "Preparing on-device model and resources",
    onErrorMessage: (Throwable) -> String = { it.message ?: "Initialization failed" },
    content: @Composable () -> Unit
) {
    var isLoading by remember(key) { mutableStateOf(true) }
    var error by remember(key) { mutableStateOf<Throwable?>(null) }
    var initJob by remember(key) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun kick() {
        initJob?.cancel()
        isLoading = true
        error = null
        initJob = scope.launch {
            try {
                init()
                isLoading = false
            } catch (ce: CancellationException) {
                // Cancellation is expected during recomposition/dispose; do not treat as error.
                // Avoid rethrow to prevent cascading cancellation in parent scopes.
                return@launch
            } catch (t: Throwable) {
                error = t
                isLoading = false
            }
        }
    }

    DisposableEffect(key) {
        onDispose { initJob?.cancel() }
    }

    LaunchedEffect(key) {
        kick()
    }

    val backplate = appBackplate()

    when {
        isLoading -> {
            Box(
                modifier
                    .fillMaxSize()
                    .imePadding()
                    .background(backplate)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    modifier = Modifier
                        .wrapContentWidth()
                        .neonEdgeThin()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))

                        val pulse = rememberInfiniteTransition(label = "init_gate_pulse")
                        val alpha by pulse.animateFloat(
                            initialValue = 0.35f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 1100, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "init_gate_alpha"
                        )

                        Text(
                            text = progressText,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = subText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        error != null -> {
            Box(
                modifier
                    .fillMaxSize()
                    .imePadding()
                    .background(backplate)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    modifier = Modifier
                        .wrapContentWidth()
                        .neonEdgeThin(
                            color = MaterialTheme.colorScheme.error,
                            intensity = 0.05f
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = onErrorMessage(error!!),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { kick() }) {
                            Text("Retry")
                        }
                    }
                }
            }
        }

        else -> content()
    }
}

/* ──────────────────────── Audio Permission Gate ─────────────────────────── */

@Composable
fun AudioPermissionGate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val permission = Manifest.permission.RECORD_AUDIO

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission =
                    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "Microphone permission is required for voice input.",
                    actionLabel = "Settings"
                )
                if (result == SnackbarResult.ActionPerformed) {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        }
    }

    if (hasPermission) {
        content()
        return
    }

    val backplate = appBackplate()

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .background(backplate)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .wrapContentWidth()
                .neonEdgeThin()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = "Microphone",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = "Microphone permission needed",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "To use voice input for survey answers, allow microphone access.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { launcher.launch(permission) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Allow microphone")
                }
                IconButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Open app settings"
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

/* ───────────────────────────── App Root ───────────────────────────── */

@Composable
private fun AppRoot() {
    AppNav()
}

/* ───────────────────────────── App Nav Root ───────────────────────────── */

@Composable
fun AppNav() {
    val appContext = LocalContext.current.applicationContext

    val options = remember(appContext) {
        val assetManager = appContext.assets
        val yamlFiles = listAssetYamlConfigs(assetManager)
            .filter { path ->
                val name = path.substringAfterLast('/').lowercase(Locale.US)
                (name.endsWith(".yaml") || name.endsWith(".yml")) &&
                        (name.startsWith("survey_") || name.startsWith("survey_config"))
            }
            .sorted()

        val mapped = yamlFiles.map { path ->
            val fileName = path.substringAfterLast('/')
            configOptionFromFileName(fileName = fileName).copy(id = path)
        }

        mapped.ifEmpty {
            listOf(
                ConfigOptionUi(
                    id = "configs/survey_config1.yaml",
                    label = "Default config",
                    description = "Fallback survey configuration loaded from survey_config1.yaml."
                )
            )
        }
    }

    var chosen by remember { mutableStateOf<ConfigOptionUi?>(null) }
    var config by remember { mutableStateOf<SurveyConfig?>(null) }
    var configLoading by remember { mutableStateOf(false) }
    var configError by remember { mutableStateOf<String?>(null) }
    var selectionEpoch by remember { mutableIntStateOf(0) }

    if (chosen == null) {
        IntroScreen(
            options = options,
            defaultOptionId = options.firstOrNull()?.id,
            onStart = { option ->
                selectionEpoch += 1
                config = null
                configError = null
                configLoading = false
                chosen = option
                Log.d(MainActivity.TAG, "Intro -> Start session. epoch=$selectionEpoch, file=${option.id}")
            },
            onResolveConfigDetails = { configId ->
                resolveConfigDetailsFromAssets(
                    context = appContext,
                    configId = configId
                )
            }
        )
        return
    }

    val sessionKey = remember(chosen!!.id, selectionEpoch) {
        "${chosen!!.id}@$selectionEpoch"
    }

    LaunchedEffect(sessionKey) {
        configLoading = true
        configError = null
        try {
            SurveyConfigLoader.setDebug(
                format = true,
                validate = true,
                prompts = true,
                dumpSystemPrompts = true
            )
            val loaded = withContext(Dispatchers.IO) {
                SurveyConfigLoader.fromAssetsValidated(appContext, chosen!!.id)
            }
            config = loaded
            Log.d(MainActivity.TAG, "Config loaded. session=$sessionKey")
        } catch (t: Throwable) {
            config = null
            configError = t.message ?: "Failed to load survey configuration."
            Log.e(MainActivity.TAG, "Config load failed. session=$sessionKey", t)
        } finally {
            configLoading = false
        }
    }

    val backplate = appBackplate()

    when {
        configLoading || (config == null && configError == null) -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .background(backplate)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    modifier = Modifier
                        .wrapContentWidth()
                        .neonEdgeThin()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Loading survey configuration…",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Parsing YAML graph and SLM/Whisper metadata",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            return
        }

        configError != null -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .background(backplate)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    modifier = Modifier
                        .wrapContentWidth()
                        .neonEdgeThin(
                            color = MaterialTheme.colorScheme.error,
                            intensity = 0.05f
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = configError!!,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                Log.d(MainActivity.TAG, "Error -> Back to selector. session=$sessionKey")
                                chosen = null
                                config = null
                                configError = null
                                configLoading = false
                            }
                        ) {
                            Text("Back to config selector")
                        }
                    }
                }
            }
            return
        }
    }

    val cfg = config!!

    val sessionVmStore = remember(sessionKey) { ViewModelStore() }
    val sessionVmOwner = remember(sessionVmStore) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = sessionVmStore
        }
    }

    DisposableEffect(sessionKey) {
        onDispose {
            Log.d(MainActivity.TAG, "Session dispose -> clearing ViewModelStore. session=$sessionKey")
            sessionVmStore.clear()
        }
    }

    val appVm: AppViewModel = viewModel(
        viewModelStoreOwner = sessionVmOwner,
        key = "AppViewModel_$sessionKey",
        factory = AppViewModel.factoryFromOverrides(
            modelUrlOverride = cfg.modelDefaults.defaultModelUrl,
            fileNameOverride = cfg.modelDefaults.defaultFileName,
            timeoutMsOverride = cfg.modelDefaults.timeoutMs,
            uiThrottleMsOverride = cfg.modelDefaults.uiThrottleMs,
            uiMinDeltaBytesOverride = cfg.modelDefaults.uiMinDeltaBytes
        )
    )

    val state by appVm.state.collectAsState()

    LaunchedEffect(state) {
        if (state is DlState.Idle) {
            Log.d(MainActivity.TAG, "DownloadGate idle -> start download. session=$sessionKey")
            appVm.ensureModelDownloaded(appContext)
        }
    }

    DownloadGate(
        state = state,
        onRetry = {
            Log.d(MainActivity.TAG, "DownloadGate retry. session=$sessionKey")
            appVm.ensureModelDownloaded(appContext)
        }
    ) { modelFile ->

        val modelConfig = remember(cfg) { buildModelConfig(cfg.slm) }
        val slmModel = remember(modelFile.absolutePath, modelConfig, cfg.modelDefaults.defaultFileName) {
            val modelName = cfg.modelDefaults.defaultFileName
                ?.substringBeforeLast('.')
                ?.ifBlank { null }
                ?: "ondevice-slm"

            Model(
                name = modelName,
                taskPath = modelFile.absolutePath,
                config = modelConfig
            )
        }

        InitGate(
            key = "slm_init@$sessionKey@${modelFile.absolutePath}",
            progressText = "Initializing Small Language Model…",
            subText = "Setting up accelerated runtime and buffers",
            onErrorMessage = { "Failed to initialize model: ${it.message}" },
            init = {
                withContext(Dispatchers.Default) {
                    LiteRtLM.initializeIfNeeded(
                        context = appContext,
                        model = slmModel,
                        supportImage = false,
                        supportAudio = false
                    )
                }
            }
        ) {
            val backStack = rememberNavBackStack(FlowHome)

            val repo: Repository = remember(slmModel, cfg) {
                LiteRtRepository(slmModel, cfg)
            }

            val vmSurvey: SurveyViewModel = viewModel(
                viewModelStoreOwner = sessionVmOwner,
                key = "SurveyViewModel_$sessionKey",
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return SurveyViewModel(nav = backStack, config = cfg) as T
                    }
                }
            )

            val vmAI: AiViewModel = viewModel(
                viewModelStoreOwner = sessionVmOwner,
                key = "AiViewModel_${sessionKey}_${slmModel.name}",
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return AiViewModel(repo) as T
                    }
                }
            )

            val resetToSelector: () -> Unit = {
                Log.d(MainActivity.TAG, "resetToSelector invoked. session=$sessionKey")
                chosen = null
                config = null
                configError = null
                configLoading = false
            }

            val voiceEnabled = remember(cfg) { cfg.whisper.enabled ?: true }
            if (voiceEnabled) {
                AudioPermissionGate {
                    SurveyNavHost(
                        vmSurvey = vmSurvey,
                        vmAI = vmAI,
                        backStack = backStack,
                        onResetToSelector = resetToSelector,
                        whisperMeta = cfg.whisper,
                        sessionId = sessionKey,
                        sessionVmOwner = sessionVmOwner
                    )
                }
            } else {
                SurveyNavHost(
                    vmSurvey = vmSurvey,
                    vmAI = vmAI,
                    backStack = backStack,
                    onResetToSelector = resetToSelector,
                    whisperMeta = cfg.whisper,
                    sessionId = sessionKey,
                    sessionVmOwner = sessionVmOwner
                )
            }
        }
    }
}

/* ───────────────────────────── No-op Speech ───────────────────────────── */

private class NoOpSpeechController(
    private val disabledReason: String = "Voice input is disabled."
) : SpeechController {

    private val _isRecording = MutableStateFlow(false)
    private val _isTranscribing = MutableStateFlow(false)
    private val _partialText = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)

    override val isRecording: StateFlow<Boolean> = _isRecording
    override val isTranscribing: StateFlow<Boolean> = _isTranscribing
    override val partialText: StateFlow<String> = _partialText
    override val errorMessage: StateFlow<String?> = _error

    override fun updateContext(surveyId: String?, questionId: String?) {
        // No-op
    }

    override fun startRecording() {
        _error.value = disabledReason
    }

    override fun stopRecording() {
        // No-op
    }

    override fun toggleRecording() {
        startRecording()
    }
}

/* ───────────────────────────── Survey Nav Host ───────────────────────────── */

@Composable
fun SurveyNavHost(
    vmSurvey: SurveyViewModel,
    vmAI: AiViewModel,
    backStack: NavBackStack<NavKey>,
    onResetToSelector: () -> Unit = {},
    whisperMeta: SurveyConfig.WhisperMeta = SurveyConfig.WhisperMeta(),
    sessionId: String = "session",
    sessionVmOwner: ViewModelStoreOwner? = null
) {
    val appContext = LocalContext.current.applicationContext
    val owner = sessionVmOwner ?: LocalViewModelStoreOwner.current
    ?: error("Missing ViewModelStoreOwner")

    val canGoBack by vmSurvey.canGoBack.collectAsState()
    val voiceEnabled = remember(whisperMeta.enabled) { whisperMeta.enabled ?: true }
    val latestNode by vmSurvey.currentNode.collectAsState()
    val latestNodeId = latestNode.id

    // IMPORTANT:
    // - IME causes recompositions and size changes.
    // - imePadding() here prevents the whole nav content from being covered by the keyboard.
    val rootModifier = Modifier.fillMaxSize().imePadding()

    val assetPath = remember(whisperMeta.assetModelPath) {
        whisperMeta.assetModelPath?.ifBlank { null } ?: DEFAULT_WHISPER_ASSET_MODEL
    }

    val lang = remember(whisperMeta.language) {
        whisperMeta.language
            ?.trim()
            ?.lowercase(Locale.US)
            ?.ifBlank { null }
            ?: DEFAULT_WHISPER_LANGUAGE
    }

    /** Keep the latest node id without forcing ViewModel recreation. */
    val latestNodeIdState = rememberUpdatedState(latestNodeId)

    val speechController: SpeechController = if (voiceEnabled) {
        val factory = remember(appContext, assetPath, lang, vmSurvey) {
            WhisperSpeechController.provideFactory(
                appContext = appContext,
                assetModelPath = assetPath,
                languageCode = lang,
                onVoiceExported = onVoiceExported@{ voice ->
                    val resolvedQid =
                        voice.questionId?.takeIf { it.isNotBlank() } ?: latestNodeIdState.value

                    if (resolvedQid.isBlank()) {
                        Log.w(
                            MainActivity.TAG,
                            "onVoiceExported: missing questionId and fallback failed. file=${voice.fileName}"
                        )
                        return@onVoiceExported
                    }

                    Log.d(
                        MainActivity.TAG,
                        "onVoiceExported: q=$resolvedQid, file=${voice.fileName}, bytes=${voice.byteSize}, checksum=${voice.checksum}"
                    )

                    vmSurvey.onVoiceExported(
                        questionId = resolvedQid,
                        fileName = voice.fileName,
                        byteSize = voice.byteSize,
                        checksum = voice.checksum,
                        replace = false
                    )
                }
            )
        }

        // IMPORTANT:
        // viewModel<T>() must be parameterized with a ViewModel type.
        val whisperVm: WhisperSpeechController = viewModel(
            viewModelStoreOwner = owner,
            key = "WhisperSpeechController_${sessionId}_${assetPath}_$lang",
            factory = factory
        )

        whisperVm
    } else {
        remember {
            NoOpSpeechController("Voice input is disabled by configuration.")
        }
    }

    LaunchedEffect(sessionId, latestNode.id) {
        // Keep SpeechController in sync with the current question/node.
        runCatching { speechController.updateContext(sessionId, latestNode.id) }
    }

    DisposableEffect(speechController) {
        onDispose {
            // Best-effort: stop recording when leaving the host.
            runCatching { speechController.stopRecording() }
        }
    }

    Box(modifier = rootModifier) {
        NavDisplay(
            backStack = backStack,
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<FlowHome> {
                    HomeScreen(
                        onStart = {
                            Log.d(MainActivity.TAG, "Home -> Start survey. session=$sessionId")
                            vmSurvey.resetToStart()
                            vmAI.resetStates(keepError = false)
                            vmSurvey.advanceToNext()
                        }
                    )
                }

                entry<FlowAI> {
                    val node by vmSurvey.currentNode.collectAsState()
                    AiScreen(
                        nodeId = node.id,
                        vmSurvey = vmSurvey,
                        vmAI = vmAI,
                        onNext = { vmSurvey.advanceToNext() },
                        onBack = { vmSurvey.backToPrevious() },
                        speechController = speechController
                    )
                }

                entry<FlowReview> {
                    ReviewScreen(
                        vm = vmSurvey,
                        onNext = { vmSurvey.advanceToNext() },
                        onBack = { vmSurvey.backToPrevious() }
                    )
                }

                entry<FlowDone> {
                    val gh = if (BuildConfig.GH_TOKEN.isNotEmpty()) {
                        GitHubUploader.GitHubConfig(
                            owner = BuildConfig.GH_OWNER,
                            repo = BuildConfig.GH_REPO,
                            branch = BuildConfig.GH_BRANCH,
                            pathPrefix = BuildConfig.GH_PATH_PREFIX,
                            token = BuildConfig.GH_TOKEN
                        )
                    } else {
                        null
                    }

                    DoneScreen(
                        vm = vmSurvey,
                        onRestart = {
                            Log.d(MainActivity.TAG, "Done -> Restart requested (return to selector)")
                            vmAI.resetStates()
                            vmSurvey.resetToStart()
                            onResetToSelector()
                        },
                        gitHubConfig = gh
                    )
                }
            }
        )
    }

    BackHandler(enabled = canGoBack) {
        Log.d(MainActivity.TAG, "BackHandler -> backToPrevious. session=$sessionId voice=$voiceEnabled")
        runCatching { speechController.stopRecording() }
        vmAI.resetStates()
        vmSurvey.backToPrevious()
    }
}

/* ───────────────────────────── Home Screen ───────────────────────────── */

@Composable
private fun HomeScreen(
    onStart: () -> Unit
) {
    val backplate = appBackplate()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .background(backplate)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            modifier = Modifier
                .wrapContentWidth()
                .neonEdgeThin()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Survey ready",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Tap Start to begin answering the configured survey.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onStart) {
                    Text("Start survey")
                }
            }
        }
    }
}

/* ───────────────────────────── Config UI Helpers ───────────────────────────── */

private fun configOptionFromFileName(fileName: String): ConfigOptionUi {
    val stem = fileName.removeSuffix(".yaml").removeSuffix(".yml")
    val lower = stem.lowercase(Locale.US)

    val isDemo = "demo" in lower
    val isFull = "full" in lower
    val isFaw = ("faw" in lower) || ("fall_armyworm" in lower) || ("armyworm" in lower)

    val configNumber = Regex("""(?:^|_)survey_config(\d+)(?:_|$)""")
        .find(lower)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    val baseLabel = when {
        isDemo -> "Demo config"
        isFull -> "Full config"
        configNumber != null -> "Config $configNumber"
        else -> prettyNameFromFileStem(stem)
    }

    val label = if (isFaw && !baseLabel.contains("FAW")) {
        "$baseLabel — FAW"
    } else {
        baseLabel
    }

    val pretty = prettyNameFromFileStem(stem)
    val description = buildString {
        if (isFaw) append("FAW survey configuration. ") else append("Survey configuration. ")
        if (pretty.isNotBlank()) append("“$pretty”. ")
        append("Loaded from $fileName.")
    }

    return ConfigOptionUi(
        id = fileName,
        label = label,
        description = description
    )
}

private fun prettyNameFromFileStem(stem: String): String {
    val tokens = stem
        .replace('-', '_')
        .split('_')
        .filter { it.isNotBlank() }
        .filterNot { t ->
            val x = t.lowercase(Locale.US)
            x == "survey" ||
                    x == "config" ||
                    x == "configs" ||
                    x == "followup" ||
                    x == "followups" ||
                    x == "fu"
        }

    if (tokens.isEmpty()) return stem

    return tokens.joinToString(" ") { token ->
        token.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase() else ch.toString()
        }
    }
}

private fun listAssetYamlConfigs(assetManager: AssetManager): List<String> {
    val roots = listOf("", "configs", "surveys")
    val out = mutableListOf<String>()

    fun walk(dir: String, depth: Int) {
        if (depth > 2) return

        val items = runCatching { assetManager.list(dir) }.getOrNull() ?: return
        for (name in items) {
            val path = if (dir.isBlank()) name else "$dir/$name"

            if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                out += path
                continue
            }

            // AssetManager.list() does not clearly distinguish files vs dirs; attempt to descend.
            walk(path, depth + 1)
        }
    }

    roots.forEach { walk(it, 0) }

    return out.distinct()
}

/* ───────────────────────────── Config Details Resolver ───────────────────────────── */

/**
 * Load config YAML text from assets and extract helpful meta (slm: section).
 *
 * Notes:
 * - No YAML library needed.
 * - Best-effort: meta parsing can fail safely; longText still shows the real YAML.
 */
private suspend fun resolveConfigDetailsFromAssets(
    context: Context,
    configId: String
): ScreenConfigDetails = withContext(Dispatchers.IO) {
    val base = configId.trim().trimStart('/')

    val hasExt =
        base.endsWith(".yaml", ignoreCase = true) || base.endsWith(".yml", ignoreCase = true)

    val candidates = buildList {
        add(base)

        if (!hasExt) {
            add("$base.yaml")
            add("$base.yml")
        }

        if (!base.startsWith("configs/")) {
            add("configs/$base")
            if (!hasExt) {
                add("configs/$base.yaml")
                add("configs/$base.yml")
            }
        }

        if (!base.startsWith("surveys/")) {
            add("surveys/$base")
            if (!hasExt) {
                add("surveys/$base.yaml")
                add("surveys/$base.yml")
            }
        }
    }.distinct()

    val (assetName, yamlText) = readFirstAssetText(context.assets, candidates)
    val meta = extractSlmMeta(yamlText)

    val summary = buildString {
        val model = meta["model_name"] ?: meta["model"] ?: meta["modelName"]
        val backend = meta["backend"]
        val accel = meta["accelerator"] ?: meta["device"]
        val maxTokens = meta["max_tokens"] ?: meta["maxTokens"]
        val topK = meta["top_k"] ?: meta["topK"]
        val topP = meta["top_p"] ?: meta["topP"]
        val temp = meta["temperature"]

        if (!model.isNullOrBlank()) append("model=$model  ")
        if (!backend.isNullOrBlank()) append("backend=$backend  ")
        if (!accel.isNullOrBlank()) append("accel=$accel  ")
        if (!maxTokens.isNullOrBlank()) append("maxTokens=$maxTokens  ")
        if (!topK.isNullOrBlank()) append("topK=$topK  ")
        if (!topP.isNullOrBlank()) append("topP=$topP  ")
        if (!temp.isNullOrBlank()) append("temp=$temp")
        if (isBlank()) append("Loaded from assets: $assetName")
    }.trim()

    ScreenConfigDetails(
        title = assetName,
        summary = summary,
        longText = yamlText,
        meta = meta
    )
}

private fun readFirstAssetText(
    assets: AssetManager,
    candidates: List<String>
): Pair<String, String> {
    var lastErr: Throwable? = null
    for (name in candidates) {
        try {
            val text = assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return name to text
        } catch (t: Throwable) {
            lastErr = t
        }
    }
    throw IllegalArgumentException(
        "Config asset not found. Tried: ${candidates.joinToString()}",
        lastErr
    )
}

/**
 * Heuristically extract key/value pairs under the "slm:" section.
 *
 * This is intentionally lightweight:
 * - Not a full YAML parser.
 * - Handles common "key: value" lines in a nested block.
 */
private fun extractSlmMeta(yamlText: String): Map<String, String> {
    val lines = yamlText.lines()

    var inSlm = false
    var slmIndent: Int? = null
    val meta = linkedMapOf<String, String>()

    fun leadingSpacesOrTabs(s: String): Int =
        s.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) s.length else it }

    for (raw in lines) {
        val lineNoComment = raw.substringBefore("#")
        if (lineNoComment.isBlank()) continue

        val indent = leadingSpacesOrTabs(lineNoComment)
        val trimmed = lineNoComment.trim()

        if (trimmed.endsWith(":") && !trimmed.contains(" ")) {
            val section = trimmed.removeSuffix(":").trim()
            if (section == "slm") {
                inSlm = true
                slmIndent = indent
            } else {
                if (inSlm && slmIndent != null && indent <= slmIndent) {
                    inSlm = false
                    slmIndent = null
                }
            }
            continue
        }

        if (!inSlm || slmIndent == null) continue
        if (indent <= slmIndent) {
            inSlm = false
            slmIndent = null
            continue
        }

        val idx = trimmed.indexOf(":")
        if (idx <= 0) continue

        val k = trimmed.take(idx).trim()
        val vRaw = trimmed.substring(idx + 1).trim()
        if (k.isBlank() || vRaw.isBlank()) continue

        val v = vRaw
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()

        meta[k] = v
    }

    return meta
}

/* ───────────────────────────── Placeholder Screen ───────────────────────────── */

@Composable
private fun PlaceholderScreen(
    title: String,
    subtitle: String
) {
    val backplate = appBackplate()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .background(backplate)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            modifier = Modifier
                .wrapContentWidth()
                .neonEdgeThin()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
