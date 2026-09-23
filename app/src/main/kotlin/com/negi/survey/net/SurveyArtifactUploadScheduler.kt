/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyArtifactUploadScheduler.kt
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */
package com.negi.survey.net

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.negi.survey.BuildConfig
import com.negi.survey.utils.ExportUtils
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Schedules optional voice and diagnostic artifacts after survey JSON queueing succeeds. */
internal class SurveyArtifactUploadScheduler(context: Context) {
    private val appContext = context.applicationContext

    suspend fun scheduleVoice(
        config: GitHubUploader.GitHubConfig,
        surveyId: String,
        expectedVoiceFileNames: Set<String>
    ) {
        val staged = withContext(Dispatchers.IO) {
            stageVoiceFilesToSharedPendingForRun(appContext, expectedVoiceFileNames, surveyId)
        }
        staged.forEach { stagedFile ->
            val canGitHub = canUploadToGitHubContentsApi(stagedFile, config.maxRawBytesHint.toLong())
            VoiceUploadCompletionStore.requireDestinations(appContext, stagedFile, canGitHub, false)
            if (!canGitHub) {
                Log.w(
                    LOG_TAG,
                    "Skip scheduling GitHub voice (outside configured size limit): name=" +
                        stagedFile.name + " bytes=" + stagedFile.length() +
                        " limit=" + config.maxRawBytesHint
                )
                return@forEach
            }
            runCatching {
                enqueueGitHubWorkerFileUpload(
                    context = appContext,
                    config = config,
                    localFile = stagedFile,
                    remoteRelativePath = REMOTE_VOICE_DIR + "/" + stagedFile.name
                )
            }.onFailure { error ->
                Log.e(LOG_TAG, "Failed to schedule GitHub voice: name=" + stagedFile.name, error)
            }
        }
    }

    suspend fun scheduleLog(
        config: GitHubUploader.GitHubConfig,
        surveyId: String,
        exportedAtStamp: String
    ) {
        val pendingLog = withContext(Dispatchers.IO) {
            captureSessionLogcatToPendingFile(appContext, surveyId, exportedAtStamp)
        }
        enqueueGitHubWorkerFileUpload(
            context = appContext,
            config = config,
            localFile = pendingLog,
            remoteRelativePath = REMOTE_LOG_DIR + "/" + pendingLog.name
        )
    }

    private fun stageVoiceFilesToSharedPendingForRun(
        context: Context,
        expectedNames: Set<String>,
        surveyUuid: String
    ): List<File> {
        if (expectedNames.isEmpty()) return emptyList()
        val safeSurvey = sanitizeWorkName(surveyUuid).ifBlank { "unknown" }
        val directory = File(context.filesDir, PENDING_DIR_SHARED + "/voice/" + safeSurvey).apply { mkdirs() }
        val expectedBase = expectedNames.map(::localFileName).filter { it.isNotBlank() }.toSet()
        val voiceDirectory = ExportUtils.getVoiceExportDir(context)
        if (!voiceDirectory.exists() || !voiceDirectory.isDirectory) return emptyList()

        val staged = ArrayList<File>(expectedBase.size)
        expectedBase.forEach { name ->
            val destination = File(directory, stableVoiceFileName(name))
            if (destination.exists() && destination.isFile && destination.length() > 0L) {
                staged += destination
                return@forEach
            }
            val source = File(voiceDirectory, name)
            if (!source.exists() || !source.isFile || source.length() <= 0L) return@forEach
            if (destination.exists()) runCatching { destination.delete() }
            runCatching { deleteVoiceSidecars(source) }
            if (source.renameTo(destination)) {
                staged += destination
                return@forEach
            }
            runCatching {
                val sourceLength = source.length().coerceAtLeast(0L)
                source.copyTo(destination, overwrite = true)
                if (destination.exists() && destination.length() == sourceLength) {
                    runCatching { source.delete() }
                }
            }.getOrElse { error ->
                throw IOException(
                    "Failed to stage voice: " + source.absolutePath + " -> " +
                        destination.absolutePath + ": " + error.message,
                    error
                )
            }
            staged += destination
        }
        return staged.sortedByDescending { it.lastModified() }
    }

