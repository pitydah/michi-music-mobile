package org.michimusic.mobile.devices.aircat

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.PersistentCheckpoint
import org.michimusic.core.models.TransferCheckpointRepository
import org.michimusic.core.models.TransferStatus
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkTransferManager
import org.michimusic.link.PairedDevice
import org.michimusic.link.PairedDeviceRegistry

@OptIn(ExperimentalCoroutinesApi::class)
class AirCatSecurityTokenBindingTest {

    private val transferManager = mockk<LinkTransferManager>(relaxed = true)
    private val pairedDeviceRegistry = mockk<PairedDeviceRegistry>(relaxed = true)
    private val checkpointRepository = mockk<TransferCheckpointRepository>(relaxed = true)

    private val client = DefaultAirCatTransferClient(
        transferManager = transferManager,
        pairedDeviceRegistry = pairedDeviceRegistry,
        libraryRepository = null,
        checkpointRepository = checkpointRepository,
    )

    private val pendingCheckpoint = PersistentCheckpoint(
        transferKey = "dev-1:lib-1:trk-1",
        transferId = "transfer-123",
        remoteDeviceId = "device-456",
        sourceLibraryId = "lib-remote",
        destinationLibraryId = "lib-1",
        trackId = "trk-1",
        remoteTrackId = "trk-remote-1",
        tempFilePath = "/tmp/trk1.part",
        finalFilePath = "/tmp/trk1.flac",
        expectedSize = 1000L,
        receivedBytes = 500L,
        transferState = TransferStatus.DOWNLOADING,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
    )

    @Test
    fun recover_whenClientTokenEmpty_rejectsWithUnauthorizedPeer() = runTest {
        val linkClient = mockk<LinkClient>(relaxed = true)
        every { linkClient.deviceToken } returns ""
        every { pairedDeviceRegistry.getDevice("device-456") } returns PairedDevice(
            deviceId = "device-456",
            deviceToken = "valid-token-abc",
        )
        coEvery { checkpointRepository.getCheckpointsForDevice("device-456") } returns listOf(pendingCheckpoint)

        val result = client.recoverIncompleteTransfers("device-456", linkClient)

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AirCatException.UnauthorizedPeer)
    }

    @Test
    fun recover_whenRegistryTokenEmpty_rejectsWithUnauthorizedPeer() = runTest {
        val linkClient = mockk<LinkClient>(relaxed = true)
        every { linkClient.deviceToken } returns "valid-token-abc"
        every { pairedDeviceRegistry.getDevice("device-456") } returns PairedDevice(
            deviceId = "device-456",
            deviceToken = "",
        )
        coEvery { checkpointRepository.getCheckpointsForDevice("device-456") } returns listOf(pendingCheckpoint)

        val result = client.recoverIncompleteTransfers("device-456", linkClient)

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AirCatException.UnauthorizedPeer)
    }

    @Test
    fun recover_whenTokensMismatch_rejectsWithUnauthorizedPeer() = runTest {
        val linkClient = mockk<LinkClient>(relaxed = true)
        every { linkClient.deviceToken } returns "token-from-client-123"
        every { pairedDeviceRegistry.getDevice("device-456") } returns PairedDevice(
            deviceId = "device-456",
            deviceToken = "token-from-registry-789",
        )
        coEvery { checkpointRepository.getCheckpointsForDevice("device-456") } returns listOf(pendingCheckpoint)

        val result = client.recoverIncompleteTransfers("device-456", linkClient)

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is AirCatException.UnauthorizedPeer)
    }

    @Test
    fun recover_whenTokensMatchAndNonEmpty_allowsRecoveryCheck() = runTest {
        val linkClient = mockk<LinkClient>(relaxed = true)
        val sharedToken = "cryptographically-verified-token-xyz"
        every { linkClient.deviceToken } returns sharedToken
        every { pairedDeviceRegistry.getDevice("device-456") } returns PairedDevice(
            deviceId = "device-456",
            deviceToken = sharedToken,
            isRevoked = false,
        )
        // With empty pending checkpoints, it succeeds and returns emptyList
        coEvery { checkpointRepository.getCheckpointsForDevice("device-456") } returns emptyList()

        val result = client.recoverIncompleteTransfers("device-456", linkClient)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.isEmpty() == true)
    }
}
