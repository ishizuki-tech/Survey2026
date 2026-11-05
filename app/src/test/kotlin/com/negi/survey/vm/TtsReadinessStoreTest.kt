package com.negi.survey.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TtsReadinessStoreTest {
    private val store = TtsReadinessStore()
    private val key = TtsReadinessKey.create("voice.engine", "en", true, true)

    @Test
    fun firstSuccessIsReusedAcrossSessionsWithEquivalentKeys() = runTest {
        var checks = 0
        assertFalse(store.isReady(key))
        assertEquals(TtsVoiceCheck.OK, store.check(key) {
            checks++
            TtsVoiceCheck.OK
        })
        val equivalent = TtsReadinessKey.create(" voice.engine ", " EN ", true, true)
        assertTrue(store.isReady(equivalent))
        assertEquals(TtsVoiceCheck.OK, store.check(equivalent) { error("Repeated check") })
        assertEquals(1, checks)
    }

    @Test
    fun differentEngineRequiresChecking() = assertSeparateChecks(
        key, TtsReadinessKey.create("other.engine", "en", true, true)
    )

    @Test
    fun differentLanguageRequiresChecking() = assertSeparateChecks(
        key, TtsReadinessKey.create("voice.engine", "sw", true, true)
    )

    @Test
    fun engineInstallationSuccessDoesNotSatisfyOfflineVoiceRequirement() = assertSeparateChecks(
        TtsReadinessKey.create("voice.engine", "en", false, true), key
    )

    @Test
    fun differentEnginePolicyRequiresChecking() = assertSeparateChecks(
        key, TtsReadinessKey.create("voice.engine", "en", true, false)
    )

    @Test
    fun failuresAreNeverCached() = runTest {
        for (failure in listOf(TtsVoiceCheck.ENGINE_MISSING, TtsVoiceCheck.VOICE_MISSING, TtsVoiceCheck.ERROR)) {
            assertEquals(failure, store.check(key) { failure })
            assertFalse(store.isReady(key))
        }
        assertEquals(TtsVoiceCheck.OK, store.check(key) { TtsVoiceCheck.OK })
    }

    @Test
    fun cancelledOwnerCannotPublishSuccessAndWaitingCallerCanRecheck() = runTest {
        val entered = CompletableDeferred<Unit>()
        val owner = async {
            store.check(key) {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    TtsVoiceCheck.OK
                }
            }
        }
        entered.await()
        val waiting = async { store.check(key) { TtsVoiceCheck.VOICE_MISSING } }
        runCurrent()

        owner.cancelAndJoin()
        assertEquals(TtsVoiceCheck.VOICE_MISSING, waiting.await())
        assertFalse(store.isReady(key))
    }

    @Test
    fun invalidationForcesRecheckAndKeepsUnrelatedSuccess() = runTest {
        val unrelated = TtsReadinessKey.create("other.engine", "en", true, true)
        store.check(key) { TtsVoiceCheck.OK }
        store.check(unrelated) { TtsVoiceCheck.OK }

        store.invalidate { it.enginePackage == key.enginePackage }

        assertFalse(store.isReady(key))
        assertTrue(store.isReady(unrelated))
        assertEquals(TtsVoiceCheck.ENGINE_MISSING, store.check(key) { TtsVoiceCheck.ENGINE_MISSING })
    }

    @Test
    fun voiceDataNotificationInvalidatesAllOfflineEnginesWithoutUsingSenderIdentity() = runTest {
        val otherEngine = TtsReadinessKey.create("other.engine", "sw", true, true)
        store.check(key) { TtsVoiceCheck.OK }
        store.check(otherEngine) { TtsVoiceCheck.OK }

        store.invalidateVoiceData()

        assertFalse(store.isReady(key))
        assertFalse(store.isReady(otherEngine))
        assertEquals(TtsVoiceCheck.VOICE_MISSING, store.check(key) { TtsVoiceCheck.VOICE_MISSING })
    }

    @Test
    fun voiceDataNotificationKeepsEngineInstallationOnlySuccess() = runTest {
        val installationOnly = TtsReadinessKey.create("voice.engine", "en", false, true)
        store.check(key) { TtsVoiceCheck.OK }
        store.check(installationOnly) { TtsVoiceCheck.OK }

        store.invalidateVoiceData()

        assertFalse(store.isReady(key))
        assertEquals(TtsVoiceCheck.OK, store.check(installationOnly) { error("Repeated installation check") })
    }

    @Test
    fun voiceDataNotificationDiscardsAnInFlightOfflineSuccess() = runTest {
        val started = CompletableDeferred<Unit>()
        val staleResult = CompletableDeferred<TtsVoiceCheck>()
        val owner = async {
            store.check(key) {
                if (started.complete(Unit)) staleResult.await() else TtsVoiceCheck.VOICE_MISSING
            }
        }
        started.await()

        store.invalidateVoiceData()
        staleResult.complete(TtsVoiceCheck.OK)

        assertEquals(TtsVoiceCheck.VOICE_MISSING, owner.await())
        assertFalse(store.isReady(key))
    }

    @Test
    fun invalidatedCompletionCannotPublishAndReplacementWaitsForOriginalCheck() = runTest {
        val oldCheck = CompletableDeferred<TtsVoiceCheck>()
        val owner = async {
            store.check(key) { withContext(NonCancellable) { oldCheck.await() } }
        }
        runCurrent()
        store.invalidate { it == key }
        var replacementStarted = false
        val waiting = async {
            store.check(key) {
                replacementStarted = true
                assertFalse(store.isReady(key))
                TtsVoiceCheck.VOICE_MISSING
            }
        }
        runCurrent()
        assertFalse(replacementStarted)
        owner.cancel()
        oldCheck.complete(TtsVoiceCheck.OK)
        owner.join()

        assertEquals(TtsVoiceCheck.VOICE_MISSING, waiting.await())
        assertTrue(replacementStarted)
        assertFalse(store.isReady(key))
    }

    @Test
    fun activeCallerDiscardsStaleSuccessAndRechecks() = runTest {
        val firstResult = CompletableDeferred<TtsVoiceCheck>()
        var checks = 0
        val owner = async {
            store.check(key) {
                checks++
                if (checks == 1) firstResult.await() else {
                    assertFalse(store.isReady(key))
                    TtsVoiceCheck.VOICE_MISSING
                }
            }
        }
        runCurrent()
        store.invalidate { it == key }
        firstResult.complete(TtsVoiceCheck.OK)

        assertEquals(TtsVoiceCheck.VOICE_MISSING, owner.await())
        assertEquals(2, checks)
        assertFalse(store.isReady(key))
    }

    @Test
    fun concurrentRequestsShareOneCheck() = runTest {
        val result = CompletableDeferred<TtsVoiceCheck>()
        var checks = 0
        val first = async {
            store.check(key) {
                checks++
                result.await()
            }
        }
        val second = async { store.check(key) { error("Duplicate check") } }
        runCurrent()
        assertFalse(second.isCompleted)
        assertEquals(1, checks)
        result.complete(TtsVoiceCheck.OK)

        assertEquals(TtsVoiceCheck.OK, first.await())
        assertEquals(TtsVoiceCheck.OK, second.await())
    }

    @Test
    fun readinessReuseKeepsSurveyPlaybackStateInIndependentOwners() = runTest {
        val sessions = SurveySessionStore()
        val firstOwner = sessions.ownerFor("survey@1")
        val first = ViewModelProvider(firstOwner)[PlaybackState::class.java]
        first.pendingUtterance = "first question"
        store.check(key) { TtsVoiceCheck.OK }
        sessions.clearSession("survey@1")

        val secondOwner = sessions.ownerFor("survey@2")
        val second = ViewModelProvider(secondOwner)[PlaybackState::class.java]

        assertEquals(TtsVoiceCheck.OK, store.check(key) { error("Repeated check") })
        assertNotSame(firstOwner.viewModelStore, secondOwner.viewModelStore)
        assertNotSame(first, second)
        assertEquals("", second.pendingUtterance)
        sessions.clearSession("survey@2")
    }

    class PlaybackState : ViewModel() {
        var pendingUtterance = ""
    }

    private fun assertSeparateChecks(first: TtsReadinessKey, second: TtsReadinessKey) = runTest {
        store.check(first) { TtsVoiceCheck.OK }
        assertFalse(store.isReady(second))
        assertEquals(TtsVoiceCheck.VOICE_MISSING, store.check(second) { TtsVoiceCheck.VOICE_MISSING })
    }
}
