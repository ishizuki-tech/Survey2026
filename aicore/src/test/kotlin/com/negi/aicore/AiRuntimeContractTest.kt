package com.negi.aicore

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertIs

class AiRuntimeContractTest {
    private val model = AiModel("model-a")
    private val config = AiRuntimeConfig()

    @Test fun successfulPrepareAndGeneration() = runBlocking {
        val runtime = prepared()
        val id = AiGenerationId.create()
        val events = mutableListOf<AiGenerationEvent>()
        val job = launch { runtime.generate(AiGenerationRequest(id, "hello")).toList(events) }
        yield()
        runtime.emitDelta(id, "hello")
        runtime.completeLogically(id)
        runtime.confirmNativeTermination(id)
        job.join()
        assertEquals(listOf("hello"), events.filterIsInstance<AiGenerationEvent.Delta>().map { it.text })
        assertTrue(events.any { it is AiGenerationEvent.Completed })
        assertTrue(events.any { it is AiGenerationEvent.NativeTerminated })
    }

    @Test fun equivalentPrepareIsIdempotent() = runBlocking {
        val runtime = FakeAiRuntime()
        assertEquals(AiPrepareResult.Ready(false), runtime.prepare(model, config))
        assertEquals(AiPrepareResult.Ready(true), runtime.prepare(model, config))
    }

    @Test(expected = AiCoreException.InvalidState::class)
    fun differentPrepareIsRejected() = runBlocking {
        val runtime = prepared()
        runtime.prepare(AiModel("other"), config)
        Unit
    }

    @Test fun cancellationBeforeGenerationStartsDoesNotAffectLaterGeneration() = runBlocking {
        val runtime = prepared()
        val id = AiGenerationId.create()
        runtime.cancel(id)
        val events = mutableListOf<AiGenerationEvent>()
        val job = launch { runtime.generate(AiGenerationRequest(id, "hello")).toList(events) }
        yield()
        runtime.completeLogically(id)
        runtime.confirmNativeTermination(id)
        job.join()
        assertFalse(events.any { it is AiGenerationEvent.Cancelled })
    }

    @Test fun cancellationDuringGenerationIsLogicalOnlyUntilNativeTermination() = runBlocking {
        val runtime = prepared()
        val id = AiGenerationId.create()
        val events = mutableListOf<AiGenerationEvent>()
        val job = launch { runtime.generate(AiGenerationRequest(id, "hello")).toList(events) }
        yield()
        runtime.cancel(id)
        yield()
        assertEquals(AiRuntimeState.GENERATING, runtime.state)
        assertTrue(events.any { it is AiGenerationEvent.Cancelled })
        runtime.confirmNativeTermination(id)
        job.join()
        assertEquals(AiRuntimeState.READY, runtime.state)
    }

    @Test fun logicalCompletionDoesNotPermitReuseBeforeNativeTermination() = runBlocking {
        val runtime = prepared()
        val first = AiGenerationId.create()
        val job = launch { runtime.generate(AiGenerationRequest(first, "first")).toList() }
        yield()
        runtime.completeLogically(first)
        val secondEvents = runtime.generate(AiGenerationRequest(AiGenerationId.create(), "second")).toList()
        assertIs<AiGenerationEvent.Failed>(secondEvents.single())
        runtime.confirmNativeTermination(first)
        job.join()
    }

    @Test fun timeoutAndFailureArePropagated() = runBlocking {
        val runtime = prepared()
        val id = AiGenerationId.create()
        val events = mutableListOf<AiGenerationEvent>()
        val job = launch { runtime.generate(AiGenerationRequest(id, "hello", timeoutMillis = 1)).toList(events) }
        yield()
        runtime.fail(id, AiCoreException.Timeout())
        runtime.confirmNativeTermination(id)
        job.join()
        assertIs<AiCoreException.Timeout>((events.filterIsInstance<AiGenerationEvent.Failed>().single()).error)
        Unit
    }

    @Test fun concurrentGenerationIsRejected() = runBlocking {
        val runtime = prepared()
        val first = AiGenerationId.create()
        val firstJob = launch { runtime.generate(AiGenerationRequest(first, "first")).toList() }
        yield()
        val rejected = runtime.generate(AiGenerationRequest(AiGenerationId.create(), "second")).toList().single()
        assertIs<AiGenerationEvent.Failed>(rejected)
        runtime.confirmNativeTermination(first)
        firstJob.join()
    }

    @Test fun concurrentCloseCallsJoinOneConfirmedRelease() = runBlocking {
        val runtime = prepared()
        val id = AiGenerationId.create()
        val generation = launch { runtime.generate(AiGenerationRequest(id, "hello")).toList() }
        yield()
        val first = async(Dispatchers.Default) { runtime.close() }
        val second = async(Dispatchers.Default) { runtime.close() }
        while (runtime.closeCallsStarted < 2) yield()
        runtime.confirmNativeTermination(id)
        assertEquals(AiCloseResult.Released, first.await())
        assertEquals(AiCloseResult.Released, second.await())
        generation.join()
    }

    @Test fun closeWhileGeneratingWaitsForNativeTermination() = runBlocking {
        val runtime = prepared()
        val id = AiGenerationId.create()
        val generation = launch { runtime.generate(AiGenerationRequest(id, "hello")).toList() }
        yield()
        val close = async { runtime.close() }
        yield()
        assertFalse(close.isCompleted)
        runtime.confirmNativeTermination(id)
        assertEquals(AiCloseResult.Released, close.await())
        generation.join()
    }

    @Test fun closeFailurePoisonsRuntime() = runBlocking {
        val runtime = prepared()
        runtime.failCloseWith = IllegalStateException("close failed")
        val result = runtime.close()
        assertIs<AiCloseResult.Unconfirmed>(result)
        assertEquals(AiRuntimeState.POISONED, runtime.state)
    }

    @Test fun closedAndPoisonedRuntimesRejectGeneration() = runBlocking {
        val closed = prepared()
        assertEquals(AiCloseResult.Released, closed.close())
        assertIs<AiGenerationEvent.Failed>(closed.generate(AiGenerationRequest(AiGenerationId.create(), "x")).toList().single())

        val poisoned = prepared().also { it.failCloseWith = IllegalStateException("close failed") }
        poisoned.close()
        assertIs<AiGenerationEvent.Failed>(poisoned.generate(AiGenerationRequest(AiGenerationId.create(), "x")).toList().single())
        Unit
    }

    @Test fun staleEventsCannotReachSubsequentGeneration() = runBlocking {
        val runtime = prepared()
        val first = AiGenerationId.create()
        val firstJob = launch { runtime.generate(AiGenerationRequest(first, "first")).toList() }
        yield()
        runtime.confirmNativeTermination(first)
        firstJob.join()

        val second = AiGenerationId.create()
        val events = mutableListOf<AiGenerationEvent>()
        val secondJob = launch { runtime.generate(AiGenerationRequest(second, "second")).toList(events) }
        yield()
        runtime.emitDelta(first, "stale")
        runtime.emitDelta(second, "fresh")
        runtime.confirmNativeTermination(second)
        secondJob.join()
        assertEquals(listOf("fresh"), events.filterIsInstance<AiGenerationEvent.Delta>().map { it.text })
    }

    private suspend fun prepared(): FakeAiRuntime = FakeAiRuntime().also { it.prepare(model, config) }
}
