/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyFileName.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  UUID-aware survey export naming helpers.
 *
 *  Design goals:
 *   • Always embed the run UUID when available.
 *   • Provide overloads to preserve legacy call sites.
 *   • Avoid overload ambiguity caused by default parameters.
 *   • Keep a single naming authority for JSON and voice exports.
 *
 *  Key fix in this version:
 *   • The legacy session-based overload now prioritizes the historical
 *     call shape: (sessionId, prefix?).
 *   • UUID injection via a session-based API uses a dedicated value class
 *     [SurveyUuid] to prevent accidental parameter mis-binding.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ============================================================
 * Types
 * ============================================================ */

/**
 * Strongly-typed wrapper for a survey run UUID used to prevent overload
 * ambiguity with legacy APIs.
 *
 * This ensures that a call like:
 *   buildSurveyFileName(1, "survey")
 * will still treat "survey" as a prefix (legacy behavior),
 * not as a UUID by mistake.
 */
@JvmInline
value class SurveyUuid(val value: String)

/* ============================================================
 * Survey JSON file name
 * ============================================================ */

private const val DEFAULT_JSON_TS_PATTERN = "yyyy-MM-dd_HH-mm-ss"
private const val DEFAULT_VOICE_TS_PATTERN = "yyyy-MM-dd_HH-mm-ss"

/**
 * Build a stable survey JSON file name that embeds the run UUID.
 *
 * Primary format:
 *   <prefix>_<surveyUuid>_<timestamp>.json
 *
 * Example:
 *   survey_550e8400-e29b-41d4-a716-446655440000_2025-12-05_14-32-08.json
 *
 * This is the preferred API for UUID-first flows.
 *
 * @param surveyId UUID of the active survey run.
 * @param prefix File prefix (default: "survey").
 * @param stamp Optional precomputed timestamp string.
 * @param nowMillis Timestamp source when [stamp] is null.
 */
fun buildSurveyFileName(
    surveyId: String,
    prefix: String = "survey",
    stamp: String? = null,
    nowMillis: Long = System.currentTimeMillis()
): String {
    val ts = safeSegment(stamp ?: timeStamp(nowMillis, pattern = DEFAULT_JSON_TS_PATTERN))
    val sid = safeSegment(surveyId)
    val pfx = safeSegment(prefix)
    return "${pfx}_${sid}_${ts}.json"
}

/**
 * Legacy-friendly overload for call sites that still name by session id.
 *
 * Legacy format:
 *   <prefix>_session<sessionId>_<timestamp>.json
 *
 * This overload intentionally does NOT accept a raw String UUID to avoid
 * the classic bug where the second positional argument accidentally binds
 * to a UUID parameter instead of a prefix.
 *
 * @param sessionId Incremental session id.
 * @param prefix File prefix (default: "survey").
 * @param stamp Optional precomputed timestamp string.
 * @param nowMillis Timestamp source when [stamp] is null.
 */
fun buildSurveyFileName(
    sessionId: Int,
    prefix: String = "survey",
    stamp: String? = null,
    nowMillis: Long = System.currentTimeMillis()
): String {
    val ts = safeSegment(stamp ?: timeStamp(nowMillis, pattern = DEFAULT_JSON_TS_PATTERN))
    val pfx = safeSegment(prefix)
    return "${pfx}_session${sessionId}_${ts}.json"
}

/**
 * Bridge overload for mixed systems that have a session counter
 * but also carry a run UUID.
 *
 * This overload requires a typed [SurveyUuid] to prevent accidental
 * prefix/UUID confusion in positional calls.
 *
 * When [surveyUuid] is blank after sanitization, this falls back to the
 * session-based naming rule.
 *
 * @param sessionId Incremental session id.
 * @param surveyUuid Typed UUID wrapper.
 * @param prefix File prefix (default: "survey").
 * @param stamp Optional precomputed timestamp string.
 * @param nowMillis Timestamp source when [stamp] is null.
 */
fun buildSurveyFileName(
    sessionId: Int,
    surveyUuid: SurveyUuid,
    prefix: String = "survey",
    stamp: String? = null,
    nowMillis: Long = System.currentTimeMillis()
): String {
    val ts = safeSegment(stamp ?: timeStamp(nowMillis, pattern = DEFAULT_JSON_TS_PATTERN))
    val pfx = safeSegment(prefix)
    val sid = surveyUuid.value.takeIf { it.isNotBlank() }?.let { safeSegment(it) }

    return if (!sid.isNullOrBlank()) {
        "${pfx}_${sid}_${ts}.json"
    } else {
        "${pfx}_session${sessionId}_${ts}.json"
    }
}

/* ============================================================
 * Voice WAV file name
 * ============================================================ */

/**
 * Build a stable voice WAV file name that embeds UUID and question id.
 *
 * Format:
 *   voice_<surveyUuid>_<questionId>_<timestamp>.wav
 *
 * This helper centralizes voice naming so the recorder/export pipeline
 * and any post-run aggregation can converge on the same convention.
 *
 * @param surveyUuid UUID of the active survey run.
 * @param questionId Node ID for the question (nullable).
 * @param prefix File prefix (default: "voice").
 * @param stamp Optional precomputed timestamp string.
 * @param nowMillis Timestamp source when [stamp] is null.
 */
fun buildVoiceFileName(
    surveyUuid: String,
    questionId: String?,
    prefix: String = "voice",
    stamp: String? = null,
    nowMillis: Long = System.currentTimeMillis()
): String {
    val ts = safeSegment(stamp ?: timeStamp(nowMillis, pattern = DEFAULT_VOICE_TS_PATTERN))
    val sid = safeSegment(surveyUuid)
    val qid = safeSegment(questionId ?: "unknown")
    val pfx = safeSegment(prefix)
    return "${pfx}_${sid}_${qid}_${ts}.wav"
}

/* ============================================================
 * Timestamp + sanitization
 * ============================================================ */

/**
 * Create a filename-friendly timestamp string using [pattern].
 *
 * Notes:
 * - This uses the device's local timezone via the default SimpleDateFormat
 *   configuration. If your export pipeline requires cross-device canonical
 *   time ordering, consider switching to a UTC formatter in a future revision.
 *
 * @param nowMillis Milliseconds used as the time source.
 * @param pattern A SimpleDateFormat pattern for filename-safe timestamps.
 */
private fun timeStamp(
    nowMillis: Long,
    pattern: String
): String {
    val fmt = SimpleDateFormat(pattern, Locale.US)
    return fmt.format(Date(nowMillis))
}

/**
 * Sanitize a filename segment to avoid path traversal and unstable characters.
 *
 * Rules:
 * - Keep only A-Z, a-z, 0-9, dot, underscore, and hyphen.
 * - Replace all other character runs with a single underscore.
 * - Trim leading/trailing underscores.
 * - Enforce a conservative length limit to reduce risk on older filesystems
 *   and to keep exported filenames readable.
 *
 * @param raw Raw segment (UUID, nodeId, prefix, or precomputed stamp).
 * @return A safe, non-empty filename segment.
 */
private fun safeSegment(raw: String): String {
    return raw
        .trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .take(80)
        .ifBlank { "unknown" }
}
