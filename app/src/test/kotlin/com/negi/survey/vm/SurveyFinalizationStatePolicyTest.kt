/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyFinalizationStatePolicyTest.kt
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */
package com.negi.survey.vm

import com.negi.survey.net.SurveyFinalizationResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyFinalizationStatePolicyTest {
    @Test
    fun stateTransitions_allowRetryAfterErrorAndNavigateOnlyAfterSuccess() {
        assertTrue(SurveyFinalizationStatePolicy.mayStart(SurveyFinalizationState.Idle))
        assertEquals(
            SurveyFinalizationState.Error("disk full"),
            SurveyFinalizationStatePolicy.complete(SurveyFinalizationResult.Failure("disk full"))
        )
        assertTrue(SurveyFinalizationStatePolicy.mayStart(SurveyFinalizationState.Error("disk full")))
        assertEquals(
            SurveyFinalizationState.Queued,
            SurveyFinalizationStatePolicy.complete(SurveyFinalizationResult.Queued(File("pending.json"), false))
        )
    }

    @Test
    fun stateTransitions_blockDuplicateFinishWhileFinishingOrQueued() {
        assertFalse(SurveyFinalizationStatePolicy.mayStart(SurveyFinalizationState.Finishing))
        assertFalse(SurveyFinalizationStatePolicy.mayStart(SurveyFinalizationState.Queued))
        assertEquals(
            SurveyFinalizationState.Queued,
            SurveyFinalizationStatePolicy.complete(SurveyFinalizationResult.AlreadyUploaded)
        )
    }
}
