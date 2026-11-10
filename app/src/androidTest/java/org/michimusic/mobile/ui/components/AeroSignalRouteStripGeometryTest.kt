package org.michimusic.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Symmetry is measured in real Compose layout coordinates, not a screenshot guess. */
@RunWith(AndroidJUnit4::class)
class AeroSignalRouteStripGeometryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dividerSeparatesTwoEqualColumnsAndKeepsIndependentActions() {
        var signalClicks = 0
        var outputClicks = 0
        compose.setContent {
            Box(modifier = Modifier.width(380.dp)) {
                AeroSignalRouteStrip(
                    signalLabel = "Signal Truth",
                    signalDescription = "Ruta de audio solicitada",
                    destination = "Este teléfono",
                    onSignalClick = { signalClicks++ },
                    onDestinationClick = { outputClicks++ },
                )
            }
        }
        val left = compose.onNodeWithTag("signal_truth_button").fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag("audio_route_button").fetchSemanticsNode().boundsInRoot
        val divider = compose.onNodeWithTag("signal_strip_center_divider", useUnmergedTree = true)
            .assertExists().fetchSemanticsNode().boundsInRoot
        assertTrue("Both targets must have positive width", left.width > 0f && right.width > 0f)
        assertTrue("Signal and output must have equal widths", abs(left.width-right.width) <= 2f)
        assertTrue("Divider must be at the midpoint", abs((left.center.x+right.center.x)/2f-divider.center.x) <= 2f)
        compose.onNodeWithTag("signal_truth_button").performClick()
        compose.onNodeWithTag("audio_route_button").performClick()
        compose.runOnIdle { assertEquals(1,signalClicks); assertEquals(1,outputClicks) }
    }
    @Test fun fileFormatIsVisibleAndNeverReplacedBySignalTruthTitle() {
        compose.setContent {
            AeroSignalRouteStrip(
                signalLabel = "MP3 · 44,1 kHz",
                signalDescription = "Datos declarados del archivo",
                destination = "Este teléfono",
                onSignalClick = {},
                onDestinationClick = {},
            )
        }
        compose.onNodeWithText("MP3 · 44,1 kHz", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("signal_truth_button").assertExists()
    }

}
