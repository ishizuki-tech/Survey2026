package com.negi.survey.net

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val DIAG_CAPTURE = "capture.json"
internal const val DIAG_OPEN = ".events.open"
internal const val DIAG_SEALED = ".events.sealed"
internal const val DIAG_ARCHIVE_TEMP = ".archive.tmp"
internal const val DIAG_ROUTE = "route.json"
internal const val DIAG_ROUTE_TEMP = ".route.tmp"

internal sealed interface V2DiagnosticState {
    data class Ready(val route: V2DiagnosticRoute, val file: File) : V2DiagnosticState
    data class Blocked(val reason: Reason) : V2DiagnosticState
    enum class Reason {
        INCOMPLETE_PUBLICATION, MISSING_ARCHIVE, ARCHIVE_MISMATCH, INVALID_ROUTE,
        ORPHAN, UNEXPECTED_FILES, CONFLICTING_IDENTITY, UNREADABLE
    }
}

/** Dedicated local namespace. Discovery has no publishing, scheduling, or deletion side effects. */
internal class V2DiagnosticRouteStore(private val appFilesDir: File) {
    fun create(capture: V2DiagnosticCapture): File = locked(capture.traceId) {
        capture.path()
        check(directory.attributesOrNull() == null) { "V2 diagnostic trace ID already exists" }
        Files.createDirectory(directory)
        syncDirectory(directory.parent)
        writeRecord(".capture.tmp", V2DiagnosticCapture.serializer(), capture)
        publish(".capture.tmp", DIAG_CAPTURE)
        directory.resolve(DIAG_OPEN).toFile()
    }

    fun discover(): Map<String, V2DiagnosticState> = synchronized(processLock) {
        val root = root()
        if (root.attributesOrNull() == null) return@synchronized emptyMap()
        requireDirectory(root)
        Files.newDirectoryStream(root).use { entries ->
            entries.map { it.fileName.toString() }.filterNot { it == LOCK_NAME }.sorted().associateWith { name ->
                try {
                    Entry(root.resolve(name), V2TraceId(name)).inspect()
                } catch (_: IllegalArgumentException) {
                    V2DiagnosticState.Blocked(V2DiagnosticState.Reason.ORPHAN)
                } catch (_: IOException) {
                    V2DiagnosticState.Blocked(V2DiagnosticState.Reason.UNREADABLE)
                }
            }
        }
    }

    internal fun <T> locked(id: V2TraceId, action: Entry.() -> T): T = synchronized(processLock) {
        val root = root()
        if (root.attributesOrNull() == null) {
            try {
                Files.createDirectory(root)
            } catch (_: FileAlreadyExistsException) {
                // Another process can create the namespace before taking its lock.
            }
        }
        requireDirectory(root)
        syncDirectory(root.parent)
        FileChannel.open(root.resolve(LOCK_NAME), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use { Entry(root.resolve(id.value), id).action() }
        }
    }

    private fun root(): Path = appFilesDir.toPath().toRealPath().resolve(V2_DIAGNOSTIC_NAMESPACE)

    internal class Entry(val directory: Path, private val id: V2TraceId) {
        fun names(): Set<String> {
            if (directory.attributesOrNull() == null) return emptySet()
            requireDirectory(directory)
            return Files.newDirectoryStream(directory).use { entries -> entries.map { it.fileName.toString() }.toSet() }
        }

        fun capture(): V2DiagnosticCapture? = readCapture()?.takeIf { it.traceId == id }

