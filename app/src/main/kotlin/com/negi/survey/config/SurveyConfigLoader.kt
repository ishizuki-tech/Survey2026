/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: SurveyConfigLoader.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2026 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Strongly-typed survey configuration model and loader.
 *  Supports JSON and YAML formats, SLM metadata, model defaults,
 *  Whisper metadata, and structural validation for graph-based survey flows.
 *
 *  Update (single-step + double-step YAML split support):
 *  ---------------------------------------------------------------------
 *   • Prompt entries now support:
 *     - prompt (legacy one-step)
 *     - eval_prompt + followup_prompt (inline two-step, legacy)
 *     - prompts_eval[] + prompts_followup[] (split two-step, recommended)
 *   • SLM meta now supports:
 *     - key_contract_eval / key_contract_followup
 *     - length_budget_eval / length_budget_followup
 *     - strict_output_eval / strict_output_followup
 *     - followup_output_mode: "JSON" or "TEXT" (2-call followup output control)
 *     - (fallback to key_contract / length_budget / strict_output when phase-specific fields are missing)
 *
 *  Notes:
 *  ---------------------------------------------------------------------
 *   • Validation assumes a single-successor graph using nextId.
 *     If you later introduce branching (per-option next ids), update validation.
 * =====================================================================
 */

@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package com.negi.survey.config

import android.content.Context
import android.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File
import java.nio.charset.Charset
import java.util.ArrayDeque
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "SurveyConfigLoader"

/**
 * Root survey configuration model.
 */
