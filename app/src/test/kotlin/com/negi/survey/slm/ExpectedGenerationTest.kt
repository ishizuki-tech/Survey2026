package com.negi.survey.slm

import org.junit.Assert.assertTrue
import org.junit.Test

class ExpectedGenerationTest {
    @Test fun exact_generation_is_accepted() {
        val identity = Any()
        requireExpectedGeneration(LiteRtGenerationLease("model", identity), "model", identity)
    }

    @Test fun missing_or_replaced_generation_is_rejected() {
        val expected = LiteRtGenerationLease("model", Any())
        assertTrue(runCatching { requireExpectedGeneration(expected, "model", null) }.isFailure)
        assertTrue(runCatching { requireExpectedGeneration(expected, "model", Any()) }.isFailure)
    }

    @Test fun same_identity_for_different_model_is_rejected() {
        val identity = Any()
        assertTrue(runCatching {
            requireExpectedGeneration(LiteRtGenerationLease("model-a", identity), "model-b", identity)
        }.isFailure)
    }
}
