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
 *  2026-02 Update:
 *   • Add session-level free text note:
 *       - Draft note editable on Home screen (NOT exported directly).
 *       - Run note is snapshotted at run start (exported + shown on Done).
 *   • Run UUID is regenerated in resetToStart(), so draft must NOT be carried
 *     automatically to a different run.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.vm

import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.negi.survey.config.NodeDTO
import com.negi.survey.config.SurveyConfig
import com.negi.survey.net.RuntimeLogStore
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

private const val TAG = "SurveyVM"

/* ───────────────────────────── Graph Model ───────────────────────────── */

enum class NodeType {
    START,
    TEXT,
    SINGLE_CHOICE,
    MULTI_CHOICE,
    AI,
    REVIEW,
    DONE
}

data class Node(
    val id: String,
    val type: NodeType,
    val title: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val nextId: String? = null
)

/* ───────────────────────────── Nav Keys ───────────────────────────── */

@Serializable object FlowHome : NavKey
@Serializable object FlowText : NavKey
@Serializable object FlowSingle : NavKey
@Serializable object FlowMulti : NavKey
@Serializable object FlowAI : NavKey
@Serializable object FlowReview : NavKey
@Serializable object FlowDone : NavKey

/* ───────────────────────────── UI Events ───────────────────────────── */

sealed interface UiEvent {
    data class Snack(val message: String) : UiEvent
    data class Dialog(val title: String, val message: String) : UiEvent
}

