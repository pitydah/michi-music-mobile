package org.michimusic.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app has three glass levels, and only the level varies between screens:
 * the hue stays in the obsidian/graphite family (#090C12 floor, #1B2029 raised).
 * Screens used to invent their own gradients, so Home was blue-grey, Now Playing
 * violet, the dock warm grey and Devices flat grey in the same session. A new
 * surface must reuse a level here instead of adding another gradient.
 */
object MichiSurfaces {
    /** Sunken panels: inline errors, dialog bodies, secondary rows. */
    val base: List<Color> = listOf(Color(0xFF1E222B), Color(0xFF171A21))

    /** Opaque variant of [base] for containers that cannot take a brush. */
    val baseSolid: Color = Color(0xFF1E222B)

    /** Floating glass: dock, search field, cards resting above the floor. */
    val raised: List<Color> = listOf(Color(0xFF272D39), Color(0xFF1E232C), AeroGraphite)

    /** Hero regions that sit on a card: the library feature block. */
    val hero: List<Color> = listOf(Color(0xFF323C4A), Color(0xFF252E3A), AeroGraphite)

    /** Hero regions that fade into the screen floor: Now Playing. */
    val heroFade: List<Color> =
        listOf(Color(0xFF323C4A), Color(0xFF252E3A), SurfaceObsidian)
}
