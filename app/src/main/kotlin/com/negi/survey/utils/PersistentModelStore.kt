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
 *  Names locate candidates only. Trusted size and SHA-256 establish identity
 *  before restoration or publication; no durable verification sidecar is used.
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
import com.negi.survey.BuildConfig
import kotlinx.coroutines.CancellationException
import java.io.File

object PersistentModelStore {

    private const val TAG = "PersistentModelStore"

    /** Subfolder under the public Downloads collection where models are kept. */
    private val SUBFOLDER = if (BuildConfig.ISSUE157_DA) "Survey2026Models-da" else "Survey2026Models"

    private val RELATIVE_PATH: String
        get() = Environment.DIRECTORY_DOWNLOADS + File.separator + SUBFOLDER

    /** One row from the persisted-models collection. */
    internal data class PersistedEntry(
        val id: Long,
        val uri: Uri,
        val displayName: String,
        val size: Long,
        val isPending: Boolean
    )

    /** True when this API level supports the MediaStore-based persistent store. */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Returns whether [displayName] is the exact logical model filename or an Android-style
     * MediaStore collision variant of it.
     */
    internal fun isMediaStoreCollisionVariant(
        displayName: String,
        targetFileName: String
    ): Boolean {
        if (displayName == targetFileName) return true

        val lastDot = targetFileName.lastIndexOf('.')
        val baseName = if (lastDot > 0) targetFileName.substring(0, lastDot) else targetFileName
        val extension = if (lastDot > 0) targetFileName.substring(lastDot) else ""
        val prefix = "$baseName ("

        if (!displayName.startsWith(prefix) || !displayName.endsWith(extension)) return false

        val ordinalEnd = displayName.length - extension.length
        if (ordinalEnd <= prefix.length || displayName[ordinalEnd - 1] != ')') return false

        val ordinal = displayName.substring(prefix.length, ordinalEnd - 1)
        return ordinal.isNotEmpty() &&
            ordinal[0] in '1'..'9' &&
            ordinal.drop(1).all { it in '0'..'9' }
    }

    /**
     * Attempts to satisfy [targetFileName] entirely from persisted storage.
     *
     * - If a persisted file with [targetFileName] or a collision variant exists, it is copied
     *   into [privateDestination], [onReused] fires with its byte size, and
     *   this returns true (caller can skip the network entirely).
     * - Different logical model names are left untouched; this returns false so
     *   the caller proceeds to download the configured model normally.
     * - If persisted storage is empty, or unsupported on this API level, this
     *   returns false with neither callback invoked.
     *
     * I/O failures are logged and treated as "not available". Cancellation propagates.
     */
    suspend fun reuseOrReplace(
        context: Context,
        targetFileName: String,
        privateDestination: File,
        onReused: (bytes: Long) -> Unit,
        onReplacing: (oldFileName: String) -> Unit,
        identity: ModelArtifactIdentity = GemmaModelIdentity.forFile(targetFileName),
    ): Boolean = ModelArtifactOperations.withDestination(privateDestination) {
        reuseOrReplaceOwned(context, targetFileName, privateDestination, onReused, onReplacing, identity)
    }

