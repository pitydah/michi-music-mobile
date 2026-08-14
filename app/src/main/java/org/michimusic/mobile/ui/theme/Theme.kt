package org.michimusic.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

@Composable
fun MichiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LuminousDarkColorScheme,
        typography = Typography,
        content = content,
    )
}
