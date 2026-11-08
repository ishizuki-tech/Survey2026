package com.negi.survey.net

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.negi.survey.BuildConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object V2VoiceWork {
    private const val PROTOCOL = "immutable_voice_v2"

    fun uniqueName(id: V2RecordingId): String = "gh_voice_v2:${id.value}"

    fun metadata(route: V2VoiceRoute): Map<String, String> = mapOf(
        "protocol" to PROTOCOL,
        "recordingId" to route.recordingId.value,
        "routeSha256" to route.identitySha256()
    )

    fun recordingId(input: Map<String, Any?>): V2RecordingId? {
        if (input["protocol"] != PROTOCOL) return null
        val value = input["recordingId"] as? String ?: return null
        return try {
            V2RecordingId(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun request(route: V2VoiceRoute) = OneTimeWorkRequestBuilder<V2VoiceWorker>()
        .setInputData(workDataOf(*metadata(route).entries.map { it.key to it.value }.toTypedArray()))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .addTag(uniqueName(route.recordingId))
        .build()
}

internal class V2VoiceScheduler(
    private val appFilesDir: File,
    private val operations: SurveyUploadWork.WorkManagerOperations,
    private val localOrDebugBuild: Boolean = BuildConfig.DEBUG || BuildConfig.GH_UPLOAD_MODE == "local"
) {
    enum class Result { ENQUEUED, ACTIVE, DEFERRED }

    /** Durable artifact validation is done by recovery, never by WorkManager's terminal state. */
    fun schedule(route: V2VoiceRoute): Result = synchronized(lock) {
        if (!allowsV2Destination(route.destination, localOrDebugBuild)) return@synchronized Result.DEFERRED
        val name = V2VoiceWork.uniqueName(route.recordingId)
        try {
            route.validate()
            val key = File(appFilesDir.canonicalFile, route.localFileIdentity).path
            val history = operations.getWorkInfosForUniqueWork(name)
            val active = history.count { !it.state.isFinished }
            if (active > 1) return@synchronized Result.DEFERRED
            if (active == 1) {
                return@synchronized Result.ACTIVE
            }
            unconfirmed[key]?.let {
                val previous = it.awaitCompletion(WAIT_MS)
                if (previous == SurveyUploadWork.EnqueueCompletion.TIMED_OUT ||
                    Thread.currentThread().isInterrupted ||
                    (previous == SurveyUploadWork.EnqueueCompletion.SUCCEEDED && history.isEmpty())
                ) return@synchronized Result.DEFERRED
                unconfirmed.remove(key)
            }
            val request = V2VoiceWork.request(route)
            val operation = operations.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
            unconfirmed[key] = operation
            val completion = operation.awaitCompletion(WAIT_MS)
            if (completion == SurveyUploadWork.EnqueueCompletion.TIMED_OUT || Thread.currentThread().isInterrupted) {
                return@synchronized Result.DEFERRED
            }
            if (completion == SurveyUploadWork.EnqueueCompletion.FAILED) {
                unconfirmed.remove(key)
                return@synchronized Result.DEFERRED
            }
            val observed = operations.getWorkInfosForUniqueWork(name)
            if (observed.isNotEmpty()) unconfirmed.remove(key)
            val activeAfter = observed.count { !it.state.isFinished }
            when {
                activeAfter > 1 -> Result.DEFERRED
                observed.any { it.id == request.id } -> Result.ENQUEUED
                activeAfter == 1 -> Result.ACTIVE
                else -> Result.DEFERRED
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.DEFERRED
        } catch (_: Exception) {
            Result.DEFERRED
        }
    }

    companion object {
        private const val WAIT_MS = 350L
        private val lock = Any()
        private val unconfirmed = mutableMapOf<String, SurveyUploadWork.EnqueueOperation>()
    }
}

class V2VoiceWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val delivery: (V2VoiceWorker) -> V2VoiceDelivery
) : CoroutineWorker(context, params) {
    constructor(context: Context, params: WorkerParameters) : this(context, params, { it.productionDelivery() })

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (DiagnosticUploadInstrumentationGate.isDisabled() ||
            BuildConfig.ISSUE86_PROCESS_DEATH_HARNESS || BuildConfig.ISSUE157_DA
        ) return@withContext Result.retry()
        when (val outcome = delivery(this@V2VoiceWorker).execute(inputData.keyValueMap)) {
            is V2VoiceDelivery.Result.Completed -> Result.success(workDataOf("remotePath" to outcome.remotePath))
            is V2VoiceDelivery.Result.Blocked -> Result.failure(workDataOf("error" to outcome.reason))
            is V2VoiceDelivery.Result.Retry -> {
                Log.i("V2VoiceWorker", outcome.reason)
                Result.retry()
            }
        }
    }

    private fun productionDelivery(): V2VoiceDelivery {
        val app = applicationContext
        return V2VoiceDelivery(
            V2VoiceRouteStore(app.filesDir), V2VoiceLedger(app.filesDir), V2VoiceFileExecution(app.filesDir),
            BuildConfig.DEBUG || BuildConfig.GH_UPLOAD_MODE == "local",
            { GitHubDiagnosticsConfigStore.buildGitHubConfigOrNull(app) },
            { recordingId ->
                val name = V2VoiceWork.uniqueName(recordingId)
                val wm = WorkManager.getInstance(app)
                val named = wm.getWorkInfosForUniqueWork(name).get(350, TimeUnit.MILLISECONDS)
                val tagged = wm.getWorkInfosByTag(name).get(350, TimeUnit.MILLISECONDS)
                (named + tagged).any { it.id != id && !it.state.isFinished }
            }
        )
    }
}
