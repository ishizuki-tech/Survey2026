/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: WhisperContext.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val LOG_TAG = "WhisperJNI"

/**
 * Kotlin wrapper for whisper.cpp JNI bindings.
 *
 * Design:
 * - Each WhisperContext owns exactly one native whisper_context pointer.
 * - All context operations after construction are serialized on one dedicated thread.
 * - Only one transcription may be requested at a time.
 * - release() blocks new work immediately, waits for already-dispatched native work,
 *   frees the native context, then closes the dispatcher.
 *
 * Cancellation:
 * - whisper_full() is a blocking native call.
 * - Cancelling the calling coroutine does not interrupt an already-running
 *   whisper_full(). Native work continues until whisper_full() returns.
 * - release() uses NonCancellable cleanup so native memory is still released even
 *   if the caller is cancelled while release() is running.
 *
 * Initialization:
 * - createContextFromFile(), createContextFromAsset(), and
 *   createContextFromInputStream() are synchronous factory methods.
 * - Do not call them on the Android main thread when loading a large model.
 *
 * Threading:
 * - transcribe/free/bench/result access are confined to the dedicated JNI thread.
 * - whisper.cpp may still use multiple native worker threads internally according
 *   to WhisperCpuConfig.preferredThreadCount.
 */
class WhisperContext private constructor(
    private var ptr: Long
) {

    /**
     * Dedicated executor for this native context.
     *
     * A single-thread executor gives us deterministic ordering between
     * transcription, result reads, benchmarks, and release().
     */
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "WhisperThread").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }.asCoroutineDispatcher()

    /**
     * True while a transcription request owns this context.
     *
     * This guard is acquired before dispatching to WhisperThread, so a second
     * caller is rejected immediately instead of silently queueing another
     * whisper_full() operation.
     */
    private val busy = AtomicBoolean(false)

    /**
     * Becomes true as soon as release() starts.
     *
     * Once true, no new native operation is accepted.
     */
    private val closing = AtomicBoolean(false)

    // ------------------------------------------------------------
    // Transcription
    // ------------------------------------------------------------

    /**
     * Runs a full synchronous whisper.cpp transcription.
     *
     * @param data Float PCM samples normalized to [-1.0, 1.0]
     * @param lang Language code such as "en", "ja", "sw", or "auto"
     * @param translate If true, requests translation to English
     * @param printTimestamp If true, appends segment timestamps
     *
     * @throws IllegalStateException if this context is closing/released or
     *         another transcription request is already active
     * @throws IllegalArgumentException if the PCM buffer is empty
     */
    suspend fun transcribeData(
        data: FloatArray,
        lang: String,
        translate: Boolean,
        printTimestamp: Boolean = true
    ): String {
        require(data.isNotEmpty()) {
            "WhisperContext: audio data must not be empty"
        }

        check(!closing.get()) {
            "WhisperContext: context is closing or already released"
        }

        if (!busy.compareAndSet(false, true)) {
            throw IllegalStateException(
                "WhisperContext: transcription already in progress"
            )
        }

        val language = lang.trim().ifEmpty { "auto" }

        try {
            return withContext(dispatcher) {
                checkNativeContext()

                val numThreads = WhisperCpuConfig.preferredThreadCount.coerceAtLeast(1)

                Log.i(
                    LOG_TAG,
                    "Transcribe start: threads=$numThreads " +
                            "lang=$language translate=$translate samples=${data.size}"
                )

                // JNI -> whisper_full()
                val status = WhisperLib.fullTranscribe(
                    ptr,
                    language,
                    numThreads,
                    translate,
                    data
                )
                if (status != 0) {
                    throw IllegalStateException(
                        "WhisperContext: native transcription failed with status $status"
                    )
                }

                // Read all segments before allowing another native operation.
                val segmentCount = WhisperLib.getTextSegmentCount(ptr)

                val result = buildString(capacity = segmentCount * 48) {
                    for (index in 0 until segmentCount) {
                        append(WhisperLib.getTextSegment(ptr, index))

                        if (printTimestamp) {
                            val t0 = WhisperLib.getTextSegmentT0(ptr, index)
                            val t1 = WhisperLib.getTextSegmentT1(ptr, index)

                            append(" [")
                            append(toTimestamp(t0))
                            append(" - ")
                            append(toTimestamp(t1))
                            append("]\n")
                        } else {
                            append('\n')
                        }
                    }
                }

                Log.i(
                    LOG_TAG,
                    "Transcribe complete: segments=$segmentCount chars=${result.length}"
                )

                result
            }
        } finally {
            busy.set(false)
        }
    }

    // ------------------------------------------------------------
    // Benchmarks / diagnostics
    // ------------------------------------------------------------

    /**
     * Returns whisper.cpp memcpy benchmark information.
     */
    suspend fun benchMemory(nThreads: Int): String {
        check(!closing.get()) {
            "WhisperContext: context is closing or already released"
        }

        return withContext(dispatcher) {
            checkNativeContext()
            WhisperLib.benchMemcpy(nThreads.coerceAtLeast(1))
        }
    }

    /**
     * Returns GGML matrix multiplication benchmark information.
     */
    suspend fun benchGgmlMulMat(nThreads: Int): String {
        check(!closing.get()) {
            "WhisperContext: context is closing or already released"
        }

        return withContext(dispatcher) {
            checkNativeContext()
            WhisperLib.benchGgmlMulMat(nThreads.coerceAtLeast(1))
        }
    }

    // ------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------

    /**
     * Frees the native whisper_context and permanently closes this instance.
     *
     * Properties:
     * - Safe to call multiple times.
     * - Only the first caller performs cleanup.
     * - New work is rejected immediately once cleanup begins.
     * - Already-running native work is serialized ahead of the free operation.
     * - Cleanup is NonCancellable to avoid leaking native memory.
     */
    suspend fun release() {
        if (!closing.compareAndSet(false, true)) {
            Log.d(LOG_TAG, "release(): context already closing or released")
            return
        }

        try {
            withContext(NonCancellable + dispatcher) {
                val nativePtr = ptr

                if (nativePtr == 0L) {
                    Log.d(LOG_TAG, "release(): native context already cleared")
                    return@withContext
                }

                try {
                    WhisperLib.freeContext(nativePtr)
                    Log.d(LOG_TAG, "Released native context (ptr=$nativePtr)")
                } catch (t: Throwable) {
                    Log.e(
                        LOG_TAG,
                        "Native context release failed (ptr=$nativePtr)",
                        t
                    )
                } finally {
                    // Never expose a freed or uncertain native pointer again.
                    ptr = 0L
                }
            }
        } finally {
            dispatcher.close()
        }
    }

    /**
     * Must only be called from the dedicated dispatcher.
     */
    private fun checkNativeContext() {
        check(!closing.get()) {
            "WhisperContext: context is closing or already released"
        }
        check(ptr != 0L) {
            "WhisperContext: native context pointer is null"
        }
    }

    // ============================================================
    // Companion — synchronous creation entry points
    // ============================================================

    companion object {

        /**
         * Creates a context from a filesystem model path.
         *
         * This call is synchronous. For large models, invoke it from a
         * background coroutine/thread rather than the Android main thread.
         */
        fun createContextFromFile(filePath: String): WhisperContext {
            require(filePath.isNotBlank()) {
                "filePath must not be blank"
            }

            val ctxPtr = WhisperLib.initContext(filePath)

            check(ctxPtr != 0L) {
                "Failed to create WhisperContext from file: $filePath"
            }

            Log.i(
                LOG_TAG,
                "WhisperContext created from file: $filePath"
            )

            return WhisperContext(ctxPtr)
        }

        /**
         * Creates a context from an InputStream.
         *
         * The native loader consumes the stream synchronously during this call.
         * This method does not close the Java InputStream; ownership remains with
         * the caller.
         */
        fun createContextFromInputStream(
            stream: InputStream
        ): WhisperContext {
            val ctxPtr = WhisperLib.initContextFromInputStream(stream)

            check(ctxPtr != 0L) {
                "Failed to create WhisperContext from InputStream"
            }

            Log.i(
                LOG_TAG,
                "WhisperContext created from InputStream"
            )

            return WhisperContext(ctxPtr)
        }

        /**
         * Creates a context from an Android asset.
         *
         * This call is synchronous. For large models, invoke it from a
         * background coroutine/thread rather than the Android main thread.
         */
        fun createContextFromAsset(
            assetManager: AssetManager,
            assetPath: String
        ): WhisperContext {
            require(assetPath.isNotBlank()) {
                "assetPath must not be blank"
            }

            val ctxPtr = WhisperLib.initContextFromAsset(
                assetManager,
                assetPath
            )

            check(ctxPtr != 0L) {
                "Failed to create WhisperContext from asset: $assetPath"
            }

            Log.i(
                LOG_TAG,
                "WhisperContext created from asset: $assetPath"
            )

            return WhisperContext(ctxPtr)
        }

        /**
         * Returns the native whisper.cpp / GGML system information string.
         */
        fun getSystemInfo(): String = WhisperLib.getSystemInfo()
    }
}

