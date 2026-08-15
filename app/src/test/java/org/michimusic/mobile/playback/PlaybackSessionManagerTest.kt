package org.michimusic.mobile.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.mockk.mockk
import io.mockk.every
import io.mockk.coEvery
import io.mockk.verify
import io.mockk.coVerify

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionManagerTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testEndpointTypes() {
        val endpoint = PlaybackEndpoint.LocalPhone
        assertTrue(endpoint.isLocal)
        assertEquals("Este teléfono", endpoint.name)
    }

    @Test
    fun handoffToRemote_transfersQueueAndSelectsRemote() {
        val linkClient = mockk<org.michimusic.link.LinkClient>(relaxed = true)
        val connectionManager = object : org.michimusic.link.ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): org.michimusic.link.LinkClient? = if (deviceId == "remote_1") linkClient else null
        }
        connectionManager.updateState("remote_1", org.michimusic.core.models.SyncConnectionState.CONNECTED)
        val audioController = mockk<org.michimusic.player.AudioController>(relaxed = true)
        val dummyStateFlow = kotlinx.coroutines.flow.MutableStateFlow(org.michimusic.player.PlayerState())
        every { audioController.state } returns dummyStateFlow
        
        val linkDiscovery = mockk<org.michimusic.link.LinkDiscovery>(relaxed = true)
        every { linkDiscovery.peers } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
        
        val registry = mockk<org.michimusic.link.PairedDeviceRegistry>(relaxed = true)
        every { registry.getAllDevices() } returns emptyList()
        
        coEvery { linkClient.search(any()) } returns Result.success(listOf(org.michimusic.link.dto.TrackResponseDto(id = "srv_t1", title = "Test")))
        coEvery { linkClient.transferQueue(any()) } returns Result.success(org.michimusic.link.dto.QueueTransferResponse(success = true, sessionId = "s1"))

        val manager = PlaybackSessionManager(
            audioController = audioController,
            connectionManager = connectionManager,
            linkDiscovery = linkDiscovery,
            registry = registry
        )
        
        val track = org.michimusic.core.models.Track("t1", "Test", "Artist", source = org.michimusic.core.models.TrackSource.LOCAL)
        dummyStateFlow.value = dummyStateFlow.value.copy(queue = listOf(track))
        
        testDispatcher.scheduler.advanceUntilIdle() // let init flows process
        manager.selectLocalEndpoint()
        
        val endpoint = PlaybackEndpoint("remote_1", "Remote", EndpointType.DESKTOP_PLAYER, isLocal = false)
        
        var successResult = false
        manager.handoffTo(endpoint) { success, _ ->
            successResult = success
        }
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        coVerify { linkClient.transferQueue(any()) }
        verify { audioController.pause() }
        
        assertTrue("Handoff debe ser exitoso", successResult)
        assertEquals("Debe marcarse como remoto activo", endpoint, manager.sessionState.value.activeEndpoint)
        assertTrue(manager.sessionState.value.isRemoteActive)
    }

    @Test
    fun attachRemote_activatesRemoteWithoutTransfer() {
        val linkClient = mockk<org.michimusic.link.LinkClient>(relaxed = true)
        val connectionManager = object : org.michimusic.link.ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): org.michimusic.link.LinkClient? = if (deviceId == "remote_2") linkClient else null
        }
        connectionManager.updateState("remote_2", org.michimusic.core.models.SyncConnectionState.CONNECTED)
        val audioController = mockk<org.michimusic.player.AudioController>(relaxed = true)
        val dummyStateFlow = kotlinx.coroutines.flow.MutableStateFlow(org.michimusic.player.PlayerState())
        every { audioController.state } returns dummyStateFlow
        
        val linkDiscovery = mockk<org.michimusic.link.LinkDiscovery>(relaxed = true)
        every { linkDiscovery.peers } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
        
        val registry = mockk<org.michimusic.link.PairedDeviceRegistry>(relaxed = true)
        every { registry.getAllDevices() } returns emptyList()

        val manager = PlaybackSessionManager(
            audioController = audioController,
            connectionManager = connectionManager,
            linkDiscovery = linkDiscovery,
            registry = registry
        )
        
        val endpoint = PlaybackEndpoint("remote_2", "Remote Node", EndpointType.SERVER, isLocal = false)
        
        var successResult = false
        manager.attachRemote(endpoint) { success, _ ->
            successResult = success
        }
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertTrue("Attach debe ser exitoso", successResult)
        assertEquals("Debe cambiar el endpoint activo", endpoint, manager.sessionState.value.activeEndpoint)
        assertTrue(manager.sessionState.value.isRemoteActive)
        
        verify(exactly = 0) { audioController.pause() }
    }

    @Test
    fun handoffToReceiver_doesNotTransferQueue() {
        val linkClient = mockk<org.michimusic.link.LinkClient>(relaxed = true)
        every { linkClient.baseUrl } returns "http://192.168.1.50:5252"
        coEvery { linkClient.getServerInfo() } returns Result.success(org.michimusic.link.dto.ServerInfoDto(server = "stream_1"))
        coEvery { linkClient.createReceiverLiteSession(any()) } returns Result.success(
            org.michimusic.link.dto.ReceiverSessionCreateResponse(
                sessionId = "s1",
                sessionToken = "tok",
                leaseSeconds = 30,
                effective = org.michimusic.link.dto.ReceiverSessionEffectiveDto(streamPort = 5004)
            )
        )
        val connectionManager = object : org.michimusic.link.ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): org.michimusic.link.LinkClient? = if (deviceId == "stream_1") linkClient else null
        }
        connectionManager.updateState("stream_1", org.michimusic.core.models.SyncConnectionState.CONNECTED)
        val audioController = mockk<org.michimusic.player.AudioController>(relaxed = true)
        val dummyStateFlow = kotlinx.coroutines.flow.MutableStateFlow(org.michimusic.player.PlayerState())
        every { audioController.state } returns dummyStateFlow
        
        val linkDiscovery = mockk<org.michimusic.link.LinkDiscovery>(relaxed = true)
        every { linkDiscovery.peers } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
        
        val registry = mockk<org.michimusic.link.PairedDeviceRegistry>(relaxed = true)
        every { registry.getAllDevices() } returns emptyList()

        val manager = PlaybackSessionManager(
            audioController = audioController,
            connectionManager = connectionManager,
            linkDiscovery = linkDiscovery,
            registry = registry
        )
        
        val receiverEndpoint = PlaybackEndpoint("stream_1", "Michi Stream Receiver", EndpointType.STREAM_RECEIVER, isLocal = false, capabilities = setOf("PLAYBACK", "AUDIO_OUTPUT"))
        
        var successResult = false
        manager.handoffTo(receiverEndpoint) { success, _ ->
            successResult = success
        }
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        coVerify(exactly = 0) { linkClient.transferQueue(any()) }
        assertTrue("Handoff a receiver debe ser exitoso", successResult)
        assertEquals(receiverEndpoint, manager.sessionState.value.activeEndpoint)
    }

    @Test
    fun authoritativeControls_localMode() {
        val audioController = mockk<org.michimusic.player.AudioController>(relaxed = true)
        val dummyStateFlow = kotlinx.coroutines.flow.MutableStateFlow(org.michimusic.player.PlayerState())
        every { audioController.state } returns dummyStateFlow
        
        val connectionManager = mockk<org.michimusic.link.ConnectionManager>(relaxed = true)
        val linkDiscovery = mockk<org.michimusic.link.LinkDiscovery>(relaxed = true)
        val registry = mockk<org.michimusic.link.PairedDeviceRegistry>(relaxed = true)

        val manager = PlaybackSessionManager(
            audioController = audioController,
            connectionManager = connectionManager,
            linkDiscovery = linkDiscovery,
            registry = registry
        )
        
        manager.setRepeatMode(1)
        assertEquals(1, manager.sessionState.value.repeatMode)
        verify { audioController.setRepeatMode(1) }
        
        manager.setShuffleMode(1)
        assertEquals(1, manager.sessionState.value.shuffleMode)
        verify { audioController.setShuffleMode(true) }
        
        manager.skipToQueueIndex(3)
        verify { audioController.skipToQueueIndex(3) }
        
        manager.clearQueue()
        verify { audioController.clearQueue() }
    }
}
