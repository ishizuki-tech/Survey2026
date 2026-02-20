/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: RuntimeLogStore.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.net

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.negi.survey.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * App-owned runtime log store (NOT logcat).
 *
 * Goals:
 * - Provide a lightweight, always-available rolling log facility.
 * - Store logs under app-private storage, ready to be bundled and uploaded later.
 * - Avoid blocking caller threads (writes are buffered and serialized on a single IO thread).
 *
 * Design:
 * - A single-writer actor (Channel) serializes all writes and rotations.
 * - Rolling files by max bytes, plus retention by total bytes + max files.
 * - `zipForUpload()` flushes a barrier before zipping for consistent bundles.
 *
 * Storage layout:
 *   files/diagnostics/runtime_logs/
 *     rtlog_yyyyMMdd_HHmmss_SSS_pid<PID>_seq<N>.log
 */
object RuntimeLogStore {

    private const val TAG = "RuntimeLogStore"

    private const val DIR_DIAGNOSTICS = "diagnostics"
    private const val DIR_RUNTIME_LOGS = "runtime_logs"

    private const val CHARSET_NAME = "UTF-8"
    private val UTF8: Charset = Charsets.UTF_8

    /** Default per-file rolling cap (bytes). */
    private const val DEFAULT_MAX_FILE_BYTES: Long = 900_000L

    /** Default max number of rolling files kept. */
    private const val DEFAULT_MAX_FILES: Int = 12

    /** Default max total bytes across all rolling files. */
    private const val DEFAULT_MAX_TOTAL_BYTES: Long = 6_000_000L

    /** Channel capacity; overflow drops (best-effort). */
    private const val DEFAULT_CHANNEL_CAPACITY: Int = 2_000

    private val started = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var rootDir: File? = null

    private val seq = AtomicLong(0L)

