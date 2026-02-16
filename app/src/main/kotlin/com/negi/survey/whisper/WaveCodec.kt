/*
 * ================================================================
 *  IshizukiTech LLC — Whisper Integration Framework
 *  ------------------------------------------------
 *  File: WaveCodec.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * ================================================================
 */

package com.negi.survey.whisper

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
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
 * ## Robustness notes
 * - Chunk sizes in broken WAVs can exceed the actual file length. This decoder
 *   clamps chunk skips to EOF instead of throwing immediately, allowing partial
 *   salvage of truncated recordings.
 *
 * @throws IOException If the file is malformed or contains invalid chunk data
 * @throws IllegalArgumentException If the file does not exist or is unreadable
 */
@Throws(IOException::class, IllegalArgumentException::class)
fun decodeWaveFile(
    file: File,
    targetSampleRate: Int = 16_000
): FloatArray {
    require(targetSampleRate > 0) { "Invalid targetSampleRate=$targetSampleRate" }
    require(file.exists()) { "File not found: ${file.path}" }
    require(file.isFile) { "Not a file: ${file.path}" }
    require(file.canRead()) { "File not readable: ${file.path}" }

    RandomAccessFile(file, "r").use { raf ->
        if (raf.length() < 44L) {
            throw IOException("Invalid WAV: header too short (${raf.length()} bytes)")
        }

        // --- Validate RIFF/WAVE headers ---
        val riff = raf.readAscii4()
        raf.skipFullyOrThrow(4) // riffSize (u32)
        val wave = raf.readAscii4()
        if (riff != "RIFF" || wave != "WAVE") {
            throw IOException("Invalid RIFF/WAVE header in ${file.name}: riff='$riff' wave='$wave'")
        }

        // --- Default metadata placeholders ---
        var fmtFound = false
        var dataFound = false

        var audioFormat = 1
        var channels = 1
        var sampleRate = 16_000
        var bitsPerSample = 16

        var dataStart = -1L
        var dataSizeHeader = 0L

        // --- Parse RIFF chunks until we find both fmt and data (order can vary) ---
        while (raf.filePointer + 8L <= raf.length()) {
            val id = raf.readAscii4()
            val size = raf.readLE32u()
            val chunkDataStart = raf.filePointer

            when (id) {
                "fmt " -> {
                    if (size < 16L) {
                        throw IOException("Invalid 'fmt ' chunk in ${file.name} (size=$size)")
                    }

                    audioFormat = raf.readLE16u()
                    channels = raf.readLE16u()
                    sampleRate = raf.readLE32u().toInt()
                    raf.skipFullyOrThrow(4) // byteRate
                    raf.skipFullyOrThrow(2) // blockAlign
                    bitsPerSample = raf.readLE16u()

                    val remaining = size - 16L
                    if (remaining > 0L) {
                        raf.skipFullyClamped(remaining, reason = "fmt-extra")
                        Log.v(LOG_TAG, "fmt: extra fmt bytes=$remaining")
                    }

                    fmtFound = true
                    Log.d(LOG_TAG, "fmt: format=$audioFormat, ch=$channels, rate=$sampleRate, bits=$bitsPerSample")
                }

                "data" -> {
                    dataStart = chunkDataStart
                    dataSizeHeader = size
                    dataFound = true

                    // IMPORTANT: Do not throw if the WAV is truncated. Clamp to EOF.
                    raf.skipFullyClamped(size, reason = "data-scan")
                }

                else -> {
                    Log.v(LOG_TAG, "Skip chunk: '$id' ($size bytes)")
                    raf.skipFullyClamped(size, reason = "unknown:$id")
                }
            }

            // RIFF chunks are padded to even size.
            if ((size and 1L) == 1L) {
                raf.skipFullyClamped(1, reason = "pad")
            }

            if (fmtFound && dataFound) break
        }

        // --- Validate parsed metadata ---
        if (!fmtFound) throw IOException("Missing 'fmt ' chunk in ${file.name}")
        if (!dataFound || dataStart < 0L || dataSizeHeader <= 0L) {
            throw IOException("Missing or empty 'data' chunk in ${file.name}")
        }
        if (channels <= 0) throw IOException("Invalid channel count ($channels) in ${file.name}")
        if (sampleRate <= 0) throw IOException("Invalid sampleRate ($sampleRate) in ${file.name}")
        if (audioFormat !in listOf(1, 3)) {
            throw IOException("Unsupported format=$audioFormat in ${file.name} (only 1=PCM, 3=FLOAT supported)")
        }
        if (audioFormat == 1 && bitsPerSample != 16) {
            throw IOException("Unsupported PCM bit depth=$bitsPerSample (only 16-bit supported)")
        }
        if (audioFormat == 3 && bitsPerSample != 32) {
            throw IOException("Unsupported FLOAT bit depth=$bitsPerSample (only 32-bit supported)")
        }

        // --- Clamp effective data size to actual file length ---
        val fileLen = raf.length()
        val maxReadable = (fileLen - dataStart).coerceAtLeast(0L)
        val dataEffectiveSize = min(dataSizeHeader, maxReadable).coerceAtLeast(0L)

        if (dataEffectiveSize <= 0L) {
            throw IOException("Empty 'data' payload in ${file.name} (declared=$dataSizeHeader, readable=$maxReadable)")
        }

        Log.v(LOG_TAG, "data chunk start=$dataStart declared=$dataSizeHeader effective=$dataEffectiveSize")

        // --- Decode PCM samples into mono float buffer ---
        raf.seek(dataStart)
        val monoSrc: FloatArray = when (audioFormat) {
            1 -> decodePcm16ToMono(raf, dataEffectiveSize, channels)
            3 -> decodeFloat32ToMono(raf, dataEffectiveSize, channels)
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
            Log.w(LOG_TAG, "Resampling ${sampleRate}Hz → ${targetSampleRate}Hz (linear interpolation)")
            resampleLinearEndpointAligned(monoSrc, sampleRate, targetSampleRate)
        }
    }
}

