package org.michimusic.mobile.screens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.TrackDto
import org.michimusic.core.models.ManifestPlaylist
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.repository.SyncedTrackRepository

@OptIn(ExperimentalCoroutinesApi::class)
class SyncedTracksViewModelTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var repository: SyncedTrackRepository
    private lateinit var viewModel: SyncedTracksViewModel

    @Before
    fun setup() {
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        repository = FakeSyncedRepo()
        viewModel = SyncedTracksViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun toTrack_mapsAllFields() {
        val cached = CachedTrack(
            id = "s1", title = "Song", artist = "A", album = "X",
            duration = 200_000L, filepath = "/music/s1.mp3", format = "flac",
        )
        val track = viewModel.toTrack(cached)
        assertEquals("s1", track.id)
        assertEquals("Song", track.title)
        assertEquals("flac", track.format)
        assertTrue(track.source.name == "SYNCED")
    }

    @Test
    fun getPlayableTracks_filtersEmptyFilepath() = runTest(testDispatcher) {
        val tracks = viewModel.getPlayableTracks()
        assertTrue(tracks.all { it.filepath.isNotEmpty() })
    }

    @Test
    fun getPlayableTracks_returnsAllValidTracks() = runTest(testDispatcher) {
        val tracks = viewModel.getPlayableTracks()
        assertEquals(3, tracks.size)
    }
}

private class FakeSyncedRepo : SyncedTrackRepository() {
    private val items = listOf(
        CachedTrack(id = "s1", title = "Song 1", artist = "A", album = "X",
            duration = 100_000L, filepath = "/mnt/s1.mp3", downloaded = true),
        CachedTrack(id = "s2", title = "Song 2", artist = "B", album = "Y",
            duration = 200_000L, filepath = "/mnt/s2.flac", downloaded = true),
        CachedTrack(id = "s3", title = "Song 3", artist = "C", album = "Z",
            duration = 300_000L, filepath = "/mnt/s3.mp3", downloaded = false),
        CachedTrack(id = "s4", title = "No File", artist = "D", album = "W",
            duration = 400_000L, filepath = "", downloaded = false),
    )

    override fun getAllSynced() = flowOf(items)
    override fun getPagedTracks() = flowOf(androidx.paging.PagingData.empty<CachedTrack>())
    override suspend fun getDownloadedIds() = items.filter { it.downloaded }.map { it.id }.toSet()
    override suspend fun count() = items.size
    override suspend fun saveLibrary(tracks: List<TrackDto>) {}
    override suspend fun saveManifestPlaylists(playlists: List<ManifestPlaylist>) {}
    override suspend fun getById(id: String) = items.find { it.id == id }
    override suspend fun markDownloaded(id: String) {}
    override suspend fun markDownloadedWithPath(id: String, filepath: String) {}
}
