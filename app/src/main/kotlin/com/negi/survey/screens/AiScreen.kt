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
 *  Update (2026-01-29):
 *  ---------------------------------------------------------------------
 *   • Adapted to AiViewModel stepHistory/StepSnapshot as the source of truth.
 *   • Removed dependency on vmAI.chatFlow / ChatMsgVm / chatAppend APIs.
 *   • Local chat list now receives user bubbles + rendered step snapshots.
 *
 *  Update (2026-01-29 hotfix):
 *  ---------------------------------------------------------------------
 *   • Prevent main answer overwrite while answering follow-ups.
 *   • Follow-up question becomes the next "active prompt question" (UI main).
 *   • Two-step step2 trigger uses followup_needed from EVAL JSON (not score threshold).
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate")

package com.negi.survey.screens

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import android.content.ClipData
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.ClipEntry
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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* =============================================================================
 * AI Evaluation Screen — Monotone × Glass × Chat
 * ============================================================================= */

private const val TAG = "AiScreen"
private const val TYPING_UPDATE_THROTTLE_MS = 90L
private const val AUTO_SCROLL_THROTTLE_MS = 40L

/**
 * UI sender type for local chat state.
 */
private enum class ChatSenderUi {
    USER,
    AI
}

/**
 * Which kind of input the composer is currently collecting.
 *
 * MAIN:
 *  - The first user answer to the root question.
 *  - This may be persisted into SurveyViewModel via setAnswer().
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
 * This avoids depending on AiViewModel chat APIs that may not exist.
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
 *
 * Implementations are expected to:
 *  - Expose recording state, transcription state, and latest recognized text
 *    as [StateFlow]s.
 *  - Provide [startRecording] and [stopRecording] controls.
 *  - Optionally rely on [toggleRecording] as a convenience entry point.
 *  - Accept optional context for export naming/correlation.
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
     * Implementations that export voice files may embed these values into
     * file names or sidecar metadata.
     *
     * Default implementation is a no-op so non-export controllers remain valid.
     *
     * @param surveyId UUID of the active survey run.
     * @param questionId Node ID for the current question.
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

    /**
     * Convenience toggle that switches between start/stop.
     *
     * This method reads [isRecording.value] directly. Implementations should
     * ensure their [StateFlow] values are updated promptly to avoid UI drift.
     */
    fun toggleRecording() {
        if (isRecording.value) stopRecording() else startRecording()
    }
}

