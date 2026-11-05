package com.negi.survey.vm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Stops playback and drops queued speech whenever the survey leaves the foreground. */
@Composable
internal fun StopQuestionSpeakerInBackground(speaker: QuestionSpeaker, lifecycle: Lifecycle) {
    DisposableEffect(speaker, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) runCatching { speaker.stop() }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { speaker.stop() }
        }
    }
}
