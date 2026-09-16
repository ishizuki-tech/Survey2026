/*
 * =====================================================================
 *  File: PersistentModelStore.kt
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Keeps one copy of the downloaded SLM model file in the device's public
 *  Downloads collection (MediaStore), so it survives the app being deleted
 *  and reinstalled — unlike everything under Context.filesDir, which the
 *  OS wipes on uninstall.
 *
 *  Identity model:
 *  ---------------------------------------------------------------------
 *  A model's on-disk file name is already derived from its configured
 *  download URL (see AppViewModel.suggestFileName). Two configs that name
 *  the same file are treated as the same model; a different file name
 *  means a different model version. No separate metadata sidecar is kept.
 *
 *  Platform support:
 *  ---------------------------------------------------------------------
 *  Uses MediaStore.Downloads, available without any runtime permission on
 *  API 29+ (an app may freely manage files it created there under scoped
 *  storage). On API < 29 this store is a no-op (returns as "unavailable"):
 *  the app behaves exactly as it did before this feature — private-storage
 *  only, redownloading after every uninstall. Extending this to legacy
 *  storage would need a WRITE_EXTERNAL_STORAGE runtime-permission prompt
 *  (the manifest already declares it, capped at maxSdkVersion 28) and was
 *  intentionally left out to keep this change small and verifiable.
 * =====================================================================
 */

package com.negi.survey.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import java.io.File

object PersistentModelStore {

    private const val TAG = "PersistentModelStore"

    /** Subfolder under the public Downloads collection where models are kept. */
    private const val SUBFOLDER = "Survey2026Models"

    private val RELATIVE_PATH: String
        get() = Environment.DIRECTORY_DOWNLOADS + File.separator + SUBFOLDER

    /** One row from the persisted-models collection. */
    private data class PersistedEntry(
        val uri: Uri,
        val displayName: String,
        val size: Long,
        val isPending: Boolean
    )

    /** True when this API level supports the MediaStore-based persistent store. */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Attempts to satisfy [targetFileName] entirely from persisted storage.
     *
     * - If a persisted file with exactly [targetFileName] exists, it is copied
     *   into [privateDestination], [onReused] fires with its byte size, and
     *   this returns true (caller can skip the network entirely).
     * - If persisted storage instead holds a *different* file (an old model
     *   version), [onReplacing] fires with that old file's name and the stale
     *   entry is deleted, then this returns false so the caller proceeds to
     *   download the new one normally.
     * - If persisted storage is empty, or unsupported on this API level, this
     *   returns false with neither callback invoked.
     *
     * Never throws: any I/O failure is logged and treated as "not available".
     */
    fun reuseOrReplace(
        context: Context,
        targetFileName: String,
        privateDestination: File,
        onReused: (bytes: Long) -> Unit,
        onReplacing: (oldFileName: String) -> Unit
    ): Boolean {
        if (!isSupported()) return false

        return runCatching {
            val entries = listEntries(context)
            val matching = entries.filter { it.displayName == targetFileName }
            val match = bestOf(matching)

            // Clean up any duplicate rows sharing targetFileName beyond the one
            // we're about to use (or, if none are usable, all of them) so they
            // don't keep piling up run after run.
            matching.filter { it.uri != match?.uri }.forEach { deleteEntry(context, it.uri) }

            if (match != null) {
                val ok = copyToPrivate(context, match.uri, privateDestination)
                if (ok) {
                    Log.i(TAG, "reuseOrReplace: reused persisted model file=$targetFileName bytes=${match.size}")
                    onReused(privateDestination.length())
                    return@runCatching true
                }
                Log.w(TAG, "reuseOrReplace: found persisted entry but copy failed, will redownload")
                deleteEntry(context, match.uri)
                return@runCatching false
            }

            val stale = entries.filter { it.displayName != targetFileName }
            if (stale.isNotEmpty()) {
                Log.i(
                    TAG,
                    "reuseOrReplace: persisted model(s) ${stale.map { it.displayName }} do not match " +
                            "required $targetFileName; removing before download"
                )
                onReplacing(stale.first().displayName)
                stale.forEach { deleteEntry(context, it.uri) }
            }

            false
        }.getOrElse { t ->
            Log.w(TAG, "reuseOrReplace failed; falling back to network", t)
            false
        }
    }

