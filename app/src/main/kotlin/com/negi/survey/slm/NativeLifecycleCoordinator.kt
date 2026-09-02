package com.negi.survey.slm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal const val NATIVE_RUNTIME_POISON_MESSAGE =
    "LiteRT-LM native runtime teardown could not be confirmed; process restart is required."

/** Stable failure published when native resource destruction cannot be confirmed. */
internal class NativeRuntimePoisonedException(
    cause: Throwable? = null,
) : IllegalStateException(NATIVE_RUNTIME_POISON_MESSAGE, cause)

/**
 * Pure lifecycle authorization state for the process-wide LiteRT-LM runtime.
 *
 * Phase 2 must use the lock order session mutex -> external state mutex -> this
 * coordinator's transition lock. The coordinator never acquires or waits for an
 * external mutex, and native operations must run without any of those locks held.
 */
internal class NativeLifecycleCoordinator {

    internal enum class Status {
        HEALTHY,
        TEARDOWN_IN_PROGRESS,
        POISONED,
    }

    internal enum class TeardownMode {
        CONVERSATION_REPLACEMENT,
        FULL_TEARDOWN,
        FAILED_ENGINE_REPLACEMENT,
    }

    internal enum class EngineLeaseRejection {
        ANOTHER_CREATION_IN_PROGRESS,
        TEARDOWN_IN_PROGRESS,
        POISONED,
    }

    /**
     * Opaque identities captured by a teardown operation.
     *
     * The coordinator compares close identities by reference, never by equals().
     */
    internal class TeardownMetadata(
        internal val runtimeIdentity: Any,
        closeIdentities: List<Any>,
        internal val replacementIdentity: Any? = null,
    ) {
        internal val closeIdentities: List<Any> = closeIdentities.toList()

        init {
            require(this.closeIdentities.isNotEmpty()) {
                "A teardown flight requires at least one close identity."
            }
            require(
                this.closeIdentities.indices.none { left ->
                    this.closeIdentities.indices.any { right ->
                        left < right &&
                            this.closeIdentities[left] === this.closeIdentities[right]
                    }
                }
            ) {
                "A teardown flight cannot contain the same close identity twice."
            }
        }
    }

    /** Opaque exclusive authorization for one Engine creation attempt. */
    internal class EngineCreationLease internal constructor(
        internal val initializationIdentity: Any,
    )

    /** Opaque shared identity for one teardown lifecycle operation. */
    internal class TeardownFlight internal constructor(
        internal val initialMode: TeardownMode,
        internal val metadata: TeardownMetadata,
        completion: Deferred<TeardownOutcome>,
    ) {
        internal val completion: Deferred<TeardownOutcome> = completion
    }

    /** Opaque one-shot authorization to publish a flight-owned replacement. */
    internal class ReplacementAuthorization internal constructor(
        internal val flight: TeardownFlight,
    )

    internal sealed interface EngineLeaseResult {
        data class Acquired(
            val lease: EngineCreationLease,
        ) : EngineLeaseResult

        data class Existing(
            val lease: EngineCreationLease,
        ) : EngineLeaseResult

        data class Rejected(
            val reason: EngineLeaseRejection,
            val poisonError: NativeRuntimePoisonedException? = null,
        ) : EngineLeaseResult
    }

    internal sealed interface TeardownStartResult {
        data class Started(
            val flight: TeardownFlight,
        ) : TeardownStartResult

        /**
         * The same process-wide runtime identity already owns a lifecycle flight.
         *
         * This does not prove that the existing flight covers a new caller's requested
         * teardown mode or resources. Such callers must join and then re-evaluate.
         */
        data class ExistingSameRuntime(
            val flight: TeardownFlight,
        ) : TeardownStartResult

        data object BusyOtherRuntime : TeardownStartResult

        data object EngineCreationInProgress : TeardownStartResult

        data class Poisoned(
            val error: NativeRuntimePoisonedException,
        ) : TeardownStartResult

        data object InvalidOwner : TeardownStartResult
    }

    internal sealed interface TeardownOutcome {
        data object ReplacementPublished : TeardownOutcome

        data object FullTeardownCompleted : TeardownOutcome

