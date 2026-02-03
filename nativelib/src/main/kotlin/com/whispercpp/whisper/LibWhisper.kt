// file: com/whispercpp/whisper/WhisperContext.kt
// ============================================================
// ✅ WhisperContext — JNI-safe, Coroutine-isolated, Debug-stable
// ------------------------------------------------------------
// • Serializes all JNI/whisper.cpp calls onto a dedicated single thread
// • Runtime ABI + CPU feature detection to choose an optimized .so
// • Clear lifecycle: init → transcribe → release → dispatcher close
// • Re-entrancy guard to avoid overlapping whisper_full() calls
// • Rich logging for diagnosability and production forensics
// • Works with three model sources: File / Asset / InputStream
// ============================================================

package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

private const val LOG_TAG = "WhisperJNI"

/**
 * Kotlin wrapper for whisper.cpp JNI bindings.
 *
 * Design constraints:
 * - whisper.cpp/ggml is effectively single-threaded at the context level,
 *   thus all JNI calls are serialized on a dedicated background thread.
 * - The native context pointer (ptr) is owned by this instance only.
 * - The dispatcher’s thread is created once and closed on release().
 *
 * Cancellation notes:
 * - `whisper_full()` is a blocking native call. Suspending callers can be
 *   cancelled, but the native computation continues until completion. If you
 *   need early aborts, wire a native-side abort hook (not included here).
 *
 * Threading:
 * - All JNI calls (init/transcribe/free/bench) run on the same single thread.
 * - This avoids subtle data races in ggml working buffers and allocator.
 */
