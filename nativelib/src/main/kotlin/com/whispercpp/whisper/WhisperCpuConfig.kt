// file: com/whispercpp/whisper/WhisperCpuConfig.kt
// ============================================================
// WhisperCpuConfig — Adaptive Thread Optimizer
// ------------------------------------------------------------
// • Prefers Linux scheduler CPU-capacity information when available
// • Falls back to per-core maximum CPU frequency
// • Avoids unreliable CPU-variant ordering heuristics
// • Ignores unreadable/zero sysfs values instead of misclassifying them
// • Computes the recommendation once and caches it for process lifetime
// • Never recommends more threads than the JVM reports as available
// ============================================================

package com.whispercpp.whisper

import android.util.Log
import java.io.File

/**
 * Provides a conservative thread-count recommendation for whisper.cpp.
 *
 * On heterogeneous Android SoCs, using every logical core can be slower than
 * using only the faster CPU clusters because whisper.cpp workers synchronize
 * during compute-heavy operations.
 *
 * Detection order:
 *
 * 1. `/sys/devices/system/cpu/cpuX/cpu_capacity`
 *    - Preferred because CPU capacity represents scheduler-visible compute
 *      capability and can account for microarchitecture differences.
 *
 * 2. `/sys/devices/system/cpu/cpuX/cpufreq/cpuinfo_max_freq`
 *    - Useful fallback when cpu_capacity is unavailable.
 *
 * 3. Runtime core-count heuristic
 *    - Used when Android/vendor permissions hide topology information.
 *
 * The result is cached because CPU topology is effectively static for the
 * lifetime of the app process.
 */
object WhisperCpuConfig {

    /**
     * Recommended whisper.cpp worker thread count.
     *
     * The value is computed once on first access.
     */
    val preferredThreadCount: Int by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CpuDetector.determineThreadCount()
    }
}

/**
 * Android/Linux CPU topology detector.
 */
private object CpuDetector {

    private const val LOG_TAG = "WhisperCpuConfig"

    private const val CPU_SYSFS_ROOT = "/sys/devices/system/cpu"

    /**
     * whisper.cpp historically shows diminishing returns at high thread counts,
     * so keep the recommendation bounded even on unusually large devices.
     */
    private const val MAX_RECOMMENDED_THREADS = 8

    /**
     * Determines a stable worker-thread recommendation.
     */
    fun determineThreadCount(): Int {
        val available = Runtime.getRuntime()
            .availableProcessors()
            .coerceAtLeast(1)

        val cpuIndices = detectCpuIndices(available)

        Log.d(
            LOG_TAG,
            "CPU detection start: available=$available indices=$cpuIndices"
        )

        val detected =
            detectByCpuCapacity(cpuIndices)
                ?: detectByMaxFrequency(cpuIndices)
                ?: safeFallback(available)

        val result = detected.coerceIn(
            1,
            minOf(available, MAX_RECOMMENDED_THREADS)
        )

        Log.i(
            LOG_TAG,
            "Selected whisper thread count=$result " +
                    "(detected=$detected available=$available)"
        )

        return result
    }

    // ------------------------------------------------------------
    // Primary strategy: scheduler CPU capacity
    // ------------------------------------------------------------

    /**
     * Uses Linux scheduler CPU capacity when it is available for every detected
     * processor.
     *
     * On heterogeneous systems, the lowest-capacity cluster is excluded and all
     * faster clusters are retained. On homogeneous systems, every core is treated
     * as high-performance.
     */
    private fun detectByCpuCapacity(
        cpuIndices: List<Int>
    ): Int? {
        if (cpuIndices.isEmpty()) {
            return null
        }

        val values = cpuIndices.mapNotNull { cpuIndex ->
            readPositiveLong(
                "$CPU_SYSFS_ROOT/cpu$cpuIndex/cpu_capacity"
            )
        }

        if (values.size != cpuIndices.size) {
            Log.d(
                LOG_TAG,
                "CPU capacity unavailable/incomplete: " +
                        "${values.size}/${cpuIndices.size}"
            )
            return null
        }

        return countFastClusters(
            values = values,
            source = "capacity"
        )
    }

    // ------------------------------------------------------------
    // Secondary strategy: maximum CPU frequency
    // ------------------------------------------------------------

    /**
     * Uses per-core maximum frequency as a fallback.
     *
     * Zero or unreadable values are rejected. A partially readable topology is
     * not used because missing cores can make cluster classification incorrect.
     */
    private fun detectByMaxFrequency(
        cpuIndices: List<Int>
    ): Int? {
        if (cpuIndices.isEmpty()) {
            return null
        }

        val values = cpuIndices.mapNotNull { cpuIndex ->
            readPositiveLong(
                "$CPU_SYSFS_ROOT/cpu$cpuIndex/cpufreq/cpuinfo_max_freq"
            )
        }

        if (values.size != cpuIndices.size) {
            Log.d(
                LOG_TAG,
                "CPU max-frequency data unavailable/incomplete: " +
                        "${values.size}/${cpuIndices.size}"
            )
            return null
        }

        return countFastClusters(
            values = values,
            source = "maxFreqKHz"
        )
    }

