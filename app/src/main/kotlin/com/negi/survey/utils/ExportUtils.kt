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
import com.negi.survey.net.V2ArtifactStore
import com.negi.survey.net.RecordingIdentity
import kotlinx.serialization.json.Json

/** Export helpers. Finalized WAVs and their metadata are published together in the V2 store. */
object ExportUtils {

    private const val TAG = "ExportUtils"

    private const val EXPORT_DIR_NAME = "exports"
    private const val VOICE_SUBDIR_NAME = "voice"
    private const val WAV_HEADER_BYTES = 44L

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

    /** Copies an actual recording once and returns its immutable JSON-reference basename. */
    @Throws(IOException::class)
    internal fun exportRecordedVoice(
        context: Context,
        source: File,
        recording: RecordingIdentity,
    ): V2ArtifactStore.Entry {
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

        val store = V2ArtifactStore(context)
        val paths = store.paths(context, recording.prompt.surveyId)
        val path = paths.voiceWav(recording.prompt.questionId, recording.id)
        return store.stage(
            recording.id, V2ArtifactStore.Kind.VOICE, paths.surveyUuid, V2ArtifactStore.profile,
            path, Json.encodeToString(recording),
        ) { output -> source.inputStream().use { it.copyTo(output) } }
    }

    private fun ensureDir(dir: File, caller: String) {
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "$caller: failed to mkdirs() for ${dir.absolutePath}")
        }
    }
}
