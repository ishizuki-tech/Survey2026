package com.negi.survey

import android.net.ConnectivityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import com.negi.survey.net.GitHubUploader
import com.negi.survey.net.PendingSurveyUploads
import com.negi.survey.net.SurveyFinalizationResult
import com.negi.survey.net.SurveyUploadFinalizer
import com.negi.survey.net.SurveyUploadWork
import com.negi.survey.screens.ConfigDetails
import com.negi.survey.screens.ConfigOptionUi
import com.negi.survey.screens.IntroScreen
import com.negi.survey.slm.Repository
import com.negi.survey.slm.RepositoryTraceContext
import com.negi.survey.ui.theme.SurveyNavTheme
import com.negi.survey.utils.DeviceUploadTag
import com.negi.survey.utils.newTimeOrderedSurveyId
import com.negi.survey.vm.AiViewModel
import com.negi.survey.vm.FlowHome
import com.negi.survey.vm.SurveyAiReason
import com.negi.survey.vm.SurveyCompletionStatus
import com.negi.survey.vm.SurveyFinalizationSnapshot
import com.negi.survey.vm.SurveySessionStore
import com.negi.survey.vm.SurveyViewModel
import com.negi.survey.vm.UploadStatus
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class SurveyNoteExitTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val surveyIds = mutableListOf<String>()
    private lateinit var survey: SurveyViewModel

    @After
    fun removeTestExports() {
        File(context.filesDir, "pending_uploads").listFiles().orEmpty()
            .filter { PendingSurveyUploads.surveyIdFromFile(it) in surveyIds }
            .forEach { it.delete() }
    }

    @Test
    fun directExitSavesDraftAndNextStartHasAFreshUuid() {
        showSurveySelector()
        val first = startSurvey()
        rule.onNode(hasSetTextAction()).performTextReplacement("Unactivated note")
        rule.onNodeWithText("Exit").performClick()
        waitForSelector()

        val record = pendingExport(first)
        assertEquals("incomplete", record.string("status"))
        assertEquals("Unactivated note", record.getValue("meta").jsonObject.string("session_free_text"))
        assertTrue(record.getValue("answers").jsonObject.isEmpty())

        val second = startSurvey()
        assertNotEquals(first, second)
        rule.runOnIdle {
            assertTrue(survey.runFreeText.value.isEmpty())
            assertTrue(survey.answers.value.isEmpty())
            assertTrue(survey.getAudioRefsForRunFlat().isEmpty())
        }
        assertEquals(record, pendingExport(first))
    }

    @Test
    fun nextAndQuestionBackKeepIdentityAndExitPreservesData() {
        showSurveySelector()
        val id = startSurvey()
        rule.onNode(hasSetTextAction()).performTextReplacement("Original note")
        rule.runOnIdle { survey.addAudioRef("__home_free_text__", "home-note.wav") }
        rule.onNodeWithText("Next").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("First answer")
        rule.runOnIdle {
            assertEquals("Q1", survey.currentNodeId)
            assertEquals(id, survey.surveyUuid.value)
            survey.addFollowupQuestion("Q1", "A follow-up?")
            survey.setAiReason("Q1", SurveyAiReason.ACHIEVED)
        }
        rule.onNodeWithText("Back").performClick()
        rule.runOnIdle {
            assertEquals("Start", survey.currentNodeId)
            assertEquals(id, survey.surveyUuid.value)
            assertEquals("First answer", survey.getAnswer("Q1"))
        }
        assertNull(PendingSurveyUploads.findPendingSurveyFile(context, id))
        rule.onNode(hasSetTextAction()).performTextReplacement("Updated note")
        rule.onNodeWithText("Exit").performClick()
        waitForSelector()

        val record = pendingExport(id)
        assertEquals("incomplete", record.string("status"))
        assertEquals("Updated note", record.getValue("meta").jsonObject.string("session_free_text"))
        assertEquals("First answer", record.getValue("answers").jsonObject.getValue("Q1").jsonObject.string("answer"))
        assertEquals("achieved", record.getValue("ai_outcomes").jsonObject.string("Q1"))
        val followup = record.getValue("followups").jsonObject.getValue("Q1").jsonArray.single().jsonObject
        assertEquals("A follow-up?", followup.string("question"))
        val audio = record.getValue("voice_files").jsonArray.single().jsonObject
        assertEquals(id, audio.string("survey_id"))
        assertEquals("home-note.wav", audio.string("file"))
    }

    @Test
    fun reviewFinishStillAdvancesToDoneWithCompletedExport() {
        showSurveySelector()
        val id = startSurvey()
        rule.onNode(hasSetTextAction()).performTextReplacement("Run note")
        rule.onNodeWithText("Next").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("Completed answer")
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Finish").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Start New Survey")).fetchSemanticsNodes().isNotEmpty() }

        val record = pendingExport(id)
        assertEquals("completed", record.string("status"))
        assertEquals("Run note", record.getValue("meta").jsonObject.string("session_free_text"))
        assertEquals("Completed answer", record.getValue("answers").jsonObject.getValue("Q1").jsonObject.string("answer"))
    }

    @Test
    fun offlineExitQueuesOneNetworkConstrainedRequestAndKeepsItsStagedJson() = runBlocking {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        assumeTrue("Run with the test device offline", connectivity.activeNetwork == null)

        val id = newTimeOrderedSurveyId()
        surveyIds += id
        val snapshot = SurveyFinalizationSnapshot(
            surveyId = id,
            questions = emptyMap(),
            answers = emptyMap(),
            followups = emptyMap(),
            audioRefs = emptyList(),
            aiOutcomesJson = "{}",
            extraMeta = mapOf("session_free_text" to "Offline note"),
            status = SurveyCompletionStatus.INCOMPLETE
        )
        val workName = checkNotNull(SurveyUploadWork.logicalWorkName(id))
        val workManager = WorkManager.getInstance(context)
        val finalizer = SurveyUploadFinalizer(context)
        val config = GitHubUploader.GitHubConfig("example", "offline-fixture", "test-placeholder")
        val tag = DeviceUploadTag("Offline_Test")
        val stamp = "2026-11-07_00-00-00"
        try {
            val staged = finalizer.finalize(snapshot, null, tag, stamp) as SurveyFinalizationResult.StagedPending
            val first = SurveyUploadWork.reconcile(context, config, id, staged.file)
            assertTrue(first.enqueued)
            val work = workManager.getWorkInfosForUniqueWork(workName).get(10, TimeUnit.SECONDS).single()
            assertEquals(WorkInfo.State.ENQUEUED, work.state)
            assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
            assertEquals("incomplete", pendingExport(id).string("status"))

            val second = SurveyUploadWork.reconcile(context, config, id, staged.file)
            assertEquals(SurveyUploadWork.SurveyWorkAction.KEEP_TRACKED, second.decision.action)
            val remaining = workManager.getWorkInfosForUniqueWork(workName).get(10, TimeUnit.SECONDS).single()
            assertEquals(work.id, remaining.id)
            assertEquals(WorkInfo.State.ENQUEUED, remaining.state)
            assertEquals("Offline note", pendingExport(id).getValue("meta").jsonObject.string("session_free_text"))
        } finally {
            workManager.cancelUniqueWork(workName).result.get(10, TimeUnit.SECONDS)
        }
        Unit
    }

    private fun showSurveySelector() {
        val sessions = SurveySessionStore()
        val config = SurveyConfig(
            graph = SurveyConfig.Graph(
                startId = "Start",
                nodes = listOf(
                    NodeDTO("Start", "START", title = "Survey Note", nextId = "Q1"),
                    NodeDTO("Q1", "TEXT", title = "Question", nextId = "Review"),
                    NodeDTO("Review", "REVIEW", nextId = "Done"),
                    NodeDTO("Done", "DONE")
                )
            )
        )
        rule.setContent {
            var epoch by remember { mutableIntStateOf(0) }
            var active by remember { mutableIntStateOf(0) }
            SurveyNavTheme {
                if (active == 0) {
                    IntroScreen(
                        options = listOf(ConfigOptionUi("fixture", "Test survey", "Survey exit test")),
                        uploadStatus = UploadStatus(),
                        onUploadStatusActive = {},
                        onStart = { active = ++epoch },
                        onResolveConfigDetails = { ConfigDetails("Test survey", "", "") },
                        onResolveUploadStatusDetails = { error("Unused") },
                        onClearCompletedSurveys = {}
                    )
                } else {
                    val key = "exit-test@$active"
                    val nav = rememberNavBackStack(FlowHome)
                    val owner = remember(key) { sessions.ownerFor(key) }
                    val current: SurveyViewModel = viewModel(owner, factory = viewModelFactory {
                        initializer { SurveyViewModel(nav, config) }
                    })
                    val ai: AiViewModel = viewModel(owner, factory = viewModelFactory {
                        initializer { AiViewModel(UnusedRepository) }
                    })
                    survey = current
                    SurveyNavHost(
                        vmSurvey = current,
                        vmAI = ai,
                        backStack = nav,
                        onResetToSelector = {
                            sessions.clearSession(key)
                            active = 0
                        },
                        onEndSurveySession = {},
                        whisperMeta = SurveyConfig.WhisperMeta(enabled = false),
                        ttsMeta = SurveyConfig.TtsMeta(enabled = false),
                        sessionId = key,
                        sessionVmOwner = owner,
                        onResolveUploadStatusDetails = { error("Unused") },
                        onClearCompletedSurveys = {}
                    )
                }
            }
        }
    }

    private fun startSurvey(): String {
        rule.onNodeWithTag("StartButton").performClick()
        val id = rule.runOnIdle { survey.surveyUuid.value }
        assertEquals(7, UUID.fromString(id).version())
        surveyIds += id
        return id
    }

    private fun waitForSelector() {
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Start")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun pendingExport(id: String): JsonObject {
        val candidates = File(context.filesDir, "pending_uploads").listFiles().orEmpty()
            .filter { PendingSurveyUploads.surveyIdFromFile(it) == id }
        return Json.parseToJsonElement(candidates.single().readText()).jsonObject.also {
            assertEquals(id, it.string("survey_id"))
        }
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

    private object UnusedRepository : Repository {
        override fun buildPrompt(userPrompt: String) = userPrompt
        override suspend fun request(prompt: String, traceContext: RepositoryTraceContext?): Flow<String> =
            error("Unused")
    }
}
