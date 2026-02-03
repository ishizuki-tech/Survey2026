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
 * This mapper:
 * - Keeps the `config` package free from any dependency on the ViewModel layer.
 * - Centralizes mapping rules (default types, field transforms, null safety).
 *
 * Fallback behavior:
 * - If [NodeDTO.type] is null/blank or cannot be mapped to [NodeType] via [NodeType.valueOf],
 *   the node defaults to [NodeType.TEXT].
 *
 * Null-safety behavior:
 * - Optional string fields are normalized with safe defaults to avoid accidental NPEs
 *   in downstream UI code.
 * - Optional list fields are passed through as-is (or empty when appropriate),
 *   depending on the expected [Node] constructor types.
 *
 * @receiver [NodeDTO] loaded from JSON/YAML configuration.
 * @return A [Node] instance suitable for use in the ViewModel layer.
 */
fun NodeDTO.toVmNode(): Node {
    val vmType = resolveVmNodeType(type)

    return Node(
        id = id,
        type = vmType,
        title = title.orEmpty(),
        question = question.orEmpty(),
        options = options,
        nextId = nextId
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

    return runCatching {
        NodeType.valueOf(normalized)
    }.getOrElse {
        NodeType.TEXT
    }
}
