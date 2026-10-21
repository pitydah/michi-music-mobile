package org.michimusic.mobile.devices.aircat

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.michimusic.data.repository.MusicLibraryRepository
import org.michimusic.link.LinkTransferManager
import org.michimusic.link.PairedDevice
import org.michimusic.link.PairedDeviceRegistry
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class AirCatContractTest {

    private lateinit var tempDir: File
    private lateinit var mockContext: Context
    private lateinit var transferManager: LinkTransferManager
    private lateinit var pairedRegistry: PairedDeviceRegistry
    private lateinit var libraryRepo: MusicLibraryRepository
    private lateinit var aircatClient: DefaultAirCatTransferClient

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("aircat_test").toFile()
        mockContext = mockk(relaxed = true)
        every { mockContext.filesDir } returns tempDir

        transferManager = LinkTransferManager(mockContext)
        pairedRegistry = mockk(relaxed = true)
        libraryRepo = mockk(relaxed = true)

        aircatClient = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun aircatUnauthorizedPeerRejectedTest() = runBlocking {
        val sourceId = "unknown_rogue_peer"
        every { pairedRegistry.getDevice(sourceId) } returns null

        val intent = TransferIntent(
            transferId = "xfer_unauth_1",
            sourceMichiId = sourceId,
            destinationMichiId = "client_id",
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_unauth_1",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 1000L,
            items = listOf(
                AirCatManifestItem("trk_1", "rem_1", "Song", "Artist", "Album", "mp3", 1000L, "")
            )
        )

        val result = aircatClient.executeTransfer(
            intent = intent,
            manifest = manifest,
            linkClient = mockk(),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue("Unauthorized peer must be rejected", result.isFailure)
        assertTrue(result.exceptionOrNull() is AirCatException.UnauthorizedPeer)
        assertEquals(AirCatTransferState.FAILED, aircatClient.progressEvents.value?.state)
    }

    @Test
    fun aircatManifestVersionMismatchTest() = runBlocking {
        val sourceId = "trusted_peer_1"
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "token")

        val intent = TransferIntent(
            transferId = "xfer_version_mismatch",
            sourceMichiId = sourceId,
            destinationMichiId = "client_id",
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
            manifestVersion = 1, // Intent specifies version 1
        )
        val manifest = AirCatManifest(
            manifestVersion = 2, // Manifest received version 2
            transferId = "xfer_version_mismatch",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 1000L,
        )

        val result = aircatClient.executeTransfer(
            intent = intent,
            manifest = manifest,
            linkClient = mockk(),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue("Manifest version mismatch must be rejected", result.isFailure)
        assertTrue(result.exceptionOrNull() is AirCatException.ManifestVersionMismatch)
    }

    @Test
    fun aircatDestinationCapacityTest() = runBlocking {
        val sourceId = "trusted_peer_1"
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "token")

        val intent = TransferIntent(
            transferId = "xfer_no_capacity",
            sourceMichiId = sourceId,
            destinationMichiId = "client_id",
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_no_capacity",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 500L * 1024 * 1024, // 500 MB
        )

        // Only 150 MB available -> does not satisfy 500 MB + 100 MB margin
        val result = aircatClient.executeTransfer(
            intent = intent,
            manifest = manifest,
            linkClient = mockk(),
            availableStorageBytes = 150L * 1024 * 1024,
        )

        assertTrue("Insufficient storage must fail transfer", result.isFailure)
        assertTrue(result.exceptionOrNull() is AirCatException.InsufficientStorage)
    }

    @Test
    fun aircatDuplicatedTransferIdTest() = runBlocking {
        val sourceId = "trusted_peer_1"
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "token")

        val content = "AirCat Audio Track".toByteArray()
        val peer = FakeAirCatPeer(sourceId)
        peer.setupTrackStreaming("trk_dup", content)

        val intent = TransferIntent(
            transferId = "xfer_duplicate_check",
            sourceMichiId = sourceId,
            destinationMichiId = "client_id",
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_duplicate_check",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = content.size.toLong(),
            items = listOf(
                AirCatManifestItem("trk_dup", "rem_1", "Song", "Artist", "Album", "mp3", content.size.toLong(), sha256(content))
            )
        )

        // First execution succeeds
        val res1 = aircatClient.executeTransfer(
            intent = intent,
            manifest = manifest,
            linkClient = peer.mockClient,
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )
        assertTrue(res1.isSuccess)
        assertEquals(AirCatTransferState.COMPLETED, aircatClient.progressEvents.value?.state)

        // Second execution with same transferId must be rejected
        val res2 = aircatClient.executeTransfer(
            intent = intent,
            manifest = manifest,
            linkClient = peer.mockClient,
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )
        assertTrue("Duplicate transferId must fail", res2.isFailure)
        assertTrue(res2.exceptionOrNull() is AirCatException.DuplicatedTransfer)
    }

    @Test
    fun aircatSuccessfulTransferBindsLibraryTest() = runBlocking {
        val sourceId = "trusted_peer_node"
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Desktop Node", deviceToken = "tok")

        val content = "Hi-Fi Studio Master Audio Stream".toByteArray()
        val expectedHash = sha256(content)
        val peer = FakeAirCatPeer(sourceId)
        peer.setupTrackStreaming("trk_hifi", content)

        val intent = TransferIntent(
            transferId = "xfer_success_binding",
            sourceMichiId = sourceId,
            destinationMichiId = "phone_client",
            sourceLibraryId = "lib_desktop",
            destinationLibraryId = "lib_phone_synced",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_success_binding",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = content.size.toLong(),
            items = listOf(
                AirCatManifestItem("trk_hifi", "remote_hifi_1", "Moonlight", "Michi Jazz", "Night Sessions", "flac", content.size.toLong(), expectedHash)
            )
        )

        val result = aircatClient.executeTransfer(
            intent = intent,
            manifest = manifest,
            linkClient = peer.mockClient,
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue(result.isSuccess)
        val files = result.getOrThrow()
        assertEquals(1, files.size)
        assertTrue(files[0].exists())
        assertEquals(content.size.toLong(), files[0].length())
        assertEquals(expectedHash, sha256(files[0].readBytes()))

        // Verify library registration was invoked with AIRC_AT origin
        io.mockk.coVerify {
            libraryRepo.registerTrackWithLocation(
                identity = match { it.id == "trk_hifi" && it.title == "Moonlight" },
                location = match { it.trackId == "trk_hifi" && it.sourceDeviceId == sourceId },
                libraryId = "lib_phone_synced",
                origin = "AIRC_AT",
            )
        }
    }

    @Test
    fun aircatRevokedPeerRejectedTest() = runBlocking {
        val sourceId = "revoked_peer_1"
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(
            deviceId = sourceId,
            deviceName = "Revoked Node",
            deviceToken = "token",
            isRevoked = true,
        )

        val intent = TransferIntent(
            transferId = "xfer_revoked_1",
            sourceMichiId = sourceId,
            destinationMichiId = "client_id",
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_revoked_1",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 1000L,
            items = listOf(
                AirCatManifestItem("trk_1", "rem_1", "Song", "Artist", "Album", "mp3", 1000L, "")
            )
        )

        val result = aircatClient.executeTransfer(
            intent = intent,
            manifest = manifest,
            linkClient = mockk(),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue("Revoked peer must be rejected", result.isFailure)
        assertTrue(result.exceptionOrNull() is AirCatException.RevokedPeer)
    }

    @Test
    fun aircatExpiredIntentRejectedTest() = runBlocking {
        var currentTime = 10_000L
        aircatClient = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            clock = { currentTime }
        )

        val sourceId = "trusted_peer_exp"
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        val intent = TransferIntent(
            transferId = "xfer_expired_1",
            sourceMichiId = sourceId,
            destinationMichiId = "client_id",
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
            expiresAt = 9_999L // Expired
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_expired_1",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 1000L,
            items = listOf(
                AirCatManifestItem("trk_1", "rem_1", "Song", "Artist", "Album", "mp3", 1000L, "")
            )
        )

        val result = aircatClient.executeTransfer(
            intent = intent,
            manifest = manifest,
            linkClient = mockk(),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue("Expired transfer must fail", result.isFailure)
        assertTrue(result.exceptionOrNull() is AirCatException.TransferExpired)
    }

    @Test
    fun aircatMismatchedTransferIdRejectedTest() = runBlocking {
        val sourceId = "trusted_peer_mis"
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        val intent = TransferIntent(
            transferId = "xfer_intent_id",
            sourceMichiId = sourceId,
            destinationMichiId = "client_id",
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_manifest_id_different",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 1000L,
            items = listOf(
                AirCatManifestItem("trk_1", "rem_1", "Song", "Artist", "Album", "mp3", 1000L, "")
            )
        )

        val result = aircatClient.executeTransfer(
            intent = intent,
            manifest = manifest,
            linkClient = mockk(),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AirCatException.TransferIdMismatch)
    }

    @Test
    fun aircatInvalidManifestStructureRejectedTest() = runBlocking {
        val sourceId = "trusted_peer_struct"
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        val intent = TransferIntent(
            transferId = "xfer_bad_struct",
            sourceMichiId = sourceId,
            destinationMichiId = "client_id",
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
        )
        // Declares 2 tracks and 2000 bytes, but provides 1 item with 1000 bytes
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_bad_struct",
            sourceDeviceId = sourceId,
            totalTracks = 2,
            totalBytes = 2000L,
            items = listOf(
                AirCatManifestItem("trk_1", "rem_1", "Song", "Artist", "Album", "mp3", 1000L, "")
            )
        )

        val result = aircatClient.executeTransfer(
            intent = intent,
            manifest = manifest,
            linkClient = mockk(),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AirCatException.InvalidManifestStructure)
    }

    @Test
    fun aircatPauseAndCancelTransferTest() = runBlocking {
        val transferId = "xfer_cancel_1"
        assertFalse(aircatClient.isTransferCancelled(transferId))
        assertFalse(aircatClient.isTransferPaused(transferId))

        aircatClient.pauseTransfer(transferId)
        assertTrue(aircatClient.isTransferPaused(transferId))

        aircatClient.resumeTransfer(transferId)
        assertFalse(aircatClient.isTransferPaused(transferId))

        aircatClient.cancelTransfer(transferId)
        assertTrue(aircatClient.isTransferCancelled(transferId))
    }
}
