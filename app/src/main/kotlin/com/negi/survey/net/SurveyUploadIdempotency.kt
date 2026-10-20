package com.negi.survey.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Pure survey-JSON comparison policy used before a canonical remote target is created or reused. */
internal object SurveyUploadIdempotency {
    data class LocalSurveyIdentity(val normalizedSurveyId: String)
    data class LocalSurveyUpload(
        val identity: LocalSurveyIdentity,
        val canonicalRelativePath: String
    )

    enum class ExistingRecord {
        MATCHING,
        CONFLICTING
    }

    /** Only a create conflict can justify re-reading a canonical remote target. */
    fun shouldReconcileAfterCreateFailure(httpStatus: Int?): Boolean = httpStatus == 409

    /**
     * A remote record is reusable only when it has the same normalized survey ID and exact UTF-8
     * payload. This deliberately fails closed instead of overwriting a record owned by another
     * payload, including malformed JSON or a same-ID record with different answers.
     */
    /**
     * Validates the local staged payload independently from any remote comparison. A persisted
     * worker may carry an old file-name value, but its survey identity always comes from JSON.
     */
    fun validateLocalPayload(expectedSurveyId: String?, content: String): LocalSurveyIdentity? {
        val localId = surveyId(content)?.let(SurveyUploadWork::normalizeLogicalSurveyId) ?: return null
        val expectedId = expectedSurveyId?.let(SurveyUploadWork::normalizeLogicalSurveyId)
        return if (expectedId == null || expectedId == localId) LocalSurveyIdentity(localId) else null
    }

    /** Resolves a survey target solely from validated local JSON; legacy file names are excluded. */
    fun resolveLocalSurveyUpload(expectedSurveyId: String?, content: String): LocalSurveyUpload? =
        validateLocalPayload(expectedSurveyId, content)?.let { identity ->
            LocalSurveyUpload(identity, SurveyUploadWork.canonicalRemotePath(identity.normalizedSurveyId))
        }

    /** Persist success before best-effort cleanup so a ledger failure preserves the staged artifact. */
    fun completeLocalSurveySuccess(
        surveyId: String,
        markUploaded: (String) -> Unit,
        deleteStagedFile: () -> Boolean
    ) {
        markUploaded(surveyId)
        runCatching(deleteStagedFile)
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
        val root = runCatching { Json.parseToJsonElement(content) as? JsonObject }.getOrNull()
            ?: return null
        return (root["survey_id"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}
