package org.michimusic.mobile.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Isolated harness mirroring MichiNavHost's route table and its BottomNavBar
 * onTabSelected navigate options (popUpTo(startDestination) + launchSingleTop,
 * WITHOUT saveState/restoreState). If MichiNavHost's onTabSelected block changes,
 * this block must be kept in sync.
 */
private fun NavHostController.selectTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id)
        launchSingleTop = true
    }
}

@Composable
private fun TestNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { Text("Home", modifier = Modifier.testTag("screen_home")) }
        composable("library") { Text("Library", modifier = Modifier.testTag("screen_library")) }
        composable("search") { Text("Search", modifier = Modifier.testTag("screen_search")) }
        composable("devices") { Text("Devices", modifier = Modifier.testTag("screen_devices")) }
        composable("settings") { Text("Settings", modifier = Modifier.testTag("screen_settings")) }
        composable(
            route = "playlist/{playlistId}",
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
        ) { Text("PlaylistDetail", modifier = Modifier.testTag("screen_playlist_detail")) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class BottomNavRoutingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var navController: NavHostController

    private fun setContentWithNavHost() {
        composeTestRule.setContent {
            navController = rememberNavController()
            TestNavHost(navController)
        }
    }

    @Test
    fun `playlist detail is discarded when switching tabs and back to home`() {
        setContentWithNavHost()

        composeTestRule.runOnUiThread { navController.navigate("playlist/pl-1") }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("screen_playlist_detail").assertExists()

        composeTestRule.runOnUiThread { navController.selectTab("library") }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("screen_library").assertExists()

        composeTestRule.runOnUiThread { navController.selectTab("home") }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("screen_home").assertExists()
        assertEquals("home", navController.currentBackStackEntry?.destination?.route)
    }

    @Test
    fun `settings is discarded when switching tabs and back to home`() {
        setContentWithNavHost()

        composeTestRule.runOnUiThread { navController.navigate("settings") }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("screen_settings").assertExists()

        composeTestRule.runOnUiThread { navController.selectTab("search") }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("screen_search").assertExists()

        composeTestRule.runOnUiThread { navController.selectTab("home") }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("screen_home").assertExists()
        assertEquals("home", navController.currentBackStackEntry?.destination?.route)
    }

    @Test
    fun `system back from playlist detail returns to home`() {
        setContentWithNavHost()

        composeTestRule.runOnUiThread { navController.navigate("playlist/pl-1") }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("screen_playlist_detail").assertExists()

        composeTestRule.runOnUiThread { navController.popBackStack() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("screen_home").assertExists()
        assertEquals("home", navController.currentBackStackEntry?.destination?.route)
    }

    @Test
    fun `switching tabs repeatedly never leaves more than home plus the current tab to pop through`() {
        setContentWithNavHost()

        composeTestRule.runOnUiThread { navController.navigate("playlist/pl-1") }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { navController.selectTab("library") }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { navController.selectTab("search") }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { navController.selectTab("devices") }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("screen_devices").assertExists()

        // Exactly one Back from "devices" must reach "home" directly - if the
        // discarded playlist/settings/library/search entries had leaked back
        // onto the stack, more than one Back would be needed.
        composeTestRule.runOnUiThread { navController.popBackStack() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("screen_home").assertExists()
        assertEquals("home", navController.currentBackStackEntry?.destination?.route)
    }
}
