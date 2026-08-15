package org.michimusic.mobile.screens

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.Album
import org.michimusic.core.models.Track
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.SyncedTrackRepository

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val localRepo = mockk<LocalMediaRepository>(relaxed = true)
    private val syncedRepo = mockk<SyncedTrackRepository>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = SearchViewModel(localRepo, syncedRepo, testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `search combines and deduplicates tracks from local and synced`() = runTest(testDispatcher) {
        val localTrack = Track(
            id = "t1", title = "My Track", artist = "My Artist", album = "My Album",
            duration = 100, size = 1000, format = "mp3", bitrate = 128, sampleRate = 44100, channels = 2,
            coverId = "", trackNumber = 1, year = 2020, filepath = "/path", source = org.michimusic.core.models.TrackSource.LOCAL
        )
        
        // Exact same track but synced
        val syncedTrack1 = CachedTrack(
            id = "t2", title = "My Track", artist = "My Artist", album = "My Album",
            duration = 100, size = 1000, format = "mp3", bitrate = 128, sampleRate = 44100, channels = 2,
            coverId = "", trackNumber = 1, year = 2020, filepath = "", downloaded = false
        )
        
        // Different track synced
        val syncedTrack2 = CachedTrack(
            id = "t3", title = "My Another Track", artist = "My Artist", album = "Another Album",
            duration = 100, size = 1000, format = "mp3", bitrate = 128, sampleRate = 44100, channels = 2,
            coverId = "", trackNumber = 1, year = 2020, filepath = "", downloaded = false
        )

        val album = Album(id = "a1", title = "My Album", artist = "My Artist", coverId = "", year = 2020)
        coEvery { localRepo.loadAlbums() } returns listOf(
            LocalMediaRepository.LocalAlbum(album = album, tracks = listOf(localTrack))
        )
        coEvery { syncedRepo.getAllSynced() } returns flowOf(listOf(syncedTrack1, syncedTrack2))

        viewModel.loadLocalTracks()
        testScheduler.advanceUntilIdle()

        viewModel.setQuery("my")
        testScheduler.advanceUntilIdle()

        val structured = viewModel.structuredResults.value
        
        // Should have deduplicated 'My Track'
        assertEquals(2, structured.tracks.size)
        val titles = structured.tracks.map { it.track.title }
        assertEquals(true, titles.contains("My Track"))
        assertEquals(true, titles.contains("My Another Track"))
        
        assertEquals(1, structured.artists.size)
        assertEquals("My Artist", structured.artists[0].artist)
    }

    @Test
    fun `search uses LOSSLESS filter correctly`() = runTest(testDispatcher) {
        val localTrackLossless = Track(
            id = "t1", title = "Hi-Res Track", artist = "My Artist", album = "My Album",
            duration = 100, size = 1000, format = "flac", bitrate = 128, sampleRate = 96000, channels = 2,
            coverId = "", trackNumber = 1, year = 2020, filepath = "/path", source = org.michimusic.core.models.TrackSource.LOCAL
        )
        val localTrackLossy = Track(
            id = "t2", title = "Lossy Track", artist = "My Artist", album = "My Album",
            duration = 100, size = 1000, format = "mp3", bitrate = 128, sampleRate = 44100, channels = 2,
            coverId = "", trackNumber = 2, year = 2020, filepath = "/path", source = org.michimusic.core.models.TrackSource.LOCAL
        )
        
        val album = Album(id = "a1", title = "My Album", artist = "My Artist", coverId = "", year = 2020)
        coEvery { localRepo.loadAlbums() } returns listOf(
            LocalMediaRepository.LocalAlbum(album = album, tracks = listOf(localTrackLossless, localTrackLossy))
        )
        coEvery { syncedRepo.getAllSynced() } returns flowOf(emptyList())

        viewModel.loadLocalTracks()
        testScheduler.advanceUntilIdle()

        viewModel.setFilter(SearchFilter.LOSSLESS)
        viewModel.setQuery("track")
        testScheduler.advanceUntilIdle()

        val structured = viewModel.structuredResults.value
        
        assertEquals(1, structured.tracks.size)
        assertEquals("Hi-Res Track", structured.tracks[0].track.title)
    }
}
