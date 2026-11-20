package com.negi.survey.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.negi.survey.slm.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Activity-retained owner for one logical survey session.
 *
 * Compose is recreated for configuration changes, while the logical survey and
 * its controllers must remain alive. Each session gets a child [ViewModelStore]
 * that is cleared only when that session ends or the hosting Activity truly ends.
 */
class SurveySessionStore : ViewModel() {

    private class SessionEntry {
        val viewModelStore = ViewModelStore()
        val ttsDisabled = MutableStateFlow(false)
        val ttsDisabledState = ttsDisabled.asStateFlow()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = this@SessionEntry.viewModelStore
        }
        var repository: Repository? = null
    }

    private val sessions = mutableMapOf<String, SessionEntry>()

    @Synchronized
    fun ownerFor(sessionKey: String): ViewModelStoreOwner =
        sessions.getOrPut(sessionKey) { SessionEntry() }.owner

    /** Interviewer playback policy, retained with the logical session across recreation. */
    @Synchronized
    fun ttsDisabledFor(sessionKey: String): StateFlow<Boolean> =
        sessions.getOrPut(sessionKey) { SessionEntry() }.ttsDisabledState

    /** Monotonic for this session; readiness or voice installation cannot undo the choice. */
    @Synchronized
    fun disableTts(sessionKey: String) {
        sessions.getOrPut(sessionKey) { SessionEntry() }.ttsDisabled.value = true
    }

    /**
     * Keeps the repository associated with the same child ViewModels across an
     * Activity recreation, so a recreated composition does not create another
     * runtime owner or repeat repository-scoped warm-up.
     */
    @Synchronized
    fun repositoryFor(sessionKey: String, create: () -> Repository): Repository {
        val entry = sessions.getOrPut(sessionKey) { SessionEntry() }
        return entry.repository ?: create().also { entry.repository = it }
    }

    /** Clears all session ViewModels only for an explicit logical session end. */
    fun clearSession(sessionKey: String) {
        val store = synchronized(this) {
            sessions.remove(sessionKey)?.viewModelStore
        } ?: return
        store.clear()
    }

    override fun onCleared() {
        val stores = synchronized(this) {
            sessions.values.map { it.viewModelStore }.also { sessions.clear() }
        }
        stores.forEach { it.clear() }
        super.onCleared()
    }
}
