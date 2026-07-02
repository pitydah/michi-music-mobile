package org.michimusic.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentCoral,
    onPrimary = SurfaceDark,
    primaryContainer = BrandCoralFaint,
    secondary = AccentPink,
    onSecondary = SurfaceDark,
    background = SurfaceDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceGlass,
    outline = SurfaceBorder,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
)

@Composable
fun MichiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}
