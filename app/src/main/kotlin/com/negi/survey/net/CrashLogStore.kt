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
 *  Captures crash reports (stacktrace + logcat tail) into local files.
 *  Intended flow:
 *   - On crash (Java/Kotlin): write a gzipped crash bundle to internal storage.
 *   - On next launch: scan pending bundles and schedule WorkManager uploads.
 *
 *  Important:
 *   - Native crashes (SIGSEGV) do NOT invoke UncaughtExceptionHandler.
 *     We must capture native tombstone/trace on the NEXT launch using
 *     ApplicationExitInfo (Android 11+).
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.app.ActivityManager
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
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import kotlin.system.exitProcess

private const val TAG = "CrashLogStore"

/**
 * Crash log capture + deferred upload scheduler.
 *
 * Design goals:
 * - Crash-time code must be fast and never throw.
 * - Upload happens later via WorkManager (GitHubUploadWorker).
 * - Stored artifacts are small (tail + gzip) to fit repository guards.
 *
 * Native crash note:
 * - SIGSEGV/native crashes skip UncaughtExceptionHandler.
 * - We capture native trace on the next start via ApplicationExitInfo (API 30+).
 */
object CrashLogStore {

    /** Directory under internal storage for pending crash bundles. */
    private const val DIR_PENDING_CRASH = "pending_uploads/crashlogs"

    /** Keep last N lines from logcat main/system buffers. */
    private const val LOGCAT_TAIL_LINES = 1200

    /** Keep last N lines from the crash buffer. */
    private const val LOGCAT_CRASH_TAIL_LINES = 200

    /** Hard cap for uncompressed crash bundle bytes (before gzip). */
    private const val MAX_UNCOMPRESSED_BYTES = 850_000

    /**
     * Operational cap for gz crash payload bytes to keep repo bloat under control.
     *
     * This is NOT an official GitHub limit. It is a practical cap for diagnostics files.
     */
    private const val CRASH_GZ_MAX_BYTES_DEFAULT = 900_000

    /**
     * Safety cap for command output read (even if logcat ignores -t on some devices).
     * Keep this comfortably below MAX_UNCOMPRESSED_BYTES.
     */
    private const val COMMAND_STDOUT_MAX_BYTES = 260_000

    /** Max bytes we read from ApplicationExitInfo.traceInputStream. */
    private const val EXITINFO_TRACE_MAX_BYTES = 650_000

    /** Prevent handler being installed multiple times in the same process. */
    private val installed = AtomicBoolean(false)

    /** Prevent startup tasks (capture + schedule) from running multiple times per process. */
    private val startupTasksRan = AtomicBoolean(false)

    /** Upper bound for how many pending files we schedule per app start. */
    private const val MAX_FILES_TO_SCHEDULE_PER_START = 20

    /** Prune guard: keep only newest N files to prevent unbounded growth. */
    private const val MAX_PENDING_FILES_TO_KEEP = 120

    /** Prune guard: delete files older than this (days). */
    private const val MAX_PENDING_AGE_DAYS = 21L

    private const val PREFS_NAME = "crash_log_store"
    private const val PREF_KEY_LAST_EXIT_TS = "last_exit_timestamp"
    private const val PREF_KEY_LAST_EXIT_PID = "last_exit_pid"
    private const val PREF_KEY_LAST_EXIT_REASON = "last_exit_reason"

