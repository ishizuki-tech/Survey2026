// file: com/whispercpp/whisper/WhisperCpuConfig.kt
// ============================================================
// ✅ WhisperCpuConfig — Adaptive Thread Optimizer (Final Debug Edition)
// ------------------------------------------------------------
// • Detects big.LITTLE topology via /sys frequency & variant heuristics
// • Robust against permission or I/O failures
// • Provides stable minimum thread count (≥2)
// • Fine-grained debug logging for SoC frequency / cluster distribution
// ============================================================

package com.whispercpp.whisper

import android.util.Log
import java.io.File

/**
 * Provides an adaptive thread count hint for whisper.cpp inference.
 *
 * whisper.cpp scales nearly linearly across high-performance cores,
 * but using LITTLE cores often introduces context-switching overhead
 * due to smaller L1 caches and reduced SIMD width (e.g. A510/A520).
 *
 * This helper estimates the number of “big” cores to use for compute-heavy
 * tasks (matrix multiplication, FFT, attention kernels).
 *
 * Design principles:
 * - Prefer /sys frequency readings (authoritative on most Android SoCs)
 * - Fallback to /proc variant ID grouping when /sys unavailable
 * - Never crash; always return ≥ 2 threads for decoding stability
 */
object WhisperCpuConfig {

    /** Lazily-evaluated thread count recommendation (≥ 2). */
    val preferredThreadCount: Int
        get() = CpuInfo.determineHighPerfCpuCount()
            .coerceAtLeast(2)
}

/**
 * Internal helper class for parsing CPU topology data.
 *
 * The logic follows a two-tier fallback:
 * 1. Primary: frequency analysis from /sys cpufreq entries
 * 2. Secondary: cluster differentiation using variant hex codes
 *
 * The fallback ensures a usable thread hint even on restricted systems
 * (e.g. sandboxed processes, devices with sealed /sys access).
 */
private class CpuInfo(private val lines: List<String>) {

    /**
     * Entry point: attempts frequency-based detection first,
     * falls back to variant-based if necessary.
     */
    fun computeHighPerfCpuCount(): Int = try {
        computeByFrequency()
    } catch (e: Exception) {
        Log.d(LOG_TAG, "Frequency detection failed → variant fallback", e)
        computeByVariant()
    }

    // ------------------------------------------------------------
    // Frequency-based detection (preferred)
    // ------------------------------------------------------------
    /**
     * Estimates number of high-performance cores via max frequency comparison.
     * Each cluster typically reports distinct cpuinfo_max_freq values.
     */
    private fun computeByFrequency(): Int {
        val freqList = getProcessorIndices().map { getMaxCpuFrequency(it) }
        if (freqList.isEmpty()) return 0

        val freqBins = freqList.groupingBy { it }.eachCount()
        Log.d(LOG_TAG, "CPU freq bins (kHz): $freqBins")

        // Identify threshold between LITTLE and big clusters
        val minFreq = freqList.minOrNull() ?: 0
        val highPerfCount = freqList.count { it > minFreq }
        Log.d(LOG_TAG, "Detected high-perf cores=$highPerfCount via freq (min=$minFreq kHz)")
        return highPerfCount
    }

    // ------------------------------------------------------------
    // Variant-based detection (fallback)
    // ------------------------------------------------------------
    /**
     * Fallback when frequency info is unavailable.
     * Uses variant field hex codes to differentiate clusters.
     */
    private fun computeByVariant(): Int {
        val variants = getCpuValues("CPU variant") {
            it.substringAfter("0x").toIntOrNull(16) ?: 0
        }
        if (variants.isEmpty()) return 0
        val variantBins = variants.groupingBy { it }.eachCount()
        Log.d(LOG_TAG, "CPU variant bins (hex): $variantBins")

        // Higher variant code typically indicates performance cluster.
        val min = variants.minOrNull() ?: 0
        val highPerfCount = variants.count { it > min }
        Log.d(LOG_TAG, "Detected high-perf cores=$highPerfCount via variant (min=0x${min.toString(16)})")
        return highPerfCount
    }

    // ------------------------------------------------------------
    // Parsing utilities
    // ------------------------------------------------------------
    /** Extracts processor indices (e.g., “processor : 0”) safely. */
    private fun getProcessorIndices(): List<Int> =
        lines.filter { it.startsWith("processor") }
            .mapNotNull { it.substringAfter(":").trim().toIntOrNull() }
            .sorted()

    /** Generic extractor for numeric fields like “CPU variant : 0x1”. */
    private fun getCpuValues(property: String, mapper: (String) -> Int): List<Int> =
        lines.asSequence()
            .filter { it.startsWith(property) }
            .mapNotNull { runCatching { mapper(it.substringAfter(':').trim()) }.getOrNull() }
            .sorted()
            .toList()

    companion object {
        private const val LOG_TAG = "WhisperCpuConfig"

        /**
         * Main entry invoked by [WhisperCpuConfig].
         * Returns detected big-core count or heuristic fallback.
         */
        fun determineHighPerfCpuCount(): Int = try {
            val info = readCpuInfo()
            val detected = info.computeHighPerfCpuCount()
            if (detected > 0) detected else safeFallback()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to parse /proc/cpuinfo → fallback", e)
            safeFallback()
        }

        /**
         * Fallback heuristic when detection fails completely.
         *
         * Strategy:
         * - For SoCs with ≥8 logical cores: assume ~half are big
         * - For smaller SoCs: at least 2, but never exceed 8
         */
        private fun safeFallback(): Int {
            val total = Runtime.getRuntime().availableProcessors()
            val est = minOf(total / 2, 8)
            val result = est.coerceAtLeast(2)
            Log.d(LOG_TAG, "Fallback: high-perf cores=$result (total=$total)")
            return result
        }

        /**
         * Reads and parses `/proc/cpuinfo`.
         * Throws if unreadable or empty to trigger fallback path.
         */
        private fun readCpuInfo(): CpuInfo {
            val file = File("/proc/cpuinfo")
            if (!file.canRead()) {
                Log.w(LOG_TAG, "/proc/cpuinfo unreadable → fallback")
                throw IllegalStateException("cpuinfo not readable")
            }
            val lines = file.useLines { it.toList() }
            if (lines.isEmpty()) throw IllegalStateException("cpuinfo empty")
            return CpuInfo(lines)
        }

        /**
         * Reads the maximum clock frequency for a given core index.
         *
         * Typical path:
         * `/sys/devices/system/cpu/cpuX/cpufreq/cpuinfo_max_freq`
         *
         * Returns 0 if unavailable, in kHz.
         */
        private fun getMaxCpuFrequency(cpuIndex: Int): Int {
            val path = "/sys/devices/system/cpu/cpu$cpuIndex/cpufreq/cpuinfo_max_freq"
            return try {
                File(path).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
            } catch (e: Exception) {
                Log.v(LOG_TAG, "Cannot read $path: ${e.message}")
                0
            }
        }
    }
}
