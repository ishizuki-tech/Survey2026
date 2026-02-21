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
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.negi.survey.net.GitHubUploadWorker
import com.negi.survey.net.GitHubUploader
import com.negi.survey.net.RuntimeLogStore
import com.negi.survey.slm.LiteRtLM
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Application bootstrap:
 * - Install CrashCapture early (attachBaseContext) to catch crashes during Application startup.
 * - Re-install (re-wrap) CrashCapture in onCreate to recover if another SDK overwrote the handler.
 * - Optionally register a lightweight self-healing hook to keep CrashCapture in the chain.
 * - Enqueue pending crash uploads on next start (onCreate).
 * - Run only in the main process.
 *
 * Notes:
 * - WorkManagerInitializer may be disabled in the manifest; initialize WorkManager manually.
 * - WorkManager may not be ready early on some devices/entry points; always guard getInstance().
 * - Prefer enqueue from onCreate, but initialize WM in onCreate to avoid attachBaseContext edge crashes.
 *
 * Runtime logs:
 * - Start RuntimeLogStore early to capture app-owned logs to files (uploadable).
 * - Use RuntimeLogStore.* for logs that must be included in runtime log bundles.
 *
 * Ring logs:
 * - Install AppRingLogStore as early as possible to reliably retain "pre-crash" context across restarts.
 * - On app start, enqueue a WorkManager job to upload the ring "segments" (seg_XX.log) to GitHub.
 *
 * Startup upload:
 * - On app start, enqueue WorkManager jobs to upload:
 *   1) RuntimeLogStore logs bundle (plain session folder)
 *   2) AppRingLogStore ring segments (full ring body)
 * - Config retrieval is best-effort via reflection to avoid hard coupling.
 */
class SurveyApp : Application(), Configuration.Provider {

    override fun attachBaseContext(base: Context) {
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

        // Guard: attachBaseContext() should run once, but OEM/SDK edge cases exist.
        if (!attachBootOnce.compareAndSet(false, true)) {
            RuntimeLogStore.d(TAG, "attachBaseContext bootstrap already executed; skipping.")
            return
        }

        // IMPORTANT:
        // Do NOT initialize WorkManager here.
        // Some devices/OS versions crash because WorkManager treats the context as null
        // during early attachBaseContext phase.
        RuntimeLogStore.d(TAG, "Skipping WorkManager init in attachBaseContext; will init in onCreate.")

        // Install as early as possible to catch crashes during Application startup.
        // Do NOT force-enqueue pending uploads here; enqueue is done in onCreate.
        safeCrashInstall(where = "attachBaseContext", context = appCtx)
    }

    override fun onCreate() {
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

        // Ensure runtime log capture is running (idempotent).
        runCatching { RuntimeLogStore.start(appCtx) }
            .onFailure { t ->
                Log.w(TAG, "RuntimeLogStore.start failed in onCreate: ${t.message}", t)
            }

        // Ensure ring log store is installed (idempotent).
        runCatching { AppRingLogStore.install(appCtx) }
            .onFailure { t ->
                RuntimeLogStore.w(TAG, "AppRingLogStore.install failed in onCreate: ${t.message}", t)
            }

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

        // Ensure WorkManager is initialized (idempotent).
        ensureWorkManagerInitialized(where = "onCreate", ctx = appCtx)

        // Startup: enqueue runtime log upload (best-effort, main process only).
        safeEnqueueStartupRuntimeLogsUploadOnce(appCtx)

        // Startup: enqueue ring segment upload (best-effort, main process only).
        safeEnqueueStartupRingLogsUploadOnce(appCtx)

        // Re-wrap default handler in case any SDK replaced it after attachBaseContext().
        safeCrashInstall(where = "onCreate(rewrap)", context = appCtx)

        // Optional: self-heal if another SDK overwrites the handler later in runtime.
        safeRegisterSelfHealingOnce(this)

        // Enqueue pending crash uploads from previous run (best-effort).
        // A-Plan: schedule delayed retry ONLY if WorkManager isn't ready yet.
        safeEnqueuePendingUploadsWithRetryOnce(appCtx)
    }

