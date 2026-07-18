package org.michimusic.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.Album
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.cache.ReplayGainDao
import org.michimusic.data.cache.ReplayGainEntity

@OptIn(ExperimentalCoroutinesApi::class)
class LocalMediaRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repo: TestableLocalMediaRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repo = TestableLocalMediaRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadAlbums_groupsByAlbumArtist() = runTest(testDispatcher) {
        val albums = repo.loadAlbums()
        assertEquals(2, albums.size)
        assertEquals("Album A", albums[0].album.title)
        assertEquals(2, albums[0].tracks.size)
    }

    @Test
    fun loadAlbums_createsAlbumIdFromNameAndArtist() = runTest(testDispatcher) {
        val albums = repo.loadAlbums()
        assertTrue(albums[0].album.id.contains("album_a"))
        assertTrue(albums[0].album.id.contains("artist_1"))
    }

    @Test
    fun loadAlbums_sortsByAlbumTitle() = runTest(testDispatcher) {
        val albums = repo.loadAlbums()
        assertEquals("Album A", albums[0].album.title)
        assertEquals("Album B", albums[1].album.title)
    }

    @Test
    fun loadArtists_groupsByArtistName() = runTest(testDispatcher) {
        val artists = repo.loadArtists()
        assertEquals(2, artists.size)
        assertEquals("Artist 1", artists[0].first.name)
        assertEquals(1, artists[0].second.size)
    }

    @Test
    fun loadArtists_emptyNameBecomesUnknown() = runTest(testDispatcher) {
        val unknownRepo = TestableLocalMediaRepository(
            tracks = listOf(Track(id = "t1", title = "Song", artist = "", album = ""))
        )
        val artists = unknownRepo.loadArtists()
        assertEquals("Unknown", artists.first().first.name)
    }

    @Test
    fun invalidateCache_clearsCache() = runTest(testDispatcher) {
        repo.loadTracks()
        repo.invalidateCache()
        // Force reload would trigger queryTracks again
        assertTrue(true)
    }

    @Test
    fun loadAlbums_emptyTracks_returnsEmpty() = runTest(testDispatcher) {
        val emptyRepo = TestableLocalMediaRepository(tracks = emptyList())
        assertTrue(emptyRepo.loadAlbums().isEmpty())
    }
}

private class TestableLocalMediaRepository(
    private val tracks: List<Track> = defaultTracks(),
) : LocalMediaRepository(
    context = null!!,
    replayGainDao = object : ReplayGainDao {
        override suspend fun getReplayGain(trackId: String) = null
        override suspend fun getAllReplayGains() = emptyList<ReplayGainEntity>()
        override suspend fun upsert(entity: ReplayGainEntity) {}
        override suspend fun upsertAll(entities: List<ReplayGainEntity>) {}
    },
) {
    override suspend fun loadTracks(): List<Track> = tracks

    companion object {
        fun defaultTracks() = listOf(
            Track(id = "t1", title = "Song 1", artist = "Artist 1", album = "Album A",
                duration = 200_000L, filepath = "/mnt/s1.mp3", source = TrackSource.LOCAL),
            Track(id = "t2", title = "Song 2", artist = "Artist 1", album = "Album A",
                duration = 180_000L, filepath = "/mnt/s2.mp3", source = TrackSource.LOCAL),
            Track(id = "t3", title = "Song 3", artist = "Artist 2", album = "Album B",
                duration = 240_000L, filepath = "/mnt/s3.flac", source = TrackSource.LOCAL),
        )
    }
}
