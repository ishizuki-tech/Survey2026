package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.Locale

/**
 * Durable destination reservation for staged survey JSON.
 *
 * A missing record deliberately means legacy/unknown, never canonical. This lets an upgraded app
 * distinguish a newly staged canonical artifact from a historical artifact whose WorkManager
 * request did not survive process death.
 */
internal class SurveyUploadRouteStore internal constructor(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    )

    fun reserveCanonical(surveyId: String): Boolean {
        val normalized = normalize(surveyId) ?: return false
        return synchronized(LOCK) {
            val key = keyFor(normalized)
            val existing = prefs.getString(key, null)
            when (existing) {
                null, CANONICAL_V1 -> prefs.edit().putString(key, CANONICAL_V1).commit()
                else -> false
            }
        }
    }

    fun protocolFor(surveyId: String): SurveyUploadWork.SurveyUploadProtocol? {
        val normalized = normalize(surveyId) ?: return null
        return synchronized(LOCK) {
            when (prefs.getString(keyFor(normalized), null)) {
                CANONICAL_V1 -> SurveyUploadWork.SurveyUploadProtocol.CANONICAL_IMMUTABLE_V1
                else -> null
            }
        }
    }

    fun clearCanonical(surveyId: String): Boolean {
        val normalized = normalize(surveyId) ?: return false
        return synchronized(LOCK) { prefs.edit().remove(keyFor(normalized)).commit() }
    }

    private fun normalize(surveyId: String): String? =
        SurveyUploadWork.normalizeLogicalSurveyId(surveyId)

    private fun keyFor(normalizedSurveyId: String): String =
        "route:" + MessageDigest.getInstance("SHA-256")
            .digest(normalizedSurveyId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

    private companion object {
        const val PREF_NAME = "survey_upload_route_v1"
        const val CANONICAL_V1 = "canonical_immutable_v1"
        val LOCK = Any()
    }
}
