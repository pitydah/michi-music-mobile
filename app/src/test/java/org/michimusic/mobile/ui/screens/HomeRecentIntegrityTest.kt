package org.michimusic.mobile.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.Track
import org.michimusic.data.cache.LibraryMembershipDao
import org.michimusic.mobile.library.LibraryScopeStore
import org.michimusic.mobile.library.MusicRootAuthority
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.screens.MediaLibraryState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class HomeRecentIntegrityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val historyDao = mockk<org.michimusic.data.cache.AppDao>(relaxed = true)

    private lateinit var koinApp: KoinApplication

    @Before
    fun setUpKoin() {
        koinApp = koinApplication {
            modules(module {
                single {
                    mockk<LibraryScopeStore>(relaxed = true).also {
                        every { it.activeScope } returns MutableStateFlow<LibraryScope>(LibraryScope.All)
                        every { it.hydrated } returns MutableStateFlow(false)
                    }
                }
                single { mockk<LibraryMembershipDao>(relaxed = true) }
                single {
                    mockk<MusicRootAuthority>(relaxed = true).also {
                        every { it.revision } returns MutableStateFlow(0L)
                    }
                }
            })
        }
    }

    @After
    fun tearDownKoin() {
        koinApp.close()
    }

    private fun setHomeContent(content: @Composable () -> Unit) {
        composeTestRule.setContent {
            KoinIsolatedContext(koinApp) { content() }
        }
    }

    @Test
    fun whenNoRecentHistory_doesNotDisplayRecientementeEscuchadasHeader() {
        val t1 = Track(id = "t1", title = "Track in Library", artist = "Artist", duration = 120)
        val mockViewModel = mockk<AlbumsViewModel>(relaxed = true)
        every { mockViewModel.mediaState } returns MutableStateFlow(MediaLibraryState.READY)
        every { mockViewModel.allTracks } returns MutableStateFlow(listOf(t1))
        every { mockViewModel.albums } returns MutableStateFlow(emptyList())
        every { mockViewModel.recentTracks } returns MutableStateFlow(emptyList()) // No history
        every { mockViewModel.topTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.favoriteTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.playlists } returns MutableStateFlow(emptyList())
        every { mockViewModel.isLoading } returns MutableStateFlow(false)
        every { mockViewModel.error } returns MutableStateFlow(null)

        val mockSession = mockk<org.michimusic.mobile.playback.PlaybackSessionManager>(relaxed = true)
        every { mockSession.sessionState } returns MutableStateFlow(org.michimusic.mobile.playback.PlaybackSessionState())
        val mockAudio = mockk<org.michimusic.player.AudioController>(relaxed = true)
        every { mockAudio.state } returns MutableStateFlow(org.michimusic.player.PlayerState())

        setHomeContent {
            HomeScreen(viewModel = mockViewModel, sessionManager = mockSession, audioController = mockAudio, historyDao = historyDao)
        }

        // Must display honest title "De tu biblioteca" rather than falsely claiming "Recientemente Escuchadas"
        composeTestRule.onNodeWithTag("home_scroll_list")
            .performScrollToNode(hasText("De tu biblioteca"))
        composeTestRule.onNodeWithText("De tu biblioteca").assertIsDisplayed()
        composeTestRule.onNodeWithText("Recientemente Escuchadas").assertDoesNotExist()
    }

    @Test
    fun whenRecentHistoryExists_displaysRecientementeEscuchadasHeader() {
        val t1 = Track(id = "t1", title = "Actually Played Track", artist = "Artist", duration = 120)
        val mockViewModel = mockk<AlbumsViewModel>(relaxed = true)
        every { mockViewModel.mediaState } returns MutableStateFlow(MediaLibraryState.READY)
        every { mockViewModel.allTracks } returns MutableStateFlow(listOf(t1))
        every { mockViewModel.albums } returns MutableStateFlow(emptyList())
        every { mockViewModel.recentTracks } returns MutableStateFlow(listOf(t1)) // Real history
        every { mockViewModel.topTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.favoriteTracks } returns MutableStateFlow(emptyList())
        every { mockViewModel.playlists } returns MutableStateFlow(emptyList())
        every { mockViewModel.isLoading } returns MutableStateFlow(false)
        every { mockViewModel.error } returns MutableStateFlow(null)

        val mockSession = mockk<org.michimusic.mobile.playback.PlaybackSessionManager>(relaxed = true)
        every { mockSession.sessionState } returns MutableStateFlow(org.michimusic.mobile.playback.PlaybackSessionState())
        val mockAudio = mockk<org.michimusic.player.AudioController>(relaxed = true)
        every { mockAudio.state } returns MutableStateFlow(org.michimusic.player.PlayerState())

        setHomeContent {
            HomeScreen(viewModel = mockViewModel, sessionManager = mockSession, audioController = mockAudio, historyDao = historyDao)
        }

        composeTestRule.onNodeWithTag("home_scroll_list")
            .performScrollToNode(hasText("Recientemente Escuchadas"))
        composeTestRule.onNodeWithText("Recientemente Escuchadas").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recent_track_t1").assertExists()
    }
}
