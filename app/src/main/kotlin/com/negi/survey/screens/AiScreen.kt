/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: AiScreen.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  AI evaluation screen that renders a monotone, glass-like chat UI on
 *  top of the Survey + SLM pipeline.
 *
 *  Update (2026-02-20):
 *  ---------------------------------------------------------------------
 *   • Migrate chat history to AiViewModel (B plan):
 *     - Chat history persists across navigation/back within the same session VM store.
 *     - Screen renders vmAI.chatHistoryFlow(contextKey).
 *     - Conversation state (role, activePromptQuestion, draft) is persisted in vmAI.
 *
 *  Fix (2026-02-20 hotfix):
 *  ---------------------------------------------------------------------
 *   • Avoid duplicate follow-up bubbles:
 *     - Persist/display follow-up question ONLY from Step2 (phase=FOLLOWUP) for TWO_STEP nodes.
 *     - For ONE_STEP nodes, persist/display follow-up from Step1 (phase=ONE_STEP).
 *     - If a step already displays follow-up as plain text bubble, do NOT add a second "fuq" bubble.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate")

package com.negi.survey.screens

import android.os.SystemClock
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.negi.survey.BuildConfig
import com.negi.survey.net.RuntimeLogStore
import com.negi.survey.slm.FollowupExtractor
import com.negi.survey.slm.PromptPhase
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

private const val TAG = "AiScreen"
private const val TYPING_UPDATE_THROTTLE_MS = 90L
private const val AUTO_SCROLL_THROTTLE_MS = 40L

