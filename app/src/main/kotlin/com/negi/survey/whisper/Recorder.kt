/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: Recorder.kt
 *  Author: Shu Ishizuki
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

@file:Suppress("KotlinJdkLibUsage")

package com.negi.survey.whisper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Recorder — High-reliability WAV recorder.
 *
 * Key rule:
 * - Control operations (start/stop state machine) run on a dedicated single thread.
 * - The blocking AudioRecord.read() loop MUST NOT run on the same single thread,
 *   otherwise stopRecording() cannot execute and you will deadlock.
 */
class Recorder(
    private val context: Context,
    private val onError: (Exception) -> Unit
) : Closeable {

    // -------------------------------------------------------------------------
    // Control dispatcher (single thread)
    // -------------------------------------------------------------------------
    private val controlExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RecorderThread").apply { priority = Thread.NORM_PRIORITY }
    }
    private val controlDispatcher: ExecutorCoroutineDispatcher = controlExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(controlDispatcher + SupervisorJob())

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------
    private var writerJob: Job? = null
    private val activeRecorder = AtomicReference<AudioRecord?>(null)
    private var tempPcm: File? = null
    private var targetWav: File? = null
    private var cfg: Config? = null

    private enum class State { Idle, Starting, Recording, Stopping }
    private val state = AtomicReference(State.Idle)

    /** Returns true if recording or transitioning between recording states. */
    fun isActive(): Boolean = when (state.get()) {
        State.Starting, State.Recording, State.Stopping -> true
        else -> false
    }

    // -------------------------------------------------------------------------
    // Start
    // -------------------------------------------------------------------------
    /**
     * Starts recording asynchronously.
     *
     * @param output Target WAV file (will be overwritten on stop)
     * @param rates Prioritized sample rate candidates
     */
    fun startRecording(
        output: File,
        rates: IntArray = intArrayOf(16_000, 48_000, 44_100)
    ) {
        if (!state.compareAndSet(State.Idle, State.Starting)) {
            Log.w(TAG, "startRecording ignored: current=${state.get()}")
            return
        }
        targetWav = output

        scope.launch {
            try {
                checkPermission()

                val conf = findConfig(rates) ?: error("No valid AudioRecord config")
                cfg = conf

                val rec = buildRecorder(conf)
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    error("AudioRecord init failed")
                }

                // If stop was requested while initializing, abort safely.
                if (state.get() != State.Starting) {
                    Log.w(TAG, "startRecording aborted: state=${state.get()}")
                    runCatching { rec.release() }
                    cleanup()
                    state.set(State.Idle)
                    return@launch
                }

                activeRecorder.set(rec)
                tempPcm = File.createTempFile("rec_", ".pcm", context.cacheDir)
                val tmp = tempPcm ?: error("Temp PCM file creation failed")

                // Create writer job first (LAZY) to avoid race where stop happens before writerJob is visible.
                val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    FileOutputStream(tmp).use { fos ->
                        BufferedOutputStream(fos, conf.bufferSize).use { bos ->
                            val shortBuf = ShortArray(conf.bufferSize / 2)
                            val byteBuf = ByteArray(shortBuf.size * 2)
                            var started = false

                            try {
                                // If we are no longer in Recording, do not start AudioRecord.
                                if (this@Recorder.state.get() != State.Recording) {
                                    Log.w(TAG, "writer: not in Recording state, skipping start (state=${this@Recorder.state.get()})")
                                    return@use
                                }

                                rec.startRecording()
                                started = true
                                Log.i(TAG, "🎙 start ${conf.sampleRate}Hz buf=${conf.bufferSize}")

                                // Use both state AND coroutine cancellation to control the loop.
                                while (isActive && this@Recorder.state.get() == State.Recording) {
                                    val n = rec.read(shortBuf, 0, shortBuf.size)
                                    if (n <= 0) {
                                        if (n < 0) Log.w(TAG, "read() error=$n → break")
                                        break
                                    }

                                    var j = 0
                                    for (i in 0 until n) {
                                        val v = shortBuf[i].toInt()
                                        byteBuf[j++] = (v and 0xFF).toByte()
                                        byteBuf[j++] = ((v ushr 8) and 0xFF).toByte()
                                    }
                                    bos.write(byteBuf, 0, n * 2)
                                }

                                bos.flush()
                            } finally {
                                // Best-effort stop/release (may already be stopped by stopRecording()).
                                if (started) runCatching { rec.stop() }
                                runCatching { rec.release() }
                                activeRecorder.compareAndSet(rec, null)
                                Log.i(TAG, "🎙 stopped & released (writer exit)")
                            }
                        }
                    }
                }

                writerJob = job

                // Commit Starting -> Recording atomically. If it fails, stop was requested.
                if (!state.compareAndSet(State.Starting, State.Recording)) {
                    Log.w(TAG, "startRecording: state changed before Recording (state=${state.get()}), aborting")
                    runCatching { job.cancel() }
                    runCatching { rec.release() }
                    activeRecorder.compareAndSet(rec, null)
                    cleanup()
                    state.set(State.Idle)
                    return@launch
                }

                job.start()
            } catch (e: Exception) {
                Log.e(TAG, "startRecording failed", e)
                onError(e)
                state.set(State.Idle)

                activeRecorder.getAndSet(null)?.let {
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }

                runCatching { writerJob?.cancel() }
                cleanup()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Stop
    // -------------------------------------------------------------------------
    /**
     * Stops recording and finalizes the WAV file.
     *
     * Runs on RecorderThread (control dispatcher) so state transitions are serialized.
     */
    suspend fun stopRecording(): Unit = withContext(controlDispatcher) {
        Log.d(TAG, "stopRecording() invoked (state=${state.get()})")

        val s = state.get()
        if (s == State.Idle) {
            Log.w(TAG, "stopRecording: already idle")
            return@withContext
        }

        state.set(State.Stopping)

        // 1) Unblock AudioRecord.read() by stopping the active recorder (do NOT release here).
        activeRecorder.get()?.let { rec ->
            Log.d(TAG, "Calling AudioRecord.stop() to unblock read() ...")
            runCatching { rec.stop() }.onFailure { Log.w(TAG, "stop() failed: $it") }
        }

        // 2) Cancel + join writer job (bounded).
        val job = writerJob
        runCatching { job?.cancel() }

        Log.d(TAG, "Joining writer job ...")
        val joined = withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) {
            job?.join()
            true
        } ?: false

        if (!joined) {
            Log.w(TAG, "Writer job join timed out (${STOP_JOIN_TIMEOUT_MS}ms). Forcing release.")
            activeRecorder.getAndSet(null)?.let { rec ->
                runCatching { rec.release() }
            }
        } else {
            Log.d(TAG, "Writer job joined successfully")
        }

        // 3) Snapshot pieces before cleanup.
        val pcm = tempPcm
        val wav = targetWav
        val c = cfg
        Log.d(TAG, "post-join: pcm=$pcm wav=$wav cfg=$c")

        if (pcm == null || wav == null || c == null) {
            Log.w(TAG, "stopRecording: missing pieces (aborting WAV write)")
            cleanup()
            state.set(State.Idle)
            return@withContext
        }

        try {
            // 4) Merge PCM payload with valid WAV header.
            // Small files are OK here; if you ever record long audio, move this to Dispatchers.IO.
            writeWavFromPcm(pcm, wav, c.sampleRate, 1, 16)
            Log.d(TAG, "WAV header written")

            if (wav.length() <= 44L) {
                Log.w(TAG, "WAV too short (<=44 bytes), deleting: ${wav.path}")
                wav.delete()
            } else {
                Log.i(TAG, "✅ WAV finalized: ${wav.path} (${wav.length()} bytes)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording error", e)
            onError(e)
        } finally {
            cleanup()
            state.set(State.Idle)
            Log.d(TAG, "Cleanup complete, state=Idle")
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------
    /** Writes RIFF/WAVE header + PCM data → valid WAV file. */
    private fun writeWavFromPcm(pcm: File, wav: File, rate: Int, ch: Int, bits: Int) {
        val size = pcm.length().toInt().coerceAtLeast(0)
        val byteRate = rate * ch * bits / 8
        val blockAlign = (ch * bits / 8).toShort()

        DataOutputStream(BufferedOutputStream(FileOutputStream(wav))).use { out ->
            out.writeBytes("RIFF"); out.writeIntLE(size + 36)
            out.writeBytes("WAVE")
            out.writeBytes("fmt "); out.writeIntLE(16)
            out.writeShortLE(1); out.writeShortLE(ch)
            out.writeIntLE(rate); out.writeIntLE(byteRate)
            out.writeShortLE(blockAlign.toInt()); out.writeShortLE(bits)
            out.writeBytes("data"); out.writeIntLE(size)
            FileInputStream(pcm).use { it.copyTo(out) }
        }
    }

    private fun DataOutputStream.writeShortLE(v: Int) {
        write(v and 0xFF); write((v ushr 8) and 0xFF)
    }

    private fun DataOutputStream.writeIntLE(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 24) and 0xFF)
    }

    /** Deletes temporary files and resets cached config. */
    private fun cleanup() {
        runCatching { tempPcm?.delete() }
        tempPcm = null
        targetWav = null
        cfg = null
        writerJob = null
    }

    /** Validates microphone presence and permission. */
    private fun checkPermission() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
            error("No microphone present on device")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }
    }

    /** Audio configuration container. */
    private data class Config(
        val sampleRate: Int,
        val channelMask: Int,
        val format: Int,
        val bufferSize: Int
    )

    /**
     * Iterates candidate sample rates, returns first working AudioRecord config.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun findConfig(rates: IntArray): Config? {
        val ch = AudioFormat.CHANNEL_IN_MONO
        val fmt = AudioFormat.ENCODING_PCM_16BIT

        for (r in rates) {
            val minBuf = AudioRecord.getMinBufferSize(r, ch, fmt)
            if (minBuf <= 0) continue

            val buf = (minBuf.coerceAtLeast(2048) / 2) * 2

            val test = AudioRecord(MediaRecorder.AudioSource.MIC, r, ch, fmt, buf)
            val ok = test.state == AudioRecord.STATE_INITIALIZED
            test.release()

            if (ok) return Config(r, ch, fmt, buf)
        }
        return null
    }

    /** Builds a new AudioRecord instance. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun buildRecorder(c: Config) = AudioRecord(
        MediaRecorder.AudioSource.MIC, c.sampleRate, c.channelMask, c.format, c.bufferSize
    )

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------
    override fun close() {
        runBlocking {
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
                if (isActive()) stopRecording()
            }
        }
        runCatching { scope.cancel() }
        runCatching { controlDispatcher.close() }
        runCatching { controlExecutor.shutdownNow() }
    }

    companion object {
        private const val TAG = "Recorder"
        private const val STOP_JOIN_TIMEOUT_MS = 3_000L
        private const val CLOSE_TIMEOUT_MS = 4_500L
    }
}
