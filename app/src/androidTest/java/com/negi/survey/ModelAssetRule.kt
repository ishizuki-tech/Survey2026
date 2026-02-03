@file:Suppress("UnusedParameter")

package com.negi.survey

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume
import org.junit.rules.ExternalResource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Instrumentation test Rule that guarantees a model artifact is available under:
 *
 *   <app internal>/files/models/<modelName>
 *
 * Strategy (API-aware, order-preserving):
 *  1) If already copied to internal storage -> done.
 *  2) Try resolving via a cached MediaStore Uri (if any).
 *  3) API 33+: query self-owned → else temporarily adopt "all-files" read and query any-owner.
 *  4) API <= 32: legacy LIKE query (kick MediaScanner as a fallback).
 *  5) If not found, create a self-owned MediaStore Download entry and download the model to it.
 *  6) Finally copy from the resolved Uri to internal storage (filesDir/models).
 *
 * Permissions / identities:
 *  - On API <= 32 adopts READ_EXTERNAL_STORAGE via shell identity for read queries.
 *  - On API 33+ uses OWNER_PACKAGE_NAME-aware queries; may temporarily adopt MANAGE_EXTERNAL_STORAGE
 *    (if available to the test run) for "any-owner" query path.
 */
class ModelAssetRule(
    private val modelName: String = DEFAULT_MODEL_NAME,
    private val relativeDir: String = DEFAULT_REL_DIR,
    private val modelUrl: String = DEFAULT_MODEL_URL,
    private val bearerToken: String? = BuildConfig.HF_TOKEN.takeIf { it.isNotBlank() },
) : ExternalResource() {

    /** Target context used for all MediaStore and file operations. */
    lateinit var context: Context
        private set

    /** Final resolved model file in app-internal storage. */
    lateinit var internalModel: File
        private set

    private val TAG = "MS-ModelPrep"

    // SharedPreferences (persisting last resolved MediaStore Uri)
    private val prefsName = "ms_cache"
    private val keyModelUri = "model_uri"

    // HTTP client for large downloads (long read timeout)
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // Identity / permission adoption bookkeeping
    private var adoptedShellRead = false
    private var adoptedForApi: Int = -1

    // I/O parameters
    private companion object {
        const val BUFFER_SIZE = 256 * 1024
        const val PREF_DIR_NAME = "models" // internal subdir
        const val DEFAULT_MODEL_NAME = "gemma-3n-E4B-it-int4.litertlm"
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm"
        val DEFAULT_REL_DIR = "${Environment.DIRECTORY_DOWNLOADS}/SurveyNavModels"
    }

    // ---------------------------------------------------------------------
    // Rule lifecycle
    // ---------------------------------------------------------------------

    override fun before() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        // This logic relies on scoped storage / RELATIVE_PATH (API 29+).
        Assume.assumeTrue(
            "Requires API 29+ (Android 10+).",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        )

        Log.i(
            TAG,
            "ModelAssetRule.before: api=${Build.VERSION.SDK_INT}, modelName=$modelName, relDir=$relativeDir"
        )

        adoptReadExternalIfNeeded()

        // 1) Already copied to internal? (fast path)
        internalModel = File(File(context.filesDir, PREF_DIR_NAME), modelName)
            .apply { parentFile?.mkdirs() }

        if (internalModel.exists() && internalModel.length() > 0) {
            Log.i(
                TAG,
                "Fast path: internal model exists -> ${internalModel.absolutePath} len=${internalModel.length()}"
            )
            return
        }

        // 2) Resolve source Uri: cache → self-owned (API 33+) → any-owner (API 33+) → legacy (<=32)
        val cached = loadCachedUri()?.let { uri ->
            val size = getSize(uri)
            if (size > 0L) {
                Log.i(TAG, "Using cached MediaStore uri=$uri (size=$size)")
                uri
            } else {
                Log.i(TAG, "Cached MediaStore uri is empty or unavailable: $uri (size=$size)")
                null
            }
        }

        // API 33+: try self-owned first
        val selfOwned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cached ?: querySelfOwnedByNamePreferPathLike(modelName, relativeDir)?.also { found ->
                Log.i(TAG, "Found self-owned model in MediaStore: $found")
                cacheUri(found)
            }
        } else {
            null
        }

        if (selfOwned != null) {
            Log.i(TAG, "Will use self-owned MediaStore entry: $selfOwned")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: temporarily adopt broader read to query any-owner items
            val copied = withAllFilesAccessForRead {
                queryAnyOwnerByNamePreferPathLikeApi33(modelName, relativeDir)?.let { uri ->
                    val sz = getSize(uri)
                    Log.i(TAG, "Found any-owner model candidate: $uri (size=$sz)")
                    if (sz > 0L) {
                        copyUriToFile(uri, internalModel)
                        Log.i(TAG, "Copied any-owner model directly to internal: ${internalModel.absolutePath}")
                        true
                    } else {
                        false
                    }
                } ?: false
            }
            if (copied) {
                check(internalModel.exists() && internalModel.length() > 0)
                return
            }
        }

        // API <= 32: legacy LIKE query (optionally poke MediaScanner first)
        val legacyFound = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            runCatching { kickMediaScanner(relativeDir, modelName) }
                .onFailure { Log.w(TAG, "kickMediaScanner failed: ${it.message}") }
            cached ?: queryByNamePreferPathLikeLegacy(modelName, relativeDir)?.also { found ->
                Log.i(TAG, "Found legacy model via DISPLAY_NAME LIKE: $found")
                cacheUri(found)
            }
        } else {
            null
        }

        // 3) If still not found, create a self-owned entry and download the model to it.
        val source: Uri = selfOwned ?: legacyFound ?: run {
            Log.i(TAG, "No existing MediaStore entry found; inserting and downloading…")
            val created = insertDownloadOwned(modelName, relativeDir)
            try {
                downloadToUriOwned(created, modelUrl, bearerToken)
                val size = getSize(created)
                require(size > 0) { "Zero bytes after download via MediaStore (uri=$created)" }
                Log.i(TAG, "Download complete into MediaStore: uri=$created size=$size")
                verifyOwnerOrWarn(created)
                cacheUri(created)
            } catch (t: Throwable) {
                safeDelete(created)
                throw t
            }
            created
        }

        val sourceKind = when (source) {
            selfOwned -> "selfOwned"
            legacyFound -> "legacyFound"
            else -> "downloaded"
        }

        // 4) Copy to app-internal storage for deterministic access by tests/app.
        copyUriToFile(source, internalModel)
        Log.i(
            TAG,
            "Copied model to internal (kind=$sourceKind): ${internalModel.absolutePath} len=${internalModel.length()}"
        )
        check(internalModel.exists() && internalModel.length() > 0)
    }

    override fun after() {
        // Drop adopted shell identity if held
        if (adoptedShellRead) {
            runCatching {
                InstrumentationRegistry
                    .getInstrumentation()
                    .uiAutomation
                    .dropShellPermissionIdentity()
            }.onFailure {
                Log.w(TAG, "dropShellPermissionIdentity (final) failed: ${it.message}")
            }
            adoptedShellRead = false
        }
    }

    // ---------------------------------------------------------------------
    // Identity / permission helpers
    // ---------------------------------------------------------------------

    /**
     * Adopt a read capability according to API level:
     * - API 33+: no adoption needed for self-owned; we keep a record to skip adoption.
     * - API <= 32: adopt READ_EXTERNAL_STORAGE via shell identity for legacy queries.
     */
    private fun adoptReadExternalIfNeeded() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            adoptedForApi = 33
            Log.i(TAG, "skip adopt for API>=33 (self-owned search path available)")
        } else {
            ui.adoptShellPermissionIdentity(Manifest.permission.READ_EXTERNAL_STORAGE)
            adoptedShellRead = true
            adoptedForApi = 32
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "adopt READ_EXTERNAL_STORAGE; checkSelfPermission=$granted")
        }
    }

    /**
     * Execute a block with temporarily adopted broader read access:
     * - API 33+: try MANAGE_EXTERNAL_STORAGE (if test process is privileged)
     * - API <= 32: adopt READ_EXTERNAL_STORAGE (legacy)
     *
     * Always restores the previous baseline adoption state on exit.
     */
    private inline fun <T> withAllFilesAccessForRead(block: () -> T): T {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        var adopted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { ui.adoptShellPermissionIdentity(Manifest.permission.MANAGE_EXTERNAL_STORAGE) }
                .onSuccess {
                    adopted = true
                    Log.i(TAG, "adopt MANAGE_EXTERNAL_STORAGE (temp)")
                }
                .onFailure {
                    Log.i(TAG, "cannot adopt MANAGE_EXTERNAL_STORAGE: ${it.message}")
                }
        } else {
            runCatching { ui.adoptShellPermissionIdentity(Manifest.permission.READ_EXTERNAL_STORAGE) }
                .onSuccess {
                    adopted = true
                    Log.i(TAG, "adopt READ_EXTERNAL_STORAGE (temp)")
                }
        }
        return try {
            block()
        } finally {
            if (adopted) {
                runCatching { ui.dropShellPermissionIdentity() }
                    .onFailure { Log.w(TAG, "drop temp all-files access failed: ${it.message}") }
                Log.i(TAG, "drop temp all-files access")
                // Restore the baseline adoption state post-op.
                adoptReadExternalIfNeeded()
                Log.i(TAG, "re-adopt after temp all-files access; api=$adoptedForApi")
            }
        }
    }

    /**
     * Temporarily drop adopted shell identity to act as the app for a write operation
     * (MediaStore insert/update). Restores the previous state afterwards.
     */
    private inline fun <T> withAppIdentity(block: () -> T): T {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val dropped = if (adoptedShellRead) {
            runCatching { ui.dropShellPermissionIdentity() }
                .onSuccess {
                    adoptedShellRead = false
                    Log.i(TAG, "drop shell identity for write")
                }
                .isSuccess
        } else {
            false
        }
        return try {
            block()
        } finally {
            if (dropped) {
                adoptReadExternalIfNeeded()
                Log.i(TAG, "re-adopt after write; api=$adoptedForApi")
            }
        }
    }

    // ---------------------------------------------------------------------
    // Query helpers
    // ---------------------------------------------------------------------

    private data class Cand(val uri: Uri, val rel: String?, val size: Long, val mod: Long)

    private fun volumes(): Set<String> =
        MediaStore.getExternalVolumeNames(context)
            .ifEmpty { setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY) }

    private fun <T> acrossVolumes(mapper: (Uri) -> List<T>): List<T> = buildList {
        for (v in volumes()) {
            addAll(mapper(MediaStore.Downloads.getContentUri(v)))
            addAll(mapper(MediaStore.Files.getContentUri(v)))
        }
    }

    private data class RelPathLikes(
        val likeA: String,
        val likeB: String,
        val likeC: String,
        val likeD: String,
        val preferSuffix: String
    )

    private fun relLikes(preferRelPath: String): RelPathLikes {
        val prefNoSlash = preferRelPath.trimEnd('/')
        val prefWithSlash = normalizeRelPath(preferRelPath)
        val altNoSlash = prefNoSlash.replaceFirst("Download", "Downloads")
        val altWithSlash = normalizeRelPath(altNoSlash)
        return RelPathLikes(
            "%${escapeLike(prefWithSlash)}",
            "%${escapeLike(prefNoSlash)}",
            "%${escapeLike(altWithSlash)}",
            "%${escapeLike(altNoSlash)}",
            normalizeRelPath(prefWithSlash)
        )
    }

    private fun likeNameAndSuffix(displayName: String, preferRelPath: String): Pair<String, String> {
        val (base, ext) = displayName.substringBeforeLast('.') to
                displayName.substringAfterLast('.', "")
        val likeName =
            if (ext.isNotEmpty()) "${escapeLike(base)}%.$ext" else "${escapeLike(base)}%"
        val preferSuffix = relLikes(preferRelPath).preferSuffix
        return likeName to preferSuffix
    }

    private fun ownerCandidates(): List<String> {
        val myPkg = context.packageName
        return listOf(
            myPkg,
            "$myPkg.debug",
            "$myPkg.staging",
            "$myPkg.beta",
            "$myPkg.release"
        ).distinct()
    }

    /** API 33+: query self-owned entries first (OWNER_PACKAGE_NAME in {self variants, null}). */
    private fun querySelfOwnedByNamePreferPathLike(
        displayName: String,
        preferRelPath: String
    ): Uri? {
        val (likeName, preferSuffix) = likeNameAndSuffix(displayName, preferRelPath)
        val owners = ownerCandidates()
        val rel = relLikes(preferRelPath)

        fun query(baseUri: Uri): List<Cand> {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.IS_PENDING,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                "is_trashed"
            )
            val ownerSel = """
                ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? OR
                ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? OR
                ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? OR
                ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? OR
                ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? OR
                ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} IS NULL
            """.trimIndent()
            val relSel = """
                ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\' OR
                ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\' OR
                ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\' OR
                ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\' OR
                ${MediaStore.MediaColumns.RELATIVE_PATH} IS NULL
            """.trimIndent()
            val sel = """
                ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\' AND
                ($ownerSel) AND
                ($relSel) AND
                (${MediaStore.MediaColumns.IS_PENDING} IS NULL OR ${MediaStore.MediaColumns.IS_PENDING}=0) AND
                (is_trashed IS NULL OR is_trashed=0)
            """.trimIndent()
            val args = arrayOf(
                likeName,
                owners[0], owners.getOrNull(1) ?: owners[0],
                owners.getOrNull(2) ?: owners[0],
                owners.getOrNull(3) ?: owners[0],
                owners.getOrNull(4) ?: owners[0],
                rel.likeA, rel.likeB, rel.likeC, rel.likeD
            )
            return queryCandidates(baseUri, projection, sel, args)
        }

        val cands = acrossVolumes(::query)
        return pickBestByRelAndTime(cands, preferSuffix)
    }

    /** API 33+: query any-owner entries. First prefer RELATIVE_PATH, then relax if empty. */
    private fun queryAnyOwnerByNamePreferPathLikeApi33(
        displayName: String,
        preferRelPath: String
    ): Uri? {
        val (likeName, preferSuffix) = likeNameAndSuffix(displayName, preferRelPath)

        fun query(baseUri: Uri, strictRel: Boolean): List<Cand> {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.IS_PENDING,
                "is_trashed"
            )
            val sel = StringBuilder(
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
            )
            val args = mutableListOf(likeName)
            if (strictRel) {
                val rel = relLikes(preferRelPath)
                sel.append(" AND (")
                    .append("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\' OR ")
                    .append("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\' OR ")
                    .append("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\' OR ")
                    .append("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\' OR ")
                    .append("${MediaStore.MediaColumns.RELATIVE_PATH} IS NULL)")
                args += listOf(rel.likeA, rel.likeB, rel.likeC, rel.likeD)
            }
            sel.append(" AND (${MediaStore.MediaColumns.IS_PENDING} IS NULL OR ${MediaStore.MediaColumns.IS_PENDING}=0)")
            sel.append(" AND (is_trashed IS NULL OR is_trashed=0)")
            return queryCandidates(baseUri, projection, sel.toString(), args.toTypedArray())
        }

        var cands = acrossVolumes { query(it, strictRel = true) }
        if (cands.isEmpty()) cands = acrossVolumes { query(it, strictRel = false) }
        if (cands.isEmpty()) return null
        return pickBestByRelAndTime(cands, preferSuffix)
    }

    /** API <= 32: fallback legacy search via DISPLAY_NAME LIKE; prefer RELATIVE_PATH if present. */
    private fun queryByNamePreferPathLikeLegacy(
        displayName: String,
        preferRelPath: String
    ): Uri? {
        val (base, ext) = displayName.substringBeforeLast('.') to
                displayName.substringAfterLast('.', "")
        val likePattern =
            if (ext.isNotEmpty()) "${escapeLike(base)}%.$ext" else "${escapeLike(base)}%"

        data class CandL(val uri: Uri, val rel: String?, val size: Long, val mod: Long)

        fun query(baseUri: Uri): List<CandL> {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.IS_PENDING,
                "is_trashed"
            )
            val out = mutableListOf<CandL>()
            context.contentResolver.query(
                baseUri,
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
                arrayOf(likePattern),
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val rel = c.getColumnIndexOrNull(MediaStore.MediaColumns.RELATIVE_PATH)
                val size = c.getColumnIndexOrNull(MediaStore.MediaColumns.SIZE)
                val mod = c.getColumnIndexOrNull(MediaStore.MediaColumns.DATE_MODIFIED)
                val pend = c.getColumnIndexOrNull(MediaStore.MediaColumns.IS_PENDING)
                val trash = c.getColumnIndexOrNull("is_trashed")
                while (c.moveToNext()) {
                    val s = size?.let { if (it >= 0) c.getLong(it) else -1L } ?: -1L
                    if (s <= 0) continue
                    if (pend?.let { it >= 0 && c.getInt(it) == 1 } == true) continue
                    if (trash?.let { it >= 0 && c.getInt(it) == 1 } == true) continue
                    out += CandL(
                        ContentUris.withAppendedId(baseUri, c.getLong(id)),
                        rel?.let { if (it >= 0) c.getString(it) else null },
                        s,
                        mod?.let { if (it >= 0) c.getLong(it) else 0L } ?: 0L
                    )
                }
            }
            return out
        }

        val cands = acrossVolumes(::query)
        if (cands.isEmpty()) return null
        val preferSuffix = normalizeRelPath(preferRelPath)
        val exact = cands
            .filter { it.rel?.let { r -> normalizeRelPath(r).endsWith(preferSuffix) } == true }
            .maxWithOrNull(compareBy<CandL> { it.mod }.thenBy { it.size })
        return (exact ?: cands.maxWithOrNull(compareBy<CandL> { it.mod }.thenBy { it.size }))?.uri
    }

    // Shared candidate query executor
    private fun queryCandidates(
        baseUri: Uri,
        projection: Array<String>,
        selection: String,
        args: Array<String>
    ): List<Cand> {
        val out = mutableListOf<Cand>()
        context.contentResolver.query(
            baseUri,
            projection,
            selection,
            args,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val rel = c.getColumnIndexOrNull(MediaStore.MediaColumns.RELATIVE_PATH)
            val size = c.getColumnIndexOrNull(MediaStore.MediaColumns.SIZE)
            val mod = c.getColumnIndexOrNull(MediaStore.MediaColumns.DATE_MODIFIED)
            val trash = c.getColumnIndexOrNull("is_trashed")
            while (c.moveToNext()) {
                if (trash?.let { it >= 0 && c.getInt(it) == 1 } == true) continue
                val s = size?.let { if (it >= 0) c.getLong(it) else -1L } ?: -1L
                if (s <= 0) continue
                out += Cand(
                    ContentUris.withAppendedId(baseUri, c.getLong(id)),
                    rel?.let { if (it >= 0) c.getString(it) else null },
                    s,
                    mod?.let { if (it >= 0) c.getLong(it) else 0L } ?: 0L
                )
            }
        }
        return out
    }

    /**
     * Prefer entries whose RELATIVE_PATH ends with the preferred suffix;
     * fallback to freshest by DATE_MODIFIED, then by size.
     */
    private fun pickBestByRelAndTime(cands: List<Cand>, preferSuffix: String): Uri? {
        if (cands.isEmpty()) return null
        val exact = cands
            .filter { it.rel?.let { r -> normalizeRelPath(r).endsWith(preferSuffix) } == true }
            .maxWithOrNull(compareBy<Cand> { it.mod }.thenBy { it.size })
        return (exact ?: cands.maxWithOrNull(compareBy<Cand> { it.mod }.thenBy { it.size }))?.uri
    }

    // ---------------------------------------------------------------------
    // MediaStore write (owned by app)
    // ---------------------------------------------------------------------

    /** Insert a pending Download entry that this app owns (RELATIVE_PATH under Downloads). */
    private fun insertDownloadOwned(displayName: String, relPath: String): Uri = withAppIdentity {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, normalizeRelPath(relPath))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        context.contentResolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            cv
        ) ?: throw IOException("MediaStore insert failed")
    }

    /** Download the model via OkHttp into the given (pending) Uri; publish by clearing IS_PENDING. */
    private fun downloadToUriOwned(dstUri: Uri, url: String, token: String?) = withAppIdentity {
        val req = Request.Builder().url(url).apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }.build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string().orEmpty().take(200)
                throw IOException("HTTP ${resp.code} $body".trim())
            }
            val body = resp.body ?: throw IOException("empty body")
            context.contentResolver.openOutputStream(dstUri, "w")?.use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            } ?: throw IOException("openOutputStream failed: $dstUri")
        }

        context.contentResolver.update(
            dstUri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        )
    }

    /** Log owner info for diagnostics; warn when not owned by this package. */
    private fun verifyOwnerOrWarn(uri: Uri) {
        val owner = getOwner(uri)
        Log.i(TAG, "owner check: $owner expected=${context.packageName}")
        if (owner != null && owner != context.packageName) {
            Log.w(TAG, "unexpected OWNER_PACKAGE_NAME: $owner")
        }
    }

    private fun getOwner(uri: Uri): String? =
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
            null,
            null,
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndexOrNull(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                    ?.let { if (it >= 0) c.getString(it) else null }
            } else {
                null
            }
        }

    // ---------------------------------------------------------------------
    // Misc utils
    // ---------------------------------------------------------------------

    private fun cacheUri(uri: Uri) =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(keyModelUri, uri.toString())
            .apply()

    private fun loadCachedUri(): Uri? =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(keyModelUri, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /**
     * Nudge MediaScanner to index a probable file location (legacy devices).
     * Only meaningful on API 29+; safe no-op otherwise.
     */
    private fun kickMediaScanner(relPath: String, displayName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val base = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        val sub = relPath.removePrefix(Environment.DIRECTORY_DOWNLOADS).trimStart('/')
        val abs = File(
            base,
            if (sub.isEmpty()) displayName else "$sub/$displayName"
        ).absolutePath
        Log.i(TAG, "kickMediaScanner for path=$abs")
        MediaScannerConnection.scanFile(context, arrayOf(abs), null, null)
    }

    /** Escape LIKE special chars for SQL pattern (\, %, _). */
    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    private fun Cursor.getColumnIndexOrNull(name: String): Int? =
        try {
            getColumnIndex(name).takeIf { it >= 0 }
        } catch (_: Throwable) {
            null
        }

    private fun normalizeRelPath(path: String): String =
        if (path.endsWith("/")) path else "$path/"

    /** Best-effort delete; logs warnings but never throws. */
    private fun safeDelete(uri: Uri) {
        runCatching {
            context.contentResolver.delete(uri, null, null)
        }.onFailure {
            Log.w(TAG, "delete failed for $uri : ${it.message}")
        }
    }

    /**
     * Size helper that tolerates IS_PENDING and falls back to AssetFileDescriptor.length().
     */
    private fun getSize(uri: Uri): Long {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_PENDING),
            null,
            null,
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                val pend = c.getColumnIndexOrNull(MediaStore.MediaColumns.IS_PENDING)
                    ?.let { if (it >= 0) c.getInt(it) else 0 } ?: 0
                if (pend == 1) return 0L
                val idx = c.getColumnIndexOrNull(MediaStore.MediaColumns.SIZE)
                if (idx != null && idx >= 0) {
                    c.getLong(idx).takeIf { it > 0 }?.let { return it }
                }
            }
        }
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")
                ?.use { if (it.length >= 0) it.length else 0L } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * Copy from a content Uri to a private file using a temp ".part" for atomic swap.
     */
    private fun copyUriToFile(src: Uri, dst: File) {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, dst.name + ".part")
        try {
            context.contentResolver.openInputStream(src)?.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            } ?: throw IOException("openInputStream failed: $src")
            require(tmp.length() > 0) { "zero bytes after copy" }
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) {
                throw IOException("renameTo failed: ${tmp.absolutePath}")
            }
        } finally {
            if (tmp.exists()) {
                runCatching { tmp.delete() }
            }
        }
    }
}