    /**
     * Uploads a freshly-downloaded, completed model file into persistent
     * storage under [fileName] so a future reinstall can reuse it.
     *
     * Best-effort: failures are logged and swallowed. The caller's in-app
     * copy in private storage remains the source of truth for the current
     * run either way.
     */
    fun persistFromPrivate(context: Context, fileName: String, sourceFile: File): Boolean {
        if (!isSupported()) return false
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return false

        return runCatching {
            val existingMatches = listEntries(context).filter { it.displayName == fileName }
            val best = bestOf(existingMatches)

            if (best != null && best.size == sourceFile.length()) {
                Log.d(TAG, "persistFromPrivate: already persisted with matching size, skipping re-upload")
                // Still clean up any other duplicate rows left over from a past run.
                existingMatches.filter { it.uri != best.uri }.forEach { deleteEntry(context, it.uri) }
                return@runCatching true
            }

            // Replacing (or no usable candidate found): clear every row under this
            // name so a stale duplicate can't linger alongside the new one.
            existingMatches.forEach { deleteEntry(context, it.uri) }

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false

            try {
                val opened = resolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { input -> input.copyTo(out, bufferSize = 1 shl 20) }
                    true
                } ?: false

                if (!opened) {
                    runCatching { resolver.delete(uri, null, null) }
                    return@runCatching false
                }

                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)

                Log.i(TAG, "persistFromPrivate: persisted $fileName (${sourceFile.length()} bytes)")
                true
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        }.getOrElse { t ->
            Log.w(TAG, "persistFromPrivate failed (non-fatal; next run will just redownload)", t)
            false
        }
    }

    // ---------------------------------------------------------------------
    // MediaStore helpers
    // ---------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listEntries(context: Context): List<PersistedEntry> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.IS_PENDING
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf("$RELATIVE_PATH${File.separator}")

        val out = mutableListOf<PersistedEntry>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val pendingCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.IS_PENDING)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                out.add(
                    PersistedEntry(
                        uri = uri,
                        displayName = cursor.getString(nameCol).orEmpty(),
                        size = cursor.getLong(sizeCol),
                        isPending = cursor.getInt(pendingCol) != 0
                    )
                )
            }
        }
        return out
    }

    /**
     * Picks the best candidate among entries that share the same display name
     * (there should only ever be one, but a killed-mid-persist run or a racing
     * write can leave more than one behind): prefer a fully-written entry
     * (not [PersistedEntry.isPending]) and, among those, the largest — a
     * truncated leftover copy is reliably smaller than a complete model file.
     * Returns null if every candidate is still pending (nothing safe to reuse).
     */
    private fun bestOf(candidates: List<PersistedEntry>): PersistedEntry? =
        candidates.filterNot { it.isPending }.maxByOrNull { it.size }

    private fun copyToPrivate(context: Context, uri: Uri, destination: File): Boolean {
        return runCatching {
            destination.parentFile?.mkdirs()
            val tmp = File(destination.parentFile, destination.name + ".fromCache.tmp")

            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out, bufferSize = 1 shl 20) }
            } ?: return false

            if (!tmp.renameTo(destination)) {
                tmp.copyTo(destination, overwrite = true)
                tmp.delete()
            }

            destination.exists() && destination.length() > 0L
        }.getOrElse { t ->
            Log.w(TAG, "copyToPrivate failed", t)
            false
        }
    }

    private fun deleteEntry(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
            .onFailure { t -> Log.w(TAG, "deleteEntry failed for $uri", t) }
    }
}
