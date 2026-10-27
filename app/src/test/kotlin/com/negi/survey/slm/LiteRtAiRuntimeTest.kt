package com.negi.survey.slm

import com.negi.aicore.AiCloseResult
import com.negi.aicore.AiGenerationEvent
import com.negi.aicore.AiGenerationId
import com.negi.aicore.AiGenerationRequest
import com.negi.aicore.AiModel
import com.negi.aicore.AiPrepareResult
import com.negi.aicore.AiRuntimeConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LiteRtAiRuntimeTest {
    private val model = AiModel("model")

    @Test fun prepareIsIdempotentAndRejectsDifferentConfiguration() = runBlocking {
        val bridge = FakeBridge()
        val runtime = LiteRtAiRuntime(bridge)
        assertEquals(AiPrepareResult.Ready(false), runtime.prepare(model, AiRuntimeConfig()))
        assertEquals(AiPrepareResult.Ready(true), runtime.prepare(model, AiRuntimeConfig()))
        assertEquals(1, bridge.prepareCalls)
        assertFailsWith<IllegalStateException> {
            runtime.prepare(model, AiRuntimeConfig(sampling = com.negi.aicore.AiSamplingConfig(temperature = 0.5)))
        }
        Unit
    }

    @Test fun collectorCancellationDoesNotReleaseNativeOwnership() = runBlocking {
        val bridge = FakeBridge()
        val runtime = prepared(bridge)
        val id = AiGenerationId.create()
        val job = launch { runtime.generate(AiGenerationRequest(id, "x")).toList() }
        yield()
        job.cancel()
        job.join()
        assertFalse(bridge.nativeTermination.isCompleted)
        val close = async { runtime.close() }
        yield()
        assertFalse(close.isCompleted)
        bridge.nativeTermination.complete(Unit)
        assertEquals(AiCloseResult.Released, close.await())
    }

    @Test fun cancellationAndNormalCompletionKeepIdentityAndWaitForNativeTermination() = runBlocking {
        val bridge = FakeBridge()
        val runtime = prepared(bridge)
        val id = AiGenerationId.create()
        val events = mutableListOf<AiGenerationEvent>()
        val job = launch { runtime.generate(AiGenerationRequest(id, "x")).toList(events) }
        yield()
        runtime.cancel(id)
        while (events.none { it is AiGenerationEvent.Cancelled }) yield()
        assertTrue(events.any { it is AiGenerationEvent.Cancelled && it.generationId == id })
        assertFalse(job.isCompleted)
        bridge.nativeTermination.complete(Unit)
        job.join()
        assertTrue(events.any { it is AiGenerationEvent.NativeTerminated && it.generationId == id })
    }

    @Test fun logicalCompletionDoesNotPermitOverlapBeforeNativeTermination() = runBlocking {
        val bridge = FakeBridge()
        val runtime = prepared(bridge)
        val first = AiGenerationId.create()
        val firstEvents = mutableListOf<AiGenerationEvent>()
        val job = launch { runtime.generate(AiGenerationRequest(first, "one")).toList(firstEvents) }
        yield()
        bridge.completeLogically()
        while (firstEvents.none { it is AiGenerationEvent.Completed }) yield()
        val rejected = runtime.generate(AiGenerationRequest(AiGenerationId.create(), "two")).toList().single()
        assertIs<AiGenerationEvent.Failed>(rejected)
        bridge.nativeTermination.complete(Unit)
        job.join()
    }

    @Test fun closeFailurePoisonsRuntimeAndBlocksLaterGeneration() = runBlocking {
        val bridge = FakeBridge().apply { closeFailure = IllegalStateException("unconfirmed") }
        val runtime = prepared(bridge)
        assertIs<AiCloseResult.Unconfirmed>(runtime.close())
        val event = runtime.generate(AiGenerationRequest(AiGenerationId.create(), "x")).toList().single()
        assertIs<AiGenerationEvent.Failed>(event)
        Unit
    }

    @Test fun initializationFailureDoesNotMakeRuntimeReady() = runBlocking {
        val bridge = FakeBridge().apply { readinessFailure = IllegalStateException("init") }
        val runtime = LiteRtAiRuntime(bridge)
        assertFailsWith<IllegalStateException> { runtime.prepare(model, AiRuntimeConfig()) }
        assertIs<AiGenerationEvent.Failed>(
            runtime.generate(AiGenerationRequest(AiGenerationId.create(), "x")).toList().single(),
        )
        Unit
    }

    @Test fun unconfirmedNativeTerminationPoisonsWithoutTerminationEvent() = runBlocking {
        val bridge = FakeBridge().apply { terminationFailure = IllegalStateException("cleanup") }
        val runtime = prepared(bridge)
        val id = AiGenerationId.create()
        val events = mutableListOf<AiGenerationEvent>()
        val job = launch { runtime.generate(AiGenerationRequest(id, "x")).toList(events) }
        yield()
        bridge.completeLogically()
        bridge.nativeTermination.complete(Unit)
        job.join()
        assertFalse(events.any { it is AiGenerationEvent.NativeTerminated })
        assertTrue(events.any { it is AiGenerationEvent.Failed })
        assertIs<AiCloseResult.Unconfirmed>(runtime.close())
        Unit
    }

    @Test fun cancellationTargetsTheActiveGenerationHandleOnly() = runBlocking {
        val bridge = FakeBridge()
        val runtime = prepared(bridge)
        val id = AiGenerationId.create()
        val job = launch { runtime.generate(AiGenerationRequest(id, "x")).toList() }
        yield()
        runtime.cancel(id)
        while (bridge.cancelledGenerationIds.isEmpty()) yield()
        assertEquals(listOf(id), bridge.cancelledGenerationIds)
        bridge.nativeTermination.complete(Unit)
        job.join()
    }

    @Test fun concurrentCloseCallersShareOnePendingFlightAndOneBridgeClose() = runBlocking {
        val bridge = FakeBridge().apply { blockClose = true }
        val runtime = prepared(bridge)
        val owner = async { runtime.close() }
        bridge.closeEntered.await()
        val joiner = async { runtime.close() }
        yield()
        assertFalse(owner.isCompleted)
        assertFalse(joiner.isCompleted)
        assertEquals(1, bridge.closeCalls)
        bridge.closeGate.complete(Unit)
        assertEquals(AiCloseResult.Released, owner.await())
        assertEquals(AiCloseResult.Released, joiner.await())
        assertEquals(1, bridge.closeCalls)
        assertEquals(AiCloseResult.AlreadyClosed, runtime.close())
    }

    @Test fun cancellingInitiatingCloseCallerDoesNotAbandonOwnerCleanup() = runBlocking {
        val bridge = FakeBridge().apply { blockClose = true }
        val runtime = prepared(bridge)
        val initiatingCaller = async { runtime.close() }
        bridge.closeEntered.await()
        initiatingCaller.cancelAndJoin()
        val joiner = async { runtime.close() }
        yield()
        assertFalse(joiner.isCompleted)
        bridge.closeGate.complete(Unit)
        assertEquals(AiCloseResult.Released, joiner.await())
        assertEquals(1, bridge.closeCalls)
    }

    @Test fun cancellingJoinersDoesNotCancelSharedCloseCleanup() = runBlocking {
        val bridge = FakeBridge().apply { blockClose = true }
        val runtime = prepared(bridge)
        val owner = async { runtime.close() }
        bridge.closeEntered.await()
        val joiner = async { runtime.close() }
        joiner.cancelAndJoin()
        owner.cancelAndJoin()
        bridge.closeGate.complete(Unit)
        val observer = withTimeout(1_000) { runtime.close() }
        assertTrue(observer == AiCloseResult.Released || observer == AiCloseResult.AlreadyClosed)
        assertEquals(1, bridge.closeCalls)
    }

    @Test fun unconfirmedAndThrownBridgeClosePoisonSharedFlight() = runBlocking {
        suspend fun assertPoisoned(bridge: FakeBridge) {
            bridge.blockClose = true
            val runtime = prepared(bridge)
            val owner = async { runtime.close() }
            bridge.closeEntered.await()
            val joiner = async { runtime.close() }
            bridge.closeGate.complete(Unit)
            assertIs<AiCloseResult.Unconfirmed>(owner.await())
            assertIs<AiCloseResult.Unconfirmed>(joiner.await())
            assertIs<AiCloseResult.Unconfirmed>(runtime.close())
            assertEquals(1, bridge.closeCalls)
        }
        assertPoisoned(FakeBridge().apply { closeFailure = IllegalStateException("unconfirmed") })
        assertPoisoned(FakeBridge().apply { closeThrows = IllegalStateException("thrown") })
    }

    @Test fun generationIsRejectedWhileCloseAwaitsNativeTermination() = runBlocking {
        val bridge = FakeBridge()
        val runtime = prepared(bridge)
        val generation = launch { runtime.generate(AiGenerationRequest(AiGenerationId.create(), "x")).toList() }
        yield()
        val close = async { runtime.close() }
        yield()
        assertIs<AiGenerationEvent.Failed>(
            runtime.generate(AiGenerationRequest(AiGenerationId.create(), "later")).toList().single(),
        )
        assertFalse(close.isCompleted)
        bridge.nativeTermination.complete(Unit)
        assertEquals(AiCloseResult.Released, close.await())
        generation.join()
    }

    @Test fun cancelledAdapterScopePoisonsWithoutClaimingNativeRelease() = runBlocking {
        val bridge = FakeBridge()
        val scopeJob = SupervisorJob()
        val runtime = LiteRtAiRuntime(bridge, CoroutineScope(scopeJob + Dispatchers.Default))
        runtime.prepare(model, AiRuntimeConfig())
        scopeJob.cancel()
        assertIs<AiCloseResult.Unconfirmed>(runtime.close())
        assertEquals(0, bridge.closeCalls)
    }

    private suspend fun prepared(bridge: FakeBridge) = LiteRtAiRuntime(bridge).also {
        it.prepare(model, AiRuntimeConfig())
    }

    private class FakeBridge : LiteRtRuntimeBridge {
        var prepareCalls = 0
        var closeFailure: Throwable? = null
        var closeThrows: Throwable? = null
        var blockClose = false
        var closeCalls = 0
        var readinessFailure: Throwable? = null
        var terminationFailure: Throwable? = null
        val cancelledGenerationIds = mutableListOf<AiGenerationId>()
        val logicalCompletion = CompletableDeferred<Unit>()
        val nativeTermination = CompletableDeferred<Unit>()
        val closeEntered = CompletableDeferred<Unit>()
        val closeGate = CompletableDeferred<Unit>()

        override suspend fun prepare(model: AiModel, config: AiRuntimeConfig): LiteRtReadinessObservation {
            prepareCalls++
            return readinessFailure?.let(LiteRtReadinessObservation::Failed)
                ?: LiteRtReadinessObservation.Ready
        }
        override fun startGeneration(request: AiGenerationRequest): LiteRtRuntimeGeneration =
            object : LiteRtRuntimeGeneration {
                override val responses: Flow<String> = flow { logicalCompletion.await() }
                override fun cancel() {
                    cancelledGenerationIds += request.generationId
                    logicalCompletion.complete(Unit)
                }
            }
        override suspend fun awaitNativeTermination(generationId: AiGenerationId): LiteRtCleanupObservation {
            nativeTermination.await()
            return terminationFailure?.let(LiteRtCleanupObservation::Unconfirmed)
                ?: LiteRtCleanupObservation.Confirmed
        }
        override suspend fun close(): LiteRtCleanupObservation {
            closeCalls++
            closeEntered.complete(Unit)
            if (blockClose) closeGate.await()
            closeThrows?.let { throw it }
            return closeFailure?.let(LiteRtCleanupObservation::Unconfirmed)
                ?: LiteRtCleanupObservation.Confirmed
        }
        fun completeLogically() { logicalCompletion.complete(Unit) }
    }
}
