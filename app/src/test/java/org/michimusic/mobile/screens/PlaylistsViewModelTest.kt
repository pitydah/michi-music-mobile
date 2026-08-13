package org.michimusic.mobile.screens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
import org.michimusic.data.repository.PlaylistRepository

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistsViewModelTest {

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var repo: PlaylistRepository
    private lateinit var viewModel: PlaylistsViewModel

    @Before
    fun setup() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        repo = FakePlaylistRepo()
        viewModel = PlaylistsViewModel(repo, testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
        val vm = PlaylistsViewModel(emptyRepo, testDispatcher)
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
}

private open class FakePlaylistRepo : PlaylistRepository() {
    override suspend fun getAllPlaylists(): List<Playlist> = listOf(
        Playlist(id = "pl1", name = "Favorites", trackCount = 2),
        Playlist(id = "pl2", name = "Recently Added", trackCount = 1),
    )
    override suspend fun getById(id: String) = getAllPlaylists().find { it.id == id }
}
