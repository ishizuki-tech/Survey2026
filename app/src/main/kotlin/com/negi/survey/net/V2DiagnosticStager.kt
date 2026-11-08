package com.negi.survey.net

import com.negi.survey.net.V2DiagnosticState.Reason
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.SequenceInputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater

internal enum class V2DiagnosticCheckpoint { SOURCE_SEALED, ARCHIVE_SYNCED, ROUTE_SYNCED, ROUTE_PUBLISHED, ARCHIVE_PUBLISHED }

internal class V2DiagnosticStager(
    private val store: V2DiagnosticRouteStore,
    private val checkpoint: (V2DiagnosticCheckpoint) -> Unit = {}
) {
    /** Only the writer calls this, after all accepted events have been written successfully. */
    fun sealAndStage(expected: V2DiagnosticCapture): V2DiagnosticState = store.locked(expected.traceId) {
        if (capture() != expected) return@locked blocked(Reason.CONFLICTING_IDENTITY)
        if (names() == setOf(DIAG_CAPTURE, DIAG_OPEN)) {
            val open = directory.resolve(DIAG_OPEN).attributesOrNull()
            if (open?.isRegularFile != true || open.size() == 0L) return@locked blocked(Reason.INCOMPLETE_PUBLICATION)
            syncFile(DIAG_OPEN)
            publish(DIAG_OPEN, DIAG_SEALED)
            checkpoint(V2DiagnosticCheckpoint.SOURCE_SEALED)
        }
        reconcile(expected)
    }

    /** An unsealed source is never proof that the queued writes completed before process death. */
    fun recover(id: V2TraceId): V2DiagnosticState = store.locked(id) {
        val capture = capture() ?: return@locked inspect()
        reconcile(capture)
    }

    private fun V2DiagnosticRouteStore.Entry.reconcile(capture: V2DiagnosticCapture): V2DiagnosticState {
        val current = inspect()
        if (current is V2DiagnosticState.Ready) return current
        if ((current as V2DiagnosticState.Blocked).reason != Reason.INCOMPLETE_PUBLICATION) return current
        val files = names()
        val source = directory.resolve(DIAG_SEALED)
        if (source.attributesOrNull()?.isRegularFile != true || DIAG_OPEN in files) return current
        syncDirectory(directory)
        val temporary = directory.resolve(DIAG_ARCHIVE_TEMP)
        if (files == setOf(DIAG_CAPTURE, DIAG_SEALED)) {
            Files.newInputStream(source, NOFOLLOW_LINKS).use { input ->
                GZIPOutputStream(Files.newOutputStream(temporary, CREATE_NEW, WRITE)).use { input.copyTo(it) }
            }
            syncFile(DIAG_ARCHIVE_TEMP)
            syncDirectory(directory)
            checkpoint(V2DiagnosticCheckpoint.ARCHIVE_SYNCED)
        }
        val routeName = when (names()) {
            setOf(DIAG_CAPTURE, DIAG_SEALED, DIAG_ARCHIVE_TEMP) -> null
            setOf(DIAG_CAPTURE, DIAG_SEALED, DIAG_ARCHIVE_TEMP, DIAG_ROUTE_TEMP) -> DIAG_ROUTE_TEMP
            setOf(DIAG_CAPTURE, DIAG_SEALED, DIAG_ARCHIVE_TEMP, DIAG_ROUTE) -> DIAG_ROUTE
            else -> return blocked(Reason.UNEXPECTED_FILES)
        }
        val fingerprint = diagnosticArchiveFingerprint(temporary) ?: return blocked(Reason.ARCHIVE_MISMATCH)
        if (!archiveMatchesSource(temporary, source)) return blocked(Reason.ARCHIVE_MISMATCH)
        val path = capture.path()
        val expected = V2DiagnosticRoute(1, capture, path.basename, path.relativePath, fingerprint.first, fingerprint.second)

        if (routeName == null) {
            writeRecord(DIAG_ROUTE_TEMP, V2DiagnosticRoute.serializer(), expected)
            checkpoint(V2DiagnosticCheckpoint.ROUTE_SYNCED)
        } else {
            val stored = route(routeName, capture) ?: return blocked(Reason.INVALID_ROUTE)
            if (stored != expected) return blocked(Reason.ARCHIVE_MISMATCH)
        }
        syncFile(DIAG_ARCHIVE_TEMP)
        if (routeName != DIAG_ROUTE) {
            syncFile(DIAG_ROUTE_TEMP)
            publish(DIAG_ROUTE_TEMP, DIAG_ROUTE)
            checkpoint(V2DiagnosticCheckpoint.ROUTE_PUBLISHED)
        }
        publish(DIAG_ARCHIVE_TEMP, expected.basename)
        checkpoint(V2DiagnosticCheckpoint.ARCHIVE_PUBLISHED)
        return inspect()
    }
}

internal fun diagnosticArchiveFingerprint(file: Path): Pair<Long, String>? {
    if (file.attributesOrNull()?.isRegularFile != true) return null
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size == -1) break
            count += size
            digest.update(buffer, 0, size)
        }
    }
    if (count == 0L) return null
    return count to digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/** Validate our single-member GZIPOutputStream format, including trailer and exact source bytes. */
internal fun archiveMatchesSource(archive: Path, source: Path): Boolean {
    if (archive.attributesOrNull()?.isRegularFile != true || source.attributesOrNull()?.isRegularFile != true) return false
    val inflater = Inflater(true)
    return try {
        DataInputStream(Files.newInputStream(archive, NOFOLLOW_LINKS)).use { compressed ->
            if (compressed.readInt() != 0x1f8b0800) return false
            compressed.readFully(ByteArray(6))
            DataInputStream(Files.newInputStream(source, NOFOLLOW_LINKS)).use { original ->
                val input = ByteArray(8192)
                val decoded = ByteArray(input.size)
                val expected = ByteArray(input.size)
                val crc = CRC32()
                var lastInputSize = 0
                var size = 0L
                while (!inflater.finished()) {
                    if (inflater.needsInput()) {
                        lastInputSize = compressed.read(input)
                        if (lastInputSize <= 0) return false
                        inflater.setInput(input, 0, lastInputSize)
                    }
                    val count = inflater.inflate(decoded)
                    if (count == 0 && !inflater.needsInput() && !inflater.finished()) return false
                    original.readFully(expected, 0, count)
                    for (index in 0 until count) if (decoded[index] != expected[index]) return false
                    crc.update(decoded, 0, count)
                    size += count
                }
                val remaining = inflater.remaining
                val bufferedTail = ByteArrayInputStream(input, lastInputSize - remaining, remaining)
                val tail = DataInputStream(SequenceInputStream(bufferedTail, compressed))
                size > 0 && original.read() == -1 && tail.readLeUInt() == crc.value &&
                    tail.readLeUInt() == (size and 0xffffffffL) && tail.read() == -1
            }
        }
    } catch (_: DataFormatException) {
        false
    } catch (_: EOFException) {
        false
    } finally {
        inflater.end()
    }
}

private fun DataInputStream.readLeUInt(): Long = Integer.reverseBytes(readInt()).toLong() and 0xffffffffL
