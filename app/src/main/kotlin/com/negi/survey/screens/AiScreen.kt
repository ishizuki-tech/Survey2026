/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AiScreen.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  AI evaluation screen that renders a monotone, glass-like chat UI on
 *  top of the Survey + SLM pipeline.
 *
 *  Responsibilities:
 *   • Bind SurveyViewModel + AiViewModel to a single AI question node.
 *   • Render chat-style history with user messages and AI JSON responses.
 *   • Manage IME, focus, and auto-scroll during streaming.
 *   • Persist answers and follow-up questions back into SurveyViewModel.
 *   • Optionally accept a SpeechController to integrate speech-to-text
 *     (e.g., Whisper.cpp) into the answer composer.
 *
 *  Notes:
 *   • All comments use KDoc-style English descriptions.
 *   • No business logic is embedded; this screen only orchestrates VMs.
 *
 *  Update (2026-02-05 - performance hardening / dedup hardening):
 *  ---------------------------------------------------------------------
 *   • PERF: Avoid per-bubble rememberInfiniteTransition; use one global phase.
 *   • PERF: Animate only the typing bubble and the latest bubble.
 *   • HARDEN: normalizeFollowupKey strengthened to prevent near-duplicate follow-ups.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate")
@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class
)

package com.negi.survey.screens

import android.annotation.SuppressLint
import android.content.ClipData
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.negi.survey.slm.FollowupExtractor
import com.negi.survey.vm.AiViewModel
import com.negi.survey.vm.SurveyViewModel
import java.security.MessageDigest
import kotlin.collections.ArrayDeque
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/* =============================================================================
 * AI Evaluation Screen — Monotone × Glass × Chat
 * ============================================================================= */

private const val TAG = "AiScreen"
private const val TYPING_UPDATE_THROTTLE_MS = 90L
private const val AUTO_SCROLL_THROTTLE_MS = 40L

/** UI sender type for local chat state. */
private enum class ChatSenderUi {
    USER,
    AI
}

/**
 * Which kind of input the composer is currently collecting.
 *
 * MAIN:
 *  - The first user answer to the root question.
 *  - Draft typing should be stored separately from submitted answers.
 *
 * FOLLOWUP:
 *  - Answers to follow-up questions.
 *  - Must NOT overwrite the main answer in SurveyViewModel.
 */
private enum class InputRole {
    MAIN,
    FOLLOWUP
}

/**
 * Local chat message model scoped to this screen.
 *
 * @param id Stable identifier.
 * @param sender Sender type.
 * @param text Plain text payload.
 * @param json JSON payload (pretty-printed).
 * @param isTyping True when this is a streaming "typing" bubble.
 */
private data class ChatMsgUi(
    val id: String,
    val sender: ChatSenderUi,
    val text: String? = null,
    val json: String? = null,
    val isTyping: Boolean = false
)

/**
 * Simple abstraction for a speech-to-text controller (e.g., Whisper.cpp).
 */
interface SpeechController {
    /** True while microphone capture is running. */
    val isRecording: StateFlow<Boolean>

    /** True while the captured audio is being transcribed. */
    val isTranscribing: StateFlow<Boolean>

    /** Latest recognized text (partial or final). */
    val partialText: StateFlow<String>

    /** Optional human-readable error message. */
    val errorMessage: StateFlow<String?>

    /**
     * Update the context used for correlating speech with the survey run.
     *
     * Default implementation is a no-op so non-export controllers remain valid.
     */
    fun updateContext(
        surveyId: String?,
        questionId: String?
    ) {
        // no-op
    }

    /** Start capturing audio and producing partial or final text. */
    fun startRecording()

    /** Stop capturing audio and finalize the current utterance. */
    fun stopRecording()

    /** Convenience toggle that switches between start/stop. */
    fun toggleRecording() {
        if (isRecording.value) stopRecording() else startRecording()
    }
}

