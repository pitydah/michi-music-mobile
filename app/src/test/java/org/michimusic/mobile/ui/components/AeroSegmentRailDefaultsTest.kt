package org.michimusic.mobile.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI8-KILLCRITIC: the micro variant must not change the rail that Library, Search and
 * Settings already use. This guard renders the default rail and proves it still stretches
 * to the full width and keeps the 48 dp segment height, so a future tweak to the shared
 * row cannot silently shrink those screens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AeroSegmentRailDefaultsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theDefaultRailStillFillsTheWidthWithFullHeightSegments() {
        compose.setContent {
            MichiTheme {
                Box(Modifier.width(360.dp).testTag("rail_default_parent")) {
                    AeroSegmentRail(modifier = Modifier.testTag("rail_default_probe")) {
                        AeroSegmentedChoice(
                            label = "Todos",
                            selected = true,
                            onClick = {},
                            modifier = Modifier.weight(1f),
                            testTag = "rail_default_first",
                        )
                        AeroSegmentedChoice(
                            label = "Favoritos",
                            selected = false,
                            onClick = {},
                            modifier = Modifier.weight(1f),
                            testTag = "rail_default_second",
                        )
                    }
                }
            }
        }

        val parent = compose.onNodeWithTag("rail_default_parent").fetchSemanticsNode().boundsInRoot
        val rail = compose.onNodeWithTag("rail_default_probe").fetchSemanticsNode().boundsInRoot
        val segment = compose.onNodeWithTag("rail_default_first").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the default rail must still fill its parent: rail=${rail.width}px parent=${parent.width}px",
            rail.width >= parent.width - 1f,
        )
        assertTrue(
            "the default segment must stay at least 48 dp tall, was ${segment.height}px",
            segment.height >= with(compose.density) { 48.dp.toPx() } - 1f,
        )
    }
}
