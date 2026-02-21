/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: FollowupExtractor.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Utility object for extracting follow-up questions and simple scores
 *  from raw SLM output. Supports:
 *    - Multiple embedded JSON fragments (JSONObject / JSONArray).
 *    - Robust key normalization (separator-insensitive + camelCase-aware).
 *    - Question-field detection with lightweight heuristics.
 *    - Deduplication with encounter order.
 *    - Lightweight score extraction with JSON-first semantics and
 *      plain-text fallback.
 *
 *  2026-02 Update:
 *    - Add extraction APIs for followup_target, followup_needed, weakness
 *      (JSON-first, plain-text best-effort).
 *    - Improve code-fence extraction (inline fences supported).
 *    - Fix contract field extraction when values are wrapped in JSONObject.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.slm

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Utility for extracting follow-up questions (and simple scores) from raw text or JSON.
 *
 * Features:
 * - Detects and parses one or more JSON fragments (JSONObject / JSONArray) embedded in free-form text.
 * - Traverses objects/arrays to find likely question-bearing fields (key-insensitive, separator-insensitive).
 * - Handles camelCase keys (e.g., followUpQuestion) by inserting separators before normalization.
 * - Deduplicates while preserving encounter order (LinkedHashSet semantics).
 *   Note: JSONArray order is stable; JSONObject key iteration order may vary by implementation.
 * - Provides a light-weight 0..100 score extractor with robust JSON recursion + textual fallback.
 *
 * Typical usage:
 * - [fromRaw] for full SLM output (possibly including code fences and commentary).
 * - [extractFollowupQuestion] when only the first follow-up question is needed.
 * - [extractScore] to pull a coarse 0..100 score from the same output.
 *
 * Contract-friendly additions:
 * - [extractFollowupTarget] for "followup_target" fields.
 * - [extractFollowupNeeded] for "followup_needed" boolean fields.
 * - [extractWeakness] for "weakness" / missing-point fields.
 */
object FollowupExtractor {

    /* --------------------------------------------------------------------- */
    /* Configuration                                                         */
    /* --------------------------------------------------------------------- */

    /** Regex used to normalize key separators (space, underscore, dashes, zero-width) into a single dash. */
    private val KEY_SEP_REGEX =
        Regex("""[\s_\u200B\u200C\u200D\u2060\u2010-\u2015]+""")

    /** Trailing question marks (ASCII or full-width) to be coalesced to exactly one. */
    private val TRAILING_QUESTION_REGEX = Regex("[?？]+$")

    /** Matches integers 0..100; last match in the text is used for fallback scoring. */
    private val NUMBER_0_TO_100_REGEX = Regex("""\b(?:100|[1-9]?\d)\b""")

    /** Avoid accidentally capturing gigantic prompt blobs as a "question". */
    private const val MAX_QUESTION_CHARS: Int = 220

    /** Soft guard: reject extremely long strings even if found under question-ish keys. */
    private const val HARD_REJECT_QUESTION_CHARS: Int = 2000

    /** Soft cap for non-question contract fields (target/weakness). */
    private const val MAX_CONTRACT_FIELD_CHARS: Int = 600

    /**
     * Normalize field keys for matching:
     * - Insert separators for camelCase and acronym boundaries.
     * - Lowercase the entire string (Locale.US).
     * - Convert any run of [space/_/unicode-dash/zero-width] to a single '-'.
     * - Trim leading/trailing dashes.
     */
    private fun normKey(k: String): String =
        decamel(k)
            .lowercase(Locale.US)
            .replace(KEY_SEP_REGEX, "-")
            .trim('-')

    /**
     * Insert separators into camelCase / acronym boundaries.
     *
     * Rules (simple and robust):
     * - lower/digit -> Upper inserts '-'
     * - Upper + Upper + lower: split before the last Upper to keep acronyms together
     *   (e.g. "JSONScore" -> "JSON-Score")
     */
    private fun decamel(s: String): String {
        if (s.isEmpty()) return s

        val out = StringBuilder(s.length + 8)
        val n = s.length

        fun isUpper(c: Char) = c in 'A'..'Z'
        fun isLower(c: Char) = c in 'a'..'z'
        fun isDigit(c: Char) = c in '0'..'9'

        for (i in 0 until n) {
            val c = s[i]
            val prev = if (i > 0) s[i - 1] else '\u0000'
            val next = if (i + 1 < n) s[i + 1] else '\u0000'

            val boundary1 = (isUpper(c) && (isLower(prev) || isDigit(prev)))
            val boundary2 = (isUpper(prev) && isUpper(c) && isLower(next))

            if (i > 0 && (boundary1 || boundary2)) out.append('-')
            out.append(c)
        }
        return out.toString()
    }

