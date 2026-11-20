package org.michimusic.mobile.ui.screens

import android.app.Application
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.screens.PlaylistItem
import org.michimusic.mobile.ui.theme.AeroTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deck uses existing playlist identities and keeps tap-to-open independent of swipes. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class UI8PlaylistDeckComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun frontCardOpensExactlyOnceInDeckMode() {
        var opens = 0
        val playlist = PlaylistItem(id = "p80", name = "80's", trackCount = 61)
        compose.setContent {
            AeroTheme {
                PlaylistCatalogSpotlight(
                    playlist = playlist,
                    visual = PlaylistVisualState(),
                    onOpen = { opens++ },
                    actions = {},
                    modifier = Modifier.width(320.dp),
                )
            }
        }
        compose.onNodeWithTag("playlist_item_p80").assertExists().performClick()
        assertEquals(1, opens)
    }

    @Test fun onlyFrontIsAccessibleAndNextActionCycles() {
        val playlists = listOf("p1", "p2", "p3")
        compose.setContent {
            AeroTheme {
                PlaylistSwipingDeck(
                    cards = playlists,
                    key = { it },
                    modifier = Modifier.size(320.dp, 278.dp),
                ) { id -> Box(Modifier.testTag("deck_card_$id")) }
            }
        }
        compose.onNodeWithTag("deck_card_p1").assertExists()
        compose.onNodeWithTag("deck_card_p2").assertDoesNotExist()
        compose.onNodeWithTag("deck_card_p3").assertDoesNotExist()
        compose.onNodeWithTag("playlist_swiping_front")
            .performCustomAccessibilityActionWithLabel("Siguiente playlist")
        compose.waitForIdle()
        compose.onNodeWithTag("deck_card_p2").assertExists()
        compose.onNodeWithTag("deck_card_p1").assertDoesNotExist()
        compose.onNodeWithTag("playlist_swiping_front")
            .performCustomAccessibilityActionWithLabel("Playlist anterior")
        compose.waitForIdle()
        compose.onNodeWithTag("deck_card_p1").assertExists()
    }

    @Test fun aFullForwardCycleReturnsToTheFirstCardInOrder() {
        val playlists = listOf("p1", "p2", "p3", "p4")
        compose.setContent {
            AeroTheme {
                PlaylistSwipingDeck(
                    cards = playlists,
                    key = { it },
                    maxVisibleCards = 3,
                    modifier = Modifier.size(320.dp, 278.dp),
                ) { id -> Box(Modifier.testTag("deck_card_$id")) }
            }
        }
        // UI8-KILLCRITIC: one advance per card, in the same order the list shows, and
        // back to the first after the last — a rotation, not a reordering.
        val expected = listOf("p2", "p3", "p4", "p1")
        expected.forEach { next ->
            compose.onNodeWithTag("playlist_swiping_front")
                .performCustomAccessibilityActionWithLabel("Siguiente playlist")
            compose.waitForIdle()
            compose.onNodeWithTag("deck_card_$next").assertExists()
            playlists.filter { it != next }.forEach {
                compose.onNodeWithTag("deck_card_$it").let { node ->
                    // The cards behind are decorative; only the front carries semantics.
                    node.assertDoesNotExist()
                }
            }
        }
    }

    @Test fun goingBackwardWalksTheSameCycleInReverse() {
        compose.setContent {
            AeroTheme {
                PlaylistSwipingDeck(
                    cards = listOf("p1", "p2", "p3"),
                    key = { it },
                    modifier = Modifier.size(320.dp, 278.dp),
                ) { id -> Box(Modifier.testTag("deck_card_$id")) }
            }
        }
        listOf("p3", "p2", "p1").forEach { next ->
            compose.onNodeWithTag("playlist_swiping_front")
                .performCustomAccessibilityActionWithLabel("Playlist anterior")
            compose.waitForIdle()
            compose.onNodeWithTag("deck_card_$next").assertExists()
        }
    }

    @Test fun disabledDeckExposesNoSwipeActionAndKeepsFront() {
        val enabled = mutableStateOf(false)
        compose.setContent {
            AeroTheme {
                PlaylistSwipingDeck(
                    cards = listOf("p1", "p2"),
                    key = { it },
                    isSwipeEnabled = enabled.value,
                    modifier = Modifier.size(320.dp, 278.dp),
                ) { id -> Box(Modifier.testTag("deck_card_$id")) }
            }
        }
        compose.onNodeWithTag("deck_card_p1").assertExists()
        val advance = runCatching {
            compose.onNodeWithTag("playlist_swiping_front")
                .performCustomAccessibilityActionWithLabel("Siguiente playlist")
        }
        assertTrue("A disabled deck must not expose the swipe action", advance.isFailure)
    }
}