@Serializable
data class SurveyConfig(
    /**
     * Legacy prompt list:
     * - One-step: prompt
     * - Inline two-step: eval_prompt + followup_prompt
     */
    val prompts: List<Prompt> = emptyList(),

    /**
     * Split two-step prompts (recommended for "double-step only" YAML).
     * Each list uses entries of { nodeId, prompt }.
     */
    @SerialName("prompts_eval") val promptsEval: List<NodePrompt> = emptyList(),
    @SerialName("prompts_followup") val promptsFollowup: List<NodePrompt> = emptyList(),

    val graph: Graph,
    val slm: SlmMeta = SlmMeta(),
    val whisper: WhisperMeta = WhisperMeta(),
    @SerialName("model_defaults") val modelDefaults: ModelDefaults = ModelDefaults()
) {

    /**
     * A prompt template entry for a graph node.
     *
     * Supported forms:
     * - One-step (legacy): prompt
     * - Inline two-step (legacy): eval_prompt + followup_prompt
     *
     * Notes:
     * - Do NOT set both prompt and eval_prompt/followup_prompt together.
     * - Inline two-step is considered "complete" only when BOTH eval_prompt and followup_prompt are present.
     */
    @Serializable
    data class Prompt(
        val nodeId: String,

        /** Legacy one-step prompt template. */
        @SerialName("prompt") val prompt: String? = null,

        /** Inline two-step evaluation prompt (A). */
        @SerialName("eval_prompt") val evalPrompt: String? = null,

        /** Inline two-step follow-up prompt (2A). */
        @SerialName("followup_prompt") val followupPrompt: String? = null
    ) {
        /** True when this entry has any inline two-step field present (may be incomplete). */
        fun isTwoStepAny(): Boolean = !evalPrompt.isNullOrBlank() || !followupPrompt.isNullOrBlank()

        /** True only when this entry is a complete inline two-step prompt definition. */
        fun isTwoStepComplete(): Boolean = !evalPrompt.isNullOrBlank() && !followupPrompt.isNullOrBlank()

        /** Backward-compatible alias: inline two-step is active only when complete. */
        fun isTwoStep(): Boolean = isTwoStepComplete()

        /** True when this entry is a legacy one-step prompt definition. */
        fun isOneStep(): Boolean = !prompt.isNullOrBlank()
    }

    /**
     * Split prompt entry for prompts_eval/prompts_followup.
     */
    @Serializable
    data class NodePrompt(
        val nodeId: String,
        val prompt: String = ""
    ) {
        fun isValid(): Boolean = nodeId.isNotBlank() && prompt.isNotBlank()
    }

    @Serializable
    data class Graph(
        val startId: String,
        val nodes: List<NodeDTO> = emptyList()
    )

    /**
     * Follow-up output mode for 2-call pipelines.
     *
     * JSON: follow-up call returns JSON (e.g., {"followup_question": "..."}).
     * TEXT: follow-up call returns plain text only (single line) or empty output.
     */
    enum class FollowupOutputMode { JSON, TEXT;

        companion object {
            fun parse(raw: String?): FollowupOutputMode {
                val norm = raw?.trim()?.uppercase().orEmpty()
                return when (norm) {
                    "TEXT", "PLAIN", "PLAIN_TEXT", "PLAINTEXT" -> TEXT
                    "JSON" -> JSON
                    else -> JSON
                }
            }
        }
    }

    @Serializable
    data class SlmMeta(
        @SerialName("accelerator") val accelerator: String? = null,
        @SerialName("max_tokens") val maxTokens: Int? = null,
        @SerialName("top_k") val topK: Int? = null,
        @SerialName("top_p") val topP: Double? = null,
        @SerialName("temperature") val temperature: Double? = null,

        @SerialName("user_turn_prefix") val userTurnPrefix: String? = null,
        @SerialName("model_turn_prefix") val modelTurnPrefix: String? = null,
        @SerialName("turn_end") val turnEnd: String? = null,

        @SerialName("empty_json_instruction") val emptyJsonInstruction: String? = null,
        @SerialName("preamble") val preamble: String? = null,

        /** One-step contract (and fallback for eval/followup if phase-specific is missing). */
        @SerialName("key_contract") val keyContract: String? = null,

        /** Two-step eval contract (preferred). */
        @SerialName("key_contract_eval") val keyContractEval: String? = null,

        /** Two-step follow-up contract (preferred). */
        @SerialName("key_contract_followup") val keyContractFollowup: String? = null,

        /** One-step length budget (and fallback for eval/followup if phase-specific is missing). */
        @SerialName("length_budget") val lengthBudget: String? = null,

        /** Two-step eval length budget (preferred). */
        @SerialName("length_budget_eval") val lengthBudgetEval: String? = null,

        /** Two-step follow-up length budget (preferred). */
        @SerialName("length_budget_followup") val lengthBudgetFollowup: String? = null,

        /** One-step strict output (and fallback). */
        @SerialName("strict_output") val strictOutput: String? = null,

        /** Two-step eval strict output (preferred). */
        @SerialName("strict_output_eval") val strictOutputEval: String? = null,

        /** Two-step follow-up strict output (preferred). */
        @SerialName("strict_output_followup") val strictOutputFollowup: String? = null,

        /**
         * Follow-up output mode:
         * - "JSON": follow-up call outputs JSON
         * - "TEXT": follow-up call outputs plain text only
         *
         * Default: JSON
         */
        @SerialName("followup_output_mode") val followupOutputMode: String? = null,

        @SerialName("scoring_rule") val scoringRule: String? = null
    ) {
        /** Resolve follow-up output mode, defaulting to JSON. */
        fun resolvedFollowupOutputMode(): FollowupOutputMode =
            FollowupOutputMode.parse(followupOutputMode)
    }

    @Serializable
    data class WhisperMeta(
        @SerialName("enabled") val enabled: Boolean? = null,
        @SerialName("asset_model_path") val assetModelPath: String? = null,
        @SerialName("language") val language: String? = null,
        @SerialName("translate") val translate: Boolean? = null,
        @SerialName("print_timestamp") val printTimestamp: Boolean? = null,
        @SerialName("target_sample_rate") val targetSampleRate: Int? = null,
        @SerialName("record_sample_rates") val recordSampleRates: List<Int>? = null,
        @SerialName("compute_checksum") val computeChecksum: Boolean? = null
    )

    @Serializable
    data class ModelDefaults(
        /** Optional model name label for UI/debug. */
        @SerialName("model_name") val modelName: String? = null,

        @SerialName("default_model_url") val defaultModelUrl: String? = null,
        @SerialName("default_file_name") val defaultFileName: String? = null,
        @SerialName("timeout_ms") val timeoutMs: Long? = null,
        @SerialName("ui_throttle_ms") val uiThrottleMs: Long? = null,
        @SerialName("ui_min_delta_bytes") val uiMinDeltaBytes: Long? = null
    )

    fun resolveOneStepPrompt(nodeId: String): String? {
        val id = nodeId.trim()
        if (id.isBlank()) return null
        val p = prompts.firstOrNull { it.nodeId.trim() == id } ?: return null
        return p.prompt?.takeIf { it.isNotBlank() }
    }

    fun resolveEvalPrompt(nodeId: String): String? {
        val id = nodeId.trim()
        if (id.isBlank()) return null

        val legacy = prompts.firstOrNull { it.nodeId.trim() == id }
        if (legacy?.isTwoStepComplete() == true) {
            return legacy.evalPrompt?.takeIf { it.isNotBlank() }
        }

        val eval = promptsEval.firstOrNull { it.nodeId.trim() == id }?.prompt
        val follow = promptsFollowup.firstOrNull { it.nodeId.trim() == id }?.prompt
        if (!eval.isNullOrBlank() && !follow.isNullOrBlank()) return eval

        return null
    }

    fun resolveFollowupPrompt(nodeId: String): String? {
        val id = nodeId.trim()
        if (id.isBlank()) return null

        val legacy = prompts.firstOrNull { it.nodeId.trim() == id }
        if (legacy?.isTwoStepComplete() == true) {
            return legacy.followupPrompt?.takeIf { it.isNotBlank() }
        }

        val eval = promptsEval.firstOrNull { it.nodeId.trim() == id }?.prompt
        val follow = promptsFollowup.firstOrNull { it.nodeId.trim() == id }?.prompt
        if (!eval.isNullOrBlank() && !follow.isNullOrBlank()) return follow

        return null
    }

    fun composeSystemPromptOneStep(): String {
        val parts = listOf(
            slm.preamble,
            slm.keyContract,
            slm.lengthBudget,
            slm.scoringRule,
            slm.strictOutput,
            slm.emptyJsonInstruction
        ).filterNot { it.isNullOrBlank() }
            .map { it!!.trim() }
        return parts.joinToString("\n")
    }

    fun composeSystemPromptEval(): String {
        val contract = slm.keyContractEval?.takeIf { it.isNotBlank() } ?: slm.keyContract
        val budget = slm.lengthBudgetEval?.takeIf { it.isNotBlank() } ?: slm.lengthBudget
        val strict = slm.strictOutputEval?.takeIf { it.isNotBlank() } ?: slm.strictOutput

        val parts = listOf(
            slm.preamble,
            contract,
            budget,
            slm.scoringRule,
            strict,
            slm.emptyJsonInstruction
        ).filterNot { it.isNullOrBlank() }
            .map { it!!.trim() }
        return parts.joinToString("\n")
    }

    private fun defaultFollowupStrictText(): String = """
        STRICT OUTPUT (NO MARKDOWN):
        - Output ONLY the follow-up question as plain text on ONE LINE, or EMPTY output if none.
        - No JSON, no quotes, no extra text.
    """.trimIndent()

    fun composeSystemPromptFollowup(): String {
        val contract = slm.keyContractFollowup?.takeIf { it.isNotBlank() } ?: slm.keyContract
        val budget = slm.lengthBudgetFollowup?.takeIf { it.isNotBlank() } ?: slm.lengthBudget
        val mode = slm.resolvedFollowupOutputMode()

        val strict = when (mode) {
            FollowupOutputMode.TEXT -> slm.strictOutputFollowup?.takeIf { it.isNotBlank() }
                ?: defaultFollowupStrictText()
            FollowupOutputMode.JSON -> slm.strictOutputFollowup?.takeIf { it.isNotBlank() }
                ?: slm.strictOutput
        }

        val parts = ArrayList<String>(8)
        slm.preamble?.takeIf { it.isNotBlank() }?.let { parts += it.trim() }
        contract?.takeIf { it.isNotBlank() }?.let { parts += it.trim() }
        budget?.takeIf { it.isNotBlank() }?.let { parts += it.trim() }
        slm.scoringRule?.takeIf { it.isNotBlank() }?.let { parts += it.trim() }
        strict?.takeIf { it.isNotBlank() }?.let { parts += it.trim() }

        if (mode == FollowupOutputMode.JSON) {
            slm.emptyJsonInstruction?.takeIf { it.isNotBlank() }?.let { parts += it.trim() }
        }

        return parts.joinToString("\n")
    }

    fun composeSystemPrompt(): String = composeSystemPromptOneStep()

    fun normalizedPrompts(): List<Prompt> {
        val legacyById = LinkedHashMap<String, Prompt>()
        prompts.forEach { p ->
            val id = p.nodeId.trim()
            if (id.isNotBlank() && !legacyById.containsKey(id)) {
                legacyById[id] = p
            }
        }

        val evalById = promptsEval
            .asSequence()
            .map { it.nodeId.trim() to it.prompt }
            .filter { it.first.isNotBlank() }
            .toMap()

        val followById = promptsFollowup
            .asSequence()
            .map { it.nodeId.trim() to it.prompt }
            .filter { it.first.isNotBlank() }
            .toMap()

        val out = ArrayList<Prompt>(legacyById.size + evalById.size)

        out.addAll(legacyById.values)

        val splitIds = (evalById.keys + followById.keys).toSet()
        splitIds.asSequence()
            .sorted()
            .forEach { id ->
                if (legacyById.containsKey(id)) return@forEach
                val e = evalById[id]
                val f = followById[id]
                if (!e.isNullOrBlank() && !f.isNullOrBlank()) {
                    out += Prompt(nodeId = id, prompt = null, evalPrompt = e, followupPrompt = f)
                }
            }

        return out
    }

    fun toJsonl(): List<String> =
        SurveyConfigLoader.jsonCompact.let { json ->
            normalizedPrompts().map { json.encodeToString(Prompt.serializer(), it) }
        }

    fun toJson(pretty: Boolean = true): String =
        (if (pretty) SurveyConfigLoader.jsonPretty else SurveyConfigLoader.jsonCompact)
            .encodeToString(serializer(), this)

    /**
     * Encode the whole config into YAML.
     *
     * Note:
     * - Uses cached Yaml instances to avoid repeated allocations.
     */
    fun toYaml(strict: Boolean = false): String =
        SurveyConfigLoader.yamlCached(strict).encodeToString(serializer(), this)

    fun debugSummary(maxIds: Int = 8): String {
        fun takeIds(xs: List<String>, n: Int): String {
            val t = xs.asSequence().map { it.trim() }.filter { it.isNotBlank() }.distinct().take(n).toList()
            return if (t.isEmpty()) "-" else t.joinToString(",")
        }

        val legacyIds = prompts.map { it.nodeId }
        val evalIds = promptsEval.map { it.nodeId }
        val followIds = promptsFollowup.map { it.nodeId }

        val legacyOne = prompts.count { it.isOneStep() }
        val legacyInlineTwo = prompts.count { it.isTwoStepComplete() }

        val followMode = slm.resolvedFollowupOutputMode().name

        return "legacy=${prompts.size}(one=$legacyOne,inline2=$legacyInlineTwo) " +
                "splitEval=${promptsEval.size} splitFollow=${promptsFollowup.size} " +
                "followMode=$followMode " +
                "legacyIds=[${takeIds(legacyIds, maxIds)}] " +
                "evalIds=[${takeIds(evalIds, maxIds)}] " +
                "followIds=[${takeIds(followIds, maxIds)}]"
    }

    fun debugDump(maxPreviewChars: Int = 320): String {
        fun preview(s: String): String {
            val t = s.replace("\r", "\\r").replace("\n", "\\n")
            return if (t.length <= maxPreviewChars) t else t.take(maxPreviewChars) + "...(truncated)"
        }

        val evalSys = composeSystemPromptEval()
        val followSys = composeSystemPromptFollowup()
        val oneSys = composeSystemPromptOneStep()

        val aiIds = graph.nodes
            .asSequence()
            .filter { it.nodeType() == NodeType.AI }
            .map { it.id.trim() }
            .filter { it.isNotBlank() }
            .toList()

        val coverage = aiIds.joinToString(",") { id ->
            val one = resolveOneStepPrompt(id) != null
            val two = resolveEvalPrompt(id) != null && resolveFollowupPrompt(id) != null
            "$id(one=$one,two=$two)"
        }

        return buildString {
            append("SurveyConfigDump(").append(debugSummary()).append(")\n")
            append("System(oneStep,len=").append(oneSys.length).append("): ").append(preview(oneSys)).append("\n")
            append("System(eval,len=").append(evalSys.length).append("): ").append(preview(evalSys)).append("\n")
            append("System(followup,len=").append(followSys.length).append("): ").append(preview(followSys)).append("\n")
            append("AI coverage: ").append(coverage.ifBlank { "-" })
        }
    }

    fun validate(): List<String> {
        val issues = mutableListOf<String>()

        if (graph.startId.isBlank()) {
            issues += "graph.startId is blank"
        }
        if (graph.nodes.isEmpty()) {
            issues += "graph.nodes is empty"
            return issues
        }

        val rawIds = graph.nodes.map { it.id }
        val ids = rawIds.map { it.trim() }
        val idSet = ids.filter { it.isNotBlank() }.toSet()

        val blankIds = rawIds.filter { it.isBlank() }.distinct()
        if (blankIds.isNotEmpty()) {
            issues += "graph.nodes contains blank id entries"
        }

        val trimmedMismatch = graph.nodes
            .map { it.id }
            .filter { it.isNotBlank() }
            .filter { it != it.trim() }
            .distinct()
        if (trimmedMismatch.isNotEmpty()) {
            issues += "graph.nodes contains ids with leading/trailing whitespace: ${trimmedMismatch.joinToString(",")}"
        }

        val startIdNorm = graph.startId.trim()
        if (startIdNorm.isNotBlank() && startIdNorm !in idSet) {
            issues += "graph.startId='${graph.startId}' not found in node ids: ${idSet.joinToString(",")}"
        }

        val duplicateIds = ids
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (duplicateIds.isNotEmpty()) {
            issues += "duplicate node ids: ${duplicateIds.joinToString(",")}"
        }

        val blankTypes = graph.nodes
            .filter { it.type.isBlank() }
            .map { it.id }
            .filter { it.isNotBlank() }
        if (blankTypes.isNotEmpty()) {
            issues += "nodes with blank type: ${blankTypes.joinToString(",")}"
        }

        val unknownTypes = graph.nodes
            .filter { it.type.isNotBlank() }
            .filter { it.nodeType() == NodeType.UNKNOWN }
            .map { it.id }
            .filter { it.isNotBlank() }
        if (unknownTypes.isNotEmpty()) {
            issues += "nodes with unknown type: ${unknownTypes.joinToString(",")}"
        }

        val startNode = graph.nodes.firstOrNull { it.id.trim() == startIdNorm }
        if (startNode != null && startNode.nodeType() != NodeType.START) {
            issues += "graph.startId points to a non-START node (id='${startNode.id}', type='${startNode.type}')"
        }

        val explicitStarts = graph.nodes.count { it.nodeType() == NodeType.START }
        if (explicitStarts == 0) {
            issues += "no START node detected (expected exactly one START node)"
        } else if (explicitStarts > 1) {
            issues += "multiple START nodes detected (count=$explicitStarts)"
        }

        val doneIds = graph.nodes
            .asSequence()
            .filter { it.nodeType() == NodeType.DONE }
            .map { it.id.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (doneIds.isEmpty()) {
            issues += "no DONE node detected (expected at least one terminal node)"
        }

        val legacyBlankTargets = prompts.map { it.nodeId }.filter { it.isBlank() }.distinct()
        if (legacyBlankTargets.isNotEmpty()) {
            issues += "prompts contain blank nodeId entries"
        }

        val legacyUnknownTargets = prompts
            .map { it.nodeId.trim() }
            .filter { it.isNotBlank() }
            .filter { it !in idSet }
            .distinct()
        if (legacyUnknownTargets.isNotEmpty()) {
            issues += "prompts contain unknown nodeIds: ${legacyUnknownTargets.joinToString(",")}"
        }

        val legacyDuplicateTargets = prompts
            .map { it.nodeId.trim() }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        if (legacyDuplicateTargets.isNotEmpty()) {
            issues += "multiple prompts defined for nodeIds: ${legacyDuplicateTargets.joinToString(",")}"
        }

        fun duplicateNodeIds(list: List<NodePrompt>, label: String) {
            val dup = list
                .map { it.nodeId.trim() }
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            if (dup.isNotEmpty()) issues += "$label contains duplicate nodeIds: ${dup.joinToString(",")}"
        }

        duplicateNodeIds(promptsEval, "prompts_eval")
        duplicateNodeIds(promptsFollowup, "prompts_followup")

        fun unknownNodeIds(list: List<NodePrompt>, label: String) {
            val unk = list
                .map { it.nodeId.trim() }
                .filter { it.isNotBlank() }
                .filter { it !in idSet }
                .distinct()
            if (unk.isNotEmpty()) issues += "$label contains unknown nodeIds: ${unk.joinToString(",")}"
        }

        unknownNodeIds(promptsEval, "prompts_eval")
        unknownNodeIds(promptsFollowup, "prompts_followup")

        val evalMap = promptsEval.associateBy({ it.nodeId.trim() }, { it.prompt })
        val followMap = promptsFollowup.associateBy({ it.nodeId.trim() }, { it.prompt })

        val allPromptIds: Set<String> = run {
            val a = prompts.map { it.nodeId.trim() }.filter { it.isNotBlank() }.toSet()
            val b = promptsEval.map { it.nodeId.trim() }.filter { it.isNotBlank() }.toSet()
            val c = promptsFollowup.map { it.nodeId.trim() }.filter { it.isNotBlank() }.toSet()
            a + b + c
        }

        var hasAnyTwoStep = false

        allPromptIds.asSequence().sorted().forEach { id ->
            val legacy = prompts.firstOrNull { it.nodeId.trim() == id }

            val legacyHasOne = legacy?.isOneStep() == true
            val legacyHasTwoAny = legacy?.isTwoStepAny() == true
            val legacyHasTwoComplete = legacy?.isTwoStepComplete() == true

            val splitEval = evalMap[id]
            val splitFollow = followMap[id]
            val splitHasAny = !splitEval.isNullOrBlank() || !splitFollow.isNullOrBlank()
            val splitHasComplete = !splitEval.isNullOrBlank() && !splitFollow.isNullOrBlank()

            if (legacy != null) {
                if (!legacyHasOne && !legacyHasTwoAny) {
                    issues += "prompt for nodeId='$id' has no prompt/eval_prompt/followup_prompt"
                }

                if (legacyHasOne && legacyHasTwoAny) {
                    issues += "prompt for nodeId='$id' sets both 'prompt' and (eval_prompt/followup_prompt) (ambiguous)"
                }

                if (legacyHasTwoAny && !legacyHasTwoComplete) {
                    if (legacy.evalPrompt.isNullOrBlank() && !legacy.followupPrompt.isNullOrBlank()) {
                        issues += "inline two-step prompt for nodeId='$id' has followup_prompt but missing eval_prompt"
                    }
                    if (!legacy.evalPrompt.isNullOrBlank() && legacy.followupPrompt.isNullOrBlank()) {
                        issues += "inline two-step prompt for nodeId='$id' has eval_prompt but missing followup_prompt"
                    }
                }
            }

            if (splitHasAny && !splitHasComplete) {
                if (splitEval.isNullOrBlank() && !splitFollow.isNullOrBlank()) {
                    issues += "split two-step for nodeId='$id' has prompts_followup but missing prompts_eval"
                }
                if (!splitEval.isNullOrBlank() && splitFollow.isNullOrBlank()) {
                    issues += "split two-step for nodeId='$id' has prompts_eval but missing prompts_followup"
                }
            }

            if (legacy != null && splitHasAny) {
                issues += "nodeId='$id' uses both legacy prompts[] and split prompts_eval/prompts_followup (ambiguous)"
            }

            val isTwoStepForId = legacyHasTwoComplete || splitHasComplete
            if (isTwoStepForId) hasAnyTwoStep = true
        }

        if (hasAnyTwoStep) {
            val hasEvalContract = !(slm.keyContractEval.isNullOrBlank() && slm.keyContract.isNullOrBlank())
            val hasFollowContract = !(slm.keyContractFollowup.isNullOrBlank() && slm.keyContract.isNullOrBlank())

            if (!hasEvalContract) issues += "two-step prompts present but missing slm.key_contract_eval (and slm.key_contract fallback is also empty)"
            if (!hasFollowContract) issues += "two-step prompts present but missing slm.key_contract_followup (and slm.key_contract fallback is also empty)"

            val mode = slm.resolvedFollowupOutputMode()
            if (mode == FollowupOutputMode.TEXT) {
                val followContract = (slm.keyContractFollowup ?: slm.keyContract).orEmpty()
                val strict = (slm.strictOutputFollowup ?: slm.strictOutput).orEmpty()
                val hasJsonHints =
                    followContract.contains("RAW JSON", ignoreCase = true) ||
                            followContract.contains("JSON", ignoreCase = true) ||
                            followContract.contains("\"followup_question\"", ignoreCase = true) ||
                            followContract.contains("Keys", ignoreCase = true)
                val strictHasJsonHints =
                    strict.contains("RAW JSON", ignoreCase = true) ||
                            strict.contains("COMPACT JSON", ignoreCase = true)

                if (hasJsonHints) {
                    issues += "followup_output_mode=TEXT but key_contract_followup appears JSON-oriented (update contract to plain text only)"
                }
                if (strictHasJsonHints) {
                    issues += "followup_output_mode=TEXT but strict_output_followup/strict_output appears JSON-oriented (provide strict_output_followup for text-only)"
                }
            }
        }

        graph.nodes
            .asSequence()
            .filter { it.nodeType() == NodeType.AI }
            .map { it.id.trim() }
            .filter { it.isNotBlank() }
            .forEach { id ->
                val hasOne = resolveOneStepPrompt(id) != null
                val hasTwo = resolveEvalPrompt(id) != null && resolveFollowupPrompt(id) != null
                if (!hasOne && !hasTwo) {
                    issues += "AI node '$id' has no usable prompt (one-step or complete two-step)"
                }
            }

        graph.nodes.forEach { node ->
            node.nextId
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { next ->
                    if (next !in idSet) {
                        issues += "node '${node.id}' references unknown nextId='$next'"
                    }
                }
        }

        graph.nodes
            .asSequence()
            .filter { it.nodeType() == NodeType.AI && it.question.isBlank() }
            .forEach { issues += "AI node '${it.id}' has empty question" }

        graph.nodes
            .asSequence()
            .filter {
                val t = it.nodeType()
                t == NodeType.SINGLE_CHOICE || t == NodeType.MULTI_CHOICE
            }
            .forEach { node ->
                if (node.options.isEmpty()) {
                    issues += "Choice node '${node.id}' has empty options"
                } else {
                    val blankOpts = node.options.filter { it.isBlank() }.distinct()
                    if (blankOpts.isNotEmpty()) {
                        issues += "Choice node '${node.id}' contains blank option entries"
                    }
                }
            }

        graph.nodes
            .asSequence()
            .filter { it.nodeType() == NodeType.DONE }
            .filter { !it.nextId.isNullOrBlank() }
            .forEach { node ->
                issues += "DONE node '${node.id}' should not define nextId (got nextId='${node.nextId}')"
            }

        graph.nodes
            .asSequence()
            .filter { it.nodeType() != NodeType.DONE && it.nodeType() != NodeType.UNKNOWN }
            .forEach { node ->
                val next = node.nextId?.trim().orEmpty()
                if (next.isBlank()) {
                    issues += "non-terminal node '${node.id}' has empty nextId"
                }
            }

        run {
            if (startIdNorm.isBlank() || startIdNorm !in idSet) return@run

            val nodeById = LinkedHashMap<String, NodeDTO>()
            graph.nodes.forEach { n ->
                val k = n.id.trim()
                if (k.isNotBlank() && !nodeById.containsKey(k)) {
                    nodeById[k] = n
                }
            }

            val visited = LinkedHashSet<String>()
            val queue: ArrayDeque<String> = ArrayDeque()
            queue.add(startIdNorm)

            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                if (!visited.add(cur)) continue
                val node = nodeById[cur] ?: continue
                val next = node.nextId?.trim()?.takeIf { it.isNotBlank() }
                if (next != null && next in idSet) queue.add(next)
            }

            val unreachable = idSet - visited
            if (unreachable.isNotEmpty()) {
                issues += "unreachable nodes from startId='$startIdNorm': ${unreachable.ellipsisJoin()}"
            }

            if (doneIds.isNotEmpty()) {
                val reachableDone = doneIds.any { it in visited }
                if (!reachableDone) {
                    issues += "no DONE node is reachable from startId='$startIdNorm' (reachable=${visited.size}, total=${idSet.size})"
                }
            }

            run {
                val seen = HashSet<String>()
                var cur: String? = startIdNorm
                var cycle = false
                while (cur != null && cur in idSet) {
                    if (!seen.add(cur)) {
                        cycle = true
                        break
                    }
                    val n = nodeById[cur]
                    cur = n?.nextId?.trim()?.takeIf { it.isNotBlank() }
                }
                if (cycle) {
                    issues += "cycle detected in nextId chain starting from startId='$startIdNorm'"
                }
            }
        }

        slm.accelerator?.let { acc ->
            val a = acc.trim().uppercase()
            if (a != "CPU" && a != "GPU") issues += "slm.accelerator should be 'CPU' or 'GPU' (got '$acc')"
        }
        slm.maxTokens?.let { if (it <= 0) issues += "slm.max_tokens must be > 0 (got $it)" }
        slm.topK?.let { if (it <= 0) issues += "slm.top_k must be >= 1 (got $it)" }
        slm.topP?.let { if (it !in 0.0..1.0) issues += "slm.top_p must be in [0.0,1.0] (got $it)" }
        slm.temperature?.let { if (it !in 0.0..2.0) issues += "slm.temperature must be in [0.0,2.0] (got $it)" }

        whisper.assetModelPath?.let { if (it.isBlank()) issues += "whisper.asset_model_path is blank" }
        whisper.language?.let { lang ->
            val norm = lang.trim().lowercase()
            if (norm !in setOf("auto", "en", "ja", "sw")) {
                issues += "whisper.language should be one of 'auto','en','ja','sw' (got '$lang')"
            }
        }
        whisper.targetSampleRate?.let { if (it <= 0) issues += "whisper.target_sample_rate must be > 0 (got $it)" }
        whisper.recordSampleRates?.let { rs ->
            if (rs.isEmpty()) {
                issues += "whisper.record_sample_rates is empty"
            } else {
                val bad = rs.filter { it <= 0 }.distinct()
                if (bad.isNotEmpty()) issues += "whisper.record_sample_rates contains non-positive entries: ${bad.joinToString(",")}"
            }
        }

        modelDefaults.modelName?.let { if (it.isBlank()) issues += "model_defaults.model_name is blank" }
        modelDefaults.defaultModelUrl?.let { if (it.isBlank()) issues += "model_defaults.default_model_url is blank" }
        modelDefaults.defaultFileName?.let { if (it.isBlank()) issues += "model_defaults.default_file_name is blank" }
        modelDefaults.timeoutMs?.let { if (it <= 0L) issues += "model_defaults.timeout_ms must be > 0 (got $it)" }
        modelDefaults.uiThrottleMs?.let { if (it < 0L) issues += "model_defaults.ui_throttle_ms must be >= 0 (got $it)" }
        modelDefaults.uiMinDeltaBytes?.let { if (it < 0L) issues += "model_defaults.ui_min_delta_bytes must be >= 0 (got $it)" }

        return issues
    }

    fun requireValid() {
        val issues = validate()
        require(issues.isEmpty()) {
            "SurveyConfig validation failed:\n- " + issues.joinToString("\n- ")
        }
    }

    private fun Collection<String>.ellipsisJoin(limit: Int = 32): String {
        val sorted = this.asSequence().map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted().toList()
        if (sorted.size <= limit) return sorted.joinToString(",")
        val head = sorted.take(limit).joinToString(",")
        return "$head,...(+${sorted.size - limit})"
    }
}

