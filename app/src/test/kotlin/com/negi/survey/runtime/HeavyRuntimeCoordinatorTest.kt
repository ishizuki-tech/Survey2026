package com.negi.survey.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeavyRuntimeCoordinatorTest {
    @Test
    fun none_to_litert_acquires_without_release() = runTest {
        var liteRtReleases = 0
        var whisperReleases = 0
        val coordinator = coordinator(
            releaseLiteRt = { liteRtReleases++ },
            releaseWhisper = { whisperReleases++ },
        )

        coordinator.withLiteRt { }

        assertEquals(0, liteRtReleases)
        assertEquals(0, whisperReleases)
    }

    @Test
    fun none_to_whisper_acquires_without_release() = runTest {
        var liteRtReleases = 0
        var whisperReleases = 0
        val coordinator = coordinator(
            releaseLiteRt = { liteRtReleases++ },
            releaseWhisper = { whisperReleases++ },
        )

        coordinator.withWhisper { }

        assertEquals(0, liteRtReleases)
        assertEquals(0, whisperReleases)
    }

    @Test
    fun litert_to_whisper_waits_for_litert_release() = runTest {
        val releaseStarted = CompletableDeferred<Unit>()
        val allowRelease = CompletableDeferred<Unit>()
        var whisperAcquired = false
        val coordinator = coordinator(
            releaseLiteRt = {
                releaseStarted.complete(Unit)
                allowRelease.await()
            },
            releaseWhisper = {},
        )
        coordinator.withLiteRt { }

        val transition = async { coordinator.withWhisper { whisperAcquired = true } }
        releaseStarted.await()

        assertFalse(whisperAcquired)
        assertFalse(transition.isCompleted)
        allowRelease.complete(Unit)
        transition.await()
        assertTrue(whisperAcquired)
    }

    @Test
    fun whisper_to_litert_waits_for_whisper_release() = runTest {
        val releaseStarted = CompletableDeferred<Unit>()
        val allowRelease = CompletableDeferred<Unit>()
        var liteRtAcquired = false
        val coordinator = coordinator(
            releaseLiteRt = {},
            releaseWhisper = {
                releaseStarted.complete(Unit)
                allowRelease.await()
            },
        )
        coordinator.withWhisper { }

        val transition = async { coordinator.withLiteRt { liteRtAcquired = true } }
        releaseStarted.await()

        assertFalse(liteRtAcquired)
        assertFalse(transition.isCompleted)
        allowRelease.complete(Unit)
        transition.await()
        assertTrue(liteRtAcquired)
    }

    @Test
    fun same_owner_reuse_does_not_release() = runTest {
        var liteRtReleases = 0
        var whisperReleases = 0
        val coordinator = coordinator(
            releaseLiteRt = { liteRtReleases++ },
            releaseWhisper = { whisperReleases++ },
        )

        coordinator.withLiteRt { }
        coordinator.withLiteRt { }
        coordinator.withWhisper { }
        coordinator.withWhisper { }

        assertEquals(1, liteRtReleases)
        assertEquals(0, whisperReleases)
    }

    @Test
    fun concurrent_opposite_acquisitions_are_serialized() = runTest {
        val releaseLiteRtStarted = CompletableDeferred<Unit>()
        val allowLiteRtRelease = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            releaseLiteRt = {
                events += "release-litert"
                releaseLiteRtStarted.complete(Unit)
                allowLiteRtRelease.await()
            },
            releaseWhisper = { events += "release-whisper" },
        )
        coordinator.withLiteRt { events += "litert-initial" }

        val whisper = async { coordinator.withWhisper { events += "whisper-acquired" } }
        releaseLiteRtStarted.await()
        val liteRt = async { coordinator.withLiteRt { events += "litert-reacquired" } }

        assertFalse(liteRt.isCompleted)
        allowLiteRtRelease.complete(Unit)
        whisper.await()
        liteRt.await()

        assertEquals(
            listOf(
                "litert-initial",
                "release-litert",
                "whisper-acquired",
                "release-whisper",
                "litert-reacquired",
            ),
            events,
        )
    }

    @Test
    fun release_failure_does_not_acquire_opposite_runtime_and_retry_is_deterministic() = runTest {
        var failRelease = true
        var whisperAcquisitions = 0
        val coordinator = coordinator(
            releaseLiteRt = {
                if (failRelease) error("release failed")
            },
            releaseWhisper = {},
        )
        coordinator.withLiteRt { }

        val failure = runCatching { coordinator.withWhisper { whisperAcquisitions++ } }
        assertTrue(failure.isFailure)
        assertEquals(0, whisperAcquisitions)

        failRelease = false
        coordinator.withWhisper { whisperAcquisitions++ }
        assertEquals(1, whisperAcquisitions)
    }

    @Test
    fun cancellation_during_release_does_not_acquire_opposite_runtime() = runTest {
        val releaseStarted = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        var whisperAcquisitions = 0
        val coordinator = coordinator(
            releaseLiteRt = {
                releaseStarted.complete(Unit)
                neverRelease.await()
            },
            releaseWhisper = {},
        )
        coordinator.withLiteRt { }

        val transition = launch { coordinator.withWhisper { whisperAcquisitions++ } }
        releaseStarted.await()
        transition.cancelAndJoin()

        assertEquals(0, whisperAcquisitions)

        neverRelease.complete(Unit)
        coordinator.withWhisper { whisperAcquisitions++ }
        assertEquals(1, whisperAcquisitions)
    }

    @Test
    fun litert_ownership_survives_logical_session_replacement() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            releaseLiteRt = { events += "release-litert" },
            releaseWhisper = { events += "release-whisper" },
        )

        coordinator.withLiteRt { events += "session-a-litert" }

        // A logical session end does not alter the process-scoped coordinator.
        coordinator.withWhisper { events += "session-b-whisper" }

        assertEquals(
            listOf("session-a-litert", "release-litert", "session-b-whisper"),
            events,
        )
    }

    @Test
    fun whisper_ownership_survives_logical_session_replacement() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            releaseLiteRt = { events += "release-litert" },
            releaseWhisper = { events += "release-whisper" },
        )

        coordinator.withWhisper { events += "session-a-whisper" }

        // A logical session end does not alter the process-scoped coordinator.
        coordinator.withLiteRt { events += "session-b-litert" }

        assertEquals(
            listOf("session-a-whisper", "release-whisper", "session-b-litert"),
            events,
        )
    }

    @Test
    fun same_litert_owner_reuses_runtime_across_logical_session_replacement() = runTest {
        var liteRtReleases = 0
        val coordinator = coordinator(
            releaseLiteRt = { liteRtReleases++ },
            releaseWhisper = {},
        )

        coordinator.withLiteRt(runtimeIdentity = "A") { }

        // A logical session end does not alter the process-scoped coordinator.
        coordinator.withLiteRt(runtimeIdentity = "A") { }

        assertEquals(0, liteRtReleases)
    }

    @Test
    fun different_litert_identity_releases_previous_before_acquiring_new() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            releaseLiteRt = { events += "release-a" },
            releaseWhisper = {},
        )

        coordinator.withLiteRt(runtimeIdentity = "A") { events += "acquire-a" }
        coordinator.withLiteRt(
            runtimeIdentity = "B",
            releaseLiteRt = { events += "release-b" },
        ) { events += "acquire-b" }

        assertEquals(listOf("acquire-a", "release-a", "acquire-b"), events)
    }

    @Test
    fun different_litert_identity_then_whisper_releases_new_owner_only() = runTest {
        var releaseA = 0
        var releaseB = 0
        val coordinator = coordinator(
            releaseLiteRt = { releaseA++ },
            releaseWhisper = {},
        )

        coordinator.withLiteRt(runtimeIdentity = "A") { }
        coordinator.withLiteRt(
            runtimeIdentity = "B",
            releaseLiteRt = { releaseB++ },
        ) { }
        coordinator.withWhisper { }

        assertEquals(1, releaseA)
        assertEquals(1, releaseB)
    }

    @Test
    fun different_litert_identity_acquire_failure_leaves_no_false_new_owner() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            releaseLiteRt = { events += "release-a" },
            releaseWhisper = {},
        )

        coordinator.withLiteRt(runtimeIdentity = "A") { events += "acquire-a" }
        val failure = runCatching {
            coordinator.withLiteRt(
                runtimeIdentity = "B",
                releaseLiteRt = { events += "release-b" },
            ) {
                events += "acquire-b"
                error("B failed")
            }
        }
        coordinator.withWhisper { events += "acquire-whisper" }

        assertTrue(failure.isFailure)
        assertEquals(
            listOf("acquire-a", "release-a", "acquire-b", "acquire-whisper"),
            events,
        )
    }

    @Test
    fun different_litert_identity_across_logical_session_replacement_releases_prior_runtime() = runTest {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            releaseLiteRt = { events += "release-session-a" },
            releaseWhisper = {},
        )

        coordinator.withLiteRt(runtimeIdentity = "A") { events += "session-a-litert" }

        // A logical session end does not alter the process-scoped coordinator.
        coordinator.withLiteRt(
            runtimeIdentity = "B",
            releaseLiteRt = { events += "release-session-b" },
        ) { events += "session-b-litert" }

        assertEquals(
            listOf("session-a-litert", "release-session-a", "session-b-litert"),
            events,
        )
    }

    private fun coordinator(
        releaseLiteRt: suspend () -> Unit,
        releaseWhisper: suspend () -> Unit,
    ) = TestCoordinator(releaseLiteRt, releaseWhisper)

    private class TestCoordinator(
        private val defaultReleaseLiteRt: suspend () -> Unit,
        releaseWhisper: suspend () -> Unit,
    ) {
        private val delegate = HeavyRuntimeCoordinator(releaseWhisper)

        suspend fun <T> withLiteRt(
            runtimeIdentity: String = "A",
            releaseLiteRt: suspend () -> Unit = defaultReleaseLiteRt,
            acquire: suspend () -> T,
        ): T = delegate.withLiteRt(runtimeIdentity, releaseLiteRt, acquire)

        suspend fun <T> withWhisper(acquire: suspend () -> T): T =
            delegate.withWhisper(acquire)
    }
}
