package org.michimusic.mobile.playback

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.link.ConnectionManager
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkDiscovery
import org.michimusic.link.PairedDeviceRegistry
import org.michimusic.link.dto.PlaybackStateDto
import org.michimusic.link.dto.QueueDto
import org.michimusic.link.dto.QueueTrackDto
import org.michimusic.link.dto.QueueTransferRequest
import org.michimusic.link.dto.QueueTransferResponse
import org.michimusic.player.AudioController
import org.michimusic.player.PlayerDependencies
import org.michimusic.player.PlayerState

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteQueueAuthorizationTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        PlayerDependencies.resetTestingOverrides()
    }

    @After
    fun tearDown() {
        PlayerDependencies.resetTestingOverrides()
        Dispatchers.resetMain()
    }

    @Test
    fun evaluatePreflight_whenProposedEmpty_returnsRejected() {
        val decision = RemoteQueuePreflightPolicy.evaluate(
            destinationId = "rem_1",
            destinationName = "Michi Desktop",
            remoteQueueSize = 5,
            remoteActiveTrackTitle = "Active Track",
            remotePlaying = true,
            proposedTracks = emptyList(),
            proposedStartIndex = 0,
        )
        assertTrue(decision is RemoteQueuePreflightDecision.Rejected)
    }

    @Test
    fun evaluatePreflight_whenRemoteIdleAndCleanQueue_returnsProceedDirectly() {
        val track = Track("srv_1", "Track 1", "Artist", source = TrackSource.STREAMING)
        val decision = RemoteQueuePreflightPolicy.evaluate(
            destinationId = "rem_1",
            destinationName = "Michi Desktop",
            remoteQueueSize = 0,
            remoteActiveTrackTitle = null,
            remotePlaying = false,
            proposedTracks = listOf(track),
            proposedStartIndex = 0,
        )
        assertTrue(decision is RemoteQueuePreflightDecision.ProceedDirectly)
        assertEquals(RemoteQueueAction.REPLACE, (decision as RemoteQueuePreflightDecision.ProceedDirectly).action)
    }

    @Test
    fun evaluatePreflight_whenRemoteHasQueue_requiresConfirmation() {
        val track = Track("srv_1", "Track 1", "Artist", source = TrackSource.STREAMING)
        val decision = RemoteQueuePreflightPolicy.evaluate(
            destinationId = "rem_1",
            destinationName = "Michi Desktop",
            remoteQueueSize = 3,
            remoteActiveTrackTitle = "Playing Song",
            remotePlaying = false,
            proposedTracks = listOf(track),
            proposedStartIndex = 0,
        )
        assertTrue(decision is RemoteQueuePreflightDecision.ConfirmationRequired)
        val conf = decision as RemoteQueuePreflightDecision.ConfirmationRequired
        assertEquals("rem_1", conf.destinationId)
        assertEquals("Michi Desktop", conf.destinationName)
        assertEquals(3, conf.remoteQueueSize)
        assertEquals("Playing Song", conf.remoteActiveTrackTitle)
    }

    @Test
    fun evaluatePreflight_whenRemoteIsPlaying_requiresConfirmationEvenIfQueueZero() {
        val track = Track("srv_1", "Track 1", "Artist", source = TrackSource.STREAMING)
        val decision = RemoteQueuePreflightPolicy.evaluate(
            destinationId = "rem_1",
            destinationName = "Michi Desktop",
            remoteQueueSize = 0,
            remoteActiveTrackTitle = "Stream Track",
            remotePlaying = true,
            proposedTracks = listOf(track),
            proposedStartIndex = 0,
        )
        assertTrue(decision is RemoteQueuePreflightDecision.ConfirmationRequired)
    }

    @Test
    fun requestQueuePlayback_whenRemoteQueueActive_blocksDispatchUntilConfirmed() = runTest {
        val linkClient = mockk<LinkClient>(relaxed = true)
        val track = Track("srv_1", "Track 1", "Artist", filepath = "http://srv/1", source = TrackSource.STREAMING)

        coEvery { linkClient.transferQueue(any()) } returns Result.success(QueueTransferResponse(success = true))
        coEvery { linkClient.sendPlaybackCommand("play") } returns Result.success("ok")

        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "remote_1") linkClient else null
        }
        connectionManager.updateState("remote_1", SyncConnectionState.CONNECTED)
        val audioController = mockk<AudioController>(relaxed = true)
        every { audioController.state } returns MutableStateFlow(PlayerState())
        val linkDiscovery = mockk<LinkDiscovery>(relaxed = true)
        every { linkDiscovery.peers } returns MutableStateFlow(emptyMap())
        val registry = mockk<PairedDeviceRegistry>(relaxed = true)
        every { registry.getAllDevices() } returns emptyList()

        val manager = PlaybackSessionManager(
            audioController = audioController,
            connectionManager = connectionManager,
            linkDiscovery = linkDiscovery,
            registry = registry,
        )

        val targetEp = PlaybackEndpoint("remote_1", "Michi Desktop", EndpointType.DESKTOP_PLAYER, isLocal = false, capabilities = setOf("PLAYBACK", "REMOTE_CONTROL"))
        manager.attachRemote(targetEp) { _, _ -> }

        // Simulate existing remote queue
        val existingTrack = Track("srv_0", "Remote Existing", "Artist", source = TrackSource.STREAMING)
        val sessionStateField = PlaybackSessionManager::class.java.getDeclaredField("_sessionState")
        sessionStateField.isAccessible = true
        val stateFlow = sessionStateField.get(manager) as MutableStateFlow<PlaybackSessionState>
        stateFlow.value = stateFlow.value.copy(
            queue = listOf(existingTrack),
            isPlaying = true,
            currentTrack = existingTrack,
        )

        // Request queue playback without pre-approval
        val outcome = manager.requestQueuePlayback(listOf(track), 0)

        // Must NOT dispatch to network immediately
        coVerify(exactly = 0) { linkClient.transferQueue(any()) }
        assertTrue("Expected PendingConfirmation outcome, got $outcome", outcome is QueuePlaybackOutcome.PendingConfirmation)
        assertNotNull(manager.pendingQueueConfirmation.value)

        // Test cancel resolution
        manager.resolvePendingQueueConfirmation(RemoteQueueAction.CANCEL)
        advanceUntilIdle()
        assertNull(manager.pendingQueueConfirmation.value)
        coVerify(exactly = 0) { linkClient.transferQueue(any()) }
    }

    @Test
    fun requestQueuePlayback_whenConfirmedReplace_dispatchesReplaceQueue() = runTest {
        val linkClient = mockk<LinkClient>(relaxed = true)
        val track = Track("srv_1", "Track 1", "Artist", filepath = "http://srv/1", source = TrackSource.STREAMING)
        val trackDto = QueueTrackDto(trackId = "srv_1", title = "Track 1", artist = "Artist", duration = 120_000L)
        val playStateDto = PlaybackStateDto(playing = true, queueIndex = 0, trackId = "srv_1", position = 0L, duration = 120_000L)
        val queueDto = QueueDto(tracks = listOf(trackDto), currentIndex = 0)

        coEvery { linkClient.transferQueue(any()) } returns Result.success(QueueTransferResponse(success = true))
        coEvery { linkClient.sendPlaybackCommand("play") } returns Result.success("ok")
        coEvery { linkClient.getPlaybackState() } returns Result.success(playStateDto)
        coEvery { linkClient.getQueue() } returns Result.success(queueDto)

        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "remote_1") linkClient else null
        }
        connectionManager.updateState("remote_1", SyncConnectionState.CONNECTED)
        val audioController = mockk<AudioController>(relaxed = true)
        every { audioController.state } returns MutableStateFlow(PlayerState())
        val linkDiscovery = mockk<LinkDiscovery>(relaxed = true)
        every { linkDiscovery.peers } returns MutableStateFlow(emptyMap())
        val registry = mockk<PairedDeviceRegistry>(relaxed = true)
        every { registry.getAllDevices() } returns emptyList()

        val manager = PlaybackSessionManager(
            audioController = audioController,
            connectionManager = connectionManager,
            linkDiscovery = linkDiscovery,
            registry = registry,
        )

        val targetEp = PlaybackEndpoint("remote_1", "Michi Desktop", EndpointType.DESKTOP_PLAYER, isLocal = false, capabilities = setOf("PLAYBACK", "REMOTE_CONTROL"))
        manager.attachRemote(targetEp) { _, _ -> }

        // Request with explicit REPLACE pre-approval
        val outcome = manager.requestQueuePlayback(listOf(track), 0, confirmedAction = RemoteQueueAction.REPLACE)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            linkClient.transferQueue(
                match<QueueTransferRequest> {
                    it.source == "mobile_queue" && it.trackIds == listOf("srv_1")
                }
            )
        }
        assertTrue("Expected Confirmed outcome, got $outcome", outcome is QueuePlaybackOutcome.Confirmed)
    }

    @Test
    fun requestQueuePlayback_whenConfirmedAppend_dispatchesAppendedQueue() = runTest {
        val linkClient = mockk<LinkClient>(relaxed = true)
        val newTrack = Track("srv_new", "New Track", "Artist", filepath = "http://srv/new", source = TrackSource.STREAMING)
        val oldTrack = Track("srv_old", "Old Track", "Artist", filepath = "http://srv/old", source = TrackSource.STREAMING)

        val trackDtoOld = QueueTrackDto(trackId = "srv_old", title = "Old Track", artist = "Artist", duration = 120_000L)
        val trackDtoNew = QueueTrackDto(trackId = "srv_new", title = "New Track", artist = "Artist", duration = 150_000L)
        val playStateDto = PlaybackStateDto(playing = true, queueIndex = 0, trackId = "srv_old", position = 40_000L, duration = 120_000L)
        val queueDto = QueueDto(tracks = listOf(trackDtoOld, trackDtoNew), currentIndex = 0)

        coEvery { linkClient.transferQueue(any()) } returns Result.success(QueueTransferResponse(success = true))
        coEvery { linkClient.getPlaybackState() } returns Result.success(playStateDto)
        coEvery { linkClient.getQueue() } returns Result.success(queueDto)

        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "remote_1") linkClient else null
        }
        connectionManager.updateState("remote_1", SyncConnectionState.CONNECTED)
        val audioController = mockk<AudioController>(relaxed = true)
        every { audioController.state } returns MutableStateFlow(PlayerState())
        val linkDiscovery = mockk<LinkDiscovery>(relaxed = true)
        every { linkDiscovery.peers } returns MutableStateFlow(emptyMap())
        val registry = mockk<PairedDeviceRegistry>(relaxed = true)
        every { registry.getAllDevices() } returns emptyList()

        val manager = PlaybackSessionManager(
            audioController = audioController,
            connectionManager = connectionManager,
            linkDiscovery = linkDiscovery,
            registry = registry,
        )

        val targetEp = PlaybackEndpoint("remote_1", "Michi Desktop", EndpointType.DESKTOP_PLAYER, isLocal = false, capabilities = setOf("PLAYBACK", "REMOTE_CONTROL"))
        manager.attachRemote(targetEp) { _, _ -> }

        // Populate session state with old track
        val sessionStateField = PlaybackSessionManager::class.java.getDeclaredField("_sessionState")
        sessionStateField.isAccessible = true
        val stateFlow = sessionStateField.get(manager) as MutableStateFlow<PlaybackSessionState>
        stateFlow.value = stateFlow.value.copy(
            queue = listOf(oldTrack),
            isPlaying = true,
            currentTrack = oldTrack,
            queueIndex = 0,
            position = 40_000L,
        )

        // Request with APPEND pre-approval
        val outcome = manager.requestQueuePlayback(listOf(newTrack), 0, confirmedAction = RemoteQueueAction.APPEND)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            linkClient.transferQueue(
                match<QueueTransferRequest> {
                    it.source == "mobile_queue_append" &&
                        it.trackIds == listOf("srv_old", "srv_new") &&
                        it.currentIndex == 0 &&
                        it.positionMs == 40_000L
                }
            )
        }
        assertTrue("Expected Confirmed outcome, got $outcome", outcome is QueuePlaybackOutcome.Confirmed)
    }
}
