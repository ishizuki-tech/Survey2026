/*
 * =====================================================================
 *  IshizukiTech LLC — Android Diagnostics
 *  ---------------------------------------------------------------------
 *  File: CrashCapture.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.negi.survey.net.GitHubDiagnosticsConfigStore
import com.negi.survey.net.GitHubUploadWorker
import com.negi.survey.net.GitHubUploader
import com.negi.survey.net.SupabaseCrashUploadWorker
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import kotlin.math.min
import kotlin.system.exitProcess

object CrashCapture {

    private const val TAG = "CrashCapture"

    /** Local directory for staged crash artifacts (.gz). */
    private const val CRASH_DIR_REL = "diagnostics/crash"

    /** Local directory for GitHub mirror copies (Supabase may delete originals). */
    private const val CRASH_GH_MIRROR_DIR_REL = "diagnostics/crash_github_mirror"

    private const val MAX_LOGCAT_BYTES = 850_000
    private const val LOGCAT_MAX_MS = 700L

    private const val LOGCAT_TAIL_LINES_FALLBACK = "3000"
    private const val LOGCAT_TAIL_LINES_SINCE = "6000"

    private const val MAX_FILES_TO_KEEP = 80
    private const val MAX_FILES_TO_ENQUEUE = 20

    /** Supabase object prefix for crash uploads (must match Storage policies). */
    private const val SUPABASE_CRASH_PREFIX = "surveyapp/crash"

    /** Prevent re-enqueue storms on rapid app restarts / multiple entry points. */
    private const val ENQUEUE_COOLDOWN_MS = 1200L

    /** Try to force logcat process shutdown quickly (best-effort). */
    private const val LOGCAT_WAITFOR_MS = 80L

    /** Prevent ensure storms (handler re-wrap). */
    private const val ENSURE_COOLDOWN_MS = 800L

    /** SharedPreferences for previous-session staging state. */
    private const val STATE_PREF_NAME = "crash_capture_state_v2"
    private const val KEY_LAST_EXIT_TS = "last_exit_ts"
    private const val KEY_LAST_EXIT_PID = "last_exit_pid"
    private const val KEY_LAST_LOGCAT_MARKER = "last_logcat_marker"

    /** Stage guards (once per current process). */
    private val stagedPrevSessionThisProcess = AtomicBoolean(false)
    private val stagedExitInfoThisProcess = AtomicBoolean(false)

    private val capturing = AtomicBoolean(false)
    private val enqueueing = AtomicBoolean(false)
    private val selfHealingRegistered = AtomicBoolean(false)

    private val lastEnqueueAt = AtomicLong(0L)
    private val lastEnsureAt = AtomicLong(0L)

    /** Root filesDir cached at install time. */
    @Volatile
    private var filesDirRoot: File? = null

    /** Our installed handler instance (stable per-process). */
    @Volatile
    private var handler: CrashHandler? = null

    /** UTC timestamp used in filenames to keep ordering stable across devices/locales. */
    private val FILE_TS_UTC = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Local timestamp for human-friendly header info. */
    private val HEADER_TS_LOCAL = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Marker for `logcat -T`.
     *
     * Use a year-inclusive format to avoid ambiguity around year boundaries.
     * logcat accepts "YYYY-MM-DD HH:MM:SS.mmm" on modern Android.
     */
    private val LOGCAT_MARKER = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // -----------------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------------

    /**
     * Install (or re-wrap) the default uncaught exception handler.
     *
     * Notes:
     * - Native SIGSEGV will NOT reach this handler.
     * - For native crashes, we stage previous-process exit info + previous-session logcat on next launch.
     *
     * IMPORTANT:
     * - DO NOT touch WorkManager here. WorkManager may not be initialized in attachBaseContext,
     *   especially when WorkManagerInitializer is disabled in the manifest.
     * - Enqueue uploads from Application.onCreate (or later) after WorkManager init is guaranteed.
     */
    fun install(context: Context) {
        installInternal(context = context, where = "install(legacy)")
    }

    /**
     * Install with a caller label.
     *
     * This helps identify unexpected re-installs (e.g., self-healing, receivers, SDK overrides).
     */
    fun install(context: Context, where: String) {
        installInternal(context = context, where = where.ifBlank { "install(custom)" })
    }

    /**
     * Optional hardening: register lifecycle callbacks to periodically re-ensure the handler chain.
     *
     * A-Plan:
     * - Keep this lightweight.
     * - Avoid calling ensureInstalled() on multiple lifecycle events back-to-back.
     * - Use an internal cooldown (ENSURE_COOLDOWN_MS) to prevent storms.
     */
    fun registerSelfHealing(application: Application) {
        if (!selfHealingRegistered.compareAndSet(false, true)) return

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityResumed(activity: Activity) {
                ensureInstalled(activity, where = "selfHeal:onActivityResumed")
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        Log.d(TAG, "registerSelfHealing: ActivityLifecycleCallbacks registered. mode=onResumedOnly")
    }

    /**
     * Ensure we are the default handler (best-effort) with a small cooldown.
     *
     * Note:
     * - This only ensures handler installation; it does NOT enqueue WorkManager uploads.
     */
    fun ensureInstalled(context: Context) {
        ensureInstalled(context = context, where = "ensureInstalled(legacy)")
    }

    /**
     * Ensure installed with a caller label for debug.
     */
    fun ensureInstalled(context: Context, where: String) {
        val now = SystemClock.elapsedRealtime()
        val prev = lastEnsureAt.get()
        if (prev != 0L && (now - prev) in 0 until ENSURE_COOLDOWN_MS) return
        lastEnsureAt.set(now)
        installInternal(context = context, where = where.ifBlank { "ensureInstalled(custom)" })
    }

    /**
     * Enqueue pending crash artifacts for upload.
     *
     * Strategy:
     * - Stage previous-process exit info (API30+) into crash dir (native crash / signaled).
     * - Stage previous-session logcat into crash dir using `logcat -T <marker>`.
     * - Then enqueue all files under crash dir to Supabase + GitHub (mirror-first).
     *
     * IMPORTANT:
     * - This method touches WorkManager. Call it only after WorkManager is initialized
     *   (typically from Application.onCreate or later).
     *
     * HARDENING:
     * - If called on the main thread, offload to a dedicated background thread to avoid startup jank / ANR.
     */
    fun enqueuePendingCrashUploadsIfPossible(context: Context) {
        enqueuePendingCrashUploadsIfPossible(context = context, where = "enqueue(legacy)")
    }

    /**
     * Enqueue with a caller label for debug.
     *
     * This is the key hook to identify who is calling enqueue twice within cooldown.
     */
    fun enqueuePendingCrashUploadsIfPossible(context: Context, where: String) {
        // Offload heavy staging (logcat / IO) from main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val appCtx = safeAppContext(context)
            val label = where.ifBlank { "enqueue(main_offload)" }
            Thread(
                {
                    runCatching { enqueuePendingCrashUploadsIfPossible(appCtx, "$label:bg") }
                        .onFailure { e -> Log.w(TAG, "enqueue offload failed: ${e.message} where=$label", e) }
                },
                "CrashCapture-Enqueue"
            ).apply { isDaemon = true }.start()
            return
        }

        val root = filesDirRoot ?: runCatching { context.filesDir }.getOrNull()
        if (root == null) {
            Log.w(TAG, "enqueuePendingCrashUploadsIfPossible: filesDir unavailable; skipping. where=$where")
            return
        }

        if (!enqueueing.compareAndSet(false, true)) {
            Log.d(TAG, "enqueuePendingCrashUploadsIfPossible skipped (already running). where=$where")
            return
        }

        try {
            val now = SystemClock.elapsedRealtime()
            val prev = lastEnqueueAt.get()
            val dt = now - prev
            if (prev != 0L && dt in 0 until ENQUEUE_COOLDOWN_MS) {
                Log.d(
                    TAG,
                    "enqueuePendingCrashUploadsIfPossible skipped (cooldown). dt=${dt}ms where=$where " +
                            "thread=${Thread.currentThread().name}"
                )
                return
            }
            lastEnqueueAt.set(now)

            val appCtx = safeAppContext(context)

            Log.d(
                TAG,
                "enqueuePendingCrashUploadsIfPossible enter where=$where pid=${Process.myPid()} " +
                        "thread=${Thread.currentThread().name}"
            )

            // 0) Stage previous-run artifacts BEFORE scanning the crash dir (no WorkManager dependency).
            val stagedExitPid = runCatching { stageLastExitInfoIfNeeded(appCtx, root) }
                .onFailure { e -> Log.w(TAG, "stageLastExitInfoIfNeeded failed: ${e.message}", e) }
                .getOrNull()

            runCatching { stagePreviousSessionLogcatIfNeeded(appCtx, root, stagedExitPid) }
                .onFailure { e -> Log.w(TAG, "stagePreviousSessionLogcatIfNeeded failed: ${e.message}", e) }

            // Always persist marker for the NEXT run (after staging).
            runCatching { persistCurrentLogcatMarker(appCtx) }
                .onFailure { e -> Log.w(TAG, "persistCurrentLogcatMarker failed: ${e.message}", e) }

            // 1) WorkManager availability check.
            // Treat "not initialized yet" as a normal situation depending on init strategy.
            val wmOk = runCatching { WorkManager.getInstance(appCtx) }
                .onFailure { e ->
                    Log.w(
                        TAG,
                        "WorkManager not available yet; will retry on next enqueue call. where=$where err=${e.message}"
                    )
                }
                .isSuccess
            if (!wmOk) return

            val dir = crashDir(root).apply { mkdirs() }
            val ghMirrorDir = crashGitHubMirrorDir(root).apply { mkdirs() }

            purgeOldFiles(dir, MAX_FILES_TO_KEEP)
            purgeOldFiles(ghMirrorDir, MAX_FILES_TO_KEEP)

            val files = dir.listFiles { f ->
                f.isFile && f.length() > 0L && !f.name.startsWith(".")
            }?.toList().orEmpty()

            if (files.isEmpty()) return

            val supabaseConfigured = SupabaseCrashUploadWorker.isConfigured()
            val ghCfg = buildCrashGitHubConfigOrNull(appCtx)

            Log.d(
                TAG,
                "Pending crash artifacts=${files.size} dir=${dir.absolutePath} " +
                        "supabaseConfigured=$supabaseConfigured githubConfigured=${ghCfg != null} where=$where"
            )

            Log.d(
                TAG,
                "Supabase hints: urlSet=${BuildConfig.SUPABASE_URL.isNotBlank()} urlLen=${BuildConfig.SUPABASE_URL.length} " +
                        "anonKeySet=${BuildConfig.SUPABASE_ANON_KEY.isNotBlank()} anonKeyLen=${BuildConfig.SUPABASE_ANON_KEY.length} " +
                        "bucket=${BuildConfig.SUPABASE_LOG_BUCKET}"
            )
            Log.d(
                TAG,
                "GitHub hints: owner=${BuildConfig.GH_OWNER} repo=${BuildConfig.GH_REPO} " +
                        "branch=${BuildConfig.GH_BRANCH} pathPrefix=${ghCfg?.pathPrefix}"
            )

            val targets = files
                .sortedByDescending { it.lastModified() }
                .take(MAX_FILES_TO_ENQUEUE)

            if (!supabaseConfigured && ghCfg == null) {
                Log.d(TAG, "No upload config found; crash artifacts will remain local. where=$where")
                return
            }

            // 2) GitHub mirror upload (copy files first to avoid Supabase delete race).
            if (ghCfg != null) {
                Log.d(TAG, "Enqueuing GitHub crash uploads… (mirror-first) where=$where")
                targets.forEach { file ->
                    val mirror = runCatching { makeGitHubMirrorCopy(file, ghMirrorDir) }
                        .onFailure { e -> Log.w(TAG, "GitHub mirror copy failed: ${file.name} err=${e.message}", e) }
                        .getOrNull()

                    if (mirror != null) {
                        val remoteRelativePath = mirror.name
                        Log.d(
                            TAG,
                            "GitHub enqueue(file): name=${mirror.name} bytes=${mirror.length()} remote=$remoteRelativePath where=$where"
                        )

                        enqueueGitHubWorkerFileUpload(
                            context = appCtx,
                            cfg = ghCfg,
                            localFile = mirror,
                            remoteRelativePath = remoteRelativePath
                        )
                    }
                }
            }

            // 3) Supabase upload for originals (preferred for cloud storage).
            if (supabaseConfigured) {
                Log.d(TAG, "Enqueuing Supabase crash uploads… prefix=$SUPABASE_CRASH_PREFIX where=$where")
                targets.forEach { file ->
                    Log.d(TAG, "Supabase enqueue: name=${file.name} bytes=${file.length()} mtime=${file.lastModified()} where=$where")
                    SupabaseCrashUploadWorker.enqueueExistingCrash(
                        context = appCtx,
                        file = file,
                        objectPrefix = SUPABASE_CRASH_PREFIX,
                        addDateSubdir = true
                    )
                }
            }
        } finally {
            enqueueing.set(false)
        }
    }

    // -----------------------------------------------------------------------------
    // Install internals
    // -----------------------------------------------------------------------------

    private fun installInternal(context: Context, where: String) {
        val root = runCatching { context.filesDir }.getOrNull()
        if (root == null) {
            Log.w(TAG, "install: context.filesDir unavailable; skipping install. where=$where")
            return
        }

        filesDirRoot = root

        // Create the handler once, then keep re-wrapping the delegate as needed.
        val h = handler ?: synchronized(this) {
            handler ?: CrashHandler(
                filesDir = root,
                capturing = capturing,
                onHardKill = { hardKill() }
            ).also { handler = it }
        }

        ensureDefaultHandlerInstalled(h)

        Log.d(
            TAG,
            "install: ensured default handler. where=$where pid=${Process.myPid()} " +
                    "default=${describeHandler(Thread.getDefaultUncaughtExceptionHandler())}"
        )
    }

    private fun ensureDefaultHandlerInstalled(h: CrashHandler) {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current === h) return

        // Always delegate to what is currently installed (if any), avoiding self-loop.
        h.updateDelegate(current)

        Thread.setDefaultUncaughtExceptionHandler(h)
    }

    // -----------------------------------------------------------------------------
    // Staging (previous process / previous session)
    // -----------------------------------------------------------------------------

    /**
     * API 30+: Convert previous process exit reason into a staged crash artifact.
     *
     * Returns:
     * - The previous process pid (if known and a crash-like exit was found), else null.
     */
    private fun stageLastExitInfoIfNeeded(context: Context, filesDir: File): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (!stagedExitInfoThisProcess.compareAndSet(false, true)) return null

        val appCtx = safeAppContext(context)
        val prefs = appCtx.getSharedPreferences(STATE_PREF_NAME, Context.MODE_PRIVATE)
        val lastTs = prefs.getLong(KEY_LAST_EXIT_TS, 0L)

        val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val reasons: List<ApplicationExitInfo> = runCatching {
            am.getHistoricalProcessExitReasons(appCtx.packageName, 0, 16)
        }.getOrDefault(emptyList())

        if (reasons.isEmpty()) return null

        // Sort newest-first defensively (OEMs should already do this, but don't trust it).
        val sorted = reasons.sortedByDescending { it.timestamp }

        // Update last processed timestamp to the newest item we observed (even if not crash-like),
        // so we don't redo this scan on every launch.
        val newestTs = sorted.firstOrNull()?.timestamp ?: 0L
        if (newestTs > 0L && newestTs != lastTs) {
            prefs.edit().putLong(KEY_LAST_EXIT_TS, newestTs).apply()
        }

        // Find the newest crash-like entry that is newer than lastTs.
        val crashLike = sorted.firstOrNull { info ->
            val ts = info.timestamp
            if (ts <= 0L) return@firstOrNull false
            if (ts == lastTs) return@firstOrNull false
            val r = info.reason
            r == ApplicationExitInfo.REASON_CRASH_NATIVE || r == ApplicationExitInfo.REASON_SIGNALED
        } ?: return null

        val ts = crashLike.timestamp
        val reason = crashLike.reason
        val pid = crashLike.pid
        val status = crashLike.status
        val desc = crashLike.description ?: ""

        // Persist last exit pid to help previous-session logcat filtering.
        prefs.edit().putInt(KEY_LAST_EXIT_PID, pid).apply()

        val now = Date()
        val stampUtc = FILE_TS_UTC.format(now)
        val stampLocal = HEADER_TS_LOCAL.format(now)

        val traceText = runCatching {
            val ins = crashLike.traceInputStream ?: return@runCatching ""
            ins.bufferedReader().use { it.readText().take(240_000) }
        }.getOrDefault("")

        val text = buildString {
            appendLine("=== Previous Process Exit (API30+) ===")
            appendLine("captured_time_utc=$stampUtc")
            appendLine("captured_time_local=$stampLocal")
            appendLine("exit_timestamp_ms=$ts")
            appendLine("exit_reason=$reason")
            appendLine("exit_status=$status")
            appendLine("exit_pid=$pid")
            appendLine("description=$desc")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("appId=${BuildConfig.APPLICATION_ID}")
            appendLine("versionName=${BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${BuildConfig.VERSION_CODE}")
            if (traceText.isNotBlank()) {
                appendLine()
                appendLine("=== trace (capped) ===")
                appendLine(traceText)
            }
        }.toByteArray(Charsets.UTF_8)

        val dir = crashDir(filesDir).apply { mkdirs() }
        val name = "exit_${stampUtc}_pid${pid}_r${reason}.log.gz"
        val outFile = File(dir, name)

        FileOutputStream(outFile).use { fos ->
            GZIPOutputStream(fos).use { gz ->
                gz.write(text)
                gz.flush()
            }
            runCatching { fos.fd.sync() }
        }

        Log.d(TAG, "Staged exit info: ${outFile.absolutePath} bytes=${outFile.length()} reason=$reason pid=$pid")
        return pid
    }

    /**
     * Stage previous-session logcat on next launch.
     *
     * - Uses last persisted marker with `logcat -T <marker>`.
     * - If a previous exit pid is known, tries `--pid=<pid>` first to reduce noise.
     */
    private fun stagePreviousSessionLogcatIfNeeded(context: Context, filesDir: File, preferredPid: Int?) {
        if (!stagedPrevSessionThisProcess.compareAndSet(false, true)) return

        val appCtx = safeAppContext(context)
        val prefs = appCtx.getSharedPreferences(STATE_PREF_NAME, Context.MODE_PRIVATE)
        val marker = prefs.getString(KEY_LAST_LOGCAT_MARKER, null)?.trim().orEmpty()
        if (marker.isBlank()) {
            Log.d(TAG, "prevSessionLogcat: no marker yet; skipping.")
            return
        }

        val savedExitPid = prefs.getInt(KEY_LAST_EXIT_PID, 0).takeIf { it > 0 }
        val pid = preferredPid ?: savedExitPid

        val now = Date()
        val stampUtc = FILE_TS_UTC.format(now)
        val stampLocal = HEADER_TS_LOCAL.format(now)

        val header = buildString {
            appendLine("=== Previous Session Logcat ===")
            appendLine("captured_time_utc=$stampUtc")
            appendLine("captured_time_local=$stampLocal")
            appendLine("marker=$marker")
            appendLine("preferred_prev_pid=${pid ?: -1}")
            appendLine("note=best-effort; buffers may be overwritten before next start")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("appId=${BuildConfig.APPLICATION_ID}")
            appendLine("versionName=${BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${BuildConfig.VERSION_CODE}")
            appendLine()
            appendLine("=== Logcat (since marker) ===")
        }.toByteArray(Charsets.UTF_8)

        val logBytes = collectLogcatBytesSinceMarker(
            marker = marker,
            preferredPid = pid,
            maxBytes = MAX_LOGCAT_BYTES,
            maxMs = 650L
        )

        val dir = crashDir(filesDir).apply { mkdirs() }
        val name = "prevlog_${stampUtc}_pid${Process.myPid()}.log.gz"
        val outFile = File(dir, name)

        FileOutputStream(outFile).use { fos ->
            GZIPOutputStream(fos).use { gz ->
                gz.write(header)
                gz.write(logBytes)
                gz.flush()
            }
            runCatching { fos.fd.sync() }
        }

        Log.d(TAG, "Staged prev session logcat: ${outFile.absolutePath} bytes=${outFile.length()} marker=$marker pid=${pid ?: -1}")
    }

    /**
     * Persist the current marker to be used on the NEXT app launch for prev-session logcat staging.
     */
    private fun persistCurrentLogcatMarker(context: Context) {
        val appCtx = safeAppContext(context)
        val prefs = appCtx.getSharedPreferences(STATE_PREF_NAME, Context.MODE_PRIVATE)
        val marker = runCatching { LOGCAT_MARKER.format(Date()) }.getOrDefault("")
        if (marker.isBlank()) return
        prefs.edit().putString(KEY_LAST_LOGCAT_MARKER, marker).apply()
        Log.d(TAG, "Persisted logcat marker for next run: $marker")
    }

    // -----------------------------------------------------------------------------
    // Mirror / file utils
    // -----------------------------------------------------------------------------

    private fun makeGitHubMirrorCopy(src: File, mirrorDir: File): File {
        mirrorDir.mkdirs()

        val dst = File(mirrorDir, src.name)

        // Reuse existing mirror if identical.
        if (dst.exists() && dst.length() == src.length() && dst.lastModified() == src.lastModified()) {
            return dst
        }

        // If name exists but differs, create a unique suffixed name.
        val target = if (!dst.exists()) dst else makeUniqueLike(srcName = src.name, dir = mirrorDir)

        FileInputStream(src).use { input ->
            FileOutputStream(target).use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
                output.flush()
            }
        }

        target.setLastModified(src.lastModified())
        return target
    }

    private fun makeUniqueLike(srcName: String, dir: File): File {
        if (srcName.endsWith(".log.gz")) {
            val base = srcName.removeSuffix(".log.gz")
            var index = 2
            while (true) {
                val candidate = File(dir, "$base-$index.log.gz")
                if (!candidate.exists()) return candidate
                index++
            }
        }

        val base = srcName.removeSuffix(".gz")
        var index = 2
        while (true) {
            val candidate = File(dir, "$base-$index.gz")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun captureCrashToFile(filesDir: File, thread: Thread, throwable: Throwable): File {
        val dir = crashDir(filesDir).apply { mkdirs() }
        purgeOldFiles(dir, MAX_FILES_TO_KEEP)

        val now = Date()
        val stampUtc = FILE_TS_UTC.format(now)
        val stampLocal = HEADER_TS_LOCAL.format(now)

        val pid = Process.myPid()
        val tid = Process.myTid()
        val uptimeTail = (SystemClock.elapsedRealtime() % 1_000_000L)

        val name = "crash_${stampUtc}_pid${pid}_tid${tid}_u${uptimeTail}.log.gz"
        val outFile = File(dir, name)

        FileOutputStream(outFile).use { fos ->
            GZIPOutputStream(fos).use { gz ->
                val header = buildString {
                    appendLine("=== Crash Report ===")
                    appendLine("time_utc=$stampUtc")
                    appendLine("time_local=$stampLocal")
                    appendLine("pid=$pid")
                    appendLine("tid=$tid")
                    appendLine("thread=${thread.name}")
                    appendLine("sdk=${Build.VERSION.SDK_INT}")
                    appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("appId=${BuildConfig.APPLICATION_ID}")
                    appendLine("versionName=${BuildConfig.VERSION_NAME}")
                    appendLine("versionCode=${BuildConfig.VERSION_CODE}")
                    appendLine()
                    appendLine("=== Exception ===")
                    appendLine(Log.getStackTraceString(throwable))
                    appendLine()
                    appendLine("=== Logcat (best-effort) ===")
                }.toByteArray(Charsets.UTF_8)

                gz.write(header)

                val logBytes = collectLogcatBytesCurrentPid(
                    pid = pid,
                    maxBytes = MAX_LOGCAT_BYTES,
                    maxMs = LOGCAT_MAX_MS
                )
                gz.write(logBytes)
                gz.flush()
            }

            runCatching { fos.fd.sync() }
        }

        return outFile
    }

    private fun crashDir(filesDir: File): File = File(filesDir, CRASH_DIR_REL)

    private fun crashGitHubMirrorDir(filesDir: File): File = File(filesDir, CRASH_GH_MIRROR_DIR_REL)

    private fun purgeOldFiles(dir: File, maxKeep: Int) {
        val all = dir.listFiles { f -> f.isFile && f.length() > 0L && !f.name.startsWith(".") }?.toList().orEmpty()
        if (all.size <= maxKeep) return

        val sorted = all.sortedBy { it.lastModified() }
        val toDelete = sorted.take(all.size - maxKeep)
        toDelete.forEach { f -> runCatching { f.delete() } }
    }

    // -----------------------------------------------------------------------------
    // Logcat capture
    // -----------------------------------------------------------------------------

    private fun collectLogcatBytesCurrentPid(pid: Int, maxBytes: Int, maxMs: Long): ByteArray {
        val primary = listOf(
            "logcat", "-d",
            "-v", "threadtime",
            "-b", "main", "-b", "system", "-b", "crash",
            "--pid=$pid",
            "-t", "2000"
        )

        val fallback = listOf(
            "logcat", "-d",
            "-v", "threadtime",
            "-b", "main", "-b", "system", "-b", "crash",
            "-t", LOGCAT_TAIL_LINES_FALLBACK
        )

        return runCatching { execAndReadCapped(primary, maxBytes, maxMs) }
            .recoverCatching { execAndReadCapped(fallback, maxBytes, maxMs) }
            .getOrElse { e ->
                ("(logcat capture failed: ${e.message})\n").toByteArray(Charsets.UTF_8)
            }
    }

    private fun collectLogcatBytesSinceMarker(
        marker: String,
        preferredPid: Int?,
        maxBytes: Int,
        maxMs: Long
    ): ByteArray {
        val withPid = preferredPid?.takeIf { it > 0 }?.let { pid ->
            listOf(
                "logcat", "-d",
                "-v", "threadtime",
                "-b", "main", "-b", "system", "-b", "crash",
                "--pid=$pid",
                "-T", marker,
                "-t", LOGCAT_TAIL_LINES_SINCE
            )
        }

        val noPid = listOf(
            "logcat", "-d",
            "-v", "threadtime",
            "-b", "main", "-b", "system", "-b", "crash",
            "-T", marker,
            "-t", LOGCAT_TAIL_LINES_SINCE
        )

        val fallback = listOf(
            "logcat", "-d",
            "-v", "threadtime",
            "-b", "main", "-b", "system", "-b", "crash",
            "-t", LOGCAT_TAIL_LINES_FALLBACK
        )

        return runCatching {
            if (withPid != null) execAndReadCapped(withPid, maxBytes, maxMs) else execAndReadCapped(noPid, maxBytes, maxMs)
        }.recoverCatching {
            execAndReadCapped(noPid, maxBytes, maxMs)
        }.recoverCatching {
            execAndReadCapped(fallback, maxBytes, maxMs)
        }.getOrElse { e ->
            ("(prev-session logcat capture failed: ${e.message})\n").toByteArray(Charsets.UTF_8)
        }
    }

    private fun execAndReadCapped(cmd: List<String>, maxBytes: Int, maxMs: Long): ByteArray {
        val start = SystemClock.elapsedRealtime()

        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        return try {
            proc.inputStream.use { input ->
                val out = ByteArrayOutputStream(min(maxBytes, 128 * 1024))
                val buf = ByteArray(16 * 1024)

                while (out.size() < maxBytes) {
                    if (SystemClock.elapsedRealtime() - start > maxMs) break
                    val remaining = maxBytes - out.size()
                    val n = input.read(buf, 0, min(buf.size, remaining))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }

                out.toByteArray()
            }
        } finally {
            runCatching { proc.waitFor(LOGCAT_WAITFOR_MS, TimeUnit.MILLISECONDS) }
            runCatching {
                proc.destroy()
                proc.waitFor(LOGCAT_WAITFOR_MS, TimeUnit.MILLISECONDS)
            }
            runCatching {
                if (proc.isAlive) proc.destroyForcibly()
            }
        }
    }

    // -----------------------------------------------------------------------------
    // GitHub enqueue
    // -----------------------------------------------------------------------------

    /**
     * Build GitHub config for crash uploads.
     *
     * Priority:
     *  1) GitHubDiagnosticsConfigStore (user-configured token/prefs)
     *  2) BuildConfig (gradle-injected secrets/config)
     */
    private fun buildCrashGitHubConfigOrNull(context: Context): GitHubUploader.GitHubConfig? {
        val fromStore = runCatching { GitHubDiagnosticsConfigStore.buildGitHubConfigOrNull(context) }.getOrNull()
        val base = fromStore ?: runCatching { buildCrashGitHubConfigFromBuildConfig() }.getOrNull()
        if (base == null) return null

        val crashPrefix = computeCrashPrefix(base.pathPrefix)

        return base.copy(
            repo = base.repo.substringAfterLast('/').trim(),
            branch = base.branch.ifBlank { "main" },
            pathPrefix = crashPrefix
        )
    }

    private fun computeCrashPrefix(basePrefix: String): String {
        val p = basePrefix.trim('/')
        if (p.endsWith("diagnostics/crash")) return p
        if (p.endsWith("diagnostics")) return listOf(p, "crash").filter { it.isNotBlank() }.joinToString("/")
        return listOf(p, "diagnostics/crash").filter { it.isNotBlank() }.joinToString("/")
    }

    private fun buildCrashGitHubConfigFromBuildConfig(): GitHubUploader.GitHubConfig? {
        if (BuildConfig.GH_TOKEN.isBlank()) return null
        if (BuildConfig.GH_OWNER.isBlank() || BuildConfig.GH_REPO.isBlank()) return null

        val repoName = BuildConfig.GH_REPO.substringAfterLast('/').trim()
        if (repoName.isBlank()) return null

        return GitHubUploader.GitHubConfig(
            owner = BuildConfig.GH_OWNER,
            repo = repoName,
            branch = BuildConfig.GH_BRANCH.ifBlank { "main" },
            pathPrefix = BuildConfig.GH_PATH_PREFIX.trim().trim('/'),
            token = BuildConfig.GH_TOKEN
        )
    }

    private fun enqueueGitHubWorkerFileUpload(
        context: Context,
        cfg: GitHubUploader.GitHubConfig,
        localFile: File,
        remoteRelativePath: String
    ) {
        val safeUnique = sanitizeWorkName("${cfg.pathPrefix}/$remoteRelativePath")
        val uniqueName = "gh_crash_upload_$safeUnique"

        val req: OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                .setInputData(
                    workDataOf(
                        GitHubUploadWorker.KEY_MODE to "file",
                        GitHubUploadWorker.KEY_OWNER to cfg.owner,
                        GitHubUploadWorker.KEY_REPO to cfg.repo,
                        GitHubUploadWorker.KEY_TOKEN to cfg.token,
                        GitHubUploadWorker.KEY_BRANCH to cfg.branch,
                        GitHubUploadWorker.KEY_PATH_PREFIX to cfg.pathPrefix,
                        GitHubUploadWorker.KEY_FILE_PATH to localFile.absolutePath,
                        GitHubUploadWorker.KEY_FILE_NAME to remoteRelativePath
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(GitHubUploadWorker.TAG)
                .addTag("${GitHubUploadWorker.TAG}:crash:$safeUnique")
                .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, req)
    }

    private fun sanitizeWorkName(value: String): String {
        return value.trim()
            .replace(Regex("""[^\w\-.]+"""), "_")
            .take(120)
    }

    private fun safeAppContext(context: Context): Context {
        /** applicationContext may be null very early; fall back to the provided context. */
        return context.applicationContext ?: context
    }

    private fun describeHandler(h: Thread.UncaughtExceptionHandler?): String {
        if (h == null) return "null"
        return "${h.javaClass.name}@${Integer.toHexString(System.identityHashCode(h))}"
    }

    // -----------------------------------------------------------------------------
    // Crash handler
    // -----------------------------------------------------------------------------

    private class CrashHandler(
        private val filesDir: File,
        private val capturing: AtomicBoolean,
        private val onHardKill: () -> Unit
    ) : Thread.UncaughtExceptionHandler {

        @Volatile
        private var delegate: Thread.UncaughtExceptionHandler? = null

        fun updateDelegate(newDelegate: Thread.UncaughtExceptionHandler?) {
            // Avoid self-loop.
            if (newDelegate === this) return
            delegate = newDelegate
        }

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            if (!capturing.compareAndSet(false, true)) {
                try {
                    delegate?.uncaughtException(thread, throwable)
                } catch (_: Throwable) {
                    onHardKill()
                }
                return
            }

            try {
                val file = runCatching { captureCrashToFile(filesDir, thread, throwable) }
                    .onFailure { e -> Log.e(TAG, "Crash capture failed: ${e.message}", e) }
                    .getOrNull()

                if (file != null) {
                    Log.e(TAG, "Crash captured: ${file.absolutePath} bytes=${file.length()}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Crash capture unexpected failure: ${t.message}", t)
            } finally {
                try {
                    val d = delegate
                    if (d != null) d.uncaughtException(thread, throwable) else onHardKill()
                } catch (_: Throwable) {
                    onHardKill()
                }
            }
        }
    }

    private fun hardKill() {
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }
}