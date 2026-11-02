package org.michimusic.mobile.ui.components

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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

}
