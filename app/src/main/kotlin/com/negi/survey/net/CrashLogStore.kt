/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: CrashLogStore.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Captures crash reports into local gz bundles + schedules deferred uploads.
 *
 *  Design goals:
 *   - Crash-time code must be fast and never throw.
 *   - Upload happens later via WorkManager (GitHubUploadWorker).
 *   - Prefer app-owned RuntimeLogStore logs for reliability in RELEASE.
 *
 *  Native crash note:
 *   - SIGSEGV/native crashes skip UncaughtExceptionHandler.
 *   - Capture native trace on the next start via ApplicationExitInfo (API 30+).
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import com.negi.survey.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlin.math.min
import kotlin.system.measureTimeMillis
import kotlin.system.exitProcess

private const val TAG = "CrashLogStore"

object CrashLogStore {

    /** Directory under internal storage for pending crash bundles. */
    private const val DIR_PENDING_CRASH = "pending_uploads/crashlogs"

    /** Preference file for de-dup + crash marker flags. */
    private const val PREFS_NAME = "crash_log_store"

    private const val PREF_KEY_LAST_EXIT_TS = "last_exit_timestamp"
    private const val PREF_KEY_LAST_EXIT_PID = "last_exit_pid"
    private const val PREF_KEY_LAST_EXIT_REASON = "last_exit_reason"
    private const val PREF_KEY_LAST_EXIT_PROC = "last_exit_process"
    private const val PREF_KEY_LAST_RUN_CRASHED = "last_run_crashed"

    /**
     * Runtime toggle for including logcat in bundles (mainly for internal builds).
     * - DEBUG: default true
     * - RELEASE: default false
     */
    private const val PREF_KEY_INCLUDE_LOGCAT = "include_logcat_in_bundle"

    /**
     * Runtime toggle for allowing non-PID logcat fallback (can include other processes).
     * - DEBUG: default true
     * - RELEASE: default false
     */
    private const val PREF_KEY_ALLOW_LOGCAT_FALLBACK = "allow_logcat_fallback"

    /** Keep last N lines from logcat main buffer. */
    private const val LOGCAT_TAIL_LINES = 1200

    /** Keep last N lines from the crash buffer. */
    private const val LOGCAT_CRASH_TAIL_LINES = 200

    /** Include up to this many bytes from RuntimeLogStore tail. */
    private const val RUNTIME_LOG_TAIL_BYTES = 260_000

    /** Hard cap for uncompressed bundle bytes (before gzip). */
    private const val MAX_UNCOMPRESSED_BYTES = 900_000

    /** Operational cap for gz bundle bytes. */
    private const val BUNDLE_GZ_MAX_BYTES_DEFAULT = 900_000

    /** Command stdout cap to avoid runaway allocations. */
    private const val COMMAND_STDOUT_MAX_BYTES = 260_000

    /** Max bytes we read from ApplicationExitInfo.traceInputStream. */
    private const val EXITINFO_TRACE_MAX_BYTES = 650_000

    /** Max command time in crash path (keep short). */
    private const val LOGCAT_CMD_TIMEOUT_MS = 900L

    /** Prevent handler being installed multiple times in the same process. */
    private val installed = AtomicBoolean(false)

    /** Prevent startup tasks from running multiple times per process. */
    private val startupTasksRan = AtomicBoolean(false)

    /** Upper bound for how many pending files we schedule per app start. */
    private const val MAX_FILES_TO_SCHEDULE_PER_START = 20

    /** Prune guard: keep only newest N files. */
    private const val MAX_PENDING_FILES_TO_KEEP = 120

    /** Prune guard: delete files older than this (days). */
    private const val MAX_PENDING_AGE_DAYS = 21L

