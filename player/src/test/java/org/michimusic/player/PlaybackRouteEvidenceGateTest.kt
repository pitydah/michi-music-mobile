package org.michimusic.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRouteEvidenceGateTest {
    @Test fun `AudioTrack PLAYING without Media3 playback is not route confirmation`() {
        val gate = PlaybackRouteEvidenceGate()
        assertFalse(gate.isCurrent(audioTrackPlaying = true))
    }

    @Test fun `Media3 PLAYING without AudioTrack PLAYING is not route confirmation`() {
        val gate = PlaybackRouteEvidenceGate()
        gate.onPlaybackChanged(true)
        assertFalse(gate.isCurrent(audioTrackPlaying = false))
    }

    @Test fun `only two active observations permit route confirmation`() {
        val gate = PlaybackRouteEvidenceGate()
        gate.onPlaybackChanged(true)
        assertTrue(gate.isCurrent(audioTrackPlaying = true))
    }

    @Test fun `paused Media3 revokes route confirmation without waiting for AudioTrack`() {
        val gate = PlaybackRouteEvidenceGate()
        gate.onPlaybackChanged(true)
        gate.onPlaybackChanged(false)
        assertFalse(gate.isCurrent(audioTrackPlaying = true))
    }

    @Test fun `service or observer release resets observation authority`() {
        val gate = PlaybackRouteEvidenceGate()
        gate.onPlaybackChanged(true)
        gate.reset()
        assertFalse(gate.isCurrent(audioTrackPlaying = true))
    }
}
