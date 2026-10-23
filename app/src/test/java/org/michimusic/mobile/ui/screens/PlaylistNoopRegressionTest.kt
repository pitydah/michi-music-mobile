package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.screens.PlaylistItem
import org.michimusic.mobile.screens.PlaylistsViewModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class PlaylistNoopRegressionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun clickingPlaylistItem_navigatesToPlaylistDetailWithCorrectId() {
        val mockViewModel = mockk<PlaylistsViewModel>(relaxed = true)
        val p1 = PlaylistItem(id = "synthwave_80s", name = "Synthwave 80s", trackCount = 15)
        every { mockViewModel.playlists } returns MutableStateFlow(listOf(p1))
        every { mockViewModel.isLoading } returns MutableStateFlow(false)

        var navigatedId: String? = null

        composeTestRule.setContent {
            PlaylistScreen(
                onNavigateToPlaylist = { id -> navigatedId = id },
                viewModel = mockViewModel,
                audioController = mockk(relaxed = true),
            )
        }

        composeTestRule.onNodeWithTag("playlist_item_synthwave_80s").assertIsDisplayed().performClick()

        assertEquals("synthwave_80s", navigatedId)
    }

    @Test
    fun clickingPlayIconOnPlaylistItem_triggersActualPlayback() {
        val mockViewModel = mockk<PlaylistsViewModel>(relaxed = true)
        val mockAudioController = mockk<org.michimusic.player.AudioController>(relaxed = true)
        val p1 = PlaylistItem(id = "lofi_chill", name = "Lo-Fi Chill", trackCount = 20)
        every { mockViewModel.playlists } returns MutableStateFlow(listOf(p1))
        every { mockViewModel.isLoading } returns MutableStateFlow(false)

        composeTestRule.setContent {
            PlaylistScreen(
                onNavigateToPlaylist = { },
                viewModel = mockViewModel,
                audioController = mockAudioController,
            )
        }

        composeTestRule.onNodeWithTag("playlist_play_lofi_chill").assertIsDisplayed().performClick()

        io.mockk.verify { mockViewModel.playPlaylist("lofi_chill", mockAudioController) }
    }
}
