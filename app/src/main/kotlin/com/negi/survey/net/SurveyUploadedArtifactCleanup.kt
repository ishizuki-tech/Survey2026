package com.negi.survey.net

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Safely removes one app-owned staged survey only after its durable uploaded ledger wins. */
internal object SurveyUploadedArtifactCleanup {
    enum class Outcome { CLEANED, ALREADY_MISSING, DEFER_ACTIVE_WORK, DEFER_UNVERIFIED, RETRY_CLEANUP }

    internal interface Operations {
        fun ledgerPayloadFingerprint(surveyId: String): String?
        fun isOwnedPendingFile(file: File): Boolean
        fun hasOtherActiveWork(surveyId: String, file: File): Boolean
        /** Returns the raw-byte fingerprint only when [file] is a valid payload for [surveyId]. */
        fun payloadFingerprint(file: File, surveyId: String): String?
        fun delete(file: File): Boolean
    }

    fun cleanup(
        context: Context,
        surveyId: String,
        file: File,
        currentWorkId: UUID? = null,
    ): Outcome {
        val appContext = context.applicationContext ?: context
        return cleanup(surveyId, file, AndroidOperations(appContext, currentWorkId))
    }

    internal fun cleanup(surveyId: String, file: File, operations: Operations): Outcome {
        val normalized = SurveyUploadWork.normalizeLogicalSurveyId(surveyId) ?: return Outcome.DEFER_UNVERIFIED
        return locks.computeIfAbsent(normalized) { ReentrantLock() }.withLock {
            val ledgerFingerprint = operations.ledgerPayloadFingerprint(normalized)
                ?.takeIf(SurveyUploadIdempotency::isPayloadFingerprint)
                ?: return@withLock Outcome.DEFER_UNVERIFIED
            if (!operations.isOwnedPendingFile(file)) return@withLock Outcome.DEFER_UNVERIFIED
            if (!file.exists()) return@withLock Outcome.ALREADY_MISSING
            if (operations.hasOtherActiveWork(normalized, file)) {
                return@withLock Outcome.DEFER_ACTIVE_WORK
            }
            if (operations.payloadFingerprint(file, normalized) != ledgerFingerprint) {
                return@withLock Outcome.DEFER_UNVERIFIED
            }
            // The validation immediately precedes deletion while holding the per-survey cleanup lock.
            if (operations.delete(file)) Outcome.CLEANED else Outcome.RETRY_CLEANUP
        }
    }

    private class AndroidOperations(private val context: Context, private val currentWorkId: UUID?) : Operations {
        override fun ledgerPayloadFingerprint(surveyId: String): String? =
            UploadedSurveyStore(context).payloadFingerprintFor(surveyId)
        override fun isOwnedPendingFile(file: File): Boolean = runCatching {
            file.canonicalFile.parentFile == File(context.filesDir, PENDING_DIRECTORY).canonicalFile && file.name.isNotBlank()
        }.getOrDefault(false)
        override fun hasOtherActiveWork(surveyId: String, file: File): Boolean = try {
            listOfNotNull(SurveyUploadWork.logicalWorkName(surveyId), SurveyUploadWork.legacyWorkName(file))
                .distinct().flatMap { WorkManager.getInstance(context).getWorkInfosForUniqueWork(it)
                    .get(LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .any { it.id != currentWorkId && it.state in ACTIVE_STATES }
        } catch (_: Exception) { true }
        override fun payloadFingerprint(file: File, surveyId: String): String? = runCatching {
            val bytes = file.readBytes()
            val content = bytes.toString(Charsets.UTF_8)
            SurveyUploadIdempotency.validateLocalPayload(surveyId, content)
                ?.let { SurveyUploadIdempotency.payloadFingerprint(bytes) }
        }.getOrNull()
        override fun delete(file: File): Boolean = file.delete()
    }

    private const val PENDING_DIRECTORY = "pending_uploads"
    private const val LOOKUP_TIMEOUT_MS = 350L
    private val ACTIVE_STATES = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
}
