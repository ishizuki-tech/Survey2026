/*
 * =====================================================================
 *  IshizukiTech LLC — SLM Integration Framework
 *  ---------------------------------------------------------------------
 *  File: ReviewScreen.kt
 *  Author: Shu Ishizuki (石附 支)
 *  License: MIT License
 *  © 2025 IshizukiTech LLC. All rights reserved.
 * =====================================================================
 *
 *  Summary:
 *  ---------------------------------------------------------------------
 *  Compact review screen that lists:
 *    • All original questions and answers.
 *    • Per-node follow-up questions and their answers.
 *
 *  Layout model:
 *    • Single LazyColumn inside a Scaffold, tuned for dense but readable
 *      typography (reduced font size + line height).
 *    • Two ElevatedCards: one for Q/A, one for follow-up history.
 *    • Bottom row of navigation buttons (“Back” / “Next”).
 *
 *  Notes:
 *    • Ordering is stabilized by sorting on nodeId, so repeated visits to
 *      this screen show a predictable layout.
 *    • Q/A section is built from the union of question + answer keys to avoid
 *      dropping unanswered or question-only nodes.
 * =====================================================================
 */

package com.negi.survey.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

import com.negi.survey.vm.SurveyViewModel
import com.negi.survey.vm.SurveyFinalizationState

/**
 * Review screen that renders a compact, read-only summary of the session.
 *
 * Responsibilities:
 *  - Read questions, answers, and follow-up entries from [SurveyViewModel].
 *  - Provide a dense but legible overview of:
 *      • All original questions and their current answers.
 *      • Follow-up questions grouped by owner node and their answers.
 *  - Expose simple navigation callbacks for returning to the flow or moving
 *    on to the final “Done” screen.
 *
 * Implementation details:
 *  - Uses [LazyColumn] for scalable performance on large interviews.
 *  - Typography is intentionally tightened (smaller font + line height) but
 *    still tied to [MaterialTheme.typography] for consistency.
 *
 * @param vm Backing ViewModel providing the survey state.
 * @param onNext Invoked when the user presses the “Next” button.
 * @param onBack Invoked when the user presses the “Back” button.
 */
