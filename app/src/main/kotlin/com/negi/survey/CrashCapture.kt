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
import com.negi.survey.diagnostics.SurveyDiagnosticAttribution
import com.negi.survey.diagnostics.V2DiagnosticLogs
import com.negi.survey.net.V2ArtifactStore
import java.time.Instant
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.negi.survey.diagnostics.DiagnosticContentPolicy
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.File
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object CrashCapture {

    private const val TAG = "CrashCapture"

    /** Local directory for staged crash artifacts (.log). */
    private const val CRASH_DIR_REL = "diagnostics/crash"

    private const val MAX_LOGCAT_BYTES = 1_400_000
    private const val LOGCAT_MAX_MS = 900L

    private const val LOGCAT_TAIL_LINES_FALLBACK = "5000"
    private const val LOGCAT_TAIL_LINES_SINCE = "12000"

    private const val MAX_FILES_TO_KEEP = 120

    /** Maximum number of new ApplicationExitInfo records staged per launch. */
    private const val MAX_EXIT_INFOS_TO_STAGE = 16

    /** Preserve only a bounded amount of system-provided trace data per exit. */
    private const val MAX_EXIT_TRACE_BYTES = 768 * 1024

    /** Prevent re-enqueue storms on rapid app restarts / multiple entry points. */
    private const val ENQUEUE_COOLDOWN_MS = 1200L

    /** Try to force logcat process shutdown quickly (best-effort). */
    private const val LOGCAT_WAITFOR_MS = 90L

    /** Prevent ensure storms (handler-chain verification). */
    private const val ENSURE_COOLDOWN_MS = 800L

    /** SharedPreferences for previous-session staging state. */
    private const val STATE_PREF_NAME = "crash_capture_state_v2_artifacts"
    private const val KEY_LAST_EXIT_TS = "last_exit_ts"
    private const val KEY_LAST_EXIT_PID = "last_exit_pid"
    private const val KEY_LAST_LOGCAT_MARKER = "last_logcat_marker"

    /** Stage guards (once per current process). */
    private val stagedPrevSessionThisProcess = AtomicBoolean(false)
    private val stagedExitInfoThisProcess = AtomicBoolean(false)

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

    /** Collects previous-process crashes and logcat on a background thread. */
    fun stagePreviousProcessDiagnostics(context: Context, where: String) {
        // Offload heavy staging (logcat / IO) from main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val appCtx = safeAppContext(context)
            val label = where.ifBlank { "enqueue(main_offload)" }
            Thread(
                {
                    runCatching { stagePreviousProcessDiagnostics(appCtx, "$label:bg") }
                        .onFailure { e -> DiagnosticContentPolicy.logWarning(TAG, "enqueue offload failed where=$label", e) }
                },
                "CrashCapture-Enqueue"
            ).apply { isDaemon = true }.start()
            return
        }

        val root = filesDirRoot ?: runCatching { context.filesDir }.getOrNull()
        if (root == null) {
            Log.w(TAG, "stagePreviousProcessDiagnostics: filesDir unavailable; skipping. where=$where")
            return
        }

        if (!enqueueing.compareAndSet(false, true)) {
            Log.d(TAG, "stagePreviousProcessDiagnostics skipped (already running). where=$where")
            return
        }

        try {
            val now = SystemClock.elapsedRealtime()
            val prev = lastEnqueueAt.get()
            val dt = now - prev
            if (prev != 0L && dt in 0 until ENQUEUE_COOLDOWN_MS) {
                Log.d(
                    TAG,
                    "stagePreviousProcessDiagnostics skipped (cooldown). dt=${dt}ms where=$where " +
                            "thread=${Thread.currentThread().name}"
                )
                return
            }
            lastEnqueueAt.set(now)

            val appCtx = safeAppContext(context)

            Log.d(
                TAG,
                "stagePreviousProcessDiagnostics enter where=$where pid=${Process.myPid()} " +
                        "thread=${Thread.currentThread().name}"
            )

            recoverJavaCrashCaptures(appCtx)

            // Stage previous-run artifacts without a WorkManager dependency.
            val stagedExitPid = runCatching { stageLastExitInfoIfNeeded(appCtx, root) }
                .onFailure { e -> DiagnosticContentPolicy.logWarning(TAG, "stageLastExitInfoIfNeeded failed", e) }
                .getOrNull()

            runCatching {
                stagePreviousSessionLogcatIfNeeded(appCtx, root, stagedExitPid)
                persistCurrentLogcatMarker(appCtx)
            }.onFailure { error ->
                // Preserve the previous collection window so a later launch can retry it.
                stagedPrevSessionThisProcess.set(false)
                DiagnosticContentPolicy.logWarning(TAG, "Previous-session logcat staging deferred", error)
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

        // Create one stable handler instance for the lifetime of the process.
        val h = handler ?: synchronized(this) {
            handler ?: CrashHandler(
                context = context,
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
                    context = appCtx,
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
        context: Context,
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

        val summary = info.processStateSummary
        val profile = SurveyDiagnosticAttribution.exitProfile(summary)
        if (profile != null && exitTs > 0L && info.processName == context.packageName) {
            val event = Instant.ofEpochMilli(exitTs)
            stageV2ExitRecord(
                context, dir, reason, info.status, pid, event, profile,
                SurveyDiagnosticAttribution.exitEvidence(context, summary, pid, event),
            ) { output ->
                output.write(text)
                traceResult?.let {
                    output.write("\n=== Encoded system trace ===\n".toByteArray())
                    val base64 = android.util.Base64OutputStream(output, android.util.Base64.NO_CLOSE)
                    base64.use { encoded -> it.file.inputStream().use { input -> input.copyTo(encoded) } }
                }
            }
        }

        Log.d(
            TAG,
            "Staged exit info: ${outFile.absolutePath} " +
                    "bytes=${outFile.length()} reason=$reason pid=$pid"
        )
    }

    internal fun stageV2ExitRecord(
        context: Context, directory: File, reason: Int, status: Int, pid: Int, event: Instant,
        profile: V2ArtifactStore.Profile, evidence: SurveyDiagnosticAttribution.Interval?,
        write: (java.io.OutputStream) -> Unit,
    ) {
        if (!isDiagnosticExitReason(reason)) return
        val id = V2ArtifactStore.stableId("exit|${event.toEpochMilli()}|$pid|$reason")
        V2DiagnosticLogs.stage(
            context, V2ArtifactStore.Kind.CRASH, id, event, event, event,
            "application_exit_record", evidence, profile, write = write,
        )
        if (!isCrashExit(reason, status)) return
        // The next-launch snapshot can span processes, so it has no exclusive survey attribution.
        AppRingLogStore.stageSnapshotForCrash(directory, "applog_exit")?.takeIf { it.length() > 0 }?.let { snapshot ->
            V2DiagnosticLogs.stage(
                context, V2ArtifactStore.Kind.CRASH, V2ArtifactStore.stableId("exit-ring|$id"),
                event, event, Instant.now().coerceAtLeast(event), "cross_process_crash_ring_snapshot", null, profile,
            ) { output -> snapshot.inputStream().use { it.copyTo(output) } }
        }
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

    internal fun isCrashExit(reason: Int, status: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> true
        ApplicationExitInfo.REASON_SIGNALED -> status in setOf(
            android.system.OsConstants.SIGILL, android.system.OsConstants.SIGTRAP,
            android.system.OsConstants.SIGABRT, android.system.OsConstants.SIGBUS,
            android.system.OsConstants.SIGFPE, android.system.OsConstants.SIGSEGV,
        )
        else -> false
    }

    internal fun isDiagnosticExitReason(reason: Int): Boolean {
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

        V2DiagnosticLogs.stage(
            appCtx, V2ArtifactStore.Kind.LOGCAT, V2ArtifactStore.stableId("previous-logcat|$marker"),
            now.toInstant(), now.toInstant(), Instant.now(), "previous_process_logcat_since_marker", null,
        ) { it.write(payload) }

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

    internal fun captureCrashToFile(
        context: Context,
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

        val event = now.toInstant()
        val session = SurveyDiagnosticAttribution.processSession
        val crashId = V2ArtifactStore.stableId("java-crash|$session|$name")
        val captureDirectory = File(javaCrashRoot(context), crashId)
        ensureDirectory(captureDirectory)
        val outFile = File(captureDirectory, name)

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
                    crashDir = captureDirectory,
                    prefix = "applog"
                )
            }.onFailure { e ->
                DiagnosticContentPolicy.logWarning(TAG, "App ring crash snapshot failed", e)
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
            appendLine(DiagnosticContentPolicy.throwableDetails(throwable))
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
            fos.fd.sync()
        }

        val capture = JavaCrashCapture(
            event.toEpochMilli(), System.currentTimeMillis(), session, pid, V2ArtifactStore.profile,
            name, header.size, ringSnapshot?.takeIf { it.length() > 0L }?.name,
            SurveyDiagnosticAttribution.currentProcessEvidence(event, event),
        )
        V2ArtifactStore.writeAtomically(File(captureDirectory, "capture"), Json.encodeToString(JavaCrashCapture.serializer(), capture))
        V2ArtifactStore.syncDirectory(captureDirectory.parentFile!!)
        V2ArtifactStore.syncDirectory(context.noBackupFilesDir)
        runCatching { stageJavaCrashCapture(context, captureDirectory, capture) }
            .onFailure { DiagnosticContentPolicy.logWarning(TAG, "V2 crash artifact staging failed", it) }

        return outFile
    }

    @Serializable
    private data class JavaCrashCapture(
        val eventMillis: Long,
        val capturedMillis: Long,
        val processSession: String,
        val pid: Int,
        val profile: V2ArtifactStore.Profile,
        val reportName: String,
        val headerSize: Int,
        val ringName: String?,
        val evidence: SurveyDiagnosticAttribution.Interval?,
    )

    private fun javaCrashRoot(context: Context) = File(context.noBackupFilesDir, "v2_java_crashes").canonicalFile

    internal fun recoverJavaCrashCaptures(context: Context) {
        javaCrashRoot(context).listFiles().orEmpty().filter(File::isDirectory).forEach { directory ->
            runCatching {
                val manifest = File(directory, "capture")
                if (!manifest.isFile) return@runCatching
                val capture = Json.decodeFromString(JavaCrashCapture.serializer(), manifest.readText())
                if (capture.processSession != SurveyDiagnosticAttribution.processSession) {
                    stageJavaCrashCapture(context, directory, capture)
                }
            }.onFailure { DiagnosticContentPolicy.logWarning(TAG, "Original crash capture retained", it) }
        }
    }

    private fun stageJavaCrashCapture(context: Context, directory: File, capture: JavaCrashCapture) {
        val crashId = V2ArtifactStore.stableId("java-crash|${capture.processSession}|${capture.reportName}")
        check(directory.name == crashId && directory.canonicalFile == directory.absoluteFile) {
            "Invalid Java crash capture directory."
        }
        val report = crashSource(directory, capture.reportName).readBytes()
        val ring = capture.ringName?.let { crashSource(directory, it) }
        check(capture.headerSize in 1..report.size)
        val event = Instant.ofEpochMilli(capture.eventMillis)
        val captured = Instant.ofEpochMilli(capture.capturedMillis).coerceAtLeast(event)
        V2DiagnosticLogs.stage(
            context, V2ArtifactStore.Kind.CRASH, crashId, event, event, event,
            "uncaught_exception_record", capture.evidence, capture.profile,
            collectorSession = capture.processSession, collectorPid = capture.pid,
        ) { it.write(report, 0, capture.headerSize) }
        if (report.size > capture.headerSize) {
            V2DiagnosticLogs.stage(
                context, V2ArtifactStore.Kind.LOGCAT, V2ArtifactStore.stableId("crash-logcat|$crashId"),
                event, event, captured, "crash_logcat_unbounded_buffer", null, capture.profile,
                collectorSession = capture.processSession, collectorPid = capture.pid,
            ) { it.write(report, capture.headerSize, report.size - capture.headerSize) }
        }
        if (ring != null) {
            V2DiagnosticLogs.stage(
                context, V2ArtifactStore.Kind.CRASH, V2ArtifactStore.stableId("crash-ring|$crashId"),
                event, event, captured, "crash_ring_snapshot_unbounded_history", null, capture.profile,
                collectorSession = capture.processSession, collectorPid = capture.pid,
            ) { output -> ring.inputStream().use { it.copyTo(output) } }
        }

        // The staged copies now own delivery. Retire the replay marker before the source files.
        check(File(directory, "capture").delete())
        V2ArtifactStore.syncDirectory(directory)
        directory.deleteRecursively()
    }

    private fun crashSource(directory: File, name: String): File {
        check(name == File(name).name && name.endsWith(".log"))
        return File(directory, name).also { check(it.isFile && it.canonicalFile == it.absoluteFile) }
    }

    private fun crashDir(filesDir: File): File = File(filesDir, CRASH_DIR_REL)


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
            ("(logcat capture failed: ${DiagnosticContentPolicy.errorDescription(e)})\n")
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
            ("(prev-session logcat capture failed: ${DiagnosticContentPolicy.errorDescription(e)})\n")
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
    internal fun execAndReadCapped(
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
        private val context: Context,
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
                            context = context,
                            filesDir = filesDir,
                            thread = thread,
                            throwable = throwable
                        )
                    }.onFailure { e ->
                        DiagnosticContentPolicy.logError(TAG, "Crash capture failed", e)
                    }.getOrNull()

                if (file != null) {
                    Log.e(
                        TAG,
                        "Crash captured: ${file.name}"
                    )
                }
            } catch (t: Throwable) {
                DiagnosticContentPolicy.logError(TAG, "Crash capture unexpected failure", t)
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
