package org.michimusic.mobile.screens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.Album
import org.michimusic.core.models.Track
import org.michimusic.data.cache.AppDao
import org.michimusic.data.cache.HistoryEntity
import org.michimusic.data.cache.PlayCountEntity
import org.michimusic.data.cache.QueueEntity
import org.michimusic.data.repository.LocalMediaRepository

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repo: LocalMediaRepository
    private lateinit var appDao: AppDao
    private lateinit var viewModel: AlbumsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repo = FakeAlbumsRepo()
        appDao = FakeAppDao()
        viewModel = AlbumsViewModel(repo, appDao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadMedia_populatesAlbums() = runTest(testDispatcher) {
        viewModel.loadMedia()
        advanceUntilIdle()
        assertTrue(viewModel.albums.value.isNotEmpty())
        assertEquals(2, viewModel.albums.value.size)
    }

    @Test
    fun loadMedia_populatesAllTracks() = runTest(testDispatcher) {
        viewModel.loadMedia()
        advanceUntilIdle()
        assertTrue(viewModel.allTracks.value.isNotEmpty())
        assertEquals(10, viewModel.allTracks.value.size)
    }

    @Test
    fun loadMedia_setsLoadingState() = runTest(testDispatcher) {
        assertFalse(viewModel.isLoading.value)
        viewModel.loadMedia()
        assertTrue(viewModel.isLoading.value)
        advanceUntilIdle()
        assertFalse(viewModel.isLoading.value)
    }

    @Test
    fun loadMedia_emptyRepo_returnsEmpty() = runTest(testDispatcher) {
        val emptyRepo = FakeAlbumsRepo(emptyList())
        val emptyVm = AlbumsViewModel(emptyRepo, appDao)
        emptyVm.loadMedia()
        advanceUntilIdle()
        assertTrue(emptyVm.albums.value.isEmpty())
        assertTrue(emptyVm.allTracks.value.isEmpty())
    }

    @Test
    fun loadMedia_repoThrows_returnsEmpty() = runTest(testDispatcher) {
        val failingRepo = object : FakeAlbumsRepo() {
            override suspend fun loadAlbums(): List<LocalMediaRepository.LocalAlbum> =
                throw RuntimeException("DB error")
        }
        val failingVm = AlbumsViewModel(failingRepo, appDao)
        failingVm.loadMedia()
        advanceUntilIdle()
        assertTrue(failingVm.albums.value.isEmpty())
        assertFalse(failingVm.isLoading.value)
    }
}

private open class FakeAlbumsRepo(
    private val albums: List<LocalMediaRepository.LocalAlbum> = defaultAlbums(),
) : LocalMediaRepository {
    override suspend fun loadAlbums(): List<LocalMediaRepository.LocalAlbum> = albums
    override suspend fun loadTracks(): List<Track> = albums.flatMap { it.tracks }
    override suspend fun loadArtists(): List<Pair<Artist, List<LocalMediaRepository.LocalAlbum>>> = emptyList()
    override suspend fun loadPlaylists(): List<Pair<Playlist, List<Track>>> = emptyList()
    override fun invalidateCache() {}

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

private typealias Artist = org.michimusic.core.models.Artist
private typealias Playlist = org.michimusic.core.models.Playlist
