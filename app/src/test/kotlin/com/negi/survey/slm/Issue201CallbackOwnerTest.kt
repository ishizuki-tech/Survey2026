package com.negi.survey.slm

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

/**
 * Executes the production callback adapter and terminal CAS with instance-owned queues.
 * Accepted-message processing and terminal side effects are recording sinks: these tests
 * do not exercise text extraction, markNativeDoneOnce, repository channels, or native code.
 */
class Issue201CallbackOwnerTest {
    @Test(timeout = 5_000) fun late_message_after_termination_is_rejected_after_successor_admission() =
        lateMessage(cancelled = false)

    @Test(timeout = 5_000) fun late_message_after_cancellation_is_rejected_after_successor_admission() =
        lateMessage(cancelled = true)

    @Test(timeout = 5_000) fun late_done_does_not_reach_successor_or_claim_its_terminal_state() {
        val f = Fixture()
        val a = f.run("A")
        a.callback.onDone()
        f.admitSuccessor()
        val b = f.run("B")
        val before = f.snapshot()
        a.callback.onDone()
        assertEquals(before, f.snapshot())
        assertEquals(1, a.terminalEntries)
        assertEquals(0, b.terminalEntries)
        assertEquals(1, a.terminalOwners)
        assertEquals(0, b.terminalOwners)
    }

    @Test(timeout = 5_000) fun late_error_does_not_reach_successor_or_claim_its_terminal_state() {
        val f = Fixture()
        val a = f.run("A")
        a.callback.onDone()
        f.admitSuccessor()
        val b = f.run("B")
        val before = f.snapshot()
        a.callback.onError(IllegalStateException("synthetic obsolete error"))
        assertEquals(before, f.snapshot())
        assertTrue(a.errors.isEmpty())
        assertTrue(b.errors.isEmpty())
        assertEquals(0, b.terminalOwners)
        // No runtime/coordinator is installed: this proves rejection before the error
        // handler, not native poison or recovery behavior.
    }

    @Test(timeout = 5_000) fun done_done_has_one_terminal_claim() = duplicateTerminals(false, false)
    @Test(timeout = 5_000) fun error_error_has_one_terminal_claim() = duplicateTerminals(true, true)
    @Test(timeout = 5_000) fun done_error_has_one_terminal_claim() = duplicateTerminals(false, true)
    @Test(timeout = 5_000) fun error_done_has_one_terminal_claim() = duplicateTerminals(true, false)

    @Test(timeout = 5_000) fun each_current_run_message_guard_rejects_before_processing_or_dispatch() {
        for (guard in 0..2) {
            val f = Fixture()
            val a = f.run("A")
            when (guard) {
                0 -> f.terminated.set(true)
                1 -> f.logicalDone.set(true)
                2 -> f.cancelRequested.set(true)
            }
            val before = f.snapshot()
            a.callback.onMessage(message)
            assertEquals(before, f.snapshot())
            assertTrue(a.accepted.isEmpty())
            assertTrue(f.queue.isEmpty())
        }
    }

    @Test(timeout = 5_000) fun queued_old_partial_invokes_captured_listener_after_successor_admission() {
        val f = Fixture()
        val a = f.run("A")
        a.callback.onMessage(message)
        assertEquals(listOf(message), a.accepted)
        assertEquals(1, f.queue.size)
        assertTrue(a.delivered.isEmpty())
        f.cancelRequested.set(true)
        f.logicalDone.set(true)
        a.callback.onDone()
        f.admitSuccessor()
        val b = f.run("B")
        val before = f.snapshot()
        f.drain()
        assertEquals(1, f.executedDeliveries)
        assertEquals(listOf("A:1"), a.delivered)
        assertTrue(b.delivered.isEmpty())
        assertEquals(before, f.snapshot())
        // This intentionally characterizes the absence of delivery-time filtering.
        // A's recording listener is NOT LiteRtRepository's guarded listener.
    }

    @Test(timeout = 5_000) fun release_before_cancellation_delivers_normal_partial() {
        val f = Fixture()
        val a = f.run("A")
        a.callback.onMessage(message)
        assertTrue(a.delivered.isEmpty())
        f.drain()
        assertEquals(listOf("A:1"), a.delivered)
        f.cancelRequested.set(true)
        f.logicalDone.set(true)
        a.callback.onMessage(message)
        assertTrue(f.queue.isEmpty())
        assertEquals(1, a.accepted.size)
    }