        private fun readCapture(): V2DiagnosticCapture? {
            val capture = readRecord(DIAG_CAPTURE, V2DiagnosticCapture.serializer()) ?: return null
            return try {
                capture.path()
                capture
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        fun route(name: String, capture: V2DiagnosticCapture): V2DiagnosticRoute? {
            val route = readRecord(name, V2DiagnosticRoute.serializer()) ?: return null
            return try {
                route.validate(capture)
                route
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        fun inspect(): V2DiagnosticState {
            val files = names()
            val capture = readCapture() ?: return blocked(
                if (DIAG_CAPTURE in files) V2DiagnosticState.Reason.INVALID_ROUTE
                else if (files == setOf(".capture.tmp")) V2DiagnosticState.Reason.INCOMPLETE_PUBLICATION
                else V2DiagnosticState.Reason.ORPHAN
            )
            if (capture.traceId != id) return blocked(V2DiagnosticState.Reason.CONFLICTING_IDENTITY)
            val basename = capture.path().basename
            if (DIAG_ROUTE !in files) {
                val allowed = setOf(DIAG_CAPTURE, DIAG_OPEN, DIAG_SEALED, DIAG_ARCHIVE_TEMP, DIAG_ROUTE_TEMP)
                return blocked(if (files.any { it !in allowed }) V2DiagnosticState.Reason.UNEXPECTED_FILES
                    else V2DiagnosticState.Reason.INCOMPLETE_PUBLICATION)
            }
            val route = readRecord(DIAG_ROUTE, V2DiagnosticRoute.serializer())
                ?: return blocked(V2DiagnosticState.Reason.INVALID_ROUTE)
            if (route.capture != capture) return blocked(V2DiagnosticState.Reason.CONFLICTING_IDENTITY)
            try {
                route.validate(capture)
            } catch (_: IllegalArgumentException) {
                return blocked(V2DiagnosticState.Reason.INVALID_ROUTE)
            }
            val expected = setOf(DIAG_CAPTURE, DIAG_SEALED, DIAG_ROUTE, basename)
            if (files == expected - basename) return blocked(V2DiagnosticState.Reason.MISSING_ARCHIVE)
            if (files == expected - basename + DIAG_ARCHIVE_TEMP) {
                return blocked(V2DiagnosticState.Reason.INCOMPLETE_PUBLICATION)
            }
            if (files != expected) return blocked(V2DiagnosticState.Reason.UNEXPECTED_FILES)
            val file = directory.resolve(basename)
            val fingerprint = diagnosticArchiveFingerprint(file)
            if (fingerprint == null || fingerprint.first != route.archiveSize || fingerprint.second != route.archiveSha256 ||
                !archiveMatchesSource(file, directory.resolve(DIAG_SEALED))
            ) {
                return blocked(V2DiagnosticState.Reason.ARCHIVE_MISMATCH)
            }
            syncDirectory(directory)
            return V2DiagnosticState.Ready(route, file.toFile())
        }

        fun publish(source: String, target: String) {
            val destination = directory.resolve(target)
            check(destination.attributesOrNull() == null) { "V2 diagnostic target already exists" }
            Files.move(directory.resolve(source), destination, ATOMIC_MOVE)
            syncDirectory(directory)
        }

        fun syncFile(name: String) {
            FileChannel.open(directory.resolve(name), WRITE, NOFOLLOW_LINKS).use { it.force(true) }
        }

        fun <T> writeRecord(name: String, serializer: KSerializer<T>, record: T) {
            val encoded = Json.encodeToString(serializer, record)
            Files.newOutputStream(directory.resolve(name), CREATE_NEW, WRITE).use {
                it.write(Json.encodeToString(envelope(encoded)).toByteArray(Charsets.UTF_8))
            }
            syncFile(name)
            syncDirectory(directory)
        }

        private fun <T> readRecord(name: String, serializer: KSerializer<T>): T? {
            if (directory.attributesOrNull()?.isDirectory != true) return null
            val file = directory.resolve(name)
            if (file.attributesOrNull()?.isRegularFile != true) return null
            val bytes = Files.readAllBytes(file)
            return try {
                val stored = Json.decodeFromString<SealedRecord>(bytes.toString(Charsets.UTF_8))
                val record = Json.decodeFromString(serializer, stored.data)
                val expected = envelope(Json.encodeToString(serializer, record))
                record.takeIf { stored == expected && Json.encodeToString(expected).toByteArray().contentEquals(bytes) }
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    @Serializable
    private data class SealedRecord(val data: String, val sha256: String)

    private companion object {
        const val LOCK_NAME = ".lock"
        val processLock = Any()
        fun envelope(data: String) = SealedRecord(data, SurveyUploadIdempotency.payloadFingerprint(data))
        fun requireDirectory(path: Path) {
            if (path.attributesOrNull()?.isDirectory != true) throw IOException("Invalid V2 diagnostic directory")
        }
    }
}

internal fun blocked(reason: V2DiagnosticState.Reason): V2DiagnosticState = V2DiagnosticState.Blocked(reason)
