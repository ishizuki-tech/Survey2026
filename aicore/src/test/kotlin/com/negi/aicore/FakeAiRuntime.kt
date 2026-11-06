package com.negi.aicore

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Test-only controllable runtime that keeps logical completion and native termination separate. */
internal class FakeAiRuntime : AiRuntime {
    private data class ActiveGeneration(
        val request: AiGenerationRequest,
        val events: Channel<AiGenerationEvent> = Channel(Channel.UNLIMITED),
        val nativeTermination: CompletableDeferred<Unit> = CompletableDeferred(),
        var logicalTerminal: Boolean = false,
    )

    private val lock = Any()
    private var prepared: Pair<AiModel, AiRuntimeConfig>? = null
    private var active: ActiveGeneration? = null
    private var closeFlight: CompletableDeferred<AiCloseResult>? = null

    var state: AiRuntimeState = AiRuntimeState.NEW
        private set
    var failCloseWith: Throwable? = null
    var closeCallsStarted: Int = 0
        private set

    override suspend fun prepare(model: AiModel, config: AiRuntimeConfig): AiPrepareResult = synchronized(lock) {
        when (state) {
            AiRuntimeState.NEW -> {
                prepared = model to config
                state = AiRuntimeState.READY
                AiPrepareResult.Ready(reusedPreparedRuntime = false)
            }
            AiRuntimeState.READY -> {
                if (prepared == model to config) AiPrepareResult.Ready(reusedPreparedRuntime = true)
                else throw AiCoreException.InvalidState("A different model or configuration requires a new runtime.")
            }
            else -> throw AiCoreException.InvalidState("prepare is not allowed while runtime state is $state.")
        }
    }

    override fun generate(request: AiGenerationRequest): Flow<AiGenerationEvent> = flow {
        val generation = synchronized(lock) {
            when (state) {
                AiRuntimeState.READY -> {
                    if (active != null) return@synchronized null to AiCoreException.ConcurrentGeneration()
                    ActiveGeneration(request).also {
                        active = it
                        state = AiRuntimeState.GENERATING
                    } to null
                }
                AiRuntimeState.CLOSED, AiRuntimeState.POISONED -> null to AiCoreException.InvalidState(
                    "Generation is not allowed while runtime state is $state.",
                )
                else -> null to AiCoreException.InvalidState("Generation is not allowed while runtime state is $state.")
            }
        }
        val (activeGeneration, rejection) = generation
        if (rejection != null) {
            emit(AiGenerationEvent.Failed(request.generationId, rejection))
            return@flow
        }
        val current = checkNotNull(activeGeneration)
        emit(AiGenerationEvent.Started(request.generationId, request.metadata))
        try {
            for (event in current.events) emit(event)
        } finally {
            // A cancelled collector must not release native ownership or make the runtime reusable.
        }
    }

    override fun cancel(generationId: AiGenerationId) {
        synchronized(lock) {
            val current = active ?: return
            if (current.request.generationId != generationId || current.logicalTerminal) return
            current.logicalTerminal = true
            current.events.trySend(AiGenerationEvent.Cancelled(generationId))
        }
    }

    override suspend fun close(): AiCloseResult {
        val flight = synchronized(lock) {
            closeCallsStarted++
            when (state) {
                AiRuntimeState.CLOSED -> return AiCloseResult.AlreadyClosed
                AiRuntimeState.POISONED -> return AiCloseResult.Unconfirmed(
                    AiCoreException.UnconfirmedNativeCleanup(),
                )
                AiRuntimeState.CLOSING -> return@synchronized checkNotNull(closeFlight)
                else -> CompletableDeferred<AiCloseResult>().also {
                    closeFlight = it
                    state = AiRuntimeState.CLOSING
                    active?.let { running -> cancel(running.request.generationId) }
                }
            }
        }
        val current = synchronized(lock) { active }
        if (current != null) current.nativeTermination.await()
        val result = synchronized(lock) {
            val failure = failCloseWith
            if (failure == null) {
                state = AiRuntimeState.CLOSED
                AiCloseResult.Released
            } else {
                state = AiRuntimeState.POISONED
                AiCloseResult.Unconfirmed(AiCoreException.UnconfirmedNativeCleanup(failure))
            }
        }
        flight.complete(result)
        return result
    }

    fun emitDelta(generationId: AiGenerationId, text: String) = emitIfCurrent(generationId) {
        AiGenerationEvent.Delta(generationId, text)
    }

    fun completeLogically(generationId: AiGenerationId) = synchronized(lock) {
        val current = active ?: return@synchronized
        if (current.request.generationId != generationId || current.logicalTerminal) return@synchronized
        current.logicalTerminal = true
        current.events.trySend(AiGenerationEvent.Completed(generationId))
    }

    fun fail(generationId: AiGenerationId, error: AiCoreException) = synchronized(lock) {
        val current = active ?: return@synchronized
        if (current.request.generationId != generationId || current.logicalTerminal) return@synchronized
        current.logicalTerminal = true
        current.events.trySend(AiGenerationEvent.Failed(generationId, error))
    }

    fun confirmNativeTermination(generationId: AiGenerationId) = synchronized(lock) {
        val current = active ?: return@synchronized
        if (current.request.generationId != generationId) return@synchronized
        current.events.trySend(AiGenerationEvent.NativeTerminated(generationId))
        current.events.close()
        current.nativeTermination.complete(Unit)
        active = null
        if (state == AiRuntimeState.GENERATING) state = AiRuntimeState.READY
    }

    private fun emitIfCurrent(generationId: AiGenerationId, build: () -> AiGenerationEvent) = synchronized(lock) {
        val current = active ?: return@synchronized
        if (current.request.generationId == generationId && !current.logicalTerminal) {
            current.events.trySend(build())
        }
    }
}
