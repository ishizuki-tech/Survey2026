package com.negi.survey.net

import com.negi.survey.net.V2JsonRecoveryState.*
import com.negi.survey.net.V2JsonStagingCheckpoint.*
import com.negi.survey.net.V2SurveyJsonDestination.Identity
import com.negi.survey.net.V2SurveyJsonRouteStore.ReadResult
import com.negi.survey.net.V2SurveyJsonStager.Result
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.vm.SurveyFinalizationSnapshot
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class V2SurveyJsonStagerTest {
    @get:Rule val files = TemporaryFolder()

    private val surveyId = "01a1b874-6bff-7abc-9def-0123456789ab"
    private val tag = DeviceUploadTag("Pixel_9a_A13F82C4D9E1")
    private val dev = V2SurveyJsonDestination(
        Identity.Development, "ishizuki-tech", "SurveyExports-Dev", "field/test", "exports/surveys"
    )
    private val production = dev.copy(identity = Identity.Production, repository = "SurveyExports")
    private val exportedAt = "2026-11-10_11-12-13"
    private val basename = "2026-11-07_23-59-59Z_survey_${tag.value}_$surveyId.json"
    private val snapshot = SurveyFinalizationSnapshot(
        surveyId = surveyId,
        questions = mapOf("Q11" to "Question?"),
        answers = mapOf("Q11" to "Mavuno — café"),
        followups = emptyMap(),
        audioRefs = emptyList(),
        aiOutcomesJson = "{}",
        extraMeta = emptyMap()
    )

    private val directory get() = File(files.root, "v2_survey_json/$surveyId")
    private val finalFile get() = File(directory, basename)
    private val routeFile get() = File(directory, V2_PENDING_ROUTE)
    private val tempFile get() = File(directory, V2_PAYLOAD_TEMP)

    @Test
    fun persistsExactRouteAndJsonAcrossRestart() {
        val staged = stage() as Result.Ready
        val expectedBytes = SurveyExportJsonBuilder.build(snapshot, exportedAt).toByteArray(Charsets.UTF_8)
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(expectedBytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

        assertFalse(staged.reused)
        assertEquals(finalFile.canonicalFile, staged.file)
        assertArrayEquals(expectedBytes, staged.file.readBytes())
        assertEquals(1, staged.route.formatVersion)
        assertEquals("immutable_v2", staged.route.protocol)
        assertEquals(surveyId, staged.route.surveyUuid)
        assertEquals(tag.value, staged.route.deviceTag)
        assertEquals(basename, staged.route.stagedFilename)
        assertEquals("v2_survey_json/$surveyId/$basename", staged.route.localFileIdentity)
        assertEquals("2026-11-07/survey/$basename", staged.route.remoteRelativePath)
        assertEquals("exports/surveys/2026-11-07/survey/$basename", staged.route.remoteTargetPath)
        assertEquals(dev, staged.route.destination)
        assertEquals(expectedDigest, staged.route.payloadSha256)
        assertEquals(ReadResult.Pending(staged.route), store().read(surveyId.uppercase()))
        assertEquals(mapOf(surveyId to ReadResult.Pending(staged.route)), store().listPending())
        assertFalse(tempFile.exists())
    }

    @Test
    fun routeCreationIsIdempotentAndConflictingFingerprintsCannotRebind() {
        val original = route()
        assertEquals(original, store().createIfAbsent(original))
        val persisted = routeFile.readBytes()
        assertEquals(original, store().createIfAbsent(original))
        assertThrows(IllegalArgumentException::class.java) {
            store().createIfAbsent(original.copy(payloadSha256 = "1".repeat(64)))
        }
        assertArrayEquals(persisted, routeFile.readBytes())
    }

    @Test
    fun enforcesDestinationIsolationAndFreezesEveryRouteField() {
        val staged = stage() as Result.Ready
        val persisted = routeFile.readBytes()
        listOf(
            production, dev.copy(owner = "other-owner"), dev.copy(branch = "main"),
            dev.copy(pathPrefix = "")
        ).forEach { changed ->
            assertState(CONFLICT_ROUTE, stage(destination = changed))
            assertThrows(IllegalArgumentException::class.java) {
                store().createIfAbsent(staged.route.copy(destination = changed))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { dev.copy(identity = Identity.Production) }
        assertThrows(IllegalArgumentException::class.java) { production.copy(identity = Identity.Development) }
        assertThrows(IllegalArgumentException::class.java) { dev.copy(branch = "branch/../other") }
        assertThrows(IllegalArgumentException::class.java) { dev.copy(pathPrefix = "exports/../other") }
        assertThrows(IllegalArgumentException::class.java) { dev.copy(owner = "org/other") }
        assertState(CONFLICT_ROUTE, stager().stage(snapshot, DeviceUploadTag("new-device"), dev, exportedAt))
        assertArrayEquals(persisted, routeFile.readBytes())
        assertEquals(dev, (stager().recover(surveyId) as Result.Ready).route.destination)
    }

    @Test
    fun independentlyStagedProductionRouteUsesItsOwnTarget() {
        val nextSurvey = snapshot.copy(surveyId = "01a1b874-6c00-7abc-9def-0123456789ab")
        val devRoute = (stage() as Result.Ready).route
        val productionTarget = production.copy(pathPrefix = "", branch = "main")
        val prodRoute = (stager().stage(nextSurvey, tag, productionTarget, exportedAt) as Result.Ready).route

        assertEquals(dev, devRoute.destination)
        assertEquals(productionTarget, prodRoute.destination)
        assertEquals(prodRoute.remoteRelativePath, prodRoute.remoteTargetPath)
        assertTrue(prodRoute.remoteTargetPath.startsWith("2026-11-08/survey/"))
        assertEquals(2, store().listPending().size)
    }

    @Test
    fun rejectsInvalidUuidBeforeWritingAnyFiles() {
        assertThrows(IllegalArgumentException::class.java) {
            stager().stage(snapshot.copy(surveyId = "not-a-uuid"), tag, dev, exportedAt)
        }
        assertTrue(files.root.list().orEmpty().isEmpty())
    }

    @Test
    fun rejectsCorruptIncompleteAndUnsupportedRecordsWithoutChangingEvidence() {
        stage()
        val original = routeFile.readText()
        val invalidRecords = listOf(
            "{",
            original.replace("immutable_v2", "immutable_v99"),
            original.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            original.replace("\"owner\":\"ishizuki-tech\",", ""),
            original.replace("field/test", "other/branch")
        )
        assertEquals(invalidRecords.size, invalidRecords.toSet().size)
        val payload = finalFile.readBytes()
        invalidRecords.forEach { corrupt ->
            assertNotEquals(original, corrupt)
            routeFile.writeText(corrupt)
            assertTrue(store().read(surveyId) is ReadResult.Invalid)
            assertTrue(store().listPending()[surveyId] is ReadResult.Invalid)
            assertState(BLOCKED_INVALID_ROUTE, stager().recover(surveyId))
            assertThrows(IllegalStateException::class.java) { store().createIfAbsent(route()) }
            assertEquals(corrupt, routeFile.readText())
            assertArrayEquals(payload, finalFile.readBytes())
        }
    }

    @Test
    fun filesystemPersistenceFailureIsReportedWithoutReplacingEvidence() {
        val blockedNamespace = File(files.root, V2_JSON_NAMESPACE).apply { writeText("existing file") }

        assertThrows(IOException::class.java) { stage() }
        assertEquals("existing file", blockedNamespace.readText())
    }

    @Test
    fun routePersistenceSyncFailureLeavesUnpublishedEvidenceAndReportsError() {
        assertThrows(IOException::class.java) { stage(checkpoint = ROUTE_WRITTEN_BEFORE_SYNC) }
        assertTrue(File(directory, V2_ROUTE_TEMP).isFile)
        assertTrue(tempFile.isFile)
        assertFalse(finalFile.exists())
        assertEquals(ReadResult.Missing, store().read(surveyId))
        assertState(BLOCKED_MISSING_ROUTE, stager().recover(surveyId))
        assertState(BLOCKED_MISSING_ROUTE, stage(destination = production))
    }

    @Test
    fun failureBeforeRoutePublicationLeavesTemporaryPayloadBlocked() {
        assertThrows(IOException::class.java) { stage(checkpoint = PAYLOAD_WRITTEN_BEFORE_SYNC) }
        assertEquals(setOf(V2_PAYLOAD_TEMP), directory.list().orEmpty().toSet())
        assertState(BLOCKED_MISSING_ROUTE, stager().recover(surveyId))
        assertState(BLOCKED_MISSING_ROUTE, stage())
    }

    @Test
    fun failureAfterRoutePublicationRecoversMatchingTemporaryPayload() {
        assertThrows(IOException::class.java) { stage(checkpoint = ROUTE_PUBLISHED) }
        val route = (store().read(surveyId) as ReadResult.Pending).route
        val original = tempFile.readBytes()
        assertFalse(finalFile.exists())

        val recovered = stager().recover(surveyId) as Result.Ready
        assertEquals(route, recovered.route)
        assertTrue(recovered.reused)
        assertArrayEquals(original, finalFile.readBytes())
        assertFalse(tempFile.exists())
    }

    @Test
    fun failureAfterJsonPublicationRecoversExistingFinalWithoutWritingItAgain() {
        assertThrows(IOException::class.java) { stage(checkpoint = PAYLOAD_PUBLISHED) }
        val original = finalFile.readBytes()
        val recovered = V2SurveyJsonStager(store(PAYLOAD_PUBLISHED)).recover(surveyId) as Result.Ready
        assertTrue(recovered.reused)
        assertArrayEquals(original, finalFile.readBytes())
        assertFalse(tempFile.exists())
    }

    @Test
    fun truncatedTemporaryPayloadIsRetainedAndNeverPublished() {
        assertThrows(IOException::class.java) { stage(checkpoint = ROUTE_PUBLISHED) }
        val truncated = tempFile.readBytes().copyOf(10)
        tempFile.writeBytes(truncated)
        assertState(CONFLICT_PAYLOAD, stager().recover(surveyId))
        assertState(CONFLICT_PAYLOAD, stage())
        assertArrayEquals(truncated, tempFile.readBytes())
        assertFalse(finalFile.exists())
        assertTrue(routeFile.isFile)
    }

    @Test
    fun finalizedPayloadWithoutRouteIsRetainedAndCannotBeRebound() {
        stage()
        val original = finalFile.readBytes()
        assertTrue(routeFile.delete())
        assertEquals(mapOf(surveyId to ReadResult.Missing), store().listPending())
        assertState(BLOCKED_MISSING_ROUTE, stager().recover(surveyId))
        assertState(BLOCKED_MISSING_ROUTE, stage(destination = production))
        assertThrows(IllegalStateException::class.java) { store().createIfAbsent(route()) }
        assertArrayEquals(original, finalFile.readBytes())
        assertFalse(routeFile.exists())
    }

    @Test
    fun routeWithoutRecoverableJsonIsRetainedAndCannotRegenerateJson() {
        val original = store().createIfAbsent(route())
        assertState(BLOCKED_MISSING_PAYLOAD, stager().recover(surveyId))
        assertState(BLOCKED_MISSING_PAYLOAD, stage())
        assertEquals(ReadResult.Pending(original), store().read(surveyId))
        assertEquals(setOf(V2_PENDING_ROUTE), directory.list().orEmpty().toSet())
    }

    @Test
    fun rejectsChangedFinalPayloadAndPreservesRouteAndBytes() {
        val original = stage() as Result.Ready
        val changed = finalFile.readText().replace("café", "coffee")
        finalFile.writeText(changed)
        assertState(CONFLICT_PAYLOAD, stager().recover(surveyId))
        assertState(CONFLICT_PAYLOAD, stage())
        assertEquals(changed, finalFile.readText())
        assertEquals(ReadResult.Pending(original.route), store().read(surveyId))
    }

    @Test
    fun duplicatePayloadsAreBlockedEvenWhenTheirBytesMatch() {
        stage()
        finalFile.copyTo(tempFile)
        assertState(CONFLICT_UNEXPECTED_FILES, stager().recover(surveyId))
        assertTrue(tempFile.isFile)
        assertTrue(tempFile.delete())
        val duplicate = finalFile.copyTo(File(directory, "duplicate.json"))
        assertState(CONFLICT_UNEXPECTED_FILES, stager().recover(surveyId))
        assertTrue(finalFile.isFile)
        assertTrue(duplicate.isFile)
    }

    @Test
    fun retryUsesOriginalBytesWithoutReserializingEvenAfterMidnight() {
        var calls = 0
        val stager = V2SurveyJsonStager(store()) { captured, stamp ->
            calls++
            SurveyExportJsonBuilder.build(captured, stamp)
        }
        val first = stager.stage(snapshot, tag, dev, exportedAt) as Result.Ready
        val bytes = first.file.readBytes()
        val retried = V2SurveyJsonStager(store(PAYLOAD_PUBLISHED)) { _, _ ->
            error("An established route must bypass serialization")
        }.stage(snapshot.copy(answers = emptyMap()), tag, dev, "2026-11-12_00-00-00") as Result.Ready

        assertEquals(1, calls)
        assertTrue(retried.reused)
        assertEquals(first.route, retried.route)
        assertTrue(retried.route.remoteRelativePath.startsWith("2026-11-07/"))
        assertTrue(retried.file.readText().contains("\"exported_at\": \"$exportedAt\""))
        assertArrayEquals(bytes, retried.file.readBytes())
    }

    @Test
    fun confirmedDeliveryRetiresPendingRouteButPreventsRebinding() {
        val staged = stage() as Result.Ready
        assertThrows(IllegalArgumentException::class.java) {
            store().removeAfterConfirmedDelivery(staged.route.copy(destination = production))
        }
        assertEquals(ReadResult.Pending(staged.route), store().read(surveyId))
        store().removeAfterConfirmedDelivery(staged.route)
        store().removeAfterConfirmedDelivery(staged.route)
        assertEquals(ReadResult.Delivered(staged.route), store().read(surveyId))
        assertTrue(store().listPending().isEmpty())
        assertState(DELIVERED, stage())
        assertThrows(IllegalArgumentException::class.java) {
            store().createIfAbsent(staged.route.copy(destination = production))
        }
        assertTrue(finalFile.isFile)
    }

    @Test
    fun simultaneousStagesAcrossStoreInstancesPublishOnlyOnce() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = (1..2).map { executor.submit(Callable { stage() as Result.Ready }) }
            val staged = tasks.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, staged.count { !it.reused })
            assertEquals(staged[0].route, staged[1].route)
            assertEquals(setOf(V2_PENDING_ROUTE, basename), directory.list().orEmpty().toSet())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun classificationIsPureAndIgnoresContentWhenRoutingEvidenceIsMissing() {
        assertEquals(ABSENT, classifyV2JsonRecovery(ReadResult.Missing, emptySet(), false))
        assertEquals(BLOCKED_MISSING_ROUTE, classifyV2JsonRecovery(ReadResult.Missing, setOf(basename), true))
        val pending = ReadResult.Pending(route())
        val files = setOf(V2_PENDING_ROUTE, V2_PAYLOAD_TEMP)
        assertEquals(PUBLISH_TEMPORARY, classifyV2JsonRecovery(pending, files, true))
        assertEquals(CONFLICT_PAYLOAD, classifyV2JsonRecovery(pending, files, false))
    }

    private fun route() = V2SurveyJsonRoute.create(surveyId, tag, dev, "0".repeat(64))

    private fun store(failAt: V2JsonStagingCheckpoint? = null) = V2SurveyJsonRouteStore(files.root) {
        if (it == failAt) throw IOException("Injected failure at $it")
    }

    private fun stager() = V2SurveyJsonStager(store())

    private fun stage(
        checkpoint: V2JsonStagingCheckpoint? = null,
        destination: V2SurveyJsonDestination = dev
    ) = V2SurveyJsonStager(store(checkpoint)).stage(snapshot, tag, destination, exportedAt)

    private fun assertState(expected: V2JsonRecoveryState, result: Result) {
        assertEquals(Result.Unavailable(expected), result)
    }
}
