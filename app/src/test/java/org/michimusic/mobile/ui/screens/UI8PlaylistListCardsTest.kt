package org.michimusic.mobile.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
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
 * UI8-KILLCRITIC: Lista is every playlist as a full card.
 *
 * The screen used to render the first playlist as a hero and the rest as compact
 * rows under an "Otras playlists" heading, so the same playlist changed shape just
 * by being second. These tests render the real [PlaylistScreen] and assert that
 * position no longer selects a design: every entry is one complete card with its
 * own actions, and the list stays lazy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class UI8PlaylistListCardsTest {

    @get:Rule
    val compose = createComposeRule()

    private val historyDao = mockk<org.michimusic.data.cache.AppDao>(relaxed = true)

    /** The compact row the old Lista used; 80 dp was its floor. */
    private val compactRowHeightDp = 80f

    private fun playlist(id: String, name: String, count: Int) =
        PlaylistItem(id = id, name = name, trackCount = count)

    private fun render(
        playlists: List<PlaylistItem>,
        widthDp: Int = 393,
        fontScale: Float = 1.0f,
    ) {
        val viewModel = mockk<PlaylistsViewModel>(relaxed = true)
        every { viewModel.playlists } returns MutableStateFlow(playlists)
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

    private fun heightDp(tag: String): Float = with(compose.density) {
        compose.onNodeWithTag(tag).fetchSemanticsNode().size.height.toDp().value
    }

    private fun widthDp(tag: String): Float = with(compose.density) {
        compose.onNodeWithTag(tag).fetchSemanticsNode().size.width.toDp().value
    }

    private fun visibleCardIds(ids: List<String>): List<String> =
        ids.filter { id ->
            compose.onAllNodesWithTag("playlist_item_$id").fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun aSinglePlaylistIsOneCompleteCard() {
        render(listOf(playlist("p-1", "Única", 7)))

        compose.onNodeWithTag("playlist_item_p-1").assertIsDisplayed()
        assertTrue(
            "the only playlist must be a hero card, not a 80 dp row (height=${heightDp("playlist_item_p-1")})",
            heightDp("playlist_item_p-1") > compactRowHeightDp,
        )
        assertTrue(
            "the hero card must keep its 198 dp floor",
            heightDp("playlist_item_p-1") >= 198f,
        )
        compose.onNodeWithTag("playlist_spotlight_title_p-1", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun twoPlaylistsAreTwoCompleteCards() {
        render(
            listOf(
                playlist("p-1", "Primera", 61),
                playlist("p-2", "Segunda", 12),
            ),
        )

        compose.onNodeWithTag("playlist_item_p-1").assertIsDisplayed()
        compose.onNodeWithTag("playlist_item_p-2").assertIsDisplayed()
        assertTrue("first card shrank", heightDp("playlist_item_p-1") >= 198f)
        assertTrue(
            "the second playlist was still rendered as a compact row " +
                "(height=${heightDp("playlist_item_p-2")}dp)",
            heightDp("playlist_item_p-2") >= 198f,
        )
        assertEquals(
            "the two cards must share the same width",
            widthDp("playlist_item_p-1"),
            widthDp("playlist_item_p-2"),
            1f,
        )
        assertEquals(
            "the two cards must share the same height",
            heightDp("playlist_item_p-1"),
            heightDp("playlist_item_p-2"),
            1f,
        )
    }

    @Test
    fun theOtherPlaylistsHeadingIsGone() {
        render(
            listOf(
                playlist("p-1", "Primera", 61),
                playlist("p-2", "Segunda", 12),
                playlist("p-3", "Tercera", 4),
            ),
        )

        val headings = compose.onAllNodesWithText("Otras playlists").fetchSemanticsNodes()
        assertEquals("the list still announces a second-class section", 0, headings.size)
    }

    @Test
    fun noEntryIsDrawnAsACompactRow() {
        render(
            listOf(
                playlist("p-1", "Primera", 61),
                playlist("p-2", "Segunda", 12),
                playlist("p-3", "Tercera", 4),
            ),
        )

        // The catalog is lazy, so each entry is measured where it is actually laid out.
        val measured = mutableListOf<Float>()
        listOf("p-1", "p-2", "p-3").forEachIndexed { index, id ->
            if (index > 0) {
                compose.onNodeWithTag("playlist_catalog_list").performScrollToIndex(index)
                compose.waitForIdle()
            }
            val height = heightDp("playlist_item_$id")
            measured += height
            assertTrue(
                "$id is drawn as a compact row (height=${height}dp); every entry must be a card",
                height >= 198f,
            )
            compose.onNodeWithTag("playlist_spotlight_title_$id", useUnmergedTree = true)
                .assertIsDisplayed()
        }
        println("LISTA-CARD-HEIGHTS ${measured.joinToString(",")}")
    }

    @Test
    fun aLongCatalogKeepsOrderAndIdentity() {
        // Zero padded so the default Nombre sort and the numeric order agree.
        val items = (1..20).map { i -> playlist("p-$i", "Playlist %02d".format(i), i) }
        render(items)

        // Only the composition window is realised, and it must follow the catalog order.
        val visible = visibleCardIds(items.map { it.id })
        assertTrue("the list composed nothing", visible.isNotEmpty())
        val expectedPrefix = items.map { it.id }.take(visible.size)
        assertEquals(
            "the composed cards are not the catalog prefix in order",
            expectedPrefix,
            visible,
        )

        // Walking to the last entry keeps identity: the scroll target is that playlist.
        compose.onNodeWithTag("playlist_catalog_list").performScrollToIndex(items.lastIndex)
        compose.waitForIdle()
        compose.onNodeWithTag("playlist_item_p-20").assertIsDisplayed()
        assertTrue(
            "the last entry is not a card",
            heightDp("playlist_item_p-20") >= 198f,
        )
    }

    @Test
    fun aHundredPlaylistsStayLazy() {
        val items = (1..100).map { i -> playlist("p-$i", "Playlist $i", i) }
        render(items)

        val composed = items.count { i ->
            compose.onAllNodesWithTag("playlist_item_${i.id}").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("100 playlists composed $composed cards; the catalog is not lazy", composed <= 8)
        assertTrue("the catalog composed nothing", composed >= 1)
    }

    @Test
    fun everyCardKeepsItsOwnPlayAndMenu() {
        render(
            listOf(
                playlist("p-1", "Primera", 61),
                playlist("p-2", "Segunda", 12),
            ),
        )

        listOf("p-1" to "Primera", "p-2" to "Segunda").forEach { (id, name) ->
            compose.onNodeWithTag("playlist_play_$id").assertIsDisplayed()
            compose.onNodeWithTag("playlist_more_$id").assertIsDisplayed()
            compose.onNodeWithContentDescription("Reproducir $name").assertIsDisplayed()
        }
    }

    @Test
    fun changingTheSortKeepsTheCardsComplete() {
        render(
            listOf(
                playlist("p-a", "Alfa", 3),
                playlist("p-b", "Beta", 40),
            ),
        )

        compose.onNodeWithTag("playlist_sort_trigger").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("playlist_sort_tracks").performClick()
        compose.waitForIdle()

        listOf("p-a", "p-b").forEach { id ->
            assertTrue(
                "$id lost its card after the sort changed (height=${heightDp("playlist_item_$id")}dp)",
                heightDp("playlist_item_$id") >= 198f,
            )
        }
    }

    @Test
    fun aLongTitleKeepsTheCardIntact() {
        val longName = "Las mejores baladas de los ochenta y noventa para viajar"
        render(listOf(playlist("p-long", longName, 128)))

        val cardHeight = heightDp("playlist_item_p-long")
        assertTrue("the card collapsed around a long title", cardHeight >= 198f)

        // The title may use its two allowed lines; what must not happen is the card
        // growing past its own content or the actions leaving the card.
        val card = compose.onNodeWithTag("playlist_item_p-long").fetchSemanticsNode()
        val play = compose.onNodeWithTag("playlist_play_p-long").fetchSemanticsNode()
        assertTrue(
            "the play action left the card bounds",
            play.boundsInRoot.bottom <= card.boundsInRoot.bottom + 0.5f &&
                play.boundsInRoot.top >= card.boundsInRoot.top - 0.5f,
        )
    }

    @Test
    fun twoHundredPercentFontScaleKeepsCardsAndControls() {
        render(
            listOf(
                playlist("p-1", "Primera", 61),
                playlist("p-2", "Segunda", 12),
            ),
            fontScale = 2.0f,
        )

        listOf("p-1", "p-2").forEach { id ->
            assertTrue(
                "$id lost the 198 dp floor at 2.0x (height=${heightDp("playlist_item_$id")}dp)",
                heightDp("playlist_item_$id") >= 198f,
            )
            compose.onNodeWithTag("playlist_play_$id").assertIsDisplayed()
            compose.onNodeWithTag("playlist_more_$id").assertIsDisplayed()
        }
    }

    @Test
    fun theCatalogDoesNotRenderACompactRowTag() {
        render(
            listOf(
                playlist("p-1", "Primera", 61),
                playlist("p-2", "Segunda", 12),
            ),
        )

        // The old compact row drew the identity 54 dp tall inside an 80 dp row; no card
        // may come back at that height, even with a short name.
        assertTrue(
            "a card is compact-sized",
            heightDp("playlist_item_p-1") > compactRowHeightDp,
        )
        assertTrue(
            "a card is compact-sized",
            heightDp("playlist_item_p-2") > compactRowHeightDp,
        )
    }
}