    /**
     * WorkManager Configuration provider.
     *
     * Even if WorkManagerInitializer is disabled and we call WorkManager.initialize() manually,
     * providing a Configuration keeps behavior consistent and supports advanced setups later
     * (e.g., setWorkerFactory, custom executor, etc.).
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .setDefaultProcessName(packageName)
            .build()

    /**
     * Ensure WorkManager is initialized.
     *
     * This is required when WorkManagerInitializer is explicitly disabled in AndroidManifest.xml.
     *
     * Key properties:
     * - Safe to call multiple times.
     * - Protects against concurrent init attempts using a lock.
     * - Re-attempts init if WorkManager is still not accessible.
     */
    private fun ensureWorkManagerInitialized(where: String, ctx: Context) {
        val appCtx = ctx.applicationContext ?: ctx

        // Fast-path: if already accessible, do nothing.
        if (isWorkManagerReady(appCtx)) {
            RuntimeLogStore.d(TAG, "WorkManager already ready. where=$where")
            return
        }

        // Prevent concurrent init attempts from different stages/threads.
        synchronized(workManagerInitLock) {
            // Re-check inside the lock (another caller may have initialized it).
            if (isWorkManagerReady(appCtx)) {
                RuntimeLogStore.d(TAG, "WorkManager became ready (after lock). where=$where")
                return
            }

            if (workManagerInitAttempted.compareAndSet(false, true)) {
                RuntimeLogStore.d(TAG, "WorkManager init attempt begins. where=$where")
            } else {
                RuntimeLogStore.d(TAG, "WorkManager init re-attempt begins. where=$where")
            }

            runCatching {
                WorkManager.initialize(appCtx, workManagerConfiguration)
                RuntimeLogStore.d(TAG, "WorkManager initialized manually. where=$where")
            }.onFailure { t ->
                // If already initialized, WorkManager may throw. That's fine; we still verify readiness below.
                RuntimeLogStore.w(TAG, "WorkManager manual init failed: where=$where msg=${t.message}", t)
            }

            if (!isWorkManagerReady(appCtx)) {
                RuntimeLogStore.w(TAG, "WorkManager still not ready after init attempt. where=$where")
            }
        }
    }

