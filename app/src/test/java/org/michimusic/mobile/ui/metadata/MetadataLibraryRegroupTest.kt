package org.michimusic.mobile.ui.metadata

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataCommittedLibraryChange
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.core.models.Track
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.MetadataCommittedTrackProjection
import org.michimusic.data.repository.MetadataLibraryPublicationGate
import org.michimusic.data.repository.artistsFromAlbums
import org.michimusic.data.repository.groupAlbumsFromTracks
import org.michimusic.mobile.ui.screens.groupLibraryGenres

/**
 * Library surfaces must regroup on the exact committed revision, not on a
 * global rescan: songs, albums, artists and genres follow the durable change,
 * while track identity and artwork stay untouched. An obsolete notification can
 * never revert Rock back after Jazz was committed.
 */
class MetadataLibraryRegroupTest {
    @Test
    fun genreArtistAlbumRegroupAndStaleEvent() = runBlocking {
        val tracks = mutableListOf(trackRock(), trackJazz())
        val revisions = mutableMapOf("local_1" to 5L, "local_2" to 5L)

        // 1. Rock -> Jazz on one track, mixed with an extended field (COMPOSER).
        //    The mixed revision must apply its projectable subset instead of being refused.
        val genreChange = change(
            transactionId = "tx-genre",
            trackId = "local_1",
            uri = URI_ONE,
            changedFields = setOf(
                MetadataField.GENRE,
                MetadataField.TITLE,
                MetadataField.COMPOSER,
            ),
            known = mapOf(
                MetadataField.GENRE to listOf("Jazz"),
                MetadataField.TITLE to listOf("Midnight Runner (Remastered)"),
                MetadataField.COMPOSER to listOf("Some Composer"),
            ),
            previousRevision = 5L,
            committedRevision = 6L,
        )
        MetadataLibraryRefreshSignal.request(genreChange)
        val deliveredGenre = withTimeout(5_000) {
            // A screen that mounts after the commit still receives the exact revision.
            MetadataLibraryRefreshSignal.changes.first {
                it.transactionId == genreChange.transactionId
            }
        }
        assertEquals(genreChange, deliveredGenre)

        val genreDecision = apply(tracks, revisions, deliveredGenre)
        assertEquals(MetadataLibraryPublicationGate.Decision.Apply, genreDecision)

        val regroupedSong = tracks.first { it.id == "local_1" }
        assertEquals(URI_ONE, regroupedSong.filepath)
        assertEquals("cover_rock", regroupedSong.coverId)
        assertEquals("Artist One", regroupedSong.artist)
        assertEquals("Midnight Runner (Remastered)", regroupedSong.title)
        assertEquals("Jazz", regroupedSong.genre)
        assertEquals(120L, regroupedSong.size)
        assertEquals("b".repeat(64), regroupedSong.contentHash)
        assertEquals(listOf("local_1", "local_2"), tracks.map { it.id })

        val genresAfterJazz = groupLibraryGenres(tracks)
        assertEquals(1, genresAfterJazz.size)
        assertEquals(
            setOf("local_1", "local_2"),
            genresAfterJazz.single().tracks.map { it.id }.toSet(),
        )
        assertFalse(tracks.any { it.id == "local_1" && it.genre == "Rock" })

        var albums = groupAlbumsFromTracks(tracks)
        var artists = artistsFromAlbums(albums)
        // Artist identity is preserved: same single artist, same two rows, no duplication.
        assertEquals(1, artists.size)
        assertEquals("artist_one", artists.single().first.id)
        assertEquals(2, artists.single().first.trackCount)

        // 2. Album change regroups the album list without touching identity or artwork.
        val albumChange = change(
            transactionId = "tx-album",
            trackId = "local_1",
            uri = URI_ONE,
            changedFields = setOf(MetadataField.ALBUM),
            known = mapOf(MetadataField.ALBUM to listOf("Rock Album II")),
            previousRevision = 6L,
            committedRevision = 7L,
        )
        val albumDecision = apply(tracks, revisions, albumChange)
        assertEquals(MetadataLibraryPublicationGate.Decision.Apply, albumDecision)

        albums = groupAlbumsFromTracks(tracks)
        artists = artistsFromAlbums(albums)
        assertEquals(
            listOf("Rock Album II", "Second Album"),
            albums.map { it.album.title },
        )
        val regroupedAlbum = albums.first { it.album.title == "Rock Album II" }
        assertEquals(listOf("local_1"), regroupedAlbum.tracks.map { it.id })
        assertEquals("cover_rock", regroupedAlbum.album.coverId)
        assertEquals(1, artists.size)
        assertEquals("artist_one", artists.single().first.id)

        // 3. Album artist changes, so the artist list regroups without losing the other album.
        val artistChange = change(
            transactionId = "tx-artist",
            trackId = "local_1",
            uri = URI_ONE,
            changedFields = setOf(MetadataField.ALBUM_ARTIST),
            known = mapOf(MetadataField.ALBUM_ARTIST to listOf("Artist Two")),
            previousRevision = 7L,
            committedRevision = 8L,
        )
        val artistDecision = apply(tracks, revisions, artistChange)
        assertEquals(MetadataLibraryPublicationGate.Decision.Apply, artistDecision)

        albums = groupAlbumsFromTracks(tracks)
        artists = artistsFromAlbums(albums)
        assertEquals(listOf("Artist One", "Artist Two"), artists.map { it.first.name })
        val secondAlbum = albums.first { it.album.title == "Second Album" }
        assertEquals("Artist One", secondAlbum.album.artist)
        assertEquals(listOf("local_2"), secondAlbum.tracks.map { it.id })
        val regroupedArtistAlbum = albums.first { it.album.title == "Rock Album II" }
        assertEquals("Artist Two", regroupedArtistAlbum.album.artist)
        assertEquals(listOf("local_1"), regroupedArtistAlbum.tracks.map { it.id })
        assertEquals("cover_rock", regroupedArtistAlbum.album.coverId)

        // 4. An obsolete event (older committed revision) is ignored, never reverted.
        val staleChange = change(
            transactionId = "tx-stale",
            trackId = "local_1",
            uri = URI_ONE,
            changedFields = setOf(MetadataField.GENRE),
            known = mapOf(MetadataField.GENRE to listOf("Rock")),
            previousRevision = 2L,
            committedRevision = 3L,
        )
        val staleDecision = apply(tracks, revisions, staleChange)
        assertTrue(staleDecision is MetadataLibraryPublicationGate.Decision.Ignore)
        assertEquals(
            "STALE_REVISION",
            (staleDecision as MetadataLibraryPublicationGate.Decision.Ignore).reason,
        )
        assertEquals("Jazz", tracks.first { it.id == "local_1" }.genre)
        assertEquals(8L, revisions["local_1"])
        assertEquals(
            setOf("local_1", "local_2"),
            groupLibraryGenres(tracks).single().tracks.map { it.id }.toSet(),
        )

        // 5. Replaying the delivered transaction is idempotent, not a stale revert.
        val replayDecision = apply(tracks, revisions, artistChange)
        assertEquals(MetadataLibraryPublicationGate.Decision.Apply, replayDecision)
        assertEquals("Artist Two", tracks.first { it.id == "local_1" }.albumArtist)
        assertEquals(8L, revisions["local_1"])
        assertEquals("Jazz", tracks.first { it.id == "local_1" }.genre)
        assertEquals("local_1", tracks.first { it.id == "local_1" }.id)
        assertEquals(URI_ONE, tracks.first { it.id == "local_1" }.filepath)

        // 6. Reopen: the same durable tracks rebuild identical surfaces without a rescan.
        val reopenedAlbums = groupAlbumsFromTracks(tracks)
        val reopenedArtists = artistsFromAlbums(reopenedAlbums)
        assertEquals(albums, reopenedAlbums)
        assertEquals(artists, reopenedArtists)
        assertEquals("local_2", tracks.first { it.id == "local_2" }.id)
        assertEquals("Jazz", tracks.first { it.id == "local_2" }.genre)
    }

