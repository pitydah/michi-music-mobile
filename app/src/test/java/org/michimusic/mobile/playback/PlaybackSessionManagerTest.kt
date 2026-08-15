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
}
