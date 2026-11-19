package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UI8PlaylistSearchNormalizationTest {
    private val items = listOf(SearchPlaylistEntry("playlist-80", "80's", 61))

    @Test fun trailingSpacesDoNotHideResultsOrInvalidateNavigation() {
        assertEquals(rankedPlaylistSearch(items, "80"), rankedPlaylistSearch(items, " 80  "))
        assertTrue(playlistSearchNavigationAllowed("playlist-80", "80 ", "PLAYLISTS", items))
    }
}
