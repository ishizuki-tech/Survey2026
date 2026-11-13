package com.negi.survey.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import com.negi.survey.whisper.RecordingSpectrum
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LiveRecordingWaveformTest {
    private val motion = object : MotionDurationScale {
        override var scaleFactor by mutableStateOf(1f)
    }

    @get:Rule val rule = createComposeRule(effectContext = motion)

    @Test
    fun rendersFifteenMirroredBarsGrowingUpFromOneBaseline() {
        val levels = MutableStateFlow<RecordingSpectrum?>(RecordingSpectrum())
        val narrow = mutableStateOf(true)
        rule.setContent {
            val colors = if (narrow.value) lightColorScheme() else darkColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface {
                    Box(
                        Modifier.width(if (narrow.value) 220.dp else 320.dp).testTag("waveform-parent"),
                        contentAlignment = Alignment.Center,
                    ) {
                        LiveRecordingWaveform(levels)
                    }
                }
            }
        }
        val waveform = rule.onNodeWithTag("live-recording-waveform")
        val bounds = waveform.fetchSemanticsNode().boundsInRoot
        val parent = rule.onNodeWithTag("waveform-parent").fetchSemanticsNode().boundsInRoot
        assertEquals(parent.width * 0.75f, bounds.width, 1f)
        assertEquals(parent.center.x, bounds.center.x, 1f)
        val resting = waveform.captureToImage().toPixelMap()
        val barCenters = resting.barCenters()
        assertEquals(15, barCenters.size)

        rule.runOnIdle { levels.value = RecordingSpectrum(floatArrayOf(0.85f, 0f, 0f, 0.6f, 0f, 0f, 0f, 0.4f)) }
        val active = waveform.captureToImage().toPixelMap()
        assertEquals(barCenters, active.barCenters())
        val x = barCenters[7]
        for (pair in 0 until 7) {
            val left = barCenters[pair]
            val right = barCenters[14 - pair]
            // Rasterization can round opposite edges by one pixel.
            assertTrue(abs(left + right - (active.width - 1)) <= 1)
            assertTrue(abs(active.barHeight(left) - active.barHeight(right)) <= 1)
            assertEquals(active[left, active.baselineRow], active[right, active.baselineRow])
        }
        for (band in listOf(1, 2, 4, 5, 6)) {
            val quiet = barCenters[7 + band]
            assertEquals(resting.barHeight(quiet), active.barHeight(quiet))
        }
        for (band in listOf(3, 7)) {
            val energized = barCenters[7 + band]
            assertTrue(active.barHeight(energized) > resting.barHeight(energized))
        }
        for (center in barCenters) {
            val bottom = active.barRows(center).last()
            assertEquals(resting.barRows(center).last(), bottom)
            assertTrue("Bar at x=$center ends at row $bottom", bottom >= active.height - 2)
        }
        assertTrue(active.barRows(x).first() < resting.barRows(x).first())
        assertTrue(active.barHeight(x) > resting.barHeight(x) * 2)
        assertTrue(active[x, active.baselineRow] != resting[x, resting.baselineRow])

        rule.runOnIdle { levels.value = RecordingSpectrum() }
        val settled = waveform.captureToImage().toPixelMap()
        for (center in barCenters) {
            assertEquals(resting.barHeight(center), settled.barHeight(center))
        }

        rule.runOnIdle {
            narrow.value = false
            levels.value = RecordingSpectrum(floatArrayOf(0.85f, 0f, 0f, 0.6f, 0f, 0f, 0f, 0.4f))
        }
        val wider = waveform.captureToImage().toPixelMap()
        assertTrue(wider.width > resting.width)
        assertEquals(15, wider.barCenters().size)

        rule.runOnIdle { motion.scaleFactor = 0f }
        val still = waveform.captureToImage().toPixelMap()
        val stillCenters = still.barCenters()
        assertEquals(15, stillCenters.size)
        assertTrue(still.barHeight(stillCenters[7]) < wider.barHeight(wider.barCenters()[7]) / 2)
        assertEquals(still.barHeight(stillCenters.first()), still.barHeight(stillCenters.last()))
    }

    private fun PixelMap.barCenters(): List<Int> = buildList {
        val y = baselineRow
        val background = this@barCenters[0, y]
        var left = -1
        for (x in 0 until width) {
            if (this@barCenters[x, y] != background) {
                if (left < 0) left = x
            } else if (left >= 0) {
                add((left + x - 1) / 2)
                left = -1
            }
        }
    }

    private val PixelMap.baselineRow: Int get() = height - 2

    private fun PixelMap.barRows(x: Int): List<Int> = (0 until height).filter { y -> this[x, y] != this[0, y] }

    private fun PixelMap.barHeight(x: Int): Int = barRows(x).size
}
