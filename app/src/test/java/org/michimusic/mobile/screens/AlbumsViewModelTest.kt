package org.michimusic.mobile.screens

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.data.cache.AppDao

import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.PlaylistRepository

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumsViewModelTest {

    private val localRepo = mockk<LocalMediaRepository>(relaxed = true)
    private val appDao = mockk<AppDao>(relaxed = true)
    private val playlistRepo = mockk<PlaylistRepository>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: AlbumsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = AlbumsViewModel(localRepo, appDao, playlistRepo, testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts in loading state before loadMedia is called`() {
        assertTrue(viewModel.isLoading.value)
    }

    @Test
    fun `loadMedia flips isLoading to false once it completes`() = runTest(testDispatcher) {
        coEvery { localRepo.loadAlbums() } returns emptyList()
        coEvery { appDao.getTopTracks(12) } returns emptyList()
        coEvery { appDao.getRecentHistory(20) } returns emptyList()
        coEvery { playlistRepo.getAllPlaylists() } returns emptyList()

        viewModel.loadMedia()
        assertTrue(viewModel.isLoading.value)

        testScheduler.advanceUntilIdle()

        assertFalse(viewModel.isLoading.value)
    }

    @Test
    fun `loadMedia fetches albums, top tracks, recent tracks and playlists`() = runTest(testDispatcher) {
        val t1 = Track(id = "t1", title = "Track 1", artist = "Artist", album = "Album", duration = 100, size = 1000, format = "mp3", bitrate = 128, sampleRate = 44100, channels = 2, coverId = "", trackNumber = 1, year = 2020, filepath = "/path", source = org.michimusic.core.models.TrackSource.LOCAL)
        val t2 = Track(id = "t2", title = "Track 2", artist = "Artist", album = "Album", duration = 100, size = 1000, format = "mp3", bitrate = 128, sampleRate = 44100, channels = 2, coverId = "", trackNumber = 2, year = 2020, filepath = "/path", source = org.michimusic.core.models.TrackSource.LOCAL)
        
        val album = org.michimusic.core.models.Album(id = "a1", title = "Album", artist = "Artist", coverId = "", year = 2020)
        val localAlbum = LocalMediaRepository.LocalAlbum(album = album, tracks = listOf(t1, t2))

        coEvery { localRepo.loadAlbums() } returns listOf(localAlbum)
        coEvery { appDao.getTopTracks(12) } returns listOf(org.michimusic.data.cache.PlayCountEntity("t1", 10))
        coEvery { appDao.getRecentHistory(20) } returns listOf(org.michimusic.data.cache.HistoryEntity(trackId = "t2", playedAt = 1000L))
        coEvery { playlistRepo.getAllPlaylists() } returns listOf(Playlist("p1", "My Playlist", emptyList()))

        viewModel.loadMedia()
        testScheduler.advanceUntilIdle()

        assertEquals(1, viewModel.albums.value.size)
        assertEquals(2, viewModel.allTracks.value.size)
        
        assertEquals(1, viewModel.topTracks.value.size)
        assertEquals("t1", viewModel.topTracks.value[0].id)

        assertEquals(1, viewModel.recentTracks.value.size)
        assertEquals("t2", viewModel.recentTracks.value[0].id)

        assertEquals(1, viewModel.playlists.value.size)
    }

    @Test
    fun `createPlaylist persists via repository and refreshes playlists state`() = runTest(testDispatcher) {
        val created = Playlist("p2", "Synthwave Nights", emptyList(), trackCount = 0)
        coEvery { playlistRepo.createPlaylist("Synthwave Nights") } returns created
        coEvery { playlistRepo.getAllPlaylists() } returns listOf(created)

        var completed = false
        viewModel.createPlaylist("Synthwave Nights") { completed = true }
        testScheduler.advanceUntilIdle()

        coVerify { playlistRepo.createPlaylist("Synthwave Nights") }
        assertEquals(1, viewModel.playlists.value.size)
        assertEquals("Synthwave Nights", viewModel.playlists.value.first().name)
        assertTrue(completed)
    }

    @Test
    fun `createPlaylist with blank name does not call repository`() = runTest(testDispatcher) {
        var completed = false
        viewModel.createPlaylist("   ") { completed = true }
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 0) { playlistRepo.createPlaylist(any()) }
        assertFalse(completed)
    }
}
