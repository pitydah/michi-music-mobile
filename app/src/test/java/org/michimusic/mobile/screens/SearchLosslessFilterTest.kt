package org.michimusic.mobile.screens

import io.mockk.coEvery
import io.mockk.every
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.Album
import org.michimusic.core.models.Track
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.SyncedTrackRepository

@OptIn(ExperimentalCoroutinesApi::class)
class SearchLosslessFilterTest {

    private val testDispatcher = StandardTestDispatcher()
    private val localRepo = mockk<LocalMediaRepository>(relaxed = true)
    private val syncedRepo = mockk<SyncedTrackRepository>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun losslessFilter_includesFlacAndWav_excludesLossy48kMp3AndAac() = runTest(testDispatcher) {
        val flacTrack = Track(id = "flac_1", title = "Beethoven Symphony", artist = "Orchestra", format = "flac", sampleRate = 44100)
        val wavTrack = Track(id = "wav_1", title = "Studio Master", artist = "Band", format = "wav", sampleRate = 96000)
        val mp3Track48k = Track(id = "mp3_1", title = "Podcast Episode", artist = "Host", format = "mp3", sampleRate = 48000)
        val aacTrack48k = Track(id = "aac_1", title = "Radio Stream", artist = "DJ", format = "aac", sampleRate = 48000)

        coEvery { localRepo.loadAlbums() } returns listOf(
            LocalMediaRepository.LocalAlbum(
                album = Album(id = "alb_1", title = "Album"),
                tracks = listOf(flacTrack, wavTrack, mp3Track48k, aacTrack48k)
            )
        )
        every { syncedRepo.getAllSynced() } returns flowOf(emptyList<CachedTrack>())

        val viewModel = SearchViewModel(localRepo, syncedRepo, testDispatcher)
        viewModel.loadLocalTracks()
        advanceUntilIdle()

        viewModel.setFilter(SearchFilter.LOSSLESS)
        viewModel.setQuery("Master") // length >= 2
        advanceUntilIdle()

        val results = viewModel.structuredResults.value.tracks
        val resultIds = results.map { it.track.id }

        assertTrue(resultIds.contains("wav_1"))
        assertTrue(!resultIds.contains("mp3_1"))
        assertTrue(!resultIds.contains("aac_1"))
    }
}
