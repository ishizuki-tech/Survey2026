/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: ExportUtils.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Helper for exporting survey JSON and recorded voice into a common
 * "exports" directory that GitHubUploadWorker (or similar) can upload
 * to the SurveyExports repo.
 *
 * Layout:
 *   <external-or-internal>/exports/
 *     └─ voice/
 *          ├─ voice_<...>_YYYYMMDD_HHmmss.wav
 *          └─ voice_<...>_YYYYMMDD_HHmmss.meta.json
 *
 * Design goals:
 * - Prefer app-scoped external storage when available (no runtime permission).
 * - Keep file naming deterministic and safe across file systems.
 * - Avoid partially-written artifacts with best-effort atomic writes.
 * - Provide a tiny sidecar metadata file to support later lookup and upload.
 */
object ExportUtils {

    private const val TAG = "ExportUtils"

    private const val EXPORT_DIR_NAME = "exports"
    private const val VOICE_SUBDIR_NAME = "voice"
    private const val META_SUFFIX = ".meta.json"

    /**
     * WAV files smaller than this size are treated as empty recordings.
     *
     * Standard PCM WAV header is 44 bytes.
     */
    private const val WAV_HEADER_BYTES = 44L

    /**
     * Maximum length for each ID segment used in file naming.
     *
     * This prevents extremely long survey/question IDs from producing
     * file names that exceed filesystem limits.
     */
    private const val MAX_SEGMENT_LEN = 48