/**
 * Full-screen AI evaluation screen bound to a single survey node.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(
    nodeId: String,
    vmSurvey: SurveyViewModel,
    vmAI: AiViewModel,
    onNext: () -> Unit,
    onBack: () -> Unit,
    speechController: SpeechController? = null
) {
    val nid = remember(nodeId) { nodeId.trim() }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val density = LocalDensity.current

    // ---------------------------------------------------------------------
    // Global animation phase (PERF)
    // - Avoid per-bubble rememberInfiniteTransition.
    // - Bubble visuals can optionally use this phase when "animated".
    // ---------------------------------------------------------------------

    val bubblePhaseT = rememberInfiniteTransition(label = "bubble-global-phase")
    val bubblePhase by bubblePhaseT.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "bubblePhase"
    )

    // ---------------------------------------------------------------------
    // Survey state
    // ---------------------------------------------------------------------

    val rootQuestion by remember(vmSurvey, nid) {
        vmSurvey.questions.map { it[nid].orEmpty() }
    }.collectAsState(initial = vmSurvey.getQuestion(nid))

    val sessionId by vmSurvey.sessionId.collectAsState()
    val surveyUuid by vmSurvey.surveyUuid.collectAsState()

    LaunchedEffect(nid, sessionId, surveyUuid, speechController) {
        speechController?.updateContext(
            surveyId = surveyUuid,
            questionId = nid
        )
    }

    // Persisted follow-ups (source of truth across navigation).
    val followupsMap by vmSurvey.followups.collectAsState()
    val persistedFollowups = remember(followupsMap, nid) { followupsMap[nid].orEmpty() }

    // ---------------------------------------------------------------------
    // Speech state with null-safe fallbacks
    // ---------------------------------------------------------------------

    val recFlow = remember(speechController) { speechController?.isRecording ?: flowOf(false) }
    val transFlow = remember(speechController) { speechController?.isTranscribing ?: flowOf(false) }
    val partialFlow = remember(speechController) { speechController?.partialText ?: flowOf("") }
    val errFlow = remember(speechController) { speechController?.errorMessage ?: flowOf<String?>(null) }

    val speechRecording by recFlow.collectAsState(initial = false)
    val speechTranscribing by transFlow.collectAsState(initial = false)
    val speechPartial by partialFlow.collectAsState(initial = "")
    val speechError by errFlow.collectAsState(initial = null)

    val textFieldEnabled = !speechRecording && !speechTranscribing

    val speechStatusText: String? = when {
        speechError != null -> speechError
        speechController != null && speechTranscribing -> "Transcribing…"
        speechController != null && speechRecording -> "Listening…"
        else -> null
    }
    val speechStatusIsError = speechError != null

    // ---------------------------------------------------------------------
    // AI state
    // ---------------------------------------------------------------------

    val loading by vmAI.loading.collectAsState()
    val stream by vmAI.stream.collectAsState()
    val error by vmAI.error.collectAsState()

    /** Step history is the source of truth for "Step1 + Step2 both remain visible". */
    val stepHistory by vmAI.stepHistory.collectAsState()

    // ---------------------------------------------------------------------
    // Local UI state (chat + composer)
    // ---------------------------------------------------------------------

    val prettyJson = remember {
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            ignoreUnknownKeys = true
        }
    }

    /**
     * Restore composer text from persisted state.
     *
     * Rule:
     * - Draft (unsent) wins.
     * - Fallback to final (submitted).
     */
    fun restoreComposerText(): String {
        return vmSurvey.getAnswerDraft(nid).ifBlank { vmSurvey.getAnswer(nid) }
    }

    /**
     * Persist MAIN draft safely.
     *
     * - Never write draft into final unless Submit is pressed.
     * - Use named arguments to avoid String,String swap bugs.
     */
    fun persistMainDraft(text: String) {
        vmSurvey.setAnswerDraft(nodeId = nid, draft = text)
    }

    var composer by remember(nid, sessionId) {
        mutableStateOf(restoreComposerText())
    }

    var inputRole by remember(nid, sessionId) { mutableStateOf(InputRole.MAIN) }
    var activePromptQuestion by remember(nid, sessionId) { mutableStateOf(rootQuestion) }

    val chat = remember(nid, sessionId) { mutableStateListOf<ChatMsgUi>() }

    /** Seed guard for rebuilding chat from persisted state on re-entry. */
    var seededFromPersisted by remember(nid, sessionId) { mutableStateOf(false) }

    /** Seen follow-ups to prevent duplicates across re-entry + repeated model outputs. */
    val seenFollowups = remember(nid, sessionId) { HashSet<String>() }

    val focusRequester = remember { FocusRequester() }
    val scroll = rememberScrollState()

    val bottomThresholdPx = remember(density) { with(density) { 28.dp.roundToPx() } }
    val isPinnedToBottom by remember {
        derivedStateOf {
            val max = scroll.maxValue
            val cur = scroll.value
            (max - cur) <= bottomThresholdPx
        }
    }

    var lastAutoScrollMs by remember(nid, sessionId) { mutableLongStateOf(0L) }
    var lastTypingUpdateMs by remember(nid, sessionId) { mutableLongStateOf(0L) }
    var lastTypingLen by remember(nid, sessionId) { mutableIntStateOf(0) }

    var renderedStepCount by remember(nid, sessionId) { mutableIntStateOf(0) }

    // ---------------------------------------------------------------------
    // Root question bubble (id-stable upsert)
    // ---------------------------------------------------------------------

    LaunchedEffect(nid, sessionId, rootQuestion) {
        val qid = "qroot-$nid"
        val idx = chat.indexOfFirst { it.id == qid }
        if (idx == -1) {
            chat.add(
                ChatMsgUi(
                    id = qid,
                    sender = ChatSenderUi.AI,
                    text = rootQuestion
                )
            )
        } else {
            val cur = chat[idx]
            if (cur.text != rootQuestion) {
                chat[idx] = cur.copy(text = rootQuestion)
            }
        }

        /** Only update activePromptQuestion if we are still on root (MAIN role). */
        if (inputRole == InputRole.MAIN) {
            activePromptQuestion = rootQuestion
        }
    }

    // ---------------------------------------------------------------------
    // Sync composer at node/session boundary (DO NOT reset on rootQuestion change)
    // ---------------------------------------------------------------------

    LaunchedEffect(nid, sessionId) {
        val restored = restoreComposerText()
        composer = restored

        renderedStepCount = 0
        seededFromPersisted = false

        Log.i(
            TAG,
            "Composer restored (node=$nid) len=${restored.length} role=$inputRole qLen=${rootQuestion.length}"
        )
    }

    // ---------------------------------------------------------------------
    // Seed local chat + restore role from persisted followups (critical on re-entry)
    // + Render existing stepHistory (stepHistory is source of truth)
    //
    // Stabilization:
    // - Run ONCE per (nid, sessionId). Do not key on stepHistory/followups changes.
    // - Buffer follow-up persistence during seed to avoid mid-flight re-trigger.
    // ---------------------------------------------------------------------

    LaunchedEffect(nid, sessionId) {
        if (seededFromPersisted) return@LaunchedEffect

        // Mark early to prevent cancellation loops if state updates happen mid-flight.
        seededFromPersisted = true

        runCatching {
            chat.clear()
            seenFollowups.clear()

            // Stable snapshots for seeding.
            val rootQ = rootQuestion.ifBlank { vmSurvey.getQuestion(nid) }
            val stepsNow = stepHistory.toList()
            val persistedNow = persistedFollowups.toList()

            // Root question bubble.
            chat.add(
                ChatMsgUi(
                    id = "qroot-$nid",
                    sender = ChatSenderUi.AI,
                    text = rootQ
                )
            )

            // Main answer bubble (final only; never draft).
            val mainAns = vmSurvey.getAnswer(nid).trim()
            if (mainAns.isNotBlank()) {
                chat.add(
                    ChatMsgUi(
                        id = "amain-$nid",
                        sender = ChatSenderUi.USER,
                        text = mainAns
                    )
                )
            }

            // Index persisted follow-ups by normalized key for easy lookup.
            val persistedAnswerByKey = HashMap<String, String>(persistedNow.size * 2)
            val persistedHasKey = HashSet<String>(persistedNow.size * 2)

            persistedNow.forEach { e ->
                val q = e.question.trim()
                if (q.isBlank()) return@forEach
                val k = normalizeFollowupKey(q)
                persistedHasKey.add(k)
                val a = e.answer?.trim().orEmpty()
                if (a.isNotBlank()) persistedAnswerByKey[k] = a
            }

            // Buffer: follow-ups we discovered in stepHistory but are missing in Survey VM.
            val followupsToPersist = ArrayList<String>(8)

            // Render existing stepHistory into chat (re-entry correctness).
            // NOTE: Do not re-trigger evaluation; only rehydrate UI history.
            var lastUnansweredQ: String? = null

            stepsNow.forEachIndexed { i, step ->
                val stepRaw = step.raw
                val stripped = stripCodeFence(stepRaw).trim()

                val parsed = parseJsonLenient(prettyJson, stripped)
                val showAsJson =
                    (step.mode == AiViewModel.EvalMode.EVAL_JSON) || (parsed != null)

                val msgId = "seed-step-${step.runId}-$nid-$i"

                val resultMsg = if (showAsJson) {
                    ChatMsgUi(
                        id = msgId,
                        sender = ChatSenderUi.AI,
                        json = prettyOrRaw(prettyJson, stepRaw)
                    )
                } else {
                    // IMPORTANT:
                    // Show the actual raw text for non-JSON steps.
                    // Follow-ups are extracted and displayed separately.
                    ChatMsgUi(
                        id = msgId,
                        sender = ChatSenderUi.AI,
                        text = stripped.ifBlank { "…" }
                    )
                }

                chat.add(resultMsg)

                // Rehydrate follow-up Q/A threads implied by the steps.
                val followups = resolveFollowupsFromStep(prettyJson, step)
                followups.forEachIndexed { j, fu ->
                    val fuNorm = fu.trim()
                    if (fuNorm.isBlank()) return@forEachIndexed
                    val key = normalizeFollowupKey(fuNorm)

                    if (seenFollowups.add(key)) {
                        chat.add(
                            ChatMsgUi(
                                id = "seed-fuq-$nid-${step.runId}-$j",
                                sender = ChatSenderUi.AI,
                                text = fuNorm
                            )
                        )

                        // Buffer persistence if not already present.
                        if (!persistedHasKey.contains(key)) {
                            followupsToPersist.add(fuNorm)
                            persistedHasKey.add(key)
                        }

                        val ans = persistedAnswerByKey[key].orEmpty()
                        if (ans.isNotBlank()) {
                            chat.add(
                                ChatMsgUi(
                                    id = "seed-fua-$nid-${step.runId}-$j",
                                    sender = ChatSenderUi.USER,
                                    text = ans
                                )
                            )
                        } else {
                            lastUnansweredQ = fuNorm
                        }
                    }
                }
            }

            // Also replay any persisted follow-ups not shown via stepHistory (legacy / heuristic cases).
            persistedNow.forEachIndexed { i, e ->
                val q = e.question.trim()
                if (q.isBlank()) return@forEachIndexed
                val key = normalizeFollowupKey(q)
                val a = e.answer?.trim().orEmpty()

                if (seenFollowups.add(key)) {
                    chat.add(
                        ChatMsgUi(
                            id = "pfu-q-$nid-$i",
                            sender = ChatSenderUi.AI,
                            text = q
                        )
                    )
                    if (a.isNotBlank()) {
                        chat.add(
                            ChatMsgUi(
                                id = "pfu-a-$nid-$i",
                                sender = ChatSenderUi.USER,
                                text = a
                            )
                        )
                    } else {
                        lastUnansweredQ = q
                    }
                }
            }

            // Persist buffered follow-ups at the end (avoid mid-seed feedback loops).
            if (followupsToPersist.isNotEmpty()) {
                followupsToPersist.forEach { q ->
                    vmSurvey.addFollowupQuestion(nodeId = nid, question = q)
                }
                Log.i(
                    TAG,
                    "Seed buffered followups persisted (node=$nid count=${followupsToPersist.size})"
                )
            }

            // Restore role: if there is an unanswered follow-up, continue FOLLOWUP mode.
            // Prefer VM state if available; otherwise fallback to lastUnansweredQ found above.
            val entriesNow = vmSurvey.followups.value[nid].orEmpty()
            val lastUnanswered = entriesNow.lastOrNull { it.answer.isNullOrBlank() }

            if (lastUnanswered != null) {
                inputRole = InputRole.FOLLOWUP
                activePromptQuestion = lastUnanswered.question.trim().ifBlank { rootQ }
                composer = ""
            } else if (lastUnansweredQ != null) {
                inputRole = InputRole.FOLLOWUP
                activePromptQuestion = lastUnansweredQ.trim().ifBlank { rootQ }
                composer = ""
            } else {
                inputRole = InputRole.MAIN
                activePromptQuestion = rootQ
                composer = restoreComposerText()
            }

            // Mark existing history as rendered (we just rehydrated it).
            renderedStepCount = stepsNow.size

            Log.i(
                TAG,
                "Seeded from persisted (node=$nid) mainAnsLen=${mainAns.length} " +
                        "followupsPersisted=${persistedNow.size} buffered=${followupsToPersist.size} role=$inputRole " +
                        "renderedStepCount=$renderedStepCount stepHistory=${stepsNow.size}"
            )
        }.onFailure { err ->
            // If seed fails, allow re-attempt on next composition.
            seededFromPersisted = false
            Log.e(
                TAG,
                "Seed failed (node=$nid) errType=${err::class.java.simpleName} errMsg=${err.message}",
                err
            )
        }
    }

    // ---------------------------------------------------------------------
    // Focus / IME bootstrap
    // ---------------------------------------------------------------------

    LaunchedEffect(nid, sessionId) {
        delay(30)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    // ---------------------------------------------------------------------
    // Surface AI errors as snackbars
    // ---------------------------------------------------------------------

    LaunchedEffect(error) {
        val msg = error ?: return@LaunchedEffect
        snack.showSnackbar(msg)
        removeTypingBubble(chat, nid)
    }

    // ---------------------------------------------------------------------
    // Typing bubble orchestration (throttled)
    // ---------------------------------------------------------------------

    LaunchedEffect(loading, stream) {
        if (!loading) return@LaunchedEffect

        val now = SystemClock.uptimeMillis()
        val len = stream.length

        val shouldUpdate =
            (now - lastTypingUpdateMs) >= TYPING_UPDATE_THROTTLE_MS &&
                    (abs(len - lastTypingLen) >= 8 || len < 12)

        if (!shouldUpdate) return@LaunchedEffect

        lastTypingUpdateMs = now
        lastTypingLen = len

        // Do not inject "…". Blank stream should show TypingDots.
        upsertTypingBubble(
            chat = chat,
            nid = nid,
            text = stream
        )
    }

    // ---------------------------------------------------------------------
    // Render step snapshots into local chat + persist followups (multi + dedup)
    // ---------------------------------------------------------------------

    LaunchedEffect(stepHistory.size) {
        if (stepHistory.size < renderedStepCount) {
            renderedStepCount = 0
        }
        if (stepHistory.size <= renderedStepCount) return@LaunchedEffect

        val newSteps = stepHistory.subList(renderedStepCount, stepHistory.size)

        newSteps.forEachIndexed { idx, step ->
            val stepRaw = step.raw
            val stripped = stripCodeFence(stepRaw).trim()

            val parsed = parseJsonLenient(prettyJson, stripped)
            val showAsJson =
                (step.mode == AiViewModel.EvalMode.EVAL_JSON) || (parsed != null)

            val msgId = "step-${step.runId}-$nid-${renderedStepCount + idx}"

            // Render step result bubble (JSON or plain).
            val resultMsg = if (showAsJson) {
                ChatMsgUi(
                    id = msgId,
                    sender = ChatSenderUi.AI,
                    json = prettyOrRaw(prettyJson, stepRaw)
                )
            } else {
                // IMPORTANT:
                // Always show the actual step raw text for non-JSON steps.
                // Follow-ups are extracted and displayed as dedicated bubbles below.
                ChatMsgUi(
                    id = msgId,
                    sender = ChatSenderUi.AI,
                    text = stripped.ifBlank { "…" }
                )
            }

            if (idx == 0) {
                replaceTypingWith(chat, nid, resultMsg)
            } else {
                chat.add(resultMsg)
            }

            // Extract MULTIPLE followups and persist them (dedup).
            val followups = resolveFollowupsFromStep(prettyJson, step)
            var anyNew = false

            followups.forEach { fu ->
                val fuNorm = fu.trim().takeIf { it.isNotBlank() } ?: return@forEach
                val key = normalizeFollowupKey(fuNorm)
                if (seenFollowups.add(key)) {
                    anyNew = true

                    // If this follow-up is already fully contained in the plain step bubble, still show it separately.
                    // This keeps the "Q -> A" thread UX consistent and makes answering sequentially obvious.
                    chat.add(
                        ChatMsgUi(
                            id = "fuq-$nid-${step.runId}-${System.nanoTime()}",
                            sender = ChatSenderUi.AI,
                            text = fuNorm
                        )
                    )

                    vmSurvey.addFollowupQuestion(nodeId = nid, question = fuNorm)

                    Log.i(
                        TAG,
                        "Follow-up persisted (node=$nid runId=${step.runId} mode=${step.mode} len=${fuNorm.length} preview=${clipForLog(fuNorm, 120)})"
                    )
                } else {
                    Log.d(
                        TAG,
                        "Duplicate follow-up ignored (node=$nid) preview=${clipForLog(fuNorm, 120)}"
                    )
                }
            }

            // If any new followups were added, switch to FOLLOWUP and point to the last unanswered.
            if (anyNew) {
                val entriesNow = vmSurvey.followups.value[nid].orEmpty()
                val lastUnanswered = entriesNow.lastOrNull { it.answer.isNullOrBlank() }

                if (lastUnanswered != null) {
                    activePromptQuestion = lastUnanswered.question.trim().ifBlank { rootQuestion }
                    inputRole = InputRole.FOLLOWUP

                    scope.launch {
                        delay(40)
                        focusRequester.requestFocus()
                        keyboard?.show()
                    }
                }
            }

            Log.d(
                TAG,
                "Step rendered (node=$nid runId=${step.runId} mode=${step.mode} showJson=$showAsJson rawLen=${stepRaw.length} followupsInStep=${step.followups.size} timedOut=${step.timedOut})"
            )
        }

        renderedStepCount = stepHistory.size
    }

    LaunchedEffect(loading) {
        if (!loading) {
            removeTypingBubble(chat, nid)
        }
    }

    // ---------------------------------------------------------------------
    // Speech → Composer commit logic (edge-aware)
    // ---------------------------------------------------------------------

    var wasRecording by remember(nid, sessionId) { mutableStateOf(false) }
    var wasTranscribing by remember(nid, sessionId) { mutableStateOf(false) }
    var lastCommitted by remember(nid, sessionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(speechRecording, speechTranscribing, speechPartial) {
        if (speechController == null) return@LaunchedEffect

        val startedThisUtterance =
            (!wasRecording && !wasTranscribing) && (speechRecording || speechTranscribing)

        if (startedThisUtterance) {
            composer = ""
            lastCommitted = null

            // Do NOT clear final here. Clear draft only.
            if (inputRole == InputRole.MAIN) {
                vmSurvey.clearAnswerDraft(nid)
            }

            Log.d(TAG, "Speech started (node=$nid role=$inputRole)")
        }

        val finishedThisUtterance =
            (wasRecording || wasTranscribing) && !speechRecording && !speechTranscribing

        if (finishedThisUtterance) {
            val text = speechPartial.trim()
            if (text.isNotEmpty() && text != lastCommitted) {
                composer = text
                lastCommitted = text

                // Speech commits to draft only (unless user presses Send).
                if (inputRole == InputRole.MAIN) {
                    persistMainDraft(text)
                }

                Log.d(TAG, "Speech committed (node=$nid role=$inputRole len=${text.length})")
            } else {
                Log.d(TAG, "Speech finished with empty/no-change (node=$nid role=$inputRole)")
            }
        }

        wasRecording = speechRecording
        wasTranscribing = speechTranscribing
    }

    // ---------------------------------------------------------------------
    // Auto-scroll (pinned-to-bottom only)
    // ---------------------------------------------------------------------

    LaunchedEffect(chat.size) {
        if (!isPinnedToBottom) return@LaunchedEffect
        val now = SystemClock.uptimeMillis()
        if ((now - lastAutoScrollMs) < AUTO_SCROLL_THROTTLE_MS) return@LaunchedEffect
        lastAutoScrollMs = now

        delay(16)
        runCatching { scroll.animateScrollTo(scroll.maxValue) }
    }

    LaunchedEffect(stream, loading) {
        if (!loading || !isPinnedToBottom) return@LaunchedEffect
        val now = SystemClock.uptimeMillis()
        if ((now - lastAutoScrollMs) < AUTO_SCROLL_THROTTLE_MS) return@LaunchedEffect
        lastAutoScrollMs = now

        delay(24)
        runCatching { scroll.scrollTo(scroll.maxValue) }
    }

    // ---------------------------------------------------------------------
    // Submit logic
    // ---------------------------------------------------------------------

    /**
     * Build an "answer context" string for evaluation prompts.
     *
     * - Always includes the main/root answer.
     * - If follow-ups exist and have answers, append them in a stable format.
     *
     * This is critical for FOLLOWUP submissions:
     * - Without this, the model never sees the follow-up answers and may
     *   repeatedly ask the same clarification question.
     */
    fun buildAnswerForEval(mainAnswer: String): String {
        val base = mainAnswer.trim()
        if (base.isEmpty()) return ""

        // Avoid smart-cast pitfalls: materialize answer strings.
        val entries = vmSurvey.followups.value[nid].orEmpty()
            .mapNotNull { e ->
                val a = e.answer?.trim()
                if (a.isNullOrBlank()) null else e to a
            }

        if (entries.isEmpty()) return base

        val sb = StringBuilder()
        sb.append(base)
        sb.append("\n\n")
        sb.append("=== FOLLOW_UPS ===\n")
        entries.forEachIndexed { i, (e, a) ->
            sb.append("#").append(i + 1).append("\n")
            sb.append("Q: ").append(e.question.trim()).append("\n")
            sb.append("A: ").append(a).append("\n\n")
        }
        return sb.toString().trimEnd()
    }

    fun submit() {
        if (loading) return
        if (speechRecording || speechTranscribing) {
            scope.launch { snack.showSnackbar("Speech is active. Stop recording first.") }
            return
        }

        val input = composer.trim()
        if (input.isBlank()) return

        val isSubmittingMain = (inputRole == InputRole.MAIN)

        if (isSubmittingMain) {
            // Commit as submitted/final answer.
            vmSurvey.setAnswer(key = nid, text = input)

            // Draft is no longer needed once submitted.
            vmSurvey.clearAnswerDraft(nid)
        } else {
            val hasAny = vmSurvey.followups.value[nid].orEmpty().isNotEmpty()
            if (hasAny) {
                // By design: answer the last follow-up (chat semantics: newest question at bottom).
                vmSurvey.answerLastFollowup(nodeId = nid, answer = input)
            } else {
                // If FOLLOWUP role but list is empty, treat this as a MAIN submission.
                vmSurvey.setAnswer(key = nid, text = input)
                vmSurvey.clearAnswerDraft(nid)
                inputRole = InputRole.MAIN
                activePromptQuestion = rootQuestion
                Log.w(TAG, "FOLLOWUP role but no persisted followups; fallback to MAIN submit (node=$nid)")
            }
        }

        // Append user bubble.
        chat.add(
            ChatMsgUi(
                id = "u-$nid-${System.nanoTime()}",
                sender = ChatSenderUi.USER,
                text = input
            )
        )

        // Decide role AFTER persistence: if unanswered followups remain, continue FOLLOWUP and do NOT call AI.
        val pending = vmSurvey.followups.value[nid].orEmpty().filter { it.answer.isNullOrBlank() }
        if (pending.isNotEmpty()) {
            // Continue follow-up answering flow without triggering evaluation.
            val lastUnanswered = pending.last()
            inputRole = InputRole.FOLLOWUP
            activePromptQuestion = lastUnanswered.question.trim().ifBlank { rootQuestion }

            composer = ""

            // Keep IME and focus for sequential follow-up answering.
            scope.launch {
                delay(20)
                focusRequester.requestFocus()
                keyboard?.show()
            }

            Log.i(
                TAG,
                "Submit consumed follow-up; pending remain -> skip AI (node=$nid pending=${pending.size} active=${clipForLog(activePromptQuestion, 120)})"
            )
            return
        } else {
            // No pending followups -> back to MAIN prompt display (evaluation will decide completion).
            inputRole = InputRole.MAIN
            activePromptQuestion = rootQuestion
        }

        // Main answer stays stable (follow-up answer must not overwrite it).
        val mainAnswerStable = vmSurvey.getAnswer(nid).trim()
        if (mainAnswerStable.isBlank()) {
            // Defensive: never evaluate with empty main answer.
            Log.w(TAG, "Submit aborted: main answer is blank (node=$nid)")
            composer = ""
            return
        }

        // Build evaluation answer context (main + answered followups).
        val answerForEval = buildAnswerForEval(mainAnswerStable)

        scope.launch {
            val runId = "ai-${nid}-${SystemClock.uptimeMillis()}-${System.nanoTime()}"
            val t0 = SystemClock.uptimeMillis()

            val q = rootQuestion
            val isTwoStep = vmSurvey.hasTwoStepPrompt(nid)

            Log.i(
                TAG,
                "Submit begin runId=$runId node=$nid role=$inputRole isTwoStep=$isTwoStep " +
                        "sessionId=${runSafe { vmSurvey.sessionId.value }} surveyUuid=${runSafe { vmSurvey.surveyUuid.value }} " +
                        "qLen=${q.length} mainAnsLen=${mainAnswerStable.length} answerForEvalLen=${answerForEval.length} " +
                        "inputLen=${input.length} loading=$loading streamLen=${stream.length}"
            )

            runCatching {
                if (!isTwoStep) {
                    Log.d(TAG, "ONE_STEP prompt build start runId=$runId node=$nid")

                    val prompt = vmSurvey.getPrompt(nid, q, answerForEval)
                    Log.i(
                        TAG,
                        "ONE_STEP prompt build ok runId=$runId node=$nid " +
                                "promptLen=${prompt.length} promptHash=${shortHash(prompt)} " +
                                "promptPreview=${clipForLog(prompt, 120)}"
                    )

                    Log.d(TAG, "ONE_STEP evaluateAsync call runId=$runId node=$nid elapsed=${SystemClock.uptimeMillis() - t0}ms")
                    vmAI.evaluateAsync(prompt)
                    Log.d(TAG, "ONE_STEP evaluateAsync returned runId=$runId node=$nid elapsed=${SystemClock.uptimeMillis() - t0}ms")
                } else {
                    Log.d(TAG, "TWO_STEP step1 prompt build start runId=$runId node=$nid")

                    val prompt1 = vmSurvey.getEvalPrompt(nid, q, answerForEval)
                    Log.i(
                        TAG,
                        "TWO_STEP step1 prompt build ok runId=$runId node=$nid " +
                                "prompt1Len=${prompt1.length} prompt1Hash=${shortHash(prompt1)} " +
                                "prompt1Preview=${clipForLog(prompt1, 120)}"
                    )

                    Log.d(TAG, "TWO_STEP evaluateConditionalTwoStepAsync call runId=$runId node=$nid elapsed=${SystemClock.uptimeMillis() - t0}ms")

                    vmAI.evaluateConditionalTwoStepAsync(
                        firstPrompt = prompt1,
                        proceedOnTimeout = true,
                        shouldRunSecond = { step1 ->
                            val needed = extractFollowupNeeded(prettyJson, step1.raw) ?: false
                            Log.i(
                                TAG,
                                "TWO_STEP shouldRunSecond runId=$runId node=$nid " +
                                        "followup_needed=$needed timedOut=${step1.timedOut} rawLen=${step1.raw.length}"
                            )
                            needed
                        },
                        buildSecondPrompt = { step1 ->
                            val step1Raw = step1.raw
                            Log.d(
                                TAG,
                                "TWO_STEP step2 prompt build start runId=$runId node=$nid " +
                                        "step1TimedOut=${step1.timedOut} step1RawLen=${step1Raw.length}"
                            )

                            val prompt2 = runCatching {
                                vmSurvey.getFollowupPrompt(
                                    nodeId = nid,
                                    question = q,
                                    answer = answerForEval,
                                    evalJsonRaw = step1Raw
                                )
                            }.onSuccess { built ->
                                Log.i(
                                    TAG,
                                    "TWO_STEP step2 prompt build ok runId=$runId node=$nid " +
                                            "prompt2Len=${built.length} prompt2Hash=${shortHash(built)} " +
                                            "prompt2Preview=${clipForLog(built, 120)}"
                                )
                            }.getOrElse { err ->
                                Log.e(
                                    TAG,
                                    "TWO_STEP step2 prompt build failed runId=$runId node=$nid " +
                                            "errType=${err::class.java.simpleName} errMsg=${err.message}",
                                    err
                                )
                                buildString {
                                    append("Generate follow-up questions.\n")
                                    append("Return plain text only.\n\n")
                                    append("=== EVAL_JSON ===\n")
                                    append(step1Raw)
                                    append("\n")
                                }.also { fallback ->
                                    Log.w(
                                        TAG,
                                        "TWO_STEP step2 prompt fallback used runId=$runId node=$nid " +
                                                "fallbackLen=${fallback.length} fallbackHash=${shortHash(fallback)}"
                                    )
                                }
                            }

                            Log.d(TAG, "TWO_STEP step2 prompt build end runId=$runId node=$nid prompt2Len=${prompt2.length}")
                            prompt2
                        }
                    )

                    Log.d(
                        TAG,
                        "TWO_STEP evaluateConditionalTwoStepAsync returned runId=$runId node=$nid " +
                                "elapsed=${SystemClock.uptimeMillis() - t0}ms"
                    )
                }
            }.onFailure { err ->
                Log.e(
                    TAG,
                    "Submit failed runId=$runId node=$nid " +
                            "errType=${err::class.java.simpleName} errMsg=${err.message} " +
                            "elapsed=${SystemClock.uptimeMillis() - t0}ms " +
                            "loading=$loading streamLen=${stream.length}",
                    err
                )

                snack.showSnackbar("Submit failed: ${err.message ?: "unknown error"}")
                removeTypingBubble(chat, nid)
            }.also {
                Log.i(TAG, "Submit end runId=$runId node=$nid elapsed=${SystemClock.uptimeMillis() - t0}ms")
            }
        }

        composer = ""
        keyboard?.hide()
        focusManager.clearFocus(force = true)
    }

    // ---------------------------------------------------------------------
    // Visuals
    // ---------------------------------------------------------------------

    val bgBrush = animatedMonotoneBackplate()
    val title = if (inputRole == InputRole.FOLLOWUP) "Follow-up • $nid" else "Question • $nid"

    Scaffold(
        topBar = { CompactTopBar(title = title) },
        snackbarHost = { SnackbarHost(snack) },
        contentWindowInsets = zeroInsetsSafe()
    ) { pad ->
        Box(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .background(bgBrush)
                .imePadding()
                .pointerInput(Unit) {
                    detectTapGestures {
                        focusManager.clearFocus(force = true)
                        keyboard?.hide()
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val lastIndex = chat.lastIndex
                        chat.forEachIndexed { index, m ->
                            val isAi = m.sender == ChatSenderUi.AI
                            val isLast = (index == lastIndex)

                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End
                            ) {
                                if (m.json != null) {
                                    JsonBubbleMono(pretty = m.json, snack = snack)
                                } else {
                                    // PERF:
                                    // Animate only the typing bubble and the latest bubble.
                                    val animateThis = m.isTyping || isLast

                                    BubbleMono(
                                        text = m.text.orEmpty(),
                                        isAi = isAi,
                                        isTyping = m.isTyping,
                                        phase = bubblePhase,
                                        animate = animateThis
                                    )
                                }
                            }
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isPinnedToBottom && chat.isNotEmpty(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                    ) {
                        JumpToBottomPill(
                            onClick = { scope.launch { scroll.animateScrollTo(scroll.maxValue) } }
                        )
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .neutralEdge(alpha = 0.14f, corner = 16.dp, stroke = 1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        ChatComposer(
                            value = composer,
                            onValueChange = {
                                composer = it

                                // Save drafts only; do NOT create a "sent" answer.
                                if (inputRole == InputRole.MAIN) {
                                    // Named args to prevent swap; draft-only persistence.
                                    persistMainDraft(it)
                                }
                            },
                            onSend = ::submit,
                            enabled = textFieldEnabled && !loading,
                            focusRequester = focusRequester,
                            speechEnabled = speechController != null,
                            speechRecording = speechRecording,
                            speechTranscribing = speechTranscribing,
                            speechStatusText = speechStatusText,
                            speechStatusIsError = speechStatusIsError,
                            onToggleSpeech = speechController?.let { sc -> { sc.toggleRecording() } }
                        )

                        HorizontalDivider(
                            thickness = DividerDefaults.Thickness,
                            color = DividerDefaults.color
                        )

                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    vmAI.resetStates()
                                    onBack()
                                }
                            ) { Text("Back") }

                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))

                            OutlinedButton(
                                onClick = {
                                    vmAI.resetStates()
                                    onNext()
                                }
                            ) { Text("Next") }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(nid, sessionId) {
        onDispose {
            runCatching {
                if (speechController?.isRecording?.value == true) {
                    speechController.stopRecording()
                }
            }
            vmAI.resetStates()

            Log.i(
                TAG,
                "Dispose (node=$nid) finalMainLen=${vmSurvey.getAnswer(nid).length} draftMainLen=${vmSurvey.getAnswerDraft(nid).length} role=$inputRole"
            )
        }
    }
}

/* ───────────────────────────── Chat list helpers ───────────────────────────── */

private fun upsertTypingBubble(
    chat: MutableList<ChatMsgUi>,
    nid: String,
    text: String
) {
    val id = "typing-$nid"
    val idx = chat.indexOfFirst { it.id == id }
    val msg = ChatMsgUi(
        id = id,
        sender = ChatSenderUi.AI,
        text = text,
        isTyping = true
    )
    if (idx == -1) chat.add(msg) else chat[idx] = msg
}

private fun removeTypingBubble(
    chat: MutableList<ChatMsgUi>,
    nid: String
) {
    val id = "typing-$nid"
    val idx = chat.indexOfFirst { it.id == id }
    if (idx != -1) chat.removeAt(idx)
}

private fun replaceTypingWith(
    chat: MutableList<ChatMsgUi>,
    nid: String,
    msg: ChatMsgUi
) {
    val id = "typing-$nid"
    val idx = chat.indexOfFirst { it.id == id }
    if (idx != -1) chat[idx] = msg else chat.add(msg)
}

/* ───────────────────────────── Step helpers ─────────────────────────────── */

/**
 * Resolve follow-up questions from a step snapshot.
 *
 * Priority:
 *  1) step.followups list (already parsed upstream)
 *  2) JSON fields: followup_question / followups[] variants
 *  3) Plain-text heuristic (question-like line)
 *
 * Returns a list (may be empty).
 */
private fun resolveFollowupsFromStep(
    json: Json,
    step: AiViewModel.StepSnapshot
): List<String> {
    val out = ArrayList<String>(4)

    // 1) Provided list from StepSnapshot.
    step.followups.forEach { s ->
        val t = s.trim()
        if (t.isNotBlank()) out.add(t)
    }

    // 2) Extract from JSON (supports multiple keys + arrays).
    val fromJson = extractFollowupCandidates(json, step.raw)
    if (fromJson.isNotEmpty()) out.addAll(fromJson)

    // 3) Plain follow-up (single).
    if (out.isEmpty()) {
        extractPlainFollowup(step.raw)?.let { out.add(it) }
    }

    // De-dup (preserve order).
    val seen = HashSet<String>()
    val dedup = ArrayList<String>(out.size)
    out.forEach { s ->
        val k = normalizeFollowupKey(s)
        if (seen.add(k)) dedup.add(s)
    }
    return dedup
}

/**
 * Extract followup_needed from EVAL JSON (step1).
 *
 * Hardened:
 * - Accepts boolean (true/false), number (1/0), and string ("true"/"false"/"1"/"0").
 * - Accepts key variants: followup_needed / followup needed / follow up needed.
 */
private fun extractFollowupNeeded(json: Json, rawText: String): Boolean? {
    val stripped = stripCodeFence(rawText).trim()
    if (stripped.isEmpty()) return null
    val element = parseJsonLenient(json, stripped) as? JsonObject ?: return null

    fun normKey(k: String): String =
        k.lowercase().replace("_", " ").replace("-", " ").trim()

    fun primToBool(p: JsonPrimitive): Boolean? {
        p.booleanOrNull?.let { return it }
        p.intOrNull?.let { return it != 0 }
        val s = p.contentOrNull()?.trim()?.lowercase() ?: return null
        if (s == "true" || s == "1") return true
        if (s == "false" || s == "0") return false
        return null
    }

    for ((k, v) in element) {
        val nk = normKey(k)
        val isTarget =
            nk == "followup needed" || nk == "follow up needed"
        if (!isTarget) continue

        val prim = v as? JsonPrimitive ?: return null
        return primToBool(prim)
    }
    return null
}

/* ───────────────────────────── Log helpers ─────────────────────────────── */

private fun clipForLog(s: String, maxChars: Int): String {
    val t = s.replace("\n", "\\n").replace("\r", "\\r").trim()
    if (t.length <= maxChars) return "\"$t\""
    return "\"${t.take(maxChars)}…\""
}

private fun shortHash(s: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
    return bytes.take(6).joinToString("") { b -> "%02x".format(b) }
}

private inline fun <T> runSafe(block: () -> T): T? {
    return runCatching { block() }.getOrNull()
}

/* ───────────────────────────── Top bar ─────────────────────────────────── */

@Composable
private fun CompactTopBar(
    title: String,
    height: Dp = 32.dp
) {
    val cs = MaterialTheme.colorScheme
    val topBrush = Brush.horizontalGradient(
        listOf(
            cs.surface.copy(alpha = 0.96f),
            Color(0xFF1A1A1A).copy(alpha = 0.75f)
        )
    )
    Surface(color = Color.Transparent, tonalElevation = 0.dp) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(topBrush)
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(height)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                color = cs.onSurface
            )
        }
    }
}