        data object TeardownCompletedWithoutReplacement : TeardownOutcome

        data class Poisoned(
            val error: NativeRuntimePoisonedException,
        ) : TeardownOutcome
    }

    /**
     * One-shot waiter notification produced by a completed lifecycle transition.
     *
     * The lifecycle owner must invoke [notifyWaiters] only after releasing its
     * external state mutex and session mutex. Notification never changes coordinator
     * state.
     */
    internal class TerminalSignal internal constructor(
        private val completion: CompletableDeferred<TeardownOutcome>,
        internal val outcome: TeardownOutcome,
    ) {
        private val notified = AtomicBoolean(false)

        internal fun notifyWaiters(): Boolean {
            if (!notified.compareAndSet(false, true)) return false
            completion.complete(outcome)
            return true
        }
    }

    /** Immutable diagnostic view; it never exposes the coordinator's mutable state record. */
    internal data class Snapshot(
        val status: Status,
        val hasEngineCreationLease: Boolean,
        val teardownFlight: TeardownFlight?,
        val teardownMode: TeardownMode?,
        val poisonError: NativeRuntimePoisonedException?,
    )

    private sealed interface State

    private data class Healthy(
        val engineCreationLease: EngineCreationLease?,
    ) : State

    private data class TeardownInProgress(
        val flight: TeardownFlight,
        val completion: CompletableDeferred<TeardownOutcome>,
        val mode: TeardownMode,
        val requiredCloseIdentities: List<Any>,
        val confirmedCloseIndices: Set<Int>,
        val replacementAuthorization: ReplacementAuthorization?,
    ) : State

    private data class Poisoned(
        val error: NativeRuntimePoisonedException,
    ) : State

    private val transitionLock = Any()
    private val state = AtomicReference<State>(Healthy(engineCreationLease = null))

    /** Lock-free poison observation for callers that must fail before taking other locks. */
    internal fun poisonErrorOrNull(): NativeRuntimePoisonedException? =
        (state.get() as? Poisoned)?.error

    internal fun snapshot(): Snapshot {
        val current = state.get()
        return when (current) {
            is Healthy ->
                Snapshot(
                    status = Status.HEALTHY,
                    hasEngineCreationLease = current.engineCreationLease != null,
                    teardownFlight = null,
                    teardownMode = null,
                    poisonError = null,
                )

            is TeardownInProgress ->
                Snapshot(
                    status = Status.TEARDOWN_IN_PROGRESS,
                    hasEngineCreationLease = false,
                    teardownFlight = current.flight,
                    teardownMode = current.mode,
                    poisonError = null,
                )

            is Poisoned ->
                Snapshot(
                    status = Status.POISONED,
                    hasEngineCreationLease = false,
                    teardownFlight = null,
                    teardownMode = null,
                    poisonError = current.error,
                )
        }
    }

    /**
     * Acquire or recognize the exclusive cold Engine-creation lease.
     *
     * [onLeasePublished] runs under the transition lock after the exact lease is
     * installed. It may only record caller-local ownership; it must not suspend,
     * acquire external locks, call native code, or throw.
     */
    internal fun acquireEngineCreationLease(
        initializationIdentity: Any,
        onLeasePublished: (EngineCreationLease) -> Unit = {},
    ): EngineLeaseResult =
        synchronized(transitionLock) {
            when (val current = state.get()) {
                is Healthy -> {
                    val existing = current.engineCreationLease
                    when {
                        existing == null -> {
                            val lease = EngineCreationLease(initializationIdentity)
                            state.set(Healthy(engineCreationLease = lease))
                            onLeasePublished(lease)
                            EngineLeaseResult.Acquired(lease)
                        }

                        existing.initializationIdentity === initializationIdentity -> {
                            onLeasePublished(existing)
                            EngineLeaseResult.Existing(existing)
                        }

                        else ->
                            EngineLeaseResult.Rejected(
                                EngineLeaseRejection.ANOTHER_CREATION_IN_PROGRESS
                            )
                    }
                }

                is TeardownInProgress ->
                    EngineLeaseResult.Rejected(
                        EngineLeaseRejection.TEARDOWN_IN_PROGRESS
                    )

                is Poisoned ->
                    EngineLeaseResult.Rejected(
                        reason = EngineLeaseRejection.POISONED,
                        poisonError = current.error,
                    )
            }
        }

