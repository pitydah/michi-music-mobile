package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import org.michimusic.data.repository.LocalMediaRepository
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
    private fun viewModelWithPlaylist(
        playlist: Playlist?,
        tracks: List<Track> = emptyList(),
        deviceTracks: List<Track> = emptyList(),
    ): PlaylistsViewModel {
        val repo = FakeDetailRepo(playlist, tracks, deviceTracks)
        val localMedia = object : LocalMediaRepository() {
            override suspend fun loadTracks(): List<Track> = deviceTracks
        }
        return PlaylistsViewModel(repo, localMedia, Dispatchers.Unconfined)
    }

    // Mutable so that confirming AddTracksDialog can be observed reflected back into
    // the screen's Found state, the same way the real Repository would persist it.
    // Resolves added ids against deviceTracks (like the real TrackDao would) so the
    // resulting title/artist shown in the list matches what the picker offered.
    private class FakeDetailRepo(
        private val basePlaylist: Playlist?,
        initialTracks: List<Track>,
        private val resolvableTracks: List<Track>,
    ) : PlaylistRepository(
        trackDao = mockk<TrackDao>(relaxed = true),
        localMediaRepository = LocalMediaRepository(),
    ) {
        private var tracks: List<Track> = initialTracks
        private var trackCount: Int = basePlaylist?.trackCount ?: initialTracks.size

        override suspend fun getById(id: String): Playlist? = basePlaylist?.copy(trackCount = trackCount)
        override suspend fun getTracksForPlaylist(playlist: Playlist): List<Track> = tracks
        override suspend fun addTracksToPlaylist(id: String, newTrackIds: List<String>) {
            val existingIds = tracks.map { it.id }
            val toAdd = newTrackIds.filter { it !in existingIds }
            tracks = tracks + toAdd.mapNotNull { trackId -> resolvableTracks.find { it.id == trackId } }
            trackCount = tracks.size
        }
        override suspend fun removeTrackFromPlaylist(id: String, trackId: String) {
            tracks = tracks.filter { it.id != trackId }
            trackCount = tracks.size
        }
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
    fun `plus button opens the add tracks selector`() {
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Vacia", trackCount = 0),
            deviceTracks = listOf(Track(id = "d1", title = "Discovery", artist = "New Artist")),
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("playlist_detail_add_tracks_button").performClick()

        composeTestRule.onNodeWithTag("add_tracks_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Discovery").assertIsDisplayed()
    }

    @Test
    fun `adding a track replaces the empty state with the track list`() {
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Vacia", trackCount = 0),
            deviceTracks = listOf(Track(id = "d1", title = "Discovery", artist = "New Artist")),
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("playlist_detail_empty_state").assertIsDisplayed()

        composeTestRule.onNodeWithTag("playlist_detail_add_tracks_button").performClick()
        composeTestRule.onNodeWithTag("add_tracks_dialog_track_d1").performClick()
        composeTestRule.onNodeWithTag("add_tracks_dialog_confirm_button").performClick()

        composeTestRule.onNodeWithTag("playlist_detail_track_list").assertIsDisplayed()
        composeTestRule.onNodeWithText("Discovery").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 canción").assertIsDisplayed()
    }

    @Test
    fun `tracks already in the playlist are not offered again in the selector`() {
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("t1"), trackCount = 1),
            tracks = listOf(Track(id = "t1", title = "Already Here", artist = "Someone")),
            deviceTracks = listOf(
                Track(id = "t1", title = "Already Here", artist = "Someone"),
                Track(id = "d2", title = "Fresh Pick", artist = "New Artist"),
            ),
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("playlist_detail_add_tracks_button").performClick()

        composeTestRule.onNodeWithTag("add_tracks_dialog_track_d2").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("add_tracks_dialog_track_t1").assertCountEquals(0)
    }

    // Reproduces the 3B-Fix2 bug directly: a track persisted in trackIds that could not be
    // resolved into `playlist.tracks` (e.g. a local track not yet backed by cached_tracks)
    // must still be filtered out of the picker - filtering must key off trackIds, not the
    // resolved tracks list.
    @Test
    fun `track persisted in trackIds but unresolved in tracks is still filtered out of the selector`() {
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("t1"), trackCount = 1),
            tracks = emptyList(),
            deviceTracks = listOf(
                Track(id = "t1", title = "Unresolved But Saved", artist = "Someone"),
                Track(id = "d2", title = "Fresh Pick", artist = "New Artist"),
            ),
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("playlist_detail_add_tracks_button").performClick()

        composeTestRule.onNodeWithTag("add_tracks_dialog_track_d2").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("add_tracks_dialog_track_t1").assertCountEquals(0)
    }

    // --- 3C: removing tracks ---

    @Test
    fun `remove action is visible for each track row`() {
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

        composeTestRule.onNodeWithTag("playlist_detail_remove_track_t1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_detail_remove_track_t2").assertIsDisplayed()
    }

    @Test
    fun `tapping remove opens a confirmation dialog naming the track`() {
        val tracks = listOf(Track(id = "t1", title = "Neon Skyline", artist = "Cyber Runner"))
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Synthwave Nights", trackIds = listOf("t1"), trackCount = 1),
            tracks = tracks,
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("playlist_detail_remove_track_t1").performClick()

        composeTestRule.onNodeWithTag("remove_track_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("¿Quieres quitar \"Neon Skyline\" de esta playlist?").assertIsDisplayed()
    }

    @Test
    fun `cancelling the confirmation keeps the track in the playlist`() {
        val tracks = listOf(Track(id = "t1", title = "Neon Skyline", artist = "Cyber Runner"))
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Synthwave Nights", trackIds = listOf("t1"), trackCount = 1),
            tracks = tracks,
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("playlist_detail_remove_track_t1").performClick()
        composeTestRule.onNodeWithTag("remove_track_dialog_cancel_button").performClick()

        composeTestRule.onAllNodesWithTag("remove_track_dialog").assertCountEquals(0)
        composeTestRule.onNodeWithTag("playlist_detail_track_t1").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 canción").assertIsDisplayed()
    }

    @Test
    fun `confirming removal drops the row and updates the count immediately`() {
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

        composeTestRule.onNodeWithTag("playlist_detail_remove_track_t1").performClick()
        composeTestRule.onNodeWithTag("remove_track_dialog_confirm_button").performClick()

        composeTestRule.onAllNodesWithTag("playlist_detail_track_t1").assertCountEquals(0)
        composeTestRule.onNodeWithTag("playlist_detail_track_t2").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 canción").assertIsDisplayed()
    }

    @Test
    fun `removing the last track shows the empty state without leaving the screen`() {
        val tracks = listOf(Track(id = "t1", title = "Neon Skyline", artist = "Cyber Runner"))
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Synthwave Nights", trackIds = listOf("t1"), trackCount = 1),
            tracks = tracks,
        )
        var backCalled = false

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = { backCalled = true }, viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag("playlist_detail_remove_track_t1").performClick()
        composeTestRule.onNodeWithTag("remove_track_dialog_confirm_button").performClick()

        composeTestRule.onNodeWithTag("playlist_detail_empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_detail_screen").assertIsDisplayed()
        assertTrue(!backCalled)
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
