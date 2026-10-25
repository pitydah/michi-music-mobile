package org.michimusic.mobile.devices.aircat

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.michimusic.core.models.LocationAvailability
import org.michimusic.core.models.TrackIdentity
import org.michimusic.core.models.TrackLocation
import org.michimusic.core.models.TrackLocationType
import org.michimusic.data.repository.MusicLibraryRepository
import org.michimusic.link.LinkClient
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

    @Test
    fun aircatPersistentCheckpointResumeAcrossRecreationTest() = runBlocking {
        val sourceId = "trusted_peer_1"
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "token")

        val trackData = "Recoverable audio content".toByteArray()
        val trackSha256 = sha256(trackData)
        val transferKey = "$sourceId:lib_dest:trk_recover_1"
        val completedFile = File(transferManager.musicDir, "${sourceId}_lib_dest_trk_recover_1.mp3")
        completedFile.parentFile?.mkdirs()
        completedFile.writeBytes(trackData)

        val checkpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        coEvery { checkpointRepo.getCheckpoint(transferKey) } returns org.michimusic.core.models.PersistentCheckpoint(
            transferKey = transferKey,
            transferId = "xfer_persisted_1",
            remoteDeviceId = sourceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dest",
            trackId = "trk_recover_1",
            remoteTrackId = "rem_1",
            tempFilePath = "${completedFile.absolutePath}.part",
            finalFilePath = completedFile.absolutePath,
            expectedSize = trackData.size.toLong(),
            receivedBytes = trackData.size.toLong(),
            verifiedBytes = trackData.size.toLong(),
            expectedSha256 = trackSha256,
            transferState = org.michimusic.core.models.TransferStatus.COMPLETED,
            createdAt = 1000L,
            updatedAt = 2000L,
        )

        // Instantiate new client instance simulating application process recreation
        val newAircatClient = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
        )

        val intent = TransferIntent(
            transferId = "xfer_persisted_1",
            sourceMichiId = sourceId,
            destinationMichiId = "client_id",
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dest",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_persisted_1",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = trackData.size.toLong(),
            items = listOf(
                AirCatManifestItem("trk_recover_1", "rem_1", "Recovered Song", "Artist", "Album", "mp3", trackData.size.toLong(), trackSha256)
            )
        )

        val result = newAircatClient.executeTransfer(
            intent = intent,
            manifest = manifest,
            linkClient = mockk(),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue("Should succeed by resuming from checkpoint without re-downloading", result.isSuccess)
        val files = result.getOrThrow()
        assertEquals(1, files.size)
        assertEquals(completedFile.absolutePath, files.first().absolutePath)
        assertEquals(AirCatTransferState.COMPLETED, newAircatClient.progressEvents.value?.state)
    }

    @Test
    fun aircatRecoverIncompleteTransfersResumesPendingCheckpoints() = runBlocking {
        val sourceId = "trusted_peer_resume"
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "token")

        val trackData = "Incomplete recovered audio content".toByteArray()
        val trackSha256 = sha256(trackData)
        val transferKey = "$sourceId:lib_dest:trk_pending_resume"
        val targetFile = File(transferManager.musicDir, "${sourceId}_lib_dest_trk_pending_resume.mp3")
        targetFile.parentFile?.mkdirs()

        val partFile = File("${targetFile.absolutePath}.part")
        val halfSize = trackData.size / 2
        partFile.writeBytes(trackData.copyOfRange(0, halfSize))

        val checkpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        val pendingCp = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = transferKey,
            transferId = "xfer_incomplete_1",
            remoteDeviceId = sourceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dest",
            trackId = "trk_pending_resume",
            remoteTrackId = "rem_pending_1",
            tempFilePath = partFile.absolutePath,
            finalFilePath = targetFile.absolutePath,
            expectedSize = trackData.size.toLong(),
            receivedBytes = halfSize.toLong(),
            verifiedBytes = halfSize.toLong(),
            expectedSha256 = trackSha256,
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
            createdAt = 1000L,
            updatedAt = 2000L,
        )

        coEvery { checkpointRepo.getCheckpointsForDevice(sourceId) } returns listOf(pendingCp)

        // Mock linkClient streaming remaining part
        val mockClient = mockk<org.michimusic.link.LinkClient>(relaxed = true)
        every { mockClient.deviceToken } returns "token"
        io.mockk.every {
            kotlinx.coroutines.runBlocking {
                mockClient.streamTrackRange(
                    trackId = any(),
                    outputStream = any(),
                    startBytes = any(),
                    expectedEtag = any(),
                    bufferSize = any(),
                    onProgress = any(),
                )
            }
        } answers {
            val out = secondArg<java.io.OutputStream>()
            val offset = thirdArg<Long>()
            val remaining = trackData.copyOfRange(offset.toInt(), trackData.size)
            out.write(remaining)
            Result.success(
                org.michimusic.link.StreamRangeResult(
                    bytesRead = remaining.size.toLong(),
                    statusCode = 206,
                    isPartialContent = true,
                    etag = "etag-resume",
                    contentLength = remaining.size.toLong(),
                )
            )
        }

        val client = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
        )

        val recoveryResult = client.recoverIncompleteTransfers(sourceId, mockClient)
        assertTrue("Expected recovery to succeed, but was: ${recoveryResult.exceptionOrNull()}", recoveryResult.isSuccess)
        val files = recoveryResult.getOrThrow()
        assertEquals(1, files.size)
        assertEquals(targetFile.absolutePath, files.first().absolutePath)
        assertTrue(targetFile.exists())
        assertEquals(trackData.size.toLong(), targetFile.length())
    }

    @Test
    fun aircatRecoverIncompleteTransfersFailsWhenPeerIsRevoked() = runBlocking {
        val revokedDeviceId = "revoked_node_123"
        val mockClient = mockk<org.michimusic.link.LinkClient>(relaxed = true)

        val revokedDevice = PairedDevice(
            deviceId = revokedDeviceId,
            deviceName = "Revoked Node",
            deviceToken = "token_revoked",
            lastUrl = "http://192.168.1.55:8400",
            isRevoked = true,
        )
        every { pairedRegistry.getDevice(revokedDeviceId) } returns revokedDevice

        val checkpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        val client = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
        )

        val result = client.recoverIncompleteTransfers(revokedDeviceId, mockClient)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AirCatException.UnauthorizedPeer)
    }

    @Test
    fun aircatRecoverIncompleteTransfersFailsWhenTokenMismatches() = runBlocking {
        val deviceId = "mismatch_node_123"
        val mockClient = mockk<org.michimusic.link.LinkClient>(relaxed = true)
        every { mockClient.deviceToken } returns "token_spoofed_or_stale"

        val device = PairedDevice(
            deviceId = deviceId,
            deviceName = "Legit Node",
            deviceToken = "token_legit_secret",
            lastUrl = "http://192.168.1.55:8400",
            isRevoked = false,
        )
        every { pairedRegistry.getDevice(deviceId) } returns device

        val checkpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        val client = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
        )

        val result = client.recoverIncompleteTransfers(deviceId, mockClient)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AirCatException.UnauthorizedPeer)
    }

    @Test
    fun aircatRecoverIncompleteTransfersAbortsWhenPeerRevokedMidTransfer() = runBlocking {
        val deviceId = "dynamic_revocation_node"
        val mockClient = mockk<org.michimusic.link.LinkClient>(relaxed = true)
        every { mockClient.deviceToken } returns "valid_token"

        var isRevokedFlag = false
        every { pairedRegistry.getDevice(any<String>()) } answers {
            PairedDevice(
                deviceId = deviceId,
                deviceName = "Dynamic Node",
                deviceToken = "valid_token",
                lastUrl = "http://192.168.1.55:8400",
                isRevoked = isRevokedFlag,
            )
        }

        val cp1 = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = "$deviceId:lib:t1",
            transferId = "xfer1",
            remoteDeviceId = deviceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib",
            trackId = "t1",
            remoteTrackId = "t1",
            tempFilePath = "/tmp/t1.part",
            finalFilePath = "/tmp/t1.mp3",
            expectedSize = 100L,
            receivedBytes = 50L,
            verifiedBytes = 0L,
            expectedSha256 = "",
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
        )
        val cp2 = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = "$deviceId:lib:t2",
            transferId = "xfer1",
            remoteDeviceId = deviceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib",
            trackId = "t2",
            remoteTrackId = "t2",
            tempFilePath = "/tmp/t2.part",
            finalFilePath = "/tmp/t2.mp3",
            expectedSize = 100L,
            receivedBytes = 50L,
            verifiedBytes = 0L,
            expectedSha256 = "",
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
        )

        val checkpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        coEvery { checkpointRepo.getCheckpointsForDevice(deviceId) } returns listOf(cp1, cp2)

        val mockTransferManager = mockk<LinkTransferManager>()
        coEvery { mockTransferManager.downloadTrack(any(), any()) } returns Result.success(File.createTempFile("t1_", ".mp3").apply { writeBytes(ByteArray(100)) })

        val client = DefaultAirCatTransferClient(
            transferManager = mockTransferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
        )

        // Make getDevice report revoked when checking t2
        var checkCount = 0
        every { pairedRegistry.getDevice(any<String>()) } answers {
            checkCount++
            PairedDevice(
                deviceId = deviceId,
                deviceName = "Dynamic Node",
                deviceToken = "valid_token",
                lastUrl = "http://192.168.1.55:8400",
                isRevoked = checkCount > 2, // 1st check for peer at start, 2nd check for t1, 3rd check for t2 revoked
            )
        }

        val result = client.recoverIncompleteTransfers(deviceId, mockClient)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AirCatException.UnauthorizedPeer)
        // Only 1 download track call was made before revocation aborted recovery
        coVerify(exactly = 1) { mockTransferManager.downloadTrack(any(), any()) }
    }

    @Test
    fun aircatExecuteTransferFailsWhenLibraryRegistrationFails() = runBlocking {
        val transferId = "aircat_fail_lib_reg"
        val sourceId = "test_source_device_1"
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "token")

        val trackData = "TestDataForFailedLibReg".toByteArray()
        val expectedSha = sha256(trackData)
        val peer = FakeAirCatPeer(sourceId)
        peer.setupTrackStreaming("track_fail_reg", trackData)

        val failingLibraryRepo = mockk<MusicLibraryRepository>()
        coEvery { failingLibraryRepo.getTrackIdentity(any()) } returns null
        coEvery {
            failingLibraryRepo.registerTrackWithLocation(any(), any(), any(), any())
        } returns Result.failure(IllegalStateException("Database constraint violation"))

        val checkpointMap = mutableMapOf<String, org.michimusic.core.models.PersistentCheckpoint>()
        val checkpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        coEvery { checkpointRepo.saveCheckpoint(any()) } answers {
            val cp = firstArg<org.michimusic.core.models.PersistentCheckpoint>()
            checkpointMap[cp.transferKey] = cp
        }
        coEvery { checkpointRepo.updateProgress(any(), any(), any(), any()) } answers {
            val key = firstArg<String>()
            val rec = secondArg<Long>()
            val ver = thirdArg<Long>()
            val state = arg<org.michimusic.core.models.TransferStatus>(3)
            val old = checkpointMap[key]
            if (old != null) {
                checkpointMap[key] = old.copy(receivedBytes = rec, verifiedBytes = ver, transferState = state)
            } else {
                checkpointMap[key] = org.michimusic.core.models.PersistentCheckpoint(
                    transferKey = key,
                    transferId = "xfer",
                    remoteDeviceId = sourceId,
                    sourceLibraryId = "lib_src",
                    destinationLibraryId = "lib_dest",
                    trackId = "track_fail_reg",
                    remoteTrackId = "track_fail_reg",
                    tempFilePath = "",
                    finalFilePath = "",
                    expectedSize = 100L,
                    receivedBytes = rec,
                    verifiedBytes = ver,
                    expectedSha256 = "",
                    transferState = state,
                )
            }
        }
        coEvery { checkpointRepo.getCheckpoint(any()) } answers {
            val queryKey = firstArg<String>()
            checkpointMap[queryKey] ?: checkpointMap.entries.firstOrNull { it.key.startsWith("$queryKey:") }?.value
        }

        val testTransferManager = LinkTransferManager(mockContext, checkpointRepo)
        val client = DefaultAirCatTransferClient(
            transferManager = testTransferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = failingLibraryRepo,
            checkpointRepository = checkpointRepo,
        )

        val manifestItem = AirCatManifestItem(
            logicalTrackId = "track_fail_reg",
            remoteTrackId = "track_fail_reg",
            title = "Failed Registration Track",
            artist = "Artist",
            album = "Album",
            format = "mp3",
            size = trackData.size.toLong(),
            sha256 = expectedSha,
        )

        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = transferId,
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = trackData.size.toLong(),
            items = listOf(manifestItem),
        )

        val intent = TransferIntent(
            transferId = transferId,
            sourceMichiId = sourceId,
            destinationMichiId = "dest_client",
            sourceLibraryId = "lib_source",
            destinationLibraryId = "lib_dest",
        )

        val result = client.executeTransfer(intent, manifest, peer.mockClient, availableStorageBytes = 10L * 1024 * 1024 * 1024)
        assertTrue("executeTransfer should fail if library registration fails", result.isFailure)

        // Checkpoint must NOT be marked COMPLETED
        val checkpoint = checkpointRepo.getCheckpoint("$sourceId:lib_dest:track_fail_reg")
        assertEquals(org.michimusic.core.models.TransferStatus.FAILED, checkpoint?.transferState)
    }

    @Test
    fun aircatRecoverIncompleteTransfers_strictCompositeCriteria_matchesDestinationLibraryAndHash() = runBlocking {
        val deviceId = "node_composite_strict"
        val mockClient = mockk<org.michimusic.link.LinkClient>(relaxed = true)
        every { mockClient.deviceToken } returns "token_strict"
        every { pairedRegistry.getDevice(deviceId) } returns PairedDevice(
            deviceId = deviceId,
            deviceName = "Strict Node",
            deviceToken = "token_strict",
            lastUrl = "http://192.168.1.10:8400",
        )

        val cp1 = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = "$deviceId:lib_target_1:track_strict_1",
            transferId = "xfer_1",
            remoteDeviceId = deviceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_target_1",
            trackId = "track_strict_1",
            remoteTrackId = "track_strict_1",
            tempFilePath = File(tempDir, "temp1.part").absolutePath,
            finalFilePath = File(tempDir, "final1.mp3").absolutePath,
            expectedSize = 1000L,
            receivedBytes = 500L,
            verifiedBytes = 0L,
            expectedSha256 = "hash_valid_1",
            etag = "",
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
            createdAt = 100L,
            updatedAt = 200L,
        )

        val cp2 = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = "$deviceId:lib_wrong_dest:track_strict_2",
            transferId = "xfer_2",
            remoteDeviceId = deviceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_wrong_dest",
            trackId = "track_strict_2",
            remoteTrackId = "track_strict_2",
            tempFilePath = File(tempDir, "temp2.part").absolutePath,
            finalFilePath = File(tempDir, "final2.mp3").absolutePath,
            expectedSize = 2000L,
            receivedBytes = 1000L,
            verifiedBytes = 0L,
            expectedSha256 = "hash_valid_2",
            etag = "",
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
            createdAt = 100L,
            updatedAt = 200L,
        )

        val checkpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        coEvery { checkpointRepo.getCheckpointsForDevice(deviceId) } returns listOf(cp1, cp2)

        val mockTransferManager = mockk<LinkTransferManager>()
        val dummyFile = File(tempDir, "final1.mp3").also { it.writeText("hello") }
        coEvery { mockTransferManager.downloadTrack(any(), any()) } returns Result.success(dummyFile)

        val client = DefaultAirCatTransferClient(
            transferManager = mockTransferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
        )

        // Only authorize track_strict_1 destined for lib_target_1
        val criteria = listOf(
            AuthorizedTransferCriteria(
                trackId = "track_strict_1",
                destinationLibraryId = "lib_target_1",
                expectedSize = 1000L,
                expectedSha256 = "hash_valid_1",
            ),
            AuthorizedTransferCriteria(
                trackId = "track_strict_2",
                destinationLibraryId = "lib_correct_dest_not_wrong", // does not match cp2
                expectedSize = 2000L,
                expectedSha256 = "hash_valid_2",
            )
        )

        val result = client.recoverIncompleteTransfers(
            remoteDeviceId = deviceId,
            linkClient = mockClient,
            authorizedCriteria = criteria,
        )

        assertTrue(result.isSuccess)
        val files = result.getOrThrow()
        assertEquals(1, files.size)
        // Exactly one download was performed (for track_strict_1), cp2 was rejected
        coVerify(exactly = 1) { mockTransferManager.downloadTrack(any(), any()) }
    }

    @Test
    fun aircatRecoverIncompleteTransfers_strictHashMatching_rejectsEmptyOrMismatchingHashWhenHashIsExpected() = runBlocking {
        val deviceId = "node_hash_matching_strict"
        val mockClient = mockk<org.michimusic.link.LinkClient>(relaxed = true)
        every { mockClient.deviceToken } returns "token_strict"
        every { pairedRegistry.getDevice(deviceId) } returns PairedDevice(
            deviceId = deviceId,
            deviceName = "Hash Node",
            deviceToken = "token_strict",
            lastUrl = "http://192.168.1.10:8400",
        )

        // cpEmpty: Checkpoint has empty hash, but criteria expects a specific hash -> MUST BE REJECTED
        val cpEmpty = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = "$deviceId:lib_1:track_empty_hash",
            transferId = "xfer_empty",
            remoteDeviceId = deviceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_1",
            trackId = "track_empty_hash",
            remoteTrackId = "track_empty_hash",
            tempFilePath = File(tempDir, "temp_empty.part").absolutePath,
            finalFilePath = File(tempDir, "final_empty.mp3").absolutePath,
            expectedSize = 1000L,
            receivedBytes = 500L,
            verifiedBytes = 0L,
            expectedSha256 = "", // Empty hash in checkpoint!
            etag = "",
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
            createdAt = 100L,
            updatedAt = 200L,
        )

        // cpMismatch: Checkpoint has mismatched hash -> MUST BE REJECTED
        val cpMismatch = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = "$deviceId:lib_1:track_mismatch_hash",
            transferId = "xfer_mismatch",
            remoteDeviceId = deviceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_1",
            trackId = "track_mismatch_hash",
            remoteTrackId = "track_mismatch_hash",
            tempFilePath = File(tempDir, "temp_mismatch.part").absolutePath,
            finalFilePath = File(tempDir, "final_mismatch.mp3").absolutePath,
            expectedSize = 1000L,
            receivedBytes = 500L,
            verifiedBytes = 0L,
            expectedSha256 = "corrupted_hash",
            etag = "",
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
            createdAt = 100L,
            updatedAt = 200L,
        )

        // cpValid: Checkpoint has matching hash -> MUST BE ACCEPTED
        val cpValid = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = "$deviceId:lib_1:track_valid_hash",
            transferId = "xfer_valid",
            remoteDeviceId = deviceId,
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_1",
            trackId = "track_valid_hash",
            remoteTrackId = "track_valid_hash",
            tempFilePath = File(tempDir, "temp_valid.part").absolutePath,
            finalFilePath = File(tempDir, "final_valid.mp3").absolutePath,
            expectedSize = 1000L,
            receivedBytes = 500L,
            verifiedBytes = 0L,
            expectedSha256 = "EXPECTED_VALID_HASH_A1B2",
            etag = "",
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
            createdAt = 100L,
            updatedAt = 200L,
        )

        val checkpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        coEvery { checkpointRepo.getCheckpointsForDevice(deviceId) } returns listOf(cpEmpty, cpMismatch, cpValid)

        val mockTransferManager = mockk<LinkTransferManager>()
        val dummyFile = File(tempDir, "final_valid.mp3").also { it.writeText("hello valid") }
        coEvery { mockTransferManager.downloadTrack(any(), any()) } returns Result.success(dummyFile)

        val client = DefaultAirCatTransferClient(
            transferManager = mockTransferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
        )

        val criteria = listOf(
            AuthorizedTransferCriteria(
                trackId = "track_empty_hash",
                destinationLibraryId = "lib_1",
                expectedSize = 1000L,
                expectedSha256 = "expected_hash_empty",
            ),
            AuthorizedTransferCriteria(
                trackId = "track_mismatch_hash",
                destinationLibraryId = "lib_1",
                expectedSize = 1000L,
                expectedSha256 = "expected_hash_mismatch",
            ),
            AuthorizedTransferCriteria(
                trackId = "track_valid_hash",
                destinationLibraryId = "lib_1",
                expectedSize = 1000L,
                expectedSha256 = "expected_valid_hash_a1b2", // Case insensitive match
            ),
        )

        val result = client.recoverIncompleteTransfers(
            remoteDeviceId = deviceId,
            linkClient = mockClient,
            authorizedCriteria = criteria,
        )

        assertTrue(result.isSuccess)
        val files = result.getOrThrow()
        assertEquals("Only cpValid should be recovered; empty and mismatching hashes must be rejected", 1, files.size)
        coVerify(exactly = 1) { mockTransferManager.downloadTrack(any(), any()) }
    }

    @Test
    fun recoverIncompleteTransfers_rejectsCheckpoint_whenPlanIdDoesNotMatch() = runBlocking {
        val deviceId = "dev_plan_isolation"
        every { pairedRegistry.getDevice(deviceId) } returns PairedDevice(deviceId = deviceId, deviceName = "Node", deviceToken = "tok")

        val cpPlanA = org.michimusic.core.models.PersistentCheckpoint(
            transferKey = "$deviceId:lib_1:track_iso:plan_A",
            transferId = "plan_A",
            remoteDeviceId = deviceId,
            sourceLibraryId = "lib_1",
            destinationLibraryId = "lib_1",
            trackId = "track_iso",
            remoteTrackId = "track_iso",
            tempFilePath = File(tempDir, "plan_A.part").absolutePath,
            finalFilePath = File(tempDir, "plan_A.mp3").absolutePath,
            expectedSize = 1000L,
            receivedBytes = 500L,
            verifiedBytes = 500L,
            expectedSha256 = "hash123",
            etag = "",
            transferState = org.michimusic.core.models.TransferStatus.DOWNLOADING,
            createdAt = 100L,
            updatedAt = 200L,
        )

        val checkpointRepo = mockk<org.michimusic.core.models.TransferCheckpointRepository>(relaxed = true)
        coEvery { checkpointRepo.getCheckpointsForDevice(deviceId) } returns listOf(cpPlanA)

        val mockTransferManager = mockk<LinkTransferManager>()
        val client = DefaultAirCatTransferClient(
            transferManager = mockTransferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
        )

        // Authorized criteria specifies plan_B
        val criteria = listOf(
            AuthorizedTransferCriteria(
                trackId = "track_iso",
                destinationLibraryId = "lib_1",
                expectedSize = 1000L,
                expectedSha256 = "hash123",
                planId = "plan_B",
            )
        )

        val mockClient = mockk<LinkClient>(relaxed = true)
        every { mockClient.deviceToken } returns "tok"

        val result = client.recoverIncompleteTransfers(
            remoteDeviceId = deviceId,
            linkClient = mockClient,
            authorizedCriteria = criteria,
        )

        assertTrue(result.isSuccess)
        val files = result.getOrThrow()
        assertEquals("Checkpoints from plan_A must NOT be recovered under plan_B criteria", 0, files.size)
        coVerify(exactly = 0) { mockTransferManager.downloadTrack(any(), any()) }
    }

    @Test
    fun executeTransfer_failsClosed_whenExistingIdentityHasConflictingContentHash() = runBlocking {
        val sourceId = "peer_conflict"
        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(deviceId = sourceId, deviceName = "Node", deviceToken = "tok")

        // Seed library repository with an existing identity for trk_conflict with hash_OLD
        coEvery { libraryRepo.getTrackIdentity("trk_conflict") } returns org.michimusic.core.models.TrackIdentity(
            id = "trk_conflict",
            title = "Existing Track",
            artist = "Artist",
            album = "Album",
            durationMs = 1000L,
            contentHash = "hash_OLD_123",
        )

        val intent = TransferIntent(
            transferId = "xfer_conflict",
            sourceMichiId = sourceId,
            destinationMichiId = "client_id",
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dst",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_conflict",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 1000L,
            items = listOf(
                AirCatManifestItem("trk_conflict", "rem_1", "Song", "Artist", "Album", "mp3", 1000L, "hash_NEW_456")
            )
        )

        val result = aircatClient.executeTransfer(
            intent = intent,
            manifest = manifest,
            linkClient = mockk(),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue("Conflicting content hash must fail execution", result.isFailure)
        assertTrue(result.exceptionOrNull() is AirCatException.CorruptedPayload)
    }

    @Test
    fun aircatTransfer_failsClosed_whenLibraryRepositoryIsNull() = runBlocking {
        val clientWithoutRepo = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = null,
            checkpointRepository = mockk(relaxed = true),
        )

        val intent = TransferIntent(
            transferId = "xfer_no_repo",
            sourceMichiId = "dev_src",
            destinationMichiId = "client_me",
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dest",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_no_repo",
            sourceDeviceId = "dev_src",
            totalTracks = 0,
            totalBytes = 0L,
            items = emptyList(),
        )

        val execResult = clientWithoutRepo.executeTransfer(
            intent = intent,
            manifest = manifest,
            linkClient = mockk(relaxed = true),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )
        assertTrue("executeTransfer must fail when libraryRepository is null", execResult.isFailure)
        assertTrue(execResult.exceptionOrNull() is AirCatException.ProtocolError)

        val recoverResult = clientWithoutRepo.recoverIncompleteTransfers(
            remoteDeviceId = "dev_src",
            linkClient = mockk(relaxed = true),
        )
        assertTrue("recoverIncompleteTransfers must fail when libraryRepository is null", recoverResult.isFailure)
        assertTrue(recoverResult.exceptionOrNull() is AirCatException.ProtocolError)
    }

    @Test
    fun aircatTransfer_nonDestructivePolicy_neverDeletesExistingRegisteredTrackOnConflict() = runBlocking {
        val sourceId = "node_registered_conflict"
        val existingFile = File(tempDir, "existing_valid_song.flac")
        existingFile.writeBytes("Pre-existing high quality audio".toByteArray())
        assertTrue(existingFile.exists())

        every { pairedRegistry.getDevice(sourceId) } returns PairedDevice(
            deviceId = sourceId,
            deviceName = "Conflict Node",
            deviceToken = "token_ok",
            lastUrl = "http://192.168.1.100:8400",
        )

        coEvery { libraryRepo.getTrackIdentity("trk_registered") } returns TrackIdentity(
            id = "trk_registered",
            title = "Existing Song",
            artist = "Artist",
            album = "Album",
            durationMs = 120000L,
            contentHash = "hash_ORIGINAL_LOCAL",
        )
        // Simulate existing file is a registered location
        coEvery { libraryRepo.getLocationsForTrack("trk_registered") } returns listOf(
            TrackLocation(
                id = "loc_local_1",
                trackId = "trk_registered",
                type = TrackLocationType.SYNCED_MANAGED,
                uri = existingFile.absolutePath,
                availability = LocationAvailability.AVAILABLE,
            )
        )

        val intent = TransferIntent(
            transferId = "xfer_reg_conflict",
            sourceMichiId = sourceId,
            destinationMichiId = "client_me",
            sourceLibraryId = "lib_src",
            destinationLibraryId = "lib_dest",
        )
        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_reg_conflict",
            sourceDeviceId = sourceId,
            totalTracks = 1,
            totalBytes = 1000L,
            items = listOf(
                AirCatManifestItem("trk_registered", "rem_1", "Song", "Artist", "Album", "flac", 1000L, "hash_DIFFERENT_INCOMING")
            )
        )

        val result = aircatClient.executeTransfer(
            intent = intent,
            manifest = manifest,
            linkClient = mockk(relaxed = true),
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AirCatException.CorruptedPayload)
        // Decisive check: existing valid local file MUST NOT be deleted
        assertTrue("Registered local file must remain intact and non-deleted", existingFile.exists())
    }
}

