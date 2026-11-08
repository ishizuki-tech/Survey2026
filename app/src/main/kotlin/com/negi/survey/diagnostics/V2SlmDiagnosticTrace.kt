package com.negi.survey.diagnostics

import com.negi.survey.net.V2DiagnosticCapture
import com.negi.survey.net.V2DiagnosticRouteStore
import com.negi.survey.net.V2DiagnosticStream
import com.negi.survey.net.V2SlmCorrelation
import com.negi.survey.net.V2SurveyJsonDestination
import com.negi.survey.slm.PromptPhase
import com.negi.survey.slm.RepositoryTraceContext
import com.negi.survey.slm.RepositoryTraceEvent
import com.negi.survey.utils.DeviceUploadTag
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit adapter for an existing repository request. No raw repository event is serialized. */
internal class V2SlmDiagnosticTrace private constructor(
    private val identity: TraceIdentity,
    private val answerTraceId: String,
    private val questionId: String,
    private val phase: PromptPhase,
    private val requestId: Long,
    private val writer: V2DiagnosticTraceWriter
) {
    private var finished = false

    @Synchronized
    fun event(event: RepositoryTraceEvent): Boolean {
        if (finished) return false
        when (event) {
            is RepositoryTraceEvent.Started -> {
                if (event.requestId != requestId) return false
                writer.event(identity, "slm", "AI_REPOSITORY_REQUEST", answerTraceId, questionId,
                    mapOf("repositoryRequestId" to requestId, "requestPhase" to phase.name))
            }
            is RepositoryTraceEvent.Finished -> {
                if (event.requestId != requestId) return false
                writer.event(identity, "slm", "AI_REPOSITORY_FINISHED", answerTraceId, questionId,
                    mapOf("repositoryRequestId" to requestId, "requestPhase" to phase.name,
                        "liteRtRunId" to event.liteRtRunId, "errorPresent" to (event.error != null)))
                finished = true
            }
        }
        return true
    }

    suspend fun finalizeTrace(): File? = withContext(Dispatchers.IO) {
        if (synchronized(this@V2SlmDiagnosticTrace) { finished }) writer.finalizeToGzip() else null
    }

    companion object {
        fun open(
            identity: TraceIdentity,
            store: V2DiagnosticRouteStore,
            destination: V2SurveyJsonDestination,
            context: RepositoryTraceContext,
            requestId: Long
        ): V2SlmDiagnosticTrace {
            val operation = when (context.phase) {
                PromptPhase.EVAL -> "eval"
                PromptPhase.FOLLOWUP -> "followup"
                PromptPhase.ONE_STEP -> throw IllegalArgumentException("V2 SLM traces require an eval or followup request")
            }
            val capture = V2DiagnosticCapture.create(
                identity.surveyId, DeviceUploadTag(identity.deviceTag), destination, V2DiagnosticStream.SLM,
                slm = V2SlmCorrelation(context.questionId, operation, requestId)
            )
            return V2SlmDiagnosticTrace(
                identity, context.answerTraceId, context.questionId, context.phase, requestId,
                V2DiagnosticTraceWriter(store, capture)
            )
        }
    }
}
