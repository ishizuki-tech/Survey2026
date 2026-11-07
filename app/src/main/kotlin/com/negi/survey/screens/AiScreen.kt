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
 *     - TWO_STEP: persist/display follow-up ONLY from Step2 (phase=FOLLOWUP).
 *     - ONE_STEP: persist/display follow-up from Step1 (phase=ONE_STEP).
 *     - If the step bubble already displays the follow-up as plain text,
 *       do NOT add a second "fuq" bubble.
 *
 *  Fix (2026-02-22):
 *  ---------------------------------------------------------------------
 *   • Reduce sensitive payload leakage in logs:
 *     - Info logs use len + short hash (no previews).
 *     - Previews are debug-only (BuildConfig.DEBUG) and remain capped.
 *
 *  Fix (2026-08-23):
 *  ---------------------------------------------------------------------
 *   • Allow identical follow-up text across independent inference runs:
 *     - Follow-up bubbles are deduplicated by stable step identity (runId),
 *       not by the normalized question text.
 *     - Recomposition remains idempotent because upsertChatItem() uses the
 *       deterministic "fuq-<nodeId>-<runId>" message ID.
 *     - A later user answer may therefore receive the same valid clarification
 *       without being incorrectly suppressed by a conversation-wide text set.
 *   • Add release-safe UI diagnostics for follow-up persistence:
 *     - Metadata-only Logcat entries remain visible in release builds.
 *     - User/model text is never emitted; diagnostics contain only step identity,
 *       structural state, lengths, and a short one-way hash for correlation.
 *   • Harden StepSnapshot consumption against StateFlow conflation:
 *     - UI progress is tracked by monotonic StepSnapshot.runId rather than list size.
 *     - A clear-and-append cycle cannot hide a new step when both lists have the
 *       same size before Compose observes the intermediate empty state.
 *     - The logic also remains correct if the bounded history later drops old
 *       snapshots while retaining the same list size.
 *   • Decode JSON preview strings through JsonPrimitive.content instead of
 *     trimming quotes from serialized JSON text, preserving escaped characters.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate")

package com.negi.survey.screens

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
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.negi.survey.BuildConfig
import com.negi.survey.net.RuntimeLogStore
import com.negi.survey.diagnostics.DiagnosticContentPolicy
import com.negi.survey.diagnostics.WhisperTraceRegistry
import com.negi.survey.slm.FollowupExtractor
import com.negi.survey.slm.PromptPhase
import com.negi.survey.ui.theme.neutralEdge
import com.negi.survey.ui.theme.outdoorGradientStops
import com.negi.survey.vm.AiViewModel
import com.negi.survey.vm.QuestionSpeaker
import com.negi.survey.vm.SurveyViewModel
import com.negi.survey.vm.SurveyAiReason
import com.negi.survey.vm.pendingAiFollowup
import com.negi.survey.vm.toggleQuestionSpeaker
import java.security.MessageDigest
import java.util.Locale
import kotlin.collections.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val TAG = "AiScreen"

internal fun followupCapacityRemaining(entries: List<SurveyViewModel.FollowupEntry>, maxFollowups: Int = 2): Int =
    (maxFollowups - entries.size).coerceAtLeast(0)

internal fun normalizeFollowupQuestion(question: String): String =
    question.trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
        .trimEnd { it == '?' || it == '？' }
        .trim()

internal fun isDistinctFollowupQuestion(
    candidate: String,
    existing: List<SurveyViewModel.FollowupEntry>
): Boolean {
    val normalizedCandidate = normalizeFollowupQuestion(candidate)
    return normalizedCandidate.isNotEmpty() && existing.none {
        normalizeFollowupQuestion(it.question) == normalizedCandidate
    }
}

internal fun canAcceptFollowupCandidate(
    candidate: String,
    existing: List<SurveyViewModel.FollowupEntry>,
    maxFollowups: Int = 2
): Boolean =
    followupCapacityRemaining(existing, maxFollowups) > 0 && isDistinctFollowupQuestion(candidate, existing)

/** Formats elapsed recording time as m:ss (e.g. 0:07, 1:23, 12:05). */
internal fun formatRecordingElapsed(totalSeconds: Int): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    return "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}

