package com.negi.survey

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.negi.survey.net.GitHubUploadWorker
import com.negi.survey.net.GitHubUploader
import com.negi.survey.net.SupabaseCrashUploadWorker
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream
import kotlin.math.min
import kotlin.system.exitProcess

object CrashCapture {

    private const val TAG = "CrashCapture"

    /** Local directory for captured crash logs (gz). */
    private const val CRASH_DIR_REL = "diagnostics/crash"

    /** Local directory for GitHub mirror copies (so Supabase worker can delete originals). */
    private const val CRASH_GH_MIRROR_DIR_REL = "diagnostics/crash_github_mirror"

    private const val MAX_LOGCAT_BYTES = 850_000
    private const val LOGCAT_MAX_MS = 700L

    private const val LOGCAT_TAIL_LINES_PID = "2000"
    private const val LOGCAT_TAIL_LINES_FALLBACK = "3000"

    private const val MAX_FILES_TO_KEEP = 80
    private const val MAX_FILES_TO_ENQUEUE = 20

    /** Supabase object prefix for crash uploads (must match Storage policies). */
    private const val SUPABASE_CRASH_PREFIX = "surveyapp/crash"

    /** Prevent re-enqueue storms on rapid app restarts / multiple entry points. */
    private const val ENQUEUE_COOLDOWN_MS = 1200L

