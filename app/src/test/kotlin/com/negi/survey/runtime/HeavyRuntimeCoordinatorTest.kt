package com.negi.survey.runtime

import com.negi.survey.slm.LiteRtGenerationLease
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HeavyRuntimeCoordinatorTest {
    @Test fun initial_litert_acquisition_does_not_release() = runTest {
        var liteRtReleases = 0
        val coordinator = coordinator({ liteRtReleases++ }, {})
        coordinator.withLiteRt { }
        assertEquals(0, liteRtReleases)
    }

    @Test fun whisper_keeps_litert_resident_until_voice_operation_ends() = runTest {
        var liteRtReleases = 0
        var whisperReleases = 0
        val coordinator = coordinator({ liteRtReleases++ }, { whisperReleases++ })

        coordinator.withLiteRt { }
        coordinator.withWhisper { }
        coordinator.releaseWhisper()
        coordinator.withLiteRt { }

        assertEquals(0, liteRtReleases)
        assertEquals(1, whisperReleases)
    }

    @Test fun multiple_voice_cycles_reuse_litert_and_reinitialize_whisper() = runTest {
        var liteRtReleases = 0
        var whisperAcquires = 0
        var whisperReleases = 0
        val coordinator = coordinator({ liteRtReleases++ }, { whisperReleases++ })
        coordinator.withLiteRt { }
        repeat(2) {
            coordinator.withWhisper { whisperAcquires++ }
            coordinator.releaseWhisper()
            coordinator.withLiteRt { }
        }
        assertEquals(2, whisperAcquires)
        assertEquals(2, whisperReleases)
        assertEquals(0, liteRtReleases)
    }

    @Test fun explicit_shutdown_releases_both_remaining_runtimes_once() = runTest {
        var liteRtReleases = 0
        var whisperReleases = 0
        val coordinator = coordinator({ liteRtReleases++ }, { whisperReleases++ })
        coordinator.withLiteRt { }
        coordinator.withWhisper { }
        coordinator.releaseCurrent()
        coordinator.releaseCurrent()
        assertEquals(1, liteRtReleases)
        assertEquals(1, whisperReleases)
    }

    @Test fun different_litert_identity_releases_prior_runtime() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator({ events += "release-a" }, {})
        coordinator.withLiteRt("A") { events += "acquire-a" }
        coordinator.withLiteRt("B", { events += "release-b" }) { events += "acquire-b" }
        coordinator.releaseCurrent()
        assertEquals(listOf("acquire-a", "acquire-b", "release-a", "release-b"), events)
    }

    @Test fun failed_whisper_acquisition_preserves_litert() = runTest {
        var liteRtReleases = 0
        val coordinator = coordinator({ liteRtReleases++ }, {})
        coordinator.withLiteRt { }
        assertTrue(runCatching { coordinator.withWhisper { error("init failed") } }.isFailure)
        coordinator.withLiteRt { }
        assertEquals(0, liteRtReleases)
    }

    @Test fun failed_litert_acquisition_does_not_publish_owner() = runTest {
        var liteRtReleases = 0
        val coordinator = coordinator({ liteRtReleases++ }, {})
        assertTrue(runCatching { coordinator.withLiteRt { error("init failed") } }.isFailure)
        coordinator.releaseCurrent()
        assertEquals(0, liteRtReleases)
    }

    @Test fun cancellation_during_generation_lease_transfer_still_publishes_owner() = runTest {
        val acquisitionComplete = CompletableDeferred<Unit>()
        val leaseRequested = CompletableDeferred<Unit>()
        val releaseLease = CompletableDeferred<Unit>()
        val generation = LiteRtGenerationLease("G", Any())
        var published: LiteRtGenerationLease? = null
        var releases = 0
        val coordinator = HeavyRuntimeCoordinator(releaseWhisperAction = {})

        val transition = async {
            coordinator.withLiteRt(
                releaseLiteRt = { releases++ },
                generationLease = {
                    leaseRequested.complete(Unit)
                    releaseLease.await()
                    generation
                },
                onOwnershipPublished = { published = it },
                acquire = { acquisitionComplete.complete(Unit) },
            )
        }

        try {
            acquisitionComplete.await()
            leaseRequested.await()
            transition.cancel()
            releaseLease.complete(Unit)
            transition.cancelAndJoin()

            assertSame(generation, published)
            coordinator.releaseCurrent()
            assertEquals(1, releases)
        } finally {
            releaseLease.complete(Unit)
            transition.cancel()
        }
    }

    @Test fun failed_generation_lease_lookup_does_not_replace_existing_owner() = runTest {
        var releases = 0
        val coordinator = HeavyRuntimeCoordinator(releaseWhisperAction = {})
        coordinator.withLiteRt(
            releaseLiteRt = { releases++ },
            generationLease = { LiteRtGenerationLease("A", Any()) },
            acquire = {},
        )

        val failure = runCatching {
            coordinator.withLiteRt(
                releaseLiteRt = { releases++ },
                generationLease = { error("generation lease unavailable") },
                acquire = {},
            )
        }.exceptionOrNull()

        assertEquals("generation lease unavailable", failure?.message)
        assertEquals(0, releases)
        coordinator.releaseCurrent()
        assertEquals(1, releases)
    }

    @Test fun cancellation_during_whisper_acquisition_keeps_litert() = runTest {
        val started = CompletableDeferred<Unit>()
        val neverFinish = CompletableDeferred<Unit>()
        var liteRtReleases = 0
        val coordinator = coordinator({ liteRtReleases++ }, {})
        coordinator.withLiteRt { }
        val transition = async { coordinator.withWhisper { started.complete(Unit); neverFinish.await() } }
        started.await()
        transition.cancelAndJoin()
        coordinator.withLiteRt { }
        assertEquals(0, liteRtReleases)
    }

    @Test fun litert_acquisition_waits_for_in_progress_whisper_initialization() = runTest {
        val whisperStarted = CompletableDeferred<Unit>()
        val allowWhisper = CompletableDeferred<Unit>()
        var litertAcquired = false
        val coordinator = coordinator({}, {})
        val whisper = async {
            coordinator.withWhisper {
                whisperStarted.complete(Unit)
                allowWhisper.await()
            }
        }
        whisperStarted.await()
        val litert = async { coordinator.withLiteRt { litertAcquired = true } }
        assertFalse(litertAcquired)
        allowWhisper.complete(Unit)
        whisper.await()
        litert.await()
        assertTrue(litertAcquired)
    }

    @Test fun stale_recovery_cannot_invalidate_newer_same_path_owner() = runTest {
        var releases = 0
        var acquires = 0
        val coordinator = coordinator({ releases++ }, {})
        var oldOwner: LiteRtGenerationLease? = null
        var newOwner: LiteRtGenerationLease? = null
        coordinator.withLiteRt("A", onOwnershipPublished = { oldOwner = it }) { acquires++ }
        coordinator.withLiteRt("B", onOwnershipPublished = { newOwner = it }) { acquires++ }
        assertFalse(coordinator.releaseLiteRt(checkNotNull(oldOwner)) { releases++ })
        coordinator.releaseCurrent()
        assertTrue(newOwner != oldOwner)
        assertEquals(2, releases)
        assertEquals(2, acquires)
    }

    @Test fun recovery_close_of_exact_owner_is_serialized_and_clears_ownership() = runTest {
        var releases = 0
        var owner: LiteRtGenerationLease? = null
        val coordinator = coordinator({ releases++ }, {})
        coordinator.withLiteRt(onOwnershipPublished = { owner = it }) { }
        assertTrue(coordinator.releaseLiteRt(checkNotNull(owner)) { releases++ })
        coordinator.releaseCurrent()
        assertEquals(1, releases)
    }

    @Test fun failed_whisper_release_remains_owned_for_shutdown_retry() = runTest {
        var releaseAttempts = 0
        val coordinator = coordinator({}, {
            releaseAttempts++
            if (releaseAttempts == 1) error("release failed")
        })
        coordinator.withWhisper { }
        assertTrue(runCatching { coordinator.releaseWhisper() }.isFailure)
        coordinator.releaseCurrent()
        assertEquals(2, releaseAttempts)
    }

    @Test fun repeated_whisper_release_does_not_duplicate_native_release() = runTest {
        var whisperReleases = 0
        val coordinator = coordinator({}, { whisperReleases++ })
        coordinator.withWhisper { }
        coordinator.releaseWhisper()
        coordinator.releaseWhisper()
        assertEquals(1, whisperReleases)
    }

    @Test fun shutdown_attempts_litert_when_whisper_release_fails() = runTest {
        var whisperReleases = 0
        var liteRtReleases = 0
        val coordinator = coordinator({ liteRtReleases++ }, { whisperReleases++; error("whisper") })
        coordinator.withLiteRt { }
        coordinator.withWhisper { }
        val failure = runCatching { coordinator.releaseCurrent() }.exceptionOrNull()
        assertEquals(1, whisperReleases)
        assertEquals(1, liteRtReleases)
        assertEquals("whisper", failure?.message)
    }

    private fun coordinator(releaseLiteRt: suspend () -> Unit, releaseWhisper: suspend () -> Unit) =
        TestCoordinator(releaseLiteRt, releaseWhisper)

    private class TestCoordinator(
        private val defaultReleaseLiteRt: suspend () -> Unit,
        releaseWhisper: suspend () -> Unit,
    ) {
        private val delegate = HeavyRuntimeCoordinator(releaseWhisper)
        private val leases = mutableMapOf<String, LiteRtGenerationLease>()
        suspend fun <T> withLiteRt(
            runtimeIdentity: String = "A",
            releaseLiteRt: suspend () -> Unit = defaultReleaseLiteRt,
            onOwnershipPublished: (LiteRtGenerationLease) -> Unit = {},
            acquire: suspend () -> T,
        ): T = delegate.withLiteRt(
            releaseLiteRt = { releaseLiteRt() },
            generationLease = { leases.getOrPut(runtimeIdentity) { LiteRtGenerationLease(runtimeIdentity, Any()) } },
            onOwnershipPublished = onOwnershipPublished,
            acquire = acquire,
        )
        suspend fun <T> withWhisper(acquire: suspend () -> T): T = delegate.withWhisper(acquire)
        suspend fun releaseWhisper() = delegate.releaseWhisper()
        suspend fun releaseLiteRt(ownership: LiteRtGenerationLease, release: suspend () -> Unit) =
            delegate.releaseLiteRt(ownership, release)
        suspend fun releaseCurrent() = delegate.releaseCurrent()
    }
}
