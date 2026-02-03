/*
 * ================================================================
 *  IshizukiTech LLC — Whisper Integration Framework
 *  ------------------------------------------------
 *  File: WaveCodec.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * ================================================================
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the “Software”), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 * ================================================================
 */

package com.negi.survey.whisper

import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

private const val LOG_TAG = "WaveCodec"

/**
 * WaveCodec — Robust WAV (RIFF) PCM decoder for Whisper integration.
 *
 * ## Overview
 * Converts standard `.wav` audio files (PCM16 or IEEE Float32) into normalized mono
 * float arrays in the range [-1.0, 1.0]. The implementation supports multi-channel
 * input (automatically mixed to mono), gracefully skips unknown RIFF chunks,
 * and optionally performs endpoint-aligned linear resampling to a target rate.
 *
 * ## Supported Formats
 * - **PCM 16-bit** (`audioFormat = 1`)
 * - **IEEE Float 32-bit** (`audioFormat = 3`)
 *
 * ## Features
 * - Tolerant RIFF parser with padding and unknown chunk skipping
 * - Automatic downmix to mono
 * - Optional resampling via linear interpolation
 * - Safe bounds checking and error messages
 * - Verbose debug logging for forensic diagnostics
 *
 * ## Output
 * Returns a normalized `FloatArray` of mono samples suitable for direct
 * Whisper model ingestion.
 *
 * Typical usage:
 * ```kotlin
 * val samples = decodeWaveFile(File("/path/audio.wav"), targetSampleRate = 16000)
 * ```
 *
 * @throws IOException If the file is malformed or contains invalid chunk data
 * @throws IllegalArgumentException If the file does not exist or is unreadable
 */
@Throws(IOException::class, IllegalArgumentException::class)
fun decodeWaveFile(
    file: File,
    targetSampleRate: Int = 16_000
): FloatArray {
    require(file.exists()) { "File not found: ${file.path}" }

    val bytes = file.readBytes()
    if (bytes.size < 44) throw IOException("Invalid WAV: header too short (${bytes.size} bytes)")

    // --- Validate RIFF/WAVE headers ---
    val riff = String(bytes, 0, 4, Charsets.US_ASCII)
    val wave = String(bytes, 8, 4, Charsets.US_ASCII)
    if (riff != "RIFF" || wave != "WAVE") {
        throw IOException("Invalid RIFF/WAVE header in ${file.name}: riff='$riff' wave='$wave'")
    }

    // --- Default metadata placeholders ---
    var pos = 12
    var audioFormat = 1
    var channels = 1
    var sampleRate = 16_000
    var bitsPerSample = 16

    var dataStart = -1
    var dataSizeHeader = 0
    var dataEffectiveSize = 0

    // --- Parse all RIFF chunks until 'data' found ---
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4, Charsets.US_ASCII)
        val size = readLE32(bytes, pos + 4).coerceAtLeast(0)
        val chunkDataStart = pos + 8
        val chunkDataEnd = min(bytes.size, chunkDataStart + size)

        when (id) {
            "fmt " -> {
                /** Parse format chunk — may contain optional extra bytes (>16) **/
                if (chunkDataEnd - chunkDataStart < 16)
                    throw IOException("Invalid 'fmt ' chunk in ${file.name} (size=$size)")

                val bb = ByteBuffer
                    .wrap(bytes, chunkDataStart, chunkDataEnd - chunkDataStart)
                    .order(ByteOrder.LITTLE_ENDIAN)

                audioFormat = bb.short.toInt() and 0xFFFF
                channels = bb.short.toInt() and 0xFFFF
                sampleRate = bb.int
                /* byteRate */ bb.int
                /* blockAlign */ bb.short
                bitsPerSample = bb.short.toInt() and 0xFFFF

                // Optional extended fmt chunk (rare)
                if (size > 16 && bb.remaining() >= 2) {
                    val extraSize = bb.short.toInt() and 0xFFFF
                    Log.v(LOG_TAG, "fmt: extra fmt bytes=$extraSize")
                }

                Log.d(
                    LOG_TAG,
                    "fmt: format=$audioFormat, ch=$channels, rate=$sampleRate, bits=$bitsPerSample"
                )
            }

            "data" -> {
                /** Found PCM data chunk **/
                dataStart = chunkDataStart
                dataSizeHeader = size
                dataEffectiveSize = (chunkDataEnd - chunkDataStart).coerceAtLeast(0)
                Log.v(LOG_TAG, "data chunk start=$dataStart size=$dataEffectiveSize")
            }

            else -> {
                /** Skip all other chunks (LIST, fact, INFO, etc.) **/
                Log.v(LOG_TAG, "Skip chunk: '$id' ($size bytes)")
            }
        }

        // --- Advance to next chunk (with even-byte padding) ---
        val padded = size + (size and 1)
        val newPos = pos.toLong() + 8L + padded
        if (newPos >= bytes.size) break
        pos = newPos.toInt()
    }

    // --- Validate parsed metadata ---
    if (dataStart < 0 || dataEffectiveSize <= 0)
        throw IOException("Missing or empty 'data' chunk in ${file.name}")
    if (channels <= 0)
        throw IOException("Invalid channel count ($channels) in ${file.name}")
    if (audioFormat !in listOf(1, 3))
        throw IOException("Unsupported format=$audioFormat in ${file.name} (only 1=PCM, 3=FLOAT supported)")
    if (audioFormat == 1 && bitsPerSample != 16)
        throw IOException("Unsupported PCM bit depth=$bitsPerSample (only 16-bit supported)")
    if (audioFormat == 3 && bitsPerSample != 32)
        throw IOException("Unsupported FLOAT bit depth=$bitsPerSample (only 32-bit supported)")

    // --- Decode PCM samples into mono float buffer ---
    val monoSrc: FloatArray = when (audioFormat) {
        1 -> decodePcm16ToMono(bytes, dataStart, dataEffectiveSize, channels)
        3 -> decodeFloat32ToMono(bytes, dataStart, dataEffectiveSize, channels)
        else -> error("Guarded by validation")
    }

    Log.d(
        LOG_TAG,
        "Decoded WAV: ${channels}ch ${sampleRate}Hz ${bitsPerSample}-bit " +
                "(frames=${monoSrc.size}, dataHeader=$dataSizeHeader, effective=$dataEffectiveSize)"
    )

    // --- Optional resampling to target sample rate ---
    return if (sampleRate == targetSampleRate) {
        monoSrc
    } else {
        Log.w(
            LOG_TAG,
            "Resampling ${sampleRate}Hz → ${targetSampleRate}Hz (linear interpolation)"
        )
        resampleLinearEndpointAligned(monoSrc, sampleRate, targetSampleRate)
    }
}

