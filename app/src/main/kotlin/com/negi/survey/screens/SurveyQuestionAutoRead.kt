package com.negi.survey.screens

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.negi.survey.BuildConfig
import com.negi.survey.vm.AiViewModel
import com.negi.survey.vm.NodeType
import com.negi.survey.vm.QuestionPlaybackTarget
import com.negi.survey.vm.QuestionSpeaker
import com.negi.survey.vm.SurveyViewModel
import com.negi.survey.vm.resolveAiAutoReadTarget
import com.negi.survey.vm.shouldAutoPlayQuestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job

/** Only the survey host requests auto-read. Animated or retained entries can only request Replay. */
@Composable
internal fun SurveyQuestionAutoRead(
    survey: SurveyViewModel,
    ai: AiViewModel,
    speaker: QuestionSpeaker?,
    speech: SpeechController?,
    enabled: Boolean,
    lifecycle: Lifecycle
) {
    if (speaker == null) return

    val presentation by survey.questionPresentation.collectAsState()
    val owner = remember(survey, presentation) { survey.questionSpeechOwner() }
    val node by survey.currentNode.collectAsState()
    val isAi = owner.nodeType == NodeType.AI
    val conversation = if (isAi) {
        remember(ai, owner.session, owner.nodeId) {
            ai.conversationStateFlow("sid=${owner.session}|nid=${owner.nodeId}")
        }
    } else null
    val recording = if (isAi) speech?.isRecording?.collectAsState()?.value == true else false
    val transcribing = if (isAi) speech?.isTranscribing?.collectAsState()?.value == true else false

    val target = when (owner.nodeType) {
        NodeType.AI -> {
            val questions by survey.questions.collectAsState()
            val answers by survey.answers.collectAsState()
            val followups by survey.followups.collectAsState()
            val reasons by survey.aiReasons.collectAsState()
            resolveAiAutoReadTarget(
                questions[owner.nodeId].orEmpty(), answers[owner.nodeId].orEmpty(),
                followups[owner.nodeId].orEmpty(), conversation?.collectAsState()?.value?.turnCompleted == true,
                reasons[owner.nodeId]
            )
        }
        NodeType.INFO, NodeType.TEXT, NodeType.SINGLE_CHOICE, NodeType.MULTI_CHOICE, NodeType.NUMBER ->
            node.question.takeIf { it.isNotBlank() }?.let { QuestionPlaybackTarget(it) }
        else -> return
    }

    DisposableEffect(survey, owner, speaker) {
        onDispose { speaker.stop() }
    }

    fun maySpeak() = survey.ownsQuestionSpeech(owner) &&
        survey.currentNode.value.readAloud &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
        shouldAutoPlayQuestion(
            enabled,
            isAi && speech?.isRecording?.value == true,
            isAi && speech?.isTranscribing?.value == true
        )

    fun logResult(accepted: Boolean) {
        if (BuildConfig.DEBUG) {
            Log.d(
                "SurveyAutoRead",
                "source=SurveyNavHost node=${owner.nodeId} type=${owner.nodeType} " +
                    "session=${owner.session} presentation=${owner.presentation} " +
                    "reason=${survey.aiReasons.value[owner.nodeId]} accepted=$accepted"
            )
        }
    }

    LaunchedEffect(survey, owner, target, speaker, speech, enabled, recording, transcribing, lifecycle) {
        if (target == null || !maySpeak()) {
            logResult(false)
            return@LaunchedEffect
        }

        // Cancel an unspoken request even when the app resumes before TTS becomes ready.
        val request = currentCoroutineContext().job
        val stopObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) request.cancel()
        }
        lifecycle.addObserver(stopObserver)
        try {
            speaker.isReady.first { it }
            currentCoroutineContext().ensureActive()
            val currentTarget = if (isAi) {
                resolveAiAutoReadTarget(
                    survey.getQuestion(owner.nodeId), survey.getAnswer(owner.nodeId),
                    survey.followups.value[owner.nodeId].orEmpty(),
                    conversation?.value?.turnCompleted == true, survey.aiReasons.value[owner.nodeId]
                )
            } else {
                QuestionPlaybackTarget(survey.currentNode.value.question)
            }

            // No suspension between the final check, deduplication and the ready speaker.
            val accepted = target == currentTarget && maySpeak() && survey.claimQuestionAutoRead(owner, target)
            logResult(accepted)
            if (accepted) {
                val utteranceId = "sid=${owner.session}|nid=${owner.nodeId}|visit=${owner.presentation}" +
                    "|followup=${target.followup?.askedAt}"
                speaker.speak(target.text, utteranceId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Log.w("SurveyAutoRead", "Question speaker failed")
        } finally {
            lifecycle.removeObserver(stopObserver)
        }
    }
}
