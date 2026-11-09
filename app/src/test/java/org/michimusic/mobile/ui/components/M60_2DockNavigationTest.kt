package org.michimusic.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.ui.theme.MichiTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
@OptIn(ExperimentalTestApi::class)
class M60_2DockNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fiveTabsRetainSemanticsAndRealNavigation() {
        val destinations = mutableListOf<String>()
        compose.setContent {
            MichiTheme {
                BottomNavBar(
                    currentRoute = "home",
                    onTabSelected = { destinations += it },
                )
            }
        }
        compose.onNodeWithTag("nav_tab_home")
            .assertIsSelected()
            .assertContentDescriptionEquals("Inicio")
            .assertHeightIsAtLeast(48.dp)
            .performClick() // Already-selected tabs must not dispatch a navigation.
        assertEquals(emptyList<String>(), destinations)
        compose.onNodeWithTag("nav_tab_library")
            .assertIsNotSelected()
            .assertContentDescriptionEquals("Biblioteca")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithTag("nav_tab_playlist").assertContentDescriptionEquals("Playlists")
        compose.onNodeWithTag("nav_tab_search").assertContentDescriptionEquals("Buscar")
        compose.onNodeWithTag("nav_tab_devices").assertContentDescriptionEquals("Dispositivos")
        assertEquals(listOf("library"), destinations)
    }
    @Test fun selectedRouteOverrideControlsCurrentTabClickGuard() {
        val destinations = mutableListOf<String>()
        compose.setContent {
            MichiTheme {
                BottomNavBar(
                    currentRoute = "home",
                    selectedRoute = "library",
                    onTabSelected = { destinations += it },
                )
            }
        }
        compose.onNodeWithTag("nav_tab_library").assertIsSelected().performClick()
        assertEquals(emptyList<String>(), destinations)
        compose.onNodeWithTag("nav_tab_home").assertIsNotSelected().performClick()
        assertEquals(listOf("home"), destinations)
    }

    @Test fun fiveDestinationsUseOneRowAtNormalScaleOnEverySupportedPhoneWidth() {
        for (width in listOf(320, 360, 393, 412, 480, 520)) {
            assertEquals(
                "Five-tab dock must stay compact at ${width}dp / 100%",
                DockLayout.SINGLE_ROW,
                dockLayoutForTabCount(width, 1.0f, 5),
            )
        }
        // Four-destination call sites keep the pre-UI5 accessibility fallback.
        assertEquals(DockLayout.TWO_BY_TWO, dockLayout(360, 2.0f))
        assertEquals(DockLayout.SINGLE_ROW, dockLayout(360, 1.5f))
    }

    @Test fun fiveDestinationFallbackFollowsPerTabLabelBudgetNotAFixedBreakpoint() {
        val expected = mapOf(
            1.0f to setOf(320, 360, 393, 412, 480, 520),
            1.3f to setOf(412, 480, 520),
            1.5f to setOf(480, 520),
            1.7f to setOf(520),
            2.0f to emptySet(),
        )
        for ((scale, singleRowWidths) in expected) {
            for (width in listOf(320, 360, 393, 412, 480, 520)) {
                val actual = dockLayoutForTabCount(width, scale, 5)
                val wanted = if (width in singleRowWidths) DockLayout.SINGLE_ROW else DockLayout.TWO_BY_TWO
                assertEquals("Unexpected dock layout at ${width}dp / ${scale}x", wanted, actual)
            }
        }
    }

    @Test fun fiveTabGeometryKeepsOneRowAndLegalTargetsAcrossWidthAndScaleMatrix() {
        val scenarios = buildList {
            for (width in listOf(320, 360, 393, 412, 480, 520)) {
                for (scale in listOf(1.0f, 1.3f, 1.5f, 1.7f, 2.0f)) {
                    add(DockScenario(width, scale))
                }
            }
        }
        var scenario by mutableStateOf(scenarios.first())
        // The matrix supplies density 1, so 48 dp is 48 px here.
        val minTargetPx = with(Density(density = 1f)) { 48.dp.toPx() }
        compose.setContent {
            val configuration = Configuration().apply {
                screenWidthDp = scenario.width
                fontScale = scenario.scale
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density = 1f, fontScale = scenario.scale),
            ) {
                MichiTheme {
                    Box(Modifier.width(scenario.width.dp)) {
                        BottomNavBar(currentRoute = "home", onTabSelected = {})
                    }
                }
            }
        }

        for (current in scenarios) {
            compose.runOnIdle { scenario = current }
            compose.waitForIdle()
            val expected = dockLayoutForTabCount(current.width, current.scale, 5)
            val tabs = listOf("home", "library", "playlist", "search", "devices")
            val bounds = tabs.associateWith { route ->
                compose.onNodeWithTag("nav_tab_$route")
                    .assertContentDescriptionEquals(defaultNavTabs.first { it.route == route }.label)
                    .assertHeightIsAtLeast(48.dp)
                    .fetchSemanticsNode().boundsInRoot
            }
            tabs.forEach { route ->
                val bound = bounds.getValue(route)
                assertTrue("${route} target width at $current", bound.width >= minTargetPx)
                val layout = labelLayout("nav_label_$route")
                val label = compose.onNodeWithTag("nav_label_$route", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                assertTrue("Label must start inside $route at $current", label.left >= bound.left)
                assertTrue("Label must end inside $route at $current", label.right <= bound.right + 0.5f)
                if (expected == DockLayout.SINGLE_ROW) {
                    assertEquals("$route should stay on one row at $current", 1, layout.lineCount)
                }
            }

            val tops = tabs.map { bounds.getValue(it).top }
            if (expected == DockLayout.SINGLE_ROW) {
                assertTrue("All five tabs must share one row at $current: $tops", tops.distinct().size == 1)
            } else {
                val firstRow = tops.take(3).distinct()
                val secondRow = tops.drop(3).distinct()
                assertEquals("First stacked row at $current", 1, firstRow.size)
                assertEquals("Second stacked row at $current", 1, secondRow.size)
                assertTrue("Stacked rows must not overlap at $current", secondRow.single() >= bounds.getValue("home").bottom)
            }
        }
    }

    @Test fun twoByTwoDockKeepsAllFullLabelsAndTouchTargets() {
        val configuration = Configuration().apply {
            screenWidthDp = 360
            fontScale = 2.0f
        }
        compose.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density = 1f, fontScale = 2f),
            ) {
                MichiTheme {
                    Box(Modifier.width(360.dp)) {
                        BottomNavBar(currentRoute = "home", onTabSelected = {})
                    }
                }
            }
        }

        val home = compose.onNodeWithTag("nav_tab_home")
            .assertContentDescriptionEquals("Inicio")
            .assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot
        val library = compose.onNodeWithTag("nav_tab_library")
            .assertContentDescriptionEquals("Biblioteca")
            .assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithTag("nav_tab_search")
            .assertContentDescriptionEquals("Buscar")
            .assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot
        val devices = compose.onNodeWithTag("nav_tab_devices")
            .assertContentDescriptionEquals("Dispositivos")
            .assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot

        val playlists = compose.onNodeWithTag("nav_tab_playlist")
            .assertContentDescriptionEquals("Playlists")
            .assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(home.top, library.top, 0.5f)
        assertEquals(home.top, playlists.top, 0.5f)
        assertEquals(search.top, devices.top, 0.5f)
        org.junit.Assert.assertTrue(search.top >= home.bottom)
    }

    @Test fun singleRowDockKeepsLabelInsideItsSelectionAtOneAndHalfScale() {
        val configuration = Configuration().apply {
            screenWidthDp = 480
            fontScale = 1.5f
        }
        compose.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density = 1f, fontScale = 1.5f),
            ) {
                MichiTheme {
                    Box(Modifier.width(480.dp)) {
                        BottomNavBar(currentRoute = "devices", onTabSelected = {})
                    }
                }
            }
        }

        val tab = compose.onNodeWithTag("nav_tab_devices")
            .fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithTag("nav_label_devices", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        org.junit.Assert.assertTrue("Label must start inside the selection", label.left >= tab.left + 2f)
        org.junit.Assert.assertTrue("Label must end inside the selection", label.right <= tab.right - 2f)
    }

    @Test fun keyboardMovesFocusAndActivatesTheNextSingleRowTab() {
        val destinations = mutableListOf<String>()
        compose.setContent {
            MichiTheme {
                BottomNavBar(
                    currentRoute = "home",
                    onTabSelected = { destinations += it },
                )
            }
        }

        compose.onNodeWithTag("nav_tab_home")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("nav_tab_library")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("library"), destinations)
    }

    private data class DockScenario(val width: Int, val scale: Float) {
        override fun toString(): String = "${width}dp / ${scale}x"
    }

    private fun labelLayout(tag: String): TextLayoutResult {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
        assertNotNull("Missing text layout action for $tag", action)
        val results = mutableListOf<TextLayoutResult>()
        action!!.invoke(results)
        assertTrue("No text layout result for $tag", results.isNotEmpty())
        return results.last()
    }

    @Test fun dpadFollowsTwoByTwoGeometryAtTwoHundredPercentFont() {
        val destinations = mutableListOf<String>()
        val configuration = Configuration().apply {
            screenWidthDp = 360
            fontScale = 2.0f
        }
        compose.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density = 1f, fontScale = 2f),
            ) {
                MichiTheme {
                    BottomNavBar(
                        currentRoute = "home",
                        onTabSelected = { destinations += it },
                    )
                }
            }
        }

        compose.onNodeWithTag("nav_tab_home")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("nav_tab_search")
            .assertIsFocused()
            .performKeyInput { pressKey(Key.Enter) }

        assertEquals(listOf("search"), destinations)
    }

}