    /**
     * Install an UncaughtExceptionHandler that writes a crash bundle to disk.
     *
     * Notes:
     * - This does NOT prevent the crash.
     * - It chains to the previous default handler after writing.
     * - Startup tasks run once per process:
     *    - capture ApplicationExitInfo trace (native crash) if any
     *    - prune pending
     *    - schedule pending uploads
     *    - if last run crashed, schedule runtime logs upload (best-effort)
     */
    fun install(context: Context) {
        val appContext = context.applicationContext

        // Avoid doing anything in non-main process to prevent duplicate scheduling.
        if (!isMainProcess(appContext)) {
            Log.d(TAG, "Not main process; skip CrashLogStore.install().")
            return
        }

        if (startupTasksRan.compareAndSet(false, true)) {
            runCatching { captureLastExitInfoBundleIfPossible(appContext) }
                .onFailure { e -> Log.w(TAG, "captureLastExitInfoBundleIfPossible failed: ${e.message}", e) }

            runCatching { prunePendingFiles(appContext) }
                .onFailure { e -> Log.w(TAG, "prunePendingFiles failed: ${e.message}", e) }

            runCatching { schedulePendingUploadsFromBestAvailableConfig(appContext) }
                .onFailure { e -> Log.w(TAG, "schedulePendingUploads failed: ${e.message}", e) }
        }

        if (!installed.compareAndSet(false, true)) {
            Log.d(TAG, "CrashLogStore already installed; skip handler re-install.")
            return
        }

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
            runCatching {
                // Mark that we crashed, so next start can also ship runtime logs.
                prefs(appContext).edit().putBoolean(PREF_KEY_LAST_RUN_CRASHED, true).apply()
            }

            runCatching {
                writeCrashBundle(appContext, thread, throwable)
            }.onFailure { e ->
                Log.w(TAG, "Crash bundle write failed: ${e.message}", e)
            }

            try {
                prev?.uncaughtException(thread, throwable)
            } catch (_: Throwable) {
                runCatching { Process.killProcess(Process.myPid()) }
                runCatching { exitProcess(10) }
            }
        }

