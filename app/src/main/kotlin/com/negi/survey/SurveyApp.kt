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
 * - Prefer enqueue from onCreate, but initialize WM in attachBaseContext to protect CrashCapture.
 *
 * Runtime logs:
 * - Start RuntimeLogStore early to capture app-owned logs to files (uploadable).
 * - Use RuntimeLogStore.* for logs that must be included in runtime log bundles.
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

        // Start runtime log capture as early as possible (main process only).
        runCatching { RuntimeLogStore.start(base.applicationContext ?: base) }
            .onFailure { t ->
                Log.w(TAG, "RuntimeLogStore.start failed in attachBaseContext: ${t.message}", t)
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
        safeCrashInstall(where = "attachBaseContext", context = base)
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

        // Ensure runtime log capture is running (idempotent).
        runCatching { RuntimeLogStore.start(applicationContext ?: this) }
            .onFailure { t ->
                Log.w(TAG, "RuntimeLogStore.start failed in onCreate: ${t.message}", t)
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
        ensureWorkManagerInitialized(where = "onCreate", ctx = this)

        // Re-wrap default handler in case any SDK replaced it after attachBaseContext().
        safeCrashInstall(where = "onCreate(rewrap)", context = this)

        // Optional: self-heal if another SDK overwrites the handler later in runtime.
        safeRegisterSelfHealingOnce(this)

        // Enqueue pending crash uploads from previous run (best-effort).
        // A-Plan: schedule delayed retry ONLY if the immediate call fails.
        safeEnqueuePendingUploadsWithRetryOnce(this)
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
     * - NEVER assumes Application.getApplicationContext() is non-null during attachBaseContext().
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

            // Record that we attempted initialization at least once (debug/guard).
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

            // Final check (best-effort).
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
     * Best-effort current process name.
     *
     * Priority:
     * 1) API 28+ Application.getProcessName()
     * 2) ActivityThread.currentProcessName() via reflection
     * 3) /proc/self/cmdline
     * 4) ActivityManager.runningAppProcesses (legacy fallback)
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
     *
     * If process name is unavailable, default to "main" to avoid breaking app startup.
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

    /**
     * Enqueue pending crash uploads once.
     *
     * A-plan change:
     * - Do the immediate enqueue.
     * - Schedule the delayed retry ONLY if the immediate call fails (throws).
     *
     * Rationale:
     * - Avoid noisy double-enqueue on healthy boots.
     * - Still recover from "WM not ready / early cooldown / transient" failures.
     */
    private fun safeEnqueuePendingUploadsWithRetryOnce(context: Context) {
        if (!enqueuePendingOnce.compareAndSet(false, true)) {
            RuntimeLogStore.d(TAG, "CrashCapture pending enqueue already executed; skipping.")
            return
        }

        val appCtx = context.applicationContext ?: context

        // Ensure WM is initialized before enqueue (idempotent).
        ensureWorkManagerInitialized(where = "enqueuePending(immediate)", ctx = appCtx)

        // 1) Immediate attempt
        val immediateOk = runCatching {
            CrashCapture.enqueuePendingCrashUploadsIfPossible(appCtx, where = "SurveyApp:enqueuePending(immediate)")
        }.onSuccess {
            RuntimeLogStore.d(TAG, "CrashCapture pending uploads enqueued (immediate).")
            lastEnqueueAtUptimeMs.set(android.os.SystemClock.uptimeMillis())
        }.onFailure { t ->
            RuntimeLogStore.w(TAG, "CrashCapture.enqueuePendingCrashUploads(immediate) failed: ${t.message}", t)
        }.isSuccess

        // 2) Delayed retry ONLY if immediate failed
        if (!immediateOk) {
            if (enqueueRetryScheduled.compareAndSet(false, true)) {
                val now = android.os.SystemClock.uptimeMillis()
                val last = lastEnqueueAtUptimeMs.get()
                val dt = if (last > 0L) now - last else -1L
                RuntimeLogStore.d(TAG, "Scheduling delayed enqueue retry because immediate failed. dtSinceLastAttemptMs=$dt")

                Handler(Looper.getMainLooper()).postDelayed(
                    {
                        ensureWorkManagerInitialized(where = "enqueuePending(delayed)", ctx = appCtx)

                        runCatching {
                            CrashCapture.enqueuePendingCrashUploadsIfPossible(appCtx, where = "SurveyApp:enqueuePending(delayed)")
                            RuntimeLogStore.d(TAG, "CrashCapture pending uploads enqueued (delayed retry).")
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
        } else {
            RuntimeLogStore.d(TAG, "Delayed enqueue retry not scheduled (immediate succeeded).")
        }
    }

    private fun logBoot(stage: String, pid: Int, processName: String?, isMain: Boolean) {
        val pn = processName?.takeIf { it.isNotBlank() } ?: "<unknown>"
        RuntimeLogStore.d(TAG, "$stage: pid=$pid process=$pn isMain=$isMain sdk=${Build.VERSION.SDK_INT}")
    }

    companion object {
        private const val TAG = "SurveyApp"

        // A-plan: retry exists but only used on immediate failure.
        private const val ENQUEUE_RETRY_DELAY_MS = 1600L

        // Process-level guards. These are static per-process.
        private val attachBootOnce = AtomicBoolean(false)
        private val onCreateBootOnce = AtomicBoolean(false)
        private val selfHealOnce = AtomicBoolean(false)
        private val enqueuePendingOnce = AtomicBoolean(false)
        private val enqueueRetryScheduled = AtomicBoolean(false)

        // WorkManager init guards.
        private val workManagerInitAttempted = AtomicBoolean(false)
        private val workManagerInitLock = Any()

        // Debug: track when we last attempted to enqueue (uptime).
        private val lastEnqueueAtUptimeMs = AtomicLong(0L)
    }
}