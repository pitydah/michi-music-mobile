package org.michimusic.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.VisualPreferences
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class, qualifiers = "w412dp-h915dp")
class MichiCardDeckUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun previousAndNextSwitchFrontIdentity() {
        rule.setContent {
            var selected by remember { mutableStateOf("one") }
            MichiCardDeck(
                items = listOf("one", "two", "three"),
                itemKey = { it },
                selectedKey = selected,
                onSelect = { selected = it },
                modifier = Modifier.fillMaxSize(),
            ) { item, isFront ->
                Text(item, modifier = Modifier.testTag(if (isFront) "front_$item" else "back_$item"))
            }
        }
        rule.onNodeWithTag("front_one").assertIsDisplayed()
        rule.onNodeWithTag("michi_card_next").performClick()
        rule.onNodeWithTag("front_two").assertIsDisplayed()
        rule.onNodeWithTag("michi_card_previous").performClick()
        rule.onNodeWithTag("front_one").assertIsDisplayed()
    }

    @Test fun highContrastStillRendersMultipleDeckLayersWithoutMotion() {
        rule.setContent {
            CompositionLocalProvider(LocalVisualPreferences provides VisualPreferences(highContrast = true)) {
                var selected by remember { mutableStateOf("a") }
                MichiCardDeck(
                    items = listOf("a", "b", "c"), itemKey = { it },
                    selectedKey = selected, onSelect = { selected = it },
                    modifier = Modifier.fillMaxSize(),
                ) { item, isFront ->
                    Text(item, Modifier.testTag(if (isFront) "high_front_$item" else "high_back_$item"))
                }
            }
        }
        // Back cards are intentionally excluded from TalkBack semantics; verify
        // the visible front and the preserved navigation rather than exposing backs.
        rule.onNodeWithTag("high_front_a").assertIsDisplayed()
        rule.onNodeWithTag("michi_card_next").performClick()
        rule.onNodeWithTag("high_front_b").assertIsDisplayed()
    }

    @Test fun swipingAClickableFrontCardSelectsWithoutOpeningItsDetail() {
        var opened = 0
        var selections = 0
        rule.setContent {
            var selected by remember { mutableStateOf("a") }
            MichiCardDeck(
                items = listOf("a", "b", "c"), itemKey = { it },
                selectedKey = selected,
                onSelect = { selected = it; selections++ },
                modifier = Modifier.fillMaxSize(),
            ) { item, isFront ->
                Text(
                    text = "Cover $item",
                    modifier = Modifier.fillMaxSize()
                        .clickable(enabled = isFront, role = Role.Button) { opened++ }
                        .testTag(if (isFront) "clickable_front_$item" else "clickable_back_$item"),
                )
            }
        }
        rule.onNodeWithTag("michi_card_front").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithTag("clickable_front_b").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, selections); assertEquals(0, opened) }
        rule.onNodeWithTag("clickable_front_b").performClick()
        rule.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun singleCardDisablesBothNavigationButtons() {
        rule.setContent {
            MichiCardDeck(
                items = listOf("only"),
                itemKey = { it },
                selectedKey = "only",
                onSelect = {},
                modifier = Modifier.fillMaxSize(),
            ) { item, _ -> Text(item) }
        }
        rule.onNodeWithTag("michi_card_previous").assertIsNotEnabled()
        rule.onNodeWithTag("michi_card_next").assertIsNotEnabled()
    }

    @Test fun repeatedIdentityDoesNotCreateExtraNavigationPosition() {
        rule.setContent {
            var selected by remember { mutableStateOf("one") }
            MichiCardDeck(
                items = listOf("one", "one", "two"),
                itemKey = { it },
                selectedKey = selected,
                onSelect = { selected = it },
                modifier = Modifier.fillMaxSize(),
            ) { item, isFront ->
                Text(item, modifier = Modifier.testTag(if (isFront) "front_$item" else "back_$item"))
            }
        }
        rule.onNodeWithTag("michi_card_next").performClick()
        rule.onNodeWithTag("front_two").assertIsDisplayed()
    }

    @Test fun horizontalGesturesMoveBothDirections() {
        var selections = 0
        rule.setContent {
            var selected by remember { mutableStateOf("one") }
            MichiCardDeck(
                items = listOf("one", "two", "three"),
                itemKey = { it }, selectedKey = selected,
                onSelect = { selected = it; selections++ },
                modifier = Modifier.fillMaxSize(),
            ) { item, front ->
                Text(item, Modifier.testTag(if (front) "front_$item" else "back_$item"))
            }
        }
        rule.onNodeWithTag("michi_card_front").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithTag("front_two").assertIsDisplayed()
        rule.onNodeWithTag("michi_card_front").performTouchInput { swipeRight() }
        rule.waitForIdle()
        rule.onNodeWithTag("front_one").assertIsDisplayed()
        rule.runOnIdle { assertEquals(2, selections) }
    }

    @Test fun cancelledGestureNeverCommitsNavigation() {
        var selectionEvents = 0
        rule.setContent {
            MichiCardDeck(
                items = listOf("a", "b"),
                itemKey = { it }, selectedKey = "a",
                onSelect = { selectionEvents++ },
                modifier = Modifier.fillMaxSize(),
            ) { item, front ->
                Text(item, Modifier.testTag(if (front) "front_$item" else "back_$item"))
            }
        }
        rule.onNodeWithTag("michi_card_front").performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.40f, 0f))
            cancel()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("front_a").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, selectionEvents) }
    }

    @Test fun externalSelectionInvalidatesPreviousGesture() {
        var selectEvents = 0
        var externallySelect: (() -> Unit)? = null
        rule.setContent {
            var selected by remember { mutableStateOf("a") }
            externallySelect = { selected = "c" }
            MichiCardDeck(
                items = listOf("a", "b", "c"),
                itemKey = { it }, selectedKey = selected,
                onSelect = { selected = it; selectEvents++ },
                modifier = Modifier.fillMaxSize(),
            ) { item, front ->
                Text(item, Modifier.testTag(if (front) "front_$item" else "back_$item"))
            }
        }
        rule.onNodeWithTag("michi_card_front").performTouchInput {
            down(center)
            moveBy(Offset(-width * 0.30f, 0f))
        }
        rule.runOnIdle { externallySelect?.invoke() }
        rule.waitForIdle()
        rule.onNodeWithTag("front_c").assertIsDisplayed()
        // Compose touch injection retains unfinished pointers across calls.
        rule.onNodeWithTag("michi_card_front").performTouchInput { cancel() }
        rule.runOnIdle { assertEquals(0, selectEvents) }
    }

    @Test fun artistNavigationKeepsCircularSemantics() {
        rule.setContent {
            var selected by remember { mutableStateOf("A") }
            MichiCardDeck(
                items = listOf("A", "B"),
                itemKey = { it }, selectedKey = selected,
                onSelect = { selected = it }, circularArtwork = true,
                modifier = Modifier.fillMaxSize(),
            ) { item, _ -> Text(item) }
        }
        rule.onNodeWithContentDescription("Artista siguiente").performClick()
        rule.onNodeWithTag("michi_card_position").assertIsDisplayed()
        rule.onNodeWithContentDescription("Artista anterior").performClick()
    }

    @Test fun tinyWindowShowsHonestFallbackInsteadOfClippedCard() {
        rule.setContent {
            MichiCardDeck(
                items = listOf("one", "two"),
                itemKey = { it },
                selectedKey = "one",
                onSelect = {},
                modifier = Modifier.width(80.dp).height(140.dp),
            ) { item, _ -> Text(item) }
        }
        rule.onNodeWithTag("michi_card_compact_hint").assertIsDisplayed()
        rule.onNodeWithTag("michi_card_front").assertDoesNotExist()
        rule.onNodeWithTag("michi_card_previous").assertExists()
        rule.onNodeWithTag("michi_card_next").assertExists()
    }

    @Test fun tinyWindowStillCanOpenSelectedItem() {
        var opened: String? = null
        rule.setContent {
            MichiCardDeck(
                items = listOf("one", "two"),
                itemKey = { it },
                selectedKey = "two",
                onSelect = {},
                onOpenSelected = { opened = it },
                modifier = Modifier.width(80.dp).height(140.dp),
            ) { item, _ -> Text(item) }
        }
        // At 80dp width the fallback is an icon with an accessible open label.
        rule.onNodeWithContentDescription("Abrir álbum seleccionado").assertIsDisplayed()
        rule.onNodeWithTag("michi_card_compact_open").performClick()
        rule.runOnIdle { assertEquals("two", opened) }
    }

    @Test fun tinyWindowCanNavigateAndOpenTheNewSelection() {
        var opened: String? = null
        rule.setContent {
            var selected by remember { mutableStateOf("one") }
            MichiCardDeck(
                items = listOf("one", "two", "three"),
                itemKey = { it },
                selectedKey = selected,
                onSelect = { selected = it },
                onOpenSelected = { opened = it },
                modifier = Modifier.width(80.dp).height(140.dp),
            ) { item, _ -> Text(item) }
        }
        rule.onNodeWithTag("michi_card_next").performScrollTo().performClick()
        rule.onNodeWithTag("michi_card_compact_hint").assertIsDisplayed()
        rule.onNodeWithText("two · 2/3").assertIsDisplayed()
        rule.onNodeWithTag("michi_card_compact_open").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("two", opened) }
    }

    @Test fun compactNavigationWithoutOpenCallbackIsStillAvailable() {
        rule.setContent {
            var selected by remember { mutableStateOf("alpha") }
            MichiCardDeck(
                items = listOf("alpha", "beta"),
                itemKey = { it }, selectedKey = selected,
                onSelect = { selected = it },
                modifier = Modifier.width(80.dp).height(140.dp),
            ) { item, _ -> Text(item) }
        }
        rule.onNodeWithTag("michi_card_previous").performScrollTo().performClick()
        rule.onNodeWithTag("michi_card_compact_hint").assertIsDisplayed()
        rule.onNodeWithTag("michi_card_compact_open").assertDoesNotExist()
    }


    @Test fun ignoredParentSelectionDoesNotLockTheCompactDeck() {
        var attempts = 0
        rule.setContent {
            MichiCardDeck(
                items = listOf("a", "b"),
                itemKey = { it },
                selectedKey = "a",
                onSelect = { attempts++ }, // A controlled parent may reject a stale selection.
                modifier = Modifier.width(80.dp).height(140.dp),
            ) { item, _ -> Text(item) }
        }
        rule.onNodeWithTag("michi_card_next").performScrollTo().performClick()
        rule.waitForIdle() // The one-frame recovery must restore the controls.
        rule.onNodeWithTag("michi_card_next").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(2, attempts) }
    }
}
