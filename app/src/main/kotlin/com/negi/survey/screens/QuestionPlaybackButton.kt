package com.negi.survey.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

@Composable
internal fun QuestionPlaybackButton(
    onClick: () -> Unit,
    isSpeaking: Boolean,
    speakerColor: Color = Color.Unspecified,
    speakerStyle: TextStyle = LocalTextStyle.current
) {
    val stopShape = RoundedCornerShape(6.dp)
    IconButton(
        onClick = onClick,
        shape = if (isSpeaking) stopShape else IconButtonDefaults.standardShape
    ) {
        if (isSpeaking) {
            Box(
                modifier = Modifier.size(24.dp).background(Color(0xFFD65A4A), stopShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(18.dp)
                        .semantics { text = AnnotatedString("\u23F9") }
                )
            }
        } else {
            Text("\uD83D\uDD0A", color = speakerColor, style = speakerStyle)
        }
    }
}