@Composable
private fun JumpToBottomPill(
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = cs.surfaceVariant.copy(alpha = 0.72f),
        shape = RoundedCornerShape(999.dp),
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .neutralEdge(alpha = 0.14f, corner = 999.dp, stroke = 1.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "Jump to bottom",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Jump to bottom",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant
            )
        }
    }
}

/* ───────────────────────────── Chat bubbles ─────────────────────────────── */

@Composable
private fun BubbleMono(
    text: String,
    isAi: Boolean,
    isTyping: Boolean,
    phase: Float,
    animate: Boolean,
    maxWidth: Dp = 520.dp
) {
    val cs = MaterialTheme.colorScheme

    val corner = 12.dp
    val padH = 10.dp
    val padV = 7.dp
    val tailW = 7f
    val tailH = 6f

    // PERF:
    // Use a stable phase for most bubbles to avoid constant animation work.
    val p = if (animate) phase else 0.35f

    val stops = if (isAi) {
        listOf(Color(0xFF111111), Color(0xFF1E1E1E), Color(0xFF2A2A2A))
    } else {
        listOf(Color(0xFFEDEDED), Color(0xFFD9D9D9), Color(0xFFC8C8C8))
    }

    val grad = Brush.linearGradient(
        colors = stops.map { c -> lerp(c, cs.surface, 0.12f) },
        start = Offset(0f, 0f),
        end = Offset(400f + 220f * p, 360f - 180f * p)
    )

    val textColor = if (isAi) Color(0xFFECECEC) else Color(0xFF111111)
    val shape = RoundedCornerShape(
        topStart = corner,
        topEnd = corner,
        bottomStart = if (isAi) 4.dp else corner,
        bottomEnd = if (isAi) corner else 4.dp
    )

    Surface(
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 3.dp,
        shape = shape,
        modifier = Modifier
            .widthIn(max = maxWidth)
            .drawBehind {
                val cr = CornerRadius(corner.toPx(), corner.toPx())
                drawRoundRect(brush = grad, cornerRadius = cr)

                val x = if (isAi) 12f else size.width - 12f
                val dir = if (isAi) -1 else 1
                drawPath(
                    path = Path().apply {
                        moveTo(x, size.height)
                        lineTo(x + dir * tailW, size.height - tailH)
                        lineTo(x + dir * tailW * 0.4f, size.height - tailH * 0.6f)
                        close()
                    },
                    brush = grad
                )

                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
                        center = center,
                        radius = (min(size.width, size.height)) * 0.54f
                    ),
                    cornerRadius = cr
                )

                drawRoundRect(
                    brush = Brush.sweepGradient(
                        0f to Color(0xFF101010).copy(alpha = 0.12f),
                        0.5f to Color(0xFF7A7A7A).copy(alpha = 0.10f),
                        1f to Color(0xFF101010).copy(alpha = 0.12f)
                    ),
                    style = Stroke(width = 0.8.dp.toPx()),
                    cornerRadius = cr
                )
            }
    ) {
        Box(Modifier.padding(horizontal = padH, vertical = padV)) {
            if (isTyping && text.isBlank()) {
                TypingDots(color = textColor)
            } else {
                Text(
                    text = text,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp)
                )
            }
        }
    }
}

