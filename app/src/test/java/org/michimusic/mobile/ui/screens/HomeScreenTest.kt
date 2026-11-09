package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.screens.MediaLibraryState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val historyDao = mockk<org.michimusic.data.cache.AppDao>(relaxed = true)

    @Test
    fun `shows loading indicator when loading`() {
        val mockViewModel = mockk<AlbumsViewModel>(relaxed = true)
        every { mockViewModel.mediaState } returns MutableStateFlow(MediaLibraryState.READY)
        every { mockViewModel.allTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.playlists } returns MutableStateFlow(emptyList())
        every { mockViewModel.isLoading } returns MutableStateFlow(true)
        every { mockViewModel.error } returns MutableStateFlow(null)
        every { mockViewModel.recentTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.topTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.favoriteTracks } returns MutableStateFlow(emptyList())
        
        val mockSession = mockk<org.michimusic.mobile.playback.PlaybackSessionManager>(relaxed = true)
        every { mockSession.sessionState } returns MutableStateFlow(org.michimusic.mobile.playback.PlaybackSessionState())
        
        val mockAudio = mockk<org.michimusic.player.AudioController>(relaxed = true)
        every { mockAudio.state } returns MutableStateFlow(org.michimusic.player.PlayerState())

        composeTestRule.setContent {
            HomeScreen(viewModel = mockViewModel, sessionManager = mockSession, audioController = mockAudio, historyDao = historyDao)
        }

        // TopBar must stay on screen during loading instead of being replaced by the spinner.
        composeTestRule.onNodeWithTag("home_settings_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("loading_indicator").assertIsDisplayed()
    }

    @Test
    fun `topbar remains mounted when loading finishes and content replaces the spinner`() {
        val isLoadingFlow = MutableStateFlow(true)

        val mockViewModel = mockk<AlbumsViewModel>(relaxed = true)
        every { mockViewModel.mediaState } returns MutableStateFlow(MediaLibraryState.READY)
        every { mockViewModel.allTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.playlists } returns MutableStateFlow(emptyList())
        every { mockViewModel.isLoading } returns isLoadingFlow
        every { mockViewModel.error } returns MutableStateFlow(null)
        every { mockViewModel.recentTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.topTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.favoriteTracks } returns MutableStateFlow(emptyList())

        val mockSession = mockk<org.michimusic.mobile.playback.PlaybackSessionManager>(relaxed = true)
        every { mockSession.sessionState } returns MutableStateFlow(org.michimusic.mobile.playback.PlaybackSessionState())

        val mockAudio = mockk<org.michimusic.player.AudioController>(relaxed = true)
        every { mockAudio.state } returns MutableStateFlow(org.michimusic.player.PlayerState())

        composeTestRule.setContent {
            HomeScreen(viewModel = mockViewModel, sessionManager = mockSession, audioController = mockAudio, historyDao = historyDao)
        }

        // Loading state: TopBar visible, spinner visible, scrollable content not yet shown.
        composeTestRule.onNodeWithTag("home_settings_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("loading_indicator").assertIsDisplayed()

        // Simulate loadMedia() completing.
        isLoadingFlow.value = false
        composeTestRule.waitForIdle()

        // Loaded state: TopBar still visible (never unmounted), spinner gone, real content shown.
        composeTestRule.onNodeWithTag("home_settings_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_scroll_list").assertIsDisplayed()
    }

    @Test
    fun `shows error message when error exists`() {
        val mockViewModel = mockk<AlbumsViewModel>(relaxed = true)
        every { mockViewModel.mediaState } returns MutableStateFlow(MediaLibraryState.READY)
        every { mockViewModel.allTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.playlists } returns MutableStateFlow(emptyList())
        every { mockViewModel.isLoading } returns MutableStateFlow(false)
        every { mockViewModel.error } returns MutableStateFlow("Error de carga")
        every { mockViewModel.recentTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.topTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.favoriteTracks } returns MutableStateFlow(emptyList())

        val mockSession = mockk<org.michimusic.mobile.playback.PlaybackSessionManager>(relaxed = true)
        every { mockSession.sessionState } returns MutableStateFlow(org.michimusic.mobile.playback.PlaybackSessionState())
        
        val mockAudio = mockk<org.michimusic.player.AudioController>(relaxed = true)
        every { mockAudio.state } returns MutableStateFlow(org.michimusic.player.PlayerState())

        composeTestRule.setContent {
            HomeScreen(viewModel = mockViewModel, sessionManager = mockSession, audioController = mockAudio, historyDao = historyDao)
        }

        composeTestRule.onNodeWithTag("error_message").assertIsDisplayed()
        composeTestRule.onNodeWithText("Error de carga").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_retry_loading_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_retry_loading_button").performClick()
        composeTestRule.waitForIdle()
        // No second launch while the ViewModel has not yet emitted a loading state.
        composeTestRule.onNodeWithTag("home_retry_loading_button").assertIsNotEnabled()
        io.mockk.verify(exactly = 2) { mockViewModel.loadMedia() }
    }

    @Test
    fun `retry re-enables after observable loading and a new failure`() {
        val loading = MutableStateFlow(false)
        val error = MutableStateFlow<String?>("Error inicial")
        val mockViewModel = mockk<AlbumsViewModel>(relaxed = true)
        every { mockViewModel.mediaState } returns MutableStateFlow(MediaLibraryState.READY)
        every { mockViewModel.allTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.playlists } returns MutableStateFlow(emptyList())
        every { mockViewModel.isLoading } returns loading
        every { mockViewModel.error } returns error
        every { mockViewModel.recentTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.topTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.favoriteTracks } returns MutableStateFlow(emptyList())
        val mockSession = mockk<org.michimusic.mobile.playback.PlaybackSessionManager>(relaxed = true)
        every { mockSession.sessionState } returns MutableStateFlow(org.michimusic.mobile.playback.PlaybackSessionState())
        val mockAudio = mockk<org.michimusic.player.AudioController>(relaxed = true)
        every { mockAudio.state } returns MutableStateFlow(org.michimusic.player.PlayerState())

        composeTestRule.setContent {
            HomeScreen(viewModel = mockViewModel, sessionManager = mockSession, audioController = mockAudio, historyDao = historyDao)
        }
        composeTestRule.onNodeWithTag("home_retry_loading_button").performClick()
        composeTestRule.onNodeWithTag("home_retry_loading_button").assertIsNotEnabled()
        loading.value = true
        error.value = null
        composeTestRule.waitForIdle()
        loading.value = false
        error.value = "Nuevo fallo"
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_retry_loading_button").assertIsEnabled()
    }

    @Test
    fun `shows recent and top tracks`() {
        val t1 = Track(id = "t1", title = "Recent Song", artist = "Artist 1", duration = 100, coverId = "")
        val t2 = Track(id = "t2", title = "Top Song", artist = "Artist 2", duration = 100, coverId = "")
        
        val mockViewModel = mockk<AlbumsViewModel>(relaxed = true)
        every { mockViewModel.mediaState } returns MutableStateFlow(MediaLibraryState.READY)
        every { mockViewModel.allTracks } returns MutableStateFlow(listOf(t2))
        every { mockViewModel.playlists } returns MutableStateFlow(emptyList())
        every { mockViewModel.isLoading } returns MutableStateFlow(false)
        every { mockViewModel.error } returns MutableStateFlow(null)
        every { mockViewModel.recentTracks } returns MutableStateFlow(listOf(t1))
        every { mockViewModel.topTracks } returns MutableStateFlow(listOf(t2))
        every { mockViewModel.favoriteTracks } returns MutableStateFlow(listOf(t2))

        val mockSession = mockk<org.michimusic.mobile.playback.PlaybackSessionManager>(relaxed = true)
        every { mockSession.sessionState } returns MutableStateFlow(org.michimusic.mobile.playback.PlaybackSessionState())
        
        val mockAudio = mockk<org.michimusic.player.AudioController>(relaxed = true)
        every { mockAudio.state } returns MutableStateFlow(org.michimusic.player.PlayerState())

        composeTestRule.setContent {
            HomeScreen(viewModel = mockViewModel, sessionManager = mockSession, audioController = mockAudio, historyDao = historyDao)
        }

        // Carousel of recent tracks
        composeTestRule.onNodeWithTag("home_scroll_list")
            .performScrollToNode(hasText("Recientemente Escuchadas"))
        composeTestRule.onNodeWithText("Recientemente Escuchadas").assertIsDisplayed()
        composeTestRule.onNodeWithText("Recent Song").assertExists()
        
        // Playlist tiles
        composeTestRule.onNodeWithTag("home_scroll_list").performScrollToNode(hasText("Favoritos"))
        composeTestRule.onNodeWithText("Favoritos").assertIsDisplayed()
        // The count goes through plurals now, so a single-track playlist says "1 pista".
        composeTestRule.onNodeWithText("1 pista").assertIsDisplayed()
        
        // Top tracks (shown in Quick Picks if we populate allTracks)
        // composeTestRule.onNodeWithText("Pistas Sugeridas").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `shows user created playlists with name and track count`() {
        val mockViewModel = mockk<AlbumsViewModel>(relaxed = true)
        every { mockViewModel.mediaState } returns MutableStateFlow(MediaLibraryState.READY)
        every { mockViewModel.allTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.playlists } returns MutableStateFlow(
            listOf(Playlist(id = "p1", name = "Synthwave Nights", trackCount = 3))
        )
        every { mockViewModel.isLoading } returns MutableStateFlow(false)
        every { mockViewModel.error } returns MutableStateFlow(null)
        every { mockViewModel.recentTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.topTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.favoriteTracks } returns MutableStateFlow(emptyList())

        val mockSession = mockk<org.michimusic.mobile.playback.PlaybackSessionManager>(relaxed = true)
        every { mockSession.sessionState } returns MutableStateFlow(org.michimusic.mobile.playback.PlaybackSessionState())

        val mockAudio = mockk<org.michimusic.player.AudioController>(relaxed = true)
        every { mockAudio.state } returns MutableStateFlow(org.michimusic.player.PlayerState())

        composeTestRule.setContent {
            HomeScreen(viewModel = mockViewModel, sessionManager = mockSession, audioController = mockAudio, historyDao = historyDao)
        }

        composeTestRule.onNodeWithText("Synthwave Nights").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 canciones").assertIsDisplayed()
    }

    @Test
    fun `long playlist names wrap instead of being clipped to the first word`() {
        val mockViewModel = mockk<AlbumsViewModel>(relaxed = true)
        every { mockViewModel.mediaState } returns MutableStateFlow(MediaLibraryState.READY)
        every { mockViewModel.allTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.playlists } returns MutableStateFlow(
            listOf(
                Playlist(id = "p1", name = "Prueba Biblioteca", trackCount = 0),
                Playlist(id = "p2", name = "Prueba Inicio", trackCount = 0),
            )
        )
        every { mockViewModel.isLoading } returns MutableStateFlow(false)
        every { mockViewModel.error } returns MutableStateFlow(null)
        every { mockViewModel.recentTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.topTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.favoriteTracks } returns MutableStateFlow(emptyList())

        val mockSession = mockk<org.michimusic.mobile.playback.PlaybackSessionManager>(relaxed = true)
        every { mockSession.sessionState } returns MutableStateFlow(org.michimusic.mobile.playback.PlaybackSessionState())

        val mockAudio = mockk<org.michimusic.player.AudioController>(relaxed = true)
        every { mockAudio.state } returns MutableStateFlow(org.michimusic.player.PlayerState())

        composeTestRule.setContent {
            HomeScreen(viewModel = mockViewModel, sessionManager = mockSession, audioController = mockAudio, historyDao = historyDao)
        }

        // Both full names must exist as distinct, fully-matched nodes even though
        // they share the same first word - if the title text were still being
        // clipped to a single word ("Prueba"), these two playlists would be
        // visually indistinguishable and only one shared "Prueba" node would exist.
        composeTestRule.onNodeWithText("Prueba Biblioteca").assertIsDisplayed()
        composeTestRule.onNodeWithText("Prueba Inicio").assertIsDisplayed()
    }

    @Test
    fun `clicking a real playlist tile invokes onNavigateToPlaylist with its id, not the top-recent callback`() {
        val mockViewModel = mockk<AlbumsViewModel>(relaxed = true)
        every { mockViewModel.mediaState } returns MutableStateFlow(MediaLibraryState.READY)
        every { mockViewModel.allTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.playlists } returns MutableStateFlow(
            listOf(Playlist(id = "real-playlist-42", name = "Synthwave Nights", trackCount = 3))
        )
        every { mockViewModel.isLoading } returns MutableStateFlow(false)
        every { mockViewModel.error } returns MutableStateFlow(null)
        every { mockViewModel.recentTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.topTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.favoriteTracks } returns MutableStateFlow(emptyList())

        val mockSession = mockk<org.michimusic.mobile.playback.PlaybackSessionManager>(relaxed = true)
        every { mockSession.sessionState } returns MutableStateFlow(org.michimusic.mobile.playback.PlaybackSessionState())

        val mockAudio = mockk<org.michimusic.player.AudioController>(relaxed = true)
        every { mockAudio.state } returns MutableStateFlow(org.michimusic.player.PlayerState())

        var navigatedPlaylistId: String? = null

        composeTestRule.setContent {
            HomeScreen(
                viewModel = mockViewModel,
                sessionManager = mockSession,
                audioController = mockAudio,
                historyDao = historyDao,
                onNavigateToPlaylist = { id -> navigatedPlaylistId = id },
            )
        }

        composeTestRule.onNodeWithTag("playlist_tile_real-playlist-42").performClick()

        assertEquals("real-playlist-42", navigatedPlaylistId)
        // Favoritos/Recientes still route through the separate type-based callback, untouched.
        io.mockk.verify(exactly = 0) { mockAudio.playQueue(any(), any()) }
    }

    @Test
    fun `home header grows instead of covering the hero at two hundred percent font`() {
        val mockViewModel = mockk<AlbumsViewModel>(relaxed = true)
        every { mockViewModel.mediaState } returns MutableStateFlow(MediaLibraryState.READY)
        every { mockViewModel.allTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.playlists } returns MutableStateFlow(emptyList())
        every { mockViewModel.isLoading } returns MutableStateFlow(false)
        every { mockViewModel.error } returns MutableStateFlow(null)
        every { mockViewModel.recentTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.topTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.favoriteTracks } returns MutableStateFlow(emptyList())

        val mockSession = mockk<org.michimusic.mobile.playback.PlaybackSessionManager>(relaxed = true)
        every { mockSession.sessionState } returns MutableStateFlow(org.michimusic.mobile.playback.PlaybackSessionState())

        val mockAudio = mockk<org.michimusic.player.AudioController>(relaxed = true)
        every { mockAudio.state } returns MutableStateFlow(org.michimusic.player.PlayerState())

        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                HomeScreen(viewModel = mockViewModel, sessionManager = mockSession, audioController = mockAudio, historyDao = historyDao)
            }
        }

        composeTestRule.onNodeWithTag("home_top_bar").assertHeightIsAtLeast(64.dp)
    }
}
