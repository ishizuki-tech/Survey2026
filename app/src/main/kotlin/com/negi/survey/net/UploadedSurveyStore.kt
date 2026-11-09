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
import java.util.IdentityHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Private, process-safe ledger of successfully uploaded survey UUIDs. */
class UploadedSurveyStore internal constructor(
    private val prefs: SharedPreferences
) {

    constructor(context: Context) : this(context, v2 = false)

    internal constructor(context: Context, v2: Boolean) : this(
        context.applicationContext.getSharedPreferences(
            if (v2) "uploaded_survey_ledger_v2" else PREF_NAME,
            Context.MODE_PRIVATE
        )
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
                    editor.putString(KEY_EXPORTED_AT_JSON, encodeMap(updated))
                    changed = true
                }
            }

            if (changed) {
                check(editor.commit()) { "Failed to persist uploaded survey ledger." }
            }
        }
    }

    /**
     * Atomically records a remotely confirmed survey payload and the SHA-256 of the exact UTF-8
     * bytes sent to (or read back from) GitHub.  Historical ID-only entries are deliberately not
     * upgraded here: doing so could manufacture proof from a later local file.
     */
    fun markConfirmedUploaded(
        surveyId: String,
        exportedAt: String?,
        payloadFingerprint: String,
        routeFingerprint: String? = null,
    ) {
        val normalized = requireNotNull(normalize(surveyId)) { "Survey ID is blank." }
        require(SurveyUploadIdempotency.isPayloadFingerprint(payloadFingerprint)) {
            "Payload fingerprint must be a SHA-256 hex digest."
        }
        val trimmedExportedAt = exportedAt?.trim()?.takeIf { it.isNotBlank() }

        synchronized(LOCK) {
            val ids = prefs.getStringSet(KEY_SURVEY_IDS, emptySet()).orEmpty().toMutableSet()
            val fingerprints = readPayloadFingerprintMapLocked()
            val priorFingerprint = fingerprints[normalized]
            val routes = readStringMapLocked(KEY_ROUTE_FINGERPRINT_JSON).toMutableMap()
            check(!ids.contains(normalized) || routes[normalized] == routeFingerprint) {
                "Existing uploaded survey record has no matching route fingerprint."
            }
            routeFingerprint?.let {
                require(SurveyUploadIdempotency.isPayloadFingerprint(it))
                routes[normalized] = it
            }
            check(!ids.contains(normalized) || priorFingerprint == payloadFingerprint) {
                "Existing uploaded survey record has no matching payload fingerprint."
            }

            val exportedAtById = readExportedAtMapLocked().toMutableMap()
            if (trimmedExportedAt != null) exportedAtById[normalized] = trimmedExportedAt
            ids += normalized
            val updatedFingerprints = fingerprints.toMutableMap().apply {
                put(normalized, payloadFingerprint)
            }
            // SharedPreferences applies changes in memory before its disk commit. Never treat
            // the in-memory result of a failed commit as durable cleanup evidence.
            val pending = unconfirmedWrites.getOrPut(prefs) { mutableSetOf() }
            pending += normalized
            val persisted = prefs.edit()
                .putStringSet(KEY_SURVEY_IDS, ids)
                .putString(KEY_EXPORTED_AT_JSON, encodeMap(exportedAtById))
                .putString(KEY_PAYLOAD_FINGERPRINT_JSON, encodeMap(updatedFingerprints))
                .putString(KEY_ROUTE_FINGERPRINT_JSON, encodeMap(routes))
                .commit()
            check(persisted) { "Failed to persist confirmed uploaded survey ledger." }
            pending -= normalized
            if (pending.isEmpty()) unconfirmedWrites.remove(prefs)
        }
    }

    internal fun confirms(surveyId: String, payloadFingerprint: String, routeFingerprint: String): Boolean =
        synchronized(LOCK) {
            payloadFingerprintFor(surveyId) == payloadFingerprint &&
                readStringMapLocked(KEY_ROUTE_FINGERPRINT_JSON)[normalize(surveyId)] == routeFingerprint
        }

    /** Returns the recorded `exported_at` stamp for [surveyId], or null if none is known. */
    fun exportedAtFor(surveyId: String): String? {
        val normalized = normalize(surveyId) ?: return null
        return synchronized(LOCK) { readExportedAtMapLocked()[normalized] }
    }

    /** Returns a valid recorded SHA-256 only; missing and malformed historical values are unusable. */
    fun payloadFingerprintFor(surveyId: String): String? {
        val normalized = normalize(surveyId) ?: return null
        return synchronized(LOCK) {
            if (normalized !in durableIds()) return@synchronized null
            readPayloadFingerprintMapLocked()[normalized]
        }
    }

    /** Returns the number of unique survey UUIDs successfully recorded. */
    fun uploadedCount(): Int = synchronized(LOCK) {
        durableIds().size
    }

    /** Returns a read-only snapshot of every survey UUID successfully recorded. */
    fun uploadedSurveyIds(): Set<String> = synchronized(LOCK) {
        durableIds().toSet()
    }

    /** One successfully uploaded survey, paired with its known export time (if any). */
    data class UploadedSurveyRecord(
        val surveyId: String,
        val exportedAt: String?,
        val payloadFingerprint: String? = null
    )

    /** Read-only snapshot of every uploaded survey, each paired with its export time if known. */
    fun uploadedSurveyRecords(): List<UploadedSurveyRecord> = synchronized(LOCK) {
        val ids = durableIds()
        val exportedAtById = readExportedAtMapLocked()
        val fingerprints = readPayloadFingerprintMapLocked()
        ids.map { id -> UploadedSurveyRecord(id, exportedAtById[id], fingerprints[id]) }
    }

    /** Returns true only when [surveyId] was previously recorded successfully. */
    fun isUploaded(surveyId: String): Boolean {
        val normalized = normalize(surveyId) ?: return false
        return synchronized(LOCK) { normalized in durableIds() }
    }

    /** Must be called while holding [LOCK]. */
    private fun durableIds(): Set<String> =
        prefs.getStringSet(KEY_SURVEY_IDS, emptySet()).orEmpty() - unconfirmedWrites[prefs].orEmpty()

    private fun normalize(surveyId: String): String? =
        surveyId.trim()
            .lowercase(Locale.US)
            .takeIf { it.isNotBlank() }

    /** Must be called while holding [LOCK]. */
    private fun readExportedAtMapLocked(): Map<String, String> {
        return readStringMapLocked(KEY_EXPORTED_AT_JSON)
    }

    /** Must be called while holding [LOCK]. */
    private fun readPayloadFingerprintMapLocked(): Map<String, String> =
        readStringMapLocked(KEY_PAYLOAD_FINGERPRINT_JSON)
            .filterValues(SurveyUploadIdempotency::isPayloadFingerprint)

    /** Must be called while holding [LOCK]. */
    private fun readStringMapLocked(key: String): Map<String, String> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
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

    private fun encodeMap(map: Map<String, String>): String =
        buildJsonObject { map.forEach { (k, v) -> put(k, v) } }.toString()

    private companion object {
        private const val PREF_NAME = "uploaded_survey_ledger_v1"
        private const val KEY_SURVEY_IDS = "survey_ids"
        private const val KEY_EXPORTED_AT_JSON = "exported_at_by_id"
        private const val KEY_ROUTE_FINGERPRINT_JSON = "route_fingerprint_by_id"
        private const val KEY_PAYLOAD_FINGERPRINT_JSON = "payload_fingerprint_by_id"
        private val LOCK = Any()
        private val unconfirmedWrites = IdentityHashMap<SharedPreferences, MutableSet<String>>()
    }
}
