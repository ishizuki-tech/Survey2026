package com.negi.survey.vm

import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import com.negi.survey.slm.FollowupExtractor
import com.negi.survey.slm.Repository
import com.negi.survey.slm.RepositoryTraceContext
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import kotlin.reflect.KClass

/** Real ViewModel/Survey publication guards with a controlled Repository input seam.
 * No LiteRtRepository listener, channel, native callback, or Main Handler is simulated here.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class Issue201ViewModelOwnershipTest {
    private val repositories = mutableListOf<ControlledRepository>()
    private val viewModels = mutableListOf<AiViewModel>()

    @Test fun cancelled_chain_before_collection_never_calls_repository() = scenario {
        val repo = repository()
        val ai = viewModel(repo)
        val survey = survey()
        val job = start(ai, survey)
        ai.resetStates()
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(0, repo.calls.size)
        assertEquals("", ai.stream.value)
        assertTrue(survey.followups.value.isEmpty())
    }

    @Test fun obsolete_evaluation_after_successor_admission_cannot_overwrite_snapshot() =
        lateResult(followup = false, lateError = false)

    @Test fun obsolete_followup_after_successor_admission_cannot_publish_or_finish_successor() =
        lateResult(followup = true, lateError = false)

    @Test fun obsolete_repository_error_cannot_replace_successor_state() =
        lateResult(followup = false, lateError = true)

    @Test fun cancel_after_streamed_chunk_rejects_obsolete_tail_before_retry_collection() =
        lateResult(followup = false, lateError = false, streamFirst = true)

    @Test fun foreign_tail_after_valid_current_chunk_fails_closed_at_survey_admission() = scenario {
        val repo = repository(HIGH)
        val ai = viewModel(repo)
        val survey = survey()
        val originalAnswer = survey.getAnswer(NODE)
        val job = start(ai, survey)
        runCurrent()
        assertTrue(job.isActive)
        assertEquals(HIGH, ai.stream.value)
        val call = repo.calls.single()
        call.response.complete(" synthetic foreign tail")
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(2, call.deliveryAttempts)
        assertEquals(HIGH + " synthetic foreign tail", ai.raw.value)
        assertEquals(SurveyAiReason.FAILURE, survey.aiReasons.value[NODE])
        assertEquals(originalAnswer, survey.getAnswer(NODE))
        assertTrue(survey.followups.value.isEmpty())
        assertEquals(2, survey.remainingFollowups(NODE))
        assertEquals("Malformed evaluation must not start FOLLOWUP", 1, repo.calls.size)
    }

    @Test fun valid_foreign_json_through_current_repository_path_is_accepted_without_provenance() = scenario {
        val repo = repository()
        val ai = viewModel(repo)
        val survey = survey()
        val a = start(ai, survey)
        runCurrent()
        val responseOriginallyForA = HIGH.dropLast(1) + ",\"note\":\"synthetic-request-A\"}"
        repo.calls.single().response.complete(responseOriginallyForA)
        runCurrent()
        assertTrue(a.isCompleted)
        assertEquals(SurveyAiReason.ACHIEVED, survey.aiReasons.value[NODE])

        ai.resetStates()
        survey.setAnswer("Different synthetic answer B", NODE)
        val b = start(ai, survey)
        runCurrent()
        assertEquals(2, repo.calls.size)
        assertNotEquals(repo.calls[0].prompt, repo.calls[1].prompt)
        repo.calls[1].response.complete(responseOriginallyForA)
        runCurrent()
        assertTrue(b.isCompleted)
        assertEquals(responseOriginallyForA, ai.raw.value)
        assertEquals(SurveyAiReason.ACHIEVED, survey.aiReasons.value[NODE])
        assertTrue(survey.followups.value.isEmpty())
        // Acceptance characterizes a provenance limitation, not a violated native contract.
    }

    private fun lateResult(followup: Boolean, lateError: Boolean, streamFirst: Boolean = false) = scenario {
        val repo = repository(if (streamFirst) HIGH.dropLast(1) else null)
        val ai = viewModel(repo)
        val survey = survey()
        val old = start(ai, survey)
        runCurrent()
        assertEquals(1, repo.calls.size)
        if (streamFirst) assertEquals(HIGH.dropLast(1), ai.stream.value)
        if (followup) {
            repo.calls[0].response.complete(LOW)
            runCurrent()
            assertEquals("Old chain must reach FOLLOWUP", 2, repo.calls.size)
        }
        val oldCall = repo.calls.last()
        ai.resetStates()
        survey.setAnswer("Replacement answer B", NODE)
        val successor = start(ai, survey)
        runCurrent()
        assertTrue(successor.isActive)
        // inferenceMutex retains collection ownership until A unwinds. B is the installed
        // UI/chain owner but cannot enter repository collection yet.
        val oldRequestCount = repo.calls.size
        assertSame(oldCall, repo.calls.last())
        val before = survey.createFinalizationSnapshot()
        val stream = ai.stream.value
        val raw = ai.raw.value
        val error = ai.error.value
        val history = ai.stepHistory.value
        val chat = ai.chatHistoryFlow(CONTEXT).value
        val loading = ai.loading.value
        var scenarioFailure: Throwable? = null
        try {
            if (lateError) oldCall.response.completeExceptionally(IllegalStateException("synthetic obsolete error"))
            else oldCall.response.complete(if (followup) "How often was synthetic crop A watered?" else LOW)
            runCurrent()
            assertTrue("Old repository delivery must actually resume", oldCall.resumed)
            assertEquals(if (lateError) 0 else if (streamFirst) 2 else 1, oldCall.deliveryAttempts)
            assertTrue(old.isCompleted)
            assertEquals(oldRequestCount + 1, repo.calls.size)
            val successorCall = repo.calls.last()
            assertNotSame(oldCall, successorCall)
            assertEquals(before, survey.createFinalizationSnapshot())
            assertEquals(stream, ai.stream.value)
            assertEquals(raw, ai.raw.value)
            assertEquals(error, ai.error.value)
            assertEquals(history, ai.stepHistory.value)
            assertEquals(chat, ai.chatHistoryFlow(CONTEXT).value)
            assertEquals(loading, ai.loading.value)
            assertTrue("Old completion must not complete B", successor.isActive)
            successorCall.response.complete(HIGH)
            runCurrent()
            assertTrue(successor.isCompleted)
            assertEquals(SurveyAiReason.ACHIEVED, survey.aiReasons.value[NODE])
            assertTrue(survey.followups.value.isEmpty())
            assertEquals(2, survey.remainingFollowups(NODE))
        } catch (testFailure: Throwable) {
            scenarioFailure = testFailure
            throw testFailure
        } finally {
            // Release non-cooperative fixtures even when an ownership assertion fails.
            // The outer fixture also restores globals if this local cleanup fails.
            try {
                oldCall.response.complete(HIGH)
                repo.calls.forEach { it.response.complete(HIGH) }
                ai.resetStates()
                runCurrent()
            } catch (cleanupFailure: Throwable) {
                val primary = scenarioFailure
                if (primary == null) throw cleanupFailure
                if (primary !== cleanupFailure) primary.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun repository(firstChunk: String? = null) = ControlledRepository(firstChunk).also(repositories::add)

    private fun TestScope.viewModel(repo: Repository) =
        AiViewModel(repo, ioDispatcher = StandardTestDispatcher(testScheduler)).also(viewModels::add)

    private fun start(ai: AiViewModel, survey: SurveyViewModel) =
        ai.evaluateSurveyTwoStepAsync(survey, NODE, CONTEXT, "Synthetic crop question")

    private fun survey() = SurveyViewModel(NavBackStack<NavKey>(FlowHome), SurveyConfig(
        aiInteraction = SurveyConfig.AiInteraction(2),
        graph = SurveyConfig.Graph("Start", listOf(
            NodeDTO("Start", "START", nextId = NODE),
            NodeDTO(NODE, "AI", question = "Synthetic crop question", nextId = "Done"),
            NodeDTO("Done", "DONE"),
        )),
        prompts = listOf(SurveyConfig.Prompt(NODE,
            evalPrompt = "Question: {{QUESTION}} Answer: {{ANSWER}} {{HISTORY}}",
            followupPrompt = "Question: {{QUESTION}} Answer: {{ANSWER}} EVAL: {{EVAL_JSON}}",
        )),
    )).also { it.setAnswer("Synthetic answer A", NODE) }

    private fun scenario(block: suspend TestScope.() -> Unit) = runTest {
        var mainInstalled = false
        val staticMocks = mutableListOf<KClass<*>>()
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (cleanupFailure: Throwable) {
                val primary = failure
                if (primary == null) failure = cleanupFailure
                else if (primary !== cleanupFailure) primary.addSuppressed(cleanupFailure)
            }
        }
        try {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            mainInstalled = true
            FollowupExtractor.hashCode()
            for (type in listOf(Log::class, SystemClock::class, Process::class, FollowupExtractor::class)) {
                // Register before installation so a partially failed mock install is released.
                staticMocks += type
                mockkStatic(type)
            }
            every { Log.println(any(), any(), any()) } returns 0
            every { Log.getStackTraceString(any()) } answers { firstArg<Throwable>().stackTraceToString() }
            every { Process.myPid() } returns 1
            every { Process.myTid() } returns 1
            every { SystemClock.elapsedRealtime() } returns 0L
            every { SystemClock.uptimeMillis() } returns 0L
            // Match existing JVM ownership tests: Android org.json extraction is outside scope.
            // Strict SurveyAiPolicy admission and all ownership/publication logic remain real.
            every { FollowupExtractor.extractScore(any()) } answers {
                runCatching { Json.parseToJsonElement(firstArg<String>()).jsonObject["score"]?.jsonPrimitive?.intOrNull }.getOrNull()
            }
            every { FollowupExtractor.fromRaw(any(), any()) } returns emptyList()
            every { FollowupExtractor.extractFollowupQuestion(any()) } answers { firstArg<String>() }
            block()
        } catch (testFailure: Throwable) {
            failure = testFailure
        } finally {
            repositories.forEach { repo -> repo.calls.forEach { call -> cleanup { call.response.complete(HIGH) } } }
            viewModels.forEach { vm -> cleanup { vm.resetStates() } }
            cleanup { runCurrent() }
            staticMocks.asReversed().forEach { type -> cleanup { unmockkStatic(type) } }
            if (mainInstalled) cleanup { Dispatchers.resetMain() }
        }
        failure?.let { throw it }
    }

    private class Call(val prompt: String) {
        val response = CompletableDeferred<String>()
        var resumed = false
        var deliveryAttempts = 0
    }

    /** Deliberately non-cooperative upstream to exercise the actual collector's guards. */
    private class ControlledRepository(private val firstChunk: String?) : Repository {
        val calls = mutableListOf<Call>()
        override fun buildPrompt(userPrompt: String) = userPrompt
        override suspend fun request(prompt: String, traceContext: RepositoryTraceContext?): Flow<String> =
            object : Flow<String> {
                override suspend fun collect(collector: FlowCollector<String>) {
                    val call = Call(prompt).also(calls::add)
                    if (calls.size == 1 && firstChunk != null) {
                        call.deliveryAttempts++
                        collector.emit(firstChunk)
                    }
                    val text = try {
                        withContext(NonCancellable) { call.response.await() }
                    } finally {
                        call.resumed = true
                    }
                    call.deliveryAttempts++
                    collector.emit(text)
                }
            }
    }

    private companion object {
        const val NODE = "Q8"
        const val CONTEXT = "issue201-synthetic"
        const val LOW = """{"score":30,"missing_points":["synthetic frequency"],"followup_needed":true}"""
        const val HIGH = """{"score":95,"missing_points":[],"followup_needed":false}"""
    }
}
