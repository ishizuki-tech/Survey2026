/*
 * =====================================================================
 *  IshizukiTech LLC — Android Diagnostics
 *  ---------------------------------------------------------------------
 *  File: AppRingLogStore.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * App-owned rotating file logger.
 *
 * Goals:
 * - Keep recent diagnostic context across process restarts.
 * - Avoid depending on logcat's finite system ring buffer.
 * - Keep write ordering deterministic through one IO executor.
 * - Provide a bounded crash snapshot for CrashCapture.
 * - Stay best-effort: logging failures must never crash the app.
 *
 * Threading:
 * - [log] may be called from any thread.
 * - File writes are serialized through [io].
 * - Crash snapshotting performs a short best-effort drain of queued writes.
 *
 * Storage:
 * - Segments live under filesDir/diagnostics/applog_ring.
 * - A fixed number of files is reused in a ring.
 */
object AppRingLogStore {

    private const val TAG = "AppRingLogStore"

    /** Directory under filesDir. */
    private const val DIR_REL = "diagnostics/applog_ring"

    /** Segment filename prefix. */
    private const val SEG_PREFIX = "seg_"

    /** Number of files in the rotating ring. */
    private const val SEG_COUNT = 16

    /** Maximum target size for one segment. */
    private const val SEG_MAX_BYTES = 256 * 1024

    /** Maximum crash snapshot payload. */
    private const val CRASH_SNAPSHOT_MAX_BYTES = 1_500_000

    /**
     * Maximum amount of snapshot memory a public caller may request.
     *
     * This prevents accidental very large ByteArray allocations.
     */
    private const val ABSOLUTE_SNAPSHOT_MAX_BYTES = SEG_COUNT * SEG_MAX_BYTES

    /**
     * Prevent one pathological message or stack trace from consuming an
     * entire segment.
     */
    private const val MAX_MESSAGE_CHARS = 16 * 1024
    private const val MAX_STACK_CHARS = 48 * 1024

    /**
     * Best-effort time allowed for queued writes to reach disk before a crash
     * snapshot starts.
     *
     * Never wait indefinitely from an uncaught-exception path.
     */
    private const val CRASH_DRAIN_TIMEOUT_MS = 300L

    private const val IO_THREAD_NAME = "AppRingLog-IO"

    private val installed = AtomicBoolean(false)

    @Volatile
    private var rootDir: File? = null

    /**
     * Only the single IO executor mutates this after installation.
     * Volatile keeps diagnostics/snapshot readers safe if they inspect it.
     */
    @Volatile
    private var currentIndex: Int = 0

