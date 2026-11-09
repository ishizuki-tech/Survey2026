package com.negi.survey.diagnostics

import android.content.Context
import com.negi.survey.net.V2ArtifactStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Best-effort voice diagnostics sharing the survey answer transaction identity. */
class WhisperTrace internal constructor(private val identity: TraceIdentity, private val writer: TraceWriter) {
    fun event(name: String, answerTraceId: String?, questionId: String?, fields: Map<String, Any?> = emptyMap()) = runCatching {
        writer.append(JSONObject().apply {
            put("traceVersion", 1); put("eventId", TraceIdentity.newEventId()); put("timestampUtc", java.time.Instant.now().toString())
            put("stream", "whisper"); put("event", name); put("surveyId", identity.surveyId); put("shortSurveyId", identity.shortSurveyId)
            put("deviceTag", identity.deviceTag); put("processId", identity.processId); identity.appVersion?.let { put("appVersion", it) }
            answerTraceId?.let { put("answerTraceId", it) }; questionId?.let { put("questionId", it) }
            DiagnosticContentPolicy.sanitizeTraceFields(fields)
                .forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }.toString())
    }
    suspend fun finalizeTrace(): File? = withContext(Dispatchers.IO) { writer.finalizeToGzip() }
    companion object {
        fun open(context: Context, surveyId: String): WhisperTrace {
            val identity = TraceIdentity.forSurvey(context.applicationContext, surveyId)
            return WhisperTrace(identity, V2TraceArtifacts.writer(context, surveyId, V2ArtifactStore.Kind.WHISPER_TRACE))
        }
    }
}

object WhisperTraceRegistry {
    private val active = ConcurrentHashMap<String, WhisperTrace>()
    private val answerIds = ConcurrentHashMap<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun start(context: Context, surveyId: String): WhisperTrace? = runCatching { active.computeIfAbsent(surveyId) { WhisperTrace.open(context, surveyId) } }.getOrNull()
    fun beginVoiceTransaction(context: Context, surveyId: String, questionId: String, answerTraceId: String) {
        answerIds["$surveyId|$questionId"] = answerTraceId
        start(context, surveyId)?.event("MIC_TRANSACTION_STARTED", answerTraceId, questionId, mapOf("inputSource" to "voice"))
    }
    fun event(surveyId: String?, questionId: String?, name: String, fields: Map<String, Any?> = emptyMap()) {
        if (surveyId == null || questionId == null) return
        active[surveyId]?.event(name, answerIds["$surveyId|$questionId"], questionId, fields)
    }
    fun finalizeAsync(surveyId: String) {
        val trace = active.remove(surveyId) ?: return
        scope.launch { runCatching { trace.finalizeTrace() } }
    }
}
