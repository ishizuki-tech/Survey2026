package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.Json

/**
 * Durable protocol authority at the survey-worker remote-write boundary.
 *
 * This is intentionally a protocol pin, not a lease: after an unknown legacy write outcome, a
 * canonical worker must not take over merely because a process restarted. Retries of the same
 * protocol remain authorized. The pin is removed only after the protocol's ledger has durably
 * recorded delivery. V2 pins also bind the exact immutable route.
 */
internal class SurveyUploadExecutionAuthority internal constructor(
    private val prefs: SharedPreferences,
    private val hasV2Evidence: (String) -> Boolean = { false }
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE),
        { V2SurveyJsonLedger.hasV2Evidence(context.filesDir, it) }
    )

    enum class Protocol {
        LEGACY,
        CANONICAL,
        IMMUTABLE_V2,
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
        data class VALID(val protocol: Protocol, val routeFingerprint: String? = null) : Owner
        /** Includes unreadable preference values; callers must not repair it automatically. */
        data object CORRUPT_OR_UNKNOWN : Owner
    }

    /** Atomically pins [surveyId] to [protocol], or rejects the competing protocol. */
    fun claim(surveyId: String, protocol: Protocol): Claim {
        val normalized = SurveyUploadWork.normalizeLogicalSurveyId(surveyId) ?: return Claim.PERSISTENCE_FAILURE
        return synchronized(LOCK) {
            if (protocol == Protocol.IMMUTABLE_V2) return@synchronized Claim.CORRUPT_OR_UNKNOWN_OWNER
            if (hasV2Evidence(normalized)) {
                return@synchronized Claim.BLOCKED_BY_OTHER_PROTOCOL(Protocol.IMMUTABLE_V2)
            }
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

    /** V2 retries can only reacquire the pin for the identical immutable route. */
    fun claimV2(route: V2SurveyJsonRoute): Claim = synchronized(LOCK) {
        val key = keyFor(route.surveyUuid)
        val fingerprint = v2RouteFingerprint(route)
        when (val owner = ownerForKey(key)) {
            Owner.NONE -> Unit
            Owner.CORRUPT_OR_UNKNOWN -> return@synchronized Claim.CORRUPT_OR_UNKNOWN_OWNER
            is Owner.VALID -> {
                if (owner.protocol != Protocol.IMMUTABLE_V2) {
                    return@synchronized Claim.BLOCKED_BY_OTHER_PROTOCOL(owner.protocol)
                }
                if (owner.routeFingerprint != fingerprint) return@synchronized Claim.CORRUPT_OR_UNKNOWN_OWNER
            }
        }
        // A failed SharedPreferences commit can leave an in-memory value. Recommit even for retries.
        if (prefs.edit().putString(key, "$V2_PREFIX$fingerprint").commit()) Claim.AUTHORIZED
        else Claim.PERSISTENCE_FAILURE
    }

    /** Called only after matching durable V2 confirmation. Never clears a competing pin. */
    fun releaseV2AfterRecorded(route: V2SurveyJsonRoute): Boolean = synchronized(LOCK) {
        val key = keyFor(route.surveyUuid)
        val owner = ownerForKey(key)
        if (owner != Owner.NONE && !ownsV2Route(owner, route)) {
            return@synchronized false
        }
        // Also recommit an absent value after an uncertain removal.
        prefs.edit().remove(key).commit()
    }

    fun ownsV2Route(owner: Owner, route: V2SurveyJsonRoute): Boolean =
        owner is Owner.VALID && owner.protocol == Protocol.IMMUTABLE_V2 &&
            owner.routeFingerprint == v2RouteFingerprint(route)

    /** Verifies durable absence without clearing an existing owner. */
    fun verifyV2AuthorityReleased(surveyId: String): Boolean = synchronized(LOCK) {
        val key = keyFor(surveyId)
        ownerForKey(key) == Owner.NONE && prefs.edit().remove(key).commit()
    }

    private fun v2RouteFingerprint(route: V2SurveyJsonRoute): String {
        route.validate()
        return SurveyUploadIdempotency.payloadFingerprint(Json.encodeToString(route))
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
        if (owner == Protocol.IMMUTABLE_V2) return false
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
            else -> {
                val fingerprint = value.removePrefix(V2_PREFIX)
                if (value.startsWith(V2_PREFIX) && SurveyUploadIdempotency.isPayloadFingerprint(fingerprint)) {
                    Owner.VALID(Protocol.IMMUTABLE_V2, fingerprint)
                } else Owner.CORRUPT_OR_UNKNOWN
            }
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
        const val V2_PREFIX = "IMMUTABLE_V2:"
        val LOCK = Any()
    }
}
