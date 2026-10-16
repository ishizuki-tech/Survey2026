package com.negi.survey.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Hand-tuned grayscale ("monotone") colors shared by the app's custom-drawn
 * chrome: the full-screen background gradient behind every node screen
 * (MainActivity's appBackplate()) and its decorative edge sweep
 * (neutralEdge()), the Intro/config-selector screen (IntroScreen.kt), and
 * the brief pre-survey model-download/init screens (AppViewModel's
 * DownloadGate).
 *
 * These composables intentionally use a strict black/gray/white palette
 * rather than Material3's tinted colorScheme surfaces (which only customize
 * primary/secondary/tertiary — see Theme.kt), so they're kept in one place
 * here instead of each hardcoding its own hex values. Both variants mirror
 * the same visual relationships (background at one extreme, a card a step
 * in from that, text at near-opposite contrast, borders mid-gray) so the
 * app reads the same shape regardless of which one is active.
 *
 * [monotoneColors] picks between them using [isSystemInDarkTheme], the same
 * signal [SurveyNavTheme] uses for `MaterialTheme.colorScheme` — so this
 * chrome and the rest of the app always agree on Light vs Dark.
 */
data class MonotoneColors(
    /** appBackplate()/DownloadGate's full-screen gradient. */
    val screenTop: Color,
    val screenBottom: Color,
    /** neutralEdge()'s decorative sweep-gradient border. */
    val edgeSweepDark: Color,
    val edgeSweepMid: Color,
    /** IntroScreen's animated full-screen gradient (4 stops). */
    val introBgStops: List<Color>,
    /** IntroScreen's main card. */
    val cardBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textHint: Color,
    /** Tappable action text ("Show more", "Retry", "Clear List", …). */
    val actionText: Color,
    val errorText: Color,
    val errorTextMuted: Color,
    /** IntroScreen card's decorative sweep-gradient border. */
    val cardBorderDark: Color,
    val cardBorderMid: Color,
    val cardBorderLight: Color,
    /** "Survey Test App" headline gradient. */
    val headlineTop: Color,
    val headlineBottom: Color,
    /** Primary Start button. */
    val buttonContainer: Color,
    val buttonContent: Color,
    /** Outlined (Restart) button. */
    val outlineBorder: Color,
    val outlineContent: Color,
    /** Small key/value meta chip. */
    val chipBg: Color,
    val chipBorder: Color,
    val chipLabel: Color,
    val chipValue: Color,
    /** Config-selector option rows. */
    val optionSelectedBg: Color,
    val optionUnselectedBg: Color,
    val optionSelectedBorder: Color,
    val optionUnselectedBorder: Color,
    val optionUnselectedLabel: Color,
    /** Selected-config "show more" details panel. */
    val detailsPanelBg: Color,
    val detailsPanelBorder: Color,
)

private val DarkMonotoneColors = MonotoneColors(
    screenTop = Color(0xFF202020),
    screenBottom = Color(0xFF040404),
    edgeSweepDark = Color(0xFF101010),
    edgeSweepMid = Color(0xFF3A3A3A),
    introBgStops = listOf(
        Color(0xFF080808),
        Color(0xFF101010),
        Color(0xFF181818),
        Color(0xFF222222),
    ),
    cardBg = Color(0xFF101010),
    textPrimary = Color(0xFFF2F2F2),
    textSecondary = Color(0xFFD2D2D2),
    textMuted = Color(0xFFB0B0B0),
    textHint = Color(0xFF9A9A9A),
    actionText = Color(0xFFE6E6E6),
    errorText = Color(0xFFFFC8C8),
    errorTextMuted = Color(0xFFE0A0A0),
    cardBorderDark = Color(0xFF2A2A2A),
    cardBorderMid = Color(0xFF7A7A7A),
    cardBorderLight = Color(0xFFF0F0F0),
    headlineTop = Color(0xFFF5F5F5),
    headlineBottom = Color(0xFFCFCFCF),
    buttonContainer = Color(0xFF1A1A1A),
    buttonContent = Color(0xFFF2F2F2),
    outlineBorder = Color(0xFF8A8A8A),
    outlineContent = Color(0xFFE0E0E0),
    chipBg = Color(0xFF151515),
    chipBorder = Color(0xFF8A8A8A),
    chipLabel = Color(0xFFBDBDBD),
    chipValue = Color(0xFFF0F0F0),
    optionSelectedBg = Color(0xFF1A1A1A),
    optionUnselectedBg = Color(0xFF141414),
    optionSelectedBorder = Color(0xFFE6E6E6),
    optionUnselectedBorder = Color(0xFF8A8A8A),
    optionUnselectedLabel = Color(0xFFE8E8E8),
    detailsPanelBg = Color(0xFF0E0E0E),
    detailsPanelBorder = Color(0xFF7A7A7A),
)

private val LightMonotoneColors = MonotoneColors(
    screenTop = Color(0xFFF2F2F2),
    screenBottom = Color(0xFFDCDCDC),
    edgeSweepDark = Color(0xFFE6E6E6),
    edgeSweepMid = Color(0xFFBDBDBD),
    introBgStops = listOf(
        Color(0xFFFAFAFA),
        Color(0xFFF2F2F2),
        Color(0xFFEAEAEA),
        Color(0xFFDEDEDE),
    ),
    cardBg = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF1A1A1A),
    textSecondary = Color(0xFF3A3A3A),
    textMuted = Color(0xFF5C5C5C),
    textHint = Color(0xFF707070),
    actionText = Color(0xFF262626),
    errorText = Color(0xFFB3261E),
    errorTextMuted = Color(0xFF8C3A3A),
    cardBorderDark = Color(0xFFE2E2E2),
    cardBorderMid = Color(0xFFABABAB),
    cardBorderLight = Color(0xFF2A2A2A),
    headlineTop = Color(0xFF181818),
    headlineBottom = Color(0xFF3B3B3B),
    buttonContainer = Color(0xFFE4E4E4),
    buttonContent = Color(0xFF1A1A1A),
    outlineBorder = Color(0xFF9E9E9E),
    outlineContent = Color(0xFF2E2E2E),
    chipBg = Color(0xFFECECEC),
    chipBorder = Color(0xFF9E9E9E),
    chipLabel = Color(0xFF5C5C5C),
    chipValue = Color(0xFF1A1A1A),
    optionSelectedBg = Color(0xFFE4E4E4),
    optionUnselectedBg = Color(0xFFF1F1F1),
    optionSelectedBorder = Color(0xFF2E2E2E),
    optionUnselectedBorder = Color(0xFF9E9E9E),
    optionUnselectedLabel = Color(0xFF3A3A3A),
    detailsPanelBg = Color(0xFFF1F1F1),
    detailsPanelBorder = Color(0xFF9E9E9E),
)

@Composable
fun monotoneColors(): MonotoneColors =
    if (isSystemInDarkTheme()) DarkMonotoneColors else LightMonotoneColors
