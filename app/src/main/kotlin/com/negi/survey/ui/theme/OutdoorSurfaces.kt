package com.negi.survey.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keeps the survey and model-download backdrops in sync with the active theme. */
@Composable
internal fun outdoorBackplate(): Brush = with(MaterialTheme.colorScheme) {
    Brush.verticalGradient(listOf(background, surfaceContainerHigh))
}

@Composable
internal fun outdoorGradientStops(): List<Color> = with(MaterialTheme.colorScheme) {
    listOf(background, surfaceContainerLow, surfaceContainer, surfaceContainerHigh)
}

@Composable
internal fun Modifier.neutralEdge(
    alpha: Float = 0.16f,
    corner: Dp = 12.dp,
    stroke: Dp = 1.dp
): Modifier {
    val colors = MaterialTheme.colorScheme
    return drawBehind {
        val outline = colors.outline.copy(alpha = alpha)
        val variant = colors.outlineVariant.copy(alpha = alpha)
        val sweep = Brush.sweepGradient(
            0f to outline,
            0.25f to variant,
            0.5f to outline.copy(alpha = alpha * 0.9f),
            0.75f to variant,
            1f to outline
        )
        drawRoundRect(
            brush = sweep,
            style = Stroke(width = stroke.toPx()),
            cornerRadius = CornerRadius(corner.toPx())
        )
    }
}
