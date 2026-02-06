/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyViewModel.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Main ViewModel responsible for managing survey navigation and state.
 *
 *  HARDENING / DEBUGGING (2026-02):
 *   - NavKey is now per-node (includes nodeId) to prevent state collision/state-bleed in Navigation3.
 *   - Text persistence is "sticky" against recomposition wipes (blank right after restore is ignored).
 *   - Follow-up draft vs committed answer is separated (draft no longer blocks commit).
 *   - Added nodeId-targeted setters to avoid late UI events mutating the wrong node.
 *   - Added invariant checks to detect stack/backstack mismatches early.
 *   - Added JSON snapshot export/restore helpers for reproducible debugging.
 *
 *  Prompt upgrades:
 *   - Two-step prompt support via SurveyConfig resolvers:
 *       - one-step: resolveOneStepPrompt(nodeId)
 *       - two-step: resolveEvalPrompt(nodeId) + resolveFollowupPrompt(nodeId)
 *   - Backward compatible: getPrompt() still works for legacy configs.
 *
 *  Debug upgrades:
 *   - Prompt source summary on init
 *   - Missing AI prompt coverage warnings on init
 *   - Detailed exception messages for missing prompt definitions
 *   - State dumps for navigation transitions
 *   - Detects suspicious "blank update" right after restore (recomposition wipe)
 *   - Warns if navigation APIs are called off the main thread
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.vm

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "SurveyVM"

/* ───────────────────────────── Graph Model ───────────────────────────── */

/**
 * Survey node types used by the runtime flow.
 *
 * These values represent the logical type of nodes in the survey graph.
 */
@Serializable
enum class NodeType {
    START,
    TEXT,
    SINGLE_CHOICE,
    MULTI_CHOICE,
    AI,
    REVIEW,
    DONE
}

/**
 * Runtime node model built from survey configuration.
 *
 * @property id Unique identifier of the node.
 * @property type Node type that determines which screen to show.
 * @property title Optional title used in the UI.
 * @property question Primary question text for this node.
 * @property options List of answer options for choice-based nodes.
 * @property nextId ID of the next node in the graph, or null if none.
 */
data class Node(
    val id: String,
    val type: NodeType,
    val title: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val nextId: String? = null
)

/**
 * Navigation 3 back stack keys.
 *
 * Notes:
 * - Keys must implement NavKey.
 * - Keys should be @Serializable for save/restore.
 */
@Serializable
data class FlowHome(val nodeId: String) : NavKey

@Serializable
data class FlowText(val nodeId: String) : NavKey

@Serializable
data class FlowSingle(val nodeId: String) : NavKey

@Serializable
data class FlowMulti(val nodeId: String) : NavKey

@Serializable
data class FlowAI(val nodeId: String) : NavKey

@Serializable
data class FlowReview(val nodeId: String) : NavKey

@Serializable
data class FlowDone(val nodeId: String) : NavKey

/* ───────────────────────────── UI Events ───────────────────────────── */

/**
 * Events emitted by the ViewModel for one-off UI feedback.
 *
 * Typical usages include snackbars, dialogs, and other transient messages.
 */
sealed interface UiEvent {

    /** Simple snackbar-like message. */
    data class Snack(val message: String) : UiEvent

    /** Dialog event that carries a title and message. */
    data class Dialog(
        val title: String,
        val message: String
    ) : UiEvent
}

/* ───────────────────────────── Prompt Mode ───────────────────────────── */

/** Prompt mode resolved per node. */
enum class PromptMode {
    ONE_STEP,
    TWO_STEP
}

/* ───────────────────────────── Main ViewModel ───────────────────────────── */

