package com.negi.survey.diagnostics

import com.negi.survey.net.*
import com.negi.survey.net.V2DiagnosticState.Reason
import com.negi.survey.slm.PromptPhase
import com.negi.survey.slm.RepositoryTraceContext
import com.negi.survey.slm.RepositoryTraceEvent
import com.negi.survey.utils.DeviceUploadTag
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class V2DiagnosticTraceTest {
    @get:Rule val files = TemporaryFolder()

    private val surveyId = "01a1b874-6bff-7abc-9def-0123456789ab"
    private val nextSurveyId = "01a1b874-6c00-7abc-9def-0123456789ab"
    private val traceId = V2TraceId("00000000000040008000000000000001")
    private val secondId = V2TraceId("00000000000040008000000000000002")
    private val identity = TraceIdentity(surveyId, deviceTag = "Pixel_Test", processId = 7, appVersion = "1")
    private val destination = V2SurveyJsonDestination(
        V2SurveyJsonDestination.Identity.Development, "ishizuki-tech", "SurveyExports-Dev", "main", ""
    )
    private val store get() = V2DiagnosticRouteStore(files.root)

    @Test
    fun fullUuidPathsAreRootRelativeAndUseSurveyStartAcrossUtcMidnightAndTimezoneChanges() {
        val old = TimeZone.getDefault()
        try {
            for (zone in listOf("Pacific/Auckland", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val survey = capture(survey = surveyId.uppercase()).path()
                val whisper = capture(stream = V2DiagnosticStream.WHISPER, survey = nextSurveyId).path()
                assertEquals(
                    "2026-11-07/diagnostics/survey/2026-11-07_23-59-59Z_survey_Pixel_Test_${surveyId}_${traceId.value}.ndjson.gz",
                    survey.relativePath
                )
                assertEquals(
                    "2026-11-08/diagnostics/whisper/2026-11-08_00-00-00Z_whisper_Pixel_Test_${nextSurveyId}_${traceId.value}.ndjson.gz",
                    whisper.relativePath
                )
            }
        } finally {
            TimeZone.setDefault(old)
        }
    }

    @Test
    fun rejectsInvalidSurveyIdsUnsafeSegmentsAndNonemptyPrefixBeforeOpening() {
        for (id in listOf("invalid", "01a1b874-6bff-4abc-9def-0123456789ab", " $surveyId")) {
            assertThrows(IllegalArgumentException::class.java) { capture(survey = id) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            V2DiagnosticCapture.create(surveyId, DeviceUploadTag("../device"), destination, V2DiagnosticStream.WHISPER)
        }
        assertThrows(IllegalArgumentException::class.java) { capture(target = destination.copy(pathPrefix = "exports")) }
        assertTrue(store.discover().isEmpty())
        assertFalse(File(files.root, V2_DIAGNOSTIC_NAMESPACE).exists())
    }

    @Test
    fun explicitSurveyAndWhisperWritersKeepCorrelationAndUseIndependentTraceIds() = runBlocking {
        val survey = SurveyTrace.openV2(identity, store, destination)
        val otherSurvey = SurveyTrace.openV2(identity, store, destination)
        val whisper = WhisperTrace.openV2(identity, store, destination)
        survey.event("ANSWER_TRANSACTION_STARTED", "answer-A", "Q14", mapOf("inputSource" to "voice"))
        otherSurvey.event("QUESTION_COMPLETED", "answer-B", "Q14")
        whisper.event("TRANSCRIPTION_FINISHED", "answer-A", "Q14", mapOf("whisperRequestId" to 42))

        val outputs = listOfNotNull(survey.finalizeTrace(), otherSurvey.finalizeTrace(), whisper.finalizeTrace())
        assertEquals(3, outputs.map { it.name }.toSet().size)
        assertTrue(outputs.all { it.name.contains(surveyId) && it.name.startsWith("2026-11-07_23-59-59Z") })
        val records = outputs.map { read(it).single() }
        assertEquals("answer-A", records[0].string("answerTraceId"))
        assertEquals("answer-A", records[2].string("answerTraceId"))
        assertEquals("Q14", records[2].string("questionId"))
        assertEquals("42", records[2].string("whisperRequestId"))
        assertEquals("whisper", records[2].string("stream"))
        assertEquals(3, records.map { it.string("eventId") }.toSet().size)
        assertEquals(3, store.discover().values.count { it is V2DiagnosticState.Ready })
        assertTrue(DiagnosticTraceArtifacts.discover(files.root).isEmpty())
        assertTrue(outputs.all { isV2StagedFile(files.root, it) })
    }

    @Test
    fun delayedEventAndFinalizationKeepFrozenStartTagAndExactRouteBytes() {
        val capture = capture()
        val writer = V2DiagnosticTraceWriter(store, capture)
        val record = v2TraceRecord(identity, "survey", "QUESTION_COMPLETED", "answer-A", "Q14", emptyMap(),
            timestamp = Instant.parse("2026-11-12T00:00:00Z"), eventId = "event-A")
        writer.append(record.toString())
        val file = requireNotNull(writer.finalizeToGzip())
        val ready = store.discover().getValue(traceId.value) as V2DiagnosticState.Ready
        assertEquals(capture, ready.route.capture)
        assertEquals(file.name, ready.route.basename)
        assertEquals(file.length(), ready.route.archiveSize)
        val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertEquals(sha, ready.route.archiveSha256)
        assertEquals("event-A", read(file).single().string("eventId"))
        assertEquals("2026-11-12T00:00:00Z", read(file).single().string("timestampUtc"))
        assertTrue(file.name.startsWith("2026-11-07_23-59-59Z_survey_Pixel_Test_"))
        assertEquals(file, writer.finalizeToGzip())
        assertEquals(ready, V2DiagnosticStager(store).recover(traceId))
        assertEquals(setOf(DIAG_CAPTURE, DIAG_SEALED, DIAG_ROUTE, file.name), directory().list().orEmpty().toSet())
    }

    @Test
    fun concurrentFinalizationWaitsForQueuedWritesAndReturnsOneArchive() {
        val queued = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val writer = V2DiagnosticTraceWriter(store, capture(), beforeAppend = {
            queued.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
        })
        val pool = Executors.newFixedThreadPool(2)
        try {
            writer.append("""{"event":"one"}""")
            writer.append("""{"event":"two"}""")
            assertTrue(queued.await(5, TimeUnit.SECONDS))
            val one = pool.submit<File?> { writer.finalizeToGzip() }
            val two = pool.submit<File?> { writer.finalizeToGzip() }
            assertFalse(one.isDone)
            assertFalse(two.isDone)
            assertEquals(Reason.INCOMPLETE_PUBLICATION, reason())
            proceed.countDown()
            val file = requireNotNull(one.get(5, TimeUnit.SECONDS))
            assertEquals(file, two.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("one", "two"), read(file).map { it.string("event") })
        } finally {
            proceed.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun activeOpenNdjsonAndFailedWriterAreNeverPromotedAfterRestart() {
        var calls = 0
        val writer = V2DiagnosticTraceWriter(store, capture(), beforeAppend = {
            if (++calls == 2) throw IOException("injected event write failure")
        })
        writer.append("""{"event":"accepted"}""")
        writer.append("""{"event":"failed"}""")
        assertNull(writer.finalizeToGzip())
        val open = File(directory(), DIAG_OPEN)
        assertTrue(open.isFile)
        assertEquals(Reason.INCOMPLETE_PUBLICATION, reason())
        assertEquals(V2DiagnosticState.Blocked(Reason.INCOMPLETE_PUBLICATION), V2DiagnosticStager(store).recover(traceId))
        assertTrue(open.readText().contains("accepted"))
        assertFalse(File(directory(), DIAG_SEALED).exists())
    }

    @Test
    fun sealedSourceOrFullyWrittenTemporaryEvidenceCanRecoverWithoutReassigningIdentity() {
        val points = listOf(
            V2DiagnosticCheckpoint.SOURCE_SEALED, V2DiagnosticCheckpoint.ARCHIVE_SYNCED,
            V2DiagnosticCheckpoint.ROUTE_SYNCED, V2DiagnosticCheckpoint.ROUTE_PUBLISHED
        )
        for ((index, point) in points.withIndex()) {
            val id = V2TraceId(index.toString(16).padStart(32, '0'))
            val capture = capture(id = id)
            val writer = interrupted(capture, point)
            assertNull(writer.finalizeToGzip())
            val expected = capture.path().basename
            assertEquals(Reason.INCOMPLETE_PUBLICATION, reason(id))
            assertFalse(File(directory(id), expected).exists())
            val ready = V2DiagnosticStager(store).recover(id) as V2DiagnosticState.Ready
            assertEquals(capture, ready.route.capture)
            assertEquals(expected, ready.file.name)
            assertEquals("record", read(ready.file).single().string("event"))
        }
    }

    @Test
    fun archivePublishedBeforeInterruptedReturnIsDiscoveredAfterRestart() {
        val capture = capture()
        assertNull(interrupted(capture, V2DiagnosticCheckpoint.ARCHIVE_PUBLISHED).finalizeToGzip())
        val ready = store.discover().getValue(traceId.value) as V2DiagnosticState.Ready
        assertEquals(ready, V2DiagnosticStager(store).recover(traceId))
        assertEquals(capture.path().basename, ready.file.name)
    }

    @Test
    fun corruptTemporaryGzipIsNeverPromotedAndOriginalTraceRemains() {
        assertNull(interrupted(capture(), V2DiagnosticCheckpoint.ARCHIVE_SYNCED).finalizeToGzip())
        val temporary = File(directory(), DIAG_ARCHIVE_TEMP)
        val complete = temporary.readBytes()
        temporary.writeBytes(complete.dropLast(4).toByteArray())
        val evidence = temporary.readBytes()
        assertEquals(V2DiagnosticState.Blocked(Reason.ARCHIVE_MISMATCH), V2DiagnosticStager(store).recover(traceId))
        assertArrayEquals(evidence, temporary.readBytes())
        temporary.writeBytes(complete + byteArrayOf(1, 2, 3))
        assertEquals(V2DiagnosticState.Blocked(Reason.ARCHIVE_MISMATCH), V2DiagnosticStager(store).recover(traceId))
        assertTrue(File(directory(), DIAG_SEALED).readText().contains("record"))
        assertFalse(File(directory(), DIAG_ROUTE).exists())
    }

    @Test
    fun conflictingCaptureAndCorruptOrMissingFinalArchivesArePreserved() {
        val capture = capture()
        val writer = V2DiagnosticTraceWriter(store, capture)
        writer.append("""{"event":"original"}""")
        val file = requireNotNull(writer.finalizeToGzip())
        val bytes = file.readBytes()
        val stager = V2DiagnosticStager(store)
        assertThrows(IllegalStateException::class.java) { store.create(capture.copy(deviceTag = "other")) }
        assertEquals(V2DiagnosticState.Blocked(Reason.CONFLICTING_IDENTITY),
            stager.sealAndStage(capture.copy(deviceTag = "other")))
        assertArrayEquals(bytes, file.readBytes())
        file.appendBytes(byteArrayOf(1))
        assertEquals(Reason.ARCHIVE_MISMATCH, reason())
        assertEquals(V2DiagnosticState.Blocked(Reason.ARCHIVE_MISMATCH), stager.recover(traceId))
        assertEquals(bytes.size + 1, file.readBytes().size)
        assertTrue(file.delete())
        assertEquals(Reason.MISSING_ARCHIVE, reason())
        assertTrue(File(directory(), DIAG_ROUTE).isFile)
        assertTrue(File(directory(), DIAG_SEALED).isFile)
    }

    @Test
    fun discoveryClassifiesOrphanUnexpectedFilesInvalidRouteAndConflictingDirectory() {
        val orphan = directory(secondId)
        val capture = capture()
        val writer = V2DiagnosticTraceWriter(store, capture)
        writer.append("""{"event":"record"}""")
        requireNotNull(writer.finalizeToGzip())
        assertTrue(orphan.mkdir())
        val orphanFile = File(orphan, "orphan.ndjson.gz").apply { writeText("retain") }
        assertEquals(Reason.ORPHAN, reason(secondId))
        assertEquals("retain", orphanFile.readText())

        val extra = File(directory(), "unexpected.txt").apply { writeText("retain") }
        assertEquals(Reason.UNEXPECTED_FILES, reason())
        assertTrue(extra.delete())
        val route = File(directory(), DIAG_ROUTE)
        route.writeText("{")
        assertEquals(Reason.INVALID_ROUTE, reason())
        assertEquals("{", route.readText())

        File(directory(), DIAG_CAPTURE).copyTo(File(orphan, DIAG_CAPTURE))
        assertEquals(Reason.CONFLICTING_IDENTITY, reason(secondId))
        assertTrue(orphanFile.isFile)
    }

    @Test
    fun productionTracePolicyPreservesMetadataAndRemovesEstablishedRawFields() {
        val fields = mapOf("transcript" to "private-transcript", "rawResponse" to "private-response",
            "finalPrompt" to "private-prompt", "followup" to "private-followup",
            "answer" to "private-answer", "missing_points" to listOf("pointA", "pointB"),
            "eventId" to "override-attempt", "requestLength" to 17)
        val record = v2TraceRecord(identity, "survey", "AI_REPOSITORY_FINISHED", "answer-A", "Q14", fields,
            timestamp = Instant.EPOCH, eventId = "event-A",
            production = true)
        assertEquals("event-A", record.string("eventId"))
        assertEquals("answer-A", record.string("answerTraceId"))
        assertEquals("17", record.string("requestLength"))
        assertEquals("[pointA, pointB]", record.string("missing_points"))
        assertTrue(listOf("transcript", "rawResponse", "finalPrompt", "followup", "answer").none { it in record })
        assertFalse(record.toString().contains("private-"))
    }

    @Test
    fun explicitSlmRecordsPreserveExistingRequestAndRunIdsWithoutAnyRawContent() = runBlocking {
        val context = RepositoryTraceContext("answer-A", "Q14", PromptPhase.EVAL) { error("Must not call inference callback") }
        val trace = V2SlmDiagnosticTrace.open(identity, store, destination, context, 9)
        assertFalse(trace.event(RepositoryTraceEvent.Started(10, "model", "wrong request")))
        assertTrue(trace.event(RepositoryTraceEvent.Started(9, "private-model", "private-prompt")))
        assertNull(trace.finalizeTrace())
        assertTrue(trace.event(RepositoryTraceEvent.Finished(9, "private-response", "private-status", "private-error", 12)))
        val output = requireNotNull(trace.finalizeTrace())
        assertEquals(output, trace.finalizeTrace())
        assertTrue(output.name.contains("_${surveyId}_Q14_eval_"))
        assertTrue(output.name.endsWith("-r9.ndjson.gz"))
        val records = read(output)
        assertEquals(listOf("AI_REPOSITORY_REQUEST", "AI_REPOSITORY_FINISHED"), records.map { it.string("event") })
        assertTrue(records.all { it.string("repositoryRequestId") == "9" && it.string("answerTraceId") == "answer-A" })
        assertEquals("12", records.last().string("liteRtRunId"))
        assertEquals("true", records.last().string("errorPresent"))
        assertFalse(records.toString().contains("private-"))
        val route = store.discover().values.single() as V2DiagnosticState.Ready
        assertTrue(route.route.remoteRelativePath.startsWith("2026-11-07/diagnostics/slm/"))
        assertEquals(V2SlmCorrelation("Q14", "eval", 9), route.route.capture.slm)
    }

    @Test
    fun slmRepeatedProcessLocalRequestIdsRemainDistinctAndFollowupIsIdentifiable(): Unit = runBlocking {
        val context = RepositoryTraceContext("answer-A", "Q14", PromptPhase.FOLLOWUP) {}
        val outputs = (1..2).map {
            val trace = V2SlmDiagnosticTrace.open(identity, store, destination, context, 1)
            assertTrue(trace.event(RepositoryTraceEvent.Finished(1, "ignored", "ignored", null, 0)))
            requireNotNull(trace.finalizeTrace())
        }
        assertNotEquals(outputs.first().name, outputs.last().name)
        assertTrue(outputs.all { it.name.contains("_Q14_followup_") && it.name.endsWith("-r1.ndjson.gz") })
        assertThrows(IllegalArgumentException::class.java) {
            V2SlmDiagnosticTrace.open(identity, store, destination, context.copy(phase = PromptPhase.ONE_STEP), 1)
        }
    }

    @Test
    fun genericV2UploadGuardAlsoRejectsDiagnosticAliases() {
        val writer = V2DiagnosticTraceWriter(store, capture())
        writer.append("""{"event":"record"}""")
        val archive = requireNotNull(writer.finalizeToGzip())
        val alias = File(files.root, "generic-alias.ndjson.gz")
        Files.createSymbolicLink(alias.toPath(), archive.toPath())
        assertTrue(isV2StagedFile(files.root, alias))
        assertTrue(DiagnosticTraceArtifacts.discover(files.root).isEmpty())
    }

    private fun capture(
        stream: V2DiagnosticStream = V2DiagnosticStream.SURVEY,
        survey: String = surveyId,
        id: V2TraceId = traceId,
        target: V2SurveyJsonDestination = destination
    ) = V2DiagnosticCapture.create(survey, DeviceUploadTag(identity.deviceTag), target, stream, id)

    private fun interrupted(capture: V2DiagnosticCapture, failAt: V2DiagnosticCheckpoint): V2DiagnosticTraceWriter =
        V2DiagnosticTraceWriter(store, capture, V2DiagnosticStager(store) {
            if (it == failAt) throw IOException("injected publication interruption")
        }).apply { append("""{"event":"record"}""") }

    private fun directory(id: V2TraceId = traceId) = File(files.root, "$V2_DIAGNOSTIC_NAMESPACE/${id.value}")

    private fun reason(id: V2TraceId = traceId) =
        (store.discover().getValue(id.value) as V2DiagnosticState.Blocked).reason

    private fun read(file: File) = GZIPInputStream(file.inputStream()).bufferedReader().use { reader ->
        reader.readLines().map { Json.parseToJsonElement(it).jsonObject }
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
