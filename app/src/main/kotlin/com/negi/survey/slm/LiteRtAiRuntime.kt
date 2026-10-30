package com.negi.survey.slm

import com.negi.aicore.AiCloseResult
import com.negi.aicore.AiCoreException
import com.negi.aicore.AiGenerationEvent
import com.negi.aicore.AiGenerationId
import com.negi.aicore.AiGenerationRequest
import com.negi.aicore.AiModel
import com.negi.aicore.AiPrepareResult
import com.negi.aicore.AiRuntime
import com.negi.aicore.AiRuntimeConfig
import com.negi.aicore.AiRuntimeState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App-local bridge used by [LiteRtAiRuntime].
 *
 * The bridge deliberately exposes a native-termination wait separately from its response Flow:
 * LiteRT's response stream can complete before the native cleanup safepoint.  Implementations
 * must not complete [awaitNativeTermination] merely because their caller stopped collecting.
 */
internal interface LiteRtRuntimeBridge {
    suspend fun prepare(model: AiModel, config: AiRuntimeConfig): LiteRtReadinessObservation
    fun startGeneration(request: AiGenerationRequest): LiteRtRuntimeGeneration
    suspend fun awaitNativeTermination(generationId: AiGenerationId): LiteRtCleanupObservation
    suspend fun close(): LiteRtCleanupObservation
}

internal interface LiteRtRuntimeGeneration {
    val responses: Flow<String>
    fun cancel()
}

/**
 * Bridge for the existing [LiteRtRepository] path.
 *
 * It intentionally uses the repository for streaming so the established process-wide gate,
 * watchdogs, cancellation grace period, recovery, and app-owned heavy-runtime handoff remain
 * authoritative.  Native termination is confirmed only by the existing synchronous teardown
 * API; this adapter is not installed in production traffic, so that teardown does not change
 * the current warm-runtime behavior.
 */
internal class LiteRtRepositoryRuntimeBridge(
    private val repository: LiteRtRepository,
    private val liteRtModel: Model,
) : LiteRtRuntimeBridge {
    override suspend fun prepare(model: AiModel, config: AiRuntimeConfig): LiteRtReadinessObservation {
        require(model.identifier == liteRtModel.name) {
            "AiModel identifier does not match the configured LiteRT model."
        }
        // Retain the existing initialization route; do not create a second Engine owner.
        return repository.prepareObservation()
    }

    override fun startGeneration(request: AiGenerationRequest): LiteRtRuntimeGeneration {
        val generation = repository.newObservedGeneration(request.generationId)
        return object : LiteRtRuntimeGeneration {
            override val responses: Flow<String> = flow {
                // Do not supply an override: preserve the repository's sampling behavior.
                repository.requestObserved(generation, request.prompt).collect { emit(it) }
            }

            override fun cancel() = generation.cancel()
        }
    }

    override suspend fun awaitNativeTermination(generationId: AiGenerationId): LiteRtCleanupObservation {
        return repository.observeCleanup()
    }

    override suspend fun close(): LiteRtCleanupObservation {
        return repository.observeCleanup()
    }
}

/**
 * Adapter boundary for the existing app-owned LiteRT pipeline.
 *
 * This is intentionally not wired into production inference call sites.  It owns no Engine,
 * Conversation, gate, or [com.negi.survey.runtime.HeavyRuntimeCoordinator]; those remain in the
 * existing LiteRT implementation behind [LiteRtRuntimeBridge].
 */