@Composable
fun ReviewScreen(
    vm: SurveyViewModel,
    finalizationState: SurveyFinalizationState,
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    val cs = MaterialTheme.colorScheme

    val sectionTitle = MaterialTheme.typography.titleMedium
    val answerLabel = MaterialTheme.typography.labelMedium
    val answerBody = MaterialTheme.typography.bodyMedium

    // Collect VM state with explicit empty defaults for safety.
    val allQuestions by vm.questions.collectAsState(initial = emptyMap())
    val allAnswers by vm.answers.collectAsState(initial = emptyMap())
    val allFollowups by vm.followups.collectAsState(initial = emptyMap())

    /**
     * Build a stable union of node IDs that own either a question or an answer.
     *
     * This prevents the review screen from dropping:
     *  - unanswered nodes,
     *  - question-only nodes,
     *  - legacy call sites that set answers before questions.
     */
    val qaOwnerIds = remember(allQuestions, allAnswers) {
        (allQuestions.keys + allAnswers.keys)
            .toSet()
            .toList()
            .sorted()
    }

    /**
     * Stable Q/A entries derived from the union list above.
     */
    val qaEntries = remember(qaOwnerIds, allQuestions, allAnswers) {
        qaOwnerIds.map { id ->
            QaEntry(
                nodeId = id,
                question = allQuestions[id].orEmpty(),
                answer = allAnswers[id].orEmpty()
            )
        }
    }

    /**
     * Follow-ups sorted by node ID for predictable layout.
     *
     * The per-node list order is preserved as-is to respect insertion/creation order.
     */
    val sortedFollowups = remember(allFollowups) {
        allFollowups.toSortedMap()
    }

    Scaffold(containerColor = Color.Transparent) { pad ->
        LazyColumn(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header.
            item {
                Text(
                    text = "Review",
                    style = sectionTitle,
                    color = cs.onSurface
                )
            }

            // Q & A Card.
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "All Original Questions and Answers",
                            style = sectionTitle,
                            color = cs.onSurface
                        )
                        Spacer(Modifier.height(6.dp))

                        if (qaEntries.isEmpty()) {
                            Text(
                                text = "No records yet.",
                                style = answerBody,
                                color = cs.onSurfaceVariant
                            )
                        } else {
                            qaEntries.forEachIndexed { idx, entry ->
                                if (idx > 0) {
                                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                }

                                QaRow(
                                    entry = entry,
                                    labelStyle = answerLabel,
                                    bodyStyle = answerBody
                                )
                            }
                        }
                    }
                }
            }

            // Follow-ups Card.
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = "Follow-up History",
                            style = sectionTitle,
                            color = cs.onSurface
                        )
                        Spacer(Modifier.height(6.dp))

                        if (sortedFollowups.isEmpty()) {
                            Text(
                                text = "No follow-up questions.",
                                style = answerBody,
                                color = cs.onSurfaceVariant
                            )
                        } else {
                            sortedFollowups.entries.forEachIndexed { idx, (nodeId, list) ->
                                if (idx > 0) {
                                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                }

                                Text(
                                    text = "Node: $nodeId",
                                    style = answerLabel,
                                    color = cs.onSurfaceVariant
                                )

                                if (list.isEmpty()) {
                                    Text(
                                        text = "– No follow-ups recorded.",
                                        style = answerBody,
                                        color = cs.onSurfaceVariant
                                    )
                                } else {
                                    list.forEachIndexed { i, entry ->
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "${i + 1}. Q: ${entry.question}",
                                            style = answerBody,
                                            color = cs.onSurface
                                        )
                                        Text(
                                            text = "   A: ${entry.answer ?: "– No Answer."}",
                                            style = answerBody,
                                            color = if (entry.answer.isNullOrBlank()) {
                                                cs.onSurfaceVariant
                                            } else {
                                                cs.onSurface
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom buttons.
            item {
                val finishing = finalizationState is SurveyFinalizationState.Finishing
                val error = (finalizationState as? SurveyFinalizationState.Error)?.message
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 8.dp)
                ) {
                    SurveyActionButton(
                        onClick = onBack,
                        enabled = !finishing,
                        primary = false,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Back")
                    }
                    SurveyActionButton(
                        onClick = onFinish,
                        enabled = !finishing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (finishing) "Finishing..." else "Finish")
                    }
                }
                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = error, style = answerBody, color = cs.error)
                }
            }
        }
    }
}

/**
 * Immutable view model for a single Q/A row in the review card.
 */
private data class QaEntry(
    val nodeId: String,
    val question: String,
    val answer: String
)

/**
 * Render a compact Q/A row with consistent missing-value styling.
 */
@Composable
private fun QaRow(
    entry: QaEntry,
    labelStyle: androidx.compose.ui.text.TextStyle,
    bodyStyle: androidx.compose.ui.text.TextStyle
) {
    val cs = MaterialTheme.colorScheme

    Column {
        Text(
            text = entry.nodeId,
            style = labelStyle,
            color = cs.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))

        val qText = if (entry.question.isBlank()) "– No Question." else "Q: ${entry.question}"
        Text(
            text = qText,
            style = bodyStyle,
            color = if (entry.question.isBlank()) {
                cs.onSurfaceVariant
            } else {
                cs.onSurface
            }
        )

        Spacer(Modifier.height(2.dp))

        val aText = if (entry.answer.isBlank()) "– No Answer." else entry.answer
        Text(
            text = "A: $aText",
            style = bodyStyle,
            color = if (entry.answer.isBlank()) {
                cs.onSurfaceVariant
            } else {
                cs.onSurface
            }
        )
    }
}
