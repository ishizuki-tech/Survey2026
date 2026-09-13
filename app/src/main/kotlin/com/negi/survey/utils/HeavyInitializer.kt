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
 *  Coordinates preparation of large local model files before LiteRT-LM
 *  initialization. Network transfer details, HTTP validation, resumable
 *  partial files, parallel range downloads, and final promotion are owned by
 *  HttpUrlFileDownloader.
 *
 *  Design goals:
 *  ---------------------------------------------------------------------
 *  - Fast local startup:
 *      A non-empty, previously completed local model is accepted immediately.
 *      Model URLs/filenames should therefore be versioned or forceFresh should
 *      be used when a remote replacement must be fetched.
 *  - Keyed single-flight:
 *      Concurrent callers targeting the same destination share one operation,
 *      while unrelated model files are not accidentally coupled together.
 *  - Resume preservation:
 *      Network failures, timeouts, and normal cancellation preserve resumable
 *      transfer artifacts unless forceFresh=true.
 *  - Structured cancellation:
 *      Owner cancellation propagates as CancellationException. Waiting callers
 *      still receive a failure Result through the shared Deferred.
 *  - Diagnostic timing:
 *      Coarse phase timings are logged without exposing credentials.
 * =====================================================================
 */

package com.negi.survey.utils

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.negi.survey.BuildConfig
import com.negi.survey.net.HttpUrlFileDownloader
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/**
 * Coordinates one model-file preparation operation per destination path.
 *
 * This object intentionally does NOT own LiteRT-LM Engine or Conversation
 * instances. Its responsibility ends when the requested local model file is
 * ready for use.
 */
object HeavyInitializer {

    private const val TAG = "HeavyInitializer"

    /**
     * Upper bound for one stalled network read inside HttpUrlFileDownloader.
     *
     * Coroutine cancellation cannot preempt a blocking HttpURLConnection read
     * immediately. Keeping this lower than the downloader's historical 90 s
     * default bounds the worst-case delay before cancellation becomes visible.
     */
    private const val DOWNLOAD_STALL_TIMEOUT_MS = 45_000

    /** Connection setup timeout passed to HttpUrlFileDownloader. */
    private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 20_000

    /** Timeout for the downloader's initial HEAD/range probe. */
    private const val DOWNLOAD_FIRST_BYTE_TIMEOUT_MS = 30_000

    /** Default number of full transfer attempts inside the downloader. */
    private const val DOWNLOAD_MAX_RETRIES = 3

    /**
     * One in-flight operation for a canonical destination path.
     *
     * The previous implementation had one global Deferred for all files. That
     * could return model A's result to a caller requesting model B. Keying by
     * destination prevents that class of cross-model race.
     */
    private val flights =
        ConcurrentHashMap<String, Flight>()

    /** Shared state for one destination-specific initialization. */
    private class Flight(
        val deferred: CompletableDeferred<Result<File>>,
    ) {
        /** Owner coroutine performing the actual transfer. */
        @Volatile
        var ownerJob: Job? = null
    }

    /** Returns true when any model-file preparation is currently active. */
    @JvmStatic
    fun isInFlight(): Boolean =
        flights.isNotEmpty()

    /**
     * Fast local gate check.
     *
     * IMPORTANT:
     * This method intentionally performs no network I/O. The old implementation
     * issued a synchronous HEAD request, which could block the main thread and
     * added duplicate network probes before HttpUrlFileDownloader performed its
     * own validation.
     *
     * A non-empty local file is treated as complete. Use a versioned filename
     * or call ensureInitialized(..., forceFresh=true) when the remote object is
     * intentionally replaced under the same URL.
     *
     * [modelUrl] and [hfToken] remain in the signature for source compatibility
     * with existing callers.
     */
    fun isAlreadyComplete(
        context: Context,
        @Suppress("UNUSED_PARAMETER")
        modelUrl: String,
        @Suppress("UNUSED_PARAMETER")
        hfToken: String?,
        fileName: String,
    ): Boolean {

        val app =
            context.applicationContext

        val file =
            resolveSafeFileUnder(
                app.filesDir,
                fileName,
            )

        val complete =
            file.exists() &&
                    file.isFile &&
                    file.length() > 0L

        if (complete) {
            Log.d(
                TAG,
                "isAlreadyComplete: local hit " +
                        "file=${file.name} " +
                        "bytes=${file.length()}",
            )
        }

        return complete
    }

