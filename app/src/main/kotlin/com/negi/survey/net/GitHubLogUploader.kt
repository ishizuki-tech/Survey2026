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
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dateDir = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        val remoteName = "logcat_${stamp}_pid${pid}.log.gz"
        val remotePath = buildRemotePath(
            remoteDir = remoteDir,
            addDateSubdir = addDateSubdir,
            dateDir = dateDir,
            fileName = remoteName,
        )

        val header = if (includeDeviceHeader) buildHeader(context, pid) else ""

        onProgress(5)
        val mainLog = collectLogcatForPid(pid = pid, buffer = null)
        val crashLog = if (includeCrashBuffer) collectLogcatForPid(pid = pid, buffer = "crash") else ""

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
        val trimmed = trimToTail(combinedBytes, maxUncompressedBytes)

        onProgress(20)

        val gz = gzipAndFitToContentsLimit(trimmed)

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
            }
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
     * dump. This may include other processes. We still tail-trim and gzip.
     */
    private fun collectLogcatForPid(pid: Int, buffer: String?): String {
        val base = mutableListOf("logcat", "-d", "-v", "threadtime")
        if (!buffer.isNullOrBlank()) {
            base.add("-b")
            base.add(buffer)
        }
        base.add("--pid=$pid")

        val firstTry = runCatching { runProcess(base.toTypedArray()) }.getOrElse { "" }
        if (firstTry.isNotBlank() && !looksLikePidUnsupported(firstTry)) return firstTry

        val fallback = mutableListOf("logcat", "-d", "-v", "threadtime")
        if (!buffer.isNullOrBlank()) {
            fallback.add("-b")
            fallback.add(buffer)
        }

        val out = runCatching { runProcess(fallback.toTypedArray()) }.getOrElse { t ->
            Log.w(TAG, "collectLogcatForPid failed: ${t.message}", t)
            "collectLogcatForPid failed: ${t.message}\n"
        }

        return buildString {
            appendLine("=== WARNING ===")
            appendLine("PID-filtered logcat is not available on this device/runtime.")
            appendLine("Fallback logcat dump may include other processes. Output is tail-trimmed.")
            appendLine("================")
            appendLine()
            append(out)
        }
    }

    /**
     * Run a process and return UTF-8 output.
     */
    private fun runProcess(cmd: Array<String>): String {
        val proc = ProcessBuilder(*cmd)
            .redirectErrorStream(true)
            .start()

        val out = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        runCatching { proc.destroy() }
        return out
    }

    /**
     * Heuristic: detect "unknown option" patterns.
     */
    private fun looksLikePidUnsupported(output: String): Boolean {
        val s = output.lowercase(Locale.US)
        return s.contains("unknown option") && s.contains("pid")
    }

    /**
     * Build a short header to help debugging.
     */
    private fun buildHeader(context: Context, pid: Int): String {
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
     * Keep only the tail of the log when it exceeds [maxBytes].
     */
    private fun trimToTail(bytes: ByteArray, maxBytes: Int): ByteArray {
        if (bytes.size <= maxBytes) return bytes
        val start = bytes.size - maxBytes
        return bytes.copyOfRange(start, bytes.size)
    }

    /**
     * Gzip compress and ensure the gzip size is under GitHub Contents API raw-byte guard.
     *
     * GitHubUploader uses a raw bytes guard (~900k). Logs compress well, but we still
     * handle worst cases by trimming further and recompressing a few times.
     */
    private fun gzipAndFitToContentsLimit(input: ByteArray): ByteArray {
        val hardLimit = 900_000 // must match your GitHubUploader guard

        var current = input
        repeat(4) { attempt ->
            val gz = gzip(current)
            if (gz.size <= hardLimit) return gz

            val nextMax = (current.size * 0.75).toInt().coerceAtLeast(50_000)
            val trimmed = trimToTail(current, nextMax)

            Log.w(
                TAG,
                "gzip too large (attempt=$attempt gz=${gz.size}). " +
                        "Trimming tail to $nextMax bytes and retrying."
            )
            current = trimmed
        }

        return gzip(current)
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
}
