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
 *  Presentation-only completion screen. It shows local survey/upload status,
 *  optionally saves the already-built JSON locally, and starts a new survey.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.screens

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.negi.survey.net.SurveyExportJsonBuilder
import com.negi.survey.utils.DeviceUploadTagProvider
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.utils.buildSurveyFileName
import com.negi.survey.vm.SurveyViewModel
import com.negi.survey.vm.UploadStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoneScreen(
    vm: SurveyViewModel,
    uploadStatus: UploadStatus,
    onRestart: () -> Unit,
    onExit: () -> Unit,
    autoSaveToDevice: Boolean = false
) {
    val questions by vm.questions.collectAsState(initial = emptyMap())
    val answers by vm.answers.collectAsState(initial = emptyMap())
    val followups by vm.followups.collectAsState(initial = emptyMap())
    val aiReasons by vm.aiReasons.collectAsState()
    val recordedAudioRefs by vm.recordedAudioRefs.collectAsState(initial = emptyMap())
    val surveyUuid by vm.surveyUuid.collectAsState()

    val runFreeText by vm.runFreeText.collectAsState(initial = "")
    val exportedAtStamp = remember(surveyUuid) {
        val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        fmt.format(Date())
    }

    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val flatAudioRefsForRun = remember(recordedAudioRefs, surveyUuid) {
        vm.getAudioRefsForRunFlat(surveyUuid)
    }

    val finalizationSnapshot = remember(
        questions,
        answers,
        followups,
        aiReasons,
        recordedAudioRefs,
        surveyUuid,
        runFreeText
    ) {
        vm.createFinalizationSnapshot()
    }
    val jsonText = remember(finalizationSnapshot, exportedAtStamp) {
        SurveyExportJsonBuilder.build(finalizationSnapshot, exportedAtStamp)
    }

    val autoSavedOnce = remember(surveyUuid) { mutableStateOf(false) }
    val deviceUploadTag = remember(context) { DeviceUploadTagProvider.from(context) }

    LaunchedEffect(autoSaveToDevice, jsonText, surveyUuid) {
        if (autoSaveToDevice && !autoSavedOnce.value) {
            val fileName = buildSurveyFileName(
                surveyId = surveyUuid,
                deviceTag = deviceUploadTag,
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
        topBar = { TopAppBar(title = { Text("Done") }) }
    ) { pad ->
        Box(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Survey completed",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Survey ID: $surveyUuid",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Device ID: ${deviceUploadTag.value}",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Uploaded Surveys on This Device: ${uploadStatus.uploadedCount}",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Pending Survey Uploads: ${uploadStatus.pendingCount}",
                    style = MaterialTheme.typography.labelLarge
                )

                Spacer(Modifier.height(16.dp))
                Text("Voice Recordings: ${flatAudioRefsForRun.size}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            onRestart()
                        }
                    ) {
                        Text("Start New Survey")
                    }
                    Button(
                        onClick = {
                            onExit()
                        }
                    ) {
                        Text("Exit")
                    }
                }
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }

    LaunchedEffect(surveyUuid) {
        snackbar.showOnce("Thank you for your responses")
    }
}

/* ============================================================
 * Auto-save helpers (no user interaction)
 * ============================================================ */

private data class SaveResult(
    val uri: Uri?,
    val file: File?,
    val location: String
)

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
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SurveyNav")
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

private suspend fun SnackbarHostState.showOnce(message: String) {
    currentSnackbarData?.dismiss()
    // If this screen is torn down before the message is dismissed, bound the wait.
    withTimeoutOrNull(6_000) { showSnackbar(message) }
}
