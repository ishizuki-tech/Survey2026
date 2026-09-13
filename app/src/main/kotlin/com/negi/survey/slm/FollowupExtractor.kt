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
 *  Extract follow-up questions and evaluation contract fields from raw
 *  on-device SLM output.
 *
 *  Design goals:
 *   - JSON-first extraction with free-text fallback.
 *   - Multiple embedded JSONObject / JSONArray fragments.
 *   - Separator-insensitive and camelCase-aware key matching.
 *   - Avoid treating unrelated nested "question" fields as follow-ups.
 *   - Bound recursion / scanning for malformed or pathological model output.
 *   - Preserve the existing public API.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.slm

import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Utility for extracting follow-up questions and simple evaluation fields.
 *
 * Public API:
 * - [fromRaw]
 * - [fromJsonAny]
 * - [extractFollowupQuestion]
 * - [extractScore]
 * - [extractFollowupTarget]
 * - [extractFollowupNeeded]
 * - [extractWeakness]
 */
object FollowupExtractor {

    // =====================================================================
    // Configuration
    // =====================================================================

    /**
     * Normalize spaces, underscores, Unicode dashes, and zero-width separators.
     */
    private val KEY_SEP_REGEX =
        Regex("""[\s_\u200B\u200C\u200D\u2060\u2010-\u2015]+""")

    /** Collapse multiple trailing ASCII/full-width question marks. */
    private val TRAILING_QUESTION_REGEX =
        Regex("[?？]+$")

    /** Collapse ordinary horizontal whitespace inside extracted fields. */
    private val HORIZONTAL_SPACE_REGEX =
        Regex("""[ \t\u00A0]+""")

    /**
     * Unlabeled integer fallback.
     *
     * Guards against accidentally extracting:
     * - "-10" as 10
     * - "0.82" as 82
     * - part of a larger integer
     */
    private val NUMBER_0_TO_100_REGEX =
        Regex("""(?<![-+\d.])(?:100|[1-9]?\d)(?![\d.])""")

    /**
     * Labeled score fallback.
     *
     * A percent sign is accepted and interpreted explicitly by
     * [parseScoreTextTo0to100OrNull].
     */
    private val LABELED_SCORE_REGEX =
        Regex(
            """(?i)\b(?:overall[_\s-]?score|evaluation[_\s-]?score|eval[_\s-]?score|rating|confidence|score)\b""" +
                    """\s*[:=]\s*([-+]?\d+(?:\.\d+)?)\s*(%)?"""
        )

    /** Avoid treating large prompt/analysis blobs as questions. */
    private const val MAX_QUESTION_CHARS = 220

    /** Reject extremely large strings even under question-like keys. */
    private const val HARD_REJECT_QUESTION_CHARS = 2_000

    /** Minimum length for an explicit follow-up value that lacks question syntax. */
    private const val MIN_NONQUESTION_FOLLOWUP_CHARS = 8

    /** Cap target/weakness fields. */
    private const val MAX_CONTRACT_FIELD_CHARS = 600

    /** Guard recursive JSONObject / JSONArray traversal. */
    private const val MAX_JSON_DEPTH = 48

    /** Guard generated output containing thousands of JSON fragments. */
    private const val MAX_JSON_FRAGMENTS = 64

    /**
     * Guard scanning work.
     *
     * The current AI layer already uses a much smaller generation safety cap,
     * but this extractor is public and should remain bounded independently.
     */
    private const val MAX_SCAN_CHARS = 512_000

    /** Maximum code-fence bodies inspected from one output. */
    private const val MAX_CODE_FENCES = 16

    /**
     * Maximum array elements inspected at one recursion level.
     *
     * Normal model contracts should be tiny; this only protects pathological
     * generated JSON.
     */
    private const val MAX_ARRAY_ITEMS_PER_LEVEL = 2_048

    // =====================================================================
    // Key normalization
    // =====================================================================

    private fun normKey(key: String): String =
        decamel(key)
            .lowercase(Locale.US)
            .replace(KEY_SEP_REGEX, "-")
            .trim('-')

    /**
     * Insert separators at camelCase and acronym boundaries.
     *
     * Examples:
     * - followUpQuestion -> follow-Up-Question
     * - JSONScore -> JSON-Score
     */
    private fun decamel(source: String): String {
        if (source.isEmpty()) {
            return source
        }

        val out =
            StringBuilder(source.length + 8)

        fun isUpper(c: Char): Boolean =
            c in 'A'..'Z'

        fun isLower(c: Char): Boolean =
            c in 'a'..'z'

        fun isDigit(c: Char): Boolean =
            c in '0'..'9'

        for (index in source.indices) {
            val current = source[index]

            val previous =
                if (index > 0) {
                    source[index - 1]
                } else {
                    '\u0000'
                }

            val next =
                if (index + 1 < source.length) {
                    source[index + 1]
                } else {
                    '\u0000'
                }

            val lowerOrDigitToUpper =
                isUpper(current) &&
                        (isLower(previous) || isDigit(previous))

            val acronymBoundary =
                isUpper(previous) &&
                        isUpper(current) &&
                        isLower(next)

            if (
                index > 0 &&
                (lowerOrDigitToUpper || acronymBoundary)
            ) {
                out.append('-')
            }

            out.append(current)
        }

        return out.toString()
    }

    private fun normalizedPlainLabelKey(
        raw: String,
    ): String {
        val stripped =
            raw.trim()
                .trim('"', '\'', '`')
                .trim()

        return normKey(stripped)
    }

    // =====================================================================
    // Known keys
    // =====================================================================

    private val FOLLOWUP_KEYS_RAW: List<String> =
        listOf(
            "followup question",
            "follow-up question",
            "follow_up_question",
            "followUpQuestion",
            "followupQuestion",
            "follow_up",
            "followup",

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
            "followupCandidates",
        )

