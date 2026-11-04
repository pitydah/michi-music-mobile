package org.michimusic.mobile.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.AeroTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tests are supplied for Gradle/Robolectric execution; not claimed as run here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AeroSignalStripCompactTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowButReadableNowPlayingStaysInTwoColumns() {
        assertFalse(aeroM60StackSignal(284f, 1f)) // 320dp window with 18dp margins
        assertFalse(aeroM60StackSignal(330f, 1f))
        assertTrue(aeroM60StackSignal(260f, 1f))
        assertTrue(aeroM60StackSignal(300f, 1.3f))
        assertTrue(aeroM60StackSignal(345f, 2f))
    }

    @Test fun twoIndependentTargetsDispatchExactlyOnce() {
        var signalActions = 0
        var outputActions = 0
        compose.setContent {
            AeroTheme {
                AeroSignalRouteStrip(
                    signalLabel = "MP3 · 44,1 kHz · 256 kb/s",
                    signalDescription = "PCM interno no observado; la salida física no está verificada.",
                    destination = "Este teléfono",
                    onSignalClick = { signalActions++ },
                    onDestinationClick = { outputActions++ },
                    modifier = Modifier.testTag("signal_strip"),
                )
            }
        }
        compose.onNodeWithTag("signal_truth_button").assertExists().assertHasClickAction().performClick()
        assertEquals(1, signalActions)
        assertEquals(0, outputActions)
        compose.onNodeWithTag("audio_route_button").assertExists().assertHasClickAction().performClick()
        assertEquals(1, signalActions)
        assertEquals(1, outputActions)
        // The large title and nested action links were removed with the giant card.
        compose.onNodeWithText("RUTA DE AUDIO").assertDoesNotExist()
        compose.onNodeWithText("Ver Signal Truth  ›").assertDoesNotExist()
        compose.onNodeWithTag("signal_strip").assertExists()
        compose.onNodeWithTag("signal_strip_footer").assertExists()
        compose.onNodeWithText("PCM interno no observado; la salida física no está verificada.").assertExists()
        // Footer spans the panel width, not just the left column. TextAlign.Center
        // additionally centers each wrapped glyph line within this full-width node.
        val panel = compose.onNodeWithTag("signal_strip").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("signal_strip_footer").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(panel.center.x - footer.center.x) <= 2f)
    }
}
