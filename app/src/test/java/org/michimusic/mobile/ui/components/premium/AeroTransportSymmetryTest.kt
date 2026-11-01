package org.michimusic.mobile.ui.components.premium

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression guard for M60's reference-image three-button optical axis. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AeroTransportSymmetryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun geometryMirrorsPreviousAndNextForRegularWidths() {
        listOf(280f, 288f, 320f, 360f, 392f, 412f, 480f).forEach { width ->
            val c = aeroTransportCenters(width)
            assertEquals(width / 2f, c.playPause, 0.0001f)
            assertEquals(c.playPause - c.previous, c.next - c.playPause, 0.0001f)
            assertTrue(c.previous < c.playPause && c.playPause < c.next)
        }
    }

    @Test fun geometryPreservesAxisWhenSecondaryControlsMoveToAnotherRow() {
        listOf(240f, 256f, 279f).forEach { width ->
            val c = aeroTransportCenters(width)
            assertEquals(width / 2f, c.playPause, 0.0001f)
            assertEquals(c.playPause - c.previous, c.next - c.playPause, 0.0001f)
        }
    }

    private fun assertRenderedGeometry(width: Dp) {
        compose.setContent {
            MichiTheme {
                Box(Modifier.width(width)) {
                    MichiPlaybackControls(
                        state = PlaybackControlUiState(isPlaying = true),
                        onPlayPause = {}, onSkipPrevious = {}, onSkipNext = {},
                        onToggleShuffle = {}, onCycleRepeat = {},
                        prevTestTag = "sym_prev", playPauseTestTag = "sym_play", nextTestTag = "sym_next",
                        repeatTestTag = "sym_repeat", shuffleTestTag = "sym_shuffle",
                    )
                }
            }
        }
        compose.waitForIdle()
        val prev = compose.onNodeWithTag("sym_prev")
        val play = compose.onNodeWithTag("sym_play")
        val next = compose.onNodeWithTag("sym_next")
        listOf(prev, play, next).forEach {
            it.assertExists().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("sym_repeat").assertExists()
        compose.onNodeWithTag("sym_shuffle").assertExists()
        val p = prev.fetchSemanticsNode().boundsInRoot.center.x
        val c = play.fetchSemanticsNode().boundsInRoot.center.x
        val n = next.fetchSemanticsNode().boundsInRoot.center.x
        assertEquals("Previous/Next must be mirrored about the play/pause axis", c - p, n - c, 1.5f)
    }

    @Test fun renderingMaintainsSymmetryOnStandardPhone() = assertRenderedGeometry(360.dp)

    @Test fun renderingMaintainsSymmetryOnCompactPhone() = assertRenderedGeometry(260.dp)
}
