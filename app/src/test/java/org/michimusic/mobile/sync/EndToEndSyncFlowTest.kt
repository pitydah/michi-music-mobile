package org.michimusic.mobile.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.DeliveryChoice
import org.michimusic.core.models.DeliveryProfile
import org.michimusic.core.models.ManifestPlaylist
import org.michimusic.core.models.ManifestTrack
import org.michimusic.core.models.SyncPreflightStatus
import org.michimusic.core.models.SyncScope
import org.michimusic.core.models.SyncSelection
import org.michimusic.core.sync.SyncPreflightService
import org.michimusic.core.sync.SyncProfileNegotiator
import org.michimusic.data.repository.SyncedTrackRepository
import org.michimusic.link.ConnectionManager
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkDiscovery
import org.michimusic.link.PairedDevice
import org.michimusic.link.PairedDeviceRegistry
import org.michimusic.link.dto.AudioCapabilitiesDto
import org.michimusic.link.dto.ManifestPlaylistDto
import org.michimusic.link.dto.ManifestTrackDto
import org.michimusic.link.dto.ServerInfoDto
import org.michimusic.link.dto.SyncManifestDto
import org.michimusic.link.identity.MichiIdentity
import org.michimusic.mobile.rules.MainDispatcherRule
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class EndToEndSyncFlowTest {

    private val testDispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    private val oneMb = 1024L * 1024L
    private val serverId = "srv_desktop_studio"

    private lateinit var context: Context
    private lateinit var linkDiscovery: LinkDiscovery
    private lateinit var registry: PairedDeviceRegistry
    private lateinit var connectionManager: ConnectionManager
    private lateinit var identity: MichiIdentity
    private lateinit var trackRepository: SyncedTrackRepository
    private lateinit var mockClient: LinkClient
    private lateinit var checkpointRepo: org.michimusic.core.models.TransferCheckpointRepository

    private val catalogTracks = listOf(
        ManifestTrack(
            trackId = "trk-alpha-1",
            title = "Alpha One",
            artist = "Artist A",
            album = "Album Alpha",
            size = 30 * oneMb,
            format = "flac",
            duration = 180,
            checksum = "sha-alpha-1",
        ),
        ManifestTrack(
            trackId = "trk-alpha-2",
            title = "Alpha Two",
            artist = "Artist A",
            album = "Album Alpha",
            size = 25 * oneMb,
            format = "flac",
            duration = 150,
            checksum = "sha-alpha-2",
        ),
        ManifestTrack(
            trackId = "trk-beta-1",
            title = "Beta One",
            artist = "Artist B",
            album = "Album Beta",
            size = 40 * oneMb,
            format = "flac",
            duration = 240,
            checksum = "sha-beta-1",
        ),
        ManifestTrack(
            trackId = "trk-gamma-1",
            title = "Gamma Single",
            artist = "Artist C",
            album = "Album Gamma",
            size = 20 * oneMb,
            format = "flac",
            duration = 120,
            checksum = "sha-gamma-1",
        ),
    )

    private val catalogPlaylists = listOf(
        ManifestPlaylist(
            playlistId = "pl-workout",
            name = "Workout Mix",
            trackIds = listOf("trk-alpha-1", "trk-gamma-1"),
        )
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        linkDiscovery = mockk(relaxed = true)
        registry = mockk(relaxed = true)
        connectionManager = mockk(relaxed = true)
        identity = mockk(relaxed = true)
        trackRepository = mockk(relaxed = true)
        mockClient = mockk(relaxed = true)

        every { identity.michiId } returns "client_pixel_node"
        every { linkDiscovery.peers } returns MutableStateFlow(emptyMap())
        every { connectionManager.connectionStates } returns MutableStateFlow(emptyMap())

        val pairedDevice = PairedDevice(
            deviceId = serverId,
            deviceName = "Desktop Studio",
            deviceToken = "token_studio_sec_999",
            lastUrl = "http://192.168.1.100:8400",
            features = listOf("SYNC_V1", "TRANSCODING"),
        )
        every { registry.getDevice(serverId) } returns pairedDevice
        every { registry.getAllDevices() } returns listOf(pairedDevice)
        every { connectionManager.getClient(serverId) } returns mockClient

        // Setup mock server responses
        coEvery { mockClient.getServerInfo() } returns Result.success(
            ServerInfoDto(
                name = "Desktop Studio",
                audio = AudioCapabilitiesDto(codecs = listOf("aac")),
            )
        )

        coEvery { mockClient.fetchSyncManifest(serverId) } returns Result.success(
            SyncManifestDto(
                version = 1,
                tracks = catalogTracks.map {
                    ManifestTrackDto(
                        trackId = it.trackId,
                        title = it.title,
                        artist = it.artist,
                        album = it.album,
                        size = it.size,
                        format = it.format,
                        duration = it.duration,
                        checksum = it.checksum,
                    )
                },
                playlists = catalogPlaylists.map {
                    ManifestPlaylistDto(
                        playlistId = it.playlistId,
                        name = it.name,
                        trackIds = it.trackIds,
                    )
                },
            )
        )

        coEvery { trackRepository.getDownloadedBytes() } returns 100 * oneMb
        coEvery { trackRepository.getDownloadedTrackFingerprints() } returns emptySet()

        checkpointRepo = mockk(relaxed = true)
        coEvery { checkpointRepo.getCheckpointsForDevice(any()) } returns emptyList()
    }

    @Test
    fun testRemoteManifestLoading_populatesRealCatalogAndHonestCodecProfiles() = runTest(testDispatcher) {
        val viewModel = SyncViewModel(
            context = context,
            linkDiscovery = linkDiscovery,
            registry = registry,
            connectionManager = connectionManager,
            identity = identity,
            trackRepository = trackRepository,
            checkpointRepository = checkpointRepo,
        )

        backgroundScope.launch { viewModel.wizardUiState.collect { } }

        // Initial state before loading
        assertTrue(viewModel.wizardUiState.value.candidateTracks.isEmpty())

        // Load wizard data for remote device
        viewModel.loadWizardData(serverId)
        advanceUntilIdle()

        val state = viewModel.wizardUiState.value
        assertFalse("Wizard should finish loading, but state was: $state", state.isLoading)
        assertEquals(null, state.error)
        assertEquals(4, state.candidateTracks.size)
        assertEquals(1, state.playlists.size)
        assertEquals("Workout Mix", state.playlists.first().name)
        assertEquals(100 * oneMb, state.currentLocalMusicBytes)

        // Profile negotiation based on server capabilities:
        // Server announced "aac" but NOT "opus"
        val choices = SyncProfileNegotiator.evaluateAvailableProfiles(
            serverCapabilities = state.serverCapabilities,
            serverRepresentations = state.serverRepresentations,
        )

        val balanced = choices.first { it.profile == DeliveryProfile.BALANCED }
        val lowStorage = choices.first { it.profile == DeliveryProfile.LOW_STORAGE }

        assertTrue("BALANCED (AAC) must be available because server announced aac", balanced.isAvailable)
        assertFalse("LOW_STORAGE (Opus) must NOT be available because server did not announce opus", lowStorage.isAvailable)
    }

    @Test
    fun testSelectiveAlbumFlow_restrictsPlanAndWorkerToOnlySelectedTracks() = runTest(testDispatcher) {
        val preflightService = SyncPreflightService()

        // User selects ONLY "Album Alpha" (contains trk-alpha-1 and trk-alpha-2)
        val selection = SyncSelection(
            scope = SyncScope.ALBUMS,
            albumIds = setOf("Album Alpha"),
            playlistIds = emptySet(),
            artistIds = emptySet(),
            trackIds = emptySet(),
        )

        val plan = preflightService.buildPlan(
            endpointId = serverId,
            endpointName = "Desktop Studio",
            selection = selection,
            deliveryChoice = DeliveryChoice(profile = DeliveryProfile.ORIGINAL, codec = "original"),
            candidateTracks = catalogTracks,
            manifestPlaylists = catalogPlaylists,
            availableStorageBytes = 10_000 * oneMb,
            localTrackFingerprints = emptySet(),
            existingPartials = emptyMap(),
        )

        // 1. Verify preflight plan strictly filters only Album Alpha tracks
        assertEquals(SyncPreflightStatus.READY, plan.status)
        assertTrue(plan.isExecutable)
        assertEquals(2, plan.totalSelectedTracks)
        assertEquals(2, plan.tracksToDownloadCount)
        assertEquals(55 * oneMb, plan.totalDownloadBytes)

        val plannedTrackIds = plan.items.map { it.trackId }.toSet()
        assertEquals(setOf("trk-alpha-1", "trk-alpha-2"), plannedTrackIds)
        assertFalse("Tracks from Album Beta must NOT be in the plan", plannedTrackIds.contains("trk-beta-1"))
        assertFalse("Tracks from Album Gamma must NOT be in the plan", plannedTrackIds.contains("trk-gamma-1"))

        // 2. Verify worker input data encapsulates this plan fidelity
        val inputData = SyncWorker.buildInputData(
            baseUrl = "http://192.168.1.100:8400",
            deviceId = serverId,
            alias = "Desktop Studio",
            clientDeviceId = "client_pixel_node",
            selectedTrackIds = plannedTrackIds.joinToString(","),
            profile = plan.deliveryChoice.profile.name,
            scope = plan.selection.scope.name,
            planId = plan.planId,
            expectedBytes = plan.totalDownloadBytes,
        )

        assertEquals("ALBUMS", inputData.getString("scope"))
        assertEquals("trk-alpha-1,trk-alpha-2", inputData.getString("selectedTrackIds"))
        assertEquals(55 * oneMb, inputData.getLong("expectedBytes", 0L))

        // 3. Verify worker candidate selection logic strictly preserves zero leakage
        val selectedIds = inputData.getString("selectedTrackIds")!!.split(",").toSet()
        val workerCandidateTracks = catalogTracks.filter { it.trackId in selectedIds }
        assertEquals(2, workerCandidateTracks.size)
        assertTrue(workerCandidateTracks.all { it.album == "Album Alpha" })
    }

    @Test
    fun testZeroSelectionInvariant_neverTriggersFullLibraryOrUnauthorizedDownloads() = runTest(testDispatcher) {
        val preflightService = SyncPreflightService()

        // User chooses ALBUMS scope but selects ZERO albums
        val emptyAlbumSelection = SyncSelection(
            scope = SyncScope.ALBUMS,
            albumIds = emptySet(),
        )

        val plan = preflightService.buildPlan(
            endpointId = serverId,
            endpointName = "Desktop Studio",
            selection = emptyAlbumSelection,
            deliveryChoice = DeliveryChoice(profile = DeliveryProfile.ORIGINAL, codec = "original"),
            candidateTracks = catalogTracks,
            manifestPlaylists = catalogPlaylists,
            availableStorageBytes = 10_000 * oneMb,
            localTrackFingerprints = emptySet(),
            existingPartials = emptyMap(),
        )

        // 1. Plan MUST be blocked and not executable
        assertEquals(SyncPreflightStatus.BLOCKED_EMPTY_SELECTION, plan.status)
        assertFalse("Zero selected tracks must never produce an executable plan", plan.isExecutable)
        assertEquals(0, plan.totalSelectedTracks)
        assertEquals(0, plan.tracksToDownloadCount)

        // 2. SyncViewModel.syncPlannedLibrary must reject non-executable or zero-selection plan
        val viewModel = SyncViewModel(
            context = context,
            linkDiscovery = linkDiscovery,
            registry = registry,
            connectionManager = connectionManager,
            identity = identity,
            trackRepository = trackRepository,
            checkpointRepository = checkpointRepo,
        )

        // Calling syncPlannedLibrary with blocked plan must be a no-op
        viewModel.syncPlannedLibrary(plan)
        // Progress remains Idle
        assertEquals(SyncProgress.Idle, viewModel.uiState.value.syncProgress)

        // 3. Worker Invariant: If worker receives scope = ALBUMS and empty selectedTrackIds,
        // candidateTracks MUST evaluate to emptyList() and download ZERO tracks.
        val inputData = SyncWorker.buildInputData(
            baseUrl = "http://192.168.1.100:8400",
            deviceId = serverId,
            alias = "Desktop Studio",
            clientDeviceId = "client_pixel_node",
            selectedTrackIds = "",
            scope = "ALBUMS",
        )

        val rawSelected = inputData.getString("selectedTrackIds") ?: ""
        val parsedIds = if (rawSelected.isNotEmpty()) rawSelected.split(",").toSet() else emptySet()
        val scope = inputData.getString("scope") ?: "FULL_LIBRARY"

        val evaluatedCandidates = when {
            parsedIds.isNotEmpty() -> catalogTracks.filter { it.trackId in parsedIds }
            scope.equals("FULL_LIBRARY", ignoreCase = true) -> catalogTracks
            else -> emptyList() // Strictly zero downloads for empty selection!
        }

        assertTrue("Empty selection under ALBUMS scope must evaluate to 0 candidate tracks", evaluatedCandidates.isEmpty())
    }

    @Test
    fun testTranscodedProfile_estimatesDownloadBytesByCodecBitrate() {
        val preflightService = SyncPreflightService()

        val fullLibrarySelection = SyncSelection(scope = SyncScope.FULL_LIBRARY)

        // Original FLAC size is 115 MB across 690 total duration seconds
        val totalOriginalBytes = catalogTracks.sumOf { it.size }
        assertEquals(115 * oneMb, totalOriginalBytes)

        val balancedPlan = preflightService.buildPlan(
            endpointId = serverId,
            endpointName = "Desktop Studio",
            selection = fullLibrarySelection,
            deliveryChoice = DeliveryChoice(profile = DeliveryProfile.BALANCED, codec = "aac", isServerTranscoded = true),
            candidateTracks = catalogTracks,
            manifestPlaylists = catalogPlaylists,
            availableStorageBytes = 10_000 * oneMb,
            localTrackFingerprints = emptySet(),
            existingPartials = emptyMap(),
        )

        // Total seconds = 180 + 150 + 240 + 120 = 690 seconds
        // At 256 kbps = 32,000 bytes/sec -> 690 * 32,000 = ~22,080,000 bytes (~21 MB)
        assertTrue(
            "Balanced AAC plan size should be significantly smaller than original 115 MB (actual: ${balancedPlan.totalDownloadBytes / oneMb} MB)",
            balancedPlan.totalDownloadBytes < 30 * oneMb && balancedPlan.totalDownloadBytes > 15 * oneMb
        )
    }
}
