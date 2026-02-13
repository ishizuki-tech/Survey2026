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

/**
 * Application bootstrap:
 * - Install CrashCapture early (attachBaseContext) WITHOUT touching applicationContext.
 * - Enqueue pending crash uploads on next start (onCreate).
 * - Run only in the main process.
 */
class SurveyApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        val isMain = isMainProcess(base)
        val pid = android.os.Process.myPid()
        val pn = currentProcessNameOrNull()

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

        val isMain = isMainProcess(this)
        val pid = android.os.Process.myPid()
        val pn = currentProcessNameOrNull()

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
     */
    private fun currentProcessNameOrNull(): String? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * Returns true if running in the main app process.
     */
    private fun isMainProcess(context: Context): Boolean {
        val myPid = android.os.Process.myPid()

        // API 28+: fast and reliable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val pn = runCatching { Application.getProcessName() }.getOrNull()
            return pn == null || pn == context.packageName
        }

        // Legacy path: ActivityManager.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        val procs = runCatching { am.runningAppProcesses }.getOrNull() ?: return true
        val mine = procs.firstOrNull { it.pid == myPid }?.processName
        return mine == null || mine == context.packageName
    }

    companion object {
        private const val TAG = "SurveyApp"
    }
}
