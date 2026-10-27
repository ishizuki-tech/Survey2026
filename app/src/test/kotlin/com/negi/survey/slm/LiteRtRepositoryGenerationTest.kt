package com.negi.survey.slm

import com.negi.aicore.AiGenerationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtRepositoryGenerationTest {
    @Test fun cancellationBeforeNativeStartIsLatchedForThisGenerationOnly() {
        val first = LiteRtRepositoryGeneration(AiGenerationId("first"), requestId = 11L)
        val second = LiteRtRepositoryGeneration(AiGenerationId("second"), requestId = 12L)
        var firstDispatches = 0
        var secondDispatches = 0

        first.cancel()
        first.installCancellationDispatcher { firstDispatches++ }
        second.installCancellationDispatcher { secondDispatches++ }

        assertEquals(1, firstDispatches)
        assertEquals(0, secondDispatches)
        assertNull(first.nativeRunIdOrNull())
    }

    @Test fun nativeRunIdIsPublishedOnceForTheOwningGeneration() {
        val generation = LiteRtRepositoryGeneration(AiGenerationId("owner"), requestId = 17L)
        generation.publishNativeRunId(91L)

        assertEquals(91L, generation.nativeRunIdOrNull())
        assertTrue(runCatching { generation.publishNativeRunId(92L) }.isFailure)
    }
}
