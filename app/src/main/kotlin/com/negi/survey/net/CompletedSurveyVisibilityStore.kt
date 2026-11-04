package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/** Display-only state. This store never writes to upload completion or recovery evidence. */
internal class CompletedSurveyVisibilityStore internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("completed_survey_visibility_v1", Context.MODE_PRIVATE)
    )

    fun visibleRecords(records: List<UploadedSurveyStore.UploadedSurveyRecord>): List<UploadedSurveyStore.UploadedSurveyRecord> =
        synchronized(LOCK) {
            val hidden = prefs.getStringSet(HIDDEN_IDS, emptySet()).orEmpty()
            records.filterNot { normalize(it.surveyId) in hidden }
        }

    /** Hides only the dialog snapshot, never completions that arrived after it was loaded. */
    fun hideCompleted(displayedIds: Set<String>, uploadedIds: Set<String>) = synchronized(LOCK) {
        val recorded = uploadedIds.mapTo(mutableSetOf(), ::normalize)
        val hidden = prefs.getStringSet(HIDDEN_IDS, emptySet()).orEmpty()
        // Prune obsolete display IDs without imposing an eviction limit that would reveal old rows.
        val updated = (hidden + displayedIds.map(::normalize)).intersect(recorded)
        // Always commit, including after an earlier failed commit changed Android's memory cache.
        check(prefs.edit().putStringSet(HIDDEN_IDS, updated).commit()) {
            "Could not save completed-list visibility. Upload history is unchanged."
        }
    }

    private fun normalize(id: String) = id.trim().lowercase(Locale.US)

    private companion object {
        const val HIDDEN_IDS = "hidden_survey_ids"
        val LOCK = Any()
    }
}
