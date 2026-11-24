package com.negi.survey.slm

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.negi.survey.vm.AiViewModel
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import kotlin.reflect.KClass

/**
 * Production callback guard/dispatcher -> extracted repository entry/trySend ->
 * real callbackFlow -> real AiViewModel. The Repository adapter supplies transport
 * only; LiteRtRepository.request's native driver, gate and recovery are not executed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue201ChannelViewModelIntegrationTest {
    @Test fun queued_old_callback_cannot_change_successor_viewmodel_through_channel_boundary() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var mainInstalled = false
        val staticMocks = mutableListOf<KClass<*>>()
        var aiToCleanUp: AiViewModel? = null
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
            Dispatchers.setMain(dispatcher)
            mainInstalled = true
            FollowupExtractor.hashCode()
            for (type in listOf(Log::class, SystemClock::class, Process::class, FollowupExtractor::class)) {
                // Register before installation so a partially failed mock install is released.
                staticMocks += type
                mockkStatic(type)
            }
            val requests = mutableListOf<Issue201ChannelHarness>()
            val repo = object : Repository {
                override fun buildPrompt(userPrompt: String) = userPrompt
                override suspend fun request(prompt: String, traceContext: RepositoryTraceContext?): Flow<String> =
                    Issue201ChannelHarness().also(requests::add).flow
            }
            val ai = AiViewModel(repo, ioDispatcher = dispatcher).also { aiToCleanUp = it }
            every { Log.println(any(), any(), any()) } returns 0
            every { Log.getStackTraceString(any()) } answers { firstArg<Throwable>().stackTraceToString() }
            every { Process.myPid() } returns 1
            every { Process.myTid() } returns 1
            every { SystemClock.elapsedRealtime() } returns 0L
            every { SystemClock.uptimeMillis() } returns 0L
            // As in Step 2, Android org.json extraction is outside this ownership test.
            every { FollowupExtractor.extractScore(any()) } returns 95
            every { FollowupExtractor.fromRaw(any(), any()) } returns emptyList()
            every { FollowupExtractor.extractFollowupQuestion(any()) } answers { firstArg<String>() }

            val first = ai.evaluateAsync("Synthetic A")
            runCurrent()
            val a = requests.single()
            assertTrue(a.bound.isCompleted)
            val queue = ArrayDeque<() -> Unit>()
            val runId = AtomicLong(1L)
            val owner = LiteRtCallbackOwner(
                runId, AtomicBoolean(false), AtomicBoolean(false), AtomicBoolean(false), AtomicLong(0L), 1L,
                dispatch = { queue.addLast(it) },
            )
            val callback = owner.callback(
                onAcceptedMessage = { owner.postPartial("obsolete synthetic A text", a.listener) },
                onCurrentDone = {}, onCurrentError = {},
            )
            callback.onMessage(Message.user(Contents.of("synthetic")))
            assertEquals(1, queue.size)
            ai.resetStates()
            val successor = ai.evaluateAsync("Synthetic B")
            runCurrent()
            assertTrue(first.isCancelled)
            assertTrue(a.collectorClosed.get())
            assertEquals(2, requests.size)
            val b = requests.last()
            assertTrue(b.bound.isCompleted)
            assertTrue(successor.isActive)
            val streamBefore = ai.stream.value
            val historyBefore = ai.stepHistory.value
            val errorBefore = ai.error.value
            val loadingBefore = ai.loading.value
            runId.incrementAndGet()
            queue.removeFirst().invoke()
            runCurrent()
            assertEquals(1, a.listenerInvocations)
            assertEquals(0, a.processingInvocations)
            assertTrue(a.sendResults.isEmpty())
            assertTrue(b.sendResults.isEmpty())
            assertEquals(streamBefore, ai.stream.value)
            assertEquals(historyBefore, ai.stepHistory.value)
            assertEquals(errorBefore, ai.error.value)
            assertEquals(loadingBefore, ai.loading.value)
            assertTrue(successor.isActive)

            val response = """{"score":95,"missing_points":[],"followup_needed":false}"""
            b.listener(response, false)
            assertEquals(listOf(true), b.sendResults)
            b.output.close()
            runCurrent()
            assertTrue(successor.isCompleted)
            assertEquals(response, ai.raw.value)
            assertEquals(95, ai.score.value)
            assertEquals(response, ai.stepHistory.value.single().raw)
        } catch (testFailure: Throwable) {
            failure = testFailure
        } finally {
            cleanup { aiToCleanUp?.resetStates() }
            cleanup { runCurrent() }
            staticMocks.asReversed().forEach { type -> cleanup { unmockkStatic(type) } }
            if (mainInstalled) cleanup { Dispatchers.resetMain() }
        }
        failure?.let { throw it }
    }
}
