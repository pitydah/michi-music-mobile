package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.mobile.screens.AlbumsViewModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class HomeRecentIntegrityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun whenNoRecentHistory_doesNotDisplayRecientementeEscuchadasHeader() {
        val t1 = Track(id = "t1", title = "Track in Library", artist = "Artist", duration = 120)
        val mockViewModel = mockk<AlbumsViewModel>(relaxed = true)
        every { mockViewModel.allTracks } returns MutableStateFlow(listOf(t1))
        every { mockViewModel.recentTracks } returns MutableStateFlow(emptyList()) // No history
        every { mockViewModel.topTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.favoriteTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.playlists } returns MutableStateFlow(emptyList())
        every { mockViewModel.isLoading } returns MutableStateFlow(false)
        every { mockViewModel.error } returns MutableStateFlow(null)

        val mockSession = mockk<org.michimusic.mobile.playback.PlaybackSessionManager>(relaxed = true)
        every { mockSession.sessionState } returns MutableStateFlow(org.michimusic.mobile.playback.PlaybackSessionState())
        val mockAudio = mockk<org.michimusic.player.AudioController>(relaxed = true)
        every { mockAudio.state } returns MutableStateFlow(org.michimusic.player.PlayerState())

        composeTestRule.setContent {
            HomeScreen(viewModel = mockViewModel, sessionManager = mockSession, audioController = mockAudio)
        }

        // Must display honest title "De tu biblioteca" rather than falsely claiming "Recientemente Escuchadas"
        composeTestRule.onNodeWithText("De tu biblioteca").assertIsDisplayed()
        composeTestRule.onNodeWithText("Recientemente Escuchadas").assertDoesNotExist()
    }

    @Test
    fun whenRecentHistoryExists_displaysRecientementeEscuchadasHeader() {
        val t1 = Track(id = "t1", title = "Actually Played Track", artist = "Artist", duration = 120)
        val mockViewModel = mockk<AlbumsViewModel>(relaxed = true)
        every { mockViewModel.allTracks } returns MutableStateFlow(listOf(t1))
        every { mockViewModel.recentTracks } returns MutableStateFlow(listOf(t1)) // Real history
        every { mockViewModel.topTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.favoriteTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.playlists } returns MutableStateFlow(emptyList())
        every { mockViewModel.isLoading } returns MutableStateFlow(false)
        every { mockViewModel.error } returns MutableStateFlow(null)

        val mockSession = mockk<org.michimusic.mobile.playback.PlaybackSessionManager>(relaxed = true)
        every { mockSession.sessionState } returns MutableStateFlow(org.michimusic.mobile.playback.PlaybackSessionState())
        val mockAudio = mockk<org.michimusic.player.AudioController>(relaxed = true)
        every { mockAudio.state } returns MutableStateFlow(org.michimusic.player.PlayerState())

        composeTestRule.setContent {
            HomeScreen(viewModel = mockViewModel, sessionManager = mockSession, audioController = mockAudio)
        }

        composeTestRule.onNodeWithText("Recientemente Escuchadas").assertIsDisplayed()
        composeTestRule.onNodeWithText("Actually Played Track").assertIsDisplayed()
    }
}
