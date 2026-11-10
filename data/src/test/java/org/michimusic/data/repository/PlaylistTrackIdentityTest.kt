package org.michimusic.data.repository

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.cache.toTrack

/** P0: a playlist ID must never jump to another library by coincidence. */
class PlaylistTrackIdentityTest {
    private val local = Track(id = "shared", title = "Local", filepath = "/local/shared.mp3")
    private val remoteA = CachedTrack(
        id = "shared", title = "Remote A", sourceId = "peer-a",
        filepath = "/peer-a/shared.mp3", size = 10, duration = 100,
    )
    private val remoteB = CachedTrack(
        id = "shared", title = "Remote B", sourceId = "peer-b",
        filepath = "/peer-b/shared.mp3", size = 20, duration = 200,
    )

    private fun repository(dao: TrackDao, localTracks: List<Track> = listOf(local)): PlaylistRepository {
        val localRepo = object : LocalMediaRepository() {
            override suspend fun loadTracks(): List<Track> = localTracks
        }
        return PlaylistRepository(trackDao = dao, localMediaRepository = localRepo)
    }

    @Test
    fun localAndRemoteWithSameIdKeepTheirOwner() = runTest {
        val dao = mockk<TrackDao>()
        coEvery { dao.getTracksById("shared") } returns listOf(remoteA, remoteB)
        coEvery { dao.getTrackBySource("peer-a", "shared") } returns remoteA
        val repo = repository(dao)

        assertEquals(
            listOf(local),
            repo.resolvePlaylistTracks(Playlist("p", "Local", listOf("shared"))).tracks,
        )
        assertEquals(
            listOf(remoteA.toTrack()),
            repo.resolvePlaylistTracks(
                Playlist("p", "Remote", listOf("shared"), sourceId = "peer-a"),
            ).tracks,
        )
    }

    @Test
    fun twoCachedOwnersWithoutSourceAreExplicitlyAmbiguous() = runTest {
        val dao = mockk<TrackDao>()
        coEvery { dao.getTracksById("shared") } returns listOf(remoteA, remoteB)
        val result = repository(dao, localTracks = emptyList())
            .resolvePlaylistTracks(Playlist("p", "Legacy", listOf("shared")))

        assertTrue(result.tracks.isEmpty())
        assertEquals(listOf("shared"), result.ambiguousTrackIds)
        assertEquals(emptyList<String>(), result.unresolvedTrackIds)
    }

    @Test
    fun remotePlaylistNeverFallsBackToAnotherSource() = runTest {
        val dao = mockk<TrackDao>()
        coEvery { dao.getTrackBySource("peer-a", "shared") } returns null
        val result = repository(dao)
            .resolvePlaylistTracks(Playlist("p", "Remote", listOf("shared"), sourceId = "peer-a"))

        assertTrue(result.tracks.isEmpty())
        assertEquals(listOf("shared"), result.unresolvedTrackIds)
    }
}