    /**
     * Time format for file naming.
     *
     * We use UTC to make exported names stable across devices and locales.
     */
    private val FILE_TS_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Time format for meta JSON.
     */
    private val META_TS_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Returns the base directory for export files.
     *
     * Storage resolution:
     * - Uses app-specific external files dir when available.
     * - Falls back to internal files dir otherwise.
     *
     * Example location:
     *   Android/data/<package>/files/exports
     */
    fun getExportBaseDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val exportDir = File(base, EXPORT_DIR_NAME)
        ensureDir(exportDir, "getExportBaseDir")
        return exportDir
    }

    /**
     * Returns the directory for exported voice files:
     *
     *   getExportBaseDir(context)/voice
     */
    fun getVoiceExportDir(context: Context): File {
        val base = getExportBaseDir(context)
        val voiceDir = File(base, VOICE_SUBDIR_NAME)
        ensureDir(voiceDir, "getVoiceExportDir")
        return voiceDir
    }

    /**
     * Copy the recorded voice file into the export voice directory and
     * return the new [File].
     *
     * Validation:
     * - [source] must exist and be a file.
     * - [source] must be larger than a minimal WAV header size.
     *
     * Naming:
     * - Base pattern:
     *     voice_<surveyId>_<questionId>_YYYYMMDD_HHmmss.wav
     * - Each optional ID segment is sanitized and length-capped.
     * - A uniqueness suffix is added if a collision occurs.
     *
     * Atomicity:
     * - The copy is performed into a temporary ".part" file first.
     * - The final file is then created via rename when possible.
     *
     * Sidecar:
     * - A best-effort meta JSON is emitted next to the WAV.
     * - Keys "survey_id" and "question_id" are omitted when null/blank.
     *
     * @throws IOException When validation fails or the copy cannot be completed.
     */
    @Throws(IOException::class)
    fun exportRecordedVoice(
        context: Context,
        source: File,
        surveyId: String? = null,
        questionId: String? = null
    ): File {
        if (!source.exists() || !source.isFile) {
            val msg = "Source audio file does not exist: ${source.absolutePath}"
            Log.e(TAG, msg)
            throw IOException(msg)
        }

        val len = source.length()
        if (len <= WAV_HEADER_BYTES) {
            val msg = "Source audio file is too small or empty: ${source.absolutePath} (size=$len)"
            Log.e(TAG, msg)
            throw IOException(msg)
        }

        val voiceDir = getVoiceExportDir(context)

        val time = FILE_TS_FORMAT.format(Date())

        val safeSurvey = surveyId
            ?.takeIf { it.isNotBlank() }
            ?.sanitizeSegment()

        val safeQuestion = questionId
            ?.takeIf { it.isNotBlank() }
            ?.sanitizeSegment()

        val rawPrefix = buildString {
            append("voice")
            if (safeSurvey != null) append("_").append(safeSurvey)
            if (safeQuestion != null) append("_").append(safeQuestion)
        }

        val safePrefix = rawPrefix.sanitizeForFileName().ifBlank { "voice" }

        val baseName = "${safePrefix}_$time"
        val target = makeUniqueFile(voiceDir, baseName, "wav")

        Log.d(
            TAG,
            "exportRecordedVoice: copying ${source.absolutePath} -> ${target.absolutePath}"
        )

        // Copy with best-effort atomic semantics.
        copyFileAtomic(source, target)

        val outSize = target.length()
        if (outSize <= WAV_HEADER_BYTES) {
            val msg = "exportRecordedVoice: exported file is unexpectedly small: ${target.absolutePath} (size=$outSize)"
            Log.e(TAG, msg)
            throw IOException(msg)
        }

        Log.d(TAG, "exportRecordedVoice: done, size=$outSize bytes")

        // Best-effort sidecar meta JSON.
        writeVoiceMeta(
            dir = voiceDir,
            wavName = target.name,
            surveyId = surveyId,
            questionId = questionId
        )

        return target
    }

    /**
     * Write a small sidecar JSON file for a voice recording:
     *
     *   voice_<...>.wav  ->  voice_<...>.meta.json
     *
     * Payload shape:
     * {
     *   "file_name": "voice_....wav",
     *   "survey_id": "...",        // omitted when null/blank
     *   "question_id": "...",      // omitted when null/blank
     *   "created_at": "2025-11-25T10:20:30Z"
     * }
     *
     * Failure policy:
     * - This method is best-effort. Failures are logged and ignored.
     */
    private fun writeVoiceMeta(
        dir: File,
        wavName: String,
        surveyId: String?,
        questionId: String?
    ) {
        val base = wavName.substringBeforeLast('.', wavName)
        val metaFile = File(dir, base + META_SUFFIX)

        val createdAt = META_TS_FORMAT.format(Date())

        val json = buildString {
            append("{\n")
            append("  \"file_name\": \"")
                .append(wavName.escapeJson())
                .append("\",\n")

            if (!surveyId.isNullOrBlank()) {
                append("  \"survey_id\": \"")
                    .append(surveyId.escapeJson())
                    .append("\",\n")
            }
            if (!questionId.isNullOrBlank()) {
                append("  \"question_id\": \"")
                    .append(questionId.escapeJson())
                    .append("\",\n")
            }

            append("  \"created_at\": \"")
                .append(createdAt.escapeJson())
                .append("\"\n")
            append("}\n")
        }

        runCatching {
            writeTextAtomic(metaFile, json)
            Log.d(TAG, "writeVoiceMeta: wrote ${metaFile.absolutePath}")
        }.onFailure { e ->
            Log.w(TAG, "writeVoiceMeta: failed, ignoring", e)
        }
    }

    /**
     * Ensure the directory exists, logging a warning on failure.
     */
    private fun ensureDir(dir: File, caller: String) {
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "$caller: failed to mkdirs() for ${dir.absolutePath}")
        }
    }

    /**
     * Create a unique file under [dir] using [baseName] and [ext].
     *
     * If "baseName.ext" already exists, it will attempt:
     * - baseName-2.ext
     * - baseName-3.ext
     * ...
     */
    private fun makeUniqueFile(dir: File, baseName: String, ext: String): File {
        var candidate = File(dir, "$baseName.$ext")
        if (!candidate.exists()) return candidate

        var index = 2
        while (true) {
            candidate = File(dir, "$baseName-$index.$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    /**
     * Copy a file using a best-effort atomic strategy:
     * - Write into "<target>.part"
     * - Rename to the final target
     *
     * If rename fails (rare on some filesystems), falls back to
     * a direct stream copy into the target.
     */
    @Throws(IOException::class)
    private fun copyFileAtomic(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null) ensureDir(parent, "copyFileAtomic")

        val part = File(parent, target.name + ".part")

        // Clean up any stale partial file.
        runCatching { if (part.exists()) part.delete() }

        try {
            source.inputStream().use { input ->
                part.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "copyFileAtomic: copy-to-part failed", e)
            runCatching { part.delete() }
            throw e
        }

        // Replace existing target if needed.
        if (target.exists() && !target.delete()) {
            Log.w(TAG, "copyFileAtomic: failed to delete existing ${target.absolutePath}")
        }

        // Try atomic-ish rename.
        if (!part.renameTo(target)) {
            Log.w(TAG, "copyFileAtomic: rename failed, falling back to direct copy")

            try {
                source.inputStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "copyFileAtomic: direct copy failed", e)
                throw e
            } finally {
                runCatching { part.delete() }
            }
        }
    }

    /**
     * Write text atomically to reduce the risk of partial/corrupt files.
     */
    private fun writeTextAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")

        // Best-effort cleanup of stale temp file.
        runCatching { if (tmp.exists()) tmp.delete() }

        tmp.writeText(text, Charsets.UTF_8)

        if (target.exists() && !target.delete()) {
            Log.w(TAG, "writeTextAtomic: failed to delete existing ${target.absolutePath}")
        }

        if (!tmp.renameTo(target)) {
            // Fallback to non-atomic write if rename fails for any reason.
            Log.w(TAG, "writeTextAtomic: rename failed, falling back to direct write")
            target.writeText(text, Charsets.UTF_8)
            runCatching { tmp.delete() }
        }
    }

    /**
     * Convert an ID segment into a file-name-safe representation with a length cap.
     */
    private fun String.sanitizeSegment(): String {
        val safe = sanitizeForFileName()
        return if (safe.length <= MAX_SEGMENT_LEN) safe else safe.take(MAX_SEGMENT_LEN)
    }

    /**
     * Simple file-name sanitizer that replaces non [A-Za-z0-9_-] chars with '_'.
     */
    private fun String.sanitizeForFileName(): String {
        return replace(Regex("""[^\w\-]+"""), "_").trim('_')
    }

    /**
     * Minimal JSON string escaper for meta payload.
     */
    private fun String.escapeJson(): String =
        buildString(length + 8) {
            for (ch in this@escapeJson) {
                when (ch) {
                    '\"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
}
