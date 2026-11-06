package com.negi.aicore

import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Backend-neutral, text-only inference runtime contract. */
interface AiRuntime {
    /**
     * Prepares this runtime for [model] and [config]. Repeating an equivalent call is idempotent.
     * A different model or configuration requires closing this runtime and creating a new one.
     */
    suspend fun prepare(model: AiModel, config: AiRuntimeConfig): AiPrepareResult

    /**
     * Starts one generation identified by [AiGenerationRequest.generationId].
     * [AiGenerationEvent.Completed] is logical completion only; native termination is separately
     * reported by [AiGenerationEvent.NativeTerminated].
     */
    fun generate(request: AiGenerationRequest): Flow<AiGenerationEvent>

    /**
     * Requests cancellation for an active generation. Returning from this method is not proof that
     * native execution has stopped or that the runtime is reusable.
     */
    fun cancel(generationId: AiGenerationId)

    /**
     * Releases the runtime. A [AiCloseResult.Released] result confirms cleanup; an
     * [AiCloseResult.Unconfirmed] result leaves the runtime poisoned and unusable.
     */
    suspend fun close(): AiCloseResult
}

data class AiModel(
    val identifier: String,
)

data class AiSamplingConfig(
    val temperature: Double? = null,
    val topK: Int? = null,
    val topP: Double? = null,
)

/** Deliberately small: backend-specific configuration belongs in backend modules. */
data class AiRuntimeConfig(
    val sampling: AiSamplingConfig = AiSamplingConfig(),
)

/**
 * The caller creates and owns this identity before requesting a Flow, allowing cancellation even
 * when the request has not reached the backend yet. One ID maps to exactly one generation attempt.
 */
@JvmInline
value class AiGenerationId(val value: String) {
    companion object {
        fun create(): AiGenerationId = AiGenerationId(UUID.randomUUID().toString())
    }
}

data class AiGenerationRequest(
    val generationId: AiGenerationId,
    val prompt: String,
    val metadata: AiGenerationMetadata = AiGenerationMetadata(),
    /** Optional caller-visible logical deadline; it is not proof of native termination. */
    val timeoutMillis: Long? = null,
)

data class AiGenerationMetadata(
    val attributes: Map<String, String> = emptyMap(),
)

sealed interface AiGenerationEvent {
    val generationId: AiGenerationId

    data class Started(
        override val generationId: AiGenerationId,
        val metadata: AiGenerationMetadata,
    ) : AiGenerationEvent

    data class Delta(
        override val generationId: AiGenerationId,
        val text: String,
    ) : AiGenerationEvent

    /** The backend has logically completed the response; cleanup may still be in progress. */
    data class Completed(
        override val generationId: AiGenerationId,
    ) : AiGenerationEvent

    data class Cancelled(
        override val generationId: AiGenerationId,
    ) : AiGenerationEvent

    data class Failed(
        override val generationId: AiGenerationId,
        val error: AiCoreException,
    ) : AiGenerationEvent

    /** The backend has confirmed that native work for this generation has terminated. */
    data class NativeTerminated(
        override val generationId: AiGenerationId,
    ) : AiGenerationEvent
}

sealed interface AiPrepareResult {
    data class Ready(val reusedPreparedRuntime: Boolean) : AiPrepareResult
}

sealed interface AiCloseResult {
    data object Released : AiCloseResult
    data object AlreadyClosed : AiCloseResult
    data class Unconfirmed(val error: AiCoreException.UnconfirmedNativeCleanup) : AiCloseResult
}

enum class AiRuntimeState {
    NEW,
    READY,
    GENERATING,
    CLOSING,
    CLOSED,
    POISONED,
}

sealed class AiCoreException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause) {
    class InvalidState(message: String) : AiCoreException(message)
    class ConcurrentGeneration : AiCoreException("An AI generation is already active.")
    class GenerationCancelled : AiCoreException("The AI generation was cancelled.")
    class Timeout : AiCoreException("The AI generation timed out.")
    class BackendFailure(message: String, cause: Throwable? = null) : AiCoreException(message, cause)
    class UnconfirmedNativeCleanup(cause: Throwable? = null) : AiCoreException(
        "Native runtime cleanup could not be confirmed; the runtime is unusable.",
        cause,
    )
}
