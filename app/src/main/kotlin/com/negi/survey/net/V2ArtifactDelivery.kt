package com.negi.survey.net

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.negi.survey.BuildConfig
import com.negi.survey.net.V2ArtifactStore.Entry
import com.negi.survey.net.V2ArtifactStore.Kind
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** V2 admission and recovery for the existing GitHub Worker. Credentials never enter a route. */
internal object V2ArtifactDelivery {
    const val MODE = "artifact_v2"
    const val RECOVERY_MODE = "artifact_v2_recovery"
    const val KEY_ARTIFACT = "artifact_v2_id"
    const val KEY_ROUTE = "artifact_v2_route"
    const val MAX_RAW_BYTES = 10 * 1024 * 1024
    private const val RECOVERY_WORK = "github_artifact_v2_reconciliation"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun config(context: Context): GitHubUploader.GitHubConfig? {
        val profile = V2ArtifactStore.profile
        if (BuildConfig.ISSUE157_DA || BuildConfig.ISSUE86_PROCESS_DEATH_HARNESS) return null
        val credentials = GitHubDiagnosticsConfigStore.load(context)
        val token = BuildConfig.GH_TOKEN.trim().ifBlank {
            credentials.token.trim().takeIf {
                credentials.enabled && credentials.owner.equals(V2ArtifactStore.OWNER, true) &&
                    credentials.repo.substringAfterLast('/').equals(profile.repo, true)
            }.orEmpty()
        }
        return configForCredential(profile, token)
    }

    internal fun configForCredential(profile: V2ArtifactStore.Profile, credential: String): GitHubUploader.GitHubConfig? {
        val token = credential.trim()
        if (token.isBlank()) return null
        return GitHubUploader.GitHubConfig(
            owner = V2ArtifactStore.OWNER, repo = profile.repo, token = token,
            branch = V2ArtifactStore.BRANCH, pathPrefix = "",
            maxRawBytesHint = MAX_RAW_BYTES, maxRequestBytesHint = 16 * 1024 * 1024,
        )
    }

    fun request(entry: Entry) = OneTimeWorkRequestBuilder<GitHubUploadWorker>()
        .setInputData(Data.Builder()
            .putString(GitHubUploadWorker.KEY_MODE, MODE)
            .putString(KEY_ARTIFACT, entry.route.id)
            .putString(KEY_ROUTE, entry.routeFingerprint)
            .build())
        .setConstraints(network)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .addTag(GitHubUploadWorker.TAG)
        .addTag("v2-artifact:${entry.route.id}")
        .addTag("${GitHubUploadWorker.TAG}:file:${entry.route.basename}")
        .build()

    fun scheduleRecovery(context: Context, immediate: Boolean = false) {
        val request = PeriodicWorkRequestBuilder<GitHubUploadWorker>(15, TimeUnit.MINUTES)
            .setInputData(Data.Builder().putString(GitHubUploadWorker.KEY_MODE, RECOVERY_MODE).build())
            .setConstraints(network)
            .build()
        val manager = WorkManager.getInstance(context)
        val periodic = manager.enqueueUniquePeriodicWork(RECOVERY_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        if (immediate) {
            val recovery = OneTimeWorkRequestBuilder<GitHubUploadWorker>()
                .setInputData(Data.Builder().putString(GitHubUploadWorker.KEY_MODE, RECOVERY_MODE).build())
                .setConstraints(network)
                .build()
            val immediateWork = manager.enqueueUniqueWork("${RECOVERY_WORK}_now", ExistingWorkPolicy.KEEP, recovery)
            // The receiver may finish as soon as this returns. Wait for both database operations
            // while its async broadcast is still active, within the broadcast time budget.
            immediateWork.result.get(3, TimeUnit.SECONDS)
            periodic.result.get(3, TimeUnit.SECONDS)
        }
    }

    fun enqueue(context: Context, entry: Entry, config: GitHubUploader.GitHubConfig) {
        check(entry.route.kind != Kind.SURVEY) { "Survey JSON is owned by SurveyUploadWork." }
        if (entry.hasReceipt()) {
            entry.cleanup()
            return
        }
        if (!entry.route.matches(config) || entry.hasRemoteConflict() ||
            entry.route.size > config.maxRawBytesHint || !entry.payload.isFile || !isEligible(context, entry)) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(entry), ExistingWorkPolicy.KEEP, request(entry)
        )
    }

    fun isEligible(context: Context, entry: Entry): Boolean =
        entry.route.kind != Kind.VOICE || V2ArtifactStore(context).isVoiceReferenced(entry.route)

    fun workName(entry: Entry): String = if (entry.route.kind == Kind.SURVEY) {
        "v2_" + SurveyUploadWork.logicalWorkName(checkNotNull(entry.route.surveyId))
    } else {
        "gh_artifact_v2_${entry.route.id}"
    }

    fun ledger(context: Context) = UploadedSurveyStore(context, v2 = true)

    fun confirmed(context: Context, entry: Entry): Boolean {
        val id = entry.route.surveyId
        if (entry.route.kind != Kind.SURVEY) return entry.hasReceipt()
        return ledger(context).confirms(checkNotNull(id), entry.route.fingerprint, entry.routeFingerprint)
    }