/**
 * Decodes 16-bit PCM (LE) samples from a RandomAccessFile and mixes channels into mono.
 *
 * Note:
 * - This function streams from disk and does not allocate large intermediate buffers.
 */
private fun decodePcm16ToMono(
    raf: RandomAccessFile,
    dataBytes: Long,
    channels: Int
): FloatArray {
    if (dataBytes <= 0L) return FloatArray(0)
    val bytesPerFrame = 2L * channels.toLong()
    if (bytesPerFrame <= 0L) return FloatArray(0)

    val frameCountLong = dataBytes / bytesPerFrame
    if (frameCountLong > Int.MAX_VALUE.toLong()) {
        throw IOException("WAV too large: frames=$frameCountLong (int overflow)")
    }

    val frameCount = frameCountLong.toInt()
    val out = FloatArray(frameCount)

    val framesPerChunk = 4096
    val buf = ByteArray((framesPerChunk.toLong() * bytesPerFrame).toInt())

    var outIndex = 0
    var remainingFrames = frameCount

    while (remainingFrames > 0) {
        val takeFrames = min(remainingFrames, framesPerChunk)
        val wantBytes = (takeFrames.toLong() * bytesPerFrame).toInt()

        val got = raf.read(buf, 0, wantBytes)
        if (got <= 0) break

        val usableBytes = got - (got % bytesPerFrame.toInt())
        val usableFrames = usableBytes / bytesPerFrame.toInt()
        if (usableFrames <= 0) break

        var p = 0
        for (i in 0 until usableFrames) {
            var acc = 0f
            for (ch in 0 until channels) {
                val lo = buf[p].toInt() and 0xFF
                val hi = buf[p + 1].toInt()
                val s = ((hi shl 8) or lo).toShort().toInt()
                acc += s / 32768f
                p += 2
            }
            out[outIndex++] = (acc / channels.toFloat()).coerceIn(-1f, 1f)
        }

        remainingFrames -= usableFrames
    }

    return if (outIndex == out.size) out else out.copyOf(outIndex)
}

/**
 * Decodes IEEE 32-bit float PCM (LE) samples from a RandomAccessFile and mixes channels into mono.
 */