    /** Raw followup-like keys we consider as primary containers. */
    private val FOLLOWUP_KEYS_RAW: List<String> = listOf(
        // Singular
        "followup question",
        "follow-up question",
        "follow_up_question",
        "followUpQuestion",
        "followupQuestion",
        "follow_up",
        "followup",

        // Plural / list containers
        "followups",
        "follow-ups",
        "followup-questions",
        "follow-up-questions",
        "follow_up_questions",
        "followUpQuestions",
        "followupQuestions",
        "follow-up-q",
        "next-questions",
        "suggested-questions",
        "suggestedQuestions",
        "follow_up_candidates",
        "followup_candidates",
        "followUpCandidates",
        "followupCandidates"
        // NOTE: We intentionally do NOT include a broad "questions" key here
        // to avoid accidentally treating original question lists as follow-ups.
    )

    /** Normalized followup-like keys we consider as primary containers. */
    private val FOLLOWUP_KEYS_NORM: Set<String> =
        FOLLOWUP_KEYS_RAW.map(::normKey).toSet()

    /** Field candidates inside an object that may carry question text. */
    private val QUESTION_FIELD_CANDIDATES: List<String> = listOf(
        "followup question",
        "follow-up question",
        "follow_up_question",
        "followUpQuestion",
        "question",
        "text",
        "q",
        "content",
        "title",
        "prompt",
        "message",
        "body",
        "value"
    )

    /** Normalized set for strict key equality checks. */
    private val QUESTION_FIELDS_NORM: Set<String> =
        QUESTION_FIELD_CANDIDATES.map(::normKey).toSet()

    /** Score keys we accept (normalized). */
    private val SCORE_KEYS_NORM: Set<String> = listOf(
        "overall_score",
        "overallScore",
        "overall-score",
        "evaluation_score",
        "evaluationScore",
        "eval_score",
        "evalScore",
        "rating",
        "confidence",
        "score"
    ).map(::normKey).toSet()

    /** Follow-up target keys (normalized). */
    private val FOLLOWUP_TARGET_KEYS_NORM: Set<String> = listOf(
        "followup_target",
        "followUpTarget",
        "follow-up-target",
        "followupTarget",
        "follow_up_target",
        "followup target",
        "followup-topic",
        "followup_topic",
        "followUpTopic",
        "missing_point",
        "missingPoint",
        "key_gap",
        "keyGap"
    ).map(::normKey).toSet()

    /** Follow-up needed boolean keys (normalized). */
    private val FOLLOWUP_NEEDED_KEYS_NORM: Set<String> = listOf(
        "followup_needed",
        "followUpNeeded",
        "follow-up-needed",
        "followupNeeded",
        "follow_up_needed",
        "needs_followup",
        "needsFollowup",
        "need_followup",
        "needFollowup",
        "needs_clarification",
        "needsClarification",
        "clarification_needed",
        "clarificationNeeded",
        "requires_clarification",
        "requiresClarification"
    ).map(::normKey).toSet()

    /** Weakness keys (normalized). */
    private val WEAKNESS_KEYS_NORM: Set<String> = listOf(
        "weakness",
        "weaknesses",
        "missing",
        "missing_info",
        "missingInfo",
        "what_is_missing",
        "whatIsMissing",
        "gap",
        "unclear",
        "not_clear",
        "notClear"
    ).map(::normKey).toSet()

    /* --------------------------------------------------------------------- */
    /* Public API                                                            */
    /* --------------------------------------------------------------------- */