    private fun captureSessionLogcatToPendingFile(
        context: Context,
        surveyUuid: String,
        exportedAtStamp: String
    ): File {
        val pid = Process.myPid()
        val shortId = surveyUuid.take(8).ifBlank { "unknown" }
        val fileName = sanitizeFileName("logcat_" + exportedAtStamp + "_pid" + pid + "_" + shortId + ".log.gz")
        val directory = File(context.filesDir, PENDING_DIR_GH).apply { mkdirs() }
        val output = uniqueIfExists(File(directory, fileName))
        val header = buildString {
            appendLine("=== Session Log Snapshot ===")
            appendLine("time_local=" + exportedAtStamp)
            appendLine("survey_id=" + surveyUuid)
            appendLine("pid=" + pid)
            appendLine("sdk=" + Build.VERSION.SDK_INT)
            appendLine("device=" + Build.MANUFACTURER + " " + Build.MODEL)
            appendLine("appId=" + BuildConfig.APPLICATION_ID)
            appendLine("versionName=" + BuildConfig.VERSION_NAME)
            appendLine("versionCode=" + BuildConfig.VERSION_CODE)
            appendLine("tags=" + LOGCAT_TAG_FILTERS.joinToString(","))
            appendLine()
            appendLine("=== Logcat (best-effort) ===")
        }.toByteArray(Charsets.UTF_8)
        val logBytes = collectLogcatBytesBestEffort(pid, MAX_LOGCAT_BYTES)
        FileOutputStream(output).use { stream ->
            GZIPOutputStream(stream).use { gzip ->
                gzip.write(header)
                gzip.write(logBytes)
                gzip.flush()
            }
        }
        Log.d(LOG_TAG, "Captured logcat snapshot: " + output.absolutePath + " (" + output.length() + " bytes gz)")
        return output
    }

