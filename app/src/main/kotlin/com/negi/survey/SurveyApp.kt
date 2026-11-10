/*
 * =====================================================================
 *  IshizukiTech LLC — Android App Shell
 *  ---------------------------------------------------------------------
 *  File: SurveyApp.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.survey

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.negi.survey.net.GitHubUploader
import com.negi.survey.net.RuntimeLogStore
import com.negi.survey.net.SurveyUploadFinalizer
import com.negi.survey.net.SurveyUploadRescheduler
import com.negi.survey.net.V2ArtifactDelivery
import com.negi.survey.diagnostics.V2TraceArtifacts
import com.negi.survey.diagnostics.SurveyDiagnosticAttribution
import com.negi.survey.runtime.HeavyRuntimeCoordinator
import com.negi.survey.slm.LiteRtLM
import com.negi.survey.whisper.WhisperEngine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


/**
 * Process-local startup state for survey upload recovery.
 *
 * Scheduling, execution, completion, and the single retry are tracked independently so a
 * temporary prerequisite failure cannot permanently suppress recovery for this process.
 */
internal class StartupSurveyRecoveryState {
    val scheduled = AtomicBoolean(false)
    val running = AtomicBoolean(false)
    val completed = AtomicBoolean(false)
    val retryScheduled = AtomicBoolean(false)
}

/**
 * Pure startup coordinator used by [SurveyApp] and focused JVM tests.
 *
 * Android lifecycle, WorkManager initialization, config loading, and logging stay behind the
 * narrow [Operations] boundary. The coordinator owns only startup timing and retry semantics.
 */
internal class StartupSurveyRecoveryCoordinator(
    private val state: StartupSurveyRecoveryState,
    private val operations: Operations,
    private val initialDelayMs: Long,
    private val retryDelayMs: Long,
) {
    internal interface Operations {
        fun postDelayed(delayMs: Long, block: () -> Unit)
        fun launchIo(block: () -> Unit)
        fun isWorkManagerAvailable(attempt: String): Boolean
        fun resolveConfig(): GitHubUploader.GitHubConfig?
        fun recover(config: GitHubUploader.GitHubConfig): SurveyUploadRescheduler.RecoverySummary
        fun logDebug(message: String)
        fun logWarning(message: String, throwable: Throwable? = null)
    }

    fun scheduleInitial() {
        if (!state.scheduled.compareAndSet(false, true)) {
            operations.logDebug("Startup survey recovery already scheduled; skipping.")
            return
        }

        operations.logDebug("Startup survey recovery deferred: delay=${initialDelayMs}ms")
        operations.postDelayed(initialDelayMs) {
            operations.launchIo {
                runAttempt(isRetry = false)
            }
        }
    }

    private fun runAttempt(isRetry: Boolean) {
        if (state.completed.get()) {
            operations.logDebug("Startup survey recovery already completed; skipping.")
            return
        }

        if (!state.running.compareAndSet(false, true)) {
            operations.logDebug("Startup survey recovery already running; skipping concurrent attempt.")
            return
        }

        val attempt = if (isRetry) "delayed" else "initial"

        try {
            if (!operations.isWorkManagerAvailable(attempt)) {
                operations.logWarning("Startup survey recovery: WorkManager unavailable ($attempt).")
                scheduleRetryIfAllowed(isRetry, reason = "workmanager")
                return
            }

            val config = try {
                operations.resolveConfig()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                operations.logWarning(
                    "Startup survey recovery: config lookup failed ($attempt): ${exception.message}",
                    exception,
                )
                scheduleRetryIfAllowed(isRetry, reason = "config_exception")
                return
            }

            if (config == null) {
                operations.logWarning("Startup survey recovery: GitHub config unavailable ($attempt).")
                scheduleRetryIfAllowed(isRetry, reason = "config")
                return
            }

            val summary = try {
                operations.recover(config)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                operations.logWarning(
                    "Startup survey recovery failed ($attempt): ${exception.message}",
                    exception,
                )
                scheduleRetryIfAllowed(isRetry, reason = "recovery")
                return
            }

            state.completed.set(true)
            logSummary(summary)
        } finally {
            state.running.set(false)
        }
    }

    private fun scheduleRetryIfAllowed(isRetry: Boolean, reason: String) {
        if (isRetry) {
            operations.logWarning("Startup survey recovery: retry exhausted; reason=$reason")
            return
        }

        if (!state.retryScheduled.compareAndSet(false, true)) {
            operations.logDebug("Startup survey recovery: retry already scheduled; reason=$reason")
            return
        }

        operations.logWarning(
            "Startup survey recovery: scheduling one retry; reason=$reason delay=${retryDelayMs}ms"
        )
        operations.postDelayed(retryDelayMs) {
            operations.launchIo {
                runAttempt(isRetry = true)
            }
        }
    }

    private fun logSummary(summary: SurveyUploadRescheduler.RecoverySummary) {
        fun count(classification: SurveyUploadRescheduler.RecoveryClassification): Int =
            summary.classificationCounts[classification] ?: 0

        val submitted = count(SurveyUploadRescheduler.RecoveryClassification.SUBMITTED)
        val active = count(SurveyUploadRescheduler.RecoveryClassification.ACTIVE)
        val trackerUnconfirmed =
            count(SurveyUploadRescheduler.RecoveryClassification.SUBMITTED_TRACKER_UNCONFIRMED)
        val alreadyUploaded =
            count(SurveyUploadRescheduler.RecoveryClassification.ALREADY_UPLOADED)
        val deferred = count(SurveyUploadRescheduler.RecoveryClassification.DEFERRED)
        val invalid = count(SurveyUploadRescheduler.RecoveryClassification.INVALID)

        val message = buildString {
            append("Startup survey recovery complete: ")
            append("discovered=${summary.discoveredSurveyCount} ")
            append("attempted=${summary.reconciledCandidateCount} ")
            append("submitted=$submitted ")
            append("active=$active ")
            append("trackerUnconfirmed=$trackerUnconfirmed ")
            append("uploaded=$alreadyUploaded ")
            append("deferred=$deferred ")
            append("invalid=$invalid ")
            append("operationalFailures=${summary.operationalFailureCount} ")
            append("duplicates=${summary.duplicateFileCount} ")
            append("unclassified=${summary.unclassifiedFileCount}")
        }

        if (summary.operationalFailureCount > 0 || invalid > 0) {
            operations.logWarning(message)
        } else {
            operations.logDebug(message)
        }
    }
}

