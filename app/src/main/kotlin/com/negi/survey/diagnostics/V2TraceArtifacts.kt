package com.negi.survey.diagnostics

import android.content.Context
import com.negi.survey.net.V2ArtifactDelivery
import com.negi.survey.net.V2ArtifactStore
import com.negi.survey.net.V2ArtifactStore.Kind
import com.negi.survey.net.V2ArtifactStore.Profile
import com.negi.survey.utils.V2ArtifactPaths
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Persists trace identity before collecting events so interrupted streams can be sealed later. */
internal object V2TraceArtifacts {
    private val processId = V2ArtifactStore.newId()
    private const val SEALED = "sealed"

    @Serializable
    private data class Capture(
        val id: String,
        val surveyId: String,
        val profile: Profile,
        val kind: Kind,
        val path: String,
        val redacted: Boolean = false,
    )

    private fun root(context: Context) = File(context.noBackupFilesDir, "v2_open_traces")

    fun writer(
        context: Context,
        surveyId: String,
        kind: Kind,
        question: String? = null,
        operation: String? = null,
        metadataOnly: Boolean = false,
    ): TraceWriter {
        val store = V2ArtifactStore(context)
        val deviceTag = store.frozenDeviceTag(context, surveyId, persist = false)
        val paths = V2ArtifactPaths(surveyId, deviceTag)
        val id = V2ArtifactStore.newId()
        val path = when (kind) {
            Kind.SURVEY_TRACE -> paths.surveyTrace(id)
            Kind.WHISPER_TRACE -> paths.whisperTrace(id)
            Kind.SLM -> paths.slmTrace(checkNotNull(question), checkNotNull(operation), id)
            else -> error("Invalid trace kind.")
        }
        val redacted = if (kind == Kind.SLM) metadataOnly else !DiagnosticContentPolicy.permitsRawRespondentContent
        val capture = Capture(id, paths.surveyUuid, V2ArtifactStore.profile, kind, path.relativePath, redacted)
        val directory = File(root(context), "$processId/$id")
        return TraceWriter(File(directory, "events.open"), path.basename, prepare = {
            store.frozenDeviceTag(context, surveyId)
            java.nio.file.Files.createDirectories(directory.toPath())
            val encoded = Json.encodeToString(Capture.serializer(), capture)
            V2ArtifactStore.writeAtomically(File(directory, "capture"), "${V2ArtifactStore.stableId(encoded)}\n$encoded")
            V2ArtifactStore.syncDirectory(directory.parentFile!!)
            V2ArtifactStore.syncDirectory(root(context))
            V2ArtifactStore.syncDirectory(context.noBackupFilesDir)
        }, onFailure = { error ->
            DiagnosticContentPolicy.logWarning("V2TraceArtifacts", "Trace write or publication failed", error)
        }) { open -> seal(context, open, capture) }
    }

    @Synchronized
    fun recover(context: Context) {
        root(context).listFiles().orEmpty().filter(File::isDirectory).forEach { process ->
            process.listFiles().orEmpty().filter(File::isDirectory).forEach directoryLoop@ { directory ->
                runCatching {
                    if (process.name == processId) {
                        val marker = File(directory, SEALED)
                        if (!marker.isFile || marker.readText() != directory.name) return@runCatching
                    }
                    val stored = File(directory, "capture").readText()
                    val encoded = stored.substringAfter('\n', "")
                    check(stored.substringBefore('\n') == V2ArtifactStore.stableId(encoded))
                    val capture = Json.decodeFromString(Capture.serializer(), encoded)
                    check(capture.id == directory.name)
                    val events = File(directory, "events.open")
                    if (events.isFile && events.length() > 0) seal(context, events, capture)
                }.onFailure { DiagnosticContentPolicy.logWarning("V2TraceArtifacts", "Interrupted trace retained", it) }
            }
            if (process.name != processId) process.delete()
        }
    }

    @Synchronized
    private fun seal(context: Context, open: File, capture: Capture): File? {
        val sealed = File(open.parentFile, SEALED)
        if (!sealed.isFile) V2ArtifactStore.writeAtomically(sealed, capture.id)
        val store = V2ArtifactStore(context)
        val path = V2ArtifactPaths.ArtifactPath(capture.path.substringAfterLast('/'), capture.path)
        val entry = store.stage(
            capture.id, capture.kind, capture.surveyId, capture.profile, path,
            diagnosticsRedacted = capture.redacted,
            rebuildUnsealed = true,
        ) { output ->
            writeGzip(output) { gzip ->
                open.bufferedReader().useLines { lines ->
                    // A process can die during append. Only complete NDJSON events are sealed.
                    var written = false
                    for (line in lines) {
                        val valid = runCatching {
                            Json.parseToJsonElement(line) is kotlinx.serialization.json.JsonObject
                        }.getOrDefault(false)
                        if (valid) {
                            gzip.write((line + "\n").toByteArray(Charsets.UTF_8))
                            written = true
                        }
                    }
                    check(written) { "Trace has no complete events." }
                }
            }
        }
        check(entry.hasReceipt() || entry.matchesPayload()) { "Staged trace is missing or corrupt." }
        if (open.delete()) {
            sealed.delete()
            File(open.parentFile, "capture").delete()
            open.parentFile?.delete()
        }
        V2ArtifactDelivery.config(context)?.let { V2ArtifactDelivery.enqueue(context, entry, it) }
        return entry.payload
    }

    fun writeGzip(output: OutputStream, write: (GZIPOutputStream) -> Unit) {
        GZIPOutputStream(object : FilterOutputStream(output) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)
            override fun close() = flush()
        }).use(write)
    }
}
