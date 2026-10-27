package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroResponsivePolicyTest {
    @Test fun stackingCoversSmallPhonesAndLargeFonts() {
        assertTrue(shouldStackAudioActions(320f, 1f))
        assertTrue(shouldStackAudioActions(349f, 1f))
        assertFalse(shouldStackAudioActions(350f, 1f))
        assertFalse(shouldStackAudioActions(420f, 1.29f))
        assertTrue(shouldStackAudioActions(420f, 1.30f))
        assertTrue(shouldStackAudioActions(Float.NaN, 1f))
        assertTrue(shouldStackAudioActions(420f, Float.POSITIVE_INFINITY))
    }

    @Test fun scrollModeDoesNotOverlapTransportOnSmallHeights() {
        assertTrue(shouldScrollNowPlaying(500, 1f))
        assertTrue(shouldScrollNowPlaying(684, 1f))
        assertFalse(shouldScrollNowPlaying(685, 1f))
        assertFalse(shouldScrollNowPlaying(800, 1.25f))
        assertTrue(shouldScrollNowPlaying(800, 1.3f))
    }

    @Test fun artworkHeightIsBoundedAndMonotonic() {
        assertEquals(168, scrollArtworkHeightDp(420))
        assertEquals(168, scrollArtworkHeightDp(539))
        assertEquals(206, scrollArtworkHeightDp(540))
        assertEquals(206, scrollArtworkHeightDp(659))
        assertEquals(240, scrollArtworkHeightDp(660))
        assertEquals(240, scrollArtworkHeightDp(1000))
    }
}