@Composable
private fun TypingDots(color: Color) {
    val t = rememberInfiniteTransition(label = "typing")
    val a1 by t.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, delayMillis = 0, easing = LinearEasing)
        ),
        label = "a1"
    )
    val a2 by t.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, delayMillis = 150, easing = LinearEasing)
        ),
        label = "a2"
    )
    val a3 by t.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, delayMillis = 300, easing = LinearEasing)
        ),
        label = "a3"
    )
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Dot(alpha = a1, color = color)
        Dot(alpha = a2, color = color)
        Dot(alpha = a3, color = color)
    }
}

@Composable
private fun Dot(alpha: Float, color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color.copy(alpha = alpha), CircleShape)
    )
}

/* ───────────────────────────── JSON bubble ──────────────────────────────── */

@Composable
private fun JsonBubbleMono(
    pretty: String,
    collapsedMaxHeight: Dp = 92.dp,
    snack: SnackbarHostState? = null
) {
    var expanded by remember(pretty) { mutableStateOf(false) }

    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clip = RoundedCornerShape(10.dp)

    val headerScore = remember(pretty) { FollowupExtractor.extractScore(pretty) }
    val previewText = remember(pretty) { buildJsonPreview(pretty) }

    Surface(
        color = cs.surfaceVariant.copy(alpha = 0.60f),
        tonalElevation = 0.dp,
        shadowElevation = 3.dp,
        shape = clip,
        modifier = Modifier
            .widthIn(max = 580.dp)
            .animateContentSize()
            .neutralEdge(alpha = 0.16f, corner = 10.dp, stroke = 1.dp)
    ) {
        Column {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF1F1F1F).copy(alpha = 0.22f),
                                Color(0xFF3A3A3A).copy(alpha = 0.22f),
                                Color(0xFF6A6A6A).copy(alpha = 0.22f)
                            )
                        )
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val scoreText = headerScore?.let { "$it / 100" } ?: "—"
                Text(
                    text = if (expanded) {
                        "Result JSON  •  Score $scoreText  (tap to collapse)"
                    } else {
                        "Score $scoreText  •  tap to expand"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = {
                        scope.launch {
                            val clipData = ClipData.newPlainText("Result JSON", pretty)
                            clipboard.setClipEntry(ClipEntry(clipData))
                            snack?.showSnackbar("JSON copied")
                        }
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy",
                        tint = cs.onSurfaceVariant
                    )
                }
            }

            if (expanded) {
                SelectionContainer {
                    Text(
                        text = pretty,
                        color = cs.onSurface,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp
                        ),
                        modifier = Modifier
                            .padding(10.dp)
                            .horizontalScroll(rememberScrollState())
                    )
                }
            } else {
                Text(
                    text = previewText,
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { expanded = true }
                        .heightIn(max = collapsedMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/* ───────────────────────────── Composer ─────────────────────────────────── */

@Composable
private fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    focusRequester: FocusRequester,
    speechEnabled: Boolean = false,
    speechRecording: Boolean = false,
    speechTranscribing: Boolean = false,
    speechStatusText: String? = null,
    speechStatusIsError: Boolean = false,
    onToggleSpeech: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 8.dp, shape = CircleShape, clip = false)
                .background(
                    brush = Brush.linearGradient(
                        listOf(
                            cs.surfaceVariant.copy(alpha = 0.65f),
                            cs.surface.copy(alpha = 0.65f)
                        )
                    ),
                    shape = CircleShape
                )
                .neutralEdge(alpha = 0.14f, corner = 999.dp, stroke = 1.dp)
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = { Text("Type your answer…") },
                minLines = 1,
                maxLines = 5,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                textStyle = MaterialTheme.typography.bodyMedium
            )

            if (speechEnabled && onToggleSpeech != null) {
                val tint = cs.onSurfaceVariant
                val micEnabled = (enabled || speechRecording) && !speechTranscribing

                IconButton(
                    onClick = onToggleSpeech,
                    enabled = micEnabled
                ) {
                    Crossfade(
                        targetState = speechRecording,
                        label = "mic-toggle-composer"
                    ) { rec ->
                        if (rec) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = "Stop recording",
                                tint = tint
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "Start recording",
                                tint = tint
                            )
                        }
                    }
                }
            }

            FilledTonalButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank(),
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "Send"
                )
            }
        }

        if (speechStatusText != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
            Text(
                text = speechStatusText,
                style = MaterialTheme.typography.labelSmall,
                color = if (speechStatusIsError) cs.error else cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}

/* ─────────────────────────── Visual utilities ───────────────────────────── */

@Composable
private fun animatedMonotoneBackplate(): Brush {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "bg-mono")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bgp"
    )

    val c0 = lerp(Color(0xFF0F0F10), cs.surface, 0.10f)
    val c1 = lerp(Color(0xFF1A1A1B), cs.surface, 0.12f)
    val c2 = lerp(Color(0xFF2A2A2B), cs.surface, 0.14f)
    val c3 = lerp(Color(0xFF3A3A3B), cs.surface, 0.16f)

    val endX = 1200f + 240f * p
    val endY = 820f - 180f * p

    return Brush.linearGradient(
        colors = listOf(c0, c1, c2, c3),
        start = Offset(0f, 0f),
        end = Offset(endX, endY)
    )
}

