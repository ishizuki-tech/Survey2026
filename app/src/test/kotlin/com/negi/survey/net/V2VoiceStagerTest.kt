package com.negi.survey.net

import com.negi.survey.net.V2VoiceStageResult.Blocked
import com.negi.survey.net.V2VoiceStageResult.Ready
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.vm.SurveyFinalizationSnapshot
import com.negi.survey.vm.SurveyViewModel
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class V2VoiceStagerTest {
    @get:Rule val files = TemporaryFolder()

    private val surveyId = "01a1b874-6bff-7abc-9def-0123456789ab"
    private val nextSurveyId = "01a1b874-6c00-7abc-9def-0123456789ab"
    private val tag = DeviceUploadTag("Pixel_Test_123")
    private val question = "Q11_follow-up.2"
    private val firstId = V2RecordingId("00000000000040008000000000000001")
    private val secondId = V2RecordingId("00000000000040008000000000000002")
    private val destination = V2SurveyJsonDestination(
        V2SurveyJsonDestination.Identity.Development, "ishizuki-tech", "SurveyExports-Dev", "main", ""
    )
    private val basename = "2026-11-07_23-59-59Z_voice_${tag.value}_${surveyId}_${question}_${firstId.value}.wav"
    private val store get() = V2VoiceRouteStore(files.root)
    private val jsonStore get() = V2SurveyJsonRouteStore(files.root)

    @Test
    fun exactWavRouteUsesCanonicalSurveyStartUtcAndOriginalQuestion() {
        val source = wav("recorded.wav")
        val ready = stage(source, survey = surveyId.uppercase()) as Ready
        val route = ready.route
        assertEquals(1, route.formatVersion)
        assertEquals(surveyId, route.surveyUuid)
        assertEquals(question, route.questionId)
        assertEquals(firstId, route.recordingId)
        assertEquals(tag.value, route.deviceTag)
        assertEquals(destination, route.destination)
        assertEquals(basename, route.basename)
        assertEquals("2026-11-07/voice/$basename", route.remoteRelativePath)
        assertEquals("$V2_VOICE_NAMESPACE/${firstId.value}/$basename", route.localFileIdentity)
        assertEquals(basename, ready.file.name)
        assertEquals(source.length(), route.wavSize)
        val expectedSha = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertEquals(expectedSha, route.wavSha256)
        assertArrayEquals(source.readBytes(), ready.file.readBytes())
        assertEquals(setOf(V2_VOICE_ROUTE, basename), directory().list().orEmpty().toSet())
    }

    @Test
    fun utcMidnightAndLaterRecordingTimesUseTheSurveyStart() {
        val first = wav("first.wav").apply {
            assertTrue(setLastModified(Instant.parse("2026-11-08T03:00:00Z").toEpochMilli()))
        }
        val later = wav("later.wav").apply {
            assertTrue(setLastModified(Instant.parse("2026-11-12T00:00:00Z").toEpochMilli()))
        }
        val original = stage(first) as Ready
        val rerecorded = stage(later, recordingId = secondId) as Ready
        val otherId = V2RecordingId("00000000000040008000000000000003")
        val midnight = stage(later, survey = nextSurveyId, recordingId = otherId) as Ready
        assertTrue(original.route.remoteRelativePath.startsWith("2026-11-07/voice/2026-11-07_23-59-59Z_"))
        assertTrue(rerecorded.route.remoteRelativePath.startsWith("2026-11-07/voice/2026-11-07_23-59-59Z_"))
        assertTrue(midnight.route.remoteRelativePath.startsWith("2026-11-08/voice/2026-11-08_00-00-00Z_"))
        assertNotEquals(original.route.remoteRelativePath, rerecorded.route.remoteRelativePath)
    }

    @Test
    fun recordingIdsAreCompactFilenameSafeUuidv4AndDistinctRoutesDoNotUseSuffixes() {
        val generated = V2RecordingId.newForRecording()
        assertEquals(generated, V2RecordingId(generated.value))
        assertEquals(32, generated.value.length)
        assertThrows(IllegalArgumentException::class.java) { V2RecordingId("../unsafe") }

        val source = wav("recorded.wav")
        val original = stage(source) as Ready
        val rerecorded = stage(source, recordingId = secondId) as Ready
        assertTrue(original.file.name.endsWith("_${firstId.value}.wav"))
        assertTrue(rerecorded.file.name.endsWith("_${secondId.value}.wav"))
        assertNotEquals(original.file, rerecorded.file)
        assertEquals(2, store.discover().size)
    }

    @Test
    fun retryRetainsExactBasenameBytesAndOriginalDeviceTagAfterRestart() {
        val source = wav("recorded.wav")
        val original = stage(source) as Ready
        val routeBytes = routeFile().readBytes()
        val retry = stage(source, deviceTag = DeviceUploadTag("different_device")) as Ready
        assertEquals(original, retry)
        assertEquals(original, V2VoiceStager(store).recover(firstId))
        assertEquals(original, store.discover().getValue(firstId.value))
        assertArrayEquals(routeBytes, routeFile().readBytes())
        assertArrayEquals(source.readBytes(), original.file.readBytes())
    }

    @Test
    fun rejectsInvalidUuidUuidv4AndUnsafeFilenameSegments() {
        val source = wav("recorded.wav")
        for (invalid in listOf("invalid", "01a1b874-6bff-4abc-9def-0123456789ab", " $surveyId")) {
            assertThrows(IllegalArgumentException::class.java) { stage(source, survey = invalid) }
        }
        for (invalid in listOf("", "../Q11", "Q/11", "Q\\11", "a..b")) {
            assertThrows(IllegalArgumentException::class.java) { stage(source, questionId = invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { stage(source, deviceTag = DeviceUploadTag("../device")) }
        assertTrue(store.discover().isEmpty())
    }

    @Test
    fun completeWavBasenameAccepts255Utf8BytesAndRejects256() {
        val source = wav("recorded.wav")
        val baseLength = "2026-11-07_23-59-59Z_voice_${tag.value}_${surveyId}__${firstId.value}.wav".length
        val longestQuestion = "Q".repeat(255 - baseLength)
        assertThrows(IllegalArgumentException::class.java) { stage(source, questionId = longestQuestion + "Q") }
        val ready = stage(source, questionId = longestQuestion) as Ready
        assertEquals(255, ready.file.name.toByteArray(Charsets.UTF_8).size)
        assertEquals(longestQuestion, ready.route.questionId)
    }

    @Test
    fun routeBindsBytesQuestionSurveyAndDestinationAcrossRetries() {
        val source = wav("recorded.wav")
        val original = stage(source) as Ready
        val changed = wav("different.wav", sample = 11)
        assertTrue(stage(changed) is Blocked)
        assertTrue(stage(source, questionId = "other") is Blocked)
        assertTrue(stage(source, survey = nextSurveyId) is Blocked)
        assertTrue(stage(source, target = destination.copy(branch = "other")) is Blocked)
        assertEquals(original, store.inspect(firstId))
        assertArrayEquals(source.readBytes(), original.file.readBytes())
    }

    @Test
    fun rejectsNonRootDestinationAndProductionFromLocalOrDebug() {
        val source = wav("recorded.wav")
        assertThrows(IllegalArgumentException::class.java) {
            stage(source, target = destination.copy(pathPrefix = "exports/surveys"))
        }
        val production = destination.copy(
            identity = V2SurveyJsonDestination.Identity.Production,
            owner = "different-owner",
            repository = "SurveyExports"
        )
        assertTrue(stage(source, target = production) is Blocked)
        assertTrue(store.discover().isEmpty())
        assertTrue(stage(source, target = production, local = false) is Ready)
        assertTrue(V2VoiceStager(store, localOrDebugBuild = true).recover(firstId) is Blocked)
    }

    @Test
    fun truncatedSourceAndTruncatedTemporaryWavAreNeverFinalized() {
        val source = wav("recorded.wav")
        val original = source.readBytes()
        source.writeBytes(original.copyOf(original.size - 1))
        assertTrue(stage(source) is Blocked)
        assertTrue(store.discover().isEmpty())
        source.writeBytes(original)

        assertThrows(IOException::class.java) {
            stage(source, failAt = V2VoiceStagingCheckpoint.ROUTE_PUBLISHED)
        }
        val partial = File(directory(), V2_VOICE_PAYLOAD_TEMP)
        partial.writeBytes(original.copyOf(original.size - 1))
        assertTrue(V2VoiceStager(store).recover(firstId) is Blocked)
        assertTrue(partial.isFile)
        assertTrue(routeFile().isFile)
        assertFalse(File(directory(), basename).exists())
    }

    @Test
    fun failureBeforeRouteCreationRetainsUnboundTemporaryBytesAndBlocksGuessing() {
        val source = wav("recorded.wav")
        assertThrows(IOException::class.java) {
            stage(source, failAt = V2VoiceStagingCheckpoint.PAYLOAD_WRITTEN_BEFORE_SYNC)
        }
        val temporary = File(directory(), V2_VOICE_PAYLOAD_TEMP)
        assertArrayEquals(source.readBytes(), temporary.readBytes())
        assertTrue(stage(source, deviceTag = DeviceUploadTag("different")) is Blocked)
        assertTrue(V2VoiceStager(store).recover(firstId) is Blocked)
        assertFalse(routeFile().exists())
    }

    @Test
    fun failedRoutePublicationKeepsEvidenceAndRecoveryUsesTheSealedRoute() {
        val source = wav("recorded.wav")
        assertThrows(IOException::class.java) {
            stage(source, failAt = V2VoiceStagingCheckpoint.ROUTE_WRITTEN_BEFORE_SYNC)
        }
        val routeTemp = File(directory(), V2_VOICE_ROUTE_TEMP)
        val originalRoute = routeTemp.readBytes()
        assertTrue(store.discover().getValue(firstId.value) is Blocked)
        assertFalse(routeFile().exists())
        assertFalse(File(directory(), basename).exists())
        assertTrue(source.delete())

        val ready = V2VoiceStager(store).recover(firstId) as Ready
        assertEquals(basename, ready.file.name)
        assertArrayEquals(originalRoute, routeFile().readBytes())
        assertFalse(routeTemp.exists())
    }

    @Test
    fun interruptedFinalPublicationRetriesWithIdenticalPathAndNoAdditionalCopy() {
        val source = wav("recorded.wav")
        val original = source.readBytes()
        assertThrows(IOException::class.java) {
            stage(source, failAt = V2VoiceStagingCheckpoint.ROUTE_PUBLISHED)
        }
        assertTrue(store.discover().getValue(firstId.value) is Blocked)
        assertTrue(routeFile().isFile)
        assertTrue(source.delete())
        assertThrows(IOException::class.java) {
            V2VoiceStager(store) {
                if (it == V2VoiceStagingCheckpoint.PAYLOAD_PUBLISHED) throw IOException("stop after publish")
            }.recover(firstId)
        }
        val ready = V2VoiceStager(store).recover(firstId) as Ready
        assertEquals(basename, ready.file.name)
        assertArrayEquals(original, ready.file.readBytes())
        assertEquals(setOf(V2_VOICE_ROUTE, basename), directory().list().orEmpty().toSet())
    }

    @Test
    fun corruptRouteMissingWavAndHashMismatchRemainBlockedAndRetained() {
        val source = wav("recorded.wav")
        val ready = stage(source) as Ready
        val originalRoute = routeFile().readBytes()
        routeFile().writeText(routeFile().readText().replace("Pixel_Test_123", "different"))
        assertTrue(store.inspect(firstId) is Blocked)
        assertTrue(V2VoiceStager(store).recover(firstId) is Blocked)
        assertTrue(ready.file.isFile)
        routeFile().writeBytes(originalRoute)

        val changed = wav("changed.wav", sample = 20).readBytes()
        ready.file.writeBytes(changed)
        assertTrue(store.discover().getValue(firstId.value) is Blocked)
        assertTrue(stage(source) is Blocked)
        assertArrayEquals(changed, ready.file.readBytes())
        assertTrue(ready.file.delete())
        assertTrue(store.inspect(firstId) is Blocked)
        assertArrayEquals(originalRoute, routeFile().readBytes())
    }

    @Test
    fun v2JsonUsesBothExactFinalizedBasenamesForMultipleRecordingsOfOneQuestion() {
        val first = stage(wav("first.wav")) as Ready
        val second = stage(wav("second.wav", sample = 22), recordingId = secondId) as Ready
        val refs = listOf(first.audioRef(1), second.audioRef(2))
        val staged = stageJson(refs) as V2SurveyJsonStager.Result.Ready
        val json = Json.parseToJsonElement(staged.file.readText()).jsonObject
        val answers = json.getValue("answers").jsonObject.getValue(question).jsonObject.getValue("audio").jsonArray
        val voices = json.getValue("voice_files").jsonArray
        val expected = listOf(first, second).map {
            assertEquals(it.file.name, it.route.remoteRelativePath.substringAfterLast('/'))
            assertEquals(it.file.name, it.route.basename)
            it.file.name
        }
        assertEquals(expected, answers.map { it.jsonObject.getValue("file").jsonPrimitive.content })
        assertEquals(expected, voices.map { it.jsonObject.getValue("file").jsonPrimitive.content })
        assertTrue(voices.all { it.jsonObject.getValue("question_id").jsonPrimitive.content == question })
    }

    @Test
    fun v2JsonFailsClosedForWrongQuestionBasenameDestinationAndMissingWav() {
        val ready = stage(wav("recorded.wav")) as Ready
        val ref = ready.audioRef(1)
        assertBlockedJson(ref.copy(questionId = "other"))
        assertBlockedJson(ref.copy(fileName = "../" + ref.fileName))
        assertBlockedJson(ref.copy(byteSize = 5))
        assertBlockedJson(ref.copy(checksum = "0".repeat(64)))
        assertBlockedJson(ref, destination.copy(branch = "different"))
        assertTrue(ready.file.delete())
        assertBlockedJson(ref)
        assertEquals(V2SurveyJsonRouteStore.ReadResult.Missing, jsonStore.read(surveyId))
    }

    @Test
    fun immutableJsonRetryWithMissingWavIsBlockedWithoutRewritingThePayload() {
        val ready = stage(wav("recorded.wav")) as Ready
        val json = stageJson(listOf(ready.audioRef(1))) as V2SurveyJsonStager.Result.Ready
        val frozen = json.file.readBytes()
        assertTrue(ready.file.delete())
        // The retry still validates the original JSON's references even if the caller omits them.
        assertEquals(V2SurveyJsonStager.Result.Unavailable(V2JsonRecoveryState.BLOCKED_VOICE_REFERENCES),
            stageJson(emptyList()))
        assertArrayEquals(frozen, json.file.readBytes())
        assertEquals(V2SurveyJsonRouteStore.ReadResult.Pending(json.route), jsonStore.read(surveyId))
    }

    private fun assertBlockedJson(
        ref: SurveyViewModel.AudioRef,
        target: V2SurveyJsonDestination = destination
    ) {
        assertEquals(V2SurveyJsonStager.Result.Unavailable(V2JsonRecoveryState.BLOCKED_VOICE_REFERENCES),
            stageJson(listOf(ref), target))
    }

    private fun stageJson(
        refs: List<SurveyViewModel.AudioRef>,
        target: V2SurveyJsonDestination = destination
    ) = V2SurveyJsonStager(jsonStore).stage(
        SurveyFinalizationSnapshot(surveyId, emptyMap(), emptyMap(), emptyMap(), refs, "{}", emptyMap()),
        tag, target, "2026-11-10_12-00-00"
    )

    private fun stage(
        source: File,
        survey: String = surveyId,
        questionId: String = question,
        recordingId: V2RecordingId = firstId,
        deviceTag: DeviceUploadTag = tag,
        target: V2SurveyJsonDestination = destination,
        local: Boolean = true,
        failAt: V2VoiceStagingCheckpoint? = null
    ) = V2VoiceStager(store, local) {
        if (it == failAt) throw IOException("Injected interruption at $it")
    }.stage(source, survey, questionId, recordingId, deviceTag, target)

    private fun directory() = File(files.root, "$V2_VOICE_NAMESPACE/${firstId.value}")
    private fun routeFile() = File(directory(), V2_VOICE_ROUTE)

    private fun wav(name: String, sample: Short = 7): File {
        val payloadSize = 8
        val bytes = ByteBuffer.allocate(44 + payloadSize).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + payloadSize).put("WAVEfmt ".toByteArray())
        bytes.putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        bytes.put("data".toByteArray()).putInt(payloadSize)
        repeat(payloadSize / 2) { bytes.putShort(sample) }
        return files.newFile(name).apply { writeBytes(bytes.array()) }
    }
}
