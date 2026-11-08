package org.michimusic.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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

    @Test fun fourTabsRetainSemanticsAndRealNavigation() {
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

    @Test fun compactTwoHundredPercentFontUsesTwoByTwoLayout() {
        assertEquals(DockLayout.TWO_BY_TWO, dockLayout(360, 2.0f))
        assertEquals(DockLayout.SINGLE_ROW, dockLayout(360, 1.5f))
        assertEquals(DockLayout.SINGLE_ROW, dockLayout(600, 2.0f))
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
                    BottomNavBar(currentRoute = "home", onTabSelected = {})
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

        assertEquals(home.top, library.top, 0.5f)
        assertEquals(search.top, devices.top, 0.5f)
        org.junit.Assert.assertTrue(search.top >= home.bottom)
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
