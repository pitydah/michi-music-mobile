package org.michimusic.mobile.screens

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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
        assertEquals("ALL", ready.scopeKey)
        assertTrue(ready.generation > 0L)

        viewModel.clear()
        assertTrue(viewModel.state.value is GenreIndexLoad.Loading)
    }

    @Test
    fun rapidScopeChangesPublishOnlyTheLatestOwner() = runBlocking {
        val trackDao = mockk<TrackDao>()
        val evidence = MutableStateFlow(
            listOf(genreRow(id = "t1", title = "Jazz Song", sourceId = "ownerA", genre = "Jazz")),
        )
        every { trackDao.observeGenreEvidenceSnapshot() } returns evidence

        val viewModel = GenreLibraryViewModel(trackDao)
        viewModel.load(
            listOf(genreTrack(id = "t1", title = "Jazz Song", sourceId = "ownerA")),
            scopeOwnerId = "ownerA",
            isAllScope = false,
        )
        val first = withTimeout(5_000) {
            viewModel.state.first { it is GenreIndexLoad.Ready && it.scopeKey == "OWNER:ownerA" }
        } as GenreIndexLoad.Ready
        assertTrue(first.index.genres.any { it.title == "Jazz" })

        evidence.value = listOf(
            genreRow(id = "t2", title = "Rock Song", sourceId = "ownerB", genre = "Rock"),
        )
        viewModel.load(
            listOf(genreTrack(id = "t2", title = "Rock Song", sourceId = "ownerB")),
            scopeOwnerId = "ownerB",
            isAllScope = false,
        )
        val second = withTimeout(5_000) {
            viewModel.state.first { it is GenreIndexLoad.Ready && it.scopeKey == "OWNER:ownerB" }
        } as GenreIndexLoad.Ready

        assertTrue(second.index.genres.any { it.title == "Rock" })
        assertTrue(second.index.genres.none { it.title == "Jazz" })
        assertTrue(second.generation > first.generation)
    }

    @Test
    fun sameOwnerDifferentLibrariesKeepSeparateScopeKeys() = runBlocking {
        val dao = mockk<TrackDao>()
        val evidence = MutableStateFlow(listOf(
            genreRow("t1", "Jazz Song", "ownerA", "Jazz"),
            genreRow("t2", "Rock Song", "ownerA", "Rock"),
        ))
        every { dao.observeGenreEvidenceSnapshot() } returns evidence
        val viewModel = GenreLibraryViewModel(dao)

        viewModel.load(
            listOf(genreTrack("t1", "Jazz Song", "ownerA")),
            scopeOwnerId = "ownerA",
            isAllScope = false,
            libraryId = "lib-a",
        )
        val first = withTimeout(5_000) {
            viewModel.state.first { it is GenreIndexLoad.Ready && it.scopeKey == "ONE:lib-a:ownerA" }
        } as GenreIndexLoad.Ready

        viewModel.load(
            listOf(genreTrack("t2", "Rock Song", "ownerA")),
            scopeOwnerId = "ownerA",
            isAllScope = false,
            libraryId = "lib-b",
        )
        val second = withTimeout(5_000) {
            viewModel.state.first { it is GenreIndexLoad.Ready && it.scopeKey == "ONE:lib-b:ownerA" }
        } as GenreIndexLoad.Ready

        assertTrue(first.scopeKey != second.scopeKey)
        assertEquals("Jazz", first.index.genres.single { it.title == "Jazz" }.title)
        assertTrue(second.index.genres.any { it.title == "Rock" })
        assertTrue(second.index.genres.none { it.title == "Jazz" })
    }

    @Test
    fun roomFailureIsReportedAndRetryPublishesReady() = runBlocking {
        val trackDao = mockk<TrackDao>()
        every { trackDao.observeGenreEvidenceSnapshot() } returnsMany listOf(
            flow<List<CachedGenreMetadata>> { throw IllegalStateException("room unavailable") },
            flowOf(
                listOf(
                    genreRow(id = "t2", title = "Rock Song", sourceId = "ownerB", genre = "Rock"),
                ),
            ),
        )

        val viewModel = GenreLibraryViewModel(trackDao)
        viewModel.load(
            listOf(genreTrack(id = "t2", title = "Rock Song", sourceId = "ownerB")),
            scopeOwnerId = "ownerB",
            isAllScope = false,
        )
        withTimeout(5_000) {
            viewModel.state.first { it is GenreIndexLoad.Failed }
        }

        viewModel.load(
            listOf(genreTrack(id = "t2", title = "Rock Song", sourceId = "ownerB")),
            scopeOwnerId = "ownerB",
            isAllScope = false,
        )
        val ready = withTimeout(5_000) {
            viewModel.state.first { it is GenreIndexLoad.Ready }
        } as GenreIndexLoad.Ready
        assertEquals("OWNER:ownerB", ready.scopeKey)
        assertTrue(ready.index.genres.any { it.title == "Rock" })
    }

    @Test
    fun clearCancelsObserverAndDiscardsLateEvidence() = runBlocking {
        val trackDao = mockk<TrackDao>()
        val evidence = MutableSharedFlow<List<CachedGenreMetadata>>(extraBufferCapacity = 1)
        every { trackDao.observeGenreEvidenceSnapshot() } returns evidence

        val viewModel = GenreLibraryViewModel(trackDao)
        viewModel.load(
            listOf(genreTrack(id = "t1", title = "Jazz Song", sourceId = "ownerA")),
            scopeOwnerId = "ownerA",
            isAllScope = false,
        )
        viewModel.clear()

        evidence.emit(
            listOf(genreRow(id = "t1", title = "Jazz Song", sourceId = "ownerA", genre = "Jazz")),
        )
        delay(200)

        assertTrue(viewModel.state.value is GenreIndexLoad.Loading)
    }

    private fun genreTrack(id: String, title: String, sourceId: String) = Track(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        duration = 1000,
        filepath = "/music/$id.flac",
        sourceId = sourceId,
    )

    private fun genreRow(
        id: String,
        title: String,
        sourceId: String,
        genre: String,
    ) = CachedGenreMetadata(
        sourceId = sourceId,
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        duration = 1000,
        filepath = "/music/$id.flac",
        genre = genre,
    )
}
