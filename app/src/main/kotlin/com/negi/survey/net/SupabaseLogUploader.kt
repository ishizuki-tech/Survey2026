/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SupabaseLogUploader.kt
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
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SupabaseLogUploader"

object SupabaseLogUploader {

    /**
     * If cfg.pathPrefix is blank, we fall back to this to match typical RLS patterns:
     *   surveyapp/diagnostics/logcat/...
     */
    private const val DEFAULT_PREFIX_FALLBACK = "surveyapp"

    /**
     * Safety timeout for logcat dumps.
     * Even with -d, some runtimes can hang; we keep this bounded.
     */
    private const val LOGCAT_TIMEOUT_MS = 1600L

    data class LogUploadResult(
        val objectPath: String,
        val publicUrl: String?,
        val etag: String?,
        val requestId: String?,
        val bytesRaw: Int,
        val bytesGz: Int
    )

    suspend fun collectAndUploadLogcat(
        context: Context,
        cfg: SupabaseUploader.SupabaseConfig,
        remoteDir: String = "diagnostics/logcat",
        addDateSubdir: Boolean = true,
        includeDeviceHeader: Boolean = true,
        maxUncompressedBytes: Int = 850_000,
        includeCrashBuffer: Boolean = true,
        tokenOverride: String? = null,
        onProgress: (Int) -> Unit = {}
    ): LogUploadResult = withContext(Dispatchers.IO) {

        onProgress(0)

        val pid = Process.myPid()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dateDir = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        val remoteName = "logcat_${stamp}_pid${pid}.log.gz"

        val objectPath = buildRemotePath(
            prefix = cfg.pathPrefix,
            remoteDir = remoteDir,
            addDateSubdir = addDateSubdir,
            dateDir = dateDir,
            fileName = remoteName
        )

        Log.d(
            TAG,
            "collectAndUploadLogcat: pid=$pid objectPath=$objectPath includeCrash=$includeCrashBuffer maxRaw=$maxUncompressedBytes"
        )

        val header = if (includeDeviceHeader) buildHeader(context, pid) else ""

        onProgress(5)

        val budgetTotal = maxUncompressedBytes.coerceAtLeast(50_000)
        val budgetMain = if (includeCrashBuffer) (budgetTotal * 3) / 4 else budgetTotal
        val budgetCrash = (budgetTotal - budgetMain).coerceAtLeast(10_000)

        val mainLog = collectLogcatForPidTail(pid = pid, buffer = null, maxBytes = budgetMain)
        val crashLog = if (includeCrashBuffer) {
            collectLogcatForPidTail(pid = pid, buffer = "crash", maxBytes = budgetCrash)
        } else {
            ""
        }

        onProgress(15)

        val combinedText = buildString {
            append(header)
            append(mainLog)
            if (includeCrashBuffer && crashLog.isNotBlank()) {
                appendLine()
                appendLine("=== crash buffer ===")
                append(crashLog)
            }
        }

        val combinedBytes = combinedText.toByteArray(Charsets.UTF_8)
        val trimmed = trimToTail(combinedBytes, budgetTotal)

        onProgress(20)

        // Keep gz under a safe-ish bound (GitHub-like). This is not a Supabase limit; it is an app policy.
        val maxGzBytes = min(cfg.maxRawBytesHint, 900_000L).toInt().coerceAtLeast(50_000)
        val gz = gzipAndFitToMaxBytes(trimmed, maxGzBytes)

        Log.d(
            TAG,
            "logcat bundle: raw=${trimmed.size}B gz=${gz.size}B maxGz=$maxGzBytes objectPath=$objectPath"
        )

        onProgress(35)

        val res = SupabaseUploader.uploadBytes(
            cfg = cfg,
            objectPath = objectPath,
            bytes = gz,
            contentType = "application/gzip",
            upsert = false,
            tokenOverride = tokenOverride,
            onProgress = { p ->
                val mapped = 35 + ((p.coerceIn(0, 100) / 100.0) * 65.0).toInt()
                onProgress(mapped.coerceIn(35, 100))
            }
        )

        onProgress(100)

        Log.i(TAG, "upload ok: objectPath=${res.objectPath} etag=${res.etag} reqId=${res.requestId}")

        LogUploadResult(
            objectPath = res.objectPath,
            publicUrl = res.publicUrl,
            etag = res.etag,
            requestId = res.requestId,
            bytesRaw = trimmed.size,
            bytesGz = gz.size
        )
    }