    /**
     * Extract follow-up questions from free-form [raw] text.
     *
     * The text may contain one or more JSON fragments. All fragments are parsed,
     * traversed, and candidate questions are collected and deduplicated in
     * encounter order, capped to [max] items.
     *
     * Fallback:
     * - If no JSON yields a question, plain text is split into sentence-like
     *   chunks and any chunk ending with '?' or '？' is treated as a question.
     */
    @JvmStatic
    fun fromRaw(raw: String, max: Int = Int.MAX_VALUE): List<String> {
        if (raw.isBlank() || max <= 0) return emptyList()

        val out = LinkedHashSet<String>()

        // Extract JSON from:
        // (1) fenced blocks, (2) the whole raw string.
        val candidates = buildList {
            addAll(extractCodeFenceBodies(raw))
            add(raw)
        }

        for (cand in candidates) {
            if (out.size >= max) break
            val fragments = extractJsonFragments(cand)
            for (frag in fragments) {
                if (out.size >= max) break
                collect(frag, out, max)
            }
        }

        // Plain text fallback: if nothing found via JSON, try sentence-level heuristic.
        if (out.isEmpty()) {
            for (piece in splitSentenceLike(raw)) {
                if (out.size >= max) break
                val trimmed = piece.trim()
                if (trimmed.endsWith("?") || trimmed.endsWith("？")) {
                    addIfMeaningful(trimmed, out, max)
                }
            }
        }

        return out.toList().take(max)
    }

    /**
     * Extract follow-up questions from a JSON-like root node or list of nodes.
     *
     * Accepted values:
     * - A single [JSONObject], [JSONArray], or [String].
     * - A [List] where each element can be any of the above.
     *
     * Result:
     * - Deduplicated questions (encounter order), limited to [max].
     */
    @JvmStatic
    fun fromJsonAny(any: Any, max: Int = Int.MAX_VALUE): List<String> {
        if (max <= 0) return emptyList()

        val out = LinkedHashSet<String>()
        when (any) {
            is List<*> -> for (elem in any) {
                if (elem != null && out.size < max) collect(elem, out, max)
            }
            else -> collect(any, out, max)
        }
        return out.toList().take(max)
    }

    /**
     * Convenience: return a likely follow-up question from [rawText], or null if none.
     *
     * Preference:
     * - Prefer entries that contain '?' or '？' (question-like).
     * - Otherwise return the first extracted candidate.
     */
    @JvmStatic
    fun extractFollowupQuestion(rawText: String): String? {
        val list = runCatching { fromRaw(rawText, max = 6) }.getOrNull().orEmpty()
        val q = list.firstOrNull { it.contains('?') || it.contains('？') } ?: list.firstOrNull()
        return q?.takeIf { it.isNotBlank() }
    }

    /** Fallback: prefer labeled score patterns like "score: 72" or "score: 0.82". */
    private val LABELED_SCORE_REGEX =
        Regex("""(?i)\b(?:overall[_\s-]?score|evaluation[_\s-]?score|eval[_\s-]?score|rating|confidence|score)\b\s*[:=]\s*(\d+(?:\.\d+)?)\b""")

    @JvmStatic
    fun extractScore(text: String): Int? {
        val candidates = buildList {
            addAll(extractCodeFenceBodies(text))
            add(text)
        }

        for (cand in candidates) {
            val fragments = extractJsonFragments(cand)
            for (frag in fragments) {
                val v = when (frag) {
                    is JSONObject -> findScoreRecursive(frag)
                    is JSONArray -> findScoreRecursive(frag)
                    else -> null
                }
                if (v != null) return clamp0to100(v)
            }
        }

        // Plain-text fallback (1): labeled pattern first (e.g., "score: 42" or "score: 0.82")
        val labeledLast = LABELED_SCORE_REGEX.findAll(text).lastOrNull()
            ?.groupValues
            ?.getOrNull(1)

        if (!labeledLast.isNullOrBlank()) {
            // Reuse the same scaling rules (0<d<1 => *100).
            val scaled = parseScoreTo0to100OrNull(labeledLast)
            if (scaled != null) return clamp0to100(scaled)
        }

        // Plain-text fallback (2): last integer 0..100 anywhere (best-effort)
        val lastMatch = NUMBER_0_TO_100_REGEX
            .findAll(text)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(0)
            ?.toIntOrNull()

        return lastMatch?.let(::clamp0to100)
    }