internal class LiteRtAiRuntime(
    private val bridge: LiteRtRuntimeBridge,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val afterCancellationHandleCapture: (suspend () -> Unit)? = null,
    private val afterDriverRetirement: (() -> Unit)? = null,
) : AiRuntime {
    private data class Active(
        val request: AiGenerationRequest,
        val runtimeGeneration: LiteRtRuntimeGeneration,
        val events: Channel<AiGenerationEvent> = Channel(Channel.UNLIMITED),
        val nativeTermination: CompletableDeferred<LiteRtCleanupObservation> = CompletableDeferred(),
        var logicalTerminal: Boolean = false,
    )

    private val mutex = Mutex()
    private var prepared: Pair<AiModel, AiRuntimeConfig>? = null
    private var active: Active? = null
    private var closeFlight: CompletableDeferred<AiCloseResult>? = null
    private var state: AiRuntimeState = AiRuntimeState.NEW

    override suspend fun prepare(model: AiModel, config: AiRuntimeConfig): AiPrepareResult = mutex.withLock {
        when (state) {
            AiRuntimeState.NEW -> {
                when (val observation = bridge.prepare(model, config)) {
                    LiteRtReadinessObservation.Ready -> Unit
                    is LiteRtReadinessObservation.Failed -> throw AiCoreException.BackendFailure(
                        "LiteRT initialization failed.",
                        observation.cause,
                    )
                }
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
        val generation = mutex.withLock {
            when (state) {
                AiRuntimeState.READY -> Active(
                    request = request,
                    runtimeGeneration = bridge.startGeneration(request),
                ).also {
                    active = it
                    state = AiRuntimeState.GENERATING
                    startDriver(it)
                }
                else -> null
            }
        }
        if (generation == null) {
            emit(AiGenerationEvent.Failed(request.generationId, AiCoreException.InvalidState("Generation is not allowed while runtime state is $state.")))
            return@flow
        }
        emit(AiGenerationEvent.Started(request.generationId, request.metadata))
        for (event in generation.events) emit(event)
    }

    override fun cancel(generationId: AiGenerationId) {
        scope.launch {
            val runtimeGeneration = mutex.withLock {
                val current = active
                if (current == null || current.request.generationId != generationId || current.logicalTerminal) null
                else {
                    current.logicalTerminal = true
                    current.events.trySend(AiGenerationEvent.Cancelled(generationId))
                    current.runtimeGeneration
                }
            }
            if (runtimeGeneration != null) {
                afterCancellationHandleCapture?.invoke()
                runtimeGeneration.cancel()
            }
        }
    }

    override suspend fun close(): AiCloseResult {
        val election = mutex.withLock {
            when (state) {
                AiRuntimeState.CLOSED -> return AiCloseResult.AlreadyClosed
                AiRuntimeState.POISONED -> return AiCloseResult.Unconfirmed(AiCoreException.UnconfirmedNativeCleanup())
                AiRuntimeState.CLOSING -> CloseElection.Join(checkNotNull(closeFlight))
                else -> if (scope.coroutineContext[Job]?.isActive == false) {
                    state = AiRuntimeState.POISONED
                    return AiCloseResult.Unconfirmed(AiCoreException.UnconfirmedNativeCleanup())
                } else {
                    val flight = CompletableDeferred<AiCloseResult>()
                    closeFlight = flight
                    state = AiRuntimeState.CLOSING
                    try {
                        // The owner starts independently of this caller, but its first mutex
                        // acquisition cannot run until this publication transaction is complete.
                        val owner = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            runCloseOwner(flight)
                        }
                        if (owner.isCancelled && !flight.isCompleted) {
                            state = AiRuntimeState.POISONED
                            flight.complete(
                                AiCloseResult.Unconfirmed(AiCoreException.UnconfirmedNativeCleanup()),
                            )
                        }
                    } catch (failure: Throwable) {
                        state = AiRuntimeState.POISONED
                        flight.complete(AiCloseResult.Unconfirmed(AiCoreException.UnconfirmedNativeCleanup(failure)))
                    }
                    CloseElection.Owner(flight)
                }
            }
        }

        return election.flight.await()
    }

    /** A close flight has one native-cleanup owner; all other callers only await it. */
    private sealed interface CloseElection {
        val flight: CompletableDeferred<AiCloseResult>

        data class Owner(override val flight: CompletableDeferred<AiCloseResult>) : CloseElection
        data class Join(override val flight: CompletableDeferred<AiCloseResult>) : CloseElection
    }

    private suspend fun runCloseOwner(flight: CompletableDeferred<AiCloseResult>) {
        try {
            val running = mutex.withLock {
                active?.also {
                    if (!it.logicalTerminal) {
                        it.logicalTerminal = true
                        it.events.trySend(AiGenerationEvent.Cancelled(it.request.generationId))
                    }
                }
            }
            running?.runtimeGeneration?.cancel()
            when (val activeCleanup = running?.nativeTermination?.await()) {
                null, LiteRtCleanupObservation.Confirmed -> Unit
                is LiteRtCleanupObservation.Unconfirmed -> throw activeCleanup.cause
            }
            when (val cleanup = bridge.close()) {
                LiteRtCleanupObservation.Confirmed -> Unit
                is LiteRtCleanupObservation.Unconfirmed -> throw cleanup.cause
            }
            completeCloseFlight(flight, null)
        } catch (failure: Throwable) {
            completeCloseFlight(flight, failure)
        }
    }

    private suspend fun completeCloseFlight(flight: CompletableDeferred<AiCloseResult>, failure: Throwable?) {
        withContext(NonCancellable) {
            val result = if (failure == null) AiCloseResult.Released
            else AiCloseResult.Unconfirmed(AiCoreException.UnconfirmedNativeCleanup(failure))
            mutex.withLock {
                if (closeFlight !== flight || flight.isCompleted) return@withLock
                state = if (failure == null) AiRuntimeState.CLOSED else AiRuntimeState.POISONED
                flight.complete(result)
            }
        }
    }

    private fun startDriver(generation: Active) {
        scope.launch {
            val id = generation.request.generationId
            var failure: Throwable? = null
            try {
                generation.runtimeGeneration.responses.collect { delta ->
                    val emit = mutex.withLock { !generation.logicalTerminal }
                    if (emit) generation.events.trySend(AiGenerationEvent.Delta(id, delta))
                }
                mutex.withLock {
                    if (!generation.logicalTerminal) {
                        generation.logicalTerminal = true
                        generation.events.trySend(AiGenerationEvent.Completed(id))
                    }
                }
            } catch (error: Throwable) {
                failure = error
                mutex.withLock {
                    if (!generation.logicalTerminal) {
                        generation.logicalTerminal = true
                        generation.events.trySend(AiGenerationEvent.Failed(id, AiCoreException.BackendFailure("LiteRT generation failed.", error)))
                    }
                }
            } finally {
                val cleanup = try {
                    bridge.awaitNativeTermination(id)
                } catch (error: Throwable) {
                    LiteRtCleanupObservation.Unconfirmed(error)
                }
                try {
                    when (cleanup) {
                        LiteRtCleanupObservation.Confirmed -> {
                            generation.events.trySend(AiGenerationEvent.NativeTerminated(id))
                        }
                        is LiteRtCleanupObservation.Unconfirmed -> {
                            failure = cleanup.cause
                            mutex.withLock { state = AiRuntimeState.POISONED }
                            generation.events.trySend(
                                AiGenerationEvent.Failed(
                                    id,
                                    AiCoreException.UnconfirmedNativeCleanup(cleanup.cause),
                                ),
                            )
                        }
                    }
                } finally {
                    generation.nativeTermination.complete(cleanup)
                    // Terminal failures are delivered as typed events. Closing normally keeps a
                    // collector from mistaking delivery of Unconfirmed for proof it was observed.
                    generation.events.close()
                    mutex.withLock {
                        if (active === generation) active = null
                        if (state == AiRuntimeState.GENERATING) state = AiRuntimeState.READY
                    }
                    afterDriverRetirement?.invoke()
                }
            }
        }
    }
}
