package org.michimusic.mobile.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroSharedAlbumMotionTest {
    @Test fun highContrastDisablesSharedAlbumMotion() {
        assertFalse(aeroAlbumShareAllowed("library:album-1", true, false, true, highContrast = true))
    }

    @Test fun stableKeyIsBasedOnFullScopedIdentity() {
        assertEquals("aero-album-art:library/Álbum-12", aeroAlbumSharedKey("library/Álbum-12"))
        assertEquals(aeroAlbumSharedKey("scope-a/album"), aeroAlbumSharedKey("scope-a/album"))
        assertFalse(aeroAlbumSharedKey("scope-a/album") == aeroAlbumSharedKey("scope-b/album"))
    }

    @Test fun sharingRequiresARealMatchingIdentityAndTransition() {
        assertTrue(aeroAlbumShareAllowed("scope/album", true, false, true))
        assertFalse(aeroAlbumShareAllowed("", true, false, true))
        assertFalse(aeroAlbumShareAllowed(null, true, false, true))
        assertFalse(aeroAlbumShareAllowed("scope/album", false, false, true))
        assertFalse(aeroAlbumShareAllowed("scope/album", true, false, false))
    }

    @Test fun navigationMotionOnlyBetweenAlbumAndLibrary() {
        assertTrue(aeroAlbumRouteTransitionAllowed("library", "album/{albumId}", false))
        assertTrue(aeroAlbumRouteTransitionAllowed("album/{albumId}", "library", false))
        assertFalse(aeroAlbumRouteTransitionAllowed("artist/{artistKey}", "album/{albumId}", false))
        assertFalse(aeroAlbumRouteTransitionAllowed("home", "library", false))
        assertFalse(aeroAlbumRouteTransitionAllowed(null, "album/{albumId}", false))
        assertFalse(aeroAlbumRouteTransitionAllowed("library", "album/{albumId}", true))
    }

    @Test fun reducedMotionDisablesTheSpatialTransition() {
        assertFalse(aeroAlbumShareAllowed("scope/album", true, true, true))
    }
}
