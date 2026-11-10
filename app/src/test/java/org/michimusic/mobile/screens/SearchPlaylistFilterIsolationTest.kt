package org.michimusic.mobile.screens

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
import org.michimusic.core.models.Album
import org.michimusic.core.models.Track
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.SyncedTrackRepository

/** A playlists-only query must never make songs masquerade as playlists. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchPlaylistFilterIsolationTest {
    private val local = mockk<LocalMediaRepository>(relaxed = true)
    private val synced = mockk<SyncedTrackRepository>(relaxed = true)
    private val dispatcher = StandardTestDispatcher()
    private lateinit var viewModel: SearchViewModel

    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        viewModel = SearchViewModel(local, synced, dispatcher)
    }

    @After fun stop() { Dispatchers.resetMain() }

    @Test fun playlistsFilterPublishesNoMusicRowsEvenWhenNamesMatch() = runTest(dispatcher) {
        val track = Track(id = "t-rock", title = "Rock Mix", artist = "Artist", album = "Rock")
        val album = Album(id = "album", title = "Rock", artist = "Artist")
        coEvery { local.loadAlbums() } returns listOf(LocalMediaRepository.LocalAlbum(album, listOf(track)))
        coEvery { synced.getAllSynced() } returns flowOf(emptyList())
        viewModel.loadLocalTracks()
        advanceUntilIdle()

        viewModel.setFilter(SearchFilter.PLAYLISTS)
        viewModel.setQuery("rock")
        advanceUntilIdle()

        val snapshot = viewModel.structuredResults.value
        assertEquals(SearchFilter.PLAYLISTS, snapshot.requestFilter)
        assertTrue(snapshot.requestGeneration >= 0)
        assertTrue(snapshot.tracks.isEmpty())
        assertTrue(snapshot.albums.isEmpty())
        assertTrue(snapshot.artists.isEmpty())
        assertTrue(viewModel.results.value.isEmpty())
    }

    @Test fun switchingBackFromPlaylistsRestoresRealTrackSearch() = runTest(dispatcher) {
        val track = Track(id = "t-mix", title = "Rock Mix", artist = "Artist", album = "Rock")
        val album = Album(id = "album", title = "Rock", artist = "Artist")
        coEvery { local.loadAlbums() } returns listOf(LocalMediaRepository.LocalAlbum(album, listOf(track)))
        coEvery { synced.getAllSynced() } returns flowOf(emptyList())
        viewModel.loadLocalTracks()
        advanceUntilIdle()

        viewModel.setFilter(SearchFilter.PLAYLISTS)
        viewModel.setQuery("rock")
        advanceUntilIdle()
        assertTrue(viewModel.structuredResults.value.tracks.isEmpty())

        viewModel.setFilter(SearchFilter.TRACKS)
        advanceUntilIdle()
        assertEquals(SearchFilter.TRACKS, viewModel.structuredResults.value.requestFilter)
        assertEquals(listOf("t-mix"), viewModel.structuredResults.value.tracks.map { it.track.id })
    }
}
