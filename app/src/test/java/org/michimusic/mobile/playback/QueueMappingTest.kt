package org.michimusic.mobile.playback

import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.link.ConnectionManager
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkDiscovery
import org.michimusic.link.PairedDeviceRegistry
import org.michimusic.link.dto.QueueTransferResponse
import org.michimusic.link.dto.TrackResponseDto
import org.michimusic.player.AudioController
import org.michimusic.player.PlayerState

@OptIn(ExperimentalCoroutinesApi::class)
class QueueMappingTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun streamingTrackUsesExistingIdWithoutSearch() = runTest(testDispatcher) {
        val linkClient = mockk<LinkClient>(relaxed = true)
        every { linkClient.baseUrl } returns "http://192.168.1.100:8400"
        coEvery { linkClient.transferQueue(any()) } returns Result.success(
            QueueTransferResponse(success = true, sessionId = "s_1")
        )
        coEvery { linkClient.sendPlaybackCommand(any()) } returns Result.success("ok")

        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "server_1") linkClient else null
        }
        connectionManager.updateState("server_1", SyncConnectionState.CONNECTED)

        val streamingTrack = Track(
            id = "server_track_42",
            title = "Stairway to Heaven",
            artist = "Led Zeppelin",
            source = TrackSource.STREAMING
        )

        val audioController = mockk<AudioController>(relaxed = true)
        every { audioController.state } returns MutableStateFlow(
            PlayerState(queue = listOf(streamingTrack), queueIndex = 0, currentTrack = streamingTrack)
        )

        val linkDiscovery = mockk<LinkDiscovery>(relaxed = true)
        every { linkDiscovery.peers } returns MutableStateFlow(emptyMap())
        val registry = mockk<PairedDeviceRegistry>(relaxed = true)
        every { registry.getAllDevices() } returns emptyList()

        val manager = PlaybackSessionManager(
            audioController = audioController,
            connectionManager = connectionManager,
            linkDiscovery = linkDiscovery,
            registry = registry
        )

        val serverEndpoint = PlaybackEndpoint(
            id = "server_1",
            name = "Michi Server",
            type = EndpointType.SERVER,
            isLocal = false,
            capabilities = setOf("PLAYBACK", "REMOTE_CONTROL")
        )

        var handoffResult = false
        var message = ""
        manager.handoffTo(serverEndpoint) { success, msg ->
            handoffResult = success
            message = msg
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("Handoff debe tener éxito para track de streaming con ID", handoffResult)
        coVerify(exactly = 0) { linkClient.search(any()) }
        coVerify(exactly = 1) { linkClient.transferQueue(match { it.trackIds == listOf("server_track_42") }) }
    }

    @Test
    fun localTrackMapsToRemoteIdViaSearch() = runTest(testDispatcher) {
        val linkClient = mockk<LinkClient>(relaxed = true)
        every { linkClient.baseUrl } returns "http://192.168.1.100:8400"
        coEvery { linkClient.search("Hotel California") } returns Result.success(
            listOf(
                TrackResponseDto(
                    id = "remote_hotel_california",
                    title = "Hotel California",
                    artist = "Eagles",
                    duration = 390000
                )
            )
        )
        coEvery { linkClient.transferQueue(any()) } returns Result.success(
            QueueTransferResponse(success = true, sessionId = "s_1")
        )
        coEvery { linkClient.sendPlaybackCommand(any()) } returns Result.success("ok")

        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "server_1") linkClient else null
        }
        connectionManager.updateState("server_1", SyncConnectionState.CONNECTED)

        val localTrack = Track(
            id = "local_1",
            title = "Hotel California",
            artist = "Eagles",
            duration = 390000,
            source = TrackSource.LOCAL
        )

        val audioController = mockk<AudioController>(relaxed = true)
        every { audioController.state } returns MutableStateFlow(
            PlayerState(queue = listOf(localTrack), queueIndex = 0, currentTrack = localTrack)
        )

        val linkDiscovery = mockk<LinkDiscovery>(relaxed = true)
        every { linkDiscovery.peers } returns MutableStateFlow(emptyMap())
        val registry = mockk<PairedDeviceRegistry>(relaxed = true)
        every { registry.getAllDevices() } returns emptyList()

        val manager = PlaybackSessionManager(
            audioController = audioController,
            connectionManager = connectionManager,
            linkDiscovery = linkDiscovery,
            registry = registry
        )

        val serverEndpoint = PlaybackEndpoint(
            id = "server_1",
            name = "Michi Server",
            type = EndpointType.SERVER,
            isLocal = false,
            capabilities = setOf("PLAYBACK", "REMOTE_CONTROL")
        )

        var handoffResult = false
        manager.handoffTo(serverEndpoint) { success, _ -> handoffResult = success }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(handoffResult)
        coVerify(exactly = 1) { linkClient.search("Hotel California") }
        coVerify(exactly = 1) { linkClient.transferQueue(match { it.trackIds == listOf("remote_hotel_california") }) }
    }

    @Test
    fun mixedQueuePreservesOrderAndSizes() = runTest(testDispatcher) {
        val linkClient = mockk<LinkClient>(relaxed = true)
        every { linkClient.baseUrl } returns "http://192.168.1.100:8400"
        coEvery { linkClient.search("Track A") } returns Result.success(
            listOf(TrackResponseDto(id = "remote_A", title = "Track A"))
        )
        coEvery { linkClient.search("Track C") } returns Result.success(
            listOf(TrackResponseDto(id = "remote_C", title = "Track C"))
        )
        coEvery { linkClient.transferQueue(any()) } returns Result.success(
            QueueTransferResponse(success = true, sessionId = "s_1")
        )
        coEvery { linkClient.sendPlaybackCommand(any()) } returns Result.success("ok")

        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "server_1") linkClient else null
        }
        connectionManager.updateState("server_1", SyncConnectionState.CONNECTED)

        val queue = listOf(
            Track(id = "local_A", title = "Track A", source = TrackSource.LOCAL),
            Track(id = "streaming_B", title = "Track B", source = TrackSource.STREAMING),
            Track(id = "local_C", title = "Track C", source = TrackSource.LOCAL)
        )

        val audioController = mockk<AudioController>(relaxed = true)
        every { audioController.state } returns MutableStateFlow(
            PlayerState(queue = queue, queueIndex = 1, currentTrack = queue[1])
        )

        val linkDiscovery = mockk<LinkDiscovery>(relaxed = true)
        every { linkDiscovery.peers } returns MutableStateFlow(emptyMap())
        val registry = mockk<PairedDeviceRegistry>(relaxed = true)
        every { registry.getAllDevices() } returns emptyList()

        val manager = PlaybackSessionManager(
            audioController = audioController,
            connectionManager = connectionManager,
            linkDiscovery = linkDiscovery,
            registry = registry
        )

        val serverEndpoint = PlaybackEndpoint(
            id = "server_1",
            name = "Michi Server",
            type = EndpointType.SERVER,
            isLocal = false,
            capabilities = setOf("PLAYBACK", "REMOTE_CONTROL")
        )

        var handoffResult = false
        manager.handoffTo(serverEndpoint) { success, _ -> handoffResult = success }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(handoffResult)
        // Verify Track B was NOT searched
        coVerify(exactly = 0) { linkClient.search("Track B") }
        // Verify transferred queue in exact order
        coVerify(exactly = 1) {
            linkClient.transferQueue(match {
                it.trackIds == listOf("remote_A", "streaming_B", "remote_C") &&
                it.currentIndex == 1
            })
        }
    }

    @Test
    fun unresolvableLocalTrackAbortsTransferWithoutModifyingRemoteQueue() = runTest(testDispatcher) {
        val linkClient = mockk<LinkClient>(relaxed = true)
        every { linkClient.baseUrl } returns "http://192.168.1.100:8400"
        coEvery { linkClient.search("Rare Vinyl Track") } returns Result.success(emptyList())

        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "server_1") linkClient else null
        }
        connectionManager.updateState("server_1", SyncConnectionState.CONNECTED)

        val queue = listOf(
            Track(id = "local_1", title = "Rare Vinyl Track", source = TrackSource.LOCAL)
        )

        val audioController = mockk<AudioController>(relaxed = true)
        every { audioController.state } returns MutableStateFlow(PlayerState(queue = queue, queueIndex = 0))

        val linkDiscovery = mockk<LinkDiscovery>(relaxed = true)
        every { linkDiscovery.peers } returns MutableStateFlow(emptyMap())
        val registry = mockk<PairedDeviceRegistry>(relaxed = true)
        every { registry.getAllDevices() } returns emptyList()

        val manager = PlaybackSessionManager(
            audioController = audioController,
            connectionManager = connectionManager,
            linkDiscovery = linkDiscovery,
            registry = registry
        )

        val serverEndpoint = PlaybackEndpoint(
            id = "server_1",
            name = "Michi Server",
            type = EndpointType.SERVER,
            isLocal = false,
            capabilities = setOf("PLAYBACK", "REMOTE_CONTROL")
        )

        var handoffResult = true
        var resultMessage = ""
        manager.handoffTo(serverEndpoint) { success, msg ->
            handoffResult = success
            resultMessage = msg
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("Handoff debe fallar si un track local no se encuentra", handoffResult)
        assertTrue(resultMessage.contains("algunas pistas locales no existen"))
        coVerify(exactly = 0) { linkClient.transferQueue(any()) }
    }
}