    /**
     * Production path: durable revision gate first, then the exact projection.
     * Only an Apply may mutate the in-memory catalog and advance the revision.
     */
    private fun apply(
        tracks: MutableList<Track>,
        revisions: MutableMap<String, Long>,
        change: MetadataCommittedLibraryChange,
    ): MetadataLibraryPublicationGate.Decision {
        val decision = MetadataLibraryPublicationGate.evaluate(
            change = change,
            marker = null,
            durableRevision = revisions[change.trackId],
        )
        if (decision == MetadataLibraryPublicationGate.Decision.Apply) {
            val index = tracks.indexOfFirst {
                it.id == change.trackId && it.filepath == change.uri
            }
            assertTrue("target missing for ${change.transactionId}", index >= 0)
            val projected = MetadataCommittedTrackProjection.apply(tracks[index], change)
            assertTrue(
                "projection refused for ${change.transactionId}: $projected",
                projected is MetadataCommittedTrackProjection.Result.Applied,
            )
            tracks[index] = (projected as MetadataCommittedTrackProjection.Result.Applied).track
            revisions[change.trackId] = change.committedRevision
        }
        return decision
    }

    private fun trackRock(): Track = Track(
        id = "local_1",
        title = "Midnight Runner",
        artist = "Artist One",
        album = "Rock Album",
        albumId = "",
        duration = 210_000L,
        size = 100L,
        format = "FLAC",
        coverId = "cover_rock",
        trackNumber = 1,
        year = 2019,
        filepath = URI_ONE,
        sourceId = SOURCE,
        genre = "Rock",
    )

    private fun trackJazz(): Track = Track(
        id = "local_2",
        title = "Blue Hours",
        artist = "Artist One",
        album = "Second Album",
        albumId = "",
        duration = 180_000L,
        size = 100L,
        format = "FLAC",
        coverId = "cover_jazz",
        trackNumber = 2,
        year = 2020,
        filepath = URI_TWO,
        sourceId = SOURCE,
        genre = "Jazz",
    )

    private fun change(
        transactionId: String,
        trackId: String,
        uri: String,
        changedFields: Set<MetadataField>,
        known: Map<MetadataField, List<String>>,
        previousRevision: Long,
        committedRevision: Long,
    ): MetadataCommittedLibraryChange = MetadataCommittedLibraryChange(
        transactionId = transactionId,
        sourceId = SOURCE,
        trackId = trackId,
        uri = uri,
        changedFields = changedFields,
        resourceOperations = emptySet(),
        snapshot = MetadataSnapshot(
            target = MetadataTarget(
                sourceId = SOURCE,
                trackId = trackId,
                uri = uri,
                evidenceToken = "post-readback",
            ),
            tagsVerified = true,
            known = known,
        ),
        previousRevision = previousRevision,
        committedRevision = committedRevision,
        committedBytes = 120L,
        committedSha256 = "b".repeat(64),
    )

    private companion object {
        val SOURCE = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID
        const val URI_ONE = "content://media/external_primary/audio/media/1"
        const val URI_TWO = "content://media/external_primary/audio/media/2"
    }
}
