package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.michimusic.mobile.ui.components.MichiCardDeck
import org.michimusic.mobile.ui.components.ArtistMosaicTile
import org.michimusic.mobile.ui.components.ArtistMosaicEntry

/** Lightweight UI6 consumer contracts; does not instantiate AlbumsScreen's Koin/audio graph. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w412dp-h915dp")
class AlbumArtistCardContractsUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun artistTogglePreservesModeAndAllowsReturningToMosaic() {
        rule.setContent {
            var cards by remember { mutableStateOf(false) }
            Column {
                ArtistViewModeToggle(cardsMode = cards, onCardsModeChange = { cards = it })
                Text(if (cards) "Tarjetas" else "Mosaico", Modifier.testTag("artist_current_view"))
            }
        }
        rule.onNodeWithContentDescription("Vista mosaico de artistas. Cambiar a tarjetas")
            .performClick()
        rule.onNodeWithText("Tarjetas").assertIsDisplayed()
        rule.onNodeWithContentDescription("Vista tarjetas de artistas. Cambiar a mosaico")
            .performClick()
        rule.onNodeWithText("Mosaico").assertIsDisplayed()
    }

    @Test fun artistCardsPreserveTheCanonicalCircularPortraitAndOpenAction() {
        var opened = false
        rule.setContent {
            ArtistMosaicTile(
                artist = ArtistMosaicEntry("Artist A", listOf("album"), 1, 4),
                portrait = null,
                onClick = { opened = true },
                maxPortraitDiameter = 210.dp,
                highlightPortrait = true,
            )
        }
        rule.onNodeWithTag("artist_mosaic_portrait_Artist A", useUnmergedTree = true)
            .assertIsDisplayed()
        rule.onNodeWithTag("artist_mosaic_tile_Artist A").performClick()
        rule.runOnIdle { assertEquals(true, opened) }
    }

    @Test fun deckChangesIdentityWithoutOpeningAnAlbum() {
        var openRequests = 0
        var selectedChanges = 0
        rule.setContent {
            var selected by remember { mutableStateOf("album-a") }
            MichiCardDeck(
                items = listOf("album-a", "album-b"), itemKey = { it },
                selectedKey = selected,
                onSelect = { selected = it; selectedChanges++ },
                onOpenSelected = { openRequests++ },
                modifier = Modifier.fillMaxSize().testTag("test_album_deck"),
            ) { card, isFront -> Text(card, Modifier.testTag(if (isFront) "front_album" else "back_album")) }
        }
        rule.onNodeWithTag("michi_card_next").performClick()
        rule.runOnIdle { assertEquals(1, selectedChanges); assertEquals(0, openRequests) }
    }
}
