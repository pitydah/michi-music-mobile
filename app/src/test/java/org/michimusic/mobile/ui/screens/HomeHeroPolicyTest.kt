package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Album
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.data.repository.LocalMediaRepository

class HomeHeroPolicyTest {
    private val t1 = Track(id = "t1", title = "First", artist = "Artist A", album = "Album A", albumId = "a1", coverId = "c1")
    private val t2 = Track(id = "t2", title = "Second", artist = "Artist B", album = "Album B", albumId = "a2", coverId = "c2")
    private val t3 = Track(id = "t3", title = "Third", artist = "Artist A", album = "Album A", albumId = "a1", coverId = "c1")
    private val albums = listOf(
        LocalMediaRepository.LocalAlbum(Album(id = "a1", title = "Album A", artist = "Artist A", coverId = "c1"), listOf(t1, t3)),
        LocalMediaRepository.LocalAlbum(Album(id = "a2", title = "Album B", artist = "Artist B", coverId = "c2"), listOf(t2)),
    )

    @Test fun carouselUsesOnlyRealLibraryItemsAndKeepsExactTrackIdentity() {
        val items = buildHomeHeroItems(
            allTracks = listOf(t1, t2, t3),
            albums = albums,
            playlists = listOf(Playlist(id = "p1", name = "Favorites", trackIds = listOf("t2", "t1"), trackCount = 2)),
            recentIds = setOf("t1"),
            currentTrackId = "t2",
        )

        assertTrue(items.isNotEmpty())
        assertEquals(HomeHeroKind.TRACK, items.first().kind)
        assertEquals("t3", items.first().sourceId)
        assertFalse(items.any { it.key == "album:a1" })
        assertTrue(items.any { it.key == "album:a2" && it.trackIds == listOf("t2") })
        assertTrue(items.any { it.key == "playlist:p1" && it.trackIds == listOf("t2", "t1") })
        assertEquals(items.size, items.map { it.key }.distinct().size)
        assertFalse(items.any { it.trackIds.isEmpty() })
    }

    @Test fun carouselNeverInventsAnItemWhenTheLibraryIsEmpty() {
        assertTrue(buildHomeHeroItems(emptyList(), albums, emptyList(), emptySet(), null).isEmpty())
    }

    @Test fun carouselLimitAndUnknownPlaylistTracksFailClosed() {
        val items = buildHomeHeroItems(
            allTracks = listOf(t1, t2),
            albums = albums,
            playlists = listOf(
                Playlist(id = "missing", name = "Missing", trackIds = listOf("gone"), trackCount = 1),
                Playlist(id = "p1", name = "One", trackIds = listOf("t1"), trackCount = 1),
            ),
            recentIds = emptySet(),
            currentTrackId = null,
            limit = 2,
        )
        assertEquals(2, items.size)
        assertFalse(items.any { it.key == "playlist:missing" })
    }
}