    /**
     * Counts all cores except the slowest cluster.
     *
     * Examples:
     *
     * 6 LITTLE + 2 big:
     *   [1800 x6, 2200 x2] -> 2
     *
     * 4 LITTLE + 3 middle + 1 prime:
     *   [1800 x4, 2800 x3, 3200 x1] -> 4
     *
     * Homogeneous 8-core:
     *   [2400 x8] -> 8
     */
    private fun countFastClusters(
        values: List<Long>,
        source: String
    ): Int? {
        if (values.isEmpty()) {
            return null
        }

        val bins = values
            .groupingBy { it }
            .eachCount()
            .toSortedMap()

        Log.d(
            LOG_TAG,
            "CPU $source bins=$bins"
        )

        if (bins.size == 1) {
            val count = values.size

            Log.d(
                LOG_TAG,
                "Homogeneous CPU topology via $source: cores=$count"
            )

            return count
        }

        val minimum = values.minOrNull()
            ?: return null

        val highPerfCount = values.count { it > minimum }

        if (highPerfCount <= 0) {
            return null
        }

        Log.d(
            LOG_TAG,
            "Detected faster clusters via $source: " +
                    "cores=$highPerfCount slowestValue=$minimum"
        )

        return highPerfCount
    }

    // ------------------------------------------------------------
    // CPU index discovery
    // ------------------------------------------------------------

    /**
     * Attempts to discover processor indices from `/proc/cpuinfo`.
     *
     * If `/proc/cpuinfo` is unavailable or does not expose processor rows,
     * contiguous indices based on Runtime.availableProcessors() are used.
     */
    private fun detectCpuIndices(
        availableProcessors: Int
    ): List<Int> {
        val fromProc = readProcessorIndicesFromProc()

        if (fromProc.isNotEmpty()) {
            return fromProc
        }

        return (0 until availableProcessors).toList()
    }

    /**
     * Reads lines such as:
     *
     * `processor : 0`
     * `processor : 1`
     */
    private fun readProcessorIndicesFromProc(): List<Int> {
        val file = File("/proc/cpuinfo")

        if (!file.canRead()) {
            Log.d(
                LOG_TAG,
                "/proc/cpuinfo is not readable"
            )
            return emptyList()
        }

        return try {
            file.useLines { lines ->
                lines
                    .mapNotNull { line ->
                        if (!line.startsWith("processor")) {
                            return@mapNotNull null
                        }

                        line.substringAfter(':')
                            .trim()
                            .toIntOrNull()
                    }
                    .distinct()
                    .sorted()
                    .toList()
            }
        } catch (e: Exception) {
            Log.d(
                LOG_TAG,
                "Unable to parse /proc/cpuinfo: ${e.message}"
            )
            emptyList()
        }
    }

    // ------------------------------------------------------------
    // Safe fallback
    // ------------------------------------------------------------

    /**
     * Conservative fallback when topology information is hidden.
     *
     * Half of the JVM-visible logical processors is a reasonable default for
     * heterogeneous mobile CPUs. The result is always between 1 and the number
     * of available processors and is capped at MAX_RECOMMENDED_THREADS.
     */
    private fun safeFallback(
        availableProcessors: Int
    ): Int {
        val available = availableProcessors.coerceAtLeast(1)

        val estimated = when (available) {
            1 -> 1
            2 -> 2
            else -> (available / 2).coerceAtLeast(2)
        }

        val result = estimated.coerceIn(
            1,
            minOf(
                available,
                MAX_RECOMMENDED_THREADS
            )
        )

        Log.d(
            LOG_TAG,
            "CPU topology fallback: threads=$result available=$available"
        )

        return result
    }

    // ------------------------------------------------------------
    // File helpers
    // ------------------------------------------------------------

    /**
     * Reads a positive integer value from a sysfs node.
     *
     * Returns null for:
     * - missing files
     * - permission failures
     * - malformed values
     * - zero/negative values
     */
    private fun readPositiveLong(
        path: String
    ): Long? {
        return try {
            val file = File(path)

            if (!file.isFile || !file.canRead()) {
                return null
            }

            file.readText()
                .trim()
                .toLongOrNull()
                ?.takeIf { it > 0L }
        } catch (e: Exception) {
            Log.v(
                LOG_TAG,
                "Cannot read $path: ${e.message}"
            )
            null
        }
    }
}
