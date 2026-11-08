package com.negi.survey.net

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal interface V2VoiceDeliveryLedger {
    enum class Evidence { MISSING, CONFIRMED, CONFLICT }
    fun inspect(route: V2VoiceRoute): Evidence
    fun recordConfirmed(route: V2VoiceRoute)
}

/** Immutable delivery evidence separate from both survey JSON and V1 voice bookkeeping. */
internal class V2VoiceLedger(private val appFilesDir: File) : V2VoiceDeliveryLedger {
    @Serializable
    private data class Record(val formatVersion: Int, val route: V2VoiceRoute)

    override fun inspect(route: V2VoiceRoute): V2VoiceDeliveryLedger.Evidence = synchronized(lock) {
        route.validate()
        val file = root().resolve("${route.recordingId.value}.json")
        val attributes = file.attributesOrNull() ?: return@synchronized V2VoiceDeliveryLedger.Evidence.MISSING
        if (!attributes.isRegularFile || !Files.readAllBytes(file).contentEquals(recordBytes(route))) {
            return@synchronized V2VoiceDeliveryLedger.Evidence.CONFLICT
        }
        syncDirectory(file.parent.parent)
        syncDirectory(file.parent)
        V2VoiceDeliveryLedger.Evidence.CONFIRMED
    }

    /** Caller holds this recording's execution lease. */
    override fun recordConfirmed(route: V2VoiceRoute) = synchronized(lock) {
        when (inspect(route)) {
            V2VoiceDeliveryLedger.Evidence.CONFIRMED -> return@synchronized
            V2VoiceDeliveryLedger.Evidence.CONFLICT -> throw V2VoiceEvidenceConflict()
            V2VoiceDeliveryLedger.Evidence.MISSING -> Unit
        }
        val directory = root()
        ensureVoiceDirectory(directory)
        syncDirectory(directory.parent)
        val temporary = Files.createTempFile(directory, ".confirmed-", ".tmp")
        try {
            temporary.toFile().outputStream().use {
                it.write(recordBytes(route))
                it.fd.sync()
            }
            Files.move(temporary, directory.resolve("${route.recordingId.value}.json"), ATOMIC_MOVE)
            syncDirectory(directory)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun recordBytes(route: V2VoiceRoute): ByteArray =
        Json.encodeToString(Record(1, route)).toByteArray(Charsets.UTF_8)

    private fun root() = appFilesDir.toPath().toRealPath().resolve(NAMESPACE).also {
        val attrs = it.attributesOrNull()
        if (attrs != null && !attrs.isDirectory) throw IOException("Invalid V2 WAV ledger directory")
    }

    companion object {
        const val NAMESPACE = "v2_voice_ledger"
        private val lock = Any()
    }
}

internal class V2VoiceEvidenceConflict : IllegalStateException("Conflicting V2 WAV delivery evidence")

internal interface V2VoiceExecution {
    suspend fun <T> own(recordingId: V2RecordingId, action: suspend () -> T): T
}

/** Kernel lease serializes attempts across workers and is automatically released on process death. */
internal class V2VoiceFileExecution(private val appFilesDir: File) : V2VoiceExecution {
    override suspend fun <T> own(recordingId: V2RecordingId, action: suspend () -> T): T {
        val local = processLeases[(recordingId.value.hashCode() and Int.MAX_VALUE) % processLeases.size]
        if (!local.tryLock()) throw IOException("Recording execution lease is busy")
        try {
            val directory = appFilesDir.toPath().toRealPath().resolve("v2_voice_execution")
            ensureVoiceDirectory(directory)
            return FileChannel.open(directory.resolve(recordingId.value), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                val lease = channel.tryLock() ?: throw IOException("Recording execution lease is busy")
                lease.use { action() }
            }
        } finally {
            local.unlock()
        }
    }

    private companion object {
        // Avoid opening another channel to the same lock inode in this JVM.
        val processLeases = Array(64) { Mutex() }
    }
}

private fun ensureVoiceDirectory(directory: java.nio.file.Path) {
    if (directory.attributesOrNull() == null) {
        try {
            Files.createDirectory(directory)
        } catch (_: FileAlreadyExistsException) {
            // Another recording may have initialized the namespace.
        }
    }
    if (directory.attributesOrNull()?.isDirectory != true) throw IOException("Invalid V2 WAV metadata directory")
}