    private val io: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, IO_THREAD_NAME).apply {
                isDaemon = true
            }
        }

    /**
     * SimpleDateFormat is not thread-safe.
     *
     * formatLine() may run on any app thread and crash staging may also format
     * a timestamp concurrently, so every access must be serialized.
     */
    private val timestampLock = Any()

    private val fileTimestampUtc =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    /**
     * Returns the ring directory.
     *
     * This does not install the logger or write a segment header.
     * Directory creation is best-effort here; [install] performs strict checks.
     */
    fun ringDir(context: Context): File {
        val appCtx = context.applicationContext ?: context
        val dir = File(appCtx.filesDir, DIR_REL)

        runCatching {
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }

        return dir
    }

    /**
     * Initializes the ring store.
     *
     * Safe to call repeatedly. If installation fails, the installed flag is
     * restored so a later call may retry.
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) {
            return
        }

        try {
            val dir = ringDir(context)
            ensureDirectory(dir)

            rootDir = dir
            currentIndex = pickWriteIndex(dir)

            val line = formatLine(
                level = "I",
                tag = TAG,
                msg = "install: pid=${Process.myPid()} " +
                        "timeUtc=${timestampUtc()} " +
                        "uptimeMs=${SystemClock.elapsedRealtime()}",
                tr = null
            )

            enqueueWrite(
                line = line,
                syncToDisk = true
            )

            Log.d(
                TAG,
                "installed: dir=${dir.absolutePath} idx=$currentIndex"
            )
        } catch (t: Throwable) {
            rootDir = null
            currentIndex = 0
            installed.set(false)

            Log.w(
                TAG,
                "install failed: ${t.message}",
                t
            )

            throw t
        }
    }

    /**
     * Writes one log entry asynchronously.
     *
     * Warning/error/fatal entries request an fsync. Lower-priority entries are
     * still flushed and the stream is closed after every write, but skip the
     * expensive fsync syscall.
     */
    fun log(
        level: String,
        tag: String,
        msg: String,
        tr: Throwable? = null
    ) {
        val normalizedLevel = normalizeLevel(level)

        val line = formatLine(
            level = normalizedLevel,
            tag = tag,
            msg = msg,
            tr = tr
        )

        enqueueWrite(
            line = line,
            syncToDisk = shouldSyncToDisk(normalizedLevel)
        )
    }

    /**
     * Creates a bounded snapshot file inside [crashDir].
     *
     * Intended for uncaught-exception handling. The method is best-effort and
     * deliberately avoids an unbounded wait for the logging executor.
     */
    fun stageSnapshotForCrash(
        crashDir: File,
        prefix: String = "applog"
    ): File? {
        val dir = rootDir ?: return null

        if (!dir.isDirectory) {
            return null
        }

        return try {
            ensureDirectory(crashDir)

            /**
             * Drain writes already queued before the barrier.
             *
             * If the crash happens on the logging executor itself, do not wait
             * on that same executor because it would deadlock.
             */
            drainPendingWritesBestEffort(CRASH_DRAIN_TIMEOUT_MS)

            val stampUtc = timestampUtc()
            val safePrefix = sanitizeFileComponent(prefix).ifBlank { "applog" }

            val out = File(
                crashDir,
                "${safePrefix}_${stampUtc}_pid${Process.myPid()}.log"
            )

            val bytes = snapshotBytes(
                maxBytes = CRASH_SNAPSHOT_MAX_BYTES
            )

            FileOutputStream(out).use { fos ->
                fos.write(bytes)
                fos.flush()

                /**
                 * Crash artifacts are worth forcing to stable storage.
                 */
                runCatching {
                    fos.fd.sync()
                }
            }

            out
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "stageSnapshotForCrash failed: ${t.message}",
                t
            )
            null
        }
    }

    /**
     * Returns the newest [maxBytes] worth of ring data.
     *
     * Segment chunks are collected newest-to-oldest, then emitted in readable
     * chronological order among the selected chunks.
     */
    fun snapshotBytes(maxBytes: Int): ByteArray {
        val dir = rootDir ?: return ByteArray(0)

        if (!dir.isDirectory) {
            return ByteArray(0)
        }

        val safeMax = maxBytes.coerceIn(
            0,
            ABSOLUTE_SNAPSHOT_MAX_BYTES
        )

        if (safeMax == 0) {
            return ByteArray(0)
        }

        val segmentsNewestFirst =
            listSegmentsNewestFirst(dir)

        if (segmentsNewestFirst.isEmpty()) {
            return ByteArray(0)
        }

        val chunks =
            ArrayList<ByteArray>(segmentsNewestFirst.size)

        var remaining = safeMax

        for (file in segmentsNewestFirst) {
            if (remaining <= 0) {
                break
            }

            val chunk = readTailBytes(
                file = file,
                maxBytes = remaining
            )

            if (chunk.isNotEmpty()) {
                chunks += chunk
                remaining -= chunk.size
            }
        }

        val out = ByteArrayOutputStreamCapped(safeMax)

        for (index in chunks.indices.reversed()) {
            out.write(chunks[index])
        }

        return out.toByteArray()
    }

    // =====================================================================
    // Write path
    // =====================================================================

    private fun enqueueWrite(
        line: String,
        syncToDisk: Boolean
    ) {
        val dir = rootDir ?: return

        try {
            io.execute {
                try {
                    val bytes = line.toByteArray(Charsets.UTF_8)

                    val file = resolveWritableSegmentFile(
                        dir = dir,
                        incomingBytes = bytes.size
                    )

                    appendBytes(
                        file = file,
                        bytes = bytes,
                        syncToDisk = syncToDisk
                    )
                } catch (t: Throwable) {
                    Log.w(
                        TAG,
                        "write failed: ${t.message}",
                        t
                    )
                }
            }
        } catch (t: Throwable) {
            /**
             * Executor rejection should never escape into application code.
             */
            Log.w(
                TAG,
                "enqueueWrite failed: ${t.message}",
                t
            )
        }
    }

    /**
     * Resolves the segment that should receive [incomingBytes].
     *
     * Rotation is based on projected size instead of waiting until a segment
     * is already oversized.
     */
    private fun resolveWritableSegmentFile(
        dir: File,
        incomingBytes: Int
    ): File {
        var index = currentIndex
        var file = currentSegmentFile(dir, index)

        val currentLength =
            if (file.exists()) file.length() else 0L

        val shouldRotate =
            currentLength > 0L &&
                    (
                            currentLength >= SEG_MAX_BYTES ||
                                    currentLength + incomingBytes.toLong() > SEG_MAX_BYTES
                            )

        if (shouldRotate) {
            index = (index + 1) % SEG_COUNT
            currentIndex = index

            file = currentSegmentFile(dir, index)

            resetSegmentFile(file)
        }

        return file
    }

    /**
     * Truncates the selected ring segment before reuse.
     *
     * Failure is propagated to the executor-level catch rather than silently
     * continuing to append to an old oversized segment.
     */
    private fun resetSegmentFile(file: File) {
        file.parentFile?.let(::ensureDirectory)

        FileOutputStream(file, false).use { fos ->
            fos.flush()
        }
    }

    private fun appendBytes(
        file: File,
        bytes: ByteArray,
        syncToDisk: Boolean
    ) {
        file.parentFile?.let(::ensureDirectory)

        FileOutputStream(file, true).use { fos ->
            BufferedOutputStream(fos, 32 * 1024).use { bos ->
                bos.write(bytes)
                bos.flush()
            }

            if (syncToDisk) {
                runCatching {
                    fos.fd.sync()
                }
            }
        }
    }

    // =====================================================================
    // Rotation / discovery
    // =====================================================================

    /**
     * Picks the most recently modified existing segment.
     *
     * If no segments exist, index 0 is used.
     */
    private fun pickWriteIndex(dir: File): Int {
        var bestIndex = 0
        var bestTimestamp = Long.MIN_VALUE

        for (index in 0 until SEG_COUNT) {
            val file = currentSegmentFile(dir, index)

            if (!file.isFile) {
                continue
            }

            val timestamp = file.lastModified()

            if (timestamp > bestTimestamp) {
                bestTimestamp = timestamp
                bestIndex = index
            }
        }

        return bestIndex
    }

    private fun currentSegmentFile(
        dir: File,
        index: Int
    ): File {
        val suffix =
            index.toString().padStart(2, '0')

        return File(
            dir,
            "$SEG_PREFIX$suffix.log"
        )
    }

    private fun listSegmentsNewestFirst(
        dir: File
    ): List<File> {
        return (0 until SEG_COUNT)
            .asSequence()
            .map { currentSegmentFile(dir, it) }
            .filter { it.isFile && it.length() > 0L }
            .sortedByDescending { it.lastModified() }
            .toList()
    }

    // =====================================================================
    // Snapshot path
    // =====================================================================

    /**
     * Reads at most [maxBytes] from the tail of [file].
     */
    private fun readTailBytes(
        file: File,
        maxBytes: Int
    ): ByteArray {
        if (maxBytes <= 0 || !file.isFile) {
            return ByteArray(0)
        }

        val length = file.length()

        if (length <= 0L) {
            return ByteArray(0)
        }

        val toReadLong = min(
            length,
            maxBytes.toLong()
        )

        if (toReadLong <= 0L) {
            return ByteArray(0)
        }

        val toRead = toReadLong.toInt()
        val buffer = ByteArray(toRead)

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(length - toReadLong)
            raf.readFully(buffer)
        }

        return buffer
    }

    /**
     * Waits briefly for all writes already queued ahead of the barrier.
     *
     * This does not guarantee that another application thread cannot enqueue a
     * new log immediately after the barrier. It only improves crash snapshots
     * without risking an indefinite deadlock.
     */
    private fun drainPendingWritesBestEffort(
        timeoutMs: Long
    ) {
        if (Thread.currentThread().name == IO_THREAD_NAME) {
            return
        }

        val barrier = runCatching {
            io.submit { Unit }
        }.getOrNull() ?: return

        runCatching {
            barrier.get(
                timeoutMs.coerceAtLeast(1L),
                TimeUnit.MILLISECONDS
            )
        }
    }

    // =====================================================================
    // Formatting / sanitization
    // =====================================================================

    private fun formatLine(
        level: String,
        tag: String,
        msg: String,
        tr: Throwable?
    ): String {
        val timestamp = timestampUtc()

        val safeLevel =
            sanitizeInline(normalizeLevel(level))
                .ifBlank { "D" }

        val safeTag =
            sanitizeInline(tag)
                .take(128)
                .ifBlank { "<no-tag>" }

        val safeMessage =
            truncate(
                sanitizeInline(msg),
                MAX_MESSAGE_CHARS
            )

        val base = buildString {
            append(timestamp)
            append(" ")
            append(safeLevel)
            append("/")
            append(safeTag)
            append(" pid=")
            append(Process.myPid())
            append(" tid=")
            append(Process.myTid())
            append(" uptimeMs=")
            append(SystemClock.elapsedRealtime())
            append(" msg=")
            append(safeMessage)
        }

        if (tr == null) {
            return "$base\n"
        }

        val stack =
            truncate(
                sanitizeInline(
                    Log.getStackTraceString(tr)
                ),
                MAX_STACK_CHARS
            )

        return "$base ex=$stack\n"
    }

    private fun timestampUtc(): String {
        return synchronized(timestampLock) {
            fileTimestampUtc.format(Date())
        }
    }

    private fun normalizeLevel(level: String): String {
        return when (level.trim().uppercase(Locale.US)) {
            "V", "VERBOSE" -> "V"
            "D", "DEBUG" -> "D"
            "I", "INFO" -> "I"
            "W", "WARN", "WARNING" -> "W"
            "E", "ERROR" -> "E"
            "F", "FATAL", "A", "ASSERT" -> "F"
            else -> level.trim().uppercase(Locale.US).take(8)
        }
    }

    private fun shouldSyncToDisk(level: String): Boolean {
        return level == "W" ||
                level == "E" ||
                level == "F"
    }

    private fun sanitizeInline(value: String): String {
        return value
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\u0000', ' ')
    }

    private fun sanitizeFileComponent(value: String): String {
        return value
            .trim()
            .replace(
                Regex("[^A-Za-z0-9._-]+"),
                "_"
            )
            .take(64)
    }

    private fun truncate(
        value: String,
        maxChars: Int
    ): String {
        if (value.length <= maxChars) {
            return value
        }

        val suffix = "...(truncated)"
        val bodyLimit =
            (maxChars - suffix.length)
                .coerceAtLeast(0)

        return value.take(bodyLimit) + suffix
    }

    // =====================================================================
    // Filesystem helpers
    // =====================================================================

    private fun ensureDirectory(dir: File) {
        if (dir.isDirectory) {
            return
        }

        if (dir.exists() && !dir.isDirectory) {
            throw IllegalStateException(
                "Path exists but is not a directory: ${dir.absolutePath}"
            )
        }

        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IllegalStateException(
                "Failed to create directory: ${dir.absolutePath}"
            )
        }
    }

    /**
     * Small bounded byte accumulator used by snapshot assembly.
     */
    private class ByteArrayOutputStreamCapped(
        private val cap: Int
    ) {
        private val buffer =
            ByteArray(cap.coerceAtLeast(0))

        private var size = 0

        private fun remaining(): Int =
            buffer.size - size

        fun write(bytes: ByteArray) {
            if (bytes.isEmpty()) {
                return
            }

            val remaining = remaining()

            if (remaining <= 0) {
                return
            }

            val count =
                min(bytes.size, remaining)

            System.arraycopy(
                bytes,
                0,
                buffer,
                size,
                count
            )

            size += count
        }

        fun toByteArray(): ByteArray =
            buffer.copyOfRange(0, size)
    }
}