    private val FOLLOWUP_KEYS_NORM: Set<String> =
        FOLLOWUP_KEYS_RAW
            .map(::normKey)
            .toSet()

    /**
     * Question-bearing scalar fields accepted inside an explicit follow-up
     * container.
     *
     * The broader names (text/content/message/etc.) are intentionally used only
     * after the traversal has entered follow-up context.
     */
    private val QUESTION_FIELD_CANDIDATES: List<String> =
        listOf(
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
            "value",
        )

    private val QUESTION_FIELDS_NORM: Set<String> =
        QUESTION_FIELD_CANDIDATES
            .map(::normKey)
            .toSet()

    private val SCORE_KEYS_ORDERED_NORM: List<String> =
        listOf(
            "overall_score",
            "overallScore",
            "overall-score",
            "evaluation_score",
            "evaluationScore",
            "eval_score",
            "evalScore",
            "rating",
            "confidence",
            "score",
        )
            .map(::normKey)
            .distinct()

    private val SCORE_KEYS_NORM: Set<String> =
        SCORE_KEYS_ORDERED_NORM.toSet()

    private val FOLLOWUP_TARGET_KEYS_NORM: Set<String> =
        listOf(
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
            "keyGap",
        )
            .map(::normKey)
            .toSet()

    private val FOLLOWUP_NEEDED_KEYS_NORM: Set<String> =
        listOf(
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
            "requiresClarification",
        )
            .map(::normKey)
            .toSet()

    private val WEAKNESS_KEYS_NORM: Set<String> =
        listOf(
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
            "notClear",
        )
            .map(::normKey)
            .toSet()

    // =====================================================================
    // Public API
    // =====================================================================

    /**
     * Extract follow-up questions from free-form SLM output.
     *
     * Processing:
     * 1. Parse code-fenced JSON and raw embedded JSON.
     * 2. Traverse only follow-up contexts for broad question-field matching.
     * 3. If no JSON was parseable, fall back to question-like plain text.
     *
     * Important:
     * - If valid JSON exists but contains no follow-up question, we do NOT scan
     *   the raw JSON text again as prose. That prevents an unrelated
     *   `"question":"original survey question?"` field from becoming a
     *   follow-up through the text fallback.
     */
    @JvmStatic
    fun fromRaw(
        raw: String,
        max: Int = Int.MAX_VALUE,
    ): List<String> {
        if (
            raw.isBlank() ||
            max <= 0
        ) {
            return emptyList()
        }

        val collector =
            QuestionCollector(max)

        var parsedAnyJson = false

        for (candidate in jsonCandidateTexts(raw)) {
            if (collector.isFull()) {
                break
            }

            val fragments =
                extractJsonFragments(candidate)

            if (fragments.isNotEmpty()) {
                parsedAnyJson = true
            }

            for (fragment in fragments) {
                if (collector.isFull()) {
                    break
                }

                collectQuestions(
                    node = fragment,
                    collector = collector,
                    depth = 0,
                    followupContext = false,
                    allowGenericQuestionAtThisObject = true,
                )
            }
        }

        if (
            collector.isEmpty() &&
            !parsedAnyJson
        ) {
            val safeRaw =
                boundedScanText(raw)

            for (piece in splitSentenceLike(safeRaw)) {
                if (collector.isFull()) {
                    break
                }

                val trimmed =
                    piece.trim()

                if (
                    trimmed.endsWith("?") ||
                    trimmed.endsWith("？")
                ) {
                    collector.add(
                        value = trimmed,
                        explicitFollowupContext = false,
                    )
                }
            }
        }

        return collector.toList()
    }

    /**
     * Extract follow-up questions from already parsed JSON-like values.
     *
     * A direct String is treated as an explicit candidate because the caller
     * has already chosen to pass it as the extraction root.
     */
    @JvmStatic
    fun fromJsonAny(
        any: Any,
        max: Int = Int.MAX_VALUE,
    ): List<String> {
        if (max <= 0) {
            return emptyList()
        }

        val collector =
            QuestionCollector(max)

        when (any) {
            is List<*> -> {
                for (element in any) {
                    if (collector.isFull()) {
                        break
                    }

                    when (element) {
                        null,
                        JSONObject.NULL,
                            -> Unit

                        is String ->
                            collector.add(
                                value = element,
                                explicitFollowupContext = true,
                            )

                        else ->
                            collectQuestions(
                                node = element,
                                collector = collector,
                                depth = 0,
                                followupContext = false,
                                allowGenericQuestionAtThisObject = true,
                            )
                    }
                }
            }

            is String ->
                collector.add(
                    value = any,
                    explicitFollowupContext = true,
                )

            else ->
                collectQuestions(
                    node = any,
                    collector = collector,
                    depth = 0,
                    followupContext = false,
                    allowGenericQuestionAtThisObject = true,
                )
        }

        return collector.toList()
    }

