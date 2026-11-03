package org.michimusic.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** WCAG 2.2 relative luminance for sRGB. Material overlays are composited before scoring. */
private fun aeroLinearChannel(encoded: Double): Double =
    if (encoded <= 0.04045) encoded / 12.92 else ((encoded + 0.055) / 1.055).pow(2.4)

private fun aeroComposite(foreground: Color, opaqueBackground: Color): Color {
    val opacity = foreground.alpha.toDouble().coerceIn(0.0, 1.0)
    val inverse = 1.0 - opacity
    return Color(
        red = (foreground.red * opacity + opaqueBackground.red * inverse).toFloat().coerceIn(0f, 1f),
        green = (foreground.green * opacity + opaqueBackground.green * inverse).toFloat().coerceIn(0f, 1f),
        blue = (foreground.blue * opacity + opaqueBackground.blue * inverse).toFloat().coerceIn(0f, 1f),
        alpha = 1f,
    )
}

private fun aeroLuminance(opaque: Color): Double =
    0.2126 * aeroLinearChannel(opaque.red.toDouble()) +
        0.7152 * aeroLinearChannel(opaque.green.toDouble()) +
        0.0722 * aeroLinearChannel(opaque.blue.toDouble())

/**
 * Effective text/surface contrast (including alpha compositing over Aero canvas).
 * This is a color-token check, not certification of TalkBack or rendered screenshots.
 */
fun aeroContrastRatio(
    foreground: Color,
    background: Color,
    canvas: Color = SurfaceObsidian,
): Double {
    val floor = aeroComposite(canvas, Color.Black)
    val opaqueBackground = aeroComposite(background, floor)
    val opaqueForeground = aeroComposite(foreground, opaqueBackground)
    val a = aeroLuminance(opaqueForeground)
    val b = aeroLuminance(opaqueBackground)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
}

/** Safe fallback for default text or contextual accent, preserving its tint when already AA. */
fun aeroReadableInk(
    preferred: Color,
    background: Color,
    minimumContrast: Double = 4.5,
): Color {
    require(minimumContrast in 1.0..21.0) { "Invalid WCAG contrast threshold" }
    if (aeroContrastRatio(preferred, background) >= minimumContrast) return preferred
    val white = aeroContrastRatio(Color.White, background)
    val black = aeroContrastRatio(Color.Black, background)
    return if (white >= black) Color.White else Color.Black
}
