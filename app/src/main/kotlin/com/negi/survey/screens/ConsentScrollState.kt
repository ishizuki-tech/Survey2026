package com.negi.survey.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job

/** Retains Q00's position and first-visit claim for one survey run. */
internal class ConsentScrollState private constructor(position: Int, private var visited: Boolean) {
    // ScrollState clamps the restored position when the viewport is measured.
    val scroll = ScrollState(position)
    private var autoScrollJob: Job? = null

    private suspend fun autoScroll(pixelsPerSecond: Float) {
        if (visited) return
        visited = true
        autoScrollJob = currentCoroutineContext().job
        try {
            val max = snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
            if (max == 0) return
            delay(2_000)
            if (scroll.value != 0 || scroll.isScrollInProgress || scroll.maxValue == 0) return

            scroll.scroll {
                var lastFrame = withFrameNanos { it }
                while (scroll.value < scroll.maxValue) {
                    val frame = withFrameNanos { it }
                    scrollBy(pixelsPerSecond * ((frame - lastFrame) / 1_000_000_000f))
                    lastFrame = frame
                }
            }
        } finally {
            autoScrollJob = null
        }
    }

    private fun stop() {
        visited = true
        autoScrollJob?.cancel()
    }

    private val manualScroll = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f) stop()
            return Offset.Zero
        }
    }

    @Composable
    fun scrollInteraction(): Modifier {
        val pixelsPerSecond = with(LocalDensity.current) { 30.dp.toPx() }
        LaunchedEffect(this) { autoScroll(pixelsPerSecond) }

        return Modifier
            .pointerInput(this) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    stop()
                }
            }
            .nestedScroll(manualScroll)
    }

    companion object {
        val Saver = listSaver(
            save = { state: ConsentScrollState -> listOf(state.scroll.value, state.visited) },
            restore = { ConsentScrollState(it[0] as Int, it[1] as Boolean) }
        )

        fun newVisit() = ConsentScrollState(position = 0, visited = false)
    }
}

@Composable
internal fun rememberConsentScrollState(surveyUuid: String): ConsentScrollState =
    key(surveyUuid) {
        rememberSaveable(saver = ConsentScrollState.Saver) { ConsentScrollState.newVisit() }
    }
