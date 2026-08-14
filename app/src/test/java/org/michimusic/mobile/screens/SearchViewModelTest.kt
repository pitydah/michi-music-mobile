package org.michimusic.mobile.screens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.Album
import org.michimusic.core.models.Artist
import org.michimusic.core.models.ManifestPlaylist
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackDto
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.SyncedTrackRepository

import org.junit.Rule
import org.michimusic.mobile.rules.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testDispatcher get() = mainDispatcherRule.testDispatcher
    private lateinit var localRepo: LocalMediaRepository
    private lateinit var syncedRepo: SyncedTrackRepository
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setup() {
        localRepo = FakeLocalMediaRepository()
        syncedRepo = FakeSyncedTrackRepository()
        viewModel = SearchViewModel(localRepo, syncedRepo, testDispatcher)
    }

    @After
    fun tearDown() {
        viewModel.clearSearch()
    }

    @Test
    fun setQuery_shortQuery_returnsEmpty() = runTest {
        viewModel.loadLocalTracks()
        advanceUntilIdle()
        viewModel.setQuery("a")
        advanceUntilIdle()
        assertTrue(viewModel.results.value.isEmpty())
    }

    @Test
    fun setQuery_matchesLocalTrackTitle() = runTest {
        viewModel.loadLocalTracks()
        advanceUntilIdle()
        viewModel.setQuery("Song 1")
        advanceUntilIdle()
        val results = viewModel.results.value
        assertTrue(results.isNotEmpty())
        assertEquals("Song 1", results.first().track.title)
        assertEquals("Local", results.first().source)
    }

    @Test
    fun setQuery_matchesSyncedTrackArtist() = runTest {
        viewModel.loadLocalTracks()
        advanceUntilIdle()
        viewModel.setQuery("Synced Artist")
        advanceUntilIdle()
        val results = viewModel.results.value
        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.source == "Sincronizada" })
    }

    @Test
    fun setQuery_matchesLocalTrackAlbum() = runTest {
        viewModel.loadLocalTracks()
        advanceUntilIdle()
        viewModel.setQuery("Test Album")
        advanceUntilIdle()
        val results = viewModel.results.value
        assertTrue(results.isNotEmpty())
        assertEquals("Test Album", results.first().track.album)
    }

    @Test
    fun setQuery_noMatch_returnsEmpty() = runTest {
        viewModel.loadLocalTracks()
        advanceUntilIdle()
        viewModel.setQuery("ZZZZnotfound")
        advanceUntilIdle()
        assertTrue(viewModel.results.value.isEmpty())
    }

    @Test
    fun clearSearch_resetsQueryAndResults() = runTest {
        viewModel.loadLocalTracks()
        advanceUntilIdle()
        viewModel.setQuery("Song")
        advanceUntilIdle()
        assertTrue(viewModel.results.value.isNotEmpty())
        viewModel.clearSearch()
        advanceUntilIdle()
        assertEquals("", viewModel.query.value)
        assertTrue(viewModel.results.value.isEmpty())
    }

    @Test
    fun resultsCappedAt50() = runTest {
        viewModel.loadLocalTracks()
        advanceUntilIdle()
        viewModel.setQuery("Track")
        advanceUntilIdle()
        val count = viewModel.results.value.size
        assertTrue("Expected at most 50 results, got $count", count <= 50)
    }
}

private class FakeLocalMediaRepository : LocalMediaRepository() {
    override suspend fun loadAlbums(): List<LocalMediaRepository.LocalAlbum> {
        val tracks = (1..60).map { i ->
            Track(
                id = "local_$i",
                title = if (i <= 30) "Song $i" else "Track $i",
                artist = "Artist $i",
                album = if (i <= 20) "Test Album" else "Other Album",
                duration = 200_000L,
                filepath = "/music/song$i.mp3",
            )
        }
        return listOf(
            LocalMediaRepository.LocalAlbum(
                album = Album(id = "album_1", title = "Test Album", artist = "Artist 1"),
                tracks = tracks.take(20),
            ),
            LocalMediaRepository.LocalAlbum(
                album = Album(id = "album_2", title = "Other Album", artist = "Artist 2"),
                tracks = tracks.drop(20),
            ),
        )
    }

    override suspend fun loadTracks(): List<Track> = loadAlbums().flatMap { it.tracks }
    override suspend fun loadArtists(): List<Pair<Artist, List<LocalMediaRepository.LocalAlbum>>> = emptyList()
    override suspend fun loadPlaylists(): List<Pair<Playlist, List<Track>>> = emptyList()
    override suspend fun invalidateCache() {}
}

private class FakeSyncedTrackRepository : SyncedTrackRepository() {
    private val cached = (1..40).map { i ->
        CachedTrack(
            id = "synced_$i",
            title = "Synced Track $i",
            artist = "Synced Artist",
            album = "Synced Album",
            duration = 180_000L,
            filepath = "/synced/track$i.mp3",
            downloaded = true,
        )
    }

    override fun getAllSynced() = flowOf(cached)
    override fun getPagedTracks() = throw UnsupportedOperationException()
    override suspend fun getDownloadedIds(): Set<String> = cached.filter { it.downloaded }.map { it.id }.toSet()
    override suspend fun count(): Int = cached.size
    override suspend fun saveLibrary(tracks: List<TrackDto>) {}
    override suspend fun saveManifestPlaylists(playlists: List<ManifestPlaylist>) {}
    override suspend fun getById(id: String): CachedTrack? = cached.find { it.id == id }
    override suspend fun markDownloaded(id: String) {}
    override suspend fun markDownloadedWithPath(id: String, filepath: String) {}
}
