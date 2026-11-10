package org.michimusic.mobile.ui.screens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.repository.LocalMediaRepository
import org.michimusic.data.repository.MoveDirection
import org.michimusic.data.repository.PlaylistRepository
import org.michimusic.mobile.screens.PlaylistsViewModel
import org.michimusic.player.AudioController
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class PlaylistDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun clearVisualPreferences() {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("michi_settings", 0).edit().clear().commit()
        File(RuntimeEnvironment.getApplication().filesDir, PLAYLIST_PRIVATE_IMAGE_DIRECTORY)
            .deleteRecursively()
    }

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
        private val deleteShouldFail: Boolean = false,
        // When true, deletePlaylist() suspends forever instead of returning - used to
        // simulate an in-flight deletion so a second tap can be observed as a no-op.
        var deleteBlocksForever: Boolean = false,
        private val renameShouldFail: Boolean = false,
        // When true, renamePlaylist() suspends forever instead of returning - used to
        // simulate an in-flight rename so a second tap can be observed as a no-op.
        var renameBlocksForever: Boolean = false,
        // When true, moveTrack() suspends forever instead of returning - used to simulate
        // an in-flight reorder so a second tap can be observed as a no-op.
        var moveBlocksForever: Boolean = false,
    ) : PlaylistRepository(
        trackDao = mockk<TrackDao>(relaxed = true),
        localMediaRepository = LocalMediaRepository(),
    ) {
        private var tracks: List<Track> = initialTracks
        private var trackCount: Int = basePlaylist?.trackCount ?: initialTracks.size
        private var name: String = basePlaylist?.name ?: ""
        var deleteCallCount: Int = 0
            private set
        var renameCallCount: Int = 0
            private set
        var renameCalledWith: String? = null
            private set
        var moveTrackCallCount: Int = 0
            private set
        var moveTrackCalledWith: Pair<String, MoveDirection>? = null
            private set

        override suspend fun getById(id: String): Playlist? = basePlaylist?.copy(name = name, trackCount = trackCount)
        override suspend fun getTracksForPlaylist(playlist: Playlist): List<Track> = tracks
        override suspend fun addTracksToPlaylist(id: String, newTrackIds: List<String>): PlaylistRepository.PlaylistAddResult {
            val existingIds = tracks.map { it.id }
            val toAdd = newTrackIds.filter { it !in existingIds }
            val before = tracks.size
            tracks = tracks + toAdd.mapNotNull { trackId -> resolvableTracks.find { it.id == trackId } }
            trackCount = tracks.size
            return PlaylistRepository.PlaylistAddResult.Success(
                addedCount = tracks.size - before,
                totalCount = tracks.size,
            )
        }
        override suspend fun removeTrackFromPlaylist(id: String, trackId: String) {
            tracks = tracks.filter { it.id != trackId }
            trackCount = tracks.size
        }
        override suspend fun deletePlaylist(id: String) {
            deleteCallCount++
            if (deleteBlocksForever) kotlinx.coroutines.awaitCancellation()
            if (deleteShouldFail) throw RuntimeException("No se pudo eliminar la playlist")
        }
        override suspend fun renamePlaylist(id: String, newName: String) {
            renameCallCount++
            renameCalledWith = newName
            if (renameBlocksForever) kotlinx.coroutines.awaitCancellation()
            if (renameShouldFail) throw RuntimeException("No se pudo renombrar la playlist")
            name = newName
        }
        override suspend fun moveTrack(id: String, trackId: String, direction: MoveDirection) {
            moveTrackCallCount++
            moveTrackCalledWith = trackId to direction
            if (moveBlocksForever) kotlinx.coroutines.awaitCancellation()
            val fromIndex = tracks.indexOfFirst { it.id == trackId }
            if (fromIndex < 0) return
            val toIndex = if (direction == MoveDirection.UP) fromIndex - 1 else fromIndex + 1
            if (toIndex !in tracks.indices) return
            tracks = tracks.toMutableList().apply {
                val moved = removeAt(fromIndex)
                add(toIndex, moved)
            }
        }
    }

    @Test
    fun `shows playlist name and track count when found`() {
        val tracks = (1..5).map { Track(id = "t$it", title = "Track $it") }
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Synthwave Nights", trackIds = tracks.map { it.id }, trackCount = 5),
            tracks = tracks,
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithText("Synthwave Nights").assertIsDisplayed()
        composeTestRule.onNodeWithText("5 canciones").assertIsDisplayed()
    }

    @Test
    fun `shows empty state message when playlist has zero tracks`() {
        val viewModel = viewModelWithPlaylist(Playlist(id = "p2", name = "Vacia", trackCount = 0))

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p2", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithText("Esta playlist todavía no tiene canciones").assertIsDisplayed()
    }

    @Test
    fun `shows not-found state without crashing for an unknown id`() {
        val viewModel = viewModelWithPlaylist(null)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "missing-id", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
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
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
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
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
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
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
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
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
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
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
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
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
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
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_options_t1").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_remove_track_t1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_detail_track_options_t2").assertIsDisplayed()
    }

    @Test
    fun `tapping remove opens a confirmation dialog naming the track`() {
        val tracks = listOf(Track(id = "t1", title = "Neon Skyline", artist = "Cyber Runner"))
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Synthwave Nights", trackIds = listOf("t1"), trackCount = 1),
            tracks = tracks,
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_options_t1").performClick()
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
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_options_t1").performClick()
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
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_options_t1").performClick()
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
            PlaylistDetailScreen(playlistId = "p1", onBack = { backCalled = true }, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_options_t1").performClick()
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
            PlaylistDetailScreen(playlistId = "p1", onBack = { backCalled = true }, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_back_button").performClick()

        assertTrue(backCalled)
    }

    // --- 4B: tap-to-play from PlaylistDetail ---

    @Test
    fun `tapping the first track plays the full playlist starting at index 0`() {
        val tracks = listOf(
            Track(id = "a", title = "Track A", artist = "Artist"),
            Track(id = "b", title = "Track B", artist = "Artist"),
            Track(id = "c", title = "Track C", artist = "Artist"),
        )
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "ABC", trackIds = listOf("a", "b", "c"), trackCount = 3),
            tracks = tracks,
        )
        val mockAudio = mockk<AudioController>(relaxed = true)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockAudio)
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_a").performClick()

        verify(exactly = 1) { mockAudio.playQueue(tracks, 0) }
    }

    @Test
    fun `tapping a middle track starts playback there while keeping the full queue and order`() {
        val tracks = listOf(
            Track(id = "a", title = "Track A", artist = "Artist"),
            Track(id = "b", title = "Track B", artist = "Artist"),
            Track(id = "c", title = "Track C", artist = "Artist"),
        )
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "ABC", trackIds = listOf("a", "b", "c"), trackCount = 3),
            tracks = tracks,
        )
        val mockAudio = mockk<AudioController>(relaxed = true)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockAudio)
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_b").performClick()

        verify(exactly = 1) { mockAudio.playQueue(tracks, 1) }
    }

    @Test
    fun `tapping the last track starts playback at the last index`() {
        val tracks = listOf(
            Track(id = "a", title = "Track A", artist = "Artist"),
            Track(id = "b", title = "Track B", artist = "Artist"),
            Track(id = "c", title = "Track C", artist = "Artist"),
        )
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "ABC", trackIds = listOf("a", "b", "c"), trackCount = 3),
            tracks = tracks,
        )
        val mockAudio = mockk<AudioController>(relaxed = true)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockAudio)
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_list")
            .performScrollToNode(hasTestTag("playlist_detail_track_c"))
        composeTestRule.onNodeWithTag("playlist_detail_track_c").performClick()

        verify(exactly = 1) { mockAudio.playQueue(tracks, 2) }
    }

    @Test
    fun `tapping the only track in a single-song playlist plays it at index 0`() {
        val tracks = listOf(Track(id = "solo", title = "Solo Track", artist = "Artist"))
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Solo", trackIds = listOf("solo"), trackCount = 1),
            tracks = tracks,
        )
        val mockAudio = mockk<AudioController>(relaxed = true)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockAudio)
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_solo").performClick()

        verify(exactly = 1) { mockAudio.playQueue(tracks, 0) }
    }

    @Test
    fun `removing a track still works and does not trigger playback`() {
        val tracks = listOf(
            Track(id = "a", title = "Track A", artist = "Artist"),
            Track(id = "b", title = "Track B", artist = "Artist"),
        )
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "AB", trackIds = listOf("a", "b"), trackCount = 2),
            tracks = tracks,
        )
        val mockAudio = mockk<AudioController>(relaxed = true)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockAudio)
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_options_a").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_remove_track_a").performClick()
        composeTestRule.onNodeWithTag("remove_track_dialog_confirm_button").performClick()

        composeTestRule.onAllNodesWithTag("playlist_detail_track_a").assertCountEquals(0)
        verify(exactly = 0) { mockAudio.playQueue(any(), any()) }
    }

    // --- 4C: deleting a playlist ---

    @Test
    fun `delete option in the overflow menu opens the delete confirmation dialog`() {
        val viewModel = viewModelWithPlaylist(Playlist(id = "p1", name = "Synthwave Nights", trackCount = 0))

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_options_button").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_delete_menu_item").performClick()

        composeTestRule.onNodeWithTag("delete_playlist_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Eliminar playlist").assertIsDisplayed()
    }

    @Test
    fun `cancelling the delete confirmation does not delete and stays on PlaylistDetail`() {
        val repo = FakeDetailRepo(Playlist(id = "p1", name = "Synthwave Nights", trackCount = 0), emptyList(), emptyList())
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)
        var backCalled = false

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = { backCalled = true }, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_options_button").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_delete_menu_item").performClick()
        composeTestRule.onNodeWithTag("delete_playlist_dialog_cancel_button").performClick()

        composeTestRule.onAllNodesWithTag("delete_playlist_dialog").assertCountEquals(0)
        composeTestRule.onNodeWithTag("playlist_detail_screen").assertIsDisplayed()
        assertEquals(0, repo.deleteCallCount)
        assertTrue(!backCalled)
    }

    @Test
    fun `confirming delete calls the delete operation exactly once`() {
        val repo = FakeDetailRepo(Playlist(id = "p1", name = "Synthwave Nights", trackCount = 0), emptyList(), emptyList())
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_options_button").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_delete_menu_item").performClick()
        composeTestRule.onNodeWithTag("delete_playlist_dialog_confirm_button").performClick()

        assertEquals(1, repo.deleteCallCount)
    }

    @Test
    fun `double tap on confirm delete does not trigger two deletions`() {
        val repo = FakeDetailRepo(
            Playlist(id = "p1", name = "Synthwave Nights", trackCount = 0),
            emptyList(),
            emptyList(),
            deleteBlocksForever = true,
        )
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_options_button").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_delete_menu_item").performClick()
        composeTestRule.onNodeWithTag("delete_playlist_dialog_confirm_button").performClick()
        composeTestRule.onNodeWithTag("delete_playlist_dialog_confirm_button").performClick()

        assertEquals(1, repo.deleteCallCount)
    }

    @Test
    fun `successful deletion navigates back to the previous screen`() {
        val repo = FakeDetailRepo(Playlist(id = "p1", name = "Synthwave Nights", trackCount = 0), emptyList(), emptyList())
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)
        var backCalled = false

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = { backCalled = true }, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_options_button").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_delete_menu_item").performClick()
        composeTestRule.onNodeWithTag("delete_playlist_dialog_confirm_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) { backCalled }
        assertTrue(backCalled)
    }

    @Test
    fun `failed deletion keeps the screen, does not navigate back and allows retry`() {
        val repo = FakeDetailRepo(
            Playlist(id = "p1", name = "Synthwave Nights", trackCount = 0),
            emptyList(),
            emptyList(),
            deleteShouldFail = true,
        )
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)
        var backCalled = false

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = { backCalled = true }, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_options_button").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_delete_menu_item").performClick()
        composeTestRule.onNodeWithTag("delete_playlist_dialog_confirm_button").performClick()

        assertTrue(!backCalled)
        composeTestRule.onNodeWithTag("playlist_detail_screen").assertIsDisplayed()
        // The dialog stays open so the user can retry directly, same idiom as AddTracksDialog on failure.
        composeTestRule.onNodeWithTag("delete_playlist_dialog").assertIsDisplayed()
    }

    @Test
    fun `delete action coexists with tap-to-play, remove track and add tracks without interference`() {
        val tracks = listOf(Track(id = "a", title = "Track A", artist = "Artist"))
        val deviceTracks = listOf(Track(id = "d1", title = "Discovery", artist = "New Artist"))
        val repo = FakeDetailRepo(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a"), trackCount = 1),
            tracks,
            deviceTracks,
        )
        val localMedia = object : LocalMediaRepository() {
            override suspend fun loadTracks(): List<Track> = deviceTracks
        }
        val viewModel = PlaylistsViewModel(repo, localMedia, Dispatchers.Unconfined)
        val mockAudio = mockk<AudioController>(relaxed = true)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockAudio)
        }

        composeTestRule.onNodeWithTag("playlist_detail_options_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_detail_add_tracks_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_detail_track_options_a").assertIsDisplayed()

        composeTestRule.onNodeWithTag("playlist_detail_track_a").performClick()
        verify(exactly = 1) { mockAudio.playQueue(tracks, 0) }

        composeTestRule.onNodeWithTag("playlist_detail_add_tracks_button").performClick()
        composeTestRule.onNodeWithTag("add_tracks_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Discovery").assertIsDisplayed()
    }

    // --- 4D: renaming a playlist ---

    @Test
    fun `rename option in the overflow menu opens the rename dialog`() {
        val viewModel = viewModelWithPlaylist(Playlist(id = "p1", name = "Synthwave Nights", trackCount = 0))

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_options_button").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_rename_menu_item").performClick()

        composeTestRule.onNodeWithTag("rename_playlist_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Renombrar playlist").assertIsDisplayed()
    }

    @Test
    fun `rename dialog is pre-filled with the current playlist name`() {
        val viewModel = viewModelWithPlaylist(Playlist(id = "p1", name = "Synthwave Nights", trackCount = 0))

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_options_button").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_rename_menu_item").performClick()

        // The header behind the dialog also shows the same name, so assert against the
        // field specifically rather than onNodeWithText, which would match both.
        composeTestRule.onNodeWithTag("rename_playlist_dialog_field").assertTextContains("Synthwave Nights")
    }

    @Test
    fun `cancelling the rename dialog does not rename and stays on PlaylistDetail`() {
        val repo = FakeDetailRepo(Playlist(id = "p1", name = "Synthwave Nights", trackCount = 0), emptyList(), emptyList())
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_options_button").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_rename_menu_item").performClick()
        composeTestRule.onNodeWithTag("rename_playlist_dialog_cancel_button").performClick()

        composeTestRule.onAllNodesWithTag("rename_playlist_dialog").assertCountEquals(0)
        composeTestRule.onNodeWithTag("playlist_detail_screen").assertIsDisplayed()
        assertEquals(0, repo.renameCallCount)
        composeTestRule.onNodeWithText("Synthwave Nights").assertIsDisplayed()
    }

    @Test
    fun `saving a valid new name calls rename and updates the title without leaving the screen`() {
        val repo = FakeDetailRepo(Playlist(id = "p1", name = "Old Name", trackCount = 0), emptyList(), emptyList())
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)
        var backCalled = false

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = { backCalled = true }, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_options_button").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_rename_menu_item").performClick()
        composeTestRule.onNodeWithTag("rename_playlist_dialog_field").performTextReplacement("New Name")
        composeTestRule.onNodeWithTag("rename_playlist_dialog_confirm_button").performClick()

        assertEquals(1, repo.renameCallCount)
        assertEquals("New Name", repo.renameCalledWith)
        composeTestRule.onAllNodesWithTag("rename_playlist_dialog").assertCountEquals(0)
        composeTestRule.onNodeWithTag("playlist_detail_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("New Name").assertIsDisplayed()
        assertTrue(!backCalled)
    }

    @Test
    fun `saved name is trimmed before being persisted`() {
        val repo = FakeDetailRepo(Playlist(id = "p1", name = "Old Name", trackCount = 0), emptyList(), emptyList())
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_options_button").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_rename_menu_item").performClick()
        composeTestRule.onNodeWithTag("rename_playlist_dialog_field").performTextReplacement("  Padded Name  ")
        composeTestRule.onNodeWithTag("rename_playlist_dialog_confirm_button").performClick()

        assertEquals("Padded Name", repo.renameCalledWith)
    }

    @Test
    fun `blank name does not enable saving`() {
        val repo = FakeDetailRepo(Playlist(id = "p1", name = "Old Name", trackCount = 0), emptyList(), emptyList())
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_options_button").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_rename_menu_item").performClick()
        composeTestRule.onNodeWithTag("rename_playlist_dialog_field").performTextReplacement("   ")
        composeTestRule.onNodeWithTag("rename_playlist_dialog_confirm_button").performClick()

        assertEquals(0, repo.renameCallCount)
        composeTestRule.onNodeWithTag("rename_playlist_dialog").assertIsDisplayed()
    }

    @Test
    fun `double tap on save does not trigger two renames`() {
        val repo = FakeDetailRepo(
            Playlist(id = "p1", name = "Old Name", trackCount = 0),
            emptyList(),
            emptyList(),
            renameBlocksForever = true,
        )
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_options_button").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_rename_menu_item").performClick()
        composeTestRule.onNodeWithTag("rename_playlist_dialog_field").performTextReplacement("New Name")
        composeTestRule.onNodeWithTag("rename_playlist_dialog_confirm_button").performClick()
        composeTestRule.onNodeWithTag("rename_playlist_dialog_confirm_button").performClick()

        assertEquals(1, repo.renameCallCount)
    }

    @Test
    fun `failed rename keeps the dialog open and does not change the title`() {
        val repo = FakeDetailRepo(
            Playlist(id = "p1", name = "Old Name", trackCount = 0),
            emptyList(),
            emptyList(),
            renameShouldFail = true,
        )
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)
        var backCalled = false

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = { backCalled = true }, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_options_button").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_rename_menu_item").performClick()
        composeTestRule.onNodeWithTag("rename_playlist_dialog_field").performTextReplacement("New Name")
        composeTestRule.onNodeWithTag("rename_playlist_dialog_confirm_button").performClick()

        assertTrue(!backCalled)
        composeTestRule.onNodeWithTag("playlist_detail_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rename_playlist_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Old Name").assertIsDisplayed()
    }

    @Test
    fun `rename option coexists with tap-to-play, remove track, add tracks and delete option`() {
        val tracks = listOf(Track(id = "a", title = "Track A", artist = "Artist"))
        val deviceTracks = listOf(Track(id = "d1", title = "Discovery", artist = "New Artist"))
        val repo = FakeDetailRepo(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a"), trackCount = 1),
            tracks,
            deviceTracks,
        )
        val localMedia = object : LocalMediaRepository() {
            override suspend fun loadTracks(): List<Track> = deviceTracks
        }
        val viewModel = PlaylistsViewModel(repo, localMedia, Dispatchers.Unconfined)
        val mockAudio = mockk<AudioController>(relaxed = true)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockAudio)
        }

        // Renaming still works alongside everything else.
        composeTestRule.onNodeWithTag("playlist_detail_options_button").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_rename_menu_item").assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_detail_delete_menu_item").assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_detail_rename_menu_item").performClick()
        composeTestRule.onNodeWithTag("rename_playlist_dialog_field").performTextReplacement("Renamed Mix")
        composeTestRule.onNodeWithTag("rename_playlist_dialog_confirm_button").performClick()
        composeTestRule.onNodeWithText("Renamed Mix").assertIsDisplayed()

        // Tap-to-play (4B) still works.
        composeTestRule.onNodeWithTag("playlist_detail_track_a").performClick()
        verify(exactly = 1) { mockAudio.playQueue(tracks, 0) }

        // Remove track (3C) is still available.
        composeTestRule.onNodeWithTag("playlist_detail_track_options_a").assertIsDisplayed()

        // Add-tracks selector (3B) still opens.
        composeTestRule.onNodeWithTag("playlist_detail_add_tracks_button").performClick()
        composeTestRule.onNodeWithTag("add_tracks_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Discovery").assertIsDisplayed()
    }

    // --- 4E: trackCount vs resolvable tracks consistency ---

    @Test
    fun `header count and rendered rows reflect resolvable tracks, not the persisted trackCount`() {
        // trackIds/trackCount claim 3 tracks (a, b, c) but "b" can no longer be resolved
        // (e.g. its device file was deleted) - getTracksForPlaylist only returns a and c.
        val resolvableTracks = listOf(
            Track(id = "a", title = "Track A", artist = "Artist"),
            Track(id = "c", title = "Track C", artist = "Artist"),
        )
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a", "b", "c"), trackCount = 3),
            tracks = resolvableTracks,
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithText("2 canciones").assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_detail_track_a").assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_detail_track_c").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("playlist_detail_track_b").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("playlist_detail_empty_state").assertCountEquals(0)
    }

    @Test
    fun `every stored id unresolvable shows EmptyPlaylistState instead of a phantom count`() {
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a", "b"), trackCount = 2),
            tracks = emptyList(),
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_empty_state").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("playlist_detail_track_list").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("2 canciones").assertCountEquals(0)
    }

    @Test
    fun `removing a resolvable track still works when the playlist also has an unresolvable id`() {
        val repo = FakeDetailRepo(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a", "ghost"), trackCount = 2),
            listOf(Track(id = "a", title = "Track A", artist = "Artist")),
            emptyList(),
        )
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithText("1 canción").assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_detail_track_options_a").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_remove_track_a").performClick()
        composeTestRule.onNodeWithTag("remove_track_dialog_confirm_button").performClick()

        composeTestRule.onNodeWithTag("playlist_detail_empty_state").assertIsDisplayed()
    }

    @Test
    fun `tapping a track plays exactly the resolvable list the user sees, indexed within it`() {
        // trackIds has "b" between "a" and "c", but only a/c resolve - the queue sent to
        // AudioController must be the resolvable list [a, c], and tapping "c" must resolve
        // to index 1 within THAT list, not its position (2) in the persisted trackIds.
        val resolvableTracks = listOf(
            Track(id = "a", title = "Track A", artist = "Artist"),
            Track(id = "c", title = "Track C", artist = "Artist"),
        )
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a", "b", "c"), trackCount = 3),
            tracks = resolvableTracks,
        )
        val mockAudio = mockk<AudioController>(relaxed = true)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockAudio)
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_c").performClick()

        verify(exactly = 1) { mockAudio.playQueue(resolvableTracks, 1) }
    }

    // --- 4F: reordering tracks ---

    @Test
    fun `reorder controls appear per row via the track options menu`() {
        val tracks = listOf(Track(id = "a", title = "Track A", artist = "Artist"), Track(id = "b", title = "Track B", artist = "Artist"))
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a", "b"), trackCount = 2),
            tracks = tracks,
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_options_a").assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_detail_track_options_a").performClick()

        composeTestRule.onNodeWithTag("playlist_detail_move_up_a").assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_detail_move_down_a").assertIsDisplayed()
    }

    @Test
    fun `moving a middle track up calls moveTrack with the UP direction`() {
        val repo = FakeDetailRepo(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a", "b", "c"), trackCount = 3),
            listOf(Track(id = "a", title = "A"), Track(id = "b", title = "B"), Track(id = "c", title = "C")),
            emptyList(),
        )
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_options_b").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_move_up_b").performClick()

        assertEquals("b" to MoveDirection.UP, repo.moveTrackCalledWith)
    }

    @Test
    fun `moving a middle track down calls moveTrack with the DOWN direction`() {
        val repo = FakeDetailRepo(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a", "b", "c"), trackCount = 3),
            listOf(Track(id = "a", title = "A"), Track(id = "b", title = "B"), Track(id = "c", title = "C")),
            emptyList(),
        )
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_options_b").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_move_down_b").performClick()

        assertEquals("b" to MoveDirection.DOWN, repo.moveTrackCalledWith)
    }

    @Test
    fun `first track does not offer moving up`() {
        val tracks = listOf(Track(id = "a", title = "A"), Track(id = "b", title = "B"))
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a", "b"), trackCount = 2),
            tracks = tracks,
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_options_a").performClick()

        composeTestRule.onNodeWithTag("playlist_detail_move_up_a").assertIsNotEnabled()
    }

    @Test
    fun `last track does not offer moving down`() {
        val tracks = listOf(Track(id = "a", title = "A"), Track(id = "b", title = "B"))
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a", "b"), trackCount = 2),
            tracks = tracks,
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_options_b").performClick()

        composeTestRule.onNodeWithTag("playlist_detail_move_down_b").assertIsNotEnabled()
    }

    @Test
    fun `double tap on move up does not trigger two reorders`() {
        val repo = FakeDetailRepo(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a", "b"), trackCount = 2),
            listOf(Track(id = "a", title = "A"), Track(id = "b", title = "B")),
            emptyList(),
            moveBlocksForever = true,
        )
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_options_b").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_move_up_b").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_track_options_b").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_move_up_b").performClick()

        assertEquals(1, repo.moveTrackCallCount)
    }

    @Test
    fun `tap to play and remove still work alongside the reorder menu`() {
        val tracks = listOf(Track(id = "a", title = "Track A", artist = "Artist"), Track(id = "b", title = "Track B", artist = "Artist"))
        val repo = FakeDetailRepo(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a", "b"), trackCount = 2),
            tracks,
            emptyList(),
        )
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)
        val mockAudio = mockk<AudioController>(relaxed = true)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockAudio)
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_a").performClick()
        verify(exactly = 1) { mockAudio.playQueue(tracks, 0) }

        composeTestRule.onNodeWithTag("playlist_detail_track_options_b").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_remove_track_b").performClick()
        composeTestRule.onNodeWithTag("remove_track_dialog_confirm_button").performClick()

        composeTestRule.onAllNodesWithTag("playlist_detail_track_b").assertCountEquals(0)
    }

    @Test
    fun `after reordering, the visible list reflects the new order`() {
        val repo = FakeDetailRepo(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a", "b", "c"), trackCount = 3),
            listOf(Track(id = "a", title = "Track A"), Track(id = "b", title = "Track B"), Track(id = "c", title = "Track C")),
            emptyList(),
        )
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        // Move "c" up twice: [a, b, c] -> [a, c, b] -> [c, a, b].
        composeTestRule.onNodeWithTag("playlist_detail_track_list")
            .performScrollToNode(hasTestTag("playlist_detail_track_c"))
        composeTestRule.onNodeWithTag("playlist_detail_track_options_c").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_move_up_c").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_track_options_c").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_move_up_c").performClick()

        // "c" is now first, so it can no longer move up...
        composeTestRule.onNodeWithTag("playlist_detail_track_list")
            .performScrollToNode(hasTestTag("playlist_detail_track_c"))
        composeTestRule.onNodeWithTag("playlist_detail_track_options_c").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_move_up_c").assertIsNotEnabled()

        // ...while "a" (now second) can.
        composeTestRule.onNodeWithTag("playlist_detail_track_options_a").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_move_up_a").assertIsEnabled()
    }

    // 4E policy, obligatorio: an id the UI can't resolve/render must not be lost or
    // corrupted by reordering the tracks that ARE visible around it. The exact "stays at
    // its absolute index" guarantee is verified precisely at the Repository level
    // (PlaylistRepositoryTest.moveTrack_unresolvableIdInBetween_*); this is the
    // UI-integration sanity check that the screen still works end-to-end in that scenario.
    @Test
    fun `reordering visible tracks still works when the playlist also has an unresolvable id`() {
        val tracks = listOf(Track(id = "a", title = "Track A"), Track(id = "c", title = "Track C"))
        val repo = FakeDetailRepo(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a", "x", "c"), trackCount = 3),
            tracks,
            emptyList(),
        )
        val viewModel = PlaylistsViewModel(repo, LocalMediaRepository(), Dispatchers.Unconfined)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_track_options_c").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_move_up_c").performClick()

        assertEquals("c" to MoveDirection.UP, repo.moveTrackCalledWith)
        composeTestRule.onNodeWithTag("playlist_detail_track_a").assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_detail_track_c").assertIsDisplayed()
    }

    // --- UI5-PL-05: empty state and add action ---

    @Test
    fun `empty playlist hides density toggle and offers a primary add action`() {
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Vacia", trackCount = 0),
            deviceTracks = listOf(Track(id = "d1", title = "Discovery", artist = "New Artist")),
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_empty_state").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("playlist_detail_density_toggle").assertCountEquals(0)
        composeTestRule.onNodeWithTag("playlist_detail_empty_add_tracks")
            .assertExists()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithTag("add_tracks_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Discovery").assertIsDisplayed()
    }

    @Test
    fun `non empty playlist keeps density toggle and does not show empty action`() {
        val tracks = listOf(Track(id = "a", title = "Track A"))
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a"), trackCount = 1),
            tracks = tracks,
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_density_toggle").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("playlist_detail_empty_add_tracks").assertCountEquals(0)
    }

    // --- UI5-PL-01 / PL-02 / PL-04: unified hero and two-field editor ---

    @Test
    fun `unified hero keeps playlist visual tags with no banner`() {
        composeTestRule.setContent {
            PlaylistVisualHero(
                id = "p1",
                title = "Mix",
                trackCount = 1,
                coverIds = emptyList(),
                visual = PlaylistVisualState(),
            )
        }

        composeTestRule.onNodeWithTag("playlist_visual_hero_p1", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("playlist_visual_cover", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("playlist_detail_title", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithTag("playlist_detail_track_count", useUnmergedTree = true).assertExists()
        composeTestRule.onAllNodesWithTag("playlist_visual_banner", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `appearance editor exposes independent cover and banner fields and cancel saves nothing`() {
        val tracks = listOf(Track(id = "a", title = "Track A"))
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a"), trackCount = 1),
            tracks = tracks,
        )
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("michi_settings", 0)

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_options_button").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_edit_visual").performClick()

        composeTestRule.onNodeWithTag("playlist_visual_editor").assertIsDisplayed()
        composeTestRule.onNodeWithText("Editar apariencia de playlist").assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_visual_editor_cover_preview")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_visual_editor_banner_preview")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_visual_editor_cover_pick")
            .performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("playlist_visual_editor_banner_pick")
            .performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("playlist_visual_editor_cancel")
            .performScrollTo().performClick()

        composeTestRule.onAllNodesWithTag("playlist_visual_editor").assertCountEquals(0)
        assertFalse(prefs.contains(playlistVisualKey("p1", "cover_focal_x")))
        assertFalse(prefs.contains(playlistVisualKey("p1", "banner_scale")))
    }

    @Test
    fun `confirming appearance editor persists scale without replacing the private image`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("michi_settings", 0)
        val importer = PlaylistPrivateImageImporter(context)
        val bytes = PlaylistTestImages.png64x32
        val imported = importer.importFromStream(
            "p1", "banner", "image/png", bytes.size.toLong(), ByteArrayInputStream(bytes),
        ) as PlaylistImageImportResult.Success
        prefs.edit().putString(playlistVisualKey("p1", "banner"), imported.token).commit()
        val tracks = listOf(Track(id = "a", title = "Track A"))
        val viewModel = viewModelWithPlaylist(
            Playlist(id = "p1", name = "Mix", trackIds = listOf("a"), trackCount = 1),
            tracks = tracks,
        )

        composeTestRule.setContent {
            PlaylistDetailScreen(playlistId = "p1", onBack = {}, viewModel = viewModel, audioController = mockk(relaxed = true))
        }

        composeTestRule.onNodeWithTag("playlist_detail_options_button").performClick()
        composeTestRule.onNodeWithTag("playlist_detail_edit_visual").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeTestRule.onNodeWithTag("playlist_visual_editor_banner_adjust").assertIsEnabled()
                true
            }.getOrDefault(false)
        }
        composeTestRule.onNodeWithTag("playlist_visual_editor_banner_adjust")
            .performScrollTo().performClick()
        composeTestRule.onNodeWithTag("playlist_visual_editor_banner_scale")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        composeTestRule.onNodeWithTag("playlist_visual_editor_confirm")
            .performScrollTo()
            .performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            prefs.getFloat(playlistVisualKey("p1", "banner_scale"), 1f) >= 1.9f
        }
        assertEquals(2f, prefs.getFloat(playlistVisualKey("p1", "banner_scale"), 1f), 0.001f)
        assertEquals(imported.token, prefs.getString(playlistVisualKey("p1", "banner"), null))
        assertTrue(requireNotNull(importer.resolve(imported.token)).isFile)
    }
}
