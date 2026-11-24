package com.negi.survey.slm

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Real extracted repository-entry guard + trySend + callbackFlow transport.
 * Lifecycle outcomes are supplied as preconditions. This does not run
 * LiteRtRepository.request's driver, normalizer, watchdogs, errors or global gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue201RepositoryChannelIntegrationTest {
    @Test fun closed_request_rejects_callbacks_independently_of_collector_and_logical_completion() = runTest {
        val channel = Channel<String>(Channel.BUFFERED)
        val closed = AtomicBoolean(true)
        val collectorClosed = AtomicBoolean(false)
        val logicalDone = AtomicBoolean(false)
        val delivery = RepositoryChannelDelivery(channel, closed, collectorClosed, logicalDone)
        var processing = 0
        var completions = 0
        var sendAttempts = 0
        val listener = delivery.listener { partial, done ->
            processing++
            if (done) completions++
            if (partial.isNotEmpty()) {
                sendAttempts++
                delivery.trySend(partial)
            }
        }
        try {
            listener("obsolete data", false)
            listener("", true)
            assertEquals(0, processing)
            assertEquals(0, completions)
            assertEquals(0, sendAttempts)
            val empty = channel.tryReceive()
            assertTrue(empty.isFailure)
            assertFalse("The channel must remain open", empty.isClosed)
            assertTrue(closed.get())
            assertFalse(collectorClosed.get())
            assertFalse(logicalDone.get())
            // Positive control: neither callback closed or otherwise disabled the channel.
            assertTrue(channel.trySend("open-channel-control").isSuccess)
            assertEquals("open-channel-control", channel.tryReceive().getOrThrow())
        } finally {
            channel.cancel()
        }
    }

    @Test fun completed_request_listener_cannot_emit_or_complete_successor() = runTest {
        val a = Issue201ChannelHarness()
        val first = collect(a)
        a.listener("A-before-completion", false)
        runCurrent()
        assertEquals(listOf("A-before-completion"), a.received)
        a.closed.set(true)
        a.output.close()
        runCurrent()
        assertTrue(first.isCompleted)

        val b = Issue201ChannelHarness()
        val second = collect(b)
        a.listener("obsolete-A", false)
        a.listener("", true)
        runCurrent()
        assertEquals(listOf("A-before-completion"), a.received)
        assertEquals(listOf(true), a.sendResults)
        assertEquals(0, a.processedDone)
        assertTrue(b.received.isEmpty())
        assertTrue(second.isActive)
        b.listener("B-current", false)
        runCurrent()
        assertEquals(listOf("B-current"), b.received)
    }

    @Test fun cancelled_collector_closure_rejects_pending_listener_before_try_send() = runTest {
        val a = Issue201ChannelHarness()
        val first = collect(a)
        first.cancelAndJoin()
        assertTrue(a.collectorClosed.get())
        val b = Issue201ChannelHarness()
        val second = collect(b)
        a.listener("obsolete-A", false)
        runCurrent()
        assertEquals(1, a.listenerInvocations)
        assertEquals(0, a.processingInvocations)
        assertTrue(a.sendResults.isEmpty())
        assertTrue(a.received.isEmpty())
        assertTrue(b.received.isEmpty())
        assertTrue(second.isActive)
    }

    @Test fun queued_callback_reaches_old_listener_but_not_old_or_successor_flow() = runTest {
        val a = Issue201ChannelHarness()
        val first = collect(a)
        val queue = ArrayDeque<() -> Unit>()
        val chronology = mutableListOf<String>()
        val runId = AtomicLong(1L)
        val owner = callbackOwner(runId) { queue.addLast(it); chronology += "queued" }
        val callback = owner.callback(
            onAcceptedMessage = {
                chronology += "callback-accepted"
                owner.postPartial("obsolete-A") { text, done ->
                    chronology += "old-listener-invoked"
                    a.listener(text, done)
                }
            }, onCurrentDone = {}, onCurrentError = {},
        )
        callback.onMessage(message)
        assertEquals(listOf("callback-accepted", "queued"), chronology)
        assertEquals(0, a.listenerInvocations)
        first.cancelAndJoin()
        assertTrue(a.collectorClosed.get())
        runId.incrementAndGet()
        val b = Issue201ChannelHarness()
        val second = collect(b)
        chronology += "dispatch-executed"
        queue.removeFirst().invoke()
        runCurrent()
        assertEquals(listOf("callback-accepted", "queued", "dispatch-executed", "old-listener-invoked"), chronology)
        assertEquals(1, a.listenerInvocations)
        assertEquals(0, a.processingInvocations)
        assertTrue("Rejected at entry: no channel send was attempted", a.sendResults.isEmpty())
        assertTrue(a.received.isEmpty())
        assertTrue(b.received.isEmpty())
        assertTrue(second.isActive)
        b.listener("B", false)
        runCurrent()
        assertEquals(listOf("B"), b.received)
    }

    @Test fun partial_before_logical_completion_is_delivered_and_later_partial_is_rejected() = runTest {
        val a = Issue201ChannelHarness()
        collect(a)
        a.listener("accepted", false)
        runCurrent()
        assertEquals(listOf("accepted"), a.received)
        // Supply the outcome of the driver's logical-completion transition.
        a.logicalDone.set(true)
        a.listener("late", false)
        a.listener("", true)
        runCurrent()
        assertEquals(listOf("accepted"), a.received)
        assertEquals(1, a.processingInvocations)
        assertEquals(0, a.processedDone)
    }

    @Test fun logical_completion_before_queued_release_prevents_channel_emission() = runTest {
        val a = Issue201ChannelHarness()
        collect(a)
        val queue = ArrayDeque<() -> Unit>()
        val owner = callbackOwner(AtomicLong(1L)) { queue.addLast(it) }
        val callback = owner.callback(
            onAcceptedMessage = { owner.postPartial("accepted-at-callback-entry", a.listener) },
            onCurrentDone = {}, onCurrentError = {},
        )
        callback.onMessage(message)
        assertEquals(1, queue.size)
        a.logicalDone.set(true)
        queue.removeFirst().invoke()
        runCurrent()
        assertEquals(1, a.listenerInvocations)
        assertTrue(a.sendResults.isEmpty())
        assertTrue(a.received.isEmpty())
    }

    @Test fun channel_close_before_flag_observation_makes_actual_try_send_fail() = runTest {
        val a = Issue201ChannelHarness()
        collect(a)
        a.output.close()
        // The producer's awaitClose cleanup is scheduled but has not executed yet.
        assertFalse(a.collectorClosed.get())
        assertFalse(a.closed.get())
        assertFalse(a.logicalDone.get())
        a.listener("racing-close", false)
        assertEquals(1, a.processingInvocations)
        assertEquals(listOf(false), a.sendResults)
        runCurrent()
        assertTrue(a.collectorClosed.get())
        assertTrue(a.received.isEmpty())
        // The repository's failure-triggered cancel/recovery branch remains outside scope.
    }

    @Test fun listeners_remain_bound_to_their_original_channel() = runTest {
        val a = Issue201ChannelHarness()
        collect(a)
        val capturedA = a.listener
        val b = Issue201ChannelHarness()
        collect(b)
        // Independent endpoints test binding only, not concurrent native admission.
        capturedA("A-only", false)
        b.listener("B-only", false)
        runCurrent()
        assertEquals(listOf("A-only"), a.received)
        assertEquals(listOf("B-only"), b.received)
        a.logicalDone.set(true)
        capturedA("obsolete-A", false)
        b.listener("B-still-current", false)
        runCurrent()
        assertEquals(listOf("A-only"), a.received)
        assertEquals(listOf("B-only", "B-still-current"), b.received)
    }

    private fun TestScope.collect(harness: Issue201ChannelHarness): Job {
        val job = backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            harness.flow.collect { harness.received += it }
        }
        runCurrent()
        assertTrue("Channel producer must be bound before test delivery", harness.bound.isCompleted)
        return job
    }

    private fun callbackOwner(runId: AtomicLong, dispatch: (() -> Unit) -> Unit) = LiteRtCallbackOwner(
        runId, AtomicBoolean(false), AtomicBoolean(false), AtomicBoolean(false), AtomicLong(0L), runId.get(), dispatch,
    )

    private companion object {
        val message = Message.user(Contents.of("synthetic callback payload"))
    }
}

/** Test transport only; all entry/rejection and trySend decisions use production code. */
internal class Issue201ChannelHarness {
    val closed = AtomicBoolean(false)
    val collectorClosed = AtomicBoolean(false)
    val logicalDone = AtomicBoolean(false)
    val bound = CompletableDeferred<Unit>()
    val received = mutableListOf<String>()
    val sendResults = mutableListOf<Boolean>()
    var listenerInvocations = 0
    var processingInvocations = 0
    var processedDone = 0
    lateinit var listener: (String, Boolean) -> Unit
    lateinit var output: SendChannel<String>
    val flow: Flow<String> = callbackFlow {
        output = channel
        val delivery = RepositoryChannelDelivery(channel, closed, collectorClosed, logicalDone)
        val guarded = delivery.listener { partial, done ->
            processingInvocations++
            if (partial.isNotEmpty()) sendResults += delivery.trySend(partial)
            if (done) processedDone++
        }
        listener = { partial, done ->
            listenerInvocations++
            guarded(partial, done)
        }
        bound.complete(Unit)
        awaitClose { collectorClosed.set(true) }
    }.buffer(Channel.BUFFERED)
}
