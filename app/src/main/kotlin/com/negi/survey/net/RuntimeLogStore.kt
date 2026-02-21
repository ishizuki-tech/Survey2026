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
import android.os.SystemClock
import android.util.Log
import com.negi.survey.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.Charset
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-owned runtime log store (NOT logcat by default).
 *
 * Goals:
 * - Provide a lightweight, always-available rolling log facility.
 * - Store logs under app-private storage, ready to be bundled and uploaded later.
 * - Avoid blocking caller threads (writes are buffered and serialized on a single IO thread).
 *
 * Design:
 * - A single-writer actor (Channel) serializes all writes and rotations.
 * - Rolling files by max bytes, plus retention by total bytes + max files.
 * - `zipForUpload()` performs a snapshot rotate to stabilize the file set.
 * - `prepareLogsForUploadPlain()` copies stable .log files to cacheDir for direct upload.
 *
 * Storage layout:
 *   files/diagnostics/runtime_logs/
 *     rtlog_<...>.log
 *
 * IMPORTANT:
 * - This is NOT a logcat mirror by default.
 * - You can optionally enable a debug-only logcat mirror via [setLogcatMirrorEnabled].
 * - Do NOT include "__" in source file names because Worker extraction uses "__<SOURCE_NAME>".
 */
object RuntimeLogStore {

    private const val TAG = "RuntimeLogStore"

    private const val DIR_DIAGNOSTICS = "diagnostics"
    private const val DIR_RUNTIME_LOGS = "runtime_logs"

    private val UTF8: Charset = Charsets.UTF_8

    /** Default per-file rolling cap (bytes). */
    private const val DEFAULT_MAX_FILE_BYTES: Long = 900_000L

    /** Default max number of rolling files kept. */
    private const val DEFAULT_MAX_FILES: Int = 12

    /** Default max total bytes across all rolling files. */
    private const val DEFAULT_MAX_TOTAL_BYTES: Long = 6_000_000L

    /**
     * Channel capacity.
     *
     * NOTE:
     * - We prefer "recent logs" under overload conditions.
     * - If the channel is full, we drop the oldest queued items (BufferOverflow.DROP_OLDEST).
     */
    private const val DEFAULT_CHANNEL_CAPACITY: Int = 12_000

    /**
     * Soft flush tuning:
     * - Without periodic flush, the file can look "too small" while the app is running
     *   because BufferedWriter keeps data in memory.
     */
    private const val SOFT_FLUSH_INTERVAL_MS: Long = 750L
    private const val SOFT_FLUSH_BYTES: Long = 16_384L

    /**
     * Flush timeout for best-effort barriers (ms).
     * Avoids deadlocks/hangs during startup or actor failures.
     */
    private const val FLUSH_TIMEOUT_MS: Long = 2_500L

    /**
     * Snapshot rotate timeout (ms).
     * Best-effort: if it times out, zipping/plain export proceeds with whatever is on disk.
     */
    private const val SNAPSHOT_TIMEOUT_MS: Long = 2_500L

    /** Max file name length (conservative; most filesystems allow 255). */
    private const val MAX_FILE_NAME_CHARS: Int = 180

    /**
     * Optional logcat mirroring:
     * - Disabled in release by default (privacy + noise).
     * - Enabled in debug by default (developer ergonomics).
     *
     * Recommended usage:
     *   RuntimeLogStore.setLogcatMirrorEnabled(BuildConfig.DEBUG)
     */
    @Volatile
    private var logcatMirrorEnabled: Boolean = BuildConfig.DEBUG

    /**
     * Enable/disable logcat mirroring (independent from file logging).
     *
     * Note:
     * - This affects only RuntimeLogStore.* calls.
     * - It does NOT change normal android.util.Log usage elsewhere.
     */
    fun setLogcatMirrorEnabled(enabled: Boolean) {
        logcatMirrorEnabled = enabled
        Log.d(TAG, "setLogcatMirrorEnabled: $enabled")
    }

