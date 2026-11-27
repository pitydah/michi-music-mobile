package org.michimusic.mobile.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.repository.MusicRootStamp
import org.michimusic.mobile.artist.ArtistVariantReviewChoice
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * K57 review-ticket lifecycle: navigating to an album, Now Playing, or recreating the
 * Activity must not revoke the opaque review context. Only an explicit close/back does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ArtistReviewRouteLifecycleTest {
    @get:Rule val compose = createComposeRule()

    private var now = 10_000L
    private val store = ArtistReviewTicketStore { now }
    private val root = ArtistReviewRootGuard(MusicRootStamp("saf:A", 4L), 7L)

    private fun issue(): String = requireNotNull(
        store.issue(
            ArtistVariantReviewChoice(
                credit = "A-ha", libraryId = "local-library",
                albumIds = listOf("album-a", "album-b"),
                verifiedMbid = null, conflictingEvidence = false,
            ),
            root,
        ),
    )

    @Composable
    private fun Harness(
        nav: NavHostController,
        ticket: String,
        start: String,
    ) {
        NavHost(navController = nav, startDestination = start) {
            composable("home") {
                Column {
                    Button(
                        onClick = { nav.navigate("artist-review/$ticket") },
                        modifier = Modifier.testTag("open_review"),
                    ) { Text("Abrir revisión") }
                }
            }
            composable(
                route = "artist-review/{ticketId}",
                arguments = listOf(navArgument("ticketId") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("ticketId").orEmpty()
                ArtistReviewRoute(
                    ticketId = id,
                    root = root,
                    store = store,
                    content = { review, onClose ->
                        Column {
                            Text("REVIEW:${review.credit}", modifier = Modifier.testTag("review_context"))
                            Button(
                                onClick = { nav.navigate("album/a1") },
                                modifier = Modifier.testTag("review_album"),
                            ) { Text("Álbum") }
                            Button(
                                onClick = {
                                    onClose()
                                    nav.popBackStack()
                                },
                                modifier = Modifier.testTag("review_back"),
                            ) { Text("Volver") }
                        }
                    },
                    expired = { onClose ->
                        Column {
                            Text("REVIEW_EXPIRED", modifier = Modifier.testTag("review_expired"))
                            Button(
                                onClick = onClose,
                                modifier = Modifier.testTag("expired_close"),
                            ) { Text("Cerrar") }
                        }
                    },
                )
            }
            composable(
                route = "album/{albumId}",
                arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
            ) {
                Column {
                    Text("ALBUM", modifier = Modifier.testTag("album_context"))
                    Button(
                        onClick = { nav.popBackStack() },
                        modifier = Modifier.testTag("album_back"),
                    ) { Text("Volver") }
                }
            }
        }
    }

    @Test
    fun reviewToAlbumAndBackPreservesTheSameContext() {
        val ticket = issue()
        compose.setContent {
            Harness(rememberNavController(), ticket, start = "artist-review/$ticket")
        }
        compose.onNodeWithTag("review_context").assertIsDisplayed()

        compose.onNodeWithTag("review_album").performClick()
        compose.onNodeWithTag("album_context").assertIsDisplayed()

        compose.onNodeWithTag("album_back").performClick()
        compose.onNodeWithTag("review_context").assertIsDisplayed()
        assertNotNull(store.resolve(ticket, root))
    }

    @Test
    fun recreationPreservesTheReviewContext() {
        val ticket = issue()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            Harness(rememberNavController(), ticket, start = "artist-review/$ticket")
        }
        compose.onNodeWithTag("review_context").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("review_context").assertIsDisplayed()
        assertNotNull(store.resolve(ticket, root))
    }

    @Test
    fun explicitBackRevokesAndReopeningFailsClosed() {
        val ticket = issue()
        compose.setContent {
            Harness(rememberNavController(), ticket, start = "home")
        }
        compose.onNodeWithTag("open_review").performClick()
        compose.onNodeWithTag("review_context").assertIsDisplayed()

        compose.onNodeWithTag("review_back").performClick()
        compose.onNodeWithTag("open_review").assertIsDisplayed()
        assertNull(store.resolve(ticket, root))

        compose.onNodeWithTag("open_review").performClick()
        compose.onNodeWithTag("review_expired").assertIsDisplayed()
        compose.onNodeWithTag("review_context").assertDoesNotExist()
    }

    @Test
    fun expiredTicketFailsClosedWithoutContext() {
        val ticket = issue()
        now += ArtistReviewTicketStore.TTL_MS
        compose.setContent {
            Harness(rememberNavController(), ticket, start = "artist-review/$ticket")
        }
        compose.onNodeWithTag("review_expired").assertIsDisplayed()
        compose.onNodeWithTag("review_context").assertDoesNotExist()
        assertNull(store.resolve(ticket, root))
    }
}