    fun cleanupSurvey(context: Context, entry: Entry): Boolean {
        if (!confirmed(context, entry)) return false
        val authority = SurveyUploadExecutionAuthority(context)
        val surveyId = checkNotNull(entry.route.surveyId)
        val protocol = SurveyUploadExecutionAuthority.Protocol.V2
        return when (authority.ownerFor(surveyId)) {
            SurveyUploadExecutionAuthority.Owner.NONE -> cleanupDelivered(context, entry)
            SurveyUploadExecutionAuthority.Owner.VALID(protocol) ->
                authority.clearForRecordedUpload(surveyId, protocol) && cleanupDelivered(context, entry)
            else -> false
        }
    }

    suspend fun deliver(
        context: Context,
        entry: Entry,
        config: GitHubUploader.GitHubConfig,
        transport: suspend (Entry, GitHubUploader.GitHubConfig, (Int) -> Unit) -> Unit = ::upload,
        progress: (Int) -> Unit,
    ) {
        require(entry.route.matches(config) && entry.route.profile == V2ArtifactStore.profile) {
            "The immutable artifact belongs to a different upload destination."
        }
        check(!entry.hasRemoteConflict()) { "Remote content conflicts with the immutable route." }
        if (entry.route.kind == Kind.SURVEY) {
            val surveyId = checkNotNull(entry.route.surveyId)
            val ledger = ledger(context)
            val outcome = SurveyUploadExecutionGuard.execute(
                surveyId = surveyId,
                protocol = SurveyUploadExecutionAuthority.Protocol.V2,
                authority = SurveyUploadExecutionAuthority(context),
                ledgerContains = {
                    confirmed(context, entry).also { matches ->
                        check(matches || !ledger.isUploaded(surveyId)) { "Survey ledger evidence does not match the route." }
                    }
                },
                remoteWrite = {
                    validatePayload(entry, config)
                    transport(entry, config, progress)
                },
                recordUploaded = {
                    ledger.markConfirmedUploaded(
                        surveyId, PendingSurveyUploads.exportedAtFromFile(entry.payload),
                        entry.route.fingerprint, entry.routeFingerprint,
                    )
                },
            )
            check(outcome == SurveyUploadExecutionGuard.Outcome.UPLOADED ||
                outcome == SurveyUploadExecutionGuard.Outcome.ALREADY_RECORDED) { "Survey execution authority: $outcome" }
        } else if (!entry.hasReceipt()) {
            check(isEligible(context, entry)) { "Recording is absent from the finalized survey." }
            validatePayload(entry, config)
            transport(entry, config, progress)
            entry.recordReceipt()
        }

        // A verified receipt survives failed cleanup. Recovery can finish without a remote write.
        if (!cleanupDelivered(context, entry)) {
            throw IOException("Verified artifact cleanup is pending.")
        }
    }

    private fun cleanupDelivered(context: Context, entry: Entry): Boolean = entry.cleanup {
        if (!confirmed(context, entry)) return@cleanup false
        if (entry.route.kind == Kind.SURVEY) entry.voiceReferences()
        true
    }

    private suspend fun upload(entry: Entry, config: GitHubUploader.GitHubConfig, progress: (Int) -> Unit) {
        if (entry.route.kind == Kind.SURVEY) {
            GitHubUploader.uploadSurveyJsonIdempotentlyAtPath(
                config, entry.route.path, checkNotNull(entry.route.surveyId),
                entry.payload.inputStream().use { java.io.InputStreamReader(it, Charsets.UTF_8.newDecoder()).readText() },
                onProgress = progress, verifyCreated = true,
            )
        } else {
            GitHubUploader.uploadFileAtPath(
                config, entry.route.path, entry.payload, onProgress = progress, verifyCreated = true,
            )
        }
    }

    private fun validatePayload(entry: Entry, config: GitHubUploader.GitHubConfig) {
        require(entry.matchesPayload()) { "Payload is missing or differs from its immutable route." }
        require(entry.route.size <= config.maxRawBytesHint) { "Content too large for upload guard." }
    }

    fun cleanupConfirmedSurveys(context: Context) {
        val store = V2ArtifactStore(context)
        store.artifactIds().filter { it.startsWith("survey-") }.forEach { id ->
            val entry = store.read(id) ?: return@forEach
            if (!entry.payload.exists()) return@forEach
            runCatching { cleanupSurvey(context, entry) }
                .onFailure { Log.w("V2ArtifactDelivery", "Survey cleanup deferred", it) }
        }
    }

    fun recoverIndependent(context: Context, config: GitHubUploader.GitHubConfig?) {
        val store = V2ArtifactStore(context)
        store.recoverInterruptedPublications()
        store.artifactIds().filterNot { it.startsWith("survey-") }.forEach { id ->
            try {
                val entry = store.read(id) ?: return@forEach
                if (entry.hasReceipt()) entry.cleanup()
                else if (config != null) enqueue(context, entry, config)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("V2ArtifactDelivery", "Could not recover artifact $id", failure)
            }
        }
    }
}
