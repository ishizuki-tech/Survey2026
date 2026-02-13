/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SupabaseCrashLogStore.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

private const val TAG = "SupabaseCrashLogStore"

object SupabaseCrashLogStore {

    /**
     * Pending directory (primary).
     * Keep consistent with other Supabase pending payload staging.
     */
    private const val DIR_PENDING_PRIMARY = "pending_uploads_supabase/crashlogs"

    /**
     * Legacy pending directory (older builds).
     * We continue to scan this to avoid orphaning crash bundles after updates.
     */
    private const val DIR_PENDING_LEGACY = "pending_uploads/supabase/crashlogs"

    private const val LOGCAT_TAIL_LINES = 1200
    private const val LOGCAT_CRASH_TAIL_LINES = 200

    private const val MAX_UNCOMPRESSED_BYTES = 850_000
    private const val CRASH_GZ_MAX_BYTES_DEFAULT = 900_000

    /** Remote directory hint passed to SupabaseUploadWorker. */
    private const val REMOTE_DIR_CRASH = "crash"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writeCrashBundle(appContext, thread, throwable)
            }.onFailure { e ->
                Log.w(TAG, "Crash bundle write failed: ${e.message}", e)
            }
            prev?.uncaughtException(thread, throwable)
        }

        Log.i(TAG, "SupabaseCrashLogStore installed.")
    }

    fun schedulePendingUploadsFromSavedConfig(context: Context) {
        val cfg = SupabaseDiagnosticsConfigStore.load(context) ?: run {
            Log.w(TAG, "No Supabase config saved; skip crashlog upload scheduling.")
            return
        }
        schedulePendingUploads(context, cfg)
    }

    fun schedulePendingUploads(
        context: Context,
        cfg: SupabaseUploader.SupabaseConfig
    ) {
        val dirs = listOf(
            pendingDirPrimary(context),
            pendingDirLegacy(context)
        )

        val files = dirs
            .flatMap { dir ->
                val list = dir.listFiles()?.toList().orEmpty()
                Log.d(TAG, "schedulePendingUploads: dir=${dir.absolutePath} files=${list.size}")
                list
            }
            .filter { it.isFile && it.length() > 0L }
            .distinctBy { it.absolutePath }

        Log.d(TAG, "schedulePendingUploads: totalFiles=${files.size} remoteDir=$REMOTE_DIR_CRASH")

        if (files.isEmpty()) {
            Log.d(TAG, "No pending crash bundles.")
            return
        }

        files.sortedBy { it.lastModified() }.forEach { f ->
            Log.i(
                TAG,
                "Enqueue crash bundle: name=${f.name} bytes=${f.length()} remoteDir=$REMOTE_DIR_CRASH upsert=false"
            )
            SupabaseUploadWorker.enqueueExistingPayload(
                context = context,
                cfg = cfg,
                file = f,
                remoteDir = REMOTE_DIR_CRASH,
                contentType = "application/gzip",
                upsert = false
            )
        }

        Log.i(TAG, "Scheduled ${files.size} crash bundle upload(s) to Supabase.")
    }

    private fun writeCrashBundle(
        context: Context,
        thread: Thread,
        throwable: Throwable
    ) {
        val pid = Process.myPid()

        // Include milliseconds to avoid name collision during crash loops.
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val baseName = "crash_${stamp}_pid${pid}.log.gz"

        val dir = pendingDirPrimary(context)
        val outFile = uniqueIfExists(File(dir, baseName))
        val tmpFile = File(dir, "${outFile.name}.tmp")

        val header = buildHeader(context, pid, thread)
        val stack = buildStacktrace(throwable)

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

        runCatching {
            tmpFile.writeBytes(gz)

            // Best-effort atomic swap.
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

    private fun buildHeader(context: Context, pid: Int, thread: Thread): String {
        val pkg = context.packageName
        val pm = context.packageManager
        val pkgInfo = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()

        val versionName = pkgInfo?.versionName ?: "unknown"
        val versionCode = runCatching {
            if (pkgInfo != null) PackageInfoCompat.getLongVersionCode(pkgInfo) else -1L
        }.getOrDefault(-1L)

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

    private fun buildStacktrace(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { pw -> t.printStackTrace(pw) }
        return sw.toString()
    }

    private fun collectLogcatTail(pid: Int, tailLines: Int): String {
        val cmd = listOf(
            "logcat",
            "-d",
            "--pid=$pid",
            "-v", "threadtime",
            "-t", tailLines.toString()
        )
        val out = runCommand(cmd, timeoutMs = 1500L)
        if (!looksLikePidUnsupported(out)) return out

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
            append(runCommand(fb, timeoutMs = 1500L))
        }
    }

    private fun collectLogcatCrashTail(tailLines: Int): String {
        val cmd = listOf(
            "logcat",
            "-d",
            "-b", "crash",
            "-v", "threadtime",
            "-t", tailLines.toString()
        )
        return runCommand(cmd, timeoutMs = 1500L)
    }

    private fun runCommand(cmd: List<String>, timeoutMs: Long): String {
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

            val out = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            runCatching { proc.destroy() }
            if (out.isBlank()) "(logcat empty or restricted)\n" else out
        } catch (t: Throwable) {
            "(command failed: ${t.message})\n"
        }
    }

    private fun looksLikePidUnsupported(output: String): Boolean {
        val s = output.lowercase(Locale.US)
        val mentionsPid = s.contains("pid")
        val looksLikeOptionError =
            s.contains("unknown option") ||
                    s.contains("unrecognized option") ||
                    s.contains("invalid option") ||
                    (s.contains("unknown") && s.contains("--pid"))

        return mentionsPid && looksLikeOptionError
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
                val gz = gzip(current)
                if (gz.size <= maxGzBytes) return@runCatching gz

                val nextMax = (current.size * 0.75).toInt().coerceAtLeast(50_000)
                current = trimToTail(current, nextMax)

                Log.w(
                    TAG,
                    "gzip too large (attempt=$attempt gz=${gz.size} > maxGzBytes=$maxGzBytes). trimming to $nextMax"
                )
            }
            gzip(current)
        }.getOrElse { t ->
            Log.w(TAG, "gzipAndFitToMaxBytesBestEffort failed: ${t.message}", t)
            gzip(input)
        }
    }

    private fun gzip(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    private fun pendingDirPrimary(context: Context): File =
        File(context.filesDir, DIR_PENDING_PRIMARY).apply { mkdirs() }

    private fun pendingDirLegacy(context: Context): File =
        File(context.filesDir, DIR_PENDING_LEGACY).apply { mkdirs() }

    /**
     * Ensure a unique file name if the target already exists.
     */
    private fun uniqueIfExists(file: File): File {
        if (!file.exists()) return file

        val base = file.nameWithoutExtension
        val ext = file.extension.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""
        var idx = 1
        while (true) {
            val c = File(file.parentFile, "${base}_$idx$ext")
            if (!c.exists()) return c
            idx++
        }
    }
}
