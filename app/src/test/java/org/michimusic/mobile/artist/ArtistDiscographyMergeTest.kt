package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Album
import org.michimusic.core.models.Track
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum

/**
 * One discography: a local album and its MusicBrainz release-group are the same row, the
 * local copy stays playable, and catalogue-only rows never become playable.
 */
class ArtistDiscographyMergeTest {

    private fun localAlbum(id: String, title: String, year: Int) = LocalAlbum(
        Album(id = id, title = title, artist = "Midnight Oil", year = year, trackCount = 1),
        listOf(Track(id = "${id}_t", title = "Track", artist = "Midnight Oil", album = title)),
    )

    private fun published(title: String, year: Int?, mbid: String) =
        ArtistPublicAlbum(releaseGroupMbid = mbid, title = title, year = year, primaryType = "Album")

    @Test
    fun aLocalAlbumAndItsPublishedTwinBecomeOnePlayableRow() {
        val merged = mergeArtistDiscography(
            local = listOf(localAlbum("album-diesel", "Diesel and Dust", 1987)),
            published = listOf(published("Diesel and Dust", 1987, "rg-1")),
        )
        assertEquals(1, merged.size)
        assertTrue(merged.single().playable)
        assertEquals("rg-1", merged.single().releaseGroupMbid)
    }

    @Test
    fun aPublishedAlbumWithoutLocalCopyIsInformationOnly() {
        val merged = mergeArtistDiscography(
            local = emptyList(),
            published = listOf(published("10, 9, 8, 7, 6, 5, 4, 3, 2, 1", 1982, "rg-2")),
        )
        assertEquals(1, merged.size)
        assertFalse(merged.single().playable)
        assertNull(merged.single().local)
    }

    @Test
    fun accentsAndCaseDoNotDuplicateAnAlbum() {
        val merged = mergeArtistDiscography(
            local = listOf(localAlbum("album-esp", " Espíritu del Sur ", 1990)),
            published = listOf(published("espiritu del sur", 1990, "rg-3")),
        )
        assertEquals(1, merged.size)
        assertTrue(merged.single().playable)
    }

    @Test
    fun aDifferentYearStaysASeparateRelease() {
        val merged = mergeArtistDiscography(
            local = listOf(localAlbum("album-diesel", "Diesel and Dust", 1987)),
            published = listOf(published("Diesel and Dust", 2007, "rg-remaster")),
        )
        assertEquals(2, merged.size)
        assertEquals(1, merged.count { it.playable })
    }

    @Test
    fun localAlbumsAreAlwaysPartOfTheDiscography() {
        val merged = mergeArtistDiscography(
            local = listOf(localAlbum("album-blue", "Blue Album", 0)),
            published = emptyList(),
        )
        assertEquals(1, merged.size)
        assertTrue(merged.single().playable)
        assertNull(merged.single().year)
        assertNotNull(merged.single().local)
    }

    @Test
    fun theDiscographyIsOrderedNewestFirst() {
        val merged = mergeArtistDiscography(
            local = listOf(localAlbum("album-old", "Old", 1987)),
            published = listOf(published("New", 2002, "rg-new")),
        )
        assertEquals(listOf("New", "Old"), merged.map { it.title })
    }
}
