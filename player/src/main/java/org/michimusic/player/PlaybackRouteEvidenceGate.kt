package org.michimusic.player

/**
 * Media3's current playback state and the AudioTrack's playState are separate
 * signals. A route is CURRENT only while both agree that PCM is playing.
 * An AudioTrack can transiently report PLAYING after Media3 paused; treating
 * that as a current confirmation produces stale Signal Truth observations.
 * No Android APIs: deterministic JVM regression tests cover transitions.
 */
internal class PlaybackRouteEvidenceGate {
    private var media3Playing: Boolean = false

    fun onPlaybackChanged(isPlaying: Boolean) {
        media3Playing = isPlaying
    }

    fun isCurrent(audioTrackPlaying: Boolean): Boolean = media3Playing && audioTrackPlaying

    fun reset() { media3Playing = false }
}