    /**
     * Return the most likely first follow-up question.
     *
     * Prefer explicit question punctuation, then preserve extractor priority.
     */
    @JvmStatic
    fun extractFollowupQuestion(
        rawText: String,
    ): String? {
        val questions =
            try {
                fromRaw(
                    raw = rawText,
                    max = 6,
                )
            } catch (_: Exception) {
                emptyList()
            }

        val result =
            questions.firstOrNull {
                it.contains('?') ||
                        it.contains('？')
            } ?: questions.firstOrNull()

        return result
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    /**
     * Extract a coarse 0..100 score.
     *
     * Order:
     * 1. JSON fields.
     * 2. Labeled text.
     * 3. Last standalone integer 0..100.
     *
     * Invalid out-of-range scores are rejected rather than clamped into a
     * seemingly valid value.
     */
    @JvmStatic
    fun extractScore(
        text: String,
    ): Int? {
        if (text.isBlank()) {
            return null
        }

        for (candidate in jsonCandidateTexts(text)) {
            for (fragment in extractJsonFragments(candidate)) {
                val score =
                    when (fragment) {
                        is JSONObject ->
                            findScoreRecursive(
                                obj = fragment,
                                depth = 0,
                            )

                        is JSONArray ->
                            findScoreRecursive(
                                arr = fragment,
                                depth = 0,
                            )

                        else -> null
                    }

                if (score != null) {
                    return score
                }
            }
        }

        val safeText =
            boundedScanText(text)

        val labeledMatch =
            LABELED_SCORE_REGEX
                .findAll(safeText)
                .lastOrNull()

        if (labeledMatch != null) {
            val numeric =
                labeledMatch
                    .groupValues
                    .getOrNull(1)
                    .orEmpty()

            val percent =
                labeledMatch
                    .groupValues
                    .getOrNull(2)
                    .orEmpty()
                    .isNotBlank()

            parseScoreTextTo0to100OrNull(
                raw = numeric,
                percentExplicit = percent,
            )?.let {
                return it
            }
        }

        return NUMBER_0_TO_100_REGEX
            .findAll(safeText)
            .lastOrNull()
            ?.value
            ?.toIntOrNull()
    }

    /**
     * Extract follow-up target/topic.
     */
    @JvmStatic
    fun extractFollowupTarget(
        text: String,
    ): String? {
        extractTextContractField(
            text = text,
            keysNorm = FOLLOWUP_TARGET_KEYS_NORM,
        )?.let {
            return it
        }

        return extractPlainLabeledValue(
            text = text,
            keysNorm = FOLLOWUP_TARGET_KEYS_NORM,
        )
            ?.let(::normalizeContractText)
    }

    /**
     * Extract follow-up-needed boolean.
     */
    @JvmStatic
    fun extractFollowupNeeded(
        text: String,
    ): Boolean? {
        for (candidate in jsonCandidateTexts(text)) {
            for (fragment in extractJsonFragments(candidate)) {
                val value =
                    when (fragment) {
                        is JSONObject ->
                            findBooleanByKeysRecursive(
                                obj = fragment,
                                keysNorm = FOLLOWUP_NEEDED_KEYS_NORM,
                                depth = 0,
                            )

                        is JSONArray ->
                            findBooleanByKeysRecursive(
                                arr = fragment,
                                keysNorm = FOLLOWUP_NEEDED_KEYS_NORM,
                                depth = 0,
                            )

                        else -> null
                    }

                if (value != null) {
                    return value
                }
            }
        }

        val plain =
            extractPlainLabeledValue(
                text = text,
                keysNorm = FOLLOWUP_NEEDED_KEYS_NORM,
            ) ?: return null

        return parseBooleanOrNull(plain)
    }

    /**
     * Extract weakness / missing-point text.
     */
    @JvmStatic
    fun extractWeakness(
        text: String,
    ): String? {
        extractTextContractField(
            text = text,
            keysNorm = WEAKNESS_KEYS_NORM,
        )?.let {
            return it
        }

        return extractPlainLabeledValue(
            text = text,
            keysNorm = WEAKNESS_KEYS_NORM,
        )
            ?.let(::normalizeContractText)
    }

    // =====================================================================
    // Question extraction
    // =====================================================================

    /**
     * Context-aware JSON traversal.
     *
     * Key safety rule:
     * - Generic fields such as "question", "text", "message", or "prompt" are
     *   accepted only:
     *     a) inside an explicit follow-up container, or
     *     b) directly on the root object passed by the caller.
     * - Arbitrary nested objects are still traversed so a nested
     *   "followup_question" container can be discovered, but their generic
     *   "question" fields are not automatically harvested.
     */
    private fun collectQuestions(
        node: Any?,
        collector: QuestionCollector,
        depth: Int,
        followupContext: Boolean,
        allowGenericQuestionAtThisObject: Boolean,
    ) {
        if (
            node == null ||
            node === JSONObject.NULL ||
            collector.isFull() ||
            depth > MAX_JSON_DEPTH
        ) {
            return
        }

        when (node) {
            is JSONArray -> {
                val count =
                    minOf(
                        node.length(),
                        MAX_ARRAY_ITEMS_PER_LEVEL,
                    )

                for (index in 0 until count) {
                    if (collector.isFull()) {
                        break
                    }

                    val value =
                        node.opt(index)

                    when (value) {
                        null,
                        JSONObject.NULL,
                            -> Unit

                        is String -> {
                            if (followupContext) {
                                collector.add(
                                    value = value,
                                    explicitFollowupContext = true,
                                )
                            }
                        }

                        is JSONObject,
                        is JSONArray,
                            ->
                            collectQuestions(
                                node = value,
                                collector = collector,
                                depth = depth + 1,
                                followupContext = followupContext,
                                allowGenericQuestionAtThisObject =
                                    followupContext,
                            )
                    }
                }
            }

            is JSONObject -> {
                val keys =
                    collectKeys(node)

                /**
                 * Process explicit follow-up containers first.
                 */
                for (key in prioritizedFollowupKeys(keys)) {
                    if (collector.isFull()) {
                        break
                    }

                    when (val value = node.opt(key)) {
                        null,
                        JSONObject.NULL,
                            -> Unit

                        is String ->
                            collector.add(
                                value = value,
                                explicitFollowupContext = true,
                            )

                        is JSONObject -> {
                            extractQuestionField(value)
                                ?.let {
                                    collector.add(
                                        value = it,
                                        explicitFollowupContext = true,
                                    )
                                }

                            collectQuestions(
                                node = value,
                                collector = collector,
                                depth = depth + 1,
                                followupContext = true,
                                allowGenericQuestionAtThisObject = true,
                            )
                        }

                        is JSONArray ->
                            collectQuestions(
                                node = value,
                                collector = collector,
                                depth = depth + 1,
                                followupContext = true,
                                allowGenericQuestionAtThisObject = true,
                            )
                    }
                }

                if (collector.isFull()) {
                    return
                }

                /**
                 * Generic scalar question fields are accepted only in a trusted
                 * question context.
                 */
                val hasFollowupSignal =
                    keys.any { key ->
                        val normalized = normKey(key)
                        normalized in FOLLOWUP_KEYS_NORM ||
                                normalized in FOLLOWUP_NEEDED_KEYS_NORM ||
                                normalized in FOLLOWUP_TARGET_KEYS_NORM
                    }

                val standaloneQuestionObject =
                    allowGenericQuestionAtThisObject &&
                            looksLikeStandaloneQuestionObject(
                                obj = node,
                                keys = keys,
                            )

                if (
                    followupContext ||
                    hasFollowupSignal ||
                    standaloneQuestionObject
                ) {
                    extractQuestionField(node)
                        ?.let {
                            collector.add(
                                value = it,
                                explicitFollowupContext =
                                    followupContext ||
                                            hasFollowupSignal,
                            )
                        }
                }

                /**
                 * Recurse into arbitrary nested structures only to discover
                 * deeper explicit follow-up containers.
                 *
                 * Do not propagate generic-question permission unless we are
                 * already inside follow-up context.
                 */
                for (key in keys) {
                    if (collector.isFull()) {
                        break
                    }

                    if (
                        normKey(key) in FOLLOWUP_KEYS_NORM
                    ) {
                        continue
                    }

                    when (val value = node.opt(key)) {
                        is JSONObject,
                        is JSONArray,
                            ->
                            collectQuestions(
                                node = value,
                                collector = collector,
                                depth = depth + 1,
                                followupContext = followupContext,
                                allowGenericQuestionAtThisObject =
                                    followupContext,
                            )
                    }
                }
            }

            is String -> {
                if (followupContext) {
                    collector.add(
                        value = node,
                        explicitFollowupContext = true,
                    )
                }
            }
        }
    }

    private fun looksLikeStandaloneQuestionObject(
        obj: JSONObject,
        keys: List<String>,
    ): Boolean {
        if (keys.isEmpty()) {
            return false
        }

        val metadataKeys =
            setOf(
                "id",
                "type",
                "lang",
                "language",
            )

        val allowed =
            keys.all { key ->
                val normalized = normKey(key)
                normalized in QUESTION_FIELDS_NORM ||
                        normalized in metadataKeys ||
                        normalized.endsWith("-question")
            }

        if (!allowed) {
            return false
        }

        val question =
            extractQuestionField(obj)
                ?: return false

        return looksLikeQuestionString(question)
    }

    private fun prioritizedFollowupKeys(
        keys: List<String>,
    ): List<String> {
        if (keys.isEmpty()) {
            return emptyList()
        }

        val priority =
            FOLLOWUP_KEYS_RAW
                .map(::normKey)
                .distinct()
                .withIndex()
                .associate {
                    it.value to it.index
                }

        return keys
            .filter {
                normKey(it) in FOLLOWUP_KEYS_NORM
            }
            .sortedWith(
                compareBy<String> {
                    priority[normKey(it)]
                        ?: Int.MAX_VALUE
                }.thenBy {
                    normKey(it)
                }
            )
    }

    private fun collectKeys(
        obj: JSONObject,
    ): List<String> {
        val out =
            ArrayList<String>()

        val iterator =
            obj.keys()

        while (iterator.hasNext()) {
            out += iterator.next()
        }

        return out
    }

    /**
     * Extract one representative question scalar from an object.
     */
    private fun extractQuestionField(
        obj: JSONObject,
    ): String? {
        val normalizedMap =
            LinkedHashMap<String, Any?>()

        val iterator =
            obj.keys()

        while (iterator.hasNext()) {
            val key =
                iterator.next()

            normalizedMap[normKey(key)] =
                obj.opt(key)
        }

        for (candidate in QUESTION_FIELD_CANDIDATES) {
            val value =
                normalizedMap[normKey(candidate)]

            if (
                value is String &&
                value.isNotBlank()
            ) {
                return value.trim()
            }
        }

        /**
         * Weak fallback only for explicit question-named fields.
         */
        for ((key, value) in normalizedMap) {
            if (
                key.contains("question") &&
                value is String &&
                value.isNotBlank()
            ) {
                return value.trim()
            }
        }

        return null
    }

    /**
     * Question collector with normalized dedupe.
     */
    private class QuestionCollector(
        private val max: Int,
    ) {
        private val values =
            ArrayList<String>()

        private val canonical =
            HashSet<String>()

        fun isFull(): Boolean =
            values.size >= max

        fun isEmpty(): Boolean =
            values.isEmpty()

        fun add(
            value: String,
            explicitFollowupContext: Boolean,
        ) {
            if (isFull()) {
                return
            }

            val normalized =
                normalizeQuestionCandidate(
                    source = value,
                    explicitFollowupContext =
                        explicitFollowupContext,
                ) ?: return

            val key =
                canonicalQuestionKey(normalized)

            if (canonical.add(key)) {
                values += normalized
            }
        }

        fun toList(): List<String> =
            values.toList()
    }

    private fun normalizeQuestionCandidate(
        source: String,
        explicitFollowupContext: Boolean,
    ): String? {
        var text =
            stripWrappingQuotes(
                stripLeadingListMarker(
                    source.trim()
                )
            )

        if (text.isBlank()) {
            return null
        }

        if (
            text.length >=
            HARD_REJECT_QUESTION_CHARS
        ) {
            return null
        }

        text =
            normalizeInlineWhitespace(text)

        val questionLike =
            looksLikeQuestionString(text)

        val sentinel =
            text
                .lowercase(Locale.US)
                .trim()
                .trimEnd('.', '!', '?', '？')

        val isEmptySentinel =
            sentinel in setOf(
                "none",
                "null",
                "n/a",
                "na",
                "no followup",
                "no follow-up",
                "not needed",
                "no question",
            )

        if (isEmptySentinel) {
            return null
        }

        /**
         * A value under an explicit follow-up field may legitimately omit a
         * question mark, but reject very short non-question placeholders.
         */
        if (!questionLike) {
            if (!explicitFollowupContext) {
                return null
            }

            if (
                text.length <
                MIN_NONQUESTION_FOLLOWUP_CHARS
            ) {
                return null
            }
        }

        /**
         * Preserve the existing UI safety cap.
         */
        if (text.length > MAX_QUESTION_CHARS) {
            text =
                text
                    .take(MAX_QUESTION_CHARS)
                    .trimEnd()
        }

        text =
            TRAILING_QUESTION_REGEX.replace(text) { match ->
                if (match.value.contains('？')) {
                    "？"
                } else {
                    "?"
                }
            }

        return text
            .trim()
            .takeIf(String::isNotBlank)
    }

    private fun canonicalQuestionKey(
        text: String,
    ): String =
        text
            .lowercase(Locale.US)
            .replace('？', '?')
            .replace(HORIZONTAL_SPACE_REGEX, " ")
            .trim()
            .trimEnd('?')
            .trim()

    private fun stripLeadingListMarker(
        source: String,
    ): String {
        val trimmed =
            source.trimStart()

        val bulletRemoved =
            trimmed.replaceFirst(
                Regex("""^(?:[-*•●▪◦]+)\s+"""),
                "",
            )

        return bulletRemoved.replaceFirst(
            Regex("""^\(?\d{1,3}[.)]\s+"""),
            "",
        )
    }

    private fun stripWrappingQuotes(
        source: String,
    ): String {
        val text =
            source.trim()

        if (text.length < 2) {
            return text
        }

        val first =
            text.first()

        val last =
            text.last()

        val wrapped =
            (first == '"' && last == '"') ||
                    (first == '\'' && last == '\'') ||
                    (first == '“' && last == '”') ||
                    (first == '『' && last == '』') ||
                    (first == '「' && last == '」')

        return if (wrapped) {
            text
                .substring(
                    1,
                    text.length - 1,
                )
                .trim()
        } else {
            text
        }
    }

    private fun normalizeInlineWhitespace(
        source: String,
    ): String =
        source
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(HORIZONTAL_SPACE_REGEX, " ")
            .trim()

    private fun looksLikeQuestionString(
        source: String,
    ): Boolean {
        val text =
            source.trim()

        if (text.isEmpty()) {
            return false
        }

        if (
            text.contains('?') ||
            text.contains('？')
        ) {
            return true
        }

        val lower =
            text.lowercase(Locale.US)

        val englishStarters =
            listOf(
                "what",
                "why",
                "how",
                "when",
                "where",
                "which",
                "who",
                "is",
                "are",
                "do",
                "did",
                "does",
                "can",
                "could",
                "would",
                "should",
                "may",
                "might",
                "please explain",
                "please describe",
                "tell me",
            )

        if (
            englishStarters.any {
                lower == it ||
                        lower.startsWith("$it ")
            }
        ) {
            return true
        }

        val japaneseStarters =
            listOf(
                "なぜ",
                "どう",
                "いつ",
                "どこ",
                "どれ",
                "どの",
                "だれ",
                "誰",
                "何",
                "どんな",
                "教えて",
                "説明して",
            )

        if (
            japaneseStarters.any {
                text.startsWith(it)
            }
        ) {
            return true
        }

        return text.endsWith("ですか") ||
                text.endsWith("ますか") ||
                text.endsWith("でしょうか") ||
                text.endsWith("ませんか") ||
                text.endsWith("ますでしょうか")
    }

    // =====================================================================
    // Score extraction
    // =====================================================================

    private fun findScoreRecursive(
        obj: JSONObject,
        depth: Int,
    ): Int? {
        if (depth > MAX_JSON_DEPTH) {
            return null
        }

        val normalizedMap =
            LinkedHashMap<String, Any?>()

        val keys =
            collectKeys(obj)

        for (key in keys) {
            normalizedMap[normKey(key)] =
                obj.opt(key)
        }

        for (key in SCORE_KEYS_ORDERED_NORM) {
            if (key !in SCORE_KEYS_NORM) {
                continue
            }

            parseScoreTo0to100OrNull(
                normalizedMap[key]
            )?.let {
                return it
            }
        }

        for (key in keys) {
            when (val value = obj.opt(key)) {
                is JSONObject ->
                    findScoreRecursive(
                        obj = value,
                        depth = depth + 1,
                    )?.let {
                        return it
                    }

                is JSONArray ->
                    findScoreRecursive(
                        arr = value,
                        depth = depth + 1,
                    )?.let {
                        return it
                    }
            }
        }

        return null
    }

    private fun findScoreRecursive(
        arr: JSONArray,
        depth: Int,
    ): Int? {
        if (depth > MAX_JSON_DEPTH) {
            return null
        }

        val count =
            minOf(
                arr.length(),
                MAX_ARRAY_ITEMS_PER_LEVEL,
            )

        for (index in 0 until count) {
            when (val value = arr.opt(index)) {
                is JSONObject ->
                    findScoreRecursive(
                        obj = value,
                        depth = depth + 1,
                    )?.let {
                        return it
                    }

                is JSONArray ->
                    findScoreRecursive(
                        arr = value,
                        depth = depth + 1,
                    )?.let {
                        return it
                    }
            }
        }

        return null
    }

    /**
     * Existing score semantics:
     * - strict fractions 0 < x < 1 are scaled to 0..100
     * - 1 remains 1
     * - ordinary values 0..100 are accepted
     * - anything outside 0..100 is rejected
     */
    private fun parseScoreTo0to100OrNull(
        value: Any?,
    ): Int? {
        if (value is String) {
            val trimmed = value.trim()
            return parseScoreTextTo0to100OrNull(
                raw = trimmed.removeSuffix("%").trim(),
                percentExplicit = trimmed.endsWith("%"),
            )
        }

        val numeric =
            (value as? Number)
                ?.toDouble()
                ?: return null

        if (!numeric.isFinite()) {
            return null
        }

        val scaled =
            if (
                numeric > 0.0 &&
                numeric < 1.0
            ) {
                numeric * 100.0
            } else {
                numeric
            }

        if (scaled !in 0.0..100.0) {
            return null
        }

        return scaled.roundToInt()
    }

    private fun parseScoreTextTo0to100OrNull(
        raw: String,
        percentExplicit: Boolean,
    ): Int? {
        val numeric =
            raw
                .trim()
                .toDoubleOrNull()
                ?: return null

        if (!numeric.isFinite()) {
            return null
        }

        val scaled =
            if (percentExplicit) {
                numeric
            } else if (
                numeric > 0.0 &&
                numeric < 1.0
            ) {
                numeric * 100.0
            } else {
                numeric
            }

        if (scaled !in 0.0..100.0) {
            return null
        }

        return scaled.roundToInt()
    }

    // =====================================================================
    // Contract fields
    // =====================================================================

    private fun extractTextContractField(
        text: String,
        keysNorm: Set<String>,
    ): String? {
        for (candidate in jsonCandidateTexts(text)) {
            for (fragment in extractJsonFragments(candidate)) {
                val value =
                    when (fragment) {
                        is JSONObject ->
                            findTextByKeysRecursive(
                                obj = fragment,
                                keysNorm = keysNorm,
                                depth = 0,
                            )

                        is JSONArray ->
                            findTextByKeysRecursive(
                                arr = fragment,
                                keysNorm = keysNorm,
                                depth = 0,
                            )

                        else -> null
                    }

                normalizeContractText(value)
                    ?.let {
                        return it
                    }
            }
        }

        return null
    }

    private fun findTextByKeysRecursive(
        obj: JSONObject,
        keysNorm: Set<String>,
        depth: Int,
    ): String? {
        if (depth > MAX_JSON_DEPTH) {
            return null
        }

        val keys =
            collectKeys(obj)

        for (key in keys) {
            if (normKey(key) !in keysNorm) {
                continue
            }

            extractTextValueBestEffort(
                value = obj.opt(key),
                depth = depth + 1,
            )?.let {
                return it
            }
        }

        for (key in keys) {
            when (val value = obj.opt(key)) {
                is JSONObject ->
                    findTextByKeysRecursive(
                        obj = value,
                        keysNorm = keysNorm,
                        depth = depth + 1,
                    )?.let {
                        return it
                    }

                is JSONArray ->
                    findTextByKeysRecursive(
                        arr = value,
                        keysNorm = keysNorm,
                        depth = depth + 1,
                    )?.let {
                        return it
                    }
            }
        }

        return null
    }

    private fun findTextByKeysRecursive(
        arr: JSONArray,
        keysNorm: Set<String>,
        depth: Int,
    ): String? {
        if (depth > MAX_JSON_DEPTH) {
            return null
        }

        val count =
            minOf(
                arr.length(),
                MAX_ARRAY_ITEMS_PER_LEVEL,
            )

        for (index in 0 until count) {
            when (val value = arr.opt(index)) {
                is JSONObject ->
                    findTextByKeysRecursive(
                        obj = value,
                        keysNorm = keysNorm,
                        depth = depth + 1,
                    )?.let {
                        return it
                    }

                is JSONArray ->
                    findTextByKeysRecursive(
                        arr = value,
                        keysNorm = keysNorm,
                        depth = depth + 1,
                    )?.let {
                        return it
                    }
            }
        }

        return null
    }

    private fun extractTextValueBestEffort(
        value: Any?,
        depth: Int,
    ): String? {
        if (
            value == null ||
            value === JSONObject.NULL ||
            depth > MAX_JSON_DEPTH
        ) {
            return null
        }

        return when (value) {
            is String ->
                normalizeContractText(value)

            is JSONArray -> {
                val parts =
                    ArrayList<String>()

                val count =
                    minOf(
                        value.length(),
                        MAX_ARRAY_ITEMS_PER_LEVEL,
                    )

                for (index in 0 until count) {
                    when (val element = value.opt(index)) {
                        is String ->
                            normalizeContractText(element)
                                ?.let(parts::add)

                        is JSONObject,
                        is JSONArray,
                            ->
                            extractTextValueBestEffort(
                                value = element,
                                depth = depth + 1,
                            )?.let(parts::add)
                    }

                    if (
                        parts.joinToString("; ").length >=
                        MAX_CONTRACT_FIELD_CHARS
                    ) {
                        break
                    }
                }

                normalizeContractText(
                    parts.joinToString("; ")
                )
            }

            is JSONObject ->
                extractStringFromObjectBestEffort(
                    obj = value,
                    depth = depth + 1,
                )

            else -> null
        }
    }

    private fun extractStringFromObjectBestEffort(
        obj: JSONObject,
        depth: Int,
    ): String? {
        if (depth > MAX_JSON_DEPTH) {
            return null
        }

        val preferredKeys =
            listOf(
                "text",
                "value",
                "content",
                "message",
                "reason",
                "summary",
                "weakness",
                "target",
                "followup_target",
                "followUpTarget",
            )
                .map(::normKey)

        val normalizedMap =
            LinkedHashMap<String, Any?>()

        val keys =
            collectKeys(obj)

        for (key in keys) {
            normalizedMap[normKey(key)] =
                obj.opt(key)
        }

        for (preferred in preferredKeys) {
            extractTextValueBestEffort(
                value = normalizedMap[preferred],
                depth = depth + 1,
            )?.let {
                return it
            }
        }

        val scalarStrings =
            normalizedMap
                .values
                .mapNotNull { it as? String }
                .mapNotNull(::normalizeContractText)

        if (scalarStrings.size == 1) {
            return scalarStrings.first()
        }

        return null
    }

    private fun findBooleanByKeysRecursive(
        obj: JSONObject,
        keysNorm: Set<String>,
        depth: Int,
    ): Boolean? {
        if (depth > MAX_JSON_DEPTH) {
            return null
        }

        val keys =
            collectKeys(obj)

        for (key in keys) {
            if (normKey(key) !in keysNorm) {
                continue
            }

            parseBooleanValueBestEffort(
                value = obj.opt(key),
                depth = depth + 1,
            )?.let {
                return it
            }
        }

        for (key in keys) {
            when (val value = obj.opt(key)) {
                is JSONObject ->
                    findBooleanByKeysRecursive(
                        obj = value,
                        keysNorm = keysNorm,
                        depth = depth + 1,
                    )?.let {
                        return it
                    }

                is JSONArray ->
                    findBooleanByKeysRecursive(
                        arr = value,
                        keysNorm = keysNorm,
                        depth = depth + 1,
                    )?.let {
                        return it
                    }
            }
        }

        return null
    }

    private fun findBooleanByKeysRecursive(
        arr: JSONArray,
        keysNorm: Set<String>,
        depth: Int,
    ): Boolean? {
        if (depth > MAX_JSON_DEPTH) {
            return null
        }

        val count =
            minOf(
                arr.length(),
                MAX_ARRAY_ITEMS_PER_LEVEL,
            )

        for (index in 0 until count) {
            when (val value = arr.opt(index)) {
                is JSONObject ->
                    findBooleanByKeysRecursive(
                        obj = value,
                        keysNorm = keysNorm,
                        depth = depth + 1,
                    )?.let {
                        return it
                    }

                is JSONArray ->
                    findBooleanByKeysRecursive(
                        arr = value,
                        keysNorm = keysNorm,
                        depth = depth + 1,
                    )?.let {
                        return it
                    }
            }
        }

        return null
    }

    private fun parseBooleanValueBestEffort(
        value: Any?,
        depth: Int,
    ): Boolean? {
        if (
            value == null ||
            value === JSONObject.NULL ||
            depth > MAX_JSON_DEPTH
        ) {
            return null
        }

        parseBooleanOrNull(value)
            ?.let {
                return it
            }

        return when (value) {
            is JSONObject -> {
                val preferred =
                    listOf(
                        "value",
                        "needed",
                        "required",
                        "enabled",
                        "followup_needed",
                    )
                        .map(::normKey)

                val normalizedMap =
                    LinkedHashMap<String, Any?>()

                val keys =
                    collectKeys(value)

                for (key in keys) {
                    normalizedMap[normKey(key)] =
                        value.opt(key)
                }

                for (key in preferred) {
                    parseBooleanValueBestEffort(
                        value = normalizedMap[key],
                        depth = depth + 1,
                    )?.let {
                        return it
                    }
                }

                null
            }

            is JSONArray -> {
                val count =
                    minOf(
                        value.length(),
                        MAX_ARRAY_ITEMS_PER_LEVEL,
                    )

                for (index in 0 until count) {
                    parseBooleanValueBestEffort(
                        value = value.opt(index),
                        depth = depth + 1,
                    )?.let {
                        return it
                    }
                }

                null
            }

            else -> null
        }
    }

    /**
     * Numeric booleans accept only exact 0 / 1.
     *
     * This intentionally rejects values such as 0.5 or 1.9 instead of
     * truncating them through Number.toInt().
     */
    private fun parseBooleanOrNull(
        value: Any?,
    ): Boolean? =
        when (value) {
            is Boolean ->
                value

            is Number -> {
                val numeric =
                    value.toDouble()

                when {
                    !numeric.isFinite() -> null
                    numeric == 1.0 -> true
                    numeric == 0.0 -> false
                    else -> null
                }
            }

            is String ->
                parseBooleanOrNull(value)

            else -> null
        }

    private fun parseBooleanOrNull(
        source: String,
    ): Boolean? {
        val normalized =
            source
                .trim()
                .trim('"', '\'', '`')
                .trim()
                .trimEnd(',')
                .trim()
                .lowercase(Locale.US)

        return when (normalized) {
            "true",
            "t",
            "yes",
            "y",
            "1",
                -> true

            "false",
            "f",
            "no",
            "n",
            "0",
                -> false

            else -> null
        }
    }

    private fun normalizeContractText(
        source: String?,
    ): String? {
        if (source == null) {
            return null
        }

        val normalized =
            stripWrappingQuotes(source.trim())
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace(HORIZONTAL_SPACE_REGEX, " ")
                .trim()
                .trimEnd(',')
                .trim()

        if (normalized.isBlank()) {
            return null
        }

        return normalized.take(
            MAX_CONTRACT_FIELD_CHARS
        )
    }

    /**
     * Strict plain-text label parser.
     *
     * The key must be the left-hand side of ':' or '='. This avoids matching
     * prose such as:
     *   "The followup_needed field should be true: ..."
     */
    private fun extractPlainLabeledValue(
        text: String,
        keysNorm: Set<String>,
    ): String? {
        val safeText =
            boundedScanText(text)

        for (line in safeText.lineSequence()) {
            val trimmed =
                line.trim()

            if (trimmed.isEmpty()) {
                continue
            }

            val colon =
                trimmed.indexOf(':')

            val equals =
                trimmed.indexOf('=')

            val separatorIndex =
                when {
                    colon < 0 -> equals
                    equals < 0 -> colon
                    else -> minOf(colon, equals)
                }

            if (separatorIndex <= 0) {
                continue
            }

            val rawKey =
                trimmed
                    .substring(
                        0,
                        separatorIndex,
                    )
                    .trim()
                    .removePrefix("-")
                    .removePrefix("*")
                    .trim()

            val key =
                normalizedPlainLabelKey(rawKey)

            if (key !in keysNorm) {
                continue
            }

            val value =
                trimmed
                    .substring(separatorIndex + 1)
                    .trim()
                    .trimEnd(',')
                    .trim()

            if (value.isNotBlank()) {
                return value
            }
        }

        return null
    }

    // =====================================================================
    // Plain-text sentence fallback
    // =====================================================================

    private fun splitSentenceLike(
        raw: String,
    ): List<String> {
        val out =
            ArrayList<String>()

        val current =
            StringBuilder()

        fun flush() {
            val text =
                current
                    .toString()
                    .trim()

            if (text.isNotEmpty()) {
                out += text
            }

            current.setLength(0)
        }

        for (character in raw) {
            when (character) {
                '\r',
                '\n',
                    -> flush()

                '。',
                '．',
                '.',
                '!',
                '！',
                '?',
                '？',
                    -> {
                    current.append(character)
                    flush()
                }

                else ->
                    current.append(character)
            }
        }

        flush()

        return out
    }

    // =====================================================================
    // JSON candidate / fence extraction
    // =====================================================================

    private fun boundedScanText(
        raw: String,
    ): String =
        if (raw.length <= MAX_SCAN_CHARS) {
            raw
        } else {
            raw.take(MAX_SCAN_CHARS)
        }

    private fun jsonCandidateTexts(
        raw: String,
    ): List<String> {
        val safeRaw =
            boundedScanText(raw)

        val out =
            ArrayList<String>()

        for (body in extractCodeFenceBodies(safeRaw)) {
            if (body.isNotBlank()) {
                out += body
            }
        }

        out += safeRaw

        return out
    }

    /**
     * Extract ```...``` and ~~~...~~~ bodies.
     *
     * The language tag is optional.
     */
    private fun extractCodeFenceBodies(
        raw: String,
    ): List<String> {
        val backtick =
            Regex(
                """```[A-Za-z0-9_-]*\s*([\s\S]*?)```"""
            )

        val tilde =
            Regex(
                """~~~[A-Za-z0-9_-]*\s*([\s\S]*?)~~~"""
            )

        val out =
            ArrayList<String>()

        fun appendMatches(regex: Regex) {
            for (match in regex.findAll(raw)) {
                if (
                    out.size >=
                    MAX_CODE_FENCES
                ) {
                    break
                }

                val body =
                    match
                        .groupValues
                        .getOrNull(1)
                        .orEmpty()
                        .trim()

                if (body.isNotBlank()) {
                    out += body
                }
            }
        }

        appendMatches(backtick)

        if (
            out.size <
            MAX_CODE_FENCES
        ) {
            appendMatches(tilde)
        }

        return out
    }

    // =====================================================================
    // JSON fragment extraction
    // =====================================================================

    /**
     * Extract parseable JSONObject / JSONArray fragments.
     *
     * Scanner behavior:
     * - Whole-string parse first.
     * - Otherwise try each object/array opener.
     * - Respect nested structures and quoted strings.
     * - Recover after malformed/unbalanced fragments.
     * - Do not let one unmatched quote in surrounding prose suppress later JSON.
     */
    private fun extractJsonFragments(
        raw: String,
    ): List<Any> {
        val source =
            boundedScanText(raw)
                .trim()

        if (source.isEmpty()) {
            return emptyList()
        }

        parseAny(source)
            ?.let {
                return listOf(it)
            }

        val fragments =
            ArrayList<Any>()

        var index = 0

        while (
            index < source.length &&
            fragments.size <
            MAX_JSON_FRAGMENTS
        ) {
            val opener =
                source[index]

            if (
                opener != '{' &&
                opener != '['
            ) {
                index++
                continue
            }

            val endExclusive =
                findBalancedJsonEnd(
                    source = source,
                    start = index,
                )

            if (endExclusive == null) {
                index++
                continue
            }

            val candidate =
                source.substring(
                    index,
                    endExclusive,
                )

            val parsed =
                parseAny(candidate)

            if (parsed != null) {
                fragments += parsed
                index = endExclusive
            } else {
                index++
            }
        }

        return fragments
    }

    /**
     * Return the exclusive end index of one balanced JSON object/array.
     */
    private fun findBalancedJsonEnd(
        source: String,
        start: Int,
    ): Int? {
        if (
            start !in source.indices
        ) {
            return null
        }

        val first =
            source[start]

        if (
            first != '{' &&
            first != '['
        ) {
            return null
        }

        val stack =
            ArrayDeque<Char>()

        stack.addLast(first)

        var inString = false
        var index = start + 1

        while (index < source.length) {
            val character =
                source[index]

            if (inString) {
                when {
                    character == '\\' -> {
                        index +=
                            if (
                                index + 1 <
                                source.length
                            ) {
                                2
                            } else {
                                1
                            }

                        continue
                    }

                    character == '"' ->
                        inString = false
                }

                index++
                continue
            }

            when (character) {
                '"' ->
                    inString = true

                '{',
                '[',
                    -> {
                    if (
                        stack.size >=
                        MAX_JSON_DEPTH
                    ) {
                        return null
                    }

                    stack.addLast(character)
                }

                '}' -> {
                    if (
                        stack.peekLast() !=
                        '{'
                    ) {
                        return null
                    }

                    stack.removeLast()

                    if (stack.isEmpty()) {
                        return index + 1
                    }
                }

                ']' -> {
                    if (
                        stack.peekLast() !=
                        '['
                    ) {
                        return null
                    }

                    stack.removeLast()

                    if (stack.isEmpty()) {
                        return index + 1
                    }
                }
            }

            index++
        }

        return null
    }

    private fun parseAny(
        source: String,
    ): Any? {
        val text =
            source.trim()

        if (text.isEmpty()) {
            return null
        }

        return try {
            when {
                text.startsWith("{") ->
                    JSONObject(text)

                text.startsWith("[") ->
                    JSONArray(text)

                else ->
                    null
            }
        } catch (_: Exception) {
            null
        }
    }
}
