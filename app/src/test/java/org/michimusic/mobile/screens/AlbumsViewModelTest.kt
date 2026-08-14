package org.michimusic.mobile.screens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.michimusic.core.models.Album
import org.michimusic.core.models.Artist
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.data.cache.AppDao
import org.michimusic.data.cache.HistoryEntity
import org.michimusic.data.cache.PlayCountEntity
import org.michimusic.data.cache.QueueEntity
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.PlaylistRepository
import org.michimusic.mobile.rules.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testDispatcher get() = mainDispatcherRule.testDispatcher
    private lateinit var repo: LocalMediaRepository
    private lateinit var appDao: AppDao
    private lateinit var playlistRepo: PlaylistRepository
    private lateinit var viewModel: AlbumsViewModel

    @Before
    fun setup() {
        repo = FakeAlbumsRepo()
        appDao = FakeAppDao()
        playlistRepo = FakeAlbumsPlaylistRepo()
        viewModel = AlbumsViewModel(repo, appDao, playlistRepo, testDispatcher)
    }

    @After
    fun tearDown() {
        viewModel.cancelLoading()
    }

    @Test
    fun loadMedia_populatesAlbums() = runTest(testDispatcher) {
        viewModel.loadMedia()
        advanceUntilIdle()
        assertTrue(viewModel.albums.value.isNotEmpty())
        assertEquals(2, viewModel.albums.value.size)
    }

    @Test
    fun loadMedia_populatesPlaylists() = runTest(testDispatcher) {
        viewModel.loadMedia()
        advanceUntilIdle()
        assertEquals(1, viewModel.playlists.value.size)
        assertEquals("Initial Playlist", viewModel.playlists.value.first().name)
    }

    @Test
    fun createPlaylist_persistsAndUpdatesState() = runTest(testDispatcher) {
        var completed = false
        viewModel.createPlaylist("Rock Classics") {
            completed = true
        }
        advanceUntilIdle()
        assertTrue(completed)
        assertEquals(2, viewModel.playlists.value.size)
        assertTrue(viewModel.playlists.value.any { it.name == "Rock Classics" })
    }
}

private class FakeAlbumsPlaylistRepo : PlaylistRepository() {
    private val playlists = mutableListOf(
        Playlist(id = "p1", name = "Initial Playlist", trackCount = 5),
    )

    override suspend fun getAllPlaylists(): List<Playlist> = playlists

    override suspend fun createPlaylist(name: String, trackIds: List<String>): Playlist {
        val created = Playlist(id = "p_${playlists.size + 1}", name = name, trackCount = trackIds.size)
        playlists.add(created)
        return created
    }

    override suspend fun deletePlaylist(id: String) {
        playlists.removeAll { it.id == id }
    }
}

private open class FakeAlbumsRepo(
    private val albums: List<LocalMediaRepository.LocalAlbum> = defaultAlbums(),
) : LocalMediaRepository() {
    override suspend fun loadAlbums(): List<LocalMediaRepository.LocalAlbum> = albums
    override suspend fun loadTracks(): List<Track> = albums.flatMap { it.tracks }
    override suspend fun loadArtists(): List<Pair<Artist, List<LocalMediaRepository.LocalAlbum>>> = emptyList()
    override suspend fun loadPlaylists(): List<Pair<Playlist, List<Track>>> = emptyList()
    override suspend fun invalidateCache() {}

    companion object {
        fun defaultAlbums() = listOf(
            LocalMediaRepository.LocalAlbum(
                album = Album(id = "a1", title = "Album One", artist = "Artist A"),
                tracks = (1..5).map { i ->
                    Track(id = "t$i", title = "Song $i", artist = "Artist A", album = "Album One",
                        duration = 200_000L, filepath = "/music/song$i.mp3")
                },
            ),
            LocalMediaRepository.LocalAlbum(
                album = Album(id = "a2", title = "Album Two", artist = "Artist B"),
                tracks = (6..10).map { i ->
                    Track(id = "t$i", title = "Song $i", artist = "Artist B", album = "Album Two",
                        duration = 180_000L, filepath = "/music/song$i.mp3")
                },
            ),
        )
    }
}

private class FakeAppDao : AppDao {
    private val topTracks = listOf("t1", "t3", "t5")
    override suspend fun getTopTracks(limit: Int) = topTracks.map { PlayCountEntity(trackId = it, playCount = 5) }
    override suspend fun getRecentHistory(limit: Int) = listOf(
        HistoryEntity(trackId = "t2", playedAt = 1000L),
        HistoryEntity(trackId = "t4", playedAt = 2000L),
    )
    override suspend fun getPlayCount(trackId: String) = null
    override suspend fun incrementPlayCount(trackId: String, playedAt: Long) {}
    override suspend fun upsertPlayCount(count: PlayCountEntity) {}
    override suspend fun insertHistory(entry: HistoryEntity) {}
    override suspend fun trimHistory(limit: Int) {}
    override suspend fun clearHistory() {}
    override suspend fun clearPlayCounts() {}
    override suspend fun getSavedQueue() = null
    override suspend fun saveQueue(queue: QueueEntity) {}
    override suspend fun clearQueue() {}
    override suspend fun getSetting(key: String) = null
    override suspend fun setSetting(setting: org.michimusic.data.cache.SettingsEntity) {}
}
