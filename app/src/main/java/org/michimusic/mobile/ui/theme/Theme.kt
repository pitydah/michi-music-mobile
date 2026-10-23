package org.michimusic.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalVisualPreferences = compositionLocalOf { VisualPreferences() }

private val LuminousDarkColorScheme = darkColorScheme(
    primary = PrimaryPink,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryPinkContainer,
    onPrimaryContainer = OnPrimaryContainer,
    inversePrimary = Color(0xFF894E50),
    secondary = SecondaryPurple,
    onSecondary = Color(0xFF40215E),
    secondaryContainer = SecondaryPurpleContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = TertiaryCyan,
    onTertiary = Color(0xFF00363B),
    tertiaryContainer = TertiaryCyanContainer,
    onTertiaryContainer = Color(0xFF005C62),
    background = BackgroundDark,
    onBackground = OnSurface,
    surface = SurfaceDark,
    onSurface = OnSurface,
    surfaceVariant = SurfaceContainerHighest,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    surfaceBright = SurfaceBright,
    surfaceDim = SurfaceDark,
    outline = OutlineColor,
    outlineVariant = OutlineVariant,
    error = ErrorColor,
    onError = Color(0xFF690005),
    errorContainer = ErrorContainer,
    onErrorContainer = Color(0xFFFFDAD6),
)

private val HighContrastDarkColorScheme = darkColorScheme(
    primary = PureWhite,
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFFFF5167),
    onPrimaryContainer = PureWhite,
    inversePrimary = PureWhite,
    secondary = Color(0xFFE2C4FF),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF583876),
    onSecondaryContainer = PureWhite,
    tertiary = Color(0xFF9EFFFF),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF00A0AA),
    onTertiaryContainer = PureWhite,
    background = Color(0xFF000000),
    onBackground = PureWhite,
    surface = Color(0xFF000000),
    onSurface = PureWhite,
    surfaceVariant = Color(0xFF1E2028),
    onSurfaceVariant = PureWhite,
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF12141A),
    surfaceContainer = Color(0xFF181B22),
    surfaceContainerHigh = Color(0xFF262832),
    surfaceContainerHighest = Color(0xFF333644),
    surfaceBright = Color(0xFF404354),
    surfaceDim = Color(0xFF000000),
    outline = PureWhite,
    outlineVariant = Color(0xFF888899),
    error = Color(0xFFFF8888),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFFB00020),
    onErrorContainer = PureWhite,
)

@Composable
fun MichiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    visualPreferences: VisualPreferences = VisualPreferences(),
    content: @Composable () -> Unit,
) {
    val colors = if (visualPreferences.highContrast) {
        HighContrastDarkColorScheme
    } else {
        LuminousDarkColorScheme
    }

    CompositionLocalProvider(
        LocalVisualPreferences provides visualPreferences
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = Typography,
            content = content,
        )
    }
}
