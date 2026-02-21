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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Ring-buffer file logger owned by the app process.
 *
 * Why:
 * - logcat is a finite system ring buffer and cannot be "reliable" for "last crash before logs".
 * - app-owned files in filesDir are reliable across restarts (unless explicitly removed).
 *
 * Design:
 * - Writes line-oriented text logs into fixed-size segments (rotating).
 * - Snapshot reads from newest->oldest to build a "last N bytes" view.
 * - Provides crash staging: copy a snapshot into CrashCapture's crash dir.
 */
object AppRingLogStore {

    private const val TAG = "AppRingLogStore"

    /** Directory under filesDir. */
    private const val DIR_REL = "diagnostics/applog_ring"

    /** Segment filename prefix. */
    private const val SEG_PREFIX = "seg_"

    /** Segment count (rotation). */
    private const val SEG_COUNT = 16

    /** Max size per segment in bytes. */
    private const val SEG_MAX_BYTES = 256 * 1024

    /** Snapshot size used for crash staging. */
    private const val CRASH_SNAPSHOT_MAX_BYTES = 1_500_000

    /** Flush strategy: flush every write for safety (slower but reliable). */
    private const val FLUSH_EVERY_WRITE = true

    private val installed = AtomicBoolean(false)

    @Volatile
    private var rootDir: File? = null

