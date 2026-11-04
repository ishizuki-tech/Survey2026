package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.Locale

/**
 * Durable protocol authority at the survey-worker remote-write boundary.
 *
 * This is intentionally a protocol pin, not a lease: after an unknown legacy write outcome, a
 * canonical worker must not take over merely because a process restarted. Retries of the same
 * protocol remain authorized. The pin is removed only after [UploadedSurveyStore] has durably
 * recorded delivery, at which point a second ledger check prevents a later worker from writing.
 */
internal class SurveyUploadExecutionAuthority internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    )

    enum class Protocol {
        LEGACY,
        CANONICAL,
    }

    sealed interface Claim {
        data object AUTHORIZED : Claim
        data class BLOCKED_BY_OTHER_PROTOCOL(val owner: Protocol) : Claim
        /** A durable value exists but cannot safely be interpreted as a protocol owner. */
        data object CORRUPT_OR_UNKNOWN_OWNER : Claim
        data object PERSISTENCE_FAILURE : Claim
    }

    sealed interface Owner {
        data object NONE : Owner
        data class VALID(val protocol: Protocol) : Owner
        /** Includes unreadable preference values; callers must not repair it automatically. */
        data object CORRUPT_OR_UNKNOWN : Owner
    }

    /** Atomically pins [surveyId] to [protocol], or rejects the competing protocol. */
    fun claim(surveyId: String, protocol: Protocol): Claim {
        val normalized = SurveyUploadWork.normalizeLogicalSurveyId(surveyId) ?: return Claim.PERSISTENCE_FAILURE
        return synchronized(LOCK) {
            val key = keyFor(normalized)
            when (val owner = ownerForKey(key)) {
                Owner.NONE -> if (prefs.edit().putString(key, protocol.name).commit()) Claim.AUTHORIZED
                else Claim.PERSISTENCE_FAILURE
                is Owner.VALID -> if (owner.protocol == protocol) Claim.AUTHORIZED
                else Claim.BLOCKED_BY_OTHER_PROTOCOL(owner.protocol)
                Owner.CORRUPT_OR_UNKNOWN -> Claim.CORRUPT_OR_UNKNOWN_OWNER
            }
        }
    }

    /** Read-only ownership inspection used by guarded execution and local operator diagnostics. */
    fun ownerFor(surveyId: String): Owner {
        val normalized = SurveyUploadWork.normalizeLogicalSurveyId(surveyId) ?: return Owner.CORRUPT_OR_UNKNOWN
        return synchronized(LOCK) { ownerForKey(keyFor(normalized)) }
    }

    /**
     * Removes only the matching valid protocol pin after a separate durable uploaded-ledger
     * confirmation. A corrupt or competing record is intentionally retained for manual review.
     */
    fun clearForRecordedUpload(surveyId: String, owner: Protocol): Boolean {
        val normalized = SurveyUploadWork.normalizeLogicalSurveyId(surveyId) ?: return false
        return synchronized(LOCK) {
            val key = keyFor(normalized)
            if (ownerForKey(key) != Owner.VALID(owner)) return false
            prefs.edit().remove(key).commit()
        }
    }

    private fun ownerForKey(key: String): Owner = try {
        when (val value = prefs.getString(key, null)) {
            null -> Owner.NONE
            Protocol.LEGACY.name -> Owner.VALID(Protocol.LEGACY)
            Protocol.CANONICAL.name -> Owner.VALID(Protocol.CANONICAL)
            else -> Owner.CORRUPT_OR_UNKNOWN
        }
    } catch (_: RuntimeException) {
        Owner.CORRUPT_OR_UNKNOWN
    }

    private fun keyFor(normalizedSurveyId: String): String = KEY_PREFIX + MessageDigest.getInstance("SHA-256")
        .digest(normalizedSurveyId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

    private companion object {
        const val PREF_NAME = "survey_upload_execution_authority_v1"
        const val KEY_PREFIX = "survey:"
        val LOCK = Any()
    }
}
