package org.michimusic.mobile.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroSharedNowPlayingMotionTest {
    @Test fun sameTrackGetsOneStableArtworkIdentity() {
        assertEquals(aeroNowPlayingSharedKey("track-42"), aeroNowPlayingSharedKey("track-42"))
        assertFalse(aeroNowPlayingSharedKey("track-42") == aeroNowPlayingSharedKey("track-43"))
    }

    @Test fun missingTrackAndColdEntryNeverShare() {
        assertFalse(aeroNowPlayingShareAllowed(null, true, false, true))
        assertFalse(aeroNowPlayingShareAllowed("", true, false, true))
        assertFalse(aeroNowPlayingShareAllowed("track-42", false, false, true))
        assertFalse(aeroNowPlayingShareAllowed("track-42", true, false, false))
    }

    @Test fun identityMustRemainStableDuringNavigation() {
        assertTrue(aeroPlayerShareSameTrack("track-3", "track-3"))
        assertFalse(aeroPlayerShareSameTrack("track-3", "track-4"))
        assertFalse(aeroPlayerShareSameTrack("track-3", null))
        assertFalse(aeroPlayerShareSameTrack(null, "track-3"))
        assertFalse(aeroPlayerShareSameTrack("", ""))
    }

    @Test fun motionPreferenceIsAuthoritative() {
        assertTrue(aeroNowPlayingShareAllowed("track-42", true, false, true))
        assertFalse(aeroNowPlayingShareAllowed("track-42", true, true, true))
        assertFalse(aeroNowPlayingShareAllowed("track-42", true, false, true, highContrast = true))
    }
}
