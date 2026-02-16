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
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
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
import java.util.TimeZone
import java.util.concurrent.TimeUnit
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

    /** Try to force logcat process shutdown quickly (best-effort). */
    private const val LOGCAT_WAITFOR_MS = 80L

    /** Prevent ensure storms (handler re-wrap). */
    private const val ENSURE_COOLDOWN_MS = 800L

    private val capturing = AtomicBoolean(false)
    private val enqueueing = AtomicBoolean(false)
    private val selfHealingRegistered = AtomicBoolean(false)

    private val lastEnqueueAt = AtomicLong(0L)
    private val lastEnsureAt = AtomicLong(0L)

    /** Root filesDir cached at install time to avoid keeping applicationContext references. */
    @Volatile
    private var filesDirRoot: File? = null

    /** Our installed handler instance (stable per-process). */
    @Volatile
    private var handler: CrashHandler? = null

    /** UTC timestamp used in filenames to keep ordering stable across devices/locales. */
    private val FILE_TS_UTC = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** Local timestamp for human-friendly header info. */
    private val HEADER_TS_LOCAL = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Install (or re-wrap) the default uncaught exception handler.
     *
     * Key behavior:
     * - Safe to call multiple times.
     * - If another SDK replaces the default handler after we installed, calling install() again
     *   will wrap the new handler and restore CrashCapture to the chain.
     */
    fun install(context: Context) {
        val root = runCatching { context.filesDir }.getOrNull()
        if (root == null) {
            Log.w(TAG, "install: context.filesDir unavailable; skipping install.")
            return
        }

        filesDirRoot = root

        val currentDefault = Thread.getDefaultUncaughtExceptionHandler()

        // Create the handler once, then keep re-wrapping the delegate as needed.
        val h = handler ?: synchronized(this) {
            handler ?: CrashHandler(
                filesDir = root,
                capturing = capturing,
                onHardKill = { hardKill() }
            ).also { handler = it }
        }

        ensureDefaultHandlerInstalled(h, currentDefault)
        Log.d(TAG, "install: ensured default handler. pid=${Process.myPid()} default=${describeHandler(Thread.getDefaultUncaughtExceptionHandler())}")
    }

    /**
     * Optional hardening: register lifecycle callbacks to periodically re-ensure the handler chain.
     *
     * Useful when:
     * - Some SDK replaces the default handler later (e.g., after Application.onCreate()).
     */
    fun registerSelfHealing(application: Application) {
        if (!selfHealingRegistered.compareAndSet(false, true)) return

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                ensureInstalled(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                ensureInstalled(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                ensureInstalled(activity)
            }

            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        Log.d(TAG, "registerSelfHealing: ActivityLifecycleCallbacks registered.")
    }

    /**
     * Ensure we are the default handler (best-effort) with a small cooldown.
     *
     * Safe to call often.
     */
    fun ensureInstalled(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val prev = lastEnsureAt.get()
        if (prev != 0L && (now - prev) in 0 until ENSURE_COOLDOWN_MS) return
        lastEnsureAt.set(now)

        install(context)
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
        val root = filesDirRoot ?: runCatching { context.filesDir }.getOrNull()
        if (root == null) {
            Log.w(TAG, "enqueuePendingCrashUploadsIfPossible: filesDir unavailable; skipping.")
            return
        }

        if (!enqueueing.compareAndSet(false, true)) {
            Log.d(TAG, "enqueuePendingCrashUploadsIfPossible skipped (already running).")
            return
        }

        try {
            val now = SystemClock.elapsedRealtime()
            val prev = lastEnqueueAt.get()
            val dt = now - prev
            if (prev != 0L && dt in 0 until ENQUEUE_COOLDOWN_MS) {
                Log.d(TAG, "enqueuePendingCrashUploadsIfPossible skipped (cooldown). dt=${dt}ms")
                return
            }
            lastEnqueueAt.set(now)

            val dir = crashDir(root).apply { mkdirs() }
            val ghMirrorDir = crashGitHubMirrorDir(root).apply { mkdirs() }

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

            Log.d(
                TAG,
                "Supabase hints: urlSet=${BuildConfig.SUPABASE_URL.isNotBlank()} urlLen=${BuildConfig.SUPABASE_URL.length} " +
                        "anonKeySet=${BuildConfig.SUPABASE_ANON_KEY.isNotBlank()} anonKeyLen=${BuildConfig.SUPABASE_ANON_KEY.length} " +
                        "bucket=${BuildConfig.SUPABASE_LOG_BUCKET}"
            )
            Log.d(
                TAG,
                "GitHub hints: owner=${BuildConfig.GH_OWNER} repo=${BuildConfig.GH_REPO} " +
                        "branch=${BuildConfig.GH_BRANCH} pathPrefix=${ghCfg?.pathPrefix}"
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
                        context = context.applicationContext,
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
                            context = context.applicationContext,
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

    private fun ensureDefaultHandlerInstalled(h: CrashHandler, currentDefault: Thread.UncaughtExceptionHandler?) {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current === h) return

        // Update delegate to whatever is currently installed (unless it is already us).
        h.updateDelegate(currentDefault ?: current)

        // Install ourselves as the default.
        Thread.setDefaultUncaughtExceptionHandler(h)
    }

    private fun makeGitHubMirrorCopy(src: File, mirrorDir: File): File {
        mirrorDir.mkdirs()

        val dst = File(mirrorDir, src.name)

        // If a previous copy exists and looks identical, reuse it.
        if (dst.exists() && dst.length() == src.length() && dst.lastModified() == src.lastModified()) {
            return dst
        }

        // If a different file already exists at the same name, create a unique suffixed name.
        val target = if (!dst.exists()) {
            dst
        } else {
            makeUniqueLike(srcName = src.name, dir = mirrorDir)
        }

        FileInputStream(src).use { input ->
            FileOutputStream(target).use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
                output.flush()
            }
        }

        target.setLastModified(src.lastModified())
        return target
    }

    /**
     * Preserve ".log.gz" naming when generating unique copies.
     * Examples:
     * - crash_x.log.gz -> crash_x-2.log.gz
     * - foo.gz -> foo-2.gz
     */
    private fun makeUniqueLike(srcName: String, dir: File): File {
        if (srcName.endsWith(".log.gz")) {
            val base = srcName.removeSuffix(".log.gz")
            var index = 2
            while (true) {
                val candidate = File(dir, "$base-$index.log.gz")
                if (!candidate.exists()) return candidate
                index++
            }
        }

        val base = srcName.removeSuffix(".gz")
        var index = 2
        while (true) {
            val candidate = File(dir, "$base-$index.gz")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun captureCrashToFile(
        filesDir: File,
        thread: Thread,
        throwable: Throwable
    ): File {
        val dir = crashDir(filesDir).apply { mkdirs() }
        purgeOldFiles(dir, MAX_FILES_TO_KEEP)

        val now = Date()
        val stampUtc = FILE_TS_UTC.format(now)
        val stampLocal = HEADER_TS_LOCAL.format(now)

        val pid = Process.myPid()
        val tid = Process.myTid()
        val uptimeTail = (SystemClock.elapsedRealtime() % 1_000_000L)

        val name = "crash_${stampUtc}_pid${pid}_tid${tid}_u${uptimeTail}.log.gz"
        val outFile = File(dir, name)

        FileOutputStream(outFile).use { fos ->
            GZIPOutputStream(fos).use { gz ->
                val header = buildString {
                    appendLine("=== Crash Report ===")
                    appendLine("time_utc=$stampUtc")
                    appendLine("time_local=$stampLocal")
                    appendLine("pid=$pid")
                    appendLine("tid=$tid")
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

            // Best-effort durability (avoid secondary crash if it fails).
            runCatching { fos.fd.sync() }
        }

        return outFile
    }

    private fun crashDir(filesDir: File): File =
        File(filesDir, CRASH_DIR_REL)

    private fun crashGitHubMirrorDir(filesDir: File): File =
        File(filesDir, CRASH_GH_MIRROR_DIR_REL)

    private fun purgeOldFiles(dir: File, maxKeep: Int) {
        val all = dir.listFiles { f -> f.isFile && f.length() > 0L && !f.name.startsWith(".") }?.toList().orEmpty()
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
            runCatching {
                proc.waitFor(LOGCAT_WAITFOR_MS, TimeUnit.MILLISECONDS)
            }
            runCatching {
                proc.destroy()
                proc.waitFor(LOGCAT_WAITFOR_MS, TimeUnit.MILLISECONDS)
            }
            runCatching {
                if (proc.isAlive) proc.destroyForcibly()
            }
        }
    }

    private fun buildCrashGitHubConfigOrNull(): GitHubUploader.GitHubConfig? {
        if (BuildConfig.GH_TOKEN.isBlank()) return null
        if (BuildConfig.GH_OWNER.isBlank() || BuildConfig.GH_REPO.isBlank()) return null

        // Accept either "repo" or "owner/repo" in GH_REPO, normalize to "repo".
        val repoName = BuildConfig.GH_REPO.substringAfterLast('/').trim()
        if (repoName.isBlank()) return null

        val basePrefix = BuildConfig.GH_PATH_PREFIX.trim('/')

        val crashPrefix = listOf(basePrefix, "diagnostics/crash")
            .filter { it.isNotBlank() }
            .joinToString("/")

        return GitHubUploader.GitHubConfig(
            owner = BuildConfig.GH_OWNER,
            repo = repoName,
            branch = BuildConfig.GH_BRANCH.ifBlank { "main" },
            pathPrefix = crashPrefix,
            token = BuildConfig.GH_TOKEN
        )
    }

    private fun describeHandler(h: Thread.UncaughtExceptionHandler?): String {
        if (h == null) return "null"
        return "${h.javaClass.name}@${Integer.toHexString(System.identityHashCode(h))}"
    }

    private class CrashHandler(
        private val filesDir: File,
        private val capturing: AtomicBoolean,
        private val onHardKill: () -> Unit
    ) : Thread.UncaughtExceptionHandler {

        @Volatile
        private var delegate: Thread.UncaughtExceptionHandler? = null

        fun updateDelegate(newDelegate: Thread.UncaughtExceptionHandler?) {
            // Avoid self-loop.
            if (newDelegate === this) return
            delegate = newDelegate
        }

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            if (!capturing.compareAndSet(false, true)) {
                try {
                    delegate?.uncaughtException(thread, throwable)
                } catch (_: Throwable) {
                    onHardKill()
                }
                return
            }

            try {
                val file = runCatching { captureCrashToFile(filesDir, thread, throwable) }
                    .onFailure { e -> Log.e(TAG, "Crash capture failed: ${e.message}", e) }
                    .getOrNull()

                if (file != null) {
                    Log.e(TAG, "Crash captured: ${file.absolutePath} bytes=${file.length()}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Crash capture unexpected failure: ${t.message}", t)
            } finally {
                try {
                    val d = delegate
                    if (d != null) {
                        d.uncaughtException(thread, throwable)
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
