package org.michimusic.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTransitionEpochTest {
    @Test fun delayedPreviousTrackCannotOverrideNextTrack() {
        val guard = PlaybackTransitionEpoch()
        val a = guard.advance()
        val b = guard.advance()
        assertFalse(guard.isCurrent(a))
        assertTrue(guard.isCurrent(b))
    }

    @Test fun repeatedMediaIdStillRejectsStaleLookup() {
        // Both queue entries can have the same mediaId; generations are distinct.
        val guard = PlaybackTransitionEpoch()
        val firstOccurrence = guard.advance()
        val secondOccurrence = guard.advance()
        assertFalse(guard.isCurrent(firstOccurrence))
        assertTrue(guard.isCurrent(secondOccurrence))
        guard.advance() // service terminated
        assertFalse(guard.isCurrent(secondOccurrence))
    }
}
