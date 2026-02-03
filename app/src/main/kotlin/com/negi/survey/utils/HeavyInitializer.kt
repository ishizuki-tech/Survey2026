/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: HeavyInitializer.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  One-shot, single-flight initializer for large model files or heavy assets.
 *  Handles download, resume, integrity check, and atomic replacement with
 *  coroutine cancellation propagation and friendly error reporting.
 *
 *  Key properties:
 *  ---------------------------------------------------------------------
 *  - Single-flight:
 *      Concurrent callers share the same in-flight Deferred<Result<File>>.
 *  - Resume:
 *      A partial ".tmp" file is preserved across cancellations/timeouts
 *      when forceFresh=false, allowing a subsequent call to continue.
 *  - Integrity:
 *      If Content-Length is known, final size must match exactly.
 *  - Replacement:
 *      Uses rename within the same directory when possible; falls back to
 *      a stream copy if rename fails.
 * =====================================================================
 */

package com.negi.survey.utils

import android.content.Context
import android.util.Log
import com.negi.survey.BuildConfig
import com.negi.survey.net.HttpUrlFileDownloader
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout

/**
 * Manages single-flight initialization for heavy assets (e.g., model download).
 *
 * This object is designed to be called from ViewModels or other lifecycle-
 * aware layers. The "owner" caller performs the work; all other callers wait
 * on the same [CompletableDeferred].
 */
object HeavyInitializer {

    private const val TAG = "HeavyInitializer"

    /**
     * A small safety buffer to reduce the risk of out-of-space errors during
     * filesystem metadata updates or temporary allocations.
     */
    private const val FREE_SPACE_MARGIN_BYTES = 64L * 1024L * 1024L // 64 MiB

    /**
     * Upper bound for manual redirect following on HEAD requests.
     */
    private const val MAX_REDIRECTS = 10

    /**
     * Tracks a single in-flight initialization for all concurrent callers.
     */
    private val inFlight = AtomicReference<CompletableDeferred<Result<File>>?>(null)

    /**
     * The Job of the "owner" coroutine currently performing the initialization.
     *
     * cancel() will cancel this Job.
     */
    @Volatile
    private var runningJob: Job? = null

    /**
     * Returns true if a download/initialization is currently in progress.
     */
    @JvmStatic
    fun isInFlight(): Boolean = inFlight.get() != null

    /**
     * Checks if a valid file already exists and (when possible) matches remote size.
     *
     * This is a synchronous best-effort probe used for quick gate checks.
     */
    fun isAlreadyComplete(
        context: Context,
        modelUrl: String,
        hfToken: String?,
        fileName: String
    ): Boolean {
        val dst = File(context.filesDir, fileName)
        if (!dst.exists() || !dst.isFile || dst.length() <= 0L) return false

        val token = hfToken?.takeIf { it.isNotBlank() }
        val remoteLen = runCatching { headContentLengthForVerify(modelUrl, token) }.getOrNull()

        // Strict match when Content-Length is known.
        return when {
            remoteLen != null -> remoteLen == dst.length()
            else -> true // Len unknown: treat existing non-empty file as usable.
        }
    }

