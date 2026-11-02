package com.negi.survey.whisper

import kotlinx.coroutines.CancellationException

/** A failed native free leaves ownership uncertain and must stop model replacement. */
internal suspend fun releaseWhisperContextForReplacement(
    release: suspend () -> Unit,
    markUncertain: (Throwable) -> Unit,
) {
    try {
        release()
    } catch (error: Throwable) {
        markUncertain(error)
        if (error is CancellationException) throw error
        throw IllegalStateException(
            "Whisper native release completion is uncertain; restart the process before reuse.",
            error,
        )
    }
}
