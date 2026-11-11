package org.michimusic.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArtworkOwnerTest {

    private val track = Track(
        id = "t1",
        title = "Song",
        artist = "Artist",
        album = "Album",
        filepath = "/music/one.flac",
        source = TrackSource.SYNCED,
        sourceId = "peer-a",
    )

    private fun item(track: Track, uri: String = "file:///music/one.flac"): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            .setMediaMetadata(PlaybackMediaMetadata.build(track))
            .build()

    @Test
    fun sameOwnerTrackAndUriBelongToRequest() {
        val owner = ArtworkOwner("peer-a", "t1", "file:///music/one.flac", 7L, 2)

        assertTrue(item(track).belongsTo(owner))
    }

    @Test
    fun sameTrackIdFromAnotherLibraryDoesNotBelong() {
        val other = track.copy(sourceId = "peer-b")
        val owner = ArtworkOwner("peer-a", "t1", "file:///music/one.flac", 7L, 2)

        assertFalse(item(other).belongsTo(owner))
    }

    @Test
    fun sameTrackIdWithAnotherUriDoesNotBelong() {
        val owner = ArtworkOwner("peer-a", "t1", "file:///music/two.flac", 7L, 2)

        assertFalse(item(track).belongsTo(owner))
    }

    @Test
    fun missingOwnershipExtrasFailsClosed() {
        val noExtras = MediaItem.Builder()
            .setMediaId("t1")
            .setUri("file:///music/one.flac")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Song").build())
            .build()
        val owner = ArtworkOwner("peer-a", "t1", "file:///music/one.flac", 7L, 2)

        assertFalse(noExtras.belongsTo(owner))
    }

    @Test
    fun queueGenerationIsCarriedByOwnerAndRejectsStaleRequest() {
        val current = ArtworkOwner("peer-a", "t1", "file:///music/one.flac", 8L, 2)
        val stale = current.copy(queueGeneration = 7L)

        assertTrue(item(track).matchesArtworkRequest(current, 8L, 2))
        assertFalse(item(track).matchesArtworkRequest(stale, 8L, 2))
        assertFalse(item(track).matchesArtworkRequest(current, 7L, 2))
        assertFalse(item(track).matchesArtworkRequest(current, 8L, 1))
    }

    @Test
    fun sequenceAReturnsWithNewGenerationAndRejectsTheOldRequest() {
        val trackB = track.copy(id = "t2", filepath = "/music/two.flac")
        val ownerA = ArtworkOwner("peer-a", "t1", "file:///music/one.flac", 1L, 0)
        val ownerB = ArtworkOwner("peer-a", "t2", "file:///music/two.flac", 2L, 1)
        val ownerAAgain = ArtworkOwner("peer-a", "t1", "file:///music/one.flac", 3L, 0)

        assertFalse(item(track).matchesArtworkRequest(ownerA, 3L, 0))
        assertTrue(item(trackB, uri = "file:///music/two.flac").matchesArtworkRequest(ownerB, 2L, 1))
        assertTrue(item(track).matchesArtworkRequest(ownerAAgain, 3L, 0))
        assertFalse(item(track).matchesArtworkRequest(ownerB, 2L, 1))
    }
}
