package com.negi.survey.net

import com.negi.survey.vm.SurveyViewModel
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val V2_VOICE_ROUTE = "route.json"
internal const val V2_VOICE_DELIVERED = "delivered.json"
internal const val V2_VOICE_ROUTE_TEMP = ".route.tmp"
internal const val V2_VOICE_PAYLOAD_TEMP = ".voice.tmp"

internal sealed interface V2VoiceStageResult {
    data class Ready(val route: V2VoiceRoute, val file: File) : V2VoiceStageResult {
        fun audioRef(finalizedAtMillis: Long): SurveyViewModel.AudioRef = SurveyViewModel.AudioRef(
            route.surveyUuid, route.questionId, route.basename, finalizedAtMillis, route.wavSize, route.wavSha256
        )
    }
    data class Blocked(val reason: String) : V2VoiceStageResult
}

internal sealed interface V2VoiceDeliveryState {
    data class Valid(
        val route: V2VoiceRoute,
        val delivered: Boolean,
        val file: Path?,
        val confirmed: Boolean
    ) : V2VoiceDeliveryState
    data class Blocked(val reason: String) : V2VoiceDeliveryState
}

/** App-private recording storage. The route is the authoritative replacement for a WAV sidecar. */
internal class V2VoiceRouteStore(private val appFilesDir: File) {
    /** Discovery never publishes or deletes incomplete evidence. */
    fun discover(): Map<String, V2VoiceStageResult> = synchronized(lock) {
        val root = root()
        if (root.attributesOrNull() == null) return@synchronized emptyMap()
        root.requireDirectory()
        Files.newDirectoryStream(root).use { entries ->
            entries.filter { it.fileName.toString() != LOCK_NAME }.associate { directory ->
                val name = directory.fileName.toString()
                val result = try {
                    Entry(directory, V2RecordingId(name)).inspect()
                } catch (_: IllegalArgumentException) {
                    V2VoiceStageResult.Blocked("Invalid V2 recording directory")
                } catch (_: IOException) {
                    V2VoiceStageResult.Blocked("Unreadable V2 recording evidence")
                }
                name to result
            }
        }
    }

    fun recordingNames(): List<String> = synchronized(lock) {
        val root = root()
        if (root.attributesOrNull() == null) return@synchronized emptyList()
        Files.newDirectoryStream(root).use { entries ->
            entries.map { it.fileName.toString() }.filterNot { it == LOCK_NAME }.sorted()
        }
    }

    fun inspect(recordingId: V2RecordingId): V2VoiceStageResult = synchronized(lock) {
        Entry(root().resolve(recordingId.value), recordingId).inspect()
    }

    /** Resolve by the generated ID suffix, then verify the entire basename against durable evidence. */
    fun verifyReference(
        surveyUuid: String,
        questionId: String,
        basename: String,
        destination: V2SurveyJsonDestination,
        byteSize: Long? = null,
        checksum: String? = null
    ): Boolean {
        if (!basename.endsWith(".wav")) return false
        val recordingId = try {
            V2RecordingId(basename.removeSuffix(".wav").substringAfterLast('_'))
        } catch (_: IllegalArgumentException) {
            return false
        }
        val state = synchronized(lock) {
            Entry(root().resolve(recordingId.value), recordingId).deliveryState(V2VoiceLedger(appFilesDir))
        } as? V2VoiceDeliveryState.Valid ?: return false
        val route = state.route
        return route.surveyUuid == surveyUuid && route.questionId == questionId &&
            route.basename == basename && route.destination == destination &&
            (byteSize == null || byteSize == route.wavSize) &&
            (checksum == null || checksum == route.wavSha256)
    }

    internal fun <T> withRecording(recordingId: V2RecordingId, operation: Entry.() -> T): T = synchronized(lock) {
        val root = root()
        if (root.attributesOrNull() == null) {
            try {
                Files.createDirectory(root)
            } catch (_: FileAlreadyExistsException) {
                // Another process can initialize the namespace before taking its lock.
            }
        }
        root.requireDirectory()
        syncDirectory(root.parent)
        FileChannel.open(root.resolve(LOCK_NAME), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use { Entry(root.resolve(recordingId.value), recordingId).operation() }
        }
    }

    private fun root(): Path = appFilesDir.toPath().toRealPath().resolve(V2_VOICE_NAMESPACE).also {
        if (it.attributesOrNull() != null) it.requireDirectory()
    }

    internal class Entry(val directory: Path, private val recordingId: V2RecordingId) {
        fun names(): Set<String> {
            if (directory.attributesOrNull() == null) return emptySet()
            directory.requireDirectory()
            return Files.newDirectoryStream(directory).use { entries ->
                entries.map { it.fileName.toString() }.toSet()
            }
        }

