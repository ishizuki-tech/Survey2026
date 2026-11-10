package com.negi.survey.screens

import android.content.ClipData
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.negi.survey.R
import com.negi.survey.vm.AiTurnOutcome
import com.negi.survey.vm.parseStrictModelJsonObject
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Composable
internal fun FieldMessage(
    text: String,
    isAi: Boolean,
    typing: Boolean,
    active: Boolean,
    historical: Boolean
) {
    val colors = MaterialTheme.colorScheme
    if (isAi) {
        Row(
            modifier = Modifier.widthIn(max = 560.dp).padding(vertical = if (historical) 0.dp else 1.dp).then(
                when {
                    active -> Modifier.testTag("Active AI question")
                    historical -> Modifier.testTag("Historical AI question")
                    else -> Modifier
                }
            ),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier.size(32.dp).background(
                    if (historical) lerp(colors.primary, colors.primaryContainer, 0.58f) else colors.primary,
                    CircleShape
                )
                    .testTag("AI question avatar"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ai_question),
                    contentDescription = null,
                    tint = if (historical) colors.onPrimaryContainer else colors.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = if (typing && text.isBlank()) "…" else text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (historical) lerp(colors.onSurfaceVariant, colors.surface, 0.08f) else colors.onSurface,
                lineHeight = if (historical) 21.sp else 22.sp,
                modifier = Modifier.padding(top = if (historical) 3.dp else 4.dp).testTag("AI message")
            )
        }
    } else {
        Surface(
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth(0.88f)
                .wrapContentWidth(Alignment.End).widthIn(max = 480.dp),
            color = lerp(colors.primaryContainer, colors.surface, 0.40f),
            contentColor = colors.onPrimaryContainer,
            shape = RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp)
        ) {
            Text(
                text, Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 21.sp
            )
        }
    }
}

