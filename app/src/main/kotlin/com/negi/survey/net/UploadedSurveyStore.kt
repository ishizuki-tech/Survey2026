/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: UploadedSurveyStore.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Persists the UUIDs of survey JSON payloads confirmed uploaded to GitHub.
 *  The UUID set is an idempotency ledger for a future device-wide upload
 *  total; it does not contain interviewer identity or other survey content.
 * =====================================================================
 */

package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Private, process-safe ledger of successfully uploaded survey UUIDs. */
class UploadedSurveyStore internal constructor(
    private val prefs: SharedPreferences
) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    )

    /**
     * Records [surveyId] once, optionally alongside [exportedAt] (the survey's own
     * `exported_at` stamp, i.e. when the survey was concluded on-device).
     *
     * Safe to call again for an already-recorded ID: a previously missing
     * [exportedAt] is filled in, but nothing is overwritten otherwise. Blank
     * IDs are ignored.
     */
    fun markUploaded(surveyId: String, exportedAt: String? = null) {
        val normalized = normalize(surveyId) ?: return
        val trimmedExportedAt = exportedAt?.trim()?.takeIf { it.isNotBlank() }

        synchronized(LOCK) {
            val ids = prefs.getStringSet(KEY_SURVEY_IDS, emptySet())
                .orEmpty()
                .toMutableSet()

            val editor = prefs.edit()
            var changed = false

            if (ids.add(normalized)) {
                editor.putStringSet(KEY_SURVEY_IDS, ids)
                changed = true
            }

            if (trimmedExportedAt != null) {
                val exportedAtById = readExportedAtMapLocked()
                if (exportedAtById[normalized] != trimmedExportedAt) {
                    val updated = exportedAtById.toMutableMap()
                    updated[normalized] = trimmedExportedAt
                    editor.putString(KEY_EXPORTED_AT_JSON, encodeExportedAtMap(updated))
                    changed = true
                }
            }

            if (changed) {
                check(editor.commit()) { "Failed to persist uploaded survey ledger." }
            }
        }
    }

    /** Returns the recorded `exported_at` stamp for [surveyId], or null if none is known. */
    fun exportedAtFor(surveyId: String): String? {
        val normalized = normalize(surveyId) ?: return null
        return synchronized(LOCK) { readExportedAtMapLocked()[normalized] }
    }

    /**
     * Clears every recorded uploaded survey ID and its export time.
     *
     * This only resets local bookkeeping (what this device believes it has
     * uploaded) — it does not affect anything already uploaded remotely, and
     * there is nothing left on-device to re-upload for a cleared entry, since
     * its staged file was already deleted on successful upload.
     */
    fun clear() {
        synchronized(LOCK) {
            check(
                prefs.edit()
                    .remove(KEY_SURVEY_IDS)
                    .remove(KEY_EXPORTED_AT_JSON)
                    .commit()
            ) { "Failed to clear uploaded survey ledger." }
        }
    }

    /** Returns the number of unique survey UUIDs successfully recorded. */
    fun uploadedCount(): Int = synchronized(LOCK) {
        prefs.getStringSet(KEY_SURVEY_IDS, emptySet()).orEmpty().size
    }

    /** Returns a read-only snapshot of every survey UUID successfully recorded. */
    fun uploadedSurveyIds(): Set<String> = synchronized(LOCK) {
        prefs.getStringSet(KEY_SURVEY_IDS, emptySet()).orEmpty().toSet()
    }

    /** One successfully uploaded survey, paired with its known export time (if any). */
    data class UploadedSurveyRecord(val surveyId: String, val exportedAt: String?)

    /** Read-only snapshot of every uploaded survey, each paired with its export time if known. */
    fun uploadedSurveyRecords(): List<UploadedSurveyRecord> = synchronized(LOCK) {
        val ids = prefs.getStringSet(KEY_SURVEY_IDS, emptySet()).orEmpty()
        val exportedAtById = readExportedAtMapLocked()
        ids.map { id -> UploadedSurveyRecord(surveyId = id, exportedAt = exportedAtById[id]) }
    }

    /** Returns true only when [surveyId] was previously recorded successfully. */
    fun isUploaded(surveyId: String): Boolean {
        val normalized = normalize(surveyId) ?: return false
        return synchronized(LOCK) {
            prefs.getStringSet(KEY_SURVEY_IDS, emptySet()).orEmpty().contains(normalized)
        }
    }

    private fun normalize(surveyId: String): String? =
        surveyId.trim()
            .lowercase(Locale.US)
            .takeIf { it.isNotBlank() }

    /** Must be called while holding [LOCK]. */
    private fun readExportedAtMapLocked(): Map<String, String> {
        val raw = prefs.getString(KEY_EXPORTED_AT_JSON, null) ?: return emptyMap()
        return runCatching {
            val root = Json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching emptyMap()
            root.entries
                .mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?.takeIf { it.isNotBlank() }
                        ?.let { k to it }
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private fun encodeExportedAtMap(map: Map<String, String>): String =
        buildJsonObject { map.forEach { (k, v) -> put(k, v) } }.toString()

    private companion object {
        private const val PREF_NAME = "uploaded_survey_ledger_v1"
        private const val KEY_SURVEY_IDS = "survey_ids"
        private const val KEY_EXPORTED_AT_JSON = "exported_at_by_id"
        private val LOCK = Any()
    }
}
