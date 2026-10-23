package org.michimusic.mobile.playback

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.link.ConnectionManager
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkDiscovery
import org.michimusic.link.PairedDeviceRegistry
import org.michimusic.link.dto.QueueDto
import org.michimusic.link.dto.QueueTrackDto
import org.michimusic.link.dto.PlaybackStateDto
import org.michimusic.player.AudioController
import org.michimusic.player.PlayerState

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteCommandAuthorityTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: CoroutineScope

    private val audioController = mockk<AudioController>(relaxed = true)
    private val connectionManager = mockk<ConnectionManager>(relaxed = true)
    private val linkDiscovery = mockk<LinkDiscovery>(relaxed = true)
    private val registry = mockk<PairedDeviceRegistry>(relaxed = true)

    private lateinit var sessionManager: PlaybackSessionManager

    private val remoteEndpoint = PlaybackEndpoint(
        id = "desktop-node-1",
        name = "Desktop Studio",
        type = EndpointType.DESKTOP_PLAYER,
        isLocal = false,
        isConnected = true,
    )

    private val trackA = Track(
        id = "trk-a",
        title = "Track A",
        artist = "Artist A",
        album = "Album A",
        duration = 180000,
        source = TrackSource.STREAMING,
    )

    private val trackB = Track(
        id = "trk-b",
        title = "Track B",
        artist = "Artist B",
        album = "Album B",
        duration = 200000,
        source = TrackSource.STREAMING,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        testScope = CoroutineScope(SupervisorJob() + testDispatcher)
        every { audioController.state } returns MutableStateFlow(PlayerState())
        every { connectionManager.connectionStates } returns MutableStateFlow(emptyMap())
        every { linkDiscovery.peers } returns MutableStateFlow(emptyMap())
        sessionManager = PlaybackSessionManager(
            audioController = audioController,
            connectionManager = connectionManager,
            linkDiscovery = linkDiscovery,
            registry = registry,
            scope = testScope,
        )
    }

    @After
    fun tearDown() {
        testScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun localPlayback_doesNotGenerateRemoteCommandRecord() = runTest(testDispatcher) {
        sessionManager.playNow(trackA)
        
        // Assert no remote command record is generated
        assertNull(sessionManager.sessionState.value.lastRemoteCommand)
    }

    @Test
    fun remotePlayback_playNow_awaitsRemoteAuthorityAndConfirms() = runTest(testDispatcher) {
        val client = mockk<LinkClient>(relaxed = true)
        every { connectionManager.getClient("desktop-node-1") } returns client
        every { client.baseUrl } returns "http://192.168.1.50:8400"
        every { client.deviceToken } returns "valid-token"

        // Mock remote command success and authoritative state retrieval
        coEvery { client.sendPlaybackCommand("play_track", "trk-a") } returns Result.success("OK")
        coEvery { client.getPlaybackState() } returns Result.success(
            PlaybackStateDto(
                playing = true,
                positionMs = 1200L,
                durationMs = 180000L,
                queueIndex = 0,
            )
        )
        coEvery { client.getQueue() } returns Result.success(
            QueueDto(
                tracks = listOf(
                    QueueTrackDto(trackId = "trk-a", title = "Track A", artist = "Artist A", album = "Album A", duration = 180000L)
                ),
                currentIndex = 0,
            )
        )

        // Attach remote endpoint
        sessionManager.attachRemote(remoteEndpoint) { _, _ -> }

        // Initial queue should be empty before action
        assertEquals(0, sessionManager.sessionState.value.queue.size)

        // Trigger remote action
        sessionManager.playNow(trackA)

        // Let coroutines settle
        advanceUntilIdle()

        // Command must have transitioned to CONFIRMED
        val lastCmd = sessionManager.sessionState.value.lastRemoteCommand
        assertNotNull(lastCmd)
        assertEquals(RemoteCommandStatus.CONFIRMED, lastCmd!!.status)
        assertEquals("play_track", lastCmd.action)
        assertEquals("trk-a", lastCmd.param)

        // Authoritative state must be reflected in sessionState
        val state = sessionManager.sessionState.value
        assertEquals("trk-a", state.currentTrack?.id)
        assertEquals(1, state.queue.size)
        assertEquals(true, state.isPlaying)

        coVerify { client.sendPlaybackCommand("play_track", "trk-a") }
        coVerify { client.getPlaybackState() }
        coVerify { client.getQueue() }
    }

    @Test
    fun remotePlayback_playNow_onFailure_marksRejectedAndDoesNotMutateQueue() = runTest(testDispatcher) {
        val client = mockk<LinkClient>(relaxed = true)
        every { connectionManager.getClient("desktop-node-1") } returns client
        every { client.baseUrl } returns "http://192.168.1.50:8400"
        every { client.deviceToken } returns "valid-token"
        coEvery { client.sendPlaybackCommand("play_track", "trk-a") } returns Result.failure(Exception("Action not supported"))

        sessionManager.attachRemote(remoteEndpoint) { _, _ -> }

        sessionManager.playNow(trackA)
        advanceUntilIdle()

        val lastCmd = sessionManager.sessionState.value.lastRemoteCommand
        assertNotNull(lastCmd)
        assertEquals(RemoteCommandStatus.REJECTED, lastCmd!!.status)
        assertEquals("Action not supported", lastCmd.resultMessage)

        // Queue must NOT have been optimistically modified
        assertEquals(0, sessionManager.sessionState.value.queue.size)
        assertNull(sessionManager.sessionState.value.currentTrack)
    }

    @Test
    fun remotePlayback_removeFromQueue_awaitsRemoteAuthority() = runTest(testDispatcher) {
        val client = mockk<LinkClient>(relaxed = true)
        every { connectionManager.getClient("desktop-node-1") } returns client
        every { client.baseUrl } returns "http://192.168.1.50:8400"
        every { client.deviceToken } returns "valid-token"

        coEvery { client.removeQueueItem("trk-a") } returns Result.success(Unit)
        coEvery { client.getQueue() } returns Result.success(
            QueueDto(
                tracks = listOf(
                    QueueTrackDto(trackId = "trk-b", title = "Track B", artist = "Artist B", album = "Album B", duration = 200000L)
                ),
                currentIndex = 0,
            )
        )

        sessionManager.attachRemote(remoteEndpoint) { _, _ -> }

        coEvery { client.getPlaybackState() } returns Result.success(PlaybackStateDto(playing = true))
        coEvery { client.getQueue() } returns Result.success(
            QueueDto(tracks = listOf(QueueTrackDto(trackId = "trk-a", title = "Track A")), currentIndex = 0)
        )

        sessionManager.playNow(trackA)
        advanceUntilIdle()

        // Now remove it
        coEvery { client.getQueue() } returns Result.success(
            QueueDto(tracks = listOf(QueueTrackDto(trackId = "trk-b", title = "Track B")), currentIndex = 0)
        )
        sessionManager.removeFromQueue(0)
        advanceUntilIdle()

        val lastCmd = sessionManager.sessionState.value.lastRemoteCommand
        assertNotNull(lastCmd)
        assertEquals(RemoteCommandStatus.CONFIRMED, lastCmd!!.status)
        assertEquals("remove_queue_item", lastCmd.action)
        assertEquals("trk-a", lastCmd.param)

        // Queue should now contain only Track B
        val state = sessionManager.sessionState.value
        assertEquals(1, state.queue.size)
        assertEquals("trk-b", state.queue[0].id)
    }

    @Test
    fun remotePlayback_playNow_whenGetQueueFails_marksAcceptedNotConfirmed() = runTest(testDispatcher) {
        val client = mockk<LinkClient>(relaxed = true)
        every { connectionManager.getClient("desktop-node-1") } returns client
        every { client.baseUrl } returns "http://192.168.1.50:8400"
        every { client.deviceToken } returns "valid-token"

        // Command succeeds on server, but subsequent queue synchronization fails
        coEvery { client.sendPlaybackCommand("play_track", "trk-a") } returns Result.success("OK")
        coEvery { client.getPlaybackState() } returns Result.success(PlaybackStateDto(playing = true))
        coEvery { client.getQueue() } returns Result.failure(Exception("Remote queue fetch failed"))

        sessionManager.attachRemote(remoteEndpoint) { _, _ -> }

        sessionManager.playNow(trackA)
        advanceUntilIdle()

        val lastCmd = sessionManager.sessionState.value.lastRemoteCommand
        assertNotNull(lastCmd)
        // Must NOT be CONFIRMED, but ACCEPTED because server did accept the command
        assertEquals(RemoteCommandStatus.ACCEPTED, lastCmd!!.status)
        org.junit.Assert.assertTrue(lastCmd.resultMessage!!.contains("sincronización de estado pendiente"))
        assertEquals(0, sessionManager.sessionState.value.queue.size)
    }

    @Test
    fun remotePlayback_playNow_onTimeout_marksTimedOut() = runTest(testDispatcher) {
        val client = mockk<LinkClient>(relaxed = true)
        every { connectionManager.getClient("desktop-node-1") } returns client
        every { client.baseUrl } returns "http://192.168.1.50:8400"
        every { client.deviceToken } returns "valid-token"
        coEvery { client.sendPlaybackCommand("play_track", "trk-a") } returns Result.failure(java.net.SocketTimeoutException("Read timeout"))

        sessionManager.attachRemote(remoteEndpoint) { _, _ -> }

        sessionManager.playNow(trackA)
        advanceUntilIdle()

        val lastCmd = sessionManager.sessionState.value.lastRemoteCommand
        assertNotNull(lastCmd)
        assertEquals(RemoteCommandStatus.TIMED_OUT, lastCmd!!.status)
    }

    @Test
    fun remotePlayback_playNow_onDisconnect_marksDisconnected() = runTest(testDispatcher) {
        val client = mockk<LinkClient>(relaxed = true)
        every { connectionManager.getClient("desktop-node-1") } returns client
        every { client.baseUrl } returns "http://192.168.1.50:8400"
        every { client.deviceToken } returns "valid-token"

        sessionManager.attachRemote(remoteEndpoint) { _, _ -> }

        // Now simulate client disconnection
        every { connectionManager.getClient("desktop-node-1") } returns null

        sessionManager.playNow(trackA)
        advanceUntilIdle()

        val lastCmd = sessionManager.sessionState.value.lastRemoteCommand
        assertNotNull(lastCmd)
        assertEquals(RemoteCommandStatus.DISCONNECTED, lastCmd!!.status)
    }

    @Test
    fun remotePlayback_setRepeatMode_nonOptimistic_onlyMutatesOnConfirmation() = runTest(testDispatcher) {
        val client = mockk<LinkClient>(relaxed = true)
        every { connectionManager.getClient("desktop-node-1") } returns client
        every { client.baseUrl } returns "http://192.168.1.50:8400"
        every { client.deviceToken } returns "valid-token"

        // Simulate failure on server
        coEvery { client.setQueueRepeatMode("one") } returns Result.failure(Exception("Remote error"))

        sessionManager.attachRemote(remoteEndpoint) { _, _ -> }
        assertEquals(0, sessionManager.sessionState.value.repeatMode)

        sessionManager.setRepeatMode(1)
        advanceUntilIdle()

        // Local state should NOT have been optimistically changed to 1
        assertEquals(0, sessionManager.sessionState.value.repeatMode)
        assertEquals(RemoteCommandStatus.REJECTED, sessionManager.sessionState.value.lastRemoteCommand?.status)

        // Now simulate success
        coEvery { client.setQueueRepeatMode("all") } returns Result.success(org.michimusic.link.dto.QueueRepeatResponseDto(repeatMode = "all"))
        sessionManager.setRepeatMode(2)
        advanceUntilIdle()

        assertEquals(2, sessionManager.sessionState.value.repeatMode)
        assertEquals(RemoteCommandStatus.CONFIRMED, sessionManager.sessionState.value.lastRemoteCommand?.status)
    }

    @Test
    fun remotePlayback_staleEndpointResponse_doesNotMutateNewEndpoint() = runTest(testDispatcher) {
        val client = mockk<LinkClient>(relaxed = true)
        every { connectionManager.getClient("desktop-node-1") } returns client
        every { client.baseUrl } returns "http://192.168.1.50:8400"
        every { client.deviceToken } returns "valid-token"

        // Setup delay so we can switch endpoints before response applies
        coEvery { client.sendPlaybackCommand("play_track", "trk-a") } coAnswers {
            kotlinx.coroutines.delay(50)
            Result.success("OK")
        }
        coEvery { client.getPlaybackState() } returns Result.success(PlaybackStateDto(playing = true))
        coEvery { client.getQueue() } returns Result.success(
            QueueDto(
                tracks = listOf(QueueTrackDto(trackId = "trk-a", title = "Track A")),
                currentIndex = 0,
            )
        )

        sessionManager.attachRemote(remoteEndpoint) { _, _ -> }
        sessionManager.playNow(trackA)

        // Switch active endpoint to LocalPhone while request is in flight
        sessionManager.selectLocalEndpoint()
        assertEquals(PlaybackEndpoint.LocalPhone.id, sessionManager.sessionState.value.activeEndpoint.id)

        advanceUntilIdle()

        // Active endpoint must remain LocalPhone, and stale command response must NOT overwrite it
        assertEquals(PlaybackEndpoint.LocalPhone.id, sessionManager.sessionState.value.activeEndpoint.id)
        assertFalse(sessionManager.sessionState.value.isRemoteActive)
    }
}
