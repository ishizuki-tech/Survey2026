package com.negi.survey.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.negi.survey.slm.Repository
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveySessionStoreTest {

    @Test
    fun configuration_recreation_reuses_session_viewmodels_and_repository() {
        val sessions = SurveySessionStore()
        val firstOwner = sessions.ownerFor("config@1")
        val viewModel = ViewModelProvider(firstOwner)[TrackingViewModel::class.java]
        val repository = sessions.repositoryFor("config@1") { FakeRepository() }

        // Simulates the new composition asking the Activity-retained owner again.
        val recreatedOwner = sessions.ownerFor("config@1")
        val recreatedViewModel = ViewModelProvider(recreatedOwner)[TrackingViewModel::class.java]
        val recreatedRepository = sessions.repositoryFor("config@1") { FakeRepository() }

        assertSame(firstOwner.viewModelStore, recreatedOwner.viewModelStore)
        assertSame(viewModel, recreatedViewModel)
        assertSame(repository, recreatedRepository)
        assertFalse(viewModel.cleared)
    }

    @Test
    fun explicit_session_end_clears_child_viewmodels_and_drops_its_repository() {
        val sessions = SurveySessionStore()
        val firstOwner = sessions.ownerFor("config@1")
        val viewModel = ViewModelProvider(firstOwner)[TrackingViewModel::class.java]
        val repository = sessions.repositoryFor("config@1") { FakeRepository() }

        sessions.clearSession("config@1")

        assertTrue(viewModel.cleared)

        val nextOwner = sessions.ownerFor("config@1")
        val nextViewModel = ViewModelProvider(nextOwner)[TrackingViewModel::class.java]
        val nextRepository = sessions.repositoryFor("config@1") { FakeRepository() }

        assertNotSame(firstOwner.viewModelStore, nextOwner.viewModelStore)
        assertNotSame(viewModel, nextViewModel)
        assertNotSame(repository, nextRepository)
        assertEquals(false, nextViewModel.cleared)
    }

    class TrackingViewModel : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    private class FakeRepository : Repository {
        override suspend fun request(prompt: String): Flow<String> = error("not used")

        override fun buildPrompt(userPrompt: String): String = userPrompt
    }
}
