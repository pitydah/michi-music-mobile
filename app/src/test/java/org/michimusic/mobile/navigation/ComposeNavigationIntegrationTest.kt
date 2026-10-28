package org.michimusic.mobile.navigation
import org.michimusic.mobile.ui.components.BottomNavBar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Composable
private fun IntegrationTestNavScaffold(
    navController: NavHostController,
    hasActiveTrack: Boolean = true,
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val chrome = navigationPresentationFor(currentRoute)
    val miniPlayerVisible = chrome.showMiniPlayer && hasActiveTrack

    Scaffold(
        bottomBar = {
            if (chrome.showDock) {
                BottomNavBar(
                    currentRoute = currentRoute,
                    selectedRoute = chrome.selectedPrimaryTab,
                    onTabSelected = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id)
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    ) { innerPadding ->
        val isFullBleed = currentRoute == "nowplaying"
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullBleed) androidx.compose.foundation.layout.PaddingValues(0.dp) else innerPadding),
        ) {
            NavHost(
                navController = navController,
                startDestination = "home",
            ) {
                composable("home") { Text("HomeScreenContent", modifier = Modifier.testTag("route_home")) }
                composable("library") { Text("LibraryScreenContent", modifier = Modifier.testTag("route_library")) }
                composable("search") { Text("SearchScreenContent", modifier = Modifier.testTag("route_search")) }
                composable("devices") { Text("DevicesScreenContent", modifier = Modifier.testTag("route_devices")) }

                composable("settings") { Text("SettingsScreenContent", modifier = Modifier.testTag("route_settings")) }
                composable("playlist") { Text("PlaylistsOverviewContent", modifier = Modifier.testTag("route_playlist")) }
                composable(
                    route = "playlist/{playlistId}",
                    arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("playlistId").orEmpty()
                    Text("PlaylistDetail:$id", modifier = Modifier.testTag("route_playlist_detail"))
                }
                composable(
                    route = "sync_wizard/{deviceId}",
                    arguments = listOf(navArgument("deviceId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("deviceId").orEmpty()
                    Text("SyncWizard:$id", modifier = Modifier.testTag("route_sync_wizard"))
                }
                composable("nowplaying") { Text("NowPlayingScreenContent", modifier = Modifier.testTag("route_nowplaying")) }
                composable("queue") { Text("QueueScreenContent", modifier = Modifier.testTag("route_queue")) }
                composable("synced") { Text("SyncedScreenContent", modifier = Modifier.testTag("route_synced")) }
                composable("remote") { Text("RemoteScreenContent", modifier = Modifier.testTag("route_remote")) }
                composable("diagnostics") { Text("DiagnosticsScreenContent", modifier = Modifier.testTag("route_diagnostics")) }
            }

            if (miniPlayerVisible) {
                Text("MiniPlayerWidget", modifier = Modifier.testTag("mini_player_widget"))
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ComposeNavigationIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var navController: NavHostController

    private fun setContent(hasActiveTrack: Boolean = true) {
        composeTestRule.setContent {
            navController = rememberNavController()
            IntegrationTestNavScaffold(navController, hasActiveTrack = hasActiveTrack)
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun startupLoadsHomeScreenWithDockAndMiniPlayer() {
        setContent(hasActiveTrack = true)

        composeTestRule.onNodeWithTag("route_home").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bottom_nav_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mini_player_widget").assertIsDisplayed()
        assertEquals("home", navController.currentBackStackEntry?.destination?.route)
    }

    @Test
    fun navigatingThroughAllPrimaryTabsHighlightsDockCorrectly() {
        setContent()

        val tabs = listOf("library", "search", "devices", "home")
        for (tab in tabs) {
            composeTestRule.runOnUiThread {
                navController.navigate(tab) {
                    popUpTo(navController.graph.findStartDestination().id)
                    launchSingleTop = true
                }
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("route_$tab").assertIsDisplayed()
            val chrome = navigationPresentationFor(tab)
            assertTrue(chrome.showDock)
            assertEquals(tab, chrome.selectedPrimaryTab)
        }
    }

    @Test
    fun secondaryDetailScreensRetainPrimaryTabMappingAndShowDock() {
        setContent()

        // 1. Playlist Detail maps to library
        composeTestRule.runOnUiThread { navController.navigate("playlist/favorites") }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("route_playlist_detail").assertIsDisplayed()
        composeTestRule.onNodeWithText("PlaylistDetail:favorites").assertIsDisplayed()
        var chrome = navigationPresentationFor("playlist/favorites")
        assertEquals("library", chrome.selectedPrimaryTab)
        assertTrue(chrome.showDock)
        assertTrue(chrome.showMiniPlayer)

        // 2. Settings maps to home
        composeTestRule.runOnUiThread { navController.navigate("settings") }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("route_settings").assertIsDisplayed()
        chrome = navigationPresentationFor("settings")
        assertEquals("home", chrome.selectedPrimaryTab)
        assertTrue(chrome.showDock)

        // 3. Remote maps to devices
        composeTestRule.runOnUiThread { navController.navigate("remote") }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("route_remote").assertIsDisplayed()
        chrome = navigationPresentationFor("remote")
        assertEquals("devices", chrome.selectedPrimaryTab)
        assertTrue(chrome.showDock)
    }

    @Test
    fun nowPlayingIsImmersiveHidingDockAndMiniPlayer() {
        setContent(hasActiveTrack = true)

        composeTestRule.runOnUiThread { navController.navigate("nowplaying") }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("route_nowplaying").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bottom_nav_bar").assertDoesNotExist()
        composeTestRule.onNodeWithTag("mini_player_widget").assertDoesNotExist()

        val chrome = navigationPresentationFor("nowplaying")
        assertFalse(chrome.showDock)
        assertFalse(chrome.showMiniPlayer)
        assertEquals(MichiScreenLevel.IMMERSIVE, chrome.level)

        // Popping back from NowPlaying restores Home, Dock and MiniPlayer
        composeTestRule.runOnUiThread { navController.popBackStack() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("route_home").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bottom_nav_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mini_player_widget").assertIsDisplayed()
    }

    @Test
    fun syncWizardIsImmersiveHidingDockAndMiniPlayer() {
        setContent(hasActiveTrack = true)

        composeTestRule.runOnUiThread { navController.navigate("sync_wizard/node-99") }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("route_sync_wizard").assertIsDisplayed()
        composeTestRule.onNodeWithText("SyncWizard:node-99").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bottom_nav_bar").assertDoesNotExist()
        composeTestRule.onNodeWithTag("mini_player_widget").assertDoesNotExist()

        val chrome = navigationPresentationFor("sync_wizard/node-99")
        assertFalse(chrome.showDock)
        assertFalse(chrome.showMiniPlayer)
        assertEquals(MichiScreenLevel.IMMERSIVE, chrome.level)
    }

    @Test
    fun miniPlayerHidesWhenNoTrackIsPlaying() {
        setContent(hasActiveTrack = false)

        composeTestRule.onNodeWithTag("route_home").assertIsDisplayed()
        composeTestRule.onNodeWithTag("bottom_nav_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mini_player_widget").assertDoesNotExist()
    }

    @Test
    fun deepNavigationPopBackStackReturnsToExpectedPredecessor() {
        setContent()

        composeTestRule.runOnUiThread { navController.navigate("settings") }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { navController.navigate("diagnostics") }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("route_diagnostics").assertIsDisplayed()

        // 1st pop -> returns to settings
        composeTestRule.runOnUiThread { navController.popBackStack() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("route_settings").assertIsDisplayed()

        // 2nd pop -> returns to home
        composeTestRule.runOnUiThread { navController.popBackStack() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("route_home").assertIsDisplayed()
        assertEquals("home", navController.currentBackStackEntry?.destination?.route)
    }
}
