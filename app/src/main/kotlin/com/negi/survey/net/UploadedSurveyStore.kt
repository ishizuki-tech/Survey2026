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

/** Private, process-safe ledger of successfully uploaded survey UUIDs. */
class UploadedSurveyStore internal constructor(
    private val prefs: SharedPreferences
) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    )

    /** Records [surveyId] once. Blank IDs are ignored. */
    fun markUploaded(surveyId: String) {
        val normalized = normalize(surveyId) ?: return

        synchronized(LOCK) {
            val ids = prefs.getStringSet(KEY_SURVEY_IDS, emptySet())
                .orEmpty()
                .toMutableSet()

            if (!ids.add(normalized)) return

            check(
                prefs.edit()
                    .putStringSet(KEY_SURVEY_IDS, ids)
                    .commit()
            ) { "Failed to persist uploaded survey ledger." }
        }
    }

    /** Returns the number of unique survey UUIDs successfully recorded. */
    fun uploadedCount(): Int = synchronized(LOCK) {
        prefs.getStringSet(KEY_SURVEY_IDS, emptySet()).orEmpty().size
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

    private companion object {
        private const val PREF_NAME = "uploaded_survey_ledger_v1"
        private const val KEY_SURVEY_IDS = "survey_ids"
        private val LOCK = Any()
    }
}
