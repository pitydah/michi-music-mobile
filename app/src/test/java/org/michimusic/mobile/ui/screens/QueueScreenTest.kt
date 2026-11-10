package org.michimusic.mobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHasNoClickAction
import org.junit.Assert.assertEquals
import io.mockk.verify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.Track
import org.michimusic.data.repository.FavoritesRepository
import org.michimusic.mobile.playback.EndpointType
import org.michimusic.mobile.playback.PlaybackEndpoint
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.playback.PlaybackSessionState
import org.michimusic.mobile.playback.RemoteCommandRecord
import org.michimusic.mobile.playback.RemoteCommandStatus
import org.michimusic.player.AudioController
import org.michimusic.player.PlayerState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class QueueScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun localRowClickJumpsWithinQueueWithoutReplacement() {
        val mockSession = mockk<PlaybackSessionManager>(relaxed = true)
        every { mockSession.sessionState } returns MutableStateFlow(PlaybackSessionState())
        val mockAudio = mockk<AudioController>(relaxed = true)
        val tracks = listOf(Track("A", "Canción A", "Artista"), Track("B", "Canción B", "Artista"))
        every { mockAudio.state } returns MutableStateFlow(PlayerState(queue = tracks, queueIndex = 0))
        every { mockAudio.isReady } returns MutableStateFlow(true)
        val mockFavs = mockk<FavoritesRepository>(relaxed = true)
        every { mockFavs.observeFavoriteTrackIds() } returns MutableStateFlow(emptySet())
        composeTestRule.setContent { QueueScreen(sessionManager = mockSession, controller = mockAudio, favoritesRepo = mockFavs) }
        composeTestRule.onNodeWithTag("queue_item_1").performClick()
        verify(exactly = 1) { mockSession.skipToQueueIndex(1) }
        verify(exactly = 0) { mockSession.playQueue(any(), any(), any()) }
    }

    @Test
    fun pendingRemoteOperationDisablesQueueMutationsAndShowsFeedback() {
        val remote = PlaybackEndpoint("desktop", "Studio Desktop", EndpointType.DESKTOP_PLAYER,
            isLocal = false, isConnected = true)
        val mockSession = mockk<PlaybackSessionManager>(relaxed = true)
        every { mockSession.sessionState } returns MutableStateFlow(PlaybackSessionState(
            activeEndpoint = remote, isRemoteActive = true,
            queue = listOf(Track("A", "Canción A", "Artista")),
            lastRemoteCommand = RemoteCommandRecord(
                endpointId = "desktop", action = "jump_queue_index", status = RemoteCommandStatus.PENDING,
            ),
        ))
        val mockAudio = mockk<AudioController>(relaxed = true)
        every { mockAudio.state } returns MutableStateFlow(PlayerState())
        every { mockAudio.isReady } returns MutableStateFlow(true)
        val mockFavs = mockk<FavoritesRepository>(relaxed = true)
        every { mockFavs.observeFavoriteTrackIds() } returns MutableStateFlow(emptySet())
        composeTestRule.setContent { QueueScreen(sessionManager = mockSession, controller = mockAudio, favoritesRepo = mockFavs) }
        composeTestRule.onNodeWithText("Sincronizando cambios de cola con Studio Desktop…").assertIsDisplayed()
        composeTestRule.onNodeWithTag("queue_remove_0").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("queue_item_0").assertHasNoClickAction()
        verify(exactly = 0) { mockSession.skipToQueueIndex(any()) }
    }

    @Test
    fun remoteDuplicateDeletionIsDisabledUntilOccurrenceIdentifiersExist() {
        val remote = PlaybackEndpoint("desktop", "Studio Desktop", EndpointType.DESKTOP_PLAYER,
            isLocal = false, isConnected = true)
        val mockSession = mockk<PlaybackSessionManager>(relaxed = true)
        val repeated = Track("A", "Canción duplicada", "Artista")
        every { mockSession.sessionState } returns MutableStateFlow(PlaybackSessionState(
            activeEndpoint = remote, isRemoteActive = true, queue = listOf(repeated, repeated),
        ))
        val mockAudio = mockk<AudioController>(relaxed = true)
        every { mockAudio.state } returns MutableStateFlow(PlayerState())
        every { mockAudio.isReady } returns MutableStateFlow(true)
        val mockFavs = mockk<FavoritesRepository>(relaxed = true)
        every { mockFavs.observeFavoriteTrackIds() } returns MutableStateFlow(emptySet())
        composeTestRule.setContent { QueueScreen(sessionManager = mockSession, controller = mockAudio, favoritesRepo = mockFavs) }
        composeTestRule.onNodeWithTag("queue_remove_0").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("queue_remove_1").assertIsNotEnabled()
    }

    @Test
    fun queuePlaylistsNavigationDoesNotInventPlaylistId() {
        val mockSession = mockk<PlaybackSessionManager>(relaxed = true)
        every { mockSession.sessionState } returns MutableStateFlow(PlaybackSessionState())
        val mockAudio = mockk<AudioController>(relaxed = true)
        every { mockAudio.state } returns MutableStateFlow(PlayerState())
        every { mockAudio.isReady } returns MutableStateFlow(true)
        val mockFavs = mockk<FavoritesRepository>(relaxed = true)
        every { mockFavs.observeFavoriteTrackIds() } returns MutableStateFlow(emptySet())
        var calls = 0
        composeTestRule.setContent { QueueScreen(
            onNavigateToPlaylists = { calls++ },
            sessionManager = mockSession, controller = mockAudio, favoritesRepo = mockFavs,
        ) }
        composeTestRule.onNodeWithTag("queue_open_playlists").performClick()
        assertEquals(1, calls)
    }

    @Test
    fun queueScreen_whenRemoteActiveAndEmpty_doesNotFallBackToLocalQueue() {
        val mockSession = mockk<PlaybackSessionManager>(relaxed = true)
        val remoteEndpoint = PlaybackEndpoint(
            id = "desktop_host_1",
            name = "Studio Desktop",
            type = EndpointType.DESKTOP_PLAYER,
            isLocal = false
        )
        val remoteSessionState = PlaybackSessionState(
            activeEndpoint = remoteEndpoint,
            isRemoteActive = true,
            queue = emptyList(), // remote queue is empty!
            queueIndex = -1
        )
        every { mockSession.sessionState } returns MutableStateFlow(remoteSessionState)

        val mockAudio = mockk<AudioController>(relaxed = true)
        val localTrack = Track("local_1", "Secret Local Track", "Artist")
        val localPlayerState = PlayerState(
            queue = listOf(localTrack), // local queue has a track!
            queueIndex = 0
        )
        every { mockAudio.state } returns MutableStateFlow(localPlayerState)
        every { mockAudio.isReady } returns MutableStateFlow(true)

        val mockFavs = mockk<FavoritesRepository>(relaxed = true)
        every { mockFavs.observeFavoriteTrackIds() } returns MutableStateFlow(emptySet())

        composeTestRule.setContent {
            QueueScreen(
                sessionManager = mockSession,
                controller = mockAudio,
                favoritesRepo = mockFavs
            )
        }

        // K46D-41: Must NOT show the local queue track
        composeTestRule.onNodeWithText("Secret Local Track").assertDoesNotExist()

        // Must show dedicated remote empty state
        composeTestRule.onNodeWithText("Cola remota vacía en Studio Desktop").assertIsDisplayed()
        composeTestRule.onNodeWithText("No hay pistas en la cola del dispositivo Studio Desktop").assertIsDisplayed()
    }

    @Test
    fun queueScreen_whenRemoteQueueSyncPending_showsPendingMessage() {
        val mockSession = mockk<PlaybackSessionManager>(relaxed = true)
        val remoteEndpoint = PlaybackEndpoint(
            id = "desktop_host_1",
            name = "Studio Desktop",
            type = EndpointType.DESKTOP_PLAYER,
            isLocal = false
        )
        val remoteSessionState = PlaybackSessionState(
            activeEndpoint = remoteEndpoint,
            isRemoteActive = true,
            queue = emptyList(),
            lastRemoteCommand = RemoteCommandRecord(
                endpointId = "desktop_host_1",
                action = "play_queue",
                status = RemoteCommandStatus.PENDING
            )
        )
        every { mockSession.sessionState } returns MutableStateFlow(remoteSessionState)

        val mockAudio = mockk<AudioController>(relaxed = true)
        every { mockAudio.state } returns MutableStateFlow(PlayerState())
        every { mockAudio.isReady } returns MutableStateFlow(true)

        val mockFavs = mockk<FavoritesRepository>(relaxed = true)
        every { mockFavs.observeFavoriteTrackIds() } returns MutableStateFlow(emptySet())

        composeTestRule.setContent {
            QueueScreen(
                sessionManager = mockSession,
                controller = mockAudio,
                favoritesRepo = mockFavs
            )
        }

        composeTestRule.onNodeWithText("Sincronizando cola con Studio Desktop...").assertIsDisplayed()
    }
}
