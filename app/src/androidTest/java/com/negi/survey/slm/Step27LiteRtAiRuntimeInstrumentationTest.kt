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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device Phase 1 verification for the app-local [LiteRtAiRuntime] adapter.
 *
 * This deliberately uses the existing [ModelAssetRule], [LiteRtRepository], process-wide
 * inference gate, and the target [SurveyApp]'s [com.negi.survey.runtime.HeavyRuntimeCoordinator].
 * It does not install the adapter in production traffic or create a second native owner.
 *
 * A single test keeps T2 behind successful T1 readiness: an initialization failure stops before
 * generation is requested. The test logs lifecycle identities and event ordering, never model
 * output text. The repository/native run identifiers are intentionally not exposed through the
 * adapter contract, so this test records that boundary rather than inventing a correlation.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class Step27LiteRtAiRuntimeInstrumentationTest {

    @get:Rule
    val modelRule = ModelAssetRule()

    @Test
    fun t1_prepare_then_t2_generate_and_close() {
        runBlocking {
            val appContext =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext
                .applicationContext
        val config = loadValidatedSurveyConfig(appContext)
        val model = createProductionConfiguredModel(config)

        SLM.setApplicationContext(appContext)

        val repository =
            LiteRtRepository(
                model = model,
                config = config,
                appContext = appContext,
                heavyRuntimeHandoff = (appContext as SurveyApp).heavyRuntimeCoordinator,
            )
        val runtime =
            LiteRtAiRuntime(
                LiteRtRepositoryRuntimeBridge(repository, model),
            )

        try {
            val aiModel = AiModel(model.name)

            // T1: LiteRtRepository.prepareObservation() is the bridge's readiness source.
            val prepareResult =
                withTimeout(PREPARE_TEST_TIMEOUT_MS) {
                    runtime.prepare(aiModel, AiRuntimeConfig())
                }
            assertEquals(AiPrepareResult.Ready(reusedPreparedRuntime = false), prepareResult)
            Log.i(TAG, "T1 ready model=${model.name}; readiness=repository-observation")

            // T2: the request deliberately supplies no sampling or timeout override.
            val generationId = AiGenerationId.create()
            val events = mutableListOf<AiGenerationEvent>()
            withTimeout(GENERATION_TEST_TIMEOUT_MS) {
                runtime
                    .generate(
                        AiGenerationRequest(
                            generationId = generationId,
                            prompt = "Reply with only the word ready.",
                        ),
                    )
                    .collect { event ->
                        events += event
                    }
            }

            assertTrue("T2 must start one generation", events.firstOrNull() is AiGenerationEvent.Started)
            assertTrue("T2 must include a response delta", events.any { it is AiGenerationEvent.Delta })
            assertTrue("T2 must report logical completion", events.any { it is AiGenerationEvent.Completed })
            assertTrue(
                "T2 must report native termination separately",
                events.any { it is AiGenerationEvent.NativeTerminated },
            )
            assertFalse("T2 must not cancel", events.any { it is AiGenerationEvent.Cancelled })
            assertFalse("T2 must not fail", events.any { it is AiGenerationEvent.Failed })
            assertTrue(
                "Every adapter event must retain the caller's generation identity",
                events.all { it.generationId == generationId },
            )

            val logicalCompletion = events.indexOfFirst { it is AiGenerationEvent.Completed }
            val nativeTermination = events.indexOfFirst { it is AiGenerationEvent.NativeTerminated }
            assertTrue(
                "Logical completion must precede the native cleanup safepoint",
                logicalCompletion in 0 until nativeTermination,
            )

            val deltaCharacters = events.filterIsInstance<AiGenerationEvent.Delta>().sumOf { it.text.length }
            Log.i(
                TAG,
                "T2 generationId=${generationId.value} events=${events.map { it::class.simpleName }} " +
                    "deltaChars=$deltaCharacters repositoryRequestId=not-exposed " +
                    "nativeRunId=not-exposed",
            )

            val closeResult = withTimeout(CLOSE_TEST_TIMEOUT_MS) { runtime.close() }
            assertEquals(AiCloseResult.Released, closeResult)
            Log.i(TAG, "close generationId=${generationId.value}; cleanup=confirmed")
        } finally {
            // Cleanup is idempotent and is required even when a Phase 1 assertion fails.
            runtime.close()
        }
        }
    }

    private fun loadValidatedSurveyConfig(context: Context): SurveyConfig =
        SurveyConfigLoader
            .fromAssets(context, "survey_config10.yaml")
            .also { loaded ->
                assertTrue(
                    "SurveyConfig invalid:\n- ${loaded.validate().joinToString("\n- ")}",
                    loaded.validate().isEmpty(),
                )
            }

    private fun createProductionConfiguredModel(config: SurveyConfig): Model {
        val modelName =
            config.modelDefaults.defaultFileName
                ?.substringBeforeLast('.')
                ?.ifBlank { null }
                ?: "ondevice-slm"
        return Model(
            name = modelName,
            taskPath = modelRule.internalModel.absolutePath,
            config = buildModelConfig(config.slm),
        )
    }

    private companion object {
        const val TAG = "Step27LiteRtAiRuntime"
        const val PREPARE_TEST_TIMEOUT_MS = 120_000L
        const val GENERATION_TEST_TIMEOUT_MS = 180_000L
        const val CLOSE_TEST_TIMEOUT_MS = 30_000L
    }
}
