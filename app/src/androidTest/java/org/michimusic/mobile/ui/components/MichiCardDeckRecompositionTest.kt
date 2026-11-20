package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI8/C2: the deck must move the front card with the finger without composing
 * new cards. Only [androidx.compose.ui.graphics.graphicsLayer] and draw phases
 * may read the live drag offset; reading it while composing invalidates every
 * visible card on every pointer move, which is exactly the main-thread cost the
 * on-device frame evidence pointed at.
 *
 * This counts compositions, not frames: a frame budget would encode the
 * emulator's software renderer into a promise. The bounded number here is
 * structural (one composition per visible card, and only when something the
 * cards can see actually changes).
 */
@RunWith(AndroidJUnit4::class)
class MichiCardDeckRecompositionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aContinuousDragDoesNotRecomposeTheVisibleCards() {
        val compositions = AtomicInteger(0)
        var selected by mutableStateOf("artist-0")
        val items = (0 until 6).map { "artist-$it" }

        compose.setContent {
            MichiCardDeck(
                items = items,
                itemKey = { it },
                selectedKey = selected,
                onSelect = { selected = it },
                modifier = Modifier.fillMaxSize().testTag("deck_under_test"),
                circularArtwork = true,
                navigationButtons = false,
            ) { item, isFront ->
                compositions.incrementAndGet()
                Box(
                    Modifier.fillMaxSize()
                        .padding(32.dp)
                        .clip(CircleShape)
                        .background(Color.DarkGray)
                        .testTag(if (isFront) "card_$item" else "card_back_$item"),
                )
            }
        }
        compose.waitForIdle()

        // A drag that stays on one side of the zero offset: the preview sign never
        // flips, so nothing the cards can observe changes while the finger moves.
        compose.onNodeWithTag("michi_card_front").performTouchInput { down(center) }
        compose.waitForIdle()
        val baseline = compositions.get()

        repeat(MOVES) {
            compose.onNodeWithTag("michi_card_front").performTouchInput { moveBy(Offset(-6f, 0f)) }
            compose.waitForIdle()
        }
        val duringDrag = compositions.get() - baseline

        compose.onNodeWithTag("michi_card_front").performTouchInput { up() }
        compose.waitForIdle()

        assertTrue(
            "the deck composed $duringDrag card bodies across $MOVES pointer moves " +
                "($baseline before the drag); the drag offset belongs to the draw phase",
            duringDrag <= MAX_COMPOSITIONS_PER_DRAG,
        )
    }

    private companion object {
        const val MOVES = 20

        /**
         * Three cards are visible at most ([michiCardVisibleCount]), so a drag with a
         * constant preview sign has no legitimate card-body composition at all. The
         * allowance keeps the guard from breaking on a single incidental invalidation
         * while still failing on the per-pointer-move behaviour it exists to catch.
         */
        const val MAX_COMPOSITIONS_PER_DRAG = 1
    }
}
