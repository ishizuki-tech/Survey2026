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
 *   - On crash: write a gzipped crash bundle to internal storage.
 *   - On next launch: scan pending bundles and schedule WorkManager uploads.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.room.util.copy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

private const val TAG = "CrashLogStore"

/**
 * Crash log capture + deferred upload scheduler.
 *
 * Design goals:
 * - Crash-time code must be fast and never throw.
 * - Upload happens later via WorkManager (GitHubUploadWorker).
 * - Stored artifacts are small (tail + gzip) to fit GitHub Contents API.
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
     * Install an UncaughtExceptionHandler that writes a crash bundle to disk.
     *
     * IMPORTANT:
     * - This does NOT prevent the crash.
     * - It chains to the previous default handler after writing.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writeCrashBundle(appContext, thread, throwable)
            }.onFailure { e ->
                Log.w(TAG, "Crash bundle write failed: ${e.message}", e)
            }

            // Always delegate to the original handler (keeps system behavior).
            prev?.uncaughtException(thread, throwable)
        }

        Log.i(TAG, "CrashLogStore installed.")
    }

    /**
     * Schedule uploads for any pending crash bundles using the saved GitHub config.
     *
     * If no config is available (token not set), this is a no-op.
     */
    fun schedulePendingUploadsFromSavedConfig(context: Context) {
        val cfg = GitHubDiagnosticsConfigStore.load(context) ?: run {
            Log.w(TAG, "No GitHub config saved; skip crashlog upload scheduling.")
            return
        }

        val uploadCfg = cfg.copy(pathPrefix = "diagnostics/crashlogs")
        schedulePendingUploads(context, uploadCfg)
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
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 0L } ?: emptyList()

        if (files.isEmpty()) {
            Log.d(TAG, "No pending crash bundles.")
            return
        }

        // Oldest first (stable behavior).
        files.sortedBy { it.lastModified() }.forEach { f ->
            GitHubUploadWorker.Companion.enqueueExistingPayload(
                context = context,
                cfg = cfg,
                file = f
            )
        }

        Log.i(TAG, "Scheduled ${files.size} crash bundle upload(s).")
    }

    /**
     * Write a gzipped crash bundle to internal storage.
     *
     * The bundle contains:
     * - Device/app header
     * - Stacktrace
     * - logcat tail (main/system/events depending on device)
     * - crash buffer tail (if available)
     */
    private fun writeCrashBundle(
        context: Context,
        thread: Thread,
        throwable: Throwable
    ) {
        val pid = Process.myPid()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "crash_${stamp}_pid${pid}.log.gz"
        val outFile = File(pendingDir(context), name)

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
        val gz = gzip(trimmed)

        outFile.parentFile?.mkdirs()
        outFile.writeBytes(gz)

        Log.i(TAG, "Crash bundle written: ${outFile.absolutePath} (${gz.size} bytes gz)")
    }

    /**
     * Build a short diagnostic header for correlation.
     */
    private fun buildHeader(context: Context, pid: Int, thread: Thread): String {
        val pkg = context.packageName
        val pm = context.packageManager

        val versionName = runCatching {
            pm.getPackageInfo(pkg, 0).versionName ?: "unknown"
        }.getOrElse { "unknown" }

        val versionCode = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0).longVersionCode
        }.getOrElse { -1L }

        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

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
     * Convert Throwable to a full stacktrace string.
     */
    private fun buildStacktrace(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { pw -> t.printStackTrace(pw) }
        return sw.toString()
    }

    /**
     * Collect logcat tail for the current app process.
     *
     * Notes:
     * - Uses `--pid` so it should mainly capture your app logs.
     * - Some devices may still restrict access; returns an error string then.
     */
    private fun collectLogcatTail(pid: Int, tailLines: Int): String {
        val cmd = listOf(
            "logcat",
            "-d",
            "--pid=$pid",
            "-v", "threadtime",
            "-t", tailLines.toString()
        )
        return runCommand(cmd, timeoutMs = 1200L)
    }

    /**
     * Collect logcat crash buffer tail.
     *
     * Notes:
     * - `-b crash` usually includes AndroidRuntime fatal exceptions.
     * - Access may vary by device.
     */
    private fun collectLogcatCrashTail(tailLines: Int): String {
        val cmd = listOf(
            "logcat",
            "-d",
            "-b", "crash",
            "-v", "threadtime",
            "-t", tailLines.toString()
        )
        return runCommand(cmd, timeoutMs = 1200L)
    }

    /**
     * Run a shell command and return stdout as text (best-effort).
     */
    private fun runCommand(cmd: List<String>, timeoutMs: Long): String {
        return try {
            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()

            // Try to finish quickly; do not block forever in crash handler.
            proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            runCatching { proc.destroy() }

            if (out.isBlank()) "(logcat empty or restricted)\n" else out
        } catch (t: Throwable) {
            "(command failed: ${t.message})\n"
        }
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
     * Gzip compress for size safety.
     */
    private fun gzip(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    /**
     * Get the internal pending directory.
     */
    private fun pendingDir(context: Context): File =
        File(context.filesDir, DIR_PENDING_CRASH).apply { mkdirs() }
}