    /**
     * Ensure that the model or asset is initialized, downloading it if needed.
     *
     * @param context App context used for storage resolution.
     * @param modelUrl Remote model URL (supports Hugging Face gated assets).
     * @param hfToken Optional Hugging Face token.
     * @param fileName Local target file name under filesDir.
     * @param timeoutMs Hard timeout for the download phase.
     * @param forceFresh If true, ignore and delete cached final/tmp files.
     * @param onProgress Callback bridging downloaded bytes and total size.
     */
    suspend fun ensureInitialized(
        context: Context,
        modelUrl: String,
        hfToken: String?,
        fileName: String,
        timeoutMs: Long,
        forceFresh: Boolean,
        onProgress: (downloaded: Long, total: Long?) -> Unit
    ): Result<File> {
        // Fast path: someone else is already doing the work.
        inFlight.get()?.let { return it.await() }

        // Attempt to become the single-flight owner.
        val deferred = CompletableDeferred<Result<File>>()
        if (!inFlight.compareAndSet(null, deferred)) {
            return inFlight.get()!!.await()
        }

        // We are the owner.
        val ownerJob = currentCoroutineContext()[Job]
        runningJob = ownerJob

        val app = context.applicationContext
        val token = hfToken?.takeIf { it.isNotBlank() }

        val dir = app.filesDir
        val finalFile = File(dir, fileName)
        val tmpFile = File(dir, "$fileName.tmp") // persistent partial for resume

        try {
            if (forceFresh) {
                runCatching { tmpFile.delete() }
                runCatching { finalFile.delete() }
            }

            val downloader = HttpUrlFileDownloader(
                hfToken = token,
                debugLogs = BuildConfig.DEBUG
            )

            val remoteLen = runCatching { headContentLength(modelUrl, token) }
                .onFailure { e -> Log.w(TAG, "HEAD content-length failed (non-fatal)", e) }
                .getOrNull()
                ?.takeIf { it > 0L }

            // Short-circuit if the final file is already valid.
            if (!forceFresh && finalFile.exists() && finalFile.length() > 0L) {
                val ok = when {
                    remoteLen != null -> finalFile.length() == remoteLen
                    else -> true
                }
                if (ok) {
                    val len = finalFile.length()
                    onProgress(len, remoteLen ?: len)
                    deferred.complete(Result.success(finalFile))
                    return deferred.await()
                }
            }

            // Resume logic for tmp file.
            if (!forceFresh && tmpFile.exists() && tmpFile.length() > 0L) {
                if (remoteLen != null && tmpFile.length() > remoteLen) {
                    Log.w(
                        TAG,
                        "tmp is larger than remoteLen -> discarding tmp " +
                                "(tmp=${tmpFile.length()}, remote=$remoteLen)"
                    )
                    runCatching { tmpFile.delete() }
                }
            }

            // Ensure tmp exists (do not delete when resuming).
            if (!tmpFile.exists()) {
                tmpFile.parentFile?.mkdirs()
                tmpFile.createNewFile()
            }

            val tmpExisting = tmpFile.takeIf { it.exists() }?.length() ?: 0L

            // Free-space validation based on remaining bytes to write into tmp.
            if (remoteLen != null) {
                val remaining = max(0L, remoteLen - tmpExisting)
                val needed = remaining + FREE_SPACE_MARGIN_BYTES
                if (dir.usableSpace < needed) {
                    val msg =
                        "Not enough free space. " +
                                "needed=${needed}B (remaining=$remaining + margin), " +
                                "usable=${dir.usableSpace}B"
                    deferred.complete(Result.failure(IOException(msg)))
                    return deferred.await()
                }
            }

            // Download with timeout.
            withTimeout(timeoutMs) {
                val progressBridge: (Long, Long?) -> Unit = { cur, total ->
                    // Respect owner coroutine cancellation.
                    if (ownerJob?.isActive == false) {
                        throw CancellationException("canceled by caller")
                    }
                    onProgress(cur, total)
                }

                // Let downloader decide whether to resume based on tmp length.
                downloader.downloadToFile(modelUrl, tmpFile, progressBridge)
            }

            // Integrity check when we know the expected size.
            if (remoteLen != null) {
                val got = tmpFile.length()
                if (got != remoteLen) {
                    throw IOException(
                        "Downloaded size mismatch. expected=$remoteLen, got=$got"
                    )
                }
            }

            // Replace final file.
            replaceFinalAtomic(tmpFile, finalFile)

            val outLen = finalFile.length()
            onProgress(outLen, remoteLen ?: outLen)

            deferred.complete(Result.success(finalFile))

        } catch (ce: CancellationException) {
            // Cancellation policy:
            // - Preserve tmp for resume unless forceFresh was requested.
            Log.w(TAG, "ensureInitialized: cancelled", ce)
            if (forceFresh) {
                runCatching { tmpFile.delete() }
            }
            deferred.complete(Result.failure(IOException("Canceled", ce)))

        } catch (te: TimeoutCancellationException) {
            // Timeout policy:
            // - Preserve tmp for resume unless forceFresh was requested.
            Log.w(TAG, "ensureInitialized: timeout after ${timeoutMs}ms", te)
            if (forceFresh) {
                runCatching { tmpFile.delete() }
            }
            deferred.complete(Result.failure(IOException("Timeout ($timeoutMs ms)", te)))

        } catch (t: Throwable) {
            val msg = userFriendlyMessage(t)
            Log.w(TAG, "Initialization error: $msg", t)

            // On unexpected errors, we usually discard tmp because it may be corrupted
            // in ways the downloader cannot resume from safely.
            runCatching { tmpFile.delete() }

            deferred.complete(Result.failure(IOException(msg, t)))

        } finally {
            // Clear in-flight owner state only when this deferred is the current one.
            if (inFlight.get() === deferred) {
                inFlight.set(null)
            }
            runningJob = null
        }

        return deferred.await()
    }

