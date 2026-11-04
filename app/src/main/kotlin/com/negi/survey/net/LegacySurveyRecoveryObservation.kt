package com.negi.survey.net

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Read-only local diagnosis for a legacy recovery record.
 *
 * This deliberately does not call [LegacySurveyOrphanRecovery.inspect]: that maintenance helper
 * may adopt work into the tracker or clear a diagnostic record. Observation performs no writes,
 * scheduling, network IO, or staged-file mutation.
 */
internal object LegacySurveyRecoveryObservation {
    enum class Status {
        ALREADY_UPLOADED,
        ACTIVE_WORK,
        MANUAL_REVIEW_REQUIRED,
        MISSING_ARTIFACT,
        INVALID_ARTIFACT,
        UNKNOWN_LOCAL_STATE
    }

    enum class WorkState {
        NO_ACTIVE_WORK,
        ACTIVE_WORK,
        UNKNOWN
    }

    data class Snapshot(
        val record: LegacySurveyRecoveryStore.Record,
        val status: Status,
        val stagedFilePresent: Boolean,
        val stagedJsonValid: Boolean?,
        val uploadedLedgerContainsSurvey: Boolean?,
        val workState: WorkState,
        val trackerIdentity: SurveyUploadWorkTracker.IdentityKind?,
        val canonicalRouteReserved: Boolean?,
        val executionAuthority: SurveyUploadExecutionAuthority.Owner?,
        /** Legacy records intentionally have no proved historical remote destination. */
        val historicalRemoteDestinationKnown: Boolean = false,
    ) {
        val operatorReviewRequired: Boolean
            get() = status == Status.MANUAL_REVIEW_REQUIRED
    }

    internal interface Operations {
        fun stagedFile(record: LegacySurveyRecoveryStore.Record): File
        fun validate(file: File, surveyId: String): SurveyUploadWork.SurveyArtifactValidation
        fun ledgerContains(surveyId: String): Boolean
        fun canonicalRouteReserved(surveyId: String): Boolean
        fun executionAuthority(surveyId: String): SurveyUploadExecutionAuthority.Owner
        fun inspectWork(record: LegacySurveyRecoveryStore.Record, file: File): WorkInspection
    }

    internal data class WorkInspection(
        val state: WorkState,
        val trackerIdentity: SurveyUploadWorkTracker.IdentityKind? = null,
    )

    fun observe(context: Context, record: LegacySurveyRecoveryStore.Record): Snapshot =
        observe(record, AndroidOperations(context.applicationContext ?: context))

    internal fun observe(record: LegacySurveyRecoveryStore.Record, operations: Operations): Snapshot {
        val file = operations.stagedFile(record)
        val present = file.isFile
        val validation = if (present) {
            runCatching { operations.validate(file, record.normalizedSurveyId) }.getOrNull()
        } else {
            null
        }
        val valid = validation is SurveyUploadWork.SurveyArtifactValidation.Valid
        val ledger = runCatching { operations.ledgerContains(record.normalizedSurveyId) }.getOrNull()
        val route = runCatching { operations.canonicalRouteReserved(record.normalizedSurveyId) }.getOrNull()
        val authority = runCatching { operations.executionAuthority(record.normalizedSurveyId) }.getOrNull()
        val work = runCatching { operations.inspectWork(record, file) }
            .getOrElse { WorkInspection(WorkState.UNKNOWN) }

        val status = when {
            ledger == true -> Status.ALREADY_UPLOADED
            !present -> Status.MISSING_ARTIFACT
            validation == null -> Status.UNKNOWN_LOCAL_STATE
            !valid -> Status.INVALID_ARTIFACT
            ledger == null || route == null || work.state == WorkState.UNKNOWN -> Status.UNKNOWN_LOCAL_STATE
            work.state == WorkState.ACTIVE_WORK -> Status.ACTIVE_WORK
            else -> Status.MANUAL_REVIEW_REQUIRED
        }
        return Snapshot(
            record = record,
            status = status,
            stagedFilePresent = present,
            stagedJsonValid = validation?.let { it is SurveyUploadWork.SurveyArtifactValidation.Valid },
            uploadedLedgerContainsSurvey = ledger,
            workState = work.state,
            trackerIdentity = work.trackerIdentity,
            canonicalRouteReserved = route,
            executionAuthority = authority,
        )
    }

    private class AndroidOperations(context: Context) : Operations {
        private val appContext = context.applicationContext ?: context
        private val workManager = WorkManager.getInstance(appContext)
        private val tracker = SurveyUploadWorkTracker(appContext)
        private val ledger = UploadedSurveyStore(appContext)
        private val routes = SurveyUploadRouteStore(appContext)
        private val authority = SurveyUploadExecutionAuthority(appContext)

        override fun stagedFile(record: LegacySurveyRecoveryStore.Record): File =
            File(File(appContext.filesDir, PENDING_DIRECTORY), record.fileName)

        override fun validate(file: File, surveyId: String): SurveyUploadWork.SurveyArtifactValidation =
            SurveyUploadWork.validateCanonicalArtifact(file, surveyId)

        override fun ledgerContains(surveyId: String): Boolean = ledger.isUploaded(surveyId)

        override fun canonicalRouteReserved(surveyId: String): Boolean =
            routes.protocolFor(surveyId) == SurveyUploadWork.SurveyUploadProtocol.CANONICAL_IMMUTABLE_V1

        override fun executionAuthority(surveyId: String): SurveyUploadExecutionAuthority.Owner =
            authority.ownerFor(surveyId)

        override fun inspectWork(
            record: LegacySurveyRecoveryStore.Record,
            file: File,
        ): WorkInspection {
            val tracked = tracker.get(record.normalizedSurveyId)
            var unknown = false
            var active = false

            if (tracked != null) {
                val trackedInfo = getWorkInfo(tracked.workRequestId)
                when {
                    trackedInfo == null -> unknown = true
                    isActive(trackedInfo.state) -> active = true
                }
            }

            val names = listOfNotNull(
                SurveyUploadWork.logicalWorkName(record.normalizedSurveyId),
                SurveyUploadWork.legacyWorkName(file),
            ).distinct()
            for (name in names) {
                when (val history = getUniqueWork(name)) {
                    null -> unknown = true
                    else -> if (history.any { isActive(it.state) }) active = true
                }
            }
            return WorkInspection(
                state = when {
                    active -> WorkState.ACTIVE_WORK
                    unknown -> WorkState.UNKNOWN
                    else -> WorkState.NO_ACTIVE_WORK
                },
                trackerIdentity = tracked?.identityKind,
            )
        }

        private fun getWorkInfo(id: java.util.UUID): WorkInfo? =
            workManager.getWorkInfoById(id).get(LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        private fun getUniqueWork(name: String): List<WorkInfo>? =
            workManager.getWorkInfosForUniqueWork(name).get(LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        private fun isActive(state: WorkInfo.State): Boolean = when (state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.BLOCKED -> true
            WorkInfo.State.SUCCEEDED,
            WorkInfo.State.FAILED,
            WorkInfo.State.CANCELLED -> false
        }
    }

    private const val PENDING_DIRECTORY = "pending_uploads"
    private const val LOOKUP_TIMEOUT_MS = 350L
}
