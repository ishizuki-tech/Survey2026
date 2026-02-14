/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: FollowupExtractor.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
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
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.slm

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

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

    /**
     * Normalize field keys for matching:
     * - Insert separators for camelCase and acronym boundaries.
     * - Lowercase the entire string (Locale.US).
     * - Convert any run of [space/_/unicode-dash/zero-width] to a single '-'.
     * - Trim leading/trailing dashes.
     *
     * Examples:
     * - "followup question"      -> "followup-question"
     * - "follow_up_question"     -> "follow-up-question"
     * - "Follow-Up–Question"     -> "follow-up-question"
     * - "followUpQuestion"       -> "follow-up-question"
     * - "overallScore"           -> "overall-score"
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
     * - Upper + Upper + lower: split before the last Upper to keep acronyms together (e.g. "JSONScore" -> "JSON-Score")
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

        // Plural / list containers
        "followup",
        "follow-up",
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
        "suggestedQuestions"
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

    /** Convenience: return the first follow-up question found in [rawText], or null if none. */
    @JvmStatic
    fun extractFollowupQuestion(rawText: String): String? =
        runCatching { fromRaw(rawText, max = 3).firstOrNull() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * Extract an integer score in the range 0..100 from [text].
     *
     * Strategy:
     *  (1) Parse JSON fragments and recursively look for "overall_score"/"overallScore"/"score"
     *      keys (numeric or numeric-string). The first valid key in document order wins.
     *  (2) If not found, fall back to the last integer 0..100 in the raw text.
     */
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

        // Plain-text fallback (last integer 0..100)
        val lastMatch = NUMBER_0_TO_100_REGEX
            .findAll(text)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(0)
            ?.toIntOrNull()

        return lastMatch?.let(::clamp0to100)
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
                // (1) Preferentially process followup-like keys (normalized).
                val iter1 = node.keys()
                while (iter1.hasNext() && out.size < max) {
                    val key = iter1.next()
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
                val iter2 = node.keys()
                while (iter2.hasNext() && out.size < max) {
                    val k = iter2.next()
                    val v = node.opt(k)
                    when (v) {
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
     * - Cap length to avoid huge blobs (keeps UX stable).
     * - Coalesce trailing question marks to exactly one (? or ？).
     */
    private fun addIfMeaningful(s: String, out: MutableSet<String>, max: Int) {
        if (out.size >= max) return

        val t0 = s.trim()
        if (t0.isEmpty()) return

        // Prevent prompt-size strings from being treated as questions.
        val t = if (t0.length > MAX_QUESTION_CHARS) t0.take(MAX_QUESTION_CHARS).trimEnd() else t0

        val normalized = TRAILING_QUESTION_REGEX.replace(t) { m ->
            // Preserve the type of question mark the model used
            if (m.value.contains('？')) "？" else "?"
        }

        out.add(normalized)
    }

    /* ----------------------- Score (recursive JSON) ----------------------- */

    /** Allowed score keys (normalized). */
    private val SCORE_KEYS = setOf(
        "overall-score",
        "overallscore", // extra tolerance (in case separators were lost upstream)
        "score"
    )

    private fun findScoreRecursive(obj: JSONObject): Int? {
        // 1) Direct keys on this object (normalized).
        val norm = mutableMapOf<String, Any?>()
        val it = obj.keys()
        while (it.hasNext()) {
            val k = it.next()
            norm[normKey(k)] = obj.opt(k)
        }
        for (k in SCORE_KEYS) {
            norm[k]?.let { v ->
                parseNumberOrNull(v)?.let { n -> return clamp0to100(n) }
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

    private fun parseNumberOrNull(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toDoubleOrNull()?.toInt()
        else -> null
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
                '。', '．', '!', '！', '?', '？' -> {
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
     * Extract all code-fence bodies (```...```) anywhere in the raw text.
     *
     * This catches common model outputs like:
     *   Some text
     *   ```json
     *   {...}
     *   ```
     *   More text
     *
     * Note: Accepts optional newline before closing fence to tolerate outputs without trailing newline.
     */
    private fun extractCodeFenceBodies(raw: String): List<String> {
        val re = Regex("""```[A-Za-z0-9_-]*\s*\n([\s\S]*?)\n?```""")
        return re.findAll(raw).map { it.groupValues[1].trim() }.toList()
    }

    /**
     * Extract JSON fragments embedded in [raw].
     *
     * Behavior:
     * - Attempts whole-string parse first; if it succeeds, returns a single fragment.
     * - Otherwise scans for balanced '{...}' / '[...]' regions while:
     *   - Respecting string literals.
     *   - Skipping escaped quotes.
     */
    private fun extractJsonFragments(raw: String): List<Any> {
        val s0 = raw.trim()
        val fragments = mutableListOf<Any>()

        // Quick path: whole string is a single JSON value.
        parseAny(s0)?.let {
            fragments.add(it)
            return fragments
        }

        // Scan for multiple fragments with brace/bracket matching.
        val n = s0.length
        var i = 0
        while (i < n) {
            val ch = s0[i]
            if (ch == '{' || ch == '[') {
                val start = i
                val stack = ArrayDeque<Char>()
                stack.addLast(ch)
                var inString = false
                i++ // move past opener

                while (i < n && stack.isNotEmpty()) {
                    val c = s0[i]
                    if (inString) {
                        if (c == '\\') {
                            // Skip escaped char safely
                            i += if (i + 1 < n) 2 else 1
                            continue
                        } else if (c == '"') {
                            inString = false
                        }
                    } else {
                        when (c) {
                            '"' -> inString = true
                            '{' -> stack.addLast('{')
                            '[' -> stack.addLast('[')
                            '}' -> if (stack.isNotEmpty() && stack.last() == '{') {
                                stack.removeLast()
                            }

                            ']' -> if (stack.isNotEmpty() && stack.last() == '[') {
                                stack.removeLast()
                            }
                        }
                    }
                    i++
                }

                val endIdx = i
                if (stack.isEmpty() && endIdx <= n) {
                    val frag = s0.substring(start, endIdx)
                    parseAny(frag)?.let { fragments.add(it) }
                    continue
                } else {
                    // Unbalanced; skip this opener and move on.
                    i = start + 1
                }
            } else {
                i++
            }
        }
        return fragments
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
