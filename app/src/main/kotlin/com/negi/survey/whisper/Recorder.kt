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
import kotlinx.coroutines.CoroutineScope
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
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Recorder — High-reliability single-threaded WAV recorder.
 *
 * ## Overview
 * Provides deterministic, coroutine-based control over the Android `AudioRecord`
 * pipeline. It records raw PCM into a temporary file and safely finalizes a
 * valid RIFF/WAV file upon stopping.
 *
 * ## State Machine
 * ```
 *   Idle → Starting → Recording → Stopping → Idle
 * ```
 *
 * ## Technical Highlights
 * - Dedicated single thread + coroutine scope ensures sequential audio operations.
 * - Writes temporary PCM and rewrites valid RIFF/WAVE header on stop().
 * - Handles rapid Record→Stop taps without race conditions.
 * - Uses atomic state and safe AudioRecord release.
 * - Fully deterministic even under cancellation or early stop.
 *
 * ## Typical Lifecycle
 * ```kotlin
 * val recorder = Recorder(context) { e -> Log.e("Recorder", "Error", e) }
 * recorder.startRecording(File(...))
 * recorder.stopRecording()
 * recorder.close()
 * ```
 *
 * @constructor
 * @param context Application context used for cache directory
 * @param onError Callback invoked on any recoverable or fatal exception
 */
class Recorder(
    private val context: Context,
    private val onError: (Exception) -> Unit
) : Closeable {

    // -------------------------------------------------------------------------
    // Dedicated single-thread execution context
    // -------------------------------------------------------------------------
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RecorderThread").apply { priority = Thread.NORM_PRIORITY }
    }
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

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
    // Start Recording
    // -------------------------------------------------------------------------
    /**
     * Starts recording in a deterministic, race-free manner.
     * Idempotent — calling during active/starting state is ignored.
     *
     * @param output Target WAV file (will be overwritten)
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

                // Abort early if user stopped before init finished
                if (state.get() != State.Starting) {
                    Log.w(TAG, "startRecording aborted: premature stop, state=${state.get()}")
                    runCatching { rec.release() }
                    cleanup()
                    state.set(State.Idle)
                    return@launch
                }

                activeRecorder.set(rec)
                tempPcm = File.createTempFile("rec_", ".pcm", context.cacheDir)
                val tmp = tempPcm ?: error("Temp PCM file creation failed")

                state.set(State.Recording)

                // Writer coroutine: blocking read() loop → PCM write → stop/release in finally
                writerJob = scope.launch {
                    FileOutputStream(tmp).use { fos ->
                        val bos = BufferedOutputStream(fos, conf.bufferSize)
                        val shortBuf = ShortArray(conf.bufferSize / 2)
                        val byteBuf = ByteArray(shortBuf.size * 2)

                        try {
                            rec.startRecording()
                            Log.i(TAG, "🎙 start ${conf.sampleRate}Hz buf=${conf.bufferSize}")

                            while (isActive) {
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
                            runCatching { rec.stop() }
                            runCatching { rec.release() }
                            activeRecorder.compareAndSet(rec, null)
                            Log.i(TAG, "🎙 stopped & released (writer exit)")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startRecording failed", e)
                onError(e)
                state.set(State.Idle)

                // Best-effort release if any recorder exists
                activeRecorder.getAndSet(null)?.let {
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }

                cleanup()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Stop Recording
    // -------------------------------------------------------------------------
    /**
     * Stops recording and finalizes the WAV file.
     *
     * ### Steps
     * 1. Transition state → Stopping
     * 2. Unblock `AudioRecord.read()` via `stop()` (release is done by writer finally)
     * 3. Cancel & join writer coroutine (with timeout)
     * 4. Write valid RIFF/WAVE header to target file (IO)
     * 5. Cleanup temp PCM and reset state
     */
    suspend fun stopRecording() = withContext(scope.coroutineContext) {
        Log.d(TAG, "stopRecording() invoked (state=${state.get()})")

        val s = state.get()
        if (s == State.Idle) {
            Log.w(TAG, "stopRecording: already idle")
            return@withContext
        }

        state.set(State.Stopping)

        // 1) Unblock AudioRecord.read() by stopping the active recorder (do NOT release here)
        val rec = activeRecorder.get()
        if (rec != null) {
            Log.d(TAG, "Calling AudioRecord.stop() to unblock read() ...")
            runCatching { rec.stop() }.onFailure { Log.w(TAG, "stop() failed: $it") }
        }

        // 2) Cancel + join writer job (bounded)
        val job = writerJob
        runCatching { job?.cancel() }

        Log.d(TAG, "Joining writer job ...")
        val joined = withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) {
            job?.join()
            true
        } ?: false

        if (!joined) {
            Log.w(TAG, "Writer job join timed out (${STOP_JOIN_TIMEOUT_MS}ms). Forcing release.")
            activeRecorder.getAndSet(null)?.let {
                runCatching { it.release() }
            }
        } else {
            Log.d(TAG, "Writer job joined successfully")
        }

        // 3) Snapshot pieces before cleanup
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
            // 4) Merge PCM payload with valid WAV header (IO)
            withContext(Dispatchers.IO) {
                writeWavFromPcm(pcm, wav, c.sampleRate, 1, 16)
            }
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
    // Internal Helpers
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
     * Uses minimal buffer to reduce latency for short clips.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun findConfig(rates: IntArray): Config? {
        val ch = AudioFormat.CHANNEL_IN_MONO
        val fmt = AudioFormat.ENCODING_PCM_16BIT

        for (r in rates) {
            val minBuf = AudioRecord.getMinBufferSize(r, ch, fmt)
            if (minBuf <= 0) continue

            // Ensure bufferSize is even and >= 2 bytes to avoid edge cases in shortBuf sizing.
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
    /**
     * Safely stops recording and releases resources.
     * Blocks until cleanup is complete.
     */
    override fun close() {
        runBlocking {
            if (isActive()) stopRecording()
        }
        runCatching { scope.cancel() }
        runCatching { dispatcher.close() }
        runCatching { executor.shutdownNow() }
    }

    companion object {
        private const val TAG = "Recorder"
        private const val STOP_JOIN_TIMEOUT_MS = 3_000L
    }
}
