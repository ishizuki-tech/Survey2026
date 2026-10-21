package com.negi.survey.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.negi.survey.BuildConfig

/**
 * One third-party component credited on the About screen.
 * Keep this list in sync with what the app actually ships or relies on.
 */
private data class Credit(
    val name: String,
    val role: String,
    val license: String,
    val url: String,
    val note: String? = null
)

private val CREDITS = listOf(
    Credit(
        name = "Swahili voice: Piper sw_CD-lanfrica-medium",
        role = "Reads Swahili survey questions aloud (via the sherpa-onnx TTS engine app).",
        license = "Training data: CC BY 4.0",
        url = "https://data.mendeley.com/datasets/vbvj6j6pm9/1",
        note = "Trained on \"A Kiswahili Dataset for Development of Text-To-Speech System\" by " +
            "Kiptoo Rono, Dedan Kimathi University of Technology. Voice model: " +
            "huggingface.co/rhasspy/piper-voices"
    ),
    Credit(
        name = "sherpa-onnx TTS Engine",
        role = "Separate text-to-speech app that runs the Swahili voice on the phone.",
        license = "Apache 2.0 (includes eSpeak NG data, GPL-3.0)",
        url = "https://github.com/k2-fsa/sherpa-onnx",
        note = "Installed separately; not bundled in this app."
    ),
    Credit(
        name = "Google Text-to-Speech",
        role = "Reads English survey questions aloud.",
        license = "Provided by Google on the device",
        url = "https://play.google.com/store/apps/details?id=com.google.android.tts"
    ),
    Credit(
        name = "whisper.cpp v1.9.3",
        role = "On-device speech recognition engine.",
        license = "MIT",
        url = "https://github.com/ggml-org/whisper.cpp"
    ),
    Credit(
        name = "OpenAI Whisper (small)",
        role = "English speech recognition model (ggml-small-q5_1).",
        license = "MIT",
        url = "https://huggingface.co/ggerganov/whisper.cpp"
    ),
    Credit(
        name = "Swahili Whisper model (dmusingu, Common Voice 14)",
        role = "Swahili speech recognition model (ggml-small-sw-cv14-q5_0).",
        license = "Apache 2.0",
        url = "https://huggingface.co/dmusingu/WHISPER-SMALL-SWAHILI-ASR-CV-14",
        note = "Fine-tuned on Mozilla Common Voice 14 (CC0)."
    ),
    Credit(
        name = "Gemma 3n E4B",
        role = "On-device language model for follow-up questions.",
        license = "Gemma Terms of Use",
        url = "https://ai.google.dev/gemma/terms",
        note = "Gemma is provided under and subject to the Gemma Terms of Use found at " +
            "ai.google.dev/gemma/terms."
    ),
    Credit(
        name = "LiteRT-LM",
        role = "On-device runtime for the language model.",
        license = "Apache 2.0",
        url = "https://github.com/google-ai-edge/LiteRT-LM"
    ),
    Credit(
        name = "Android Jetpack, Jetpack Compose, Kotlin",
        role = "App framework and UI.",
        license = "Apache 2.0",
        url = "https://developer.android.com/jetpack"
    ),
)

/**
 * Full-screen About page: app version/build, and credits and licences for the
 * voices, models and libraries the app uses.
 */
@Composable
fun AboutScreen(onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("About", style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .widthIn(max = 640.dp)
                ) {
                    Text("Survey Test App", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    InfoLine("Version", BuildConfig.DISPLAY_VERSION)
                    InfoLine("Branch", BuildConfig.GH_BRANCH.ifBlank { "—" })
                    InfoLine("Commit", BuildConfig.GIT_COMMIT_SHA.take(12).ifBlank { "—" })
                    InfoLine("Built", BuildConfig.BUILD_TIMESTAMP)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "App source code: MIT License, © IshizukiTech LLC.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(20.dp))
                    Text("Credits and licences", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))

                    CREDITS.forEach { credit ->
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        CreditItem(credit)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun CreditItem(credit: Credit) {
    val linkColor = MaterialTheme.colorScheme.primary
    Column {
        Text(credit.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(credit.role, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Licence: ${credit.license}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        credit.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = buildAnnotatedString {
                withLink(
                    LinkAnnotation.Url(
                        credit.url,
                        TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    )
                ) { append(credit.url) }
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
}
