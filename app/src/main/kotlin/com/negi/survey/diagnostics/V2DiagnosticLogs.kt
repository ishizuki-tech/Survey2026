package com.negi.survey.diagnostics

import android.content.Context
import android.os.Process
import com.negi.survey.net.V2ArtifactStore
import com.negi.survey.net.V2ArtifactStore.Kind
import com.negi.survey.utils.DeviceUploadTagProvider
import com.negi.survey.utils.V2ArtifactPaths
import com.negi.survey.utils.V2ArtifactPaths.StandaloneDiagnostic
import java.io.OutputStream
import java.time.Instant

/** Stages log captures without inferring Survey ownership from the caller or a filename. */
internal object V2DiagnosticLogs {
    fun stage(
        context: Context,
        kind: Kind,
        id: String,
        event: Instant,
        captureStart: Instant,
        captureEnd: Instant,
        scope: String,
        evidence: SurveyDiagnosticAttribution.Interval?,
        profile: V2ArtifactStore.Profile = V2ArtifactStore.profile,
        relatedSurveyIds: Set<String> = emptySet(),
        collectorSession: String = SurveyDiagnosticAttribution.processSession,
        collectorPid: Int = Process.myPid(),
        write: (OutputStream) -> Unit,
    ) {
        val store = V2ArtifactStore(context)
        val log = when (kind) {
            Kind.LOGCAT -> StandaloneDiagnostic.LOGCAT
            Kind.RUNTIME -> StandaloneDiagnostic.RUNTIME
            Kind.CRASH -> StandaloneDiagnostic.CRASH
            else -> error("Invalid log kind.")
        }
        // Repeat recovery of a known capture must use the route already committed for that capture.
        if (store.hasReservedRoute(id)) {
            val staged = checkNotNull(store.recoverAndRead(id)) { "Existing log capture route is invalid." }
            check(staged.hasReceipt() || staged.matchesPayload()) { "Existing log capture payload is invalid." }
            return
        }
        val path = if (evidence != null) {
            store.paths(context, evidence.surveyId).diagnostic(log, id)
        } else {
            V2ArtifactPaths.standaloneDiagnostic(log, event, DeviceUploadTagProvider.from(context), id)
        }
        store.stage(id, kind, evidence?.surveyId, profile, path, rebuildUnsealed = true) { output ->
            val writeWithMetadata: (OutputStream) -> Unit = { stream ->
                val metadata = buildString {
                    appendLine("event_utc=$event")
                    appendLine("capture_start_utc=$captureStart")
                    appendLine("capture_end_utc=$captureEnd")
                    appendLine("collection_scope=$scope")
                    appendLine("collector_process_session=$collectorSession")
                    appendLine("collector_process_pid=$collectorPid")
                    if (relatedSurveyIds.isNotEmpty()) {
                        appendLine("related_survey_ids=${relatedSurveyIds.sorted().joinToString(",")}")
                    }
                    evidence?.let {
                        appendLine("survey_id=${it.surveyId}")
                        appendLine("survey_active_start_utc=${Instant.ofEpochMilli(it.startMillis)}")
                        appendLine("survey_active_end_utc=${it.endMillis?.let(Instant::ofEpochMilli) ?: "active at capture"}")
                        appendLine("survey_process_session=${it.processSession}")
                        appendLine("survey_process_pid=${it.pid}")
                    }
                }
                stream.write(metadata.toByteArray(Charsets.UTF_8))
                write(stream)
            }
            if (log == StandaloneDiagnostic.LOGCAT) V2TraceArtifacts.writeGzip(output, writeWithMetadata)
            else writeWithMetadata(output)
        }
    }
}
