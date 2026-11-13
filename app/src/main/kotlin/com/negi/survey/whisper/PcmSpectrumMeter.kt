package com.negi.survey.whisper

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Immutable display levels, ordered from the lowest frequency band to the highest. */
class RecordingSpectrum internal constructor(levels: FloatArray = FloatArray(BAND_COUNT)) {
    private val bands = levels.copyOf()

    operator fun get(band: Int): Float = bands[band]

    companion object {
        const val BAND_COUNT = 8
    }
}

/** Analyzes one Hann-windowed PCM16 frame per ~33 ms of capture. */
internal class PcmSpectrumMeter(sampleRate: Int) {
    val windowSamples = sampleRate / 30
    private val fftSize = Integer.highestOneBit(windowSamples - 1) shl 1
    private val fftBits = Integer.numberOfTrailingZeros(fftSize)
    private val bitReversed = IntArray(fftSize) { Integer.reverse(it) ushr (32 - fftBits) }
    private val window = FloatArray(windowSamples) { index ->
        (0.5 - 0.5 * cos(2 * PI * index / (windowSamples - 1))).toFloat()
    }
    private val powerScale = 4f / (fftSize * window.sumOf { (it * it).toDouble() }).toFloat()
    private val cosines = FloatArray(fftSize / 2) { cos(2 * PI * it / fftSize).toFloat() }
    private val sines = FloatArray(fftSize / 2) { -sin(2 * PI * it / fftSize).toFloat() }
    private val real = FloatArray(fftSize)
    private val imaginary = FloatArray(fftSize)
    private val binEdges = IntArray(BAND_EDGES_HZ.size) { index ->
        // Round upward so each bin center belongs to exactly one band.
        (BAND_EDGES_HZ[index] * fftSize + sampleRate - 1) / sampleRate
    }
    private val envelope = FloatArray(RecordingSpectrum.BAND_COUNT)
    private var sampleCount = 0

    /** Returns the latest completed spectrum, or null while collecting the next frame. */
    fun read(samples: ShortArray, count: Int): RecordingSpectrum? {
        var completed = false
        for (index in 0 until count) {
            real[bitReversed[sampleCount]] = samples[index] * (1f / 32768f) * window[sampleCount]
            sampleCount++
            if (sampleCount == windowSamples) {
                measureBands()
                sampleCount = 0
                completed = true
            }
        }
        return if (completed) RecordingSpectrum(envelope) else null
    }

    private fun measureBands() {
        // Input is written in bit-reversed order for the iterative radix-2 transform.
        for (index in windowSamples until fftSize) real[bitReversed[index]] = 0f
        imaginary.fill(0f)
        var span = 2
        while (span <= fftSize) {
            val half = span / 2
            val stride = fftSize / span
            for (start in 0 until fftSize step span) {
                for (offset in 0 until half) {
                    val even = start + offset
                    val odd = even + half
                    val twiddle = offset * stride
                    val rotatedReal = real[odd] * cosines[twiddle] - imaginary[odd] * sines[twiddle]
                    val rotatedImaginary = real[odd] * sines[twiddle] + imaginary[odd] * cosines[twiddle]
                    real[odd] = real[even] - rotatedReal
                    imaginary[odd] = imaginary[even] - rotatedImaginary
                    real[even] += rotatedReal
                    imaginary[even] += rotatedImaginary
                }
            }
            span *= 2
        }
        for (band in envelope.indices) {
            var power = 0f
            for (bin in binEdges[band] until binEdges[band + 1]) {
                power += real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
            }
            val amplitude = sqrt(power * powerScale)
            val target = sqrt(((amplitude - 0.003f) / 0.247f).coerceIn(0f, 1f))
            val level = maxOf(target, envelope[band] * 0.4f)
            envelope[band] = if (level < 0.02f) 0f else level
        }
    }

    private companion object {
        val BAND_EDGES_HZ = intArrayOf(80, 180, 320, 560, 950, 1600, 2600, 4200, 6800)
    }
}