private fun Modifier.neutralEdge(
    alpha: Float = 0.16f,
    corner: Dp = 12.dp,
    stroke: Dp = 1.dp
): Modifier = this.then(
    Modifier.drawBehind {
        val cr = CornerRadius(corner.toPx(), corner.toPx())
        val sweep = Brush.sweepGradient(
            0f to Color(0xFF101010).copy(alpha = alpha),
            0.25f to Color(0xFF3A3A3A).copy(alpha = alpha),
            0.5f to Color(0xFF7A7A7A).copy(alpha = alpha * 0.9f),
            0.75f to Color(0xFF3A3A3A).copy(alpha = alpha),
            1f to Color(0xFF101010).copy(alpha = alpha)
        )
        drawRoundRect(
            brush = sweep,
            style = Stroke(width = stroke.toPx()),
            cornerRadius = cr
        )
    }
)

private fun zeroInsetsSafe(): WindowInsets {
    return WindowInsets(0, 0, 0, 0)
}

/* ───────────────────────────── JSON helpers ─────────────────────────────── */

private fun prettyOrRaw(json: Json, raw: String): String {
    val stripped = stripCodeFence(raw)
    val element = parseJsonLenient(json, stripped)
    return if (element != null) {
        json.encodeToString(JsonElement.serializer(), element)
    } else {
        raw
    }
}

