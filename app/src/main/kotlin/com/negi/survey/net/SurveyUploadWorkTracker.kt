/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyUploadWorkTracker.kt
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */
package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Durable pointer to the current application-owned WorkManager request for one logical survey. */
internal class SurveyUploadWorkTracker internal constructor(
    private val prefs: SharedPreferences,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    )

    enum class Phase {
        PREPARED,
        ENQUEUED
    }

    enum class IdentityKind {
        LOGICAL,
        LEGACY
    }

    data class TrackedSurveyWork(
        val normalizedSurveyId: String,
        val workRequestId: UUID,
        val phase: Phase,
        val identityKind: IdentityKind,
        val updatedAtEpochMs: Long
    )

    /** Returns the current record for [surveyId], or null for invalid/corrupt records. */
    fun get(surveyId: String): TrackedSurveyWork? {
        val normalizedSurveyId = normalizeSurveyId(surveyId) ?: return null
        return synchronized(LOCK) { readLocked(normalizedSurveyId) }
    }

    /** Persists a replacement PREPARED record for one logical survey. */
    fun prepare(
        surveyId: String,
        requestId: UUID,
        identityKind: IdentityKind
    ): TrackedSurveyWork? {
        val normalizedSurveyId = normalizeSurveyId(surveyId) ?: return null
        val record = TrackedSurveyWork(
            normalizedSurveyId = normalizedSurveyId,
            workRequestId = requestId,
            phase = Phase.PREPARED,
            identityKind = identityKind,
            updatedAtEpochMs = nowEpochMs()
        )
        return synchronized(LOCK) {
            if (writeLocked(record)) record else null
        }
    }

    /** Transitions only the matching PREPARED record to ENQUEUED. */
    fun markEnqueuedIfMatches(surveyId: String, requestId: UUID): Boolean {
        val normalizedSurveyId = normalizeSurveyId(surveyId) ?: return false
        return synchronized(LOCK) {
            val current = readLocked(normalizedSurveyId) ?: return@synchronized false
            if (current.workRequestId != requestId || current.phase != Phase.PREPARED) {
                return@synchronized false
            }
            writeLocked(current.copy(phase = Phase.ENQUEUED, updatedAtEpochMs = nowEpochMs()))
        }
    }

    /** Removes only the record owned by [requestId]. */
    fun clearIfMatches(surveyId: String, requestId: UUID): Boolean {
        val normalizedSurveyId = normalizeSurveyId(surveyId) ?: return false
        return synchronized(LOCK) {
            val current = readLocked(normalizedSurveyId) ?: return@synchronized false
            if (current.workRequestId != requestId) return@synchronized false
            removeLocked(normalizedSurveyId)
        }
    }

    /** Removes the current tracker pointer once a separate completion ledger has won. */
    fun clearForUploaded(surveyId: String): Boolean {
        val normalizedSurveyId = normalizeSurveyId(surveyId) ?: return false
        return synchronized(LOCK) { removeLocked(normalizedSurveyId) }
    }

    /** Stable collision-resistant key used for one normalized logical survey ID. */
    fun storageKeyFor(surveyId: String): String? =
        normalizeSurveyId(surveyId)?.let { "survey:${sha256Hex(it)}" }

    private fun readLocked(normalizedSurveyId: String): TrackedSurveyWork? {
        val key = storageKeyFor(normalizedSurveyId) ?: return null
        val storedSurveyId = prefs.getString("$key:surveyId", null) ?: return null
        if (storedSurveyId != normalizedSurveyId) return null
        val requestId = runCatching {
            UUID.fromString(prefs.getString("$key:requestId", null))
        }.getOrNull() ?: return null
        val phase = runCatching {
            Phase.valueOf(prefs.getString("$key:phase", null).orEmpty())
        }.getOrNull() ?: return null
        val identityKind = runCatching {
            IdentityKind.valueOf(prefs.getString("$key:identityKind", null).orEmpty())
        }.getOrNull() ?: return null
        val updatedAtEpochMs = prefs.getLong("$key:updatedAtEpochMs", -1L)
        if (updatedAtEpochMs < 0L) return null
        return TrackedSurveyWork(
            normalizedSurveyId = storedSurveyId,
            workRequestId = requestId,
            phase = phase,
            identityKind = identityKind,
            updatedAtEpochMs = updatedAtEpochMs
        )
    }

    private fun writeLocked(record: TrackedSurveyWork): Boolean {
        val key = storageKeyFor(record.normalizedSurveyId) ?: return false
        return prefs.edit()
            .putString("$key:surveyId", record.normalizedSurveyId)
            .putString("$key:requestId", record.workRequestId.toString())
            .putString("$key:phase", record.phase.name)
            .putString("$key:identityKind", record.identityKind.name)
            .putLong("$key:updatedAtEpochMs", record.updatedAtEpochMs)
            .commit()
    }

    private fun removeLocked(normalizedSurveyId: String): Boolean {
        val key = storageKeyFor(normalizedSurveyId) ?: return false
        return prefs.edit()
            .remove("$key:surveyId")
            .remove("$key:requestId")
            .remove("$key:phase")
            .remove("$key:identityKind")
            .remove("$key:updatedAtEpochMs")
            .commit()
    }

    private fun normalizeSurveyId(surveyId: String): String? =
        surveyId.trim()
            .lowercase(Locale.US)
            .takeIf { it.isNotBlank() }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }

    private companion object {
        const val PREF_NAME = "survey_upload_work_tracking_v1"
        val LOCK = Any()
    }
}
