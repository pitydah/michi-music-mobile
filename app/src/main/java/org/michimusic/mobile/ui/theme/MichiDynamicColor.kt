package org.michimusic.mobile.ui.theme

import androidx.compose.ui.graphics.Color

fun michiAccentFor(seed: String?): Color {
    val palette = listOf(
        AccentCoral,
        AccentPink,
        AccentPurple,
        AccentBlue,
        Color(0xFFE3B769),
        Color(0xFF55D6B3),
    )
    if (seed.isNullOrBlank()) return AccentCoral
    return palette[(seed.hashCode() and Int.MAX_VALUE) % palette.size]
}
