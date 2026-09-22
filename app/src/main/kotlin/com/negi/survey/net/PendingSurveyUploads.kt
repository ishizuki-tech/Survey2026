/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: PendingSurveyUploads.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.net

import android.content.Context
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shared classifier for staged survey JSON files in the GitHub pending directory. */
internal object PendingSurveyUploads {
    private const val PENDING_DIR_GH = "pending_uploads"

    /** Returns a nonblank top-level survey ID, or null when [file] is not a survey JSON payload. */
    fun surveyIdFromFile(file: File): String? {
        if (!file.isFile || !file.extension.equals("json", ignoreCase = true)) return null

        return runCatching {
            val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
                ?: return@runCatching null
            (root["survey_id"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** Returns unique survey IDs from direct files in files/pending_uploads only. */
    fun pendingSurveyIds(context: Context): Set<String> =
        pendingSurveyIds(File(context.filesDir, PENDING_DIR_GH))

    /** Returns one deterministic existing staged JSON file for [surveyId], if present. */
    fun findPendingSurveyFile(context: Context, surveyId: String): File? =
        findPendingSurveyFile(File(context.filesDir, PENDING_DIR_GH), surveyId)

    /** Visible for JVM tests and recovery callers that already have the pending directory. */
    internal fun pendingSurveyIds(directory: File): Set<String> =
        directory.listFiles()
            .orEmpty()
            .asSequence()
            .mapNotNull(::surveyIdFromFile)
            .toSet()

    internal fun findPendingSurveyFile(directory: File, surveyId: String): File? {
        val target = surveyId.trim()
        if (target.isBlank()) return null
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { surveyIdFromFile(it) == target }
            .sortedBy { it.name }
            .firstOrNull()
    }
}
