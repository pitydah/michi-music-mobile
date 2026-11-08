@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package org.michimusic.mobile.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/** Navigation 2 passes these scopes only to its library/album destinations. */
val LocalAeroSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAeroAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }
/** A detail opened from artists has no matching mosaic source; keep its existing reveal. */
val LocalAeroAlbumShareEnabled = compositionLocalOf { false }

/** Stable per scoped album identity, never a CoverFlow position or image URL. */
fun aeroAlbumSharedKey(albumIdentity: String): String = "aero-album-art:$albumIdentity"

/** Other screen pairs remain instant: the old M52 NavHost policy still applies. */
fun aeroAlbumRouteTransitionAllowed(fromRoute: String?, toRoute: String?, reducedMotion: Boolean): Boolean =
    !reducedMotion && (
        (fromRoute == "library" && toRoute == "album/{albumId}") ||
            (fromRoute == "album/{albumId}" && toRoute == "library")
    )

/** Pure guard: ambiguous or missing identities and reduced motion never share artwork. */
fun aeroAlbumShareAllowed(
    albumIdentity: String?,
    eligible: Boolean,
    reducedMotion: Boolean,
    transitionAvailable: Boolean,
    highContrast: Boolean = false,
): Boolean = albumIdentity?.isNotEmpty() == true && eligible && !reducedMotion &&
    !highContrast && transitionAvailable

@Composable
fun aeroSharedAlbumAvailable(albumIdentity: String?, reducedMotion: Boolean): Boolean =
    aeroAlbumShareAllowed(
        albumIdentity = albumIdentity,
        eligible = LocalAeroAlbumShareEnabled.current,
        reducedMotion = reducedMotion,
        highContrast = org.michimusic.mobile.ui.theme.LocalVisualPreferences.current.highContrast,
        transitionAvailable = LocalAeroSharedTransitionScope.current != null &&
            LocalAeroAnimatedVisibilityScope.current != null,
    )

/**
 * The actual destination/root artwork is the shared element, not a duplicated bitmap.
 * When a source is removed by rescanning or the library is not ready in time, NavHost
 * simply renders the destination artwork without fabricating an alternate album.
 */
@Composable
fun Modifier.aeroSharedAlbumArtwork(
    albumIdentity: String?,
    eligible: Boolean,
    reducedMotion: Boolean,
): Modifier {
    val shared = LocalAeroSharedTransitionScope.current
    val visibility = LocalAeroAnimatedVisibilityScope.current
    if (!aeroAlbumShareAllowed(
        albumIdentity, eligible && LocalAeroAlbumShareEnabled.current,
        reducedMotion, shared != null && visibility != null,
        highContrast = org.michimusic.mobile.ui.theme.LocalVisualPreferences.current.highContrast,
    )) return this
    // All values are non-null after the guard; the key comes from library scope identity.
    return with(shared!!) {
        this@aeroSharedAlbumArtwork.sharedElement(
            rememberSharedContentState(key = aeroAlbumSharedKey(albumIdentity!!)),
            animatedVisibilityScope = visibility!!,
        )
    }
}