    private fun collectLogcatForPidTail(pid: Int, buffer: String?, maxBytes: Int): String {
        val base = mutableListOf("logcat", "-d", "-v", "threadtime")
        if (!buffer.isNullOrBlank()) {
            base.add("-b")
            base.add(buffer)
        }
        base.add("--pid=$pid")

        val firstTry = runCatching { runProcessTail(base.toTypedArray(), maxBytes, LOGCAT_TIMEOUT_MS) }
            .getOrElse { "" }

        if (firstTry.isNotBlank() && !looksLikePidUnsupported(firstTry)) return firstTry

        val fallback = mutableListOf("logcat", "-d", "-v", "threadtime")
        if (!buffer.isNullOrBlank()) {
            fallback.add("-b")
            fallback.add(buffer)
        }

        val out = runCatching { runProcessTail(fallback.toTypedArray(), maxBytes, LOGCAT_TIMEOUT_MS) }
            .getOrElse { t ->
                Log.w(TAG, "collectLogcatForPidTail failed: ${t.message}", t)
                "collectLogcatForPidTail failed: ${t.message}\n"
            }

        return buildString {
            appendLine("=== WARNING ===")
            appendLine("PID-filtered logcat is not available on this device/runtime.")
            appendLine("Fallback logcat dump may include other processes. Output is tail-captured.")
            appendLine("================")
            appendLine()
            append(out)
        }
    }

    /**
     * Run a process and capture only the last [maxBytes] bytes of its stdout/stderr.
     * This method is timeout-bounded to avoid rare hangs.
     */
    private fun runProcessTail(cmd: Array<String>, maxBytes: Int, timeoutMs: Long): String {
        val proc = ProcessBuilder(*cmd)
            .redirectErrorStream(true)
            .start()

        val tail = TailBuffer(maxBytes.coerceAtLeast(10_000))

        val readerThread = Thread {
            runCatching {
                proc.inputStream.use { stream ->
                    pumpToTail(stream, tail)
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val finished = runCatching { proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)

        if (!finished) {
            Log.w(TAG, "process timeout: ${cmd.joinToString(" ")}")
            runCatching { proc.destroy() }
            runCatching { proc.destroyForcibly() }
        }

        // Give the reader a short chance to flush after destroy.
        runCatching { readerThread.join(250L) }

        runCatching { proc.destroy() }

        val bytes = tail.toByteArray()
        return if (bytes.isEmpty()) "(logcat empty or restricted)\n" else String(bytes, Charsets.UTF_8)
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
                    (s.contains("unknown") && s.contains("--pid"))

        return mentionsPid && looksLikeOptionError
    }

    private fun buildHeader(context: Context, pid: Int): String {
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
            appendLine("=== Diagnostics Header ===")
            appendLine("time_utc=$utc")
            appendLine("package=$pkg")
            appendLine("versionName=$versionName")
            appendLine("versionCode=$versionCode")
            appendLine("pid=$pid")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("==========================")
            appendLine()
        }
    }

    private fun trimToTail(bytes: ByteArray, maxBytes: Int): ByteArray {
        if (bytes.size <= maxBytes) return bytes
        val start = bytes.size - maxBytes
        return bytes.copyOfRange(start, bytes.size)
    }

    private fun gzipAndFitToMaxBytes(input: ByteArray, maxGzBytes: Int): ByteArray {
        var current = input

        repeat(4) { attempt ->
            val gz = gzip(current)
            if (gz.size <= maxGzBytes) return gz

            val nextMax = (current.size * 0.75).toInt().coerceAtLeast(50_000)
            val trimmed = trimToTail(current, nextMax)

            Log.w(TAG, "gzip too large (attempt=$attempt gz=${gz.size} > maxGzBytes=$maxGzBytes). trimming to $nextMax")
            current = trimmed
        }

        return gzip(current)
    }

    private fun gzip(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    /**
     * Build remote path:
     * - If [prefix] is blank, use DEFAULT_PREFIX_FALLBACK.
     * - Avoid accidental double-prefix when remoteDir already starts with prefix.
     */
    private fun buildRemotePath(
        prefix: String,
        remoteDir: String,
        addDateSubdir: Boolean,
        dateDir: String,
        fileName: String
    ): String {
        val pfx = prefix.trim('/').ifBlank { DEFAULT_PREFIX_FALLBACK }
        val dirRaw = remoteDir.trim('/')

        val dir = when {
            dirRaw.isBlank() -> ""
            dirRaw == pfx -> ""
            dirRaw.startsWith("$pfx/") -> dirRaw.removePrefix("$pfx/").trim('/')
            else -> dirRaw
        }

        val parts = mutableListOf<String>()
        pfx.takeIf { it.isNotBlank() }?.let(parts::add)
        dir.takeIf { it.isNotBlank() }?.let(parts::add)
        if (addDateSubdir) parts.add(dateDir)
        parts.add(fileName.trim('/'))

        return parts.filter { it.isNotBlank() }.joinToString("/")
    }

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
