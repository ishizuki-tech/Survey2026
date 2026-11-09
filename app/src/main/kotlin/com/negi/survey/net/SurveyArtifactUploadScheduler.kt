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
import android.os.Process
import com.negi.survey.CrashCapture
import com.negi.survey.diagnostics.SurveyDiagnosticAttribution
import com.negi.survey.diagnostics.V2DiagnosticLogs
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Collects finalization logcat using the existing PID and fallback collection modes. */
internal class SurveyArtifactUploadScheduler(context: Context) {
    private val appContext = context.applicationContext

    suspend fun stageV2Logcat(triggerSurveyId: String) = withContext(Dispatchers.IO) {
        val pid = Process.myPid()
        val started = Instant.now()
        val activeStart = SurveyDiagnosticAttribution.activeStart()
        val scoped = activeStart?.let { start ->
            runCatching {
                val marker = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS", Locale.US)
                    .withZone(ZoneId.systemDefault()).format(start)
                execAndReadCapped(
                    arrayOf("logcat", "-d", "--pid=$pid", "-T", marker, "-v", "epoch", "-s", *LOGCAT_TAG_FILTERS),
                    MAX_LOGCAT_BYTES
                )
            }.getOrNull()
        }
        val interval = scoped?.let { logcatInterval(it, pid) }
        val bytes = if (interval == null) collectLogcatBytesBestEffort(pid, MAX_LOGCAT_BYTES) else checkNotNull(scoped)
        val ended = Instant.now()
        val evidence = interval?.takeIf { it.second <= ended }?.let {
            SurveyDiagnosticAttribution.currentProcessEvidence(it.first, it.second)
        }
        V2DiagnosticLogs.stage(
            appContext, V2ArtifactStore.Kind.LOGCAT, V2ArtifactStore.newId(), started,
            interval?.first ?: started, interval?.second ?: ended,
            if (interval != null) "verified_pid_epoch_logcat" else "best_effort_logcat",
            evidence,
            relatedSurveyIds = setOf(triggerSurveyId),
        ) { it.write(bytes) }
    }

    private fun logcatInterval(bytes: ByteArray, expectedPid: Int): Pair<Instant, Instant>? {
        if (bytes.isEmpty() || bytes.last() != '\n'.code.toByte()) return null
        val entry = Regex("^([0-9]+)\\.([0-9]{3,6})\\s+([0-9]+)\\s+[0-9]+\\s+[VDIWEFAS]\\s+.*$")
        val events = mutableListOf<Instant>()
        for (line in bytes.toString(Charsets.UTF_8).lineSequence()) {
            if (line.isBlank() || line.startsWith("---------")) continue
            val parsed = entry.matchEntire(line) ?: return null
            if (parsed.groupValues[3].toIntOrNull() != expectedPid) return null
            val seconds = parsed.groupValues[1].toLongOrNull() ?: return null
            val nanos = parsed.groupValues[2].padEnd(9, '0').toLong()
            events += Instant.ofEpochSecond(seconds, nanos)
        }
        return (events.minOrNull() ?: return null) to (events.maxOrNull() ?: return null)
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
        return CrashCapture.execAndReadCapped(command.toList(), maxBytes, 600L)
    }

    private companion object {
        const val MAX_LOGCAT_BYTES = 850_000
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
