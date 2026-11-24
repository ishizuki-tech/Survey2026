package com.negi.survey.slm

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Tests the listener used by generateText, not its admission, mutex or native lifecycle. */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue201DirectListenerTest {
    @Test fun final_partial_is_appended_before_deferred_completion() = runTest {
        val f = Fixture()
        f.listener("first", false)
        assertFalse(f.done.isCompleted)
        f.listener("last", true)
        assertEquals(listOf("first", "last"), f.partials)
        assertEquals("firstlast", f.done.await())
    }

    @Test fun queued_accepted_partial_still_invokes_direct_listener_after_waiter_cancellation() = runTest {
        val f = Fixture()
        val queue = ArrayDeque<() -> Unit>()
        val cancelled = AtomicBoolean(false)
        val owner = LiteRtCallbackOwner(
            AtomicLong(1L), AtomicBoolean(false), AtomicBoolean(false), cancelled,
            AtomicLong(0L), 1L, dispatch = { queue.addLast(it) },
        )
        val callback = owner.callback(
            onAcceptedMessage = { owner.postPartial("accepted-before-cancel", f.listener) },
            onCurrentDone = {},
            onCurrentError = {},
        )
        val caller = launch { f.done.await() }
        runCurrent()
        callback.onMessage(Message.user(Contents.of("synthetic")))
        assertEquals(1, queue.size)
        assertTrue(f.partials.isEmpty())
        caller.cancelAndJoin()
        cancelled.set(true)
        assertFalse(f.done.isCompleted)
        queue.removeFirst().invoke()
        assertEquals(listOf("accepted-before-cancel"), f.partials)
        assertEquals("accepted-before-cancel", f.buffer.toString())
        assertTrue(caller.isCancelled)
        assertFalse(f.done.isCompleted)
        f.done.cancel()
        // This proves the extracted listener's behavior after cancellation of its
        // awaiter. It does not execute generateText's cancel(model) or apiMutex path.
    }

    @Test fun partial_after_completion_mutates_buffer_but_not_completed_result() = runTest {
        val f = Fixture()
        f.listener("result", true)
        assertEquals("result", f.done.await())
        f.listener("late", false)
        assertEquals(listOf("result", "late"), f.partials)
        assertEquals("resultlate", f.buffer.toString())
        assertEquals("result", f.done.await())
        // Characterizes the listener if called in this order; does not claim that
        // a native terminal callback can violate its documented callback order.
    }

    @Test fun repeated_done_does_not_replace_completed_result() = runTest {
        val f = Fixture()
        f.listener("result", true)
        f.listener("", true)
        assertEquals("result", f.done.await())
        assertEquals(listOf("result"), f.partials)
    }

    @Test fun partial_consumer_failure_is_reported_without_discarding_output() = runTest {
        val buffer = StringBuilder()
        val done = CompletableDeferred<String>()
        val failure = IllegalStateException("synthetic consumer failure")
        val failures = mutableListOf<Throwable>()
        val listener = liteRtTextResultListener(buffer, done, { throw failure }, { failures += it })
        listener("kept", true)
        assertEquals("kept", done.await())
        assertEquals(listOf(failure), failures)
    }

    private class Fixture {
        val buffer = StringBuilder()
        val done = CompletableDeferred<String>()
        val partials = mutableListOf<String>()
        val listener = liteRtTextResultListener(buffer, done, { partials += it }, { throw AssertionError(it) })
    }
}
