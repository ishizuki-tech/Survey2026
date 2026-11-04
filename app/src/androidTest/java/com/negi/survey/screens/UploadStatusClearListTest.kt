package com.negi.survey.screens

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.negi.survey.net.LegacySurveyRecoveryStore
import com.negi.survey.net.SurveyUploadExecutionAuthority
import com.negi.survey.net.SurveyUploadIdempotency
import com.negi.survey.net.SurveyUploadRouteStore
import com.negi.survey.net.SurveyUploadWork
import com.negi.survey.net.UploadedSurveyStore
import com.negi.survey.vm.UploadStatus
import com.negi.survey.vm.UploadStatusDetails
import com.negi.survey.vm.UploadStatusEntry
import com.negi.survey.vm.UploadStatusViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Real preferences, ViewModel and dialog; scoped data and fake-disabled uploads only. */
class UploadStatusClearListTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val owners = mutableListOf<ViewModelStore>()

    @After fun closeViewModels() {
        instrumentation.runOnMainSync { owners.forEach { it.clear() } }
    }

    @Test fun clearButtonPreservesLedgerPendingLegacyAuthorityAndCanonicalTotal() = runBlocking {
        val app = scopedApp()
        val ledger = UploadedSurveyStore(app)
        confirm(ledger, "confirmed")
        ledger.markUploaded("historical", "2026-11-03")
        val pendingId = "issue122-pending-${UUID.randomUUID()}"
        val pending = File(app.filesDir, "pending_uploads/pending.json").apply {
            parentFile!!.mkdirs()
            writeText("""{"survey_id":"$pendingId","exported_at":"2026-11-03"}""")
        }
        assertTrue(LegacySurveyRecoveryStore(app).record(pendingId, pending.name, "MANUAL_REVIEW"))
        assertTrue(SurveyUploadRouteStore(app).reserveCanonical(pendingId))
        val authority = SurveyUploadExecutionAuthority(app)
        assertEquals(SurveyUploadExecutionAuthority.Claim.AUTHORIZED,
            authority.claim(pendingId, SurveyUploadExecutionAuthority.Protocol.LEGACY))
        val ledgerBefore = app.getSharedPreferences("uploaded_survey_ledger_v1", Context.MODE_PRIVATE).all
        val vm = viewModel(app)
        val before = vm.loadDetails()
        val manager = WorkManager.getInstance(app)
        val workName = requireNotNull(SurveyUploadWork.logicalWorkName(pendingId))
        val workBefore = manager.getWorkInfosForUniqueWork(workName).get().map { it.id to it.state }
        show(vm)

        composeRule.onNodeWithTag("UploadStatusArea").performClick()
        composeRule.onNodeWithText("Completed (2)").assertIsDisplayed()
        composeRule.onNodeWithTag("ClearCompletedUploadsButton").performClick()
        waitForText("Completed (0)")
        composeRule.onNodeWithText("Pending (1)").assertIsDisplayed()
        composeRule.onNodeWithText("Completed uploads: 2").assertExists()
        val after = vm.loadDetails()
        assertEquals(before.pending, after.pending)
        assertEquals(before.deferredLegacyRecovery, after.deferredLegacyRecovery)
        assertTrue(pending.exists())
        assertEquals(ledgerBefore, app.getSharedPreferences("uploaded_survey_ledger_v1", Context.MODE_PRIVATE).all)
        assertEquals(SurveyUploadExecutionAuthority.Owner.VALID(SurveyUploadExecutionAuthority.Protocol.LEGACY), authority.ownerFor(pendingId))
        assertEquals(SurveyUploadWork.SurveyUploadProtocol.CANONICAL_IMMUTABLE_V1, SurveyUploadRouteStore(app).protocolFor(pendingId))
        assertEquals(workBefore, manager.getWorkInfosForUniqueWork(workName).get().map { it.id to it.state })
        assertEquals(2, ledger.uploadedCount())
        assertTrue(ledger.isUploaded("confirmed"))
        assertEquals("2026-11-03", ledger.exportedAtFor("confirmed"))
        assertEquals(fingerprint, ledger.payloadFingerprintFor("confirmed"))
        assertEquals(null, ledger.payloadFingerprintFor("historical"))
        // Reopening the dialog and recreating the ViewModel retain independent UI/ledger state.
        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithTag("UploadStatusArea").performClick()
        waitForText("Completed (0)")
        val recreated = viewModel(app)
        assertTrue(recreated.loadDetails().completed.isEmpty())
        confirm(ledger, "new-legacy-id")
        assertEquals(setOf("new-legacy-id"), recreated.loadDetails().completedSurveyIds)
        assertEquals(3, ledger.uploadedCount())
    }

    @Test fun dialogSnapshotDoesNotHideCompletionThatArrivesBeforeClearClick() = runBlocking {
        val app = scopedApp()
        val ledger = UploadedSurveyStore(app)
        confirm(ledger, "shown")
        val vm = viewModel(app)
        show(vm)
        composeRule.onNodeWithTag("UploadStatusArea").performClick()
        composeRule.onNodeWithText("Completed (1)").assertIsDisplayed()
        confirm(ledger, "completed-after-dialog-opened")
        composeRule.onNodeWithTag("ClearCompletedUploadsButton").performClick()
        composeRule.onNodeWithText("Completed (1)").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5000) {
            runBlocking { vm.loadDetails().completedSurveyIds == setOf("completed-after-dialog-opened") }
        }
        assertEquals(setOf("completed-after-dialog-opened"), vm.loadDetails().completedSurveyIds)
        assertEquals(2, ledger.uploadedCount())
    }

    @Test fun clearFailureIsShownAndDisplayedRowsRemain() {
        val details = UploadStatusDetails(emptyList(), listOf(UploadStatusEntry("shown", null, null)),
            completedSurveyIds = setOf("shown"))
        composeRule.setContent {
            MaterialTheme {
                UploadStatusSummary(UploadStatus(uploadedCount = 1), { details },
                    { throw IllegalStateException("Injected failed visibility commit") })
            }
        }
        composeRule.onNodeWithTag("UploadStatusArea").performClick()
        composeRule.onNodeWithTag("ClearCompletedUploadsButton").performClick()
        waitForText("Could not clear completed list. Please try again.")
        composeRule.onNodeWithText("Completed (1)").assertIsDisplayed()
    }

    @Test fun processRestart_writeHiddenState() = runBlocking {
        val app = restartApp()
        val ledger = UploadedSurveyStore(app)
        confirm(ledger, "persisted-confirmed")
        ledger.markUploaded("persisted-historical", "2026-11-03")
        val vm = viewModel(app)
        vm.clearCompletedSurveys(vm.loadDetails().completedSurveyIds)
        assertTrue(vm.loadDetails().completed.isEmpty())
        assertTrue(app.getSharedPreferences("restart_probe", Context.MODE_PRIVATE).edit()
            .putInt("writer_pid", Process.myPid()).commit())
    }

    @Test fun processRestart_readHiddenStateAndLedgerInNewProcess() = runBlocking {
        val app = restartApp()
        val priorPid = app.getSharedPreferences("restart_probe", Context.MODE_PRIVATE).getInt("writer_pid", -1)
        assertTrue("Run the write phase first", priorPid > 0)
        assertNotEquals("Read phase must use another process", priorPid, Process.myPid())
        val ledger = UploadedSurveyStore(app)
        val vm = viewModel(app)
        assertTrue(vm.loadDetails().completed.isEmpty())
        assertEquals(2, ledger.uploadedCount())
        assertEquals("2026-11-03", ledger.exportedAtFor("persisted-confirmed"))
        assertEquals(fingerprint, ledger.payloadFingerprintFor("persisted-confirmed"))
        assertTrue(ledger.isUploaded("persisted-historical"))
        assertEquals(null, ledger.payloadFingerprintFor("persisted-historical"))
        confirm(ledger, "new-after-process-restart")
        assertEquals(setOf("new-after-process-restart"), vm.loadDetails().completedSurveyIds)
        assertEquals(3, ledger.uploadedCount())
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun show(vm: UploadStatusViewModel) {
        composeRule.setContent {
            val status by vm.status.collectAsState()
            MaterialTheme { UploadStatusSummary(status, vm::loadDetails, vm::clearCompletedSurveys) }
        }
    }

    private fun viewModel(app: Application): UploadStatusViewModel {
        // The scoped Application deliberately has no WorkManager configuration of its own.
        WorkManager.getInstance(instrumentation.targetContext)
        lateinit var vm: UploadStatusViewModel
        instrumentation.runOnMainSync {
            vm = UploadStatusViewModel(app)
            owners += ViewModelStore().apply { put("upload-status", vm) }
        }
        return vm
    }

    private fun scopedApp() = ScopedApplication(instrumentation.targetContext, UUID.randomUUID().toString())
    private fun restartApp(): Application {
        val session = InstrumentationRegistry.getArguments().getString("issue122RestartSession").orEmpty()
        assumeTrue("Separate process phases require an explicit session", session.isNotBlank())
        require(session.matches(Regex("[A-Za-z0-9_-]{8,64}")))
        return ScopedApplication(instrumentation.targetContext, session)
    }

    private val fingerprint = SurveyUploadIdempotency.payloadFingerprint("issue122 confirmed bytes")
    private fun confirm(ledger: UploadedSurveyStore, id: String) = ledger.markConfirmedUploaded(id, "2026-11-03", fingerprint)

    /** Isolates test preferences/files without replacing the app's WorkManager or production stores. */
    private class ScopedApplication(private val base: Context, private val namespace: String) : Application() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(base.filesDir, "issue122_$namespace").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences("issue122_${namespace}_$name", mode)
    }
}