    private val writerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rtlog-io").apply { isDaemon = true }
    }
    private val writerDispatcher = writerExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + writerDispatcher)

    private sealed class Cmd {
        data class Write(val line: String) : Cmd()
        data class Flush(val ack: CompletableDeferred<Unit>) : Cmd()
        data class Rotate(val reason: String) : Cmd()
    }

    private val ch: Channel<Cmd> = Channel(capacity = DEFAULT_CHANNEL_CAPACITY)

    @Volatile
    private var maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES

    @Volatile
    private var maxFiles: Int = DEFAULT_MAX_FILES

    @Volatile
    private var maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES

    private val fileLock = Any()

    private var activeFile: File? = null
    private var activeWriter: BufferedWriter? = null
    private var activeBytes: Long = 0L

    /**
     * Start the store (idempotent).
     *
     * Call once early, e.g. Application.onCreate().
     */
    fun start(
        context: Context,
        maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
        maxFiles: Int = DEFAULT_MAX_FILES,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES
    ) {
        if (!started.compareAndSet(false, true)) return

        this.appContext = context.applicationContext
        this.maxFileBytes = maxFileBytes.coerceAtLeast(50_000L)
        this.maxFiles = maxFiles.coerceIn(2, 100)
        this.maxTotalBytes = maxTotalBytes.coerceAtLeast(this.maxFileBytes * 2L)

        val dir = File(context.filesDir, "$DIR_DIAGNOSTICS/$DIR_RUNTIME_LOGS").apply { mkdirs() }
        rootDir = dir

        scope.launch {
            runCatching {
                openWriterIfNeededLocked("start")
                writeHeaderLocked()
                enforceRetentionLocked("start")
            }.onFailure { e ->
                Log.w(TAG, "start: init failed: ${e.message}", e)
            }

            // Actor loop
            for (cmd in ch) {
                try {
                    when (cmd) {
                        is Cmd.Write -> {
                            openWriterIfNeededLocked("write")
                            writeLineLocked(cmd.line)
                            if (activeBytes >= this@RuntimeLogStore.maxFileBytes) {
                                rotateLocked("maxFileBytes")
                            }
                        }
                        is Cmd.Flush -> {
                            flushLocked()
                            cmd.ack.complete(Unit)
                        }
                        is Cmd.Rotate -> {
                            rotateLocked(cmd.reason)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "actor: failed: ${t.message}", t)
                    // Best-effort: try to rotate to recover from IO errors.
                    runCatching { rotateLocked("actor-error") }
                }
            }
        }
    }

    /**
     * Write a DEBUG log line (best-effort, non-blocking).
     */
    fun d(tag: String, message: String) = write(Level.D, tag, message, null)

    /**
     * Write an INFO log line (best-effort, non-blocking).
     */
    fun i(tag: String, message: String) = write(Level.I, tag, message, null)

    /**
     * Write a WARN log line (best-effort, non-blocking).
     */
    fun w(tag: String, message: String, tr: Throwable? = null) = write(Level.W, tag, message, tr)

    /**
     * Write an ERROR log line (best-effort, non-blocking).
     */
    fun e(tag: String, message: String, tr: Throwable? = null) = write(Level.E, tag, message, tr)

    /**
     * Request a manual rotate (best-effort).
     */
    fun rotate(reason: String = "manual") {
        if (!started.get()) return
        ch.trySend(Cmd.Rotate(reason))
    }

    /**
     * Flush buffered logs (suspend).
     *
     * Use this before zipping if you want stronger consistency.
     */
    suspend fun flush() {
        if (!started.get()) return
        val ack = CompletableDeferred<Unit>()
        ch.send(Cmd.Flush(ack))
        ack.await()
    }

    /**
     * Build a ZIP bundle for upload.
     *
     * Includes:
     * - runtime log files under files/diagnostics/runtime_logs/
     * - a manifest.json at zip root
     *
     * Returns the created zip file in cacheDir/diagnostics_upload/.
     */
    suspend fun zipForUpload(reason: String = "wm"): File = withContext(Dispatchers.IO) {
        val ctx = appContext ?: throw IllegalStateException("RuntimeLogStore not started (context=null).")
        val dir = rootDir ?: throw IllegalStateException("RuntimeLogStore not started (dir=null).")

        // Ensure everything queued is flushed to disk.
        flush()

        val outDir = File(ctx.cacheDir, "diagnostics_upload").apply { mkdirs() }
        val stamp = utcStamp()
        val pid = Process.myPid()
        val safeReason = safeSegment(reason)
        val zipFile = File(outDir, "runtime_logs_${stamp}_pid${pid}_${safeReason}.zip")

        val logFiles = synchronized(fileLock) {
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".log", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
        }

        val manifest = buildManifestJson(reason = safeReason, files = logFiles)

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // manifest.json
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray(UTF8))
            zos.closeEntry()

            // logs/
            for (f in logFiles) {
                val entryName = "logs/${f.name}"
                zos.putNextEntry(ZipEntry(entryName))
                f.inputStream().use { ins ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        zos.write(buf, 0, n)
                    }
                }
                zos.closeEntry()
            }
        }

        zipFile
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private enum class Level(val short: String) {
        D("D"),
        I("I"),
        W("W"),
        E("E")
    }

    private fun write(level: Level, tag: String, message: String, tr: Throwable?) {
        if (!started.get()) return

        val line = formatLine(level, tag, message, tr)
        // Best-effort: drop if channel is full.
        ch.trySend(Cmd.Write(line))
    }

    private fun formatLine(level: Level, tag: String, message: String, tr: Throwable?): String {
        val ts = utcIso8601()
        val tid = Thread.currentThread().name
        val safeTag = tag.take(64)
        val msg = message.replace("\r\n", "\n").replace("\r", "\n")

        val base = "$ts ${level.short}/$safeTag [$tid] $msg"
        return if (tr == null) {
            base + "\n"
        } else {
            val stack = Log.getStackTraceString(tr)
            base + "\n" + stack + "\n"
        }
    }

    private fun utcIso8601(): String {
        val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date())
    }

    private fun utcStamp(): String {
        val df = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date())
    }

    private fun safeSegment(s: String): String =
        s.trim().ifBlank { "wm" }
            .replace(Regex("""[^A-Za-z0-9_\-\.]+"""), "_")
            .take(24)

    private fun openWriterIfNeededLocked(why: String) {
        synchronized(fileLock) {
            if (activeWriter != null && activeFile != null) return

            val ctx = appContext ?: return
            val dir = rootDir ?: File(ctx.filesDir, "$DIR_DIAGNOSTICS/$DIR_RUNTIME_LOGS").apply { mkdirs() }.also { rootDir = it }

            val stamp = utcStamp()
            val pid = Process.myPid()
            val s = seq.incrementAndGet()
            val f = File(dir, "rtlog_${stamp}_pid${pid}_seq${s}.log")

            val os = FileOutputStream(f, true)
            val w = BufferedWriter(OutputStreamWriter(os, UTF8), 32 * 1024)

            activeFile = f
            activeWriter = w
            activeBytes = f.length().coerceAtLeast(0L)

            Log.d(TAG, "openWriter: $why -> ${f.absolutePath} (bytes=$activeBytes)")
        }
    }

    private fun writeHeaderLocked() {
        synchronized(fileLock) {
            val w = activeWriter ?: return
            val header = buildString {
                appendLine("=== RuntimeLogStore Header ===")
                appendLine("ts=${utcIso8601()}")
                appendLine("appId=${BuildConfig.APPLICATION_ID}")
                appendLine("versionName=${BuildConfig.VERSION_NAME}")
                appendLine("versionCode=${BuildConfig.VERSION_CODE}")
                appendLine("debug=${BuildConfig.DEBUG}")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("sdk=${Build.VERSION.SDK_INT}")
                appendLine("pid=${Process.myPid()}")
                appendLine("==============================")
            }
            w.write(header)
            w.flush()
            activeBytes += header.toByteArray(UTF8).size.toLong()
        }
    }

    private fun writeLineLocked(line: String) {
        synchronized(fileLock) {
            val w = activeWriter ?: return
            w.write(line)
            // Flush lightly: do not flush every time (costly).
            // We flush on barriers, rotates, and occasionally by size growth.
            activeBytes += line.toByteArray(UTF8).size.toLong()
            if (activeBytes % 64_000L < 4_096L) {
                w.flush()
            }
        }
    }

    private fun flushLocked() {
        synchronized(fileLock) {
            runCatching { activeWriter?.flush() }
        }
    }

    private fun rotateLocked(reason: String) {
        synchronized(fileLock) {
            runCatching { activeWriter?.flush() }
            runCatching { activeWriter?.close() }
            activeWriter = null
            activeFile = null
            activeBytes = 0L
        }

        openWriterIfNeededLocked("rotate:$reason")

        synchronized(fileLock) {
            val w = activeWriter ?: return
            val line = "${utcIso8601()} I/RuntimeLogStore [rtlog-io] --- rotated (reason=$reason) ---\n"
            w.write(line)
            w.flush()
            activeBytes += line.toByteArray(UTF8).size.toLong()
        }

        enforceRetentionLocked("rotate:$reason")
    }

    private fun enforceRetentionLocked(why: String) {
        synchronized(fileLock) {
            val dir = rootDir ?: return
            val files = dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".log", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()

            // Keep newest first.
            val keep = files.take(maxFiles)
            val dropByCount = files.drop(maxFiles)

            for (f in dropByCount) {
                runCatching { f.delete() }
            }

            // Enforce total bytes on remaining.
            var kept = keep
            var total = kept.sumOf { it.length().coerceAtLeast(0L) }

            while (kept.size > 2 && total > maxTotalBytes) {
                val last = kept.last()
                total -= last.length().coerceAtLeast(0L)
                runCatching { last.delete() }
                kept = kept.dropLast(1)
            }

            Log.d(TAG, "retention: $why files=${files.size} kept=${kept.size} totalBytes=$total")
        }
    }

    private fun buildManifestJson(reason: String, files: List<File>): String {
        val obj = JSONObject()
        obj.put("kind", "runtime_logs")
        obj.put("reason", reason)
        obj.put("ts_utc", utcIso8601())
        obj.put("appId", BuildConfig.APPLICATION_ID)
        obj.put("versionName", BuildConfig.VERSION_NAME)
        obj.put("versionCode", BuildConfig.VERSION_CODE)
        obj.put("debug", BuildConfig.DEBUG)
        obj.put("pid", Process.myPid())
        obj.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        obj.put("sdk", Build.VERSION.SDK_INT)

        val arr = org.json.JSONArray()
        for (f in files) {
            val it = JSONObject()
            it.put("name", f.name)
            it.put("bytes", f.length().coerceAtLeast(0L))
            it.put("mtime", f.lastModified())
            arr.put(it)
        }
        obj.put("files", arr)

        return obj.toString(2)
    }
}