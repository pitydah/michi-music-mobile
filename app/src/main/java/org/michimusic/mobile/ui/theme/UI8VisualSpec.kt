package org.michimusic.mobile.ui.theme

import androidx.compose.ui.unit.dp

/**
 * UI8 visual-design contract for the refinements introduced by UI8-01..04.
 * Keeps dimensions and motion settings adjacent to their real Compose consumers.
 * These are presentation tokens only; Media3, metadata, SAF and data identity
 * remain owned by the existing domain code.
 *
 * Visual acceptance still requires screenshots on Android devices.
 */
internal object UI8VisualSpec {
    // Overlay and image geometry
    val popupCorner = 18.dp
    val playlistHeroCorner = 20.dp
    val captionCorner = 12.dp
    val disclosureCorner = 10.dp
    val screenSideInset = 20.dp
    val minTouchTarget = 48.dp
    val playlistDeckHeight = 278.dp
    val playlistDeckMinHeight = 210.dp

    /** Proportionate to the screen; small devices may scroll the surrounding panel. */
    fun playlistDeckHeightFor(screenHeightDp: Int) =
        (screenHeightDp.coerceAtLeast(1) * 0.40f).dp.coerceIn(playlistDeckMinHeight, playlistDeckHeight)

    // Only behind the caption; NEVER over the entire playlist banner.
    const val captionMatteAlpha = 0.72f

    // Short finite transitions; reduced-motion alternatives stay unanimated.
    const val disclosureExpandMillis = 180
    const val deckExitMillis = 190
    const val searchFocusMillis = 150
    const val searchFilterMillis = 180
    const val searchResultMillis = 180
    const val searchDetailOpenMillis = 240
    const val searchDetailCloseMillis = 180
}