private fun buildJsonPreview(pretty: String): String {
    val json = Json { ignoreUnknownKeys = true }
    val stripped = stripCodeFence(pretty)
    val element = parseJsonLenient(json, stripped)

    val obj = element as? JsonObject
    if (obj != null) {
        fun pick(vararg keys: String): String? {
            for (k in keys) {
                val v = obj[k]?.toString()?.trim('"')
                if (!v.isNullOrBlank()) return v
            }
            return null
        }

        val analysis = pick("analysis")
        val expected = pick("expected answer", "expected_answer", "expectedAnswer")
        val fu = pick(
            "follow-up question",
            "follow_up_question",
            "followup_question",
            "followupQuestion",
            "followups"
        )

        val lines = buildList {
            if (!analysis.isNullOrBlank()) add("analysis: $analysis")
            if (!expected.isNullOrBlank()) add("expected: $expected")
            if (!fu.isNullOrBlank()) add("follow-up: $fu")
        }

        if (lines.isNotEmpty()) return lines.joinToString("\n")
    }

    val t = stripped.trim()
    return if (t.length <= 180) t else t.take(180).trimEnd() + "…"
}

/**
 * Extract follow-up candidates from raw text that may include JSON.
 * Supports:
 *  - followup_question (string)
 *  - followups (array of strings)
 *  - follow_up_question(s) variants
 */
