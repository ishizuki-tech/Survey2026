package com.negi.survey.net

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Pure validation and comparison policy for immutable canonical survey JSON records. */
internal object SurveyUploadIdempotency {
    data class LocalSurveyIdentity(val normalizedSurveyId: String)

    enum class ExistingRecord { MATCHING, CONFLICTING }

    /** SHA-256 of the exact UTF-8 JSON bytes transported for a survey payload. */
    fun payloadFingerprint(content: String): String = payloadFingerprint(content.toByteArray(Charsets.UTF_8))

    fun payloadFingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun isPayloadFingerprint(value: String?): Boolean =
        value?.matches(Regex("[0-9a-f]{64}")) == true

    fun validateLocalPayload(expectedSurveyId: String, content: String): LocalSurveyIdentity? {
        val local = surveyId(content)?.let(SurveyUploadWork::normalizeLogicalSurveyId) ?: return null
        val expected = SurveyUploadWork.normalizeLogicalSurveyId(expectedSurveyId) ?: return null
        return LocalSurveyIdentity(local).takeIf { it.normalizedSurveyId == expected }
    }

    fun classifyExisting(
        localIdentity: LocalSurveyIdentity,
        expectedContent: String,
        existingContent: String
    ): ExistingRecord {
        val existingId = surveyId(existingContent)?.let(SurveyUploadWork::normalizeLogicalSurveyId)
            ?: return ExistingRecord.CONFLICTING
        return if (existingId == localIdentity.normalizedSurveyId && existingContent == expectedContent) {
            ExistingRecord.MATCHING
        } else {
            ExistingRecord.CONFLICTING
        }
    }

    private fun surveyId(content: String): String? {
        val root = runCatching { Json.parseToJsonElement(content) as? JsonObject }.getOrNull() ?: return null
        return (root["survey_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.trim()?.takeIf { it.isNotBlank() }
    }
}