    /**
     * Extract "followup_target" (or equivalent) as a short string from [text].
     *
     * Strategy:
     * - JSON-first: find first value whose key matches known followup_target keys.
     *   Supports String or Array-of-Strings or JSONObject wrappers.
     * - Plain-text fallback: look for a simple "followup_target: ..." line.
     */
    @JvmStatic
    fun extractFollowupTarget(text: String): String? {
        val candidates = buildList {
            addAll(extractCodeFenceBodies(text))
            add(text)
        }

        for (cand in candidates) {
            val fragments = extractJsonFragments(cand)
            for (frag in fragments) {
                val v = when (frag) {
                    is JSONObject -> findTextByKeysRecursive(frag, FOLLOWUP_TARGET_KEYS_NORM)
                    is JSONArray -> findTextByKeysRecursive(frag, FOLLOWUP_TARGET_KEYS_NORM)
                    else -> null
                }
                if (!v.isNullOrBlank()) return v.trim().take(MAX_CONTRACT_FIELD_CHARS)
            }
        }

        // Plain-text fallback (very light)
        val line = text.lineSequence().firstOrNull { it.contains("followup_target", ignoreCase = true) }
            ?: return null
        val idx = line.indexOf(':').takeIf { it >= 0 } ?: line.indexOf('=').takeIf { it >= 0 } ?: return null
        val rhs = line.substring(idx + 1).trim()
        return rhs.takeIf { it.isNotBlank() }?.take(MAX_CONTRACT_FIELD_CHARS)
    }

    /**
     * Extract "followup_needed" (or equivalent) boolean from [text].
     *
     * Strategy:
     * - JSON-first: find first boolean-ish value whose key matches known followup_needed keys.
     * - Plain-text fallback: look for "followup_needed: true/false".
     */
    @JvmStatic
    fun extractFollowupNeeded(text: String): Boolean? {
        val candidates = buildList {
            addAll(extractCodeFenceBodies(text))
            add(text)
        }

        for (cand in candidates) {
            val fragments = extractJsonFragments(cand)
            for (frag in fragments) {
                val v = when (frag) {
                    is JSONObject -> findBooleanByKeysRecursive(frag, FOLLOWUP_NEEDED_KEYS_NORM)
                    is JSONArray -> findBooleanByKeysRecursive(frag, FOLLOWUP_NEEDED_KEYS_NORM)
                    else -> null
                }
                if (v != null) return v
            }
        }

        // Plain-text fallback (very light)
        val line = text.lineSequence().firstOrNull { it.contains("followup_needed", ignoreCase = true) }
            ?: return null
        val idx = line.indexOf(':').takeIf { it >= 0 } ?: line.indexOf('=').takeIf { it >= 0 } ?: return null
        val rhs = line.substring(idx + 1).trim()
        return parseBooleanOrNull(rhs)
    }

    /**
     * Extract "weakness" (or equivalent) from [text].
     *
     * Strategy:
     * - JSON-first: find first value whose key matches known weakness keys.
     *   Supports String or Array-of-Strings or JSONObject wrappers.
     * - Plain-text fallback: look for a "weakness: ..." line.
     */
    @JvmStatic
    fun extractWeakness(text: String): String? {
        val candidates = buildList {
            addAll(extractCodeFenceBodies(text))
            add(text)
        }

        for (cand in candidates) {
            val fragments = extractJsonFragments(cand)
            for (frag in fragments) {
                val v = when (frag) {
                    is JSONObject -> findTextByKeysRecursive(frag, WEAKNESS_KEYS_NORM)
                    is JSONArray -> findTextByKeysRecursive(frag, WEAKNESS_KEYS_NORM)
                    else -> null
                }
                if (!v.isNullOrBlank()) return v.trim().take(MAX_CONTRACT_FIELD_CHARS)
            }
        }

        // Plain-text fallback (very light)
        val line = text.lineSequence().firstOrNull { it.contains("weakness", ignoreCase = true) }
            ?: return null
        val idx = line.indexOf(':').takeIf { it >= 0 } ?: line.indexOf('=').takeIf { it >= 0 } ?: return null
        val rhs = line.substring(idx + 1).trim()
        return rhs.takeIf { it.isNotBlank() }?.take(MAX_CONTRACT_FIELD_CHARS)
    }

    /* --------------------------------------------------------------------- */
    /* Internal helpers                                                      */
    /* --------------------------------------------------------------------- */

    private fun clamp0to100(x: Int): Int = max(0, min(100, x))

