/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: DoneScreen.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Final survey screen that summarizes answers and follow-up questions,
 *  renders them in a simple vertical layout, and exposes export actions:
 *
 *   • Local JSON export (auto-save to device storage).
 *   • Immediate GitHub upload (online now, JSON + voice WAV + logcat snapshot).
 *   • Deferred GitHub upload via WorkManager (runs when online).
 *   • Deferred upload of recorded voice WAV files via WorkManager.
 *   • Optional log-only upload now / later for offline-friendly diagnostics.
 *
 *  Key design rule:
 *   • JSON must be built from SurveyViewModel.recordedAudioRefs (logical manifest),
 *     not from file system scans, so repeated JSON exports remain stable even if
 *     WAV files were already uploaded and deleted.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.screens

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.negi.survey.BuildConfig
import com.negi.survey.net.GitHubUploadWorker
import com.negi.survey.net.GitHubUploader
import com.negi.survey.utils.ExportUtils
import com.negi.survey.utils.buildSurveyFileName
import com.negi.survey.vm.Node
import com.negi.survey.vm.SurveyViewModel
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val REMOTE_VOICE_DIR = "voice"

/** Remote directory for session log snapshots (gzip). */
private const val REMOTE_LOG_DIR = "diagnostics/logcat"

/** Cap logcat bytes to keep GitHub contents uploads safe-ish. */
private const val MAX_LOGCAT_BYTES = 850_000

private const val LOG_TAG = "DoneScreen"

/**
 * Limit logcat to a small set of app tags to reduce noise.
 * Note: This is best-effort; fallbacks may capture broader output depending on device support.
 */
private val LOGCAT_TAG_FILTERS = arrayOf(
    "WhisperEngine",
    "MainActivity",
    "CrashCapture",
    "GitHubUploadWorker",
    "GitHubUploader",
    "LiteRtLM",
    "LiteRtRepository"
)