/** Debug-only crash button visibility. */
private val SHOW_DEBUG_CRASH_BUTTON: Boolean = BuildConfig.DEBUG

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
     * @param surveyId UUID of the active survey run.
     * @param questionId Node ID for the current question.
     */
    fun updateContext(surveyId: String?, questionId: String?) {
        // no-op
    }

    /** Start capturing audio and producing partial or final text. */
    fun startRecording()

    /** Stop capturing audio and finalize the current utterance. */
    fun stopRecording()

    /**
     * Convenience toggle that switches between start/stop.
     */
    fun toggleRecording() {
        if (isRecording.value) stopRecording() else startRecording()
    }
}

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

    val rootQuestion by remember(vmSurvey, nid) {
        vmSurvey.questions.map { it[nid].orEmpty() }
    }.collectAsState(initial = vmSurvey.getQuestion(nid))

    val surveySessionId by vmSurvey.sessionId.collectAsState()
    val surveyUuid by vmSurvey.surveyUuid.collectAsState()

    val contextKey = remember(nid, surveySessionId) { "sid=$surveySessionId|nid=$nid" }

    /**
     * Determine if this node is configured as TWO_STEP.
     *
     * This is used to decide which step is allowed to persist/display follow-up question:
     * - TWO_STEP: only Step2 (phase=FOLLOWUP)
     * - ONE_STEP: Step1 (phase=ONE_STEP)
     */
    val isTwoStepNode = remember(vmSurvey, nid, surveySessionId) {
        vmSurvey.hasTwoStepPrompt(nid)
    }

    LaunchedEffect(nid, surveySessionId, surveyUuid, speechController) {
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

    val stepHistory by vmAI.stepHistory.collectAsState()

    // ---------------------------------------------------------------------
    // Persistent chat + conversation state (B plan)
    // ---------------------------------------------------------------------

    LaunchedEffect(contextKey, rootQuestion) {
        vmAI.ensureConversationContext(
            contextKey = contextKey,
            rootQuestion = rootQuestion,
            initialDraft = vmSurvey.getAnswer(nid)
        )
        vmAI.ensureRootQuestionMessage(contextKey, nid, rootQuestion)
        vmAI.ensureActivePromptIfMain(contextKey, rootQuestion)
    }

    val conv by vmAI.conversationStateFlow(contextKey)
        .collectAsState(
            initial = AiViewModel.ConversationState(
                role = AiViewModel.ComposerRole.MAIN,
                activePromptQuestion = rootQuestion,
                composerDraft = vmSurvey.getAnswer(nid)
            )
        )

    val chatItems by vmAI.chatHistoryFlow(contextKey).collectAsState(initial = emptyList())

    val prettyJson = remember {
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            ignoreUnknownKeys = true
        }
    }

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

    var lastAutoScrollMs by remember(contextKey) { mutableLongStateOf(0L) }
    var lastTypingUpdateMs by remember(contextKey) { mutableLongStateOf(0L) }
    var lastTypingLen by remember(contextKey) { mutableIntStateOf(0) }

    /** Track which steps have already been consumed to avoid reprocessing. */
    var renderedStepCount by remember(contextKey) { mutableIntStateOf(0) }

    LaunchedEffect(contextKey) {
        delay(30)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    LaunchedEffect(error) {
        val msg = error ?: return@LaunchedEffect
        snack.showSnackbar(msg)
        vmAI.removeTypingMessage(contextKey, nid)
    }

    // ---------------------------------------------------------------------
    // Typing bubble orchestration (persisted)
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

        vmAI.upsertTypingMessage(contextKey, nid, stream.ifBlank { "…" })
    }

    LaunchedEffect(loading) {
        if (!loading) {
            vmAI.removeTypingMessage(contextKey, nid)
        }
    }

    // ---------------------------------------------------------------------
    // Step snapshots -> persisted chat
    // ---------------------------------------------------------------------

    LaunchedEffect(stepHistory.size, contextKey) {
        if (stepHistory.size < renderedStepCount) {
            renderedStepCount = 0
        }
        if (stepHistory.size <= renderedStepCount) return@LaunchedEffect

        val newSteps = stepHistory.subList(renderedStepCount, stepHistory.size)

        newSteps.forEach { step ->
            val stepRaw = step.raw
            val stripped = stripCodeFence(stepRaw).trim()

            val parsed = parseJsonLenient(prettyJson, stripped)
            val showAsJson = (step.mode == AiViewModel.EvalMode.EVAL_JSON) || (parsed != null)

            vmAI.removeTypingMessage(contextKey, nid)

            val stepMsgId = "step-${step.runId}-$nid"
            val stepPlainText: String? = if (!showAsJson) {
                resolveFollowupFromStep(prettyJson, step)
                    ?: extractPlainFollowup(stepRaw)
                    ?: stripped.ifBlank { "…" }
            } else {
                null
            }

            if (showAsJson) {
                val pretty = prettyOrRaw(prettyJson, stepRaw)
                vmAI.upsertChatItem(
                    contextKey,
                    AiViewModel.ChatItem(
                        id = stepMsgId,
                        sender = AiViewModel.ChatSender.AI,
                        json = pretty
                    )
                )
            } else {
                vmAI.upsertChatItem(
                    contextKey,
                    AiViewModel.ChatItem(
                        id = stepMsgId,
                        sender = AiViewModel.ChatSender.AI,
                        text = stepPlainText
                    )
                )
            }

            /**
             * IMPORTANT:
             * Follow-up question persist/display policy:
             * - TWO_STEP nodes: ONLY Step2 (phase=FOLLOWUP) may persist/display follow-up question.
             * - ONE_STEP nodes: Step1 (phase=ONE_STEP) may persist/display follow-up question.
             */
            val shouldPersistFollowup =
                if (isTwoStepNode) {
                    step.phase == PromptPhase.FOLLOWUP
                } else {
                    step.phase == PromptPhase.ONE_STEP
                }

            if (shouldPersistFollowup) {
                val fu = resolveFollowupFromStep(prettyJson, step)
                    ?: (if (!showAsJson) extractPlainFollowup(stepRaw) else null)

                val fuNorm = fu?.trim()?.takeIf { it.isNotBlank() }
                if (fuNorm != null) {
                    val isNew = vmAI.markFollowupSeen(contextKey, fuNorm)

                    /**
                     * If the step already displays the follow-up as plain text,
                     * do NOT add a second "fuq" bubble.
                     */
                    val displayedAsPlain =
                        (!showAsJson) && (stepPlainText?.trim() == fuNorm)

                    if (isNew) {
                        if (!displayedAsPlain) {
                            val fuqId = "fuq-$nid-${step.runId}"
                            vmAI.upsertChatItem(
                                contextKey,
                                AiViewModel.ChatItem(
                                    id = fuqId,
                                    sender = AiViewModel.ChatSender.AI,
                                    text = fuNorm
                                )
                            )
                        }

                        vmSurvey.addFollowupQuestion(nid, fuNorm)
                        vmAI.setFollowupMode(contextKey, fuNorm)

                        RuntimeLogStore.i(
                            TAG,
                            "Follow-up persisted (context=$contextKey node=$nid runId=${step.runId} " +
                                    "phase=${step.phase} mode=${step.mode} twoStep=$isTwoStepNode " +
                                    "len=${fuNorm.length} preview=${clipForLog(fuNorm, 120)} displayedAsPlain=$displayedAsPlain)"
                        )

                        scope.launch {
                            delay(40)
                            focusRequester.requestFocus()
                            keyboard?.show()
                        }
                    }
                }
            }

            RuntimeLogStore.d(
                TAG,
                "Step rendered (context=$contextKey node=$nid runId=${step.runId} phase=${step.phase} " +
                        "mode=${step.mode} showJson=$showAsJson twoStep=$isTwoStepNode " +
                        "rawLen=${stepRaw.length} followups=${step.followups.size} timedOut=${step.timedOut})"
            )
        }

        renderedStepCount = stepHistory.size
    }

    // ---------------------------------------------------------------------
    // Speech → Draft commit
    // ---------------------------------------------------------------------

    var wasRecording by remember(contextKey) { mutableStateOf(false) }
    var wasTranscribing by remember(contextKey) { mutableStateOf(false) }
    var lastCommitted by remember(contextKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(speechRecording, speechTranscribing, speechPartial) {
        if (speechController == null) return@LaunchedEffect

        val startedThisUtterance =
            (!wasRecording && !wasTranscribing) && (speechRecording || speechTranscribing)

        if (startedThisUtterance) {
            vmAI.updateComposerDraft(contextKey, "")
            lastCommitted = null

            if (conv.role == AiViewModel.ComposerRole.MAIN) {
                vmSurvey.clearAnswer(nid)
            }

            RuntimeLogStore.d(TAG, "Speech started (node=$nid role=${conv.role})")
        }

        val finishedThisUtterance =
            (wasRecording || wasTranscribing) && !speechRecording && !speechTranscribing

        if (finishedThisUtterance) {
            val text = speechPartial.trim()
            if (text.isNotEmpty() && text != lastCommitted) {
                vmAI.updateComposerDraft(contextKey, text)
                lastCommitted = text

                if (conv.role == AiViewModel.ComposerRole.MAIN) {
                    vmSurvey.setAnswer(text, nid)
                }

                RuntimeLogStore.d(TAG, "Speech committed (node=$nid role=${conv.role} len=${text.length})")
            } else {
                RuntimeLogStore.d(TAG, "Speech finished with empty/no-change (node=$nid role=${conv.role})")
            }
        }

        wasRecording = speechRecording
        wasTranscribing = speechTranscribing
    }

    // ---------------------------------------------------------------------
    // Auto-scroll (pinned-to-bottom only)
    // ---------------------------------------------------------------------

    LaunchedEffect(chatItems.size) {
        if (!isPinnedToBottom) return@LaunchedEffect
        val now = SystemClock.uptimeMillis()
        if ((now - lastAutoScrollMs) < AUTO_SCROLL_THROTTLE_MS) return@LaunchedEffect
        lastAutoScrollMs = now

        delay(16)
        scroll.animateScrollTo(scroll.maxValue)
    }

    LaunchedEffect(stream, loading) {
        if (!loading || !isPinnedToBottom) return@LaunchedEffect
        val now = SystemClock.uptimeMillis()
        if ((now - lastAutoScrollMs) < AUTO_SCROLL_THROTTLE_MS) return@LaunchedEffect
        lastAutoScrollMs = now

        delay(24)
        scroll.scrollTo(scroll.maxValue)
    }

    // ---------------------------------------------------------------------
    // Submit logic (ALWAYS TWO-STEP if configured)
    // ---------------------------------------------------------------------

    fun submit() {
        if (loading) return
        if (speechRecording || speechTranscribing) {
            scope.launch { snack.showSnackbar("Speech is active. Stop recording first.") }
            return
        }

        val input = conv.composerDraft.trim()
        if (input.isBlank()) return

        val isSubmittingMain = (conv.role == AiViewModel.ComposerRole.MAIN)

        if (isSubmittingMain) {
            vmSurvey.setAnswer(input, nid)
        } else {
            vmSurvey.answerLastFollowup(nid, input)
        }

        vmAI.appendUserMessage(contextKey, input)

        val questionForTurn = if (isSubmittingMain) rootQuestion else conv.activePromptQuestion.ifBlank { rootQuestion }
        val answerForTurn = input

        scope.launch {
            val runId = "ai-${nid}-${SystemClock.uptimeMillis()}-${System.nanoTime()}"
            val t0 = SystemClock.uptimeMillis()

            val isTwoStep = vmSurvey.hasTwoStepPrompt(nid)

            RuntimeLogStore.i(
                TAG,
                "Submit begin runId=$runId node=$nid role=${conv.role} isTwoStep=$isTwoStep " +
                        "sessionId=${runSafe { vmSurvey.sessionId.value }} surveyUuid=${runSafe { vmSurvey.surveyUuid.value }} " +
                        "qLen=${questionForTurn.length} aLen=${answerForTurn.length} " +
                        "qPreview=${clipForLog(questionForTurn, 96)} aPreview=${clipForLog(answerForTurn, 96)}"
            )

            runCatching {
                if (!isTwoStep) {
                    val prompt = vmSurvey.getPrompt(nid, questionForTurn, answerForTurn)
                    vmAI.evaluateAsync(prompt)
                    return@runCatching
                }

                val prompt1 = vmSurvey.getEvalPrompt(nid, questionForTurn, answerForTurn)

                vmAI.evaluateConditionalTwoStepAsync(
                    firstPrompt = prompt1,
                    proceedOnTimeout = true,
                    shouldRunSecond = { step1 ->
                        val needed = extractFollowupNeeded(prettyJson, step1.raw) ?: false
                        RuntimeLogStore.i(
                            TAG,
                            "TWO_STEP shouldRunSecond runId=$runId node=$nid role=${conv.role} " +
                                    "followup_needed=$needed timedOut=${step1.timedOut} rawLen=${step1.raw.length}"
                        )
                        needed
                    },
                    buildSecondPrompt = { step1 ->
                        vmSurvey.getFollowupPrompt(
                            nodeId = nid,
                            question = questionForTurn,
                            answer = answerForTurn,
                            evalJsonRaw = step1.raw
                        )
                    }
                )
            }.onFailure { err ->
                RuntimeLogStore.e(
                    TAG,
                    "Submit failed runId=$runId node=$nid role=${conv.role} errType=${err::class.java.simpleName} errMsg=${err.message}",
                    err
                )
                snack.showSnackbar("Submit failed: ${err.message ?: "unknown error"}")
                vmAI.removeTypingMessage(contextKey, nid)
            }.also {
                RuntimeLogStore.i(TAG, "Submit end runId=$runId node=$nid elapsed=${SystemClock.uptimeMillis() - t0}ms")
            }
        }

        vmAI.updateComposerDraft(contextKey, "")
        keyboard?.hide()
        focusManager.clearFocus(force = true)
    }

    // ---------------------------------------------------------------------
    // Visuals
    // ---------------------------------------------------------------------

    val bgBrush = animatedMonotoneBackplate()
    val title = if (conv.role == AiViewModel.ComposerRole.FOLLOWUP) "Follow-up • $nid" else "Question • $nid"

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
                        chatItems.forEach { m ->
                            val isAi = m.sender == AiViewModel.ChatSender.AI
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

                    androidx.compose.animation.AnimatedVisibility(
                        visible = !isPinnedToBottom && chatItems.isNotEmpty(),
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
                        if (SHOW_DEBUG_CRASH_BUTTON) {
                            OutlinedButton(onClick = { throw RuntimeException("Debug crash button pressed") }) {
                                Text("Crash now (debug)")
                            }
                        }

                        ChatComposer(
                            value = conv.composerDraft,
                            onValueChange = { t ->
                                vmAI.updateComposerDraft(contextKey, t)
                                if (conv.role == AiViewModel.ComposerRole.MAIN) {
                                    vmSurvey.setAnswer(t, nid)
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

    DisposableEffect(contextKey) {
        onDispose {
            runCatching {
                if (speechController?.isRecording?.value == true) {
                    speechController.stopRecording()
                }
            }
            vmAI.resetStates()
            vmAI.removeTypingMessage(contextKey, nid)
        }
    }
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
            Text(
                text = if (isTyping && text.isBlank()) "…" else text,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp)
            )
        }
    }
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
    val clipboard = LocalClipboardManager.current
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
                            clipboard.setText(AnnotatedString(pretty))
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

private fun zeroInsetsSafe(): WindowInsets = WindowInsets(0, 0, 0, 0)

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

/* ───────────────────────────── Step helpers ─────────────────────────────── */

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

private inline fun <T> runSafe(block: () -> T): T? = runCatching { block() }.getOrNull()

/* ───────────────────────────── JSON extraction ───────────────────────────── */

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

private fun JsonPrimitive.contentOrNull(): String? = runCatching { this.content }.getOrNull()

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