    /**
     * Depth-first traversal collecting candidate questions into [out].
     *
     * Behavior:
     * - For JSON arrays:
     *   - Strings are taken directly as candidates.
     *   - JSONObject elements are inspected for question-like fields, then recursed.
     *   - JSONArray elements are recursed.
     * - For JSON objects:
     *   1) Process followup-like containers first (e.g., "followup_questions").
     *   2) Traverse all fields and:
     *      - Recurse into nested objects/arrays.
     *      - Accept strings from question-like fields ("question", "text", etc.).
     * - For plain strings: trim and add if non-empty.
     */
    private fun collect(node: Any?, out: MutableSet<String>, max: Int) {
        if (node == null || out.size >= max) return

        when (node) {
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    if (out.size >= max) break
                    val v = node.opt(i)
                    when (v) {
                        is String -> addIfMeaningful(v, out, max)
                        is JSONObject -> {
                            extractQuestionField(v)?.let { addIfMeaningful(it, out, max) }
                            collect(v, out, max)
                        }
                        is JSONArray -> collect(v, out, max)
                    }
                }
            }

            is JSONObject -> {
                val keys = collectKeys(node)

                // (1) Preferentially process followup-like keys (normalized).
                for (key in keys) {
                    if (out.size >= max) break
                    if (FOLLOWUP_KEYS_NORM.contains(normKey(key))) {
                        when (val value = node.opt(key)) {
                            is String -> addIfMeaningful(value, out, max)
                            is JSONArray -> collect(value, out, max)
                            is JSONObject -> {
                                extractQuestionField(value)?.let { addIfMeaningful(it, out, max) }
                                collect(value, out, max)
                            }
                        }
                    }
                }
                if (out.size >= max) return

                // (2) Traverse all fields; pick strings in question-like fields; recurse into nested structures.
                for (k in keys) {
                    if (out.size >= max) break
                    when (val v = node.opt(k)) {
                        is JSONArray, is JSONObject -> collect(v, out, max)
                        is String -> {
                            val kn = normKey(k)
                            val looksLikeQuestionField =
                                kn in QUESTION_FIELDS_NORM ||
                                        kn == "question" ||
                                        kn.endsWith("-question") ||
                                        kn.endsWith("-q") ||
                                        kn.contains("follow-up") ||
                                        (kn.contains("followup") && kn.contains("question"))

                            if (looksLikeQuestionField) {
                                addIfMeaningful(v, out, max)
                            }
                        }
                    }
                }
            }

