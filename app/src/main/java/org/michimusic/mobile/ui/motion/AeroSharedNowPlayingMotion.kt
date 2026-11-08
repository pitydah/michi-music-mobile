@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package org.michimusic.mobile.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * True native shared artwork: one key for the SAME active track in the mini-player
 * and Now Playing. The transport state remains owned by PlaybackSessionManager.
 * The layout has no independent playback collection and never opens another player.
 */
val LocalAeroPlayerShareEnabled = compositionLocalOf { false }

fun aeroNowPlayingSharedKey(trackId: String): String = "aero-nowplaying-art:$trackId"

/** A transition may only connect two snapshots of the same playable track. */
fun aeroPlayerShareSameTrack(sourceTrackId: String?, destinationTrackId: String?): Boolean =
    !sourceTrackId.isNullOrBlank() && sourceTrackId == destinationTrackId

fun aeroNowPlayingShareAllowed(
    trackId: String?,
    shareEnabled: Boolean,
    reducedMotion: Boolean,
    scopesReady: Boolean,
    highContrast: Boolean = false,
): Boolean = !trackId.isNullOrBlank() && shareEnabled && !reducedMotion && !highContrast && scopesReady

/** This is also the guard for the duplicate scale-in hero on the destination. */
@Composable
fun aeroNowPlayingShareAvailable(trackId: String?): Boolean = aeroNowPlayingShareAllowed(
    trackId = trackId,
    shareEnabled = LocalAeroPlayerShareEnabled.current,
    reducedMotion = org.michimusic.mobile.ui.theme.LocalVisualPreferences.current.reducedMotion,
    highContrast = org.michimusic.mobile.ui.theme.LocalVisualPreferences.current.highContrast,
    scopesReady = LocalAeroSharedTransitionScope.current != null &&
        LocalAeroAnimatedVisibilityScope.current != null,
)

/**
 * [visibilityScope] is supplied by the MiniPlayer's own AnimatedVisibility and
 * defaults to NavHost's destination scope in Now Playing. No global crossfade.
 * A simultaneous skip changes the key, therefore it cannot morph two DIFFERENT
 * covers. Compose falls back to showing the current truthful artwork instead.
 */
@Composable
fun Modifier.aeroSharedNowPlayingArtwork(
    trackId: String?,
    visibilityScope: AnimatedVisibilityScope? = null,
): Modifier {
    val shared = LocalAeroSharedTransitionScope.current
    val destinationScope = visibilityScope ?: LocalAeroAnimatedVisibilityScope.current
    val allowed = aeroNowPlayingShareAllowed(
        trackId, LocalAeroPlayerShareEnabled.current,
        org.michimusic.mobile.ui.theme.LocalVisualPreferences.current.reducedMotion,
        shared != null && destinationScope != null,
        highContrast = org.michimusic.mobile.ui.theme.LocalVisualPreferences.current.highContrast,
    )
    if (!allowed) return this
    return with(shared!!) {
        this@aeroSharedNowPlayingArtwork.sharedElement(
            rememberSharedContentState(key = aeroNowPlayingSharedKey(trackId!!)),
            animatedVisibilityScope = destinationScope!!,
        )
    }
}
