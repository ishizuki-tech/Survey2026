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
import androidx.work.WorkManager
import com.negi.survey.slm.LiteRtLM
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application bootstrap:
 * - Install CrashCapture early (attachBaseContext) to catch crashes during Application startup.
 * - Re-install (re-wrap) CrashCapture in onCreate to recover if another SDK overwrote the handler.
 * - Optionally register a lightweight self-healing hook to keep CrashCapture in the chain.
 * - Enqueue pending crash uploads on next start (onCreate).
 * - Run only in the main process.
 *
 * Notes:
 * - Avoid assuming applicationContext is non-null in attachBaseContext.
 * - WorkManager may not be initialized in attachBaseContext; prefer enqueue from onCreate.
 * - Add a delayed retry to avoid cooldown edge cases when early enqueue fails before WM init.
 */
class SurveyApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        val pn = currentProcessName(base)
        val isMain = isMainProcess(base, pn)
        val pid = android.os.Process.myPid()

        logBoot("attachBaseContext", pid, pn, isMain)

        if (!isMain) {
            Log.d(TAG, "Non-main process; bootstrap skipped in attachBaseContext.")
            return
        }

        // Guard: attachBaseContext() should run once, but OEM/SDK edge cases exist.
        if (!attachBootOnce.compareAndSet(false, true)) {
            Log.d(TAG, "attachBaseContext bootstrap already executed; skipping.")
            return
        }

        // Install as early as possible to catch crashes during Application startup.
        // Do NOT force-enqueue pending uploads here; WM may not be ready yet.
        safeCrashInstall(where = "attachBaseContext", context = base)
    }

    override fun onCreate() {
        super.onCreate()

        val pn = currentProcessName(this)
        val isMain = isMainProcess(this, pn)
        val pid = android.os.Process.myPid()

        logBoot("onCreate", pid, pn, isMain)

        if (!isMain) {
            Log.d(TAG, "Non-main process; bootstrap skipped in onCreate.")
            return
        }

        // Guard: onCreate() should run once per process, but keep it defensive.
        if (!onCreateBootOnce.compareAndSet(false, true)) {
            Log.d(TAG, "onCreate bootstrap already executed; skipping.")
            return
        }

        // Keep any global singletons ready early.
        runCatching { LiteRtLM.setApplicationContext(this) }
            .onFailure { t ->
                Log.w(TAG, "LiteRtLM.setApplicationContext failed: ${t.message}", t)
            }

        // Prime WorkManager early (best-effort) so crash upload enqueue won't be a no-op.
        primeWorkManager(this)

        // Re-wrap default handler in case any SDK replaced it after attachBaseContext().
        safeCrashInstall(where = "onCreate(rewrap)", context = this)

        // Optional: self-heal if another SDK overwrites the handler later in runtime.
        safeRegisterSelfHealingOnce(this)

        // Enqueue pending crash uploads from previous run (best-effort).
        // Also schedule a delayed retry to bypass cooldown edge cases if early enqueue ran before WM init.
        safeEnqueuePendingUploadsWithRetryOnce(this)
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
        runCatching {
            CrashCapture.install(context)
            Log.d(TAG, "CrashCapture installed: where=$where")
        }.onFailure { t ->
            Log.w(TAG, "CrashCapture.install failed: where=$where msg=${t.message}", t)
        }
    }

    /**
     * Prime WorkManager initialization (best-effort).
     *
     * Some devices/entry points can hit crash-capture install before WM is ready.
     * Calling getInstance here tends to initialize WM early enough for enqueue calls.
     */
    private fun primeWorkManager(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext ?: context)
            Log.d(TAG, "WorkManager primed.")
        }.onFailure { t ->
            Log.w(TAG, "WorkManager prime failed: ${t.message}", t)
        }
    }

    /**
     * Register self-healing hook (Application required by CrashCapture API).
     */
    private fun safeRegisterSelfHealingOnce(app: Application) {
        if (!selfHealOnce.compareAndSet(false, true)) {
            Log.d(TAG, "CrashCapture self-healing already registered; skipping.")
            return
        }
        runCatching {
            CrashCapture.registerSelfHealing(app)
            Log.d(TAG, "CrashCapture self-healing registered.")
        }.onFailure { t ->
            Log.w(TAG, "CrashCapture.registerSelfHealing failed: ${t.message}", t)
        }
    }

    /**
     * Enqueue pending crash uploads once, plus one delayed retry.
     *
     * Why retry:
     * - If an early enqueue happens before WM init, CrashCapture may record cooldown timing.
     * - The immediate call in onCreate might get skipped due to cooldown.
     * - A delayed retry (> cooldown) makes "next launch uploads" robust without tight coupling.
     */
    private fun safeEnqueuePendingUploadsWithRetryOnce(context: Context) {
        if (!enqueuePendingOnce.compareAndSet(false, true)) {
            Log.d(TAG, "CrashCapture pending enqueue already executed; skipping.")
            return
        }

        val appCtx = context.applicationContext ?: context

        // 1) Immediate attempt
        runCatching {
            CrashCapture.enqueuePendingCrashUploadsIfPossible(appCtx)
            Log.d(TAG, "CrashCapture pending uploads enqueued (immediate).")
        }.onFailure { t ->
            Log.w(TAG, "CrashCapture.enqueuePendingCrashUploads(immediate) failed: ${t.message}", t)
        }

        // 2) Delayed retry to bypass cooldown / WM-init timing edge cases
        if (enqueueRetryScheduled.compareAndSet(false, true)) {
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    runCatching {
                        CrashCapture.enqueuePendingCrashUploadsIfPossible(appCtx)
                        Log.d(TAG, "CrashCapture pending uploads enqueued (delayed retry).")
                    }.onFailure { t ->
                        Log.w(TAG, "CrashCapture.enqueuePendingCrashUploads(delayed) failed: ${t.message}", t)
                    }
                },
                ENQUEUE_RETRY_DELAY_MS
            )
        }
    }

    private fun logBoot(stage: String, pid: Int, processName: String?, isMain: Boolean) {
        val pn = processName?.takeIf { it.isNotBlank() } ?: "<unknown>"
        Log.d(TAG, "$stage: pid=$pid process=$pn isMain=$isMain sdk=${Build.VERSION.SDK_INT}")
    }

    companion object {
        private const val TAG = "SurveyApp"

        // Retry delay should exceed CrashCapture cooldown (~1200ms) with a small buffer.
        private const val ENQUEUE_RETRY_DELAY_MS = 1600L

        // Process-level guards. These are static per-process.
        private val attachBootOnce = AtomicBoolean(false)
        private val onCreateBootOnce = AtomicBoolean(false)
        private val selfHealOnce = AtomicBoolean(false)
        private val enqueuePendingOnce = AtomicBoolean(false)
        private val enqueueRetryScheduled = AtomicBoolean(false)
    }
}
