package org.michimusic.mobile.remote

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.core.models.Track
import org.michimusic.link.ConnectionManager
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
    private val sessionState = MutableStateFlow(PlaybackSessionState())
    private val connectionStates = MutableStateFlow<Map<String, SyncConnectionState>>(emptyMap())

    @Before
    fun setup() {
        sessionManager = mockk(relaxed = true)
        connectionManager = mockk(relaxed = true)

        val remoteEndpoint = PlaybackEndpoint(
            id = "dev_1",
            name = "Remote Node",
            type = org.michimusic.mobile.playback.EndpointType.SERVER,
            isLocal = false
        )
        sessionState.value = PlaybackSessionState(
            activeEndpoint = remoteEndpoint,
            isPlaying = true,
            currentTrack = Track("t1", "Track 1", "Artist 1"),
            position = 1000L,
            duration = 5000L,
        )
        connectionStates.value = mapOf("dev_1" to SyncConnectionState.CONNECTED)
        
        every { sessionManager.sessionState } returns sessionState
        every { connectionManager.connectionStates } returns connectionStates

        viewModel = RemoteViewModel(sessionManager, connectionManager, testDispatcher)
    }

    @Test
    fun `uiState reflects sessionManager state reactively`() = runTest(testDispatcher) {
        val job = backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.connected)
        assertEquals("Remote Node", state.sourceName)
        assertEquals(RemoteSourceMode.REMOTE, state.mode)
        assertEquals("playing", state.playerState.state)
        assertEquals("Track 1", state.playerState.title)
        assertEquals(RemoteConnectionState.CONNECTED, state.connState)
        
        job.cancel()
    }

    @Test
    fun `disconnect delegates to sessionManager selectLocalEndpoint`() = runTest(testDispatcher) {
        viewModel.disconnect()
        runCurrent()

        verify { sessionManager.selectLocalEndpoint() }
    }

    @Test
    fun `play delegates to sessionManager play`() = runTest(testDispatcher) {
        viewModel.play()
        runCurrent()

        verify { sessionManager.play() }
    }

    @Test
    fun `pause delegates to sessionManager pause`() = runTest(testDispatcher) {
        viewModel.pause()
        runCurrent()

        verify { sessionManager.pause() }
    }

    @Test
    fun `stop delegates to sessionManager stop`() = runTest(testDispatcher) {
        viewModel.stop()
        runCurrent()

        verify { sessionManager.stop() }
    }

    @Test
    fun `togglePlayPause delegates to sessionManager playPause`() = runTest(testDispatcher) {
        viewModel.togglePlayPause()
        runCurrent()

        verify { sessionManager.playPause() }
    }

    @Test
    fun `queueJump delegates to sessionManager skipToQueueIndex`() = runTest(testDispatcher) {
        viewModel.queueJump(2)
        runCurrent()

        verify { sessionManager.skipToQueueIndex(2) }
    }

    @Test
    fun `unmappedOrAbsentConnectionState_isDisconnected_neverConnected`() = runTest(testDispatcher) {
        val job = backgroundScope.launch { viewModel.uiState.collect { } }
        // Update connectionStates to empty map (dev_1 missing)
        connectionStates.value = emptyMap()
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(RemoteConnectionState.DISCONNECTED, state.connState)
        assertFalse("Must NOT claim connected when device state is missing", state.connected)

        job.cancel()
    }

    @Test
    fun `unauthorizedConnectionState_isUnauthorized_notConnected`() = runTest(testDispatcher) {
        val job = backgroundScope.launch { viewModel.uiState.collect { } }
        connectionStates.value = mapOf("dev_1" to SyncConnectionState.UNAUTHORIZED)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(RemoteConnectionState.UNAUTHORIZED, state.connState)
        assertFalse(state.connected)
        assertEquals("Autenticación requerida con el host remoto", state.error)

        job.cancel()
    }

    @Test
    fun `offlineConnectionState_isOffline_notConnected`() = runTest(testDispatcher) {
        val job = backgroundScope.launch { viewModel.uiState.collect { } }
        connectionStates.value = mapOf("dev_1" to SyncConnectionState.OFFLINE)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(RemoteConnectionState.OFFLINE, state.connState)
        assertFalse(state.connected)
        assertEquals("Host remoto fuera de línea", state.error)

        job.cancel()
    }

    @Test
    fun `connectingConnectionState_isConnecting_notConnected`() = runTest(testDispatcher) {
        val job = backgroundScope.launch { viewModel.uiState.collect { } }
        connectionStates.value = mapOf("dev_1" to SyncConnectionState.CONNECTING)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(RemoteConnectionState.CONNECTING, state.connState)
        assertFalse(state.connected)

        job.cancel()
    }
}
