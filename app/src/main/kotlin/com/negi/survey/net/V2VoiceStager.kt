package com.negi.survey.net

import com.negi.survey.BuildConfig
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.utils.V2ArtifactPaths
import java.io.File
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

internal enum class V2VoiceStagingCheckpoint {
    PAYLOAD_WRITTEN_BEFORE_SYNC, ROUTE_WRITTEN_BEFORE_SYNC, ROUTE_PUBLISHED, PAYLOAD_PUBLISHED
}

/** Explicit staging for an already-finalized recording. The caller retains its RecordingID. */
internal class V2VoiceStager(
    private val store: V2VoiceRouteStore,
    private val localOrDebugBuild: Boolean = BuildConfig.DEBUG || BuildConfig.GH_UPLOAD_MODE == "local",
    private val checkpoint: (V2VoiceStagingCheckpoint) -> Unit = {}
) {
    fun stage(
        source: File,
        surveyId: String,
        questionId: String,
        recordingId: V2RecordingId,
        deviceTag: DeviceUploadTag,
        destination: V2SurveyJsonDestination
    ): V2VoiceStageResult {
        val surveyUuid = V2ArtifactPaths(surveyId, DeviceUploadTag("route")).surveyUuid
        require(destination.pathPrefix.isEmpty()) { "V2 WAV destinations must use the repository root" }
        if (!allowsV2Destination(destination, localOrDebugBuild)) {
            return V2VoiceStageResult.Blocked("V2 WAV destination is prohibited in this build")
        }

        return store.withRecording(recordingId) {
            val sourcePath = source.toPath()
            val fingerprint = wavFingerprint(sourcePath)
                ?: return@withRecording V2VoiceStageResult.Blocked("Source is not a complete WAV")
            val files = names()
            if (files.isNotEmpty()) {
                val existing = readRoute(V2_VOICE_ROUTE) ?: readRoute(V2_VOICE_ROUTE_TEMP)
                    ?: return@withRecording V2VoiceStageResult.Blocked("Missing or corrupt V2 WAV route")
                if (existing.surveyUuid != surveyUuid || existing.questionId != questionId ||
                    existing.destination != destination || !fingerprint.matches(existing)
                ) return@withRecording V2VoiceStageResult.Blocked("RecordingID conflicts with its immutable route or bytes")

                // The first persisted route owns the device tag and basename, including after restart.
                return@withRecording recoverEntry()
            }

            val route = V2VoiceRoute.create(surveyUuid, questionId, recordingId, deviceTag, destination, fingerprint)
            copyTemporary(sourcePath) { checkpoint(V2VoiceStagingCheckpoint.PAYLOAD_WRITTEN_BEFORE_SYNC) }
            if (wavFingerprint(directory.resolve(V2_VOICE_PAYLOAD_TEMP)) != fingerprint) {
                return@withRecording V2VoiceStageResult.Blocked("Source WAV changed during staging")
            }
            writeRoute(route) { checkpoint(V2VoiceStagingCheckpoint.ROUTE_WRITTEN_BEFORE_SYNC) }
            recoverEntry()
        }
    }

    /** Explicit restart/retry API. Uses only the bytes and route already stored for this recording. */
    fun recover(recordingId: V2RecordingId): V2VoiceStageResult =
        store.withRecording(recordingId) { recoverEntry() }

    private fun V2VoiceRouteStore.Entry.recoverEntry(): V2VoiceStageResult {
        val files = names()
        val routeName = if (V2_VOICE_ROUTE in files) V2_VOICE_ROUTE else V2_VOICE_ROUTE_TEMP
        val route = readRoute(routeName)
            ?: return V2VoiceStageResult.Blocked("Missing or corrupt V2 WAV route")
        if (!allowsV2Destination(route.destination, localOrDebugBuild)) {
            return V2VoiceStageResult.Blocked("V2 WAV destination is prohibited in this build")
        }
        if (files == setOf(V2_VOICE_ROUTE, route.basename)) return inspect()

        val temporary = directory.resolve(V2_VOICE_PAYLOAD_TEMP)
        if (files != setOf(routeName, V2_VOICE_PAYLOAD_TEMP) || wavFingerprint(temporary)?.matches(route) != true) {
            return V2VoiceStageResult.Blocked("V2 WAV evidence is incomplete, unexpected, or changed")
        }
        syncFile(temporary)
        if (routeName == V2_VOICE_ROUTE_TEMP) {
            syncFile(directory.resolve(routeName))
            publish(routeName, V2_VOICE_ROUTE)
            checkpoint(V2VoiceStagingCheckpoint.ROUTE_PUBLISHED)
        }
        // The committed route must be durable before making the WAV visible as finalized.
        syncDirectory(directory)
        publish(V2_VOICE_PAYLOAD_TEMP, route.basename)
        checkpoint(V2VoiceStagingCheckpoint.PAYLOAD_PUBLISHED)
        return inspect()
    }

    private fun syncFile(file: Path) {
        FileChannel.open(file, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
    }
}

/** Validate the complete RIFF envelope without decoding or allocating audio samples. */
internal fun wavFingerprint(file: Path): V2WavFingerprint? {
    val attributes = file.attributesOrNull() ?: return null
    val size = attributes.size()
    if (!attributes.isRegularFile || size <= 44 || !hasCompleteWave(file, size)) return null

    val digest = MessageDigest.getInstance("SHA-256")
    var readSize = 0L
    Files.newInputStream(file, NOFOLLOW_LINKS).use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("WAV verification interrupted")
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            readSize += count
        }
    }
    if (readSize != size) return null
    val sha256 = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return V2WavFingerprint(size, sha256)
}

private fun hasCompleteWave(file: Path, size: Long): Boolean = RandomAccessFile(file.toFile(), "r").use { wav ->
    if (wav.readInt() != 0x52494646 || wav.readUnsignedLeInt() + 8 != size ||
        wav.readInt() != 0x57415645
    ) return@use false

    var blockAlign = 0
    var dataSize = 0L
    while (wav.filePointer < size) {
        if (size - wav.filePointer < 8) return@use false
        val kind = wav.readInt()
        val length = wav.readUnsignedLeInt()
        val start = wav.filePointer
        val end = start + length + (length and 1)
        if (end > size) return@use false
        when (kind) {
            0x666d7420 -> { // fmt
                if (blockAlign != 0 || length < 16) return@use false
                wav.seek(start + 12)
                blockAlign = wav.readUnsignedByte() or (wav.readUnsignedByte() shl 8)
                if (blockAlign == 0) return@use false
            }
            0x64617461 -> { // data
                if (dataSize != 0L || length == 0L) return@use false
                dataSize = length
            }
        }
        wav.seek(end)
    }
    blockAlign > 0 && dataSize > 0 && dataSize % blockAlign == 0L
}

private fun RandomAccessFile.readUnsignedLeInt(): Long =
    Integer.reverseBytes(readInt()).toLong() and 0xffffffffL
