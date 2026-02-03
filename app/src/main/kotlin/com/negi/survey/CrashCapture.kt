package com.negi.survey

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.negi.survey.net.GitHubUploadWorker
import com.negi.survey.net.GitHubUploader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream
import kotlin.collections.orEmpty
import kotlin.collections.sortedBy
import kotlin.collections.sortedByDescending
import kotlin.system.exitProcess

object CrashCapture {

    private const val TAG = "CrashCapture"
    private const val CRASH_DIR_REL = "diagnostics/crash"

    private const val MAX_LOGCAT_BYTES = 850_000
    private const val LOGCAT_MAX_MS = 700L

    private const val LOGCAT_TAIL_LINES_PID = "2000"
    private const val LOGCAT_TAIL_LINES_FALLBACK = "3000"

    private const val MAX_FILES_TO_KEEP = 80
    private const val MAX_FILES_TO_ENQUEUE = 20

    private val installed = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        val prior = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (!capturing.compareAndSet(false, true)) {
                try {
                    prior?.uncaughtException(thread, throwable)
                } catch (_: Throwable) {
                    hardKill()
                }
                return@setDefaultUncaughtExceptionHandler
            }

            try {
                val file = runCatching { captureCrashToFile(appContext, thread, throwable) }
                    .onFailure { e -> Log.e(TAG, "Crash capture failed: ${e.message}", e) }
                    .getOrNull()

                if (file != null) {
                    Log.e(TAG, "Crash captured: ${file.absolutePath}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Crash capture unexpected failure: ${t.message}", t)
            } finally {
                try {
                    if (prior != null) {
                        prior.uncaughtException(thread, throwable)
                    } else {
                        hardKill()
                    }
                } catch (_: Throwable) {
                    hardKill()
                }
            }
        }

        Log.d(TAG, "Installed default uncaught exception handler.")
    }

    fun enqueuePendingCrashUploadsIfPossible(context: Context) {
        val cfg = buildCrashGitHubConfigOrNull() ?: run {
            Log.d(TAG, "GitHub config missing; crash uploads will remain local.")
            return
        }

        val dir = crashDir(context).apply { mkdirs() }
        purgeOldCrashFiles(dir)

        val files = dir.listFiles { f ->
            f.isFile && f.length() > 0L && !f.name.startsWith(".")
        }?.toList().orEmpty()

        if (files.isEmpty()) return

        Log.d(TAG, "Found ${files.size} pending crash file(s). Enqueuing uploads…")

        files
            .sortedByDescending { it.lastModified() }
            .take(MAX_FILES_TO_ENQUEUE)
            .forEach { file ->
                GitHubUploadWorker.enqueueExistingPayload(
                    context = context.applicationContext,
                    cfg = cfg,
                    file = file
                )
            }
    }

    private fun captureCrashToFile(
        context: Context,
        thread: Thread,
        throwable: Throwable
    ): File {
        val dir = crashDir(context).apply { mkdirs() }
        purgeOldCrashFiles(dir)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val pid = android.os.Process.myPid()
        val name = "crash_${stamp}_pid${pid}.log.gz"
        val outFile = File(dir, name)

        FileOutputStream(outFile).use { fos ->
            GZIPOutputStream(fos).use { gz ->
                val header = buildString {
                    appendLine("=== Crash Report ===")
                    appendLine("time_local=$stamp")
                    appendLine("pid=$pid")
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

                val logBytes = collectLogcatBytes(
                    pid = pid,
                    maxBytes = MAX_LOGCAT_BYTES,
                    maxMs = LOGCAT_MAX_MS
                )

                gz.write(logBytes)
                gz.flush()
            }
        }

        return outFile
    }

    private fun crashDir(context: Context): File =
        File(context.filesDir, CRASH_DIR_REL)

    private fun purgeOldCrashFiles(dir: File) {
        val all = dir.listFiles { f -> f.isFile && f.length() > 0L }?.toList().orEmpty()
        if (all.size <= MAX_FILES_TO_KEEP) return

        val sorted = all.sortedBy { it.lastModified() }
        val toDelete = sorted.take(all.size - MAX_FILES_TO_KEEP)
        toDelete.forEach { f -> runCatching { f.delete() } }
    }

    private fun collectLogcatBytes(pid: Int, maxBytes: Int, maxMs: Long): ByteArray {
        val primary = listOf(
            "logcat",
            "-d",
            "-v", "threadtime",
            "-b", "main",
            "-b", "system",
            "-b", "crash",
            "--pid=$pid",
            "-t", LOGCAT_TAIL_LINES_PID
        )

        val fallback = listOf(
            "logcat",
            "-d",
            "-v", "threadtime",
            "-b", "main",
            "-b", "system",
            "-b", "crash",
            "-t", LOGCAT_TAIL_LINES_FALLBACK
        )

        return runCatching { execAndReadCapped(primary, maxBytes, maxMs) }
            .recoverCatching { execAndReadCapped(fallback, maxBytes, maxMs) }
            .getOrElse { e ->
                ("(logcat capture failed: ${e.message})\n").toByteArray(Charsets.UTF_8)
            }
    }

    private fun execAndReadCapped(cmd: List<String>, maxBytes: Int, maxMs: Long): ByteArray {
        val start = SystemClock.elapsedRealtime()

        val pb = ProcessBuilder(cmd)
            .redirectErrorStream(true)

        val proc = pb.start()

        return try {
            proc.inputStream.use { input ->
                val out = ByteArrayOutputStream(minOf(maxBytes, 128 * 1024))
                val buf = ByteArray(16 * 1024)

                while (out.size() < maxBytes) {
                    if (SystemClock.elapsedRealtime() - start > maxMs) break

                    val remaining = maxBytes - out.size()
                    val n = input.read(buf, 0, minOf(buf.size, remaining))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }

                out.toByteArray()
            }
        } finally {
            runCatching { proc.destroy() }
        }
    }

    private fun buildCrashGitHubConfigOrNull(): GitHubUploader.GitHubConfig? {
        if (BuildConfig.GH_TOKEN.isBlank()) return null
        if (BuildConfig.GH_OWNER.isBlank() || BuildConfig.GH_REPO.isBlank()) return null

        val basePrefix = BuildConfig.GH_PATH_PREFIX.trim('/')
        val crashPrefix = listOf(basePrefix, "diagnostics/crash")
            .filter { it.isNotBlank() }
            .joinToString("/")

        return GitHubUploader.GitHubConfig(
            owner = BuildConfig.GH_OWNER,
            repo = BuildConfig.GH_REPO,
            branch = BuildConfig.GH_BRANCH.ifBlank { "main" },
            pathPrefix = crashPrefix,
            token = BuildConfig.GH_TOKEN
        )
    }

    private fun hardKill() {
        android.os.Process.killProcess(Process.myPid())
        exitProcess(10)
    }
}
