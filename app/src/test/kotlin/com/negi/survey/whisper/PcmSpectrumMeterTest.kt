package com.negi.survey.whisper

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmSpectrumMeterTest {
    @Test
    fun windowsSpanReadsAtEachCaptureRateAndIgnoreUnusedSamples() {
        for (rate in captureRates) {
            val meter = PcmSpectrumMeter(rate)
            val window = rate / 30
            val samples = ShortArray(window + 1)
            samples[window] = Short.MAX_VALUE
            assertNull(meter.read(samples, window - 1))
            assertSilent(checkNotNull(meter.read(samples, 1)))
            assertNull(meter.read(samples, 1))
        }
    }

    @Test
    fun eachSyntheticTonePeaksInItsOwnBandAtEveryCaptureRate() {
        val tones = listOf(125, 250, 440, 750, 1250, 2000, 3400, 5400)
        for (rate in captureRates) {
            for ((expectedBand, frequency) in tones.withIndex()) {
                val meter = PcmSpectrumMeter(rate)
                val samples = ShortArray(rate / 30) { tone(frequency, it, rate, 4000).toInt().toShort() }
                val original = samples.copyOf()
                val spectrum = checkNotNull(meter.read(samples, samples.size))
                assertArrayEquals(original, samples)
                val bands = (0 until RecordingSpectrum.BAND_COUNT).map { spectrum[it] }
                assertTrue(bands.all { it in 0f..1f })
                assertEquals("Peak for ${frequency}Hz at ${rate}Hz", expectedBand, bands.indices.maxBy { bands[it] })
                assertTrue(bands[expectedBand] in 0.65f..0.75f)
                assertTrue(bands.indices.filter { it != expectedBand }.all { bands[it] < 0.2f })
            }
        }
    }

    @Test
    fun simultaneousTonesDriveSeparateBandsAndClippedSamplesStayBounded() {
        val rate = 16_000
        val meter = PcmSpectrumMeter(rate)
        val samples = ShortArray(rate / 30) { index ->
            (tone(250, index, rate, 35_000) + tone(2000, index, rate, 18_000))
                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val spectrum = checkNotNull(meter.read(samples, samples.size))
        assertEquals(1f, spectrum[1], 0f)
        assertEquals(1f, spectrum[5], 0f)
        assertTrue(spectrum[1] > spectrum[0])
        assertTrue(spectrum[5] > spectrum[4])
        assertTrue((0 until RecordingSpectrum.BAND_COUNT).all { spectrum[it] in 0f..1f })
    }

    @Test
    fun dcStaysQuietAndBandsAttackInOneWindowAndSettleWithinFive() {
        val rate = 16_000
        val meter = PcmSpectrumMeter(rate)
        val samples = ShortArray(rate / 30) { 4000 }
        assertSilent(checkNotNull(meter.read(samples, samples.size)))
        for (index in samples.indices) samples[index] = tone(750, index, rate, 12_000).toInt().toShort()
        val active = checkNotNull(meter.read(samples, samples.size))
        assertEquals(1f, active[3], 0f)
        samples.fill(0)
        assertEquals(0.4f, checkNotNull(meter.read(samples, samples.size))[3], 0.001f)
        repeat(3) { meter.read(samples, samples.size) }
        assertSilent(checkNotNull(meter.read(samples, samples.size)))
        assertEquals(1f, active[3], 0f)
    }

    private fun assertSilent(spectrum: RecordingSpectrum) {
        for (band in 0 until RecordingSpectrum.BAND_COUNT) assertEquals(0f, spectrum[band], 0f)
    }

    private fun tone(frequency: Int, index: Int, rate: Int, amplitude: Int): Double =
        amplitude * sin(2 * PI * frequency * index / rate)

    private val captureRates = listOf(16_000, 44_100, 48_000)
}
