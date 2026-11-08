package com.negi.survey.net

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.negi.survey.BuildConfig
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_BRANCH
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_MODE
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_OWNER
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_PATH_PREFIX
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_REPO
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_SURVEY_ID
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_SURVEY_UPLOAD_PROTOCOL
import com.negi.survey.net.GitHubUploadWorker.Companion.KEY_UPLOAD_KIND
import com.negi.survey.net.GitHubUploadWorker.Companion.UPLOAD_KIND_SURVEY_JSON
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.utils.V2ArtifactPaths
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

internal object V2SurveyJsonWork {
    const val KEY_FILE_IDENTITY = "v2.fileIdentity"
    const val KEY_ROUTE_KEY = "v2.routeKey"
    const val KEY_FINGERPRINT = "v2.payloadSha256"
    const val KEY_DESTINATION = "v2.destination"
    private const val WORK_PREFIX = "gh_survey_v2:"

    fun uniqueWorkName(surveyId: String): String =
        WORK_PREFIX + V2ArtifactPaths(surveyId, DeviceUploadTag("route")).surveyUuid

    fun metadata(route: V2SurveyJsonRoute): Map<String, String> = mapOf(
        KEY_MODE to "file",
        KEY_UPLOAD_KIND to UPLOAD_KIND_SURVEY_JSON,
        KEY_SURVEY_UPLOAD_PROTOCOL to V2_JSON_PROTOCOL,
        KEY_SURVEY_ID to route.surveyUuid,
        KEY_ROUTE_KEY to route.surveyUuid,
        KEY_FILE_IDENTITY to route.localFileIdentity,
        KEY_FINGERPRINT to route.payloadSha256,
        KEY_OWNER to route.destination.owner,
        KEY_REPO to route.destination.repository,
        KEY_BRANCH to route.destination.branch,
        KEY_PATH_PREFIX to route.destination.pathPrefix,
        KEY_DESTINATION to route.destination.identity.name
    )

    fun buildRequest(appFilesDir: File, route: V2SurveyJsonRoute, stagedFile: File): OneTimeWorkRequest {
        route.validate()
        val expectedFile = File(appFilesDir.canonicalFile, route.localFileIdentity)
        require(stagedFile.absoluteFile == expectedFile && stagedFile.canonicalFile == expectedFile &&
            stagedFile.isFile
        ) { "V2 request requires the canonical staged JSON file" }

        val metadata = metadata(route)
        return OneTimeWorkRequestBuilder<GitHubUploadWorker>()
            .setInputData(workDataOf(*metadata.entries.map { it.key to it.value }.toTypedArray()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(uniqueWorkName(route.surveyUuid))
            .build()
    }
}

/** Shared scheduling for explicit requests and recovery. Call on an I/O thread. */
internal class V2SurveyJsonScheduler(
    private val appFilesDir: File,
    private val operations: SurveyUploadWork.WorkManagerOperations,
    private val localOrDebugBuild: Boolean
) {
    sealed interface Result {
        data class Enqueued(val requestId: UUID) : Result
        data class Kept(val requestId: UUID) : Result
        data class Deferred(val reason: String) : Result
    }

    fun schedule(surveyId: String): Result = synchronized(scheduleLock) {
        val staged = try {
            V2SurveyJsonStager(V2SurveyJsonRouteStore(appFilesDir)).recover(surveyId)
        } catch (_: IOException) {
            return@synchronized Result.Deferred("V2 staging could not be read or published")
        } catch (_: IllegalArgumentException) {
            return@synchronized Result.Deferred("Invalid V2 survey identity")
        }
        if (staged is V2SurveyJsonStager.Result.Unavailable) {
            return@synchronized Result.Deferred("V2 staging is unavailable: ${staged.state}")
        }
        check(staged is V2SurveyJsonStager.Result.Ready)
        scheduleReady(staged.route, staged.file, recoverTerminalSuccess = false)
    }

    /** Recovery has already validated durable evidence, including a delivered route if applicable. */
    internal fun scheduleReady(
        route: V2SurveyJsonRoute,
        file: File,
        recoverTerminalSuccess: Boolean
    ): Result = synchronized(scheduleLock) {
        if (!allowsV2Destination(route.destination, localOrDebugBuild)) {
            return@synchronized Result.Deferred("V2 destination is prohibited in this build")
        }

        val name = V2SurveyJsonWork.uniqueWorkName(route.surveyUuid)
        val operationKey = file.path
        try {
            val history = operations.getWorkInfosForUniqueWork(name)
            val active = history.filterNot { it.state.isFinished }
            if (active.size > 1) {
                return@synchronized Result.Deferred("Multiple active V2 requests require reconciliation")
            }

            unconfirmed[operationKey]?.let { operation ->
                val completion = operation.awaitCompletion(WAIT_MS)
                if (completion == SurveyUploadWork.EnqueueCompletion.TIMED_OUT ||
                    Thread.currentThread().isInterrupted ||
                    (completion == SurveyUploadWork.EnqueueCompletion.SUCCEEDED && history.isEmpty())
                ) {
                    return@synchronized active.singleOrNull()?.let { Result.Kept(it.id) }
                        ?: Result.Deferred("Previous V2 enqueue is still unconfirmed")
                }
                unconfirmed.remove(operationKey)
            }

            active.singleOrNull()?.let { return@synchronized Result.Kept(it.id) }
            if (!recoverTerminalSuccess && history.any { it.state == WorkInfo.State.SUCCEEDED }) {
                return@synchronized Result.Deferred("V2 work succeeded but its route is still pending")
            }

            val request = V2SurveyJsonWork.buildRequest(appFilesDir, route, file)
            val operation = operations.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
            unconfirmed[operationKey] = operation
            val completion = operation.awaitCompletion(WAIT_MS)
            if (completion == SurveyUploadWork.EnqueueCompletion.TIMED_OUT || Thread.currentThread().isInterrupted) {
                return@synchronized Result.Deferred("V2 enqueue is unconfirmed")
            }
            if (completion != SurveyUploadWork.EnqueueCompletion.SUCCEEDED) {
                unconfirmed.remove(operationKey)
                return@synchronized Result.Deferred("V2 enqueue failed")
            }

            // KEEP may complete successfully without inserting this request if another writer won.
            val observed = operations.getWorkInfosForUniqueWork(name)
            if (observed.isNotEmpty()) unconfirmed.remove(operationKey)
            val observedActive = observed.filterNot { it.state.isFinished }
            when {
                observedActive.size > 1 -> Result.Deferred("Multiple active V2 requests require reconciliation")
                observed.any { it.id == request.id } -> Result.Enqueued(request.id)
                observedActive.size == 1 -> Result.Kept(observedActive.single().id)
                else -> Result.Deferred("V2 enqueue could not be observed")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.Deferred("V2 WorkManager operation was interrupted")
        } catch (_: Exception) {
            Result.Deferred("V2 WorkManager state could not be confirmed")
        }
    }

    companion object {
        fun schedule(context: Context, surveyId: String): Result {
            val workManager = try {
                WorkManager.getInstance(context.applicationContext)
            } catch (_: IllegalStateException) {
                return Result.Deferred("WorkManager is unavailable")
            }
            return V2SurveyJsonScheduler(
                context.filesDir,
                SurveyUploadWork.AndroidWorkManagerOperations(workManager),
                BuildConfig.DEBUG || BuildConfig.GH_UPLOAD_MODE == "local"
            ).schedule(surveyId)
        }

        private const val WAIT_MS = 350L
        private val scheduleLock = Any()
        private val unconfirmed = mutableMapOf<String, SurveyUploadWork.EnqueueOperation>()
    }
}
