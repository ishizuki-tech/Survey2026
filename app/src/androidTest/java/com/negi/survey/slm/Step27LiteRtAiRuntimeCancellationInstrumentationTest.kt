package com.negi.survey.slm

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.negi.aicore.AiCloseResult
import com.negi.aicore.AiGenerationEvent
import com.negi.aicore.AiGenerationId
import com.negi.aicore.AiGenerationRequest
import com.negi.aicore.AiModel
import com.negi.aicore.AiPrepareResult
import com.negi.aicore.AiRuntimeConfig
import com.negi.survey.ModelAssetRule
import com.negi.survey.SurveyApp
import com.negi.survey.config.SurveyConfig
import com.negi.survey.config.SurveyConfigLoader
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device T3-T5 checks for generation-scoped cancellation.
 *
 * [RecordingBridge] is test-only: it delegates to the existing repository and exposes the
 * existing internal generation handle for evidence. It creates no Engine, Conversation, gate, or
 * coordinator. Native cancellation-target invocation remains repository-internal, so this test
 * reports only the run ID that the repository actually publishes and does not claim a separately
 * observable native cancel-call argument.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class Step27LiteRtAiRuntimeCancellationInstrumentationTest {

    @get:Rule
    val modelRule = ModelAssetRule()

    @Test
    fun t3_active_generation_cancellation_reaches_confirmed_cleanup() {
        runBlocking {
            val fixture = createFixture()
            try {
                prepare(fixture)
                val generationId = AiGenerationId.create()
                val events = mutableListOf<AiGenerationEvent>()
                var cancellationRequested = false

                withTimeout(GENERATION_TEST_TIMEOUT_MS) {
                    fixture.runtime
                        .generate(AiGenerationRequest(generationId, ACTIVE_CANCEL_PROMPT))
                        .collect { event ->
                            events += event
                            if (event is AiGenerationEvent.Delta && !cancellationRequested) {
                                cancellationRequested = true
                                fixture.runtime.cancel(generationId)
                            }
                        }
                }

                assertTrue("T3 must observe a real native delta before cancellation", cancellationRequested)
                assertCancelledBeforeNativeTermination(events, generationId)
                assertEquals(1, fixture.bridge.cancelInvocationCount())
                logGeneration("T3", fixture.bridge.requireGeneration(), events)
            } finally {
                assertEquals(AiCloseResult.Released, fixture.runtime.close())
            }
        }
    }

    @Test
    fun t4_pre_start_cancellation_is_latched_until_repository_startup() {
        runBlocking {
            val startBarrier = CompletableDeferred<Unit>()
            val fixture = createFixture(startBarrier)
            try {
                prepare(fixture)
                val generationId = AiGenerationId.create()
                val started = CompletableDeferred<Unit>()
                val events = mutableListOf<AiGenerationEvent>()
                val collector = async {
                    fixture.runtime
                        .generate(AiGenerationRequest(generationId, ACTIVE_CANCEL_PROMPT))
                        .collect { event ->
                            events += event
                            if (event is AiGenerationEvent.Started) started.complete(Unit)
                        }
                }

                withTimeout(TEST_SYNC_TIMEOUT_MS) { started.await() }
                fixture.runtime.cancel(generationId)
                withTimeout(TEST_SYNC_TIMEOUT_MS) { fixture.bridge.cancellationObserved.await() }
                assertFalse("Repository must remain behind the deterministic pre-start barrier", startBarrier.isCompleted)

                startBarrier.complete(Unit)
                withTimeout(GENERATION_TEST_TIMEOUT_MS) { collector.await() }

                assertCancelledBeforeNativeTermination(events, generationId)
                assertEquals(1, fixture.bridge.cancelInvocationCount())
                logGeneration("T4", fixture.bridge.requireGeneration(), events)
            } finally {
                startBarrier.complete(Unit)
                assertEquals(AiCloseResult.Released, fixture.runtime.close())
            }
        }
    }

    @Test
    fun t5_repeated_and_stale_cancellation_are_generation_scoped() {
        runBlocking {
            val fixture = createFixture()
            try {
                prepare(fixture)
                val firstId = AiGenerationId.create()
                val firstEvents = mutableListOf<AiGenerationEvent>()
                var repeatedCancellationIssued = false
                withTimeout(GENERATION_TEST_TIMEOUT_MS) {
                    fixture.runtime
                        .generate(AiGenerationRequest(firstId, ACTIVE_CANCEL_PROMPT))
                        .collect { event ->
                            firstEvents += event
                            if (event is AiGenerationEvent.Delta && !repeatedCancellationIssued) {
                                repeatedCancellationIssued = true
                                fixture.runtime.cancel(firstId)
                                fixture.runtime.cancel(firstId)
                                fixture.runtime.cancel(firstId)
                            }
                        }
                }
                assertTrue("T5 must reach an active generation before repeated cancellation", repeatedCancellationIssued)
                assertCancelledBeforeNativeTermination(firstEvents, firstId)
                assertEquals("Repeated cancellation must dispatch once", 1, fixture.bridge.cancelInvocationCount())
                val firstGeneration = fixture.bridge.requireGeneration()
                logGeneration("T5-first", firstGeneration, firstEvents)

                val secondId = AiGenerationId.create()
                val secondEvents = mutableListOf<AiGenerationEvent>()
                var staleCancelAttempted = false
                withTimeout(GENERATION_TEST_TIMEOUT_MS) {
                    fixture.runtime
                        .generate(AiGenerationRequest(secondId, NORMAL_PROMPT))
                        .collect { event ->
                            secondEvents += event
                            if (event is AiGenerationEvent.Started && !staleCancelAttempted) {
                                staleCancelAttempted = true
                                fixture.runtime.cancel(firstId)
                            }
                        }
                }

                assertTrue(staleCancelAttempted)
                assertTrue(secondEvents.any { it is AiGenerationEvent.Completed })
                assertTrue(secondEvents.any { it is AiGenerationEvent.NativeTerminated })
                assertFalse("A stale ID must not cancel the replacement generation", secondEvents.any { it is AiGenerationEvent.Cancelled })
                assertFalse("A stale ID must not dispatch a second backend cancel", fixture.bridge.cancelInvocationCount() > 1)
                logGeneration("T5-second", fixture.bridge.requireGeneration(), secondEvents)
            } finally {
                assertEquals(AiCloseResult.Released, fixture.runtime.close())
            }
        }
    }

    /**
     * T6 proves adapter collector isolation, not a duration claim about native-resource lifetime.
     * The barrier runs only after the real repository cleanup observation has returned.
     */
    @Test
    fun t6_collector_cancellation_does_not_dispatch_repository_cancel_or_permit_reuse() {
        runBlocking {
            val cleanupObservationBarrier = CompletableDeferred<Unit>()
            val fixture = createFixture(cleanupObservationBarrier = cleanupObservationBarrier)
            try {
                prepare(fixture)
                val firstId = AiGenerationId.create()
                val firstDelta = CompletableDeferred<Unit>()
                val collector = async {
                    fixture.runtime
                        .generate(AiGenerationRequest(firstId, ACTIVE_CANCEL_PROMPT))
                        .collect { event ->
                            if (event is AiGenerationEvent.Delta) firstDelta.complete(Unit)
                        }
                }

                withTimeout(TEST_SYNC_TIMEOUT_MS) { firstDelta.await() }
                collector.cancel()
                collector.join()

                assertEquals(
                    "Cancelling the adapter event collector must not dispatch repository cancellation.",
                    0,
                    fixture.bridge.cancelInvocationCount(),
                )
                val competing =
                    fixture.runtime
                        .generate(AiGenerationRequest(AiGenerationId.create(), NORMAL_PROMPT))
                        .toList()
                assertTrue(competing.single() is AiGenerationEvent.Failed)
                assertEquals("The competing request must not reach the repository bridge.", 1, fixture.bridge.startCount())

                fixture.runtime.cancel(firstId)
                withTimeout(TEST_SYNC_TIMEOUT_MS) { fixture.bridge.cancellationObserved.await() }
                val close = async { fixture.runtime.close() }
                assertEquals(
                    LiteRtCleanupObservation.Confirmed,
                    withTimeout(GENERATION_TEST_TIMEOUT_MS) { fixture.bridge.delegatedNativeCleanup.await() },
                )
                assertFalse("Adapter close must wait for its withheld cleanup observation.", close.isCompleted)

                cleanupObservationBarrier.complete(Unit)
                assertEquals(AiCloseResult.Released, withTimeout(CLOSE_TEST_TIMEOUT_MS) { close.await() })
                logGeneration("T6", fixture.bridge.requireGeneration(), emptyList())
            } finally {
                cleanupObservationBarrier.complete(Unit)
                fixture.runtime.close()
            }
        }
    }

    @Test
    fun t7_competing_generation_is_rejected_before_repository_start() {
        runBlocking {
            val fixture = createFixture()
            try {
                prepare(fixture)
                val firstId = AiGenerationId.create()
                val firstDelta = CompletableDeferred<Unit>()
                val firstEvents = mutableListOf<AiGenerationEvent>()
                val first = async {
                    fixture.runtime
                        .generate(AiGenerationRequest(firstId, ACTIVE_CANCEL_PROMPT))
                        .collect { event ->
                            firstEvents += event
                            if (event is AiGenerationEvent.Delta) firstDelta.complete(Unit)
                        }
                }

                withTimeout(TEST_SYNC_TIMEOUT_MS) { firstDelta.await() }
                val competing =
                    fixture.runtime
                        .generate(AiGenerationRequest(AiGenerationId.create(), NORMAL_PROMPT))
                        .toList()
                assertEquals(1, competing.size)
                assertTrue("T7 competing generation must fail locally.", competing.single() is AiGenerationEvent.Failed)
                assertEquals("T7 must start exactly one repository generation.", 1, fixture.bridge.startCount())

                fixture.runtime.cancel(firstId)
                withTimeout(GENERATION_TEST_TIMEOUT_MS) { first.await() }
                assertCancelledBeforeNativeTermination(firstEvents, firstId)
                assertEquals(
                    LiteRtCleanupObservation.Confirmed,
                    withTimeout(TEST_SYNC_TIMEOUT_MS) { fixture.bridge.delegatedNativeCleanup.await() },
                )
                logGeneration("T7", fixture.bridge.requireGeneration(), firstEvents)
            } finally {
                assertEquals(AiCloseResult.Released, fixture.runtime.close())
            }
        }
    }

    /**
     * T8 withholds only the adapter's post-cleanup observation. The repository cleanup has
     * already completed before the barrier is entered, so this does not claim native liveness.
     */
    @Test
    fun t8_completed_generation_remains_fenced_until_adapter_reports_native_termination() {
        runBlocking {
            val cleanupObservationBarrier = CompletableDeferred<Unit>()
            val fixture = createFixture(cleanupObservationBarrier = cleanupObservationBarrier)
            try {
                prepare(fixture)
                val firstId = AiGenerationId.create()
                val completed = CompletableDeferred<Unit>()
                val firstEvents = mutableListOf<AiGenerationEvent>()
                val first = async {
                    fixture.runtime
                        .generate(AiGenerationRequest(firstId, NORMAL_PROMPT))
                        .collect { event ->
                            firstEvents += event
                            if (event is AiGenerationEvent.Completed) completed.complete(Unit)
                        }
                }

                withTimeout(GENERATION_TEST_TIMEOUT_MS) { completed.await() }
                assertEquals(
                    LiteRtCleanupObservation.Confirmed,
                    withTimeout(GENERATION_TEST_TIMEOUT_MS) { fixture.bridge.delegatedNativeCleanup.await() },
                )
                assertFalse(firstEvents.any { it is AiGenerationEvent.NativeTerminated })

                val rejected =
                    fixture.runtime
                        .generate(AiGenerationRequest(AiGenerationId.create(), NORMAL_PROMPT))
                        .toList()
                assertEquals(1, rejected.size)
                assertTrue(rejected.single() is AiGenerationEvent.Failed)
                assertEquals(1, fixture.bridge.startCount())

                cleanupObservationBarrier.complete(Unit)
                withTimeout(TEST_SYNC_TIMEOUT_MS) { first.await() }
                assertTrue(firstEvents.any { it is AiGenerationEvent.NativeTerminated })

                val successorId = AiGenerationId.create()
                val successorEvents = mutableListOf<AiGenerationEvent>()
                withTimeout(GENERATION_TEST_TIMEOUT_MS) {
                    fixture.runtime
                        .generate(AiGenerationRequest(successorId, NORMAL_PROMPT))
                        .collect { successorEvents += it }
                }
                assertTrue(successorEvents.any { it is AiGenerationEvent.Completed })
                assertTrue(successorEvents.any { it is AiGenerationEvent.NativeTerminated })
                assertEquals(2, fixture.bridge.startCount())
                logGeneration("T8", fixture.bridge.requireGeneration(), successorEvents)
            } finally {
                cleanupObservationBarrier.complete(Unit)
                assertEquals(AiCloseResult.Released, fixture.runtime.close())
            }
        }
    }

    @Test
    fun t9_concurrent_close_calls_join_one_bridge_close_flight() {
        runBlocking {
            val closeBarrier = CompletableDeferred<Unit>()
            val fixture = createFixture(closeBarrier = closeBarrier)
            try {
                prepare(fixture)
                val generationId = AiGenerationId.create()
                val firstDelta = CompletableDeferred<Unit>()
                val generation = async {
                    fixture.runtime
                        .generate(AiGenerationRequest(generationId, ACTIVE_CANCEL_PROMPT))
                        .collect { event ->
                            if (event is AiGenerationEvent.Delta) firstDelta.complete(Unit)
                        }
                }
                withTimeout(TEST_SYNC_TIMEOUT_MS) { firstDelta.await() }

                val firstClose = async(start = CoroutineStart.UNDISPATCHED) { fixture.runtime.close() }
                withTimeout(GENERATION_TEST_TIMEOUT_MS) { fixture.bridge.bridgeCloseEntered.await() }
                assertFalse(firstClose.isCompleted)

                val secondClose = async(start = CoroutineStart.UNDISPATCHED) { fixture.runtime.close() }
                assertFalse("The second close must join the unresolved first close flight.", secondClose.isCompleted)
                assertEquals(1, fixture.bridge.bridgeCloseInvocationCount())
                assertEquals(
                    LiteRtCleanupObservation.Confirmed,
                    withTimeout(TEST_SYNC_TIMEOUT_MS) { fixture.bridge.delegatedNativeCleanup.await() },
                )

                closeBarrier.complete(Unit)
                assertEquals(AiCloseResult.Released, withTimeout(CLOSE_TEST_TIMEOUT_MS) { firstClose.await() })
                assertEquals(AiCloseResult.Released, withTimeout(CLOSE_TEST_TIMEOUT_MS) { secondClose.await() })
                withTimeout(TEST_SYNC_TIMEOUT_MS) { generation.await() }
                assertEquals(1, fixture.bridge.bridgeCloseInvocationCount())
                assertEquals(1, fixture.bridge.delegatedBridgeCloseCleanupCount())
            } finally {
                closeBarrier.complete(Unit)
                fixture.runtime.close()
            }
        }
    }

    /**
     * T10 injects an adapter observation failure only after real delegated cleanup succeeds.
     * It is intentionally not evidence of LiteRT native cleanup failure or recovery.
     */
    @Test
    fun t10_synthetic_unconfirmed_cleanup_poisons_and_fences_the_adapter() {
        runBlocking {
            val fixture = createFixture(
                syntheticCleanupFailure = IllegalStateException("T10 synthetic post-cleanup observation failure"),
            )
            try {
                prepare(fixture)
                val generationId = AiGenerationId.create()
                val events = mutableListOf<AiGenerationEvent>()
                withTimeout(GENERATION_TEST_TIMEOUT_MS) {
                    fixture.runtime
                        .generate(AiGenerationRequest(generationId, NORMAL_PROMPT))
                        .collect { events += it }
                }

                assertEquals(
                    LiteRtCleanupObservation.Confirmed,
                    withTimeout(TEST_SYNC_TIMEOUT_MS) { fixture.bridge.delegatedNativeCleanup.await() },
                )
                assertTrue(
                    "T10 must observe a real Delta for its generation before synthetic failure injection.",
                    events.any { it is AiGenerationEvent.Delta && it.generationId == generationId },
                )
                val realGeneration = fixture.bridge.requireGeneration()
                assertEquals(generationId, realGeneration.generationId)
                logGeneration("T10", realGeneration, events)
                assertFalse(events.any { it is AiGenerationEvent.NativeTerminated })
                assertTrue(
                    events.any {
                        it is AiGenerationEvent.Failed &&
                            it.error is com.negi.aicore.AiCoreException.UnconfirmedNativeCleanup
                    },
                )
                assertTrue(
                    fixture.runtime
                        .generate(AiGenerationRequest(AiGenerationId.create(), NORMAL_PROMPT))
                        .toList()
                        .single() is AiGenerationEvent.Failed,
                )
                var prepareRejected = false
                try {
                    fixture.runtime.prepare(AiModel(fixture.model.name), AiRuntimeConfig())
                } catch (_: IllegalStateException) {
                    prepareRejected = true
                }
                assertTrue("A poisoned runtime must reject prepare.", prepareRejected)
                assertTrue(fixture.runtime.close() is AiCloseResult.Unconfirmed)
            } finally {
                // The injected failure is post-cleanup; this remains an idempotent safety check.
                fixture.bridge.ensureDelegatedCleanup()
            }
        }
    }

    private suspend fun prepare(fixture: Fixture) {
        assertEquals(
            AiPrepareResult.Ready(reusedPreparedRuntime = false),
            withTimeout(PREPARE_TEST_TIMEOUT_MS) {
                fixture.runtime.prepare(AiModel(fixture.model.name), AiRuntimeConfig())
            },
        )
    }

    private fun createFixture(
        startBarrier: CompletableDeferred<Unit>? = null,
        cleanupObservationBarrier: CompletableDeferred<Unit>? = null,
        closeBarrier: CompletableDeferred<Unit>? = null,
        syntheticCleanupFailure: Throwable? = null,
    ): Fixture {
        val appContext =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val config =
            SurveyConfigLoader.fromAssets(appContext, "survey_config10.yaml").also {
                assertTrue("SurveyConfig invalid: ${it.validate()}", it.validate().isEmpty())
            }
        val model =
            Model(
                name = config.modelDefaults.defaultFileName?.substringBeforeLast('.')?.ifBlank { null } ?: "ondevice-slm",
                taskPath = modelRule.internalModel.absolutePath,
                config = buildModelConfig(config.slm),
            )
        SLM.setApplicationContext(appContext)
        val repository =
            LiteRtRepository(
                model = model,
                config = config,
                appContext = appContext,
                heavyRuntimeHandoff = (appContext as SurveyApp).heavyRuntimeCoordinator,
            )
        val bridge = RecordingBridge(
            repository = repository,
            model = model,
            startBarrier = startBarrier,
            cleanupObservationBarrier = cleanupObservationBarrier,
            closeBarrier = closeBarrier,
            syntheticCleanupFailure = syntheticCleanupFailure,
        )
        return Fixture(model, bridge, LiteRtAiRuntime(bridge))
    }

    private fun assertCancelledBeforeNativeTermination(
        events: List<AiGenerationEvent>,
        generationId: AiGenerationId,
    ) {
        assertTrue(events.all { it.generationId == generationId })
        val cancelled = events.indexOfFirst { it is AiGenerationEvent.Cancelled }
        val nativeTerminated = events.indexOfFirst { it is AiGenerationEvent.NativeTerminated }
        assertTrue("Cancellation is a logical event, not termination", cancelled in 0 until nativeTerminated)
        assertFalse(events.any { it is AiGenerationEvent.Failed })
    }

    private fun logGeneration(
        phase: String,
        generation: LiteRtRepositoryGeneration,
        events: List<AiGenerationEvent>,
    ) {
        val runId = generation.nativeRunIdOrNull()
        assertTrue("Native run ID must be published for $phase", runId != null)
        Log.i(
            TAG,
            "$phase generationId=${generation.generationId.value} requestId=${generation.requestId} " +
                "nativeRunId=$runId events=${events.map { it::class.simpleName }}",
        )
    }

    private data class Fixture(
        val model: Model,
        val bridge: RecordingBridge,
        val runtime: LiteRtAiRuntime,
    )

    private class RecordingBridge(
        private val repository: LiteRtRepository,
        private val model: Model,
        private val startBarrier: CompletableDeferred<Unit>?,
        private val cleanupObservationBarrier: CompletableDeferred<Unit>?,
        private val closeBarrier: CompletableDeferred<Unit>?,
        private val syntheticCleanupFailure: Throwable?,
    ) : LiteRtRuntimeBridge {
        private val cancellationCalls = AtomicInteger(0)
        private val starts = AtomicInteger(0)
        private val bridgeCloseCalls = AtomicInteger(0)
        private val delegatedBridgeCloseCleanups = AtomicInteger(0)
        val cancellationObserved = CompletableDeferred<Unit>()
        val delegatedNativeCleanup = CompletableDeferred<LiteRtCleanupObservation>()
        val bridgeCloseEntered = CompletableDeferred<Unit>()

        @Volatile
        private var generation: LiteRtRepositoryGeneration? = null

        override suspend fun prepare(model: AiModel, config: AiRuntimeConfig): LiteRtReadinessObservation =
            repository.prepareObservation()

        override fun startGeneration(request: AiGenerationRequest): LiteRtRuntimeGeneration {
            starts.incrementAndGet()
            val handle = repository.newObservedGeneration(request.generationId)
            generation = handle
            return object : LiteRtRuntimeGeneration {
                override val responses: Flow<String> = flow {
                    startBarrier?.await()
                    repository.requestObserved(handle, request.prompt).collect { emit(it) }
                }

                override fun cancel() {
                    cancellationCalls.incrementAndGet()
                    cancellationObserved.complete(Unit)
                    handle.cancel()
                }
            }
        }

        override suspend fun awaitNativeTermination(generationId: AiGenerationId): LiteRtCleanupObservation {
            val observation = repository.observeCleanup()
            delegatedNativeCleanup.complete(observation)
            cleanupObservationBarrier?.await()
            return syntheticCleanupFailure?.let(LiteRtCleanupObservation::Unconfirmed) ?: observation
        }

        override suspend fun close(): LiteRtCleanupObservation {
            bridgeCloseCalls.incrementAndGet()
            bridgeCloseEntered.complete(Unit)
            closeBarrier?.await()
            val observation = repository.observeCleanup()
            delegatedBridgeCloseCleanups.incrementAndGet()
            return observation
        }

        fun cancelInvocationCount(): Int = cancellationCalls.get()

        fun startCount(): Int = starts.get()

        fun bridgeCloseInvocationCount(): Int = bridgeCloseCalls.get()

        fun delegatedBridgeCloseCleanupCount(): Int = delegatedBridgeCloseCleanups.get()

        suspend fun ensureDelegatedCleanup() {
            repository.observeCleanup()
        }

        fun requireGeneration(): LiteRtRepositoryGeneration = checkNotNull(generation)
    }

    private companion object {
        const val TAG = "Step27LiteRtAiRuntimeCancel"
        const val PREPARE_TEST_TIMEOUT_MS = 120_000L
        const val GENERATION_TEST_TIMEOUT_MS = 180_000L
        const val CLOSE_TEST_TIMEOUT_MS = 30_000L
        const val TEST_SYNC_TIMEOUT_MS = 15_000L
        const val ACTIVE_CANCEL_PROMPT = "Explain the water cycle in detail, using several paragraphs."
        const val NORMAL_PROMPT = "Reply with only the word ready."
    }
}
