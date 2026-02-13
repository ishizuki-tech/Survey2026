/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: GitHubLogUploader.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Collect logcat for current PID, gzip-compress it, and upload to GitHub
 *  using GitHubUploader (Contents API).
 *
 *  Design notes:
 *   - Reuses GitHubUploader to avoid duplicate HTTP logic.
 *   - Progress is mapped to 0..100:
 *       0..20  = collect/build
 *      20..35  = gzip (and size guard)
 *      35..100 = upload progress (GitHubUploader progress scaled)
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
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
import kotlin.system.measureTimeMillis

private const val TAG = "GitHubLogUploader"

/**
 * Collects app logcat output (current PID) and uploads it to GitHub via [GitHubUploader].
 *
 * Notes:
 * - Android restricts log access; PID-filtered collection is best-effort.
 * - Contents API has practical size constraints; we trim tail and gzip.
 */
object GitHubLogUploader {

    /**
     * Operational cap for gzip log payload bytes to keep repo bloat under control.
     *
     * This is NOT an official GitHub limit. It is a practical cap for diagnostics files.
     */
    private const val LOG_GZ_MAX_BYTES_DEFAULT = 900_000

    /** Timeout for each logcat dump command. */
    private const val LOGCAT_CMD_TIMEOUT_MS = 1800L

    /**
     * Result payload for log upload.
     */
    data class LogUploadResult(
        val remotePath: String,
        val fileUrl: String?,
        val commitSha: String?,
        val bytesRaw: Int,
        val bytesGz: Int,
    )