private fun extractFollowupCandidates(json: Json, rawText: String): List<String> {
    val stripped = stripCodeFence(rawText).trim()
    if (stripped.isEmpty()) return emptyList()

    val element = parseJsonLenient(json, stripped) ?: return emptyList()
    val out = ArrayList<String>(4)

    fun walk(e: JsonElement) {
        when (e) {
            is JsonObject -> {
                out.addAll(extractFollowupFromObject(e))
                for ((_, v) in e) walk(v)
            }
            is JsonArray -> {
                for (v in e) walk(v)
            }
            else -> Unit
        }
    }

    walk(element)

    // De-dup (preserve order).
    val seen = HashSet<String>()
    val dedup = ArrayList<String>(out.size)
    out.forEach { s ->
        val t = s.trim()
        if (t.isBlank()) return@forEach
        val k = normalizeFollowupKey(t)
        if (seen.add(k)) dedup.add(t)
    }
    return dedup
}

private fun extractFollowupFromObject(obj: JsonObject): List<String> {
    fun normKey(k: String): String =
        k.lowercase().replace("_", " ").replace("-", " ").trim()

    val singleKeySet = setOf(
        "follow up question",
        "followup question",
        "followup",
        "follow up"
    )

    val arrayKeySet = setOf(
        "followups",
        "follow up questions",
        "followup questions",
        "follow up list"
    )

    val out = ArrayList<String>(3)

    // Prefer explicit single question keys.
    for ((k, v) in obj) {
        val nk = normKey(k)
        if (nk in singleKeySet) {
            val s = (v as? JsonPrimitive)?.contentOrNull()?.trim()
            if (!s.isNullOrBlank()) out.add(s)
        }
    }

    // Then arrays.
    for ((k, v) in obj) {
        val nk = normKey(k)
        if (nk in arrayKeySet) {
            val arr = v as? JsonArray ?: continue
            for (item in arr) {
                val s = (item as? JsonPrimitive)?.contentOrNull()?.trim()
                if (!s.isNullOrBlank()) out.add(s)
            }
        }
    }

    return out
}

