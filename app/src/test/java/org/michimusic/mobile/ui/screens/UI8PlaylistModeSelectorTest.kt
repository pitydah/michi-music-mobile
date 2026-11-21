package org.michimusic.mobile.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.cache.AppDao
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

    private val historyDao = mockk<AppDao>(relaxed = true)

    // One composition per test: the configuration is state-driven because the rule
    // allows a single setContent call.
    private val widthState = mutableStateOf(393)
    private val scaleState = mutableStateOf(1.0f)

    /**
     * UI8-KILLCRITIC: one icon, like Artists.
     *
     * The segmented capsule was rejected twice: at 133 dp and again at 118 dp it still
     * looked like a second control fighting the title. These tests pin the replacement
     * contract — a single toggle that announces the mode it will switch to.
     */
    private fun render() {
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
        val base = Density(1f, scaleState.value)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides base) {
                MichiTheme {
                    Box(Modifier.width(widthState.value.dp).height(900.dp)) {
                        PlaylistScreen(
                            onNavigateToPlaylist = {},
                            viewModel = viewModel,
                            audioController = mockk(relaxed = true),
                            historyDao = historyDao,
                        )
                    }
                }
            }
        }
    }

    private fun toggle() = compose.onNodeWithTag("playlist_view_mode_toggle")

    private fun classicDescription() = "Vista lista de playlists. Cambiar a tarjetas"

    private fun cardsDescription() = "Vista tarjetas de playlists. Cambiar a lista"

    @Test
    fun thereIsExactlyOneViewModeButtonAndItAnnouncesTheNextMode() {
        render()

        compose.onAllNodesWithTag("playlist_view_mode_toggle").assertCountEquals(1)
        toggle().assertContentDescriptionEquals(classicDescription())

        toggle().performClick()
        compose.waitForIdle()

        toggle().assertContentDescriptionEquals(cardsDescription())
    }

    @Test
    fun eachClickSwitchesExactlyOnce() {
        render()

        repeat(4) { round ->
            val expectedCards = round % 2 == 0
            toggle().performClick()
            compose.waitForIdle()
            assertEquals(
                "round $round must land on cards=$expectedCards",
                expectedCards,
                compose.onAllNodesWithTag("playlist_catalog_cards").fetchSemanticsNodes().isNotEmpty(),
            )
            assertEquals(
                "round $round must hide the other layout",
                !expectedCards,
                compose.onAllNodesWithTag("playlist_catalog_list").fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    @Test
    fun tenSwitchesKeepTheStateAndTheLayoutInStep() {
        render()

        repeat(10) { index ->
            toggle().performClick()
            compose.waitForIdle()
            // The screen opens in Lista, so an odd number of presses lands on Tarjetas.
            val cards = (index + 1) % 2 == 1
            assertEquals(
                "switch ${index + 1} must show cards=$cards",
                cards,
                compose.onAllNodesWithTag("playlist_catalog_cards").fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    @Test
    fun theToggleIsA48dpTargetLeftOfCreateAndNeverMoves() {
        render()

        val toggle = toggle().fetchSemanticsNode().boundsInRoot
        val create = compose.onNodeWithContentDescription("Nueva Playlist")
            .fetchSemanticsNode().boundsInRoot
        val targetDp = with(compose.density) { toggle.height.toDp().value }
        val gapDp = with(compose.density) { (create.left - toggle.right).toDp().value }
        println("PLAYLIST-TOGGLE target=${targetDp}dp gap=${gapDp}dp width=${with(compose.density) { toggle.width.toDp().value }}dp")

        assertTrue("the toggle target is $targetDp dp tall; 48 dp is required", targetDp >= 48f - 0.5f)
        assertTrue("the toggle must sit left of create, gone $gapDp dp", toggle.right <= create.left + 0.5f)
        assertTrue("the gap to create is $gapDp dp; 8-10 dp expected", gapDp in 7.5f..10.5f)

        val baseline = listOf(toggle.left, toggle.top, toggle.width, toggle.height, create.left)
        repeat(10) {
            toggle().performClick()
            compose.waitForIdle()
            val moved = toggle().fetchSemanticsNode().boundsInRoot
            val plus = compose.onNodeWithContentDescription("Nueva Playlist")
                .fetchSemanticsNode().boundsInRoot
            assertEquals(
                "the header must not move when the mode changes",
                baseline,
                listOf(moved.left, moved.top, moved.width, moved.height, plus.left),
            )
        }
    }

    @Test
    fun theTitlesSurviveEveryFontScale() {
        render()

        for (fontScale in listOf(1.0f, 1.5f, 2.0f)) {
            scaleState.value = fontScale
            compose.waitForIdle()
            compose.onNodeWithText("Tus Playlists").assertIsDisplayed()
            toggle().assertIsDisplayed()
            compose.onNodeWithContentDescription("Nueva Playlist").assertIsDisplayed()
        }
    }

    @Test
    fun theToggleWorksAcrossTheSupportedWidths() {
        render()

        for (widthDp in listOf(320, 360, 393, 430, 600)) {
            widthState.value = widthDp
            compose.waitForIdle()
            toggle().assertIsDisplayed()
            if (compose.onAllNodesWithTag("playlist_catalog_list").fetchSemanticsNodes().isEmpty()) {
                toggle().performClick()
                compose.waitForIdle()
            }
            toggle().performClick()
            compose.waitForIdle()
            assertTrue(
                "at ${widthDp}dp the cards layout must appear",
                compose.onAllNodesWithTag("playlist_catalog_cards").fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }
}