typealias PromptEntry = SurveyConfig.Prompt
typealias GraphConfig = SurveyConfig.Graph
typealias NodePromptEntry = SurveyConfig.NodePrompt

@Serializable
data class NodeDTO(
    val id: String = "",
    val type: String = "",
    val title: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
    val nextId: String? = null
) {
    fun nodeType(): NodeType = NodeType.from(type)
}

enum class NodeType {
    START,
    TEXT,
    SINGLE_CHOICE,
    MULTI_CHOICE,
    AI,
    REVIEW,
    DONE,
    UNKNOWN;

    companion object {
        fun from(raw: String?): NodeType {
            val norm = raw
                ?.trim()
                ?.replace(Regex("""[\s_\-]+"""), "_")
                ?.uppercase()
                ?: return UNKNOWN

            return when (norm) {
                "START" -> START
                "TEXT" -> TEXT
                "SINGLE_CHOICE", "SINGLECHOICE", "SINGLE_OPTION", "RADIO" -> SINGLE_CHOICE
                "MULTI_CHOICE", "MULTICHOICE", "MULTI_OPTION", "CHECKBOX" -> MULTI_CHOICE
                "AI", "LLM", "SLM" -> AI
                "REVIEW" -> REVIEW
                "DONE", "FINISH", "FINAL" -> DONE
                else -> UNKNOWN
            }
        }
    }
}

