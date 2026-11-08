package org.michimusic.mobile.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroNowPlayingEntranceTest {
    @Test fun activeTrackMayRevealOnceWhenMotionAllowed() {
        assertTrue(aeroNowPlayingInitialScale(true, false) in 0.9f..1f)
        assertTrue(aeroNowPlayingInitialAlpha(true, false) in 0.5f..1f)
    }

    @Test fun emptyAndReducedMotionAppearImmediately() {
        assertEquals(1f, aeroNowPlayingInitialScale(false, false), 0f)
        assertEquals(1f, aeroNowPlayingInitialAlpha(false, false), 0f)
        assertEquals(1f, aeroNowPlayingInitialScale(true, true), 0f)
        assertEquals(1f, aeroNowPlayingInitialAlpha(true, true), 0f)
    }
}
