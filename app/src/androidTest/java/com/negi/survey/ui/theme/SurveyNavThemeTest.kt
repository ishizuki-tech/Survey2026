package com.negi.survey.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurveyNavThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun systemLightModeUsesFixedOutdoorPaletteByDefault() {
        assertFixedPalette(Configuration.UI_MODE_NIGHT_NO) { scheme ->
            assertEquals(Color(0xFF42A5F5), scheme.primary)
            assertEquals(Color.Black, scheme.onPrimary)
            assertEquals(Color(0xFFF7F8FA), scheme.background)
            assertEquals(Color(0xFF111111), scheme.onBackground)
            assertEquals(Color.White, scheme.surface)
            assertEquals(Color(0xFF111111), scheme.onSurface)
            assertEquals(Color(0xFF353535), scheme.onSurfaceVariant)
            assertEquals(Color(0xFFDCEEFF), scheme.primaryContainer)
            assertEquals(Color(0xFF111111), scheme.onPrimaryContainer)
            assertEquals(Color(0xFF455A64), scheme.outline)
            assertEquals(Color(0xFFB3261E), scheme.error)
        }
    }

    @Test
    fun systemDarkModeUsesFixedDarkPaletteByDefault() {
        assertFixedPalette(Configuration.UI_MODE_NIGHT_YES) { scheme ->
            assertEquals(Color(0xFF90CAF9), scheme.primary)
            assertEquals(Color.Black, scheme.onPrimary)
            assertEquals(Color(0xFF121212), scheme.background)
            assertEquals(Color.White, scheme.onBackground)
            assertEquals(Color(0xFF202428), scheme.surface)
            assertEquals(Color.White, scheme.onSurface)
            assertEquals(Color(0xFF18354D), scheme.primaryContainer)
            assertEquals(Color.White, scheme.onPrimaryContainer)
            assertEquals(Color(0xFFAEB8C2), scheme.outline)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun explicitDynamicColorUsesAndroidPalette() {
        compose.setContent {
            val context = LocalContext.current
            val expectedLight = dynamicLightColorScheme(context)
            val expectedDark = dynamicDarkColorScheme(context)
            SurveyNavTheme(darkTheme = false, dynamicColor = true) {
                val actual = MaterialTheme.colorScheme
                SideEffect {
                    assertEquals(expectedLight.primary, actual.primary)
                    assertEquals(expectedLight.surface, actual.surface)
                }
            }
            SurveyNavTheme(darkTheme = true, dynamicColor = true) {
                val actual = MaterialTheme.colorScheme
                SideEffect {
                    assertEquals(expectedDark.primary, actual.primary)
                    assertEquals(expectedDark.surface, actual.surface)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun providesSharedTypographyAndShapes() {
        compose.setContent {
            SurveyNavTheme {
                val typography = MaterialTheme.typography
                val shapes = MaterialTheme.shapes
                SideEffect {
                    assertStyle("headlineSmall", typography.headlineSmall, 24, FontWeight.Bold, 32)
                    assertStyle("titleLarge", typography.titleLarge, 20, FontWeight.SemiBold, 28)
                    assertStyle("bodyLarge", typography.bodyLarge, 18, FontWeight.Normal, 26)
                    assertStyle("labelLarge", typography.labelLarge, 18, FontWeight.SemiBold, 24)
                    assertStyle("bodyMedium", typography.bodyMedium, 16, FontWeight.Normal, 24)
                    assertStyle("labelMedium", typography.labelMedium, 14, FontWeight.Medium, 20)
                    assertEquals(RoundedCornerShape(8.dp), shapes.small)
                    assertEquals(RoundedCornerShape(12.dp), shapes.medium)
                    assertEquals(RoundedCornerShape(16.dp), shapes.large)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertFixedPalette(nightMode: Int, assertRequiredColors: (ColorScheme) -> Unit) {
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                SurveyNavTheme {
                    val scheme = MaterialTheme.colorScheme
                    SideEffect {
                        assertRequiredColors(scheme)
                        assertPaletteContrast(scheme)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertStyle(role: String, style: TextStyle, size: Int, weight: FontWeight, height: Int) {
        assertEquals(role, size.sp, style.fontSize)
        assertEquals(role, weight, style.fontWeight)
        assertEquals(role, height.sp, style.lineHeight)
        assertEquals(role, FontFamily.Default, style.fontFamily)
    }

    private fun assertPaletteContrast(scheme: ColorScheme) = with(scheme) {
        assertContrast("onPrimary / primary", onPrimary, primary, 7.0)
        assertContrast("onPrimaryContainer / primaryContainer", onPrimaryContainer, primaryContainer)
        assertContrast("onSecondary / secondary", onSecondary, secondary)
        assertContrast("onSecondaryContainer / secondaryContainer", onSecondaryContainer, secondaryContainer)
        assertContrast("onTertiary / tertiary", onTertiary, tertiary)
        assertContrast("onTertiaryContainer / tertiaryContainer", onTertiaryContainer, tertiaryContainer)
        assertContrast("onBackground / background", onBackground, background)
        assertContrast("onSurface / surface", onSurface, surface)
        assertContrast("onSurfaceVariant / surfaceVariant", onSurfaceVariant, surfaceVariant)
        assertContrast("onSurfaceVariant / surface", onSurfaceVariant, surface)
        assertContrast("inverseOnSurface / inverseSurface", inverseOnSurface, inverseSurface)
        assertContrast("inversePrimary / inverseSurface", inversePrimary, inverseSurface)
        assertContrast("onError / error", onError, error)
        assertContrast("onErrorContainer / errorContainer", onErrorContainer, errorContainer)
        assertContrast("error / surface", error, surface)
        assertContrast("outline / surface", outline, surface, 3.0)
    }

    private fun assertContrast(role: String, foreground: Color, background: Color, minimum: Double = 4.5) {
        val foregroundLuminance = relativeLuminance(foreground)
        val backgroundLuminance = relativeLuminance(background)
        val ratio = (maxOf(foregroundLuminance, backgroundLuminance) + 0.05) /
            (minOf(foregroundLuminance, backgroundLuminance) + 0.05)
        assertTrue("$role contrast is $ratio, expected at least $minimum", ratio >= minimum)
    }

    // WCAG 2.2 relative luminance for sRGB.
    private fun relativeLuminance(color: Color): Double {
        val argb = color.toArgb()
        val red = linearChannel(argb ushr 16 and 0xFF)
        val green = linearChannel(argb ushr 8 and 0xFF)
        val blue = linearChannel(argb and 0xFF)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    private fun linearChannel(channel: Int): Double {
        val srgb = channel / 255.0
        return if (srgb <= 0.04045) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
    }
}