    /**
     * Publish a fully-created Engine/Conversation pair and release the exact lease.
     *
     * Phase 2 must acquire its session mutex and external state mutex before calling
     * this method. [publish] runs at the coordinator transition's linearization point
     * and may only perform the short external mutation protected by that already-held
     * state mutex. It must not acquire the state mutex, suspend, call native code, wait,
     * or throw after making externally visible changes. If it throws before publishing,
     * the exact Engine lease remains installed. [onCommitted] runs immediately after
     * the coordinator publishes Healthy and must only record caller-local ownership.
     */
    internal fun completeEnginePublication(
        lease: EngineCreationLease,
        onCommitted: () -> Unit = {},
        publish: () -> Unit,
    ): Boolean =
        synchronized(transitionLock) {
            val current = state.get() as? Healthy ?: return@synchronized false
            if (current.engineCreationLease !== lease) return@synchronized false

            publish()
            state.set(Healthy(engineCreationLease = null))
            onCommitted()
            true
        }

    /** Release the exact lease when failure occurred before any native Engine existed. */
    internal fun failEngineCreationBeforeNativeState(
        lease: EngineCreationLease,
    ): Boolean =
        synchronized(transitionLock) {
            val current = state.get() as? Healthy ?: return@synchronized false
            if (current.engineCreationLease !== lease) return@synchronized false

            state.set(Healthy(engineCreationLease = null))
            true
        }

    /**
     * Poison native lifecycle ownership when Engine construction may have begun but
     * no closeable Engine reference was returned.
     */
    internal fun poisonEngineCreationLease(
        lease: EngineCreationLease,
        cause: Throwable,
    ): NativeRuntimePoisonedException? =
        synchronized(transitionLock) {
            val current = state.get() as? Healthy ?: return@synchronized null
            if (current.engineCreationLease !== lease) return@synchronized null

            val error = NativeRuntimePoisonedException(cause)
            state.set(Poisoned(error))
            error
        }

    /**
     * Atomically convert a failed Engine lease into teardown ownership.
     * No unrestricted Healthy state is published between these states.
     * [onStarted] has the same non-throwing, caller-local-only contract as the
     * Engine-lease publication callback.
     */
    internal fun convertFailedEngineLeaseToTeardown(
        lease: EngineCreationLease,
        metadata: TeardownMetadata,
        onStarted: (TeardownFlight) -> Unit = {},
    ): TeardownStartResult =
        synchronized(transitionLock) {
            val current = state.get() as? Healthy
                ?: return@synchronized invalidLeaseTransitionResultLocked(
                    requestedRuntimeIdentity = metadata.runtimeIdentity
                )

            if (current.engineCreationLease !== lease) {
                return@synchronized TeardownStartResult.InvalidOwner
            }

            startFlightLocked(
                mode = TeardownMode.FAILED_ENGINE_REPLACEMENT,
                metadata = metadata,
                onStarted = onStarted,
            )
        }

    /**
     * Start teardown only from unrestricted Healthy state.
     *
     * [onStarted] runs under the transition lock after the exact flight is
     * installed. It may only record caller-local ownership; it must not suspend,
     * acquire external locks, call native code, or throw.
     */
    internal fun startTeardown(
        mode: TeardownMode,
        metadata: TeardownMetadata,
        onStarted: (TeardownFlight) -> Unit = {},
    ): TeardownStartResult =
        synchronized(transitionLock) {
            when (val current = state.get()) {
                is Healthy -> {
                    if (current.engineCreationLease != null) {
                        TeardownStartResult.EngineCreationInProgress
                    } else {
                        startFlightLocked(mode, metadata, onStarted)
                    }
                }

                is TeardownInProgress ->
                    existingFlightResultLocked(
                        current = current,
                        requestedRuntimeIdentity = metadata.runtimeIdentity,
                    )

                is Poisoned ->
                    TeardownStartResult.Poisoned(current.error)
            }
        }

