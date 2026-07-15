package com.aegisedge.os.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = SensePrimary,
    onPrimary = SenseOnPrimary,
    primaryContainer = SensePrimaryLight.copy(alpha = 0.2f),
    onPrimaryContainer = SensePrimaryDark,
    secondary = SenseSecondary,
    onSecondary = SenseOnSecondary,
    secondaryContainer = SenseSecondaryLight.copy(alpha = 0.15f),
    onSecondaryContainer = SenseSecondary,
    tertiary = SenseTertiary,
    onTertiary = SenseOnTertiary,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = SenseError,
    onError = SenseOnError,
    errorContainer = SenseErrorContainer,
    onErrorContainer = SenseOnErrorContainer,
)

private val DarkColorScheme = darkColorScheme(
    primary = SensePrimaryLight,
    onPrimary = SensePrimaryDark,
    primaryContainer = SensePrimary.copy(alpha = 0.3f),
    onPrimaryContainer = SensePrimaryLight,
    secondary = SenseSecondaryLight,
    onSecondary = SenseSecondary,
    secondaryContainer = SenseSecondary.copy(alpha = 0.25f),
    onSecondaryContainer = SenseSecondaryLight,
    tertiary = SenseTertiary,
    onTertiary = SenseOnTertiary,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = SenseError,
    onError = SenseOnError,
    errorContainer = SenseErrorContainer,
    onErrorContainer = SenseOnErrorContainer,
)

@Composable
fun SenseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SenseTypography,
        content = content,
    )
}
