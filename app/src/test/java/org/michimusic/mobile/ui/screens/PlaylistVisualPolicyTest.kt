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
        assertEquals(40, playlistTrackLayout(PlaylistTrackDensity.COMPACT).artworkDp)
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
}