/* ───────────────────────────── Prompt Mode ───────────────────────────── */

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

        private const val KEY_QUESTION = "QUESTION"
        private const val KEY_ANSWER = "ANSWER"
        private const val KEY_NODE_ID = "NODE_ID"
        private const val KEY_EVAL_JSON = "EVAL_JSON"

        private const val KEY_PREVIEW_LIMIT = 64

        /** Export metadata key for the run-level free text note. */
        private const val EXPORT_META_SESSION_FREE_TEXT = "session_free_text"

        /** Safety cap for free text to keep exports stable. */
        private const val SESSION_FREE_TEXT_MAX_CHARS: Int = 20_000
    }

    private val graph: Map<String, Node>
    val nodes: Map<String, Node>
        get() = graph

    private val startId: String = config.graph.startId.trim()
    private val nodeStack = ArrayDeque<String>()

    private val _sessionId = MutableStateFlow(0L)
    val sessionId: StateFlow<Long> = _sessionId.asStateFlow()

    private val _surveyUuid = MutableStateFlow(UUID.randomUUID().toString())
    val surveyUuid: StateFlow<String> = _surveyUuid.asStateFlow()

    private val _runFreeText = MutableStateFlow("")
    val runFreeText: StateFlow<String> = _runFreeText.asStateFlow()

    private fun normalizeRunFreeText(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .take(SESSION_FREE_TEXT_MAX_CHARS)
    }

    fun setRunFreeText(text: String) {
        _runFreeText.value = normalizeRunFreeText(text)
    }


    fun resetRunFreeText() {
        _runFreeText.value = ""
    }

    @Deprecated("Use resetRunFreeText()", ReplaceWith("resetRunFreeText()"))
    fun resetSessionFreeText() {
        resetRunFreeText()
    }

    fun exportExtraMeta(): Map<String, String> {
        val t = runFreeText.value.trim()
        if (t.isBlank()) return emptyMap()
        return linkedMapOf(EXPORT_META_SESSION_FREE_TEXT to t.take(SESSION_FREE_TEXT_MAX_CHARS))
    }

    private fun regenerateSurveyUuid() {
        _surveyUuid.value = UUID.randomUUID().toString()
    }

    private val _currentNode = MutableStateFlow(
        Node(id = "Loading", type = NodeType.START)
    )
    val currentNode: StateFlow<Node> = _currentNode.asStateFlow()

    val currentNodeId: String
        get() = _currentNode.value.id

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    fun emitSnack(message: String) {
        _events.tryEmit(UiEvent.Snack(message))
    }

    fun emitDialog(title: String, message: String) {
        _events.tryEmit(UiEvent.Dialog(title, message))
    }

    /* ───────────────────────────── Questions ───────────────────────────── */

    private val _questions = MutableStateFlow<Map<String, String>>(LinkedHashMap())
    val questions: StateFlow<Map<String, String>> = _questions.asStateFlow()

    fun setQuestion(text: String, key: String) {
        _questions.update { old ->
            old.mutableLinked().apply { put(key.trim(), text) }
        }
    }

    fun getQuestion(key: String): String = questions.value[key.trim()].orEmpty()

    fun resetQuestions() {
        _questions.value = LinkedHashMap()
    }

    /* ───────────────────────────── Answers ───────────────────────────── */

    private val _answers = MutableStateFlow<Map<String, String>>(LinkedHashMap())
    val answers: StateFlow<Map<String, String>> = _answers.asStateFlow()

    fun setAnswer(text: String, key: String) {
        _answers.update { old ->
            old.mutableLinked().apply { put(key.trim(), text) }
        }
    }

    fun getAnswer(key: String): String = answers.value[key.trim()].orEmpty()

    fun clearAnswer(key: String) {
        _answers.update { old ->
            old.mutableLinked().apply { remove(key.trim()) }
        }
    }

    fun resetAnswers() {
        _answers.value = LinkedHashMap()
    }

    /* ───────────────────────────── Choice Selections ───────────────────────────── */

    private val _single = MutableStateFlow<String?>(null)
    val single: StateFlow<String?> = _single.asStateFlow()

    fun setSingleChoice(opt: String?) {
        _single.value = opt
    }

    private val _multi = MutableStateFlow<Set<String>>(emptySet())
    val multi: StateFlow<Set<String>> = _multi.asStateFlow()

    fun toggleMultiChoice(opt: String) {
        _multi.update { cur ->
            cur.toMutableSet().apply {
                if (!add(opt)) remove(opt)
            }
        }
    }

    fun clearSelections() {
        _single.value = null
        _multi.value = emptySet()
    }

    /* ───────────────────────────── Follow-ups ───────────────────────────── */

    data class FollowupEntry(
        val question: String,
        val answer: String? = null,
        val askedAt: Long = System.currentTimeMillis(),
        val answeredAt: Long? = null
    )

    private val _followups = MutableStateFlow<Map<String, List<FollowupEntry>>>(LinkedHashMap())
    val followups: StateFlow<Map<String, List<FollowupEntry>>> = _followups.asStateFlow()

    fun addFollowupQuestion(
        nodeId: String,
        question: String,
        dedupAdjacent: Boolean = true
    ) {
        val k = nodeId.trim()
        _followups.update { old ->
            val mutable = old.mutableLinkedLists<FollowupEntry>()
            val list = mutable.getOrPut(k) { mutableListOf() }
            val last = list.lastOrNull()
            if (!(dedupAdjacent && last?.question == question)) {
                list.add(FollowupEntry(question = question))
            }
            mutable.toImmutableLists()
        }
    }

    fun answerLastFollowup(nodeId: String, answer: String) {
        val k = nodeId.trim()
        _followups.update { old ->
            val mutable = old.mutableLinkedLists<FollowupEntry>()
            val list = mutable[k] ?: return@update old
            val idx = list.indexOfLast { it.answer == null }
            if (idx < 0) return@update old
            list[idx] = list[idx].copy(
                answer = answer,
                answeredAt = System.currentTimeMillis()
            )
            mutable.toImmutableLists()
        }
    }

    fun answerFollowupAt(nodeId: String, index: Int, answer: String) {
        val k = nodeId.trim()
        _followups.update { old ->
            val mutable = old.mutableLinkedLists<FollowupEntry>()
            val list = mutable[k] ?: return@update old
            if (index !in list.indices) return@update old
            list[index] = list[index].copy(
                answer = answer,
                answeredAt = System.currentTimeMillis()
            )
            mutable.toImmutableLists()
        }
    }

    fun clearFollowups(nodeId: String) {
        val k = nodeId.trim()
        _followups.update { old ->
            val mutable = old.mutableLinkedLists<FollowupEntry>()
            mutable.remove(k)
            mutable.toImmutableLists()
        }
    }

    fun resetFollowups() {
        _followups.value = LinkedHashMap()
    }

    /* ───────────────────────────── Recorded Audio Refs ───────────────────────────── */

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

        RuntimeLogStore.d(TAG, "addAudioRef -> q=$qid, file=$fileName, sid=$sid")
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

        RuntimeLogStore.d(TAG, "replaceAudioRef -> q=$qid, file=$fileName, sid=$sid")
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

        RuntimeLogStore.d(TAG, "removeAudioRef -> q=$qid, file=$fileName")
    }

    @Synchronized
    fun clearAudioRefs(questionId: String) {
        val qid = questionId.trim()
        _recordedAudioRefs.update { old ->
            val mutable = old.mutableLinkedLists<AudioRef>()
            mutable.remove(qid)
            mutable.toImmutableLists()
        }

        RuntimeLogStore.d(TAG, "clearAudioRefs -> q=$qid")
    }

    @Synchronized
    fun resetAudioRefs() {
        _recordedAudioRefs.value = LinkedHashMap()
        RuntimeLogStore.d(TAG, "resetAudioRefs -> cleared")
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
        RuntimeLogStore.d(TAG, "onVoiceExported -> q=${questionId.trim()}, file=$fileName, replace=$replace")
    }

    /* ───────────────────────────── Prompt Helpers ───────────────────────────── */

    fun hasTwoStepPrompt(nodeId: String): Boolean {
        val k = nodeId.trim()
        if (k.isBlank()) return false

        val eval = config.resolveEvalPrompt(k)
        val follow = config.resolveFollowupPrompt(k)
        val has = !eval.isNullOrBlank() && !follow.isNullOrBlank()

        if (DEBUG_PROMPTS) {
            RuntimeLogStore.d(
                TAG,
                "hasTwoStepPrompt[$k] -> $has (eval=${eval?.length ?: 0}, fu=${follow?.length ?: 0})"
            )
        }
        return has
    }

    fun getPromptMode(nodeId: String): PromptMode =
        if (hasTwoStepPrompt(nodeId)) PromptMode.TWO_STEP else PromptMode.ONE_STEP

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
            RuntimeLogStore.d(TAG, "getPrompt[$k] -> len=${rendered.length} (src=$src)")
        }

        return rendered
    }

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
            RuntimeLogStore.d(TAG, "getEvalPrompt[$k] -> len=${rendered.length}")
        }

        return rendered
    }

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
            RuntimeLogStore.d(TAG, "getFollowupPrompt[$k] -> len=${rendered.length} evalLen=${evalJsonRaw.length}")
        }

        return rendered
    }

    private fun renderTemplate(template: String, vars: LinkedHashMap<String, String>): String {
        var out = template
        for ((key, value) in vars) {
            val k = Regex.escape(key)
            val pattern = Regex("\\{\\{\\s*$k\\s*\\}\\}")
            out = out.replace(pattern, value)
        }

        if (DEBUG_RENDER) {
            RuntimeLogStore.d(TAG, "renderTemplate -> inLen=${template.length} outLen=${out.length} keys=${vars.keys}")
        }

        if (DEBUG_PROMPTS) {
            val leftover = Regex("\\{\\{\\s*[A-Z0-9_]+\\s*\\}\\}").find(out)?.value
            if (leftover != null) {
                RuntimeLogStore.w(TAG, "renderTemplate -> unresolved placeholder detected: '$leftover' (outLen=${out.length})")
            }
        }

        return out
    }

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

    private fun navKeyFor(node: Node): NavKey =
        when (node.type) {
            NodeType.START -> FlowHome
            NodeType.TEXT -> FlowText
            NodeType.SINGLE_CHOICE -> FlowSingle
            NodeType.MULTI_CHOICE -> FlowMulti
            NodeType.AI -> FlowAI
            NodeType.REVIEW -> FlowReview
            NodeType.DONE -> FlowDone
        }

    @Synchronized
    private fun push(node: Node) {
        _currentNode.value = node
        nodeStack.addLast(node.id)

        clearSelections()

        nav.add(navKeyFor(node))
        updateCanGoBack()

        RuntimeLogStore.d(TAG, "push -> ${node.id}, navSize=${nav.size}, stackSize=${nodeStack.size}")
    }

    private fun ensureQuestion(id: String) {
        val k = id.trim()
        if (getQuestion(k).isEmpty()) {
            val questionText = nodeOf(k).question
            if (questionText.isNotEmpty()) setQuestion(questionText, k)
        }
    }

    @Synchronized
    fun goto(nodeId: String) {
        val k = nodeId.trim()
        val node = nodeOf(k)
        ensureQuestion(node.id)
        push(node)
    }

    @Synchronized
    fun replaceTo(nodeId: String) {
        val k = nodeId.trim()
        val node = nodeOf(k)
        ensureQuestion(node.id)

        if (nodeStack.isNotEmpty()) {
            nodeStack.removeLast()
            nav.removeLastOrNull()
        }

        push(node)
        RuntimeLogStore.d(TAG, "replaceTo -> ${node.id}")
    }

    @Synchronized
    private fun resetNavToStart(start: Node) {
        val startKey = navKeyFor(start)
        while (nav.size > 0) nav.removeLastOrNull()
        nav.add(startKey)
        RuntimeLogStore.d(TAG, "resetNavToStart -> key=$startKey, navSize=${nav.size}")
    }

    /**
     * Start a brand-new run (new UUID).
     *
     * preserveSessionFreeText:
     * - true  -> snapshot Home draft into run note, then clear draft.
     * - false -> clear both run note and draft.
     *
     * This prevents draft carryover across different run UUIDs.
     */
    @Synchronized
    fun resetToStart(preserveSessionFreeText: Boolean = false) {
        val keepFreeText = if (preserveSessionFreeText) {
            normalizeRunFreeText(_runFreeText.value)
        } else {
            ""
        }

        regenerateSurveyUuid()

        resetQuestions()
        resetAnswers()
        resetFollowups()
        resetAudioRefs()
        clearSelections()

        // Preserve only within this new run when explicitly requested.
        _runFreeText.value = keepFreeText

        nodeStack.clear()
        val start = nodeOf(startId)
        ensureQuestion(start.id)

        _currentNode.value = start
        nodeStack.addLast(start.id)

        resetNavToStart(start)

        updateCanGoBack()
        _sessionId.update { it + 1 }

        RuntimeLogStore.d(
            TAG,
            "resetToStart -> ${start.id}, session=${_sessionId.value}, uuid=${_surveyUuid.value}, preserveFreeText=$preserveSessionFreeText"
        )
    }

    @Synchronized
    fun backToPrevious() {
        if (nodeStack.size <= 1) {
            RuntimeLogStore.d(TAG, "backToPrevious: at root (no-op)")
            return
        }

        nav.removeLastOrNull()
        nodeStack.removeLast()

        val prevId = nodeStack.last()
        _currentNode.value = nodeOf(prevId)
        updateCanGoBack()

        clearSelections()

        RuntimeLogStore.d(TAG, "backToPrevious -> $prevId")
    }

    @Synchronized
    fun advanceToNext() {
        val cur = _currentNode.value
        val nextId = cur.nextId?.trim().orEmpty()
        if (nextId.isBlank()) {
            RuntimeLogStore.d(TAG, "advanceToNext: no nextId from ${cur.id}")
            return
        }

        if (!graph.containsKey(nextId)) {
            throw IllegalStateException(
                "nextId '$nextId' from node '${cur.id}' does not exist in graph. " +
                        "graphSize=${graph.size}, preview=${graph.keys.sorted().take(KEY_PREVIEW_LIMIT)}"
            )
        }

        ensureQuestion(nextId)
        push(nodeOf(nextId))
    }

    private fun nodeOf(id: String): Node {
        val k = id.trim()
        return graph[k] ?: error(buildNodeNotFoundError(k))
    }

    private fun buildNodeNotFoundError(id: String): String {
        val preview = graph.keys.sorted().take(KEY_PREVIEW_LIMIT)
        return buildString {
            append("Node not found: id='$id'. ")
            append("graphSize=${graph.size}. ")
            append("definedPreview=$preview")
            if (graph.size > preview.size) append(" ...(truncated)")
        }
    }

    private fun updateCanGoBack() {
        _canGoBack.value = nodeStack.size > 1
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
                RuntimeLogStore.w(TAG, "Unknown node type '$rawType' for id='${this.id}'. Defaulting to TEXT.")
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

        RuntimeLogStore.d(TAG, "promptSources -> legacy=${legacyIds.size}, eval=${evalIds.size}, follow=${followIds.size}")
        RuntimeLogStore.d(TAG, "promptSources.preview -> legacy=${legacyIds.take(24)}, eval=${evalIds.take(24)}, follow=${followIds.take(24)}")

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
                RuntimeLogStore.e(TAG, "AI prompt coverage missing for nodeIds=${missing.take(64)} (count=${missing.size})")
            } else {
                RuntimeLogStore.d(TAG, "AI prompt coverage OK (aiCount=${aiNodeIds.size})")
            }
        }
    }

    private fun validateGraphOrThrow(dtos: List<NodeDTO>) {
        require(startId.isNotBlank()) { "graph.startId is blank" }

        val ids = dtos.map { it.id.trim() }
        val blank = ids.filter { it.isBlank() }.distinct()
        require(blank.isEmpty()) { "Graph contains blank node IDs after trim." }

        val dup = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(dup.isEmpty()) { "Duplicate node IDs after trim: ${dup.sorted()}" }

        val idSet = ids.toSet()
        require(idSet.contains(startId)) {
            "startId '$startId' does not exist in graph. graphSize=${idSet.size}, preview=${idSet.sorted().take(KEY_PREVIEW_LIMIT)}"
        }

        val missingNext = dtos.asSequence()
            .mapNotNull { it.nextId?.trim() }
            .filter { it.isNotBlank() }
            .filter { !idSet.contains(it) }
            .distinct()
            .sorted()
            .toList()

        require(missingNext.isEmpty()) {
            "Graph contains nextId references that do not exist: $missingNext"
        }
    }

    /* ───────────────────────────── Initialization ───────────────────────────── */

    init {
        val dtos = config.graph.nodes
        validateGraphOrThrow(dtos)

        graph = dtos
            .associateBy { it.id.trim() }
            .mapValues { (_, dto) -> dto.toVmNode() }

        val start = nodeOf(startId)
        ensureQuestion(start.id)

        _currentNode.value = start
        nodeStack.clear()
        nodeStack.addLast(start.id)

        val startKey = navKeyFor(start)
        val needsReset = (nav.size != 1) || (nav.getOrNull(0) != startKey)
        if (needsReset) {
            resetNavToStart(start)
        }

        updateCanGoBack()

        _runFreeText.value = ""

        RuntimeLogStore.d(
            TAG,
            "init -> ${start.id}, session=${_sessionId.value}, uuid=${_surveyUuid.value}, navSize=${nav.size}, graphSize=${graph.size}"
        )

        if (DEBUG_PROMPTS) {
            val issues = try {
                config.validate()
            } catch (t: Throwable) {
                listOf("validate() crashed: ${t.message}")
            }
            RuntimeLogStore.d(TAG, "config.validate -> issues=${issues.size}")
            if (issues.isNotEmpty()) {
                issues.take(64).forEach { RuntimeLogStore.w(TAG, "  - $it") }
            }

            debugDumpPromptSummary()
        }
    }
}