        /** Both retirement and reference validation use the same exact route, ledger and WAV checks. */
        fun deliveryState(ledger: V2VoiceDeliveryLedger): V2VoiceDeliveryState {
            if (directory.attributesOrNull()?.isDirectory != true) {
                return V2VoiceDeliveryState.Blocked("Missing or invalid V2 WAV directory")
            }
            val filenames = names()
            val delivered = V2_VOICE_DELIVERED in filenames
            val routeName = if (delivered) V2_VOICE_DELIVERED else V2_VOICE_ROUTE
            val route = readRoute(routeName)
                ?: return V2VoiceDeliveryState.Blocked("Missing or corrupt V2 WAV route")
            val evidence = ledger.inspect(route)
            val confirmed = evidence == V2VoiceDeliveryLedger.Evidence.CONFIRMED
            if (evidence == V2VoiceDeliveryLedger.Evidence.CONFLICT || (delivered && !confirmed)) {
                return V2VoiceDeliveryState.Blocked("Missing or conflicting V2 WAV delivery evidence")
            }
            val file = directory.resolve(route.basename)
            if (delivered && filenames == setOf(routeName)) {
                syncDirectory(directory)
                return V2VoiceDeliveryState.Valid(route, true, null, true)
            }
            if (filenames != setOf(routeName, route.basename) || wavFingerprint(file)?.matches(route) != true) {
                return V2VoiceDeliveryState.Blocked("V2 WAV is missing, incomplete, unexpected, or changed")
            }
            syncDirectory(directory)
            return V2VoiceDeliveryState.Valid(route, delivered, file, confirmed)
        }

        fun interruptedRoute(): V2VoiceRoute? {
            if (directory.attributesOrNull()?.isDirectory != true) return null
            val files = names()
            val routeName = if (V2_VOICE_ROUTE in files) V2_VOICE_ROUTE else V2_VOICE_ROUTE_TEMP
            if (files != setOf(routeName, V2_VOICE_PAYLOAD_TEMP)) return null
            return readRoute(routeName)
        }

        fun inspect(): V2VoiceStageResult {
            val route = readRoute(V2_VOICE_ROUTE)
                ?: return V2VoiceStageResult.Blocked("Missing or corrupt finalized V2 WAV route")
            val file = directory.resolve(route.basename)
            if (names() != setOf(V2_VOICE_ROUTE, route.basename) || wavFingerprint(file)?.matches(route) != true) {
                return V2VoiceStageResult.Blocked("V2 WAV is missing, incomplete, unexpected, or changed")
            }
            // A rename may be visible after a failed sync. Prove its durability before returning Ready.
            syncDirectory(directory.parent.parent)
            syncDirectory(directory.parent)
            syncDirectory(directory)
            return V2VoiceStageResult.Ready(route, file.toFile())
        }

        fun readRoute(name: String): V2VoiceRoute? {
            if (directory.attributesOrNull()?.isDirectory != true) return null
            val file = directory.resolve(name)
            if (file.attributesOrNull()?.isRegularFile != true) return null
            val bytes = Files.readAllBytes(file)
            return try {
                val record = Json.decodeFromString<StoredRoute>(bytes.toString(Charsets.UTF_8))
                record.route.validate(recordingId)
                record.route.takeIf { record == stored(it) && Json.encodeToString(record).toByteArray().contentEquals(bytes) }
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        fun copyTemporary(source: Path, beforeSync: () -> Unit) {
            if (directory.attributesOrNull() == null) Files.createDirectory(directory)
            directory.requireDirectory()
            syncDirectory(directory.parent)
            val file = directory.resolve(V2_VOICE_PAYLOAD_TEMP)
            FileChannel.open(file, CREATE_NEW, WRITE).use { channel ->
                Files.newInputStream(source, NOFOLLOW_LINKS).use { input ->
                    input.copyTo(Channels.newOutputStream(channel))
                }
                beforeSync()
                channel.force(true)
            }
            syncDirectory(directory)
        }

        fun writeRoute(route: V2VoiceRoute, beforeSync: () -> Unit) {
            route.validate(recordingId)
            val bytes = ByteBuffer.wrap(Json.encodeToString(stored(route)).toByteArray())
            FileChannel.open(directory.resolve(V2_VOICE_ROUTE_TEMP), CREATE_NEW, WRITE).use { file ->
                while (bytes.hasRemaining()) file.write(bytes)
                beforeSync()
                file.force(true)
            }
            syncDirectory(directory)
        }

        fun publish(source: String, target: String) {
            val destination = directory.resolve(target)
            check(destination.attributesOrNull() == null) { "V2 WAV publication target already exists" }
            Files.move(directory.resolve(source), destination, ATOMIC_MOVE)
            syncDirectory(directory)
        }
    }

    @Serializable
    private data class StoredRoute(val route: V2VoiceRoute, val sha256: String)

    private companion object {
        const val LOCK_NAME = ".lock"
        val lock = Any()
        fun stored(route: V2VoiceRoute) =
            StoredRoute(route, route.identitySha256())
    }
}

private fun Path.requireDirectory() {
    if (attributesOrNull()?.isDirectory != true) throw IOException("Invalid V2 WAV directory")
}