    /**
     * Install an UncaughtExceptionHandler that writes a crash bundle to disk.
     *
     * IMPORTANT:
     * - This does NOT prevent the crash.
     * - It chains to the previous default handler after writing.
     *
     * Startup tasks:
     * - On every normal app start (first install call per process),
     *   we capture native crash traces (Android 11+) and schedule pending uploads.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext

        // Run startup tasks exactly once per process (avoid cooldown spam / duplicate scheduling).
        if (startupTasksRan.compareAndSet(false, true)) {
            runCatching {
                captureLastExitInfoBundleIfPossible(appContext)
            }.onFailure { e ->
                Log.w(TAG, "captureLastExitInfoBundleIfPossible failed: ${e.message}", e)
            }

            runCatching {
                prunePendingFiles(appContext)
            }.onFailure { e ->
                Log.w(TAG, "prunePendingFiles failed: ${e.message}", e)
            }

            runCatching {
                schedulePendingUploadsFromBestAvailableConfig(appContext)
            }.onFailure { e ->
                Log.w(TAG, "Failed to schedule pending crash uploads: ${e.message}", e)
            }
        }

        // Avoid double-install within the same process.
        if (!installed.compareAndSet(false, true)) {
            Log.d(TAG, "CrashLogStore already installed; skip handler re-install.")
            return
        }

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writeCrashBundle(appContext, thread, throwable)
            }.onFailure { e ->
                Log.w(TAG, "Crash bundle write failed: ${e.message}", e)
            }

            // Always delegate to the original handler (keeps system behavior).
            try {
                prev?.uncaughtException(thread, throwable)
            } catch (_: Throwable) {
                // Last resort: ensure process terminates even if the previous handler misbehaves.
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
     * 1) GitHubDiagnosticsConfigStore (persisted runtime config)
     * 2) BuildConfig (compile-time config)
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

        // GH_OWNER is preferred, but allow extracting from GH_REPO when GH_OWNER is blank.
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
    fun schedulePendingUploads(
        context: Context,
        cfg: GitHubUploader.GitHubConfig
    ) {
        val dir = pendingDir(context)
        val files = dir.listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.filter { !it.name.endsWith(".tmp", ignoreCase = true) } // avoid partial crash writes
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
                // Pass size hints ~file size to avoid mismatched guards.
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
     * Why:
     * - SIGSEGV/native crashes do NOT invoke UncaughtExceptionHandler.
     * - ApplicationExitInfo can provide a tombstone-like trace stream on the next start.
     *
     * Output:
     * - Writes a gz bundle into the same pending dir, so existing upload pipeline works.
     */
    fun captureLastExitInfoBundleIfPossible(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = prefs(context)

        val lastTs = prefs.getLong(PREF_KEY_LAST_EXIT_TS, -1L)
        val lastPid = prefs.getInt(PREF_KEY_LAST_EXIT_PID, -1)
        val lastReason = prefs.getInt(PREF_KEY_LAST_EXIT_REASON, -1)

        val list = runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 12)
        }.getOrElse {
            Log.w(TAG, "getHistoricalProcessExitReasons failed: ${it.message}", it)
            return
        }

        if (list.isEmpty()) return

        // Prefer the newest record that either has a trace stream or indicates a crash-like reason.
        val candidate = list
            .sortedByDescending { it.timestamp }
            .firstOrNull { info ->
                val hasTrace = runCatching { info.traceInputStream != null }.getOrDefault(false)
                val crashLike = isCrashLikeReason(info.reason)
                hasTrace || crashLike
            } ?: return

        // De-dup across launches.
        val ts = candidate.timestamp
        val pid = candidate.pid
        val reason = candidate.reason

        if (ts == lastTs && pid == lastPid && reason == lastReason) {
            Log.d(TAG, "Last exit info already captured. ts=$ts pid=$pid reason=$reason")
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

        // Optionally include crash buffer tail (best-effort).
        val crashBuf = runCatching { collectLogcatCrashTail(LOGCAT_CRASH_TAIL_LINES) }
            .getOrElse { "(logcat crash buffer read failed: ${it.message})\n" }

        val combined = buildString {
            appendLine("=== Last Exit Bundle (ApplicationExitInfo) ===")
            appendLine()
            append(header)
            appendLine()
            appendLine("=== Trace (ApplicationExitInfo.traceInputStream) ===")
            appendLine(traceText)
            appendLine()
            appendLine("=== Logcat crash buffer (tail) ===")
            appendLine(crashBuf)
            appendLine()
        }.toByteArray(Charsets.UTF_8)

        val trimmed = trimToTail(combined, MAX_UNCOMPRESSED_BYTES)
        val gz = gzipAndFitToMaxBytesBestEffort(trimmed, CRASH_GZ_MAX_BYTES_DEFAULT)

        val outFile = writePendingGzFile(
            context = context,
            prefix = "exitinfo",
            stampUtcMillis = ts,
            pid = pid,
            suffix = "reason${reason}"
        ) { gz }

        if (outFile != null) {
            prefs.edit()
                .putLong(PREF_KEY_LAST_EXIT_TS, ts)
                .putInt(PREF_KEY_LAST_EXIT_PID, pid)
                .putInt(PREF_KEY_LAST_EXIT_REASON, reason)
                .apply()

            Log.i(TAG, "Last exit bundle written: ${outFile.absolutePath} (${gz.size} bytes gz)")
        }
    }

    /**
     * Write a gzipped crash bundle to internal storage (Java/Kotlin exceptions only).
     *
     * The bundle contains:
     * - Device/app header
     * - Stacktrace
     * - logcat tail (PID-filtered, best-effort)
     * - crash buffer tail (best-effort)
     */
    private fun writeCrashBundle(
        context: Context,
        thread: Thread,
        throwable: Throwable
    ) {
        val pid = Process.myPid()

        // Use millis + short UUID suffix to avoid filename collisions on rapid repeated crashes.
        val stampUtc = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val nonce = UUID.randomUUID().toString().substring(0, 8)

        val header = buildHeader(context, pid, thread)
        val stack = buildStacktrace(throwable)

        // Best-effort logcat (may be restricted on some devices/ROMs).
        val logMain = collectLogcatTail(pid = pid, tailLines = LOGCAT_TAIL_LINES)
        val logCrash = collectLogcatCrashTail(tailLines = LOGCAT_CRASH_TAIL_LINES)

        val combined = buildString {
            appendLine("=== Crash Bundle ===")
            appendLine()
            append(header)
            appendLine()
            appendLine("=== Stacktrace ===")
            appendLine(stack)
            appendLine()
            appendLine("=== Logcat (tail) ===")
            appendLine(logMain)
            appendLine()
            appendLine("=== Logcat crash buffer (tail) ===")
            appendLine(logCrash)
            appendLine()
        }.toByteArray(Charsets.UTF_8)

        val trimmed = trimToTail(combined, MAX_UNCOMPRESSED_BYTES)
        val gz = gzipAndFitToMaxBytesBestEffort(trimmed, CRASH_GZ_MAX_BYTES_DEFAULT)

        val namePrefix = "crash_${stampUtc}_pid${pid}_$nonce"
        val dir = pendingDir(context)
        val outFile = File(dir, "$namePrefix.log.gz")
        val tmpFile = File(dir, "$namePrefix.log.gz.tmp")

        runCatching {
            tmpFile.writeBytes(gz)
            if (outFile.exists()) runCatching { outFile.delete() }
            val renamed = tmpFile.renameTo(outFile)
            if (!renamed) {
                // Fallback to direct write if rename fails.
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
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
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

    /**
     * Determine whether a reason is crash-like.
     */
    private fun isCrashLikeReason(reason: Int): Boolean {
        return reason == ApplicationExitInfo.REASON_CRASH ||
                reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                reason == ApplicationExitInfo.REASON_ANR ||
                reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
    }

    /**
     * Get PackageInfo safely on all API levels.
     */
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

    /**
     * Get versionCode as Long safely on all API levels (minSdk=26+).
     */
    private fun getVersionCodeCompat(pkgInfo: PackageInfo?): Long {
        if (pkgInfo == null) return -1L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }
    }

    /**
     * Convert Throwable to a full stacktrace string.
     */
    private fun buildStacktrace(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { pw -> t.printStackTrace(pw) }
        return sw.toString()
    }

    /**
     * Collect logcat tail for the current app process.
     */
    private fun collectLogcatTail(pid: Int, tailLines: Int): String {
        val cmd = listOf(
            "logcat",
            "-d",
            "--pid=$pid",
            "-v", "threadtime",
            "-t", tailLines.toString()
        )
        val out = runCommand(cmd, timeoutMs = 1200L, maxStdoutBytes = COMMAND_STDOUT_MAX_BYTES)
        if (!looksLikePidUnsupported(out)) return out

        // Fallback without --pid
        val fb = listOf(
            "logcat",
            "-d",
            "-v", "threadtime",
            "-t", tailLines.toString()
        )
        return buildString {
            appendLine("=== WARNING ===")
            appendLine("PID-filtered logcat is not available on this device/runtime.")
            appendLine("Fallback logcat dump may include other processes.")
            appendLine("================")
            appendLine()
            append(runCommand(fb, timeoutMs = 1200L, maxStdoutBytes = COMMAND_STDOUT_MAX_BYTES))
        }
    }

    /**
     * Collect logcat crash buffer tail.
     */
    private fun collectLogcatCrashTail(tailLines: Int): String {
        val cmd = listOf(
            "logcat",
            "-d",
            "-b", "crash",
            "-v", "threadtime",
            "-t", tailLines.toString()
        )
        return runCommand(cmd, timeoutMs = 1200L, maxStdoutBytes = COMMAND_STDOUT_MAX_BYTES)
    }

    /**
     * Run a shell command and return stdout as text (best-effort).
     */
    private fun runCommand(cmd: List<String>, timeoutMs: Long, maxStdoutBytes: Int): String {
        return try {
            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()

            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { proc.destroy() }
                runCatching { proc.destroyForcibly() }
                return "(command timeout: ${cmd.joinToString(" ")})\n"
            }

            val out = proc.inputStream.use { readTextLimited(it, maxStdoutBytes) }
            runCatching { proc.destroy() }

            out.ifBlank { "(logcat empty or restricted)\n" }
        } catch (t: Throwable) {
            "(command failed: ${t.message})\n"
        }
    }

    /**
     * Read at most [maxBytes] from [input] and decode as UTF-8.
     */
    private fun readTextLimited(input: InputStream, maxBytes: Int): String {
        return runCatching {
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
            bos.toString(Charsets.UTF_8.name())
        }.getOrElse { t ->
            "(read failed: ${t.message})\n"
        }
    }

    /**
     * Read at most [maxBytes] raw bytes from [input].
     */
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

    /**
     * Heuristic: detect PID option unsupported patterns.
     */
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

    /**
     * Keep only the tail portion when exceeding max bytes.
     */
    private fun trimToTail(bytes: ByteArray, maxBytes: Int): ByteArray {
        if (bytes.size <= maxBytes) return bytes
        val start = bytes.size - maxBytes
        return bytes.copyOfRange(start, bytes.size)
    }

    /**
     * Gzip compress and best-effort fit under [maxGzBytes].
     */
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

    /**
     * Gzip compress in a crash-safe way (never throws).
     */
    private fun safeGzip(input: ByteArray): ByteArray {
        return runCatching { gzip(input) }
            .getOrElse { t ->
                runCatching { gzip("(gzip failed: ${t.message})\n".toByteArray(Charsets.UTF_8)) }
                    .getOrElse { ByteArray(1) } // non-empty sentinel
            }
    }

    /**
     * Gzip compress for size safety.
     */
    private fun gzip(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    /**
     * Write a pending gz file via temp+rename to avoid partial writes.
     */
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

    /**
     * Prune old / excessive pending files to prevent unbounded storage growth.
     */
    private fun prunePendingFiles(context: Context) {
        val dir = pendingDir(context)
        val files = dir.listFiles()
            ?.filter { it.isFile }
            ?.filter { !it.name.endsWith(".tmp", ignoreCase = true) }
            ?: return

        if (files.isEmpty()) return

        val now = System.currentTimeMillis()
        val maxAgeMs = TimeUnit.DAYS.toMillis(MAX_PENDING_AGE_DAYS)

        // 1) Delete very old files.
        val old = files.filter { now - it.lastModified() > maxAgeMs }
        old.forEach { f -> runCatching { f.delete() } }
        if (old.isNotEmpty()) {
            Log.w(TAG, "Pruned old pending files: deleted=${old.size} ageDays>$MAX_PENDING_AGE_DAYS")
        }

        // 2) Keep only newest N.
        val remain = dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp", ignoreCase = true) }
            ?: return

        if (remain.size <= MAX_PENDING_FILES_TO_KEEP) return

        val toDelete = remain
            .sortedByDescending { it.lastModified() }
            .drop(MAX_PENDING_FILES_TO_KEEP)

        toDelete.forEach { f -> runCatching { f.delete() } }
        Log.w(
            TAG,
            "Pruned pending files by count: deleted=${toDelete.size} keep=$MAX_PENDING_FILES_TO_KEEP totalBefore=${remain.size}"
        )
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Get the internal pending directory.
     */
    private fun pendingDir(context: Context): File =
        File(context.filesDir, DIR_PENDING_CRASH).apply { mkdirs() }
}
