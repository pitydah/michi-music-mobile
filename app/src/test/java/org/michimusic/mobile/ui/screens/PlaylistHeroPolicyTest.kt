package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistHeroPolicyTest {
    @Test fun coverRemainsAFocalAnchorAndShrinksOnlyOnNarrowScreens() {
        assertEquals(104f, playlistHeroCoverSizeDp(320f), 0f)
        assertEquals(104f, playlistHeroCoverSizeDp(339f), 0f)
        assertEquals(114f, playlistHeroCoverSizeDp(340f), 0f)
        assertEquals(114f, playlistHeroCoverSizeDp(360f), 0f)
        assertEquals(114f, playlistHeroCoverSizeDp(480f), 0f)
        assertEquals(114f, playlistHeroCoverSizeDp(Float.NaN), 0f)
    }

    @Test fun heroHasEditorialHeightButStaysInThePlannedRange() {
        assertEquals(240, PLAYLIST_HERO_MIN_HEIGHT_DP)
    }
}
