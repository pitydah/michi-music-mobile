package org.michimusic.mobile.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.screens.PlaylistItem
import org.michimusic.mobile.screens.PlaylistsViewModel
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI8-KILLCRITIC: the catalogue mode is one choice, not two filters.
 *
 * The two chips that used to sit side by side read as two unrelated toggles, and
 * a user could believe both were active. These tests render the real
 * [PlaylistScreen] and assert the semantics a screen reader sees: exactly one of
 * the two options is selected at any moment, each option is labelled with a word
 * that says what it shows, and picking one hides the other layout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class UI8PlaylistModeSelectorTest {

    @get:Rule
    val compose = createComposeRule()

    private val historyDao = mockk<org.michimusic.data.cache.AppDao>(relaxed = true)

    private fun render(widthDp: Int = 393, fontScale: Float = 1.0f) {
        val viewModel = mockk<PlaylistsViewModel>(relaxed = true)
        every { viewModel.playlists } returns MutableStateFlow(
            listOf(
                PlaylistItem(id = "pl-a", name = "Noche de vinilo", trackCount = 1),
                PlaylistItem(id = "pl-b", name = "Playlist Alfa", trackCount = 2),
            ),
        )
        every { viewModel.isLoading } returns MutableStateFlow(false)
        every { viewModel.isCreatingPlaylist } returns MutableStateFlow(false)
        every { viewModel.createPlaylistError } returns MutableStateFlow(null)
        val audio = mockk<org.michimusic.player.AudioController>(relaxed = true)
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale),
            ) {
                MichiTheme {
                    Box(Modifier.width(widthDp.dp).height(900.dp)) {
                        PlaylistScreen(
                            onNavigateToPlaylist = {},
                            viewModel = viewModel,
                            audioController = audio,
                            historyDao = historyDao,
                        )
                    }
                }
            }
        }
    }

    private fun classic() = compose.onNodeWithTag("playlist_mode_classic")

    private fun cards() = compose.onNodeWithTag("playlist_mode_cards")

    @Test
    fun theModeIsASingleMutuallyExclusiveChoice() {
        render()

        classic().assertIsSelected()
        cards().assertIsNotSelected()

        cards().performClick()
        compose.waitForIdle()

        classic().assertIsNotSelected()
        cards().assertIsSelected()

        classic().performClick()
        compose.waitForIdle()

        classic().assertIsSelected()
        cards().assertIsNotSelected()
    }

    @Test
    fun pickingAModeShowsThatLayoutAndHidesTheOther() {
        render()

        compose.onNodeWithTag("playlist_catalog_list").assertIsDisplayed()

        cards().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("playlist_catalog_cards").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("playlist_catalog_list").fetchSemanticsNodes().size)

        classic().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("playlist_catalog_list").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("playlist_catalog_cards").fetchSemanticsNodes().size)
    }

    @Test
    fun theOptionsAreNamedForWhatTheyShow() {
        render()

        compose.onNodeWithText("Lista").assertIsDisplayed()
        compose.onNodeWithText("Tarjetas").assertIsDisplayed()
    }

    @Test
    fun theStackedSelectorKeepsBothLabelsAtTheSmallestWidthAndLargestScale() {
        render(widthDp = 320, fontScale = 2.0f)

        // The rail stacks instead of squeezing the words: both labels are still
        // whole at 320 dp and 200%, and the choice is still mutually exclusive.
        compose.onNodeWithText("Lista").assertIsDisplayed()
        compose.onNodeWithText("Tarjetas").assertIsDisplayed()
        classic().assertIsSelected()

        cards().performClick()
        compose.waitForIdle()

        cards().assertIsSelected()
        classic().assertIsNotSelected()
    }
}
