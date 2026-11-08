package org.michimusic.mobile.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AeroAlbumMotionTest {
    @Test fun identityKeyDoesNotChangeWhenUniqueAlbumMoves() {
        assertEquals(albumMosaicMotionKey("library:album", 1, 0), albumMosaicMotionKey("library:album", 1, 9))
    }

    @Test fun ambiguousDuplicateKeysAreSeparatedInsteadOfPretendingSharedIdentity() {
        assertNotEquals(albumMosaicMotionKey("same", 2, 0), albumMosaicMotionKey("same", 2, 1))
    }

    @Test fun pressIsDisabledForUnknownAlbumsAndReducedMotion() {
        assertEquals(0.975f, albumMosaicPressScale(true, true, false), 0f)
        assertEquals(1f, albumMosaicPressScale(true, false, false), 0f)
        assertEquals(1f, albumMosaicPressScale(true, true, true), 0f)
        assertEquals(1f, albumMosaicPressScale(false, true, false), 0f)
    }

    @Test fun heroNeverHidesAlbumWhenReducedMotionEnabled() {
        assertEquals(AeroAlbumHeroTarget(0f, 0.965f, 0.045f), aeroAlbumHeroTarget(false, false))
        assertEquals(AeroAlbumHeroTarget(1f, 1f, 0f), aeroAlbumHeroTarget(true, false))
        assertEquals(AeroAlbumHeroTarget(1f, 1f, 0f), aeroAlbumHeroTarget(false, true))
        assertEquals(AeroAlbumHeroTarget(1f, 1f, 0f), aeroAlbumHeroTarget(false, false, highContrast = true))
    }
}
