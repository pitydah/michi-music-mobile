package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistVisualPolicyTest {
    private val tracks = listOf(
        PlaylistCatalogEntry("z", "Tardes", 2),
        PlaylistCatalogEntry("a", "Ámbar", 40),
        PlaylistCatalogEntry("b", "Noches", 8),
    )

    @Test fun pinsAreFirstWithoutMutatingOriginalOrderOrTrackCounts() {
        val ordered = orderedPlaylistCatalog(tracks, setOf("b"), PlaylistCatalogSort.TRACKS)
        assertEquals(listOf("b", "a", "z"), ordered.map { it.id })
        assertEquals(listOf("z", "a", "b"), tracks.map { it.id })
        assertEquals(8, ordered.first().trackCount)
    }

    @Test fun nameOrderingIsStableAndDoesNotInventEntries() {
        val ordered = orderedPlaylistCatalog(tracks, emptySet(), PlaylistCatalogSort.NAME)
        assertEquals(listOf("a", "b", "z"), ordered.map { it.id })
        assertEquals(3, ordered.size)
        assertEquals(ordered.map { it.id }, orderedPlaylistCatalog(tracks, emptySet(), PlaylistCatalogSort.NAME).map { it.id })
    }

    @Test fun densityIsVisualOnly() {
        assertEquals(44, playlistTrackLayout(PlaylistTrackDensity.COMPACT).artworkDp)
        assertEquals(56, playlistTrackLayout(PlaylistTrackDensity.EXPANDED).artworkDp)
        assertTrue(playlistTrackLayout(PlaylistTrackDensity.EXPANDED).verticalPaddingDp >
            playlistTrackLayout(PlaylistTrackDensity.COMPACT).verticalPaddingDp)
    }

    @Test fun preferencesKeysDoNotUseRawTitlesOrIds() {
        val a = playlistVisualKey("a/b?=", "banner")
        val b = playlistVisualKey("a/b?=", "cover")
        assertNotEquals(a, b)
        assertTrue(!a.contains("a/b?="))
        assertEquals(a, playlistVisualKey("a/b?=", "banner"))
    }

    @Test fun sharedImageGrantsAreReleasedOnlyAfterLastReferenceAndOnlyIfOwned() {
        val image = "content://provider/photo/1"
        assertTrue(!playlistGrantReleaseAllowed(image, emptySet(), emptyList()))
        assertTrue(!playlistGrantReleaseAllowed(image, setOf(image), listOf(image)))
        assertTrue(!playlistGrantReleaseAllowed(image, setOf(image), listOf(null, image, image)))
        assertTrue(playlistGrantReleaseAllowed(image, setOf(image), listOf(null, "content://other")))
        val key = playlistOwnedGrantKey(image)
        assertTrue(!key.contains(image))
        assertEquals(key, playlistOwnedGrantKey(image))
    }

    @Test fun focalInputAlwaysRecoversFromCorruption() {
        assertEquals(.5f, safePlaylistBannerFocal(Float.NaN), 0f)
        assertEquals(.5f, safePlaylistBannerFocal(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, safePlaylistBannerFocal(-2f), 0f)
        assertEquals(1f, safePlaylistBannerFocal(2f), 0f)
    }

    @Test fun focalStepsStayInRange() {
        var focal = 0.2f
        repeat(12) {
            focal = nextPlaylistBannerFocal(focal)
            assertTrue(focal in 0f..1f)
        }
    }

    @Test fun catalogSummaryKeepsPinnedCountForEveryCombination() {
        assertEquals("0 playlists", playlistCatalogSummary(0, 0))
        assertEquals("1 playlist", playlistCatalogSummary(1, 0))
        assertEquals("1 playlist · 1 fijada", playlistCatalogSummary(1, 1))
        assertEquals("2 playlists · 1 fijada", playlistCatalogSummary(2, 1))
        assertEquals("5 playlists · 3 fijadas", playlistCatalogSummary(5, 3))
        assertEquals("10 playlists", playlistCatalogSummary(10, 0))
    }

    @Test fun spotlightPolicyShrinksCoverAndAllowsTitleGrowthAtLargeFonts() {
        assertEquals(80f, playlistSpotlightCoverSizeDp(320f, 2f), 0f)
        assertEquals(80f, playlistSpotlightCoverSizeDp(360f, 1.5f), 0f)
        assertEquals(88f, playlistSpotlightCoverSizeDp(393f, 1f), 0f)
        assertEquals(88f, playlistSpotlightCoverSizeDp(Float.NaN, 1f), 0f)

        assertEquals(3, playlistSpotlightTitleLines(320f, 2f))
        assertEquals(3, playlistSpotlightTitleLines(360f, 1.5f))
        assertEquals(2, playlistSpotlightTitleLines(393f, 1f))
        assertEquals(3, playlistSpotlightTitleLines(393f, Float.NaN))
    }
}
