package org.michimusic.mobile.ui.screens

import androidx.compose.ui.graphics.Color
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.TertiaryCyan

/** Presentation-only priority: drop target wins over current track. */
internal enum class PlaylistRowEmphasis { NONE, CURRENT_PAUSED, CURRENT_PLAYING, DROP_TARGET }

internal data class PlaylistRowOutline(val widthDp: Float, val tint: Color)

internal fun playlistRowEmphasis(
    isObservedActive: Boolean,
    isObservedPlaying: Boolean,
    dropTarget: Boolean,
): PlaylistRowEmphasis = when {
    dropTarget -> PlaylistRowEmphasis.DROP_TARGET
    !isObservedActive -> PlaylistRowEmphasis.NONE
    isObservedPlaying -> PlaylistRowEmphasis.CURRENT_PLAYING
    else -> PlaylistRowEmphasis.CURRENT_PAUSED
}

/** No new surface or effects; use existing Aero tokens and visible high-contrast edges. */
internal fun playlistRowOutline(emphasis: PlaylistRowEmphasis, highContrast: Boolean): PlaylistRowOutline? =
    when (emphasis) {
        PlaylistRowEmphasis.NONE -> null
        PlaylistRowEmphasis.DROP_TARGET -> PlaylistRowOutline(
            widthDp = if (highContrast) 2.0f else 1.5f,
            tint = PrimaryPinkContainer,
        )
        PlaylistRowEmphasis.CURRENT_PLAYING -> PlaylistRowOutline(
            widthDp = if (highContrast) 1.5f else 1.0f,
            tint = TertiaryCyan.copy(alpha = if (highContrast) 1f else 0.80f),
        )
        PlaylistRowEmphasis.CURRENT_PAUSED -> PlaylistRowOutline(
            widthDp = if (highContrast) 1.5f else 0.9f,
            tint = TertiaryCyan.copy(alpha = if (highContrast) 0.92f else 0.48f),
        )
    }
