package org.michimusic.mobile.screens

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.Album
import org.michimusic.core.models.Track
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.SyncedTrackRepository
import org.michimusic.mobile.library.MusicRootAuthority

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
        
        // Distinct local and synced representations must remain selectable.
        assertEquals(3, structured.tracks.size)
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

    @Test
    fun `search uses DOWNLOADED filter checking actual file availability`() = runTest(testDispatcher) {
        val downloadedContentTrack = Track(
            id = "t1", title = "Downloaded MediaStore", artist = "My Artist", album = "My Album",
            duration = 100, size = 1000, format = "flac", bitrate = 128, sampleRate = 96000, channels = 2,
            coverId = "", trackNumber = 1, year = 2020, filepath = "content://media/external/audio/media/1234", source = org.michimusic.core.models.TrackSource.LOCAL
        )
        val missingLocalTrack = Track(
            id = "t2", title = "Missing Track", artist = "My Artist", album = "My Album",
            duration = 100, size = 1000, format = "mp3", bitrate = 128, sampleRate = 44100, channels = 2,
            coverId = "", trackNumber = 2, year = 2020, filepath = "/nonexistent/path/song.mp3", source = org.michimusic.core.models.TrackSource.LOCAL
        )

        val album = Album(id = "a1", title = "My Album", artist = "My Artist", coverId = "", year = 2020)
        coEvery { localRepo.loadAlbums() } returns listOf(
            LocalMediaRepository.LocalAlbum(album = album, tracks = listOf(downloadedContentTrack, missingLocalTrack))
        )
        coEvery { syncedRepo.getAllSynced() } returns flowOf(emptyList())

        viewModel.loadLocalTracks()
        testScheduler.advanceUntilIdle()

        viewModel.setQuery("downloaded")
        testScheduler.advanceUntilIdle()

        viewModel.setFilter(SearchFilter.DOWNLOADED)
        testScheduler.advanceUntilIdle()

        val structured = viewModel.structuredResults.value
        assertEquals(1, structured.tracks.size)
        assertEquals("Downloaded MediaStore", structured.tracks[0].track.title)
    }

    @Test
    fun `a retry during an in-flight catalog scan does not restart the scan`() = runTest(testDispatcher) {
        coEvery { localRepo.loadAlbums() } coAnswers {
            kotlinx.coroutines.delay(500)
            emptyList<LocalMediaRepository.LocalAlbum>()
        }
        coEvery { syncedRepo.getAllSynced() } returns flowOf(emptyList())

        viewModel.loadLocalTracks()
        testScheduler.runCurrent()
        assertTrue(viewModel.isSearching.value)

        // A retry arrives while the first catalog scan is still running.
        viewModel.loadLocalTracks()
        testScheduler.runCurrent()
        // The in-flight scan keeps ownership instead of being cancelled and restarted.
        assertTrue(viewModel.isSearching.value)

        testScheduler.advanceUntilIdle()
        assertFalse(viewModel.isSearching.value)
        assertNull(viewModel.error.value)
        coVerify(exactly = 1) { localRepo.loadAlbums() }
    }

    @Test
    fun `a trailing-space query normalizes and still finds the song`() = runTest(testDispatcher) {
        val rock = Track(id = "rock-1", title = "Rock Song", artist = "The Band", album = "Live")
        coEvery { localRepo.loadAlbums() } returns listOf(
            LocalMediaRepository.LocalAlbum(
                album = Album(id = "live", title = "Live", artist = "The Band"),
                tracks = listOf(rock),
            ),
        )
        viewModel.loadLocalTracks()
        testScheduler.advanceUntilIdle()

        viewModel.setQuery("rock ")
        testScheduler.advanceUntilIdle()

        val snapshot = viewModel.structuredResults.value
        assertEquals("rock", snapshot.requestQuery)
        assertEquals(SearchFilter.ALL, snapshot.requestFilter)
        assertTrue(snapshot.requestGeneration >= 0L)
        assertFalse(viewModel.isSearching.value)
        assertTrue(snapshot.tracks.any { it.track.id == "rock-1" })
    }

    @Test
    fun `a confirmed root revision supersedes an in-flight catalog scan`() = runTest(testDispatcher) {
        val authority = mockk<MusicRootAuthority>(relaxed = true)
        val revisions = MutableStateFlow(0L)
        every { authority.revision } returns revisions
        val scopedViewModel = SearchViewModel(localRepo, syncedRepo, testDispatcher, authority)
        val oldTrack = Track(id = "old", title = "Old Root Song", artist = "Artist")
        val newTrack = Track(id = "new", title = "New Root Song", artist = "Artist")
        val album = Album(id = "a1", title = "Album", artist = "Artist")
        var calls = 0
        coEvery { localRepo.loadAlbums() } coAnswers {
            calls += 1
            if (calls == 1) delay(500)
            listOf(
                LocalMediaRepository.LocalAlbum(
                    album = album,
                    tracks = listOf(if (calls == 1) oldTrack else newTrack),
                )
            )
        }
        coEvery { syncedRepo.getAllSynced() } returns flowOf(emptyList())

        scopedViewModel.loadLocalTracks()
        testScheduler.runCurrent()
        assertTrue(scopedViewModel.isSearching.value)

        // The shared root authority confirms a new root while the old scan is running.
        revisions.value = 1L
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 2) { localRepo.loadAlbums() }
        scopedViewModel.setQuery("root")
        testScheduler.advanceUntilIdle()
        assertEquals(
            listOf("new"),
            scopedViewModel.structuredResults.value.tracks.map { it.track.id },
        )
    }
}