    @Volatile
    private var currentIndex: Int = 0

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppRingLog-IO").apply { isDaemon = true }
    }

    private val FILE_TS_UTC = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Returns the ring directory (best-effort).
     *
     * Notes:
     * - This does NOT implicitly install the store (no segment selection, no header write).
     * - It is safe to call even before [install]; callers may use it for discovery.
     */
    fun ringDir(context: Context): File {
        val appCtx = context.applicationContext ?: context
        val dir = File(appCtx.filesDir, DIR_REL)
        runCatching { dir.mkdirs() }
        return dir
    }

    /**
     * Install (initialize) the ring directory and pick a writable segment.
     *
     * Call early (Application.onCreate).
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return

        val dir = ringDir(context)
        dir.mkdirs()
        rootDir = dir

        // Pick the newest segment index as the current write head.
        currentIndex = pickWriteIndex(dir)

        // Write a small session header line.
        val now = Date()
        enqueueWrite(
            formatLine(
                level = "I",
                tag = TAG,
                msg = "install: pid=${Process.myPid()} timeUtc=${FILE_TS_UTC.format(now)} uptimeMs=${SystemClock.elapsedRealtime()}",
                tr = null
            )
        )

        Log.d(TAG, "installed: dir=${dir.absolutePath} idx=$currentIndex")
    }

    /**
     * Log a line into the ring store.
     *
     * Notes:
     * - Keep messages short-ish; snapshot size is capped.
     * - Do not call from performance-critical tight loops without throttling.
     */
    fun log(level: String, tag: String, msg: String, tr: Throwable? = null) {
        val line = formatLine(level = level, tag = tag, msg = msg, tr = tr)
        enqueueWrite(line)
    }

    /**
     * Stage a snapshot into the given crash directory.
     *
     * This is designed to be called from an uncaughtException handler (best-effort).
     * It performs only bounded IO (last N bytes) and writes a plain text file.
     */
    fun stageSnapshotForCrash(crashDir: File, prefix: String = "applog"): File? {
        val dir = rootDir ?: return null
        if (!dir.exists()) return null

        val now = Date()
        val stampUtc = FILE_TS_UTC.format(now)
        val out = File(crashDir, "${prefix}_${stampUtc}_pid${Process.myPid()}.log")

        return try {
            val bytes = snapshotBytes(maxBytes = CRASH_SNAPSHOT_MAX_BYTES)
            FileOutputStream(out).use { fos ->
                fos.write(bytes)
                fos.flush()
                runCatching { fos.fd.sync() }
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "stageSnapshotForCrash failed: ${t.message}", t)
            null
        }
    }

    /**
     * Build a snapshot of the most recent bytes across segments (newest->oldest).
     */
    fun snapshotBytes(maxBytes: Int): ByteArray {
        val dir = rootDir ?: return ByteArray(0)
        val segs = listSegmentsNewestFirst(dir)
        if (segs.isEmpty()) return ByteArray(0)

        val out = ByteArrayOutputStreamCapped(maxBytes)
        for (f in segs) {
            if (out.remaining() <= 0) break
            val chunk = readTailBytes(f, out.remaining())
            out.write(chunk)
        }
        return out.toByteArray()
    }

    // -----------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------

    private fun enqueueWrite(line: String) {
        val dir = rootDir ?: return
        io.execute {
            try {
                val f = currentSegmentFile(dir, currentIndex)
                rotateIfNeeded(dir, f)

                appendLine(f, line)
            } catch (t: Throwable) {
                Log.w(TAG, "write failed: ${t.message}", t)
            }
        }
    }

    private fun rotateIfNeeded(dir: File, f: File) {
        if (f.exists() && f.length() >= SEG_MAX_BYTES) {
            currentIndex = (currentIndex + 1) % SEG_COUNT
            val next = currentSegmentFile(dir, currentIndex)
            // Start a fresh segment (truncate).
            runCatching {
                if (next.exists()) next.writeText("")
            }
        }
    }

    private fun appendLine(file: File, line: String) {
        FileOutputStream(file, true).use { fos ->
            BufferedOutputStream(fos, 32 * 1024).use { bos ->
                bos.write(line.toByteArray(Charsets.UTF_8))
                if (FLUSH_EVERY_WRITE) {
                    bos.flush()
                    fos.flush()
                    runCatching { fos.fd.sync() }
                }
            }
        }
    }

    private fun pickWriteIndex(dir: File): Int {
        // Find the newest existing segment; if none, start at 0.
        var bestIdx = 0
        var bestTs = -1L
        for (i in 0 until SEG_COUNT) {
            val f = currentSegmentFile(dir, i)
            if (f.exists()) {
                val ts = f.lastModified()
                if (ts > bestTs) {
                    bestTs = ts
                    bestIdx = i
                }
            }
        }
        return bestIdx
    }

    private fun currentSegmentFile(dir: File, index: Int): File {
        return File(dir, "$SEG_PREFIX${index.toString().padStart(2, '0')}.log")
    }

    private fun listSegmentsNewestFirst(dir: File): List<File> {
        val segs = (0 until SEG_COUNT)
            .map { currentSegmentFile(dir, it) }
            .filter { it.exists() && it.length() > 0L }
        return segs.sortedByDescending { it.lastModified() }
    }

    private fun readTailBytes(file: File, maxBytes: Int): ByteArray {
        val len = file.length().toInt().coerceAtLeast(0)
        if (len <= 0) return ByteArray(0)

        val toRead = min(len, maxBytes)
        val buf = ByteArray(toRead)

        // Read from end.
        val raf = java.io.RandomAccessFile(file, "r")
        return try {
            raf.seek((len - toRead).toLong())
            raf.readFully(buf)
            buf
        } finally {
            runCatching { raf.close() }
        }
    }

    private fun formatLine(level: String, tag: String, msg: String, tr: Throwable?): String {
        val now = Date()
        val stampUtc = FILE_TS_UTC.format(now)
        val base = buildString {
            append(stampUtc)
            append(" ")
            append(level)
            append("/")
            append(tag)
            append(" pid=")
            append(Process.myPid())
            append(" tid=")
            append(Process.myTid())
            append(" uptimeMs=")
            append(SystemClock.elapsedRealtime())
            append(" msg=")
            append(msg.replace('\n', ' '))
        }
        if (tr == null) return "$base\n"
        val stack = Log.getStackTraceString(tr).replace('\n', ' ')
        return "$base ex=$stack\n"
    }

    private class ByteArrayOutputStreamCapped(private val cap: Int) {
        private val buf = ByteArray(cap)
        private var size = 0

        fun remaining(): Int = cap - size

        fun write(bytes: ByteArray) {
            if (bytes.isEmpty() || remaining() <= 0) return
            val n = min(bytes.size, remaining())
            System.arraycopy(bytes, 0, buf, size, n)
            size += n
        }

        fun toByteArray(): ByteArray {
            return buf.copyOfRange(0, size)
        }
    }
}