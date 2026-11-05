package com.negi.survey.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SurveyActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val sizedModifier = modifier.heightIn(min = 48.dp)
    val padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    if (primary) {
        Button(onClick, sizedModifier, enabled, shape = CircleShape, contentPadding = padding, content = content)
    } else {
        FilledTonalButton(onClick, sizedModifier, enabled, shape = CircleShape, contentPadding = padding, content = content)
    }
}

@Composable
internal fun SurveyNavigationButtons(
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
    secondaryEnabled: Boolean = true
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SurveyActionButton(
            onClick = onSecondary,
            modifier = Modifier.widthIn(min = 112.dp),
            enabled = secondaryEnabled,
            primary = false
        ) {
            Icon(Icons.AutoMirrored.Outlined.NavigateBefore, secondaryLabel, Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(secondaryLabel, modifier = Modifier.weight(1f, fill = false))
        }
        SurveyActionButton(
            onClick = onPrimary,
            modifier = Modifier.widthIn(min = 112.dp),
            enabled = primaryEnabled
        ) {
            Text(primaryLabel, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Outlined.NavigateNext, primaryLabel, Modifier.size(24.dp))
        }
    }
}