    /** Record one exact native close returning successfully. */
    internal fun confirmClose(
        flight: TeardownFlight,
        closeIdentity: Any,
    ): Boolean =
        synchronized(transitionLock) {
            val current = exactFlightLocked(flight) ?: return@synchronized false
            val index = current.requiredCloseIdentities.indexOfIdentity(closeIdentity)
            if (index < 0 || index in current.confirmedCloseIndices) {
                return@synchronized false
            }

            state.set(
                current.copy(
                    confirmedCloseIndices = current.confirmedCloseIndices + index
                )
            )
            true
        }

    /** Publish poison only while the exact expected close remains unconfirmed. */
    internal fun poisonIfCloseUnconfirmed(
        flight: TeardownFlight,
        closeIdentity: Any,
        cause: Throwable? = null,
    ): TerminalSignal? =
        synchronized(transitionLock) {
            val current = exactFlightLocked(flight) ?: return@synchronized null
            val index = current.requiredCloseIdentities.indexOfIdentity(closeIdentity)
            if (index < 0 || index in current.confirmedCloseIndices) {
                return@synchronized null
            }

            poisonLocked(current, cause)
        }

    /** A close exception leaves native destruction unconfirmed and poisons the runtime. */
    internal fun recordCloseException(
        flight: TeardownFlight,
        closeIdentity: Any,
        cause: Throwable,
    ): TerminalSignal? = poisonIfCloseUnconfirmed(flight, closeIdentity, cause)

    /** Publish poison for another unrecoverable failure owned by the exact flight. */
    internal fun poisonFlight(
        flight: TeardownFlight,
        cause: Throwable? = null,
    ): TerminalSignal? =
        synchronized(transitionLock) {
            val current = exactFlightLocked(flight) ?: return@synchronized null
            poisonLocked(current, cause)
        }

    /** Claim the exact flight's single replacement-publication authorization. */
    internal fun authorizeReplacement(
        flight: TeardownFlight,
    ): ReplacementAuthorization? =
        synchronized(transitionLock) {
            val current = exactFlightLocked(flight) ?: return@synchronized null
            if (current.mode == TeardownMode.FULL_TEARDOWN) return@synchronized null
            if (!current.allClosesConfirmed()) return@synchronized null
            if (current.replacementAuthorization != null) return@synchronized null

            val authorization = ReplacementAuthorization(flight)
            state.set(current.copy(replacementAuthorization = authorization))
            authorization
        }

    /**
     * Publish a replacement and Healthy as one authorized transition.
     *
     * Phase 2 must acquire its session mutex and external state mutex before calling
     * this method. [publish] runs at the coordinator transition's linearization point
     * and may only perform the short external mutation protected by that already-held
     * state mutex. It must not acquire the state mutex, suspend, call native code, wait,
     * or throw after making externally visible changes. If it throws before publishing,
     * the exact flight remains installed and no successful terminal signal is produced.
     * [onCommitted] records caller-local ownership immediately after Healthy is published;
     * it must be short, non-blocking, and non-throwing.
     */
    internal fun finalizeReplacementPublication(
        authorization: ReplacementAuthorization,
        onCommitted: (TerminalSignal) -> Unit = {},
        publish: () -> Unit,
    ): TerminalSignal? =
        synchronized(transitionLock) {
            val current = exactFlightLocked(authorization.flight)
                ?: return@synchronized null
            if (current.replacementAuthorization !== authorization) {
                return@synchronized null
            }
            if (!current.allClosesConfirmed()) return@synchronized null

            val signal = TerminalSignal(
                completion = current.completion,
                outcome = TeardownOutcome.ReplacementPublished,
            )
            publish()
            state.set(Healthy(engineCreationLease = null))
            onCommitted(signal)
            signal
        }

    /** Complete a full teardown only after every required close returned successfully. */
    internal fun completeFullTeardown(
        flight: TeardownFlight,
    ): TerminalSignal? =
        synchronized(transitionLock) {
            val current = exactFlightLocked(flight) ?: return@synchronized null
            if (current.mode != TeardownMode.FULL_TEARDOWN) return@synchronized null
            if (!current.allClosesConfirmed()) return@synchronized null

            state.set(Healthy(engineCreationLease = null))
            TerminalSignal(
                completion = current.completion,
                outcome = TeardownOutcome.FullTeardownCompleted,
            )
        }