/**
 * Full-screen AI evaluation screen bound to a single survey node.
 *
 * IME handling policy:
 * - The Activity should use SOFT_INPUT_ADJUST_NOTHING.
 * - This composable uses [Modifier.imePadding] to avoid double-resize.
 *
 * @param nodeId Graph node identifier for the current AI question.
 * @param vmSurvey Survey-level ViewModel providing questions and answers.
 * @param vmAI AI-specific ViewModel for streaming and step history.
 * @param onNext Callback invoked when the user presses the "Next" button.
 * @param onBack Callback invoked when the user presses the "Back" button.
 * @param speechController Optional speech controller backing the composer mic.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSerializationApi::class)
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
    // Survey state
    // ---------------------------------------------------------------------

    /**
     * Root question text stream for this node.
     *
     * We default the initial state to the ViewModel snapshot to avoid a
     * transient blank state when the Flow is still cold.
     */
    val rootQuestion by remember(vmSurvey, nid) {
        vmSurvey.questions.map { it[nid].orEmpty() }
    }.collectAsState(initial = vmSurvey.getQuestion(nid))

    /** Session id increments when the survey is reset. */
    val sessionId by vmSurvey.sessionId.collectAsState()

    /** Stable UUID for the active survey run. */
    val surveyUuid by vmSurvey.surveyUuid.collectAsState()

    /**
     * Keep speech controller context in sync with the active survey run + node.
     */
    LaunchedEffect(nid, sessionId, surveyUuid, speechController) {
        speechController?.updateContext(
            surveyId = surveyUuid,
            questionId = nid
        )
    }

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

    /**
     * Text field should be disabled while recording or transcribing.
     */
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

    /**
     * Step history is the source of truth for "Step1 + Step2 both remain visible".
     */
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
     * Composer text.
     *
     * IMPORTANT:
     * - While answering FOLLOWUP, we must not call vmSurvey.setAnswer() on every keystroke.
     */
    var composer by remember(nid, sessionId) {
        mutableStateOf(vmSurvey.getAnswer(nid))
    }

    /**
     * Current input role.
     *
     * - Starts in MAIN.
     * - Switches to FOLLOWUP when a follow-up question is persisted.
     */
    var inputRole by remember(nid, sessionId) { mutableStateOf(InputRole.MAIN) }

    /**
     * The "active prompt question" shown to the user conceptually.
     * - Starts as rootQuestion.
     * - Updated to the latest follow-up question when generated.
     */
    var activePromptQuestion by remember(nid, sessionId) { mutableStateOf(rootQuestion) }

    /**
     * Local chat state (nodeId, sessionId scoped).
     *
     * This does not require AiViewModel chat persistence APIs.
     */
    val chat = remember(nid, sessionId) { mutableStateListOf<ChatMsgUi>() }

    val focusRequester = remember { FocusRequester() }
    val scroll = rememberScrollState()

    /**
     * Scroll policy:
     *  - Only auto-scroll while user is pinned to bottom.
     *  - If user scrolls up, show a "Jump to bottom" affordance.
     */
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

    /**
     * Track which steps have already been rendered into chat bubbles.
     */
    var renderedStepCount by remember(nid, sessionId) { mutableIntStateOf(0) }

    /**
     * Track follow-up persistence to avoid duplicates.
     */
    var lastFollowupLocal by remember(nid, sessionId) { mutableStateOf<String?>(null) }

    /**
     * Ensure root question bubble exists and stays current.
     *
     * NOTE:
     * - Root question should remain visible (conversation context).
     * - Follow-up questions are appended as additional AI bubbles,
     *   and also become activePromptQuestion.
     */
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

        // Only update activePromptQuestion if we are still on root.
        if (inputRole == InputRole.MAIN && (activePromptQuestion.isBlank() || activePromptQuestion == curTextOrBlank(chat, qid))) {
            activePromptQuestion = rootQuestion
        }
    }

    /**
     * Sync composer with persisted Survey answers at node/session boundaries.
     */
    LaunchedEffect(nid, sessionId) {
        composer = vmSurvey.getAnswer(nid)
        inputRole = InputRole.MAIN
        activePromptQuestion = rootQuestion
        renderedStepCount = 0
        lastFollowupLocal = null
        // Keep chat as-is for the current node/session.
    }

    /**
     * Request focus and show IME only once per node/session.
     */
    LaunchedEffect(nid, sessionId) {
        delay(30)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    /**
     * Surface AI errors as snackbars.
     */
    LaunchedEffect(error) {
        val msg = error ?: return@LaunchedEffect
        snack.showSnackbar(msg)
        removeTypingBubble(chat, nid)
    }

    // ---------------------------------------------------------------------
    // Typing bubble orchestration (throttled)
    // ---------------------------------------------------------------------

    /**
     * Maintain/update typing bubble during streaming.
     *
     * This update is throttled to avoid updating the chat list on every token.
     */
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

        val txt = stream.ifBlank { "…" }

        upsertTypingBubble(
            chat = chat,
            nid = nid,
            text = txt
        )
    }

    /**
     * Render Step snapshots into local chat.
     *
     * Core behavior:
     * - stepHistory always receives Step1 + Step2 results.
     * - Replace typing bubble with each Step result bubble.
     * - Resolve/persist follow-up questions per step.
     * - When a follow-up question is persisted:
     *    • append it (if not already shown)
     *    • set activePromptQuestion to it
     *    • switch inputRole to FOLLOWUP (prevents main overwrite)
     */
    LaunchedEffect(stepHistory.size) {
        // History got reset (new chain). Reset step cursor only.
        if (stepHistory.size < renderedStepCount) {
            renderedStepCount = 0
            lastFollowupLocal = null
        }

        if (stepHistory.size <= renderedStepCount) return@LaunchedEffect

        val newSteps = stepHistory.subList(renderedStepCount, stepHistory.size)

        newSteps.forEachIndexed { idx, step ->
            val stepRaw = step.raw
            val stripped = stripCodeFence(stepRaw).trim()

            val parsed = parseJsonLenient(prettyJson, stripped)
            val showAsJson = (step.mode == AiViewModel.EvalMode.EVAL_JSON) || (parsed != null)

            val resultMsg = if (showAsJson) {
                val pretty = prettyOrRaw(prettyJson, stepRaw)
                ChatMsgUi(
                    id = "step-${step.runId}-$nid",
                    sender = ChatSenderUi.AI,
                    json = pretty
                )
            } else {
                val text = resolveFollowupFromStep(prettyJson, step)
                    ?: extractPlainFollowup(stepRaw)
                    ?: stripped.ifBlank { "…" }

                ChatMsgUi(
                    id = "step-${step.runId}-$nid",
                    sender = ChatSenderUi.AI,
                    text = text
                )
            }

            // First new step likely replaces the typing bubble; subsequent ones append.
            if (idx == 0) {
                replaceTypingWith(chat, nid, resultMsg)
            } else {
                chat.add(resultMsg)
            }

            // Follow-up resolution & persistence (dedup).
            val fu = resolveFollowupFromStep(prettyJson, step)
                ?: (if (!showAsJson) extractPlainFollowup(stepRaw) else null)

            val fuNorm = fu?.trim()?.takeIf { it.isNotBlank() }
            if (fuNorm != null && fuNorm != lastFollowupLocal) {
                lastFollowupLocal = fuNorm

                // If this step bubble already displayed the same follow-up as plain text, do not append again.
                val displayedAsPlain = (!showAsJson) && (resultMsg.text?.trim() == fuNorm)

                if (!displayedAsPlain) {
                    chat.add(
                        ChatMsgUi(
                            id = "fuq-$nid-${step.runId}-${System.nanoTime()}",
                            sender = ChatSenderUi.AI,
                            text = fuNorm
                        )
                    )
                }

                // Persist follow-up question in Survey VM.
                vmSurvey.addFollowupQuestion(nid, fuNorm)

                // This follow-up becomes the next "active prompt question".
                activePromptQuestion = fuNorm
                inputRole = InputRole.FOLLOWUP

                Log.i(
                    TAG,
                    "Follow-up persisted (node=$nid runId=${step.runId} mode=${step.mode} len=${fuNorm.length} preview=${clipForLog(fuNorm, 120)})"
                )

                // Bring IME back for the next answer (do not overwrite draft).
                scope.launch {
                    delay(40)
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
            }

            Log.d(
                TAG,
                "Step rendered (node=$nid runId=${step.runId} mode=${step.mode} showJson=$showAsJson rawLen=${stepRaw.length} followups=${step.followups.size} timedOut=${step.timedOut})"
            )
        }

        renderedStepCount = stepHistory.size
    }

    /**
     * If streaming stops without step snapshots (cancel/error), ensure typing bubble is removed.
     */
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

    /**
     * Commit recognized speech only when an utterance finishes.
     *
     * IMPORTANT:
     * - While answering FOLLOWUP, do not clear or overwrite the main Survey answer.
     * - We still update the composer UI text.
     */
    LaunchedEffect(speechRecording, speechTranscribing, speechPartial) {
        if (speechController == null) return@LaunchedEffect

        val startedThisUtterance =
            (!wasRecording && !wasTranscribing) && (speechRecording || speechTranscribing)

        if (startedThisUtterance) {
            composer = ""
            lastCommitted = null

            // Only clear persisted main answer when collecting MAIN input.
            if (inputRole == InputRole.MAIN) {
                vmSurvey.clearAnswer(nid)
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

                // Persist draft only for MAIN role (FOLLOWUP must not overwrite main answer).
                if (inputRole == InputRole.MAIN) {
                    vmSurvey.setAnswer(text, nid)
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

    /**
     * Auto-scroll to bottom when chat size changes, but only when pinned.
     */
    LaunchedEffect(chat.size) {
        if (!isPinnedToBottom) return@LaunchedEffect
        val now = SystemClock.uptimeMillis()
        if ((now - lastAutoScrollMs) < AUTO_SCROLL_THROTTLE_MS) return@LaunchedEffect
        lastAutoScrollMs = now

        delay(16)
        scroll.animateScrollTo(scroll.maxValue)
    }

    /**
     * Keep view pinned to the bottom while streaming grows, only when pinned.
     */
    LaunchedEffect(stream, loading) {
        if (!loading || !isPinnedToBottom) return@LaunchedEffect
        val now = SystemClock.uptimeMillis()
        if ((now - lastAutoScrollMs) < AUTO_SCROLL_THROTTLE_MS) return@LaunchedEffect
        lastAutoScrollMs = now

        delay(24)
        scroll.scrollTo(scroll.maxValue)
    }

    // ---------------------------------------------------------------------
    // Submit logic
    // ---------------------------------------------------------------------

    /**
     * Submit current answer and trigger AI evaluation.
     *
     * IMPORTANT:
     * - MAIN answer is persisted via vmSurvey.setAnswer().
     * - FOLLOWUP answers must NOT overwrite main answer.
     *   -> only vmSurvey.answerLastFollowup() is called for FOLLOWUP.
     *
     * Prompt rules:
     * - Step1 (EVAL): always use rootQuestion + mainAnswer (stable)
     * - Step2 (FOLLOWUP): run iff followup_needed==true in EVAL JSON
     */
    fun submit() {
        if (loading) return
        if (speechRecording || speechTranscribing) {
            scope.launch { snack.showSnackbar("Speech is active. Stop recording first.") }
            return
        }

        val input = composer.trim()
        if (input.isBlank()) return

        val existingMain = vmSurvey.getAnswer(nid).trim()
        val isSubmittingMain = (inputRole == InputRole.MAIN)

        // Persist answer depending on role.
        if (isSubmittingMain) {
            vmSurvey.setAnswer(input, nid)
        } else {
            vmSurvey.answerLastFollowup(nid, input)
        }

        // Append user bubble.
        chat.add(
            ChatMsgUi(
                id = "u-$nid-${System.nanoTime()}",
                sender = ChatSenderUi.USER,
                text = input
            )
        )

        // Stable main answer for prompt building (do not use follow-up input as main).
        val mainAnswerStable = if (isSubmittingMain) input else existingMain

        scope.launch {
            val runId = "ai-${nid}-${SystemClock.uptimeMillis()}-${System.nanoTime()}"
            val t0 = SystemClock.uptimeMillis()

            val q = rootQuestion
            val isTwoStep = vmSurvey.hasTwoStepPrompt(nid)

            Log.i(
                TAG,
                "Submit begin runId=$runId node=$nid role=$inputRole isTwoStep=$isTwoStep " +
                        "sessionId=${runSafe { vmSurvey.sessionId.value }} surveyUuid=${runSafe { vmSurvey.surveyUuid.value }} " +
                        "qLen=${q.length} mainAnsLen=${mainAnswerStable.length} inputLen=${input.length} " +
                        "loading=$loading streamLen=${stream.length}"
            )

            runCatching {
                if (!isTwoStep) {
                    Log.d(TAG, "ONE_STEP prompt build start runId=$runId node=$nid")

                    val prompt = vmSurvey.getPrompt(nid, q, mainAnswerStable)
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

                    val prompt1 = vmSurvey.getEvalPrompt(nid, q, mainAnswerStable)
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

                        /**
                         * Run step2 iff followup_needed == true in step1 EVAL JSON.
                         *
                         * This avoids suppressing follow-ups when score is high
                         * but clarification is still required.
                         */
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
                                    answer = mainAnswerStable,
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
                                    append("Generate ONE follow-up question.\n")
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
        /**
         * Keep Scaffold from auto-applying IME/system insets.
         * We handle insets explicitly to avoid double-resize.
         */
        contentWindowInsets = zeroInsetsSafe()
    ) { pad ->
        Box(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .background(bgBrush)
                /**
                 * Critical: IME padding applied exactly once at the root content.
                 * This assumes Activity is using SOFT_INPUT_ADJUST_NOTHING.
                 */
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
                        chat.forEach { m ->
                            val isAi = m.sender == ChatSenderUi.AI
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End
                            ) {
                                if (m.json != null) {
                                    JsonBubbleMono(pretty = m.json, snack = snack)
                                } else {
                                    BubbleMono(
                                        text = m.text.orEmpty(),
                                        isAi = isAi,
                                        isTyping = m.isTyping
                                    )
                                }
                            }
                        }
                    }

                    /**
                     * Jump-to-bottom affordance:
                     * Visible only when user is not pinned and chat has content.
                     */
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isPinnedToBottom && chat.isNotEmpty(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                    ) {
                        JumpToBottomPill(
                            onClick = {
                                scope.launch { scroll.animateScrollTo(scroll.maxValue) }
                            }
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

                                // Persist drafts only for MAIN. FOLLOWUP must not overwrite main answer.
                                if (inputRole == InputRole.MAIN) {
                                    vmSurvey.setAnswer(it, nid)
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
                            ) {
                                Text("Back")
                            }

                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))

                            OutlinedButton(
                                onClick = {
                                    vmAI.resetStates()
                                    onNext()
                                }
                            ) {
                                Text("Next")
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Stop speech capture and clear transient AI state when leaving this screen.
     */
    DisposableEffect(nid, sessionId) {
        onDispose {
            runCatching {
                if (speechController?.isRecording?.value == true) {
                    speechController.stopRecording()
                }
            }
            vmAI.resetStates()
        }
    }
}

/* ───────────────────────────── Chat list helpers ───────────────────────────── */

private fun curTextOrBlank(chat: List<ChatMsgUi>, id: String): String {
    return chat.firstOrNull { it.id == id }?.text.orEmpty()
}

/**
 * Upsert a typing bubble for the current node.
 *
 * @param chat Local chat list.
 * @param nid Node id.
 * @param text Current streaming text (already throttled).
 */
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

/**
 * Remove typing bubble if present.
 *
 * @param chat Local chat list.
 * @param nid Node id.
 */
private fun removeTypingBubble(
    chat: MutableList<ChatMsgUi>,
    nid: String
) {
    val id = "typing-$nid"
    val idx = chat.indexOfFirst { it.id == id }
    if (idx != -1) chat.removeAt(idx)
}

/**
 * Replace typing bubble with a final AI message.
 *
 * If typing bubble is missing, this will append instead.
 *
 * @param chat Local chat list.
 * @param nid Node id.
 * @param msg Final message.
 */
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
 * Resolve a follow-up question candidate from a step snapshot.
 *
 * Priority:
 *  1) step.followups.first()
 *  2) JSON keys (follow_up_question, followup_question, follow-ups arrays)
 *  3) Plain-text heuristic (for step2 text output)
 *
 * @param json Json instance for parsing.
 * @param step Step snapshot.
 */
private fun resolveFollowupFromStep(
    json: Json,
    step: AiViewModel.StepSnapshot
): String? {
    val fromList = step.followups.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
    if (fromList != null) return fromList

    val fromJson = extractFollowupCandidate(json, step.raw)?.trim()?.takeIf { it.isNotBlank() }
    if (fromJson != null) return fromJson

    val fromText = extractPlainFollowup(step.raw)?.trim()?.takeIf { it.isNotBlank() }
    if (fromText != null) return fromText

    return null
}

/**
 * Extract followup_needed from EVAL JSON (step1).
 *
 * @return true/false if found, null if JSON can't be parsed or key missing.
 */
private fun extractFollowupNeeded(json: Json, rawText: String): Boolean? {
    val stripped = stripCodeFence(rawText).trim()
    if (stripped.isEmpty()) return null
    val element = parseJsonLenient(json, stripped) as? JsonObject ?: return null

    fun normKey(k: String): String =
        k.lowercase().replace("_", " ").replace("-", " ").trim()

    for ((k, v) in element) {
        val nk = normKey(k)
        if (nk == "followup needed" || nk == "follow up needed") {
            val prim = v as? JsonPrimitive ?: return null
            return prim.contentOrNull()?.trim()?.lowercase()?.let { it == "true" || it == "1" }
        }
    }
    return null
}

/* ───────────────────────────── Log helpers ─────────────────────────────── */

/**
 * Clip a string for logs to avoid leaking full prompt contents.
 *
 * @param s Input string.
 * @param maxChars Max characters to keep.
 */
private fun clipForLog(s: String, maxChars: Int): String {
    val t = s.replace("\n", "\\n").replace("\r", "\\r").trim()
    if (t.length <= maxChars) return "\"$t\""
    return "\"${t.take(maxChars)}…\""
}

/**
 * Create a short stable hash for correlating prompts without logging the full text.
 *
 * @param s Input string.
 */
private fun shortHash(s: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
    return bytes.take(6).joinToString("") { b -> "%02x".format(b) }
}

/**
 * Run a lambda safely for log-only reads (never throw from logging).
 *
 * @param block Read operation.
 */
private inline fun <T> runSafe(block: () -> T): T? {
    return runCatching { block() }.getOrNull()
}

/* ───────────────────────────── Top bar ─────────────────────────────────── */

/**
 * Minimal top bar for the AI screen.
 *
 * @param title Display title for the current node.
 * @param height Height of the bar content area.
 */
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

/**
 * Minimal "jump to bottom" pill shown when the user scrolls up.
 *
 * @param onClick Callback to scroll to the bottom.
 */
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
    maxWidth: Dp = 520.dp
) {
    val cs = MaterialTheme.colorScheme

    val corner = 12.dp
    val padH = 10.dp
    val padV = 7.dp
    val tailW = 7f
    val tailH = 6f

    val stops = if (isAi) {
        listOf(Color(0xFF111111), Color(0xFF1E1E1E), Color(0xFF2A2A2A))
    } else {
        listOf(Color(0xFFEDEDED), Color(0xFFD9D9D9), Color(0xFFC8C8C8))
    }

    val t = rememberInfiniteTransition(label = "bubble-mono")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "p"
    )
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
    /** Reset expansion whenever the JSON changes. */
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
            // Make only the header row togglable (avoids copy button toggling expansion).
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
                            // Use LocalClipboard (suspend) instead of deprecated LocalClipboardManager.
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
                // Expanded: allow selection; do NOT add clickable here to avoid fighting selection gestures.
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
                // Collapsed: allow tap to expand on the preview area too.
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

                /**
                 * Mic button is disabled while transcribing to avoid overlapping
                 * utterance sessions in controllers that do not support re-entrancy.
                 */
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

@Composable
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
        val fu = pick("follow-up question", "follow_up_question", "followup_question", "followupQuestion")

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

private fun extractFollowupCandidate(json: Json, rawText: String): String? {
    val stripped = stripCodeFence(rawText).trim()
    if (stripped.isEmpty()) return null

    val element = parseJsonLenient(json, stripped) ?: return null
    return findFollowupInElement(element)
}

private fun findFollowupInElement(e: JsonElement): String? {
    return when (e) {
        is JsonObject -> {
            val direct = findFollowupInObject(e)
            if (direct != null) return direct
            for ((_, v) in e) {
                val found = findFollowupInElement(v)
                if (found != null) return found
            }
            null
        }
        is JsonArray -> {
            for (v in e) {
                val found = findFollowupInElement(v)
                if (found != null) return found
            }
            null
        }
        else -> null
    }
}

private fun findFollowupInObject(obj: JsonObject): String? {
    fun normKey(k: String): String =
        k.lowercase().replace("_", " ").replace("-", " ").trim()

    val followupKeySet = setOf(
        "follow up question",
        "followup question",
        "followup",
        "follow up"
    )

    val followupsArrayKeySet = setOf(
        "followups",
        "follow up questions",
        "followup questions",
        "follow up list"
    )

    for ((k, v) in obj) {
        val nk = normKey(k)
        if (nk in followupKeySet) {
            val s = (v as? JsonPrimitive)?.contentOrNull()?.trim()
            if (!s.isNullOrBlank()) return s
        }
    }

    for ((k, v) in obj) {
        val nk = normKey(k)
        if (nk in followupsArrayKeySet) {
            val arr = v as? JsonArray ?: continue
            for (item in arr) {
                val s = (item as? JsonPrimitive)?.contentOrNull()?.trim()
                if (!s.isNullOrBlank()) return s
            }
        }
    }

    return null
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