private fun extractPlainFollowup(rawText: String): String? {
    val stripped = stripCodeFence(rawText).trim()
    if (stripped.isEmpty()) return null
    if (stripped.startsWith("{") || stripped.startsWith("[")) return null

    val line = stripped
        .trim()
        .trim('"')
        .lines()
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()

    if (line.length !in 4..240) return null

    val hasQmark = line.contains('?') || line.contains('？')
    val startsInterrogative =
        line.startsWith("is ", ignoreCase = true) ||
                line.startsWith("are ", ignoreCase = true) ||
                line.startsWith("do ", ignoreCase = true) ||
                line.startsWith("does ", ignoreCase = true) ||
                line.startsWith("did ", ignoreCase = true) ||
                line.startsWith("what ", ignoreCase = true) ||
                line.startsWith("why ", ignoreCase = true) ||
                line.startsWith("how ", ignoreCase = true) ||
                line.startsWith("when ", ignoreCase = true) ||
                line.startsWith("where ", ignoreCase = true)

    if (!hasQmark && !startsInterrogative) return null
    return line
}

/**
 * Normalize follow-up text to a stable dedup key.
 *
 * Goals:
 * - Collapse whitespace and casing differences.
 * - Remove leading numbering/prefix noise ("1) ", "- ", "• ").
 * - Remove surrounding quotes/brackets that models sometimes add.
 * - Trim trailing punctuation ("?", "？", ".", "。", ":") to prevent near-duplicates.
 */
private fun normalizeFollowupKey(s: String): String {
    var t = s.trim()

    // Strip code-fence residue / quotes.
    t = t.trim().trim('"', '\'', '“', '”', '‘', '’', '「', '」', '『', '』', '(', ')', '[', ']', '{', '}')

    // Strip common bullet/number prefixes.
    t = t.replace(Regex("^\\s*(?:[-•*]|\\d+[\\).]|\\(\\d+\\)|#\\d+)\\s+"), "")

    // Collapse whitespace.
    t = t.replace(Regex("\\s+"), " ").trim()

    // Strip trailing punctuation that commonly varies.
    t = t.replace(Regex("[\\s\\u00A0]*[\\?？!！\\.。:：;；,，]+$"), "").trim()

    return t.lowercase()
}

private fun JsonPrimitive.contentOrNull(): String? {
    return runCatching { this.content }.getOrNull()
}

private fun parseJsonLenient(json: Json, text: String): JsonElement? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null

    parseOrNull(json, trimmed)?.let { return it }

    var i = 0
    while (i < trimmed.length) {
        when (trimmed[i]) {
            '{', '[' -> {
                val end = findMatchingJsonBoundary(trimmed, i)
                if (end != -1) {
                    val candidate = trimmed.substring(i, end + 1)
                    parseOrNull(json, candidate)?.let { return it }
                    i = end
                }
            }
        }
        i++
    }
    return null
}

private fun parseOrNull(json: Json, value: String): JsonElement? =
    runCatching { json.parseToJsonElement(value) }.getOrNull()

private fun stripCodeFence(text: String): String {
    val t = text.trim()
    if (!t.startsWith("```")) return t
    val last = t.lastIndexOf("```")
    if (last <= 3) return t
    val firstNewline = t.indexOf('\n', startIndex = 3)
    val contentStart = if (firstNewline == -1) 3 else firstNewline + 1
    return t.substring(contentStart, last).trim()
}

@SuppressLint("SameParameterValue")
private fun findMatchingJsonBoundary(text: String, start: Int): Int {
    if (start !in text.indices) return -1
    val open = text[start]
    if (open != '{' && open != '[') return -1

    val stack = ArrayDeque<Char>()
    stack.addLast(open)

    var i = start + 1
    var inString = false
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            if (c == '\\' && i + 1 < text.length) {
                i += 2
                continue
            }
            if (c == '"') inString = false
        } else {
            when (c) {
                '"' -> inString = true
                '{', '[' -> stack.addLast(c)
                '}' -> if (stack.isEmpty() || stack.removeLast() != '{') return -1
                ']' -> if (stack.isEmpty() || stack.removeLast() != '[') return -1
            }
        }
        if (stack.isEmpty()) return i
        i++
    }
    return -1
}
