package com.negi.survey.slm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Characterizes the current native-publication cleanup authority. */
class LiteRtPublicationHandoffBoundaryTest {
    @Test fun abandoned_before_publication_gives_publisher_one_cleanup_claim() {
        val handoff = LiteRtPublicationHandoff()
        val lease = LiteRtGenerationLease("model", Any())
        assertNull(handoff.abandon())
        assertFalse(handoff.publish(lease))
        assertSame(lease, handoff.claimAbandonedPublicationCleanup(lease))
        assertNull(handoff.claimAbandonedPublicationCleanup(lease))
        assertNull(handoff.abandon())
    }

    @Test fun abandoned_after_publication_claims_exact_generation_once() {
        val handoff = LiteRtPublicationHandoff()
        val lease = LiteRtGenerationLease("model", Any())
        assertTrue(handoff.publish(lease))
        assertSame(lease, handoff.abandon())
        assertNull(handoff.abandon())
        assertNull(handoff.claimAbandonedPublicationCleanup(lease))
    }

    @Test fun warm_adoption_revokes_abandoned_initializer_cleanup_authority() {
        val handoff = LiteRtPublicationHandoff()
        val lease = LiteRtGenerationLease("model", Any())
        assertTrue(handoff.publish(lease))
        assertTrue(handoff.cleanupClaim().adopt())
        assertNull(handoff.abandon())
        assertNull(handoff.claimAbandonedPublicationCleanup(lease))
        assertFalse(handoff.cleanupClaim().adopt())
    }

    @Test fun cleanup_claim_prevents_later_warm_adoption() {
        val handoff = LiteRtPublicationHandoff()
        val lease = LiteRtGenerationLease("model", Any())
        assertTrue(handoff.publish(lease))
        assertSame(lease, handoff.abandon())
        assertFalse(handoff.cleanupClaim().adopt())
    }
}