    /**
     * Ensures that [fileName] exists locally and is ready for LiteRT-LM.
     *
     * Behavior:
     * - Existing non-empty local files are accepted immediately when
     *   [forceFresh] is false.
     * - Concurrent callers for the same destination share one operation.
     * - HttpUrlFileDownloader owns HTTP probing, authorization, parallel range
     *   transfer, resume validation, free-space checks, and final promotion.
     * - Partial transfer state is preserved on normal failure/cancellation so a
     *   later call can resume instead of redownloading a multi-gigabyte model.
     *
     * @param context Android context; application context is retained only for
     * file resolution.
     * @param modelUrl Remote model URL.
     * @param hfToken Optional Hugging Face bearer token. The token value is
     * never logged.
     * @param fileName Relative destination path under application filesDir.
     * @param timeoutMs Overall transfer timeout. Must be positive.
     * @param forceFresh When true, remove the completed file and all known
     * resumable transfer artifacts before starting.
     * @param onProgress Progress callback. Exceptions thrown by the callback are
     * isolated so UI code cannot abort the network transfer.
     */
    suspend fun ensureInitialized(
        context: Context,
        modelUrl: String,
        hfToken: String?,
        fileName: String,
        timeoutMs: Long,
        forceFresh: Boolean,
        onProgress: (
            downloaded: Long,
            total: Long?,
        ) -> Unit,
    ): Result<File> {

        require(timeoutMs > 0L) {
            "timeoutMs must be > 0"
        }

        val app =
            context.applicationContext

        val finalFile =
            resolveSafeFileUnder(
                app.filesDir,
                fileName,
            )

        val flightKey =
            finalFile.canonicalPath

        /*
         * Install a destination-specific single-flight entry. If another
         * coroutine already owns this destination, simply await its result.
         */
        val candidate =
            Flight(
                deferred =
                    CompletableDeferred(),
            )

        val existing =
            flights.putIfAbsent(
                flightKey,
                candidate,
            )

        if (existing != null) {
            Log.d(
                TAG,
                "ensureInitialized: joining existing flight " +
                        "file=${finalFile.name}",
            )

            return existing.deferred.await()
        }

        val flight =
            candidate

        flight.ownerJob =
            currentCoroutineContext()[Job]

        val startedAtMs =
            SystemClock.elapsedRealtime()

        val token =
            hfToken
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }

        val progressCallbackFailed =
            AtomicBoolean(false)

        val safeProgress:
                    (Long, Long?) -> Unit =
            { downloaded, total ->

                if (
                    flight.ownerJob?.isActive !=
                    false
                ) {
                    try {
                        onProgress(
                            downloaded,
                            total,
                        )
                    } catch (
                        t: Throwable
                    ) {
                        /*
                         * Progress rendering is observational. It must never
                         * abort a large model transfer. Log only the first
                         * callback error to avoid flooding Logcat on every
                         * progress update.
                         */
                        if (
                            progressCallbackFailed
                                .compareAndSet(
                                    false,
                                    true,
                                )
                        ) {
                            Log.w(
                                TAG,
                                "Progress callback failed; " +
                                        "transfer will continue: ${t.message}",
                                t,
                            )
                        }
                    }
                }
            }

