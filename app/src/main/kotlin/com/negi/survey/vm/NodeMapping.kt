/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: NodeMappers.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 */

package com.negi.survey.vm

import com.negi.survey.config.NodeDTO
import java.util.Locale

/**
 * Convert a configuration-layer [NodeDTO] into a ViewModel-layer [Node].
 *
 * Design goals:
 * - Keep the `config` package free from any dependency on the ViewModel layer.
 * - Centralize mapping rules (aliases, normalization, null safety).
 *
 * Normalization:
 * - `id` is trimmed to prevent hidden-whitespace key mismatches.
 * - `nextId` is trimmed and blank is converted to null.
 * - `title` / `question` are trimmed (safe for accidental leading/trailing whitespace).
 *
 * Type mapping:
 * - Supports aliases (e.g. "RADIO" -> SINGLE_CHOICE, "LLM" -> AI, "FINAL" -> DONE).
 * - Falls back to [NodeType.TEXT] when unknown/blank.
 */
fun NodeDTO.toVmNode(): Node {
    val vmType = resolveVmNodeType(type)

    val safeId = id.trim()
    val safeNextId = nextId?.trim()?.takeIf { it.isNotBlank() }

    val safeOptions: List<String> = options
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()

    return Node(
        id = safeId,
        type = vmType,
        title = title.orEmpty().trim(),
        question = question.orEmpty().trim(),
        options = safeOptions,
        nextId = safeNextId
    )
}

/**
 * Resolve the ViewModel-layer [NodeType] from a config-layer raw type string.
 *
 * @param rawType Raw node type from configuration.
 * @return A normalized [NodeType] with safe fallback.
 */
private fun resolveVmNodeType(rawType: String?): NodeType {
    val normalized = rawType
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.uppercase(Locale.ROOT)
        ?: return NodeType.TEXT

    // Keep backward compatibility with legacy config spellings / aliases.
    return when (normalized) {
        "START" -> NodeType.START
        "TEXT" -> NodeType.TEXT

        "SINGLE_CHOICE", "SINGLECHOICE", "RADIO" -> NodeType.SINGLE_CHOICE
        "MULTI_CHOICE", "MULTICHOICE", "CHECKBOX" -> NodeType.MULTI_CHOICE

        "AI", "LLM", "SLM" -> NodeType.AI

        "REVIEW" -> NodeType.REVIEW

        "DONE", "FINISH", "FINAL" -> NodeType.DONE

        else -> runCatching { NodeType.valueOf(normalized) }
            .getOrElse { NodeType.TEXT }
    }
}