// ============================================================
// JNI bridge — WhisperLib
// ------------------------------------------------------------
// JNI method names and signatures must match WhisperLib.c exactly.
// ============================================================

private class WhisperLib {
    companion object {

        init {
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            val cpuFeatures = cpuInfo().orEmpty()

            val fp16 = isArm64(abi) && hasFp16(cpuFeatures)
            val vfpv4 = isArmv7(abi) && hasVfpv4(cpuFeatures)

            fun tryLoad(name: String): Boolean {
                return try {
                    System.loadLibrary(name)
                    true
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(
                        LOG_TAG,
                        "Unable to load lib$name.so on ABI=$abi: ${e.message}"
                    )
                    false
                }
            }

            val loadedLibrary = when {
                fp16 && tryLoad("whisper_v8fp16_va") ->
                    "whisper_v8fp16_va"

                vfpv4 && tryLoad("whisper_vfpv4") ->
                    "whisper_vfpv4"

                tryLoad("whisper") ->
                    "whisper"

                else ->
                    null
            }

            if (loadedLibrary == null) {
                throw UnsatisfiedLinkError(
                    "Failed to load a compatible whisper native library for ABI=$abi"
                )
            }

            Log.i(
                LOG_TAG,
                "Loaded lib$loadedLibrary.so " +
                        "(ABI=$abi fp16=$fp16 vfpv4=$vfpv4)"
            )
        }

        // --------------------------------------------------------
        // JNI declarations
        // --------------------------------------------------------

        @JvmStatic
        external fun initContext(modelPath: String): Long

        @JvmStatic
        external fun initContextFromAsset(
            assetManager: AssetManager,
            assetPath: String
        ): Long

        @JvmStatic
        external fun initContextFromInputStream(
            inputStream: InputStream
        ): Long

        @JvmStatic
        external fun freeContext(contextPtr: Long)

        @JvmStatic
        external fun fullTranscribe(
            contextPtr: Long,
            lang: String,
            numThreads: Int,
            translate: Boolean,
            audioData: FloatArray
        ): Int

        @JvmStatic
        external fun getTextSegmentCount(
            contextPtr: Long
        ): Int

        @JvmStatic
        external fun getTextSegment(
            contextPtr: Long,
            index: Int
        ): String

        @JvmStatic
        external fun getTextSegmentT0(
            contextPtr: Long,
            index: Int
        ): Long

        @JvmStatic
        external fun getTextSegmentT1(
            contextPtr: Long,
            index: Int
        ): Long

        @JvmStatic
        external fun getSystemInfo(): String

        @JvmStatic
        external fun benchMemcpy(
            nthread: Int
        ): String

        @JvmStatic
        external fun benchGgmlMulMat(
            nthread: Int
        ): String

        // --------------------------------------------------------
        // Runtime CPU feature detection
        // --------------------------------------------------------

        /**
         * Reads Linux CPU feature information when available.
         *
         * Failure is intentionally non-fatal. The generic native library is
         * used if optimized feature detection is unavailable.
         */
        private fun cpuInfo(): String? {
            return try {
                File("/proc/cpuinfo")
                    .bufferedReader()
                    .use { reader -> reader.readText() }
            } catch (e: Exception) {
                Log.w(
                    LOG_TAG,
                    "Could not read /proc/cpuinfo; optimized library detection disabled",
                    e
                )
                null
            }
        }

        /**
         * True when Android reports the process-preferred ABI as ARM64.
         */
        private fun isArm64(abi: String): Boolean =
            abi.equals(
                "arm64-v8a",
                ignoreCase = true
            )

        /**
         * True when Android reports the process-preferred ABI as ARMv7.
         */
        private fun isArmv7(abi: String): Boolean =
            abi.equals(
                "armeabi-v7a",
                ignoreCase = true
            )

        /**
         * Detects ARM FP16 arithmetic capability tokens.
         */
        private fun hasFp16(info: String): Boolean {
            val normalized = info.lowercase(Locale.US)

            return "asimdhp" in normalized ||
                    "fphp" in normalized ||
                    "fp16" in normalized
        }

        /**
         * Detects ARMv7 VFPv4 capability.
         */
        private fun hasVfpv4(info: String): Boolean {
            val normalized = info.lowercase(Locale.US)
            return "vfpv4" in normalized
        }
    }
}

// ============================================================
// Utility
// ============================================================

/**
 * Converts whisper segment timestamp units to hh:mm:ss.mmm.
 *
 * whisper_full_get_segment_t0/t1 use 10 ms timestamp units.
 */
private fun toTimestamp(
    timestampUnits: Long,
    comma: Boolean = false
): String {
    var milliseconds = timestampUnits.coerceAtLeast(0L) * 10L

    val hours = milliseconds / 3_600_000L
    milliseconds %= 3_600_000L

    val minutes = milliseconds / 60_000L
    milliseconds %= 60_000L

    val seconds = milliseconds / 1_000L
    milliseconds %= 1_000L

    val delimiter = if (comma) "," else "."

    return String.format(
        Locale.US,
        "%02d:%02d:%02d%s%03d",
        hours,
        minutes,
        seconds,
        delimiter,
        milliseconds
    )
}
