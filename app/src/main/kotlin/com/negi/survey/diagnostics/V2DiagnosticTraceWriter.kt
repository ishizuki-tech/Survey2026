package com.negi.survey.diagnostics

import com.negi.survey.net.V2DiagnosticCapture
import com.negi.survey.net.V2DiagnosticRouteStore
import com.negi.survey.net.V2DiagnosticStager
import com.negi.survey.net.V2DiagnosticState
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Separate explicit writer. Closing queues one seal behind every accepted event. */
internal class V2DiagnosticTraceWriter(
    store: V2DiagnosticRouteStore,
    private val capture: V2DiagnosticCapture,
    private val stager: V2DiagnosticStager = V2DiagnosticStager(store),
    private val beforeAppend: () -> Unit = {}
) : DiagnosticTraceWriter {
    private val open = store.create(capture).toPath()
    private val executor = Executors.newSingleThreadExecutor { action ->
        Thread(action, "V2DiagnosticTraceWriter").apply { isDaemon = true }
    }
    private var finalized: Future<File?>? = null
    private var writeFailed = false // Accessed only on the writer executor.
    override val finalFileName: String get() = capture.path().basename

    @Synchronized
    override fun append(line: String) {
        if (finalized != null) return
        require('\n' !in line && '\r' !in line)
        executor.execute {
            try {
                beforeAppend()
                require(Json.parseToJsonElement(line) is JsonObject)
                Files.newBufferedWriter(open, Charsets.UTF_8, CREATE, APPEND, NOFOLLOW_LINKS).use {
                    it.write(line)
                    it.write("\n")
                }
            } catch (_: Exception) {
                writeFailed = true
            }
        }
    }

    override fun finalizeToGzip(): File? {
        val result = synchronized(this) {
            finalized ?: executor.submit<File?> {
                if (writeFailed) null
                else (stager.sealAndStage(capture) as? V2DiagnosticState.Ready)?.file
            }.also {
                finalized = it
                executor.shutdown()
            }
        }
        return try {
            result.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: java.util.concurrent.ExecutionException) {
            // Leave the sealed/open evidence in place for explicit discovery and recovery.
            null
        }
    }
}

internal fun V2DiagnosticTraceWriter.event(
    identity: TraceIdentity,
    stream: String,
    name: String,
    answerTraceId: String?,
    questionId: String?,
    fields: Map<String, Any?>
) = append(v2TraceRecord(identity, stream, name, answerTraceId, questionId, fields).toString())

internal fun v2TraceRecord(
    identity: TraceIdentity,
    stream: String,
    name: String,
    answerTraceId: String?,
    questionId: String?,
    fields: Map<String, Any?>,
    timestamp: Instant = Instant.now(),
    eventId: String = TraceIdentity.newEventId(),
    production: Boolean = !DiagnosticContentPolicy.permitsRawRespondentContent
): JsonObject = JsonObject(buildMap {
    put("traceVersion", JsonPrimitive(1))
    put("eventId", JsonPrimitive(eventId))
    put("timestampUtc", JsonPrimitive(timestamp.toString()))
    put("stream", JsonPrimitive(stream))
    put("event", JsonPrimitive(name))
    put("surveyId", JsonPrimitive(identity.surveyId))
    put("shortSurveyId", JsonPrimitive(identity.shortSurveyId))
    put("deviceTag", JsonPrimitive(identity.deviceTag))
    put("processId", JsonPrimitive(identity.processId))
    identity.appVersion?.let { put("appVersion", JsonPrimitive(it)) }
    answerTraceId?.let { put("answerTraceId", JsonPrimitive(it)) }
    questionId?.let { put("questionId", JsonPrimitive(it)) }
    DiagnosticContentPolicy.sanitizeTraceFields(fields, production).forEach { (key, value) ->
        // ANSWER_COMMITTED supplies this field directly in the existing Survey trace.
        if (!production || key != "answer") putIfAbsent(key, traceValue(value))
    }
})

private fun traceValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Boolean -> JsonPrimitive(value)
    is Double -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
    is Float -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    // Android JSONObject writes the existing list-of-strings fields as a JSON string.
    is List<*> -> if (value.all { it is String }) JsonPrimitive(value.toString()) else JsonNull
    else -> JsonNull
}
