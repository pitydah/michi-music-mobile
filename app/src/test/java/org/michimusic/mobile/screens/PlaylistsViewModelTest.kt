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
import org.michimusic.data.repository.MoveDirection
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
    fun loadPlaylistDetail_repositoryError_emitsError() = runTest(testDispatcher) {
        val failingRepo = object : FakePlaylistRepo() {
            override suspend fun getById(id: String): Playlist? {
                throw java.io.IOException("DB disk read failure")
            }
        }
        val vm = PlaylistsViewModel(failingRepo, LocalMediaRepository(), testDispatcher)
        vm.loadPlaylistDetail("p1")
        advanceUntilIdle()

        val state = vm.selectedPlaylistState.value
        assertTrue(state is PlaylistDetailUiState.Error)
        assertEquals("DB disk read failure", (state as PlaylistDetailUiState.Error).message)
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

    // --- 3C: removing tracks ---

    @Test
    fun removeTrackFromPlaylist_callsRepositoryAndRefreshesDetailWithTrackRemoved() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(
            initialTracks = listOf(Track(id = "A", title = "A"), Track(id = "B", title = "B")),
        )
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        vm.removeTrackFromPlaylist("p1", "A")
        advanceUntilIdle()

        assertEquals("p1" to "A", recordingRepo.removeTrackCalledWith)
        val state = vm.selectedPlaylistState.value
        assertTrue(state is PlaylistDetailUiState.Found)
        assertEquals(listOf("B"), (state as PlaylistDetailUiState.Found).playlist.tracks.map { it.id })
    }

    @Test
    fun removeTrackFromPlaylist_updatesTrackCountInDetailState() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(
            initialTracks = listOf(Track(id = "A", title = "A"), Track(id = "B", title = "B")),
        )
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        vm.removeTrackFromPlaylist("p1", "A")
        advanceUntilIdle()

        val state = vm.selectedPlaylistState.value as PlaylistDetailUiState.Found
        assertEquals(1, state.playlist.trackCount)
    }

    @Test
    fun removeTrackFromPlaylist_removingLastTrack_leavesEmptyFoundState() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(initialTracks = listOf(Track(id = "A", title = "A")))
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        vm.removeTrackFromPlaylist("p1", "A")
        advanceUntilIdle()

        val state = vm.selectedPlaylistState.value as PlaylistDetailUiState.Found
        assertEquals(0, state.playlist.trackCount)
        assertTrue(state.playlist.tracks.isEmpty())
    }

    @Test
    fun removeTrackFromPlaylist_isRemovingTrack_backToFalseAfterSuccess() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(initialTracks = listOf(Track(id = "A", title = "A")))
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        assertFalse(vm.isRemovingTrack.value)
        vm.removeTrackFromPlaylist("p1", "A")
        advanceUntilIdle()

        assertFalse(vm.isRemovingTrack.value)
    }

    @Test
    fun removeTrackFromPlaylist_repositoryFailure_doesNotCrashAndExposesError() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(shouldFail = true)
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        vm.removeTrackFromPlaylist("p1", "A")
        advanceUntilIdle()

        assertFalse(vm.isRemovingTrack.value)
        assertEquals("boom", vm.addTracksError.value)
        // The current detail state must survive the failure, not be wiped out.
        assertTrue(vm.selectedPlaylistState.value !is PlaylistDetailUiState.NotFound)
    }

    @Test
    fun removeTrackFromPlaylist_calledAgainWhileInFlight_isIgnored() = runTest(testDispatcher) {
        // A repository call that never completes keeps isRemovingTrack true, so a second
        // tap while the first removal is still in flight must be a synchronous no-op.
        var callCount = 0
        val neverCompletingRepo = object : PlaylistRepository(
            trackDao = mockk<TrackDao>(relaxed = true),
            localMediaRepository = LocalMediaRepository(),
        ) {
            override suspend fun getById(id: String) = Playlist(id = id, name = "Mix", trackCount = 2)
            override suspend fun getTracksForPlaylist(playlist: Playlist): List<Track> = emptyList()
            override suspend fun removeTrackFromPlaylist(id: String, trackId: String) {
                callCount++
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val vm = PlaylistsViewModel(neverCompletingRepo, LocalMediaRepository(), testDispatcher)

        vm.removeTrackFromPlaylist("p1", "A")
        testDispatcher.scheduler.runCurrent()
        vm.removeTrackFromPlaylist("p1", "B")
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, callCount)
        assertTrue(vm.isRemovingTrack.value)
    }

    // --- 4C: deleting a playlist ---

    @Test
    fun deletePlaylist_callsRepositoryExactlyOnceAndReportsSuccess() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(initialTracks = listOf(Track(id = "A", title = "A")))
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)
        var result: Boolean? = null

        vm.deletePlaylist("p1") { success -> result = success }
        advanceUntilIdle()

        assertEquals("p1", recordingRepo.deletePlaylistCalledWith)
        assertEquals(1, recordingRepo.deletePlaylistCallCount)
        assertEquals(true, result)
    }

    @Test
    fun deletePlaylist_isDeletingPlaylist_backToFalseAfterSuccess() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(initialTracks = listOf(Track(id = "A", title = "A")))
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        assertFalse(vm.isDeletingPlaylist.value)
        vm.deletePlaylist("p1")
        advanceUntilIdle()

        assertFalse(vm.isDeletingPlaylist.value)
    }

    @Test
    fun deletePlaylist_repositoryFailure_doesNotCrashExposesErrorAndReportsFailure() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(shouldFail = true)
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)
        var result: Boolean? = null

        vm.deletePlaylist("p1") { success -> result = success }
        advanceUntilIdle()

        assertFalse(vm.isDeletingPlaylist.value)
        assertEquals("boom", vm.addTracksError.value)
        assertEquals(false, result)
    }

    @Test
    fun deletePlaylist_calledAgainWhileInFlight_isIgnored() = runTest(testDispatcher) {
        // Mirrors removeTrackFromPlaylist_calledAgainWhileInFlight_isIgnored: a repository
        // call that never completes keeps isDeletingPlaylist true, so a second tap on the
        // confirm button while the first deletion is still in flight must be a no-op.
        var callCount = 0
        val neverCompletingRepo = object : PlaylistRepository(
            trackDao = mockk<TrackDao>(relaxed = true),
            localMediaRepository = LocalMediaRepository(),
        ) {
            override suspend fun deletePlaylist(id: String) {
                callCount++
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val vm = PlaylistsViewModel(neverCompletingRepo, LocalMediaRepository(), testDispatcher)

        vm.deletePlaylist("p1")
        testDispatcher.scheduler.runCurrent()
        vm.deletePlaylist("p1")
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, callCount)
        assertTrue(vm.isDeletingPlaylist.value)
    }

    // --- 4D: renaming a playlist ---

    @Test
    fun renamePlaylist_validName_callsRepositoryExactlyOnceAndReportsSuccess() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo()
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)
        var result: Boolean? = null

        vm.renamePlaylist("p1", "New Name") { success -> result = success }
        advanceUntilIdle()

        assertEquals("p1" to "New Name", recordingRepo.renamePlaylistCalledWith)
        assertEquals(1, recordingRepo.renamePlaylistCallCount)
        assertEquals(true, result)
    }

    @Test
    fun renamePlaylist_success_refreshesDetailStateWithNewName() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo()
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        vm.renamePlaylist("p1", "New Name")
        advanceUntilIdle()

        val state = vm.selectedPlaylistState.value
        assertTrue(state is PlaylistDetailUiState.Found)
        assertEquals("New Name", (state as PlaylistDetailUiState.Found).playlist.name)
    }

    @Test
    fun renamePlaylist_isRenamingPlaylist_backToFalseAfterSuccess() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo()
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        assertFalse(vm.isRenamingPlaylist.value)
        vm.renamePlaylist("p1", "New Name")
        advanceUntilIdle()

        assertFalse(vm.isRenamingPlaylist.value)
    }

    @Test
    fun renamePlaylist_repositoryFailure_doesNotCrashExposesErrorAndReportsFailure() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(shouldFail = true)
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)
        var result: Boolean? = null

        vm.renamePlaylist("p1", "New Name") { success -> result = success }
        advanceUntilIdle()

        assertFalse(vm.isRenamingPlaylist.value)
        assertEquals("boom", vm.addTracksError.value)
        assertEquals(false, result)
    }

    @Test
    fun renamePlaylist_calledAgainWhileInFlight_isIgnored() = runTest(testDispatcher) {
        var callCount = 0
        val neverCompletingRepo = object : PlaylistRepository(
            trackDao = mockk<TrackDao>(relaxed = true),
            localMediaRepository = LocalMediaRepository(),
        ) {
            override suspend fun renamePlaylist(id: String, newName: String) {
                callCount++
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val vm = PlaylistsViewModel(neverCompletingRepo, LocalMediaRepository(), testDispatcher)

        vm.renamePlaylist("p1", "New Name")
        testDispatcher.scheduler.runCurrent()
        vm.renamePlaylist("p1", "Another Name")
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, callCount)
        assertTrue(vm.isRenamingPlaylist.value)
    }

    @Test
    fun renamePlaylist_blankName_doesNotCallRepositoryAndReportsFailure() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo()
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)
        var result: Boolean? = null

        vm.renamePlaylist("p1", "   ") { success -> result = success }
        advanceUntilIdle()

        assertEquals(0, recordingRepo.renamePlaylistCallCount)
        assertEquals(false, result)
    }

    @Test
    fun renamePlaylist_trimsNameBeforeCallingRepository() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo()
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        vm.renamePlaylist("p1", "  Padded Name  ")
        advanceUntilIdle()

        assertEquals("p1" to "Padded Name", recordingRepo.renamePlaylistCalledWith)
    }

    // --- 4F: reordering tracks ---

    @Test
    fun moveTrack_validOperation_callsRepositoryExactlyOnce() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(
            initialTracks = listOf(Track(id = "A", title = "A"), Track(id = "B", title = "B")),
        )
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        vm.moveTrack("p1", "B", MoveDirection.UP)
        advanceUntilIdle()

        assertEquals(Triple("p1", "B", MoveDirection.UP), recordingRepo.moveTrackCalledWith)
        assertEquals(1, recordingRepo.moveTrackCallCount)
    }

    @Test
    fun moveTrack_success_refreshesDetailStateWithNewOrder() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(
            initialTracks = listOf(Track(id = "A", title = "A"), Track(id = "B", title = "B"), Track(id = "C", title = "C")),
        )
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        vm.moveTrack("p1", "C", MoveDirection.UP)
        advanceUntilIdle()

        val state = vm.selectedPlaylistState.value
        assertTrue(state is PlaylistDetailUiState.Found)
        assertEquals(listOf("A", "C", "B"), (state as PlaylistDetailUiState.Found).playlist.tracks.map { it.id })
    }

    @Test
    fun moveTrack_isReorderingTrack_backToFalseAfterSuccess() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(
            initialTracks = listOf(Track(id = "A", title = "A"), Track(id = "B", title = "B")),
        )
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        assertFalse(vm.isReorderingTrack.value)
        vm.moveTrack("p1", "B", MoveDirection.UP)
        advanceUntilIdle()

        assertFalse(vm.isReorderingTrack.value)
    }

    @Test
    fun moveTrack_repositoryFailure_doesNotCrashAndExposesError() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(
            initialTracks = listOf(Track(id = "A", title = "A"), Track(id = "B", title = "B")),
            shouldFail = true,
        )
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        vm.moveTrack("p1", "B", MoveDirection.UP)
        advanceUntilIdle()

        assertFalse(vm.isReorderingTrack.value)
        assertEquals("boom", vm.addTracksError.value)
    }

    @Test
    fun moveTrack_boundaryNoOp_doesNotChangeOrder() = runTest(testDispatcher) {
        val recordingRepo = RecordingPlaylistRepo(
            initialTracks = listOf(Track(id = "A", title = "A"), Track(id = "B", title = "B")),
        )
        val vm = PlaylistsViewModel(recordingRepo, LocalMediaRepository(), testDispatcher)

        // "A" is already first - the Repository's own boundary check makes this a no-op;
        // the ViewModel doesn't duplicate that validation, it just must not corrupt state.
        vm.moveTrack("p1", "A", MoveDirection.UP)
        advanceUntilIdle()

        val state = vm.selectedPlaylistState.value as PlaylistDetailUiState.Found
        assertEquals(listOf("A", "B"), state.playlist.tracks.map { it.id })
    }

    @Test
    fun moveTrack_calledAgainWhileInFlight_isIgnored() = runTest(testDispatcher) {
        var callCount = 0
        val neverCompletingRepo = object : PlaylistRepository(
            trackDao = mockk<TrackDao>(relaxed = true),
            localMediaRepository = LocalMediaRepository(),
        ) {
            override suspend fun moveTrack(id: String, trackId: String, direction: MoveDirection) {
                callCount++
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val vm = PlaylistsViewModel(neverCompletingRepo, LocalMediaRepository(), testDispatcher)

        vm.moveTrack("p1", "A", MoveDirection.UP)
        testDispatcher.scheduler.runCurrent()
        vm.moveTrack("p1", "B", MoveDirection.DOWN)
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, callCount)
        assertTrue(vm.isReorderingTrack.value)
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
    var removeTrackCalledWith: Pair<String, String>? = null
        private set
    var deletePlaylistCalledWith: String? = null
        private set
    var deletePlaylistCallCount: Int = 0
        private set
    var renamePlaylistCalledWith: Pair<String, String>? = null
        private set
    var renamePlaylistCallCount: Int = 0
        private set
    var moveTrackCalledWith: Triple<String, String, MoveDirection>? = null
        private set
    var moveTrackCallCount: Int = 0
        private set
    private var currentName: String = "Mix"

    override suspend fun getById(id: String) = Playlist(id = id, name = currentName, trackCount = currentTracks.size)

    override suspend fun getTracksForPlaylist(playlist: Playlist): List<Track> = currentTracks

    override suspend fun addTracksToPlaylist(id: String, newTrackIds: List<String>) {
        addTracksCalledWith = id to newTrackIds
        if (shouldFail) throw RuntimeException("boom")
        currentTracks = currentTracks + newTrackIds.map { Track(id = it, title = "Title-$it") }
    }

    override suspend fun removeTrackFromPlaylist(id: String, trackId: String) {
        removeTrackCalledWith = id to trackId
        if (shouldFail) throw RuntimeException("boom")
        currentTracks = currentTracks.filter { it.id != trackId }
    }

    override suspend fun deletePlaylist(id: String) {
        deletePlaylistCalledWith = id
        deletePlaylistCallCount++
        if (shouldFail) throw RuntimeException("boom")
    }

    override suspend fun renamePlaylist(id: String, newName: String) {
        renamePlaylistCalledWith = id to newName
        renamePlaylistCallCount++
        if (shouldFail) throw RuntimeException("boom")
        currentName = newName
    }

    override suspend fun moveTrack(id: String, trackId: String, direction: MoveDirection) {
        moveTrackCalledWith = Triple(id, trackId, direction)
        moveTrackCallCount++
        if (shouldFail) throw RuntimeException("boom")
        val fromIndex = currentTracks.indexOfFirst { it.id == trackId }
        if (fromIndex < 0) return
        val toIndex = if (direction == MoveDirection.UP) fromIndex - 1 else fromIndex + 1
        if (toIndex !in currentTracks.indices) return
        currentTracks = currentTracks.toMutableList().apply {
            val moved = removeAt(fromIndex)
            add(toIndex, moved)
        }
    }
}
