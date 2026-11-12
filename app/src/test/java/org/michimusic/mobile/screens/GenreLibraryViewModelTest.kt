package org.michimusic.mobile.screens

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.data.cache.CachedGenreMetadata
import org.michimusic.data.cache.TrackDao
import org.michimusic.mobile.ui.screens.GenreIndexLoad

@OptIn(ExperimentalCoroutinesApi::class)
class GenreLibraryViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadsGenreIndexFromOneOwnerSnapshotAndClearsOnExit() = runBlocking {
        val trackDao = mockk<TrackDao>()
        every { trackDao.observeGenreEvidenceSnapshot() } returns flowOf(
            listOf(
                CachedGenreMetadata(
                    sourceId = "",
                    id = "t1",
                    title = "Song",
                    artist = "Artist",
                    album = "Album",
                    duration = 1000,
                    filepath = "/music/song.flac",
                    genre = "Jazz",
                ),
            ),
        )
        val track = Track(
            id = "t1",
            title = "Song",
            artist = "Artist",
            album = "Album",
            duration = 1000,
            filepath = "/music/song.flac",
            sourceId = "",
        )

        val viewModel = GenreLibraryViewModel(trackDao)
        viewModel.load(listOf(track), scopeOwnerId = null, isAllScope = true)

        val ready = withTimeout(5_000) {
            viewModel.state.first { it is GenreIndexLoad.Ready }
        } as GenreIndexLoad.Ready
        assertTrue(ready.index.genres.any { it.title == "Jazz" })

        viewModel.clear()
        assertTrue(viewModel.state.value is GenreIndexLoad.Loading)
    }
}
