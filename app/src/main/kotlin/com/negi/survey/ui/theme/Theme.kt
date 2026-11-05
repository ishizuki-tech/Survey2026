package com.negi.survey.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.Black,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightInk,
    inversePrimary = DarkPrimary,
    secondary = LightSecondary,
    onSecondary = Color.White,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightInk,
    tertiary = LightTertiary,
    onTertiary = Color.White,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightInk,
    background = LightBackground,
    onBackground = LightInk,
    surface = Color.White,
    onSurface = LightInk,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    inverseSurface = DarkSurface,
    inverseOnSurface = Color.White,
    error = LightError,
    onError = Color.White,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = LightSurfaceDim,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = LightBackground,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceVariant,
    primaryFixed = LightPrimaryContainer,
    primaryFixedDim = DarkPrimary,
    onPrimaryFixed = LightInk,
    onPrimaryFixedVariant = DarkPrimaryContainer,
    secondaryFixed = LightSecondaryContainer,
    secondaryFixedDim = DarkSecondary,
    onSecondaryFixed = DarkOnSecondary,
    onSecondaryFixedVariant = DarkSecondaryContainer,
    tertiaryFixed = LightTertiaryContainer,
    tertiaryFixedDim = DarkTertiary,
    onTertiaryFixed = DarkOnTertiary,
    onTertiaryFixedVariant = DarkTertiaryContainer,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color.Black,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = Color.White,
    inversePrimary = DarkInversePrimary,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = LightSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = LightTertiaryContainer,
    background = DarkBackground,
    onBackground = Color.White,
    surface = DarkSurface,
    onSurface = Color.White,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    inverseSurface = LightBackground,
    inverseOnSurface = LightInk,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = Color.Black,
    surfaceBright = DarkSurfaceBright,
    surfaceDim = DarkBackground,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceVariant,
    primaryFixed = LightPrimaryContainer,
    primaryFixedDim = DarkPrimary,
    onPrimaryFixed = LightInk,
    onPrimaryFixedVariant = DarkPrimaryContainer,
    secondaryFixed = LightSecondaryContainer,
    secondaryFixedDim = DarkSecondary,
    onSecondaryFixed = DarkOnSecondary,
    onSecondaryFixedVariant = DarkSecondaryContainer,
    tertiaryFixed = LightTertiaryContainer,
    tertiaryFixedDim = DarkTertiary,
    onTertiaryFixed = DarkOnTertiary,
    onTertiaryFixedVariant = DarkTertiaryContainer,
)

@Composable
fun SurveyNavTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
