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
import androidx.annotation.RequiresApi
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.system.exitProcess

object CrashCapture {

    private const val TAG = "CrashCapture"

    /** Local directory for staged crash artifacts (.log). */
    private const val CRASH_DIR_REL = "diagnostics/crash"

    /** Local directory for GitHub mirror copies (Supabase may delete originals). */
    private const val CRASH_GH_MIRROR_DIR_REL = "diagnostics/crash_github_mirror"

    /** Remote subdir name for uploading AppRingLogStore "raw ring body". */
    private const val REMOTE_RING_SUBDIR = "ring_store"

    private const val MAX_LOGCAT_BYTES = 1_400_000
    private const val LOGCAT_MAX_MS = 900L

    private const val LOGCAT_TAIL_LINES_FALLBACK = "5000"
    private const val LOGCAT_TAIL_LINES_SINCE = "12000"

    private const val MAX_FILES_TO_KEEP = 120
    private const val MAX_FILES_TO_ENQUEUE = 30

    /** Maximum number of new ApplicationExitInfo records staged per launch. */
    private const val MAX_EXIT_INFOS_TO_STAGE = 16

    /** Preserve only a bounded amount of system-provided trace data per exit. */
    private const val MAX_EXIT_TRACE_BYTES = 768 * 1024

    /**
     * Limit ring uploads to avoid enqueue storms.
     * Increase if your ring is intentionally large.
     */
    private const val MAX_RING_FILES_TO_ENQUEUE = 300

    /**
     * Hard cap for ring total bytes uploaded per enqueue call.
     * This prevents a runaway directory from generating hundreds of MB of uploads.
     */
    private const val MAX_RING_TOTAL_BYTES_TO_ENQUEUE = 90_000_000L // 90MB

    /** Prevent re-enqueue storms on rapid app restarts / multiple entry points. */
    private const val ENQUEUE_COOLDOWN_MS = 1200L

    /** Try to force logcat process shutdown quickly (best-effort). */
    private const val LOGCAT_WAITFOR_MS = 90L

    /** Prevent ensure storms (handler-chain verification). */
    private const val ENSURE_COOLDOWN_MS = 800L

    /** SharedPreferences for previous-session staging state. */
    private const val STATE_PREF_NAME = "crash_capture_state_v3"
    private const val KEY_LAST_EXIT_TS = "last_exit_ts"
    private const val KEY_LAST_EXIT_PID = "last_exit_pid"
    private const val KEY_LAST_LOGCAT_MARKER = "last_logcat_marker"

    /** Stage guards (once per current process). */
    private val stagedPrevSessionThisProcess = AtomicBoolean(false)
    private val stagedExitInfoThisProcess = AtomicBoolean(false)
    private val stagedRingUploadThisProcess = AtomicBoolean(false)

    private val capturing = AtomicBoolean(false)
    private val enqueueing = AtomicBoolean(false)
    private val selfHealingRegistered = AtomicBoolean(false)

    /**
     * Once our handler has been installed, a later different default handler
     * may be an SDK wrapper that already delegates to us. Re-parenting our
     * delegate to that wrapper would create a handler cycle.
     */
    private val handlerInstalledOnce = AtomicBoolean(false)

    private val lastEnqueueAt = AtomicLong(0L)
    private val lastEnsureAt = AtomicLong(0L)

    /** Root filesDir cached at install time. */
    @Volatile
    private var filesDirRoot: File? = null

    /** Our installed handler instance (stable per-process). */
    @Volatile
    private var handler: CrashHandler? = null

    /**
     * SimpleDateFormat is not thread-safe.
     *
     * Crash capture, startup staging, and enqueue work can execute on different
     * threads, so all formatter access is serialized through [timestampLock].
     */
    private val timestampLock = Any()

    /** UTC timestamp used in filenames to keep ordering stable across devices/locales. */
    private val fileTsUtc = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Local timestamp for human-friendly header info. */
    private val headerTsLocal = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Marker for `logcat -T`.
     *
     * Use a year-inclusive format to avoid ambiguity around year boundaries.
     */
    private val logcatMarkerFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // -----------------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------------

