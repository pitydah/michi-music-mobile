package org.michimusic.data.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.LibraryExclusionDao
import org.michimusic.data.cache.LibraryExclusionEntity
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
    fun localPlaylistWithoutSourceNeverResolvesFromCachedRemoteRows() = runTest {
        val dao = mockk<TrackDao>()
        coEvery { dao.getTracksById("shared") } returns listOf(remoteA, remoteB)
        val result = repository(dao, localTracks = emptyList())
            .resolvePlaylistTracks(Playlist("p", "Legacy", listOf("shared")))

        assertTrue(result.tracks.isEmpty())
        assertEquals(listOf("shared"), result.unresolvedTrackIds)
        assertEquals(emptyList<String>(), result.ambiguousTrackIds)
    }

    @Test
    fun hiddenLocalTrackCannotBeResurrectedByPlaylistResolution() = runTest {
        val source = libraryVisibilitySource(MusicRoot.Default)
        val preferences = mockk<MusicRootPreferences>()
        every { preferences.currentRoot() } returns MusicRoot.Default
        val exclusions = mockk<LibraryExclusionDao>()
        coEvery { exclusions.forSource(source) } returns listOf(
            LibraryExclusionEntity(
                sourceId = source,
                trackId = local.id,
                guardFingerprint = libraryVisibilityFingerprint(local),
                title = local.title,
                artist = local.artist,
                album = local.album,
            ),
        )
        val localRepo = object : LocalMediaRepository(
            rootPreferences = preferences,
            libraryExclusionDao = exclusions,
        ) {
            override suspend fun loadTracks(): List<Track> = listOf(local)
        }
        val repo = PlaylistRepository(trackDao = mockk(relaxed = true), localMediaRepository = localRepo)

        val result = repo.resolvePlaylistTracks(Playlist("p", "Local", listOf(local.id)))

        assertTrue(result.tracks.isEmpty())
        assertEquals(listOf(local.id), result.hiddenTrackIds)
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
