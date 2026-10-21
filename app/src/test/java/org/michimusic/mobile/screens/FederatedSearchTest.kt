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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.SyncedTrackRepository

@OptIn(ExperimentalCoroutinesApi::class)
class FederatedSearchTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var localRepo: LocalMediaRepository
    private lateinit var syncedRepo: SyncedTrackRepository
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        localRepo = mockk(relaxed = true)
        syncedRepo = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `searchDeduplicatesByIdentityPreservingDistinctMasters`() = runTest(testDispatcher) {
        // Master 1: 44.1 kHz 16-bit FLAC
        val master1 = Track(
            id = "t1",
            title = "Hotel California",
            artist = "Eagles",
            album = "Hotel California",
            duration = 391000L,
            sampleRate = 44100,
            format = "flac",
            filepath = "content://media/external/audio/1",
        )

        // Master 2: 96.0 kHz 24-bit FLAC (Remaster)
        val master2 = Track(
            id = "t2",
            title = "Hotel California",
            artist = "Eagles",
            album = "Hotel California",
            duration = 391000L,
            sampleRate = 96000,
            format = "flac",
            filepath = "file:///synced/hotel_hi_res.flac",
        )

        // Duplicate of Master 1 on local storage
        val master1Duplicate = Track(
            id = "t1_dup",
            title = "Hotel California",
            artist = "Eagles",
            album = "Hotel California",
            duration = 391000L,
            sampleRate = 44100,
            format = "flac",
            filepath = "content://media/external/audio/100",
        )

        coEvery { localRepo.loadAlbums() } returns listOf(
            LocalMediaRepository.LocalAlbum(
                album = org.michimusic.core.models.Album(id = "a1", title = "Hotel California", artist = "Eagles", year = 1976),
                tracks = listOf(master1, master2, master1Duplicate),
            )
        )
        coEvery { syncedRepo.getAllSynced() } returns flowOf(emptyList())

        viewModel = SearchViewModel(localRepo, syncedRepo, testDispatcher)
        viewModel.loadLocalTracks()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setQuery("Hotel")
        testDispatcher.scheduler.advanceUntilIdle()

        val results = viewModel.structuredResults.value.tracks
        // Must preserve the two distinct masters (44.1kHz vs 96kHz) while dropping the duplicate 44.1kHz track
        assertEquals(2, results.size)
        assertTrue(results.any { it.track.sampleRate == 44100 })
        assertTrue(results.any { it.track.sampleRate == 96000 })
        assertTrue(results.all { it.availableLocations.isNotEmpty() })
    }
}
