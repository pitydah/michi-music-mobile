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
    fun dynamicFormatChange_toIncompatibleFormat_fallsBackToLocalCleanlyWithReason() {
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
        coEvery { linkClient.createReceiverLiteSession(any()) } returns Result.success(
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
                    ssrc = 1000L,
                    streamPort = 5004,
                    volume = 70
                )
            )
        )
        coEvery { linkClient.deleteReceiverLiteSession(any()) } returns Result.success(Unit)

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

        assertEquals(EndpointType.STREAM_RECEIVER, manager.sessionState.value.activeEndpoint.type)

        // Switch to incompatible 96 kHz track
        formatFlow.value = PcmFormat(sampleRate = 96000, channels = 2, bitDepth = 16, codec = "pcm_s16le")
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify fallback to local phone with structured error reason
        assertEquals(PlaybackEndpoint.LocalPhone, manager.sessionState.value.activeEndpoint)
        assertFalse(manager.sessionState.value.isRemoteActive)
        assertEquals(StreamErrorReason.FORMAT_UNSUPPORTED, manager.sessionState.value.lastSessionError)
        assertTrue(manager.sessionState.value.statusMessage?.contains("no es compatible") == true)
        coVerify(atLeast = 1) { linkClient.deleteReceiverLiteSession("tok_44100") }
    }

    @Test
    fun dynamicFormatChange_createSessionFailure_tearsDownCleanlyWithReason() {
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
        // First session succeeds, subsequent renegotiation call fails
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
                    ssrc = 1000L,
                    streamPort = 5004,
                    volume = 70
                )
            )
        )
        coEvery { linkClient.createReceiverLiteSession(match { it.sampleRate == 48000 }) } returns Result.failure(
            Exception("Receiver overloaded")
        )
        coEvery { linkClient.deleteReceiverLiteSession(any()) } returns Result.success(Unit)

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

        assertEquals(EndpointType.STREAM_RECEIVER, manager.sessionState.value.activeEndpoint.type)

        // Switch to 48 kHz track which fails at receiver creation
        formatFlow.value = PcmFormat(sampleRate = 48000, channels = 2, bitDepth = 16, codec = "pcm_s16le")
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify fallback to local phone with structured error reason
        assertEquals(PlaybackEndpoint.LocalPhone, manager.sessionState.value.activeEndpoint)
        assertFalse(manager.sessionState.value.isRemoteActive)
        assertEquals(StreamErrorReason.RECEIVER_NEGOTIATION_FAILED, manager.sessionState.value.lastSessionError)
        coVerify(atLeast = 1) { linkClient.deleteReceiverLiteSession("tok_44100") }
    }

    @Test
    fun dynamicFormatChange_renegotiationFailure_resumesPlaybackWhenWasPlayingTrue() {
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
                    ssrc = 1000L,
                    streamPort = 5004,
                    volume = 70
                )
            )
        )
        coEvery { linkClient.deleteReceiverLiteSession(any()) } returns Result.success(Unit)

        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "stream_1") linkClient else null
        }
        connectionManager.updateState("stream_1", SyncConnectionState.CONNECTED)
        val audioController = mockk<AudioController>(relaxed = true)
        val dummyStateFlow = MutableStateFlow(PlayerState(isPlaying = true))
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

        // Switch to incompatible 96 kHz track while wasPlaying was true
        formatFlow.value = PcmFormat(sampleRate = 96000, channels = 2, bitDepth = 16, codec = "pcm_s16le")
        testDispatcher.scheduler.advanceUntilIdle()

        // Playback must resume on local phone
        assertEquals(PlaybackEndpoint.LocalPhone, manager.sessionState.value.activeEndpoint)
        assertEquals(StreamErrorReason.FORMAT_UNSUPPORTED, manager.sessionState.value.lastSessionError)
        verify(atLeast = 1) { audioController.play() }
    }

    @Test
    fun dynamicFormatChange_renegotiationFailure_doesNotPlayWhenWasPlayingFalse() {
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
                    ssrc = 1000L,
                    streamPort = 5004,
                    volume = 70
                )
            )
        )
        coEvery { linkClient.deleteReceiverLiteSession(any()) } returns Result.success(Unit)

        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "stream_1") linkClient else null
        }
        connectionManager.updateState("stream_1", SyncConnectionState.CONNECTED)
        val audioController = mockk<AudioController>(relaxed = true)
        val dummyStateFlow = MutableStateFlow(PlayerState(isPlaying = false))
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

        // Switch to incompatible 96 kHz track while wasPlaying was false
        formatFlow.value = PcmFormat(sampleRate = 96000, channels = 2, bitDepth = 16, codec = "pcm_s16le")
        testDispatcher.scheduler.advanceUntilIdle()

        // Local phone must remain paused
        assertEquals(PlaybackEndpoint.LocalPhone, manager.sessionState.value.activeEndpoint)
        assertEquals(StreamErrorReason.FORMAT_UNSUPPORTED, manager.sessionState.value.lastSessionError)
        verify(exactly = 0) { audioController.play() }
    }

    @Test
    fun dynamicFormatChange_successfulRenegotiation_rebindsSessionAndResumes() {
        val formatFlow = MutableStateFlow<PcmFormat?>(PcmFormat(sampleRate = 44100, channels = 2, bitDepth = 16, codec = "pcm_s16le"))
        PlayerDependencies.mockPcmFormatFlow = formatFlow
        PlayerDependencies.mockPcmFormatForTesting = formatFlow.value
        PlayerDependencies.mockResumePcmStreamingResultForTesting = true

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
                    ssrc = 1000L,
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
                    ssrc = 1001L,
                    streamPort = 5004,
                    volume = 70
                )
            )
        )
        coEvery { linkClient.deleteReceiverLiteSession(any()) } returns Result.success(Unit)

        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "stream_1") linkClient else null
        }
        connectionManager.updateState("stream_1", SyncConnectionState.CONNECTED)
        val audioController = mockk<AudioController>(relaxed = true)
        val dummyStateFlow = MutableStateFlow(PlayerState(isPlaying = true))
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

        // Switch to compatible 48 kHz track while playing
        formatFlow.value = PcmFormat(sampleRate = 48000, channels = 2, bitDepth = 16, codec = "pcm_s16le")
        testDispatcher.scheduler.advanceUntilIdle()

        // Should remain on remote stream receiver and re-negotiated to 48kHz
        assertEquals(receiverEndpoint, manager.sessionState.value.activeEndpoint)
        assertEquals("s_48000", manager.activeReceiverSessionId)
        assertEquals("tok_48000", manager.activeReceiverSessionToken)
        verify(atLeast = 1) { audioController.play() }
    }

    @Test
    fun streamFailure_neverBlarePhoneSpeaker() {
        val linkClient = mockk<LinkClient>(relaxed = true)
        every { linkClient.baseUrl } returns "http://192.168.1.50:8400"
        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "stream_1") linkClient else null
        }
        connectionManager.updateState("stream_1", SyncConnectionState.CONNECTED)
        val audioController = mockk<AudioController>(relaxed = true)
        val dummyStateFlow = MutableStateFlow(PlayerState(isPlaying = true))
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

        // Simulate network loss error triggering local endpoint fallback
        manager.selectLocalEndpoint(
            reason = StreamErrorReason.NETWORK_LOST,
            message = "Se perdió la conexión con Michi Stream; reproducción pausada por seguridad",
            resumePlayback = false
        )

        assertEquals(PlaybackEndpoint.LocalPhone, manager.sessionState.value.activeEndpoint)
        assertEquals(StreamErrorReason.NETWORK_LOST, manager.sessionState.value.lastSessionError)
        assertFalse(manager.sessionState.value.isPlaying)
        // audioController.pause() MUST be called to protect speaker, and play() must NOT be called
        verify(exactly = 1) { audioController.pause() }
        verify(exactly = 0) { audioController.play() }
    }

    @Test
    fun fallback_cleansUpSessionResourcesAndSetsLocalPhone() {
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

        val manager = PlaybackSessionManager(
            audioController = audioController,
            connectionManager = connectionManager,
            linkDiscovery = linkDiscovery,
            registry = registry
        )

        manager.selectLocalEndpoint(
            reason = StreamErrorReason.NETWORK_LOST,
            message = "Conexión perdida con receptor"
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(PlaybackEndpoint.LocalPhone, manager.sessionState.value.activeEndpoint)
        assertFalse(manager.sessionState.value.isRemoteActive)
        assertEquals(StreamErrorReason.NETWORK_LOST, manager.sessionState.value.lastSessionError)
        assertEquals("Conexión perdida con receptor", manager.sessionState.value.statusMessage)
    }

    @Test
    fun authoritativeControls_localMode() {
        val audioController = mockk<AudioController>(relaxed = true)
        val dummyStateFlow = MutableStateFlow(PlayerState())
        every { audioController.state } returns dummyStateFlow
        
        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {}
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

        val testTrack = Track("t_local", "Local Title", "Artist")
        manager.playNow(testTrack)
        verify { audioController.playQueue(listOf(testTrack), 0) }

        manager.playNext(testTrack)
        verify { audioController.playNext(testTrack) }

        manager.addToQueue(testTrack)
        verify { audioController.addToQueue(testTrack) }

        manager.moveQueueItem(0, 1)
        verify { audioController.moveQueueItem(0, 1) }
    }

    @Test
    fun authoritativeControls_remoteMode_routesToClientWithoutTouchingAudioController() {
        val linkClient = mockk<LinkClient>(relaxed = true)
        val track = Track("track_123", "Remote Track", "Artist")
        val track2 = Track("track_456", "Second Track", "Artist")
        val queueDto1 = org.michimusic.link.dto.QueueDto(
            tracks = listOf(org.michimusic.link.dto.QueueTrackDto(track.id, track.title, track.artist, "", 100, "")),
            currentIndex = 0
        )
        val queueDto2 = org.michimusic.link.dto.QueueDto(
            tracks = listOf(
                org.michimusic.link.dto.QueueTrackDto(track.id, track.title, track.artist, "", 100, ""),
                org.michimusic.link.dto.QueueTrackDto(track2.id, track2.title, track2.artist, "", 120, "")
            ),
            currentIndex = 0
        )
        val playStateDto = org.michimusic.link.dto.PlaybackStateDto(
            state = "playing",
            playing = true,
            queueIndex = 0,
            position = 0L,
            duration = 100_000L
        )

        coEvery { linkClient.sendPlaybackCommand(any(), any()) } returns Result.success("ok")
        coEvery { linkClient.reorderQueue(any()) } returns Result.success(Unit)
        coEvery { linkClient.getPlaybackState() } returns Result.success(playStateDto)
        coEvery { linkClient.getQueue() } returns Result.success(queueDto1) andThen Result.success(queueDto1) andThen Result.success(queueDto2)

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

        val manager = PlaybackSessionManager(
            audioController = audioController,
            connectionManager = connectionManager,
            linkDiscovery = linkDiscovery,
            registry = registry
        )

        // Select remote endpoint
        val remoteEndpoint = PlaybackEndpoint("remote_1", "Remote Server", EndpointType.SERVER, isLocal = false)
        manager.attachRemote(remoteEndpoint) { _, _ -> }
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(manager.sessionState.value.isRemoteActive)

        // 1. playNow
        manager.playNow(track)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { linkClient.sendPlaybackCommand("play_track", "track_123") }
        verify(exactly = 0) { audioController.playQueue(any(), any()) }

        // 2. playNext
        manager.playNext(track)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { linkClient.sendPlaybackCommand("play_next", "track_123") }
        verify(exactly = 0) { audioController.playNext(any()) }
        assertTrue(manager.sessionState.value.queue.any { it.id == track.id })

        // 3. addToQueue
        manager.addToQueue(track2)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { linkClient.sendPlaybackCommand("add_to_queue", "track_456") }
        verify(exactly = 0) { audioController.addToQueue(any()) }
        assertTrue(manager.sessionState.value.queue.any { it.id == track2.id })

        // 4. moveQueueItem
        manager.moveQueueItem(0, 1)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { linkClient.reorderQueue(org.michimusic.link.dto.QueueReorderRequest(fromIndex = 0, toIndex = 1)) }
        verify(exactly = 0) { audioController.moveQueueItem(any(), any()) }

        // 5. playQueue in Remote Mode with Remote-playable track:
        val remotePlayableTrack = Track("srv_track_99", "Server Song", "Artist", source = TrackSource.STREAMING)
        manager.playQueue(listOf(remotePlayableTrack), 0)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { linkClient.transferQueue(match { it.trackIds == listOf("srv_track_99") && it.currentIndex == 0 }) }
        verify(exactly = 0) { audioController.playQueue(any(), any()) }

        // 6. playQueue in Remote Mode with local-only track:
        val localOnlyTrack = Track("local_100", "Local File", "Artist", source = TrackSource.LOCAL, filepath = "/sdcard/Music/test.mp3")
        manager.playQueue(listOf(localOnlyTrack), 0)
        testDispatcher.scheduler.advanceUntilIdle()
        verify(exactly = 0) { audioController.playQueue(any(), any()) }
        assertTrue(manager.sessionState.value.statusMessage?.contains("Pista local no disponible") == true)
    }

    @Test
    fun playQueue_localMode_invokesAudioControllerPlayQueue() {
        val audioController = mockk<AudioController>(relaxed = true)
        val dummyStateFlow = MutableStateFlow(PlayerState())
        every { audioController.state } returns dummyStateFlow
        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {}
        val linkDiscovery = mockk<LinkDiscovery>(relaxed = true)
        val registry = mockk<PairedDeviceRegistry>(relaxed = true)

        val manager = PlaybackSessionManager(
            audioController = audioController,
            connectionManager = connectionManager,
            linkDiscovery = linkDiscovery,
            registry = registry
        )

        val track = Track("track_local_1", "Local Song", "Artist")
        manager.playQueue(listOf(track), 0)
        verify(exactly = 1) { audioController.playQueue(listOf(track), 0) }
    }

    @Test
    fun handoffToLocal_whenPlaying_pausesRemoteAndPreservesPlayingIntent() {
        val linkClient = mockk<LinkClient>(relaxed = true)
        coEvery { linkClient.sendPlaybackCommand(any()) } returns Result.success("ok")
        coEvery { linkClient.sendPlaybackCommand(any(), any()) } returns Result.success("ok")
        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "remote_host_1") linkClient else null
        }
        connectionManager.updateState("remote_host_1", SyncConnectionState.CONNECTED)
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

        val remoteEndpoint = PlaybackEndpoint("remote_host_1", "Remote Desktop", EndpointType.DESKTOP_PLAYER, isLocal = false)
        manager.attachRemote(remoteEndpoint) { _, _ -> }
        testDispatcher.scheduler.advanceUntilIdle()

        // Set remote playing
        manager.play()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(manager.sessionState.value.isPlaying)

        manager.handoffTo(PlaybackEndpoint.LocalPhone) { success, _ ->
            assertTrue(success)
        }
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { linkClient.sendPlaybackCommand("pause") }
        assertEquals(PlaybackEndpoint.LocalPhone, manager.sessionState.value.activeEndpoint)
        assertFalse(manager.sessionState.value.isRemoteActive)
        assertEquals(PlaybackTransitionIntent.USER_HANDOFF_TO_PHONE, manager.sessionState.value.lastTransitionIntent)
        verify(atLeast = 1) { audioController.play() }
    }

    @Test
    fun playQueue_michStreamReceiver_routesThroughAudioControllerForBothLocalAndRemote() {
        val linkClient = mockk<LinkClient>(relaxed = true)
        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "stream_receiver_1") linkClient else null
        }
        connectionManager.updateState("stream_receiver_1", SyncConnectionState.CONNECTED)
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

        val streamEndpoint = PlaybackEndpoint(
            id = "stream_receiver_1",
            name = "Michi Stream Box",
            type = EndpointType.STREAM_RECEIVER,
            isLocal = false,
            capabilities = setOf("PLAYBACK", "AUDIO_OUTPUT")
        )
        manager.handoffTo(streamEndpoint) { _, _ -> }
        testDispatcher.scheduler.advanceUntilIdle()

        val localTrack = Track("loc_1", "Local Song", "Artist", source = TrackSource.LOCAL, filepath = "/path/test.flac")
        val streamTrack = Track("str_2", "Stream Song", "Artist", source = TrackSource.STREAMING)
        val queue = listOf(localTrack, streamTrack)

        manager.playQueue(queue, 0)
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 1) { audioController.playQueue(queue, 0) }
        coVerify(exactly = 0) { linkClient.transferQueue(any()) }
        coVerify(exactly = 0) { linkClient.sendPlaybackCommand(any(), any()) }
    }

    @Test
    fun playQueue_remotePlaybackHost_transfersQueueFullSemantics() {
        val linkClient = mockk<LinkClient>(relaxed = true)
        coEvery { linkClient.transferQueue(any()) } returns Result.success(QueueTransferResponse(success = true, sessionId = "s1"))
        coEvery { linkClient.sendPlaybackCommand(any()) } returns Result.success("ok")
        coEvery { linkClient.sendPlaybackCommand(any(), any()) } returns Result.success("ok")
        coEvery { linkClient.getPlaybackState() } returns Result.success(org.michimusic.link.dto.PlaybackStateDto(state = "playing", playing = true))
        coEvery { linkClient.getQueue() } returns Result.success(org.michimusic.link.dto.QueueDto(tracks = emptyList(), currentIndex = 0))

        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "desktop_host_1") linkClient else null
        }
        connectionManager.updateState("desktop_host_1", SyncConnectionState.CONNECTED)
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

        val hostEndpoint = PlaybackEndpoint(
            id = "desktop_host_1",
            name = "Desktop Studio",
            type = EndpointType.DESKTOP_PLAYER,
            isLocal = false,
            capabilities = setOf("PLAYBACK", "REMOTE_CONTROL", "LIBRARY_SYNC")
        )
        manager.attachRemote(hostEndpoint) { _, _ -> }
        testDispatcher.scheduler.advanceUntilIdle()

        val remoteTracks = listOf(
            Track("srv_1", "Song 1", "Artist", source = TrackSource.STREAMING),
            Track("srv_2", "Song 2", "Artist", source = TrackSource.STREAMING)
        )

        manager.playQueue(remoteTracks, 1)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            linkClient.transferQueue(match {
                it.trackIds == listOf("srv_1", "srv_2") &&
                    it.currentIndex == 1 &&
                    it.source == "mobile_queue"
            })
        }
        verify(exactly = 0) { audioController.playQueue(any(), any()) }
    }

    @Test
    fun playQueue_remotePlaybackHost_whenTransferFails_doesNotFallBackToPlayTrackAndReportsFailure() {
        val linkClient = mockk<LinkClient>(relaxed = true)
        coEvery { linkClient.transferQueue(any()) } returns Result.failure(java.io.IOException("Queue transfer rejected"))
        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "desktop_host_1") linkClient else null
        }
        connectionManager.updateState("desktop_host_1", SyncConnectionState.CONNECTED)
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

        val hostEndpoint = PlaybackEndpoint(
            id = "desktop_host_1",
            name = "Desktop Studio",
            type = EndpointType.DESKTOP_PLAYER,
            isLocal = false,
            capabilities = setOf("PLAYBACK", "REMOTE_CONTROL")
        )
        manager.attachRemote(hostEndpoint) { _, _ -> }
        testDispatcher.scheduler.advanceUntilIdle()

        val remoteTracks = listOf(
            Track("srv_1", "Song 1", "Artist", source = TrackSource.STREAMING),
            Track("srv_2", "Song 2", "Artist", source = TrackSource.STREAMING)
        )

        manager.playQueue(remoteTracks, 0)
        testDispatcher.scheduler.advanceUntilIdle()

        // K46D-39: Must NOT silently fall back to play_track
        coVerify(exactly = 0) { linkClient.sendPlaybackCommand("play_track", any()) }
        val lastCmd = manager.sessionState.value.lastRemoteCommand
        assertTrue(lastCmd?.status == RemoteCommandStatus.DISCONNECTED || lastCmd?.status == RemoteCommandStatus.REJECTED || lastCmd?.status == RemoteCommandStatus.FAILED)
        assertFalse("Should not be CONFIRMED", lastCmd?.status == RemoteCommandStatus.CONFIRMED)
    }

    @Test
    fun playQueue_remotePlaybackHost_whenPlayCommandFails_reportsFailure() {
        val linkClient = mockk<LinkClient>(relaxed = true)
        coEvery { linkClient.transferQueue(any()) } returns Result.success(QueueTransferResponse(success = true, sessionId = "s1"))
        coEvery { linkClient.sendPlaybackCommand("play") } returns Result.failure(java.io.IOException("Play command failed"))

        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "desktop_host_1") linkClient else null
        }
        connectionManager.updateState("desktop_host_1", SyncConnectionState.CONNECTED)
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

        val hostEndpoint = PlaybackEndpoint(
            id = "desktop_host_1",
            name = "Desktop Studio",
            type = EndpointType.DESKTOP_PLAYER,
            isLocal = false,
            capabilities = setOf("PLAYBACK", "REMOTE_CONTROL")
        )
        manager.attachRemote(hostEndpoint) { _, _ -> }
        testDispatcher.scheduler.advanceUntilIdle()

        val remoteTracks = listOf(
            Track("srv_1", "Song 1", "Artist", source = TrackSource.STREAMING),
            Track("srv_2", "Song 2", "Artist", source = TrackSource.STREAMING)
        )

        manager.playQueue(remoteTracks, 0)
        testDispatcher.scheduler.advanceUntilIdle()

        val lastCmd = manager.sessionState.value.lastRemoteCommand
        assertFalse("Should not be CONFIRMED when play command failed", lastCmd?.status == RemoteCommandStatus.CONFIRMED)
        assertTrue(lastCmd?.status == RemoteCommandStatus.DISCONNECTED || lastCmd?.status == RemoteCommandStatus.REJECTED || lastCmd?.status == RemoteCommandStatus.FAILED)
    }


    @Test
    fun rtpError_pausesLocalOutputBeforeReleasingMuteAndSetsRtpFailureIntent() {
        val audioController = mockk<AudioController>(relaxed = true)
        val dummyStateFlow = MutableStateFlow(PlayerState(isPlaying = true))
        every { audioController.state } returns dummyStateFlow

        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {}
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

        manager.selectLocalEndpoint(
            reason = StreamErrorReason.NETWORK_LOST,
            message = "RTP error",
            resumePlayback = false,
            intent = PlaybackTransitionIntent.RTP_FAILURE
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(PlaybackEndpoint.LocalPhone, manager.sessionState.value.activeEndpoint)
        assertEquals(PlaybackTransitionIntent.RTP_FAILURE, manager.sessionState.value.lastTransitionIntent)
        assertEquals(StreamErrorReason.NETWORK_LOST, manager.sessionState.value.lastSessionError)
        verify(exactly = 1) { audioController.pause() }
        verify(exactly = 0) { audioController.play() }
    }

    @Test
    fun handoffToLocal_whenRemotePauseFails_abortsWithoutStartingLocalAudio() {
        val linkClient = mockk<LinkClient>(relaxed = true)
        coEvery { linkClient.sendPlaybackCommand("pause") } returns Result.failure(java.io.IOException("Remote connection lost"))
        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "remote_host_1") linkClient else null
        }
        connectionManager.updateState("remote_host_1", SyncConnectionState.CONNECTED)
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

        val remoteEndpoint = PlaybackEndpoint("remote_host_1", "Remote Desktop", EndpointType.DESKTOP_PLAYER, isLocal = false)
        manager.attachRemote(remoteEndpoint) { _, _ -> }
        testDispatcher.scheduler.advanceUntilIdle()

        // Set remote playing
        manager.play()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(manager.sessionState.value.isPlaying)

        var callbackSuccess = true
        var callbackMessage = ""
        manager.handoffTo(PlaybackEndpoint.LocalPhone) { success, msg ->
            callbackSuccess = success
            callbackMessage = msg
        }
        testDispatcher.scheduler.advanceUntilIdle()

        // MUST fail safely without starting local audio
        assertFalse("Handoff should have failed when remote pause failed", callbackSuccess)
        verify(exactly = 0) { audioController.play() }
        verify(exactly = 0) { audioController.playQueue(any(), any()) }
        assertTrue(manager.sessionState.value.isRemoteActive)
    }

    @Test
    fun handoffToLocal_whenStreamReceiverPauseFails_abortsWithoutStartingLocalAudio() {
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
        coEvery { linkClient.createReceiverLiteSession(any()) } returns Result.success(
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
                    ssrc = 1000L,
                    streamPort = 5004,
                    volume = 70
                )
            )
        )
        coEvery { linkClient.patchReceiverLiteSession(any(), any()) } returns Result.failure(java.io.IOException("Stream error"))
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

        val streamEndpoint = PlaybackEndpoint("stream_1", "Michi Stream", EndpointType.STREAM_RECEIVER, isLocal = false, capabilities = setOf("AUDIO_OUTPUT"))
        manager.handoffTo(streamEndpoint) { _, _ -> }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(EndpointType.STREAM_RECEIVER, manager.sessionState.value.activeEndpoint.type)

        var callbackSuccess = true
        var callbackMessage = ""
        manager.handoffTo(PlaybackEndpoint.LocalPhone) { success, msg ->
            callbackSuccess = success
            callbackMessage = msg
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("Handoff should fail when stream pause fails", callbackSuccess)
        verify(exactly = 0) { audioController.play() }
        assertEquals(EndpointType.STREAM_RECEIVER, manager.sessionState.value.activeEndpoint.type)
    }
}

