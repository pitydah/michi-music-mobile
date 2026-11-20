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
    val disclosureCorner = 10.dp
    val screenSideInset = 20.dp
    val minTouchTarget = 48.dp
    // UI8-KILLCRITIC: the deck has no height policy of its own. A screen-derived
    // height was what made the Tarjetas card taller than the Lista hero; the card
    // now measures itself and the deck simply wraps it.

    // UI8-KILLCRITIC: the caption no longer sits on a black plate. This is the peak
    // opacity of the radial scrim that fades out behind the words; it must stay high
    // enough that a white title keeps AA contrast over a white banner, and low enough
    // that no rectangular edge is visible.
    const val captionScrimAlpha = 0.78f

    // Short finite transitions; reduced-motion alternatives stay unanimated.
    const val disclosureExpandMillis = 180
    const val deckExitMillis = 190
    const val searchFocusMillis = 150
    const val searchFilterMillis = 180
    const val searchResultMillis = 180
    const val searchDetailOpenMillis = 240
    const val searchDetailCloseMillis = 180
}