    /**
     * Install the uncaught exception handler while preserving a safe delegate chain.
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
     * This helps identify repeated install calls (e.g., self-healing, receivers, SDK activity).
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
     * Verify/restore a safe handler chain (best-effort) with a small cooldown.
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
     * - Then enqueue all files under crash dir to GitHub (mirror-first).
     * - Additionally: upload the raw AppRingLogStore ring directory ("ring body") as-is (files),
     *   placed under a timestamped remote directory to avoid WorkManager unique-name collisions.
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
            val wmOk = runCatching { WorkManager.getInstance(appCtx) }
                .onFailure { e ->
                    Log.w(
                        TAG,
                        "WorkManager not available yet; will retry on next enqueue call. where=$where err=${e.message}"
                    )
                }
                .isSuccess
            if (!wmOk) return

            val dir = crashDir(root)
            val ghMirrorDir = crashGitHubMirrorDir(root)

            ensureDirectory(dir)
            ensureDirectory(ghMirrorDir)

            purgeOldFiles(dir, MAX_FILES_TO_KEEP)

            val files = dir.listFiles { f ->
                f.isFile && f.length() > 0L && !f.name.startsWith(".")
            }?.toList().orEmpty()

            if (files.isEmpty()) {
                Log.d(TAG, "No crash artifacts found; skipping enqueue. where=$where")
                return
            }

            val ghCfg = buildCrashGitHubConfigOrNull(appCtx)
            if (ghCfg == null) {
                Log.d(TAG, "No upload config found; crash artifacts will remain local. where=$where")
                return
            }

            Log.d(
                TAG,
                "GitHub hints: owner=${BuildConfig.GH_OWNER} repo=${BuildConfig.GH_REPO} " +
                        "branch=${BuildConfig.GH_BRANCH} pathPrefix=${ghCfg.pathPrefix}"
            )

            val targets = files
                .sortedByDescending { it.lastModified() }
                .take(MAX_FILES_TO_ENQUEUE)

            Log.d(TAG, "Enqueuing GitHub crash uploads… (mirror-first) where=$where")
            targets.forEach { file ->
                val mirror = runCatching { makeGitHubMirrorCopy(file, ghMirrorDir) }
                    .onFailure { e -> Log.w(TAG, "GitHub mirror copy failed: ${file.name} err=${e.message}", e) }
                    .getOrNull()

                if (mirror != null) {
                    val remoteRelativePath = mirror.name
                    Log.d(
                        TAG,
                        "GitHub enqueue(crash file): name=${mirror.name} bytes=${mirror.length()} remote=$remoteRelativePath where=$where"
                    )

                    enqueueGitHubWorkerFileUpload(
                        context = appCtx,
                        cfg = ghCfg,
                        localFile = mirror,
                        remoteRelativePath = remoteRelativePath,
                        kindTag = "crash"
                    )
                }
            }

            // 2) Additionally upload raw ring body (AppRingLogStore directory) under a timestamped remote dir.
            //    This avoids WorkManager unique-name collisions because ring segment filenames are usually stable.
            runCatching { stageAndEnqueueRingStoreUploads(appCtx, root, ghMirrorDir, ghCfg, where) }
                .onFailure { e -> Log.w(TAG, "Ring store upload staging failed: ${e.message}", e) }

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

        // Create one stable handler instance for the lifetime of the process.
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

        if (current === h) {
            handlerInstalledOnce.set(true)
            return
        }

        /**
         * First installation is safe: capture the handler that existed before us
         * and place CrashCapture at the top of the chain.
         */
        if (handlerInstalledOnce.compareAndSet(false, true)) {
            h.updateDelegate(current)
            Thread.setDefaultUncaughtExceptionHandler(h)
            return
        }

        /**
         * If the current handler is exactly our known delegate, something restored
         * the pre-CrashCapture handler. Reinstalling ourselves is safe because that
         * handler cannot be an outer wrapper around us.
         */
        if (current === h.delegateSnapshot()) {
            Thread.setDefaultUncaughtExceptionHandler(h)
            return
        }

