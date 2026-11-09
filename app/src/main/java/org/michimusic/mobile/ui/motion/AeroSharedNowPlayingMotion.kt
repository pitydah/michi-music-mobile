package org.michimusic.mobile.ui.motion

/**
 * One local, reversible player transition policy.
 *
 * The previous shared-element path could animate the same thumbnail through the
 * MiniPlayer's visibility transition and the immersive Scaffold geometry at the
 * same time. Physical QA demonstrated detached artwork on the reverse path, so
 * player artwork is intentionally never shared. Album shared elements retain
 * their separate policy and coordinate space.
 */
data class AeroNowPlayingTransitionPolicy(
    val shareArtwork: Boolean,
    val animateMiniPlayerVisibility: Boolean,
    val animateNowPlayingHero: Boolean,
)

fun aeroNowPlayingTransitionPolicy(
    reducedMotion: Boolean = false,
    highContrast: Boolean = false,
): AeroNowPlayingTransitionPolicy {
    val animateLocally = !reducedMotion && !highContrast
    return AeroNowPlayingTransitionPolicy(
        shareArtwork = false,
        animateMiniPlayerVisibility = animateLocally,
        animateNowPlayingHero = animateLocally,
    )
}