internal fun runStartupV2Recovery(
    recoverTraces: () -> Unit,
    stageDiagnostics: () -> Unit,
    recoverIndependentArtifacts: () -> Unit,
    cleanupConfirmedSurveys: () -> Unit,
    logWarning: (String, Exception) -> Unit,
) {
    fun recover(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            logWarning("Startup V2 recovery failed during $operation", exception)
        }
    }

    recover("V2TraceArtifacts.recover", recoverTraces)
    recover("CrashCapture.stagePreviousProcessDiagnostics", stageDiagnostics)
    recover("V2ArtifactDelivery.recoverIndependent", recoverIndependentArtifacts)
    recover("V2ArtifactDelivery.cleanupConfirmedSurveys", cleanupConfirmedSurveys)
}

/**
 * Application bootstrap:
 * - Install CrashCapture early (attachBaseContext) to catch crashes during Application startup.
 * - Re-install (re-wrap) CrashCapture in onCreate to recover if another SDK overwrote the handler.
 * - Optionally register a lightweight self-healing hook to keep CrashCapture in the chain.
 * - Enqueue pending crash uploads on next start (onCreate).
 * - Run only in the main process.
 *
 * Notes:
 * - If the default WorkManager initializer is disabled, Configuration.Provider enables on-demand
 *   initialization on the first WorkManager.getInstance(context) call.
 * - Prefer enqueue from onCreate and keep WorkManager off the attachBaseContext critical path.
 * - Startup upload work is best-effort and retried once when WorkManager/config is temporarily unavailable.
 *
 * Performance note:
 * - Startup uploads (WorkManager + upload workers) can compete with LiteRT engine initialization
 *   for CPU / IO. To reduce cold-start contention, startup upload enqueues are deferred.
 */
class SurveyApp : Application(), Configuration.Provider {

    /** Process-wide owner for LiteRT-LM and Whisper native-runtime handoff. */
    val heavyRuntimeCoordinator = HeavyRuntimeCoordinator(
        releaseWhisperAction = { WhisperEngine.release() },
    )

    // Guard JNI load to avoid duplicate loads and allow retry on failure.
    private val litertJniLoadOnce = AtomicBoolean(false)

