package com.negi.survey.slm

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmReuseAuthorityTest {
    @Test fun repeated_warm_adopters_are_accepted_and_revoke_cleanup() {
        val claim = PublicationCleanupClaim()
        val snapshot = NativeLifecycleCoordinator().snapshot()
        repeat(3) { requireWarmReuseAuthority(claim, snapshot) }
        assertFalse(claim.claimCleanup())
    }

    @Test fun claimed_cleanup_is_never_reported_ready() {
        val claim = PublicationCleanupClaim()
        assertTrue(claim.claimCleanup())
        val failure = runCatching { requireWarmReuseAuthority(claim, NativeLifecycleCoordinator().snapshot()) }
        assertTrue(failure.isFailure)
    }

    @Test fun teardown_and_poison_reject_reuse_without_adopting_claim() {
        val lifecycle = NativeLifecycleCoordinator()
        val claim = PublicationCleanupClaim()
        val conversation = Any()
        val result = lifecycle.startTeardown(
            NativeLifecycleCoordinator.TeardownMode.CONVERSATION_REPLACEMENT,
            NativeLifecycleCoordinator.TeardownMetadata(Any(), listOf(conversation)),
        ) as NativeLifecycleCoordinator.TeardownStartResult.Started
        assertTrue(runCatching { requireWarmReuseAuthority(claim, lifecycle.snapshot()) }.isFailure)
        assertTrue(claim.claimCleanup())
        assertTrue(lifecycle.poisonFlight(result.flight) != null)
        assertTrue(runCatching { requireWarmReuseAuthority(null, lifecycle.snapshot()) }.isFailure)
    }

    @Test fun concurrent_warm_callers_all_adopt_before_cleanup() {
        val claim = PublicationCleanupClaim()
        val snapshot = NativeLifecycleCoordinator().snapshot()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map {
                executor.submit<Boolean> { start.await(); requireWarmReuseAuthority(claim, snapshot); true }
            }
            start.countDown()
            assertEquals(4, futures.count { it.get(5, TimeUnit.SECONDS) })
            assertFalse(claim.claimCleanup())
        } finally {
            executor.shutdownNow()
        }
    }
}
