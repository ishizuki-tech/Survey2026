/* =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: LiteRtLM.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * ===================================================================== */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.slm

import android.os.Build
import android.util.Log

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.negi.survey.BuildConfig
import com.negi.survey.net.RuntimeLogStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "LiteRtLM"

/** Upper bound for error strings rendered in UI/log aggregation. */
private const val ERROR_MAX_CHARS = 280

/** Absolute cap for maxNumTokens. */
private const val ABS_MAX_NUM_TOKENS = 4096

/** Per-request decode cap for compact Survey responses. */
private const val MAX_OUTPUT_TOKENS_PER_REQUEST = 64
/**
 * Maximum Engine token capacity used for Android Emulator CPU execution.
 *
 * Emulator runs are intended primarily for functional validation. Keeping the
 * KV-cache capacity smaller reduces memory pressure while preserving the full
 * production token configuration on physical devices.
 */
private const val EMULATOR_MAX_NUM_TOKENS = 1024

/** CPU worker count used only for Android Emulator functional tests. */
private const val EMULATOR_CPU_THREAD_COUNT = 4

/**
 * A/B diagnostic switch: recreate the Conversation before each emulator inference.
 *
 * Production policy:
 * - Keep this disabled so a real request is not preceded by a diagnostic session reset.
 * - The repository already performs its normal post-inference Conversation reset at the
 *   native cleanup safepoint, so disabling this A/B reset does not enable history reuse.
 * - Re-enable only for controlled emulator experiments that intentionally measure a
 *   fresh Conversation before every request.
 */
private const val EMULATOR_FRESH_CONVERSATION_PER_INFERENCE_AB_TEST = false

/**
 * A/B diagnostic switch: sweep synthetic prompt lengths on emulator text requests.
 *
 * Keep this disabled while benchmarking the real Survey prompt. The synthetic
 * sweep helpers remain available so the same build can be switched back to the
 * controlled length test without changing the inference pipeline.
 * Physical devices and multimodal requests always use the original application input.
 */
private const val EMULATOR_PREFILL_SWEEP_AB_TEST = false
private val EMULATOR_PREFILL_SWEEP_LENGTHS = intArrayOf(19, 100, 300, 600, 1_000, 1_400)
private val emulatorPrefillSweepSequence = AtomicLong(0L)

/**
 * Native benchmark collection for the current Android Emulator diagnostic build.
 *
 * This switch is still gated by [isAndroidEmulator] before Engine construction, so
 * physical devices keep ExperimentalFlags.enableBenchmark=false. Keep this enabled
 * only while collecting prefill/decode timing diagnostics, then return it to false
 * for production-equivalent measurements.
 */
private const val EMULATOR_NATIVE_BENCHMARK_LOGGING = false

/** Base instruction used by the synthetic prefill benchmark prompts. */
private const val EMULATOR_PREFILL_SWEEP_BASE_TEXT = "Reply with only: OK"

/** Neutral padding repeated until the requested benchmark character length is reached. */
private const val EMULATOR_PREFILL_SWEEP_PADDING =
    "\nIgnore all remaining benchmark padding. Neutral benchmark context. "

private const val DEFAULT_TOPK = 40
private const val DEFAULT_TOPP = 0.9f
private const val DEFAULT_TEMPERATURE = 0.7f

/**
 * Warm-engine retention window.
 *
 * Engine.initialize() is expensive on large GPU models because LiteRT-LM may
 * need to load several model components, construct delegates, prepare kernels,
 * and restore/compile backend cache artifacts. A two-minute timeout causes
 * unnecessary cold reinitialization during normal survey pauses, so keep a
 * healthy engine warm for the full interactive session window.
 *
 * forceCleanUp() remains available for explicit memory release.
 */
private const val IDLE_CLEANUP_MS = 30L * 60L * 1000L

/**
 * Retry budget for replacing a Conversation after deterministic close.
 *
 * Conversation.close() synchronously deletes the native Conversation. Start the
 * replacement immediately and back off only if LiteRT-LM still reports that the
 * single-session slot is occupied.
 */
private const val SESSION_RECREATE_RETRY_TIMEOUT_MS = 5_000L
private const val SESSION_RECREATE_EXTRA_RETRY_MS = 1_500L

/** Init await timeout. */
private const val INIT_AWAIT_TIMEOUT_MS = 90_000L

/** Streaming watchdog. */
private const val STREAM_WATCHDOG_MS = 120_000L

/** Emergency hard-close watchdog. */
private const val HARD_CLOSE_TIMEOUT_MS = 15_000L
private const val HARD_CLOSE_POLL_MS = 750L
private const val HARD_CLOSE_ENABLE = true

/**
 * Upper bound used by synchronous recovery teardown.
 *
 * This covers the native hard-close watchdog plus a small scheduling cushion.
 */
private const val FORCE_CLOSE_WAIT_TIMEOUT_MS =
    HARD_CLOSE_TIMEOUT_MS + 2_000L

private const val FORCE_CLOSE_WAIT_POLL_MS = 50L

/** Persistent cache root dir name for LiteRT-LM serialized artifacts. */
private const val LITERT_CACHE_SUBDIR = "litertlm_cache"

/** Bump this if cache format changes or you want to invalidate old caches. */
private const val LITERT_CACHE_VERSION = 1

/** Streaming debug toggles. */
private val DEBUG_STREAM: Boolean = BuildConfig.DEBUG
private const val DEBUG_STREAM_EVERY_N = 16

/** Throwable debug toggles. */
private val DEBUG_ERROR_THROWABLE: Boolean = BuildConfig.DEBUG
private const val DEBUG_ERROR_STACK_LINES = 18

/** RunState snapshot logging (safe + short). */
private val DEBUG_STATE: Boolean = BuildConfig.DEBUG
private const val DEBUG_STATE_EVERY_N = 1

/**
 * Holder for a LiteRT-LM Engine and its active Conversation.
 *
 * IMPORTANT:
 * - Do not close engine/conversation while native stream may still be active.
 *
 * Snapshot notes:
 * - systemMessage/tools snapshots are stored to preserve behavior across
 *   capability upgrades (re-init triggered by multimodal needs).
 */
data class LiteRtLmInstance(
    val engine: Engine,
    @Volatile var conversation: Conversation,
    val supportImage: Boolean,
    val supportAudio: Boolean,
    val engineConfigSnapshot: EngineConfig,
    @Volatile var conversationConfigSnapshot: ConversationConfig,
    @Volatile var systemMessageSnapshot: Message?,
    @Volatile var toolsSnapshot: List<Any>,
)

/**
 * LiteRT-LM integration singleton.
 */
object LiteRtLM {

    /** Main thread handler for UI-safe callbacks. */
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    /** Dedicated IO scope for init/cleanup work. */
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Global lock for instance map + lifecycle transitions. */
    private val stateMutex: Mutex = Mutex()

    /** Runtime instances keyed by runtimeKey(model). */
    private val instances: MutableMap<String, LiteRtLmInstance> = ConcurrentHashMap()

    /** Pending actions to execute once the native stream terminates. */
    private val pendingAfterStream: MutableMap<String, MutableList<() -> Unit>> = ConcurrentHashMap()

    /**
     * Initialization ownership marker.
     *
     * Access to this set and [initSignals] is coordinated by [initFlightGuard].
     * The guard covers only tiny in-memory state transitions; no blocking or
     * suspend work is performed while holding it.
     */
    private val initInFlight: MutableSet<String> =
        ConcurrentHashMap.newKeySet()

    /**
     * Completion signal for the current initialization owner of each key.
     *
     * Contract:
     * - "" means success.
     * - A non-empty string means initialization failed.
     *
     * A completed signal may remain in this map for diagnostics until the next
     * owner atomically replaces it.
     */
    private val initSignals:
            ConcurrentHashMap<String, CompletableDeferred<String>> =
        ConcurrentHashMap()

    /**
     * Short critical-section guard that makes initInFlight + initSignals one
     * coherent state machine.
     *
     * This prevents the "completed signal replaced while old owner is still
     * marked in-flight" race that can otherwise create a Deferred with no
     * producer.
     */
    private val initFlightGuard =
        Any()

    /** Result of atomically attempting to become the initialization owner. */
    private data class InitFlightAcquire(
        val owner: Boolean,
        val signal: CompletableDeferred<String>,
    )

    /** Serialize initializeIfNeeded() and generateText(). */
    private val apiMutex: Mutex = Mutex()

    /** Busy flag used only by generateText() (suspend API). */
    private val busy: AtomicBoolean = AtomicBoolean(false)

    /** Scheduled idle cleanup jobs (per key). */
    private val cleanupJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

    /** Stored application context for best-effort auto re-init inside runInference(). */
    private val appContextRef: AtomicReference<Context?> = AtomicReference(null)

    /**
     * Per-key "session lock" (Conversation lifecycle lock).
     *
     * Why:
     * - Some LiteRT-LM builds support only ONE active session per process/engine.
     * - Conversation.close() may be async-ish; createConversation() immediately after close can
     *   hit FAILED_PRECONDITION: "A session already exists".
     * - We must serialize create/close/reset paths per key to eliminate races.
     */
    private val sessionMutexes: ConcurrentHashMap<String, Mutex> = ConcurrentHashMap()

    /** Returns true when a generateText call is currently in progress. */
    fun isBusy(): Boolean = busy.get()

    /** Stable runtime key. */
    private fun runtimeKey(model: Model): String = "${model.name}|${model.taskPath}"

    /** Post work onto the main thread. */
    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** Allow host app to set context early. */
    fun setApplicationContext(context: Context) {
        appContextRef.set(context.applicationContext)
    }

    /**
     * Build a deterministic synthetic prompt with an exact character length.
     *
     * The instruction stays at the beginning so generated output remains comparable,
     * while neutral padding changes only the amount of text consumed during prefill.
     */
    private fun buildEmulatorPrefillSweepPrompt(targetLength: Int): String {
        val safeTarget = targetLength.coerceAtLeast(1)

        if (safeTarget <= EMULATOR_PREFILL_SWEEP_BASE_TEXT.length) {
            return EMULATOR_PREFILL_SWEEP_BASE_TEXT.take(safeTarget)
        }

        return buildString(capacity = safeTarget) {
            append(EMULATOR_PREFILL_SWEEP_BASE_TEXT)

            while (length < safeTarget) {
                val remaining = safeTarget - length
                append(
                    EMULATOR_PREFILL_SWEEP_PADDING.take(remaining)
                )
            }
        }
    }


