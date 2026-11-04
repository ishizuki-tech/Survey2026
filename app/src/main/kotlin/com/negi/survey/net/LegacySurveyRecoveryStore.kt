package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.Locale

/** Durable, non-sensitive operator record for a markerless survey that cannot be retried safely. */
internal class LegacySurveyRecoveryStore internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    )

    data class Record(val normalizedSurveyId: String, val fileName: String, val reason: String)

    fun record(surveyId: String, fileName: String, reason: String): Boolean {
        val id = SurveyUploadWork.normalizeLogicalSurveyId(surveyId) ?: return false
        val safeName = fileName.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return false
        val key = keyFor(id)
        synchronized(LOCK) {
            val value = "$id\n$safeName\n$reason"
            val indexedKeys = indexedKeysLocked()
            if (prefs.getString(key, null) == value && key in indexedKeys) return true
            return prefs.edit()
                .putString(key, value)
                .putStringSet(KEY_INDEX, (indexedKeys + key).toSet())
                .commit()
        }
    }

    fun get(surveyId: String): Record? {
        val id = SurveyUploadWork.normalizeLogicalSurveyId(surveyId) ?: return null
        return synchronized(LOCK) {
            parseRecord(keyFor(id), prefs.getString(keyFor(id), null))
        }
    }

    fun clear(surveyId: String): Boolean {
        val id = SurveyUploadWork.normalizeLogicalSurveyId(surveyId) ?: return false
        return synchronized(LOCK) {
            val key = keyFor(id)
            val indexedKeys = indexedKeysLocked()
            prefs.edit()
                .remove(key)
                .putStringSet(KEY_INDEX, (indexedKeys - key).toSet())
                .commit()
        }
    }

    /**
     * Returns every well-formed deferred-record snapshot in deterministic survey-ID order.
     *
     * This is the diagnostics-facing read API: missing or corrupt indexes are reconstructed only
     * in memory. Index repair is reserved for explicit [record] and [clear] mutations.
     */
    fun records(): List<Record> = synchronized(LOCK) {
        effectiveRecordKeysReadOnly()
            .mapNotNull { key -> parseRecord(key, prefs.getString(key, null)) }
            .sortedBy { it.normalizedSurveyId }
    }

    /** Returns usable indexed keys, or a read-only full scan when the durable index is invalid. */
    private fun effectiveRecordKeysReadOnly(): Set<String> {
        val stored = prefs.getStringSet(KEY_INDEX, null)
        val validStored = stored?.filterTo(linkedSetOf()) { key -> isRecordKey(key) }
        val allIndexedRecordsAreReadable = validStored?.all { key ->
            parseRecord(key, prefs.getString(key, null)) != null
        } == true
        if (stored != null && validStored != null && validStored.size == stored.size && allIndexedRecordsAreReadable) {
            return validStored
        }
        return prefs.all.asSequence()
            .mapNotNull { (key, value) -> parseRecord(key, value as? String)?.let { key } }
            .toCollection(linkedSetOf())
    }

    /** Returns the durable key index, migrating legacy unindexed records when necessary. */
    private fun indexedKeysLocked(): Set<String> {
        val stored = prefs.getStringSet(KEY_INDEX, null)
        val validStored = stored?.filterTo(linkedSetOf()) { key -> isRecordKey(key) }
        val allIndexedRecordsAreReadable = validStored?.all { key ->
            parseRecord(key, prefs.getString(key, null)) != null
        } == true
        if (stored != null && validStored != null && validStored.size == stored.size && allIndexedRecordsAreReadable) {
            return validStored
        }

        // This scan is migration/corruption fallback only; normal enumeration uses KEY_INDEX.
        val discovered = prefs.all
            .asSequence()
            .mapNotNull { (key, value) ->
                parseRecord(key, value as? String)?.let { key }
            }
            .toCollection(linkedSetOf())
        prefs.edit().putStringSet(KEY_INDEX, discovered).commit()
        return discovered
    }

    private fun parseRecord(key: String, raw: String?): Record? {
        if (!isRecordKey(key)) return null
        val fields = raw?.split('\n', limit = 3)?.takeIf { it.size == 3 } ?: return null
        val (storedId, fileName, reason) = fields
        val normalized = SurveyUploadWork.normalizeLogicalSurveyId(storedId) ?: return null
        val safeName = fileName.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        if (key != keyFor(normalized)) return null
        return Record(normalized, safeName, reason)
    }

    private fun isRecordKey(key: String): Boolean =
        key.startsWith(KEY_PREFIX) && key.length == KEY_PREFIX.length + SHA256_HEX_LENGTH

    private fun keyFor(id: String): String = KEY_PREFIX + MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

    private companion object {
        const val PREF_NAME = "legacy_survey_recovery_v1"
        const val KEY_PREFIX = "legacy:"
        const val KEY_INDEX = "legacy_record_keys_v1"
        const val SHA256_HEX_LENGTH = 64
        val LOCK = Any()
    }
}