    /** Thread-safe, cached formatters for timestamps. */
    private val ISO_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).withZone(ZoneOffset.UTC)

    private val STAMP_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.US).withZone(ZoneOffset.UTC)

    /**
     * Runtime log file name format selector.
     *
     * LEGACY:
     * - Matches the previous naming: rtlog_<stamp>_pid<PID>_seq<N>.log
     *
     * RICH:
     * - Adds build/app/version info (still safe and bounded):
     *   rtlog_<stamp>_<dbg|rel>_<app>_v<versionName>_c<versionCode>_sdk<sdk>_pid<pid>_seq<seq>.log
     *
     * SHORT:
     * - Minimal but collision-safe:
     *   rtlog_<stamp>_pid<pid>_s<seq>.log
     */
    enum class FileNameFormat { LEGACY, RICH, SHORT }

    @Volatile
    private var fileNameFormat: FileNameFormat = FileNameFormat.LEGACY

    /**
     * Set the runtime log file name format.
     *
     * Call this before start() for deterministic naming from the first file.
     */
    fun setFileNameFormat(format: FileNameFormat) {
        fileNameFormat = format
        Log.d(TAG, "setFileNameFormat: $format")
    }

    /** Indicates start() has been called at least once. */
    private val started = AtomicBoolean(false)

    /** Actor loop launch guard. */
    private val actorLaunched = AtomicBoolean(false)

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
        data class Write(val line: String, val forceFlush: Boolean) : Cmd()
        data class Flush(val ack: CompletableDeferred<Unit>) : Cmd()
        data class Rotate(val reason: String) : Cmd()

        /**
         * Rotate with acknowledgement.
         *
         * Used to create a stable snapshot boundary before zipping/plain export.
         * The ack returns the newly opened active file name (to be excluded from export).
         */
        data class RotateAck(val reason: String, val ack: CompletableDeferred<String?>) : Cmd()
    }

    private val ch: Channel<Cmd> = Channel(
        capacity = DEFAULT_CHANNEL_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @Volatile
    private var maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES

    @Volatile
    private var maxFiles: Int = DEFAULT_MAX_FILES

    @Volatile
    private var maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES

    private val fileLock = Any()

    private var activeFile: File? = null
    private var activeWriter: BufferedWriter? = null
    private var activeStream: FileOutputStream? = null
    private var activeBytes: Long = 0L

    private var lastSoftFlushUptimeMs: Long = 0L
    private var lastSoftFlushBytes: Long = 0L

    /**
     * Start the store (idempotent & repairable).
     */
    fun start(
        context: Context,
        maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
        maxFiles: Int = DEFAULT_MAX_FILES,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES
    ) {
        val ctx = context.applicationContext ?: context

        // Always refresh (repair).
        this.appContext = ctx
        this.maxFileBytes = maxFileBytes.coerceAtLeast(50_000L)
        this.maxFiles = maxFiles.coerceIn(2, 100)
        this.maxTotalBytes = maxTotalBytes.coerceAtLeast(this.maxFileBytes * 2L)

        val dir = File(ctx.filesDir, "$DIR_DIAGNOSTICS/$DIR_RUNTIME_LOGS").apply { mkdirs() }
        rootDir = dir

        if (!started.get()) started.set(true)

        if (!actorLaunched.compareAndSet(false, true)) {
            ch.trySend(Cmd.Write(formatLine(Level.I, TAG, "start repair: ctx/dir refreshed", null), forceFlush = true))
            return
        }

        scope.launch {
            runCatching {
                openWriterIfNeededLocked("start")
                writeHeaderLocked()
                writeLineLocked(
                    formatLine(Level.I, TAG, "start: actor launched (fileNameFormat=$fileNameFormat)", null),
                    forceFlush = true
                )
                enforceRetentionLocked("start")
            }.onFailure { e ->
                Log.w(TAG, "start: init failed: ${e.message}", e)
            }

            for (cmd in ch) {
                try {
                    when (cmd) {
                        is Cmd.Write -> {
                            openWriterIfNeededLocked("write")
                            writeLineLocked(cmd.line, cmd.forceFlush)
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

                        is Cmd.RotateAck -> {
                            rotateLocked(cmd.reason)
                            val exclude = synchronized(fileLock) { activeFile?.name }
                            cmd.ack.complete(exclude)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "actor: failed: ${t.message}", t)
                    runCatching { rotateLocked("actor-error") }
                }
            }
        }
    }

    fun d(tag: String, message: String) = write(Level.D, tag, message, null)
    fun i(tag: String, message: String) = write(Level.I, tag, message, null)
    fun w(tag: String, message: String, tr: Throwable? = null) = write(Level.W, tag, message, tr)
    fun e(tag: String, message: String, tr: Throwable? = null) = write(Level.E, tag, message, tr)

    fun rotate(reason: String = "manual") {
        if (!started.get() || !actorLaunched.get()) return
        ch.trySend(Cmd.Rotate(reason))
    }

    suspend fun flush() {
        if (!started.get() || !actorLaunched.get()) return
        val ack = CompletableDeferred<Unit>()

        runCatching { ch.send(Cmd.Flush(ack)) }.onFailure { return }

        val ok = withTimeoutOrNull(FLUSH_TIMEOUT_MS) { ack.await() } != null
        if (!ok) Log.w(TAG, "flush: timed out (best-effort).")
    }

    fun currentActiveLogFileName(): String? = synchronized(fileLock) { activeFile?.name }

    fun deleteRolledLogFiles(fileNames: Collection<String>, excludeActive: Boolean = true): Int {
        if (fileNames.isEmpty()) return 0

        val ctx = appContext
        val dir = rootDir ?: ctx?.let { File(it.filesDir, "$DIR_DIAGNOSTICS/$DIR_RUNTIME_LOGS") }
        if (dir == null || !dir.exists()) return 0

        val active = if (excludeActive) currentActiveLogFileName() else null
        var deleted = 0

        for (name in fileNames) {
            val n = name.trim()
            if (n.isBlank()) continue
            if (active != null && n == active) continue

            val f = File(dir, n)
            if (!f.exists() || !f.isFile) continue

            val ok = runCatching { f.delete() }.getOrNull() == true
            if (ok) deleted++
        }

        if (deleted > 0) {
            Log.d(TAG, "deleteRolledLogFiles: deleted=$deleted excludeActive=$excludeActive active=$active")
        }
        return deleted
    }

    suspend fun prepareLogsForUploadPlain(
        reason: String = "wm",
        limitFiles: Int = DEFAULT_MAX_FILES,
        includeActive: Boolean = false,
        rotateSnapshot: Boolean = true,
        writeManifest: Boolean = false
    ): List<File> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: throw IllegalStateException("RuntimeLogStore not started (context=null).")
        val dir = rootDir ?: throw IllegalStateException("RuntimeLogStore not started (dir=null).")

        flush()

        val safeReason = safeSegment(reason)
        val excludeName: String? = when {
            includeActive -> null
            rotateSnapshot -> snapshotRotateForPlainUpload(safeReason)
            else -> synchronized(fileLock) { activeFile?.name }
        }

        val candidates = dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".log", ignoreCase = true) }
            ?.filter { f -> excludeName == null || f.name != excludeName }
            ?.sortedByDescending { it.lastModified() }
            ?.toList()
            .orEmpty()

        val picked = candidates.take(limitFiles.coerceIn(1, 200))

        val outDir = File(ctx.cacheDir, "diagnostics_upload").apply { mkdirs() }
        val stamp = utcStamp()
        val pid = Process.myPid()

        val prepared = ArrayList<File>(picked.size)
        val skipped = ArrayList<String>()

        for (src in picked) {
            val dstName = "runtime_log_${stamp}_pid${pid}_${safeReason}__${src.name}"
            val dst = File(outDir, dstName)
            runCatching {
                copyFile(src, dst)
                prepared.add(dst)
            }.onFailure { t ->
                skipped.add("${src.name}: ${t.javaClass.simpleName}:${t.message}")
            }
        }

        if (writeManifest) {
            runCatching {
                val manifestFile = File(outDir, "plain_logs_manifest_${stamp}_pid${pid}_${safeReason}.json")
                val json = buildPlainManifestJson(
                    reason = safeReason,
                    excludedActive = excludeName,
                    sourceDir = dir,
                    pickedSources = picked,
                    preparedOutputs = prepared,
                    skipped = skipped
                )
                manifestFile.writeText(json, UTF8)
            }
        }

        prepared
    }

    suspend fun zipForUpload(reason: String = "wm"): File = withContext(Dispatchers.IO) {
        val ctx = appContext ?: throw IllegalStateException("RuntimeLogStore not started (context=null).")
        val dir = rootDir ?: throw IllegalStateException("RuntimeLogStore not started (dir=null).")

        flush()
        val excludeName = snapshotRotateForZip(reason)

        val outDir = File(ctx.cacheDir, "diagnostics_upload").apply { mkdirs() }
        val stamp = utcStamp()
        val pid = Process.myPid()
        val safeReason = safeSegment(reason)
        val zipFile = File(outDir, "runtime_logs_${stamp}_pid${pid}_${safeReason}.zip")

        val logFiles = dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".log", ignoreCase = true) }
            ?.filter { it.name != excludeName }
            ?.sortedByDescending { it.lastModified() }
            ?.toList()
            .orEmpty()

        val manifest = buildManifestJson(reason = safeReason, files = logFiles)

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray(UTF8))
            zos.closeEntry()

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

    private enum class Level(val short: String, val logcatPriority: Int) {
        D("D", Log.DEBUG),
        I("I", Log.INFO),
        W("W", Log.WARN),
        E("E", Log.ERROR)
    }

    /**
     * Best-effort logcat mirror.
     *
     * Notes:
     * - This is intentionally lightweight (no chunking).
     * - Android logcat may truncate very long lines.
     */
    private fun mirrorToLogcat(level: Level, tag: String, message: String, tr: Throwable?) {
        if (!logcatMirrorEnabled) return

        val safeTag = tag.take(64)
        if (tr == null) {
            Log.println(level.logcatPriority, safeTag, message)
        } else {
            Log.println(level.logcatPriority, safeTag, "$message\n${Log.getStackTraceString(tr)}")
        }
    }

    private fun write(level: Level, tag: String, message: String, tr: Throwable?) {
        // Mirror to logcat even if the file store is not started yet.
        mirrorToLogcat(level, tag, message, tr)

        // File logging requires the actor to be running.
        if (!started.get() || !actorLaunched.get()) return

        val line = formatLine(level, tag, message, tr)
        val forceFlush = (level == Level.W || level == Level.E)
        ch.trySend(Cmd.Write(line, forceFlush = forceFlush))
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

    private fun utcIso8601(): String = ISO_FMT.format(Instant.now())
    private fun utcStamp(): String = STAMP_FMT.format(Instant.now())

    private fun safeSegment(s: String): String =
        s.trim().ifBlank { "wm" }
            .replace(Regex("""[^A-Za-z0-9_\-\.]+"""), "_")
            .take(24)

    private fun safeToken(s: String, max: Int): String {
        val cleaned = s.trim()
            .replace("__", "_") // keep Worker extraction safe
            .replace(Regex("""[^A-Za-z0-9_\-\.]+"""), "_")
            .trim('_')
        return cleaned.ifBlank { "na" }.take(max)
    }

    private fun buildRuntimeLogFileName(stamp: String, pid: Int, seq: Long): String {
        val fmt = fileNameFormat
        val buildTag = if (BuildConfig.DEBUG) "dbg" else "rel"
        val appShort = safeToken(BuildConfig.APPLICATION_ID.substringAfterLast('.'), 24)
        val ver = safeToken(BuildConfig.VERSION_NAME, 20)
        val code = BuildConfig.VERSION_CODE.toString()
        val sdk = Build.VERSION.SDK_INT.toString()

        val parts = when (fmt) {
            FileNameFormat.LEGACY -> listOf(
                "rtlog",
                stamp,
                "pid$pid",
                "seq$seq"
            )

            FileNameFormat.RICH -> listOf(
                "rtlog",
                stamp,
                buildTag,
                appShort,
                "v$ver",
                "c$code",
                "sdk$sdk",
                "pid$pid",
                "seq$seq"
            )

            FileNameFormat.SHORT -> listOf(
                "rtlog",
                stamp,
                "pid$pid",
                "s$seq"
            )
        }

        val base = parts.joinToString("_") { safeToken(it, 48) }
        var name = "$base.log"

        if (name.length > MAX_FILE_NAME_CHARS) {
            // Trim from the middle while keeping prefix/suffix meaningful.
            val keepHead = 120
            val keepTail = 40
            val head = name.take(keepHead)
            val tail = name.takeLast(keepTail)
            name = (head + "_tr_" + tail).take(MAX_FILE_NAME_CHARS)
        }

        // Final safety: do not allow "__" in source file name.
        name = name.replace("__", "_")
        return name
    }

    private suspend fun snapshotRotateForZip(reason: String): String? {
        if (!started.get() || !actorLaunched.get()) return null
        val ack = CompletableDeferred<String?>()
        runCatching { ch.send(Cmd.RotateAck("zip_snapshot:${safeSegment(reason)}", ack)) }.onFailure { return null }
        val excludeName = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { ack.await() }
        if (excludeName == null) Log.w(TAG, "snapshotRotateForZip: timed out (best-effort).")
        flush()
        return excludeName
    }

    private suspend fun snapshotRotateForPlainUpload(reason: String): String? {
        if (!started.get() || !actorLaunched.get()) return null
        val ack = CompletableDeferred<String?>()
        runCatching { ch.send(Cmd.RotateAck("plain_snapshot:${safeSegment(reason)}", ack)) }.onFailure { return null }
        val excludeName = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { ack.await() }
        if (excludeName == null) Log.w(TAG, "snapshotRotateForPlainUpload: timed out (best-effort).")
        flush()
        return excludeName
    }

    private fun openWriterIfNeededLocked(why: String) {
        synchronized(fileLock) {
            if (activeWriter != null && activeFile != null && activeStream != null) return

            val ctx = appContext ?: return
            val dir = rootDir
                ?: File(ctx.filesDir, "$DIR_DIAGNOSTICS/$DIR_RUNTIME_LOGS").apply { mkdirs() }
                    .also { rootDir = it }

            val stamp = utcStamp()
            val pid = Process.myPid()
            val s = seq.incrementAndGet()

            val fileName = buildRuntimeLogFileName(stamp = stamp, pid = pid, seq = s)
            val f = File(dir, fileName)

            val os = FileOutputStream(f, true)
            val w = BufferedWriter(OutputStreamWriter(os, UTF8), 32 * 1024)

            activeFile = f
            activeStream = os
            activeWriter = w
            activeBytes = f.length().coerceAtLeast(0L)

            val now = SystemClock.uptimeMillis()
            lastSoftFlushUptimeMs = now
            lastSoftFlushBytes = activeBytes

            Log.d(TAG, "openWriter: $why -> ${f.absolutePath} (bytes=$activeBytes format=$fileNameFormat)")
        }
    }

    private fun writeHeaderLocked() {
        synchronized(fileLock) {
            val w = activeWriter ?: return
            val header = buildString {
                appendLine("=== RuntimeLogStore Header ===")
                appendLine("ts=${utcIso8601()}")
                appendLine("fileNameFormat=$fileNameFormat")
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

            val now = SystemClock.uptimeMillis()
            lastSoftFlushUptimeMs = now
            lastSoftFlushBytes = activeBytes
        }
    }

    private fun writeLineLocked(line: String, forceFlush: Boolean) {
        synchronized(fileLock) {
            val w = activeWriter ?: return
            w.write(line)
            activeBytes += line.toByteArray(UTF8).size.toLong()

            val now = SystemClock.uptimeMillis()
            val needSoftFlush =
                (now - lastSoftFlushUptimeMs) >= SOFT_FLUSH_INTERVAL_MS ||
                        (activeBytes - lastSoftFlushBytes) >= SOFT_FLUSH_BYTES

            if (forceFlush || needSoftFlush) {
                runCatching { w.flush() }
                lastSoftFlushUptimeMs = now
                lastSoftFlushBytes = activeBytes
            }
        }
    }

    private fun flushLocked() {
        synchronized(fileLock) {
            runCatching { activeWriter?.flush() }
            runCatching { activeStream?.fd?.sync() }
            val now = SystemClock.uptimeMillis()
            lastSoftFlushUptimeMs = now
            lastSoftFlushBytes = activeBytes
        }
    }

    private fun rotateLocked(reason: String) {
        synchronized(fileLock) {
            runCatching { activeWriter?.flush() }
            runCatching { activeStream?.fd?.sync() }
            runCatching { activeWriter?.close() }
            runCatching { activeStream?.close() }

            activeWriter = null
            activeStream = null
            activeFile = null
            activeBytes = 0L
            lastSoftFlushUptimeMs = 0L
            lastSoftFlushBytes = 0L
        }

        openWriterIfNeededLocked("rotate:$reason")
        runCatching { writeHeaderLocked() }

        synchronized(fileLock) {
            val w = activeWriter ?: return
            val line = "${utcIso8601()} I/RuntimeLogStore [rtlog-io] --- rotated (reason=$reason) ---\n"
            w.write(line)
            w.flush()
            activeBytes += line.toByteArray(UTF8).size.toLong()

            val now = SystemClock.uptimeMillis()
            lastSoftFlushUptimeMs = now
            lastSoftFlushBytes = activeBytes
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

            val keep = files.take(maxFiles)
            val dropByCount = files.drop(maxFiles)
            for (f in dropByCount) runCatching { f.delete() }

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
        obj.put("kind", "runtime_logs_zip")
        obj.put("reason", reason)
        obj.put("ts_utc", utcIso8601())
        obj.put("fileNameFormat", fileNameFormat.name)
        obj.put("appId", BuildConfig.APPLICATION_ID)
        obj.put("versionName", BuildConfig.VERSION_NAME)
        obj.put("versionCode", BuildConfig.VERSION_CODE)
        obj.put("debug", BuildConfig.DEBUG)
        obj.put("pid", Process.myPid())
        obj.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        obj.put("sdk", Build.VERSION.SDK_INT)

        val arr = JSONArray()
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

    private fun buildPlainManifestJson(
        reason: String,
        excludedActive: String?,
        sourceDir: File,
        pickedSources: List<File>,
        preparedOutputs: List<File>,
        skipped: List<String>
    ): String {
        val obj = JSONObject()
        obj.put("kind", "runtime_logs_plain")
        obj.put("reason", reason)
        obj.put("ts_utc", utcIso8601())
        obj.put("fileNameFormat", fileNameFormat.name)
        obj.put("appId", BuildConfig.APPLICATION_ID)
        obj.put("versionName", BuildConfig.VERSION_NAME)
        obj.put("versionCode", BuildConfig.VERSION_CODE)
        obj.put("debug", BuildConfig.DEBUG)
        obj.put("pid", Process.myPid())
        obj.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        obj.put("sdk", Build.VERSION.SDK_INT)
        obj.put("excluded_active", excludedActive ?: JSONObject.NULL)
        obj.put("source_dir", sourceDir.absolutePath)

        val srcArr = JSONArray()
        for (f in pickedSources) {
            val it = JSONObject()
            it.put("name", f.name)
            it.put("bytes", f.length().coerceAtLeast(0L))
            it.put("mtime", f.lastModified())
            srcArr.put(it)
        }
        obj.put("picked_sources", srcArr)

        val outArr = JSONArray()
        for (f in preparedOutputs) {
            val it = JSONObject()
            it.put("name", f.name)
            it.put("bytes", f.length().coerceAtLeast(0L))
            it.put("mtime", f.lastModified())
            it.put("path", f.absolutePath)
            outArr.put(it)
        }
        obj.put("prepared_outputs", outArr)

        val sk = JSONArray()
        for (s in skipped) sk.put(s)
        obj.put("skipped", sk)

        return obj.toString(2)
    }

    private fun copyFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        src.inputStream().use { ins ->
            FileOutputStream(dst).use { outs ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    outs.write(buf, 0, n)
                }
                outs.fd.sync()
            }
        }
    }
}