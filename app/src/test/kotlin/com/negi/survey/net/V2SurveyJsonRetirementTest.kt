package com.negi.survey.net

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.negi.survey.net.V2SurveyJsonRetirement.Result
import com.negi.survey.net.V2SurveyJsonRouteStore.ReadResult
import com.negi.survey.net.V2SurveyJsonStager.Result.Ready
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.vm.SurveyFinalizationSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class V2SurveyJsonRetirementTest {
    @get:Rule val files = TemporaryFolder()

    private val id = "01a1b874-6bff-7abc-9def-0123456789ab"
    private val destination = V2SurveyJsonDestination(
        V2SurveyJsonDestination.Identity.Development, "ishizuki-tech", "SurveyExports-Dev", "main", "frozen"
    )
    private var historicalUploaded = false
    private var owner: String? = null
    private var authoritySyncSucceeds = true
    private val prefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val store get() = V2SurveyJsonRouteStore(files.root)
    private val ledger get() = V2SurveyJsonLedger(files.root) { historicalUploaded }
    private val authority = SurveyUploadExecutionAuthority(prefs)

    init {
        every { prefs.getString(any(), null) } answers { owner }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers { owner = secondArg(); editor }
        every { editor.remove(any()) } answers { owner = null; editor }
        every { editor.commit() } answers { authoritySyncSucceeds }
    }

    @Test
    fun confirmedPendingRetiresBeforeDeletionAndPreservesFrozenRouteAndLedger() = runBlocking {
        val staged = stage()
        val routeBytes = pending(staged).readBytes()
        val ledgerBytes = evidence().readBytes()
        val unrelated = files.newFile("voice.wav").apply { writeText("outside V2 staging") }
        var deletions = 0
        val cleanup = retirement(delete = { file ->
            assertFalse(pending(staged).exists())
            assertArrayEquals(routeBytes, delivered(staged).readBytes())
            assertEquals(staged.file.toPath(), file)
            Files.delete(file)
            deletions++
        })
        repeat(2) {
            assertEquals(Result.Completed(staged.route.remoteTargetPath), cleanup.retire(id, metadata(staged)))
        }
        assertEquals(1, deletions)
        assertArrayEquals(routeBytes, delivered(staged).readBytes())
        assertArrayEquals(ledgerBytes, evidence().readBytes())
        assertEquals("outside V2 staging", unrelated.readText())
        assertFalse(staged.file.exists())
        assertEquals(ReadResult.Delivered(staged.route), store.read(id))
    }

    @Test
    fun unconfirmedPendingAndDeliveredRoutesKeepTheirJson() = runBlocking {
        val staged = stage(confirmed = false)
        assertBlocked(staged)
        assertTrue(pending(staged).isFile)
        store.removeAfterConfirmedDelivery(staged.route)
        assertBlocked(staged)
        assertTrue(delivered(staged).isFile)
        assertTrue(staged.file.isFile)
    }

    @Test
    fun changedLedgerAndConflictingV1EvidenceBlockCleanup() = runBlocking {
        val staged = stage()
        historicalUploaded = true
        assertBlocked(staged)
        assertTrue(pending(staged).isFile)
        historicalUploaded = false
        store.removeAfterConfirmedDelivery(staged.route)
        evidence().writeText(evidence().readText().replace("frozen/", "different/"))
        assertBlocked(staged)
        assertTrue(staged.file.isFile)
        assertTrue(delivered(staged).isFile)
        assertTrue(evidence().readText().contains("different/"))
    }

    @Test
    fun heldOrCorruptAuthorityIsPreservedAndFailedDurabilityRetries() = runBlocking {
        val staged = stage()
        assertEquals(SurveyUploadExecutionAuthority.Claim.AUTHORIZED, authority.claimV2(staged.route))
        val pin = owner
        assertBlocked(staged)
        assertEquals(pin, owner)
        owner = "CANONICAL"
        assertBlocked(staged)
        assertEquals("CANONICAL", owner)
        owner = "corrupt"
        assertBlocked(staged)
        assertEquals("corrupt", owner)
        owner = null
        authoritySyncSucceeds = false
        assertTrue(retirement().retire(id, metadata(staged)) is Result.Retry)
        assertTrue(pending(staged).isFile)
        assertTrue(staged.file.isFile)
    }

    @Test
    fun pendingWithoutJsonIsBlockedButConfirmedDeliveredWithoutJsonCompletes() = runBlocking {
        val staged = stage()
        assertTrue(staged.file.delete())
        assertBlocked(staged)
        assertTrue(pending(staged).isFile)
        store.removeAfterConfirmedDelivery(staged.route)
        assertTrue(retirement(delete = { error("Missing file cannot be deleted") })
            .retire(id, metadata(staged)) is Result.Completed)
        assertTrue(delivered(staged).isFile)
        assertTrue(evidence().isFile)
    }

    @Test
    fun deliveredValidJsonIsCleanedAndModifiedJsonIsRetained() = runBlocking {
        val staged = stage()
        store.removeAfterConfirmedDelivery(staged.route)
        val original = staged.file.readBytes()
        staged.file.appendText(" ")
        val changed = staged.file.readBytes()
        assertBlocked(staged)
        assertArrayEquals(changed, staged.file.readBytes())
        staged.file.writeBytes(original)
        assertTrue(retirement().retire(id, metadata(staged)) is Result.Completed)
        assertFalse(staged.file.exists())
    }

    @Test
    fun corruptAndDuplicateRouteRecordsAreRetained() = runBlocking {
        val staged = stage()
        val bytes = pending(staged).readBytes()
        pending(staged).writeText("{")
        assertBlocked(staged)
        assertEquals("{", pending(staged).readText())
        pending(staged).writeBytes(bytes)
        pending(staged).copyTo(delivered(staged))
        assertBlocked(staged)
        assertTrue(pending(staged).isFile)
        assertTrue(delivered(staged).isFile)
        assertTrue(staged.file.isFile)
    }

    @Test
    fun unexpectedFilesBlockDestructionInPendingAndDeliveredStates() = runBlocking {
        val staged = stage()
        val extra = File(staged.file.parentFile, "unexpected.tmp").apply { writeText("keep") }
        assertBlocked(staged)
        assertTrue(pending(staged).isFile)
        store.removeAfterConfirmedDelivery(staged.route)
        assertBlocked(staged)
        assertTrue(staged.file.isFile)
        assertEquals("keep", extra.readText())
    }

    @Test
    fun failedRoutePublicationPreservesPendingAndNextAttemptCompletes() = runBlocking {
        val staged = stage()
        val failed = retirement(publish = { _, _ -> throw IOException("publish failed") })
        assertTrue(failed.retire(id, metadata(staged)) is Result.Retry)
        assertTrue(pending(staged).isFile)
        assertFalse(delivered(staged).exists())
        assertTrue(staged.file.isFile)
        assertTrue(retirement().retire(id, metadata(staged)) is Result.Completed)
    }

    @Test
    fun deletionFailureLeavesDeliveredEvidenceAndNextAttemptCompletes() = runBlocking {
        val staged = stage()
        val failed = retirement(delete = { throw IOException("unlink failed") })
        assertTrue(failed.retire(id, metadata(staged)) is Result.Retry)
        assertTrue(staged.file.isFile)
        assertTrue(delivered(staged).isFile)
        assertTrue(retirement().retire(id, metadata(staged)) is Result.Completed)
        assertTrue(evidence().isFile)
    }

    @Test
    fun failedPreDeleteSyncRetainsJsonEvenWithVisibleDeliveredRoute() = runBlocking {
        val staged = stage()
        val failed = retirement(sync = { throw IOException("directory sync failed") })
        assertTrue(failed.retire(id, metadata(staged)) is Result.Retry)
        assertTrue(delivered(staged).isFile)
        assertTrue(staged.file.isFile)
        assertTrue(retirement().retire(id, metadata(staged)) is Result.Completed)
    }

    @Test
    fun mismatchedMetadataAndExternalSymlinkCannotAuthorizeDeletion() = runBlocking {
        val staged = stage()
        val outside = files.newFile("external.json").apply { writeBytes(staged.file.readBytes()) }
        val invalid = metadata(staged) + (V2SurveyJsonWork.KEY_FILE_IDENTITY to outside.absolutePath)
        assertTrue(retirement().retire(id, invalid) is Result.Blocked)
        assertTrue(retirement().retire(id.uppercase(), metadata(staged)) is Result.Blocked)
        assertTrue(staged.file.delete())
        Files.createSymbolicLink(staged.file.toPath(), outside.toPath())
        assertBlocked(staged)
        assertTrue(Files.isSymbolicLink(staged.file.toPath()))
        assertTrue(outside.isFile)
        assertTrue(pending(staged).isFile)
    }

    @Test
    fun competingOrUnknownWorkBlocksDestructionBeforeAndAfterRetirement() = runBlocking {
        val staged = stage()
        val competing = retirement(active = { true })
        assertTrue(competing.retire(id, metadata(staged)) is Result.Retry)
        assertTrue(pending(staged).isFile)
        assertTrue(staged.file.isFile)
        store.removeAfterConfirmedDelivery(staged.route)
        assertTrue(competing.retire(id, metadata(staged)) is Result.Retry)
        assertTrue(retirement(active = { error("WorkManager unavailable") })
            .retire(id, metadata(staged)) is Result.Retry)
        assertTrue(staged.file.isFile)
    }

    @Test
    fun changedEvidenceAfterPublicationBlocksDeletion() = runBlocking {
        val staged = stage()
        val cleanup = retirement(publish = { entry, route ->
            entry.removeAfterConfirmedDelivery(route)
            evidence().writeText("{")
        }, delete = { error("Conflicting evidence must retain the JSON") })
        assertTrue(cleanup.retire(id, metadata(staged)) is Result.Blocked)
        assertTrue(delivered(staged).isFile)
        assertTrue(staged.file.isFile)
    }

    @Test
    fun retirementWaitsForTheCompetingInProcessTransportToFinish() = runBlocking {
        val staged = stage(confirmed = false)
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val delivery = V2SurveyJsonDelivery(
            V2SurveyJsonPreflight(files.root, true), authority, ledger,
            { GitHubUploader.GitHubConfig(destination.owner, destination.repository, "test", "main", "frozen") },
            V2SurveyJsonTransport { _, _, _, _ ->
                entered.complete(Unit)
                proceed.await()
            }
        )
        val sending = async { delivery.execute(metadata(staged)) }
        entered.await()
        val cleaning = async(start = CoroutineStart.UNDISPATCHED) { retirement().retire(id, metadata(staged)) }
        assertFalse(cleaning.isCompleted)
        assertTrue(staged.file.isFile)
        assertTrue(pending(staged).isFile)
        proceed.complete(Unit)
        assertTrue(sending.await() is V2SurveyJsonDelivery.Result.Confirmed)
        assertTrue(cleaning.await() is Result.Completed)
    }

    @Test
    fun workerRetryAfterDeliveredPublicationSkipsDeliveryAndRequiresCleanup() = runBlocking {
        val staged = stage()
        val input = V2SurveyJsonWork.buildRequest(files.root, staged.route, staged.file).workSpec.input
        val interrupted = retirement(publish = { entry, route ->
            entry.removeAfterConfirmedDelivery(route)
            throw IOException("interrupted after retirement publication")
        })
        mockkStatic(Log::class)
        try {
            every { Log.i(any(), any()) } returns 0
            assertEquals(ListenableWorker.Result.retry(), worker(input, interrupted).doWork())
            assertTrue(delivered(staged).isFile)
            assertTrue(staged.file.isFile)
            assertTrue(worker(input, retirement()).doWork() is ListenableWorker.Result.Success)
            assertFalse(staged.file.exists())
            assertTrue(worker(input, retirement(delete = { error("Must not delete twice") }))
                .doWork() is ListenableWorker.Result.Success)
        } finally {
            unmockkStatic(Log::class)
        }
    }

    @Test
    fun workerRetryAfterUnlinkBeforeSyncSucceedsWithoutTransportOrSecondDeletion() = runBlocking {
        val staged = stage()
        val input = V2SurveyJsonWork.buildRequest(files.root, staged.route, staged.file).workSpec.input
        val interrupted = retirement(sync = {
            if (!staged.file.exists()) throw IOException("interrupted after unlink")
            syncDirectory(it)
        })
        mockkStatic(Log::class)
        try {
            every { Log.i(any(), any()) } returns 0
            assertEquals(ListenableWorker.Result.retry(), worker(input, interrupted).doWork())
            assertFalse(staged.file.exists())
            assertTrue(delivered(staged).isFile)
            assertTrue(evidence().isFile)
            assertTrue(worker(input, retirement(delete = { error("Must not delete twice") }))
                .doWork() is ListenableWorker.Result.Success)
        } finally {
            unmockkStatic(Log::class)
        }
    }

    private fun stage(confirmed: Boolean = true): Ready {
        val staged = V2SurveyJsonStager(store).stage(
            SurveyFinalizationSnapshot(id, emptyMap(), mapOf("Q11" to "café"), emptyMap(), emptyList(), "{}", emptyMap()),
            DeviceUploadTag("Pixel_test"), destination, "2026-11-10_12-00-00"
        ) as Ready
        if (confirmed) ledger.recordConfirmed(staged.route)
        return staged
    }

    private fun metadata(staged: Ready) = V2SurveyJsonWork.metadata(staged.route)
    private fun pending(staged: Ready) = File(staged.file.parentFile, V2_PENDING_ROUTE)
    private fun delivered(staged: Ready) = File(staged.file.parentFile, V2_DELIVERED_ROUTE)
    private fun evidence() = File(files.root, "${V2SurveyJsonLedger.NAMESPACE}/$id.json")

    private suspend fun assertBlocked(staged: Ready) {
        assertTrue(retirement(delete = { error("Blocked state must not delete") })
            .retire(id, metadata(staged)) is Result.Blocked)
    }

    private fun retirement(
        active: (String) -> Boolean = { false },
        publish: (V2SurveyJsonRouteStore.Entry, V2SurveyJsonRoute) -> Unit =
            { entry, route -> entry.removeAfterConfirmedDelivery(route) },
        delete: (Path) -> Unit = { Files.delete(it) },
        sync: (Path) -> Unit = ::syncDirectory
    ) = V2SurveyJsonRetirement(store, ledger, authority, active, publish, delete, sync)

    private fun worker(input: androidx.work.Data, retirement: V2SurveyJsonRetirement): GitHubUploadWorker {
        val params = mockk<WorkerParameters>()
        every { params.inputData } returns input
        every { params.id } returns UUID.randomUUID()
        return GitHubUploadWorker(mockk<Context>(), params,
            v2Delivery = { error("Confirmed cleanup must never invoke delivery") },
            v2Retirement = { _, _ -> retirement })
    }
}
