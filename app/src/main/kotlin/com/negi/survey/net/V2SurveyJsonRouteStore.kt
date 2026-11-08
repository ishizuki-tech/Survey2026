package com.negi.survey.net

import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.utils.V2ArtifactPaths
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val V2_PENDING_ROUTE = "route.json"
internal const val V2_ROUTE_TEMP = ".route.tmp"
internal const val V2_PAYLOAD_TEMP = ".payload.tmp"
internal const val V2_DELIVERED_ROUTE = "delivered.json"
private const val LOCK_FILENAME = ".lock"

internal enum class V2JsonStagingCheckpoint {
    PAYLOAD_WRITTEN_BEFORE_SYNC,
    ROUTE_WRITTEN_BEFORE_SYNC,
    ROUTE_PUBLISHED,
    PAYLOAD_PUBLISHED
}

/** File-backed, app-private route storage. All writers use the same namespace lock. */
internal class V2SurveyJsonRouteStore(
    internal val appFilesDir: File,
    private val checkpoint: (V2JsonStagingCheckpoint) -> Unit = {}
) {
    sealed interface ReadResult {
        data object Missing : ReadResult
        data class Invalid(val reason: String) : ReadResult
        data class Pending(val route: V2SurveyJsonRoute) : ReadResult
        data class Delivered(val route: V2SurveyJsonRoute) : ReadResult
    }

    data class RecoveryEntry(val directoryName: String, val route: ReadResult, val filenames: Set<String>)

    /** Read-only snapshot. Recovery revalidates candidates under the storage lock before acting. */
    fun scanRecovery(): List<RecoveryEntry> = synchronized(processLock) {
        val root = appFilesDir.toPath().toRealPath().resolve(V2_JSON_NAMESPACE)
        root.entryNames().filterNot { it == LOCK_FILENAME }.sorted().map { name ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val directory = root.resolve(name)
            val canonical = try {
                normalizeId(name) == name
            } catch (_: IllegalArgumentException) {
                false
            }
            try {
                if (!canonical || directory.attributesOrNull()?.isDirectory != true) {
                    RecoveryEntry(name, ReadResult.Invalid("Invalid V2 survey directory identity"), emptySet())
                } else {
                    val entry = Entry(directory, name)
                    RecoveryEntry(name, entry.read(), entry.names())
                }
            } catch (_: IOException) {
                RecoveryEntry(name, ReadResult.Invalid("Could not read V2 recovery evidence"), emptySet())
            }
        }
    }

    /** Returns the existing record only if it is identical to the proposed route. */
    fun createIfAbsent(route: V2SurveyJsonRoute): V2SurveyJsonRoute {
        route.validate()
        return withSurvey(route.surveyUuid) { createIfAbsent(route) }
    }

    fun read(surveyId: String): ReadResult = withSurvey(surveyId) { read() }

    /** Includes malformed records and orphan entries so reconciliation can report them as blocked. */
    fun listPending(): Map<String, ReadResult> = locked { root ->
        root.entryNames().filterNot { it == LOCK_FILENAME }.sorted().mapNotNull { name ->
            val canonical = try {
                normalizeId(name) == name
            } catch (_: IllegalArgumentException) {
                false
            }
            val result = if (canonical) Entry(root.resolve(name), name).read()
            else ReadResult.Invalid("Invalid V2 survey directory identity")
            if (result is ReadResult.Delivered) null else name to result
        }.toMap()
    }

    /**
     * Retires the pending route after the caller verifies delivery of this exact route and payload.
     * The delivered record remains as an identity reservation and prevents future rebinding.
     */
    fun removeAfterConfirmedDelivery(confirmedRoute: V2SurveyJsonRoute) {
        confirmedRoute.validate()
        withSurvey(confirmedRoute.surveyUuid) { removeAfterConfirmedDelivery(confirmedRoute) }
    }

    internal fun <T> withSurvey(surveyId: String, operation: Entry.() -> T): T {
        val surveyUuid = normalizeId(surveyId)
        return locked { root -> Entry(root.resolve(surveyUuid), surveyUuid).operation() }
    }

    private fun <T> locked(operation: (Path) -> T): T = synchronized(processLock) {
        val parent = appFilesDir.toPath().toRealPath()
        val root = parent.resolve(V2_JSON_NAMESPACE)
        root.ensureDirectory()
        // Also cover a retry after the namespace was created but its parent sync failed.
        syncDirectory(parent)
        FileChannel.open(root.resolve(LOCK_FILENAME), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use { operation(root) }
        }
    }

    internal inner class Entry(val directory: Path, val surveyUuid: String) {
        /** The caller holds the storage lock across validation, retirement, and any cleanup. */
        fun removeAfterConfirmedDelivery(confirmedRoute: V2SurveyJsonRoute) {
            confirmedRoute.validate(surveyUuid)
            when (val current = read()) {
                is ReadResult.Pending -> {
                    require(current.route == confirmedRoute) { "Confirmed route does not match pending route" }
                    publish(V2_PENDING_ROUTE, V2_DELIVERED_ROUTE)
                }
                is ReadResult.Delivered ->
                    require(current.route == confirmedRoute) { "Confirmed route does not match delivered route" }
                else -> throw IllegalStateException("No valid route to retire")
            }
            syncDirectory(directory)
        }

        fun names(): Set<String> = directory.entryNames()

        fun read(): ReadResult {
            val attributes = directory.attributesOrNull() ?: return ReadResult.Missing
            if (!attributes.isDirectory) return ReadResult.Invalid("Invalid V2 survey directory")
            val pending = directory.resolve(V2_PENDING_ROUTE)
            val delivered = directory.resolve(V2_DELIVERED_ROUTE)
            val pendingAttributes = pending.attributesOrNull()
            val deliveredAttributes = delivered.attributesOrNull()
            if (pendingAttributes == null && deliveredAttributes == null) return ReadResult.Missing
            if (pendingAttributes != null && deliveredAttributes != null) {
                return ReadResult.Invalid("Multiple V2 route records")
            }
            val file = if (pendingAttributes != null) pending else delivered
            if (!(pendingAttributes ?: checkNotNull(deliveredAttributes)).isRegularFile) {
                return ReadResult.Invalid("V2 route is not a regular file")
            }
            val bytes = Files.readAllBytes(file)
            val route = try {
                val record = Json.decodeFromString<StoredRoute>(bytes.toString(Charsets.UTF_8))
                record.route.validate(surveyUuid)
                require(record.sha256 == routeFingerprint(record.route))
                require(Json.encodeToString(record).toByteArray(Charsets.UTF_8).contentEquals(bytes))
                record.route
            } catch (_: IllegalArgumentException) {
                return ReadResult.Invalid("Unsupported or corrupt V2 route record")
            }
            return if (pendingAttributes != null) ReadResult.Pending(route) else ReadResult.Delivered(route)
        }

        fun createIfAbsent(
            route: V2SurveyJsonRoute,
            preparedPayload: Boolean = false
        ): V2SurveyJsonRoute {
            route.validate(surveyUuid)
            when (val current = read()) {
                is ReadResult.Pending -> {
                    require(current.route == route) { "Conflicting immutable V2 route" }
                    syncDirectory(directory)
                    return current.route
                }
                is ReadResult.Delivered -> {
                    require(current.route == route) { "Conflicting delivered V2 route" }
                    syncDirectory(directory)
                    return current.route
                }
                is ReadResult.Invalid -> throw IllegalStateException(current.reason)
                ReadResult.Missing -> Unit
            }
            val expectedFiles = if (preparedPayload) setOf(V2_PAYLOAD_TEMP) else emptySet()
            check(names() == expectedFiles) { "Unbound V2 files require recovery review" }
            val record = StoredRoute(route, routeFingerprint(route))
            writeSynced(
                V2_ROUTE_TEMP, Json.encodeToString(record).toByteArray(Charsets.UTF_8),
                V2JsonStagingCheckpoint.ROUTE_WRITTEN_BEFORE_SYNC
            )
            publish(V2_ROUTE_TEMP, V2_PENDING_ROUTE)
            checkpoint(V2JsonStagingCheckpoint.ROUTE_PUBLISHED)
            return route
        }

        fun writeSynced(name: String, bytes: ByteArray, beforeSync: V2JsonStagingCheckpoint) {
            directory.ensureDirectory()
            syncDirectory(directory.parent)
            val buffer = ByteBuffer.wrap(bytes)
            FileChannel.open(directory.resolve(name), CREATE_NEW, WRITE).use { file ->
                while (buffer.hasRemaining()) file.write(buffer)
                checkpoint(beforeSync)
                file.force(true)
            }
            syncDirectory(directory)
        }

        fun publish(source: String, target: String) {
            val destination = directory.resolve(target)
            // ATOMIC_MOVE may replace a destination on some providers. The namespace lock
            // serializes the absence check and rename for all writers to these private paths.
            check(destination.attributesOrNull() == null) { "V2 publication target already exists" }
            Files.move(directory.resolve(source), destination, ATOMIC_MOVE)
            syncDirectory(directory)
        }

        fun payloadPublished() = checkpoint(V2JsonStagingCheckpoint.PAYLOAD_PUBLISHED)
    }

    @Serializable
    private data class StoredRoute(val route: V2SurveyJsonRoute, val sha256: String)

    private companion object {
        val processLock = Any()

        fun routeFingerprint(route: V2SurveyJsonRoute): String =
            SurveyUploadIdempotency.payloadFingerprint(Json.encodeToString(route))

        // Phase 1 owns UUID validation and timestamp bounds. This placeholder never enters a route.
        fun normalizeId(surveyId: String): String =
            V2ArtifactPaths(surveyId, DeviceUploadTag("route")).surveyUuid
    }
}

internal fun Path.attributesOrNull(): BasicFileAttributes? = try {
    Files.readAttributes(this, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
} catch (_: NoSuchFileException) {
    null
}

private fun Path.ensureDirectory() {
    if (attributesOrNull() == null) {
        try {
            Files.createDirectory(this)
        } catch (_: FileAlreadyExistsException) {
            // Another process may have initialized the namespace before taking its lock.
        }
    }
    if (attributesOrNull()?.isDirectory != true) throw IOException("Invalid V2 directory")
}

private fun Path.entryNames(): Set<String> {
    val attributes = attributesOrNull() ?: return emptySet()
    if (!attributes.isDirectory) throw IOException("Invalid V2 directory")
    return Files.newDirectoryStream(this).use { entries ->
        entries.map { it.fileName.toString() }.toSet()
    }
}

internal fun syncDirectory(directory: Path) {
    FileChannel.open(directory, READ).use { it.force(true) }
}
