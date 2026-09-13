/*
 * =====================================================================
 *  IshizukiTech LLC — Android App Shell
 *  ---------------------------------------------------------------------
 *  File: SurveyApp.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("unused")

package com.negi.survey

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.negi.survey.net.GitHubUploadWorker
import com.negi.survey.net.GitHubUploader
import com.negi.survey.net.RuntimeLogStore
import com.negi.survey.slm.LiteRtLM
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application bootstrap:
 * - Install CrashCapture early (attachBaseContext) to catch crashes during Application startup.
 * - Re-install (re-wrap) CrashCapture in onCreate to recover if another SDK overwrote the handler.
 * - Optionally register a lightweight self-healing hook to keep CrashCapture in the chain.
 * - Enqueue pending crash uploads on next start (onCreate).
 * - Run only in the main process.
 *
 * Notes:
 * - If the default WorkManager initializer is disabled, Configuration.Provider enables on-demand
 *   initialization on the first WorkManager.getInstance(context) call.
 * - Prefer enqueue from onCreate and keep WorkManager off the attachBaseContext critical path.
 * - Startup upload work is best-effort and retried once when WorkManager/config is temporarily unavailable.
 *
 * Performance note:
 * - Startup uploads (WorkManager + upload workers) can compete with LiteRT engine initialization
 *   for CPU / IO. To reduce cold-start contention, startup upload enqueues are deferred.
 */
class SurveyApp : Application(), Configuration.Provider {

    // Guard JNI load to avoid duplicate loads and allow retry on failure.
    private val litertJniLoadOnce = AtomicBoolean(false)

    override fun attachBaseContext(base: Context) {
        val t0 = SystemClock.elapsedRealtime()
        super.attachBaseContext(base)

        val pn = currentProcessName(base)
        val isMain = isMainProcess(base, pn)
        val pid = android.os.Process.myPid()

        if (!isMain) {
            Log.d(TAG, "attachBaseContext: pid=$pid process=${pn ?: "<unknown>"} isMain=false sdk=${Build.VERSION.SDK_INT}")
            Log.d(TAG, "Non-main process; bootstrap skipped in attachBaseContext.")
            return
        }

        val appCtx = base.applicationContext ?: base

        // Load LiteRTLM JNI as early as possible (main process only).
        ensureLiteRtLmJniLoaded(where = "attachBaseContext")

        // Start runtime log capture as early as possible (main process only).
        runCatching { RuntimeLogStore.start(appCtx) }
            .onFailure { t ->
                Log.w(TAG, "RuntimeLogStore.start failed in attachBaseContext: ${t.message}", t)
            }

        // Install ring log store as early as possible to retain pre-crash context across restarts.
        runCatching { AppRingLogStore.install(appCtx) }
            .onFailure { t ->
                RuntimeLogStore.w(TAG, "AppRingLogStore.install failed in attachBaseContext: ${t.message}", t)
            }

        logBoot("attachBaseContext", pid, pn, isMain)
        RuntimeLogStore.d(TAG, "bootTiming: attachBaseContext total=${SystemClock.elapsedRealtime() - t0}ms")

        // Guard: attachBaseContext() should run once, but OEM/SDK edge cases exist.
        if (!attachBootOnce.compareAndSet(false, true)) {
            RuntimeLogStore.d(TAG, "attachBaseContext bootstrap already executed; skipping.")
            return
        }

        // IMPORTANT:
        // Do NOT initialize WorkManager here.
        RuntimeLogStore.d(TAG, "Skipping WorkManager init in attachBaseContext; will init lazily on first enqueue.")

        // Install as early as possible to catch crashes during Application startup.
        // Do NOT force-enqueue pending uploads here; enqueue is done later (deferred) in onCreate.
        safeCrashInstall(where = "attachBaseContext", context = appCtx)
    }