    /**
     * Complete failed-Engine teardown when no replacement native state was created.
     *
     * Conversation replacement cannot use this terminal path; its failure must escalate
     * to full Engine teardown.
     */
    internal fun completeFailedEngineTeardownWithoutReplacement(
        flight: TeardownFlight,
    ): TerminalSignal? =
        synchronized(transitionLock) {
            val current = exactFlightLocked(flight) ?: return@synchronized null
            if (current.mode != TeardownMode.FAILED_ENGINE_REPLACEMENT) {
                return@synchronized null
            }
            if (!current.allClosesConfirmed()) return@synchronized null

            state.set(Healthy(engineCreationLease = null))
            TerminalSignal(
                completion = current.completion,
                outcome = TeardownOutcome.TeardownCompletedWithoutReplacement,
            )
        }

    /**
     * Keep the same flight while escalating failed replacement creation to full teardown.
     * A previously issued replacement authorization becomes invalid immediately.
     */
    internal fun escalateReplacementFailureToFullTeardown(
        flight: TeardownFlight,
        additionalCloseIdentity: Any,
    ): Boolean =
        synchronized(transitionLock) {
            val current = exactFlightLocked(flight) ?: return@synchronized false
            if (current.mode == TeardownMode.FULL_TEARDOWN) return@synchronized false
            if (!current.allClosesConfirmed()) return@synchronized false
            if (current.requiredCloseIdentities.indexOfIdentity(additionalCloseIdentity) >= 0) {
                return@synchronized false
            }

            state.set(
                current.copy(
                    mode = TeardownMode.FULL_TEARDOWN,
                    requiredCloseIdentities =
                        current.requiredCloseIdentities + additionalCloseIdentity,
                    replacementAuthorization = null,
                )
            )
            true
        }

    private fun startFlightLocked(
        mode: TeardownMode,
        metadata: TeardownMetadata,
        onStarted: (TeardownFlight) -> Unit,
    ): TeardownStartResult.Started {
        val completion = CompletableDeferred<TeardownOutcome>()
        val flight =
            TeardownFlight(
                initialMode = mode,
                metadata = metadata,
                completion = completion,
            )

        state.set(
            TeardownInProgress(
                flight = flight,
                completion = completion,
                mode = mode,
                requiredCloseIdentities = metadata.closeIdentities,
                confirmedCloseIndices = emptySet(),
                replacementAuthorization = null,
            )
        )
        onStarted(flight)
        return TeardownStartResult.Started(flight)
    }

    private fun invalidLeaseTransitionResultLocked(
        requestedRuntimeIdentity: Any,
    ): TeardownStartResult =
        when (val current = state.get()) {
            is TeardownInProgress ->
                existingFlightResultLocked(current, requestedRuntimeIdentity)
            is Poisoned -> TeardownStartResult.Poisoned(current.error)
            is Healthy -> TeardownStartResult.InvalidOwner
        }

    private fun existingFlightResultLocked(
        current: TeardownInProgress,
        requestedRuntimeIdentity: Any,
    ): TeardownStartResult =
        if (current.flight.metadata.runtimeIdentity === requestedRuntimeIdentity) {
            TeardownStartResult.ExistingSameRuntime(current.flight)
        } else {
            TeardownStartResult.BusyOtherRuntime
        }

    private fun exactFlightLocked(
        flight: TeardownFlight,
    ): TeardownInProgress? {
        val current = state.get() as? TeardownInProgress ?: return null
        return current.takeIf { it.flight === flight }
    }

    private fun poisonLocked(
        current: TeardownInProgress,
        cause: Throwable?,
    ): TerminalSignal {
        val error = NativeRuntimePoisonedException(cause)
        state.set(Poisoned(error))
        return TerminalSignal(
            completion = current.completion,
            outcome = TeardownOutcome.Poisoned(error),
        )
    }

    private fun TeardownInProgress.allClosesConfirmed(): Boolean =
        confirmedCloseIndices.size == requiredCloseIdentities.size

    private fun List<Any>.indexOfIdentity(identity: Any): Int =
        indices.firstOrNull { this[it] === identity } ?: -1
}
