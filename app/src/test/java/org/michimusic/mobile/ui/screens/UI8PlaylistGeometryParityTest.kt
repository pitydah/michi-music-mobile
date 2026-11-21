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
import kotlin.math.abs

/**
 * UI8-KILLCRITIC: one playlist must be one card.
 *
 * These tests render the real [PlaylistScreen] — not an isolated spotlight — and
 * measure the real semantics bounds of the hero card in both modes, converting
 * pixels to dp with the composition density. The acceptance contract is a
 * maximum difference of 1 dp in width and height for the same playlist, the
 * same device and the same font scale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class UI8PlaylistGeometryParityTest {

    @get:Rule
    val compose = createComposeRule()

    private val historyDao = mockk<org.michimusic.data.cache.AppDao>(relaxed = true)

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

    private fun heroWidthDp(tag: String): Float = with(compose.density) {
        compose.onNodeWithTag(tag).fetchSemanticsNode().size.width.toDp().value
    }

    private fun heroHeightDp(tag: String): Float = with(compose.density) {
        compose.onNodeWithTag(tag).fetchSemanticsNode().size.height.toDp().value
    }

    private fun switchToCards() {
        if (compose.onAllNodesWithTag("playlist_catalog_cards").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("playlist_view_mode_toggle").performClick()
            compose.waitForIdle()
        }
    }

    private fun switchToLista() {
        if (compose.onAllNodesWithTag("playlist_catalog_list").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("playlist_view_mode_toggle").performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun theFrontCardKeepsTheListaGeometryInTarjetas() {
        render(
            listOf(
                playlist("p-hero", "80's", 61),
                playlist("p-second", "Jazz nocturno", 12),
            ),
        )
        val listaWidth = heroWidthDp("playlist_item_p-hero")
        val listaHeight = heroHeightDp("playlist_item_p-hero")

        switchToCards()
        val cardsWidth = heroWidthDp("playlist_item_p-hero")
        val cardsHeight = heroHeightDp("playlist_item_p-hero")
        println(
            "GEOMETRY-PARITY device=393dp fontScale=1.0 " +
                "lista=${listaWidth}x${listaHeight}dp tarjetas=${cardsWidth}x${cardsHeight}dp",
        )

        assertTrue(
            "width drifted between modes: Lista=${listaWidth}dp Tarjetas=${cardsWidth}dp",
            abs(listaWidth - cardsWidth) <= 1f,
        )
        assertTrue(
            "height drifted between modes: Lista=${listaHeight}dp Tarjetas=${cardsHeight}dp",
            abs(listaHeight - cardsHeight) <= 1f,
        )
    }

    @Test
    fun aLongTitleKeepsTheSameGeometryInBothModes() {
        val longName = "Las mejores baladas de los ochenta y noventa para viajar"
        render(
            listOf(
                playlist("p-long", longName, 128),
                playlist("p-other", "Otro", 3),
            ),
        )
        val lista = heroHeightDp("playlist_item_p-long")
        val listaWidth = heroWidthDp("playlist_item_p-long")

        switchToCards()
        val cards = heroHeightDp("playlist_item_p-long")
        val cardsWidth = heroWidthDp("playlist_item_p-long")

        assertTrue(
            "width drifted with a long title: Lista=$listaWidth Tarjetas=$cardsWidth",
            abs(listaWidth - cardsWidth) <= 1f,
        )
        assertTrue(
            "height drifted with a long title: Lista=$lista Tarjetas=$cards",
            abs(lista - cards) <= 1f,
        )
    }

    @Test
    fun repeatedModeSwitchingKeepsTheGeometryStable() {
        render(listOf(playlist("p-hero", "80's", 61), playlist("p-second", "Jazz", 12)))
        val reference = heroWidthDp("playlist_item_p-hero") to heroHeightDp("playlist_item_p-hero")

        repeat(10) {
            switchToCards()
            val cards = heroWidthDp("playlist_item_p-hero") to
                heroHeightDp("playlist_item_p-hero")
            assertEquals("cards width changed on toggle $it", reference.first, cards.first, 1f)
            assertEquals("cards height changed on toggle $it", reference.second, cards.second, 1f)

            switchToLista()
            val lista = heroWidthDp("playlist_item_p-hero") to
                heroHeightDp("playlist_item_p-hero")
            assertEquals("lista width changed on toggle $it", reference.first, lista.first, 1f)
            assertEquals("lista height changed on toggle $it", reference.second, lista.second, 1f)
        }
    }

    @Test
    fun aSinglePlaylistKeepsTheHeroGeometryInTarjetas() {
        render(listOf(playlist("p-only", "Única", 7)))
        val lista = heroWidthDp("playlist_item_p-only") to heroHeightDp("playlist_item_p-only")

        switchToCards()
        val cards = heroWidthDp("playlist_item_p-only") to heroHeightDp("playlist_item_p-only")

        assertEquals("single playlist width drifted", lista.first, cards.first, 1f)
        assertEquals("single playlist height drifted", lista.second, cards.second, 1f)
    }

    @Test
    fun everyCardKeepsTheHeroGeometryWhateverItsPosition() {
        render(
            listOf(
                playlist("p-1", "Primera", 61),
                playlist("p-2", "Segunda", 12),
                playlist("p-3", "Tercera", 4),
            ),
        )
        // The catalog is lazy: each card is measured where it is actually laid out, and the
        // values are compared afterwards so a disposed card cannot skew the result.
        val measured = linkedMapOf<String, Pair<Float, Float>>()
        listOf("p-1", "p-2", "p-3").forEachIndexed { index, id ->
            if (index > 0) {
                compose.onNodeWithTag("playlist_catalog_list").performScrollToIndex(index)
                compose.waitForIdle()
            }
            measured[id] = heroWidthDp("playlist_item_$id") to heroHeightDp("playlist_item_$id")
        }
        val reference = measured.getValue("p-1")

        measured.filterKeys { it != "p-1" }.forEach { (id, size) ->
            println("GEOMETRY-POSITION id=$id width=${size.first}dp height=${size.second}dp")
            assertEquals("card $id is not as wide as the first card", reference.first, size.first, 1f)
            assertEquals("card $id is not as tall as the first card", reference.second, size.second, 1f)
        }

        // The deck front is the same card, so the reference must survive the mode change.
        compose.onNodeWithTag("playlist_catalog_list").performScrollToIndex(0)
        compose.waitForIdle()
        switchToCards()
        assertEquals("the deck front drifted", reference.first, heroWidthDp("playlist_item_p-1"), 1f)
        assertEquals("the deck front drifted", reference.second, heroHeightDp("playlist_item_p-1"), 1f)
    }

    @Test
    fun aPinnedPlaylistKeepsTheHeroGeometry() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<Application>()
        kotlinx.coroutines.runBlocking {
            PlaylistVisualStore(context).setPinned("p-2", true)
        }

        render(
            listOf(
                playlist("p-1", "Primera", 61),
                playlist("p-2", "Segunda", 12),
            ),
        )
        compose.waitForIdle()

        // Pinned first is the order contract; the geometry must not be what changes.
        val pinnedTop = compose.onNodeWithTag("playlist_item_p-2").fetchSemanticsNode()
            .boundsInRoot.top
        val otherTop = compose.onNodeWithTag("playlist_item_p-1").fetchSemanticsNode()
            .boundsInRoot.top
        assertTrue(
            "the pinned playlist is not first (pinned.top=$pinnedTop other.top=$otherTop)",
            pinnedTop < otherTop,
        )
        assertEquals(
            "the pinned card is not as wide as the other card",
            heroWidthDp("playlist_item_p-1"),
            heroWidthDp("playlist_item_p-2"),
            1f,
        )
        // Height is content driven with the 198 dp floor, and the pinned card legitimately
        // carries one extra line (the FIJADA marker), so the contract is: same width, never
        // shorter than the other card, and never below the floor. Position must not shrink it.
        val plainHeight = heroHeightDp("playlist_item_p-1")
        val pinnedHeight = heroHeightDp("playlist_item_p-2")
        println("GEOMETRY-PINNED plain=${plainHeight}dp pinned=${pinnedHeight}dp")
        assertTrue("the plain card lost the 198 dp floor (height=${plainHeight}dp)", plainHeight >= 198f)
        assertTrue("the pinned card lost the 198 dp floor (height=${pinnedHeight}dp)", pinnedHeight >= 198f)
        assertTrue(
            "the pinned card is shorter than the plain card (pinned=${pinnedHeight}dp plain=${plainHeight}dp)",
            pinnedHeight >= plainHeight,
        )
        // The marker is composited with the card; the geometric claim above is what matters.
        compose.onNodeWithText("FIJADA", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aTwoHundredPercentFontScaleGrowsBothModesEqually() {
        render(
            listOf(
                playlist("p-hero", "80's", 61),
                playlist("p-second", "Jazz", 12),
            ),
            fontScale = 2.0f,
        )
        val lista = heroWidthDp("playlist_item_p-hero") to heroHeightDp("playlist_item_p-hero")

        switchToCards()
        val cards = heroWidthDp("playlist_item_p-hero") to heroHeightDp("playlist_item_p-hero")
        println(
            "GEOMETRY-PARITY device=393dp fontScale=2.0 " +
                "lista=${lista.first}x${lista.second}dp tarjetas=${cards.first}x${cards.second}dp",
        )

        assertEquals("width drifted at 2.0x", lista.first, cards.first, 1f)
        assertEquals("height drifted at 2.0x", lista.second, cards.second, 1f)
        assertTrue("the 2.0x card lost its 198dp floor", cards.second >= 198f)
    }
}