/** Runs one capture interval; cancellation stops updates when capture ends. */
internal suspend fun runRecordingTimer(
    recording: Boolean,
    captureActive: Boolean,
    elapsedRealtime: () -> Long,
    publish: (Int) -> Unit,
) {
    publish(0)
    if (!recording || !captureActive) return
    val startedAt = elapsedRealtime()
    while (true) {
        val elapsedMs = elapsedRealtime() - startedAt
        publish((elapsedMs / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        delay(1000L - elapsedMs % 1000L)
    }
}

internal fun canAdvanceAiTurn(
    aiLoading: Boolean,
    mainSubmissionPending: Boolean,
    speechRecording: Boolean,
    speechTranscribing: Boolean,
): Boolean =
    !aiLoading &&
            !mainSubmissionPending &&
            !speechRecording &&
            !speechTranscribing

/**
 * Simple abstraction for a speech-to-text controller (e.g., Whisper.cpp).
 */
interface SpeechController {

    /** True while microphone capture is running. */
    val isRecording: StateFlow<Boolean>

    /** True after the recorder reports that the microphone is capturing. */
    val isCaptureActive: StateFlow<Boolean> get() = isRecording

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

/**
 * Toggles microphone capture while keeping question read-aloud out of the
 * recording path. A recording may not start until the current TTS request has
 * been stopped and any pending utterance cleared.
 */
internal fun toggleSpeechRecordingWithTtsInterlock(
    speechController: SpeechController,
    ttsController: QuestionSpeaker?
) {
    if (speechController.isRecording.value) {
        speechController.stopRecording()
    } else if (!speechController.isTranscribing.value) {
        ttsController?.stop()
        speechController.startRecording()
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
    speechController: SpeechController? = null,
    ttsController: QuestionSpeaker? = null,
    playbackLifecycle: Lifecycle = LocalLifecycleOwner.current.lifecycle
) {
    val nid = remember(nodeId) { nodeId.trim() }
    val localContext = LocalContext.current.applicationContext

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }

    // ---------------------------------------------------------------------
    // Survey state
    // ---------------------------------------------------------------------

    val questions by vmSurvey.questions.collectAsState()
    val rootQuestion = questions[nid].orEmpty()

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
    val captureFlow = remember(speechController) { speechController?.isCaptureActive ?: flowOf(false) }
    val captureActive by captureFlow.collectAsState(initial = false)
    val transFlow = remember(speechController) { speechController?.isTranscribing ?: flowOf(false) }
    val partialFlow = remember(speechController) { speechController?.partialText ?: flowOf("") }
    val errFlow = remember(speechController) { speechController?.errorMessage ?: flowOf<String?>(null) }

    val speechRecording by recFlow.collectAsState(initial = false)
    val speechTranscribing by transFlow.collectAsState(initial = false)
    val speechPartial by partialFlow.collectAsState(initial = "")
    val speechError by errFlow.collectAsState(initial = null)

    var recordingElapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(speechRecording, captureActive) {
        runRecordingTimer(speechRecording, captureActive, SystemClock::elapsedRealtime) {
            recordingElapsedSeconds = it
        }
    }

    // ---------------------------------------------------------------------
    // Text-to-speech state with null-safe fallback (question read-aloud)
    // ---------------------------------------------------------------------

    val ttsSpeakingFlow = remember(ttsController) { ttsController?.isSpeaking ?: flowOf(false) }
    val ttsSpeaking by ttsSpeakingFlow.collectAsState(initial = false)

    val textFieldEnabled = !speechRecording && !speechTranscribing

    val speechStatusText: String? = when {
        speechError != null -> speechError
        speechController != null && speechTranscribing -> "Transcribing…"
        speechController != null && speechRecording && captureActive -> "Listening…"
        speechController != null && speechRecording -> "Starting microphone…"
        else -> null
    }
    val speechStatusIsError = speechError != null

    // ---------------------------------------------------------------------
    // AI state
    // ---------------------------------------------------------------------

    val loading by vmAI.loading.collectAsState()
    val error by vmAI.error.collectAsState()
    val aiReasons by vmSurvey.aiReasons.collectAsState()
    val aiReason = aiReasons[nid]
    var submissionPending by remember(contextKey) { mutableStateOf(false) }

    // ---------------------------------------------------------------------
    // Persistent chat + conversation state (B plan)
    // ---------------------------------------------------------------------

    val conversation = remember(vmAI, contextKey) { vmAI.conversationStateFlow(contextKey) }
    val conv by conversation.collectAsState()
    val presentation by vmSurvey.questionPresentation.collectAsState()
    val savedFollowups by vmSurvey.followups.collectAsState()
    val followups = savedFollowups[nid].orEmpty()
    val pendingQuestion = pendingAiFollowup(followups)?.question
    var submittedInPresentation by key(contextKey, presentation) {
        rememberSaveable { mutableStateOf(false) }
    }
    val processing = loading || submissionPending
    val followOutput = processing ||
        (submittedInPresentation && (conv.turnCompleted || conv.validationFailed))

    LaunchedEffect(contextKey, rootQuestion, presentation) {
        if (!vmSurvey.ownsAiPresentation(presentation, surveySessionId, nid)) return@LaunchedEffect
        val reason = vmSurvey.aiReasons.value[nid]
        val pending = pendingAiFollowup(vmSurvey.followups.value[nid].orEmpty())
        vmAI.ensureConversationContext(
            contextKey = contextKey,
            rootQuestion = rootQuestion,
            initialDraft = if (pending == null && !vmAI.isRunning &&
                !(isTwoStepNode && reason != null)
            ) vmSurvey.getAnswer(nid) else ""
        )
        vmAI.ensureRootQuestionMessage(contextKey, nid, rootQuestion)
        vmAI.ensureActivePromptIfMain(contextKey, rootQuestion)
        when {
            isTwoStepNode && reason?.terminal == true ->
                vmAI.completeValidationTurn(contextKey, rootQuestion)
            isTwoStepNode && reason == SurveyAiReason.FAILURE && !vmAI.isRunning ->
                vmAI.failValidationTurn(contextKey)
        }
    }

    LaunchedEffect(contextKey, presentation, pendingQuestion) {
        if (pendingQuestion == null ||
            !vmSurvey.ownsAiPresentation(presentation, surveySessionId, nid) ||
            pendingAiFollowup(vmSurvey.followups.value[nid].orEmpty())?.question != pendingQuestion ||
            conversation.value.turnCompleted
        ) return@LaunchedEffect
        val reason = vmSurvey.aiReasons.value[nid]
        if (isTwoStepNode && (reason?.terminal == true ||
                (reason == SurveyAiReason.FAILURE && !vmAI.isRunning))
        ) return@LaunchedEffect
        vmAI.restoreFollowupMode(contextKey, pendingQuestion)
    }

    fun ownsPresentation() = vmSurvey.ownsAiPresentation(presentation, surveySessionId, nid)

    val prettyJson = remember {
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            ignoreUnknownKeys = true
        }
    }

    var typingMode by remember(contextKey) { mutableStateOf(false) }
    val showTextComposer = speechController == null || typingMode

    /**
     * StepSnapshot persistence is consumed directly from AiViewModel.stepHistory
     * in a lifecycle-scoped LaunchedEffect below. Keeping this stream out of
     * Compose render state avoids recomposition solely for persistence side effects.
     */

    LaunchedEffect(error) {
        val msg = error ?: return@LaunchedEffect
        snack.showSnackbar(msg)
        if (!isTwoStepNode) vmAI.removeTypingMessage(contextKey, nid)
    }

    // ---------------------------------------------------------------------
    // Step snapshots -> persisted chat
    // ---------------------------------------------------------------------

    LaunchedEffect(vmAI, contextKey, isTwoStepNode) {
        // Two-step commits are owned by the serialized chain, never by replayed global snapshots.
        if (isTwoStepNode) return@LaunchedEffect
        /**
         * Use a local monotonic watermark inside this lifecycle-scoped collector.
         * AiViewModel allocates a unique increasing runId for every completed step,
         * including separate run IDs for Step1 and Step2 of a TWO_STEP chain.
         *
         * This is deliberately identity-based instead of size-based:
         * - StateFlow may conflate a fast clear -> append transition, so the UI may
         *   observe size 1 -> size 1 even though the snapshot identity changed.
         * - AiViewModel bounds step history and may eventually drop an old item while
         *   appending a new one, which also leaves list size unchanged.
         * - A collector can miss intermediate StateFlow values while busy, but the
         *   next history value still contains every retained runId; filtering by the
         *   watermark therefore catches all unseen snapshots in order.
         *
         * The watermark is intentionally local to this effect. It resets only when
         * the ViewModel, conversation context, or node mode changes. Stable chat item
         * IDs make any lifecycle replay idempotent at the persistence layer.
         */
        var lastRenderedStepRunId = 0L
        var pendingRepairInitialRunId: Long? = null

        vmAI.stepHistory.collect { history ->
            val newSteps =
                history.filter { step ->
                    step.runId > lastRenderedStepRunId
                }

            if (newSteps.isEmpty()) return@collect

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

                val stepItem = AiViewModel.ChatItem(
                    id = stepMsgId,
                    sender = AiViewModel.ChatSender.AI,
                    json = if (showAsJson) prettyOrRaw(prettyJson, stepRaw) else null,
                    text = stepPlainText
                )
                vmAI.upsertChatItem(contextKey, stepItem)

                /**
                 * IMPORTANT:
                 * Follow-up question persist/display policy:
                 * - TWO_STEP nodes: ONLY Step2 (phase=FOLLOWUP) may persist/display follow-up question.
                 * - ONE_STEP nodes: Step1 (phase=ONE_STEP), or its owned repair FOLLOWUP step.
                 */
                val existingFollowups = vmSurvey.followups.value[nid].orEmpty()
                val capacityRemaining = followupCapacityRemaining(existingFollowups, vmSurvey.maxFollowups)
                val repairEligibleInitial =
                    !isTwoStepNode && AiViewModel.needsFollowupRepair(
                        phase = step.phase,
                        score = step.score,
                        followups = step.followups,
                        timedOut = step.timedOut,
                        error = step.error,
                        capacityRemaining = capacityRemaining
                    )
                if (repairEligibleInitial) {
                    pendingRepairInitialRunId = step.runId
                    RuntimeLogStore.i(
                        TAG,
                        "AI_FOLLOWUP_REPAIR node=$nid initialRun=${step.runId} score=${step.score} attempted=true"
                    )
                }

                val isRepairFollowup =
                    !isTwoStepNode &&
                            step.phase == PromptPhase.FOLLOWUP &&
                            pendingRepairInitialRunId?.let { step.runId > it } == true

                val shouldPersistFollowup =
                    if (isTwoStepNode) {
                        step.phase == PromptPhase.FOLLOWUP
                    } else {
                        step.phase == PromptPhase.ONE_STEP || isRepairFollowup
                    }

                if (shouldPersistFollowup) {
                    val fu = resolveFollowupFromStep(prettyJson, step)
                        ?: (if (!showAsJson) extractPlainFollowup(stepRaw) else null)

                    val fuNorm = fu?.trim()?.takeIf { it.isNotBlank() }
                    val candidateAccepted =
                        fuNorm != null && canAcceptFollowupCandidate(fuNorm, existingFollowups, vmSurvey.maxFollowups)

                    if (candidateAccepted) {
                        val displayedAsPlain =
                            (!showAsJson) && (stepPlainText?.trim() == fuNorm)

                        if (vmSurvey.addFollowupQuestion(nid, fuNorm)) {
                            val questionItem = if (displayedAsPlain) {
                                stepItem.copy(isFollowupQuestion = true)
                            } else {
                                AiViewModel.ChatItem(
                                    id = "fuq-$nid-${step.runId}",
                                    sender = AiViewModel.ChatSender.AI,
                                    text = fuNorm,
                                    isFollowupQuestion = true
                                )
                            }
                            vmAI.upsertChatItem(contextKey, questionItem)
                            vmAI.setFollowupMode(contextKey, fuNorm)

                            // Info log: do not include raw text preview (use len + hash).
                            RuntimeLogStore.i(
                                TAG,
                                "Follow-up persisted (context=$contextKey node=$nid runId=${step.runId} " +
                                        "phase=${step.phase} twoStep=$isTwoStepNode mode=${step.mode} len=${fuNorm.length} " +
                                        "sha6=${shortHash(fuNorm)} displayedAsPlain=$displayedAsPlain)"
                            )

                            /**
                             * Release-safe Logcat diagnostic.
                             *
                             * RuntimeLogStore intentionally disables its Logcat mirror in release
                             * builds. This direct entry keeps the follow-up UI commit observable
                             * during release validation without exposing the follow-up text or any
                             * other user/model payload. The short hash is correlation metadata only.
                             */
                            Log.i(
                                TAG,
                                "Follow-up UI commit: node=$nid runId=${step.runId} " +
                                        "phase=${step.phase} mode=${step.mode} len=${fuNorm.length} " +
                                        "sha6=${shortHash(fuNorm)} displayedAsPlain=$displayedAsPlain"
                            )

                            // Debug-only preview for local iteration.
                            if (BuildConfig.DEBUG) {
                                RuntimeLogStore.d(
                                    TAG,
                                    "Follow-up preview (debug) node=$nid runId=${step.runId} " +
                                            "preview=${clipForLog(fuNorm, 120)}"
                                )
                            }

                        }
                    } else if (!repairEligibleInitial) {
                        if (fuNorm != null) {
                            RuntimeLogStore.i(
                                TAG,
                                "FOLLOWUP_CANDIDATE node=$nid run=${step.runId} duplicate=" +
                                        "${!isDistinctFollowupQuestion(fuNorm, existingFollowups)} " +
                                        "capacityRemaining=$capacityRemaining accepted=false"
                            )
                        }
                        vmAI.completeValidationTurn(contextKey, rootQuestion)
                    }

                    if (isRepairFollowup) {
                        RuntimeLogStore.i(
                            TAG,
                            "AI_FOLLOWUP_REPAIR_RESULT node=$nid run=${step.runId} " +
                                    "timedOut=${step.timedOut} errorPresent=${step.error != null} parsedCount=${step.followups.size}"
                        )
                        pendingRepairInitialRunId = null
                    }
                }

                RuntimeLogStore.d(
                    TAG,
                    "Step rendered (context=$contextKey node=$nid runId=${step.runId} phase=${step.phase} " +
                            "mode=${step.mode} showJson=$showAsJson rawLen=${stepRaw.length} followups=${step.followups.size} " +
                            "timedOut=${step.timedOut} twoStep=$isTwoStepNode)"
                )

                /**
                 * Release-safe step diagnostic.
                 *
                 * This intentionally logs structural metadata only. It confirms that a
                 * StepSnapshot reached the Compose persistence layer even when the
                 * RuntimeLogStore Logcat mirror is disabled for a release build.
                 */
                Log.i(
                    TAG,
                    "Step UI rendered: node=$nid runId=${step.runId} phase=${step.phase} " +
                            "mode=${step.mode} showJson=$showAsJson followups=${step.followups.size} " +
                            "timedOut=${step.timedOut} twoStep=$isTwoStepNode"
                )
            }

            /**
             * Advance the watermark only after every currently visible new snapshot
             * has been committed. The max() form documents the monotonic invariant and
             * remains robust if a future producer ever supplies a non-contiguous list.
             */
            lastRenderedStepRunId =
                maxOf(
                    lastRenderedStepRunId,
                    newSteps.maxOf { it.runId }
                )
        }
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
            val traceId = vmSurvey.answerTraceId(nid)
            WhisperTraceRegistry.start(localContext, surveyUuid)?.event("RECORDING_STARTED", traceId, nid, mapOf("inputSource" to "voice"))
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
                    vmSurvey.acceptVoiceTranscript(nid, text)
                    WhisperTraceRegistry.start(localContext, surveyUuid)?.event(
                        "TRANSCRIPT_ACCEPTED",
                        vmSurvey.answerTraceId(nid),
                        nid,
                        mapOf(
                            "transcript" to DiagnosticContentPolicy.rawOrNull(text),
                            "transcriptLength" to text.length,
                            "inputSource" to "voice",
                        ),
                    )
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
    // Submit logic (ALWAYS TWO-STEP if configured)
    // ---------------------------------------------------------------------

    fun startOneStepValidation(prompt: String, capacityRemaining: Int) =
        vmAI.evaluateConditionalTwoStepAsync(
            firstPrompt = prompt,
            firstPhase = PromptPhase.ONE_STEP,
            proceedOnTimeout = false,
            shouldRunSecond = { step1 ->
                AiViewModel.needsFollowupRepair(
                    phase = PromptPhase.ONE_STEP,
                    score = step1.score,
                    followups = step1.followups,
                    timedOut = step1.timedOut,
                    error = step1.error,
                    capacityRemaining = capacityRemaining
                )
            },
            buildSecondPrompt = { prompt }
        )

    fun inputBlocked(): Boolean =
        submissionPending || vmAI.loading.value || vmAI.isRunning ||
            speechController?.isRecording?.value == true ||
            speechController?.isTranscribing?.value == true

    fun acceptAnswer(input: String, role: AiViewModel.ComposerRole): Boolean {
        if (input.isBlank()) return false
        if (role == AiViewModel.ComposerRole.FOLLOWUP) {
            val pending = pendingAiFollowup(vmSurvey.followups.value[nid].orEmpty())
            if (pending?.question != conversation.value.activePromptQuestion) return false
            vmSurvey.answerLastFollowup(nid, input)
        } else {
            if (isTwoStepNode && !vmSurvey.consumeAcceptedVoiceTransaction(nid)) {
                vmSurvey.beginAnswerTransaction(nid, inputSource = "typed")
            }
            vmSurvey.setAnswer(input, nid)
        }
        submissionPending = true
        submittedInPresentation = true
        vmAI.appendUserMessage(contextKey, input)
        vmAI.updateComposerDraft(contextKey, "")
        return true
    }

    fun runTwoStep(retry: Boolean = false) {
        if (inputBlocked()) return
        val current = conversation.value
        if (retry) {
            if (!current.validationFailed) return
            submissionPending = true
            submittedInPresentation = true
        } else if (current.turnCompleted || current.validationFailed ||
            !acceptAnswer(current.composerDraft.trim(), current.role)
        ) return
        scope.launch {
            try {
                vmAI.evaluateSurveyTwoStepAsync(vmSurvey, nid, contextKey, rootQuestion).join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vmSurvey.setAiReason(nid, com.negi.survey.vm.SurveyAiReason.FAILURE)
                vmAI.failValidationTurn(contextKey)
                snack.showSnackbar("Validation failed. Retry using the saved answers.")
            } finally {
                submissionPending = false
            }
        }
    }

    fun submit() {
        if (inputBlocked()) return
        if (isTwoStepNode) {
            runTwoStep()
            return
        }
        val current = conversation.value
        val input = current.composerDraft.trim()
        if (!acceptAnswer(input, current.role)) return
        vmAI.beginValidationTurn(contextKey)

        if (current.role == AiViewModel.ComposerRole.FOLLOWUP) {
            scope.launch {
                val runId = "ai-followup-${nid}-${SystemClock.uptimeMillis()}-${System.nanoTime()}"
                val t0 = SystemClock.uptimeMillis()
                try {
                    val entries = vmSurvey.followups.value[nid].orEmpty()
                    val capacityRemaining = followupCapacityRemaining(entries, vmSurvey.maxFollowups)
                    val originalMainAnswer = vmSurvey.getAnswer(nid)
                    val accumulatedPrompt = vmSurvey.getAccumulatedPrompt(
                        nodeId = nid,
                        question = rootQuestion,
                        mainAnswer = originalMainAnswer
                    )
                    RuntimeLogStore.i(
                        TAG,
                        "FOLLOWUP_REVALIDATE node=$nid answeredCount=${entries.count { it.answer != null }} " +
                                "capacityRemaining=$capacityRemaining"
                    )
                    startOneStepValidation(accumulatedPrompt, capacityRemaining).join()
                } catch (err: Throwable) {
                    if (err is CancellationException) throw err
                    RuntimeLogStore.e(
                        TAG,
                        "Follow-up revalidation failed runId=$runId node=$nid " +
                                "errType=${err::class.java.simpleName} errMsg=${err.message}",
                        err
                    )
                    vmAI.completeValidationTurn(contextKey, rootQuestion)
                    snack.showSnackbar("Validation failed: ${err.message ?: "unknown error"}")
                    vmAI.removeTypingMessage(contextKey, nid)
                } finally {
                    submissionPending = false
                    RuntimeLogStore.i(
                        TAG,
                        "FOLLOWUP_REVALIDATE_END node=$nid runId=$runId " +
                                "elapsed=${SystemClock.uptimeMillis() - t0}ms"
                    )
                }
            }
            return
        }

        val questionForTurn = rootQuestion
        val answerForTurn = input

        scope.launch {
            val runId = "ai-${nid}-${SystemClock.uptimeMillis()}-${System.nanoTime()}"
            val t0 = SystemClock.uptimeMillis()

            try {
                val isTwoStep = vmSurvey.hasTwoStepPrompt(nid)

                // Info log: lengths + short hashes only (no previews).
                RuntimeLogStore.i(
                    TAG,
                    "Submit begin runId=$runId node=$nid role=${conv.role} isTwoStep=$isTwoStep " +
                            "sessionId=${runSafe { vmSurvey.sessionId.value }} surveyUuid=${runSafe { vmSurvey.surveyUuid.value }} " +
                            "qLen=${questionForTurn.length} aLen=${answerForTurn.length} " +
                            "qSha6=${shortHash(questionForTurn)} aSha6=${shortHash(answerForTurn)}"
                )

                // Debug-only: capped previews for local iteration.
                if (BuildConfig.DEBUG) {
                    RuntimeLogStore.d(
                        TAG,
                        "Submit payload preview (debug) runId=$runId node=$nid " +
                                "qPreview=${clipForLog(questionForTurn, 96)} aPreview=${clipForLog(answerForTurn, 96)}"
                    )
                }

                val originalPrompt = vmSurvey.getPrompt(nid, questionForTurn, answerForTurn)
                val inferenceJob = startOneStepValidation(
                    prompt = originalPrompt,
                    capacityRemaining = vmSurvey.remainingFollowups(nid)
                )
                inferenceJob.join()
            } catch (err: Throwable) {
                if (err is CancellationException) throw err
                RuntimeLogStore.e(
                    TAG,
                    "Submit failed runId=$runId node=$nid role=${conv.role} errType=${err::class.java.simpleName} errMsg=${err.message}",
                    err
                )
                snack.showSnackbar("Submit failed: ${err.message ?: "unknown error"}")
                vmAI.removeTypingMessage(contextKey, nid)
                vmAI.completeValidationTurn(contextKey, rootQuestion)
            } finally {
                submissionPending = false
                RuntimeLogStore.i(TAG, "Submit end runId=$runId node=$nid elapsed=${SystemClock.uptimeMillis() - t0}ms")
            }
        }
    }

    // ---------------------------------------------------------------------
    // Visuals
    // ---------------------------------------------------------------------

    val bgBrush = animatedOutdoorBackplate()
    val title =
        if (conv.role == AiViewModel.ComposerRole.FOLLOWUP) "Follow-up • $nid" else "Question • $nid"

    Scaffold(
        topBar = {
            CompactTopBar(
                title = title,
                onReplay = {
                    if (ttsController != null && ownsPresentation() &&
                        playbackLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    ) {
                        if (ttsController.isSpeaking.value ||
                            (speechController?.isRecording?.value != true &&
                                speechController?.isTranscribing?.value != true)
                        ) {
                            val completed = conversation.value.turnCompleted ||
                                vmSurvey.aiReasons.value[nid]?.terminal == true
                            val pending = pendingAiFollowup(vmSurvey.followups.value[nid].orEmpty())
                            val prompt = pending?.question?.takeUnless { completed } ?: vmSurvey.getQuestion(nid)
                            toggleQuestionSpeaker(ttsController, prompt, "$contextKey|visit=$presentation|manual")
                        }
                    }
                },
                isSpeaking = ttsSpeaking
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        contentWindowInsets = zeroInsetsSafe()
    ) { pad ->
        BoxWithConstraints(
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Reserve space for the question as well as the scrollable composer when the
            // keyboard leaves a short viewport. Include the gap between the two regions.
            val conversationGap = 10.dp
            val footerMaxHeight = if (showTextComposer) {
                (maxHeight - minOf(96.dp, maxHeight / 2) - conversationGap).coerceAtLeast(0.dp)
            } else {
                maxHeight - minOf(64.dp, maxHeight / 4)
            }
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(conversationGap)
            ) {
                key(surveyUuid, contextKey) {
                    if (!isTwoStepNode) PublishStreamingChatRow(vmAI, contextKey, nid)
                    val history = remember(vmAI, contextKey) { vmAI.chatHistoryFlow(contextKey) }
                    val chatItems by history.collectAsState()
                    // Persistence can deliver the new pending question before the chain swaps
                    // its preparation row. Defer the fallback while temporary output is present.
                    val lastRow = chatItems.lastOrNull()
                    val hasTemporaryOutput = lastRow?.isTyping == true ||
                        lastRow?.followupNotice == AiViewModel.FollowupNotice.PREPARING
                    val visibleFollowups =
                        if (isTwoStepNode && hasTemporaryOutput &&
                            pendingQuestion != null && followups.lastOrNull()?.answer == null
                        ) followups.dropLast(1) else followups
                    AiConversation(
                        nodeId = nid,
                        presentation = presentation,
                        chatItems = chatItems,
                        followups = visibleFollowups,
                        loading = loading,
                        followOutput = followOutput,
                        fieldEvaluation = isTwoStepNode,
                        snack = snack,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = footerMaxHeight)
                        .navigationBarsPadding()
                        .neutralEdge(alpha = 0.14f, corner = 16.dp, stroke = 1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 6.dp)
                    ) {
                        if (isTwoStepNode) {
                            if (BuildConfig.DEBUG) {
                                // The running chain records FAILURE as its recovery fallback.
                                // Only present that status once the active attempt has stopped.
                                aiReason?.takeUnless { processing && it == SurveyAiReason.FAILURE }?.let {
                                    Text(
                                        "Status: ${it.wireValue.replace('_', ' ')}",
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                    )
                                }
                            }
                            if (conv.validationFailed) {
                                OutlinedButton(
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                                    enabled = !loading && !submissionPending && !speechRecording && !speechTranscribing,
                                    onClick = { runTwoStep(retry = true) }
                                ) { Text("Retry saved answers") }
                            }
                        }

                        val composerEnabled = textFieldEnabled && !processing &&
                            !(isTwoStepNode && (conv.validationFailed || conv.turnCompleted))
                        val onToggleSpeech: (() -> Unit)? = speechController?.let { sc ->
                            {
                                if (!sc.isRecording.value) {
                                    keyboard?.hide()
                                    focusManager.clearFocus(force = true)
                                    val traceId = vmSurvey.beginAnswerTransaction(nid, "voice")
                                    WhisperTraceRegistry.beginVoiceTransaction(localContext, surveyUuid, nid, traceId)
                                }
                                toggleSpeechRecordingWithTtsInterlock(sc, ttsController)
                            }
                        }
                        val onSendAnswer: () -> Unit = {
                            if (composerEnabled) submit()
                        }
                        val displayedDraft = if (processing) "" else conv.composerDraft
                        if (showTextComposer) {
                            ChatComposer(
                                value = displayedDraft,
                                onValueChange = { t ->
                                    val current = conversation.value
                                    if (composerEnabled && !inputBlocked() &&
                                        !(isTwoStepNode && (current.validationFailed || current.turnCompleted))
                                    ) {
                                        vmAI.updateComposerDraft(contextKey, t)
                                        if (current.role == AiViewModel.ComposerRole.MAIN) vmSurvey.setAnswer(t, nid)
                                    }
                                },
                                onSend = onSendAnswer,
                                enabled = composerEnabled,
                                textFieldEnabled = textFieldEnabled,
                                speechEnabled = speechController != null,
                                speechRecording = speechRecording,
                                speechTranscribing = speechTranscribing,
                                speechStatusText = speechStatusText,
                                speechStatusIsError = speechStatusIsError,
                                onToggleSpeech = onToggleSpeech,
                                onVoiceInstead = if (speechController != null) {{
                                    keyboard?.hide()
                                    focusManager.clearFocus(force = true)
                                    typingMode = false
                                }} else null
                            )
                        } else if (onToggleSpeech != null) {
                            VoiceFirstComposer(
                                value = displayedDraft,
                                enabled = composerEnabled,
                                speechRecording = speechRecording,
                                speechTranscribing = speechTranscribing,
                                speechStatusText = speechStatusText,
                                speechStatusIsError = speechStatusIsError,
                                onToggleSpeech = onToggleSpeech,
                                onSend = onSendAnswer,
                                onTypeInstead = { typingMode = true }
                            )
                        }

                        HorizontalDivider(
                            thickness = DividerDefaults.Thickness,
                            color = DividerDefaults.color
                        )

                        SurveyNavigationButtons(
                            primaryLabel = "Next",
                            primaryEnabled = canAdvanceAiTurn(
                                aiLoading = loading,
                                mainSubmissionPending = submissionPending,
                                speechRecording = speechRecording,
                                speechTranscribing = speechTranscribing,
                            ),
                            onPrimary = {
                                if (canAdvanceAiTurn(
                                        aiLoading = loading,
                                        mainSubmissionPending = submissionPending,
                                        speechRecording = speechRecording,
                                        speechTranscribing = speechTranscribing,
                                    )
                                ) {
                                    keyboard?.hide()
                                    focusManager.clearFocus(force = true)
                                    vmAI.resetStates()
                                    onNext()
                                }
                            },
                            secondaryLabel = "Back",
                            onSecondary = {
                                keyboard?.hide()
                                focusManager.clearFocus(force = true)
                                vmAI.resetStates()
                                onBack()
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }

    if (speechController != null && speechRecording) {
        var stopRequested by remember(speechController) { mutableStateOf(false) }
        RecordingTimerOverlay(
            question = followups.lastOrNull()?.question ?: rootQuestion,
            elapsedSeconds = recordingElapsedSeconds,
            statusText = speechStatusText,
            stopRequested = stopRequested
        ) {
            if (!stopRequested && speechController.isRecording.value) {
                stopRequested = true
                speechController.stopRecording()
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
            if (!isTwoStepNode) vmAI.removeTypingMessage(contextKey, nid)
        }
    }
}

/* ─────────────────────── Recording timer ───────────────────────────────── */

/**
 * Full-screen timer shown while a dictated answer is being recorded. It counts up so the
 * interviewer can see at a glance how long the farmer has been talking; there is no limit.
 * Stop (or the system back gesture) ends the recording.
 */
@Composable
private fun RecordingTimerOverlay(
    question: String,
    elapsedSeconds: Int,
    statusText: String?,
    stopRequested: Boolean,
    onStop: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val elapsed = formatRecordingElapsed(elapsedSeconds)

    Dialog(
        onDismissRequest = onStop,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = cs.surface.copy(alpha = 0.94f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (question.isNotBlank()) {
                    Text(
                        text = question,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState(), reverseScrolling = true)
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Text(
                    text = elapsed,
                    style = MaterialTheme.typography.displayMedium,
                    color = cs.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.semantics {
                        contentDescription = "Recording for $elapsed"
                    }
                )
                if (!statusText.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = onStop,
                    enabled = !stopRequested,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cs.error,
                        contentColor = cs.onError
                    ),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Stop", style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
}

/* ───────────────────────────── Top bar ─────────────────────────────────── */

@Composable
private fun CompactTopBar(
    title: String,
    onReplay: (() -> Unit)? = null,
    isSpeaking: Boolean = false
) {
    val cs = MaterialTheme.colorScheme
    val topBrush = Brush.horizontalGradient(
        listOf(
            cs.surface.copy(alpha = 0.96f),
            cs.surfaceContainerHigh.copy(alpha = 0.75f)
        )
    )
    Surface(color = Color.Transparent, tonalElevation = 0.dp) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(topBrush)
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (onReplay != null) {
                IconButton(onClick = onReplay) {
                    Text(
                        text = if (isSpeaking) "\u23F9" else "\uD83D\uDD0A",
                        color = cs.onSurface,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun PublishStreamingChatRow(vmAI: AiViewModel, contextKey: String, nodeId: String) {
    // Token updates invalidate this scope instead of the composer and its input session.
    val stream by vmAI.stream.collectAsState()
    val loading by vmAI.loading.collectAsState()
    LaunchedEffect(vmAI, contextKey, nodeId, loading, stream) {
        if (loading) {
            vmAI.upsertTypingMessage(contextKey, nodeId, stream.ifBlank { "…" })
        } else {
            vmAI.removeTypingMessage(contextKey, nodeId)
        }
    }
}

@Composable
internal fun AiConversation(
    nodeId: String,
    presentation: Long,
    chatItems: List<AiViewModel.ChatItem>,
    followups: List<SurveyViewModel.FollowupEntry>,
    loading: Boolean,
    snack: SnackbarHostState,
    modifier: Modifier,
    followOutput: Boolean = loading,
    fieldEvaluation: Boolean = false
) {
    val rootId = "qroot-$nodeId"
    val latestPublishedId = chatItems.lastOrNull { it.isFollowupQuestion }?.id
    val (newestFirst, latestRestoredId) = remember(chatItems, followups, rootId) {
        val rows = chatItems.toMutableList()
        var previousQuestionIndex = rows.indexOfFirst { it.id == rootId }
        var restoredId: String? = null
        // Retained survey state can supply questions whose in-memory chat rows are missing.
        followups.forEachIndexed { index, entry ->
            val publishedIndex = rows.indexOfLast { it.isFollowupQuestion && it.text == entry.question }
            restoredId = if (publishedIndex >= 0) {
                previousQuestionIndex = publishedIndex
                null
            } else {
                val id = "restored-followup-$nodeId-$index-${entry.askedAt}"
                rows.add(++previousQuestionIndex, AiViewModel.ChatItem(id, AiViewModel.ChatSender.AI, text = entry.question))
                id
            }
        }
        rows.asReversed() to restoredId
    }

    // Use the same order and keys as the LazyColumn. Live processing has its own
    // scrolling behavior. The opening target is the newest committed component.
    val entryRowId = newestFirst.firstOrNull {
        !it.isTyping && it.evaluation != AiViewModel.EvaluationPresentation.REVIEWING &&
            it.followupNotice != AiViewModel.FollowupNotice.PREPARING
    }?.id

    // Navigation creates a presentation. Activity recreation can restore its viewport.
    val scroll = key(presentation) {
        rememberLazyListState(
            initialFirstVisibleItemIndex = newestFirst.indexOfFirst { it.id == entryRowId }.coerceAtLeast(0)
        )
    }
    var positionedRowId by key(presentation) { rememberSaveable { mutableStateOf<String?>(null) } }
    val following = key(presentation) { rememberSaveable { mutableStateOf(true) } }
    val readingGesture = remember(following) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                    following.value = false
                }
                return Offset.Zero
            }
        }
    }
    // Fresh visits start with the existing history. Recreation keeps the last handled
    // publication so a follow-up accepted while the viewport was detached can still be shown.
    val handledPublication = key(presentation) { rememberSaveable { mutableStateOf(latestPublishedId) } }
    var seenPublication by handledPublication
    var settledRestoration by remember(scroll) { mutableStateOf(latestRestoredId) }
    SideEffect {
        // Acceptance and chat publication can arrive in separate snapshots during inference.
        if (!loading || latestRestoredId == null) settledRestoration = latestRestoredId
    }

    val newPublicationId = latestPublishedId?.takeIf { it != seenPublication }
    val openingRowId = entryRowId.takeIf { positionedRowId == null }
    val targetId = newPublicationId ?: openingRowId
    val activeQuestionId = settledRestoration ?: latestPublishedId ?: rootId
    val outputId = newestFirst.firstOrNull()?.id?.takeIf { followOutput && it != activeQuestionId }
    val currentOutputId by rememberUpdatedState(outputId)
    val currentRows by rememberUpdatedState(newestFirst)
    val fieldOutputId = outputId.takeIf { fieldEvaluation }
    val followStreamingOutput = !fieldEvaluation && outputId != null
    LaunchedEffect(scroll, targetId, newPublicationId, following.value, fieldOutputId, followStreamingOutput) {
        if (targetId != null) {
            val layout = snapshotFlow { scroll.layoutInfo }.first {
                it.viewportSize.height > 0 && it.totalItemsCount == currentRows.size &&
                    currentRows.any { row -> row.id == targetId }
            }
            if (newPublicationId != null || layout.revealDelta(targetId, false) != 0) {
                scroll.scroll(MutatePriority.PreventUserInput) {
                    val index = currentRows.indexOfFirst { it.id == targetId }
                    if (index >= 0) LazyLayoutScrollScope(scroll, this).snapToItem(index, 0)
                }
            }
            positionedRowId = targetId
            if (newPublicationId != null) {
                seenPublication = newPublicationId
                following.value = true
            }
        } else if (!followStreamingOutput) {
            if (!following.value) return@LaunchedEffect
            // Reverse layout otherwise preserves the old anchor when new output is
            // inserted below it. Keep the live edge only for readers who stayed there.
            if (fieldOutputId != null) {
                snapshotFlow {
                    scroll.layoutInfo.viewportSize.height > 0 &&
                        scroll.layoutInfo.totalItemsCount == currentRows.size
                }.first { it }
                val index = currentRows.indexOfFirst { it.id == fieldOutputId }
                if (index >= 0 && scroll.layoutInfo.revealDelta(fieldOutputId, false) != 0) {
                    scroll.scrollToItem(index)
                }
            }
            // Keep the positioned component readable as the IME reduces the viewport.
            // Accordion remeasurement does not change the viewport height.
            var height = scroll.layoutInfo.viewportSize.height
            snapshotFlow { scroll.layoutInfo.viewportSize.height }.collect { newHeight ->
                val shrank = newHeight in 1 until height
                height = newHeight
                val id = positionedRowId
                if (shrank && following.value && currentOutputId == null && id != null) {
                    val index = currentRows.indexOfFirst { it.id == id }
                    if (index >= 0 && scroll.layoutInfo.revealDelta(id, false) != 0) {
                        scroll.scrollToItem(index)
                    }
                }
            }
        } else {
            fun correction(): Int {
                if (!following.value || scroll.isScrollInProgress) return 0
                val id = currentOutputId ?: return 0
                val rows = currentRows
                if (scroll.layoutInfo.totalItemsCount != rows.size) return 0
                val index = rows.indexOfFirst { it.id == id }
                return scroll.followCorrection(id, index)
            }

            while (following.value) {
                snapshotFlow { correction() }.first { it != 0 }
                withFrameNanos { }
                val delta = correction()
                if (delta == 0) continue
                // Isolate one default-priority mutation so a user gesture can interrupt it.
                // The reader's gesture, rather than cancellation alone, disables following.
                coroutineScope {
                    launch {
                        scroll.scroll {
                            if (following.value) scrollBy(delta.toFloat())
                        }
                    }
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier.nestedScroll(readingGesture).testTag("AI conversation"),
        state = scroll,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top)
    ) {
        items(newestFirst, key = { it.id }) { item ->
            val isAi = item.sender == AiViewModel.ChatSender.AI
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().testTag(item.id),
                horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End
            ) {
                when {
                    item.evaluation == AiViewModel.EvaluationPresentation.REVIEWING ||
                        item.followupNotice == AiViewModel.FollowupNotice.PREPARING -> {
                        val visible by remember(scroll, item.id) {
                            derivedStateOf { scroll.layoutInfo.visibleItemsInfo.any { it.key == item.id } }
                        }
                        FieldProcessingRow(
                            reviewing = item.evaluation == AiViewModel.EvaluationPresentation.REVIEWING,
                            visible = visible
                        )
                    }
                    item.evaluation != null -> FieldEvaluationDetails(
                        reviewed = item.evaluation == AiViewModel.EvaluationPresentation.REVIEWED,
                        raw = item.json.orEmpty(),
                        snack = snack
                    )
                    item.followupNotice == AiViewModel.FollowupNotice.UNAVAILABLE ->
                        FieldFollowupUnavailable()
                    item.json != null -> JsonBubbleMono(pretty = item.json, snack = snack)
                    else -> FieldMessage(text = item.text.orEmpty(), isAi = isAi, typing = item.isTyping)
                }
            }
        }
    }
}

private fun LazyListState.followCorrection(id: String, index: Int): Int {
    val layout = layoutInfo
    val viewport = layout.viewportEndOffset - layout.viewportStartOffset
    val visible = layout.visibleItemsInfo
    if (index < 0 || viewport <= 0 || visible.isEmpty()) return 0

    // Take at most a fraction of the current viewport per frame. When a newly inserted
    // target is still unmeasured, approach its index until its actual edges are available.
    val step = (viewport / 12).coerceAtLeast(1)
    val delta = layout.revealDelta(id, followNewestEnd = true) ?: when {
        index < visible.first().index -> -step
        index > visible.last().index -> step
        else -> 0 // The row identity has changed and layout has not caught up yet.
    }
    return when {
        delta < -1 && canScrollBackward -> delta.coerceAtLeast(-step)
        delta > 1 && canScrollForward -> delta.coerceAtMost(step)
        else -> 0
    }
}

private fun LazyListLayoutInfo.revealDelta(id: String, followNewestEnd: Boolean): Int? {
    val row = visibleItemsInfo.firstOrNull { it.key == id } ?: return null
    // Reverse layout measures from the visual bottom, where the newest output lines
    // appear and where an oversized question meets the composer.
    val bottomDelta = row.offset - viewportStartOffset
    if (row.size > viewportEndOffset - viewportStartOffset || bottomDelta < 0) return bottomDelta
    if (followNewestEnd) return 0
    // A fitting question should be fully readable. Output follows only its growing end.
    return (row.offset + row.size - viewportEndOffset).coerceAtLeast(0)
}

/* ───────────────────────────── JSON bubble ──────────────────────────────── */

@Composable
private fun JsonBubbleMono(
    pretty: String,
    collapsedMaxHeight: Dp = 92.dp,
    snack: SnackbarHostState? = null
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

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
                                cs.surfaceContainerHigh.copy(alpha = 0.22f),
                                cs.outlineVariant.copy(alpha = 0.22f),
                                cs.outline.copy(alpha = 0.22f)
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
                            val clipData = ClipData.newPlainText("JSON", pretty)
                            clipboard.setClipEntry(ClipEntry(clipData))
                            snack?.showSnackbar("JSON copied")
                        }
                    },
                    modifier = Modifier.size(48.dp)
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
                            fontFamily = FontFamily.Monospace
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
    textFieldEnabled: Boolean,
    speechEnabled: Boolean = false,
    speechRecording: Boolean = false,
    speechTranscribing: Boolean = false,
    speechStatusText: String? = null,
    speechStatusIsError: Boolean = false,
    onToggleSpeech: (() -> Unit)? = null,
    onVoiceInstead: (() -> Unit)? = null
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var entryFocusHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        // Retain the one-time entry decision across restoration of this composer.
        if (!enabled || entryFocusHandled) return@LaunchedEffect
        entryFocusHandled = true
        focusRequester.requestFocus()
        keyboard?.show()
    }
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
                    .focusRequester(focusRequester)
                    .semantics {
                        if (!enabled) stateDescription = "Answer input inactive"
                    },
                placeholder = { Text(if (enabled) "Type your answer…" else "Answer input inactive") },
                minLines = 1,
                maxLines = 5,
                // Keep the input session alive while Send and draft changes are gated.
                enabled = textFieldEnabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = OutlinedTextFieldDefaults.colors(
                    cursorColor = if (enabled) cs.onSurface else Color.Transparent,
                    focusedPlaceholderColor = if (enabled) cs.onSurfaceVariant else cs.onSurface.copy(alpha = 0.38f),
                    unfocusedPlaceholderColor = if (enabled) cs.onSurfaceVariant else cs.onSurface.copy(alpha = 0.38f),
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

            SurveyActionButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "Send"
                )
            }
        }

        if (onVoiceInstead != null) {
            TextButton(
                onClick = onVoiceInstead,
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurface)
            ) { Text("Use voice instead") }
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

/**
 * Voice-first answer area: a large mic button while there is no answer yet,
 * then the transcript with Send and Record again. Typing is a secondary
 * action ("Type instead"); tapping the transcript also opens it for editing.
 */
@Composable
internal fun VoiceFirstComposer(
    value: String,
    enabled: Boolean,
    speechRecording: Boolean,
    speechTranscribing: Boolean,
    speechStatusText: String?,
    speechStatusIsError: Boolean,
    onToggleSpeech: () -> Unit,
    onSend: () -> Unit,
    onTypeInstead: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val micEnabled = (enabled || speechRecording) && !speechTranscribing
    val hasAnswer = value.isNotBlank() && !speechRecording && !speechTranscribing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (hasAnswer) {
            Surface(
                onClick = onTypeInstead,
                enabled = enabled,
                shape = RoundedCornerShape(16.dp),
                color = cs.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
            if (speechStatusIsError && !speechStatusText.isNullOrBlank()) {
                Text(speechStatusText, color = cs.error, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SurveyActionButton(
                    onClick = onToggleSpeech,
                    enabled = micEnabled,
                    primary = false
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Record again", modifier = Modifier.weight(1f, fill = false))
                }
                SurveyActionButton(onClick = onSend, enabled = enabled) {
                    Text("Send", modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null)
                }
            }
        } else {
            Surface(
                onClick = onToggleSpeech,
                enabled = micEnabled,
                shape = CircleShape,
                color = when {
                    !micEnabled -> cs.onSurface.copy(alpha = 0.12f)
                    speechRecording -> cs.error
                    else -> cs.primary
                },
                contentColor = when {
                    !micEnabled -> cs.onSurface.copy(alpha = 0.38f)
                    speechRecording -> cs.onError
                    else -> cs.onPrimary
                },
                modifier = Modifier.size(96.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (speechRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (speechRecording) "Stop recording" else "Record answer",
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = speechStatusText ?: "Tap to answer",
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    speechStatusIsError -> cs.error
                    !micEnabled && speechStatusText == null -> cs.onSurface.copy(alpha = 0.38f)
                    else -> cs.onSurface
                }
            )
        }

        if (!speechRecording && !speechTranscribing) {
            TextButton(
                onClick = onTypeInstead,
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurface)
            ) {
                Icon(Icons.Filled.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (hasAnswer) "Edit by typing" else "Type instead")
            }
        }
    }
}

/* ─────────────────────────── Visual utilities ───────────────────────────── */

@Composable
private fun animatedOutdoorBackplate(): Brush {
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

    val endX = 1200f + 240f * p
    val endY = 820f - 180f * p

    return Brush.linearGradient(
        colors = outdoorGradientStops(),
        start = Offset(0f, 0f),
        end = Offset(endX, endY)
    )
}

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
                /**
                 * Read the primitive's decoded content rather than calling
                 * JsonElement.toString(), which returns serialized JSON and keeps
                 * escape sequences such as \" or \n in their encoded form.
                 */
                val value =
                    (obj[k] as? JsonPrimitive)
                        ?.contentOrNull()
                        ?.trim()

                if (!value.isNullOrBlank()) return value
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
