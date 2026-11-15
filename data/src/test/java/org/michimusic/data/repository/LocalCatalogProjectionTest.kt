package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track

class LocalCatalogProjectionTest {
    private fun track(id: String, albumId: String = "", album: String = "Compilation",
        artist: String = "Guest", albumArtist: String = "Main") = Track(
        id = id, title = id, artist = artist, album = album,
        albumId = albumId, albumArtist = albumArtist,
    )

    @Test fun mediastoreIdMatchesExistingAlbumGrouping() {
        val rows = LocalCatalogProjection.fromTracks(listOf(track("t1", albumId = "987")))
        assertEquals(setOf("album_mediastore_987"), rows.map { it.albumKey }.toSet())
        assertEquals(setOf("Guest", "Main"), rows.map { it.creditedArtist }.toSet())
    }

    @Test fun aCompilationWithGuestPerformersHasOneStableAlbumKey() {
        val rows = LocalCatalogProjection.fromTracks(listOf(
            track("t1", albumId = "45", artist = "Guest A"),
            track("t2", albumId = "45", artist = "Guest B"),
        ))
        assertEquals(setOf("album_mediastore_45"), rows.map { it.albumKey }.toSet())
        assertEquals(4, rows.size) // no extra album sorting or local music scan
    }

    @Test fun tagBasedAlbumsUseTheSameFirstTrackKeyAsExistingGrouping() {
        val rows = LocalCatalogProjection.fromTracks(listOf(
            track("t1", albumId = "", album = "Album Title", albumArtist = "Artist X"),
            track("t2", albumId = "", album = "Album Title", albumArtist = "Artist X"),
        ))
        assertEquals(setOf("album_album_title_artist_x"), rows.map { it.albumKey }.toSet())
    }

    @Test fun emptyCreditsDoNotCreateUnknownIdentity() {
        assertTrue(LocalCatalogProjection.fromTracks(listOf(
            track("t1", artist = "", albumArtist = ""),
        )).isEmpty())
    }
    @Test fun observedIdsStayInTheirOriginalRoleAndNeverChangeCreditOrAlbum() {
        val artistId = "bd6d4f63-5cd0-4d78-a292-167997794c21"
        val albumId = "05072650-4b05-4e8b-83d0-dba2576e306a"
        val song = track("t1", albumId = "101", artist = "Guest", albumArtist = "Main")
            .copy(observedArtistMbids = listOf(artistId),
                observedAlbumArtistMbids = listOf(albumId))
        val rows = LocalCatalogProjection.fromTracks(listOf(song))
        assertEquals(2, rows.size)
        assertEquals(listOf(artistId), rows.single { it.creditedArtist == "Guest" }.observedArtistMbids)
        assertTrue(rows.single { it.creditedArtist == "Guest" }.observedAlbumArtistMbids.isEmpty())
        assertEquals(listOf(albumId), rows.single { it.creditedArtist == "Main" }.observedAlbumArtistMbids)
        assertTrue(rows.single { it.creditedArtist == "Main" }.observedArtistMbids.isEmpty())
        assertEquals(setOf("album_mediastore_101"), rows.map { it.albumKey }.toSet())
    }

}