enum class ConfigFormat { JSON, YAML, AUTO }

object SurveyConfigLoader {

    @Volatile private var debugFormat: Boolean = false
    @Volatile private var debugValidate: Boolean = false
    @Volatile private var debugPrompts: Boolean = false
    @Volatile private var debugDumpSystemPrompts: Boolean = false

    fun setDebug(
        format: Boolean? = null,
        validate: Boolean? = null,
        prompts: Boolean? = null,
        dumpSystemPrompts: Boolean? = null
    ) {
        format?.let { debugFormat = it }
        validate?.let { debugValidate = it }
        prompts?.let { debugPrompts = it }
        dumpSystemPrompts?.let { debugDumpSystemPrompts = it }
    }

    internal data class FormatDecision(
        val format: ConfigFormat,
        val reason: String,
        val firstMeaningfulLine: String,
        val leadingChars: String
    ) {
        fun debugString(): String {
            fun safeInline(s: String, max: Int): String {
                val t = s.replace("\n", "\\n").replace("\r", "\\r")
                return if (t.length <= max) t else t.take(max) + "..."
            }
            val first = safeInline(firstMeaningfulLine, 180)
            val lead = safeInline(leadingChars, 32)
            return "format=${format.name}, reason=$reason, firstLine='$first', leading='$lead'"
        }
    }