    private fun enqueueGitHubWorkerFileUpload(
        context: Context,
        config: GitHubUploader.GitHubConfig,
        localFile: File,
        remoteRelativePath: String
    ) {
        val input = Data.Builder()
            .putString(GitHubUploadWorker.KEY_MODE, "file")
            .putString(GitHubUploadWorker.KEY_OWNER, config.owner)
            .putString(GitHubUploadWorker.KEY_REPO, normalizeRepoName(config.repo))
            .putString(GitHubUploadWorker.KEY_TOKEN, config.token)
            .putString(GitHubUploadWorker.KEY_BRANCH, config.branch)
            .putString(GitHubUploadWorker.KEY_PATH_PREFIX, config.pathPrefix)
            .putString(GitHubUploadWorker.KEY_FILE_PATH, localFile.absolutePath)
            .putString(GitHubUploadWorker.KEY_FILE_NAME, remoteRelativePath)
            .putLong(GitHubUploadWorker.KEY_FILE_MAX_BYTES_HINT, config.maxRawBytesHint.toLong())
            .putInt(GitHubUploadWorker.KEY_FILE_MAX_REQUEST_BYTES_HINT, config.maxRequestBytesHint)
            .build()
        val request = OneTimeWorkRequestBuilder<GitHubUploadWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(GitHubUploadWorker.TAG)
            .addTag(GitHubUploadWorker.TAG + ":file:" + SurveyUploadWork.safeWorkNameSegment(remoteRelativePath))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SurveyUploadWork.uniqueWorkName(remoteRelativePath),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun collectLogcatBytesBestEffort(pid: Int, maxBytes: Int): ByteArray {
        val commands = listOf(
            arrayOf("logcat", "-d", "--pid=" + pid, "-v", "threadtime", "-s", *LOGCAT_TAG_FILTERS),
            arrayOf("logcat", "-d", "--pid=" + pid, "-v", "threadtime"),
            arrayOf("logcat", "-d", "-v", "threadtime", "-s", *LOGCAT_TAG_FILTERS),
            arrayOf("logcat", "-d", "-v", "threadtime")
        )
        return runCatching { execAndReadCapped(commands[0], maxBytes) }
            .recoverCatching { execAndReadCapped(commands[1], maxBytes) }
            .recoverCatching { execAndReadCapped(commands[2], maxBytes) }
            .recoverCatching { execAndReadCapped(commands[3], maxBytes) }
            .getOrElse { error ->
                ("(logcat capture failed: " + error.message + ")\\n").toByteArray(Charsets.UTF_8)
            }
    }

    private fun execAndReadCapped(command: Array<String>, maxBytes: Int): ByteArray {
        val process = Runtime.getRuntime().exec(command)
        val input = BufferedInputStream(process.inputStream)
        val output = ByteArray(maxBytes)
        var total = 0
        while (total < maxBytes) {
            val read = input.read(output, total, maxBytes - total)
            if (read <= 0) break
            total += read
        }
        runCatching { input.close() }
        runCatching { process.destroy() }
        return if (total == output.size) output else output.copyOf(total)
    }

    private fun canUploadToGitHubContentsApi(file: File, maxRawBytes: Long): Boolean {
        val length = runCatching { file.length() }.getOrDefault(0L)
        return maxRawBytes > 0L && length in 1L..maxRawBytes
    }

    private fun deleteVoiceSidecars(wavFile: File) {
        val directory = wavFile.parentFile ?: return
        val base = wavFile.name.substringBeforeLast('.', wavFile.name)
        val metadata = File(directory, base + ".meta.json")
        if (metadata.exists()) runCatching { metadata.delete() }
    }

    private fun localFileName(reference: String): String {
        val trimmed = reference.trim()
        if (trimmed.isBlank()) return ""
        val separator = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
        return if (separator >= 0 && separator + 1 < trimmed.length) trimmed.substring(separator + 1) else trimmed
    }

    private fun stableVoiceFileName(name: String): String {
        val localName = localFileName(name).trim()
        if (localName.isBlank()) return "unknown.wav"
        return if (localName.contains('/') || localName.contains('\\')) sanitizeFileName(localName) else localName
    }

    private fun sanitizeFileName(name: String): String =
        name.replace("/", "_").replace(Regex("""[^\w\-.]"""), "_")

    private fun sanitizeWorkName(value: String): String =
        value.trim().replace(Regex("""[^\w\-.]+"""), "_").take(120)

    private fun uniqueIfExists(file: File): File {
        if (!file.exists()) return file
        val base = file.nameWithoutExtension
        val extension = file.extension.takeIf { it.isNotEmpty() }?.let { "." + it }.orEmpty()
        var index = 1
        while (true) {
            val candidate = File(file.parentFile, base + "_" + index + extension)
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun normalizeRepoName(repo: String): String {
        val trimmed = repo.trim()
        return if (trimmed.contains('/')) trimmed.substringAfterLast('/').trim() else trimmed
    }

    private companion object {
        const val LOG_TAG = "SurveyArtifactUpload"
        const val REMOTE_VOICE_DIR = "voice"
        const val REMOTE_LOG_DIR = "diagnostics/logcat"
        const val MAX_LOGCAT_BYTES = 850_000
        const val PENDING_DIR_GH = "pending_uploads"
        const val PENDING_DIR_SHARED = "pending_uploads_shared"
        val LOGCAT_TAG_FILTERS = arrayOf(
            "WhisperEngine",
            "MainActivity",
            "CrashCapture",
            "GitHubUploadWorker",
            "GitHubUploader",
            "LiteRtLM",
            "LiteRtRepository",
            "SupabaseUploadWorker",
            "SupabaseStorageUp"
        )
    }
}