open class SurveyViewModel(
    private val nav: NavBackStack<NavKey>,
    private val config: SurveyConfig
) : ViewModel() {

    companion object {
        private const val DEBUG_PROMPTS = true
        private const val DEBUG_RENDER = false

        /** Navigation/state debugging toggle. */
        private const val DEBUG_NAV_STATE = true

        /** Verbose logs for map mutations (useful when chasing "disappearing" bugs). */
        private const val DEBUG_MAP_MUTATION = true

        private const val KEY_QUESTION = "QUESTION"
        private const val KEY_ANSWER = "ANSWER"
        private const val KEY_NODE_ID = "NODE_ID"
        private const val KEY_EVAL_JSON = "EVAL_JSON"

        /** Sentinel node id used before the real start node is loaded. */
        private const val LOADING_NODE_ID = "Loading"

        /**
         * Window (ms) in which a blank update is treated as "likely UI reset/recomposition wipe"
         * and will be ignored to protect existing cached content.
         *
         * This lets "user intentionally clears to blank" still work after the window passes.
         */
        private const val STICKY_BLANK_IGNORE_WINDOW_MS = 1200L

        /** Limit to prevent log spam. */
        private const val LOG_PREVIEW_MAX = 120

        /** Snapshot safety: skip export if JSON is too large (avoid UI / IPC issues). */
        private const val SNAPSHOT_MAX_CHARS = 450_000
    }

    /* ───────────────────────────── JSON Codec ───────────────────────────── */

    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /* ───────────────────────────── Identity / Debug Helpers ───────────────────────────── */

    /** Unique identity for this ViewModel instance (useful to detect recreation). */
    private val vmId: Int = System.identityHashCode(this)

    /** Monotonically increasing survey session ID. */
    private val _sessionId = MutableStateFlow(0L)
    val sessionId: StateFlow<Long> = _sessionId.asStateFlow()

    /** Stable UUID for the active survey run. */
    private val _surveyUuid = MutableStateFlow(UUID.randomUUID().toString())
    val surveyUuid: StateFlow<String> = _surveyUuid.asStateFlow()

    private fun dbgSuffix(): String =
        " (vmId=$vmId session=${_sessionId.value} uuid=${_surveyUuid.value})"

    private fun logd(msg: String) = Log.d(TAG, msg + dbgSuffix())
    private fun logw(msg: String) = Log.w(TAG, msg + dbgSuffix())
    private fun loge(msg: String) = Log.e(TAG, msg + dbgSuffix())

    /** Regenerate the survey UUID for a brand-new run. */
    private fun regenerateSurveyUuid() {
        _surveyUuid.value = UUID.randomUUID().toString()
    }

    /**
     * Warn if navigation operations are invoked off the main thread.
     *
     * Navigation3 backstack mutations and most UI state should be main-thread driven.
     */
    private fun warnIfNotMainThread(api: String) {
        if (!DEBUG_NAV_STATE) return
        val isMain = Looper.myLooper() == Looper.getMainLooper()
        if (!isMain) logw("$api called off main thread (this can cause nondeterministic bugs).")
    }

    /**
     * Assert internal invariants to catch hard-to-debug navigation/state issues early.
     *
     * This is intentionally "log-only" (no crash) to keep production safe.
     */
    private fun assertInvariants(label: String) {
        if (!DEBUG_NAV_STATE) return

        val stackSize = nodeStack.size
        val navSize = nav.size
        val curId = _currentNode.value.id.trim()
        val top = nodeStack.lastOrNull()?.trim().orEmpty()

        if (stackSize == 0) {
            loge("invariant[$label] violated: nodeStack is empty (navSize=$navSize, cur=$curId)")
            return
        }
        if (top != curId) {
            loge("invariant[$label] violated: stackTop != current (top=$top, cur=$curId)")
        }
        if (navSize != stackSize) {
            logw("invariant[$label] suspicious: navSize != stackSize (nav=$navSize, stack=$stackSize)")
        }
    }

    /* ───────────────────────────── Graph / Navigation State ───────────────────────────── */

    /**
     * Survey graph as a map from node ID to [Node].
     *
     * IMPORTANT:
     * - Keys are normalized (trimmed) to avoid hidden whitespace bugs.
     */
    private val graph: Map<String, Node>

    /** Read-only view of the runtime survey graph, keyed by node ID. */
    val nodes: Map<String, Node>
        get() = graph

    /**
     * ID of the starting node defined in [SurveyConfig.graph.startId].
     *
     * IMPORTANT:
     * - Normalized (trimmed) to match graph keys.
     */
    private val startId: String = config.graph.startId.trim()

    /**
     * Internal stack that tracks the sequence of visited node IDs.
     *
     * The last element corresponds to the currently active node.
     */
    private val nodeStack = ArrayDeque<String>()

    /** StateFlow representing the currently active [Node]. */
    private val _currentNode = MutableStateFlow(
        Node(id = LOADING_NODE_ID, type = NodeType.START)
    )
    val currentNode: StateFlow<Node> = _currentNode.asStateFlow()

    /** Convenience accessor for the current node ID. */
    val currentNodeId: String
        get() = _currentNode.value.id

    /** Whether backwards navigation is currently possible. */
    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    /** UI-level event stream (snackbars, dialogs, etc.). */
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    /** Emit a snackbar-like UI event. */
    fun emitSnack(message: String) {
        _events.tryEmit(UiEvent.Snack(message))
    }

    /** Emit a dialog UI event. */
    fun emitDialog(title: String, message: String) {
        _events.tryEmit(UiEvent.Dialog(title, message))
    }

    /* ───────────────────────────── Questions ───────────────────────────── */

    private val _questions = MutableStateFlow<Map<String, String>>(LinkedHashMap())
    val questions: StateFlow<Map<String, String>> = _questions.asStateFlow()

    /** Update or insert a question text for the given key (node ID). */
    fun setQuestion(text: String, key: String) {
        val k = key.trim()
        _questions.update { old ->
            old.mutableLinked().apply { put(k, text) }
        }
        if (DEBUG_MAP_MUTATION) {
            logd("setQuestion[$k] -> len=${text.length}")
        }
    }

    /** Retrieve a question text by key (node ID) or return an empty string. */
    fun getQuestion(key: String): String = questions.value[key.trim()].orEmpty()

    /** Clear all stored questions. */
    fun resetQuestions() {
        _questions.value = LinkedHashMap()
        if (DEBUG_MAP_MUTATION) logd("resetQuestions -> cleared")
    }

    /* ───────────────────────────── Answers ───────────────────────────── */

    private val _answers = MutableStateFlow<Map<String, String>>(LinkedHashMap())
    val answers: StateFlow<Map<String, String>> = _answers.asStateFlow()

    /** Update or insert an answer text for the given key (node ID). */
    fun setAnswer(text: String, key: String) {
        val k = key.trim()
        _answers.update { old ->
            old.mutableLinked().apply { put(k, text) }
        }
        if (DEBUG_MAP_MUTATION) {
            logd("setAnswer[$k] -> len=${text.length}")
        }
    }

    /** Convenience: update answer for the current node. */
    fun setAnswerForCurrent(text: String) {
        val k = currentNodeId.trim()
        if (k.isBlank() || k == LOADING_NODE_ID) return
        setAnswer(text, k)
    }

    /** Retrieve an answer by key (node ID) or return an empty string. */
    fun getAnswer(key: String): String = answers.value[key.trim()].orEmpty()

    /** Remove an answer associated with the given key (node ID). */
    fun clearAnswer(key: String) {
        val k = key.trim()
        _answers.update { old ->
            old.mutableLinked().apply { remove(k) }
        }
        if (DEBUG_MAP_MUTATION) {
            logd("clearAnswer[$k]")
        }
    }

    /** Clear all stored answers. */
    fun resetAnswers() {
        _answers.value = LinkedHashMap()
        if (DEBUG_MAP_MUTATION) logd("resetAnswers -> cleared")
    }

    /* ───────────────────────────── Text Input Persistence (HARDENED) ───────────────────────────── */

    /**
     * Current text input for the ACTIVE node (TEXT/AI).
     *
     * IMPORTANT:
     * - This must be driven by UI on every text change (two-way binding),
     *   otherwise drafts will be lost on back navigation.
     */
    private val _textInput = MutableStateFlow("")
    val textInput: StateFlow<String> = _textInput.asStateFlow()

    /**
     * Per-node cache for text input (TEXT/AI).
     *
     * Key: nodeId, Value: last text input draft.
     */
    private val _textByNode = MutableStateFlow<Map<String, String>>(LinkedHashMap())

    /**
     * Tracks the most recent restore moment for TEXT/AI nodes.
     *
     * Used to detect "blank update right after restore" which is usually a UI init/recomposition wipe.
     */
    @Volatile private var lastTextRestoreNodeId: String? = null
    @Volatile private var lastTextRestoreAtMs: Long = 0L

    private fun markTextRestore(nodeId: String) {
        lastTextRestoreNodeId = nodeId
        lastTextRestoreAtMs = SystemClock.elapsedRealtime()
    }

    private fun isWithinTextRestoreWindow(nodeId: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        val same = lastTextRestoreNodeId == nodeId
        val within = (now - lastTextRestoreAtMs) <= STICKY_BLANK_IGNORE_WINDOW_MS
        return same && within
    }

    /**
     * Returns draft text for a node.
     *
     * NOTE:
     * - This is backed by the per-node cache used for text persistence (_textByNode).
     */
    fun getAnswerDraft(nodeId: String): String {
        val k = nodeId.trim()
        return _textByNode.value[k].orEmpty()
    }

    /**
     * Saves a draft answer for a node safely.
     *
     * HARDENING:
     * - Non-blank draft will mirror to answers[] for Review/Export stability.
     * - Blank draft will NOT wipe answers[] (use forceClearTextForNode() for deliberate wipes).
     */
    fun setAnswerDraft(nodeId: String, draft: String) {
        val k = nodeId.trim()
        if (k.isBlank() || k == LOADING_NODE_ID) return

        val d = draft
        _textByNode.update { old ->
            LinkedHashMap(old).apply {
                if (d.isBlank()) remove(k) else put(k, d)
            }
        }

        if (d.isNotBlank()) {
            if (getAnswer(k) != d) setAnswer(d, k)
        }

        if (DEBUG_NAV_STATE) {
            logd("setAnswerDraft[$k] -> len=${d.length} (mirrorsAnswers=${d.isNotBlank()})")
        }
    }

    /** Clears the draft answer for the node (does NOT wipe answers[]). */
    fun clearAnswerDraft(nodeId: String) {
        val k = nodeId.trim()
        if (k.isBlank() || k == LOADING_NODE_ID) return
        _textByNode.update { old -> LinkedHashMap(old).apply { remove(k) } }
        if (DEBUG_NAV_STATE) logd("clearAnswerDraft[$k] -> draft cleared (answers kept)")
    }

    /**
     * Return the best-known text for a node without losing data.
     *
     * Order:
     *  1) screen (_textInput) if the node is current AND non-blank
     *  2) per-node cache (draft)
     *  3) answers map
     */
    fun getBestTextForNode(nodeId: String): String {
        val k = nodeId.trim()
        if (k.isBlank() || k == LOADING_NODE_ID) return ""

        val cur = if (currentNodeId.trim() == k) _textInput.value else ""
        val cached = _textByNode.value[k].orEmpty()
        val ans = getAnswer(k)

        return when {
            cur.isNotBlank() -> cur
            cached.isNotBlank() -> cached
            ans.isNotBlank() -> ans
            else -> ""
        }
    }

    /**
     * Node-targeted text input update (recommended).
     *
     * Why:
     * - Prevents late UI events (from a disposed screen) from mutating the "current" node accidentally.
     *
     * Behavior:
     * - If nodeId is the current node and type is TEXT/AI: updates screen state + persists (with wipe-protection).
     * - If nodeId is NOT current: updates only per-node cache + answers (non-blank only), without touching screen state.
     */
    fun setTextInput(nodeId: String, text: String) {
        val k = nodeId.trim()
        if (k.isBlank() || k == LOADING_NODE_ID) return

        val node = nodeOf(k)
        if (node.type != NodeType.TEXT && node.type != NodeType.AI) {
            if (DEBUG_NAV_STATE) logw("setTextInput[$k] ignored (nodeType=${node.type})")
            return
        }

        val isCurrent = (currentNodeId.trim() == k)

        if (!isCurrent) {
            // Off-screen update: do not touch _textInput (screen state).
            if (text.isNotBlank()) {
                _textByNode.update { old -> LinkedHashMap(old).apply { put(k, text) } }
                if (getAnswer(k) != text) setAnswer(text, k)
            } else {
                // Blank off-screen update is ignored to avoid accidental wipes.
                if (DEBUG_NAV_STATE) logw("setTextInput[$k] blank ignored (off-screen safety)")
            }
            return
        }

        // Current node: apply hardened logic.
        setTextInputForCurrent(text)
    }

    /**
     * Update the current text input for the active node and persist it.
     *
     * HARDENING RULE:
     * - Blank updates immediately after restore (within a short window) are treated as
     *   likely UI init/recomposition wipes and are ignored to protect existing cached content.
     * - After that window passes, a blank update is considered user-intended and is allowed,
     *   which will clear cache + answer for that node.
     *
     * If you truly want to clear text regardless of window, use:
     *  - forceClearTextForCurrent()
     */
    fun setTextInputForCurrent(text: String) {
        val nodeId = currentNodeId.trim()
        if (nodeId.isBlank() || nodeId == LOADING_NODE_ID) {
            _textInput.value = text
            return
        }

        val node = nodeOf(nodeId)
        if (node.type != NodeType.TEXT && node.type != NodeType.AI) {
            // Ignore text updates on non-text nodes to avoid state bleed.
            if (DEBUG_NAV_STATE) logw("setTextInputForCurrent[$nodeId] ignored (nodeType=${node.type})")
            return
        }

        val incoming = text
        val cached = _textByNode.value[nodeId].orEmpty()
        val ans = getAnswer(nodeId)

        val wouldWipe = incoming.isBlank() && (cached.isNotBlank() || ans.isNotBlank())
        val ignoreAsLikelyWipe = wouldWipe && isWithinTextRestoreWindow(nodeId)

        if (ignoreAsLikelyWipe) {
            // Keep the best-known value on screen to avoid flicker/confusing blank.
            val best = when {
                cached.isNotBlank() -> cached
                ans.isNotBlank() -> ans
                else -> ""
            }
            _textInput.value = best

            if (DEBUG_NAV_STATE) {
                logw(
                    "setTextInputForCurrent[$nodeId] blank ignored (restore-window) " +
                            "cachedLen=${cached.length} ansLen=${ans.length}"
                )
            }
            return
        }

        // Normal path: apply screen value first (UI should reflect user input).
        _textInput.value = incoming

        // Persist per-node cache.
        _textByNode.update { old ->
            LinkedHashMap(old).apply {
                if (incoming.isBlank()) remove(nodeId) else put(nodeId, incoming)
            }
        }

        // Mirror into answers map for downstream usage (review/export).
        if (incoming.isBlank()) {
            clearAnswer(nodeId)
        } else {
            setAnswer(incoming, nodeId)
        }

        if (DEBUG_NAV_STATE) {
            logd("setTextInputForCurrent[$nodeId] -> len=${incoming.length}")
        }
    }

    /**
     * Force clear text for the current TEXT/AI node.
     *
     * This is the only API that can wipe non-blank cached/answer values deterministically.
     */
    fun forceClearTextForCurrent() {
        val nodeId = currentNodeId.trim()
        if (nodeId.isBlank() || nodeId == LOADING_NODE_ID) return

        val node = nodeOf(nodeId)
        if (node.type != NodeType.TEXT && node.type != NodeType.AI) {
            _textInput.value = ""
            return
        }

        _textInput.value = ""
        _textByNode.update { old ->
            LinkedHashMap(old).apply { remove(nodeId) }
        }
        clearAnswer(nodeId)

        if (DEBUG_NAV_STATE) logd("forceClearTextForCurrent[$nodeId] -> cleared")
    }

    /**
     * Force clear text for a specific node (TEXT/AI).
     *
     * Useful for "Reset this answer" UI actions where nodeId is known.
     */
    fun forceClearTextForNode(nodeId: String) {
        val k = nodeId.trim()
        if (k.isBlank() || k == LOADING_NODE_ID) return
        val node = nodeOf(k)
        if (node.type != NodeType.TEXT && node.type != NodeType.AI) return

        _textByNode.update { old -> LinkedHashMap(old).apply { remove(k) } }
        clearAnswer(k)

        if (currentNodeId.trim() == k) {
            _textInput.value = ""
            lastTextRestoreNodeId = null
            lastTextRestoreAtMs = 0L
        }

        if (DEBUG_NAV_STATE) logd("forceClearTextForNode[$k] -> cleared")
    }

    /** Clear cached text input state for ALL nodes. */
    private fun resetTextCaches() {
        _textByNode.value = LinkedHashMap()
        _textInput.value = ""
        lastTextRestoreNodeId = null
        lastTextRestoreAtMs = 0L
        if (DEBUG_NAV_STATE) logd("resetTextCaches -> cleared")
    }

    /**
     * Restore text input for a node from caches, with a safety fallback to answers[].
     */
    private fun restoreTextInput(nodeId: String) {
        val k = nodeId.trim()
        if (k.isBlank() || k == LOADING_NODE_ID) {
            _textInput.value = ""
            lastTextRestoreNodeId = null
            lastTextRestoreAtMs = 0L
            return
        }

        val fromCache = _textByNode.value[k]
        val fromAnswers = getAnswer(k)

        // Prefer cache if present; else fall back to answers.
        val restored = when {
            !fromCache.isNullOrEmpty() -> fromCache
            fromAnswers.isNotEmpty() -> fromAnswers
            else -> ""
        }

        _textInput.value = restored
        markTextRestore(k)

        if (DEBUG_NAV_STATE) {
            logd(
                "restoreTextInput[$k] -> len=${restored.length} " +
                        "(cache=${fromCache?.length ?: 0}, ans=${fromAnswers.length})"
            )
        }
    }

    /**
     * Commit text input for a node in a "sticky" way:
     * - Never overwrites an existing non-blank cache/answer with blank.
     * - Useful when UI accidentally clears input during composition.
     */
    private fun commitTextInputSticky(nodeId: String) {
        val k = nodeId.trim()
        if (k.isBlank() || k == LOADING_NODE_ID) return

        val cur = _textInput.value
        val cached = _textByNode.value[k].orEmpty()
        val ans = getAnswer(k)

        // Pick the best known value without losing info.
        val best = when {
            cur.isNotBlank() -> cur
            cached.isNotBlank() -> cached
            ans.isNotBlank() -> ans
            else -> ""
        }

        if (best.isNotBlank()) {
            _textByNode.update { old ->
                LinkedHashMap(old).apply { put(k, best) }
            }
            if (ans != best) setAnswer(best, k)

            if (DEBUG_NAV_STATE) logd("commitTextInputSticky[$k] -> keep len=${best.length}")
        } else {
            if (DEBUG_NAV_STATE) logd("commitTextInputSticky[$k] -> skip blank")
        }
    }

    /* ───────────────────────────── Choice Selections ───────────────────────────── */

    /**
     * Current single-choice selection for the ACTIVE node.
     *
     * IMPORTANT:
     * - This is screen-level state; we must persist it per node to survive navigation.
     */
    private val _single = MutableStateFlow<String?>(null)
    val single: StateFlow<String?> = _single.asStateFlow()

    /**
     * Current multi-choice selection for the ACTIVE node.
     *
     * IMPORTANT:
     * - This is screen-level state; we must persist it per node to survive navigation.
     */
    private val _multi = MutableStateFlow<Set<String>>(emptySet())
    val multi: StateFlow<Set<String>> = _multi.asStateFlow()

    /**
     * Per-node cache for single-choice selection.
     *
     * Key: nodeId, Value: selected option or null.
     */
    private val _singleByNode = MutableStateFlow<Map<String, String?>>(LinkedHashMap())

    /**
     * Per-node cache for multi-choice selection.
     *
     * Key: nodeId, Value: set of selected options.
     */
    private val _multiByNode = MutableStateFlow<Map<String, Set<String>>>(LinkedHashMap())

    /**
     * Node-targeted single-choice update (recommended).
     *
     * - If nodeId is current: updates screen state + persists.
     * - If nodeId is NOT current: updates caches + answers only (no screen mutation).
     */
    fun setSingleChoice(nodeId: String, opt: String?) {
        val k = nodeId.trim()
        if (k.isBlank() || k == LOADING_NODE_ID) return

        val node = nodeOf(k)
        if (node.type != NodeType.SINGLE_CHOICE) {
            if (DEBUG_NAV_STATE) logw("setSingleChoice[$k] ignored (nodeType=${node.type})")
            return
        }

        val normalized = opt?.trim().takeIf { !it.isNullOrBlank() }

        _singleByNode.update { old ->
            LinkedHashMap(old).apply { put(k, normalized) }
        }

        if (normalized.isNullOrBlank()) {
            clearAnswer(k)
        } else {
            setAnswer(normalized, k)
        }

        if (currentNodeId.trim() == k) {
            _single.value = normalized
            _multi.value = emptySet()
        }

        if (DEBUG_NAV_STATE) logd("setSingleChoice[$k] -> '${normalized.orEmpty()}'")
    }

    /**
     * Set the current single-choice selection, or null to clear.
     */
    fun setSingleChoice(opt: String?) {
        val nodeId = currentNodeId.trim()
        if (nodeId.isBlank() || nodeId == LOADING_NODE_ID) {
            _single.value = opt
            return
        }
        setSingleChoice(nodeId, opt)
    }

    /**
     * Node-targeted multi-choice toggle (recommended).
     *
     * - If nodeId is current: updates screen state + persists.
     * - If nodeId is NOT current: updates caches + answers only (no screen mutation).
     */
    fun toggleMultiChoice(nodeId: String, opt: String) {
        val k = nodeId.trim()
        if (k.isBlank() || k == LOADING_NODE_ID) return

        val node = nodeOf(k)
        if (node.type != NodeType.MULTI_CHOICE) {
            if (DEBUG_NAV_STATE) logw("toggleMultiChoice[$k] ignored (nodeType=${node.type})")
            return
        }

        val curSet = if (currentNodeId.trim() == k) _multi.value else (_multiByNode.value[k] ?: emptySet())
        val next = curSet.toMutableSet().apply {
            if (!add(opt)) remove(opt)
        }.toSet()

        _multiByNode.update { old ->
            LinkedHashMap(old).apply { put(k, next) }
        }

        if (next.isEmpty()) {
            clearAnswer(k)
        } else {
            val encoded = next.toList().sorted().joinToString(separator = "\n")
            setAnswer(encoded, k)
        }

        if (currentNodeId.trim() == k) {
            _multi.value = next
            _single.value = null
        }

        if (DEBUG_NAV_STATE) logd("toggleMultiChoice[$k] -> size=${next.size}")
    }

    /** Toggle multi-choice option for current node. */
    fun toggleMultiChoice(opt: String) {
        val nodeId = currentNodeId.trim()
        if (nodeId.isBlank() || nodeId == LOADING_NODE_ID) return
        toggleMultiChoice(nodeId, opt)
    }

    /**
     * Clear screen-level selections ONLY (does not touch per-node caches).
     */
    fun clearSelections() {
        _single.value = null
        _multi.value = emptySet()
    }

    /**
     * Clear screen-level text ONLY.
     *
     * HARDENING:
     * - If current TEXT/AI node already has non-blank cached/answer, ignore accidental clears.
     * - Use forceClearTextForCurrent() to deliberately clear persisted data.
     */
    fun clearTextInput() {
        val nodeId = currentNodeId.trim()
        if (nodeId.isBlank() || nodeId == LOADING_NODE_ID) {
            _textInput.value = ""
            return
        }

        val node = nodeOf(nodeId)
        if (node.type == NodeType.TEXT || node.type == NodeType.AI) {
            val cached = _textByNode.value[nodeId].orEmpty()
            val ans = getAnswer(nodeId)

            if (cached.isNotBlank() || ans.isNotBlank()) {
                if (DEBUG_NAV_STATE) {
                    logw(
                        "clearTextInput[$nodeId] ignored (sticky) " +
                                "cachedLen=${cached.length} ansLen=${ans.length}"
                    )
                }
                return
            }
        }

        _textInput.value = ""
    }

    /** Clear cached selection state for ALL nodes. */
    private fun resetSelectionCaches() {
        _singleByNode.value = LinkedHashMap()
        _multiByNode.value = LinkedHashMap()
    }

    /**
     * Parse multi-choice selection from an encoded answers[] value (newline-joined).
     */
    private fun parseMultiFromAnswer(answer: String): Set<String> {
        return answer
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /**
     * Commit selections for a node in a "sticky" way.
     */
    private fun commitSelectionsSticky(nodeId: String) {
        val k = nodeId.trim()
        if (k.isBlank() || k == LOADING_NODE_ID) return

        val node = nodeOf(k)

        when (node.type) {
            NodeType.SINGLE_CHOICE -> {
                val cur = _single.value?.trim().takeIf { !it.isNullOrBlank() }
                val cached = _singleByNode.value[k]?.trim().takeIf { !it.isNullOrBlank() }
                val ans = getAnswer(k).trim().takeIf { it.isNotBlank() }

                val best: String? = when {
                    !cur.isNullOrBlank() -> cur
                    !cached.isNullOrBlank() -> cached
                    !ans.isNullOrBlank() -> ans
                    else -> null
                }

                if (!best.isNullOrBlank()) {
                    _singleByNode.update { old ->
                        LinkedHashMap(old).apply { put(k, best) }
                    }
                    if (getAnswer(k) != best) setAnswer(best, k)
                } else {
                    if (DEBUG_NAV_STATE) logd("commitSelectionsSticky[$k] single -> skip (all empty)")
                }
            }

            NodeType.MULTI_CHOICE -> {
                val cur = _multi.value
                val cached = _multiByNode.value[k]
                val ans = getAnswer(k)

                val best: Set<String> = when {
                    cur.isNotEmpty() -> cur
                    cached != null && cached.isNotEmpty() -> cached
                    ans.isNotBlank() -> parseMultiFromAnswer(ans)
                    else -> emptySet()
                }

                if (best.isNotEmpty()) {
                    _multiByNode.update { old ->
                        LinkedHashMap(old).apply { put(k, best) }
                    }
                    val encoded = best.toList().sorted().joinToString(separator = "\n")
                    if (getAnswer(k) != encoded) setAnswer(encoded, k)
                } else {
                    if (DEBUG_NAV_STATE) logd("commitSelectionsSticky[$k] multi -> skip (all empty)")
                }
            }

            else -> Unit
        }
    }

    /**
     * Restore selections for a node:
     * - Prefer caches.
     * - Fall back to answers[] as a safety net.
     */
    private fun restoreSelections(nodeId: String) {
        val k = nodeId.trim()
        if (k.isBlank() || k == LOADING_NODE_ID) {
            _single.value = null
            _multi.value = emptySet()
            return
        }

        val node = nodeOf(k)

        when (node.type) {
            NodeType.SINGLE_CHOICE -> {
                val cached = _singleByNode.value[k]?.trim().takeIf { !it.isNullOrBlank() }
                val ans = getAnswer(k).trim().takeIf { it.isNotBlank() }
                _single.value = cached ?: ans
                _multi.value = emptySet()
            }

            NodeType.MULTI_CHOICE -> {
                val cached = _multiByNode.value[k]
                val ans = getAnswer(k)

                val restored = when {
                    cached != null && cached.isNotEmpty() -> cached
                    cached != null && cached.isEmpty() && ans.isNotBlank() -> {
                        if (DEBUG_NAV_STATE) logw("restoreSelections[$k] cached empty but answers non-empty -> prefer answers")
                        parseMultiFromAnswer(ans)
                    }
                    ans.isNotBlank() -> parseMultiFromAnswer(ans)
                    else -> emptySet()
                }

                _multi.value = restored
                _single.value = null
            }

            else -> {
                _single.value = null
                _multi.value = emptySet()
            }
        }

        if (DEBUG_NAV_STATE) {
            logd("restoreSelections[$k] -> single=${_single.value != null}, multiSize=${_multi.value.size}")
        }
    }

    /**
     * Restore the screen input state for a node based on node type.
     */
    private fun restoreScreenState(nodeId: String) {
        val k = nodeId.trim()
        if (k.isBlank() || k == LOADING_NODE_ID) {
            _single.value = null
            _multi.value = emptySet()
            _textInput.value = ""
            lastTextRestoreNodeId = null
            lastTextRestoreAtMs = 0L
            return
        }

        val node = nodeOf(k)

        when (node.type) {
            NodeType.SINGLE_CHOICE, NodeType.MULTI_CHOICE -> {
                restoreSelections(k)
                _textInput.value = ""
                lastTextRestoreNodeId = null
                lastTextRestoreAtMs = 0L
            }

            NodeType.TEXT, NodeType.AI -> {
                _single.value = null
                _multi.value = emptySet()
                restoreTextInput(k)
            }

            else -> {
                _single.value = null
                _multi.value = emptySet()
                _textInput.value = ""
                lastTextRestoreNodeId = null
                lastTextRestoreAtMs = 0L
            }
        }
    }

    /**
     * Commit the current screen input state for a node in a safe way.
     */
    private fun commitScreenStateSticky(nodeId: String) {
        val k = nodeId.trim()
        if (k.isBlank() || k == LOADING_NODE_ID) return

        val node = nodeOf(k)
        when (node.type) {
            NodeType.SINGLE_CHOICE, NodeType.MULTI_CHOICE -> commitSelectionsSticky(k)
            NodeType.TEXT, NodeType.AI -> commitTextInputSticky(k)
            else -> Unit
        }
    }

    /* ───────────────────────────── Follow-ups ───────────────────────────── */

    /**
     * Follow-up entry used to track AI-generated questions, drafts, and committed answers.
     *
     * HARDENING:
     * - Draft does NOT mark an entry as "answered".
     * - "Unanswered" is determined by answeredAt == null.
     */
    @Serializable
    data class FollowupEntry(
        val question: String,
        val draft: String = "",
        val answer: String? = null,
        val askedAt: Long = System.currentTimeMillis(),
        val answeredAt: Long? = null
    ) {
        /** Returns the best text to display in UI (answer if committed; else draft). */
        fun bestText(): String = answer ?: draft
    }

    private val _followups = MutableStateFlow<Map<String, List<FollowupEntry>>>(LinkedHashMap())
    val followups: StateFlow<Map<String, List<FollowupEntry>>> = _followups.asStateFlow()

    private fun normalizeFollowupText(s: String): String =
        s.trim()
            .replace(Regex("\\s+"), " ")
            .replace("\u00A0", " ")
            .trim()

    /**
     * Add a follow-up question for a given node ID.
     *
     * HARDENING:
     * - Dedup across ALL existing followups for the node by normalized text (not just adjacent).
     */
    fun addFollowupQuestion(
        nodeId: String,
        question: String,
        dedupAll: Boolean = true
    ) {
        val k = nodeId.trim()
        val qNorm = normalizeFollowupText(question)
        if (k.isBlank() || qNorm.isBlank()) return

        _followups.update { old ->
            val mutable = old.mutableLinkedLists<FollowupEntry>()
            val list = mutable.getOrPut(k) { mutableListOf() }

            val exists = if (dedupAll) {
                list.any { normalizeFollowupText(it.question) == qNorm }
            } else {
                list.lastOrNull()?.let { normalizeFollowupText(it.question) == qNorm } == true
            }

            if (!exists) {
                list.add(FollowupEntry(question = question.trim()))
            }

            mutable.toImmutableLists()
        }

        if (DEBUG_NAV_STATE) logd("addFollowupQuestion[$k] -> '${qNorm.take(LOG_PREVIEW_MAX)}'")
    }

    /**
     * Set draft for a follow-up at a specific index.
     *
     * Draft does not mark it as answered.
     */
    fun setFollowupDraftAt(nodeId: String, index: Int, draft: String) {
        val k = nodeId.trim()
        _followups.update { old ->
            val mutable = old.mutableLinkedLists<FollowupEntry>()
            val list = mutable[k] ?: return@update old
            if (index !in list.indices) return@update old

            val prev = list[index]
            list[index] = prev.copy(draft = draft)
            mutable.toImmutableLists()
        }
    }

    /**
     * Commit answer for a follow-up at a specific index.
     *
     * This clears draft and sets answeredAt timestamp.
     */
    fun commitFollowupAnswerAt(nodeId: String, index: Int, answer: String) {
        val k = nodeId.trim()
        _followups.update { old ->
            val mutable = old.mutableLinkedLists<FollowupEntry>()
            val list = mutable[k] ?: return@update old
            if (index !in list.indices) return@update old

            val prev = list[index]
            list[index] = prev.copy(
                answer = answer,
                draft = "",
                answeredAt = System.currentTimeMillis()
            )
            mutable.toImmutableLists()
        }
    }

    /**
     * Commit answer for the last unanswered follow-up (answeredAt == null).
     */
    fun commitLastUnansweredFollowup(nodeId: String, answer: String) {
        val k = nodeId.trim()
        _followups.update { old ->
            val mutable = old.mutableLinkedLists<FollowupEntry>()
            val list = mutable[k] ?: return@update old
            val idx = list.indexOfLast { it.answeredAt == null }
            if (idx < 0) return@update old

            val prev = list[idx]
            list[idx] = prev.copy(
                answer = answer,
                draft = "",
                answeredAt = System.currentTimeMillis()
            )
            mutable.toImmutableLists()
        }
    }

    /**
     * Compatibility API for older UI code (AiScreen.kt).
     *
     * Notes:
     * - Older code sometimes calls answerLastFollowup(). Keep it as a thin wrapper.
     */
    fun answerLastFollowup(nodeId: String, answer: String) {
        commitLastUnansweredFollowup(nodeId, answer)
    }

    /**
     * Compatibility overload for "current node" usage.
     */
    fun answerLastFollowup(answer: String) {
        val k = currentNodeId.trim()
        if (k.isBlank() || k == LOADING_NODE_ID) return
        commitLastUnansweredFollowup(k, answer)
    }

    /** Remove all follow-ups associated with the given node ID. */
    fun clearFollowups(nodeId: String) {
        val k = nodeId.trim()
        _followups.update { old ->
            val mutable = old.mutableLinkedLists<FollowupEntry>()
            mutable.remove(k)
            mutable.toImmutableLists()
        }
    }

    /** Clear all follow-ups for all nodes. */
    fun resetFollowups() {
        _followups.value = LinkedHashMap()
    }

    /* ───────────────────────────── Recorded Audio Refs ───────────────────────────── */

    @Serializable
    data class AudioRef(
        val surveyId: String,
        val questionId: String,
        val fileName: String,
        val createdAt: Long = System.currentTimeMillis(),
        val byteSize: Long? = null,
        val checksum: String? = null
    )

    private val _recordedAudioRefs = MutableStateFlow<Map<String, List<AudioRef>>>(LinkedHashMap())
    val recordedAudioRefs: StateFlow<Map<String, List<AudioRef>>> = _recordedAudioRefs.asStateFlow()

    @Synchronized
    fun addAudioRef(
        questionId: String,
        fileName: String,
        byteSize: Long? = null,
        checksum: String? = null,
        dedupByFileName: Boolean = true
    ) {
        val qid = questionId.trim()
        val sid = surveyUuid.value

        _recordedAudioRefs.update { old ->
            val mutable = old.mutableLinkedLists<AudioRef>()
            val list = mutable.getOrPut(qid) { mutableListOf() }

            val existsSameRun = list.any { it.fileName == fileName && it.surveyId == sid }
            if (!dedupByFileName || !existsSameRun) {
                list.add(
                    AudioRef(
                        surveyId = sid,
                        questionId = qid,
                        fileName = fileName,
                        byteSize = byteSize,
                        checksum = checksum
                    )
                )
            }

            mutable.toImmutableLists()
        }

        logd("addAudioRef -> q=$qid, file=$fileName, sid=$sid")
    }

    @Synchronized
    fun replaceAudioRef(
        questionId: String,
        fileName: String,
        byteSize: Long? = null,
        checksum: String? = null
    ) {
        val qid = questionId.trim()
        val sid = surveyUuid.value

        _recordedAudioRefs.update { old ->
            val mutable = old.mutableLinkedLists<AudioRef>()
            mutable[qid] = mutableListOf(
                AudioRef(
                    surveyId = sid,
                    questionId = qid,
                    fileName = fileName,
                    byteSize = byteSize,
                    checksum = checksum
                )
            )
            mutable.toImmutableLists()
        }

        logd("replaceAudioRef -> q=$qid, file=$fileName, sid=$sid")
    }

    @Synchronized
    fun removeAudioRef(questionId: String, fileName: String) {
        val qid = questionId.trim()
        _recordedAudioRefs.update { old ->
            val mutable = old.mutableLinkedLists<AudioRef>()
            val list = mutable[qid] ?: return@update old

            list.removeAll { it.fileName == fileName }
            if (list.isEmpty()) mutable.remove(qid)

            mutable.toImmutableLists()
        }

        logd("removeAudioRef -> q=$qid, file=$fileName")
    }

    @Synchronized
    fun clearAudioRefs(questionId: String) {
        val qid = questionId.trim()
        _recordedAudioRefs.update { old ->
            val mutable = old.mutableLinkedLists<AudioRef>()
            mutable.remove(qid)
            mutable.toImmutableLists()
        }

        logd("clearAudioRefs -> q=$qid")
    }

    @Synchronized
    fun resetAudioRefs() {
        _recordedAudioRefs.value = LinkedHashMap()
        logd("resetAudioRefs -> cleared")
    }

    fun getAudioRefs(questionId: String): List<AudioRef> =
        recordedAudioRefs.value[questionId.trim()].orEmpty()

    fun getAudioRefsForRun(surveyId: String = surveyUuid.value): Map<String, List<AudioRef>> {
        return recordedAudioRefs.value
            .mapValues { (_, list) -> list.filter { it.surveyId == surveyId } }
            .filterValues { it.isNotEmpty() }
    }

    fun getAudioRefsForRunFlat(surveyId: String = surveyUuid.value): List<AudioRef> {
        return getAudioRefsForRun(surveyId)
            .values
            .flatten()
            .sortedBy { it.createdAt }
    }

    fun hasAudioRef(questionId: String, surveyId: String = surveyUuid.value): Boolean {
        return getAudioRefs(questionId).any { it.surveyId == surveyId }
    }

    fun onVoiceExported(
        questionId: String,
        fileName: String,
        byteSize: Long? = null,
        checksum: String? = null,
        replace: Boolean = false
    ) {
        if (replace) {
            replaceAudioRef(questionId, fileName, byteSize, checksum)
        } else {
            addAudioRef(questionId, fileName, byteSize, checksum, dedupByFileName = true)
        }
        logd("onVoiceExported -> q=${questionId.trim()}, file=$fileName, replace=$replace")
    }

    /* ───────────────────────────── Prompt Helpers ───────────────────────────── */

    /**
     * Returns true if the node has two-step prompts configured.
     */
    fun hasTwoStepPrompt(nodeId: String): Boolean {
        val k = nodeId.trim()
        if (k.isBlank()) return false

        val eval = config.resolveEvalPrompt(k)
        val follow = config.resolveFollowupPrompt(k)
        val has = !eval.isNullOrBlank() && !follow.isNullOrBlank()

        if (DEBUG_PROMPTS) {
            logd("hasTwoStepPrompt[$k] -> $has (eval=${eval?.length ?: 0}, fu=${follow?.length ?: 0})")
        }
        return has
    }

    /** Resolve prompt mode for the node. */
    fun getPromptMode(nodeId: String): PromptMode =
        if (hasTwoStepPrompt(nodeId)) PromptMode.TWO_STEP else PromptMode.ONE_STEP

    /**
     * Build a rendered ONE-STEP prompt string for the given node and answer.
     */
    fun getPrompt(nodeId: String, question: String, answer: String): String {
        val k = nodeId.trim()
        require(k.isNotBlank()) { "getPrompt: nodeId is blank" }

        val one = config.resolveOneStepPrompt(k)
        val eval = config.resolveEvalPrompt(k)

        val src = when {
            !one.isNullOrBlank() -> "one_step"
            !eval.isNullOrBlank() -> "eval_fallback"
            else -> "none"
        }

        val template = when (src) {
            "one_step" -> one!!
            "eval_fallback" -> eval!!
            else -> throw IllegalArgumentException(buildMissingPromptError(k, phase = "ONE_STEP"))
        }

        val rendered = renderTemplate(
            template = template,
            vars = linkedMapOf(
                KEY_QUESTION to question.trim(),
                KEY_ANSWER to answer.trim(),
                KEY_NODE_ID to k
            )
        )

        if (DEBUG_PROMPTS) {
            logd("getPrompt[$k] -> len=${rendered.length} (src=$src)")
        }

        return rendered
    }

    /**
     * Build a rendered STEP-1 (EVAL) prompt string.
     */
    fun getEvalPrompt(nodeId: String, question: String, answer: String): String {
        val k = nodeId.trim()
        require(k.isNotBlank()) { "getEvalPrompt: nodeId is blank" }

        val template = config.resolveEvalPrompt(k)
            ?: throw IllegalArgumentException(buildMissingPromptError(k, phase = "EVAL"))

        val rendered = renderTemplate(
            template = template,
            vars = linkedMapOf(
                KEY_QUESTION to question.trim(),
                KEY_ANSWER to answer.trim(),
                KEY_NODE_ID to k
            )
        )

        if (DEBUG_PROMPTS) {
            logd("getEvalPrompt[$k] -> len=${rendered.length}")
        }

        return rendered
    }

    /**
     * Build a rendered STEP-2 (FOLLOWUP) prompt string using step1 raw JSON.
     */
    fun getFollowupPrompt(
        nodeId: String,
        question: String,
        answer: String,
        evalJsonRaw: String
    ): String {
        val k = nodeId.trim()
        require(k.isNotBlank()) { "getFollowupPrompt: nodeId is blank" }

        val template = config.resolveFollowupPrompt(k)
            ?: throw IllegalArgumentException(buildMissingPromptError(k, phase = "FOLLOWUP"))

        val rendered = renderTemplate(
            template = template,
            vars = linkedMapOf(
                KEY_QUESTION to question.trim(),
                KEY_ANSWER to answer.trim(),
                KEY_NODE_ID to k,
                KEY_EVAL_JSON to evalJsonRaw.trim()
            )
        )

        if (DEBUG_PROMPTS) {
            logd("getFollowupPrompt[$k] -> len=${rendered.length} evalLen=${evalJsonRaw.length}")
        }

        return rendered
    }

    /**
     * Replace placeholders in a template using the format `{{KEY}}`.
     */
    private fun renderTemplate(template: String, vars: LinkedHashMap<String, String>): String {
        var out = template
        for ((key, value) in vars) {
            val k = Regex.escape(key)
            val pattern = Regex("\\{\\{\\s*$k\\s*\\}\\}")
            out = out.replace(pattern, value)
        }

        if (DEBUG_RENDER) {
            Log.v(TAG, "renderTemplate -> inLen=${template.length} outLen=${out.length} keys=${vars.keys}")
        }

        if (DEBUG_PROMPTS) {
            val leftover = Regex("\\{\\{\\s*[A-Z0-9_]+\\s*\\}\\}").find(out)?.value
            if (leftover != null) {
                logw("renderTemplate -> unresolved placeholder detected: '$leftover' (outLen=${out.length})")
            }
        }

        return out
    }

    /**
     * Build a detailed error message when prompt resolution fails.
     */
    private fun buildMissingPromptError(nodeId: String, phase: String): String {
        val legacyIds = config.prompts.map { it.nodeId.trim() }.filter { it.isNotBlank() }.distinct().sorted()
        val evalIds = config.promptsEval.map { it.nodeId.trim() }.filter { it.isNotBlank() }.distinct().sorted()
        val followIds = config.promptsFollowup.map { it.nodeId.trim() }.filter { it.isNotBlank() }.distinct().sorted()

        val one = config.resolveOneStepPrompt(nodeId)
        val eval = config.resolveEvalPrompt(nodeId)
        val follow = config.resolveFollowupPrompt(nodeId)

        val previewLegacy = legacyIds.take(24).joinToString(",")
        val previewEval = evalIds.take(24).joinToString(",")
        val previewFollow = followIds.take(24).joinToString(",")

        return buildString {
            append("No prompt defined for nodeId=$nodeId (phase=$phase). ")
            append("resolved(one=${one?.length ?: 0}, eval=${eval?.length ?: 0}, follow=${follow?.length ?: 0}). ")
            append("counts(legacy=${legacyIds.size}, eval=${evalIds.size}, follow=${followIds.size}). ")
            append("knownIds(legacy=[$previewLegacy], eval=[$previewEval], follow=[$previewFollow]). ")
            append("Hint: if your YAML uses prompts_eval/prompts_followup, legacy prompts[] may be empty; ")
            append("this VM now reads resolvers, so validate config pair completeness for the node.")
        }
    }

    /* ───────────────────────────── Navigation ───────────────────────────── */

    private fun navKeyFor(node: Node): NavKey {
        val id = node.id.trim()
        return when (node.type) {
            NodeType.START -> FlowHome(nodeId = id)
            NodeType.TEXT -> FlowText(nodeId = id)
            NodeType.SINGLE_CHOICE -> FlowSingle(nodeId = id)
            NodeType.MULTI_CHOICE -> FlowMulti(nodeId = id)
            NodeType.AI -> FlowAI(nodeId = id)
            NodeType.REVIEW -> FlowReview(nodeId = id)
            NodeType.DONE -> FlowDone(nodeId = id)
        }
    }

    /**
     * Dump state snapshot for debugging navigation issues.
     */
    private fun debugDumpNodeState(label: String, nodeId: String) {
        if (!DEBUG_NAV_STATE) return
        val k = nodeId.trim()
        if (k.isBlank()) return

        val qLen = getQuestion(k).length
        val aLen = getAnswer(k).length
        val singleCached = _singleByNode.value[k]
        val multiCachedSize = _multiByNode.value[k]?.size ?: 0
        val textCachedLen = _textByNode.value[k]?.length ?: 0

        logd(
            "state[$label] node=$k qLen=$qLen aLen=$aLen " +
                    "screen(single=${_single.value != null}, multi=${_multi.value.size}, textLen=${_textInput.value.length}) " +
                    "cache(single=${singleCached != null}, multi=$multiCachedSize, textLen=$textCachedLen) " +
                    "stack=${nodeStack.size} nav=${nav.size}"
        )
    }

    /**
     * Ensure questions[] contains a stable question for the given node id.
     */
    private fun ensureQuestion(id: String) {
        val k = id.trim()
        if (k.isBlank()) return
        if (getQuestion(k).isNotEmpty()) return

        val node = nodeOf(k)
        val q = when {
            node.question.isNotBlank() -> node.question
            node.title.isNotBlank() -> node.title
            else -> ""
        }

        if (q.isNotBlank()) setQuestion(q, k)
    }

    @Synchronized
    private fun pushInternal(node: Node, commitPrev: Boolean) {
        warnIfNotMainThread("push")

        val prevId = _currentNode.value.id.trim()
        if (commitPrev && prevId.isNotBlank() && prevId != LOADING_NODE_ID) {
            commitScreenStateSticky(prevId)
            debugDumpNodeState("leave", prevId)
        }

        ensureQuestion(node.id)

        _currentNode.value = node
        nodeStack.addLast(node.id.trim())

        restoreScreenState(node.id)

        nav.add(navKeyFor(node))
        updateCanGoBack()

        debugDumpNodeState("enter", node.id)
        logd("push -> ${node.id}, navSize=${nav.size}, stackSize=${nodeStack.size}")
        assertInvariants("push")
    }

    @Synchronized
    private fun push(node: Node) {
        pushInternal(node, commitPrev = true)
    }

    @Synchronized
    fun goto(nodeId: String) {
        warnIfNotMainThread("goto")

        val k = nodeId.trim()
        if (k.isBlank()) return
        if (k == currentNodeId.trim()) {
            if (DEBUG_NAV_STATE) logd("goto[$k] ignored (already current)")
            return
        }

        val node = nodeOf(k)
        ensureQuestion(node.id)
        push(node)
    }

    @Synchronized
    fun replaceTo(nodeId: String) {
        warnIfNotMainThread("replaceTo")

        val k = nodeId.trim()
        if (k.isBlank()) return
        if (k == currentNodeId.trim()) {
            if (DEBUG_NAV_STATE) logd("replaceTo[$k] ignored (already current)")
            return
        }

        val prevId = _currentNode.value.id.trim()
        if (prevId.isNotBlank() && prevId != LOADING_NODE_ID) {
            commitScreenStateSticky(prevId)
            debugDumpNodeState("leave_replace", prevId)
        }

        val node = nodeOf(k)
        ensureQuestion(node.id)

        if (nodeStack.isNotEmpty()) {
            nodeStack.removeLast()
        }
        nav.removeLastOrNull()

        pushInternal(node, commitPrev = false)
        logd("replaceTo -> ${node.id}")
        assertInvariants("replaceTo")
    }

    @Synchronized
    private fun resetNavToStart(stackIds: List<String>) {
        warnIfNotMainThread("resetNavToStart")

        while (nav.size > 0) nav.removeLastOrNull()
        for (id in stackIds) {
            val n = nodeOf(id)
            nav.add(navKeyFor(n))
        }
        logd("resetNavToStart -> navSize=${nav.size} stackSize=${stackIds.size}")
    }

    @Synchronized
    fun resetToStart() {
        warnIfNotMainThread("resetToStart")

        regenerateSurveyUuid()

        resetQuestions()
        resetAnswers()
        resetFollowups()
        resetAudioRefs()

        clearSelections()
        resetSelectionCaches()
        resetTextCaches()

        nodeStack.clear()

        val start = nodeOf(startId)
        ensureQuestion(start.id)

        _currentNode.value = start
        nodeStack.addLast(start.id.trim())

        restoreScreenState(start.id)

        resetNavToStart(nodeStack.toList())

        updateCanGoBack()
        _sessionId.update { it + 1 }

        debugDumpNodeState("reset_start", start.id)
        logd("resetToStart -> ${start.id}")
        assertInvariants("resetToStart")
    }

    @Synchronized
    fun backToPrevious() {
        warnIfNotMainThread("backToPrevious")

        if (nodeStack.size <= 1) {
            logd("backToPrevious: at root (no-op)")
            return
        }

        val curId = _currentNode.value.id.trim()
        if (curId.isNotBlank() && curId != LOADING_NODE_ID) {
            commitScreenStateSticky(curId)
            debugDumpNodeState("leave_back", curId)
        }

        nav.removeLastOrNull()
        nodeStack.removeLast()

        val prevId = nodeStack.last().trim()
        ensureQuestion(prevId)

        _currentNode.value = nodeOf(prevId)
        updateCanGoBack()

        restoreScreenState(prevId)

        debugDumpNodeState("enter_back", prevId)
        logd("backToPrevious -> $prevId")
        assertInvariants("backToPrevious")
    }

    @Synchronized
    fun advanceToNext() {
        warnIfNotMainThread("advanceToNext")

        val cur = _currentNode.value
        val nextId = cur.nextId?.trim().orEmpty()
        if (nextId.isBlank()) {
            logd("advanceToNext: no nextId from ${cur.id}")
            return
        }
        if (nextId == cur.id.trim()) {
            logw("advanceToNext: nextId equals currentId (loop) -> ignored (id=$nextId)")
            return
        }

        if (!graph.containsKey(nextId)) {
            throw IllegalStateException("nextId '$nextId' from node '${cur.id}' does not exist in graph.")
        }

        val curId = cur.id.trim()
        if (curId.isNotBlank() && curId != LOADING_NODE_ID) {
            commitScreenStateSticky(curId)
            debugDumpNodeState("leave_next", curId)
        }

        ensureQuestion(nextId)
        push(nodeOf(nextId))
        assertInvariants("advanceToNext")
    }

    private fun nodeOf(id: String): Node {
        val k = id.trim()
        return graph[k] ?: error(
            "Node not found: id=$k (definedCount=${graph.size}). " +
                    "Hint: check whitespace/typos in YAML node IDs."
        )
    }

    private fun updateCanGoBack() {
        _canGoBack.value = nodeStack.size > 1
    }

    /* ───────────────────────────── Snapshot (Debug / Persistence Helper) ───────────────────────────── */

    /**
     * Serializable snapshot of the survey runtime state.
     */
    @Serializable
    data class SurveySnapshot(
        val sessionId: Long,
        val surveyUuid: String,
        val currentNodeId: String,
        val nodeStack: List<String>,
        val questions: Map<String, String>,
        val answers: Map<String, String>,
        val textByNode: Map<String, String>,
        val singleByNode: Map<String, String?>,
        val multiByNode: Map<String, List<String>>,
        val followups: Map<String, List<FollowupEntry>>,
        val audioRefs: Map<String, List<AudioRef>>
    )

    /**
     * Export the current state as a JSON snapshot string.
     *
     * Returns empty string if snapshot is too large.
     */
    fun exportSnapshotJson(): String {
        val snap = SurveySnapshot(
            sessionId = _sessionId.value,
            surveyUuid = _surveyUuid.value,
            currentNodeId = currentNodeId.trim(),
            nodeStack = nodeStack.map { it.trim() }.toList(),
            questions = LinkedHashMap(_questions.value),
            answers = LinkedHashMap(_answers.value),
            textByNode = LinkedHashMap(_textByNode.value),
            singleByNode = LinkedHashMap(_singleByNode.value),
            multiByNode = _multiByNode.value.mapValues { (_, set) -> set.toList().sorted() },
            followups = LinkedHashMap(_followups.value),
            audioRefs = LinkedHashMap(_recordedAudioRefs.value)
        )

        val jsonStr = runCatching { json.encodeToString(snap) }.getOrElse {
            loge("exportSnapshotJson failed: ${it.message}")
            return ""
        }

        if (jsonStr.length > SNAPSHOT_MAX_CHARS) {
            logw("exportSnapshotJson skipped: too large (${jsonStr.length} chars)")
            return ""
        }

        if (DEBUG_NAV_STATE) logd("exportSnapshotJson -> chars=${jsonStr.length}")
        return jsonStr
    }

    /**
     * Restore state from a JSON snapshot.
     *
     * @param snapshotJson JSON created by exportSnapshotJson().
     * @param resetNav If true, rebuild Navigation3 backstack from nodeStack.
     */
    @Synchronized
    fun restoreSnapshotJson(snapshotJson: String, resetNav: Boolean = true): Boolean {
        warnIfNotMainThread("restoreSnapshotJson")

        val snap = runCatching { json.decodeFromString<SurveySnapshot>(snapshotJson) }.getOrElse {
            loge("restoreSnapshotJson decode failed: ${it.message}")
            emitSnack("Snapshot restore failed: ${it.message ?: "decode error"}")
            return false
        }

        val stack = snap.nodeStack.map { it.trim() }.filter { it.isNotBlank() && graph.containsKey(it) }
        val curId = snap.currentNodeId.trim().takeIf { it.isNotBlank() && graph.containsKey(it) } ?: startId

        _sessionId.value = snap.sessionId
        _surveyUuid.value = snap.surveyUuid

        _questions.value = LinkedHashMap(snap.questions)
        _answers.value = LinkedHashMap(snap.answers)
        _textByNode.value = LinkedHashMap(snap.textByNode)
        _singleByNode.value = LinkedHashMap(snap.singleByNode)

        val multiConverted = LinkedHashMap<String, Set<String>>()
        for ((k, list) in snap.multiByNode) {
            multiConverted[k.trim()] = list.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }
        _multiByNode.value = multiConverted

        _followups.value = LinkedHashMap(snap.followups)
        _recordedAudioRefs.value = LinkedHashMap(snap.audioRefs)

        nodeStack.clear()
        if (stack.isNotEmpty()) {
            stack.forEach { nodeStack.addLast(it) }
        } else {
            nodeStack.addLast(curId)
        }

        if (nodeStack.last().trim() != curId) {
            nodeStack.addLast(curId)
        }

        _currentNode.value = nodeOf(curId)
        restoreScreenState(curId)

        if (resetNav) {
            resetNavToStart(nodeStack.toList())
        }

        updateCanGoBack()
        debugDumpNodeState("restore_snapshot", curId)
        assertInvariants("restoreSnapshotJson")

        if (DEBUG_NAV_STATE) logd("restoreSnapshotJson -> ok (cur=$curId, stack=${nodeStack.size})")
        return true
    }

    /* ───────────────────────────── Map Helpers ───────────────────────────── */

    private fun Map<String, String>.mutableLinked(): LinkedHashMap<String, String> =
        LinkedHashMap(this)

    private fun <T> Map<String, List<T>>.mutableLinkedLists(): LinkedHashMap<String, MutableList<T>> {
        val result = LinkedHashMap<String, MutableList<T>>()
        for ((key, value) in this) result[key] = value.toMutableList()
        return result
    }

    private fun <T> LinkedHashMap<String, MutableList<T>>.toImmutableLists(): Map<String, List<T>> =
        this.mapValues { (_, list) -> list.toList() }

    /* ───────────────────────────── DTO Mapping ───────────────────────────── */

    /** Convert a config DTO node into a runtime node. */
    private fun NodeDTO.toVmNode(): Node {
        val rawType = this.type.trim()
        val t = when (rawType.uppercase()) {
            "START" -> NodeType.START
            "TEXT" -> NodeType.TEXT
            "SINGLE_CHOICE", "SINGLECHOICE", "RADIO" -> NodeType.SINGLE_CHOICE
            "MULTI_CHOICE", "MULTICHOICE", "CHECKBOX" -> NodeType.MULTI_CHOICE
            "AI", "LLM", "SLM" -> NodeType.AI
            "REVIEW" -> NodeType.REVIEW
            "DONE", "FINISH", "FINAL" -> NodeType.DONE
            else -> {
                if (DEBUG_NAV_STATE) logw("Unknown node type '$rawType' for id='${this.id.trim()}' -> default TEXT")
                NodeType.TEXT
            }
        }

        return Node(
            id = this.id.trim(),
            type = t,
            title = this.title,
            question = this.question,
            options = this.options,
            nextId = this.nextId?.trim()
        )
    }

    /* ───────────────────────────── Debug ───────────────────────────── */

    private fun debugDumpPromptSummary() {
        val legacyIds = config.prompts.map { it.nodeId.trim() }.filter { it.isNotBlank() }.distinct().sorted()
        val evalIds = config.promptsEval.map { it.nodeId.trim() }.filter { it.isNotBlank() }.distinct().sorted()
        val followIds = config.promptsFollowup.map { it.nodeId.trim() }.filter { it.isNotBlank() }.distinct().sorted()

        logd("promptSources -> legacy=${legacyIds.size}, eval=${evalIds.size}, follow=${followIds.size}")
        logd("promptSources.preview -> legacy=${legacyIds.take(24)}, eval=${evalIds.take(24)}, follow=${followIds.take(24)}")

        val aiNodeIds = graph.values.asSequence()
            .filter { it.type == NodeType.AI }
            .map { it.id.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()

        if (aiNodeIds.isNotEmpty()) {
            val missing = aiNodeIds.filter { id ->
                val hasOne = !config.resolveOneStepPrompt(id).isNullOrBlank()
                val hasTwo = hasTwoStepPrompt(id)
                !(hasOne || hasTwo)
            }

            if (missing.isNotEmpty()) {
                loge("AI prompt coverage missing for nodeIds=${missing.take(64)} (count=${missing.size})")
            } else {
                logd("AI prompt coverage OK (aiCount=${aiNodeIds.size})")
            }
        }
    }

    /**
     * Prefill questions map for all nodes to avoid "missing question" on back navigation.
     */
    private fun prefillQuestions() {
        val map = LinkedHashMap<String, String>()
        graph.values.forEach { n ->
            val id = n.id.trim()
            if (id.isBlank() || id == LOADING_NODE_ID) return@forEach

            val q = when {
                n.question.isNotBlank() -> n.question
                n.title.isNotBlank() -> n.title
                else -> ""
            }

            if (q.isNotBlank()) map[id] = q
        }

        if (map.isNotEmpty()) {
            _questions.value = map
            if (DEBUG_NAV_STATE) logd("prefillQuestions -> count=${map.size}")
        }
    }

    /* ───────────────────────────── Initialization ───────────────────────────── */

    init {
        val rawIds = config.graph.nodes.map { it.id }
        val trimmed = rawIds.map { it.trim() }
        val dup = trimmed.groupingBy { it }.eachCount().filter { it.key.isNotBlank() && it.value > 1 }.keys.sorted()
        if (dup.isNotEmpty()) {
            loge("Duplicate node IDs after trim detected: ${dup.take(64)} (count=${dup.size})")
        }

        graph = config.graph.nodes
            .associateBy { it.id.trim() }
            .mapValues { (_, dto) -> dto.toVmNode() }

        prefillQuestions()

        val start = nodeOf(startId)
        ensureQuestion(start.id)

        _currentNode.value = start
        nodeStack.clear()
        nodeStack.addLast(start.id.trim())

        restoreScreenState(start.id)

        resetNavToStart(nodeStack.toList())

        updateCanGoBack()

        logd("init -> ${start.id}, navSize=${nav.size}")

        if (DEBUG_NAV_STATE) {
            debugDumpNodeState("init", start.id)
            assertInvariants("init")
        }

        if (DEBUG_PROMPTS) {
            val issues = try {
                config.validate()
            } catch (t: Throwable) {
                listOf("validate() crashed: ${t.message}")
            }
            logd("config.validate -> issues=${issues.size}")
            if (issues.isNotEmpty()) {
                issues.take(64).forEach { logw("  - $it") }
            }

            debugDumpPromptSummary()
        }
    }
}