    @Test(timeout = 5_000) fun accepted_chunks_keep_enqueue_order_across_terminal_claim() {
        val f = Fixture()
        val a = f.run("A")
        a.callback.onMessage(message)
        a.callback.onMessage(message)
        a.callback.onDone()
        // Flags are a supplied terminal-state precondition, not a simulated native close.
        f.terminated.set(true)
        f.logicalDone.set(true)
        a.callback.onMessage(message)
        assertEquals(2, f.queue.size)
        assertEquals(1, a.terminalOwners)
        f.drain()
        assertEquals(listOf("A:1", "A:2"), a.delivered)
        assertEquals(2, f.executedDeliveries)
    }

    @Test(timeout = 5_000) fun cancel_flag_during_stream_suppresses_new_chunks_but_allows_terminal_claim() {
        val f = Fixture()
        val a = f.run("A")
        a.callback.onMessage(message)
        f.drain()
        f.cancelRequested.set(true)
        a.callback.onMessage(message)
        a.callback.onDone()
        a.callback.onError(IllegalStateException("synthetic duplicate terminal"))
        assertEquals(listOf("A:1"), a.delivered)
        assertEquals(1, a.accepted.size)
        assertEquals(1, a.terminalOwners)
        assertTrue(f.queue.isEmpty())
    }

    @Test(timeout = 5_000) fun separate_instances_do_not_share_dispatch_or_ownership() {
        val first = Fixture()
        val second = Fixture()
        val a = first.run("A")
        val b = second.run("B")
        a.callback.onMessage(message)
        b.callback.onMessage(message)
        first.drain()
        assertEquals(listOf("A:1"), a.delivered)
        assertTrue(b.delivered.isEmpty())
        assertEquals(1, second.queue.size)
        a.callback.onDone()
        assertEquals(0L, second.terminalRunId.get())
        second.drain()
        assertEquals(listOf("B:1"), b.delivered)
    }

    private fun lateMessage(cancelled: Boolean) {
        val f = Fixture()
        val a = f.run("A")
        if (cancelled) {
            f.cancelRequested.set(true)
            f.logicalDone.set(true)
        }
        a.callback.onDone()
        f.admitSuccessor()
        val b = f.run("B")
        val before = f.snapshot()
        a.callback.onMessage(message)
        f.drain()
        assertEquals(before, f.snapshot())
        assertTrue(a.accepted.isEmpty())
        assertTrue(a.delivered.isEmpty())
        assertTrue(b.delivered.isEmpty())
        assertEquals(0, b.terminalEntries)
    }

    private fun duplicateTerminals(firstError: Boolean, secondError: Boolean) {
        val f = Fixture()
        val a = f.run("A")
        fun terminal(error: Boolean) {
            if (error) a.callback.onError(IllegalStateException("synthetic terminal"))
            else a.callback.onDone()
        }
        terminal(firstError)
        terminal(secondError)
        assertEquals(2, a.terminalEntries)
        assertEquals(1, a.terminalOwners)
        assertEquals(1L, f.terminalRunId.get())
        assertEquals(1L, f.runId.get())
        f.admitSuccessor()
        val before = f.snapshot()
        terminal(secondError)
        assertEquals(before, f.snapshot())
        assertEquals(2, a.terminalEntries)
    }

    private class Run(val owner: LiteRtCallbackOwner, name: String) {
        val accepted = mutableListOf<Message>()
        val delivered = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        var terminalEntries = 0
        var terminalOwners = 0
        private fun claim() {
            terminalEntries++
            if (owner.claimTerminal()) terminalOwners++
        }
        val callback: MessageCallback = owner.callback(
            onAcceptedMessage = {
                accepted += it
                owner.postPartial("$name:${accepted.size}") { delta, done ->
                    assertFalse(done)
                    delivered += delta
                }
            },
            onCurrentDone = { claim() },
            onCurrentError = { errors += it; claim() },
        )
    }

    private class Fixture {
        val runId = AtomicLong(1L)
        val terminated = AtomicBoolean(false)
        val logicalDone = AtomicBoolean(false)
        val cancelRequested = AtomicBoolean(false)
        val terminalRunId = AtomicLong(0L)
        val queue = ArrayDeque<() -> Unit>()
        var executedDeliveries = 0
        fun run(name: String) = Run(LiteRtCallbackOwner(
            runId, terminated, logicalDone, cancelRequested, terminalRunId, runId.get(),
            dispatch = { queue.addLast(it) },
        ), name)
        fun admitSuccessor() {
            runId.incrementAndGet()
            terminated.set(false)
            logicalDone.set(false)
            cancelRequested.set(false)
        }
        fun snapshot() = listOf(runId.get(), terminalRunId.get(), terminated.get(), logicalDone.get(), cancelRequested.get())
        fun drain() {
            while (queue.isNotEmpty()) {
                executedDeliveries++
                queue.removeFirst().invoke()
            }
        }
    }

    private companion object {
        val message = Message.user(Contents.of("synthetic callback payload"))
    }
}