    /**
     * Collect and upload a logcat snapshot.
     *
     * @param context Android context.
     * @param cfg GitHub config (owner/repo/token/branch).
     * @param remoteDir Repo directory (e.g., "diagnostics/logs").
     * @param addDateSubdir If true, inserts yyyy-MM-dd as a folder.
     * @param includeDeviceHeader If true, prepends device/app header.
     * @param maxUncompressedBytes Tail bytes before gzip.
     * @param includeCrashBuffer If true, tries to append crash buffer too.
     * @param onProgress Progress callback (0..100).
     */
    suspend fun collectAndUploadLogcat(
        context: Context,
        cfg: GitHubUploader.GitHubConfig,
        remoteDir: String = "diagnostics/logs",
        addDateSubdir: Boolean = true,
        includeDeviceHeader: Boolean = true,
        maxUncompressedBytes: Int = 850_000,
        includeCrashBuffer: Boolean = true,
        onProgress: (Int) -> Unit = {},
    ): LogUploadResult = withContext(Dispatchers.IO) {

        onProgress(0)

        val pid = Process.myPid()

        // Use UTC for stable correlation across devices/timezones.
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val dateDir = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val remoteName = "logcat_${stamp}_pid${pid}.log.gz"
        val remotePath = buildRemotePath(
            remoteDir = remoteDir,
            addDateSubdir = addDateSubdir,
            dateDir = dateDir,
            fileName = remoteName,
        )

        val header = if (includeDeviceHeader) buildHeader(context, pid) else ""

        onProgress(5)

        // Split uncompressed budget to avoid holding too much in memory at once.
        val budgetTotal = maxUncompressedBytes.coerceAtLeast(50_000)
        val budgetMain = if (includeCrashBuffer) (budgetTotal * 3) / 4 else budgetTotal
        val budgetCrash = (budgetTotal - budgetMain).coerceAtLeast(10_000)

        val mainLog = collectLogcatForPidTail(
            pid = pid,
            buffer = null,
            maxBytes = budgetMain,
            timeoutMs = LOGCAT_CMD_TIMEOUT_MS
        )

        val crashLog = if (includeCrashBuffer) {
            collectLogcatForPidTail(
                pid = pid,
                buffer = "crash",
                maxBytes = budgetCrash,
                timeoutMs = LOGCAT_CMD_TIMEOUT_MS
            )
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

        // Final tail trim across the whole combined text (header included).
        val combinedBytes = combinedText.toByteArray(Charsets.UTF_8)
        val trimmed = trimToTail(combinedBytes, budgetTotal)

        onProgress(20)

        val cfgRawHint = cfg.maxRawBytesHint.takeIf { it > 0 } ?: LOG_GZ_MAX_BYTES_DEFAULT
        val maxGzBytes = min(cfgRawHint, LOG_GZ_MAX_BYTES_DEFAULT).coerceAtLeast(50_000)
        val gz = gzipAndFitToMaxBytesBestEffort(trimmed, maxGzBytes)

        onProgress(35)

        val uploadResult = GitHubUploader.uploadFile(
            owner = cfg.owner,
            repo = cfg.repo,
            branch = cfg.branch,
            path = remotePath,
            token = cfg.token,
            bytes = gz,
            message = "Upload diagnostics log ($stamp)",
            onProgress = { p ->
                val mapped = 35 + ((p.coerceIn(0, 100) / 100.0) * 65.0).toInt()
                onProgress(mapped.coerceIn(35, 100))
            },
            maxRawBytesHint = cfg.maxRawBytesHint,
            maxRequestBytesHint = cfg.maxRequestBytesHint,
        )

        onProgress(100)

        LogUploadResult(
            remotePath = remotePath,
            fileUrl = uploadResult.fileUrl,
            commitSha = uploadResult.commitSha,
            bytesRaw = trimmed.size,
            bytesGz = gz.size,
        )
    }

    /**
     * Collect logcat output for the given PID.
     *
     * Uses:
     * - logcat -d --pid=<pid> -v threadtime
     * - optionally: -b <buffer>
     *
     * If --pid is not supported on the device, this will fall back to a non-PID
     * dump. Output is tail-captured to avoid large allocations.
     */
    private fun collectLogcatForPidTail(
        pid: Int,
        buffer: String?,
        maxBytes: Int,
        timeoutMs: Long
    ): String {
        val base = mutableListOf("logcat", "-d", "-v", "threadtime")
        if (!buffer.isNullOrBlank()) {
            base.add("-b")
            base.add(buffer)
        }
        base.add("--pid=$pid")

        val firstTry = runCatching {
            runProcessTail(base.toTypedArray(), maxBytes, timeoutMs)
        }.getOrElse { "" }

        if (firstTry.isNotBlank() && !looksLikePidUnsupported(firstTry)) return firstTry

        val fallback = mutableListOf("logcat", "-d", "-v", "threadtime")
        if (!buffer.isNullOrBlank()) {
            fallback.add("-b")
            fallback.add(buffer)
        }

        val out = runCatching {
            runProcessTail(fallback.toTypedArray(), maxBytes, timeoutMs)
        }.getOrElse { t ->
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
     * Run a process and return tail-captured UTF-8 output.
     *
     * This avoids building a giant String in memory when logcat output is large.
     * A timeout is enforced to avoid hung diagnostics collection on some devices.
     */
    private fun runProcessTail(cmd: Array<String>, maxBytes: Int, timeoutMs: Long): String {
        val cap = maxBytes.coerceAtLeast(10_000)
        val tail = TailBuffer(cap)

        val proc = ProcessBuilder(*cmd)
            .redirectErrorStream(true)
            .start()

        var readError: Throwable? = null

        val reader = Thread {
            runCatching {
                proc.inputStream.use { stream ->
                    pumpToTail(stream, tail)
                }
            }.onFailure { t ->
                readError = t
            }
        }.apply {
            isDaemon = true
            name = "SurveyFix-LogcatReader"
            start()
        }

        val elapsedMs = measureTimeMillis {
            val finished = runCatching { proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!finished) {
                runCatching { proc.destroy() }
                runCatching { proc.destroyForcibly() }
            }
        }

        // Give the reader a moment to observe EOF after destroy().
        runCatching { reader.join(250L) }

        runCatching { proc.destroy() }

        val out = if (readError != null) {
            "(logcat read failed: ${readError?.message})\n"
        } else {
            String(tail.toByteArray(), Charsets.UTF_8)
        }

        if (out.isBlank()) return "(logcat empty or restricted)\n"
        if (elapsedMs >= timeoutMs) {
            return "(logcat command timeout after ${timeoutMs}ms: ${cmd.joinToString(" ")})\n$out"
        }
        return out
    }

    /**
     * Pump stream into a tail buffer.
     */
    private fun pumpToTail(stream: InputStream, tail: TailBuffer) {
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            tail.append(buf, 0, n)
        }
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
     * Build a short header to help debugging.
     */
    private fun buildHeader(context: Context, pid: Int): String {
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
     * Keep only the tail of the log when it exceeds [maxBytes].
     */
    private fun trimToTail(bytes: ByteArray, maxBytes: Int): ByteArray {
        if (bytes.size <= maxBytes) return bytes
        val start = bytes.size - maxBytes
        return bytes.copyOfRange(start, bytes.size)
    }

    /**
     * Gzip compress and best-effort fit under [maxGzBytes].
     *
     * This function should not throw; diagnostics should "upload something" even under pressure.
     */
    private fun gzipAndFitToMaxBytesBestEffort(input: ByteArray, maxGzBytes: Int): ByteArray {
        return runCatching {
            var current = input
            repeat(5) { attempt ->
                val gz = safeGzip(current)
                if (gz.size in 1..maxGzBytes) return@runCatching gz

                val nextMax = (current.size * 0.70).toInt().coerceAtLeast(30_000)
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
     * Gzip compress in a safe way (never throws).
     */
    private fun safeGzip(input: ByteArray): ByteArray {
        return runCatching { gzip(input) }
            .getOrElse { t ->
                runCatching { gzip("(gzip failed: ${t.message})\n".toByteArray(Charsets.UTF_8)) }
                    .getOrElse { ByteArray(1) } // non-empty sentinel
            }
    }

    /**
     * Gzip compress.
     */
    private fun gzip(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    /**
     * Build remote path.
     */
    private fun buildRemotePath(
        remoteDir: String,
        addDateSubdir: Boolean,
        dateDir: String,
        fileName: String,
    ): String {
        val parts = mutableListOf<String>()
        remoteDir.trim('/').takeIf { it.isNotBlank() }?.let(parts::add)
        if (addDateSubdir) parts.add(dateDir)
        parts.add(fileName.trim('/'))
        return parts.joinToString("/")
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
            if (size < capacity) {
                return buf.copyOfRange(0, size)
            }
            val out = ByteArray(capacity)
            val tailLen = capacity - pos
            System.arraycopy(buf, pos, out, 0, tailLen)
            System.arraycopy(buf, 0, out, tailLen, pos)
            return out
        }
    }
}