class WhisperContext private constructor(
    private var ptr: Long
) {

    // ------------------------------------------------------------
    // Dedicated single-thread dispatcher + scope
    // ------------------------------------------------------------
    /**
     * We retain a dedicated single thread for the entire lifetime of this context
     * to ensure strict serialization of all JNI calls and to keep thread-local
     * state on the native side stable (env attach, allocators, caches, etc.).
     */
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "WhisperThread").apply {
                // Keep the thread non-blocking for app shutdown.
                isDaemon = true
                // Leave at normal priority; CPU-bound workloads scale via thread count instead.
                priority = Thread.NORM_PRIORITY
            }
        }.asCoroutineDispatcher()

    /** Supervisor scope so failures in one job do not cancel siblings. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Re-entrancy guard to prevent overlapping transcriptions on the same ctx. */
    private val busy = AtomicBoolean(false)

    // ------------------------------------------------------------
    // Transcription API
    // ------------------------------------------------------------

    /**
     * Runs full synchronous transcription (native) on normalized PCM data.
     *
     * Contract:
     * - Executes on the dedicated JNI thread (never the caller thread).
     * - Not re-entrant: throws if a previous call is still running.
     * - Returns concatenated segments; optionally appends timestamps.
     *
     * @param data Float PCM samples normalized to [-1.0, 1.0]
     * @param lang Language code ("en", "ja", "sw") or "auto" for auto-detect
     * @param translate If true, runs translation-to-English mode
     * @param printTimestamp Append per-segment [t0 - t1] after each line
     */
    suspend fun transcribeData(
        data: FloatArray,
        lang: String,
        translate: Boolean,
        printTimestamp: Boolean = true
    ): String = withContext(scope.coroutineContext) {
        check(ptr != 0L) { "WhisperContext: already released (ptr == 0)" }
        // Enforce single active whisper_full() per context.
        if (!busy.compareAndSet(false, true)) {
            throw IllegalStateException("Transcription already in progress on this WhisperContext")
        }
        try {
            val numThreads = WhisperCpuConfig.preferredThreadCount
            Log.i(LOG_TAG, "Transcribe start: threads=$numThreads lang=$lang translate=$translate, samples=${data.size}")

            // JNI → whisper_full()
            WhisperLib.fullTranscribe(ptr, lang, numThreads, translate, data)

            // Gather decoded segments
            val n = WhisperLib.getTextSegmentCount(ptr)
            buildString(capacity = n * 32) {
                for (i in 0 until n) {
                    append(WhisperLib.getTextSegment(ptr, i))
                    if (printTimestamp) {
                        val t0 = WhisperLib.getTextSegmentT0(ptr, i)
                        val t1 = WhisperLib.getTextSegmentT1(ptr, i)
                        append(" [${toTimestamp(t0)} - ${toTimestamp(t1)}]\n")
                    } else {
                        append('\n')
                    }
                }
            }.also {
                Log.i(LOG_TAG, "Transcribe complete: segments=$n chars=${it.length}")
            }
        } finally {
            busy.set(false)
        }
    }

    // ------------------------------------------------------------
    // Benchmarks / Diagnostics (optional)
    // ------------------------------------------------------------

    /**
     * Returns memcpy throughput diagnostic (if compiled with WHISPER_BENCH).
     * NOTE: if not built with bench enabled, returns a static message.
     */
    suspend fun benchMemory(nThreads: Int): String =
        withContext(scope.coroutineContext) { WhisperLib.benchMemcpy(nThreads) }

    /**
     * Returns ggml matmul throughput diagnostic (if compiled with WHISPER_BENCH).
     * NOTE: if not built with bench enabled, returns a static message.
     */
    suspend fun benchGgmlMulMat(nThreads: Int): String =
        withContext(scope.coroutineContext) { WhisperLib.benchGgmlMulMat(nThreads) }

    // ------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------

    /**
     * Explicitly frees the native whisper context and closes the dispatcher.
     * Safe to call multiple times. After this returns, the instance is unusable.
     *
     * Implementation detail:
     * - Free runs on the JNI thread for symmetry with all other calls.
     * - After native free, we cancel the scope and close the dispatcher to
     *   tear down the backing Executor thread. Subsequent calls are no-ops.
     */
    suspend fun release() {
        // 1) Free native on the JNI thread
        withContext(scope.coroutineContext) {
            if (ptr != 0L) {
                runCatching { WhisperLib.freeContext(ptr) }
                    .onSuccess { Log.d(LOG_TAG, "Released native context (ptr=$ptr)") }
                    .onFailure { e -> Log.e(LOG_TAG, "Error releasing native context", e) }
                ptr = 0L
            } else {
                Log.w(LOG_TAG, "Release called on an already freed context")
            }
        }
        // 2) Cancel coroutines and close dispatcher (outside of dispatcher context)
        scope.cancel()
        // Closing the dispatcher shuts down the Executor thread to avoid thread leaks.
        (dispatcher).close()
    }

    /**
     * GC fallback: ensures native memory is not leaked if release() wasn’t called.
     * Note that finalize() is best-effort and may never run; release() is preferred.
     */
    @Suppress("deprecation")
    protected fun finalize() {
        try {
            if (ptr != 0L) {
                Log.w(LOG_TAG, "finalize(): native context still alive; forcing release()")
                runBlocking { release() }
            }
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "finalize() cleanup failed", t)
        }
    }

    // ============================================================
    // Companion — creation entry points
    // ============================================================

    companion object {

        /**
         * Create a context from a filesystem path (fastest; mmap-capable).
         */
        fun createContextFromFile(filePath: String): WhisperContext {
            require(filePath.isNotBlank()) { "filePath must not be blank" }
            val ptr = WhisperLib.initContext(filePath)
            require(ptr != 0L) { "Failed to create context from file: $filePath" }
            Log.i(LOG_TAG, "WhisperContext created from file: $filePath")
            return WhisperContext(ptr)
        }

        /**
         * Create a context by streaming model bytes from an InputStream.
         * The stream may be network-backed or decrypted-on-the-fly.
         */
        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            val ptr = WhisperLib.initContextFromInputStream(stream)
            require(ptr != 0L) { "Failed to create context from InputStream" }
            Log.i(LOG_TAG, "WhisperContext created from InputStream")
            return WhisperContext(ptr)
        }

        /**
         * Create a context from an Android asset (AAsset streaming; low-RAM).
         */
        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            require(assetPath.isNotBlank()) { "assetPath must not be blank" }
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)
            require(ptr != 0L) { "Failed to create context from asset: $assetPath" }
            Log.i(LOG_TAG, "WhisperContext created from asset: $assetPath")
            return WhisperContext(ptr)
        }

        /** Returns GGML/whisper system info string from native. */
        fun getSystemInfo(): String = WhisperLib.getSystemInfo()
    }
}