    /**
     * Returns true if WorkManager.getInstance() works without throwing.
     */
    private fun isWorkManagerReady(ctx: Context): Boolean {
        return try {
            WorkManager.getInstance(ctx)
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * On app start, upload remaining RuntimeLogStore logs to GitHub.
     */
    private fun safeEnqueueStartupRuntimeLogsUploadOnce(context: Context) {
        if (!startupRtLogsOnce.compareAndSet(false, true)) {
            RuntimeLogStore.d(TAG, "Startup runtime logs enqueue already executed; skipping.")
            return
        }

        val appCtx = context.applicationContext ?: context

        // WM must be ready before enqueue.
        ensureWorkManagerInitialized(where = "startupRtLogs", ctx = appCtx)

        val cfg = runCatching { tryLoadGitHubConfigBestEffort(appCtx) }
            .onFailure { t ->
                RuntimeLogStore.w(TAG, "Startup runtime logs: config lookup failed: ${t.message}", t)
            }
            .getOrNull()

        if (cfg == null || cfg.owner.isBlank() || cfg.repo.isBlank() || cfg.token.isBlank()) {
            RuntimeLogStore.d(TAG, "Startup runtime logs: GitHub config not available; skip enqueue.")
            return
        }

        runCatching {
            GitHubUploadWorker.enqueueStartupRuntimeLogsUpload(
                context = appCtx,
                cfg = cfg,
                remoteDir = "diagnostics/runtime_logs",
                addDateSubdir = true,
                reason = "app_start",
                deleteZipAfter = true,
                // NOTE: maxZipBytes is treated as per-file max bytes hint in plain mode.
                maxZipBytes = 1_000_000L,
                // NEW: delete uploaded source .log files from device (excluding active).
                deleteSourceAfterUpload = true
            )
            RuntimeLogStore.d(TAG, "Startup runtime logs: enqueue requested.")
        }.onFailure { t ->
            RuntimeLogStore.w(TAG, "Startup runtime logs: enqueue failed: ${t.confirmedMsg()}", t)
        }
    }

    /**
     * On app start, upload AppRingLogStore ring segments (seg_XX.log) to GitHub.
     *
     * Notes:
     * - The ring is a safety buffer for "pre-crash context", so we DO NOT delete segments on device.
     * - Upload is best-effort and requires GitHub config to be available.
     */
    private fun safeEnqueueStartupRingLogsUploadOnce(context: Context) {
        if (!startupRingLogsOnce.compareAndSet(false, true)) {
            RuntimeLogStore.d(TAG, "Startup ring logs enqueue already executed; skipping.")
            return
        }

        val appCtx = context.applicationContext ?: context

        // WM must be ready before enqueue.
        ensureWorkManagerInitialized(where = "startupRingLogs", ctx = appCtx)

        val cfg = runCatching { tryLoadGitHubConfigBestEffort(appCtx) }
            .onFailure { t ->
                RuntimeLogStore.w(TAG, "Startup ring logs: config lookup failed: ${t.message}", t)
            }
            .getOrNull()

        if (cfg == null || cfg.owner.isBlank() || cfg.repo.isBlank() || cfg.token.isBlank()) {
            RuntimeLogStore.d(TAG, "Startup ring logs: GitHub config not available; skip enqueue.")
            return
        }

        runCatching {
            GitHubUploadWorker.enqueueStartupRingLogsUpload(
                context = appCtx,
                cfg = cfg,
                remoteDir = "diagnostics/applog_ring",
                addDateSubdir = true,
                reason = "app_start"
            )
            RuntimeLogStore.d(TAG, "Startup ring logs: enqueue requested.")
        }.onFailure { t ->
            RuntimeLogStore.w(TAG, "Startup ring logs: enqueue failed: ${t.confirmedMsg()}", t)
        }
    }

    /**
     * Best-effort GitHub config retrieval without hard dependency on a config store implementation.
     */
    private fun tryLoadGitHubConfigBestEffort(context: Context): GitHubUploader.GitHubConfig? {
        val candidates = listOf(
            "com.negi.survey.net.GitHubDiagnosticsConfigStore",
            "com.negi.survey.net.GitHubDiagnosticsConfig",
            "com.negi.survey.net.GitHubConfigStore"
        )

        val methodNames = listOf(
            "getGitHubConfig",
            "loadGitHubConfig",
            "readGitHubConfig",
            "getConfig",
            "load",
            "get",
            "read"
        )

        for (className in candidates) {
            val cls = runCatching { Class.forName(className) }.getOrNull() ?: continue

            // Try Kotlin object INSTANCE first.
            val receiver: Any? = runCatching {
                cls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            }.getOrNull()

            // 1) Try methods that accept (Context)
            for (mn in methodNames) {
                val m = runCatching { cls.methods.firstOrNull { it.name == mn && it.parameterTypes.size == 1 } }.getOrNull()
                if (m != null) {
                    val pt = m.parameterTypes[0]
                    if (Context::class.java.isAssignableFrom(pt)) {
                        val out = runCatching { m.invoke(receiver, context) }.getOrNull()
                        parseGitHubConfigFromAny(out)?.let { return it }
                    }
                }
            }

            // 2) Try parameterless methods
            for (mn in methodNames) {
                val m = runCatching { cls.methods.firstOrNull { it.name == mn && it.parameterTypes.isEmpty() } }.getOrNull()
                if (m != null) {
                    val out = runCatching { m.invoke(receiver) }.getOrNull()
                    parseGitHubConfigFromAny(out)?.let { return it }
                }
            }
        }

        return null
    }

    /**
     * Convert unknown shapes into GitHubUploader.GitHubConfig if possible.
     */
    private fun parseGitHubConfigFromAny(any: Any?): GitHubUploader.GitHubConfig? {
        if (any == null) return null
        if (any is GitHubUploader.GitHubConfig) return any

        // Map-like (java.util.Map)
        if (any is Map<*, *>) {
            val owner = any["owner"]?.toString().orEmpty()
            val repo = any["repo"]?.toString().orEmpty()
            val token = any["token"]?.toString().orEmpty()
            val branch = any["branch"]?.toString().takeIf { !it.isNullOrBlank() } ?: "main"
            val pathPrefix = any["pathPrefix"]?.toString().orEmpty()
            if (owner.isBlank() || repo.isBlank() || token.isBlank()) return null
            return GitHubUploader.GitHubConfig(
                owner = owner,
                repo = repo,
                token = token,
                branch = branch,
                pathPrefix = pathPrefix
            )
        }

        // Plain object with fields/getters.
        fun readProp(name: String): String? {
            val getter = runCatching {
                any.javaClass.methods.firstOrNull {
                    it.parameterTypes.isEmpty() && (it.name.equals(name, true) ||
                            it.name.equals("get${name.replaceFirstChar { c -> c.uppercaseChar() }}", true))
                }
            }.getOrNull()

            val gv = runCatching { getter?.invoke(any)?.toString() }.getOrNull()
            if (!gv.isNullOrBlank()) return gv

            val fv = runCatching {
                any.javaClass.declaredFields.firstOrNull { it.name.equals(name, true) }
                    ?.apply { isAccessible = true }
                    ?.get(any)
                    ?.toString()
            }.getOrNull()

            return fv
        }

        val owner = readProp("owner").orEmpty()
        val repo = readProp("repo").orEmpty()
        val token = readProp("token").orEmpty()
        val branch = readProp("branch")?.takeIf { it.isNotBlank() } ?: "main"
        val pathPrefix = readProp("pathPrefix").orEmpty()

        if (owner.isBlank() || repo.isBlank() || token.isBlank()) return null

        return GitHubUploader.GitHubConfig(
            owner = owner,
            repo = repo,
            token = token,
            branch = branch,
            pathPrefix = pathPrefix
        )
    }

    /**
     * Best-effort current process name.
     */
    private fun currentProcessName(context: Context): String? {
        // 1) API 28+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        // 2) Reflection: ActivityThread.currentProcessName()
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val m = at.getDeclaredMethod("currentProcessName")
            m.invoke(null) as? String
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // 3) /proc/self/cmdline
        runCatching {
            val bytes = File("/proc/self/cmdline")
                .inputStream()
                .use { it.readBytes() }

            val cmd = bytes
                .takeWhile { it.toInt() != 0 }
                .toByteArray()
                .toString(Charsets.UTF_8)
                .trim()

            cmd
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // 4) ActivityManager fallback
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val pid = android.os.Process.myPid()
            val procs = am?.runningAppProcesses
            procs?.firstOrNull { it.pid == pid }?.processName
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return null
    }

    /**
     * Returns true if running in the main app process.
     */
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

    /**
     * Register self-healing hook (Application required by CrashCapture API).
     */
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

    private fun safeEnqueuePendingUploadsWithRetryOnce(context: Context) {
        if (!enqueuePendingOnce.compareAndSet(false, true)) {
            RuntimeLogStore.d(TAG, "CrashCapture pending enqueue already executed; skipping.")
            return
        }

        val appCtx = context.applicationContext ?: context

        // Ensure WM is initialized before enqueue (idempotent).
        ensureWorkManagerInitialized(where = "enqueuePending(immediate)", ctx = appCtx)

        // If WM isn't ready yet, schedule delayed retry (do NOT rely on exceptions from CrashCapture).
        if (!isWorkManagerReady(appCtx)) {
            RuntimeLogStore.w(TAG, "WorkManager not ready yet; scheduling delayed enqueue retry.")

            if (enqueueRetryScheduled.compareAndSet(false, true)) {
                Handler(Looper.getMainLooper()).postDelayed(
                    {
                        ensureWorkManagerInitialized(where = "enqueuePending(delayed)", ctx = appCtx)

                        if (!isWorkManagerReady(appCtx)) {
                            RuntimeLogStore.w(TAG, "WorkManager still not ready in delayed retry; skipping enqueue.")
                            return@postDelayed
                        }

                        runCatching {
                            CrashCapture.enqueuePendingCrashUploadsIfPossible(appCtx, where = "SurveyApp:enqueuePending(delayed)")
                            RuntimeLogStore.d(TAG, "CrashCapture pending uploads requested (delayed retry).")
                            lastEnqueueAtUptimeMs.set(android.os.SystemClock.uptimeMillis())
                        }.onFailure { t ->
                            RuntimeLogStore.w(TAG, "CrashCapture.enqueuePendingCrashUploads(delayed) failed: ${t.message}", t)
                        }
                    },
                    ENQUEUE_RETRY_DELAY_MS
                )
            } else {
                RuntimeLogStore.d(TAG, "Delayed enqueue retry already scheduled; skipping.")
            }
            return
        }

        // Immediate attempt (WM ready).
        runCatching {
            CrashCapture.enqueuePendingCrashUploadsIfPossible(appCtx, where = "SurveyApp:enqueuePending(immediate)")
            RuntimeLogStore.d(TAG, "CrashCapture pending uploads requested (immediate).")
            lastEnqueueAtUptimeMs.set(android.os.SystemClock.uptimeMillis())
        }.onFailure { t ->
            RuntimeLogStore.w(TAG, "CrashCapture.enqueuePendingCrashUploads(immediate) failed: ${t.message}", t)
        }
    }

    private fun logBoot(stage: String, pid: Int, processName: String?, isMain: Boolean) {
        val pn = processName?.takeIf { it.isNotBlank() } ?: "<unknown>"
        val msg = "$stage: pid=$pid process=$pn isMain=$isMain sdk=${Build.VERSION.SDK_INT}"
        RuntimeLogStore.d(TAG, msg)

        // Also send boot markers into the ring log store (reliable "pre-crash" context).
        runCatching { AppRingLogStore.log("D", TAG, msg) }
    }

    private fun Throwable.confirmedMsg(): String = message ?: "Unknown error"

    companion object {
        private const val TAG = "SurveyApp"

        // A-plan: retry exists but only used when WorkManager isn't ready yet.
        private const val ENQUEUE_RETRY_DELAY_MS = 1600L

        // Process-level guards. These are static per-process.
        private val attachBootOnce = AtomicBoolean(false)
        private val onCreateBootOnce = AtomicBoolean(false)
        private val selfHealOnce = AtomicBoolean(false)
        private val enqueuePendingOnce = AtomicBoolean(false)
        private val enqueueRetryScheduled = AtomicBoolean(false)

        // Startup runtime logs enqueue guard (per process).
        private val startupRtLogsOnce = AtomicBoolean(false)

        // Startup ring logs enqueue guard (per process).
        private val startupRingLogsOnce = AtomicBoolean(false)

        // WorkManager init guards.
        private val workManagerInitAttempted = AtomicBoolean(false)
        private val workManagerInitLock = Any()

        // Debug: track when we last attempted to enqueue (uptime).
        private val lastEnqueueAtUptimeMs = AtomicLong(0L)
    }
}