    private val installed = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)
    private val enqueueing = AtomicBoolean(false)
    private val lastEnqueueAt = AtomicLong(0L)

    /**
     * Install default uncaught exception handler that writes a gz crash report file.
     *
     * Note:
     * - Keep this method non-suspending and fast.
     * - Avoid allocations that could crash again.
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return

        val appContext = context.applicationContext ?: context
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        val pidAtInstall = Process.myPid()

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
                    Log.e(TAG, "Crash captured: ${file.absolutePath} bytes=${file.length()}")
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

        Log.d(TAG, "Installed default uncaught exception handler. pid=$pidAtInstall")
    }

    /**
     * Enqueue pending crash files for upload.
     *
     * Strategy:
     * - If Supabase is configured, enqueue Supabase uploads.
     * - If GitHub is configured, ALSO enqueue GitHub mirror uploads (optional but useful).
     * - Keep files locally if nothing is configured.
     */
    fun enqueuePendingCrashUploadsIfPossible(context: Context) {
        val appContext = context.applicationContext ?: context

        val now = SystemClock.elapsedRealtime()
        val prev = lastEnqueueAt.get()
        val dt = now - prev
        if (prev != 0L && dt in 0 until ENQUEUE_COOLDOWN_MS) {
            Log.d(TAG, "enqueuePendingCrashUploadsIfPossible skipped (cooldown). dt=${dt}ms")
            return
        }
        lastEnqueueAt.set(now)

        if (!enqueueing.compareAndSet(false, true)) {
            Log.d(TAG, "enqueuePendingCrashUploadsIfPossible skipped (already running).")
            return
        }

        try {
            val dir = crashDir(appContext).apply { mkdirs() }
            val ghMirrorDir = crashGitHubMirrorDir(appContext).apply { mkdirs() }

            purgeOldFiles(dir, MAX_FILES_TO_KEEP)
            purgeOldFiles(ghMirrorDir, MAX_FILES_TO_KEEP)

            val files = dir.listFiles { f ->
                f.isFile && f.length() > 0L && !f.name.startsWith(".")
            }?.toList().orEmpty()

            if (files.isEmpty()) return

            val supabaseConfigured = SupabaseCrashUploadWorker.isConfigured()
            val ghCfg = buildCrashGitHubConfigOrNull()

            Log.d(
                TAG,
                "Pending crash files=${files.size} dir=${dir.absolutePath} " +
                        "supabaseConfigured=$supabaseConfigured githubConfigured=${ghCfg != null}"
            )

            // Helpful hints for debugging config mismatches.
            Log.d(
                TAG,
                "Supabase hints: urlSet=${BuildConfig.SUPABASE_URL.isNotBlank()} urlLen=${BuildConfig.SUPABASE_URL.length} " +
                        "anonKeySet=${BuildConfig.SUPABASE_ANON_KEY.isNotBlank()} anonKeyLen=${BuildConfig.SUPABASE_ANON_KEY.length} " +
                        "bucket=${BuildConfig.SUPABASE_LOG_BUCKET}"
            )
            Log.d(
                TAG,
                "GitHub hints: owner=${BuildConfig.GH_OWNER} repo=${BuildConfig.GH_REPO} " +
                        "branch=${BuildConfig.GH_BRANCH} pathPrefix=${buildCrashGitHubConfigOrNull()?.pathPrefix}"
            )

            val targets = files
                .sortedByDescending { it.lastModified() }
                .take(MAX_FILES_TO_ENQUEUE)

            if (!supabaseConfigured && ghCfg == null) {
                Log.d(TAG, "No upload config found; crash uploads will remain local.")
                return
            }

            // 1) Supabase upload for originals (preferred)
            if (supabaseConfigured) {
                Log.d(TAG, "Enqueuing Supabase crash uploads… prefix=$SUPABASE_CRASH_PREFIX")
                targets.forEach { file ->
                    Log.d(
                        TAG,
                        "Supabase enqueue: name=${file.name} bytes=${file.length()} mtime=${file.lastModified()}"
                    )
                    SupabaseCrashUploadWorker.enqueueExistingCrash(
                        context = appContext,
                        file = file,
                        objectPrefix = SUPABASE_CRASH_PREFIX,
                        addDateSubdir = true
                    )
                }
            }

            // 2) GitHub mirror upload (copy files so Supabase worker can delete originals safely)
            if (ghCfg != null) {
                Log.d(TAG, "Enqueuing GitHub crash uploads… (mirror)")
                targets.forEach { file ->
                    val mirror = runCatching { makeGitHubMirrorCopy(file, ghMirrorDir) }
                        .onFailure { e ->
                            Log.w(TAG, "GitHub mirror copy failed: ${file.name} err=${e.message}", e)
                        }
                        .getOrNull()

                    if (mirror != null) {
                        Log.d(
                            TAG,
                            "GitHub enqueue: name=${mirror.name} bytes=${mirror.length()} mtime=${mirror.lastModified()}"
                        )
                        GitHubUploadWorker.enqueueExistingPayload(
                            context = appContext,
                            cfg = ghCfg,
                            file = mirror
                        )
                    }
                }
            }
        } finally {
            enqueueing.set(false)
        }
    }

    private fun makeGitHubMirrorCopy(src: File, mirrorDir: File): File {
        mirrorDir.mkdirs()

        // Keep the same filename but add a stable suffix to avoid collisions.
        val dstName = src.name.removeSuffix(".gz") + ".ghcopy.gz"
        val dst = File(mirrorDir, dstName)

        // If a previous copy exists and looks valid, reuse it.
        if (dst.exists() && dst.length() == src.length()) return dst

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

    private fun captureCrashToFile(
        context: Context,
        thread: Thread,
        throwable: Throwable
    ): File {
        val dir = crashDir(context).apply { mkdirs() }
        purgeOldFiles(dir, MAX_FILES_TO_KEEP)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val pid = Process.myPid()
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

    private fun crashGitHubMirrorDir(context: Context): File =
        File(context.filesDir, CRASH_GH_MIRROR_DIR_REL)

    private fun purgeOldFiles(dir: File, maxKeep: Int) {
        val all = dir.listFiles { f -> f.isFile && f.length() > 0L }?.toList().orEmpty()
        if (all.size <= maxKeep) return

        val sorted = all.sortedBy { it.lastModified() }
        val toDelete = sorted.take(all.size - maxKeep)
        toDelete.forEach { f -> runCatching { f.delete() } }
    }

    private fun collectLogcatBytes(pid: Int, maxBytes: Int, maxMs: Long): ByteArray {
        val primary = listOf(
            "logcat", "-d",
            "-v", "threadtime",
            "-b", "main", "-b", "system", "-b", "crash",
            "--pid=$pid",
            "-t", LOGCAT_TAIL_LINES_PID
        )

        val fallback = listOf(
            "logcat", "-d",
            "-v", "threadtime",
            "-b", "main", "-b", "system", "-b", "crash",
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

        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()

        return try {
            proc.inputStream.use { input ->
                val out = ByteArrayOutputStream(min(maxBytes, 128 * 1024))
                val buf = ByteArray(16 * 1024)

                while (out.size() < maxBytes) {
                    if (SystemClock.elapsedRealtime() - start > maxMs) break

                    val remaining = maxBytes - out.size()
                    val n = input.read(buf, 0, min(buf.size, remaining))
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
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }
}