    /** SHA-1 hex for stable cache directory naming (fast + sufficient for this use). */
    private fun sha1Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(((b.toInt() and 0xFF) + 0x100).toString(16).substring(1))
        return sb.toString()
    }

    /** Best-effort count files in a directory (returns null if inaccessible). */
    private fun dirFileCount(path: String?): Int? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            val f = File(path)
            if (!f.exists() || !f.isDirectory) return@runCatching 0
            f.listFiles()?.size ?: 0
        }.getOrNull()
    }

    /** Best-effort mkdirs; returns true if exists or created. */
    private fun ensureDirExists(dir: File): Boolean {
        return runCatching {
            if (dir.exists()) return@runCatching dir.isDirectory
            dir.mkdirs()
        }.getOrElse { false }
    }

    /**
     * Compute a stable per-engine cache directory path (prefers noBackupFilesDir).
     *
     * Why:
     * - GPU/OpenCL delegate compilation artifacts are expensive.
     * - cacheDir can be cleared by the OS; noBackupFilesDir is much more persistent.
     * - Separating by backend/capabilities reduces risk of incompatible cache reuse.
     */
    private fun stableEngineCacheDir(
        context: Context,
        modelPath: String,
        backend: Backend,
        supportImage: Boolean,
        supportAudio: Boolean,
    ): String? {
        return runCatching {
            val base = File(context.noBackupFilesDir, LITERT_CACHE_SUBDIR)
            if (!ensureDirExists(base)) return@runCatching null

            /*
             * Include a lightweight model-file fingerprint in the cache key.
             * Replacing a model in-place under the same pathname must not reuse
             * serialized GPU artifacts produced for different model bytes.
             */
            val modelFile = File(modelPath)
            val modelSize = runCatching { modelFile.length() }.getOrDefault(-1L)
            val modelMtime = runCatching { modelFile.lastModified() }.getOrDefault(-1L)

            val key = buildString {
                append("v=").append(LITERT_CACHE_VERSION)
                append("|path=").append(modelPath)
                append("|size=").append(modelSize)
                append("|mtime=").append(modelMtime)
                append("|backend=").append(backend.name)
                append("|img=").append(supportImage)
                append("|aud=").append(supportAudio)
            }

            val id = sha1Hex(key)
            val dir = File(base, id)
            if (!ensureDirExists(dir)) return@runCatching null

            dir.absolutePath
        }.getOrNull()
    }

    /**
     * Per-key run state (native lifecycle + logical completion + cancel).
     */
    private data class RunState(
        val active: AtomicBoolean = AtomicBoolean(false),
        val terminated: AtomicBoolean = AtomicBoolean(false),
        val logicalDone: AtomicBoolean = AtomicBoolean(false),
        val cancelRequested: AtomicBoolean = AtomicBoolean(false),
        val pendingCancel: AtomicBoolean = AtomicBoolean(false),
        val runId: AtomicLong = AtomicLong(0L),
        val lastTerminateAtMs: AtomicLong = AtomicLong(0L),
        val lastUseAtMs: AtomicLong = AtomicLong(0L),
        val lastMessageAtMs: AtomicLong = AtomicLong(0L),
        val logicalTerminator: AtomicReference<((Long) -> Unit)?> = AtomicReference(null),
        val hardCloseRunId: AtomicLong = AtomicLong(0L),
        val terminalRunId: AtomicLong = AtomicLong(0L),
        val cleanupToken: AtomicLong = AtomicLong(0L),

        /**
         * Hook invoked after native termination (onDone/onError), OR after hard-close watchdog.
         * Must be set per active run, and cleared after firing.
         */
        val nativeDoneHook: AtomicReference<(() -> Unit)?> = AtomicReference(null),
    )

    private val runStates: ConcurrentHashMap<String, RunState> = ConcurrentHashMap()

    internal data class RunControlTestHooks(
        val beforeScopedCancelValidation: suspend (Long) -> Unit = {},
        val armHardCloseOnRunStart: (Long) -> Boolean = { false },
        val awaitHardCloseAction: (suspend (Long) -> Unit)? = null,
    )

    @Volatile
    internal var runControlTestHooks: RunControlTestHooks? = null

    /** Get or create per-key run state (thread-safe). */
    private fun getRunState(key: String): RunState {
        val existing = runStates[key]
        if (existing != null) return existing
        val created = RunState()
        val prev = runStates.putIfAbsent(key, created)
        return prev ?: created
    }

    /** Claim terminal processing for exactly one owner of the current run. */
    private fun claimTerminalRun(
        rs: RunState,
        expectedRunId: Long,
    ): Boolean {
        while (true) {
            if (rs.runId.get() != expectedRunId) return false

            val observedOwner = rs.terminalRunId.get()
            if (observedOwner == expectedRunId) return false

            if (rs.terminalRunId.compareAndSet(observedOwner, expectedRunId)) {
                return true
            }
        }
    }

    /** Touch last-use time and invalidate any scheduled cleanup. */
    private fun markUsed(key: String) {
        val now = SystemClock.elapsedRealtime()
        val rs = getRunState(key)
        rs.lastUseAtMs.set(now)
        rs.cleanupToken.incrementAndGet()
    }

    /** Cancel any scheduled idle cleanup for this key. */
    private fun cancelScheduledCleanup(key: String, reason: String) {
        val job = cleanupJobs.remove(key)
        if (job != null) {
            if (job.isActive) {
                job.cancel()
                RuntimeLogStore.d(TAG, "Idle cleanup cancelled: key='$key' reason='$reason'")
            } else {
                RuntimeLogStore.d(TAG, "Idle cleanup cleared: key='$key' reason='$reason'")
            }
        }
    }

    /**
     * Schedule an idle cleanup (debounced + token-guarded).
     *
     * The map removal in finally is identity-checked. Without that guard, an
     * older cancelled cleanup coroutine can wake up after a newer job has been
     * installed and accidentally remove the newer job from cleanupJobs.
     */
    private fun scheduleIdleCleanup(
        key: String,
        delayMs: Long,
        reason: String,
    ) {
        cancelScheduledCleanup(key, "reschedule:$reason")

        val tokenAtSchedule =
            getRunState(key).cleanupToken.get()

        lateinit var job: Job

        job =
            ioScope.launch {
                try {
                    RuntimeLogStore.d(
                        TAG,
                        "Idle cleanup scheduled: key='$key' " +
                                "in ${delayMs}ms reason='$reason'"
                    )

                    delay(delayMs)

                    closeInstanceIfStillIdle(
                        key = key,
                        requiredIdleMs = delayMs,
                        requiredToken = tokenAtSchedule,
                        reason = "idle:$reason",
                    )
                } finally {
                    cleanupJobs.remove(key, job)
                }
            }

        cleanupJobs[key] = job
    }

    /**
     * Atomically acquire initialization ownership or join the current owner.
     */
    private fun acquireInitFlight(
        key: String,
    ): InitFlightAcquire =
        synchronized(initFlightGuard) {
            if (initInFlight.contains(key)) {
                val activeSignal =
                    initSignals[key]
                        ?: throw IllegalStateException(
                            "LiteRT-LM init state is inconsistent: " +
                                    "in-flight key has no signal."
                        )

                return@synchronized InitFlightAcquire(
                    owner = false,
                    signal = activeSignal,
                )
            }

            val signal =
                CompletableDeferred<String>()

            initSignals[key] =
                signal

            initInFlight.add(key)

            InitFlightAcquire(
                owner = true,
                signal = signal,
            )
        }

    /**
     * Complete an initialization signal without throwing on duplicate completion.
     */
    private fun completeInitSignal(
        signal: CompletableDeferred<String>,
        error: String,
    ) {
        if (!signal.isCompleted) {
            signal.complete(error)
        }
    }

    /**
     * Clear ownership only if [signal] is still the signal registered for
     * [key]. The identity check prevents a stale owner from clearing a newer
     * initialization attempt.
     */
    private fun releaseInitFlight(
        key: String,
        signal: CompletableDeferred<String>,
    ) {
        synchronized(initFlightGuard) {
            if (initSignals[key] === signal) {
                initInFlight.remove(key)
            }
        }
    }

    /**
     * Snapshot the active initialization signal without creating/replacing it.
     */
    private fun activeInitSignal(
        key: String,
    ): CompletableDeferred<String>? =
        synchronized(initFlightGuard) {
            if (!initInFlight.contains(key)) {
                null
            } else {
                initSignals[key]
                    ?: throw IllegalStateException(
                        "LiteRT-LM init state is inconsistent: " +
                                "in-flight key has no signal."
                    )
            }
        }

    /**
     * Remove only a completed/stale signal.
     *
     * A new initialization may acquire ownership before a cleanup coroutine
     * finishes removing an old runtime. Never remove the signal while the key
     * is currently owned by an initialization flight.
     */
    private fun clearInitSignalIfIdle(
        key: String,
    ) {
        synchronized(initFlightGuard) {
            if (!initInFlight.contains(key)) {
                initSignals.remove(key)
            }
        }
    }

    /**
     * Await completion of an initialization that is already in flight.
     *
     * A waiter is strictly read-only with respect to the signal lifecycle.
     */
    private suspend fun awaitInitIfInFlight(
        key: String,
        reason: String,
    ) {
        val signal =
            activeInitSignal(key)
                ?: return

        RuntimeLogStore.d(
            TAG,
            "Awaiting init in flight: key='$key' reason='$reason'"
        )

        val error =
            withTimeoutOrNull(
                INIT_AWAIT_TIMEOUT_MS
            ) {
                signal.await()
            } ?: "Initialization timed out after " +
            "${INIT_AWAIT_TIMEOUT_MS}ms."

        if (error.isNotEmpty()) {
            throw IllegalStateException(
                "LiteRT-LM init-in-flight failed: $error"
            )
        }
    }
    /**
     * Returns true when the process is running on an Android Emulator.
     *
     * LiteRT-LM GPU execution depends on device GPU/OpenCL capabilities that are
     * generally not exposed by the Android Emulator in the same way as physical
     * Android hardware. Force CPU execution on emulators to avoid initializing an
     * unsupported OpenCL path.
     */
    private val isAndroidEmulator: Boolean by lazy {
        val fingerprint = Build.FINGERPRINT.lowercase(Locale.US)
        val model = Build.MODEL.lowercase(Locale.US)
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.US)
        val brand = Build.BRAND.lowercase(Locale.US)
        val device = Build.DEVICE.lowercase(Locale.US)
        val product = Build.PRODUCT.lowercase(Locale.US)
        val hardware = Build.HARDWARE.lowercase(Locale.US)

        fingerprint.startsWith("generic") ||
                fingerprint.contains("emulator") ||
                model.contains("google_sdk") ||
                model.contains("emulator") ||
                model.contains("android sdk built for") ||
                manufacturer.contains("genymotion") ||
                hardware.contains("goldfish") ||
                hardware.contains("ranchu") ||
                product.contains("sdk_gphone") ||
                product.contains("emulator") ||
                (brand.startsWith("generic") && device.startsWith("generic"))
    }

    /** Normalize accelerator string for stable backend selection. */
    private fun normalizedAccelerator(model: Model): String {
        return model.getStringConfigValue(ConfigKey.ACCELERATOR, Accelerator.GPU.label)
            .trim()
            .uppercase(Locale.US)
            .ifBlank { Accelerator.GPU.label }
    }

    /**
     * Resolve the effective backend for the current runtime environment.
     *
     * Physical devices honor the model accelerator configuration. Android
     * Emulators always use the CPU backend because their virtual GPU environment
     * does not reliably expose the OpenCL capabilities required by LiteRT-LM.
     */
    private fun preferredBackend(model: Model): Backend {
        if (isAndroidEmulator) {
            return Backend.CPU(
                threadCount = EMULATOR_CPU_THREAD_COUNT,
            )
        }

        return when (normalizedAccelerator(model)) {
            Accelerator.CPU.label -> Backend.CPU()
            Accelerator.GPU.label -> Backend.GPU()
            else -> Backend.GPU()
        }
    }
    /** Return a backend's stable semantic name without relying on object equality. */
    private fun backendName(backend: Backend?): String? =
        backend?.name

    /**
     * Resolve the Engine token capacity for the current runtime environment.
     *
     * The model configuration remains authoritative on physical devices. Android
     * Emulator CPU execution uses a smaller capacity because a large KV cache adds
     * unnecessary memory pressure during functional testing.
     */
    private fun resolvedMaxTokens(model: Model): Pair<Int, Int> {
        val defaultMax =
            defaultMaxTokensForModel(model.name)

        val raw =
            model.getIntConfigValue(
                ConfigKey.MAX_TOKENS,
                defaultMax,
            ).coerceAtLeast(1)

        val projectLimited =
            raw.coerceIn(
                1,
                ABS_MAX_NUM_TOKENS,
            )

        val effective =
            if (isAndroidEmulator) {
                projectLimited.coerceAtMost(
                    EMULATOR_MAX_NUM_TOKENS
                )
            } else {
                projectLimited
            }

        return raw to effective
    }
    /**
     * Return true when an existing initialized Engine can satisfy the requested
     * model/backend/capability configuration without paying another cold
     * Engine.initialize() cost.
     *
     * Capability supersets are reusable: an engine already initialized with
     * image/audio support can also serve a text-only request.
     */
    private fun engineCanServe(
        instance: LiteRtLmInstance,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
    ): Boolean {
        val requestedBackend =
            preferredBackend(model)

        val (_, requestedMaxTokens) =
            resolvedMaxTokens(model)

        val cfg =
            instance.engineConfigSnapshot

        val backendMatches =
            backendName(cfg.backend) ==
                    backendName(requestedBackend)

        val tokenCapacityMatches =
            cfg.maxNumTokens ==
                    requestedMaxTokens

        val imageSupported =
            !supportImage ||
                    (
                            instance.supportImage &&
                                    cfg.visionBackend != null
                            )

        val audioSupported =
            !supportAudio ||
                    (
                            instance.supportAudio &&
                                    cfg.audioBackend != null
                            )

        return backendMatches &&
                tokenCapacityMatches &&
                imageSupported &&
                audioSupported
    }

    /**
     * Enable LiteRT-LM native benchmark collection for emulator diagnostics.
     *
     * LiteRT-LM reads this process-global experimental flag only when a new
     * Engine is created. Keep it disabled on physical devices so benchmark
     * instrumentation cannot affect production inference.
     */
    @OptIn(ExperimentalApi::class)
    private fun configureNativeBenchmarkForDiagnostics(): Boolean {
        val enabled =
            isAndroidEmulator &&
                    EMULATOR_NATIVE_BENCHMARK_LOGGING

        ExperimentalFlags.enableBenchmark =
            enabled

        return enabled
    }

    /**
     * Read the native benchmark snapshot after a completed inference.
     *
     * This helper isolates the experimental LiteRT-LM API behind a single
     * opt-in boundary so the normal inference path does not need experimental
     * annotations.
     */
    @OptIn(ExperimentalApi::class)
    private fun buildNativeBenchmarkMessage(
        conversation: Conversation,
        key: String,
        runId: Long,
        effectiveTextLength: Int,
    ): String {
        val info =
            conversation.getBenchmarkInfo()

        val kvTokenCount =
            runCatching {
                conversation.getTokenCount()
            }.getOrDefault(-1)

        return "LiteRT-LM native benchmark: " +
                "key='$key' runId=$runId " +
                "initSec=${info.initTimeInSecond} " +
                "nativeTtftSec=${info.timeToFirstTokenInSecond} " +
                "prefillTokens=${info.lastPrefillTokenCount} " +
                "prefillTokPerSec=${info.lastPrefillTokensPerSecond} " +
                "decodeTokens=${info.lastDecodeTokenCount} " +
                "decodeTokPerSec=${info.lastDecodeTokensPerSecond} " +
                "kvTokens=$kvTokenCount " +
                "effectiveTextLen=$effectiveTextLength"
    }

    /**
     * Explicitly disable LiteRT-LM speculative decoding / MTP.
     *
     * Why this is required:
     * - Speculative decoding is only valid when the selected .litertlm package
     *   contains a compatible TF_LITE_MTP_DRAFTER section.
     * - The current Gemma 3n package used by this application does not contain
     *   that section.
     * - Forcing enableSpeculativeDecoding=true therefore makes Engine creation
     *   fail with NOT_FOUND before normal inference can begin.
     *
     * Compatibility strategy:
     * - LiteRT-LM has exposed ExperimentalFlags differently across releases.
     * - Reflection keeps this wrapper source-compatible with versions where the
     *   flag class, setter, or backing field is absent.
     * - A return value of true means this wrapper successfully wrote false into
     *   the runtime flag. A false return means the API was unavailable; in that
     *   case LiteRT-LM's own default behavior remains in effect.
     *
     * This setting is process-global in LiteRT-LM, so it is applied before
     * constructing every new Engine rather than only once at application start.
     */
    private fun disableSpeculativeDecodingBestEffort(): Boolean {
        return runCatching {
            val cls =
                Class.forName(
                    "com.google.ai.edge.litertlm.ExperimentalFlags"
                )

            val receiver =
                runCatching {
                    cls.getField("INSTANCE")
                        .get(null)
                }.getOrNull()

            val setter =
                cls.methods.firstOrNull { method ->
                    method.name ==
                            "setEnableSpeculativeDecoding" &&
                            method.parameterCount == 1 &&
                            (
                                    method.parameterTypes[0] ==
                                            Boolean::class.javaPrimitiveType ||
                                            method.parameterTypes[0] ==
                                            Boolean::class.javaObjectType
                                    )
                }

            if (setter != null) {
                setter.invoke(
                    receiver,
                    false,
                )

                true
            } else {
                val field =
                    cls.declaredFields.firstOrNull { candidate ->
                        candidate.name ==
                                "enableSpeculativeDecoding" &&
                                (
                                        candidate.type ==
                                                Boolean::class.javaPrimitiveType ||
                                                candidate.type ==
                                                Boolean::class.javaObjectType
                                        )
                    } ?: return@runCatching false

                field.isAccessible = true

                if (Modifier.isStatic(field.modifiers)) {
                    field.set(
                        null,
                        false,
                    )
                } else {
                    val target =
                        receiver
                            ?: return@runCatching false

                    field.set(
                        target,
                        false,
                    )
                }

                true
            }
        }.getOrDefault(false)
    }

    /**
     * Detect the exact model/runtime incompatibility raised when speculative
     * decoding is enabled but the package does not contain an MTP drafter.
     */
    private fun isMissingMtpDrafterError(
        throwable: Throwable,
    ): Boolean {
        var current: Throwable? =
            throwable

        while (current != null) {
            val message =
                (
                        current.message
                            ?: current.toString()
                        ).uppercase(Locale.US)

            if (
                "TF_LITE_MTP_DRAFTER" in message &&
                (
                        "NOT_FOUND" in message ||
                                "NOT FOUND" in message
                        )
            ) {
                return true
            }

            current =
                current.cause
        }

        return false
    }

    /** Sanitize TopK - must be >= 1. */
    private fun sanitizeTopK(k: Int): Int = k.coerceAtLeast(1)

    /** Sanitize TopP - must be in [0, 1]. */
    private fun sanitizeTopP(p: Float): Float = p.takeIf { it in 0f..1f } ?: DEFAULT_TOPP

    /** Sanitize Temperature - typical safe band [0, 2]. */
    private fun sanitizeTemperature(t: Float): Float = t.takeIf { it in 0f..2f } ?: DEFAULT_TEMPERATURE

    /** Sanitize a per-request decode limit used by normal and diagnostic warm-up requests. */
    private fun sanitizeMaxOutputTokens(maxOutputTokens: Int): Int =
        maxOutputTokens.coerceIn(1, MAX_OUTPUT_TOKENS_PER_REQUEST)

    /** Clean and compress error messages for UI/logging. */
    private fun cleanError(msg: String?): String {
        return msg
            ?.replace("INTERNAL:", "", ignoreCase = true)
            ?.replace("\\s+".toRegex(), " ")
            ?.trim()
            ?.take(ERROR_MAX_CHARS)
            ?.takeIf { it.isNotEmpty() }
            ?: "Unknown error"
    }

    /** Build a short stack string for logs. */
    private fun shortStack(t: Throwable, maxLines: Int = DEBUG_ERROR_STACK_LINES): String {
        val lines = t.stackTrace.take(maxLines).joinToString(separator = "\n") { "  at $it" }
        val cause = t.cause
        val causeLine = if (cause != null) "\nCaused by: ${cause::class.java.name}: ${cause.message}" else ""
        return "${t::class.java.name}: ${t.message}\n$lines$causeLine"
    }

    /**
     * Try to extract a "status code" (or similar) from Throwable using reflection.
     *
     * This is intentionally defensive because SDK versions differ.
     */
    private fun extractStatusCodeBestEffort(t: Throwable): Int? {
        val methodNames = listOf(
            "getStatusCode",
            "statusCode",
            "getCode",
            "code",
            "getErrorCode",
            "errorCode",
        )
        for (name in methodNames) {
            val m = runCatching {
                t.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterCount == 0 &&
                            (it.returnType == Int::class.javaPrimitiveType || it.returnType == Int::class.javaObjectType)
                }
            }.getOrNull() ?: continue

            val v = runCatching { m.invoke(t) as? Int }.getOrNull()
            if (v != null) return v
        }

        val fieldNames = listOf("statusCode", "code", "errorCode")
        for (fn in fieldNames) {
            val f = runCatching { t.javaClass.getDeclaredField(fn) }.getOrNull() ?: continue
            runCatching { f.isAccessible = true }
            val v = runCatching { f.get(t) }.getOrNull()
            if (v is Int) return v
        }

        val c = t.cause
        if (c != null && c !== t) return extractStatusCodeBestEffort(c)

        return null
    }

    /** Detect cancellation from throwable/message. */
    private fun isCancellationThrowable(t: Throwable, msg: String): Boolean {
        if (t is CancellationException) return true
        val lc = msg.lowercase(Locale.US)
        if (lc.contains("cancel")) return true
        if (lc.contains("canceled")) return true
        if (lc.contains("cancelled")) return true
        if (lc.contains("aborted") && lc.contains("user")) return true
        return false
    }

    /** Detect "session already exists" class of errors (FAILED_PRECONDITION). */
    private fun isSessionAlreadyExistsError(t: Throwable): Boolean {
        val m = (t.message ?: t.toString()).lowercase(Locale.US)
        if (m.contains("a session already exists")) return true
        if (m.contains("only one session is supported")) return true
        if (m.contains("failed_precondition")) return true
        return false
    }

    /** Detect "Conversation is not alive" errors for recovery paths. */
    private fun isConversationNotAliveError(t: Throwable): Boolean {
        val m = (t.message ?: t.toString()).lowercase(Locale.US)
        return m.contains("conversation is not alive")
    }

    /** Get or create the per-key session mutex. */
    private fun getSessionMutex(key: String): Mutex {
        val existing = sessionMutexes[key]
        if (existing != null) return existing
        val created = Mutex()
        val prev = sessionMutexes.putIfAbsent(key, created)
        return prev ?: created
    }

    /** Run a block under the per-key session lock. */
    private suspend fun <T> withSessionLock(
        key: String,
        @Suppress("UNUSED_PARAMETER")
        reason: String,
        block: suspend () -> T,
    ): T {
        return getSessionMutex(key).withLock {
            block()
        }
    }

    /** Convert this Bitmap to PNG bytes. */
    private fun Bitmap.toPngByteArray(): ByteArray {
        return ByteArrayOutputStream().use { stream ->
            compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
    }

    /** Build Content list for a single message (multimodal first, then text). */
    private fun buildContentList(
        input: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
    ): List<Content> {
        val contents = mutableListOf<Content>()
        for (image in images) contents.add(Content.ImageBytes(image.toPngByteArray()))
        for (audio in audioClips) contents.add(Content.AudioBytes(audio))
        val t = input.trim()
        if (t.isNotEmpty()) contents.add(Content.Text(t))
        return contents
    }

    /**
     * Build a Contents object from a List<Content> with reflection.
     *
     * We avoid compile-time dependency on a specific Contents factory/ctor,
     * because LiteRT-LM SDK has changed APIs across versions.
     */
    private fun buildContentsObject(contents: List<Content>): Contents {
        val cls = Contents::class.java

        runCatching {
            val ctor = cls.constructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 1 && List::class.java.isAssignableFrom(p[0])
            } ?: return@runCatching null
            (ctor.newInstance(contents) as Contents)
        }.getOrNull()?.let { return it }

        runCatching {
            val ctor = cls.constructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 1 && p[0].isArray
            } ?: return@runCatching null
            val arr = contents.toTypedArray()
            (ctor.newInstance(arr) as Contents)
        }.getOrNull()?.let { return it }

        runCatching {
            val m = cls.methods.firstOrNull { m ->
                (m.name == "of" || m.name == "from" || m.name == "create") &&
                        Modifier.isStatic(m.modifiers) &&
                        m.parameterTypes.size == 1 &&
                        (m.parameterTypes[0].isArray || List::class.java.isAssignableFrom(m.parameterTypes[0]))
            } ?: return@runCatching null

            val inst = if (m.parameterTypes[0].isArray) {
                m.invoke(null, contents.toTypedArray())
            } else {
                m.invoke(null, contents)
            }
            (inst as Contents)
        }.getOrNull()?.let { return it }

        runCatching {
            val companionField = cls.getDeclaredField("Companion")
            val companion = companionField.get(null) ?: return@runCatching null
            val m = companion.javaClass.methods.firstOrNull { m ->
                (m.name == "of" || m.name == "from" || m.name == "create") &&
                        m.parameterTypes.size == 1 &&
                        (m.parameterTypes[0].isArray || List::class.java.isAssignableFrom(m.parameterTypes[0]))
            } ?: return@runCatching null

            val inst = if (m.parameterTypes[0].isArray) {
                m.invoke(companion, contents.toTypedArray())
            } else {
                m.invoke(companion, contents)
            }
            (inst as Contents)
        }.getOrNull()?.let { return it }

        throw IllegalStateException("Unable to construct Contents for current LiteRT-LM SDK.")
    }

    /**
     * Extract textual content from a streaming Message chunk.
     *
     * LiteRT-LM's current Kotlin callback contract delivers a NEW message
     * chunk to MessageCallback.onMessage(). Therefore this method intentionally
     * does not attempt snapshot/delta inference. Treating a real delta chunk as
     * an accumulated snapshot can silently drop repeated text when the new
     * chunk happens to match a suffix of previously emitted output.
     *
     * The structured Content.Text path is preferred because it avoids parsing
     * debug/toString representations. A toString() fallback is retained only
     * for defensive compatibility with SDK builds that may return an unusual
     * message wrapper.
     */
    private fun extractChunkText(
        message: Message,
    ): String {
        val structured =
            runCatching {
                message.contents.contents
                    .asSequence()
                    .filterIsInstance<Content.Text>()
                    .joinToString(separator = "") { content ->
                        content.text
                    }
            }.getOrDefault("")

        if (structured.isNotEmpty()) {
            return structured
        }

        return runCatching {
            message.toString()
        }.getOrDefault("")
    }

    /** Normalize tokenizer artifacts into plain text. */
    private fun normalizeDeltaText(s: String): String {
        if (s.isEmpty()) return s
        return s
            .replace('\u00A0', ' ')
            .replace('\uFEFF', ' ')
            .replace('\u2581', ' ')
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
    }

    /** Heuristic default max tokens by model name. */
    private fun defaultMaxTokensForModel(modelName: String): Int {
        val n = modelName.lowercase(Locale.US)
        return if (n.contains("functiongemma") || n.contains("270m") || n.contains("tinygarden")) 1024 else 4096
    }

    /**
     * Best-effort "await initialized" that does NOT use apiMutex (deadlock-safe).
     */
    private suspend fun awaitInitializedInternal(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key = runtimeKey(model)

        val already = stateMutex.withLock { instances.containsKey(key) }
        if (already) return

        val completion =
            CompletableDeferred<String>()

        initialize(
            context = context,
            model = model,
            supportImage = supportImage,
            supportAudio = supportAudio,
            onDone = { error ->
                if (!completion.isCompleted) {
                    completion.complete(error)
                }
            },
            systemMessage = systemMessage,
            tools = tools,
        )

        val error =
            withTimeoutOrNull(
                INIT_AWAIT_TIMEOUT_MS
            ) {
                completion.await()
            } ?: "Initialization timed out after " +
            "${INIT_AWAIT_TIMEOUT_MS}ms."

        if (error.isNotEmpty()) {
            throw IllegalStateException(
                "LiteRT-LM initialization failed: $error"
            )
        }
    }

    /** Snapshot used to recreate a clean Conversation for emulator A/B tests. */
    private data class EmulatorFreshConversationSnapshot(
        val supportImage: Boolean,
        val supportAudio: Boolean,
        val systemMessage: Message?,
        val tools: List<Any>,
    )

    private data class CapabilityUpgradePlan(
        val nextImage: Boolean,
        val nextAudio: Boolean,
        val systemMessage: Message?,
        val tools: List<Any>,
        val detail: String,
    )

    /**
     * Upgrade (reinitialize) runtime capabilities if needed.
     *
     * IMPORTANT:
     * - Preserve systemMessage/tools across capability upgrades unless explicitly provided.
     *   This prevents behavior drift when an app uses tools/system prompts and later needs
     *   to re-init due to multimodal input (image/audio).
     */
    private suspend fun upgradeCapabilitiesIfNeeded(
        context: Context,
        model: Model,
        wantImage: Boolean,
        wantAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        if (!wantImage && !wantAudio) return

        val key = runtimeKey(model)

        val plan: CapabilityUpgradePlan? = stateMutex.withLock {
            val inst = instances[key] ?: return@withLock null
            val needImage = wantImage && !inst.supportImage
            val needAudio = wantAudio && !inst.supportAudio
            if (!needImage && !needAudio) return@withLock null

            val nextImage = inst.supportImage || wantImage
            val nextAudio = inst.supportAudio || wantAudio

            val sys = systemMessage ?: inst.systemMessageSnapshot
            val tl = if (tools.isNotEmpty()) tools else inst.toolsSnapshot

            val detail =
                "needImage=$needImage needAudio=$needAudio have(image=${inst.supportImage},audio=${inst.supportAudio}) " +
                        "preserve(sys=${sys != null},tools=${tl.size})"

            CapabilityUpgradePlan(
                nextImage = nextImage,
                nextAudio = nextAudio,
                systemMessage = sys,
                tools = tl,
                detail = detail,
            )
        }

        if (plan == null) return

        RuntimeLogStore.w(
            TAG,
            "Capability upgrade requested: key='$key' -> image=${plan.nextImage} audio=${plan.nextAudio} (${plan.detail})"
        )

        val completion =
            CompletableDeferred<String>()

        initialize(
            context = context,
            model = model,
            supportImage = plan.nextImage,
            supportAudio = plan.nextAudio,
            onDone = { error ->
                if (!completion.isCompleted) {
                    completion.complete(error)
                }
            },
            systemMessage = plan.systemMessage,
            tools = plan.tools,
        )

        val error =
            withTimeoutOrNull(
                INIT_AWAIT_TIMEOUT_MS
            ) {
                completion.await()
            } ?: "Initialization timed out after " +
            "${INIT_AWAIT_TIMEOUT_MS}ms."

        if (error.isNotEmpty()) {
            throw IllegalStateException(
                "LiteRT-LM capability upgrade failed: $error"
            )
        }
    }

    /**
     * Create a conversation with retry on FAILED_PRECONDITION ("session already exists").
     */
    private suspend fun createConversationWithRetry(
        engine: Engine,
        cfg: ConversationConfig,
        key: String,
        reason: String,
        timeoutMs: Long = SESSION_RECREATE_RETRY_TIMEOUT_MS,
        initialDelayMs: Long = 25L,
        maxDelayMs: Long = 250L,
    ): Conversation {
        val start = SystemClock.elapsedRealtime()
        var delayMs = initialDelayMs
        var attempt = 0

        while (true) {
            attempt++
            try {
                val conv = engine.createConversation(cfg)
                if (attempt > 1) {
                    RuntimeLogStore.w(
                        TAG,
                        "createConversationWithRetry succeeded: key='$key' attempts=$attempt reason='$reason'"
                    )
                }
                return conv
            } catch (t: Throwable) {
                if (!isSessionAlreadyExistsError(t)) throw t

                val now = SystemClock.elapsedRealtime()
                val elapsed = now - start
                if (elapsed >= timeoutMs) {
                    RuntimeLogStore.e(
                        TAG,
                        "createConversationWithRetry timed out: key='$key' attempts=$attempt elapsed=${elapsed}ms reason='$reason' err=${t.message}",
                        t
                    )
                    throw t
                }

                RuntimeLogStore.w(
                    TAG,
                    "createConversationWithRetry: session exists, retrying: key='$key' attempt=$attempt elapsed=${elapsed}ms nextDelay=${delayMs}ms reason='$reason'"
                )
                delay(delayMs)
                delayMs = min(maxDelayMs, delayMs * 2)
            }
        }
    }

    /** Build conversation config for current model sampler + optional system/tools. */
    private fun buildConversationConfig(
        model: Model,
        systemMessage: Message?,
        tools: List<Any>,
    ): ConversationConfig {
        val topK =
            sanitizeTopK(
                model.getIntConfigValue(ConfigKey.TOP_K, DEFAULT_TOPK)
            )

        val topP =
            sanitizeTopP(
                model.getFloatConfigValue(ConfigKey.TOP_P, DEFAULT_TOPP)
            )

        val temperature =
            sanitizeTemperature(
                model.getFloatConfigValue(
                    ConfigKey.TEMPERATURE,
                    DEFAULT_TEMPERATURE
                )
            )

        // Convert generic tool list to LiteRT-LM ToolProvider list.
        val toolProviders = tools.map { tool ->
            require(tool is ToolProvider) {
                "Unsupported LiteRT-LM tool type: ${tool::class.java.name}"
            }
            tool
        }

        return ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = topK,
                topP = topP.toDouble(),
                temperature = temperature.toDouble(),
            ),
            systemInstruction = systemMessage?.contents,
            tools = toolProviders,
        )
    }
    /**
     * Initialize LiteRT-LM Engine + Conversation asynchronously.
     *
     * Performance policy:
     * - Reuse an already-compatible warm Engine instead of recreating it.
     * - If only ConversationConfig changed, recreate only the Conversation.
     * - Reinitialize the Engine only when backend, token capacity, or requested
     *   multimodal capability requires a different native runtime.
     *
     * This distinction is important because Engine.initialize() is the expensive
     * phase for multi-gigabyte GPU models, while Conversation recreation is
     * comparatively lightweight.
     */
    fun initialize(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        onDone: (String) -> Unit,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val appContext =
            context.applicationContext

        val key =
            runtimeKey(model)

        setApplicationContext(appContext)
        markUsed(key)
        cancelScheduledCleanup(key, "initialize")

        /*
         * Acquire owner/joiner state atomically. This is deliberately a single
         * operation so a joiner can never observe a newly-created signal that
         * is disconnected from the current owner.
         */
        val flight =
            acquireInitFlight(key)

        val signal =
            flight.signal

        if (!flight.owner) {
            ioScope.launch {
                val error =
                    withTimeoutOrNull(
                        INIT_AWAIT_TIMEOUT_MS
                    ) {
                        signal.await()
                    } ?: "Initialization timed out after " +
                    "${INIT_AWAIT_TIMEOUT_MS}ms."

                postToMain {
                    onDone(error)
                }
            }

            return
        }

        ioScope.launch {
            val totalInitStartedAt =
                SystemClock.elapsedRealtime()

            var engineToCloseOnFailure: Engine? =
                null

            var completed =
                false

            try {
                withSessionLock(
                    key = key,
                    reason = "initialize",
                ) {
                    val runState =
                        getRunState(key)

                    stateMutex.withLock {
                        if (runState.active.get()) {
                            throw IllegalStateException(
                                "Initialization rejected: active native " +
                                        "stream in progress for key='$key'."
                            )
                        }
                    }

                    val backend =
                        preferredBackend(model)

                    val (maxTokensRaw, maxTokens) =
                        resolvedMaxTokens(model)

                    if (isAndroidEmulator) {
                        val message =
                            "Android Emulator execution policy: " +
                                    "configuredBackend=${normalizedAccelerator(model)} " +
                                    "effectiveBackend=${backend.name} " +
                                    "configuredMaxTokens=$maxTokensRaw " +
                                    "effectiveMaxTokens=$maxTokens"

                        RuntimeLogStore.w(TAG, message)
                        Log.w(TAG, message)
                    }

                    val topK =
                        sanitizeTopK(
                            model.getIntConfigValue(
                                ConfigKey.TOP_K,
                                DEFAULT_TOPK,
                            )
                        )

                    val topP =
                        sanitizeTopP(
                            model.getFloatConfigValue(
                                ConfigKey.TOP_P,
                                DEFAULT_TOPP,
                            )
                        )

                    val temperature =
                        sanitizeTemperature(
                            model.getFloatConfigValue(
                                ConfigKey.TEMPERATURE,
                                DEFAULT_TEMPERATURE,
                            )
                        )

                    val modelPath =
                        model.getPath()

                    val modelFile =
                        File(modelPath)

                    val modelBytes =
                        runCatching {
                            modelFile.length()
                        }.getOrDefault(-1L)

                    val modelMtime =
                        runCatching {
                            modelFile.lastModified()
                        }.getOrDefault(-1L)

                    val desiredConversationConfig =
                        buildConversationConfig(
                            model = model,
                            systemMessage = systemMessage,
                            tools = tools,
                        )

                    /*
                     * Fast warm path.
                     *
                     * Do not close a healthy Engine merely because initialize()
                     * was called again. This prevents repeated model loading and
                     * GPU delegate construction during one survey session.
                     */
                    val existing =
                        stateMutex.withLock {
                            instances[key]
                        }

                    if (
                        existing != null &&
                        engineCanServe(
                            instance = existing,
                            model = model,
                            supportImage = supportImage,
                            supportAudio = supportAudio,
                        )
                    ) {
                        if (
                            existing.conversationConfigSnapshot ==
                            desiredConversationConfig
                        ) {
                            markUsed(key)

                            RuntimeLogStore.d(
                                TAG,
                                "Initialization reused warm Engine + " +
                                        "Conversation: key='$key' " +
                                        "backend=${existing.engineConfigSnapshot.backend} " +
                                        "totalMs=${
                                            SystemClock.elapsedRealtime() -
                                                    totalInitStartedAt
                                        }"
                            )

                            postToMain {
                                onDone("")
                            }

                            completeInitSignal(
                                signal,
                                "",
                            )

                            completed = true
                            return@withSessionLock
                        }

                        /*
                         * The native Engine is compatible, but sampler/system/
                         * tool configuration changed. Recreate only the session.
                         */
                        val conversationStartedAt =
                            SystemClock.elapsedRealtime()

                        RuntimeLogStore.d(
                            TAG,
                            "Warm Engine retained; rebuilding Conversation " +
                                    "only: key='$key'"
                        )

                        runCatching {
                            existing.conversation.close()
                        }.onFailure { error ->
                            RuntimeLogStore.w(
                                TAG,
                                "Failed to close previous Conversation " +
                                        "during warm reconfiguration: " +
                                        "key='$key' err=${error.message}",
                                error,
                            )
                        }

                        val replacement =
                            createConversationWithRetry(
                                engine = existing.engine,
                                cfg = desiredConversationConfig,
                                key = key,
                                reason = "initialize-warm-reconfigure",
                                timeoutMs =
                                    SESSION_RECREATE_RETRY_TIMEOUT_MS +
                                            SESSION_RECREATE_EXTRA_RETRY_MS,
                            )

                        existing.conversation =
                            replacement

                        existing.conversationConfigSnapshot =
                            desiredConversationConfig

                        existing.systemMessageSnapshot =
                            systemMessage

                        existing.toolsSnapshot =
                            tools

                        markUsed(key)

                        RuntimeLogStore.d(
                            TAG,
                            "Warm Conversation reconfiguration completed: " +
                                    "key='$key' took=${
                                        SystemClock.elapsedRealtime() -
                                                conversationStartedAt
                                    }ms totalMs=${
                                        SystemClock.elapsedRealtime() -
                                                totalInitStartedAt
                                    }"
                        )

                        postToMain {
                            onDone("")
                        }

                        completeInitSignal(
                            signal,
                            "",
                        )

                        completed = true
                        return@withSessionLock
                    }

                    /*
                     * An instance exists but cannot satisfy the requested engine
                     * configuration. Retire it before constructing the new one.
                     */
                    val retired =
                        stateMutex.withLock {
                            instances.remove(key)
                        }

                    if (retired != null) {
                        RuntimeLogStore.w(
                            TAG,
                            "initialize: retiring incompatible Engine: " +
                                    "key='$key' " +
                                    "oldBackend=${retired.engineConfigSnapshot.backend} " +
                                    "requestedBackend=$backend " +
                                    "oldMaxTokens=${retired.engineConfigSnapshot.maxNumTokens} " +
                                    "requestedMaxTokens=$maxTokens " +
                                    "oldCaps(image=${retired.supportImage}," +
                                    "audio=${retired.supportAudio}) " +
                                    "requestedCaps(image=$supportImage," +
                                    "audio=$supportAudio)"
                        )

                        runCatching {
                            retired.conversation.close()
                        }.onFailure { error ->
                            RuntimeLogStore.w(
                                TAG,
                                "initialize: failed to close retired " +
                                        "Conversation: ${error.message}",
                                error,
                            )
                        }

                        runCatching {
                            retired.engine.close()
                        }.onFailure { error ->
                            RuntimeLogStore.w(
                                TAG,
                                "initialize: failed to close retired " +
                                        "Engine: ${error.message}",
                                error,
                            )
                        }

                    }

                    RuntimeLogStore.d(
                        TAG,
                        "Initializing LiteRT-LM: " +
                                "model='${model.name}', key='$key'"
                    )

                    RuntimeLogStore.d(
                        TAG,
                        "Capabilities: image=$supportImage " +
                                "audio=$supportAudio"
                    )

                    RuntimeLogStore.d(
                        TAG,
                        "Backend=$backend " +
                                "maxNumTokens=$maxTokens " +
                                "(raw=$maxTokensRaw) " +
                                "topK=$topK topP=$topP " +
                                "temp=$temperature"
                    )

                    RuntimeLogStore.d(
                        TAG,
                        "Model path='$modelPath' " +
                                "sizeBytes=$modelBytes " +
                                "lastModified=$modelMtime"
                    )

                    if (maxTokens > 2_048) {
                        RuntimeLogStore.w(
                            TAG,
                            "Large maxNumTokens=$maxTokens requested. " +
                                    "This increases KV-cache capacity and memory " +
                                    "pressure. For short survey JSON/follow-up " +
                                    "generation, validate whether a smaller " +
                                    "configured value is sufficient."
                        )
                    }

                    /*
                     * Native benchmark collection must be configured before
                     * Engine construction because LiteRT-LM snapshots the
                     * experimental flag when the Engine is created.
                     */
                    val nativeBenchmarkEnabled =
                        configureNativeBenchmarkForDiagnostics()

                    val benchmarkPolicyMessage =
                        "LiteRT-LM native benchmark policy: " +
                                "enabled=$nativeBenchmarkEnabled " +
                                "emulator=$isAndroidEmulator"

                    /*
                     * A disabled benchmark is the expected production policy, not a warning.
                     * Keep warning severity only when diagnostic instrumentation is enabled.
                     */
                    if (nativeBenchmarkEnabled) {
                        RuntimeLogStore.w(TAG, benchmarkPolicyMessage)
                        Log.w(TAG, benchmarkPolicyMessage)
                    } else {
                        RuntimeLogStore.d(TAG, benchmarkPolicyMessage)
                        Log.d(TAG, benchmarkPolicyMessage)
                    }

                    /*
                     * Speculative decoding is intentionally disabled for the
                     * current model package.
                     *
                     * The package does not provide TF_LITE_MTP_DRAFTER. Leaving
                     * the process-global LiteRT-LM flag enabled would cause
                     * Engine construction to fail with NOT_FOUND before the
                     * normal GPU execution path is initialized.
                     */
                    val mtpDisableApplied =
                        disableSpeculativeDecodingBestEffort()

                    RuntimeLogStore.d(
                        TAG,
                        "Speculative decoding / MTP policy: " +
                                "enabled=false " +
                                "runtimeFlagUpdated=$mtpDisableApplied " +
                                "model='${model.name}'"
                    )

                    val fallbackCacheDir =
                        runCatching {
                            appContext.noBackupFilesDir.absolutePath
                        }.getOrNull()
                            ?: runCatching {
                                appContext.filesDir.absolutePath
                            }.getOrNull()
                            ?: runCatching {
                                appContext.cacheDir.absolutePath
                            }.getOrNull()

                    fun resolveCacheDir(
                        forBackend: Backend,
                    ): String? {
                        val stable =
                            stableEngineCacheDir(
                                context = appContext,
                                modelPath = modelPath,
                                backend = forBackend,
                                supportImage = supportImage,
                                supportAudio = supportAudio,
                            )

                        return stable
                            ?: fallbackCacheDir
                    }

                    fun buildConfig(
                        forBackend: Backend,
                        visionBackend: Backend?,
                        audioBackend: Backend?,
                    ): EngineConfig {
                        val cacheDirPath =
                            resolveCacheDir(
                                forBackend
                            )

                        val countBefore =
                            dirFileCount(
                                cacheDirPath
                            )

                        RuntimeLogStore.d(
                            TAG,
                            "Engine cacheDir resolved: " +
                                    "key='$key' backend=$forBackend " +
                                    "path='${cacheDirPath ?: "<null>"}' " +
                                    "filesBefore=${countBefore ?: -1}"
                        )

                        return EngineConfig(
                            modelPath = modelPath,
                            backend = forBackend,
                            visionBackend = visionBackend,
                            audioBackend = audioBackend,
                            maxNumTokens = maxTokens,
                            cacheDir = cacheDirPath,
                        )
                    }

                    val visionPreferred =
                        if (supportImage) {
                            if (isAndroidEmulator) {
                                Backend.CPU()
                            } else {
                                Backend.GPU()
                            }
                        } else {
                            null
                        }

                    val audioPreferred =
                        if (supportAudio) {
                            Backend.CPU()
                        } else {
                            null
                        }

                    var engineConfig =
                        buildConfig(
                            forBackend = backend,
                            visionBackend =
                                visionPreferred,
                            audioBackend =
                                audioPreferred,
                        )

                    val engineInitStartedAt =
                        SystemClock.elapsedRealtime()

                    val engine =
                        runCatching {
                            Engine(
                                engineConfig
                            ).also { candidate ->
                                engineToCloseOnFailure =
                                    candidate

                                candidate.initialize()
                            }
                        }.getOrElse { firstError ->
                            if (backend is Backend.GPU) {
                                RuntimeLogStore.w(
                                    TAG,
                                    "GPU initialization failed; " +
                                            "trying CPU fallback: " +
                                            "${firstError.message}",
                                    firstError,
                                )

                                /*
                                 * Close the failed GPU Engine before allocating
                                 * the CPU fallback. The previous implementation
                                 * overwrote this reference and could retain
                                 * native resources from the failed attempt.
                                 */
                                runCatching {
                                    engineToCloseOnFailure
                                        ?.close()
                                }.onFailure { closeError ->
                                    RuntimeLogStore.w(
                                        TAG,
                                        "Failed to close failed GPU Engine: " +
                                                "${closeError.message}",
                                        closeError,
                                    )
                                }

                                engineToCloseOnFailure =
                                    null

                                val fallbackVision =
                                    if (supportImage) {
                                        Backend.CPU()
                                    } else {
                                        null
                                    }

                                val fallbackAudio =
                                    if (supportAudio) {
                                        Backend.CPU()
                                    } else {
                                        null
                                    }

                                engineConfig =
                                    buildConfig(
                                        forBackend =
                                            Backend.CPU(),
                                        visionBackend =
                                            fallbackVision,
                                        audioBackend =
                                            fallbackAudio,
                                    )

                                Engine(
                                    engineConfig
                                ).also { candidate ->
                                    engineToCloseOnFailure =
                                        candidate

                                    candidate.initialize()
                                }
                            } else {
                                throw firstError
                            }
                        }

                    val engineInitElapsed =
                        SystemClock.elapsedRealtime() -
                                engineInitStartedAt

                    val cacheFilesAfter =
                        dirFileCount(
                            engineConfig.cacheDir
                        )
                    val engineTimingMessage =
                        "Engine.initialize completed: " +
                                "key='$key' " +
                                "backend=${engineConfig.backend} " +
                                "took=${engineInitElapsed}ms " +
                                "cacheFilesAfter=${cacheFilesAfter ?: -1}"

                    RuntimeLogStore.w(TAG, engineTimingMessage)
                    Log.w(TAG, engineTimingMessage)

                    val conversationStartedAt =
                        SystemClock.elapsedRealtime()

                    val conversation =
                        createConversationWithRetry(
                            engine = engine,
                            cfg =
                                desiredConversationConfig,
                            key = key,
                            reason = "initialize",
                            timeoutMs =
                                SESSION_RECREATE_RETRY_TIMEOUT_MS +
                                        SESSION_RECREATE_EXTRA_RETRY_MS,
                        )

                    val conversationElapsed =
                        SystemClock.elapsedRealtime() -
                                conversationStartedAt

                    RuntimeLogStore.d(
                        TAG,
                        "createConversation completed: " +
                                "key='$key' " +
                                "took=${conversationElapsed}ms"
                    )

                    stateMutex.withLock {
                        instances[key] =
                            LiteRtLmInstance(
                                engine = engine,
                                conversation =
                                    conversation,
                                supportImage =
                                    supportImage,
                                supportAudio =
                                    supportAudio,
                                engineConfigSnapshot =
                                    engineConfig,
                                conversationConfigSnapshot =
                                    desiredConversationConfig,
                                systemMessageSnapshot =
                                    systemMessage,
                                toolsSnapshot =
                                    tools,
                            )
                    }

                    /*
                     * Ownership has moved into instances. The failure cleanup
                     * reference must no longer close the healthy engine.
                     */
                    engineToCloseOnFailure =
                        null

                    markUsed(key)

                    RuntimeLogStore.d(
                        TAG,
                        "LiteRT-LM initialization succeeded: " +
                                "model='${model.name}', key='$key' " +
                                "engineMs=$engineInitElapsed " +
                                "conversationMs=$conversationElapsed " +
                                "totalMs=${
                                    SystemClock.elapsedRealtime() -
                                            totalInitStartedAt
                                }"
                    )

                    postToMain {
                        onDone("")
                    }

                    completeInitSignal(
                        signal,
                        "",
                    )

                    completed =
                        true
                }
            } catch (
                error: Exception
            ) {
                val message =
                    if (isMissingMtpDrafterError(error)) {
                        "LiteRT-LM model does not contain TF_LITE_MTP_DRAFTER. " +
                                "Speculative decoding must remain disabled for this model package."
                    } else {
                        cleanError(
                            error.message
                        )
                    }

                RuntimeLogStore.e(
                    TAG,
                    "LiteRT-LM initialization failed: " +
                            "$message totalMs=${
                                SystemClock.elapsedRealtime() -
                                        totalInitStartedAt
                            }",
                    error,
                )

                runCatching {
                    engineToCloseOnFailure
                        ?.close()
                }.onFailure { closeError ->
                    RuntimeLogStore.w(
                        TAG,
                        "Failed to close Engine after " +
                                "initialization failure: " +
                                "${closeError.message}",
                        closeError,
                    )
                }

                postToMain {
                    onDone(message)
                }

                completeInitSignal(
                    signal,
                    message,
                )

                completed =
                    true
            } finally {
                if (!completed) {
                    completeInitSignal(
                        signal,
                        "Initialization aborted unexpectedly.",
                    )
                }

                releaseInitFlight(
                    key = key,
                    signal = signal,
                )
            }
        }
    }

    /**
     * Suspend-style initialization entry point.
     *
     * This method performs an inexpensive compatibility check before entering
     * the serialized API path. If the existing Engine and Conversation already
     * satisfy the request, it returns immediately. Otherwise initialize() is
     * used; initialize() itself now distinguishes between:
     *
     * 1. Full warm reuse.
     * 2. Conversation-only reconfiguration.
     * 3. True Engine reinitialization.
     */
    suspend fun initializeIfNeeded(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val appContext =
            context.applicationContext

        val key =
            runtimeKey(model)

        setApplicationContext(appContext)
        markUsed(key)
        cancelScheduledCleanup(
            key,
            "initializeIfNeeded",
        )

        /*
         * If another owner is initializing this exact runtime, join it before
         * checking compatibility. This prevents observing an instance while it
         * is being retired/replaced.
         */
        awaitInitIfInFlight(
            key = key,
            reason = "initializeIfNeeded-precheck",
        )

        val desiredConversationConfig =
            buildConversationConfig(
                model = model,
                systemMessage = systemMessage,
                tools = tools,
            )

        val ready =
            stateMutex.withLock {
                val instance =
                    instances[key]
                        ?: return@withLock false

                engineCanServe(
                    instance = instance,
                    model = model,
                    supportImage = supportImage,
                    supportAudio = supportAudio,
                ) &&
                        instance.conversationConfigSnapshot ==
                        desiredConversationConfig
            }

        if (ready) {
            RuntimeLogStore.d(
                TAG,
                "initializeIfNeeded: warm runtime already ready: " +
                        "key='$key'"
            )

            return
        }

        apiMutex.withLock {
            /*
             * Re-check after acquiring the API mutex because another coroutine
             * may have completed initialization while this caller was waiting.
             */
            awaitInitIfInFlight(
                key = key,
                reason = "initializeIfNeeded-under-apiMutex",
            )

            val readyAfterLock =
                stateMutex.withLock stateLock@{
                    val instance =
                        instances[key]
                            ?: return@stateLock false

                    engineCanServe(
                        instance = instance,
                        model = model,
                        supportImage = supportImage,
                        supportAudio = supportAudio,
                    ) &&
                            instance.conversationConfigSnapshot ==
                            desiredConversationConfig
                }

            if (readyAfterLock) {
                return@withLock
            }

            val completion =
                CompletableDeferred<String>()

            initialize(
                context = appContext,
                model = model,
                supportImage = supportImage,
                supportAudio = supportAudio,
                onDone = { error ->
                    if (!completion.isCompleted) {
                        completion.complete(error)
                    }
                },
                systemMessage = systemMessage,
                tools = tools,
            )

            val error =
                withTimeoutOrNull(
                    INIT_AWAIT_TIMEOUT_MS
                ) {
                    completion.await()
                } ?: "Initialization timed out after " +
                "${INIT_AWAIT_TIMEOUT_MS}ms."

            if (error.isNotEmpty()) {
                throw IllegalStateException(
                    "LiteRT-LM initialization failed: $error"
                )
            }
        }
    }

    /** Fire native done hook once (safe no-op if already cleared). */
    private fun fireNativeDoneHookOnce(key: String) {
        val rs = getRunState(key)
        val hook = rs.nativeDoneHook.getAndSet(null) ?: return
        runCatching { hook.invoke() }
            .onFailure { t -> RuntimeLogStore.w(TAG, "nativeDoneHook failed: key='$key' err=${t.message}", t) }
    }

    /** Log a compact snapshot of RunState (debug only). */
    private fun debugState(key: String, rs: RunState, prefix: String) {
        if (!DEBUG_STATE) return
        val rid = rs.runId.get()
        val active = rs.active.get()
        val term = rs.terminated.get()
        val logical = rs.logicalDone.get()
        val cancel = rs.cancelRequested.get()
        val pending = rs.pendingCancel.get()
        val lastMsg = rs.lastMessageAtMs.get()
        val lastTerm = rs.lastTerminateAtMs.get()
        RuntimeLogStore.d(
            TAG,
            "state[$prefix] key='$key' runId=$rid active=$active terminated=$term logicalDone=$logical " +
                    "cancel=$cancel pendingCancel=$pending lastMsgAt=$lastMsg lastTermAt=$lastTerm"
        )
    }

    /** Close and remove an instance NOW (best-effort). */
    private suspend fun closeInstanceNowBestEffort(key: String, reason: String) {
        cancelScheduledCleanup(key, "closeNow:$reason")

        runCatching { awaitInitIfInFlight(key, reason = "closeNow:$reason") }
            .onFailure {
                RuntimeLogStore.w(
                    TAG,
                    "closeInstanceNowBestEffort: init wait failed: key='$key' reason='$reason' err=${it.message}"
                )
                return
            }

        withSessionLock(key, reason = "closeNow:$reason") {
            val instance: LiteRtLmInstance? = stateMutex.withLock {
                val rs = getRunState(key)
                if (rs.active.get()) return@withLock null
                if (initInFlight.contains(key)) return@withLock null

                rs.cancelRequested.set(false)
                rs.pendingCancel.set(false)
                rs.logicalTerminator.set(null)
                rs.nativeDoneHook.set(null)
                rs.terminated.set(true)
                rs.logicalDone.set(true)

                pendingAfterStream.remove(key)
                instances.remove(key).also { clearInitSignalIfIdle(key) }
            }

            if (instance == null) {
                RuntimeLogStore.d(
                    TAG,
                    "closeInstanceNowBestEffort: nothing to close (or active/initInFlight): key='$key' reason='$reason'"
                )
                return@withSessionLock
            }

            runCatching { instance.conversation.close() }
                .onFailure {
                    RuntimeLogStore.e(
                        TAG,
                        "Failed to close conversation: key='$key' reason='$reason' err=${it.message}",
                        it
                    )
                }
            runCatching { instance.engine.close() }
                .onFailure {
                    RuntimeLogStore.e(
                        TAG,
                        "Failed to close engine: key='$key' reason='$reason' err=${it.message}",
                        it
                    )
                }

            RuntimeLogStore.d(TAG, "LiteRT-LM closed: key='$key' reason='$reason'")
        }
    }

    private data class IdleClosePlan(
        val instance: LiteRtLmInstance,
        val idleForMs: Long,
        val tokenNow: Long,
        val nowMs: Long,
        val reason: String,
    )

    /** Token + idleness guarded closer for idle cleanup. */
    private suspend fun closeInstanceIfStillIdle(
        key: String,
        requiredIdleMs: Long,
        requiredToken: Long,
        reason: String,
    ) {
        if (initInFlight.contains(key)) {
            RuntimeLogStore.d(TAG, "Idle cleanup skipped (init in flight): key='$key'")
            return
        }

        withSessionLock(key, reason = "idleClose:$reason") {
            val plan: IdleClosePlan? = stateMutex.withLock {
                val rs = getRunState(key)
                val nowInner = SystemClock.elapsedRealtime()
                val idleForInner = nowInner - rs.lastUseAtMs.get()
                val tokenInner = rs.cleanupToken.get()

                if (rs.active.get()) {
                    RuntimeLogStore.d(TAG, "Idle cleanup skipped (active native stream): key='$key'")
                    return@withLock null
                }
                if (initInFlight.contains(key)) {
                    RuntimeLogStore.d(TAG, "Idle cleanup skipped (init in flight): key='$key'")
                    return@withLock null
                }
                if (tokenInner != requiredToken) {
                    RuntimeLogStore.d(
                        TAG,
                        "Idle cleanup skipped (token changed): key='$key' required=$requiredToken now=$tokenInner"
                    )
                    return@withLock null
                }
                if (idleForInner < requiredIdleMs) {
                    RuntimeLogStore.d(
                        TAG,
                        "Idle cleanup skipped (recent use): key='$key' idleFor=${idleForInner}ms < ${requiredIdleMs}ms"
                    )
                    return@withLock null
                }

                rs.cancelRequested.set(false)
                rs.pendingCancel.set(false)
                rs.logicalTerminator.set(null)
                rs.nativeDoneHook.set(null)
                rs.terminated.set(true)
                rs.logicalDone.set(true)

                pendingAfterStream.remove(key)
                val inst = instances.remove(key)
                if (inst == null) {
                    RuntimeLogStore.d(TAG, "Idle cleanup: nothing to close: key='$key'")
                    return@withLock null
                }

                clearInitSignalIfIdle(key)

                IdleClosePlan(
                    instance = inst,
                    idleForMs = idleForInner,
                    tokenNow = tokenInner,
                    nowMs = nowInner,
                    reason = reason,
                )
            }

            if (plan == null) return@withSessionLock

            runCatching { plan.instance.conversation.close() }
                .onFailure {
                    RuntimeLogStore.e(
                        TAG,
                        "Failed to close conversation: key='$key' reason='${plan.reason}' err=${it.message}",
                        it
                    )
                }
            runCatching { plan.instance.engine.close() }
                .onFailure {
                    RuntimeLogStore.e(
                        TAG,
                        "Failed to close engine: key='$key' reason='${plan.reason}' err=${it.message}",
                        it
                    )
                }

            RuntimeLogStore.d(
                TAG,
                "LiteRT-LM closed: key='$key' reason='${plan.reason}' idleFor=${plan.idleForMs}ms token=${plan.tokenNow}"
            )
        }
    }

    /** Request a deferred idle cleanup. */
    fun cleanUp(model: Model, onDone: () -> Unit) {
        val key = runtimeKey(model)

        ioScope.launch {
            runCatching { awaitInitIfInFlight(key, reason = "cleanUp") }
                .onFailure { RuntimeLogStore.w(TAG, "cleanUp: init wait failed: key='$key' err=${it.message}") }

            val action: () -> Unit = {
                scheduleIdleCleanup(key, IDLE_CLEANUP_MS, "explicit-cleanUp")
                postToMain { onDone() }
            }

            val defer = stateMutex.withLock { getRunState(key).active.get() }
            if (defer) {
                stateMutex.withLock { pendingAfterStream.getOrPut(key) { mutableListOf() }.add(action) }
                RuntimeLogStore.w(TAG, "cleanUp deferred (will schedule after native termination): key='$key'")
                return@launch
            }

            action.invoke()
        }
    }

    /**
     * Reset conversation while reusing the existing Engine.
     *
     * NOTE:
     * - Public API is fire-and-forget; internal implementation is suspend to allow safe composition.
     */
    fun resetConversation(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key = runtimeKey(model)

        ioScope.launch {
            markUsed(key)
            cancelScheduledCleanup(key, "resetConversation")

            runCatching { awaitInitIfInFlight(key, reason = "resetConversation") }
                .onFailure {
                    RuntimeLogStore.w(TAG, "resetConversation skipped (init wait failed): key='$key' err=${it.message}")
                    return@launch
                }

            val defer = stateMutex.withLock { getRunState(key).active.get() }
            if (defer) {
                stateMutex.withLock {
                    pendingAfterStream.getOrPut(key) { mutableListOf() }.add {
                        ioScope.launch {
                            runCatching {
                                resetConversationInternal(key, model, supportImage, supportAudio, systemMessage, tools, "resetConversation")
                            }
                        }
                    }
                }
                RuntimeLogStore.w(TAG, "resetConversation deferred (active stream): key='$key'")
                return@launch
            }

            runCatching {
                resetConversationInternal(key, model, supportImage, supportAudio, systemMessage, tools, "resetConversation")
            }.onFailure { RuntimeLogStore.w(TAG, "resetConversation action failed: key='$key' err=${it.message}", it) }
        }
    }

    /**
     * Recreate the Conversation and suspend until the replacement is ready.
     *
     * This is the synchronization-safe variant for repository code that must
     * not release a process-wide inference permit until session repair has
     * actually completed.
     *
     * Unlike [resetConversation], this method does not silently defer behind an
     * active native stream. Callers must invoke it only after the native
     * termination safepoint has been reached.
     */
    suspend fun resetConversationAndWait(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message? = null,
        tools: List<Any> = emptyList(),
    ) {
        val key =
            runtimeKey(model)

        markUsed(key)
        cancelScheduledCleanup(
            key,
            "resetConversationAndWait",
        )

        awaitInitIfInFlight(
            key = key,
            reason = "resetConversationAndWait",
        )

        val active =
            stateMutex.withLock {
                getRunState(key).active.get()
            }

        check(!active) {
            "resetConversationAndWait rejected: " +
                    "native stream is still active for key='$key'."
        }

        resetConversationInternal(
            key = key,
            model = model,
            supportImage = supportImage,
            supportAudio = supportAudio,
            systemMessage = systemMessage,
            tools = tools,
            reason = "resetConversationAndWait",
        )
    }

    /**
     * Recreate only the Conversation while retaining the initialized Engine.
     *
     * This function executes while holding the per-key session mutex. Recovery
     * must therefore never call another helper that reacquires the same mutex:
     * kotlinx.coroutines Mutex is non-reentrant and doing so self-deadlocks.
     */
    private suspend fun resetConversationInternal(
        key: String,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemMessage: Message?,
        tools: List<Any>,
        reason: String,
    ) {
        withSessionLock(
            key = key,
            reason = "resetConversationInternal:$reason",
        ) {
            val (instance, runState) =
                stateMutex.withLock {
                    instances[key] to
                            getRunState(key)
                }

            if (instance == null) {
                RuntimeLogStore.w(
                    TAG,
                    "resetConversationInternal skipped: " +
                            "not initialized key='$key'"
                )

                return@withSessionLock
            }

            if (runState.active.get()) {
                val message =
                    "resetConversationInternal rejected: " +
                            "active stream key='$key'"

                RuntimeLogStore.w(
                    TAG,
                    message,
                )

                throw IllegalStateException(message)
            }

            /*
             * A capability superset is valid. For example, an Engine that was
             * initialized with image support may still create a text-only
             * Conversation without rebuilding the Engine.
             */
            val missingImageCapability =
                supportImage &&
                        !instance.supportImage

            val missingAudioCapability =
                supportAudio &&
                        !instance.supportAudio

            if (
                missingImageCapability ||
                missingAudioCapability
            ) {
                val message =
                    "resetConversationInternal rejected: " +
                            "requested capability is not available: " +
                            "key='$key' " +
                            "have(image=${instance.supportImage}," +
                            "audio=${instance.supportAudio}) " +
                            "want(image=$supportImage," +
                            "audio=$supportAudio)"

                RuntimeLogStore.w(
                    TAG,
                    message,
                )

                throw IllegalStateException(message)
            }

            val config =
                buildConversationConfig(
                    model = model,
                    systemMessage = systemMessage,
                    tools = tools,
                )

            val startedAt =
                SystemClock.elapsedRealtime()

            val closeStartedAt =
                SystemClock.elapsedRealtime()

            val closeFailure =
                runCatching {
                    instance.conversation.close()
                }.exceptionOrNull()

            val closeMs =
                SystemClock.elapsedRealtime() -
                        closeStartedAt

            if (closeFailure != null) {
                RuntimeLogStore.w(
                    TAG,
                    "resetConversationInternal: failed to close " +
                            "old Conversation: key='$key' " +
                            "closeMs=$closeMs err=${closeFailure.message}",
                    closeFailure,
                )
            }

            val createStartedAt =
                SystemClock.elapsedRealtime()

            val freshConversation =
                try {
                    createConversationWithRetry(
                        engine = instance.engine,
                        cfg = config,
                        key = key,
                        reason =
                            "resetConversationInternal:$reason",
                        timeoutMs =
                            SESSION_RECREATE_RETRY_TIMEOUT_MS +
                                    SESSION_RECREATE_EXTRA_RETRY_MS,
                    )
                } catch (error: Throwable) {
                    RuntimeLogStore.e(
                        TAG,
                        "resetConversationInternal failed: " +
                                "key='$key' err=${error.message}",
                        error,
                    )

                    /*
                     * We are already inside this key's session mutex. Remove
                     * and close the broken instance directly instead of calling
                     * closeInstanceNowBestEffort(), which would try to acquire
                     * the same non-reentrant mutex again.
                     */
                    stateMutex.withLock {
                        if (
                            instances[key] ===
                            instance
                        ) {
                            instances.remove(key)
                            clearInitSignalIfIdle(key)
                            pendingAfterStream.remove(key)
                        }

                        runState.active.set(false)
                        runState.terminated.set(true)
                        runState.logicalDone.set(true)
                        runState.cancelRequested.set(false)
                        runState.pendingCancel.set(false)
                        runState.logicalTerminator.set(null)
                        runState.nativeDoneHook.set(null)
                    }

                    runCatching {
                        instance.engine.close()
                    }.onFailure { closeError ->
                        RuntimeLogStore.w(
                            TAG,
                            "resetConversationInternal recovery: " +
                                    "Engine.close failed: key='$key' " +
                                    "err=${closeError.message}",
                            closeError,
                        )
                    }

                    /*
                     * The runtime was removed and the Engine was closed, so the
                     * caller must observe this failure. Returning normally here
                     * would make resetConversationAndWait() report success while
                     * leaving the runtime uninitialized.
                     */
                    val createMs =
                        SystemClock.elapsedRealtime() -
                                createStartedAt

                    val failedTimingMessage =
                        "Conversation reset timing: " +
                                "key='$key' reason='$reason' " +
                                "closeMs=$closeMs createMs=$createMs " +
                                "totalMs=${SystemClock.elapsedRealtime() - startedAt} " +
                                "success=false"

                    RuntimeLogStore.w(TAG, failedTimingMessage)
                    Log.w(TAG, failedTimingMessage)

                    throw error
                }

            val createMs =
                SystemClock.elapsedRealtime() -
                        createStartedAt

            instance.conversation =
                freshConversation

            instance.conversationConfigSnapshot =
                config

            instance.systemMessageSnapshot =
                systemMessage

            instance.toolsSnapshot =
                tools

            markUsed(key)

            val totalMs =
                SystemClock.elapsedRealtime() -
                        startedAt

            val timingMessage =
                "Conversation reset timing: " +
                        "key='$key' reason='$reason' " +
                        "closeMs=$closeMs createMs=$createMs " +
                        "totalMs=$totalMs success=true"

            RuntimeLogStore.w(TAG, timingMessage)
            Log.w(TAG, timingMessage)

            RuntimeLogStore.d(
                TAG,
                "resetConversationInternal completed: " +
                        "key='$key' reason='$reason' " +
                        "took=${totalMs}ms (Engine retained)"
            )
        }
    }

    /**
     * Force teardown and suspend until the runtime is no longer active.
     *
     * This is intended for recovery paths where the caller cannot safely
     * release its own serialization gate while a poisoned or unresponsive
     * Conversation may still exist.
     *
     * If inference is active, cancellation is requested first. The method then
     * waits for normal native termination or the wrapper's hard-close watchdog.
     * Once the stream is inactive, any remaining Engine/Conversation instance
     * is closed synchronously from the caller's perspective.
     */
    suspend fun forceCleanUpAndWait(
        model: Model,
    ) {
        val key =
            runtimeKey(model)

        markUsed(key)
        cancelScheduledCleanup(
            key,
            "forceCleanUpAndWait",
        )

        awaitInitIfInFlight(
            key = key,
            reason = "forceCleanUpAndWait",
        )

        val rs =
            getRunState(key)

        if (rs.active.get()) {
            val expectedRunId = rs.runId.get()

            val terminator =
                rs.logicalTerminator.get()

            if (terminator != null) {
                runCatching {
                    terminator.invoke(expectedRunId)
                }.onFailure { error ->
                    RuntimeLogStore.w(
                        TAG,
                        "forceCleanUpAndWait: logical terminator failed: " +
                                "key='$key' err=${error.message}",
                        error,
                    )
                }
            } else {
                val conversation =
                    stateMutex.withLock {
                        instances[key]?.conversation
                    }

                if (
                    conversation != null &&
                    rs.runId.get() == expectedRunId &&
                    rs.active.get()
                ) {
                    runCatching {
                        conversation.cancelProcess()
                    }.onFailure { error ->
                        RuntimeLogStore.w(
                            TAG,
                            "forceCleanUpAndWait: cancelProcess failed: " +
                                    "key='$key' err=${error.message}",
                            error,
                        )
                    }
                }

                if (HARD_CLOSE_ENABLE && conversation != null) {
                    startHardCloseWatchdog(
                        key = key,
                        expectedRunId = expectedRunId,
                        expectedConversation = conversation,
                        reason = "forceCleanUpAndWait",
                    )
                }
            }

            val becameIdle =
                withTimeoutOrNull(
                    FORCE_CLOSE_WAIT_TIMEOUT_MS
                ) {
                    while (rs.active.get()) {
                        delay(
                            FORCE_CLOSE_WAIT_POLL_MS
                        )
                    }

                    true
                } ?: false

            check(becameIdle) {
                "forceCleanUpAndWait timed out while waiting for " +
                        "native termination: key='$key' timeoutMs=" +
                        FORCE_CLOSE_WAIT_TIMEOUT_MS
            }
        }

        closeInstanceNowBestEffort(
            key = key,
            reason = "forceCleanUpAndWait",
        )
    }

    /**
     * Force immediate teardown (best-effort).
     *
     * Contract:
     * - If a native stream is active, defer until after termination.
     */
    fun forceCleanUp(model: Model, onDone: () -> Unit) {
        val key = runtimeKey(model)

        ioScope.launch {
            markUsed(key)
            cancelScheduledCleanup(key, "forceCleanUp")

            runCatching { awaitInitIfInFlight(key, reason = "forceCleanUp") }
                .onFailure { RuntimeLogStore.w(TAG, "forceCleanUp: init wait failed: key='$key' err=${it.message}") }

            val action: suspend () -> Unit = {
                closeInstanceNowBestEffort(key, reason = "forceCleanUp")
                postToMain { onDone() }
            }

            val defer = stateMutex.withLock { getRunState(key).active.get() }
            if (defer) {
                stateMutex.withLock {
                    pendingAfterStream.getOrPut(key) { mutableListOf() }.add {
                        ioScope.launch { runCatching { action() } }
                    }
                }
                RuntimeLogStore.w(TAG, "forceCleanUp deferred (active stream): key='$key'")
                return@launch
            }

            runCatching { action() }
                .onFailure {
                    RuntimeLogStore.w(TAG, "forceCleanUp failed: key='$key' err=${it.message}", it)
                    postToMain { onDone() }
                }
        }
    }

    /** Acquire watchdog ownership for a run. Caller must already hold sessionMutex. */
    private suspend fun armHardCloseWatchdogLocked(
        key: String,
        expectedRunId: Long,
        expectedConversation: Conversation,
        reason: String,
    ) {
        val rs = getRunState(key)
        if (rs.runId.get() != expectedRunId || !rs.active.get()) return

        val currentConversation =
            stateMutex.withLock {
                instances[key]?.conversation
            }

        if (currentConversation !== expectedConversation) return

        while (true) {
            if (rs.runId.get() != expectedRunId || !rs.active.get()) return

            val owner = rs.hardCloseRunId.get()
            if (owner == expectedRunId) return

            if (rs.hardCloseRunId.compareAndSet(owner, expectedRunId)) break
        }

        ioScope.launch {
            try {
                val start = SystemClock.elapsedRealtime()
                RuntimeLogStore.w(
                    TAG,
                    "Hard-close watchdog started: key='$key' reason='$reason' timeout=${HARD_CLOSE_TIMEOUT_MS}ms"
                )

                val testTrigger = runControlTestHooks?.awaitHardCloseAction
                if (testTrigger != null) {
                    testTrigger(expectedRunId)
                } else {
                    while (true) {
                        delay(HARD_CLOSE_POLL_MS)

                        if (
                            rs.hardCloseRunId.get() != expectedRunId ||
                            rs.runId.get() != expectedRunId ||
                            !rs.active.get() ||
                            rs.terminated.get()
                        ) {
                            RuntimeLogStore.d(TAG, "Hard-close watchdog exit: key='$key' runId=$expectedRunId no longer active")
                            return@launch
                        }

                        val now = SystemClock.elapsedRealtime()
                        val elapsed = now - start
                        val sinceMsg = now - rs.lastMessageAtMs.get()

                        if (sinceMsg in 0..2_000L && elapsed < HARD_CLOSE_TIMEOUT_MS) continue
                        if (elapsed >= HARD_CLOSE_TIMEOUT_MS) break
                    }
                }

                if (
                    rs.hardCloseRunId.get() != expectedRunId ||
                    rs.runId.get() != expectedRunId ||
                    !rs.active.get()
                ) return@launch

                val elapsed = SystemClock.elapsedRealtime() - start
                val sinceMsg = SystemClock.elapsedRealtime() - rs.lastMessageAtMs.get()
                RuntimeLogStore.e(TAG, "Hard-close watchdog firing: key='$key' runId=$expectedRunId elapsed=${elapsed}ms sinceMsg=${sinceMsg}ms")
                debugState(key, rs, "hardClose:firing")

                var deferredActions: List<() -> Unit> = emptyList()

                withSessionLock(key, reason = "hardClose:$reason") {
                    if (
                        rs.hardCloseRunId.get() != expectedRunId ||
                        rs.runId.get() != expectedRunId ||
                        !rs.active.get()
                    ) return@withSessionLock

                    val inst = stateMutex.withLock { instances[key] }
                    if (inst == null || inst.conversation !== expectedConversation) {
                        return@withSessionLock
                    }

                    if (!claimTerminalRun(rs, expectedRunId)) {
                        return@withSessionLock
                    }

                    rs.terminated.set(true)

                    val removedExpectedInstance =
                        stateMutex.withLock {
                            if (instances[key] !== inst) {
                                false
                            } else {
                                deferredActions =
                                    pendingAfterStream.remove(key)?.toList() ?: emptyList()

                                clearInitSignalIfIdle(key)
                                instances.remove(key)
                                true
                            }
                        }

                    if (!removedExpectedInstance) return@withSessionLock

                    runCatching {
                        inst.conversation.close()
                    }.onFailure {
                        RuntimeLogStore.e(
                            TAG,
                            "Hard-close: conversation.close failed: key='$key' err=${it.message}",
                            it,
                        )
                    }

                    runCatching {
                        inst.engine.close()
                    }.onFailure {
                        RuntimeLogStore.e(
                            TAG,
                            "Hard-close: engine.close failed: key='$key' err=${it.message}",
                            it,
                        )
                    }

                    rs.lastTerminateAtMs.set(SystemClock.elapsedRealtime())
                    rs.active.set(false)
                    rs.logicalDone.set(true)
                    rs.logicalTerminator.set(null)

                    fireNativeDoneHookOnce(key)
                }

                deferredActions.forEach { action ->
                    runCatching { action.invoke() }
                        .onFailure {
                            RuntimeLogStore.w(
                                TAG,
                                "Hard-close deferred action failed: key='$key' err=${it.message}",
                                it,
                            )
                        }
                }

                RuntimeLogStore.e(TAG, "Hard-close completed: key='$key' runId=$expectedRunId")
                debugState(key, rs, "hardClose:done")
            } finally {
                rs.hardCloseRunId.compareAndSet(expectedRunId, 0L)
            }
        }
    }

    /** Acquire session ownership before arming a hard-close watchdog. */
    private fun startHardCloseWatchdog(
        key: String,
        expectedRunId: Long,
        expectedConversation: Conversation,
        reason: String,
    ) {
        ioScope.launch {
            withSessionLock(key, reason = "armHardClose:$reason") {
                armHardCloseWatchdogLocked(
                    key = key,
                    expectedRunId = expectedRunId,
                    expectedConversation = expectedConversation,
                    reason = reason,
                )
            }
        }
    }

    /**
     * Low-level callback-based streaming API.
     *
     * Contract:
     * - resultListener(..., done=true) is "logical completion" (UI completion).
     * - cleanUpListener() is invoked ONLY after native termination (onDone/onError),
     *   or after hard-close watchdog if enabled.
     */
    fun runInference(
        model: Model,
        input: String,
        resultListener: (partialResult: String, done: Boolean) -> Unit,
        cleanUpListener: () -> Unit,
        onError: (message: String) -> Unit = {},
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        notifyCancelToOnError: Boolean = false,
        maxOutputTokens: Int = MAX_OUTPUT_TOKENS_PER_REQUEST,
        onRunStarted: (Long) -> Unit = {},
    ) {
        val key = runtimeKey(model)
        val effectiveMaxOutputTokens = sanitizeMaxOutputTokens(maxOutputTokens)

        ioScope.launch {
            val requestStartedAtMs =
                SystemClock.elapsedRealtime()

            markUsed(key)
            cancelScheduledCleanup(key, "runInference")

            runCatching { awaitInitIfInFlight(key, reason = "runInference") }
                .onFailure { t ->
                    val msg = "LiteRT-LM cannot start inference while initialization is in progress: ${cleanError(t.message)}"
                    RuntimeLogStore.e(TAG, msg, t)
                    postToMain {
                        onError(msg)
                        resultListener("", true)
                        runCatching { cleanUpListener.invoke() }
                    }
                    return@launch
                }

            val needAutoInit = stateMutex.withLock { instances[key] == null }
            if (needAutoInit) {
                val ctx = appContextRef.get()
                if (ctx == null) {
                    val msg =
                        "LiteRT-LM model '${model.name}' is not initialized, and no application context is set. " +
                                "Call setApplicationContext() or initializeIfNeeded() first."
                    RuntimeLogStore.e(TAG, msg)
                    postToMain {
                        onError(msg)
                        resultListener("", true)
                        runCatching { cleanUpListener.invoke() }
                    }
                    return@launch
                }

                val reqImage = images.isNotEmpty()
                val reqAudio = audioClips.isNotEmpty()
                runCatching {
                    awaitInitializedInternal(
                        context = ctx,
                        model = model,
                        supportImage = reqImage,
                        supportAudio = reqAudio,
                    )
                }.onFailure { t ->
                    val msg = "LiteRT-LM auto-init failed: ${cleanError(t.message)}"
                    RuntimeLogStore.e(TAG, msg, t)
                    postToMain {
                        onError(msg)
                        resultListener("", true)
                        runCatching { cleanUpListener.invoke() }
                    }
                    return@launch
                }
            }

            val wantImage = images.isNotEmpty()
            val wantAudio = audioClips.isNotEmpty()
            if (wantImage || wantAudio) {
                val ctx = appContextRef.get()
                if (ctx != null) {
                    runCatching {
                        upgradeCapabilitiesIfNeeded(
                            context = ctx,
                            model = model,
                            wantImage = wantImage,
                            wantAudio = wantAudio,
                        )
                    }.onFailure { t ->
                        val msg = "LiteRT-LM capability upgrade failed: ${cleanError(t.message)}"
                        RuntimeLogStore.e(TAG, msg, t)
                        postToMain {
                            onError(msg)
                            resultListener("", true)
                            runCatching { cleanUpListener.invoke() }
                        }
                        return@launch
                    }
                }
            }

            val trimmed = input.trim()
            val hasText = trimmed.isNotEmpty()
            val hasMm = images.isNotEmpty() || audioClips.isNotEmpty()

            if (!hasText && !hasMm) {
                val msg = "LiteRT-LM input rejected: empty message (no text/images/audio)."
                RuntimeLogStore.w(TAG, msg)
                postToMain {
                    onError(msg)
                    resultListener("", true)
                    runCatching { cleanUpListener.invoke() }
                }
                return@launch
            }

            /*
             * Emulator-only prompt-length sweep. Each text-only request advances to
             * the next synthetic length so TTFT can be plotted against prefill size
             * without changing Engine, sampler, callback, or multimodal behavior.
             */
            val prefillSweepSequence =
                if (
                    isAndroidEmulator &&
                    EMULATOR_PREFILL_SWEEP_AB_TEST &&
                    !hasMm
                ) {
                    emulatorPrefillSweepSequence.getAndIncrement()
                } else {
                    -1L
                }

            val prefillSweepTargetLength =
                if (prefillSweepSequence >= 0L) {
                    EMULATOR_PREFILL_SWEEP_LENGTHS[
                        (prefillSweepSequence % EMULATOR_PREFILL_SWEEP_LENGTHS.size).toInt()
                    ]
                } else {
                    null
                }

            val effectiveText =
                if (prefillSweepTargetLength != null) {
                    buildEmulatorPrefillSweepPrompt(prefillSweepTargetLength)
                } else {
                    trimmed
                }

            if (prefillSweepTargetLength != null) {
                val benchmarkMessage =
                    "Emulator A/B prefill sweep: " +
                            "key='$key' enabled=true " +
                            "sequence=$prefillSweepSequence " +
                            "targetTextLen=$prefillSweepTargetLength " +
                            "originalTextLen=${trimmed.length} " +
                            "effectiveTextLen=${effectiveText.length}"

                RuntimeLogStore.w(TAG, benchmarkMessage)
                Log.w(TAG, benchmarkMessage)
            }

            if (
                isAndroidEmulator &&
                EMULATOR_NATIVE_BENCHMARK_LOGGING &&
                !EMULATOR_PREFILL_SWEEP_AB_TEST &&
                !hasMm
            ) {
                /*
                 * Real-prompt benchmark marker. Do not log prompt contents here;
                 * only the actual application input length is needed to correlate
                 * the native token-count benchmark with the Survey request.
                 */
                val realPromptBenchmarkMessage =
                    "Emulator A/B real prompt benchmark: " +
                            "key='$key' enabled=true " +
                            "textLen=${effectiveText.length}"

                RuntimeLogStore.w(TAG, realPromptBenchmarkMessage)
                Log.w(TAG, realPromptBenchmarkMessage)
            }

            /*
             * Emulator-only A/B diagnostic. Recreate only the Conversation while
             * retaining the already initialized Engine. The replacement preserves
             * the same system instruction, tools, and engine capabilities, but it
             * intentionally drops accumulated conversation history.
             *
             * This block executes before inferenceStartedAtMs is captured, so the
             * existing TTFT metric remains sendMessageAsync -> first output. The
             * reset cost is logged separately and requestToFirstOutputMs includes
             * the complete wrapper-visible latency.
             */
            if (
                isAndroidEmulator &&
                EMULATOR_FRESH_CONVERSATION_PER_INFERENCE_AB_TEST
            ) {
                val snapshot =
                    stateMutex.withLock {
                        instances[key]?.let { instance ->
                            EmulatorFreshConversationSnapshot(
                                supportImage = instance.supportImage,
                                supportAudio = instance.supportAudio,
                                systemMessage = instance.systemMessageSnapshot,
                                tools = instance.toolsSnapshot.toList(),
                            )
                        }
                    }

                if (snapshot == null) {
                    val msg =
                        "Emulator A/B fresh Conversation failed: " +
                                "runtime missing for key='$key'."

                    RuntimeLogStore.e(TAG, msg)
                    Log.e(TAG, msg)
                    postToMain {
                        onError(msg)
                        resultListener("", true)
                        runCatching { cleanUpListener.invoke() }
                    }
                    return@launch
                }

                val resetStartedAtMs =
                    SystemClock.elapsedRealtime()

                val resetError =
                    runCatching {
                        resetConversationInternal(
                            key = key,
                            model = model,
                            supportImage = snapshot.supportImage,
                            supportAudio = snapshot.supportAudio,
                            systemMessage = snapshot.systemMessage,
                            tools = snapshot.tools,
                            reason = "emulator-fresh-conversation-ab",
                        )
                    }.exceptionOrNull()

                if (resetError != null) {
                    val msg =
                        "Emulator A/B fresh Conversation failed: " +
                                "key='$key' err=${cleanError(resetError.message)}"

                    RuntimeLogStore.e(TAG, msg, resetError)
                    Log.e(TAG, msg)
                    postToMain {
                        onError(msg)
                        resultListener("", true)
                        runCatching { cleanUpListener.invoke() }
                    }
                    return@launch
                }

                val resetMs =
                    SystemClock.elapsedRealtime() -
                            resetStartedAtMs

                val abMessage =
                    "Emulator A/B fresh Conversation: " +
                            "key='$key' enabled=true " +
                            "resetMs=$resetMs " +
                            "originalTextLen=${trimmed.length} " +
                            "effectiveTextLen=${effectiveText.length} " +
                            "systemInstruction=${snapshot.systemMessage != null} " +
                            "tools=${snapshot.tools.size}"

                RuntimeLogStore.w(TAG, abMessage)
                Log.w(TAG, abMessage)
            }

            var rsLocal: RunState? = null
            var myRunId = 0L
            var conversation: Conversation? = null
            var rejectMsg: String? = null

            withSessionLock(key, reason = "runInference-start") {
                stateMutex.withLock {
                    val i = instances[key]
                    if (i == null) {
                        rejectMsg = "LiteRT-LM model '${model.name}' is not initialized. Call initializeIfNeeded() first."
                        return@withLock
                    }

                    if (images.isNotEmpty() && !i.supportImage) {
                        rejectMsg = "Vision input rejected: supportImage=false for key='$key'. Reinitialize with supportImage=true."
                        return@withLock
                    }
                    if (audioClips.isNotEmpty() && !i.supportAudio) {
                        rejectMsg = "Audio input rejected: supportAudio=false for key='$key'. Reinitialize with supportAudio=true."
                        return@withLock
                    }

                    val rs = getRunState(key)

                    val acquired = rs.active.compareAndSet(false, true)
                    if (!acquired) {
                        rejectMsg = "LiteRT-LM runInference rejected: another native stream is already active for key='$key'."
                        return@withLock
                    }

                    myRunId = rs.runId.incrementAndGet()
                    rs.terminated.set(false)
                    rs.logicalDone.set(false)
                    rs.lastMessageAtMs.set(SystemClock.elapsedRealtime())

                    /*
                     * Cancellation is scoped to an already-active run. Clear any
                     * legacy/stale pending state before a new run starts so a
                     * late cancel from the previous request cannot abort this
                     * request.
                     */
                    rs.pendingCancel.set(false)
                    rs.cancelRequested.set(false)

                    rsLocal = rs
                    conversation = i.conversation
                }

                val rs = rsLocal
                var conv = conversation
                val reject = rejectMsg
                if (reject != null || rs == null || conv == null) return@withSessionLock

                if (DEBUG_STATE) debugState(key, rs, "run:start")

                RuntimeLogStore.d(
                    TAG,
                    "runInference start: key='$key' runId=$myRunId " +
                            "hasText=$hasText originalTextLen=${trimmed.length} effectiveTextLen=${effectiveText.length} " +
                            "images=${images.size} audioClips=${audioClips.size}"
                )

                val callbackLock = Any()
                var emittedChars = 0L
                var msgCount = 0

                /*
                 * Inference timing probes:
                 * - TTFT measures sendMessageAsync -> first visible output.
                 * - totalMs measures sendMessageAsync -> native termination.
                 *
                 * These numbers make prefill latency distinguishable from
                 * steady-state decode latency without exposing prompt content.
                 */
                val inferenceStartedAtMs =
                    SystemClock.elapsedRealtime()

                val firstOutputAtMs =
                    AtomicLong(0L)

                val nativeStarted =
                    AtomicBoolean(false)

                /*
                 * Timestamp captured immediately after sendMessageAsync() returns.
                 * This separates synchronous Kotlin/JNI dispatch cost from the
                 * post-dispatch wait until the first streaming callback.
                 */
                val sendAcceptedAtMs =
                    AtomicLong(0L)

                suspend fun runDeferredActions() {
                    val deferred: List<() -> Unit> = stateMutex.withLock {
                        pendingAfterStream.remove(key)?.toList() ?: emptyList()
                    }
                    deferred.forEach { act ->
                        runCatching { act.invoke() }
                            .onFailure { t -> RuntimeLogStore.w(TAG, "Deferred action failed for key='$key': ${t.message}", t) }
                    }
                }

                fun scheduleDeferredActions() {
                    ioScope.launch { runDeferredActions() }
                }

                fun scheduleCleanUpListener() {
                    postToMain {
                        runCatching { cleanUpListener.invoke() }
                            .onFailure { t -> RuntimeLogStore.w(TAG, "cleanUpListener failed: ${t.message}", t) }
                    }
                }

                rs.nativeDoneHook.set hook@{
                    if (rs.runId.get() != myRunId) return@hook
                    scheduleCleanUpListener()
                    scheduleDeferredActions()
                }

                fun cancelProcessBestEffort(
                    expectedRunId: Long,
                    expectedConversation: Conversation,
                    stage: String,
                ) {
                    ioScope.launch cancelDispatch@{
                        if (
                            rs.runId.get() != expectedRunId ||
                            !rs.active.get()
                        ) {
                            RuntimeLogStore.d(
                                TAG,
                                "cancelProcess skipped: stale run key='$key' runId=$expectedRunId stage='$stage'",
                            )
                            return@cancelDispatch
                        }

                        val identityMatches =
                            stateMutex.withLock {
                                instances[key]?.conversation === expectedConversation
                            }

                        if (
                            !identityMatches ||
                            rs.runId.get() != expectedRunId ||
                            !rs.active.get()
                        ) return@cancelDispatch

                        runCatching { expectedConversation.cancelProcess() }
                            .onFailure { t ->
                                RuntimeLogStore.w(TAG, "cancelProcess() failed: key='$key' stage='$stage' err=${t.message}", t)
                            }
                    }
                }

                var watchdog: Job? = null

                fun deliverLogicalDoneOnce(errorMessage: String? = null, isCancel: Boolean = false) {
                    if (!rs.logicalDone.compareAndSet(false, true)) return
                    if (DEBUG_STATE) debugState(key, rs, "logicalDone")

                    postToMain {
                        val cancelled = isCancel || rs.cancelRequested.get()
                        if (cancelled) {
                            if (notifyCancelToOnError && !errorMessage.isNullOrBlank()) {
                                onError(errorMessage)
                            }
                        } else if (!errorMessage.isNullOrBlank()) {
                            onError(errorMessage)
                        }
                        resultListener("", true)
                    }
                }

                fun markNativeDoneOnce(
                    errorMessage: String? = null,
                    isCancel: Boolean = false,
                ) {
                    if (!claimTerminalRun(rs, myRunId)) return

                    rs.terminated.set(true)

                    watchdog?.cancel()
                    watchdog = null

                    val now =
                        SystemClock.elapsedRealtime()

                    rs.lastTerminateAtMs.set(now)

                    rs.active.set(false)
                    rs.logicalTerminator.set(null)

                    val firstOutput =
                        firstOutputAtMs.get()

                    val ttftMs =
                        if (firstOutput > 0L) {
                            firstOutput -
                                    inferenceStartedAtMs
                        } else {
                            -1L
                        }

                    val acceptedAt =
                        sendAcceptedAtMs.get()

                    val dispatchMs =
                        if (acceptedAt > 0L) {
                            acceptedAt -
                                    inferenceStartedAtMs
                        } else {
                            -1L
                        }

                    val postDispatchTtftMs =
                        if (
                            firstOutput > 0L &&
                            acceptedAt > 0L
                        ) {
                            firstOutput - acceptedAt
                        } else {
                            -1L
                        }

                    val (
                        messageCountSnapshot,
                        emittedCharsSnapshot,
                    ) =
                        synchronized(
                            callbackLock
                        ) {
                            msgCount to
                                    emittedChars
                        }

                    val inferenceTimingMessage =
                        "Inference timing: key='$key' " +
                                "runId=$myRunId " +
                                "ttftMs=$ttftMs " +
                                "dispatchMs=$dispatchMs " +
                                "postDispatchTtftMs=$postDispatchTtftMs " +
                                "totalMs=${
                                    now -
                                            inferenceStartedAtMs
                                } " +
                                "callbacks=$messageCountSnapshot " +
                                "outputChars=$emittedCharsSnapshot " +
                                "cancelled=$isCancel " +
                                "error=${!errorMessage.isNullOrBlank()} " +
                                "requestTotalMs=${now - requestStartedAtMs}"

                    RuntimeLogStore.w(TAG, inferenceTimingMessage)
                    Log.w(TAG, inferenceTimingMessage)

                    if (
                        EMULATOR_NATIVE_BENCHMARK_LOGGING &&
                        isAndroidEmulator &&
                        !isCancel &&
                        errorMessage.isNullOrBlank()
                    ) {
                        /*
                         * Query the native benchmark only after LiteRT-LM has
                         * reported stream completion. BenchmarkInfo exposes the
                         * actual token counts and native prefill/decode rates,
                         * which are more useful than Java/Kotlin character count
                         * when comparing prompts with different tokenization.
                         */
                        val benchmarkResult =
                            runCatching {
                                buildNativeBenchmarkMessage(
                                    conversation = checkNotNull(conv) {
                                        "Conversation became unavailable before benchmark collection."
                                    },
                                    key = key,
                                    runId = myRunId,
                                    effectiveTextLength = effectiveText.length,
                                )
                            }

                        benchmarkResult
                            .onSuccess { benchmarkMessage ->
                                RuntimeLogStore.w(TAG, benchmarkMessage)
                                Log.w(TAG, benchmarkMessage)
                            }
                            .onFailure { benchmarkError ->
                                val benchmarkErrorMessage =
                                    "LiteRT-LM native benchmark unavailable: " +
                                            "key='$key' runId=$myRunId " +
                                            "err=${cleanError(benchmarkError.message)}"

                                RuntimeLogStore.w(
                                    TAG,
                                    benchmarkErrorMessage,
                                    benchmarkError,
                                )
                                Log.w(
                                    TAG,
                                    benchmarkErrorMessage,
                                    benchmarkError,
                                )
                            }
                    }

                    deliverLogicalDoneOnce(
                        errorMessage =
                            errorMessage,
                        isCancel =
                            isCancel,
                    )

                    fireNativeDoneHookOnce(key)

                    if (DEBUG_STATE) {
                        debugState(
                            key,
                            rs,
                            "nativeDone",
                        )
                    }
                }

                suspend fun requestLogicalCancelLocked(
                    expectedRunId: Long,
                    reason: String,
                ) {
                    if (
                        rs.runId.get() != expectedRunId ||
                        !rs.active.get()
                    ) return

                    val expectedConversation = conv ?: return

                    rs.cancelRequested.set(true)
                    rs.pendingCancel.set(false)
                    deliverLogicalDoneOnce(errorMessage = reason, isCancel = true)

                    cancelProcessBestEffort(
                        expectedRunId = expectedRunId,
                        expectedConversation = expectedConversation,
                        stage = "logicalCancel",
                    )

                    if (HARD_CLOSE_ENABLE) {
                        armHardCloseWatchdogLocked(
                            key = key,
                            expectedRunId = expectedRunId,
                            expectedConversation = expectedConversation,
                            reason = "logicalCancel",
                        )
                    }
                }

                fun requestLogicalCancel(
                    expectedRunId: Long,
                    reason: String,
                ) {
                    ioScope.launch {
                        withSessionLock(
                            key = key,
                            reason = "logicalCancel:$reason",
                        ) {
                            requestLogicalCancelLocked(
                                expectedRunId = expectedRunId,
                                reason = reason,
                            )
                        }
                    }
                }

                rs.logicalTerminator.set { expectedRunId ->
                    requestLogicalCancel(
                        expectedRunId = expectedRunId,
                        reason = "Cancelled",
                    )
                }

                if (
                    runControlTestHooks
                        ?.armHardCloseOnRunStart
                        ?.invoke(myRunId) == true
                ) {
                    armHardCloseWatchdogLocked(
                        key = key,
                        expectedRunId = myRunId,
                        expectedConversation = conv,
                        reason = "test-run-start",
                    )
                }

                onRunStarted(myRunId)

                if (rs.cancelRequested.get()) {
                    RuntimeLogStore.d(TAG, "LiteRT-LM start cancelled before sendMessageAsync: key='$key'")
                    markNativeDoneOnce(errorMessage = "Cancelled", isCancel = true)
                    return@withSessionLock
                }

                watchdog = ioScope.launch streamWatchdog@{
                    delay(STREAM_WATCHDOG_MS)
                    if (rs.runId.get() != myRunId) return@streamWatchdog
                    if (rs.terminated.get()) return@streamWatchdog

                    RuntimeLogStore.e(TAG, "Stream watchdog fired: key='$key' runId=$myRunId timeout=${STREAM_WATCHDOG_MS}ms")
                    debugState(key, rs, "watchdog:fired")

                    requestLogicalCancel(
                        expectedRunId = myRunId,
                        reason = "Timeout: inference did not complete in ${STREAM_WATCHDOG_MS}ms",
                    )
                    if (!nativeStarted.get()) markNativeDoneOnce("Timeout before native start")
                }

                val callback = object : MessageCallback {

                    override fun onMessage(message: Message) {
                        if (rs.runId.get() != myRunId) return
                        if (rs.terminated.get()) return
                        if (rs.logicalDone.get() || rs.cancelRequested.get()) return

                        val now = SystemClock.elapsedRealtime()
                        rs.lastMessageAtMs.set(now)

                        /*
                         * LiteRT-LM MessageCallback.onMessage() delivers a new
                         * message chunk. Do not run snapshot/delta heuristics
                         * here: repeated real chunks such as "a", "a", "a"
                         * must remain visible so the repository-level loop
                         * detector can stop pathological generation correctly.
                         */
                        val deltaRaw =
                            extractChunkText(message)

                        if (deltaRaw.isEmpty()) {
                            return
                        }

                        val delta =
                            normalizeDeltaText(
                                deltaRaw
                            )

                        val emittedCharsNow =
                            synchronized(
                                callbackLock
                            ) {
                                msgCount++
                                emittedChars +=
                                    delta.length.toLong()
                                emittedChars
                            }

                        if (
                            delta.isNotEmpty() &&
                            firstOutputAtMs.compareAndSet(
                                0L,
                                now,
                            )
                        ) {
                            val acceptedAt =
                                sendAcceptedAtMs.get()

                            val dispatchMs =
                                if (acceptedAt > 0L) {
                                    acceptedAt - inferenceStartedAtMs
                                } else {
                                    -1L
                                }

                            val postDispatchTtftMs =
                                if (acceptedAt > 0L) {
                                    now - acceptedAt
                                } else {
                                    -1L
                                }

                            val firstOutputMessage =
                                "Inference first output: " +
                                        "key='$key' runId=$myRunId " +
                                        "ttftMs=${now - inferenceStartedAtMs} " +
                                        "dispatchMs=$dispatchMs " +
                                        "postDispatchTtftMs=$postDispatchTtftMs " +
                                        "requestToFirstOutputMs=${now - requestStartedAtMs}"

                            RuntimeLogStore.w(TAG, firstOutputMessage)
                            Log.w(TAG, firstOutputMessage)
                        }

                        if (DEBUG_STREAM) {
                            val c: Int
                            synchronized(callbackLock) { c = msgCount }
                            if (c == 1 || c % DEBUG_STREAM_EVERY_N == 0) {
                                /*
                                 * Never log generated text, even in debug builds.
                                 * Runtime logs can be persisted and uploaded as
                                 * diagnostics, so only non-content metadata is safe.
                                 */
                                RuntimeLogStore.d(
                                    TAG,
                                    "stream[key=$key runId=$myRunId] msg#$c " +
                                            "rawChunkLen=${deltaRaw.length} " +
                                            "deltaLen=${delta.length} " +
                                            "emittedChars=$emittedCharsNow"
                                )
                            }
                        }

                        postToMain { resultListener(delta, false) }
                    }

                    override fun onDone() {
                        if (rs.runId.get() != myRunId) return
                        markNativeDoneOnce(null)
                    }

                    override fun onError(throwable: Throwable) {
                        if (rs.runId.get() != myRunId) return

                        val rawMsg = throwable.message ?: throwable.toString()
                        val msg = cleanError(rawMsg)
                        val code = extractStatusCodeBestEffort(throwable)

                        if (DEBUG_ERROR_THROWABLE) {
                            val cls = throwable::class.java.name
                            val codeStr = code?.toString() ?: "n/a"
                            RuntimeLogStore.e(
                                TAG,
                                "LiteRT-LM onError(Throwable): key='$key' runId=$myRunId type=$cls code=$codeStr msg='$msg'\n" +
                                        shortStack(throwable),
                                throwable
                            )
                        }

                        val cancelled = rs.cancelRequested.get() || isCancellationThrowable(throwable, msg)
                        if (cancelled) {
                            RuntimeLogStore.d(TAG, "LiteRT-LM inference cancelled: key='$key' runId=$myRunId")
                            markNativeDoneOnce(errorMessage = "Cancelled", isCancel = true)
                            return
                        }

                        val decorated = if (code != null) "Error($code): $msg" else "Error: $msg"
                        RuntimeLogStore.e(TAG, "LiteRT-LM inference error: key='$key' runId=$myRunId $decorated")
                        markNativeDoneOnce(decorated)
                    }
                }

                try {
                    if (!hasMm) {
                        /*
                         * Do not log prompt content. Diagnostics may outlive the
                         * process and can be uploaded by the host application.
                         */
                        RuntimeLogStore.d(
                            TAG,
                            "LiteRT-LM sendMessageAsync(text): key='$key' runId=$myRunId len=${effectiveText.length} maxOutputTokens=$effectiveMaxOutputTokens"
                        )
                        conv.sendMessageAsync(
                            effectiveText,
                            callback,
                            maxOutputToken = effectiveMaxOutputTokens,
                        )
                    } else {
                        RuntimeLogStore.d(
                            TAG,
                            "LiteRT-LM sendMessageAsync(mm): key='$key' runId=$myRunId textLen=${effectiveText.length} images=${images.size} audio=${audioClips.size} maxOutputTokens=$effectiveMaxOutputTokens"
                        )
                        val contentList = buildContentList(input = effectiveText, images = images, audioClips = audioClips)
                        val contentsObj = buildContentsObject(contentList)
                        conv.sendMessageAsync(
                            contentsObj,
                            callback,
                            maxOutputToken = effectiveMaxOutputTokens,
                        )
                    }
                    val acceptedAt =
                        SystemClock.elapsedRealtime()

                    sendAcceptedAtMs.set(acceptedAt)
                    nativeStarted.set(true)

                    val dispatchTimingMessage =
                        "sendMessageAsync accepted: " +
                                "key='$key' runId=$myRunId " +
                                "dispatchMs=${acceptedAt - inferenceStartedAtMs}"

                    RuntimeLogStore.d(TAG, dispatchTimingMessage)

                    if (
                        isAndroidEmulator &&
                        EMULATOR_NATIVE_BENCHMARK_LOGGING
                    ) {
                        Log.w(TAG, dispatchTimingMessage)
                    }
                } catch (e: Exception) {
                    val recoverable = isConversationNotAliveError(e)
                    RuntimeLogStore.e(TAG, "LiteRT-LM sendMessageAsync failed: key='$key' msg=${e.message}", e)

                    if (recoverable) {
                        RuntimeLogStore.w(TAG, "Recovering from not-alive conversation: key='$key' runId=$myRunId")

                        stateMutex.withLock {
                            rs.active.set(false)
                            rs.logicalTerminator.set(null)
                        }

                        val recoveryResult = runCatching {
                            val i2 =
                                stateMutex.withLock { instances[key] }
                                    ?: throw IllegalStateException(
                                        "Recovery failed: runtime missing for key='$key'."
                                    )

                            val cfg = i2.conversationConfigSnapshot
                            runCatching { i2.conversation.close() }
                            val fresh = createConversationWithRetry(
                                engine = i2.engine,
                                cfg = cfg,
                                key = key,
                                reason = "runInference-recover",
                                timeoutMs = SESSION_RECREATE_RETRY_TIMEOUT_MS + SESSION_RECREATE_EXTRA_RETRY_MS
                            )
                            i2.conversation = fresh
                            conv = fresh
                            conversation = fresh
                        }

                        if (recoveryResult.isSuccess) {
                            val reacquired = stateMutex.withLock {
                                val acquired2 = rs.active.compareAndSet(false, true)
                                if (acquired2) {
                                    rs.logicalTerminator.set { expectedRunId ->
                                        requestLogicalCancel(
                                            expectedRunId = expectedRunId,
                                            reason = "Cancelled",
                                        )
                                    }
                                }
                                acquired2
                            }

                            if (reacquired) {
                                runCatching {
                                    if (!hasMm) {
                                        conv!!.sendMessageAsync(
                                            effectiveText,
                                            callback,
                                            maxOutputToken = effectiveMaxOutputTokens,
                                        )
                                    } else {
                                        val contentList = buildContentList(input = effectiveText, images = images, audioClips = audioClips)
                                        val contentsObj = buildContentsObject(contentList)
                                        conv!!.sendMessageAsync(
                                            contentsObj,
                                            callback,
                                            maxOutputToken = effectiveMaxOutputTokens,
                                        )
                                    }
                                }.onSuccess {
                                    sendAcceptedAtMs.set(
                                        SystemClock.elapsedRealtime()
                                    )
                                    RuntimeLogStore.w(TAG, "Recovery retry succeeded: key='$key' runId=$myRunId")
                                    nativeStarted.set(true)
                                }.onFailure { e2 ->
                                    RuntimeLogStore.e(TAG, "Recovery retry failed: key='$key' runId=$myRunId err=${e2.message}", e2)
                                    markNativeDoneOnce(cleanError(e2.message))
                                }
                            } else {
                                markNativeDoneOnce("Recovery failed: could not reacquire active stream")
                            }
                        } else {
                            val recoveryError =
                                recoveryResult.exceptionOrNull()

                            RuntimeLogStore.e(
                                TAG,
                                "Recovery failed: key='$key' runId=$myRunId " +
                                        "err=${cleanError(recoveryError?.message)}",
                                recoveryError,
                            )

                            markNativeDoneOnce(
                                "Recovery failed: ${cleanError(recoveryError?.message)}"
                            )
                        }
                    } else {
                        markNativeDoneOnce(cleanError(e.message))
                    }
                }
            }

            val reject = rejectMsg
            if (reject != null) {
                RuntimeLogStore.w(TAG, reject)
                postToMain {
                    onError(reject)
                    resultListener("", true)
                    runCatching { cleanUpListener.invoke() }
                }
            }
        }
    }

    /**
     * High-level suspend API:
     * - Serializes calls via apiMutex.
     * - Uses runInference internally and returns full aggregated text.
     */
    suspend fun generateText(
        model: Model,
        input: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        onPartial: (String) -> Unit = {},
        maxOutputTokens: Int = MAX_OUTPUT_TOKENS_PER_REQUEST,
    ): String = apiMutex.withLock {
        val key = runtimeKey(model)

        markUsed(key)
        cancelScheduledCleanup(key, "generateText")

        if (!busy.compareAndSet(false, true)) {
            throw IllegalStateException("LiteRT-LM is already busy with another request.")
        }

        try {
            val buffer = StringBuilder()
            val doneSignal = CompletableDeferred<String>()

            runInference(
                model = model,
                input = input,
                images = images,
                audioClips = audioClips,
                resultListener = { partial, done ->
                    if (partial.isNotEmpty()) {
                        buffer.append(partial)
                        runCatching { onPartial(partial) }
                            .onFailure { t -> RuntimeLogStore.w(TAG, "onPartial failed: ${t.message}", t) }
                    }
                    if (done && !doneSignal.isCompleted) {
                        doneSignal.complete(buffer.toString())
                    }
                },
                cleanUpListener = { /* no-op */ },
                onError = { message ->
                    if (!doneSignal.isCompleted) {
                        if (message.equals("Cancelled", ignoreCase = true)) {
                            doneSignal.completeExceptionally(CancellationException("Cancelled"))
                        } else {
                            doneSignal.completeExceptionally(
                                IllegalStateException("LiteRT-LM generation error: $message")
                            )
                        }
                    }
                },
                notifyCancelToOnError = true,
                maxOutputTokens = maxOutputTokens,
            )

            try {
                doneSignal.await()
            } catch (e: CancellationException) {
                RuntimeLogStore.d(TAG, "generateText cancelled: key='$key'")
                cancel(model)
                throw e
            }
        } finally {
            busy.set(false)
        }
    }

    /**
     * Best-effort cancellation of the currently active native stream.
     *
     * Important:
     * - Cancellation is intentionally a no-op when no stream is active.
     * - A late cancel must never poison the next request. LiteRT-LM documents
     *   cancelProcess() as leaving the current Conversation unusable, so the
     *   cancellation state belongs only to the run that was actually active
     *   when cancellation was requested.
     */
    fun cancel(
        model: Model,
        expectedRunId: Long,
    ) {
        val key =
            runtimeKey(model)

        ioScope.launch {
            runControlTestHooks
                ?.beforeScopedCancelValidation
                ?.invoke(expectedRunId)

            val rs =
                getRunState(key)

            if (
                rs.runId.get() != expectedRunId ||
                !rs.active.get()
            ) {
                RuntimeLogStore.d(
                    TAG,
                    "cancel ignored: stale/inactive run for key='$key' expectedRunId=$expectedRunId",
                )

                if (DEBUG_STATE) {
                    debugState(
                        key,
                        rs,
                        "cancel:idle",
                    )
                }

                return@launch
            }

            val terminator =
                rs.logicalTerminator.get()

            if (terminator != null) {
                runCatching {
                    terminator.invoke(expectedRunId)
                }.onFailure { error ->
                    RuntimeLogStore.w(
                        TAG,
                        "logical terminator failed in cancel(): " +
                                "key='$key' err=${error.message}",
                        error,
                    )

                    if (HARD_CLOSE_ENABLE) {
                        val conversation =
                            stateMutex.withLock {
                                instances[key]?.conversation
                            }

                        if (conversation != null) {
                            startHardCloseWatchdog(
                                key = key,
                                expectedRunId = expectedRunId,
                                expectedConversation = conversation,
                                reason = "cancel()-terminator-failed",
                            )
                        }
                    }
                }
            } else {
                RuntimeLogStore.d(
                    TAG,
                    "cancel ignored: terminator unavailable for key='$key' expectedRunId=$expectedRunId",
                )
            }

            if (DEBUG_STATE) {
                debugState(
                    key,
                    rs,
                    "cancel",
                )
            }
        }
    }

    /** Best-effort compatibility API scoped to the run active at invocation time. */
    fun cancel(model: Model) {
        val key = runtimeKey(model)
        val rs = getRunState(key)

        val before = rs.runId.get()
        if (!rs.active.get()) return
        val after = rs.runId.get()

        if (before != after || !rs.active.get()) return

        cancel(
            model = model,
            expectedRunId = after,
        )
    }
}
