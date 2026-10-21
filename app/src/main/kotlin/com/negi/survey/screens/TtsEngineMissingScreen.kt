package com.negi.survey.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Full-screen error shown when the survey's required TTS voice isn't available.
 * English: Google TTS has no English voice downloaded (`tts.require_offline_voice`).
 * Swahili: the sherpa-onnx voice app isn't installed (`tts.require_engine`).
 *
 * Text is bilingual. The survey's own language comes first (Swahili survey: Swahili
 * first; English survey: English first).
 *
 * @param enginePackage Package name of the missing engine, shown for the supervisor.
 * @param languageCode Survey TTS language ("sw", "en", ...), which picks the wording.
 * @param onContinueWithoutVoice Starts the survey with read-aloud disabled.
 * @param onExit Main action: closes the app, so the voice app can be installed first.
 *   The check runs again the next time the survey is opened.
 */
@Composable
fun TtsEngineMissingScreen(
    enginePackage: String,
    languageCode: String,
    onContinueWithoutVoice: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val text = engineMissingText(languageCode)
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = text.title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = text.subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = text.body,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = text.bodySecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = enginePackage,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(28.dp))
                Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                    Text(text.exit)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onContinueWithoutVoice, modifier = Modifier.fillMaxWidth()) {
                    Text(text.continueWithoutVoice)
                }

            }
        }
    }
}

/** Wording for [TtsEngineMissingScreen]; primary language first, the other second. */
private data class EngineMissingText(
    val title: String,
    val subtitle: String,
    val body: String,
    val bodySecondary: String,
    val continueWithoutVoice: String,
    val exit: String
)

private fun engineMissingText(languageCode: String): EngineMissingText =
    when (languageCode.trim().lowercase()) {
        // English: only the offline-voice message (Google TTS must have an English voice
        // downloaded); there is no separate "app not installed" message.
        "en" -> EngineMissingText(
            title = "The English voice is not downloaded",
            subtitle = "Sauti ya Kiingereza haijapakuliwa",
            body = "Questions will not be read aloud. Please open Settings > Text-to-speech > " +
                "Google > Install voice data, download an English voice, then start this " +
                "survey application again.",
            bodySecondary = "Maswali hayatasomwa kwa sauti. Tafadhali fungua Settings > " +
                "Text-to-speech > Google > Install voice data, pakua sauti ya Kiingereza, " +
                "kisha uanzishe programu hii ya utafiti tena.",
            continueWithoutVoice = "Continue without voice · Endelea bila sauti",
            exit = "Exit the app · Funga programu"
        )
        // Swahili (and any other language): only the "voice app not installed" message.
        else -> EngineMissingText(
            title = "Programu ya sauti ya Kiswahili haijasakinishwa",
            subtitle = "The Swahili voice app is not installed",
            body = "Maswali hayatasomwa kwa sauti. Tafadhali sakinisha programu ya " +
                "Kiswahili ya kusoma maandishi kwa sauti (Text-to-Speech), kisha uanzishe " +
                "programu hii ya utafiti tena.",
            bodySecondary = "Questions will not be read aloud. Please install the Swahili " +
                "Text-to-Speech application, then start this survey application again.",
            continueWithoutVoice = "Endelea bila sauti · Continue without voice",
            exit = "Funga programu · Exit the app"
        )
    }