// ============================================================
// JNI Bridge — WhisperLib
// ------------------------------------------------------------
// Loads an appropriate native .so and exposes JNI entry points.
// The JNI signatures must match the C code exactly.
// ============================================================
private class WhisperLib {
    companion object {
        init {
            // Try to load an optimized ABI-specific binary first, then fall back.
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            val feats = cpuInfo().orEmpty()

            fun tryLoad(name: String): Boolean = try {
                System.loadLibrary(name); true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(LOG_TAG, "Unable to load lib$name.so on $abi: ${e.message}")
                false
            }

            val loaded =
                // ARMv8.2-A + FP16 (half-precision SIMD) → asimdhp/fphp/fp16
                (isArm64(abi) && hasFp16(feats) && tryLoad("whisper_v8fp16_va")) ||
                        // ARMv7-A + VFPv4
                        (isArmv7(abi) && hasVfpv4(feats) && tryLoad("whisper_vfpv4")) ||
                        // Generic fallback (portable CPU path)
                        tryLoad("whisper")

            if (loaded) {
                Log.i(LOG_TAG, "Native whisper library loaded (ABI=$abi, fp16=${hasFp16(feats)}, vfpv4=${hasVfpv4(feats)})")
            } else {
                error("Failed to load any native whisper library")
            }
        }

        // -------- JNI method declarations (must match C signatures) --------
        @JvmStatic external fun initContext(modelPath: String): Long
        @JvmStatic external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        @JvmStatic external fun initContextFromInputStream(inputStream: InputStream): Long
        @JvmStatic external fun freeContext(contextPtr: Long)
        @JvmStatic external fun fullTranscribe(contextPtr: Long, lang: String, numThreads: Int, translate: Boolean, audioData: FloatArray)
        @JvmStatic external fun getTextSegmentCount(contextPtr: Long): Int
        @JvmStatic external fun getTextSegment(contextPtr: Long, index: Int): String
        @JvmStatic external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        @JvmStatic external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        @JvmStatic external fun getSystemInfo(): String
        @JvmStatic external fun benchMemcpy(nthread: Int): String
        @JvmStatic external fun benchGgmlMulMat(nthread: Int): String

        // -------- Runtime feature detection helpers --------

        /** Returns raw /proc/cpuinfo contents (if available). */
        private fun cpuInfo(): String? = try {
            File("/proc/cpuinfo").inputStream().bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not read /proc/cpuinfo", e); null
        }

        /** True if ABI indicates 64-bit ARM. */
        private fun isArm64(abi: String): Boolean = abi.equals("arm64-v8a", ignoreCase = true)

        /** True if ABI indicates 32-bit ARMv7. */
        private fun isArmv7(abi: String): Boolean = abi.equals("armeabi-v7a", ignoreCase = true)

        /**
         * Detects FP16 (half-precision) SIMD support for arm64.
         * Common tokens: "asimdhp", "fphp", "fp16".
         */
        private fun hasFp16(info: String): Boolean {
            val s = info.lowercase()
            return "asimdhp" in s || "fphp" in s || "fp16" in s
        }

        /**
         * Detects VFPv4 on armv7. Common tokens: "vfpv4", "neon", "asimd".
         * We require at least VFPv4 for the optimized v7 binary.
         */
        private fun hasVfpv4(info: String): Boolean {
            val s = info.lowercase()
            return "vfpv4" in s
        }
    }
}

// ============================================================
// Utility functions (pure Kotlin)
// ============================================================

/**
 * Converts whisper segment ticks (10 ms per unit) to "hh:mm:ss.mmm".
 * Note: whisper_t0/t1 are typically in 10 ms units; this function
 * multiplies by 10 to convert to milliseconds.
 */
private fun toTimestamp(t: Long, comma: Boolean = false): String {
    var msec = t * 10
    val hr = msec / (1000 * 60 * 60)
    msec -= hr * (1000 * 60 * 60)
    val min = msec / (1000 * 60)
    msec -= min * (1000 * 60)
    val sec = msec / 1000
    msec -= sec * 1000
    val delim = if (comma) "," else "."
    return String.format("%02d:%02d:%02d%s%03d", hr, min, sec, delim, msec)
}

