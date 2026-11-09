package com.negi.survey.diagnostics

import android.content.Context
import com.negi.survey.net.V2ArtifactStore.Kind
import com.negi.survey.slm.PromptPhase
import com.negi.survey.slm.RepositoryTraceEvent
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Mirrors existing repository events using operation and request metadata only. */
internal object V2SlmTrace {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    fun observer(
        context: Context,
        surveyId: String,
        phase: PromptPhase,
        questionId: String,
        answerTraceId: String,
    ): ((RepositoryTraceEvent) -> Unit)? {
        val operation = when (phase) {
            PromptPhase.EVAL -> "eval"
            PromptPhase.FOLLOWUP -> "followup"
            PromptPhase.ONE_STEP -> return null
        }
        var writer: TraceWriter? = null
        var requestId: Long? = null
        return { event ->
            val line = buildJsonObject {
                put("surveyId", surveyId)
                put("questionId", questionId)
                put("answerTraceId", answerTraceId)
                put("timestampUtc", Instant.now().toString())
                put("requestPhase", phase.name)
                when (event) {
                    is RepositoryTraceEvent.Started -> {
                        put("event", "AI_REPOSITORY_REQUEST")
                        put("repositoryRequestId", event.requestId)
                    }
                    is RepositoryTraceEvent.Finished -> {
                        put("event", "AI_REPOSITORY_FINISHED")
                        put("repositoryRequestId", event.requestId)
                        put("liteRtRunId", event.liteRtRunId)
                        put("errorPresent", event.error != null)
                    }
                }
            }.toString()
            val started = event is RepositoryTraceEvent.Started
            val id = when (event) {
                is RepositoryTraceEvent.Started -> event.requestId
                is RepositoryTraceEvent.Finished -> event.requestId
            }
            scope.launch {
                runCatching {
                    if (started) {
                        writer?.finalizeToGzip()
                        writer = V2TraceArtifacts.writer(
                            context, surveyId, Kind.SLM, questionId, operation, metadataOnly = true
                        )
                        requestId = id
                    }
                    if (requestId == id) {
                        writer?.append(line)
                        if (!started) {
                            writer?.finalizeToGzip()
                            writer = null
                            requestId = null
                        }
                    }
                }.onFailure { DiagnosticContentPolicy.logWarning("V2SlmTrace", "Request trace staging failed", it) }
            }
        }
    }
}