    // Process-lifetime scope for startup survey recovery work.
    private val startupSurveyRecoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun attachBaseContext(base: Context) {
        val t0 = SystemClock.elapsedRealtime()
        super.attachBaseContext(base)

        val pn = currentProcessName(base)
        val isMain = isMainProcess(base, pn)
        val pid = android.os.Process.myPid()

        if (!isMain) {
            Log.d(TAG, "attachBaseContext: pid=$pid process=${pn ?: "<unknown>"} isMain=false sdk=${Build.VERSION.SDK_INT}")
            Log.d(TAG, "Non-main process; bootstrap skipped in attachBaseContext.")
            return
        }

        val appCtx = base.applicationContext ?: base
        SurveyDiagnosticAttribution.registerProcess(appCtx)

        // Load LiteRTLM JNI as early as possible (main process only).
        ensureLiteRtLmJniLoaded(where = "attachBaseContext")

        // Start runtime log capture as early as possible (main process only).
        runCatching { RuntimeLogStore.start(appCtx) }
            .onFailure { t ->
                Log.w(TAG, "RuntimeLogStore.start failed in attachBaseContext: ${t.message}", t)
            }

        // Install ring log store as early as possible to retain pre-crash context across restarts.
        runCatching { AppRingLogStore.install(appCtx) }
            .onFailure { t ->
                RuntimeLogStore.w(TAG, "AppRingLogStore.install failed in attachBaseContext: ${t.message}", t)
            }

        logBoot("attachBaseContext", pid, pn, isMain)
        RuntimeLogStore.d(TAG, "bootTiming: attachBaseContext total=${SystemClock.elapsedRealtime() - t0}ms")

        // Guard: attachBaseContext() should run once, but OEM/SDK edge cases exist.
        if (!attachBootOnce.compareAndSet(false, true)) {
            RuntimeLogStore.d(TAG, "attachBaseContext bootstrap already executed; skipping.")
            return
        }

        // IMPORTANT:
        // Do NOT initialize WorkManager here.
        RuntimeLogStore.d(TAG, "Skipping WorkManager init in attachBaseContext; will init lazily on first enqueue.")

        // Install as early as possible to catch crashes during Application startup.
        // Do NOT force-enqueue pending uploads here; enqueue is done later (deferred) in onCreate.
        safeCrashInstall(where = "attachBaseContext", context = appCtx)
    }

    private fun ensureLiteRtLmJniLoaded(where: String) {
        if (!litertJniLoadOnce.compareAndSet(false, true)) {
            Log.d(TAG, "LiteRTLM JNI already loaded; skip. where=$where")
            return
        }

        /** Load LiteRTLM JNI library early to avoid call-before-load UnsatisfiedLinkError. */
        runCatching {
            System.loadLibrary("litertlm_jni")
            Log.d(TAG, "Loaded LiteRTLM JNI: litertlm_jni where=$where")
        }.onFailure { t ->
            // Allow retry later if the first attempt fails.
            litertJniLoadOnce.set(false)
            Log.e(TAG, "Failed to load LiteRTLM JNI (litertlm_jni) where=$where", t)

            // If RuntimeLogStore is already active, also mirror the error there.
            runCatching {
                RuntimeLogStore.e(TAG, "Failed to load LiteRTLM JNI where=$where: ${t.message}")
            }
        }
    }

