package org.michimusic.mobile.screens

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class UI8SearchLoadLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private val local = mockk<LocalMediaRepository>(relaxed = true)
    private val synced = mockk<SyncedTrackRepository>(relaxed = true)
    private val album = Album(id = "root", title = "Raíz", artist = "Artista")

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { local.filterHiddenSynced(any()) } answers { firstArg() }
        coEvery { synced.getAllSynced() } returns flowOf(emptyList())
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun tracks(id: String) = listOf(
        LocalMediaRepository.LocalAlbum(album = album,
            tracks = listOf(Track(id = id, title = "Raíz $id", artist = "Artista"))),
    )

    @Test fun existingRoomFlowRefreshesSyncedRowsWithoutReloadingLocalMedia() = runTest(dispatcher) {
        val updates = MutableStateFlow(emptyList<CachedTrack>())
        coEvery { synced.getAllSynced() } returns updates
        coEvery { local.loadAlbums() } returns tracks("local")
        val viewModel = SearchViewModel(local, synced, dispatcher)
        viewModel.loadLocalTracks()
        advanceUntilIdle()
        viewModel.setQuery("nuevo")
        advanceUntilIdle()
        assertTrue(viewModel.structuredResults.value.tracks.isEmpty())

        val first = CachedTrack(id = "same-id", sourceId = "micro-a", title = "Nuevo A",
            artist = "Autor", album = "Album", duration = 100, size = 1000,
            format = "flac", bitrate = 900, sampleRate = 44100, channels = 2,
            coverId = "", trackNumber = 1, year = 2026, filepath = "file://same.flac",
            downloaded = false)
        val second = first.copy(sourceId = "micro-b", title = "Nuevo B")
        updates.value = listOf(first, second)
        advanceUntilIdle()
        assertEquals(setOf("micro-a", "micro-b"),
            viewModel.structuredResults.value.tracks.map { it.track.sourceId }.toSet())
        updates.value = listOf(second)
        advanceUntilIdle()
        assertEquals(listOf("micro-b"),
            viewModel.structuredResults.value.tracks.map { it.track.sourceId })
        coVerify(exactly = 1) { local.loadAlbums() }
    }

    @Test fun duplicateRoomEmissionDoesNotRevokeValidResults() = runTest(dispatcher) {
        val updates = MutableSharedFlow<List<CachedTrack>>(replay = 1)
        updates.tryEmit(emptyList())
        every { synced.getAllSynced() } returns updates
        coEvery { local.loadAlbums() } returns tracks("local")
        val viewModel = SearchViewModel(local, synced, dispatcher)
        viewModel.loadLocalTracks()
        advanceUntilIdle()
        viewModel.setQuery("raíz")
        advanceUntilIdle()
        val searchGeneration = viewModel.currentSearchGeneration
        val published = viewModel.structuredResults.value
        updates.emit(emptyList()) // Room can invalidate a table with identical rows.
        advanceUntilIdle()
        assertEquals(searchGeneration, viewModel.currentSearchGeneration)
        assertEquals(published, viewModel.structuredResults.value)
        coVerify(exactly = 1) { local.loadAlbums() }
    }

    @Test fun staleFirstObserverEmissionCannotReplaceNewerInitialRead() = runTest(dispatcher) {
        fun row(title: String) = CachedTrack(id = "shared", sourceId = "micro-a",
            title = title, artist = "Autor", album = "Album", duration = 100,
            size = 1000, format = "flac", bitrate = 900, sampleRate = 44100,
            channels = 2, coverId = "", trackNumber = 1, year = 2026,
            filepath = "file://track.flac", downloaded = false)
        var subscriptions = 0
        every { synced.getAllSynced() } answers {
            subscriptions++
            if (subscriptions == 1) flow {
                delay(200) // delayed first collector emission from an older snapshot
                emit(listOf(row("Nuevo antiguo")))
            } else flowOf(listOf(row("Nuevo actual")))
        }
        coEvery { local.loadAlbums() } returns tracks("local")
        val viewModel = SearchViewModel(local, synced, dispatcher)
        viewModel.loadLocalTracks()
        advanceUntilIdle()
        viewModel.setQuery("nuevo")
        advanceUntilIdle()
        assertEquals(listOf("Nuevo actual"),
            viewModel.structuredResults.value.tracks.map { it.track.title })
        coVerify(exactly = 1) { local.loadAlbums() }
    }

    @Test fun roomCollectorFailureRevokesResultsAndRetryRestoresThem() = runTest(dispatcher) {
        val crash = MutableSharedFlow<Unit>()
        var subscriptions = 0
        every { synced.getAllSynced() } answers {
            subscriptions++
            if (subscriptions == 1) flow {
                emit(emptyList<CachedTrack>())
                crash.first()
                throw IllegalStateException("Room observer unavailable")
            } else flowOf(emptyList())
        }
        coEvery { local.loadAlbums() } returns tracks("local")
        val viewModel = SearchViewModel(local, synced, dispatcher)
        viewModel.loadLocalTracks()
        advanceUntilIdle()
        viewModel.setQuery("raíz")
        advanceUntilIdle()
        assertEquals(listOf("local"), viewModel.structuredResults.value.tracks.map { it.track.id })

        crash.emit(Unit)
        advanceUntilIdle()
        assertEquals("No se pudo observar la biblioteca sincronizada", viewModel.error.value)
        assertTrue(viewModel.results.value.isEmpty())
        assertTrue(viewModel.structuredResults.value.tracks.isEmpty())
        viewModel.setQuery("raíz")
        advanceUntilIdle()
        assertTrue(viewModel.structuredResults.value.tracks.isEmpty())
        viewModel.loadLocalTracks(force = true)
        advanceUntilIdle()
        assertEquals(null, viewModel.error.value)
        assertEquals(listOf("local"), viewModel.structuredResults.value.tracks.map { it.track.id })
        coVerify(exactly = 2) { local.loadAlbums() }
    }

    @Test fun collectorFailureDuringInitialLoadCannotPublishUnobservedResults() = runTest(dispatcher) {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val crash = MutableSharedFlow<Unit>()
        var subscriptions = 0
        every { synced.getAllSynced() } answers {
            subscriptions++
            if (subscriptions == 1) flow {
                emit(emptyList<CachedTrack>())
                crash.first()
                throw IllegalStateException("Room collector died")
            } else flowOf(emptyList())
        }
        coEvery { local.loadAlbums() } coAnswers {
            release.await()
            tracks("local")
        }
        val viewModel = SearchViewModel(local, synced, dispatcher)
        viewModel.loadLocalTracks()
        runCurrent() // loading is still blocked; Room collector can terminate first
        crash.emit(Unit)
        advanceUntilIdle()
        release.complete(Unit)
        advanceUntilIdle()
        viewModel.setQuery("raíz")
        advanceUntilIdle()
        assertTrue(viewModel.structuredResults.value.tracks.isEmpty())
        assertEquals("No se pudo observar la biblioteca sincronizada", viewModel.error.value)
        viewModel.loadLocalTracks(force = true)
        advanceUntilIdle()
        assertEquals(listOf("local"), viewModel.structuredResults.value.tracks.map { it.track.id })
    }

    @Test fun failedRootReloadBlocksPreviousSongsThroughQueryAndFilterUntilSuccessfulRetry() = runTest(dispatcher) {
        var calls = 0
        coEvery { local.loadAlbums() } coAnswers {
            when (++calls) {
                1 -> tracks("old")
                2 -> throw IllegalStateException("No se puede leer la nueva carpeta")
                else -> tracks("new")
            }
        }
        val viewModel = SearchViewModel(local, synced, dispatcher)
        viewModel.loadLocalTracks()
        advanceUntilIdle()
        viewModel.setQuery("raíz")
        advanceUntilIdle()
        assertEquals(listOf("old"), viewModel.structuredResults.value.tracks.map { it.track.id })

        viewModel.loadLocalTracks(force = true)
        // Root switch invalidates even before the new IO dispatcher is scheduled.
        assertTrue(viewModel.structuredResults.value.tracks.isEmpty())
        assertTrue(viewModel.results.value.isEmpty())
        advanceUntilIdle()
        assertEquals("No se puede leer la nueva carpeta", viewModel.error.value)
        assertFalse(viewModel.isSearching.value)

        viewModel.setQuery("raíz ")
        advanceUntilIdle()
        assertEquals("No se puede leer la nueva carpeta", viewModel.error.value)
        assertTrue(viewModel.structuredResults.value.tracks.isEmpty())
        assertTrue(viewModel.results.value.isEmpty())
        viewModel.setFilter(SearchFilter.TRACKS)
        advanceUntilIdle()
        assertTrue(viewModel.structuredResults.value.tracks.isEmpty())
        assertEquals("No se puede leer la nueva carpeta", viewModel.error.value)

        viewModel.loadLocalTracks() // explicit retry
        advanceUntilIdle()
        assertEquals(3, calls)
        assertEquals(null, viewModel.error.value)
        assertEquals(listOf("new"), viewModel.structuredResults.value.tracks.map { it.track.id })
    }

    @Test fun observedRootRevisionFailsClosedWithoutDisplayingPreviousRoot() = runTest(dispatcher) {
        val authority = mockk<MusicRootAuthority>(relaxed = true)
        val revisions = MutableStateFlow(0L)
        every { authority.revision } returns revisions
        var calls = 0
        coEvery { local.loadAlbums() } coAnswers {
            when (++calls) {
                1 -> tracks("old")
                2 -> throw IllegalStateException("Permiso SAF revocado")
                else -> tracks("new")
            }
        }
        val viewModel = SearchViewModel(local, synced, dispatcher, authority)
        runCurrent() // start collecting root revisions before changes
        viewModel.loadLocalTracks()
        advanceUntilIdle()
        viewModel.setQuery("raíz")
        advanceUntilIdle()
        assertEquals(listOf("old"), viewModel.structuredResults.value.tracks.map { it.track.id })

        revisions.value = 1L
        runCurrent()
        assertTrue(viewModel.structuredResults.value.tracks.isEmpty())
        advanceUntilIdle()
        assertEquals("Permiso SAF revocado", viewModel.error.value)
        viewModel.setQuery("raíz")
        advanceUntilIdle()
        assertTrue(viewModel.structuredResults.value.tracks.isEmpty())
        viewModel.loadLocalTracks()
        advanceUntilIdle()
        assertEquals(listOf("new"), viewModel.structuredResults.value.tracks.map { it.track.id })
    }
}
