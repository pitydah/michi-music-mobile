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
    fun theSelectorSitsInTheHeaderLeftOfTheCreateButton() {
        render()

        val selector = compose.onNodeWithTag("playlist_catalog_mode_picker")
            .fetchSemanticsNode().boundsInRoot
        val create = compose.onNodeWithContentDescription("Nueva Playlist")
            .fetchSemanticsNode().boundsInRoot
        val summary = compose.onNodeWithTag("playlist_catalog_summary")
            .fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().size.width
        val selectorWidthDp = with(compose.density) { selector.width.toDp().value }
        val rootWidthDp = with(compose.density) { root.toDp().value }

        // Selector and "+" are one group: same band, no overlap, selector first.
        assertTrue(
            "the selector is not left of the create button " +
                "(selector.right=${selector.right} create.left=${create.left})",
            selector.right <= create.left + 0.5f,
        )
        assertTrue(
            "the selector and the create button overlap vertically or horizontally",
            selector.left < create.right && selector.top < create.bottom && create.top < selector.bottom,
        )
        assertTrue(
            "the create button leaves the viewport (right=${create.right} viewport=$root)",
            create.right <= root + 0.5f,
        )
        assertTrue(
            "the selector is not compact: ${selectorWidthDp}dp of ${rootWidthDp}dp",
            selectorWidthDp < rootWidthDp * 0.55f,
        )
        // UI8-KILLCRITIC: the header control has a size target, not only a ratio. A
        // secondary action that is 133 dp wide and 48 dp tall competes with the title.
        val visibleHeightDp = with(compose.density) { selector.height.toDp().value }
        val classicTargetDp = with(compose.density) {
            compose.onNodeWithTag("playlist_mode_classic").fetchSemanticsNode().boundsInRoot.height
                .toDp().value
        }
        val cardsTargetDp = with(compose.density) {
            compose.onNodeWithTag("playlist_mode_cards").fetchSemanticsNode().boundsInRoot.height
                .toDp().value
        }
        // Robolectric collapses intrinsic text widths, so it cannot certify the absolute
        // width (it measured 50 dp for a control the device draws at ~121 dp). The window
        // is asserted in UI8PlaylistScreenFullCaptureTest; here only the ceiling matters.
        assertTrue(
            "the selector is $selectorWidthDp dp wide at 1.0x; it must stay under 124 dp",
            selectorWidthDp < 124f,
        )
        // UI8-KILLCRITIC: the control draws at its touch-target height. A smaller pill
        // was measured first (37.71 dp) and had to be abandoned: Compose clamps a child to
        // its parent, so a 34 dp pill inside an overflowing 48 dp host kept a 38 dp hit
        // area and the tap above the pill landed on nothing.
        assertTrue(
            "the visible selector is $visibleHeightDp dp tall; the target is 48 dp",
            visibleHeightDp >= 47f,
        )
        // The micro pill reports its drawn height, not its hit area: the 48 dp target
        // overflows the pill and is proven on device by tapping above the visible edge
        // (UI8PlaylistScreenFullCaptureTest). Asserting 48 here would only measure the
        // pill, and asserting the pill proves nothing about the target.
        assertTrue(
            "the segment pills are ${classicTargetDp}/${cardsTargetDp} dp tall",
            classicTargetDp >= 34f && cardsTargetDp >= 34f,
        )
        // It belongs to the header: above the summary/sort row, not to its own band under it.
        assertTrue(
            "the selector is not in the header band (selector.top=${selector.top} summary.top=${summary.top})",
            selector.top < summary.top,
        )
        println(
            "SELECTOR-GEOMETRY width=393dp fs=1.0 selector=${selectorWidthDp}dp " +
                "gap=${with(compose.density) { (create.left - selector.right).toDp().value }}dp",
        )
    }

    @Test
    fun switchingModeTenTimesNeverMovesTheSelectorOrTheCreateButton() {
        render()
        val classic = compose.onNodeWithTag("playlist_mode_classic")
        val cards = compose.onNodeWithTag("playlist_mode_cards")

        fun snapshot(): List<Float> {
            val selector = compose.onNodeWithTag("playlist_catalog_mode_picker")
                .fetchSemanticsNode().boundsInRoot
            val create = compose.onNodeWithContentDescription("Nueva Playlist")
                .fetchSemanticsNode().boundsInRoot
            return listOf(selector.left, selector.top, selector.width, selector.height,
                create.left, create.top, create.width)
        }

        // UI8-KILLCRITIC: the selected weight used to widen "Tarjetas" by 2-3 dp and
        // shift the +, so the header jumped every time the user switched modes.
        val baseline = snapshot()
        repeat(5) {
            cards.performClick()
            compose.waitForIdle()
            assertEquals("the header moved when switching to Tarjetas (round $it)", baseline, snapshot())
            classic.performClick()
            compose.waitForIdle()
            assertEquals("the header moved when switching to Lista (round $it)", baseline, snapshot())
        }
    }

    @Test
    fun theSelectorStaysWithTheCreateButtonAtTheSmallestWidthAndLargestScale() {
        render(widthDp = 320, fontScale = 2.0f)

        val selector = compose.onNodeWithTag("playlist_catalog_mode_picker")
            .fetchSemanticsNode().boundsInRoot
        val create = compose.onNodeWithContentDescription("Nueva Playlist")
            .fetchSemanticsNode().boundsInRoot
        val summary = compose.onNodeWithTag("playlist_catalog_summary")
            .fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().size.width

        assertTrue(
            "the selector left the viewport at 320 dp (left=${selector.left})",
            selector.left >= -0.5f,
        )
        assertTrue(
            "the create button left the viewport at 320 dp (right=${create.right} viewport=$root)",
            create.right <= root + 0.5f,
        )
        assertTrue(
            "the pair no longer fits its group: selector.right=${selector.right} create.left=${create.left}",
            selector.right <= create.left + 0.5f,
        )
        assertTrue(
            "the selector left the header band at 320 dp/2.0 " +
                "(selector.top=${selector.top} summary.top=${summary.top})",
            selector.top < summary.top,
        )
        compose.onNodeWithText("Lista").assertIsDisplayed()
        compose.onNodeWithText("Tarjetas").assertIsDisplayed()

        // The choice stays mutually exclusive at this size too.
        classic().assertIsSelected()
        cards().performClick()
        compose.waitForIdle()
        cards().assertIsSelected()
        classic().assertIsNotSelected()
    }

    @Test
    fun theSelectorDoesNotOwnAFullWidthRow() {
        render(widthDp = 393, fontScale = 1.0f)

        val selector = compose.onNodeWithTag("playlist_catalog_mode_picker")
            .fetchSemanticsNode().size.width
        val root = compose.onRoot().fetchSemanticsNode().size.width
        assertTrue(
            "the selector still takes ${with(compose.density) { selector.toDp().value }}dp " +
                "of ${with(compose.density) { root.toDp().value }}dp",
            selector < root * 0.5f,
        )
    }
}