private fun decodeFloat32ToMono(
    raf: RandomAccessFile,
    dataBytes: Long,
    channels: Int
): FloatArray {
    if (dataBytes <= 0L) return FloatArray(0)
    val bytesPerFrame = 4L * channels.toLong()
    if (bytesPerFrame <= 0L) return FloatArray(0)

    val frameCountLong = dataBytes / bytesPerFrame
    if (frameCountLong > Int.MAX_VALUE.toLong()) {
        throw IOException("WAV too large: frames=$frameCountLong (int overflow)")
    }

    val frameCount = frameCountLong.toInt()
    val out = FloatArray(frameCount)

    val framesPerChunk = 2048
    val buf = ByteArray((framesPerChunk.toLong() * bytesPerFrame).toInt())

    var outIndex = 0
    var remainingFrames = frameCount

    while (remainingFrames > 0) {
        val takeFrames = min(remainingFrames, framesPerChunk)
        val wantBytes = (takeFrames.toLong() * bytesPerFrame).toInt()

        val got = raf.read(buf, 0, wantBytes)
        if (got <= 0) break

        val usableBytes = got - (got % bytesPerFrame.toInt())
        val usableFrames = usableBytes / bytesPerFrame.toInt()
        if (usableFrames <= 0) break

        var p = 0
        for (i in 0 until usableFrames) {
            var acc = 0f
            for (ch in 0 until channels) {
                val b0 = buf[p].toInt() and 0xFF
                val b1 = buf[p + 1].toInt() and 0xFF
                val b2 = buf[p + 2].toInt() and 0xFF
                val b3 = buf[p + 3].toInt() and 0xFF
                val bits = (b0) or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                acc += Float.fromBits(bits)
                p += 4
            }
            out[outIndex++] = (acc / channels.toFloat()).coerceIn(-1f, 1f)
        }

        remainingFrames -= usableFrames
    }

    return if (outIndex == out.size) out else out.copyOf(outIndex)
}

/**
 * Endpoint-aligned linear resampler.
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
 * Reads exactly 4 ASCII bytes as chunk ID.
 */
private fun RandomAccessFile.readAscii4(): String {
    val b = ByteArray(4)
    readFully(b)
    return String(b, Charsets.US_ASCII)
}

/**
 * Reads an unsigned little-endian 16-bit integer.
 */
private fun RandomAccessFile.readLE16u(): Int {
    val b0 = read()
    val b1 = read()
    if (b0 < 0 || b1 < 0) throw IOException("Unexpected EOF while reading LE16")
    return (b0 and 0xFF) or ((b1 and 0xFF) shl 8)
}

/**
 * Reads an unsigned little-endian 32-bit integer (returned as Long).
 */
private fun RandomAccessFile.readLE32u(): Long {
    val b0 = read()
    val b1 = read()
    val b2 = read()
    val b3 = read()
    if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) throw IOException("Unexpected EOF while reading LE32")
    return ((b0.toLong() and 0xFFL)) or
            ((b1.toLong() and 0xFFL) shl 8) or
            ((b2.toLong() and 0xFFL) shl 16) or
            ((b3.toLong() and 0xFFL) shl 24)
}

/**
 * Skips exactly n bytes, throwing on unexpected EOF.
 *
 * This is used only when the spec guarantees presence (e.g., fixed RIFF header fields).
 */
private fun RandomAccessFile.skipFullyOrThrow(n: Long) {
    if (n <= 0L) return
    val target = filePointer + n
    if (target > length()) {
        seek(length())
        throw IOException("Unexpected EOF while skipping $n bytes")
    }
    seek(target)
}

/**
 * Skips up to n bytes, clamped to EOF. Never throws due to chunk-size overrun.
 *
 * @return The actual number of bytes skipped.
 */
private fun RandomAccessFile.skipFullyClamped(n: Long, reason: String): Long {
    if (n <= 0L) return 0L
    val cur = filePointer
    val max = length()
    val target = (cur + n).coerceAtMost(max)
    seek(target)
    val skipped = target - cur
    if (skipped < n) {
        Log.w(LOG_TAG, "Clamped skip ($reason): wanted=$n actual=$skipped fp=$cur len=$max")
    }
    return skipped
}
