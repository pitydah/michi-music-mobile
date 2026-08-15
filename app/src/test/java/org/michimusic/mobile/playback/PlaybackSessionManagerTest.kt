package org.michimusic.mobile.playback

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.PcmFormat
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.link.ConnectionManager
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkDiscovery
import org.michimusic.link.PairedDeviceRegistry
import org.michimusic.link.dto.AudioCapabilitiesDto
import org.michimusic.link.dto.QueueTransferResponse
import org.michimusic.link.dto.ReceiverSessionCreateResponse
import org.michimusic.link.dto.ReceiverSessionEffectiveDto
import org.michimusic.link.dto.ServerInfoDto
import org.michimusic.link.dto.TrackResponseDto
import org.michimusic.player.AudioController
import org.michimusic.player.PlayerDependencies
import org.michimusic.player.PlayerState

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionManagerTest {

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
    fun testEndpointTypes() {
        val endpoint = PlaybackEndpoint.LocalPhone
        assertTrue(endpoint.isLocal)
        assertEquals("Este teléfono", endpoint.name)
    }

    @Test
    fun handoffToRemote_transfersQueueAndSelectsRemote() {
        val linkClient = mockk<LinkClient>(relaxed = true)
        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "remote_1") linkClient else null
        }
        connectionManager.updateState("remote_1", SyncConnectionState.CONNECTED)
        val audioController = mockk<AudioController>(relaxed = true)
        val dummyStateFlow = MutableStateFlow(PlayerState())
        every { audioController.state } returns dummyStateFlow
        
        val linkDiscovery = mockk<LinkDiscovery>(relaxed = true)
        every { linkDiscovery.peers } returns MutableStateFlow(emptyMap())
        
        val registry = mockk<PairedDeviceRegistry>(relaxed = true)
        every { registry.getAllDevices() } returns emptyList()
        
        coEvery { linkClient.search(any()) } returns Result.success(listOf(TrackResponseDto(id = "srv_t1", title = "Test")))
        coEvery { linkClient.transferQueue(any()) } returns Result.success(QueueTransferResponse(success = true, sessionId = "s1"))

        val manager = PlaybackSessionManager(
            audioController = audioController,
            connectionManager = connectionManager,
            linkDiscovery = linkDiscovery,
            registry = registry
        )
        
        val track = Track("t1", "Test", "Artist", source = TrackSource.LOCAL)
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
        val linkClient = mockk<LinkClient>(relaxed = true)
        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "remote_2") linkClient else null
        }
        connectionManager.updateState("remote_2", SyncConnectionState.CONNECTED)
        val audioController = mockk<AudioController>(relaxed = true)
        val dummyStateFlow = MutableStateFlow(PlayerState())
        every { audioController.state } returns dummyStateFlow
        
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
    fun handoffToReceiver_abortsWhenFormatIsUnknown() {
        val linkClient = mockk<LinkClient>(relaxed = true)
        every { linkClient.baseUrl } returns "http://192.168.1.50:8400"
        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "stream_1") linkClient else null
        }
        connectionManager.updateState("stream_1", SyncConnectionState.CONNECTED)
        val audioController = mockk<AudioController>(relaxed = true)
        val dummyStateFlow = MutableStateFlow(PlayerState())
        every { audioController.state } returns dummyStateFlow
        
        val linkDiscovery = mockk<LinkDiscovery>(relaxed = true)
        every { linkDiscovery.peers } returns MutableStateFlow(emptyMap())
        
        val registry = mockk<PairedDeviceRegistry>(relaxed = true)
        every { registry.getAllDevices() } returns emptyList()

        // Format is UNKNOWN (null)
        PlayerDependencies.mockPcmFormatForTesting = null

        val manager = PlaybackSessionManager(
            audioController = audioController,
            connectionManager = connectionManager,
            linkDiscovery = linkDiscovery,
            registry = registry
        )
        
        val receiverEndpoint = PlaybackEndpoint("stream_1", "Michi Stream Receiver", EndpointType.STREAM_RECEIVER, isLocal = false, capabilities = setOf("PLAYBACK", "AUDIO_OUTPUT"))
        
        var successResult = true
        var message = ""
        manager.handoffTo(receiverEndpoint) { success, msg ->
            successResult = success
            message = msg
        }
        
        testDispatcher.scheduler.advanceUntilIdle()
        
        assertFalse("Handoff debe fallar si el formato es UNKNOWN", successResult)
        assertTrue(message.contains("Esperando formato de audio"))
        coVerify(exactly = 0) { linkClient.createReceiverLiteSession(any()) }
    }

    @Test
    fun handoffToReceiver_doesNotTransferQueue() {
        PlayerDependencies.mockPcmFormatForTesting = PcmFormat(sampleRate = 48000, channels = 2, bitDepth = 16, codec = "pcm_s16le")

        val linkClient = mockk<LinkClient>(relaxed = true)
        every { linkClient.baseUrl } returns "http://192.168.1.50:8400"
        coEvery { linkClient.getServerInfo() } returns Result.success(
            ServerInfoDto(
                server = "stream_1",
                audio = AudioCapabilitiesDto(
                    transports = listOf("rtp_udp"),
                    codecs = listOf("pcm_s16le"),
                    sampleRates = listOf(48000),
                    bitDepths = listOf(16),
                    channels = listOf(2),
                    packetMs = listOf(10),
                    payloadTypes = listOf(97)
                )
            )
        )
        coEvery { linkClient.createReceiverLiteSession(any()) } returns Result.success(
            ReceiverSessionCreateResponse(
                sessionId = "s1",
                sessionToken = "tok",
                leaseSeconds = 30,
                effective = ReceiverSessionEffectiveDto(
                    transport = "rtp_udp",
                    codec = "pcm_s16le",
                    sampleRate = 48000,
                    bitDepth = 16,
                    channels = 2,
                    packetMs = 10,
                    bufferMs = 120,
                    payloadType = 97,
                    ssrc = 12345L,
                    streamPort = 5004,
                    volume = 70
                )
            )
        )
        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "stream_1") linkClient else null
        }
        connectionManager.updateState("stream_1", SyncConnectionState.CONNECTED)
        val audioController = mockk<AudioController>(relaxed = true)
        val dummyStateFlow = MutableStateFlow(PlayerState())
        every { audioController.state } returns dummyStateFlow
        
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
    fun dynamicFormatChange_duringStreamRenegotiatesReceiverSession() {
        val formatFlow = MutableStateFlow<PcmFormat?>(PcmFormat(sampleRate = 44100, channels = 2, bitDepth = 16, codec = "pcm_s16le"))
        PlayerDependencies.mockPcmFormatFlow = formatFlow
        PlayerDependencies.mockPcmFormatForTesting = formatFlow.value

        val linkClient = mockk<LinkClient>(relaxed = true)
        every { linkClient.baseUrl } returns "http://192.168.1.50:8400"
        coEvery { linkClient.getServerInfo() } returns Result.success(
            ServerInfoDto(
                server = "stream_1",
                audio = AudioCapabilitiesDto(
                    transports = listOf("rtp_udp"),
                    codecs = listOf("pcm_s16le"),
                    sampleRates = listOf(44100, 48000),
                    bitDepths = listOf(16),
                    channels = listOf(2),
                    packetMs = listOf(10),
                    payloadTypes = listOf(97)
                )
            )
        )
        coEvery { linkClient.createReceiverLiteSession(match { it.sampleRate == 44100 }) } returns Result.success(
            ReceiverSessionCreateResponse(
                sessionId = "s_44100",
                sessionToken = "tok_44100",
                leaseSeconds = 30,
                effective = ReceiverSessionEffectiveDto(
                    transport = "rtp_udp",
                    codec = "pcm_s16le",
                    sampleRate = 44100,
                    bitDepth = 16,
                    channels = 2,
                    packetMs = 10,
                    bufferMs = 120,
                    payloadType = 97,
                    ssrc = 1111L,
                    streamPort = 5004,
                    volume = 70
                )
            )
        )
        coEvery { linkClient.createReceiverLiteSession(match { it.sampleRate == 48000 }) } returns Result.success(
            ReceiverSessionCreateResponse(
                sessionId = "s_48000",
                sessionToken = "tok_48000",
                leaseSeconds = 30,
                effective = ReceiverSessionEffectiveDto(
                    transport = "rtp_udp",
                    codec = "pcm_s16le",
                    sampleRate = 48000,
                    bitDepth = 16,
                    channels = 2,
                    packetMs = 10,
                    bufferMs = 120,
                    payloadType = 97,
                    ssrc = 2222L,
                    streamPort = 5004,
                    volume = 70
                )
            )
        )
        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "stream_1") linkClient else null
        }
        connectionManager.updateState("stream_1", SyncConnectionState.CONNECTED)
        val audioController = mockk<AudioController>(relaxed = true)
        val dummyStateFlow = MutableStateFlow(PlayerState())
        every { audioController.state } returns dummyStateFlow
        
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
        
        val receiverEndpoint = PlaybackEndpoint("stream_1", "Michi Stream Receiver", EndpointType.STREAM_RECEIVER, isLocal = false, capabilities = setOf("PLAYBACK", "AUDIO_OUTPUT"))
        
        manager.handoffTo(receiverEndpoint) { _, _ -> }
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify initial 44.1 kHz session was established
        coVerify(exactly = 1) { linkClient.createReceiverLiteSession(match { it.sampleRate == 44100 }) }

        // Simulate next track starting with 48 kHz format
        formatFlow.value = PcmFormat(sampleRate = 48000, channels = 2, bitDepth = 16, codec = "pcm_s16le")
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify old session was deleted and new 48 kHz session was created
        coVerify(atLeast = 1) { linkClient.deleteReceiverLiteSession("tok_44100") }
        coVerify(exactly = 1) { linkClient.createReceiverLiteSession(match { it.sampleRate == 48000 }) }
    }

    @Test
    fun authoritativeControls_localMode() {
        val audioController = mockk<AudioController>(relaxed = true)
        val dummyStateFlow = MutableStateFlow(PlayerState())
        every { audioController.state } returns dummyStateFlow
        
        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val linkDiscovery = mockk<LinkDiscovery>(relaxed = true)
        val registry = mockk<PairedDeviceRegistry>(relaxed = true)

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
