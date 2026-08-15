package org.michimusic.mobile.remote

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.michimusic.link.ConnectionManager
import org.michimusic.link.EventClient
import org.michimusic.link.LinkClient
import org.michimusic.link.dto.PlaybackStateDto
import org.michimusic.link.dto.QueueDto
import org.michimusic.link.errors.LinkException
import org.michimusic.mobile.playback.PlaybackEndpoint
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.playback.PlaybackSessionState
import org.michimusic.mobile.rules.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private lateinit var viewModel: RemoteViewModel
    private lateinit var sessionManager: PlaybackSessionManager
    private lateinit var connectionManager: ConnectionManager
    private lateinit var linkClient: LinkClient
    private lateinit var eventClient: EventClient

    @Before
    fun setup() {
        sessionManager = mockk(relaxed = true)
        connectionManager = mockk(relaxed = true)
        linkClient = mockk(relaxed = true)
        eventClient = mockk(relaxed = true)

        val remoteEndpoint = org.michimusic.mobile.playback.PlaybackEndpoint(
            id = "dev_1",
            name = "Remote Node",
            type = org.michimusic.mobile.playback.EndpointType.SERVER,
            isLocal = false
        )
        val sessionState = MutableStateFlow(PlaybackSessionState(activeEndpoint = remoteEndpoint))
        every { sessionManager.sessionState } returns sessionState
        every { connectionManager.getClient("dev_1") } returns linkClient
        every { linkClient.createEventClient(any()) } returns eventClient
        every { eventClient.events } returns kotlinx.coroutines.flow.MutableSharedFlow<org.michimusic.link.ServerEvent>()

        viewModel = RemoteViewModel(sessionManager, connectionManager, testDispatcher)
    }

    @After
    fun teardown() {
        viewModel.disconnect()
    }

    @Test
    fun `connectIfNeeded sets state correctly and polls`() = runTest(testDispatcher) {
        val job = backgroundScope.launch { viewModel.uiState.collect { } }
        coEvery { linkClient.getPlaybackState() } returns Result.success(PlaybackStateDto(state = "playing"))
        coEvery { linkClient.getQueue() } returns Result.success(QueueDto())

        viewModel.connectIfNeeded()
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.connected)
        assertEquals("Remote Node", state.sourceName)
        assertEquals(RemoteSourceMode.REMOTE, state.mode)
        assertEquals("playing", state.playerState.effectiveState)
        
        viewModel.disconnect()
        job.cancel()
    }

    @Test
    fun `connectIfNeeded handles unauthorized error`() = runTest(testDispatcher) {
        val job = backgroundScope.launch { viewModel.uiState.collect { } }
        coEvery { linkClient.getPlaybackState() } returns Result.failure(LinkException.Unauthorized)

        viewModel.connectIfNeeded()
        runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.connected)
        assertEquals(RemoteConnectionState.UNAUTHORIZED, state.connState)
        
        viewModel.disconnect()
        job.cancel()
    }

    @Test
    fun `disconnect clears state`() = runTest(testDispatcher) {
        val job = backgroundScope.launch { viewModel.uiState.collect { } }
        coEvery { linkClient.getPlaybackState() } returns Result.success(PlaybackStateDto(state = "playing"))
        coEvery { linkClient.getQueue() } returns Result.success(QueueDto())

        viewModel.connectIfNeeded()
        runCurrent()
        assertTrue(viewModel.uiState.value.connected)

        viewModel.disconnect()
        runCurrent()
        
        val state = viewModel.uiState.value
        assertFalse(state.connected)
        assertEquals(RemoteConnectionState.DISCONNECTED, state.connState)
        
        job.cancel()
    }

    @Test
    fun `play calls sendCommand`() = runTest(testDispatcher) {
        val job = backgroundScope.launch { viewModel.uiState.collect { } }
        coEvery { linkClient.getPlaybackState() } returns Result.success(PlaybackStateDto(state = "playing"))
        coEvery { linkClient.getQueue() } returns Result.success(QueueDto())

        viewModel.connectIfNeeded()
        runCurrent()

        viewModel.play()
        runCurrent()

        io.mockk.coVerify { linkClient.sendPlaybackCommand("play", "") }
        
        viewModel.disconnect()
        job.cancel()
    }
}