        try {
            currentCoroutineContext()
                .ensureActive()

            if (forceFresh) {
                Log.i(
                    TAG,
                    "phase=cleanup-fresh " +
                            "file=${finalFile.name}",
                )

                cleanupTransferState(
                    finalFile = finalFile,
                    deleteFinal = true,
                    includeLegacyTmpState = true,
                )
            } else {
                /*
                 * Preserve resume compatibility with the previous
                 * HeavyInitializer implementation, which downloaded into
                 * <file>.tmp before performing a second rename.
                 */
                migrateLegacyTransferState(
                    finalFile
                )
            }

            /*
             * Fast local path.
             *
             * This deliberately avoids HTTP. Once a known model is installed,
             * restarting the application should not require a remote HEAD
             * request before LiteRT-LM can start loading the local model.
             */
            if (
                !forceFresh &&
                finalFile.exists() &&
                finalFile.isFile &&
                finalFile.length() > 0L
            ) {
                val length =
                    finalFile.length()

                safeProgress(
                    length,
                    length,
                )

                val result =
                    Result.success(
                        finalFile
                    )

                flight.deferred
                    .complete(result)

                Log.i(
                    TAG,
                    "phase=local-hit " +
                            "file=${finalFile.name} " +
                            "bytes=$length " +
                            "totalMs=${elapsedMs(startedAtMs)}",
                )

                return result
            }

            val downloader =
                HttpUrlFileDownloader(
                    hfToken = token,
                    debugLogs =
                        BuildConfig.DEBUG,
                )

            val downloadStartedAtMs =
                SystemClock.elapsedRealtime()

            Log.i(
                TAG,
                "phase=download-start " +
                        "file=${finalFile.name} " +
                        "tokenConfigured=${token != null} " +
                        "forceFresh=$forceFresh",
            )

            withTimeout(
                timeoutMs
            ) {
                currentCoroutineContext()
                    .ensureActive()

                /*
                 * Download directly to the final destination name.
                 *
                 * HttpUrlFileDownloader already stages data into:
                 *
                 *     <dst>.part
                 *
                 * and promotes the file only after successful transfer and
                 * validation. A second <dst>.tmp layer in HeavyInitializer is
                 * therefore redundant and makes resumable state harder to
                 * manage.
                 */
                downloader.downloadToFile(
                    url = modelUrl,
                    dst = finalFile,
                    onProgress =
                        safeProgress,
                    connectTimeoutMs =
                        DOWNLOAD_CONNECT_TIMEOUT_MS,
                    firstByteTimeoutMs =
                        DOWNLOAD_FIRST_BYTE_TIMEOUT_MS,
                    stallTimeoutMs =
                        minOf(
                            DOWNLOAD_STALL_TIMEOUT_MS
                                .toLong(),
                            timeoutMs,
                        )
                            .coerceAtLeast(
                                1_000L
                            )
                            .toInt(),
                    maxRetries =
                        DOWNLOAD_MAX_RETRIES,
                )

                currentCoroutineContext()
                    .ensureActive()
            }

            if (
                !finalFile.exists() ||
                !finalFile.isFile ||
                finalFile.length() <= 0L
            ) {
                throw IOException(
                    "Downloader completed but destination " +
                            "is missing or empty: " +
                            finalFile.absolutePath,
                )
            }

            val outputLength =
                finalFile.length()

            safeProgress(
                outputLength,
                outputLength,
            )

            val result =
                Result.success(
                    finalFile
                )

            flight.deferred
                .complete(result)

            Log.i(
                TAG,
                "phase=download-complete " +
                        "file=${finalFile.name} " +
                        "bytes=$outputLength " +
                        "downloadMs=${elapsedMs(downloadStartedAtMs)} " +
                        "totalMs=${elapsedMs(startedAtMs)}",
            )

            return result

        } catch (
            te: TimeoutCancellationException
        ) {
            /*
             * IMPORTANT:
             *
             * TimeoutCancellationException extends CancellationException.
             * Therefore this catch must appear BEFORE the general
             * CancellationException handler.
             */
            if (forceFresh) {
                cleanupTransferState(
                    finalFile = finalFile,
                    deleteFinal = false,
                    includeLegacyTmpState = true,
                )
            }

            val error =
                IOException(
                    "Timeout ($timeoutMs ms)",
                    te,
                )

            val result =
                Result.failure<File>(
                    error
                )

            flight.deferred
                .complete(result)

            Log.w(
                TAG,
                "phase=timeout " +
                        "file=${finalFile.name} " +
                        "timeoutMs=$timeoutMs " +
                        "totalMs=${elapsedMs(startedAtMs)}",
                te,
            )

            return result

        } catch (
            ie: InterruptedIOException
        ) {
            if (forceFresh) {
                cleanupTransferState(
                    finalFile = finalFile,
                    deleteFinal = false,
                    includeLegacyTmpState = true,
                )
            }

            val error =
                IOException(
                    "Canceled",
                    ie,
                )

            val result =
                Result.failure<File>(
                    error
                )

            flight.deferred
                .complete(result)

            Log.w(
                TAG,
                "phase=interrupted " +
                        "file=${finalFile.name} " +
                        "totalMs=${elapsedMs(startedAtMs)}",
                ie,
            )

            return result

        } catch (
            ce: CancellationException
        ) {
            if (forceFresh) {
                cleanupTransferState(
                    finalFile = finalFile,
                    deleteFinal = false,
                    includeLegacyTmpState = true,
                )
            }

            /*
             * Release other callers waiting on this shared operation before
             * propagating structured cancellation to the owner coroutine.
             */
            flight.deferred.complete(
                Result.failure(
                    IOException(
                        "Canceled",
                        ce,
                    )
                )
            )

            Log.w(
                TAG,
                "phase=cancelled " +
                        "file=${finalFile.name} " +
                        "totalMs=${elapsedMs(startedAtMs)}",
                ce,
            )

            throw ce

        } catch (
            t: Throwable
        ) {
            val message =
                userFriendlyMessage(t)

            /*
             * Preserve partial/chunk state on normal failures.
             *
             * HttpUrlFileDownloader validates metadata, Content-Range, ETag,
             * chunk length, and remote size before reusing resumable state.
             * Deleting all partial files here would defeat that recovery logic
             * and restart a multi-gigabyte model from byte zero.
             */
            if (forceFresh) {
                cleanupTransferState(
                    finalFile = finalFile,
                    deleteFinal = false,
                    includeLegacyTmpState = true,
                )
            } else if (
                finalFile.exists() &&
                finalFile.length() <= 0L
            ) {
                runCatching {
                    finalFile.delete()
                }
            }

            val error =
                IOException(
                    message,
                    t,
                )

            val result =
                Result.failure<File>(
                    error
                )

            flight.deferred
                .complete(result)

            Log.w(
                TAG,
                "phase=failed " +
                        "file=${finalFile.name} " +
                        "message=$message " +
                        "totalMs=${elapsedMs(startedAtMs)}",
                t,
            )

            return result

        } finally {
            flight.ownerJob =
                null

            flights.remove(
                flightKey,
                flight,
            )
        }
    }

    /**
     * Requests cancellation of every active HeavyInitializer owner.
     *
     * The owner coroutine receives CancellationException. Other callers waiting
     * on that destination's shared Deferred receive Result.failure.
     */
    fun cancel() {
        val snapshot =
            flights.values.toSet()

        if (snapshot.isEmpty()) {
            Log.d(
                TAG,
                "cancel: no active initialization",
            )

            return
        }

        snapshot.forEach { flight ->
            flight.ownerJob?.cancel(
                CancellationException(
                    "canceled by user"
                )
            )
        }

        Log.w(
            TAG,
            "Initialization cancel requested for " +
                    "${snapshot.size} flight(s).",
        )
    }

    /**
     * Clears shared debug state and cancels all current owners.
     *
     * This method is intended only for development/instrumentation tests.
     */
    fun resetForDebug() {
        val snapshot =
            flights.entries.toList()

        snapshot.forEach { (key, flight) ->
            flights.remove(
                key,
                flight,
            )

            flight.deferred.complete(
                Result.failure(
                    IOException(
                        "resetForDebug"
                    )
                )
            )

            flight.ownerJob?.cancel(
                CancellationException(
                    "resetForDebug"
                )
            )
        }

        Log.w(
            TAG,
            "resetForDebug(): cleared " +
                    "${snapshot.size} in-flight operation(s)",
        )
    }

    // ---------------------------------------------------------------------
    // Transfer-state migration / cleanup
    // ---------------------------------------------------------------------

    /**
     * Migrates resumable files created by the previous two-stage
     * <final>.tmp-based HeavyInitializer into the downloader's current
     * <final>.part naming scheme.
     *
     * Migration is best-effort and only occurs when the new destination file
     * does not already exist. All files live in the same directory, so rename
     * should normally be atomic and inexpensive.
     */
    private fun migrateLegacyTransferState(
        finalFile: File,
    ) {
        val parent =
            finalFile.parentFile
                ?: return

        val legacyTmp =
            File(
                parent,
                finalFile.name + ".tmp",
            )

        val legacyPart =
            File(
                parent,
                legacyTmp.name + ".part",
            )

        val legacyMeta =
            File(
                parent,
                legacyPart.name + ".meta",
            )

        val currentPart =
            File(
                parent,
                finalFile.name + ".part",
            )

        val currentMeta =
            File(
                parent,
                currentPart.name + ".meta",
            )

        if (
            !finalFile.exists() &&
            legacyTmp.exists() &&
            legacyTmp.isFile &&
            legacyTmp.length() > 0L
        ) {
            moveBestEffort(
                source =
                    legacyTmp,
                destination =
                    finalFile,
            )

            Log.d(
                TAG,
                "Migrated legacy completed temp file " +
                        "to ${finalFile.name}",
            )
        }

        if (
            !currentPart.exists() &&
            legacyPart.exists()
        ) {
            moveBestEffort(
                source =
                    legacyPart,
                destination =
                    currentPart,
            )
        }

        if (
            !currentMeta.exists() &&
            legacyMeta.exists()
        ) {
            moveBestEffort(
                source =
                    legacyMeta,
                destination =
                    currentMeta,
            )
        }

        /*
         * Parallel downloader chunks use:
         *
         *     <part>.chunk.<index>
         *
         * Rename old:
         *
         *     <final>.tmp.part.chunk.*
         *
         * to:
         *
         *     <final>.part.chunk.*
         *
         * so interrupted parallel transfers remain resumable.
         */
        parent.listFiles()
            ?.filter { file ->
                file.name.startsWith(
                    legacyPart.name +
                            ".chunk."
                )
            }
            ?.forEach { oldChunk ->

                val suffix =
                    oldChunk.name
                        .removePrefix(
                            legacyPart.name
                        )

                val newChunk =
                    File(
                        parent,
                        currentPart.name +
                                suffix,
                    )

                if (
                    !newChunk.exists()
                ) {
                    moveBestEffort(
                        source =
                            oldChunk,
                        destination =
                            newChunk,
                    )
                }
            }
    }

    /**
     * Removes completed/partial/chunk files associated with one destination.
     */
    private fun cleanupTransferState(
        finalFile: File,
        deleteFinal: Boolean,
        includeLegacyTmpState: Boolean,
    ) {
        val parent =
            finalFile.parentFile
                ?: return

        if (deleteFinal) {
            safeDelete(
                finalFile
            )
        }

        val currentPart =
            File(
                parent,
                finalFile.name +
                        ".part",
            )

        val currentMeta =
            File(
                parent,
                currentPart.name +
                        ".meta",
            )

        val currentMetaTmp =
            File(
                parent,
                currentMeta.name +
                        ".tmp",
            )

        safeDelete(
            currentPart
        )

        safeDelete(
            currentMeta
        )

        safeDelete(
            currentMetaTmp
        )

        deleteFilesWithPrefix(
            parent = parent,
            prefix =
                currentPart.name +
                        ".chunk.",
        )

        if (
            includeLegacyTmpState
        ) {
            val legacyTmp =
                File(
                    parent,
                    finalFile.name +
                            ".tmp",
                )

            val legacyPart =
                File(
                    parent,
                    legacyTmp.name +
                            ".part",
                )

            val legacyMeta =
                File(
                    parent,
                    legacyPart.name +
                            ".meta",
                )

            val legacyMetaTmp =
                File(
                    parent,
                    legacyMeta.name +
                            ".tmp",
                )

            safeDelete(
                legacyTmp
            )

            safeDelete(
                legacyPart
            )

            safeDelete(
                legacyMeta
            )

            safeDelete(
                legacyMetaTmp
            )

            deleteFilesWithPrefix(
                parent = parent,
                prefix =
                    legacyPart.name +
                            ".chunk.",
            )
        }
    }

    private fun deleteFilesWithPrefix(
        parent: File,
        prefix: String,
    ) {
        parent.listFiles()
            ?.filter {
                it.name.startsWith(
                    prefix
                )
            }
            ?.forEach(
                ::safeDelete
            )
    }

    /**
     * Moves a file within the application's private directory.
     *
     * renameTo() is preferred because source and destination normally share the
     * same filesystem. Copy is retained only as a defensive fallback.
     */
    private fun moveBestEffort(
        source: File,
        destination: File,
    ) {
        if (
            !source.exists() ||
            destination.exists()
        ) {
            return
        }

        destination.parentFile
            ?.mkdirs()

        if (
            source.renameTo(
                destination
            )
        ) {
            return
        }

        source.copyTo(
            target =
                destination,
            overwrite =
                false,
        )

        if (
            !source.delete()
        ) {
            Log.w(
                TAG,
                "moveBestEffort: copied but failed " +
                        "to delete ${source.absolutePath}",
            )
        }
    }

    private fun safeDelete(
        file: File,
    ) {
        runCatching {
            if (
                file.exists() &&
                !file.delete()
            ) {
                Log.w(
                    TAG,
                    "Failed to delete " +
                            file.absolutePath,
                )
            }
        }.onFailure { error ->
            Log.w(
                TAG,
                "Delete failed for " +
                        "${file.absolutePath}: " +
                        "${error.message}",
                error,
            )
        }
    }

    // ---------------------------------------------------------------------
    // Safe path resolution
    // ---------------------------------------------------------------------

    /**
     * Resolves a relative path under [baseDir] without allowing directory
     * traversal outside the application's private files directory.
     */
    private fun resolveSafeFileUnder(
        baseDir: File,
        relativePath: String,
    ): File {
        val path =
            relativePath.trim()

        require(
            path.isNotEmpty()
        ) {
            "fileName must not be empty"
        }

        require(
            !File(path).isAbsolute
        ) {
            "absolute paths are not allowed: $path"
        }

        val segments =
            path.split(
                '/',
                '\\',
            )

        require(
            segments.none { segment ->
                segment == ".."
            }
        ) {
            "path traversal is not allowed: $path"
        }

        val base =
            baseDir.canonicalFile

        val resolved =
            File(
                base,
                path,
            ).canonicalFile

        val basePath =
            base.path

        val resolvedPath =
            resolved.path

        val insideBase =
            resolvedPath == basePath ||
                    resolvedPath.startsWith(
                        basePath +
                                File.separator
                    )

        require(
            insideBase
        ) {
            "resolved path escapes baseDir: $path"
        }

        return resolved
    }

    // ---------------------------------------------------------------------
    // Error mapping / diagnostics
    // ---------------------------------------------------------------------

    /**
     * Converts low-level transport errors into concise messages suitable for
     * the download gate UI while preserving the original Throwable as cause.
     */
    private fun userFriendlyMessage(
        throwable: Throwable,
    ): String {
        val raw =
            throwable.message
                ?: throwable::class
                    .java
                    .simpleName

        val normalized =
            raw.lowercase()

        return when {
            "unauthorized" in normalized ||
                    "401" in normalized ->
                "Authorization failed (HF token?)"

            "forbidden" in normalized ||
                    "403" in normalized ->
                "Access denied (token/permissions?)"

            "timeout" in normalized ->
                "Network timeout"

            "space" in normalized ->
                "Not enough free space"

            "content-range" in normalized ||
                    "416" in normalized ||
                    "range" in normalized ->
                "Resume failed (server refused range)"

            "unknown host" in normalized ||
                    "dns" in normalized ->
                "Unknown host (check connectivity)"

            else ->
                raw
        }
    }

    /** Returns milliseconds elapsed since [startedAtMs]. */
    private fun elapsedMs(
        startedAtMs: Long,
    ): Long =
        SystemClock.elapsedRealtime() -
                startedAtMs
}