@Composable
internal fun FieldProcessingRow(reviewing: Boolean, visible: Boolean) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val colors = MaterialTheme.colorScheme
    val moving = visible && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    // Read the animated value in Canvas so animation frames only invalidate drawing.
    val phase = if (moving) {
        rememberInfiniteTransition(label = "Field AI processing").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(if (reviewing) 2200 else 1350, easing = LinearEasing)),
            label = "Processing phase"
        )
    } else null
    val ink = colors.tertiary
    val dot = Color(0xFFC6F5CB)
    Row(
        modifier = Modifier.heightIn(min = 56.dp).padding(vertical = 4.dp).semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
        },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(
            Modifier.size(32.dp).background(
                if (reviewing) colors.tertiaryContainer else Color(0xFF12452F),
                RoundedCornerShape(7.dp)
            )
        ) {
            val cycle = phase?.value ?: 0f
            if (reviewing) {
                val left = size.width * 0.19f
                val right = size.width - left
                val top = size.height * 0.20f
                val bottom = size.height - top
                val grid = ink.copy(alpha = 0.25f)
                for (line in 0..3) {
                    val x = left + (right - left) * line / 3f
                    val y = top + (bottom - top) * line / 3f
                    drawLine(grid, Offset(x, top), Offset(x, bottom), strokeWidth = 1.dp.toPx())
                    drawLine(grid, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
                }
                val travel = (1f - cos(2 * PI * (cycle + 0.25f)).toFloat()) / 2f
                val y = top + (bottom - top) * travel
                drawLine(ink, Offset(left, y), Offset(right, y), 2.dp.toPx(), StrokeCap.Round)
            } else {
                for (index in 0..2) {
                    val time = ((cycle - index * 0.16f) / 0.48f).coerceIn(0f, 1f)
                    val lift = (1f - cos(2 * PI * time).toFloat()) / 2f
                    drawCircle(
                        dot, radius = 3.dp.toPx(),
                        center = Offset(size.width * (0.25f + index * 0.25f), center.y - 5.dp.toPx() * lift)
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (reviewing) "Reviewing your answer…" else "Preparing follow-up…",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface
            )
            Text(
                if (reviewing) "Checking for additional details" else "Getting the next question ready",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun FieldOutcomeRow(outcome: AiTurnOutcome, nextFollowupNumber: Int) {
    val (heading, explanation) = when (outcome) {
        AiTurnOutcome.ACHIEVED ->
            "Answer complete" to "Your answer meets the requirements. No follow-up question is needed."
        AiTurnOutcome.LIMIT_REACHED ->
            "Follow-up limit reached" to "No more follow-up questions can be asked."
        AiTurnOutcome.EVALUATION_FAILED ->
            "Evaluation failed" to "We couldn't evaluate your answer."
        AiTurnOutcome.FOLLOWUP_GENERATION_FAILED ->
            "Follow-up generation failed" to "We couldn't prepare another question."
        AiTurnOutcome.DUPLICATE_FOLLOWUP ->
            "No new follow-up available" to
                "The ${followupOrdinal(nextFollowupNumber)} follow-up question matched a question already asked, " +
                "so no additional question was shown."
        AiTurnOutcome.UNKNOWN ->
            "AI review unavailable" to "We couldn't complete the AI review."
        AiTurnOutcome.UNABLE_REFUSED ->
            "AI review ended" to "No further follow-up question is available."
    }
    val colors = MaterialTheme.colorScheme
    val (icon, color) = when (outcome) {
        AiTurnOutcome.ACHIEVED -> Icons.Filled.Check to colors.primary
        AiTurnOutcome.EVALUATION_FAILED, AiTurnOutcome.FOLLOWUP_GENERATION_FAILED, AiTurnOutcome.UNKNOWN ->
            Icons.Outlined.ErrorOutline to colors.error
        else -> Icons.Outlined.Info to colors.onSurfaceVariant
    }
    Row(
        Modifier.widthIn(max = 520.dp).padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Column {
            Text(heading, style = MaterialTheme.typography.titleSmall, color = color)
            Text(explanation, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

private fun followupOrdinal(number: Int): String = when (number) {
    1 -> "first"
    2 -> "second"
    3 -> "third"
    else -> {
        val suffix = if (number % 100 in 11..13) "th" else when (number % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        "$number$suffix"
    }
}

@Composable
internal fun FieldEvaluationDetails(reviewed: Boolean, raw: String, snack: SnackbarHostState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var rawExpanded by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val heading = if (reviewed) "Answer reviewed" else "Evaluation details"
    Surface(
        modifier = Modifier.widthIn(max = 520.dp),
        color = if (expanded) colors.surfaceContainer else Color.Transparent,
        contentColor = colors.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.align(Alignment.End)
                    .heightIn(min = 48.dp)
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide details" else "View details") {
                        expanded = !expanded
                        rawExpanded = false
                    }
                    .padding(horizontal = 4.dp, vertical = 1.dp)
                    .offset(y = (-6).dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (reviewed) Icons.Filled.Check else Icons.Outlined.Info, contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    heading + if (expanded) " · Hide details" else " · View details",
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelMedium
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
            if (expanded) {
                val result = remember(raw) { parseStrictModelJsonObject(raw) }
                val score = (result?.get("score") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                val missing = result?.get("missing_points") as? JsonArray
                Column(
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Evaluation details", style = MaterialTheme.typography.titleSmall)
                    if (score != null) Text("AI score $score", style = MaterialTheme.typography.bodyMedium)
                    if (missing != null) {
                        val points = missing.mapNotNull {
                            (it as? JsonPrimitive)?.takeIf { point -> point.isString && point.content.isNotBlank() }?.content
                        }
                        if (points.isNotEmpty() || missing.isEmpty()) {
                            Text("Additional details identified", style = MaterialTheme.typography.labelLarge)
                            if (missing.isEmpty()) Text("None listed", style = MaterialTheme.typography.bodyMedium)
                            points.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    Text("This information was generated by AI.", style = MaterialTheme.typography.bodySmall)
                    if (raw.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .semantics { stateDescription = if (rawExpanded) "Expanded" else "Collapsed" }
                                .clickable(role = Role.Button) { rawExpanded = !rawExpanded },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Raw JSON", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            Icon(if (rawExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                        }
                        if (rawExpanded) {
                            val clipboard = LocalClipboard.current
                            val scope = rememberCoroutineScope()
                            Text("Original model output", style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Evaluation output", raw)))
                                    snack.showSnackbar("Evaluation output copied")
                                }
                            }) { Text("Copy") }
                            SelectionContainer {
                                Text(
                                    raw,
                                    Modifier.fillMaxWidth().heightIn(max = 240.dp)
                                        .verticalScroll(rememberScrollState()).testTag("Evaluation raw output"),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                        }
                    } else {
                        Text("No model output is available.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
