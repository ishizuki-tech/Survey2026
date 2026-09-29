package com.negi.survey.diagnostics

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Best-effort, full-payload survey trace. It is deliberately independent of survey behavior. */
class SurveyTrace private constructor(
    private val identity: TraceIdentity,
    private val writer: TraceWriter
) {
    val finalizedFileName: String get() = writer.finalFileName
    fun event(name: String, answerTraceId: String? = null, questionId: String? = null, fields: Map<String, Any?> = emptyMap()) {
        runCatching { eventUnsafe(name, answerTraceId, questionId, fields) }
    }

    private fun eventUnsafe(name: String, answerTraceId: String?, questionId: String?, fields: Map<String, Any?>) {
        val record = JSONObject().apply {
            put("traceVersion", 1)
            put("eventId", TraceIdentity.newEventId())
            put("timestampUtc", java.time.Instant.now().toString())
            put("stream", "survey")
            put("event", name)
            put("surveyId", identity.surveyId)
            put("shortSurveyId", identity.shortSurveyId)
            put("deviceTag", identity.deviceTag)
            put("processId", identity.processId)
            identity.appVersion?.let { put("appVersion", it) }
            answerTraceId?.let { put("answerTraceId", it) }
            questionId?.let { put("questionId", it) }
            fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }
        writer.append(record.toString())
    }

    suspend fun finalizeTrace(): File? = withContext(Dispatchers.IO) { writer.finalizeToGzip() }

    companion object {
        fun open(context: Context, surveyId: String): SurveyTrace {
            val identity = TraceIdentity.forSurvey(context.applicationContext, surveyId)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(context.filesDir, "diagnostics/survey_traces/${stamp}_${identity.deviceTag}_${identity.shortSurveyId}_survey.ndjson.open")
            return SurveyTrace(identity, TraceWriter(file))
        }
    }
}

/** Keeps the active in-process trace reachable from finalization without changing upload behavior. */
object SurveyTraceRegistry {
    private val traces = ConcurrentHashMap<String, SurveyTrace>()
    private val finalizationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context, surveyId: String, configId: String? = null): SurveyTrace? = runCatching {
        traces[surveyId] ?: run {
            SurveyTrace.open(context, surveyId).also {
                it.event("SURVEY_STARTED", fields = mapOf("configId" to configId))
                traces.putIfAbsent(surveyId, it)?.let { existing -> return@run existing }
            }
        }
    }.getOrNull()

    /** Diagnostics-only process scope: finalization never delays survey JSON reconciliation. */
    fun finalizeAsync(surveyId: String) {
        val trace = runCatching { traces.remove(surveyId) }.getOrNull() ?: return
        finalizationScope.launch {
            runCatching {
                trace.event("SURVEY_FINALIZED", fields = mapOf(
                    "surveyId" to surveyId,
                    "compressedFileName" to trace.finalizedFileName
                ))
                trace.finalizeTrace()
            }
        }
    }
}