    internal val jsonCompact: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        isLenient = true
        explicitNulls = false
    }

    internal val jsonPretty: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
        explicitNulls = false
    }

    private fun createYaml(strict: Boolean): Yaml =
        Yaml(
            configuration = YamlConfiguration(
                encodeDefaults = false,
                strictMode = strict
            )
        )

    // Cached instances (avoid repeated allocations during frequent export).
    private val yamlLenient: Yaml by lazy { createYaml(strict = false) }
    private val yamlStrict: Yaml by lazy { createYaml(strict = true) }

    /**
     * Return cached YAML instance.
     *
     * Note:
     * - strictMode primarily affects parsing behavior, but we keep it consistent here.
     */
    internal fun yamlCached(strict: Boolean = false): Yaml = if (strict) yamlStrict else yamlLenient

    fun fromAssets(
        context: Context,
        fileName: String,
        charset: Charset = Charsets.UTF_8,
        format: ConfigFormat = ConfigFormat.AUTO
    ): SurveyConfig =
        try {
            context.assets.open(fileName).bufferedReader(charset).use { reader ->
                val raw = reader.readText()
                fromString(text = raw, format = format, fileNameHint = fileName)
            }
        } catch (ex: Exception) {
            throw IllegalArgumentException(
                "Failed to load SurveyConfig from assets/$fileName: ${ex.message}",
                ex
            )
        }

    fun fromAssetsValidated(
        context: Context,
        fileName: String,
        charset: Charset = Charsets.UTF_8,
        format: ConfigFormat = ConfigFormat.AUTO
    ): SurveyConfig =
        fromAssets(context, fileName, charset, format).also {
            if (debugPrompts) Log.d(TAG, "Loaded config (assets/$fileName): ${it.debugSummary()}")
            if (debugValidate) {
                val issues = it.validate()
                Log.d(TAG, "fromAssetsValidated -> issues=${issues.size} (${it.debugSummary()})")
                issues.forEach { msg -> Log.d(TAG, "  - $msg") }
            }
            if (debugDumpSystemPrompts) Log.d(TAG, it.debugDump())
            it.requireValid()
        }

    fun fromFile(
        path: String,
        charset: Charset = Charsets.UTF_8,
        format: ConfigFormat = ConfigFormat.AUTO
    ): SurveyConfig =
        try {
            val file = File(path)
            require(file.exists()) { "Config file not found: $path" }
            file.bufferedReader(charset).use { reader ->
                val raw = reader.readText()
                fromString(text = raw, format = format, fileNameHint = file.name)
            }
        } catch (ex: Exception) {
            throw IllegalArgumentException(
                "Failed to load SurveyConfig from file '$path': ${ex.message}",
                ex
            )
        }

    fun fromFileValidated(
        path: String,
        charset: Charset = Charsets.UTF_8,
        format: ConfigFormat = ConfigFormat.AUTO
    ): SurveyConfig =
        fromFile(path, charset, format).also {
            if (debugPrompts) Log.d(TAG, "Loaded config (file/$path): ${it.debugSummary()}")
            if (debugValidate) {
                val issues = it.validate()
                Log.d(TAG, "fromFileValidated -> issues=${issues.size} (${it.debugSummary()})")
                issues.forEach { msg -> Log.d(TAG, "  - $msg") }
            }
            if (debugDumpSystemPrompts) Log.d(TAG, it.debugDump())
            it.requireValid()
        }

    fun toFile(
        config: SurveyConfig,
        path: String,
        charset: Charset = Charsets.UTF_8,
        format: ConfigFormat = ConfigFormat.AUTO,
        prettyJson: Boolean = true,
        strictYaml: Boolean = false
    ) {
        val file = File(path)
        file.parentFile?.mkdirs()

        val chosen = when (format) {
            ConfigFormat.JSON -> ConfigFormat.JSON
            ConfigFormat.YAML -> ConfigFormat.YAML
            ConfigFormat.AUTO -> {
                val lower = file.name.lowercase()
                when {
                    lower.endsWith(".yaml") || lower.endsWith(".yml") -> ConfigFormat.YAML
                    else -> ConfigFormat.JSON
                }
            }
        }

        val text = when (chosen) {
            ConfigFormat.JSON -> config.toJson(pretty = prettyJson)
            ConfigFormat.YAML -> config.toYaml(strict = strictYaml)
            ConfigFormat.AUTO -> error("AUTO should have been resolved before encoding; this is a bug.")
        }

        file.writeText(text, charset)
    }

    fun fromString(
        text: String,
        format: ConfigFormat = ConfigFormat.AUTO,
        fileNameHint: String? = null
    ): SurveyConfig {
        val sanitized = text.normalizeText()
        val decision = decideFormat(desired = format, fileName = fileNameHint, text = sanitized)

        if (debugFormat) {
            Log.d(TAG, "fromString -> ${decision.debugString()} file='${fileNameHint.orEmpty()}'")
        }

        val cfg = try {
            when (decision.format) {
                ConfigFormat.JSON -> jsonCompact.decodeFromString(SurveyConfig.serializer(), sanitized)
                ConfigFormat.YAML -> yamlLenient.decodeFromString(SurveyConfig.serializer(), sanitized)
                ConfigFormat.AUTO -> error("AUTO should have been resolved before decoding; this is a bug.")
            }
        } catch (ex: SerializationException) {
            val preview = sanitized.safePreview()
            throw IllegalArgumentException(
                "Parsing error (${decision.debugString()}, file='${fileNameHint.orEmpty()}'). " +
                        "First 200 chars: $preview :: ${ex.message}",
                ex
            )
        } catch (ex: Exception) {
            val preview = sanitized.safePreview()
            throw IllegalArgumentException(
                "Unexpected error while parsing SurveyConfig " +
                        "(${decision.debugString()}, file='${fileNameHint.orEmpty()}'). " +
                        "First 200 chars: $preview :: ${ex.message}",
                ex
            )
        }

        if (debugPrompts) {
            Log.d(TAG, "fromString loaded (${fileNameHint.orEmpty()}): ${cfg.debugSummary()}")
        }
        if (debugDumpSystemPrompts) {
            Log.d(TAG, cfg.debugDump())
        }

        return cfg
    }

    fun fromStringValidated(
        text: String,
        format: ConfigFormat = ConfigFormat.AUTO,
        fileNameHint: String? = null
    ): SurveyConfig =
        fromString(text, format, fileNameHint).also {
            if (debugValidate) {
                val issues = it.validate()
                Log.d(TAG, "fromStringValidated -> issues=${issues.size} (${it.debugSummary()})")
                issues.forEach { msg -> Log.d(TAG, "  - $msg") }
            }
            if (debugDumpSystemPrompts) Log.d(TAG, it.debugDump())
            it.requireValid()
        }

    fun fromStringYamlStrictValidated(
        text: String,
        fileNameHint: String? = null
    ): SurveyConfig {
        val sanitized = text.normalizeText()
        try {
            val cfg = yamlStrict.decodeFromString(SurveyConfig.serializer(), sanitized)
            if (debugPrompts) Log.d(TAG, "strictYaml loaded (${fileNameHint.orEmpty()}): ${cfg.debugSummary()}")
            if (debugValidate) {
                val issues = cfg.validate()
                Log.d(TAG, "fromStringYamlStrictValidated -> issues=${issues.size} (${cfg.debugSummary()})")
                issues.forEach { msg -> Log.d(TAG, "  - $msg") }
            }
            if (debugDumpSystemPrompts) Log.d(TAG, cfg.debugDump())
            cfg.requireValid()
            return cfg
        } catch (ex: Exception) {
            val preview = sanitized.safePreview()
            throw IllegalArgumentException(
                "Failed strict YAML parse (file='${fileNameHint.orEmpty()}'). First 200 chars: $preview :: ${ex.message}",
                ex
            )
        }
    }

    private fun decideFormat(
        desired: ConfigFormat,
        fileName: String? = null,
        text: String? = null
    ): FormatDecision {
        val (first, lead) = text.debugPeek()

        if (desired != ConfigFormat.AUTO) {
            return FormatDecision(desired, "forced by caller", first, lead)
        }

        val lower = fileName?.lowercase().orEmpty()
        if (lower.endsWith(".json")) return FormatDecision(ConfigFormat.JSON, "file extension .json", first, lead)
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return FormatDecision(ConfigFormat.YAML, "file extension .yml/.yaml", first, lead)

        val sniffed = text?.let(::sniffFormat) ?: ConfigFormat.JSON
        return FormatDecision(
            sniffed,
            reason = if (text == null) "no content; default JSON" else "sniffed from content",
            firstMeaningfulLine = first,
            leadingChars = lead
        )
    }

    private fun sniffFormat(text: String): ConfigFormat {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return ConfigFormat.JSON

        val firstMeaningful = trimmed
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            .orEmpty()

        if (firstMeaningful.startsWith("%YAML")) return ConfigFormat.YAML
        if (firstMeaningful.startsWith("---")) return ConfigFormat.YAML
        if (firstMeaningful.startsWith("- ")) return ConfigFormat.YAML

        val yamlKeyPattern = Regex("""^[A-Za-z_][A-Za-z0-9_\-]*\s*:(\s*.*)?$""")
        if (yamlKeyPattern.containsMatchIn(firstMeaningful)) return ConfigFormat.YAML

        return ConfigFormat.JSON
    }

    private fun String.normalizeText(): String =
        this.stripBom()
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trimEnd('\n')

    private fun String.stripBom(): String =
        if (this.isNotEmpty() && this[0] == '\uFEFF') this.drop(1) else this

    private fun String.safePreview(max: Int = 200): String =
        this.replace("\n", "\\n")
            .replace("\r", "\\r")
            .let { t -> if (t.length <= max) t else t.take(max) + "..." }

    private fun String?.debugPeek(): Pair<String, String> {
        if (this.isNullOrBlank()) return "" to ""
        val norm = this.stripBom()
        val lead = norm.trimStart().take(32)
        val first = norm
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            .orEmpty()
        return first to lead
    }
}