/**
 * Final survey screen that summarizes all collected answers and follow-ups.
 *
 * Responsibilities:
 * - Read the current questions, answers, follow-ups, and audio refs from [SurveyViewModel].
 * - Render a human-readable summary of the interview session.
 * - Build a JSON export payload covering answers + follow-ups + audio references.
 * - Provide optional GitHub upload and deferred upload via WorkManager.
 * - Optionally auto-save the JSON to device storage on first composition.
 * - Upload WAV files that physically exist under exports/voice for this run.
 * - Capture and upload a logcat snapshot (gzip) for diagnostics (now / later).
 *
 * The key design rule:
 * - JSON must be built from the logical audio manifest in the ViewModel,
 *   not from file system scans.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoneScreen(
    vm: SurveyViewModel,
    onRestart: () -> Unit,
    gitHubConfig: GitHubUploader.GitHubConfig? = null,
    autoSaveToDevice: Boolean = false
) {
    val questions by vm.questions.collectAsState(initial = emptyMap())
    val answers by vm.answers.collectAsState(initial = emptyMap())
    val followups by vm.followups.collectAsState(initial = emptyMap())

    /**
     * Keep collecting this StateFlow so recomposition is triggered when
     * logical audio refs change.
     */
    val recordedAudioRefs by vm.recordedAudioRefs.collectAsState(initial = emptyMap())

    /** Stable UUID for the active survey run (single source of truth). */
    val surveyUuid by vm.surveyUuid.collectAsState()

    /**
     * Stable export timestamp for this DoneScreen session.
     *
     * This prevents accidental "new file name per click" within the same
     * completion screen.
     */
    val exportedAtStamp = remember(surveyUuid) {
        val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        fmt.format(Date())
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf(false) }
    val context = LocalContext.current

    /**
     * Audio refs that belong to the active survey run.
     *
     * The ViewModel is the logical source of truth for audio references.
     */
    val audioRefsForRun = remember(recordedAudioRefs, surveyUuid) {
        vm.getAudioRefsForRun(surveyUuid)
    }

    /**
     * Flattened audio refs for the active run.
     *
     * Keep the ordering stable and semantically meaningful.
     * The ViewModel already sorts by createdAt; we preserve that ordering.
     */
    val flatAudioRefsForRun = remember(recordedAudioRefs, surveyUuid) {
        vm.getAudioRefsForRunFlat(surveyUuid)
    }

    /**
     * File name set that should be present for this run.
     *
     * This is used to select physical WAV files for upload.
     */
    val expectedVoiceFileNames = remember(flatAudioRefsForRun) {
        flatAudioRefsForRun.map { it.fileName }.toSet()
    }

    /**
     * Physical WAV files currently present on the device for this run.
     *
     * We only include files that are referenced by the ViewModel manifest.
     */
    val voiceFilesState = remember(surveyUuid) {
        mutableStateOf<List<File>>(emptyList())
    }

    LaunchedEffect(expectedVoiceFileNames, surveyUuid) {
        val files = withContext(Dispatchers.IO) {
            scanVoiceFilesByNames(context, expectedVoiceFileNames)
        }
        voiceFilesState.value = files
    }

    val voiceFilesForRun = voiceFilesState.value

    /**
     * A lightweight snapshot of the runtime node map.
     *
     * This allows us to fall back to config-defined question texts
     * when the questions StateFlow hasn't stored them.
     */
    val nodesSnapshot: Map<String, Node> = remember(vm) { vm.nodes }

    /**
     * Build the union of keys to avoid dropping answers/audio-only nodes.
     */
    val answerOwnerIds = remember(questions, answers, audioRefsForRun) {
        val ids = linkedSetOf<String>()
        ids.addAll(questions.keys)
        ids.addAll(answers.keys)
        ids.addAll(audioRefsForRun.keys)
        ids.toList().sorted()
    }

    /**
     * Build a compact JSON export representing:
     *  - "survey_id": UUID for the current run.
     *  - "exported_at": timestamp for this export.
     *  - "answers": per-node question/answer pairs (+ optional "audio" array).
     *  - "followups": per-node arrays of follow-up question/answer pairs.
     *  - "voice_files": a flat array for ingestion pipelines.
     *
     * IMPORTANT:
     *  - The audio lists are derived from ViewModel audio refs,
     *    NOT from the file system.
     */
    val jsonText = remember(
        questions,
        answers,
        followups,
        audioRefsForRun,
        flatAudioRefsForRun,
        surveyUuid,
        exportedAtStamp,
        answerOwnerIds
    ) {
        val sortedFollowups = followups.toSortedMap()

        buildString {
            append("{\n")

            // survey_id + exported_at
            append("  \"survey_id\": \"")
                .append(escapeJson(surveyUuid))
                .append("\",\n")
            append("  \"exported_at\": \"")
                .append(escapeJson(exportedAtStamp))
                .append("\",\n")

            // answers with optional audio array
            append("  \"answers\": {\n")
            answerOwnerIds.forEachIndexed { idx, id ->
                val q = questions[id]
                    ?: nodesSnapshot[id]?.question
                    ?: ""
                val a = answers[id].orEmpty()

                /**
                 * Preserve ViewModel-provided ordering.
                 * Do not re-sort locally to avoid contradicting VM intent.
                 */
                val audioList = audioRefsForRun[id].orEmpty()

                append("    \"")
                    .append(escapeJson(id))
                    .append("\": {\n")
                append("      \"question\": \"")
                    .append(escapeJson(q))
                    .append("\",\n")
                append("      \"answer\": \"")
                    .append(escapeJson(a))
                    .append("\"")

                if (audioList.isNotEmpty()) {
                    append(",\n")
                    append("      \"audio\": [\n")
                    audioList.forEachIndexed { j, ref ->
                        append("        { \"file\": \"")
                            .append(escapeJson(ref.fileName))
                            .append("\" }")
                        if (j != audioList.lastIndex) append(",")
                        append("\n")
                    }
                    append("      ]\n")
                } else {
                    append("\n")
                }

                append("    }")
                if (idx != answerOwnerIds.lastIndex) append(",")
                append("\n")
            }
            append("  },\n")

            // followups
            append("  \"followups\": {\n")
            val fEntries = sortedFollowups.entries.toList()
            fEntries.forEachIndexed { i, (ownerId, list) ->
                append("    \"").append(escapeJson(ownerId)).append("\": [\n")
                list.forEachIndexed { j, fu ->
                    val fq = fu.question
                    val fa = fu.answer.orEmpty()
                    append("      { ")
                        .append("\"question\": \"").append(escapeJson(fq)).append("\", ")
                        .append("\"answer\": \"").append(escapeJson(fa)).append("\" ")
                        .append("}")
                    if (j != list.lastIndex) append(",")
                    append("\n")
                }
                append("    ]")
                if (i != fEntries.lastIndex) append(",")
                append("\n")
            }
            append("  },\n")

            // voice_files flat list view for ingestion pipelines
            append("  \"voice_files\": [\n")
            flatAudioRefsForRun.forEachIndexed { idx, ref ->
                val qId = ref.questionId
                val questionText = questions[qId]
                    ?: nodesSnapshot[qId]?.question
                    ?: ""
                val answerText = answers[qId].orEmpty()

                append("    {\n")
                append("      \"file\": \"")
                    .append(escapeJson(ref.fileName))
                    .append("\",\n")
                append("      \"survey_id\": \"")
                    .append(escapeJson(surveyUuid))
                    .append("\",\n")
                append("      \"question_id\": \"")
                    .append(escapeJson(qId))
                    .append("\",\n")
                append("      \"question\": \"")
                    .append(escapeJson(questionText))
                    .append("\",\n")
                append("      \"answer\": \"")
                    .append(escapeJson(answerText))
                    .append("\"\n")
                append("    }")
                if (idx != flatAudioRefsForRun.lastIndex) append(",")
                append("\n")
            }
            append("  ]\n")

            append("}\n")
        }
    }

    /**
     * Auto-save JSON once per survey UUID if requested.
     */
    val autoSavedOnce = remember(surveyUuid) { mutableStateOf(false) }

    LaunchedEffect(autoSaveToDevice, jsonText, surveyUuid) {
        if (autoSaveToDevice && !autoSavedOnce.value) {
            val fileName = buildSurveyFileName(
                surveyId = surveyUuid,
                prefix = "survey",
                stamp = exportedAtStamp
            )
            runCatching {
                val result = withContext(Dispatchers.IO) {
                    saveJsonAutomatically(
                        context = context,
                        fileName = fileName,
                        content = jsonText
                    )
                }
                autoSavedOnce.value = true
                snackbar.showOnce("Saved to device: ${result.location}")
            }.onFailure { e ->
                snackbar.showOnce("Auto-save failed: ${e.message}")
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { TopAppBar(title = { Text("Done") }) },
        snackbarHost = { SnackbarHost(hostState = snackbar) }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .padding(20.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Thanks! Here is your response summary.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Survey ID: $surveyUuid",
                style = MaterialTheme.typography.labelLarge
            )

            Spacer(Modifier.height(16.dp))

            // Answers section.
            Text("■ Answers", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (answerOwnerIds.isEmpty()) {
                Text("No answers yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                answerOwnerIds.forEach { id ->
                    val q = questions[id]
                        ?: nodesSnapshot[id]?.question
                        ?: "(unknown question)"
                    val a = answers[id].orEmpty()
                    val audioCount = audioRefsForRun[id].orEmpty().size

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Text("Q: $q", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "A: ${if (a.isBlank()) "(empty)" else a}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (audioCount > 0) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Audio: $audioCount file(s)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }

            Spacer(Modifier.height(20.dp))

            // Follow-ups section.
            Text("■ Follow-ups", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (followups.isEmpty()) {
                Text("No follow-ups.", style = MaterialTheme.typography.bodyMedium)
            } else {
                followups.toSortedMap().forEach { (ownerId, list) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = "Owner node: $ownerId",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(6.dp))
                        list.forEachIndexed { idx, fu ->
                            Text(
                                text = "${idx + 1}. ${fu.question}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            val ans = fu.answer
                            if (!ans.isNullOrBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "   ↳ $ans",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                    HorizontalDivider()
                }
            }

            Spacer(Modifier.height(20.dp))

            // Recorded voice section (logical + physical).
            Text("■ Recorded voice files", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (flatAudioRefsForRun.isEmpty()) {
                Text(
                    text = "No voice recordings registered for this survey run.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    text = "Manifest: ${flatAudioRefsForRun.size} reference(s).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "On device now: ${voiceFilesForRun.size} file(s).",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.height(6.dp))
                flatAudioRefsForRun.take(8).forEach { ref ->
                    Text(
                        text = "• ${ref.fileName}  (q=${ref.questionId})",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (flatAudioRefsForRun.size > 8) {
                    Spacer(Modifier.height(2.dp))
                    Text("… and more", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(24.dp))

            // Export / upload actions (JSON + voice + logs).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Immediate GitHub upload (JSON + voice WAV + logcat snapshot).
                if (gitHubConfig != null) {
                    Button(
                        onClick = {
                            if (uploading) return@Button
                            scope.launch {
                                uploading = true
                                try {
                                    val cfg = gitHubConfig
                                    val resultLabel: String
                                    val voiceCount: Int
                                    val logUploaded: Boolean

                                    val fileName = buildSurveyFileName(
                                        surveyId = surveyUuid,
                                        prefix = "survey",
                                        stamp = exportedAtStamp
                                    )

                                    val (jsonRes, voicesUploaded, didUploadLog) =
                                        withContext(Dispatchers.IO) {
                                            // 1) Upload JSON summary.
                                            val jsonResult = GitHubUploader.uploadJson(
                                                cfg = cfg,
                                                relativePath = fileName,
                                                content = jsonText,
                                                message = "Upload $fileName"
                                            )

                                            // 2) Upload WAV files referenced by manifest AND present on device.
                                            val currentVoiceFiles =
                                                scanVoiceFilesByNames(context, expectedVoiceFileNames)

                                            var uploadedVoices = 0
                                            currentVoiceFiles.forEach { file ->
                                                val bytes = file.readBytes()
                                                GitHubUploader.uploadFile(
                                                    cfg = cfg,
                                                    relativePath = "$REMOTE_VOICE_DIR/${file.name}",
                                                    bytes = bytes,
                                                    message = "Upload ${file.name}"
                                                )
                                                runCatching { deleteVoiceSidecars(file) }
                                                runCatching { file.delete() }
                                                uploadedVoices++
                                            }

                                            // 3) Upload session log snapshot (gzip).
                                            val logFile = captureSessionLogcatToPendingFile(
                                                context = context,
                                                surveyUuid = surveyUuid,
                                                exportedAtStamp = exportedAtStamp,
                                                maxBytes = MAX_LOGCAT_BYTES
                                            )

                                            val didUpload = runCatching {
                                                GitHubUploader.uploadFile(
                                                    cfg = cfg,
                                                    relativePath = "$REMOTE_LOG_DIR/${logFile.name}",
                                                    bytes = logFile.readBytes(),
                                                    message = "Upload ${logFile.name}"
                                                )
                                            }.isSuccess

                                            if (didUpload) {
                                                runCatching { logFile.delete() }
                                            }

                                            Triple(jsonResult, uploadedVoices, didUpload)
                                        }

                                    resultLabel = jsonRes.fileUrl ?: jsonRes.commitSha ?: "(no URL)"
                                    voiceCount = voicesUploaded
                                    logUploaded = didUploadLog

                                    val logMsg = if (logUploaded) " + logs" else ""
                                    if (voiceCount > 0) {
                                        snackbar.showOnce("Uploaded JSON + $voiceCount voice file(s)$logMsg: $resultLabel")
                                    } else {
                                        snackbar.showOnce("Uploaded JSON$logMsg: $resultLabel")
                                    }

                                    // Refresh physical list after deletion.
                                    val remaining = withContext(Dispatchers.IO) {
                                        scanVoiceFilesByNames(context, expectedVoiceFileNames)
                                    }
                                    voiceFilesState.value = remaining
                                } catch (e: Exception) {
                                    snackbar.showOnce("Upload failed: ${e.message}")
                                } finally {
                                    uploading = false
                                }
                            }
                        },
                        enabled = !uploading
                    ) {
                        Text(if (uploading) "Uploading..." else "Upload now")
                    }
                }

                // Deferred GitHub upload for JSON + voice + logs via WorkManager.
                if (gitHubConfig != null) {
                    Button(
                        onClick = {
                            val cfg = gitHubConfig

                            // 1) Schedule JSON by writing a pending file.
                            val jsonRemoteName = buildSurveyFileName(
                                surveyId = surveyUuid,
                                prefix = "survey",
                                stamp = exportedAtStamp
                            )

                            runCatching {
                                val pendingJson = writePendingTextFile(
                                    context = context,
                                    fileName = jsonRemoteName,
                                    content = jsonText
                                )
                                enqueueWorkerFileUpload(
                                    context = context,
                                    cfg = cfg,
                                    localFile = pendingJson,
                                    remoteRelativePath = jsonRemoteName
                                )
                                scope.launch {
                                    snackbar.showOnce("Upload scheduled (JSON, will run when online).")
                                }
                            }.onFailure { e ->
                                scope.launch {
                                    snackbar.showOnce("Failed to schedule JSON upload: ${e.message}")
                                }
                            }

                            // 2) Schedule WAV uploads that physically exist and are referenced by manifest.
                            val wavsToSchedule = voiceFilesForRun
                            if (wavsToSchedule.isEmpty()) {
                                scope.launch {
                                    snackbar.showOnce("No voice recordings on device to upload.")
                                }
                            } else {
                                wavsToSchedule.forEach { file ->
                                    val remote = "$REMOTE_VOICE_DIR/${file.name}"
                                    runCatching {
                                        enqueueWorkerFileUpload(
                                            context = context,
                                            cfg = cfg,
                                            localFile = file,
                                            remoteRelativePath = remote
                                        )
                                    }
                                }
                                scope.launch {
                                    snackbar.showOnce("Upload scheduled (${wavsToSchedule.size} voice file(s)).")
                                }
                            }

                            // 3) Schedule logcat snapshot upload (gzip).
                            runCatching {
                                val pendingLog = captureSessionLogcatToPendingFile(
                                    context = context,
                                    surveyUuid = surveyUuid,
                                    exportedAtStamp = exportedAtStamp,
                                    maxBytes = MAX_LOGCAT_BYTES
                                )
                                enqueueWorkerFileUpload(
                                    context = context,
                                    cfg = cfg,
                                    localFile = pendingLog,
                                    remoteRelativePath = "$REMOTE_LOG_DIR/${pendingLog.name}"
                                )
                                scope.launch {
                                    snackbar.showOnce("Upload scheduled (logs, will run when online).")
                                }
                            }.onFailure { e ->
                                scope.launch {
                                    snackbar.showOnce("Failed to schedule logs: ${e.message}")
                                }
                            }
                        },
                        enabled = !uploading
                    ) {
                        Text("Upload later")
                    }
                }

                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))

            // Optional: log-only actions.
            if (gitHubConfig != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            if (uploading) return@Button
                            scope.launch {
                                uploading = true
                                try {
                                    val cfg = gitHubConfig
                                    val ok = withContext(Dispatchers.IO) {
                                        val logFile = captureSessionLogcatToPendingFile(
                                            context = context,
                                            surveyUuid = surveyUuid,
                                            exportedAtStamp = exportedAtStamp,
                                            maxBytes = MAX_LOGCAT_BYTES
                                        )
                                        val didUpload = runCatching {
                                            GitHubUploader.uploadFile(
                                                cfg = cfg,
                                                relativePath = "$REMOTE_LOG_DIR/${logFile.name}",
                                                bytes = logFile.readBytes(),
                                                message = "Upload ${logFile.name}"
                                            )
                                        }.isSuccess
                                        if (didUpload) runCatching { logFile.delete() }
                                        didUpload
                                    }
                                    snackbar.showOnce(if (ok) "Uploaded logs." else "Log upload failed.")
                                } catch (e: Exception) {
                                    snackbar.showOnce("Log upload failed: ${e.message}")
                                } finally {
                                    uploading = false
                                }
                            }
                        },
                        enabled = !uploading
                    ) {
                        Text("Upload logs")
                    }

                    Button(
                        onClick = {
                            val cfg = gitHubConfig
                            runCatching {
                                val pendingLog = captureSessionLogcatToPendingFile(
                                    context = context,
                                    surveyUuid = surveyUuid,
                                    exportedAtStamp = exportedAtStamp,
                                    maxBytes = MAX_LOGCAT_BYTES
                                )
                                enqueueWorkerFileUpload(
                                    context = context,
                                    cfg = cfg,
                                    localFile = pendingLog,
                                    remoteRelativePath = "$REMOTE_LOG_DIR/${pendingLog.name}"
                                )
                                scope.launch {
                                    snackbar.showOnce("Logs scheduled (will run when online).")
                                }
                            }.onFailure { e ->
                                scope.launch {
                                    snackbar.showOnce("Failed to schedule logs: ${e.message}")
                                }
                            }
                        },
                        enabled = !uploading
                    ) {
                        Text("Upload logs later")
                    }

                    Spacer(Modifier.weight(1f))
                }

                Spacer(Modifier.height(12.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = onRestart, enabled = !uploading) {
                    Text("Restart")
                }
            }
        }
    }

    /**
     * Friendly completion snackbar for this run.
     */
    LaunchedEffect(surveyUuid) {
        snackbar.showOnce("Thank you for your responses")
    }
}

/* ============================================================
 * WorkManager enqueue helpers
 * ============================================================ */

/**
 * Enqueue GitHubUploadWorker in "file" mode with a custom remote relative path.
 *
 * Important:
 * - GitHubUploadWorker uses KEY_FILE_NAME as the *remote relative path*.
 * - Passing "voice/<name>.wav" keeps remote structure aligned with immediate upload.
 */
private fun enqueueWorkerFileUpload(
    context: Context,
    cfg: GitHubUploader.GitHubConfig,
    localFile: File,
    remoteRelativePath: String
) {
    val safeUnique = sanitizeWorkName(remoteRelativePath)
    val uniqueName = "upload_$safeUnique"

    val req: OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<GitHubUploadWorker>()
            .setInputData(
                workDataOf(
                    GitHubUploadWorker.KEY_MODE to "file",
                    GitHubUploadWorker.KEY_OWNER to cfg.owner,
                    GitHubUploadWorker.KEY_REPO to cfg.repo,
                    GitHubUploadWorker.KEY_TOKEN to cfg.token,
                    GitHubUploadWorker.KEY_BRANCH to cfg.branch,
                    GitHubUploadWorker.KEY_PATH_PREFIX to cfg.pathPrefix,

                    GitHubUploadWorker.KEY_FILE_PATH to localFile.absolutePath,
                    GitHubUploadWorker.KEY_FILE_NAME to remoteRelativePath
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(GitHubUploadWorker.TAG)
            .addTag("${GitHubUploadWorker.TAG}:file:$safeUnique")
            .build()

    WorkManager.getInstance(context)
        .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, req)
}

/**
 * Sanitize a unique work name so WorkManager never sees illegal characters.
 */
private fun sanitizeWorkName(value: String): String {
    return value
        .trim()
        .replace(Regex("""[^\w\-.]+"""), "_")
        .take(120)
}

/**
 * Write a pending UTF-8 text payload to app-private storage so WorkManager can upload it later.
 *
 * Location:
 *  - /files/pending_uploads/<fileName or unique>
 */
private fun writePendingTextFile(
    context: Context,
    fileName: String,
    content: String
): File {
    require(fileName.isNotBlank()) { "fileName is blank." }

    val safeName = sanitizeFileName(fileName)
    val dir = File(context.filesDir, "pending_uploads").apply { mkdirs() }
    val target = uniqueIfExists(File(dir, safeName))

    target.writeText(content, Charsets.UTF_8)
    return target
}

/**
 * Replace all non `[A-Za-z0-9_.-/]` characters with underscores.
 *
 * Note:
 * - We allow '/' because remoteRelativePath may include subdirs like "voice/<name>.wav".
 * - For local pending files, slashes are converted to underscores to keep a flat directory.
 */
private fun sanitizeFileName(name: String): String {
    val flattened = name.replace("/", "_")
    return flattened.replace(Regex("""[^\w\-.]"""), "_")
}

/**
 * Append a numeric suffix until a non-existing file name is found.
 */
private fun uniqueIfExists(file: File): File {
    if (!file.exists()) return file

    val base = file.nameWithoutExtension
    val ext = file.extension.takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""

    var idx = 1
    while (true) {
        val candidate = File(file.parentFile, "${base}_$idx$ext")
        if (!candidate.exists()) return candidate
        idx++
    }
}

/* ============================================================
 * Voice scan helpers (physical files)
 * ============================================================ */

/**
 * Scan exported voice WAV files under exports/voice and return only files whose
 * names are included in [expectedNames].
 *
 * This ensures we upload only files referenced by the ViewModel manifest,
 * avoiding accidental uploads of leftovers from other runs.
 */
private fun scanVoiceFilesByNames(
    context: Context,
    expectedNames: Set<String>
): List<File> {
    if (expectedNames.isEmpty()) return emptyList()

    val voiceDir = ExportUtils.getVoiceExportDir(context)
    if (!voiceDir.exists() || !voiceDir.isDirectory) return emptyList()

    val wavFiles = voiceDir.listFiles { f ->
        f.isFile &&
                !f.name.startsWith(".") &&
                f.name.lowercase(Locale.US).endsWith(".wav") &&
                expectedNames.contains(f.name)
    } ?: return emptyList()

    return wavFiles.sortedByDescending { it.lastModified() }
}

/**
 * Delete sidecar metadata files for a given WAV if they exist.
 *
 * Expected patterns:
 *  - <baseName>.meta.json
 *
 * This is safe even if your current export pipeline no longer writes meta files.
 */
private fun deleteVoiceSidecars(wavFile: File) {
    val dir = wavFile.parentFile ?: return
    val base = wavFile.name.substringBeforeLast('.', wavFile.name)
    val meta = File(dir, "$base.meta.json")
    if (meta.exists()) {
        runCatching { meta.delete() }
    }
}

/* ============================================================
 * Logcat capture helpers (diagnostics)
 * ============================================================ */

/**
 * Capture a best-effort logcat snapshot for this survey run as a gzip file
 * under app-private storage.
 *
 * The file is placed under /files/pending_uploads so it can be uploaded later
 * by WorkManager even if the device is currently offline.
 */
private fun captureSessionLogcatToPendingFile(
    context: Context,
    surveyUuid: String,
    exportedAtStamp: String,
    maxBytes: Int
): File {
    val pid = Process.myPid()
    val shortId = surveyUuid.take(8).ifBlank { "unknown" }
    val baseName = "logcat_${exportedAtStamp}_pid${pid}_$shortId.log.gz"
    val safeName = sanitizeFileName(baseName)

    val dir = File(context.filesDir, "pending_uploads").apply { mkdirs() }
    val outFile = uniqueIfExists(File(dir, safeName))

    val header = buildString {
        appendLine("=== Session Log Snapshot ===")
        appendLine("time_local=$exportedAtStamp")
        appendLine("survey_id=$surveyUuid")
        appendLine("pid=$pid")
        appendLine("sdk=${Build.VERSION.SDK_INT}")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("appId=${BuildConfig.APPLICATION_ID}")
        appendLine("versionName=${BuildConfig.VERSION_NAME}")
        appendLine("versionCode=${BuildConfig.VERSION_CODE}")
        appendLine("tags=${LOGCAT_TAG_FILTERS.joinToString(",")}")
        appendLine()
        appendLine("=== Logcat (best-effort) ===")
    }.toByteArray(Charsets.UTF_8)

    val logBytes = collectLogcatBytesBestEffort(
        pid = pid,
        maxBytes = maxBytes,
        tags = LOGCAT_TAG_FILTERS
    )

    FileOutputStream(outFile).use { fos ->
        GZIPOutputStream(fos).use { gz ->
            gz.write(header)
            gz.write(logBytes)
            gz.flush()
        }
    }

    Log.d(LOG_TAG, "Captured logcat snapshot: ${outFile.absolutePath} (${outFile.length()} bytes gz)")
    return outFile
}

/**
 * Collect logcat output as bytes with multiple fallback strategies.
 *
 * Strategy priority (best to worst):
 *  1) logcat -d --pid=<pid> -v threadtime -s <tags...>
 *  2) logcat -d --pid=<pid> -v threadtime
 *  3) logcat -d -v threadtime -s <tags...>
 *  4) logcat -d -v threadtime
 */
private fun collectLogcatBytesBestEffort(
    pid: Int,
    maxBytes: Int,
    tags: Array<String>
): ByteArray {
    val cmd1 = arrayOf("logcat", "-d", "--pid=$pid", "-v", "threadtime", "-s", *tags)
    val cmd2 = arrayOf("logcat", "-d", "--pid=$pid", "-v", "threadtime")
    val cmd3 = arrayOf("logcat", "-d", "-v", "threadtime", "-s", *tags)
    val cmd4 = arrayOf("logcat", "-d", "-v", "threadtime")

    return runCatching { execAndReadCapped(cmd1, maxBytes) }
        .recoverCatching { execAndReadCapped(cmd2, maxBytes) }
        .recoverCatching { execAndReadCapped(cmd3, maxBytes) }
        .recoverCatching { execAndReadCapped(cmd4, maxBytes) }
        .getOrElse { e ->
            ("(logcat capture failed: ${e.message})\n").toByteArray(Charsets.UTF_8)
        }
}

/**
 * Execute a command and read stdout up to [maxBytes].
 */
private fun execAndReadCapped(cmd: Array<String>, maxBytes: Int): ByteArray {
    val proc = Runtime.getRuntime().exec(cmd)
    val input = BufferedInputStream(proc.inputStream)

    val out = ByteArray(maxBytes)
    var total = 0

    while (total < maxBytes) {
        val n = input.read(out, total, maxBytes - total)
        if (n <= 0) break
        total += n
    }

    runCatching { input.close() }
    runCatching { proc.destroy() }

    return if (total == out.size) out else out.copyOf(total)
}

/* ============================================================
 * Auto-save helpers (no user interaction)
 * ============================================================ */

private data class SaveResult(
    val uri: Uri?,
    val file: File?,
    val location: String
)

/**
 * Save JSON to a stable device location without user interaction.
 *
 * - API 29+: MediaStore Downloads/SurveyNav
 * - API 28-: App-specific external Downloads/SurveyNav
 */
private fun saveJsonAutomatically(
    context: Context,
    fileName: String,
    content: String
): SaveResult {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveToDownloadsQPlus(context, fileName, content)
    } else {
        saveToAppExternalPreQ(context, fileName, content)
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun saveToDownloadsQPlus(
    context: Context,
    fileName: String,
    content: String
): SaveResult {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "application/json")
        put(
            MediaStore.Downloads.RELATIVE_PATH,
            Environment.DIRECTORY_DOWNLOADS + "/SurveyNav"
        )
        put(MediaStore.Downloads.IS_PENDING, 1)
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("Failed to create download entry")

    try {
        resolver.openOutputStream(uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Failed to open output stream")

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        return SaveResult(
            uri = uri,
            file = null,
            location = "Downloads/SurveyNav/$fileName"
        )
    } catch (t: Throwable) {
        resolver.delete(uri, null, null)
        throw t
    }
}

private fun saveToAppExternalPreQ(
    context: Context,
    fileName: String,
    content: String
): SaveResult {
    val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: context.filesDir
    val dir = File(base, "SurveyNav").apply { mkdirs() }
    val file = File(dir, fileName)
    file.writeText(content, Charsets.UTF_8)

    return SaveResult(
        uri = null,
        file = file,
        location = file.absolutePath
    )
}

/* ============================================================
 * Snackbar + JSON utilities
 * ============================================================ */

/**
 * Show a snackbar message while ensuring only one active snackbar at a time.
 */
private suspend fun SnackbarHostState.showOnce(message: String) {
    currentSnackbarData?.dismiss()
    showSnackbar(message)
}

/**
 * Minimal JSON string escaper for manual export.
 */
private fun escapeJson(s: String): String =
    buildString(s.length + 8) {
        s.forEach { ch ->
            when (ch) {
                '\"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
