package org.michimusic.mobile.screens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.Playlist
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.PlaylistRepository

import org.junit.Rule
import org.michimusic.core.models.Track
import org.michimusic.mobile.rules.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val testDispatcher get() = mainDispatcherRule.testDispatcher
    private lateinit var repo: PlaylistRepository
    private lateinit var viewModel: PlaylistsViewModel

    @Before
    fun setup() {
        repo = FakePlaylistRepo()
        viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), testDispatcher)
    }

    @After
    fun tearDown() {
    }

    @Test
    fun loadPlaylists_populatesList() = runTest(testDispatcher) {
        viewModel.loadPlaylists()
        advanceUntilIdle()
        assertEquals(2, viewModel.playlists.value.size)
        assertEquals("Favorites", viewModel.playlists.value.first().name)
    }

    @Test
    fun loadPlaylists_emptyRepo_returnsEmpty() = runTest(testDispatcher) {
        val emptyRepo = object : FakePlaylistRepo() {
            override suspend fun getAllPlaylists(): List<Playlist> = emptyList()
        }
        val vm = PlaylistsViewModel(emptyRepo, LocalMediaRepository(), testDispatcher)
        vm.loadPlaylists()
        advanceUntilIdle()
        assertTrue(vm.playlists.value.isEmpty())
    }

    @Test
    fun loadPlaylists_togglesLoadingState() = runTest(testDispatcher) {
        assertFalse(viewModel.isLoading.value)
        viewModel.loadPlaylists()
        advanceUntilIdle()
        assertFalse(viewModel.isLoading.value)
    }

    @Test
    fun loadPlaylistDetail_existingPlaylist_emitsFound() = runTest(testDispatcher) {
        viewModel.loadPlaylistDetail("pl1")
        advanceUntilIdle()

        val state = viewModel.selectedPlaylistState.value
        assertTrue(state is PlaylistDetailUiState.Found)
        assertEquals("Favorites", (state as PlaylistDetailUiState.Found).playlist.name)
        assertEquals(2, state.playlist.trackCount)
    }

    @Test
    fun loadPlaylistDetail_unknownId_emitsNotFound() = runTest(testDispatcher) {
        viewModel.loadPlaylistDetail("does-not-exist")
        advanceUntilIdle()

        assertEquals(PlaylistDetailUiState.NotFound, viewModel.selectedPlaylistState.value)
    }

    @Test
    fun loadPlaylistDetail_resolvesTracksInStoredOrder() = runTest(testDispatcher) {
        val orderedIds = listOf("trackC", "trackA", "trackB")
        val tracksByOrder = orderedIds.mapIndexed { idx, id -> Track(id = id, title = "T$idx") }
        val repoWithTracks = object : FakePlaylistRepo() {
            override suspend fun getById(id: String) =
                Playlist(id = "p1", name = "Ordered", trackIds = orderedIds, trackCount = orderedIds.size)
            override suspend fun getTracksForPlaylist(playlist: Playlist) =
                playlist.trackIds.mapNotNull { trackId -> tracksByOrder.find { it.id == trackId } }
        }
        val vm = PlaylistsViewModel(repoWithTracks, LocalMediaRepository(), testDispatcher)

        vm.loadPlaylistDetail("p1")
        advanceUntilIdle()

        val state = vm.selectedPlaylistState.value
        assertTrue(state is PlaylistDetailUiState.Found)
        assertEquals(orderedIds, (state as PlaylistDetailUiState.Found).playlist.tracks.map { it.id })
    }

    @Test
    fun loadPlaylistDetail_skipsTrackIdsMissingFromResolution() = runTest(testDispatcher) {
        val repoWithMissingTrack = object : FakePlaylistRepo() {
            override suspend fun getById(id: String) =
                Playlist(id = "p1", name = "Mix", trackIds = listOf("t1", "ghost"), trackCount = 2)
            override suspend fun getTracksForPlaylist(playlist: Playlist) =
                playlist.trackIds.filter { it != "ghost" }.map { Track(id = it, title = "Title-$it") }
        }
        val vm = PlaylistsViewModel(repoWithMissingTrack, LocalMediaRepository(), testDispatcher)

        vm.loadPlaylistDetail("p1")
        advanceUntilIdle()

        val state = vm.selectedPlaylistState.value
        assertTrue(state is PlaylistDetailUiState.Found)
        assertEquals(listOf("t1"), (state as PlaylistDetailUiState.Found).playlist.tracks.map { it.id })
    }

    @Test
    fun loadPlaylistDetail_emptyPlaylist_returnsEmptyTracks() = runTest(testDispatcher) {
        val vm = PlaylistsViewModel(FakePlaylistRepo(), LocalMediaRepository(), testDispatcher)
        vm.loadPlaylistDetail("pl2")
        advanceUntilIdle()

        val state = vm.selectedPlaylistState.value
        assertTrue(state is PlaylistDetailUiState.Found)
        assertTrue((state as PlaylistDetailUiState.Found).playlist.tracks.isEmpty())
    }

    @Test
    fun loadPlaylistDetail_doesNotDependOnObservedPlaylistsList() = runTest(testDispatcher) {
        // A repo whose observePlaylists() flow never emits (simulating a not-yet-loaded
        // reactive list) must not block loadPlaylistDetail from resolving via getById.
        val slowRepo = object : FakePlaylistRepo() {
            override fun observePlaylists() = kotlinx.coroutines.flow.flow<List<org.michimusic.core.models.Playlist>> {
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val vm = PlaylistsViewModel(slowRepo, LocalMediaRepository(), testDispatcher)

        vm.loadPlaylistDetail("pl2")
        advanceUntilIdle()

        assertTrue(vm.playlists.value.isEmpty())
        val state = vm.selectedPlaylistState.value
        assertTrue(state is PlaylistDetailUiState.Found)
        assertEquals("Recently Added", (state as PlaylistDetailUiState.Found).playlist.name)
    }

    // --- 3B: adding tracks ---

    @Test
    fun addTracksToPlaylist_callsRepositoryAndRefreshesDetailWithNewTracks() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(
            initialTracks = listOf(Track(id = "A", title = "A"), Track(id = "B", title = "B")),
        )
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        vm.addTracksToPlaylist("p1", listOf("D", "C"))
        advanceUntilIdle()

        assertEquals("p1" to listOf("D", "C"), recordingRepo.addTracksCalledWith)
        val state = vm.selectedPlaylistState.value
        assertTrue(state is PlaylistDetailUiState.Found)
        assertEquals(listOf("A", "B", "D", "C"), (state as PlaylistDetailUiState.Found).playlist.tracks.map { it.id })
    }

    @Test
    fun addTracksToPlaylist_updatesTrackCountInDetailState() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(initialTracks = listOf(Track(id = "A", title = "A")))
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        vm.addTracksToPlaylist("p1", listOf("B"))
        advanceUntilIdle()

        val state = vm.selectedPlaylistState.value as PlaylistDetailUiState.Found
        assertEquals(2, state.playlist.trackCount)
    }

    @Test
    fun addTracksToPlaylist_isAddingTracks_backToFalseAfterSuccess() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo()
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        assertFalse(vm.isAddingTracks.value)
        vm.addTracksToPlaylist("p1", listOf("A"))
        advanceUntilIdle()

        assertFalse(vm.isAddingTracks.value)
    }

    @Test
    fun addTracksToPlaylist_repositoryFailure_doesNotCrashAndClearsAddingState() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(shouldFail = true)
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        vm.addTracksToPlaylist("p1", listOf("X"))
        advanceUntilIdle()

        assertFalse(vm.isAddingTracks.value)
        assertEquals("boom", vm.addTracksError.value)
    }

    @Test
    fun addTracksToPlaylist_emptySelection_doesNotCallRepository() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo()
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        vm.addTracksToPlaylist("p1", emptyList())
        advanceUntilIdle()

        assertEquals(null, recordingRepo.addTracksCalledWith)
    }

    @Test
    fun clearAddTracksError_resetsErrorToNull() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(shouldFail = true)
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        vm.addTracksToPlaylist("p1", listOf("X"))
        advanceUntilIdle()
        assertEquals("boom", vm.addTracksError.value)

        vm.clearAddTracksError()
        assertEquals(null, vm.addTracksError.value)
    }

    @Test
    fun loadAvailableTracks_populatesFromLocalMediaRepository() = runTest(testDispatcher) {
        val deviceTracks = listOf(Track(id = "t1", title = "One"), Track(id = "t2", title = "Two"))
        val localMedia = object : LocalMediaRepository() {
            override suspend fun loadTracks(): List<Track> = deviceTracks
        }
        val vm = PlaylistsViewModel(FakePlaylistRepo(), localMedia, testDispatcher)

        vm.loadAvailableTracks()
        advanceUntilIdle()

        assertEquals(deviceTracks, vm.availableTracks.value)
        assertFalse(vm.isLoadingAvailableTracks.value)
    }

    @Test
    fun loadAvailableTracks_repositoryFailure_returnsEmptyWithoutCrashing() = runTest(testDispatcher) {
        val failingLocalMedia = object : LocalMediaRepository() {
            override suspend fun loadTracks(): List<Track> = throw RuntimeException("scan failed")
        }
        val vm = PlaylistsViewModel(FakePlaylistRepo(), failingLocalMedia, testDispatcher)

        vm.loadAvailableTracks()
        advanceUntilIdle()

        assertTrue(vm.availableTracks.value.isEmpty())
        assertFalse(vm.isLoadingAvailableTracks.value)
    }
}

