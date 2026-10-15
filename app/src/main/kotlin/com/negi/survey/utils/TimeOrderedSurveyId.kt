/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: TimeOrderedSurveyId.kt
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Generates and decodes time-ordered survey run IDs.
 *
 *  Survey run IDs are formatted as UUIDv7 (RFC 9562): the leading 48 bits
 *  encode the Unix epoch millisecond the survey run started, followed by
 *  the version/variant bits, followed by random bits. This keeps the ID a
 *  valid, opaque-looking UUID string everywhere it is already used
 *  (filenames, the uploaded-survey ledger, logs) while also letting the
 *  app recover "when was this survey started" purely from the ID string
 *  itself — including after the original staged file (whose name also
 *  carried a timestamp) has been deleted once the survey uploaded.
 *
 *  IDs generated before this change are plain random UUIDv4 and carry no
 *  recoverable timestamp; [surveyIdTimestampMillis] returns null for those
 *  rather than a bogus value.
 * =====================================================================
 */
package com.negi.survey.utils

import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val secureRandom = SecureRandom()

/** 48-bit mask for the millisecond timestamp field. */
private const val TIMESTAMP_MASK = 0xFFFFFFFFFFFFL

/** 12-bit mask for the random `rand_a` field. */
private const val RAND_A_MASK = 0x0FFFL

/** 62-bit mask for the random `rand_b` field (top 2 bits reserved for the variant). */
private const val RAND_B_MASK = 0x3FFFFFFFFFFFFFFFL

/** UUIDv7 version nibble ("0111"), positioned at bits 15..12 of the MSB word. */
private const val VERSION_7_NIBBLE = 0x7L

/** UUIDv7 variant bits ("10"), positioned at bits 63..62 of the LSB word. */
private const val VARIANT_10_BITS = 0x2L

/**
 * Generates a new time-ordered survey run ID.
 *
 * The result is a standard, valid UUID string (version 7): existing code that
 * treats survey IDs as opaque strings (filenames, the uploaded ledger, logs)
 * needs no changes. [nowMillis] becomes the recoverable "run started at"
 * timestamp — see [surveyIdTimestampMillis].
 */
fun newTimeOrderedSurveyId(nowMillis: Long = System.currentTimeMillis()): String {
    val timestampBits = nowMillis and TIMESTAMP_MASK
    val randA = secureRandom.nextLong() and RAND_A_MASK
    val mostSigBits = (timestampBits shl 16) or (VERSION_7_NIBBLE shl 12) or randA

    val randB = secureRandom.nextLong() and RAND_B_MASK
    val leastSigBits = (VARIANT_10_BITS shl 62) or randB

    return UUID(mostSigBits, leastSigBits).toString()
}

/**
 * Recovers the Unix epoch millisecond embedded in a time-ordered survey ID.
 *
 * Returns null when [surveyId] is not a syntactically valid UUID, or is a
 * valid UUID of a version other than 7 (e.g. an older, plain-random ID that
 * carries no timestamp).
 */
fun surveyIdTimestampMillis(surveyId: String): Long? {
    val uuid = runCatching { UUID.fromString(surveyId.trim()) }.getOrNull() ?: return null
    if (uuid.version() != 7) return null
    return uuid.mostSignificantBits ushr 16
}

/** Formats [millis] for display, in the device's local time zone. */
fun formatSurveyTimestamp(millis: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return formatter.format(Date(millis))
}

/**
 * Returns the first hyphen-delimited segment of [surveyId] for display
 * (e.g. "01a1406f" from "01a1406f-98dd-7f16-abc4-67660049649e").
 *
 * This is a short, non-identifying fragment of an already-opaque ID — never
 * the full ID — used only to tell entries apart in the upload status list.
 * Falls back to "?" when [surveyId] is blank or has no hyphen.
 */
fun uuidFirstSegment(surveyId: String): String =
    surveyId.trim().substringBefore('-').takeIf { it.isNotBlank() } ?: "?"
