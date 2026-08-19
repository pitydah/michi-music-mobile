package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.repository.PlaylistRepository
import org.michimusic.mobile.screens.PlaylistsViewModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class PlaylistDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Dispatchers.Unconfined resolves the fake repo's (non-suspending) result
    // synchronously, so the ViewModel's state is settled before first composition.
    private fun viewModelWithPlaylist(playlist: Playlist?, tracks: List<Track> = emptyList()): PlaylistsViewModel {
        val repo = object : PlaylistRepository(trackDao = mockk<TrackDao>(relaxed = true)) {
            override suspend fun getById(id: String): Playlist? = playlist
            override suspend fun getTracksForPlaylist(playlist: Playlist): List<Track> = tracks
        }
        return PlaylistsViewModel(repo, Dispatchers.Unconfined)
    }

    @Test
    fun `shows playlist name and track count when found`() {
        val viewModel = viewModelWithPlaylist(Playlist(id = "p1", name = "Synthwave Nights", trackCount = 5))

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithText("Synthwave Nights").assertIsDisplayed()
        composeTestRule.onNodeWithText("5 canciones").assertIsDisplayed()
    }

    @Test
    fun `shows empty state message when playlist has zero tracks`() {
        val viewModel = viewModelWithPlaylist(Playlist(id = "p2", name = "Vacia", trackCount = 0))

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p2", onBack = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("playlist_detail_empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithText("Esta playlist todavía no tiene canciones").assertIsDisplayed()
    }

    @Test
    fun `shows not-found state without crashing for an unknown id`() {
        val viewModel = viewModelWithPlaylist(null)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "missing-id", onBack = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("playlist_detail_not_found_state").assertIsDisplayed()
        composeTestRule.onNodeWithText("Playlist no encontrada").assertIsDisplayed()
    }

    @Test
    fun `shows resolved track titles and artists when playlist has tracks`() {
        val tracks = listOf(
            Track(id = "t1", title = "Neon Skyline", artist = "Cyber Runner"),
            Track(id = "t2", title = "Midnight Drive", artist = "Vapor Wave"),
        )
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Synthwave Nights", trackIds = listOf("t1", "t2"), trackCount = 2),
            tracks = tracks,
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_list").assertIsDisplayed()
        composeTestRule.onNodeWithText("Neon Skyline").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cyber Runner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Midnight Drive").assertIsDisplayed()
        composeTestRule.onNodeWithText("Vapor Wave").assertIsDisplayed()
    }

    @Test
    fun `shows long track title without crashing the layout`() {
        val longTitle = "A".repeat(120)
        val tracks = listOf(Track(id = "t1", title = longTitle, artist = "Some Artist"))
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Long Names", trackIds = listOf("t1"), trackCount = 1),
            tracks = tracks,
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_t1").assertIsDisplayed()
    }

    @Test
    fun `back button invokes onBack`() {
        val viewModel = viewModelWithPlaylist(Playlist(id = "p1", name = "Synthwave Nights", trackCount = 5))
        var backCalled = false

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = { backCalled = true }, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("playlist_detail_back_button").performClick()

        assertTrue(backCalled)
    }
}