    /**
     * Cancels any running initialization.
     *
     * This requests cancellation of the owner coroutine that is currently
     * performing the download. Awaiters will receive a failure Result.
     */
    suspend fun cancel() {
        runningJob?.cancel(CancellationException("canceled by user"))
        Log.w(TAG, "Initialization cancel requested.")
    }

    /**
     * Clears all in-flight and debug state.
     *
     * This is intended for development/testing only.
     */
    fun resetForDebug() {
        inFlight.getAndSet(null)?.cancel(CancellationException("resetForDebug"))
        runningJob?.cancel(CancellationException("resetForDebug"))
        runningJob = null
        Log.w(TAG, "resetForDebug(): cleared in-flight state")
    }

    // ---------------------------------------------------------------------
    // HEAD probe for strict content-length validation
    // ---------------------------------------------------------------------

    private fun headContentLength(srcUrl: String, hfToken: String?): Long? =
        headContentLengthForVerify(srcUrl, hfToken)

    /**
     * HEAD request with manual redirect handling.
     *
     * Notes:
     * - We disable gzip by forcing Accept-Encoding=identity to preserve
     *   a stable Content-Length when servers honor it.
     * - For Hugging Face, we attach Bearer token when provided.
     */
    private fun headContentLengthForVerify(srcUrl: String, hfToken: String?): Long? {
        var current = srcUrl

        repeat(MAX_REDIRECTS) {
            val u = URL(current)
            val conn = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "SurveyNav/1.0 (Android)")
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("Accept-Encoding", "identity")
                if (
                    (u.host == "huggingface.co" || u.host.endsWith(".huggingface.co")) &&
                    !hfToken.isNullOrBlank()
                ) {
                    setRequestProperty("Authorization", "Bearer $hfToken")
                }
            }

            try {
                val code = conn.responseCode

                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return null
                    current = URL(u, loc).toString()
                    return@repeat
                }

                if (code !in 200..299) return null

                val len = conn.getHeaderFieldLong("Content-Length", -1L)
                return len.takeIf { it >= 0L }

            } finally {
                conn.disconnect()
            }
        }

        return null
    }

    // ---------------------------------------------------------------------
    // Error messaging
    // ---------------------------------------------------------------------

    private fun userFriendlyMessage(t: Throwable): String {
        val raw = t.message ?: t::class.java.simpleName
        val s = raw.lowercase()

        return when {
            "unauthorized" in s || "401" in s -> "Authorization failed (HF token?)"
            "forbidden" in s || "403" in s -> "Access denied (token/permissions?)"
            "timeout" in s -> "Network timeout"
            "space" in s -> "Not enough free space"
            "content-range" in s || "416" in s || "range" in s ->
                "Resume failed (server refused range)"
            "unknown host" in s || "dns" in s -> "Unknown host (check connectivity)"
            else -> raw
        }
    }

    // ---------------------------------------------------------------------
    // Replacement
    // ---------------------------------------------------------------------

    /**
     * Approximates atomic replace within the same directory.
     *
     * Strategy:
     * 1) Delete destination if it exists.
     * 2) Try rename(tmp -> dst).
     * 3) If rename fails, fall back to stream copy.
     *
     * We assume tmp and dst are under the same parent directory.
     */
    private fun replaceFinalAtomic(tmp: File, dst: File) {
        if (!tmp.exists() || tmp.length() <= 0L) {
            throw IOException("Temp file missing or empty: ${tmp.absolutePath}")
        }

        if (dst.exists() && !dst.delete()) {
            Log.w(TAG, "replaceFinalAtomic: failed to delete existing ${dst.absolutePath}")
        }

        // Attempt fast path.
        if (tmp.renameTo(dst)) {
            return
        }

        Log.w(TAG, "replaceFinalAtomic: rename failed, falling back to copy")

        // Fallback copy.
        try {
            tmp.inputStream().use { input ->
                dst.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            throw IOException("replaceFinalAtomic: copy fallback failed", e)
        } finally {
            runCatching { tmp.delete() }
        }

        if (!dst.exists() || dst.length() <= 0L) {
            throw IOException("replaceFinalAtomic: destination invalid after copy: ${dst.absolutePath}")
        }
    }
}
