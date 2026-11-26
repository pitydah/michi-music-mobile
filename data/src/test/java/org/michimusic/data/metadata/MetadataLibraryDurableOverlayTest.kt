package org.michimusic.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.MetadataLibraryDurableOverlay

class MetadataLibraryDurableOverlayTest {
    private val source = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID
    private val uri = "content://media/external_primary/audio/media/1"
    private val sha = "b".repeat(64)

    private fun track() = Track(
        id = "local_1",
        title = "Provider title",
        artist = "Provider artist",
        album = "Provider album",
        albumId = "7",
        duration = 200_000L,
        size = 120L,
        format = "FLAC",
        coverId = "cover_rock",
        trackNumber = 1,
        year = 2019,
        filepath = uri,
        sourceId = source,
        genre = "Rock",
    )

    private fun durable() = CachedTrack(
        id = "local_1",
        title = "Durable title",
        artist = "Durable artist",
        album = "Durable album",
        albumArtist = "Durable album artist",
        duration = 200_000L,
        size = 120L,
        format = "FLAC",
        coverId = "cover_jazz",
        trackNumber = 3,
        year = 2021,
        filepath = uri,
        sourceId = source,
        genre = "Jazz",
        metadataRevision = 4L,
        verifiedSha256 = sha,
    )

    @Test
    fun overlaysOnlyTheDurableDisplayFieldsAndClearsChangedAlbumIdentity() {
        val original = track()
        val overlaid = MetadataLibraryDurableOverlay.apply(
            listOf(original),
            listOf(durable()),
        ).single()

        assertEquals("Durable title", overlaid.title)
        assertEquals("Durable artist", overlaid.artist)
        assertEquals("Durable album", overlaid.album)
        assertEquals("", overlaid.albumId)
        assertEquals("Durable album artist", overlaid.albumArtist)
        assertEquals("Jazz", overlaid.genre)
        assertEquals(listOf("Jazz"), overlaid.genreValues)
        assertEquals(3, overlaid.trackNumber)
        assertEquals(2021, overlaid.year)
        assertEquals(120L, overlaid.size)
        assertEquals(sha, overlaid.contentHash)
        // Provider-owned artwork identity and source remain authoritative.
        assertEquals("cover_rock", overlaid.coverId)
        assertEquals(MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID, overlaid.sourceId)
    }

    @Test
    fun sizeMismatchLeavesTheProviderTrackUntouched() {
        val original = track()
        val mismatched = durable().copy(size = 121L)

        val overlaid = MetadataLibraryDurableOverlay.apply(listOf(original), listOf(mismatched))

        assertSame(original, overlaid.single())
    }

    @Test
    fun pathMismatchLeavesTheProviderTrackUntouched() {
        val original = track()
        val mismatched = durable().copy(filepath = "content://media/external_primary/audio/media/2")

        val overlaid = MetadataLibraryDurableOverlay.apply(listOf(original), listOf(mismatched))

        assertSame(original, overlaid.single())
    }
}