        Thread.setDefaultUncaughtExceptionHandler(handler)
        Log.i(TAG, "CrashLogStore installed.")
    }

    /**
     * Schedule uploads using the best available configuration source.
     *
     * Priority:
     *  1) GitHubDiagnosticsConfigStore (persisted runtime config)
     *  2) BuildConfig (compile-time config; avoid embedding secrets in production if possible)
     */
    fun schedulePendingUploadsFromBestAvailableConfig(context: Context) {
        val fromStore = buildGitHubConfigFromConfigStoreOrNull(context)
        val cfg = fromStore ?: buildGitHubConfigFromBuildConfigOrNull()

        if (cfg == null) {
            Log.w(
                TAG,
                "No usable GitHub config for crashlog uploads. " +
                        "ConfigStoreReady=${fromStore != null} " +
                        "BuildConfigHints(ownerSet=${BuildConfig.GH_OWNER.isNotBlank()} repoSet=${BuildConfig.GH_REPO.isNotBlank()} tokenSet=${BuildConfig.GH_TOKEN.isNotBlank()})"
            )
            return
        }

        schedulePendingUploads(context, cfg)

        // If last run crashed, also ship runtime logs as a separate upload (zip).
        val p = prefs(context)
        val lastRunCrashed = p.getBoolean(PREF_KEY_LAST_RUN_CRASHED, false)
        if (lastRunCrashed) {
            runCatching {
                GitHubUploadWorker.enqueueRuntimeLogsUpload(
                    context = context,
                    cfg = cfg.copy(pathPrefix = cfg.pathPrefix.trim('/')), // keep prefix as-is
                    remoteDir = "diagnostics/runtime_logs",
                    addDateSubdir = true,
                    reason = "crash",
                    deleteZipAfter = true
                )
                p.edit().putBoolean(PREF_KEY_LAST_RUN_CRASHED, false).apply()
                Log.i(TAG, "Scheduled runtime logs upload due to previous crash.")
            }.onFailure { e ->
                Log.w(TAG, "Failed to schedule runtime logs upload: ${e.message}", e)
            }
        }
    }

    private fun buildGitHubConfigFromConfigStoreOrNull(context: Context): GitHubUploader.GitHubConfig? {
        val baseCfg = GitHubDiagnosticsConfigStore.buildGitHubConfigOrNull(context)
        if (baseCfg == null) {
            Log.d(TAG, "GitHub config store not ready; will try BuildConfig fallback.")
            return null
        }

        val uploadCfg = baseCfg.copy(pathPrefix = "diagnostics/crashlogs")
        Log.d(
            TAG,
            "Using GitHub config from ConfigStore. owner=${uploadCfg.owner} repo=${uploadCfg.repo} branch=${uploadCfg.branch} prefix=${uploadCfg.pathPrefix}"
        )
        return uploadCfg
    }

    private fun buildGitHubConfigFromBuildConfigOrNull(): GitHubUploader.GitHubConfig? {
        val token = BuildConfig.GH_TOKEN.trim()
        if (token.isBlank()) return null

        val rawRepo = BuildConfig.GH_REPO.trim()
        if (rawRepo.isBlank()) return null

        val owner = BuildConfig.GH_OWNER.trim().ifBlank {
            rawRepo.substringBefore('/', missingDelimiterValue = "").trim()
        }
        val repo = rawRepo.substringAfterLast('/').trim()
        if (owner.isBlank() || repo.isBlank()) return null

        val basePrefix = BuildConfig.GH_PATH_PREFIX.trim('/')
        val crashPrefix = listOf(basePrefix, "diagnostics/crashlogs")
            .filter { it.isNotBlank() }
            .joinToString("/")

        val branch = BuildConfig.GH_BRANCH.ifBlank { "main" }

        Log.d(TAG, "Using GitHub config from BuildConfig. owner=$owner repo=$repo branch=$branch prefix=$crashPrefix")

        return GitHubUploader.GitHubConfig(
            owner = owner,
            repo = repo,
            branch = branch,
            pathPrefix = crashPrefix,
            token = token
        )
    }

    /**
     * Schedule uploads for pending crash bundles using the provided config.
     *
     * This enqueues one WorkManager job per file, and the worker deletes the file on success.
     */
    fun schedulePendingUploads(context: Context, cfg: GitHubUploader.GitHubConfig) {
        val dir = pendingDir(context)
        val files = dir.listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.filter { !it.name.endsWith(".tmp", ignoreCase = true) }
            ?: emptyList()

        if (files.isEmpty()) {
            Log.d(TAG, "No pending crash bundles.")
            return
        }

        val targets = files
            .sortedBy { it.lastModified() } // oldest first
            .take(MAX_FILES_TO_SCHEDULE_PER_START)

        targets.forEach { f ->
            GitHubUploadWorker.enqueueExistingPayload(
                context = context,
                cfg = cfg,
                file = f,
                maxBytesHint = f.length().coerceAtLeast(1L)
            )
        }

        Log.i(
            TAG,
            "Scheduled ${targets.size} crash bundle upload(s). " +
                    "pendingTotal=${files.size} dir=${dir.absolutePath}"
        )
    }

    /**
     * Capture the last process exit trace (native crash / ANR / etc.) on Android 11+.
     *
     * Output:
     * - Writes a gz bundle into the pending dir, so existing upload pipeline works.
     */
    fun captureLastExitInfoBundleIfPossible(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val p = prefs(context)

        val lastTs = p.getLong(PREF_KEY_LAST_EXIT_TS, -1L)
        val lastPid = p.getInt(PREF_KEY_LAST_EXIT_PID, -1)
        val lastReason = p.getInt(PREF_KEY_LAST_EXIT_REASON, -1)
        val lastProc = p.getString(PREF_KEY_LAST_EXIT_PROC, "") ?: ""

        val list = runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 12)
        }.getOrElse {
            Log.w(TAG, "getHistoricalProcessExitReasons failed: ${it.message}", it)
            return
        }

        if (list.isEmpty()) return

        val candidate = list
            .sortedByDescending { it.timestamp }
            .firstOrNull { info ->
                val hasTrace = runCatching { info.traceInputStream != null }.getOrDefault(false)
                val crashLike = isCrashLikeReason(info.reason)
                hasTrace || crashLike
            } ?: return

        val ts = candidate.timestamp
        val pid = candidate.pid
        val reason = candidate.reason
        val procName = candidate.processName ?: ""

        if (ts == lastTs && pid == lastPid && reason == lastReason && procName == lastProc) {
            Log.d(TAG, "Last exit info already captured. ts=$ts pid=$pid reason=$reason proc=$procName")
            return
        }

        val header = buildExitInfoHeader(context, candidate)

        val traceBytes = runCatching {
            candidate.traceInputStream?.use { readBytesLimited(it, EXITINFO_TRACE_MAX_BYTES) }
        }.getOrNull()

        val traceText = when {
            traceBytes == null -> "(traceInputStream is null or unreadable)\n"
            traceBytes.isEmpty() -> "(traceInputStream empty)\n"
            else -> runCatching { traceBytes.toString(Charsets.UTF_8) }
                .getOrElse { "(trace decode failed: ${it.message})\n" }
        }

        val runtimeTail = collectRuntimeLogsTailBestEffort(context, RUNTIME_LOG_TAIL_BYTES)

        val crashBuf = if (includeLogcatInBundles(context)) {
            runCatching { collectLogcatCrashTail(LOGCAT_CRASH_TAIL_LINES) }
                .getOrElse { "(logcat crash buffer read failed: ${it.message})\n" }
        } else {
            "(logcat disabled)\n"
        }

        val combined = buildString {
            appendLine("=== Last Exit Bundle (ApplicationExitInfo) ===")
            appendLine()
            append(header)
            appendLine()
            appendLine("=== Trace (ApplicationExitInfo.traceInputStream) ===")
            appendLine(traceText)
            appendLine()
            appendLine("=== RuntimeLogStore tail (best-effort) ===")
            appendLine(runtimeTail)
            appendLine()
            appendLine("=== Logcat crash buffer (tail) ===")
            appendLine(crashBuf)
            appendLine()
        }.toByteArray(Charsets.UTF_8)

        val trimmed = trimToTail(combined, MAX_UNCOMPRESSED_BYTES)
        val gz = gzipAndFitToMaxBytesBestEffort(trimmed, BUNDLE_GZ_MAX_BYTES_DEFAULT)

        val outFile = writePendingGzFile(
            context = context,
            prefix = "exitinfo",
            stampUtcMillis = ts,
            pid = pid,
            suffix = "reason$reason"
        ) { gz }

        if (outFile != null) {
            p.edit()
                .putLong(PREF_KEY_LAST_EXIT_TS, ts)
                .putInt(PREF_KEY_LAST_EXIT_PID, pid)
                .putInt(PREF_KEY_LAST_EXIT_REASON, reason)
                .putString(PREF_KEY_LAST_EXIT_PROC, procName)
                .apply()

            Log.i(TAG, "Last exit bundle written: ${outFile.absolutePath} (${gz.size} bytes gz)")
        }
    }

    /**
     * Write a gzipped crash bundle to internal storage (Java/Kotlin exceptions only).
     *
     * The bundle contains:
     *  - Device/app header
     *  - Stacktrace
     *  - RuntimeLogStore tail (best-effort)
     *  - Optional logcat tail (best-effort; usually disabled in RELEASE by default)
     *  - Optional crash buffer tail
     */
    private fun writeCrashBundle(context: Context, thread: Thread, throwable: Throwable) {
        val pid = Process.myPid()

        val stampUtc = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val nonce = UUID.randomUUID().toString().substring(0, 8)

        val header = buildHeader(context, pid, thread)
        val stack = buildStacktrace(throwable)

        val runtimeTail = collectRuntimeLogsTailBestEffort(context, RUNTIME_LOG_TAIL_BYTES)

        val includeLogcat = includeLogcatInBundles(context)
        val allowFallback = allowLogcatFallback(context)

        val logMain = if (includeLogcat) collectLogcatTail(pid, LOGCAT_TAIL_LINES, allowFallback) else "(logcat disabled)\n"
        val logCrash = if (includeLogcat) collectLogcatCrashTail(LOGCAT_CRASH_TAIL_LINES) else "(logcat disabled)\n"

        val combined = buildString {
            appendLine("=== Crash Bundle ===")
            appendLine()
            append(header)
            appendLine()
            appendLine("=== Stacktrace ===")
            appendLine(stack)
            appendLine()
            appendLine("=== RuntimeLogStore tail (best-effort) ===")
            appendLine(runtimeTail)
            appendLine()
            appendLine("=== Logcat (tail) ===")
            appendLine(logMain)
            appendLine()
            appendLine("=== Logcat crash buffer (tail) ===")
            appendLine(logCrash)
            appendLine()
        }.toByteArray(Charsets.UTF_8)

        val trimmed = trimToTail(combined, MAX_UNCOMPRESSED_BYTES)
        val gz = gzipAndFitToMaxBytesBestEffort(trimmed, BUNDLE_GZ_MAX_BYTES_DEFAULT)

        val namePrefix = "crash_${stampUtc}_pid${pid}_$nonce"
        val dir = pendingDir(context)
        val outFile = File(dir, "$namePrefix.log.gz")
        val tmpFile = File(dir, "$namePrefix.log.gz.tmp")

        runCatching {
            tmpFile.writeBytes(gz)
            if (outFile.exists()) runCatching { outFile.delete() }
            val renamed = tmpFile.renameTo(outFile)
            if (!renamed) {
                outFile.writeBytes(gz)
                runCatching { tmpFile.delete() }
            }
        }.onFailure {
            runCatching { tmpFile.delete() }
        }

        Log.i(TAG, "Crash bundle written: ${outFile.absolutePath} (${gz.size} bytes gz)")
    }

    /**
     * Build a short diagnostic header for correlation.
     */
    private fun buildHeader(context: Context, pid: Int, thread: Thread): String {
        val pkg = context.packageName
        val pm = context.packageManager

        val pkgInfo = getPackageInfoCompat(pm, pkg)
        val versionName = pkgInfo?.versionName ?: "unknown"
        val versionCode = getVersionCodeCompat(pkgInfo)

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val utc = sdf.format(Date())

        return buildString {
            appendLine("time_utc=$utc")
            appendLine("package=$pkg")
            appendLine("versionName=$versionName")
            appendLine("versionCode=$versionCode")
            appendLine("pid=$pid")
            appendLine("thread=${thread.name}")
            appendLine("process=${currentProcessName(context)}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("isDebug=${BuildConfig.DEBUG}")
        }
    }

    /**
     * Build an ApplicationExitInfo header.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun buildExitInfoHeader(context: Context, info: ApplicationExitInfo): String {
        val pkg = context.packageName
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val utc = sdf.format(Date(info.timestamp))

        return buildString {
            appendLine("time_utc=$utc")
            appendLine("package=$pkg")
            appendLine("processName=${info.processName}")
            appendLine("pid=${info.pid}")
            appendLine("reason=${info.reason}")
            appendLine("status=${info.status}")
            appendLine("description=${info.description}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
        }
    }

    private fun isCrashLikeReason(reason: Int): Boolean {
        return reason == ApplicationExitInfo.REASON_CRASH ||
                reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                reason == ApplicationExitInfo.REASON_ANR ||
                reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
    }

    private fun getPackageInfoCompat(pm: PackageManager, pkg: String): PackageInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }
        }.getOrNull()
    }

    private fun getVersionCodeCompat(pkgInfo: PackageInfo?): Long {
        if (pkgInfo == null) return -1L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }
    }

    private fun buildStacktrace(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { pw -> t.printStackTrace(pw) }
        return sw.toString()
    }

    /**
     * Collect RuntimeLogStore tail by reading the newest *.log files from app-private dir.
     */
    private fun collectRuntimeLogsTailBestEffort(context: Context, maxBytes: Int): String {
        val dir = File(context.filesDir, "diagnostics/runtime_logs")
        if (!dir.exists()) return "(runtime log dir missing)\n"

        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") && it.length() > 0L }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (files.isEmpty()) return "(no runtime log files)\n"

        val cap = maxBytes.coerceAtLeast(16_000)
        val out = ByteArrayOutputStream(cap)

        // Read newest first, tail of each file, until cap reached.
        for (f in files) {
            if (out.size() >= cap) break
            val remain = cap - out.size()
            val chunk = readFileTailBytesBestEffort(f, remain)
            if (chunk.isNotEmpty()) {
                out.write("\n--- ${f.name} (tail) ---\n".toByteArray(Charsets.UTF_8))
                out.write(chunk)
                out.write("\n".toByteArray(Charsets.UTF_8))
            }
        }

        val bytes = out.toByteArray()
        return if (bytes.isEmpty()) "(runtime logs empty)\n" else bytes.toString(Charsets.UTF_8)
    }

    private fun readFileTailBytesBestEffort(file: File, maxBytes: Int): ByteArray {
        return runCatching {
            val len = file.length()
            if (len <= 0L) return@runCatching ByteArray(0)
            val want = maxBytes.coerceAtLeast(1).toLong()
            val start = (len - want).coerceAtLeast(0L)

            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray((len - start).toInt().coerceAtMost(maxBytes))
                raf.readFully(buf)
                buf
            }
        }.getOrElse { ByteArray(0) }
    }

    /**
     * Collect PID-filtered logcat tail (best-effort).
     */
    private fun collectLogcatTail(pid: Int, tailLines: Int, allowFallback: Boolean): String {
        val cmd = listOf(
            "logcat", "-d",
            "--pid=$pid",
            "-v", "threadtime",
            "-t", tailLines.toString()
        )

        val out = runCommandTail(cmd, timeoutMs = LOGCAT_CMD_TIMEOUT_MS, maxStdoutBytes = COMMAND_STDOUT_MAX_BYTES)
        if (!looksLikePidUnsupported(out)) return out
        if (!allowFallback) {
            return buildString {
                appendLine("=== WARNING ===")
                appendLine("PID-filtered logcat is not available; fallback disabled.")
                appendLine("================")
                appendLine()
                append(out)
            }
        }

        val fb = listOf(
            "logcat", "-d",
            "-v", "threadtime",
            "-t", tailLines.toString()
        )

        return buildString {
            appendLine("=== WARNING ===")
            appendLine("PID-filtered logcat is not available on this device/runtime.")
            appendLine("Fallback logcat dump may include other processes.")
            appendLine("================")
            appendLine()
            append(runCommandTail(fb, timeoutMs = LOGCAT_CMD_TIMEOUT_MS, maxStdoutBytes = COMMAND_STDOUT_MAX_BYTES))
        }
    }

    private fun collectLogcatCrashTail(tailLines: Int): String {
        val cmd = listOf(
            "logcat", "-d",
            "-b", "crash",
            "-v", "threadtime",
            "-t", tailLines.toString()
        )
        return runCommandTail(cmd, timeoutMs = LOGCAT_CMD_TIMEOUT_MS, maxStdoutBytes = COMMAND_STDOUT_MAX_BYTES)
    }

    /**
     * Run a process and return tail-captured UTF-8 output.
     *
     * This avoids pipe deadlocks by draining stdout while waiting.
     */
    private fun runCommandTail(cmd: List<String>, timeoutMs: Long, maxStdoutBytes: Int): String {
        val cap = maxStdoutBytes.coerceAtLeast(16_000)
        val tail = TailBuffer(cap)

        return try {
            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()

            val readErrorRef = AtomicReference<Throwable?>(null)

            val reader = Thread {
                runCatching {
                    proc.inputStream.use { stream ->
                        pumpToTail(stream, tail)
                    }
                }.onFailure { t ->
                    readErrorRef.set(t)
                }
            }.apply {
                isDaemon = true
                name = "CrashLogStore-CmdReader"
                start()
            }

            val elapsedMs = measureTimeMillis {
                val finished = runCatching { proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
                if (!finished) {
                    runCatching { proc.destroy() }
                    runCatching { proc.destroyForcibly() }
                }
            }

            runCatching { reader.join(250L) }
            runCatching { proc.destroy() }
            runCatching { if (proc.isAlive) proc.destroyForcibly() }

            val readError = readErrorRef.get()
            val out = if (readError != null) {
                "(command read failed: ${readError.message})\n"
            } else {
                String(tail.toByteArray(), Charsets.UTF_8)
            }

            if (out.isBlank()) return "(logcat empty or restricted)\n"
            if (elapsedMs >= timeoutMs) {
                return "(command timeout after ${timeoutMs}ms: ${cmd.joinToString(" ")})\n$out"
            }
            out
        } catch (t: Throwable) {
            "(command failed: ${t.message})\n"
        }
    }

    private fun pumpToTail(stream: InputStream, tail: TailBuffer) {
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            tail.append(buf, 0, n)
        }
    }

    private fun looksLikePidUnsupported(output: String): Boolean {
        val s = output.lowercase(Locale.US)
        val mentionsPid = s.contains("pid") || s.contains("--pid")
        val looksLikeOptionError =
            s.contains("unknown option") ||
                    s.contains("unrecognized option") ||
                    s.contains("invalid option") ||
                    s.contains("unknown argument") ||
                    (s.contains("unknown") && s.contains("--pid")) ||
                    (s.contains("usage:") && s.contains("logcat") && s.contains("pid"))
        return mentionsPid && looksLikeOptionError
    }

    private fun readBytesLimited(input: InputStream, maxBytes: Int): ByteArray {
        val buf = ByteArray(8_192)
        val bos = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            val remain = maxBytes - total
            if (remain <= 0) break
            val toWrite = minOf(n, remain)
            bos.write(buf, 0, toWrite)
            total += toWrite
            if (total >= maxBytes) break
        }
        return bos.toByteArray()
    }

    private fun trimToTail(bytes: ByteArray, maxBytes: Int): ByteArray {
        if (bytes.size <= maxBytes) return bytes
        val start = bytes.size - maxBytes
        return bytes.copyOfRange(start, bytes.size)
    }

    private fun gzipAndFitToMaxBytesBestEffort(input: ByteArray, maxGzBytes: Int): ByteArray {
        return runCatching {
            var current = input
            repeat(4) { attempt ->
                val gz = safeGzip(current)
                if (gz.size in 1..maxGzBytes) return@runCatching gz

                val nextMax = (current.size * 0.75).toInt().coerceAtLeast(50_000)
                current = trimToTail(current, nextMax)

                Log.w(
                    TAG,
                    "gzip too large (attempt=$attempt gz=${gz.size} > maxGzBytes=$maxGzBytes). " +
                            "Trimming tail to $nextMax bytes and retrying."
                )
            }
            safeGzip(current)
        }.getOrElse { t ->
            Log.w(TAG, "gzipAndFitToMaxBytesBestEffort failed: ${t.message}", t)
            safeGzip("(gzip failed: ${t.message})\n".toByteArray(Charsets.UTF_8))
        }
    }

    private fun safeGzip(input: ByteArray): ByteArray {
        return runCatching { gzip(input) }
            .getOrElse { t ->
                runCatching { gzip("(gzip failed: ${t.message})\n".toByteArray(Charsets.UTF_8)) }
                    .getOrElse { ByteArray(1) }
            }
    }

    private fun gzip(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    private fun writePendingGzFile(
        context: Context,
        prefix: String,
        stampUtcMillis: Long,
        pid: Int,
        suffix: String,
        bytesProvider: () -> ByteArray
    ): File? {
        val dir = pendingDir(context)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(stampUtcMillis))

        val nonce = UUID.randomUUID().toString().substring(0, 8)
        val base = "${prefix}_${stamp}_pid${pid}_${suffix}_$nonce.log.gz"

        val outFile = File(dir, base)
        val tmpFile = File(dir, "$base.tmp")

        return runCatching {
            val gz = bytesProvider()
            tmpFile.writeBytes(gz)
            if (outFile.exists()) runCatching { outFile.delete() }
            val renamed = tmpFile.renameTo(outFile)
            if (!renamed) {
                outFile.writeBytes(gz)
                runCatching { tmpFile.delete() }
            }
            outFile
        }.onFailure {
            runCatching { tmpFile.delete() }
        }.getOrNull()
    }

    private fun prunePendingFiles(context: Context) {
        val dir = pendingDir(context)
        val files = dir.listFiles()
            ?.filter { it.isFile }
            ?.filter { !it.name.endsWith(".tmp", ignoreCase = true) }
            ?: return

        if (files.isEmpty()) return

        val now = System.currentTimeMillis()
        val maxAgeMs = TimeUnit.DAYS.toMillis(MAX_PENDING_AGE_DAYS)

        val old = files.filter { now - it.lastModified() > maxAgeMs }
        old.forEach { f -> runCatching { f.delete() } }
        if (old.isNotEmpty()) {
            Log.w(TAG, "Pruned old pending files: deleted=${old.size} ageDays>$MAX_PENDING_AGE_DAYS")
        }

        val remain = dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp", ignoreCase = true) }
            ?: return

        if (remain.size <= MAX_PENDING_FILES_TO_KEEP) return

        val toDelete = remain
            .sortedByDescending { it.lastModified() }
            .drop(MAX_PENDING_FILES_TO_KEEP)

        toDelete.forEach { f -> runCatching { f.delete() } }
        Log.w(TAG, "Pruned pending files by count: deleted=${toDelete.size} keep=$MAX_PENDING_FILES_TO_KEEP totalBefore=${remain.size}")
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun pendingDir(context: Context): File =
        File(context.filesDir, DIR_PENDING_CRASH).apply { mkdirs() }

    private fun includeLogcatInBundles(context: Context): Boolean {
        if (BuildConfig.DEBUG) return true
        return prefs(context).getBoolean(PREF_KEY_INCLUDE_LOGCAT, false)
    }

    private fun allowLogcatFallback(context: Context): Boolean {
        if (BuildConfig.DEBUG) return true
        return prefs(context).getBoolean(PREF_KEY_ALLOW_LOGCAT_FALLBACK, false)
    }

    private fun isMainProcess(context: Context): Boolean {
        val pn = context.packageName
        val proc = currentProcessName(context)
        return proc == pn
    }

    private fun currentProcessName(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                Application.getProcessName() ?: ""
            } else {
                val am = context.getSystemService(ActivityManager::class.java) ?: return ""
                val pid = Process.myPid()
                am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName ?: ""
            }
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Fixed-size tail buffer for bytes.
     */
    private class TailBuffer(private val capacity: Int) {
        private val buf = ByteArray(capacity)
        private var pos = 0
        private var size = 0

        fun append(src: ByteArray, off: Int, len: Int) {
            var o = off
            var l = len
            while (l > 0) {
                val spaceToEnd = capacity - pos
                val n = min(spaceToEnd, l)
                System.arraycopy(src, o, buf, pos, n)
                pos = (pos + n) % capacity
                size = min(capacity, size + n)
                o += n
                l -= n
            }
        }

        fun toByteArray(): ByteArray {
            if (size == 0) return ByteArray(0)
            if (size < capacity) return buf.copyOfRange(0, size)

            val out = ByteArray(capacity)
            val tailLen = capacity - pos
            System.arraycopy(buf, pos, out, 0, tailLen)
            System.arraycopy(buf, 0, out, tailLen, pos)
            return out
        }
    }
}