private open class FakePlaylistRepo : PlaylistRepository(
    trackDao = mockk<TrackDao>(relaxed = true),
    localMediaRepository = LocalMediaRepository(),
) {
    override suspend fun getAllPlaylists(): List<Playlist> = listOf(
        Playlist(id = "pl1", name = "Favorites", trackCount = 2),
        Playlist(id = "pl2", name = "Recently Added", trackCount = 1),
    )
    override suspend fun getById(id: String) = getAllPlaylists().find { it.id == id }
}

// Stateful fake used to verify the add-tracks orchestration (Repository call -> detail
// refresh) without needing a real Room-backed PlaylistRepository.
private class RecordingPlaylistRepo(
    initialTracks: List<Track> = emptyList(),
    private val shouldFail: Boolean = false,
) : PlaylistRepository(
    trackDao = mockk<TrackDao>(relaxed = true),
    localMediaRepository = LocalMediaRepository(),
) {

    private var currentTracks: List<Track> = initialTracks
    var addTracksCalledWith: Pair<String, List<String>>? = null
        private set

    override suspend fun getById(id: String) = Playlist(id = id, name = "Mix", trackCount = currentTracks.size)

    override suspend fun getTracksForPlaylist(playlist: Playlist): List<Track> = currentTracks

    override suspend fun addTracksToPlaylist(id: String, newTrackIds: List<String>) {
        addTracksCalledWith = id to newTrackIds
        if (shouldFail) throw RuntimeException("boom")
        currentTracks = currentTracks + newTrackIds.map { Track(id = it, title = "Title-$it") }
    }
}
