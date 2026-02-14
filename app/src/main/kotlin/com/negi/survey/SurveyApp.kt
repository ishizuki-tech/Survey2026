/*
 * =====================================================================
 *  IshizukiTech LLC — Android App Shell
 *  ---------------------------------------------------------------------
 *  File: SurveyApp.kt
 *  Author: Shu Ishizuki
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
import android.util.Log
import com.negi.survey.slm.LiteRtLM
import java.io.File

/**
 * Application bootstrap:
 * - Install CrashCapture early (attachBaseContext) WITHOUT touching applicationContext.
 * - Enqueue pending crash uploads on next start (onCreate).
 * - Run only in the main process.
 */
class SurveyApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        val pn = currentProcessName(base)
        val isMain = isMainProcess(base, pn)
        val pid = android.os.Process.myPid()

        Log.d(TAG, "attachBaseContext: pid=$pid process=$pn isMain=$isMain")

        if (!isMain) {
            Log.d(TAG, "Non-main process; CrashCapture install skipped.")
            return
        }

        // Install as early as possible to catch crashes during Application.onCreate().
        runCatching {
            // IMPORTANT: Do not use base.applicationContext here.
            CrashCapture.install(base)
            Log.d(TAG, "CrashCapture installed in attachBaseContext.")
        }.onFailure { t ->
            Log.w(TAG, "CrashCapture.install failed in attachBaseContext: ${t.message}", t)
        }
    }

    override fun onCreate() {
        super.onCreate()

        val pn = currentProcessName(this)
        val isMain = isMainProcess(this, pn)
        val pid = android.os.Process.myPid()

        Log.d(TAG, "onCreate: pid=$pid process=$pn isMain=$isMain")

        if (!isMain) {
            Log.d(TAG, "Non-main process; bootstrap skipped.")
            return
        }

        // Keep any global singletons ready early.
        runCatching { LiteRtLM.setApplicationContext(this) }
            .onFailure { t ->
                Log.w(TAG, "LiteRtLM.setApplicationContext failed: ${t.message}", t)
            }

        // Enqueue pending crash uploads from previous run (best-effort).
        runCatching {
            CrashCapture.enqueuePendingCrashUploadsIfPossible(this)
            Log.d(TAG, "CrashCapture pending uploads enqueued.")
        }.onFailure { t ->
            Log.w(TAG, "CrashCapture.enqueuePendingCrashUploads failed: ${t.message}", t)
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
            val cmd = File("/proc/self/cmdline")
                .inputStream()
                .use { it.readBytes() }
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

    companion object {
        private const val TAG = "SurveyApp"
    }
}
