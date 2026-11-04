package com.negi.survey.net

/**
 * The survey-only remote-write boundary used by [GitHubUploadWorker].
 *
 * This deliberately contains no scheduling or file discovery. Its narrow operation callbacks make
 * the worker's ledger/authority ordering deterministic to test without changing generic uploads.
 */
internal object SurveyUploadExecutionGuard {
    sealed interface Outcome {
        data object UPLOADED : Outcome
        data object ALREADY_RECORDED : Outcome
        data class BLOCKED_BY_OTHER_PROTOCOL(val owner: SurveyUploadExecutionAuthority.Protocol) : Outcome
        data object CORRUPT_OR_UNKNOWN_AUTHORITY : Outcome
        data object AUTHORITY_PERSISTENCE_FAILURE : Outcome
        data object AUTHORITY_RELEASE_FAILURE : Outcome
    }

    suspend fun execute(
        surveyId: String,
        protocol: SurveyUploadExecutionAuthority.Protocol,
        authority: SurveyUploadExecutionAuthority,
        ledgerContains: () -> Boolean,
        remoteWrite: suspend () -> Unit,
        recordUploaded: () -> Unit,
        afterRemoteWrite: suspend () -> Unit = {},
        afterRecordedUpload: suspend () -> Unit = {},
    ): Outcome {
        // A prior durable ledger always wins; it must never authorize another write.
        if (ledgerContains()) return releaseRecorded(authority, surveyId, protocol)
        when (val claim = authority.claim(surveyId, protocol)) {
            SurveyUploadExecutionAuthority.Claim.AUTHORIZED -> Unit
            is SurveyUploadExecutionAuthority.Claim.BLOCKED_BY_OTHER_PROTOCOL ->
                return Outcome.BLOCKED_BY_OTHER_PROTOCOL(claim.owner)
            SurveyUploadExecutionAuthority.Claim.CORRUPT_OR_UNKNOWN_OWNER ->
                return Outcome.CORRUPT_OR_UNKNOWN_AUTHORITY
            SurveyUploadExecutionAuthority.Claim.PERSISTENCE_FAILURE ->
                return Outcome.AUTHORITY_PERSISTENCE_FAILURE
        }
        // Closes the normal ledger-commit / released-pin race before transport.
        if (ledgerContains()) return releaseRecorded(authority, surveyId, protocol)
        remoteWrite()
        // No-op in normal operation. The isolated process-death harness uses this exact
        // post-confirmation, pre-ledger boundary.
        afterRemoteWrite()
        recordUploaded() // Throws on a failed durable ledger commit; retain pin and staged file.
        val outcome = releaseRecorded(authority, surveyId, protocol, uploaded = true)
        // Authority has now been released; Worker cleanup has not yet started.
        if (outcome == Outcome.UPLOADED) afterRecordedUpload()
        return outcome
    }

    private fun releaseRecorded(
        authority: SurveyUploadExecutionAuthority,
        surveyId: String,
        protocol: SurveyUploadExecutionAuthority.Protocol,
        uploaded: Boolean = false,
    ): Outcome {
        val owner = authority.ownerFor(surveyId)
        val released = when (owner) {
            SurveyUploadExecutionAuthority.Owner.NONE -> true
            is SurveyUploadExecutionAuthority.Owner.VALID ->
                owner.protocol == protocol && authority.clearForRecordedUpload(surveyId, protocol)
            else -> false
        }
        return if (released) {
            if (uploaded) Outcome.UPLOADED else Outcome.ALREADY_RECORDED
        } else {
            Outcome.AUTHORITY_RELEASE_FAILURE
        }
    }
}