    private suspend fun reuseOrReplaceOwned(
        context: Context,
        targetFileName: String,
        privateDestination: File,
        onReused: (bytes: Long) -> Unit,
        onReplacing: (oldFileName: String) -> Unit,
        identity: ModelArtifactIdentity,
    ): Boolean {
        if (!isSupported()) return false

        return runCatching {
            val entries = listEntries(context)
            val matching = entries.filter {
                isMediaStoreCollisionVariant(it.displayName, targetFileName)
            }
            for (match in matching.filter { !it.isPending && it.size > 0 }.sortedByDescending { it.id }) {
                try {
                    copyToPrivate(context, match.uri, privateDestination, identity)
                    onReused(privateDestination.length())
                    return@runCatching true
                } catch (ce: CancellationException) {
                    throw ce
                } catch (error: java.io.IOException) {
                    // An invalid newest row must not hide a valid older fallback.
                    Log.w(TAG, "Persisted candidate rejected; checking older completed rows", error)
                }
            }

            false
        }.getOrElse { t ->
            if (t is CancellationException) throw t
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
     * run either way. [replaceExisting] is retained for caller compatibility; every
     * replacement now verifies the actual pending bytes, regardless of reported size.
     */
    suspend fun persistFromPrivate(
        context: Context, fileName: String, sourceFile: File, replaceExisting: Boolean = false,
        identity: ModelArtifactIdentity = GemmaModelIdentity.forFile(fileName),
    ): Boolean = ModelArtifactOperations.withDestination(File(context.applicationContext.filesDir, fileName)) {
        persistFromPrivateOwned(context, fileName, sourceFile, replaceExisting, identity)
    }

    private suspend fun persistFromPrivateOwned(
        context: Context, fileName: String, sourceFile: File, replaceExisting: Boolean,
        identity: ModelArtifactIdentity,
    ): Boolean {
        if (!isSupported()) return false
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return false

        return runCatching {
            val existingMatches = listEntries(context).filter {
                isMediaStoreCollisionVariant(it.displayName, fileName)
            }
            ModelArtifactOperations.current().checkCurrent()
            // Same size does not establish content identity. Always verify the pending copy.
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            ModelArtifactOperations.current().persist(
                create = {
                    resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw java.io.IOException("Could not create persisted model row")
                },
                write = { uri ->
                    (resolver.openOutputStream(uri)
                        ?: throw java.io.IOException("Could not write persisted model")).use { out ->
                        sourceFile.inputStream().use { input -> input.copyTo(out, bufferSize = 1 shl 20) }
                    }
                    identity.validateStream { resolver.openInputStream(uri) }
                },
                commit = { uri ->
                    val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    check(resolver.update(uri, done, null, null) == 1) { "Could not publish persisted model" }
                },
                cleanup = { uri -> resolver.delete(uri, null, null) }
            )
            // Keep the previous completed copy until the replacement has become visible.
            existingMatches.filterNot { it.isPending }.forEach { deleteEntry(context, it.uri) }
            Log.i(TAG, "persistFromPrivate: persisted $fileName (${sourceFile.length()} bytes)")
            true
        }.getOrElse { t ->
            if (t is CancellationException) throw t
            Log.w(TAG, "persistFromPrivate failed (non-fatal; next run will just redownload)", t)
            false
        }
    }

    /**
     * Removes every persisted entry for [targetFileName] (and MediaStore
     * collision variants of it) from the public persisted store, including
     * pending rows.
     *
     * Explicit administrative/test cleanup only. Native initialization failure must
     * not call this: runtime incompatibility does not invalidate verified fallback bytes.
     *
     * Best-effort: failures are logged and swallowed.
     */
    suspend fun forget(context: Context, targetFileName: String): Boolean = ModelArtifactOperations.withDestination(File(context.applicationContext.filesDir, targetFileName)) {
        forgetOwned(context, targetFileName)
    }

    private suspend fun forgetOwned(context: Context, targetFileName: String): Boolean {
        if (!isSupported()) return false

        return runCatching {
            listEntries(context)
                .filter { isMediaStoreCollisionVariant(it.displayName, targetFileName) }
                .forEach { deleteEntry(context, it.uri) }
            true
        }.getOrElse { t ->
            if (t is CancellationException) throw t
            Log.w(TAG, "forget failed for $targetFileName", t)
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
                        id = id,
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
     * Selects the newest completed, nonempty installation, not the largest file.
     * MediaProvider allocates increasing row IDs to our serialized inserts. An older
     * completed row may survive a failed delete or process death after replacement
     * publication; it remains a fallback while the replacement is pending, but must
     * not supersede the newer completed row. No wall-clock timestamp ordering is used.
     */
    internal fun bestOf(candidates: List<PersistedEntry>): PersistedEntry? =
        candidates.filter { !it.isPending && it.size > 0L }.maxByOrNull { it.id }

    private suspend fun copyToPrivate(context: Context, uri: Uri, destination: File, identity: ModelArtifactIdentity): Boolean {
        check(ModelArtifactOperations.current().file == destination.canonicalFile)
        ModelArtifactOperations.current().import(
            open = { context.contentResolver.openInputStream(uri) },
            validate = { identity.validate(it) }, installed = { }
        )
        return true
    }

    private fun deleteEntry(context: Context, uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
            .onFailure { t -> Log.w(TAG, "deleteEntry failed for $uri", t) }
    }
}
