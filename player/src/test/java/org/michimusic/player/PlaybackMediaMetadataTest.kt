package org.michimusic.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaybackMediaMetadataTest {

    private val localMediaStore = Track(
        id = "local_1",
        title = "I Want to Know What Love Is",
        artist = "Foreigner",
        album = "Agent Provocateur",
        duration = 304_000,
        trackNumber = 3,
        year = 1984,
        coverId = "42",
        filepath = "content://media/external/audio/media/100",
        source = TrackSource.LOCAL,
        sourceId = "",
    )

    @Test
    fun localMediaStoreNumericCoverBecomesAlbumArtUri() {
        val metadata = PlaybackMediaMetadata.build(localMediaStore)

        assertEquals(
            "content://media/external/audio/albumart/42",
            metadata.artworkUri.toString(),
        )
        assertEquals("Foreigner", metadata.artist)
        assertEquals("Agent Provocateur", metadata.albumTitle)
        assertEquals(3, metadata.trackNumber)
        assertEquals(1984, metadata.releaseYear)
        assertEquals(304_000L, metadata.durationMs)
        assertEquals("michi_source_id", PlaybackMediaMetadata.EXTRA_SOURCE_ID)
        assertEquals("", metadata.extras?.getString(PlaybackMediaMetadata.EXTRA_SOURCE_ID))
    }

    @Test
    fun syncedNumericCoverNeverBorrowsLocalAlbumArt() {
        val synced = localMediaStore.copy(sourceId = "peer-a", source = TrackSource.SYNCED)

        assertNull(PlaybackMediaMetadata.artworkUriFor(synced))
    }

    @Test
    fun safNumericCoverNeverBorrowsMediaStoreAlbumArt() {
        val saf = localMediaStore.copy(
            sourceId = "safroot_tree-7",
            filepath = "content://com.android.providers.media.documents/document/audio%3A1",
        )

        assertNull(PlaybackMediaMetadata.artworkUriFor(saf))
    }

    @Test
    fun explicitContentCoverUriIsPreserved() {
        val explicit = localMediaStore.copy(coverId = "content://media/external/audio/albumart/99")

        assertEquals(
            "content://media/external/audio/albumart/99",
            PlaybackMediaMetadata.artworkUriFor(explicit)?.toString(),
        )
    }

    @Test
    fun artworkBytesTakePrecedenceOverUri() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val metadata = PlaybackMediaMetadata.build(localMediaStore, artworkBytes = bytes)

        assertEquals(bytes.toList(), metadata.artworkData?.toList())
        assertNull(metadata.artworkUri)
    }
}