/**
 * Decodes 16-bit PCM (LE) samples and mixes all channels into mono.
 */
private fun decodePcm16ToMono(
    bytes: ByteArray,
    start: Int,
    effectiveSize: Int,
    channels: Int
): FloatArray {
    if (effectiveSize <= 0) return FloatArray(0)
    val bytesPerFrame = 2 * channels
    if (bytesPerFrame <= 0) return FloatArray(0)

    val frameCount = (effectiveSize / bytesPerFrame).coerceAtLeast(0)
    val out = FloatArray(frameCount)

    val bb = ByteBuffer
        .wrap(bytes, start, min(effectiveSize, bytes.size - start))
        .order(ByteOrder.LITTLE_ENDIAN)

    for (i in 0 until frameCount) {
        var acc = 0f
        for (ch in 0 until channels) {
            val s = bb.short.toInt() // signed 16-bit sample
            acc += s / 32768f
        }
        out[i] = (acc / channels.toFloat()).coerceIn(-1f, 1f)
    }
    return out
}

/**
 * Decodes IEEE 32-bit float PCM samples and mixes all channels to mono.
 */
private fun decodeFloat32ToMono(
    bytes: ByteArray,
    start: Int,
    effectiveSize: Int,
    channels: Int
): FloatArray {
    if (effectiveSize <= 0) return FloatArray(0)
    val bytesPerFrame = 4 * channels
    if (bytesPerFrame <= 0) return FloatArray(0)

    val frameCount = (effectiveSize / bytesPerFrame).coerceAtLeast(0)
    val out = FloatArray(frameCount)

    val bb = ByteBuffer
        .wrap(bytes, start, min(effectiveSize, bytes.size - start))
        .order(ByteOrder.LITTLE_ENDIAN)

    for (i in 0 until frameCount) {
        var acc = 0f
        for (ch in 0 until channels) {
            acc += bb.float
        }
        out[i] = (acc / channels.toFloat()).coerceIn(-1f, 1f)
    }
    return out
}

/**
 * Endpoint-aligned linear resampler.
 *
 * Guarantees that the first and last samples align between
 * source and target buffers, ensuring no phase drift.
 * This is sufficient for Whisper inference which is insensitive
 * to slight resampling artifacts.
 */
private fun resampleLinearEndpointAligned(
    src: FloatArray,
    srcRate: Int,
    dstRate: Int
): FloatArray {
    if (src.isEmpty() || srcRate <= 0 || dstRate <= 0) return src

    val dstLen = if (src.size == 1) 1
    else ((src.size - 1).toLong() * dstRate / srcRate + 1)
        .toInt().coerceAtLeast(1)

    val dst = FloatArray(dstLen)
    if (dstLen == 1) {
        dst[0] = src[0]
        return dst
    }

    val step = (src.size - 1.0) / (dstLen - 1.0)
    var x = 0.0
    val last = src.lastIndex

    for (i in 0 until dstLen) {
        val i0 = x.toInt().coerceIn(0, last)
        val i1 = (i0 + 1).coerceAtMost(last)
        val frac = (x - i0).toFloat()
        dst[i] = src[i0] + (src[i1] - src[i0]) * frac
        x += step
    }
    return dst
}

/**
 * Safely reads a 32-bit little-endian integer from byte array.
 */
private fun readLE32(b: ByteArray, off: Int): Int {
    if (off < 0 || off + 4 > b.size) return 0
    return (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