    private fun ensureLiteRtLmJniLoaded(where: String) {
        if (!litertJniLoadOnce.compareAndSet(false, true)) {
            Log.d(TAG, "LiteRTLM JNI already loaded; skip. where=$where")
            return
        }

        /** Load LiteRTLM JNI library early to avoid call-before-load UnsatisfiedLinkError. */
        runCatching {
            System.loadLibrary("litertlm_jni")
            Log.d(TAG, "Loaded LiteRTLM JNI: litertlm_jni where=$where")
        }.onFailure { t ->
            // Allow retry later if the first attempt fails.
            litertJniLoadOnce.set(false)
            Log.e(TAG, "Failed to load LiteRTLM JNI (litertlm_jni) where=$where", t)

            // If RuntimeLogStore is already active, also mirror the error there.
            runCatching {
                RuntimeLogStore.e(TAG, "Failed to load LiteRTLM JNI where=$where: ${t.message}")
            }
        }
    }

    override fun onCreate() {
        val t0 = SystemClock.elapsedRealtime()
        super.onCreate()

        val pn = currentProcessName(this)
        val isMain = isMainProcess(this, pn)
        val pid = android.os.Process.myPid()

        if (!isMain) {
            Log.d(TAG, "onCreate: pid=$pid process=${pn ?: "<unknown>"} isMain=false sdk=${Build.VERSION.SDK_INT}")
            Log.d(TAG, "Non-main process; bootstrap skipped in onCreate.")
            return
        }

        val appCtx = applicationContext ?: this

        // Retry JNI loading here if the early attachBaseContext attempt failed.
        // The AtomicBoolean is reset on failure, so this is a real second chance.
        ensureLiteRtLmJniLoaded(where = "onCreate(retry)")

        // Ensure runtime log capture is running (idempotent).
        val tRt0 = SystemClock.elapsedRealtime()
        runCatching { RuntimeLogStore.start(appCtx) }
            .onFailure { t ->
                Log.w(TAG, "RuntimeLogStore.start failed in onCreate: ${t.message}", t)
            }
        RuntimeLogStore.d(TAG, "bootTiming: RuntimeLogStore.start took=${SystemClock.elapsedRealtime() - tRt0}ms")

        // Ensure ring log store is installed (idempotent).
        val tRing0 = SystemClock.elapsedRealtime()
        runCatching { AppRingLogStore.install(appCtx) }
            .onFailure { t ->
                RuntimeLogStore.w(TAG, "AppRingLogStore.install failed in onCreate: ${t.message}", t)
            }
        RuntimeLogStore.d(TAG, "bootTiming: AppRingLogStore.install took=${SystemClock.elapsedRealtime() - tRing0}ms")

        logBoot("onCreate", pid, pn, isMain)

        // Guard: onCreate() should run once per process, but keep it defensive.
        if (!onCreateBootOnce.compareAndSet(false, true)) {
            RuntimeLogStore.d(TAG, "onCreate bootstrap already executed; skipping.")
            return
        }

        // Keep any global singletons ready early.
        runCatching { LiteRtLM.setApplicationContext(this) }
            .onFailure { t ->
                RuntimeLogStore.w(TAG, "LiteRtLM.setApplicationContext failed: ${t.message}", t)
            }

        // Re-wrap default handler in case any SDK replaced it after attachBaseContext().
        safeCrashInstall(where = "onCreate(rewrap)", context = appCtx)

        // Optional: self-heal if another SDK overwrites the handler later in runtime.
        safeRegisterSelfHealingOnce(this)

        // Defer WorkManager-related enqueues to reduce contention with model initialization.
        scheduleDeferredStartupEnqueues(appCtx)

        RuntimeLogStore.d(TAG, "bootTiming: onCreate total=${SystemClock.elapsedRealtime() - t0}ms")
    }

