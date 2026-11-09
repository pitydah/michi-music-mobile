package org.michimusic.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme

/**
 * Real-font geometry for the five-tab dock. Robolectric cannot measure the
 * bundled Inter font reliably, so this matrix owns truncation evidence while
 * the JVM suite owns the pure layout policy.
 */
@RunWith(AndroidJUnit4::class)
class DockLayoutComposeTest {
    @get:Rule val rule = createComposeRule()

    private data class Scenario(val width: Int, val scale: Float) {
        override fun toString(): String = "${width}dp / ${scale}x"
    }

    @Test
    fun compactDockKeepsLegalOneLineTargetsAcrossTheRequestedMatrix() {
        val scenarios = buildList {
            for (width in listOf(320, 360, 393, 412, 480, 520)) {
                for (scale in listOf(1.0f, 1.3f, 1.5f, 1.7f, 2.0f)) {
                    add(Scenario(width, scale))
                }
            }
        }
        var scenario by mutableStateOf(scenarios.first())
        // Bounds are pixels; convert 48 dp using the density supplied below.
        val minTargetPx = 48.dp.value * 2f
        rule.setContent {
            val configuration = Configuration().apply {
                screenWidthDp = scenario.width
                fontScale = scenario.scale
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                // The AVD window is 360 dp wide at density 3; density 2 keeps the
                // full 320-520 dp matrix inside the host while retaining real fonts.
                LocalDensity provides Density(density = 2f, fontScale = scenario.scale),
            ) {
                MichiTheme {
                    Box(Modifier.width(scenario.width.dp)) {
                        BottomNavBar(
                            currentRoute = "devices",
                            selectedRoute = "devices",
                            onTabSelected = {},
                        )
                    }
                }
            }
        }

        for (current in scenarios) {
            rule.runOnIdle { scenario = current }
            rule.waitForIdle()
            val expected = dockLayoutForTabCount(current.width, current.scale, 5)
            val tabs = listOf("home", "library", "playlist", "search", "devices")
            val bounds = tabs.associateWith { route ->
                rule.onNodeWithTag("nav_tab_$route")
                    .assertContentDescriptionEquals(defaultNavTabs.first { it.route == route }.label)
                    .fetchSemanticsNode().boundsInRoot
            }
            tabs.forEach { route ->
                val tab = bounds.getValue(route)
                assertTrue("${route} width at $current", tab.width >= minTargetPx)
                assertTrue("${route} height at $current", tab.height >= minTargetPx)

                val layout = labelLayout("nav_label_$route")
                assertFalse(
                    "Label ellipsis for $route at $current: size=${layout.size}, " +
                        "lines=${layout.lineCount}, font=${layout.layoutInput.style.fontSize}, " +
                        "constraints=${layout.layoutInput.constraints}",
                    layout.isLineEllipsized(layout.lineCount - 1),
                )
                if (expected == DockLayout.SINGLE_ROW) {
                    assertEquals("$route should use one line at $current", 1, layout.lineCount)
                }
            }

            val tops = tabs.map { bounds.getValue(it).top }
            if (expected == DockLayout.SINGLE_ROW) {
                assertEquals("All five tabs must share one row at $current", 1, tops.distinct().size)
            } else {
                assertEquals("First stacked row at $current", 1, tops.take(3).distinct().size)
                assertEquals("Second stacked row at $current", 1, tops.drop(3).distinct().size)
                assertTrue(
                    "Stacked rows must not overlap at $current",
                    tops.drop(3).distinct().single() >= bounds.getValue("home").bottom,
                )
            }
        }
    }

    private fun labelLayout(tag: String): TextLayoutResult {
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
        assertNotNull("Missing text layout action for $tag", action)
        val results = mutableListOf<TextLayoutResult>()
        action!!.invoke(results)
        assertTrue("No text layout result for $tag", results.isNotEmpty())
        return results.last()
    }
}