    override fun onCreate() {
        val t0 = SystemClock.elapsedRealtime()
        super.onCreate()

        val pn = currentProcessName(this)
        val isMain = isMainProcess(this, pn)
        val pid = android.os.Process.myPid()

        if (!isMain) {
            Log.d(TAG, "onCreate: pid=$pid process=${pn ?: "<unknown>"} isMain=false sdk=${Build.VERSION.SDK_INT}")
            Log.d(TAG, "Non-main process; bootstrap skipped in onCreate.")
            return
        }

        val appCtx = applicationContext ?: this
        // Retry JNI loading here if the early attachBaseContext attempt failed.
        // The AtomicBoolean is reset on failure, so this is a real second chance.
        ensureLiteRtLmJniLoaded(where = "onCreate(retry)")

        // Ensure runtime log capture is running (idempotent).
        val tRt0 = SystemClock.elapsedRealtime()
        runCatching { RuntimeLogStore.start(appCtx) }
            .onFailure { t ->
                Log.w(TAG, "RuntimeLogStore.start failed in onCreate: ${t.message}", t)
            }
        RuntimeLogStore.d(TAG, "bootTiming: RuntimeLogStore.start took=${SystemClock.elapsedRealtime() - tRt0}ms")

        // Ensure ring log store is installed (idempotent).
        val tRing0 = SystemClock.elapsedRealtime()
        runCatching { AppRingLogStore.install(appCtx) }
            .onFailure { t ->
                RuntimeLogStore.w(TAG, "AppRingLogStore.install failed in onCreate: ${t.message}", t)
            }
        RuntimeLogStore.d(TAG, "bootTiming: AppRingLogStore.install took=${SystemClock.elapsedRealtime() - tRing0}ms")

        logBoot("onCreate", pid, pn, isMain)

        // Guard: onCreate() should run once per process, but keep it defensive.
        if (!onCreateBootOnce.compareAndSet(false, true)) {
            RuntimeLogStore.d(TAG, "onCreate bootstrap already executed; skipping.")
            return
        }

        // Keep any global singletons ready early.
        runCatching { LiteRtLM.setApplicationContext(this) }
            .onFailure { t ->
                RuntimeLogStore.w(TAG, "LiteRtLM.setApplicationContext failed: ${t.message}", t)
            }

        // Re-wrap default handler in case any SDK replaced it after attachBaseContext().
        safeCrashInstall(where = "onCreate(rewrap)", context = appCtx)

        // Optional: self-heal if another SDK overwrites the handler later in runtime.
        safeRegisterSelfHealingOnce(this)

        // Local recovery does not require a connection or upload credentials.
        startupSurveyRecoveryScope.launch {
            runStartupV2Recovery(
                recoverTraces = { V2TraceArtifacts.recover(appCtx) },
                stageDiagnostics = { CrashCapture.stagePreviousProcessDiagnostics(appCtx, "SurveyApp:onCreate") },
                recoverIndependentArtifacts = { V2ArtifactDelivery.recoverIndependent(appCtx, null) },
                cleanupConfirmedSurveys = { V2ArtifactDelivery.cleanupConfirmedSurveys(appCtx) },
                logWarning = { message, exception -> RuntimeLogStore.w(TAG, message, exception) },
            )
            try {
                SurveyUploadFinalizer(appCtx).recoverAbandonedRecordings(appCtx)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                RuntimeLogStore.w(TAG, "Prior-process recording recovery failed", failure)
            }
            scheduleStartupSurveyRecoveryOnce(appCtx)
        }

        RuntimeLogStore.d(TAG, "bootTiming: onCreate total=${SystemClock.elapsedRealtime() - t0}ms")
    }

