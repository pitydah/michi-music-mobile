package org.michimusic.mobile.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Test

class AeroArtistMotionTest {
    @Test fun portraitEntryUsesCalmScaleAndOpacity() {
        assertEquals(0.97f, aeroArtistHeroInitialScale(true), 0f)
        assertEquals(0.82f, aeroArtistHeroInitialAlpha(true), 0f)
        assertEquals(1f, aeroArtistHeroInitialScale(false), 0f)
        assertEquals(1f, aeroArtistHeroInitialAlpha(false), 0f)
    }
}
