package com.negi.survey.net

import androidx.work.NetworkType
import com.negi.survey.net.V2ArtifactStore.Kind
import com.negi.survey.net.V2ArtifactStore.Profile
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.utils.V2ArtifactPaths
import com.negi.survey.utils.newTimeOrderedSurveyId
import java.io.File
import java.io.IOException
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class V2ArtifactStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val surveyId = newTimeOrderedSurveyId(Instant.parse("2026-11-08T23:59:59.999Z").toEpochMilli())
    private val paths = V2ArtifactPaths(surveyId, DeviceUploadTag("TestDevice_123"))
    private val profile = Profile.DEVELOPMENT
    private val surveyKey = V2ArtifactStore.surveyKey(surveyId)

    @Test fun reopenedRoutePreservesExactDestinationAndOriginalBytes() {
        val original = survey(V2ArtifactStore(temp.root), "original")
        val reopened = survey(V2ArtifactStore(temp.root), "different retry bytes")
        assertEquals(original.route, reopened.route)
        assertEquals("original", reopened.payload.readText())
        assertEquals("2026-11-08/survey/${paths.surveyJson().basename}", reopened.route.path)
        assertTrue(reopened.matchesPayload())
        assertTrue(profile.matches(config("SurveyExports-Dev")))
        assertFalse(profile.matches(config("SurveyExports")))
        assertFalse(profile.matches(config("SurveyExports-Dev", prefix = "exports")))
        assertFalse(profile.matches(config("SurveyExports-Dev").copy(branch = "preview")))
    }

    @Test fun artifactEnumerationExcludesDeviceMetadataAndRetainsInvalidRoutes() {
        val store = V2ArtifactStore(temp.root)
        val deviceTags = temp.newFolder("device")
        File(deviceTags, surveyId).writeText("TestDevice_123")
        val staged = survey(store, "staged")
        val malformed = temp.newFolder("malformed-artifact")
        File(malformed, "route").writeText("invalid manifest")

        assertEquals(setOf(staged.route.id, malformed.name), store.artifactIds().toSet())
        assertTrue(checkNotNull(store.read(staged.route.id)).matchesPayload())
        assertNull(store.read(malformed.name))
    }

    @Test fun sealedStagingRecoversButMissingRouteNeverPublishes() {
        val store = V2ArtifactStore(temp.root)
        val original = survey(store, "sealed before publish")
        val interrupted = File(original.directory.parentFile, ".stage-$surveyKey")
        assertTrue(original.directory.renameTo(interrupted))
        val orphan = File(original.directory.parentFile, ".stage-orphan").apply { mkdir() }
        File(orphan, "retained").writeText("incomplete")
        assertNull(store.read(surveyKey))
        val reopened = V2ArtifactStore(temp.root)
        reopened.recoverInterruptedPublications()
        assertEquals(original.route, reopened.read(surveyKey)?.route)
        assertEquals("sealed before publish", reopened.read(surveyKey)?.payload?.readText())
        assertTrue(orphan.isDirectory)
        assertNull(reopened.read("orphan"))
    }

    @Test fun surveyCanRebuildUnsealedStageButKeepsConflictingEvidence() {
        val pending = temp.newFolder(".stage-$surveyKey")
        File(pending, paths.surveyJson().basename).writeText("partial")
        val store = V2ArtifactStore(temp.root)
        assertFalse(store.hasReservedRoute(surveyKey))
        val entry = survey(store, "final")
        assertEquals("final", entry.payload.readText())
        assertEquals("partial", temp.root.listFiles().orEmpty().single { it.name.startsWith(".incomplete-") }
            .resolve(entry.route.basename).readText())

        assertTrue(entry.directory.renameTo(pending))
        File(pending, "route").appendText("broken")
        assertTrue(store.hasReservedRoute(surveyKey))
        assertThrows(IllegalStateException::class.java) { survey(store, "replacement") }
        assertEquals("final", File(pending, entry.route.basename).readText())
    }

    @Test fun productionCredentialSelectsRootRelativeProductionWithoutActivationFlag() {
        val production = checkNotNull(V2ArtifactDelivery.configForCredential(Profile.PRODUCTION, "fixture"))
        val development = checkNotNull(V2ArtifactDelivery.configForCredential(Profile.DEVELOPMENT, "fixture"))
        assertEquals("ishizuki-tech", production.owner)
        assertEquals("SurveyExports", production.repo)
        assertEquals("main", production.branch)
        assertEquals("", production.pathPrefix)
        assertEquals("SurveyExports-Dev", development.repo)
        assertFalse(Profile.PRODUCTION.matches(development))
        assertNull(V2ArtifactDelivery.configForCredential(Profile.PRODUCTION, " "))
    }

    @Test fun receiptAndCleanupAreIndependentAndRouteBound() {
        val store = V2ArtifactStore(temp.root)
        val survey = survey(store, "survey")
        val voice = store.stage(
            "recording-1", Kind.VOICE, surveyId, profile, paths.voiceWav("Q8", "recording-1"), "{}",
        ) { it.write(byteArrayOf(1, 2, 3)) }
        assertFalse(voice.cleanup())
        assertTrue(voice.payload.exists())
        survey.recordReceipt()
        assertTrue(survey.cleanup())
        assertTrue(voice.matchesPayload())
        assertFalse(voice.hasReceipt())

        voice.recordReceipt()
        voice.payload.writeBytes(byteArrayOf(4, 5, 6))
        assertFalse(voice.cleanup())
        assertTrue(voice.payload.exists())
        voice.payload.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(voice.cleanup())
        assertTrue(checkNotNull(V2ArtifactStore(temp.root).read(voice.route.id)).hasReceipt())
        assertFalse(File(voice.directory, V2ArtifactStore.VOICE_METADATA).exists())
    }

    @Test fun corruptRouteAndReceiptFailClosed() {
        val store = V2ArtifactStore(temp.root)
        val entry = survey(store, "retained")
        File(entry.directory, "delivered").writeText("corrupt")
        assertThrows(IllegalStateException::class.java) { entry.cleanup() }
        File(entry.directory, "route").appendText("corrupt")
        assertNull(store.read(surveyKey))
        assertTrue(entry.payload.exists())
        assertThrows(IllegalStateException::class.java) { survey(store, "replacement") }
    }

    @Test fun staleEntryCannotCleanAChangedRouteEvenWithAReceipt() {
        val entry = survey(V2ArtifactStore(temp.root), "retained")
        entry.recordReceipt()
        File(entry.directory, "route").appendText("corrupt")
        assertFalse(entry.cleanup())
        assertTrue(entry.payload.isFile)
    }

    @Test fun traceCanRebuildOnlyAnUnsealedDerivedPackage() {
        val store = V2ArtifactStore(temp.root)
        val id = "trace-retry"
        val scratch = File(temp.root, ".stage-$id").apply { mkdir() }
        File(scratch, "partial-gzip").writeText("interrupted")
        val path = paths.surveyTrace(id)
        fun stage() = store.stage(id, Kind.SURVEY_TRACE, surveyId, profile, path, rebuildUnsealed = true) {
            it.write("rebuilt".toByteArray())
        }
        val entry = stage()
        assertTrue(entry.matchesPayload())
        assertFalse(scratch.exists())
        assertTrue(entry.directory.renameTo(scratch))
        File(scratch, "route").appendText("corrupt")
        assertThrows(IllegalStateException::class.java) { stage() }
        assertTrue(File(scratch, path.basename).isFile)
        assertNull(store.read(id))
    }

    @Test fun failedWriterNeverPublishes() {
        val store = V2ArtifactStore(temp.root)
        assertThrows(IOException::class.java) {
            store.stage(surveyKey, Kind.SURVEY, surveyId, profile, paths.surveyJson()) {
                it.write(42)
                throw IOException("partial copy")
            }
        }
        assertNull(store.read(surveyKey))
        assertEquals(emptyList<String>(), store.artifactIds())
    }

    @Test fun diagnosticRedactionMetadataRemainsPartOfTheImmutableRoute() {
        val store = V2ArtifactStore(temp.root)
        val raw = store.stage("trace-raw", Kind.SURVEY_TRACE, surveyId, profile, paths.surveyTrace("trace-raw")) {
            it.write(1)
        }
        val slm = store.stage("slm-1", Kind.SLM, surveyId, profile, paths.slmTrace("Q8", "eval", "slm-1")) {
            it.write(2)
        }
        val safe = store.stage(
            "trace-redacted", Kind.WHISPER_TRACE, surveyId, profile, paths.whisperTrace("trace-redacted"),
            diagnosticsRedacted = true,
        ) { it.write(3) }
        val reopened = V2ArtifactStore(temp.root)
        assertFalse(checkNotNull(reopened.read(raw.route.id)).route.diagnosticsRedacted)
        assertFalse(checkNotNull(reopened.read(slm.route.id)).route.diagnosticsRedacted)
        assertTrue(checkNotNull(reopened.read(safe.route.id)).route.diagnosticsRedacted)
    }

    @Test fun v2RequestUsesExistingWorkerAndDurableIdentityWithoutCredentials() {
        val entry = survey(V2ArtifactStore(temp.root), "bytes")
        val request = V2ArtifactDelivery.request(entry)
        val data = request.workSpec.input
        assertEquals(GitHubUploadWorker::class.java.name, request.workSpec.workerClassName)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(V2ArtifactDelivery.MODE, data.getString(GitHubUploadWorker.KEY_MODE))
        assertEquals(surveyKey, data.getString(V2ArtifactDelivery.KEY_ARTIFACT))
        assertEquals(entry.routeFingerprint, data.getString(V2ArtifactDelivery.KEY_ROUTE))
        assertNull(data.getString(GitHubUploadWorker.KEY_TOKEN))
        assertEquals(V2ArtifactDelivery.workName(entry), V2ArtifactDelivery.workName(entry.copy()))
    }

    private fun survey(store: V2ArtifactStore, value: String) =
        store.stage(surveyKey, Kind.SURVEY, surveyId, profile, paths.surveyJson()) { it.write(value.toByteArray()) }

    private fun config(repo: String, prefix: String = "") =
        GitHubUploader.GitHubConfig("ishizuki-tech", repo, "test-credential", "main", prefix)
}