    /**
     * WorkManager Configuration provider.
     */
    override val workManagerConfiguration: Configuration by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .setDefaultProcessName(packageName)
            .build()
    }


    /**
     * Schedules survey-result recovery once per application process.
     *
     * The delayed callback stays on the main handler, while discovery/reconciliation runs on the
     * application-owned IO scope.
     */
    private fun scheduleStartupSurveyRecoveryOnce(context: Context) {
        val appCtx = context.applicationContext ?: context
        StartupSurveyRecoveryCoordinator(
            state = startupSurveyRecoveryState,
            operations = AndroidStartupSurveyRecoveryOperations(appCtx),
            initialDelayMs = STARTUP_DEFERRED_ENQUEUE_DELAY_MS,
            retryDelayMs = STARTUP_ENQUEUE_RETRY_DELAY_MS,
        ).scheduleInitial()
    }

    /**
     * Narrow readiness check for survey startup recovery.
     *
     * Existing diagnostic callers retain [ensureWorkManagerAvailable] unchanged. This boundary
     * intentionally does not convert [Error] subclasses into a normal "unavailable" result.
     */
    private fun ensureSurveyRecoveryWorkManagerAvailable(where: String, ctx: Context): Boolean {
        val appCtx = ctx.applicationContext ?: ctx
        val t0 = SystemClock.elapsedRealtime()

        return try {
            WorkManager.getInstance(appCtx)
            RuntimeLogStore.d(
                TAG,
                "Survey recovery WorkManager available. where=$where " +
                        "took=${SystemClock.elapsedRealtime() - t0}ms"
            )
            true
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            RuntimeLogStore.w(
                TAG,
                "Survey recovery WorkManager interrupted. where=$where msg=${interrupted.message}",
                interrupted,
            )
            false
        } catch (exception: Exception) {
            RuntimeLogStore.w(
                TAG,
                "Survey recovery WorkManager unavailable. where=$where msg=${exception.message}",
                exception,
            )
            false
        }
    }

    /** Android adapter for the pure startup survey-recovery coordinator. */
    private inner class AndroidStartupSurveyRecoveryOperations(
        private val appContext: Context,
    ) : StartupSurveyRecoveryCoordinator.Operations {
        override fun postDelayed(delayMs: Long, block: () -> Unit) {
            mainHandler.postDelayed({ block() }, delayMs)
        }

        override fun launchIo(block: () -> Unit) {
            startupSurveyRecoveryScope.launch {
                block()
            }
        }

        override fun isWorkManagerAvailable(attempt: String): Boolean =
            ensureSurveyRecoveryWorkManagerAvailable(
                where = "startupSurveyUploads($attempt)",
                ctx = appContext,
            )

        override fun resolveConfig(): GitHubUploader.GitHubConfig? {
            V2ArtifactDelivery.scheduleRecovery(appContext)
            return V2ArtifactDelivery.config(appContext)
        }

        override fun recover(
            config: GitHubUploader.GitHubConfig
        ): SurveyUploadRescheduler.RecoverySummary =
            SurveyUploadRescheduler.recoverPendingSurveyUploads(
                context = appContext,
                config = config,
            )

        override fun logDebug(message: String) {
            RuntimeLogStore.d(TAG, message)
        }

        override fun logWarning(message: String, throwable: Throwable?) {
            RuntimeLogStore.w(TAG, message, throwable)
        }
    }

    private fun currentProcessName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { android.os.Process.myProcessName() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        // Public Linux procfs fallback for older Android versions.
        runCatching {
            val bytes = File("/proc/self/cmdline")
                .inputStream()
                .use { it.readBytes() }

            bytes
                .takeWhile { it.toInt() != 0 }
                .toByteArray()
                .toString(Charsets.UTF_8)
                .trim()
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // Last-resort framework lookup.
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val pid = android.os.Process.myPid()
            am?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return null
    }

    private fun isMainProcess(context: Context, processName: String?): Boolean {
        val pn = processName?.trim().orEmpty()
        if (pn.isEmpty()) return true
        return pn == context.packageName
    }

    private fun safeCrashInstall(where: String, context: Context) {
        val appCtx = context.applicationContext ?: context
        val label = "SurveyApp:$where"
        runCatching {
            CrashCapture.install(appCtx, where = label)
            RuntimeLogStore.d(TAG, "CrashCapture installed: where=$label")
        }.onFailure { t ->
            RuntimeLogStore.w(TAG, "CrashCapture.install failed: where=$label msg=${t.message}", t)
        }
    }

    private fun safeRegisterSelfHealingOnce(app: Application) {
        if (!selfHealOnce.compareAndSet(false, true)) {
            RuntimeLogStore.d(TAG, "CrashCapture self-healing already registered; skipping.")
            return
        }
        runCatching {
            CrashCapture.registerSelfHealing(app)
            RuntimeLogStore.d(TAG, "CrashCapture self-healing registered.")
        }.onFailure { t ->
            RuntimeLogStore.w(TAG, "CrashCapture.registerSelfHealing failed: ${t.message}", t)
        }
    }

    private fun logBoot(stage: String, pid: Int, processName: String?, isMain: Boolean) {
        val pn = processName?.takeIf { it.isNotBlank() } ?: "<unknown>"
        val msg = "$stage: pid=$pid process=$pn isMain=$isMain sdk=${Build.VERSION.SDK_INT}"
        RuntimeLogStore.d(TAG, msg)
        runCatching { AppRingLogStore.log("D", TAG, msg) }
    }


    companion object {
        private const val TAG = "SurveyApp"


        private const val STARTUP_ENQUEUE_RETRY_DELAY_MS = 1600L

        // New: defer startup enqueues to reduce contention with model initialization.
        private const val STARTUP_DEFERRED_ENQUEUE_DELAY_MS = 2500L

        // Process-level guards (single source of truth).
        private val attachBootOnce = AtomicBoolean(false)
        private val onCreateBootOnce = AtomicBoolean(false)
        private val selfHealOnce = AtomicBoolean(false)


        private val startupSurveyRecoveryState = StartupSurveyRecoveryState()



        private val mainHandler: Handler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            Handler(Looper.getMainLooper())
        }
    }
}
