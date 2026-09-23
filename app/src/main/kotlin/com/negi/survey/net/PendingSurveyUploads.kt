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
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shared classifier for staged survey JSON files in the GitHub pending directory. */
internal object PendingSurveyUploads {
    private const val PENDING_DIR_GH = "pending_uploads"

    /**
     * One logical pending survey and every direct-file artifact currently associated with it.
     *
     * [canonicalFile] is the lexically first file name in the normalized-ID group. The remaining
     * files are retained in [duplicateFiles] rather than being changed or discarded.
     */
    data class PendingSurveyCandidate(
        val normalizedSurveyId: String,
        val canonicalFile: File,
        val duplicateFiles: List<File>
    )

    /** Read-only snapshot of classified direct files in a pending-upload directory. */
    data class PendingSurveyDiscovery(
        val candidates: List<PendingSurveyCandidate>,
        val unclassifiedFiles: List<File>
    )

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

    /** Returns a read-only grouped snapshot of direct files in files/pending_uploads. */
    fun discoverPendingSurveys(context: Context): PendingSurveyDiscovery =
        discoverPendingSurveys(File(context.filesDir, PENDING_DIR_GH))

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

    /**
     * Classifies direct pending files without changing them.
     *
     * New grouped discovery uses the same trim rule as legacy parsing, then lowercases with
     * [Locale.US] to align its logical IDs with the uploaded-survey ledger. Legacy APIs retain
     * their existing case-preserving behavior.
     */
    internal fun discoverPendingSurveys(directory: File): PendingSurveyDiscovery {
        val groupedFiles = linkedMapOf<String, MutableList<File>>()
        val unclassifiedFiles = mutableListOf<File>()

        directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile }
            .sortedBy { it.name }
            .forEach { file ->
                val normalizedSurveyId = surveyIdFromFile(file)
                    ?.let(::normalizeGroupedSurveyId)
                if (normalizedSurveyId == null) {
                    unclassifiedFiles += file
                } else {
                    groupedFiles.getOrPut(normalizedSurveyId) { mutableListOf() } += file
                }
            }

        val candidates = groupedFiles
            .map { (normalizedSurveyId, files) ->
                PendingSurveyCandidate(
                    normalizedSurveyId = normalizedSurveyId,
                    canonicalFile = files.first(),
                    duplicateFiles = files.drop(1)
                )
            }
            .sortedBy { it.normalizedSurveyId }

        return PendingSurveyDiscovery(
            candidates = candidates,
            unclassifiedFiles = unclassifiedFiles
        )
    }

    private fun normalizeGroupedSurveyId(surveyId: String): String? =
        surveyId.trim()
            .lowercase(Locale.US)
            .takeIf { it.isNotBlank() }
}