    /**
     * WorkManager Configuration provider.
     */
    override val workManagerConfiguration: Configuration by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .setDefaultProcessName(packageName)
            .build()
    }

    /**
     * Defer startup enqueues to reduce cold-start contention (CPU/IO) with LiteRT initialization.
     *
     * Tuning:
     * - Set STARTUP_DEFERRED_ENQUEUE_DELAY_MS = 0L to enqueue immediately (not recommended for cold-start perf).
     */
    private fun scheduleDeferredStartupEnqueues(context: Context) {
        if (!startupDeferredOnce.compareAndSet(false, true)) {
            RuntimeLogStore.d(TAG, "Deferred startup enqueues already scheduled; skipping.")
            return
        }

        val appCtx = context.applicationContext ?: context
        val delayMs = STARTUP_DEFERRED_ENQUEUE_DELAY_MS

        RuntimeLogStore.w(TAG, "Startup enqueues deferred: delay=${delayMs}ms (to reduce init contention)")

        mainHandler.postDelayed(
            {
                val t0 = SystemClock.elapsedRealtime()
                RuntimeLogStore.w(TAG, "Deferred startup enqueues begin...")

                // Startup: enqueue runtime log upload (best-effort, main process only).
                safeEnqueueStartupRuntimeLogsUploadOnce(appCtx)

                // Startup: enqueue ring segment upload (best-effort, main process only).
                safeEnqueueStartupRingLogsUploadOnce(appCtx)

                // Enqueue pending crash uploads from previous run (best-effort).
                safeEnqueuePendingUploadsWithRetryOnce(appCtx)

                RuntimeLogStore.w(TAG, "Deferred startup enqueues done: took=${SystemClock.elapsedRealtime() - t0}ms")
            },
            delayMs
        )
    }

    /**
     * Ensures WorkManager can be obtained.
     *
     * With Configuration.Provider + disabled default initializer, getInstance(context)
     * performs WorkManager's documented on-demand initialization. Do not call
     * WorkManager.initialize() manually from this path.
     */
    private fun ensureWorkManagerAvailable(where: String, ctx: Context): Boolean {
        val appCtx = ctx.applicationContext ?: ctx
        val t0 = SystemClock.elapsedRealtime()

        return try {
            WorkManager.getInstance(appCtx)
            RuntimeLogStore.d(
                TAG,
                "WorkManager available. where=$where took=${SystemClock.elapsedRealtime() - t0}ms"
            )
            true
        } catch (t: Throwable) {
            RuntimeLogStore.w(
                TAG,
                "WorkManager unavailable. where=$where msg=${t.message}",
                t
            )
            false
        }
    }

    /**
     * On app start, upload remaining RuntimeLogStore logs to GitHub.
     */
    private fun safeEnqueueStartupRuntimeLogsUploadOnce(context: Context) {
        if (!startupRtLogsOnce.compareAndSet(false, true)) {
            RuntimeLogStore.d(TAG, "Startup runtime logs enqueue already executed; skipping.")
            return
        }

        val appCtx = context.applicationContext ?: context
        enqueueStartupRuntimeLogsInternal(appCtx, attempt = "immediate")
    }

    private fun enqueueStartupRuntimeLogsInternal(context: Context, attempt: String) {
        val appCtx = context.applicationContext ?: context

        if (!ensureWorkManagerAvailable(where = "startupRtLogs($attempt)", ctx = appCtx)) {
            RuntimeLogStore.w(TAG, "Startup runtime logs: WorkManager unavailable ($attempt).")
            scheduleStartupRuntimeLogsRetry(appCtx, reason = "workmanager")
            return
        }

        val cfg = runCatching { resolveGitHubConfigNormalizedBestEffort(appCtx) }
            .onFailure { t ->
                RuntimeLogStore.w(TAG, "Startup runtime logs: config lookup failed: ${t.message}", t)
            }
            .getOrNull()

        if (cfg == null || cfg.owner.isBlank() || cfg.repo.isBlank() || cfg.token.isBlank()) {
            RuntimeLogStore.d(TAG, "Startup runtime logs: GitHub config not available ($attempt).")
            scheduleStartupRuntimeLogsRetry(appCtx, reason = "config")
            return
        }

        RuntimeLogStore.d(
            TAG,
            "Startup runtime logs target -> owner=${cfg.owner} repo=${cfg.repo} branch=${cfg.branch} base='${cfg.pathPrefix.trim('/')}/${REMOTE_DIR_RUNTIME_LOGS}'"
        )

        runCatching {
            GitHubUploadWorker.enqueueStartupRuntimeLogsUpload(
                context = appCtx,
                cfg = cfg,
                remoteDir = REMOTE_DIR_RUNTIME_LOGS,
                addDateSubdir = true,
                reason = "app_start",
                deleteZipAfter = true,
                maxZipBytes = 1_000_000L,
                deleteSourceAfterUpload = true
            )
            RuntimeLogStore.d(TAG, "Startup runtime logs: enqueue requested ($attempt).")
        }.onFailure { t ->
            RuntimeLogStore.w(TAG, "Startup runtime logs: enqueue failed: ${t.confirmedMsg()}", t)
        }
    }

    private fun scheduleStartupRuntimeLogsRetry(context: Context, reason: String) {
        if (!startupRtLogsRetryScheduled.compareAndSet(false, true)) {
            RuntimeLogStore.d(TAG, "Startup runtime logs: retry already scheduled; reason=$reason")
            return
        }

        RuntimeLogStore.w(TAG, "Startup runtime logs: scheduling one retry; reason=$reason")
        mainHandler.postDelayed(
            { enqueueStartupRuntimeLogsInternal(context, attempt = "delayed") },
            STARTUP_ENQUEUE_RETRY_DELAY_MS
        )
    }

    /**
     * On app start, upload AppRingLogStore ring segments (seg_XX.log) to GitHub.
     */
    private fun safeEnqueueStartupRingLogsUploadOnce(context: Context) {
        if (!startupRingLogsOnce.compareAndSet(false, true)) {
            RuntimeLogStore.d(TAG, "Startup ring logs enqueue already executed; skipping.")
            return
        }

        val appCtx = context.applicationContext ?: context
        enqueueStartupRingLogsInternal(appCtx, attempt = "immediate")
    }

    private fun enqueueStartupRingLogsInternal(context: Context, attempt: String) {
        val appCtx = context.applicationContext ?: context

        if (!ensureWorkManagerAvailable(where = "startupRingLogs($attempt)", ctx = appCtx)) {
            RuntimeLogStore.w(TAG, "Startup ring logs: WorkManager unavailable ($attempt).")
            scheduleStartupRingLogsRetry(appCtx, reason = "workmanager")
            return
        }

        val cfg = runCatching { resolveGitHubConfigNormalizedBestEffort(appCtx) }
            .onFailure { t ->
                RuntimeLogStore.w(TAG, "Startup ring logs: config lookup failed: ${t.message}", t)
            }
            .getOrNull()

        if (cfg == null || cfg.owner.isBlank() || cfg.repo.isBlank() || cfg.token.isBlank()) {
            RuntimeLogStore.d(TAG, "Startup ring logs: GitHub config not available ($attempt).")
            scheduleStartupRingLogsRetry(appCtx, reason = "config")
            return
        }

        RuntimeLogStore.d(
            TAG,
            "Startup ring logs target -> owner=${cfg.owner} repo=${cfg.repo} branch=${cfg.branch} base='${cfg.pathPrefix.trim('/')}/${REMOTE_DIR_APPLOG_RING}'"
        )

        runCatching {
            GitHubUploadWorker.enqueueStartupRingLogsUpload(
                context = appCtx,
                cfg = cfg,
                remoteDir = REMOTE_DIR_APPLOG_RING,
                addDateSubdir = true,
                reason = "app_start"
            )
            RuntimeLogStore.d(TAG, "Startup ring logs: enqueue requested ($attempt).")
        }.onFailure { t ->
            RuntimeLogStore.w(TAG, "Startup ring logs: enqueue failed: ${t.confirmedMsg()}", t)
        }
    }

    private fun scheduleStartupRingLogsRetry(context: Context, reason: String) {
        if (!startupRingLogsRetryScheduled.compareAndSet(false, true)) {
            RuntimeLogStore.d(TAG, "Startup ring logs: retry already scheduled; reason=$reason")
            return
        }

        RuntimeLogStore.w(TAG, "Startup ring logs: scheduling one retry; reason=$reason")
        mainHandler.postDelayed(
            { enqueueStartupRingLogsInternal(context, attempt = "delayed") },
            STARTUP_ENQUEUE_RETRY_DELAY_MS
        )
    }

    private fun resolveGitHubConfigNormalizedBestEffort(context: Context): GitHubUploader.GitHubConfig? {
        val raw = tryLoadGitHubConfigBestEffort(context) ?: return null
        return normalizeGitHubConfig(raw)
    }

    private fun normalizeGitHubConfig(cfg: GitHubUploader.GitHubConfig): GitHubUploader.GitHubConfig? {
        var owner = cfg.owner.trim()
        var repo = cfg.repo.trim()
        val token = cfg.token.trim()
        val branch = cfg.branch.trim().ifBlank { "main" }
        val prefix = cfg.pathPrefix.trim().trim('/')

        if (repo.contains('/')) {
            val inferredOwner = repo.substringBefore('/').trim()
            val inferredRepo = repo.substringAfterLast('/').trim()
            if (owner.isBlank()) owner = inferredOwner
            repo = inferredRepo
        }

        if (owner.isBlank() || repo.isBlank() || token.isBlank()) return null
        if (owner.contains(' ') || repo.contains(' ')) return null

        return GitHubUploader.GitHubConfig(
            owner = owner,
            repo = repo,
            token = token,
            branch = branch,
            pathPrefix = prefix
        )
    }

    private fun tryLoadGitHubConfigBestEffort(context: Context): GitHubUploader.GitHubConfig? {
        val candidates = listOf(
            "com.negi.survey.net.GitHubDiagnosticsConfigStore",
            "com.negi.survey.net.GitHubDiagnosticsConfig",
            "com.negi.survey.net.GitHubConfigStore"
        )

        val methodNames = listOf(
            "getGitHubConfig",
            "loadGitHubConfig",
            "readGitHubConfig",
            "getConfig",
            "load",
            "get",
            "read"
        )

        for (className in candidates) {
            val cls = runCatching { Class.forName(className) }.getOrNull() ?: continue

            val receiver: Any? = runCatching {
                cls.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            }.getOrNull()

            for (mn in methodNames) {
                val m = runCatching {
                    cls.methods.firstOrNull { method ->
                        method.name == mn &&
                                method.parameterTypes.size == 1 &&
                                Context::class.java.isAssignableFrom(method.parameterTypes[0])
                    }
                }.getOrNull()

                if (m != null) {
                    val invokeReceiver = if (Modifier.isStatic(m.modifiers)) null else receiver
                    if (Modifier.isStatic(m.modifiers) || invokeReceiver != null) {
                        val out = runCatching { m.invoke(invokeReceiver, context) }.getOrNull()
                        parseGitHubConfigFromAny(out)?.let { return it }
                    }
                }
            }

            for (mn in methodNames) {
                val m = runCatching {
                    cls.methods.firstOrNull { method ->
                        method.name == mn && method.parameterTypes.isEmpty()
                    }
                }.getOrNull()

                if (m != null) {
                    val invokeReceiver = if (Modifier.isStatic(m.modifiers)) null else receiver
                    if (Modifier.isStatic(m.modifiers) || invokeReceiver != null) {
                        val out = runCatching { m.invoke(invokeReceiver) }.getOrNull()
                        parseGitHubConfigFromAny(out)?.let { return it }
                    }
                }
            }
        }

        return null
    }

    private fun parseGitHubConfigFromAny(any: Any?): GitHubUploader.GitHubConfig? {
        if (any == null) return null
        if (any is GitHubUploader.GitHubConfig) return any

        if (any is Map<*, *>) {
            val owner = (any["owner"] ?: any["gh_owner"] ?: any["GH_OWNER"])?.toString().orEmpty()
            val repo = (any["repo"] ?: any["gh_repo"] ?: any["GH_REPO"])?.toString().orEmpty()
            val token = (any["token"] ?: any["gh_token"] ?: any["GH_TOKEN"])?.toString().orEmpty()
            val branch = (any["branch"] ?: any["gh_branch"] ?: any["GH_BRANCH"])?.toString()
                .takeIf { !it.isNullOrBlank() } ?: "main"
            val pathPrefix = (any["pathPrefix"] ?: any["path_prefix"] ?: any["prefix"] ?: any["path"])?.toString().orEmpty()

            if (token.isBlank()) return null

            return normalizeGitHubConfig(
                GitHubUploader.GitHubConfig(
                    owner = owner,
                    repo = repo,
                    token = token,
                    branch = branch,
                    pathPrefix = pathPrefix
                )
            )
        }

        fun readProp(name: String): String? {
            val getter = runCatching {
                any.javaClass.methods.firstOrNull {
                    it.parameterTypes.isEmpty() && (it.name.equals(name, true) ||
                            it.name.equals("get${name.replaceFirstChar { c -> c.uppercaseChar() }}", true))
                }
            }.getOrNull()

            val gv = runCatching { getter?.invoke(any)?.toString() }.getOrNull()
            if (!gv.isNullOrBlank()) return gv

            val fv = runCatching {
                any.javaClass.declaredFields.firstOrNull { it.name.equals(name, true) }
                    ?.apply { isAccessible = true }
                    ?.get(any)
                    ?.toString()
            }.getOrNull()

            return fv
        }

        val owner = readProp("owner").orEmpty()
        val repo = readProp("repo").orEmpty()
        val token = readProp("token").orEmpty()
        val branch = readProp("branch")?.takeIf { it.isNotBlank() } ?: "main"
        val pathPrefix = (readProp("pathPrefix") ?: readProp("path_prefix") ?: readProp("prefix")).orEmpty()

        return normalizeGitHubConfig(
            GitHubUploader.GitHubConfig(
                owner = owner,
                repo = repo,
                token = token,
                branch = branch,
                pathPrefix = pathPrefix
            )
        )
    }

    private fun currentProcessName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { android.os.Process.myProcessName() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        // Public Linux procfs fallback for older Android versions.
        runCatching {
            val bytes = File("/proc/self/cmdline")
                .inputStream()
                .use { it.readBytes() }

            bytes
                .takeWhile { it.toInt() != 0 }
                .toByteArray()
                .toString(Charsets.UTF_8)
                .trim()
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // Last-resort framework lookup.
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val pid = android.os.Process.myPid()
            am?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return null
    }

    private fun isMainProcess(context: Context, processName: String?): Boolean {
        val pn = processName?.trim().orEmpty()
        if (pn.isEmpty()) return true
        return pn == context.packageName
    }

    private fun safeCrashInstall(where: String, context: Context) {
        val appCtx = context.applicationContext ?: context
        val label = "SurveyApp:$where"
        runCatching {
            CrashCapture.install(appCtx, where = label)
            RuntimeLogStore.d(TAG, "CrashCapture installed: where=$label")
        }.onFailure { t ->
            RuntimeLogStore.w(TAG, "CrashCapture.install failed: where=$label msg=${t.message}", t)
        }
    }

    private fun safeRegisterSelfHealingOnce(app: Application) {
        if (!selfHealOnce.compareAndSet(false, true)) {
            RuntimeLogStore.d(TAG, "CrashCapture self-healing already registered; skipping.")
            return
        }
        runCatching {
            CrashCapture.registerSelfHealing(app)
            RuntimeLogStore.d(TAG, "CrashCapture self-healing registered.")
        }.onFailure { t ->
            RuntimeLogStore.w(TAG, "CrashCapture.registerSelfHealing failed: ${t.message}", t)
        }
    }

    private fun safeEnqueuePendingUploadsWithRetryOnce(context: Context) {
        if (!enqueuePendingOnce.compareAndSet(false, true)) {
            RuntimeLogStore.d(TAG, "CrashCapture pending enqueue already executed; skipping.")
            return
        }

        val appCtx = context.applicationContext ?: context

        if (!ensureWorkManagerAvailable(where = "enqueuePending(immediate)", ctx = appCtx)) {
            RuntimeLogStore.w(TAG, "WorkManager unavailable; scheduling delayed crash enqueue retry.")

            if (enqueueRetryScheduled.compareAndSet(false, true)) {
                mainHandler.postDelayed(
                    {
                        if (!ensureWorkManagerAvailable(where = "enqueuePending(delayed)", ctx = appCtx)) {
                            RuntimeLogStore.w(TAG, "WorkManager still unavailable in delayed retry; skipping enqueue.")
                            return@postDelayed
                        }

                        runCatching {
                            CrashCapture.enqueuePendingCrashUploadsIfPossible(
                                appCtx,
                                where = "SurveyApp:enqueuePending(delayed)"
                            )
                            RuntimeLogStore.d(TAG, "CrashCapture pending uploads requested (delayed retry).")
                        }.onFailure { t ->
                            RuntimeLogStore.w(
                                TAG,
                                "CrashCapture.enqueuePendingCrashUploads(delayed) failed: ${t.message}",
                                t
                            )
                        }
                    },
                    ENQUEUE_RETRY_DELAY_MS
                )
            } else {
                RuntimeLogStore.d(TAG, "Delayed enqueue retry already scheduled; skipping.")
            }
            return
        }

        runCatching {
            CrashCapture.enqueuePendingCrashUploadsIfPossible(
                appCtx,
                where = "SurveyApp:enqueuePending(immediate)"
            )
            RuntimeLogStore.d(TAG, "CrashCapture pending uploads requested (immediate).")
        }.onFailure { t ->
            RuntimeLogStore.w(
                TAG,
                "CrashCapture.enqueuePendingCrashUploads(immediate) failed: ${t.message}",
                t
            )
        }
    }

    private fun logBoot(stage: String, pid: Int, processName: String?, isMain: Boolean) {
        val pn = processName?.takeIf { it.isNotBlank() } ?: "<unknown>"
        val msg = "$stage: pid=$pid process=$pn isMain=$isMain sdk=${Build.VERSION.SDK_INT}"
        RuntimeLogStore.d(TAG, msg)
        runCatching { AppRingLogStore.log("D", TAG, msg) }
    }

    private fun Throwable.confirmedMsg(): String = message ?: "Unknown error"

    companion object {
        private const val TAG = "SurveyApp"

        private const val REMOTE_DIR_RUNTIME_LOGS = "diagnostics/runtime_logs"
        private const val REMOTE_DIR_APPLOG_RING = "diagnostics/applog_ring"

        private const val ENQUEUE_RETRY_DELAY_MS = 1600L
        private const val STARTUP_ENQUEUE_RETRY_DELAY_MS = 1600L

        // New: defer startup enqueues to reduce contention with model initialization.
        private const val STARTUP_DEFERRED_ENQUEUE_DELAY_MS = 2500L

        // Process-level guards (single source of truth).
        private val attachBootOnce = AtomicBoolean(false)
        private val onCreateBootOnce = AtomicBoolean(false)
        private val selfHealOnce = AtomicBoolean(false)
        private val enqueuePendingOnce = AtomicBoolean(false)
        private val enqueueRetryScheduled = AtomicBoolean(false)

        private val startupDeferredOnce = AtomicBoolean(false)

        private val startupRtLogsOnce = AtomicBoolean(false)
        private val startupRtLogsRetryScheduled = AtomicBoolean(false)

        private val startupRingLogsOnce = AtomicBoolean(false)
        private val startupRingLogsRetryScheduled = AtomicBoolean(false)

        private val mainHandler: Handler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            Handler(Looper.getMainLooper())
        }
    }
}
