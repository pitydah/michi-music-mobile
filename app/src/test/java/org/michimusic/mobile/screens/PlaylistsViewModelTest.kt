package org.michimusic.mobile.screens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.michimusic.data.cache.CachedPlaylist
import org.michimusic.data.repository.PlaylistRepository

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repo: PlaylistRepository
    private lateinit var viewModel: PlaylistsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repo = FakePlaylistRepo()
        viewModel = PlaylistsViewModel(repo)
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
            override suspend fun getAllPlaylists(): List<CachedPlaylist> = emptyList()
        }
        val vm = PlaylistsViewModel(emptyRepo)
        vm.loadPlaylists()
        advanceUntilIdle()
        assertTrue(vm.playlists.value.isEmpty())
    }

    @Test
    fun loadPlaylists_togglesLoadingState() = runTest(testDispatcher) {
        assertFalse(viewModel.isLoading.value)
        viewModel.loadPlaylists()
        assertTrue(viewModel.isLoading.value)
        advanceUntilIdle()
        assertFalse(viewModel.isLoading.value)
    }
}

private open class FakePlaylistRepo : PlaylistRepository {
    override suspend fun getAllPlaylists(): List<CachedPlaylist> = listOf(
        CachedPlaylist(id = "pl1", name = "Favorites", trackIds = "t1,t2", trackCount = 2),
        CachedPlaylist(id = "pl2", name = "Recently Added", trackIds = "t3", trackCount = 1),
    )
    override suspend fun getById(id: String) = getAllPlaylists().find { it.id == id }
}