        /**
         * Do NOT blindly re-wrap an unknown later handler.
         *
         * A common SDK pattern is:
         *
         *     sdkHandler.delegate = CrashCapture
         *
         * If we then set:
         *
         *     CrashCapture.delegate = sdkHandler
         *
         * the chain becomes CrashCapture -> SDK -> CrashCapture and recurses.
         *
         * Leave the later handler in place. Well-behaved crash SDKs normally keep
         * the previous handler in their delegate chain, so CrashCapture remains
         * reachable without risking a cycle.
         */
        Log.w(
            TAG,
            "Default uncaught handler changed after CrashCapture installation; " +
                    "leaving outer handler in place to avoid a delegate cycle. " +
                    "current=${describeHandler(current)}"
        )
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
    private fun stageLastExitInfoIfNeeded(
        context: Context,
        filesDir: File
    ): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }

        if (!stagedExitInfoThisProcess.compareAndSet(false, true)) {
            return null
        }

        try {
            val appCtx = safeAppContext(context)
            val prefs = appCtx.getSharedPreferences(
                STATE_PREF_NAME,
                Context.MODE_PRIVATE
            )

            val lastProcessedTs =
                prefs.getLong(KEY_LAST_EXIT_TS, 0L)

            val am =
                appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return null

            val reasons =
                am.getHistoricalProcessExitReasons(
                    appCtx.packageName,
                    0,
                    MAX_EXIT_INFOS_TO_STAGE
                )

            if (reasons.isEmpty()) {
                return null
            }

            /**
             * Work only on records newer than the marker from the previous scan.
             *
             * The previous implementation compared `ts != lastTs`, which could
             * repeatedly stage an older crash record whenever the newest exit was
             * a non-crash exit. The ordering marker must be a strict `>` boundary.
             */
            val newRecords = reasons
                .asSequence()
                .filter { it.timestamp > lastProcessedTs }
                .sortedByDescending { it.timestamp }
                .toList()

            if (newRecords.isEmpty()) {
                return null
            }

            val newestObservedTs =
                newRecords.maxOf { it.timestamp }

            val diagnosticRecords =
                newRecords.filter { isDiagnosticExitReason(it.reason) }

            val dir = crashDir(filesDir)
            ensureDirectory(dir)

            for (info in diagnosticRecords) {
                stageApplicationExitInfo(
                    info = info,
                    dir = dir
                )
            }

            /**
             * Persist the marker only after all selected records were staged
             * successfully. commit() is intentional here because this state controls
             * de-duplication across process restarts.
             */
            val newestDiagnosticPid =
                diagnosticRecords.firstOrNull()
                    ?.pid
                    ?.takeIf { it > 0 }

            val editor = prefs.edit()
                .putLong(KEY_LAST_EXIT_TS, newestObservedTs)

            if (newestDiagnosticPid != null) {
                editor.putInt(
                    KEY_LAST_EXIT_PID,
                    newestDiagnosticPid
                )
            } else {
                editor.remove(KEY_LAST_EXIT_PID)
            }

            if (!editor.commit()) {
                Log.w(
                    TAG,
                    "Failed to persist ApplicationExitInfo staging marker."
                )
            }

            return newestDiagnosticPid
        } catch (t: Throwable) {
            /**
             * A transient platform/filesystem error should not permanently disable
             * staging for the remainder of this process.
             */
            stagedExitInfoThisProcess.set(false)
            throw t
        }
    }

    /**
     * Stage one ApplicationExitInfo record.
     *
     * System traces are preserved as raw bytes. In particular, Android 12+
     * exposes native tombstones through traceInputStream as protobuf; decoding
     * that stream as UTF-8 text would corrupt the diagnostic payload.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun stageApplicationExitInfo(
        info: ApplicationExitInfo,
        dir: File
    ) {
        val now = Date()
        val capturedUtc = formatFileTimestampUtc(now)
        val capturedLocal = formatHeaderTimestampLocal(now)

        val exitTs = info.timestamp
        val exitStampUtc =
            if (exitTs > 0L) {
                formatFileTimestampUtc(Date(exitTs))
            } else {
                capturedUtc
            }

        val reason = info.reason
        val pid = info.pid
        val baseName =
            "exit_${exitStampUtc}_${exitTs}_pid${pid}_r${reason}"

        val traceResult =
            stageApplicationExitTrace(
                info = info,
                dir = dir,
                baseName = baseName
            )

        val text = buildString {
            appendLine("=== Previous Process Exit (API30+) ===")
            appendLine("captured_time_utc=$capturedUtc")
            appendLine("captured_time_local=$capturedLocal")
            appendLine("exit_timestamp_ms=$exitTs")
            appendLine("exit_reason=$reason")
            appendLine("exit_reason_name=${exitReasonName(reason)}")
            appendLine("exit_status=${info.status}")
            appendLine("exit_pid=$pid")
            appendLine("process_name=${info.processName.orEmpty()}")
            appendLine("importance=${info.importance}")
            appendLine("pss_kb=${info.pss}")
            appendLine("rss_kb=${info.rss}")
            appendLine("description=${sanitizeSingleLine(info.description.orEmpty())}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("appId=${BuildConfig.APPLICATION_ID}")
            appendLine("versionName=${BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${BuildConfig.VERSION_CODE}")

            if (traceResult != null) {
                appendLine("trace_file=${traceResult.file.name}")
                appendLine("trace_bytes=${traceResult.file.length()}")
                appendLine("trace_truncated=${traceResult.truncated}")
                appendLine("trace_format=${traceResult.format}")
            }
        }.toByteArray(Charsets.UTF_8)

        val outFile =
            File(dir, "$baseName.log")

        writeBytesDurably(
            file = outFile,
            bytes = text
        )

        Log.d(
            TAG,
            "Staged exit info: ${outFile.absolutePath} " +
                    "bytes=${outFile.length()} reason=$reason pid=$pid"
        )
    }

    private data class StagedTrace(
        val file: File,
        val truncated: Boolean,
        val format: String
    )

    private data class CappedRead(
        val bytes: ByteArray,
        val truncated: Boolean
    )

    @RequiresApi(Build.VERSION_CODES.R)
    private fun stageApplicationExitTrace(
        info: ApplicationExitInfo,
        dir: File,
        baseName: String
    ): StagedTrace? {
        val input =
            runCatching { info.traceInputStream }
                .getOrNull()
                ?: return null

        val capped =
            input.use {
                readInputStreamCapped(
                    input = it,
                    maxBytes = MAX_EXIT_TRACE_BYTES
                )
            }

        if (capped.bytes.isEmpty()) {
            return null
        }

        val isNativeTombstoneProto =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE

        val suffix =
            if (isNativeTombstoneProto) {
                ".tombstone.pb"
            } else {
                ".trace"
            }

        val format =
            if (isNativeTombstoneProto) {
                "android_native_tombstone_protobuf"
            } else {
                "raw_system_trace"
            }

        val file =
            File(dir, "$baseName$suffix")

        writeBytesDurably(
            file = file,
            bytes = capped.bytes
        )

        return StagedTrace(
            file = file,
            truncated = capped.truncated,
            format = format
        )
    }

    private fun readInputStreamCapped(
        input: InputStream,
        maxBytes: Int
    ): CappedRead {
        val safeMax = maxBytes.coerceAtLeast(0)

        if (safeMax == 0) {
            return CappedRead(
                bytes = ByteArray(0),
                truncated = true
            )
        }

        val out =
            ByteArrayOutputStream(
                min(safeMax, 64 * 1024)
            )

        val buffer = ByteArray(16 * 1024)
        var truncated = false

        while (out.size() < safeMax) {
            val remaining = safeMax - out.size()
            val count = input.read(
                buffer,
                0,
                min(buffer.size, remaining)
            )

            if (count <= 0) {
                break
            }

            out.write(buffer, 0, count)
        }

        /**
         * Probe one additional byte to distinguish exact-size input from a
         * truncated payload.
         */
        if (out.size() >= safeMax) {
            truncated =
                runCatching { input.read() >= 0 }
                    .getOrDefault(false)
        }

        return CappedRead(
            bytes = out.toByteArray(),
            truncated = truncated
        )
    }

    private fun isDiagnosticExitReason(reason: Int): Boolean {
        return reason == ApplicationExitInfo.REASON_CRASH ||
                reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                reason == ApplicationExitInfo.REASON_SIGNALED ||
                reason == ApplicationExitInfo.REASON_ANR ||
                reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ||
                reason == ApplicationExitInfo.REASON_LOW_MEMORY
    }

    private fun exitReasonName(reason: Int): String {
        return when (reason) {
            ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER"
            else -> "REASON_$reason"
        }
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
        val stampUtc = formatFileTimestampUtc(now)
        val stampLocal = formatHeaderTimestampLocal(now)

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
            maxMs = LOGCAT_MAX_MS
        )

        val dir = crashDir(filesDir)
        ensureDirectory(dir)

        val name = "prevlog_${stampUtc}_pid${Process.myPid()}.log"
        val outFile = File(dir, name)

        val payload = ByteArrayOutputStream(header.size + logBytes.size).apply {
            write(header)
            write(logBytes)
        }.toByteArray()

        writeBytesDurably(
            file = outFile,
            bytes = payload
        )

        Log.d(TAG, "Staged prev session logcat: ${outFile.absolutePath} bytes=${outFile.length()} marker=$marker pid=${pid ?: -1}")
    }

    /**
     * Persist the current marker to be used on the NEXT app launch for prev-session logcat staging.
     */
    private fun persistCurrentLogcatMarker(context: Context) {
        val appCtx = safeAppContext(context)
        val prefs = appCtx.getSharedPreferences(STATE_PREF_NAME, Context.MODE_PRIVATE)
        val marker = runCatching { formatLogcatMarker(Date()) }.getOrDefault("")
        if (marker.isBlank()) return
        val ok = prefs.edit()
            .putString(KEY_LAST_LOGCAT_MARKER, marker)
            .commit()

        if (!ok) {
            Log.w(TAG, "Failed to persist logcat marker for next run.")
            return
        }

        Log.d(TAG, "Persisted logcat marker for next run: $marker")
    }

    // -----------------------------------------------------------------------------
    // Ring store upload (raw directory)
    // -----------------------------------------------------------------------------

    /**
     * Upload the raw AppRingLogStore segment files.
     *
     * The ring store is part of this application module, so use its typed API
     * directly instead of reflection. A per-capture remote directory keeps
     * concurrently unfinished unique WorkManager jobs from colliding on stable
     * segment names.
     */
    private fun stageAndEnqueueRingStoreUploads(
        context: Context,
        filesDir: File,
        ghMirrorDir: File,
        cfg: GitHubUploader.GitHubConfig,
        where: String
    ) {
        if (!stagedRingUploadThisProcess.compareAndSet(false, true)) {
            Log.d(
                TAG,
                "Ring store upload already staged in this process; skipping. where=$where"
            )
            return
        }

        try {
            val ringDir =
                runCatching {
                    AppRingLogStore.ringDir(context)
                }.getOrElse {
                    File(filesDir, "diagnostics/applog_ring")
                }

            if (!ringDir.isDirectory) {
                stagedRingUploadThisProcess.set(false)
                Log.d(
                    TAG,
                    "Ring store dir not found; skipping ring upload. where=$where"
                )
                return
            }

            val all =
                listFilesRecursively(ringDir)
                    .filter {
                        it.isFile &&
                                it.length() > 0L &&
                                !it.name.startsWith(".")
                    }

            if (all.isEmpty()) {
                /**
                 * The logger may have been installed but its first async write may
                 * not have reached disk yet. Allow a later enqueue call to retry.
                 */
                stagedRingUploadThisProcess.set(false)
                Log.d(
                    TAG,
                    "Ring store dir empty; retry allowed. " +
                            "dir=${ringDir.absolutePath} where=$where"
                )
                return
            }

            val sorted =
                all.sortedByDescending { it.lastModified() }

            val stampUtc =
                formatFileTimestampUtc(Date())

            val remoteCaptureId =
                "${stampUtc}_pid${Process.myPid()}_" +
                        "u${SystemClock.elapsedRealtime() % 1_000_000L}"

            val remoteBase =
                "${REMOTE_RING_SUBDIR}/${remoteCaptureId}"

            val mirrorBaseDir =
                File(ghMirrorDir, remoteBase)

            ensureDirectory(mirrorBaseDir)

            var enqueuedCount = 0
            var enqueuedBytes = 0L

            for (src in sorted) {
                if (enqueuedCount >= MAX_RING_FILES_TO_ENQUEUE) {
                    break
                }

                if (enqueuedBytes >= MAX_RING_TOTAL_BYTES_TO_ENQUEUE) {
                    break
                }

                val rel =
                    safeRelativePathOrNull(
                        root = ringDir,
                        file = src
                    ) ?: continue

                val relNorm =
                    rel.replace('\\', '/')
                        .trimStart('/')

                if (relNorm.isBlank()) {
                    continue
                }

                val mirror =
                    runCatching {
                        mirrorCopyPreserveRelPath(
                            src = src,
                            mirrorBaseDir = mirrorBaseDir,
                            relNorm = relNorm
                        )
                    }.onFailure { e ->
                        Log.w(
                            TAG,
                            "Ring mirror copy failed: ${src.name} err=${e.message}",
                            e
                        )
                    }.getOrNull() ?: continue

                val remoteRelativePath =
                    "${remoteBase}/${relNorm}"

                Log.d(
                    TAG,
                    "GitHub enqueue(ring file): bytes=${mirror.length()} " +
                            "remote=$remoteRelativePath src=${src.name} where=$where"
                )

                enqueueGitHubWorkerFileUpload(
                    context = context,
                    cfg = cfg,
                    localFile = mirror,
                    remoteRelativePath = remoteRelativePath,
                    kindTag = "ring"
                )

                enqueuedCount++
                enqueuedBytes += mirror.length()
            }

            Log.d(
                TAG,
                "Ring store enqueue done. dir=${ringDir.absolutePath} " +
                        "files=$enqueuedCount bytes=$enqueuedBytes " +
                        "capFiles=$MAX_RING_FILES_TO_ENQUEUE " +
                        "capBytes=$MAX_RING_TOTAL_BYTES_TO_ENQUEUE where=$where"
            )
        } catch (t: Throwable) {
            stagedRingUploadThisProcess.set(false)
            throw t
        }
    }

    private fun listFilesRecursively(dir: File): List<File> {
        val out = ArrayList<File>(64)
        val stack = ArrayDeque<File>()
        stack.add(dir)

        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val children = cur.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) stack.add(c) else out.add(c)
            }
        }
        return out
    }

    private fun safeRelativePathOrNull(root: File, file: File): String? {
        return runCatching {
            val rootPath = root.canonicalFile.toPath()
            val filePath = file.canonicalFile.toPath()
            if (!filePath.startsWith(rootPath)) return null
            rootPath.relativize(filePath).toString()
        }.getOrNull()
    }

    private fun mirrorCopyPreserveRelPath(src: File, mirrorBaseDir: File, relNorm: String): File {
        val dst = File(mirrorBaseDir, relNorm)
        dst.parentFile?.let(::ensureDirectory)

        // Reuse existing mirror if identical.
        if (dst.exists() && dst.length() == src.length() && dst.lastModified() == src.lastModified()) {
            return dst
        }

        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
                output.flush()
            }
        }

        dst.setLastModified(src.lastModified())
        return dst
    }

    // -----------------------------------------------------------------------------
    // Mirror / file utils
    // -----------------------------------------------------------------------------

    private fun makeGitHubMirrorCopy(src: File, mirrorDir: File): File {
        ensureDirectory(mirrorDir)

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
        if (srcName.endsWith(".log")) {
            val base = srcName.removeSuffix(".log")
            var index = 2
            while (true) {
                val candidate = File(dir, "$base-$index.log")
                if (!candidate.exists()) return candidate
                index++
            }
        }

        val base = srcName
        var index = 2
        while (true) {
            val candidate = File(dir, "$base-$index")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun captureCrashToFile(
        filesDir: File,
        thread: Thread,
        throwable: Throwable
    ): File {
        val dir = crashDir(filesDir)
        ensureDirectory(dir)

        purgeOldFiles(
            dir = dir,
            maxKeep = MAX_FILES_TO_KEEP
        )

        val now = Date()
        val stampUtc = formatFileTimestampUtc(now)
        val stampLocal = formatHeaderTimestampLocal(now)

        val pid = Process.myPid()
        val tid = Process.myTid()
        val uptimeTail =
            SystemClock.elapsedRealtime() % 1_000_000L

        val name =
            "crash_${stampUtc}_pid${pid}_tid${tid}_u${uptimeTail}.log"

        val outFile = File(dir, name)

        /**
         * Put the uncaught exception itself into the app-owned ring first. The
         * snapshot method performs a short best-effort drain of queued writes.
         */
        runCatching {
            AppRingLogStore.log(
                level = "E",
                tag = TAG,
                msg = "uncaughtException thread=${thread.name}",
                tr = throwable
            )
        }

        /**
         * Capture the app-owned ring snapshot before spawning logcat. It is more
         * deterministic and survives cases where logcat is unavailable or already
         * overwritten.
         */
        val ringSnapshot =
            runCatching {
                AppRingLogStore.stageSnapshotForCrash(
                    crashDir = dir,
                    prefix = "applog"
                )
            }.onFailure { e ->
                Log.w(
                    TAG,
                    "App ring crash snapshot failed: ${e.message}",
                    e
                )
            }.getOrNull()

        val header = buildString {
            appendLine("=== Crash Report ===")
            appendLine("time_utc=$stampUtc")
            appendLine("time_local=$stampLocal")
            appendLine("pid=$pid")
            appendLine("tid=$tid")
            appendLine("thread=${sanitizeSingleLine(thread.name)}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("appId=${BuildConfig.APPLICATION_ID}")
            appendLine("versionName=${BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${BuildConfig.VERSION_CODE}")

            if (ringSnapshot != null) {
                appendLine("app_ring_snapshot=${ringSnapshot.name}")
                appendLine("app_ring_snapshot_bytes=${ringSnapshot.length()}")
            }

            appendLine()
            appendLine("=== Exception ===")
            appendLine(Log.getStackTraceString(throwable))
            appendLine()
            appendLine("=== Logcat (best-effort) ===")
        }.toByteArray(Charsets.UTF_8)

        val logBytes =
            collectLogcatBytesCurrentPid(
                pid = pid,
                maxBytes = MAX_LOGCAT_BYTES,
                maxMs = LOGCAT_MAX_MS
            )

        FileOutputStream(outFile).use { fos ->
            fos.write(header)
            fos.write(logBytes)
            fos.flush()

            runCatching {
                fos.fd.sync()
            }
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

    private fun collectLogcatBytesCurrentPid(
        pid: Int,
        maxBytes: Int,
        maxMs: Long
    ): ByteArray {
        val primary = listOf(
            "logcat", "-d",
            "-v", "threadtime",
            "-b", "main", "-b", "system", "-b", "crash",
            "--pid=$pid",
            "-t", "4000"
        )

        val fallback = listOf(
            "logcat", "-d",
            "-v", "threadtime",
            "-b", "main", "-b", "system", "-b", "crash",
            "-t", LOGCAT_TAIL_LINES_FALLBACK
        )

        return runCatching {
            execAndReadCapped(
                cmd = primary,
                maxBytes = maxBytes,
                maxMs = maxMs
            ).requireUsefulLogcat()
        }.recoverCatching {
            execAndReadCapped(
                cmd = fallback,
                maxBytes = maxBytes,
                maxMs = maxMs
            ).requireUsefulLogcat()
        }.getOrElse { e ->
            ("(logcat capture failed: ${e.message})\n")
                .toByteArray(Charsets.UTF_8)
        }
    }

    private fun collectLogcatBytesSinceMarker(
        marker: String,
        preferredPid: Int?,
        maxBytes: Int,
        maxMs: Long
    ): ByteArray {
        val withPid =
            preferredPid
                ?.takeIf { it > 0 }
                ?.let { pid ->
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
            val first =
                if (withPid != null) {
                    execAndReadCapped(
                        cmd = withPid,
                        maxBytes = maxBytes,
                        maxMs = maxMs
                    )
                } else {
                    execAndReadCapped(
                        cmd = noPid,
                        maxBytes = maxBytes,
                        maxMs = maxMs
                    )
                }

            first.requireUsefulLogcat()
        }.recoverCatching {
            execAndReadCapped(
                cmd = noPid,
                maxBytes = maxBytes,
                maxMs = maxMs
            ).requireUsefulLogcat()
        }.recoverCatching {
            execAndReadCapped(
                cmd = fallback,
                maxBytes = maxBytes,
                maxMs = maxMs
            ).requireUsefulLogcat()
        }.getOrElse { e ->
            ("(prev-session logcat capture failed: ${e.message})\n")
                .toByteArray(Charsets.UTF_8)
        }
    }

    /**
     * Execute logcat with a real wall-clock timeout.
     *
     * The previous implementation checked elapsed time only before calling
     * InputStream.read(). A blocked read could therefore exceed maxMs
     * indefinitely. Reading on a daemon thread lets the caller enforce a hard
     * wait bound and terminate the subprocess when necessary.
     */
    private fun execAndReadCapped(
        cmd: List<String>,
        maxBytes: Int,
        maxMs: Long
    ): ByteArray {
        val safeMaxBytes =
            maxBytes.coerceAtLeast(0)

        if (safeMaxBytes == 0) {
            return ByteArray(0)
        }

        val process =
            ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()

        val collector =
            CappedByteCollector(safeMaxBytes)

        val readerError =
            AtomicReference<Throwable?>(null)

        val readerDone =
            CountDownLatch(1)

        val readerThread =
            Thread(
                {
                    try {
                        process.inputStream.use { input ->
                            val buffer = ByteArray(16 * 1024)

                            while (collector.remaining() > 0) {
                                val count =
                                    input.read(
                                        buffer,
                                        0,
                                        min(
                                            buffer.size,
                                            collector.remaining()
                                        )
                                    )

                                if (count <= 0) {
                                    break
                                }

                                collector.write(
                                    buffer = buffer,
                                    offset = 0,
                                    count = count
                                )
                            }
                        }
                    } catch (t: Throwable) {
                        readerError.set(t)
                    } finally {
                        readerDone.countDown()
                    }
                },
                "CrashCapture-LogcatReader"
            ).apply {
                isDaemon = true
            }

        readerThread.start()

        val completed =
            runCatching {
                readerDone.await(
                    maxMs.coerceAtLeast(1L),
                    TimeUnit.MILLISECONDS
                )
            }.getOrDefault(false)

        if (!completed) {
            runCatching {
                process.destroy()
            }

            runCatching {
                process.waitFor(
                    LOGCAT_WAITFOR_MS,
                    TimeUnit.MILLISECONDS
                )
            }

            if (process.isAlive) {
                runCatching {
                    process.destroyForcibly()
                }
            }

            runCatching {
                readerDone.await(
                    LOGCAT_WAITFOR_MS,
                    TimeUnit.MILLISECONDS
                )
            }

            collector.appendUtf8(
                "\n(logcat capture timed out after ${maxMs}ms)\n"
            )
        } else {
            runCatching {
                process.waitFor(
                    LOGCAT_WAITFOR_MS,
                    TimeUnit.MILLISECONDS
                )
            }
        }

        runCatching {
            if (process.isAlive) {
                process.destroy()
            }
        }

        runCatching {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }

        val error = readerError.get()

        if (
            error != null &&
            collector.size() == 0
        ) {
            throw error
        }

        return collector.toByteArray()
    }

    private fun ByteArray.requireUsefulLogcat(): ByteArray {
        if (isEmpty()) {
            throw IllegalStateException("logcat returned no data")
        }

        return this
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
    private fun buildCrashGitHubConfigOrNull(
        context: Context
    ): GitHubUploader.GitHubConfig? {
        val fromStore =
            runCatching {
                GitHubDiagnosticsConfigStore
                    .buildGitHubConfigOrNull(context)
            }.getOrNull()

        val base =
            fromStore
                ?: runCatching {
                    buildCrashGitHubConfigFromBuildConfig()
                }.getOrNull()
                ?: return null

        return normalizeCrashGitHubConfig(base)
    }

    private fun normalizeCrashGitHubConfig(
        cfg: GitHubUploader.GitHubConfig
    ): GitHubUploader.GitHubConfig? {
        var owner = cfg.owner.trim()
        var repo = cfg.repo.trim()
        val token = cfg.token.trim()
        val branch = cfg.branch.trim().ifBlank { "main" }

        if (repo.contains('/')) {
            val inferredOwner =
                repo.substringBefore('/').trim()

            val inferredRepo =
                repo.substringAfterLast('/').trim()

            if (owner.isBlank()) {
                owner = inferredOwner
            }

            repo = inferredRepo
        }

        if (
            owner.isBlank() ||
            repo.isBlank() ||
            token.isBlank()
        ) {
            return null
        }

        if (
            owner.any(Char::isWhitespace) ||
            repo.any(Char::isWhitespace)
        ) {
            return null
        }

        return cfg.copy(
            owner = owner,
            repo = repo,
            token = token,
            branch = branch,
            pathPrefix = computeCrashPrefix(cfg.pathPrefix)
        )
    }

    private fun computeCrashPrefix(basePrefix: String): String {
        val p = basePrefix.trim('/')
        if (p.endsWith("diagnostics/crash")) return p
        if (p.endsWith("diagnostics")) return listOf(p, "crash").filter { it.isNotBlank() }.joinToString("/")
        return listOf(p, "diagnostics/crash").filter { it.isNotBlank() }.joinToString("/")
    }

    private fun buildCrashGitHubConfigFromBuildConfig(): GitHubUploader.GitHubConfig? {
        val token = BuildConfig.GH_TOKEN.trim()
        val rawRepo = BuildConfig.GH_REPO.trim()

        if (token.isBlank() || rawRepo.isBlank()) {
            return null
        }

        var owner = BuildConfig.GH_OWNER.trim()
        var repo = rawRepo

        if (rawRepo.contains('/')) {
            if (owner.isBlank()) {
                owner = rawRepo.substringBefore('/').trim()
            }

            repo = rawRepo.substringAfterLast('/').trim()
        }

        if (owner.isBlank() || repo.isBlank()) {
            return null
        }

        return GitHubUploader.GitHubConfig(
            owner = owner,
            repo = repo,
            branch = BuildConfig.GH_BRANCH.trim().ifBlank { "main" },
            pathPrefix = BuildConfig.GH_PATH_PREFIX.trim().trim('/'),
            token = token
        )
    }

    private fun enqueueGitHubWorkerFileUpload(
        context: Context,
        cfg: GitHubUploader.GitHubConfig,
        localFile: File,
        remoteRelativePath: String,
        kindTag: String
    ) {
        val safeUnique = sanitizeWorkName("${cfg.pathPrefix}/$remoteRelativePath")
        val uniqueName = "gh_upload_$safeUnique"

        val req: OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                .setInputData(
                    workDataOf(
                        GitHubUploadWorker.KEY_MODE to "file",
                        GitHubUploadWorker.KEY_OWNER to cfg.owner,
                        GitHubUploadWorker.KEY_REPO to cfg.repo,
                        GitHubUploadWorker.KEY_BRANCH to cfg.branch,
                        GitHubUploadWorker.KEY_PATH_PREFIX to cfg.pathPrefix,
                        GitHubUploadWorker.KEY_FILE_PATH to localFile.absolutePath,
                        // IMPORTANT: allow path separators in "file name" so worker can upload into subdirs.
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
                .addTag("${GitHubUploadWorker.TAG}:$kindTag:$safeUnique")
                .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, req)
    }

    private fun sanitizeWorkName(value: String): String {
        val raw = value.trim()

        val hash =
            Integer.toHexString(raw.hashCode())

        val stem =
            raw.replace(
                Regex("""[^\w\-.]+"""),
                "_"
            )
                .trim('_')
                .take(96)
                .ifBlank { "work" }

        return "${stem}_$hash"
            .take(120)
    }

    private fun formatFileTimestampUtc(date: Date): String {
        return synchronized(timestampLock) {
            fileTsUtc.format(date)
        }
    }

    private fun formatHeaderTimestampLocal(date: Date): String {
        return synchronized(timestampLock) {
            headerTsLocal.format(date)
        }
    }

    private fun formatLogcatMarker(date: Date): String {
        return synchronized(timestampLock) {
            logcatMarkerFormat.format(date)
        }
    }

    private fun sanitizeSingleLine(value: String): String {
        return value
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\u0000', ' ')
    }

    private fun ensureDirectory(dir: File) {
        if (dir.isDirectory) {
            return
        }

        if (dir.exists() && !dir.isDirectory) {
            throw IllegalStateException(
                "Path exists but is not a directory: ${dir.absolutePath}"
            )
        }

        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IllegalStateException(
                "Failed to create directory: ${dir.absolutePath}"
            )
        }
    }

    private fun writeBytesDurably(
        file: File,
        bytes: ByteArray
    ) {
        file.parentFile?.let(::ensureDirectory)

        FileOutputStream(file).use { fos ->
            fos.write(bytes)
            fos.flush()

            runCatching {
                fos.fd.sync()
            }
        }
    }

    /**
     * Thread-safe bounded collector for subprocess output.
     */
    private class CappedByteCollector(
        capacity: Int
    ) {
        private val buffer =
            ByteArray(capacity.coerceAtLeast(0))

        private var size = 0

        @Synchronized
        fun remaining(): Int =
            buffer.size - size

        @Synchronized
        fun size(): Int =
            size

        @Synchronized
        fun write(
            buffer: ByteArray,
            offset: Int,
            count: Int
        ) {
            if (count <= 0 || size >= this.buffer.size) {
                return
            }

            val safeCount =
                min(
                    count,
                    this.buffer.size - size
                )

            System.arraycopy(
                buffer,
                offset,
                this.buffer,
                size,
                safeCount
            )

            size += safeCount
        }

        fun appendUtf8(text: String) {
            val bytes =
                text.toByteArray(Charsets.UTF_8)

            write(
                buffer = bytes,
                offset = 0,
                count = bytes.size
            )
        }

        @Synchronized
        fun toByteArray(): ByteArray =
            buffer.copyOfRange(0, size)
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
            // Avoid an immediate self-loop.
            if (newDelegate === this) return
            delegate = newDelegate
        }

        fun delegateSnapshot(): Thread.UncaughtExceptionHandler? = delegate

        override fun uncaughtException(
            thread: Thread,
            throwable: Throwable
        ) {
            if (!capturing.compareAndSet(false, true)) {
                try {
                    val currentDelegate = delegate

                    if (currentDelegate != null) {
                        currentDelegate.uncaughtException(
                            thread,
                            throwable
                        )
                    } else {
                        onHardKill()
                    }
                } catch (_: Throwable) {
                    onHardKill()
                }

                return
            }

            try {
                val file =
                    runCatching {
                        captureCrashToFile(
                            filesDir = filesDir,
                            thread = thread,
                            throwable = throwable
                        )
                    }.onFailure { e ->
                        Log.e(
                            TAG,
                            "Crash capture failed: ${e.message}",
                            e
                        )
                    }.getOrNull()

                if (file != null) {
                    Log.e(
                        TAG,
                        "Crash captured: ${file.absolutePath} " +
                                "bytes=${file.length()}"
                    )
                }
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    "Crash capture unexpected failure: ${t.message}",
                    t
                )
            } finally {
                try {
                    val currentDelegate = delegate

                    if (currentDelegate != null) {
                        currentDelegate.uncaughtException(
                            thread,
                            throwable
                        )

                        /**
                         * The platform's default handler normally terminates the
                         * process and never returns. If a custom delegate does
                         * return, allow a future uncaught exception to be captured.
                         */
                        capturing.set(false)
                    } else {
                        onHardKill()
                    }
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