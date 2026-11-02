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
        val remoteExistingTrack = QueueTrackDto(trackId = "srv_0", title = "Remote Existing", artist = "Artist")
        coEvery { linkClient.getPlaybackState() } returns Result.success(PlaybackStateDto(playing = true, queueIndex = 0, trackId = "srv_0"))
        coEvery { linkClient.getQueue() } returns Result.success(QueueDto(tracks = listOf(remoteExistingTrack), currentIndex = 0))

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

        // Obtain the real preflight ticket; do not bypass user authorization in tests.
        val pending = manager.requestQueuePlayback(listOf(track), 0)
        assertTrue(pending is QueuePlaybackOutcome.PendingConfirmation)
        val outcome = manager.requestQueuePlayback(listOf(track), 0,
            confirmedAction = RemoteQueueAction.REPLACE,
            confirmedTicket = (pending as QueuePlaybackOutcome.PendingConfirmation).confirmation.ticket)
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
        val queueDtoBefore = QueueDto(tracks = listOf(trackDtoOld), currentIndex = 0)
        val queueDtoAfter = QueueDto(tracks = listOf(trackDtoOld, trackDtoNew), currentIndex = 0)

        // Attaching a remote host now performs one initial status read, so the queue
        // snapshot must stay stable for every read before the transfer instead of
        // relying on a fixed call index.
        var queueTransferred = false
        coEvery { linkClient.transferQueue(any()) } coAnswers {
            queueTransferred = true
            Result.success(QueueTransferResponse(success = true))
        }
        coEvery { linkClient.getPlaybackState() } returns Result.success(playStateDto)
        coEvery { linkClient.getQueue() } coAnswers {
            Result.success(if (queueTransferred) queueDtoAfter else queueDtoBefore)
        }

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

        // APPEND also requires a ticket bound to the host queue snapshot.
        val pending = manager.requestQueuePlayback(listOf(newTrack), 0)
        assertTrue(pending is QueuePlaybackOutcome.PendingConfirmation)
        val outcome = manager.requestQueuePlayback(listOf(newTrack), 0,
            confirmedAction = RemoteQueueAction.APPEND,
            confirmedTicket = (pending as QueuePlaybackOutcome.PendingConfirmation).confirmation.ticket)
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

    @Test
    fun requestQueuePlayback_whenConfirmedActionHasNoTicket_rejectsWithoutTransfer() = runTest {
        val client = mockk<LinkClient>(relaxed = true)
        val track = Track("srv_1", "Track", "Artist", filepath = "http://host/1", source = TrackSource.STREAMING)
        coEvery { client.getPlaybackState() } returns Result.success(PlaybackStateDto())
        coEvery { client.getQueue() } returns Result.success(QueueDto())
        val connections = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = client
        }
        connections.updateState("remote_1", SyncConnectionState.CONNECTED)
        val controller = mockk<AudioController>(relaxed = true)
        every { controller.state } returns MutableStateFlow(PlayerState())
        val discovery = mockk<LinkDiscovery>(relaxed = true)
        every { discovery.peers } returns MutableStateFlow(emptyMap())
        val registry = mockk<PairedDeviceRegistry>(relaxed = true)
        every { registry.getAllDevices() } returns emptyList()
        val manager = PlaybackSessionManager(controller, connections, discovery, registry)
        manager.attachRemote(PlaybackEndpoint("remote_1", "Host", EndpointType.DESKTOP_PLAYER,
            isLocal = false, capabilities = setOf("PLAYBACK", "REMOTE_CONTROL"))) { _, _ -> }
        val outcome = manager.requestQueuePlayback(listOf(track), 0, RemoteQueueAction.REPLACE)
        assertTrue(outcome is QueuePlaybackOutcome.Rejected)
        coVerify(exactly = 0) { client.transferQueue(any()) }
    }

    @Test
    fun requestQueuePlayback_whenTicketExpired_rejectsWithCancelled() = runTest {
        val linkClient = mockk<LinkClient>(relaxed = true)
        val track = Track("srv_1", "Song", "Artist", filepath = "http://srv/1", source = TrackSource.STREAMING)

        coEvery { linkClient.getPlaybackState() } returns Result.success(PlaybackStateDto())
        coEvery { linkClient.getQueue() } returns Result.success(QueueDto())

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

        // Expired ticket (issued 70 seconds ago, duration 60s)
        val expiredTicket = RemoteQueueAuthorizationTicket(
            endpointId = "remote_1",
            sessionEpoch = manager.currentSessionEpoch,
            snapshotTrackCount = 1,
            createdAtMs = System.currentTimeMillis() - 70_000L,
            validDurationMs = 60_000L,
        )
        manager.registerTicketForTesting(expiredTicket)

        val outcome = manager.requestQueuePlayback(
            tracks = listOf(track),
            startIndex = 0,
            confirmedAction = RemoteQueueAction.REPLACE,
            confirmedTicket = expiredTicket
        )
        advanceUntilIdle()

        assertTrue("Expired ticket must reject queue playback (K49B-04)", outcome is QueuePlaybackOutcome.Cancelled)
        assertTrue((outcome as QueuePlaybackOutcome.Cancelled).reason.contains("caducó"))
    }

    @Test
    fun requestQueuePlayback_whenQueueDriftedDuringReplace_rejectsWithCancelled() = runTest {
        val linkClient = mockk<LinkClient>(relaxed = true)
        val track = Track("srv_new", "New Song", "Artist", filepath = "http://srv/new", source = TrackSource.STREAMING)
        val trackDtoOld = QueueTrackDto(trackId = "srv_1", title = "Old Song", artist = "Artist", duration = 120_000L)
        val trackDtoAddedByOther = QueueTrackDto(trackId = "srv_other", title = "Other Song", artist = "Artist", duration = 120_000L)

        // Host queue now has 2 tracks (drifted from original snapshot of 1 track!)
        coEvery { linkClient.getQueue() } returns Result.success(QueueDto(tracks = listOf(trackDtoOld, trackDtoAddedByOther), currentIndex = 0))
        coEvery { linkClient.getPlaybackState() } returns Result.success(PlaybackStateDto(playing = true, queueIndex = 0, trackId = "srv_1"))

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

        // Ticket was issued when host had only 1 track
        val ticket = RemoteQueueAuthorizationTicket(
            endpointId = "remote_1",
            sessionEpoch = manager.currentSessionEpoch,
            snapshotTrackCount = 1,
            snapshotTrackFingerprint = "srv_1",
        )
        manager.registerTicketForTesting(ticket)

        val outcome = manager.requestQueuePlayback(
            tracks = listOf(track),
            startIndex = 0,
            confirmedAction = RemoteQueueAction.REPLACE,
            confirmedTicket = ticket
        )
        advanceUntilIdle()

        assertTrue("Queue drift must reject REPLACE to prevent clobbering concurrent user actions (K49B-04)", outcome is QueuePlaybackOutcome.Cancelled)
        assertTrue((outcome as QueuePlaybackOutcome.Cancelled).reason.contains("cambió"))
    }

    @Test
    fun requestQueuePlayback_whenTicketNotIssuedByManager_rejectsWithRejected() = runTest {
        val linkClient = mockk<LinkClient>(relaxed = true)
        val track = Track("srv_1", "Track", "Artist", filepath = "http://host/1", source = TrackSource.STREAMING)
        coEvery { linkClient.getPlaybackState() } returns Result.success(PlaybackStateDto())
        coEvery { linkClient.getQueue() } returns Result.success(QueueDto())
        val connections = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = linkClient
        }
        connections.updateState("remote_1", SyncConnectionState.CONNECTED)
        val controller = mockk<AudioController>(relaxed = true)
        every { controller.state } returns MutableStateFlow(PlayerState())
        val discovery = mockk<LinkDiscovery>(relaxed = true)
        every { discovery.peers } returns MutableStateFlow(emptyMap())
        val registry = mockk<PairedDeviceRegistry>(relaxed = true)
        every { registry.getAllDevices() } returns emptyList()
        val manager = PlaybackSessionManager(controller, connections, discovery, registry)
        manager.attachRemote(PlaybackEndpoint("remote_1", "Host", EndpointType.DESKTOP_PLAYER,
            isLocal = false, capabilities = setOf("PLAYBACK", "REMOTE_CONTROL"))) { _, _ -> }

        // Arbitrary unissued ticket created by external caller (K50-02)
        val unissuedTicket = RemoteQueueAuthorizationTicket(
            endpointId = "remote_1",
            sessionEpoch = manager.currentSessionEpoch,
            snapshotTrackCount = 0
        )
        val outcome = manager.requestQueuePlayback(
            tracks = listOf(track),
            startIndex = 0,
            confirmedAction = RemoteQueueAction.REPLACE,
            confirmedTicket = unissuedTicket
        )
        assertTrue("Ticket not in manager registry must be rejected (K50-02)", outcome is QueuePlaybackOutcome.Rejected)
        assertTrue((outcome as QueuePlaybackOutcome.Rejected).reason.contains("no fue emitido"))
        coVerify(exactly = 0) { linkClient.transferQueue(any()) }
    }

    @Test
    fun requestQueuePlayback_whenTicketReused_rejectsSecondUseWithRejected() = runTest {
        val linkClient = mockk<LinkClient>(relaxed = true)
        val track = Track("srv_1", "Track 1", "Artist", filepath = "http://host/1", source = TrackSource.STREAMING)
        val trackDto = QueueTrackDto(trackId = "srv_1", title = "Track 1", artist = "Artist", duration = 120_000L)
        val playStateDto = PlaybackStateDto(playing = true, queueIndex = 0, trackId = "srv_1", position = 0L, duration = 120_000L)
        val queueDto = QueueDto(tracks = listOf(trackDto), currentIndex = 0)
        coEvery { linkClient.getPlaybackState() } returns Result.success(playStateDto)
        coEvery { linkClient.getQueue() } returns Result.success(queueDto)
        coEvery { linkClient.transferQueue(any()) } returns Result.success(QueueTransferResponse(success = true))
        coEvery { linkClient.sendPlaybackCommand("play") } returns Result.success("ok")

        val connections = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = linkClient
        }
        connections.updateState("remote_1", SyncConnectionState.CONNECTED)
        val controller = mockk<AudioController>(relaxed = true)
        every { controller.state } returns MutableStateFlow(PlayerState())
        val discovery = mockk<LinkDiscovery>(relaxed = true)
        every { discovery.peers } returns MutableStateFlow(emptyMap())
        val registry = mockk<PairedDeviceRegistry>(relaxed = true)
        every { registry.getAllDevices() } returns emptyList()
        val manager = PlaybackSessionManager(controller, connections, discovery, registry)
        manager.attachRemote(PlaybackEndpoint("remote_1", "Host", EndpointType.DESKTOP_PLAYER,
            isLocal = false, capabilities = setOf("PLAYBACK", "REMOTE_CONTROL"))) { _, _ -> }

        // 1. Issue genuine ticket via preflight
        val pending = manager.requestQueuePlayback(listOf(track), 0)
        assertTrue(pending is QueuePlaybackOutcome.PendingConfirmation)
        val ticket = (pending as QueuePlaybackOutcome.PendingConfirmation).confirmation.ticket

        // 2. Consume ticket first time
        val firstOutcome = manager.requestQueuePlayback(
            tracks = listOf(track),
            startIndex = 0,
            confirmedAction = RemoteQueueAction.REPLACE,
            confirmedTicket = ticket
        )
        assertTrue("First use must confirm", firstOutcome is QueuePlaybackOutcome.Confirmed)

        // 3. Re-use same ticket second time (Single-use policy violation, K50-02)
        val secondOutcome = manager.requestQueuePlayback(
            tracks = listOf(track),
            startIndex = 0,
            confirmedAction = RemoteQueueAction.REPLACE,
            confirmedTicket = ticket
        )
        assertTrue("Second use must be rejected (single-use policy)", secondOutcome is QueuePlaybackOutcome.Rejected)
        assertTrue((secondOutcome as QueuePlaybackOutcome.Rejected).reason.contains("consumido"))
    }

    @Test
    fun requestQueuePlayback_whenInitiallyEmptyQueueBecomesOccupied_blocksDirectDispatchAndRequiresConfirmation() = runTest {
        val linkClient = mockk<LinkClient>(relaxed = true)
        val track = Track("srv_1", "New Track", "Artist", filepath = "http://host/1", source = TrackSource.STREAMING)

        val emptyQueue = QueueDto(tracks = emptyList(), currentIndex = 0)
        val occupiedTrack = QueueTrackDto(trackId = "srv_other", title = "Added by other controller", artist = "Artist")
        val occupiedQueue = QueueDto(tracks = listOf(occupiedTrack), currentIndex = 0)

        // 1st getQueue (during preflight): empty.
        // 2nd getQueue (during final check): occupied!
        coEvery { linkClient.getPlaybackState() } returns Result.success(PlaybackStateDto(playing = false)) andThen
            Result.success(PlaybackStateDto(playing = false, queueIndex = 0, trackId = "srv_other"))
        coEvery { linkClient.getQueue() } returns Result.success(emptyQueue) andThen Result.success(occupiedQueue)

        val connections = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = linkClient
        }
        connections.updateState("remote_1", SyncConnectionState.CONNECTED)
        val controller = mockk<AudioController>(relaxed = true)
        every { controller.state } returns MutableStateFlow(PlayerState())
        val discovery = mockk<LinkDiscovery>(relaxed = true)
        every { discovery.peers } returns MutableStateFlow(emptyMap())
        val registry = mockk<PairedDeviceRegistry>(relaxed = true)
        every { registry.getAllDevices() } returns emptyList()
        val manager = PlaybackSessionManager(controller, connections, discovery, registry)
        manager.attachRemote(PlaybackEndpoint("remote_1", "Host", EndpointType.DESKTOP_PLAYER,
            isLocal = false, capabilities = setOf("PLAYBACK", "REMOTE_CONTROL"))) { _, _ -> }

        // Initial request with confirmedAction = null
        val outcome = manager.requestQueuePlayback(listOf(track), 0)
        advanceUntilIdle()

        // K50-01: Must NOT proceed with transferQueue! Must demand confirmation!
        coVerify(exactly = 0) { linkClient.transferQueue(any()) }
        assertTrue("Must halt and return PendingConfirmation when empty queue becomes occupied (K50-01), got: $outcome",
            outcome is QueuePlaybackOutcome.PendingConfirmation)
        assertNotNull(manager.pendingQueueConfirmation.value)
    }

    @Test
    fun requestQueuePlayback_whenTracksFingerprintMismatchesTicket_rejectsWithRejected() = runTest {
        val linkClient = mockk<LinkClient>(relaxed = true)
        val trackA = Track("srv_A", "Song A", "Artist", filepath = "http://srv/A", source = TrackSource.STREAMING)
        val trackB = Track("srv_B", "Song B", "Artist", filepath = "http://srv/B", source = TrackSource.STREAMING)

        coEvery { linkClient.getPlaybackState() } returns Result.success(PlaybackStateDto())
        coEvery { linkClient.getQueue() } returns Result.success(QueueDto())

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
        val targetEp = PlaybackEndpoint("remote_1", "Host", EndpointType.DESKTOP_PLAYER, isLocal = false, capabilities = setOf("PLAYBACK", "REMOTE_CONTROL"))
        manager.attachRemote(targetEp) { _, _ -> }

        // Ticket authorized for trackA
        val ticket = RemoteQueueAuthorizationTicket(
            endpointId = "remote_1",
            sessionEpoch = manager.currentSessionEpoch,
            snapshotTrackCount = 1,
            proposedTracksFingerprint = "srv_A",
            proposedStartIndex = 0,
        )
        manager.registerTicketForTesting(ticket)

        // Attempting to execute with trackB (substitution attack, K52-03)
        val outcome = manager.requestQueuePlayback(
            tracks = listOf(trackB),
            startIndex = 0,
            confirmedAction = RemoteQueueAction.REPLACE,
            confirmedTicket = ticket
        )
        advanceUntilIdle()

        assertTrue("Substituted track payload must be rejected (K52-03)", outcome is QueuePlaybackOutcome.Rejected)
        assertTrue((outcome as QueuePlaybackOutcome.Rejected).reason.contains("no coinciden con la autorización"))
    }

    @Test
    fun requestQueuePlayback_whenStartIndexMismatchesTicket_rejectsWithRejected() = runTest {
        val linkClient = mockk<LinkClient>(relaxed = true)
        val track1 = Track("srv_A", "Song A", "Artist", filepath = "http://srv/A", source = TrackSource.STREAMING)
        val track2 = Track("srv_B", "Song B", "Artist", filepath = "http://srv/B", source = TrackSource.STREAMING)

        coEvery { linkClient.getPlaybackState() } returns Result.success(PlaybackStateDto())
        coEvery { linkClient.getQueue() } returns Result.success(QueueDto())

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
        val targetEp = PlaybackEndpoint("remote_1", "Host", EndpointType.DESKTOP_PLAYER, isLocal = false, capabilities = setOf("PLAYBACK", "REMOTE_CONTROL"))
        manager.attachRemote(targetEp) { _, _ -> }

        // Ticket authorized for tracks A & B starting at index 0
        val ticket = RemoteQueueAuthorizationTicket(
            endpointId = "remote_1",
            sessionEpoch = manager.currentSessionEpoch,
            snapshotTrackCount = 1,
            proposedTracksFingerprint = "srv_A,srv_B",
            proposedStartIndex = 0,
        )
        manager.registerTicketForTesting(ticket)

        // Attempting to execute with index 1 (index substitution, K52-03)
        val outcome = manager.requestQueuePlayback(
            tracks = listOf(track1, track2),
            startIndex = 1,
            confirmedAction = RemoteQueueAction.REPLACE,
            confirmedTicket = ticket
        )
        advanceUntilIdle()

        assertTrue("Substituted start index must be rejected (K52-03)", outcome is QueuePlaybackOutcome.Rejected)
        assertTrue((outcome as QueuePlaybackOutcome.Rejected).reason.contains("índice de reproducción no coincide"))
    }

    @Test
    fun handoffTo_whenRemoteHostHasActiveQueue_haltsAndRequiresConfirmation() = runTest {
        val linkClient = mockk<LinkClient>(relaxed = true)
        val occupiedTrack = QueueTrackDto(trackId = "srv_remote", title = "Remote Playing Track", artist = "Artist")
        val occupiedQueue = QueueDto(tracks = listOf(occupiedTrack), currentIndex = 0)

        coEvery { linkClient.getPlaybackState() } returns Result.success(PlaybackStateDto(playing = true, title = "Remote Playing Track"))
        coEvery { linkClient.getQueue() } returns Result.success(occupiedQueue)
        coEvery { linkClient.search(any()) } returns Result.success(listOf(org.michimusic.link.dto.TrackResponseDto(id = "srv_local", title = "Local Track")))

        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "remote_1") linkClient else null
        }
        connectionManager.updateState("remote_1", SyncConnectionState.CONNECTED)
        val audioController = mockk<AudioController>(relaxed = true)
        val dummyPlayerState = MutableStateFlow(PlayerState())
        every { audioController.state } returns dummyPlayerState
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
        val localTrack = Track("local_1", "Local Track", "Artist", source = TrackSource.LOCAL)
        dummyPlayerState.value = dummyPlayerState.value.copy(queue = listOf(localTrack), queueIndex = 0)
        manager.selectLocalEndpoint()

        val targetEndpoint = PlaybackEndpoint("remote_1", "Remote Server", EndpointType.SERVER, isLocal = false)

        var handoffReportedSuccess: Boolean? = null
        manager.handoffTo(targetEndpoint) { success, _ ->
            handoffReportedSuccess = success
        }
        advanceUntilIdle()

        // K52-01: Must NOT perform blind destructive transferQueue!
        coVerify(exactly = 0) { linkClient.transferQueue(any()) }

        // Must emit pendingQueueConfirmation dialog prompt
        assertNotNull("handoffTo must emit pending confirmation when target has active queue (K52-01)",
            manager.pendingQueueConfirmation.value)
        assertEquals("remote_1", manager.pendingQueueConfirmation.value?.destinationId)
    }

    @Test
    fun handoffTo_whenRemoteHostIdleAndEmpty_proceedsDirectly() = runTest {
        val linkClient = mockk<LinkClient>(relaxed = true)
        coEvery { linkClient.getPlaybackState() } returns Result.success(PlaybackStateDto(playing = false))
        coEvery { linkClient.getQueue() } returns Result.success(QueueDto(tracks = emptyList(), currentIndex = -1))
        coEvery { linkClient.search(any()) } returns Result.success(listOf(org.michimusic.link.dto.TrackResponseDto(id = "srv_local", title = "Local Track")))
        coEvery { linkClient.transferQueue(any()) } returns Result.success(QueueTransferResponse(success = true))

        val connectionManager = object : ConnectionManager(mockk(relaxed = true), mockk(relaxed = true)) {
            override fun getClient(deviceId: String): LinkClient? = if (deviceId == "remote_1") linkClient else null
        }
        connectionManager.updateState("remote_1", SyncConnectionState.CONNECTED)
        val audioController = mockk<AudioController>(relaxed = true)
        val dummyPlayerState = MutableStateFlow(PlayerState())
        every { audioController.state } returns dummyPlayerState
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
        val localTrack = Track("local_1", "Local Track", "Artist", source = TrackSource.LOCAL)
        dummyPlayerState.value = dummyPlayerState.value.copy(queue = listOf(localTrack), queueIndex = 0)
        manager.selectLocalEndpoint()

        val targetEndpoint = PlaybackEndpoint("remote_1", "Remote Server", EndpointType.SERVER, isLocal = false)

        var handoffReportedSuccess = false
        manager.handoffTo(targetEndpoint) { success, _ ->
            handoffReportedSuccess = success
        }
        advanceUntilIdle()

        // Clean remote host: proceeds directly
        coVerify(exactly = 1) { linkClient.transferQueue(any()) }
        assertTrue("handoffTo must succeed directly on empty idle host", handoffReportedSuccess)
        assertEquals(targetEndpoint, manager.sessionState.value.activeEndpoint)
    }
}