            is String -> addIfMeaningful(node, out, max)
        }
    }

    private fun collectKeys(obj: JSONObject): List<String> {
        val out = ArrayList<String>()
        val it = obj.keys()
        while (it.hasNext()) out.add(it.next())
        return out
    }

    /**
     * Try to extract a question string from common fields in [obj].
     *
     * Strategy:
     * - Build a normalized key→value map using [normKey].
     * - First, look up exact normalized candidates from [QUESTION_FIELD_CANDIDATES].
     * - Then, fall back to any field whose normalized name contains "question".
     */
    private fun extractQuestionField(obj: JSONObject): String? {
        val normalizedMap = mutableMapOf<String, Any?>()
        val itAll = obj.keys()
        while (itAll.hasNext()) {
            val k = itAll.next()
            normalizedMap[normKey(k)] = obj.opt(k)
        }

        // Strong match: exact normalized candidate keys
        for (candidate in QUESTION_FIELD_CANDIDATES) {
            val v = normalizedMap[normKey(candidate)]
            if (v is String && v.isNotBlank()) return v.trim()
        }

        // Weak match: any field whose normalized name contains "question"
        for ((kNorm, v) in normalizedMap) {
            if (kNorm.contains("question") && v is String && v.isNotBlank()) return v.trim()
        }
        return null
    }

    /**
     * Add a normalized non-empty string to [out] if still under [max].
     *
     * Light heuristics:
     * - Trim and reject empty.
     * - Reject extremely long strings (likely prompt/essay blobs).
     * - Cap length to avoid huge blobs (keeps UX stable).
     * - Coalesce trailing question marks to exactly one (? or ？).
     */
    private fun addIfMeaningful(s: String, out: MutableSet<String>, max: Int) {
        if (out.size >= max) return

        val t0 = stripWrappingQuotes(s.trim())
        if (t0.isEmpty()) return
        if (t0.length >= HARD_REJECT_QUESTION_CHARS) return

        // Prevent prompt-size strings from being treated as questions.
        val t = if (t0.length > MAX_QUESTION_CHARS) t0.take(MAX_QUESTION_CHARS).trimEnd() else t0

        val keep = looksLikeQuestionString(t) || t.contains('?') || t.contains('？')
        if (!keep && t.length < 8) return

        val normalized = TRAILING_QUESTION_REGEX.replace(t) { m ->
            if (m.value.contains('？')) "？" else "?"
        }

        out.add(normalized)
    }

    private fun stripWrappingQuotes(s: String): String {
        val t = s.trim()
        if (t.length >= 2) {
            val a = t.first()
            val b = t.last()
            if (
                (a == '"' && b == '"') ||
                (a == '“' && b == '”') ||
                (a == '『' && b == '』') ||
                (a == '「' && b == '」')
            ) {
                return t.substring(1, t.length - 1).trim()
            }
        }
        return t
    }

    /**
     * Extremely lightweight question-likeness heuristic.
     * This avoids capturing random "message/body" blobs as follow-ups.
     */
    private fun looksLikeQuestionString(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        if (t.contains('?') || t.contains('？')) return true

        val lower = t.lowercase(Locale.US)
        val starters = listOf(
            "what", "why", "how", "when", "where", "which", "who",
            "is", "are", "do", "did", "does", "can", "could", "would", "should", "may", "might"
        )
        if (starters.any { lower.startsWith("$it ") }) return true

        val jpStarters = listOf("なぜ", "どう", "いつ", "どこ", "どれ", "どの", "だれ", "何", "どんな")
        if (jpStarters.any { t.startsWith(it) }) return true
        if (t.endsWith("ですか") || t.endsWith("ますか") || t.endsWith("でしょうか") || t.endsWith("か")) return true

        return false
    }

    /* ----------------------- Score (recursive JSON) ----------------------- */

    private fun findScoreRecursive(obj: JSONObject): Int? {
        // 1) Direct keys on this object (normalized).
        val norm = mutableMapOf<String, Any?>()
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            norm[normKey(k)] = obj.opt(k)
        }

        val ordered = listOf(
            "overall_score",
            "overallScore",
            "overall-score",
            "evaluation_score",
            "evaluationScore",
            "eval_score",
            "evalScore",
            "rating",
            "confidence",
            "score"
        ).map(::normKey)

        for (k in ordered) {
            if (!SCORE_KEYS_NORM.contains(k)) continue
            norm[k]?.let { v ->
                parseScoreTo0to100OrNull(v)?.let { n -> return n }
            }
        }

        // 2) Recurse into child values.
        val it2 = obj.keys()
        while (it2.hasNext()) {
            val k = it2.next()
            when (val v = obj.opt(k)) {
                is JSONObject -> findScoreRecursive(v)?.let { return it }
                is JSONArray -> findScoreRecursive(v)?.let { return it }
            }
        }
        return null
    }

    private fun findScoreRecursive(arr: JSONArray): Int? {
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is JSONObject -> findScoreRecursive(v)?.let { return it }
                is JSONArray -> findScoreRecursive(v)?.let { return it }
            }
        }
        return null
    }

    /**
     * Parse score-like values robustly:
     * - Number: supports 0..1 (scaled to 0..100) only when it's a true fraction (0<d<1).
     * - String: parses double similarly.
     *
     * IMPORTANT:
     * - Previously, "1" matched 0..1 and became 100. We only scale strict fractions now.
     */
    private fun parseScoreTo0to100OrNull(v: Any?): Int? {
        val d: Double = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull() ?: return null
            else -> return null
        }

        if (d.isNaN()) return null

        // Scale only strict fractions (0 < d < 1). This prevents 1 -> 100.
        val scaled = if (d > 0.0 && d < 1.0) d * 100.0 else d
        return clamp0to100(scaled.roundToInt())
    }

    /* ----------------------- Contract fields (recursive JSON) ------------- */

    private fun findTextByKeysRecursive(obj: JSONObject, keysNorm: Set<String>): String? {
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            val kn = normKey(k)
            if (kn !in keysNorm) continue

            val v = obj.opt(k)
            val s = extractTextValueBestEffort(v)
            if (!s.isNullOrBlank()) return s.take(MAX_CONTRACT_FIELD_CHARS)
        }
        val it2 = obj.keys()
        while (it2.hasNext()) {
            val k = it2.next()
            when (val v = obj.opt(k)) {
                is JSONObject -> findTextByKeysRecursive(v, keysNorm)?.let { return it }
                is JSONArray -> findTextByKeysRecursive(v, keysNorm)?.let { return it }
            }
        }
        return null
    }

    private fun findTextByKeysRecursive(arr: JSONArray, keysNorm: Set<String>): String? {
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is JSONObject -> findTextByKeysRecursive(v, keysNorm)?.let { return it }
                is JSONArray -> findTextByKeysRecursive(v, keysNorm)?.let { return it }
            }
        }
        return null
    }

    /**
     * Extract a textual value for contract fields (target/weakness/etc).
     *
     * IMPORTANT:
     * - This must NOT reuse question-oriented heuristics.
     * - Some models wrap contract fields in objects like {"text":"...","lang":"en"}.
     */
    private fun extractTextValueBestEffort(v: Any?): String? {
        return when (v) {
            is String -> v.trim().takeIf { it.isNotBlank() }?.take(MAX_CONTRACT_FIELD_CHARS)

            is JSONArray -> {
                val parts = ArrayList<String>()
                for (i in 0 until v.length()) {
                    val x = v.opt(i)
                    if (x is String) {
                        val t = x.trim()
                        if (t.isNotBlank()) parts.add(t)
                    }
                }
                parts.joinToString(separator = "; ")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.take(MAX_CONTRACT_FIELD_CHARS)
            }

            is JSONObject -> extractStringFromObjectBestEffort(v)?.take(MAX_CONTRACT_FIELD_CHARS)

            else -> null
        }
    }

    /**
     * Extract a representative string from a JSONObject wrapper.
     *
     * Priority:
     * 1) Common scalar fields: text/value/content/message/reason/summary/weakness/target
     * 2) If exactly one non-blank String field exists, return it
     * 3) Otherwise null
     */
    private fun extractStringFromObjectBestEffort(obj: JSONObject): String? {
        val preferredKeys = listOf(
            "text",
            "value",
            "content",
            "message",
            "reason",
            "summary",
            "weakness",
            "target",
            "followup_target",
            "followUpTarget"
        ).map(::normKey)

        val normMap = LinkedHashMap<String, Any?>()
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            normMap[normKey(k)] = obj.opt(k)
        }

        for (k in preferredKeys) {
            val v = normMap[k]
            if (v is String) {
                val t = v.trim()
                if (t.isNotBlank() && t.length < HARD_REJECT_QUESTION_CHARS) return t
            }
        }

        val strings = normMap.values
            .mapNotNull { it as? String }
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length < HARD_REJECT_QUESTION_CHARS }

        if (strings.size == 1) return strings.first()

        // Last resort: if it has a nested string array under preferred scalar key, join it.
        for (k in preferredKeys) {
            val v = normMap[k]
            if (v is JSONArray) {
                val parts = ArrayList<String>()
                for (i in 0 until v.length()) {
                    val x = v.opt(i)
                    if (x is String) {
                        val t = x.trim()
                        if (t.isNotBlank()) parts.add(t)
                    }
                }
                val joined = parts.joinToString("; ").trim()
                if (joined.isNotBlank()) return joined
            }
        }

        return null
    }

    private fun findBooleanByKeysRecursive(obj: JSONObject, keysNorm: Set<String>): Boolean? {
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            val kn = normKey(k)
            val v = obj.opt(k)
            if (kn in keysNorm) {
                parseBooleanOrNull(v)?.let { return it }
            }
        }
        val it2 = obj.keys()
        while (it2.hasNext()) {
            val k = it2.next()
            when (val v = obj.opt(k)) {
                is JSONObject -> findBooleanByKeysRecursive(v, keysNorm)?.let { return it }
                is JSONArray -> findBooleanByKeysRecursive(v, keysNorm)?.let { return it }
            }
        }
        return null
    }

    private fun findBooleanByKeysRecursive(arr: JSONArray, keysNorm: Set<String>): Boolean? {
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is JSONObject -> findBooleanByKeysRecursive(v, keysNorm)?.let { return it }
                is JSONArray -> findBooleanByKeysRecursive(v, keysNorm)?.let { return it }
            }
        }
        return null
    }

    private fun parseBooleanOrNull(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> when (v.toInt()) {
            1 -> true
            0 -> false
            else -> null
        }
        is String -> parseBooleanOrNull(v)
        else -> null
    }

    private fun parseBooleanOrNull(s: String): Boolean? {
        val t = s.trim().lowercase(Locale.US)
        return when (t) {
            "true", "t", "yes", "y", "1" -> true
            "false", "f", "no", "n", "0" -> false
            else -> null
        }
    }

    /* ----------------------- Plain-text sentence split -------------------- */

    /**
     * A tiny sentence-like splitter used only for non-JSON fallback.
     *
     * This avoids zero-width regex split pitfalls and keeps punctuation
     * attached to the fragment.
     */
    private fun splitSentenceLike(raw: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()

        fun flush() {
            val t = sb.toString().trim()
            if (t.isNotEmpty()) out.add(t)
            sb.setLength(0)
        }

        for (ch in raw) {
            when (ch) {
                '\r', '\n' -> flush()
                '。', '．', '.', '!', '！', '?', '？' -> {
                    sb.append(ch)
                    flush()
                }
                else -> sb.append(ch)
            }
        }
        flush()
        return out
    }

    /* ----------------------- JSON fragment extraction --------------------- */

    /**
     * Extract all code-fence bodies (```...``` or ~~~...~~~) anywhere in the raw text.
     *
     * Supports:
     * - Standard multi-line fences: ```json\n{...}\n```
     * - Inline fences: ```json {...}```
     * - Alternative fences: ~~~json\n{...}\n~~~
     */
    private fun extractCodeFenceBodies(raw: String): List<String> {
        val reBacktick = Regex("""```[A-Za-z0-9_-]*\s*([\s\S]*?)```""")
        val reTilde = Regex("""~~~[A-Za-z0-9_-]*\s*([\s\S]*?)~~~""")
        return buildList {
            addAll(reBacktick.findAll(raw).map { it.groupValues[1].trim() }.toList())
            addAll(reTilde.findAll(raw).map { it.groupValues[1].trim() }.toList())
        }
    }

    /**
     * Extract JSON fragments embedded in [raw].
     *
     * Behavior:
     * - Attempts whole-string parse first; if it succeeds, returns a single fragment.
     * - Otherwise scans for balanced '{...}' / '[...]' regions while:
     *   - Respecting string literals (global scan + fragment scan).
     *   - Skipping escaped quotes.
     * - If a mismatched closing bracket is found, the fragment is treated as invalid and discarded.
     */
    private fun extractJsonFragments(raw: String): List<Any> {
        val s0 = raw.trim()
        val fragments = mutableListOf<Any>()

        // Quick path: whole string is a single JSON value.
        parseAny(s0)?.let {
            fragments.add(it)
            return fragments
        }

        val n = s0.length
        var i = 0
        var inString = false

        while (i < n) {
            val ch = s0[i]

            // Track outer string context to avoid starting fragments inside quoted text.
            if (ch == '"' && !isEscapedQuote(s0, i)) {
                inString = !inString
                i++
                continue
            }
            if (inString) {
                i++
                continue
            }

            if (ch == '{' || ch == '[') {
                val start = i
                val stack = ArrayDeque<Char>()
                stack.addLast(ch)

                var innerInString = false
                var invalid = false

                i++ // move past opener
                while (i < n && stack.isNotEmpty()) {
                    val c = s0[i]
                    if (innerInString) {
                        if (c == '\\') {
                            i += if (i + 1 < n) 2 else 1
                            continue
                        } else if (c == '"') {
                            innerInString = false
                        }
                    } else {
                        when (c) {
                            '"' -> innerInString = true
                            '{' -> stack.addLast('{')
                            '[' -> stack.addLast('[')
                            '}' -> {
                                val top = stack.lastOrNull()
                                if (top == '{') stack.removeLast() else {
                                    invalid = true
                                    break
                                }
                            }
                            ']' -> {
                                val top = stack.lastOrNull()
                                if (top == '[') stack.removeLast() else {
                                    invalid = true
                                    break
                                }
                            }
                        }
                    }
                    i++
                }

                if (invalid || stack.isNotEmpty()) {
                    i = start + 1
                    continue
                }

                val endIdx = i // i points right after the closing bracket
                if (endIdx <= n) {
                    val frag = s0.substring(start, endIdx)
                    parseAny(frag)?.let { fragments.add(it) }
                    continue
                }
            }

            i++
        }

        return fragments
    }

    /**
     * Returns true if the quote at [idx] is escaped by an odd number of backslashes.
     */
    private fun isEscapedQuote(s: String, idx: Int): Boolean {
        var bs = 0
        var i = idx - 1
        while (i >= 0 && s[i] == '\\') {
            bs++
            i--
        }
        return (bs % 2) == 1
    }

    /** Try to parse [s] into a JSONObject or JSONArray; returns null on failure. */
    private fun parseAny(s: String): Any? = try {
        val t = s.trim()
        when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> JSONArray(t)
            else -> null
        }
    } catch (_: Throwable) {
        null
    }
}