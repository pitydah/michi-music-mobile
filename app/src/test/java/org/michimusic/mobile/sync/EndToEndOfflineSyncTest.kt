package org.michimusic.mobile.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.DownloadItem
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.PersistentCheckpoint
import org.michimusic.core.models.TransferCheckpointRepository
import org.michimusic.core.models.TransferStatus
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.DefaultMusicLibraryRepository
import org.michimusic.data.repository.MusicLibraryRepository
import org.michimusic.data.repository.RoomTransferCheckpointRepository
import org.michimusic.link.LinkTransferManager
import org.michimusic.link.PairedDevice
import org.michimusic.link.PairedDeviceRegistry
import org.michimusic.link.StreamRangeResult
import org.michimusic.mobile.devices.aircat.AirCatManifest
import org.michimusic.mobile.devices.aircat.AirCatManifestItem
import org.michimusic.mobile.devices.aircat.DefaultAirCatTransferClient
import org.michimusic.mobile.devices.aircat.FakeAirCatPeer
import org.michimusic.mobile.devices.aircat.TransferIntent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EndToEndOfflineSyncTest {

    private lateinit var tempDir: File
    private lateinit var appContext: Context
    private lateinit var database: MichiDatabase
    private lateinit var checkpointRepo: TransferCheckpointRepository
    private lateinit var libraryRepo: MusicLibraryRepository
    private lateinit var transferManager: LinkTransferManager
    private lateinit var pairedRegistry: PairedDeviceRegistry

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("e2e_sync_test").toFile()
        appContext = ApplicationProvider.getApplicationContext()

        // Build a real in-memory Room database with all DAOs active
        database = Room.inMemoryDatabaseBuilder(appContext, MichiDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        checkpointRepo = RoomTransferCheckpointRepository(database.transferCheckpointDao())
        libraryRepo = DefaultMusicLibraryRepository(
            libraryDao = database.libraryDao(),
            identityDao = database.trackIdentityDao(),
            locationDao = database.trackLocationDao(),
            membershipDao = database.libraryMembershipDao(),
        )

        val contextMock = mockk<Context>(relaxed = true)
        every { contextMock.filesDir } returns tempDir

        transferManager = LinkTransferManager(contextMock, checkpointRepo)
        pairedRegistry = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testCompleteOfflineSyncFlowWithRealRoomPersistence() = runBlocking {
        val serverId = "trusted_desktop_studio"
        val destLibId = "lib_phone_offline_synced"

        // Ensure destination library exists in Room
        database.libraryDao().insertLibrary(
            org.michimusic.data.cache.LibraryEntity(
                libraryId = destLibId,
                name = "Phone Synced",
                type = "SYNCED",
                ownerId = serverId,
                isWritable = true,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        every { pairedRegistry.getDevice(serverId) } returns PairedDevice(
            deviceId = serverId,
            deviceName = "Desktop Master Hub",
            deviceToken = "tok_master_999",
            lastUrl = "http://192.168.1.120:8400",
        )

        val trackData1 = "FLAC Lossless Audio Stream - Part One 24bit/96kHz".toByteArray()
        val trackData2 = "FLAC Lossless Audio Stream - Part Two 24bit/96kHz".toByteArray()
        val hash1 = sha256(trackData1)
        val hash2 = sha256(trackData2)

        val peer = FakeAirCatPeer(serverId)
        peer.setupTrackStreaming("trk_lossless_1", trackData1)
        peer.setupTrackStreaming("trk_lossless_2", trackData2)

        val aircatClient = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
        )

        val intent = TransferIntent(
            transferId = "xfer_e2e_resumable_001",
            sourceMichiId = serverId,
            destinationMichiId = "client_pixel",
            sourceLibraryId = "lib_desktop_hifi",
            destinationLibraryId = destLibId,
        )

        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_e2e_resumable_001",
            sourceDeviceId = serverId,
            totalTracks = 2,
            totalBytes = (trackData1.size + trackData2.size).toLong(),
            items = listOf(
                AirCatManifestItem("trk_lossless_1", "rem_1", "Autumn Leaves", "Bill Michi", "Jazz Master", "flac", trackData1.size.toLong(), hash1),
                AirCatManifestItem("trk_lossless_2", "rem_2", "Blue in Green", "Miles Michi", "Kind of Blue", "flac", trackData2.size.toLong(), hash2),
            )
        )

        // Execute E2E transfer
        val result = aircatClient.executeTransfer(
            intent = intent,
            manifest = manifest,
            linkClient = peer.mockClient,
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        assertTrue("Transfer must succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        val downloaded = result.getOrThrow()
        assertEquals(2, downloaded.size)

        // 1. Verify checkpoints were persisted directly into SQLite Room table
        val checkpoints = checkpointRepo.getCheckpointsForDevice(serverId)
        assertEquals(2, checkpoints.size)
        assertTrue(checkpoints.all { it.transferState == TransferStatus.COMPLETED })

        val key1 = "$serverId:$destLibId:trk_lossless_1"
        val cp1 = checkpointRepo.getCheckpoint(key1)
        assertNotNull("Checkpoint 1 must exist in Room", cp1)
        assertEquals(trackData1.size.toLong(), cp1?.receivedBytes)
        assertEquals(TransferStatus.COMPLETED, cp1?.transferState)

        // 2. Verify audio files exist on disk with valid checksum and are absolute paths in transferManager.musicDir
        val f1 = downloaded[0]
        val f2 = downloaded[1]
        assertTrue(f1.exists())
        assertTrue(f2.exists())
        assertEquals(hash1, sha256(f1.readBytes()))
        assertEquals(hash2, sha256(f2.readBytes()))
        assertEquals(transferManager.musicDir.absolutePath, f1.parentFile?.absolutePath)

        // 3. Verify real Room Multi-Library persistence (identities, locations, and memberships)
        val resolvedTracks = libraryRepo.observeTracks(LibraryScope.One(destLibId)).first()
        assertEquals(2, resolvedTracks.size)

        val track1 = resolvedTracks.firstOrNull { it.identity.id == "trk_lossless_1" }
        assertNotNull("Track 1 must be registered in Room", track1)
        assertEquals("Autumn Leaves", track1?.identity?.title)
        assertNotNull("Track 1 location must be resolved", track1?.preferredLocation)
        assertEquals(f1.absolutePath, track1?.preferredLocation?.uri)

        val track2 = resolvedTracks.firstOrNull { it.identity.id == "trk_lossless_2" }
        assertNotNull("Track 2 must be registered in Room", track2)
        assertEquals("Blue in Green", track2?.identity?.title)
        assertEquals(f2.absolutePath, track2?.preferredLocation?.uri)
    }

    @Test
    fun testProcessInterruptionRecoveryWithPersistentCheckpoint() = runBlocking {
        val serverId = "desktop_node_interrupted"
        val destLibId = "lib_phone_offline_synced"
        val trackId = "trk_resumable_long"
        val transferKey = trackId // used directly by LinkTransferManager preflight

        val fullData = "Complete High-Resolution Stream Payload Bytes (100% data)".toByteArray()
        val partialData = "Complete High-Resolution Stream Payload ".toByteArray() // first 40 bytes
        val fullHash = sha256(fullData)

        // 1. Simulate prior crashed transfer by persisting a partial checkpoint in Room
        val finalFile = File(transferManager.musicDir, "$trackId.flac")
        val partFile = File(transferManager.musicDir, "$trackId.flac.part")
        partFile.writeBytes(partialData)

        val priorCheckpoint = PersistentCheckpoint(
            transferKey = transferKey,
            transferId = "xfer_interrupted_001",
            remoteDeviceId = serverId,
            sourceLibraryId = "lib_source",
            destinationLibraryId = destLibId,
            trackId = trackId,
            remoteTrackId = "rem_resumable",
            tempFilePath = partFile.absolutePath,
            finalFilePath = finalFile.absolutePath,
            expectedSize = fullData.size.toLong(),
            receivedBytes = partialData.size.toLong(),
            verifiedBytes = partialData.size.toLong(),
            expectedSha256 = fullHash,
            etag = "etag-v1-hash",
            transferState = TransferStatus.PAUSED,
            createdAt = System.currentTimeMillis() - 10000,
            updatedAt = System.currentTimeMillis() - 5000,
        )
        checkpointRepo.saveCheckpoint(priorCheckpoint)

        // 2. Simulate process death: create a BRAND NEW LinkTransferManager with clean in-memory state
        val contextMock2 = mockk<Context>(relaxed = true)
        every { contextMock2.filesDir } returns tempDir
        val freshTransferManager = LinkTransferManager(contextMock2, checkpointRepo)

        // 3. Setup peer with resume support (mock client returning the remaining slice)
        val peer = FakeAirCatPeer(serverId)
        val mockClient = peer.mockClient
        val remainingData = fullData.copyOfRange(partialData.size, fullData.size)

        // Intercept streamTrackRange: write remaining bytes to outputStream
        io.mockk.coEvery {
            mockClient.streamTrackRange(
                trackId = trackId,
                outputStream = any(),
                startBytes = partialData.size.toLong(),
                expectedEtag = any(),
                onProgress = any(),
            )
        } answers {
            val os = secondArg<java.io.OutputStream>()
            os.write(remainingData)
            Result.success(
                StreamRangeResult(
                    bytesRead = remainingData.size.toLong(),
                    statusCode = 206,
                    isPartialContent = true,
                    etag = "etag-v1-hash",
                )
            )
        }

        // 4. Download resuming from checkpoint
        val downloadItem = DownloadItem(
            trackId = trackId,
            title = "Resumable Song",
            format = "flac",
            checksum = fullHash,
            size = fullData.size.toLong(),
        )

        val res = freshTransferManager.downloadTrack(mockClient, downloadItem)
        assertTrue("Resumed download must succeed: ${res.exceptionOrNull()?.message}", res.isSuccess)

        val resultFile = res.getOrThrow()
        assertTrue(resultFile.exists())
        assertEquals(fullData.size.toLong(), resultFile.length())
        assertEquals(fullHash, sha256(resultFile.readBytes()))

        // 5. Verify that freshTransferManager discovered the checkpoint in Room and updated it
        val finalCp = checkpointRepo.getCheckpoint(transferKey)
        assertNotNull(finalCp)
        assertEquals(TransferStatus.DOWNLOADING, finalCp?.transferState)
    }

    @Test
    fun testTransactionIntegrityWhenLibraryRegistrationFails() = runBlocking {
        val serverId = "trusted_desktop_failing"
        val destLibId = "lib_phone_failing"

        every { pairedRegistry.getDevice(serverId) } returns PairedDevice(
            deviceId = serverId,
            deviceName = "Desktop Master Hub",
            deviceToken = "tok_master_999",
            lastUrl = "http://192.168.1.120:8400",
        )

        val trackData = "Song data".toByteArray()
        val hash = sha256(trackData)
        val peer = FakeAirCatPeer(serverId)
        peer.setupTrackStreaming("trk_fail", trackData)

        // Failing library repository
        val failingLibraryRepo = mockk<MusicLibraryRepository>(relaxed = true)
        io.mockk.coEvery { failingLibraryRepo.registerTrackWithLocation(any(), any(), any(), any()) } throws RuntimeException("SQLite disk I/O error or constraint failure")

        val aircatClient = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = failingLibraryRepo,
            checkpointRepository = checkpointRepo,
        )

        val intent = TransferIntent(
            transferId = "xfer_fail_test",
            sourceMichiId = serverId,
            destinationMichiId = "client_pixel",
            sourceLibraryId = "lib_desktop",
            destinationLibraryId = destLibId,
        )

        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = "xfer_fail_test",
            sourceDeviceId = serverId,
            totalTracks = 1,
            totalBytes = trackData.size.toLong(),
            items = listOf(
                AirCatManifestItem("trk_fail", "rem_fail", "Failed Track", "Unknown", "Unknown", "mp3", trackData.size.toLong(), hash)
            )
        )

        val result = aircatClient.executeTransfer(
            intent = intent,
            manifest = manifest,
            linkClient = peer.mockClient,
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        // Transfer must fail due to DB error
        assertTrue("Transfer must fail when library registration fails", result.isFailure)

        // Verify checkpoint does NOT remain COMPLETED
        val key = "$serverId:$destLibId:trk_fail"
        val cp = checkpointRepo.getCheckpoint(key)
        assertNotNull(cp)
        assertEquals("Checkpoint must be FAILED if library registration fails", TransferStatus.FAILED, cp?.transferState)
    }

    @Test
    fun testTwoServersWithSameTrackIdDoNotCollideInCheckpoints() = runBlocking {
        val server1 = "server_alpha"
        val server2 = "server_beta"
        val destLibId = "lib_phone_synced"
        val sharedTrackId = "trk_common_song_42"

        val server1Data = "Server 1 audio payload version 1".toByteArray()
        val server2Data = "Server 2 completely different audio bytes 2".toByteArray()

        val peer1 = FakeAirCatPeer(server1)
        peer1.setupTrackStreaming(sharedTrackId, server1Data)

        val peer2 = FakeAirCatPeer(server2)
        peer2.setupTrackStreaming(sharedTrackId, server2Data)

        val item1 = DownloadItem(
            trackId = sharedTrackId,
            title = "Alpha Track",
            format = "mp3",
            checksum = sha256(server1Data),
            size = server1Data.size.toLong(),
            remoteDeviceId = server1,
            destinationLibraryId = destLibId,
            transferKey = "$server1:$destLibId:$sharedTrackId",
        )

        val item2 = DownloadItem(
            trackId = sharedTrackId,
            title = "Beta Track",
            format = "mp3",
            checksum = sha256(server2Data),
            size = server2Data.size.toLong(),
            remoteDeviceId = server2,
            destinationLibraryId = destLibId,
            transferKey = "$server2:$destLibId:$sharedTrackId",
        )

        // 1. Save separate initial checkpoints for both servers
        checkpointRepo.saveCheckpoint(
            PersistentCheckpoint(
                transferKey = item1.effectiveTransferKey,
                transferId = "xfer_server1",
                remoteDeviceId = server1,
                sourceLibraryId = "lib_server1",
                destinationLibraryId = destLibId,
                trackId = sharedTrackId,
                remoteTrackId = "rem_shared_1",
                tempFilePath = File(tempDir, "server1.part").absolutePath,
                finalFilePath = File(tempDir, "server1.mp3").absolutePath,
                expectedSize = server1Data.size.toLong(),
                receivedBytes = 10L,
                verifiedBytes = 10L,
                expectedSha256 = item1.checksum,
                transferState = TransferStatus.PAUSED,
                updatedAt = 1000L,
            )
        )

        checkpointRepo.saveCheckpoint(
            PersistentCheckpoint(
                transferKey = item2.effectiveTransferKey,
                transferId = "xfer_server2",
                remoteDeviceId = server2,
                sourceLibraryId = "lib_server2",
                destinationLibraryId = destLibId,
                trackId = sharedTrackId,
                remoteTrackId = "rem_shared_2",
                tempFilePath = File(tempDir, "server2.part").absolutePath,
                finalFilePath = File(tempDir, "server2.mp3").absolutePath,
                expectedSize = server2Data.size.toLong(),
                receivedBytes = 20L,
                verifiedBytes = 20L,
                expectedSha256 = item2.checksum,
                transferState = TransferStatus.PAUSED,
                updatedAt = 2000L,
            )
        )

        // 2. Verify that checkpointRepository queries each specific checkpoint by effectiveTransferKey
        val cp1 = checkpointRepo.getCheckpoint(item1.effectiveTransferKey)
        val cp2 = checkpointRepo.getCheckpoint(item2.effectiveTransferKey)

        assertNotNull(cp1)
        assertNotNull(cp2)
        assertEquals(10L, cp1?.receivedBytes)
        assertEquals(20L, cp2?.receivedBytes)
        assertEquals(server1, cp1?.remoteDeviceId)
        assertEquals(server2, cp2?.remoteDeviceId)

        // 3. Verify that updating progress for server 1 does not overwrite or mutate checkpoint 2
        checkpointRepo.updateProgress(
            transferKey = item1.effectiveTransferKey,
            receivedBytes = server1Data.size.toLong(),
            verifiedBytes = server1Data.size.toLong(),
            state = TransferStatus.DOWNLOADING,
        )

        val refreshedCp1 = checkpointRepo.getCheckpoint(item1.effectiveTransferKey)
        val refreshedCp2 = checkpointRepo.getCheckpoint(item2.effectiveTransferKey)

        assertEquals(server1Data.size.toLong(), refreshedCp1?.receivedBytes)
        assertEquals(20L, refreshedCp2?.receivedBytes)
        assertEquals(TransferStatus.PAUSED, refreshedCp2?.transferState)
    }

    @Test
    fun testAirCatInteractivePauseResumeCancel() = runBlocking {
        val serverId = "desktop_node_aircat"
        val destLibId = "lib_phone_aircat"

        every { pairedRegistry.getDevice(serverId) } returns PairedDevice(
            deviceId = serverId,
            deviceName = "Desktop Master Hub",
            deviceToken = "tok_master_aircat",
            lastUrl = "http://192.168.1.130:8400",
        )

        val aircatClient = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
        )

        val transferId = "xfer_pause_resume_cancel"

        // Test pause, resume, cancel methods
        aircatClient.pauseTransfer(transferId)
        val pausedProgress = aircatClient.progressEvents.first()
        assertNotNull(pausedProgress)
        assertEquals(org.michimusic.mobile.devices.aircat.AirCatTransferState.PAUSED, pausedProgress?.state)
        assertEquals(transferId, pausedProgress?.transferId)

        aircatClient.resumeTransfer(transferId)
        val resumedProgress = aircatClient.progressEvents.first()
        assertNotNull(resumedProgress)
        assertEquals(org.michimusic.mobile.devices.aircat.AirCatTransferState.TRANSFERRING, resumedProgress?.state)

        aircatClient.cancelTransfer(transferId)
        val cancelledProgress = aircatClient.progressEvents.first()
        assertNotNull(cancelledProgress)
        assertEquals(org.michimusic.mobile.devices.aircat.AirCatTransferState.CANCELLED, cancelledProgress?.state)
    }

    @Test
    fun testDualServerPhysicalFileIsolation() = runBlocking {
        val server1 = "desktop_studio_hub"
        val server2 = "laptop_travel_node"
        val destLib1 = "lib_destination_studio"
        val destLib2 = "lib_destination_travel"
        val sharedTrackId = "trk_coltrane_giant_steps"

        val server1Audio = "Giant Steps 24-bit 192kHz Master (Studio Studio Hub)".toByteArray()
        val server2Audio = "Giant Steps 16-bit 44.1kHz Vinyl Rip (Laptop Node)".toByteArray()

        val peer1 = FakeAirCatPeer(server1)
        peer1.setupTrackStreaming(sharedTrackId, server1Audio)

        val peer2 = FakeAirCatPeer(server2)
        peer2.setupTrackStreaming(sharedTrackId, server2Audio)

        val item1 = DownloadItem(
            trackId = sharedTrackId,
            title = "Giant Steps Studio",
            format = "flac",
            checksum = sha256(server1Audio),
            size = server1Audio.size.toLong(),
            remoteDeviceId = server1,
            destinationLibraryId = destLib1,
            transferKey = "$server1:$destLib1:$sharedTrackId",
        )

        val item2 = DownloadItem(
            trackId = sharedTrackId,
            title = "Giant Steps Vinyl",
            format = "flac",
            checksum = sha256(server2Audio),
            size = server2Audio.size.toLong(),
            remoteDeviceId = server2,
            destinationLibraryId = destLib2,
            transferKey = "$server2:$destLib2:$sharedTrackId",
        )

        // Download both tracks with identical logical trackId from both servers
        val client1 = peer1.mockClient
        val client2 = peer2.mockClient

        val file1Result = transferManager.downloadTrack(client1, item1)
        val file2Result = transferManager.downloadTrack(client2, item2)

        assertTrue("File 1 download must succeed", file1Result.isSuccess)
        assertTrue("File 2 download must succeed", file2Result.isSuccess)

        val file1 = file1Result.getOrThrow()
        val file2 = file2Result.getOrThrow()

        // 1. Files must have distinct isolated names on disk
        assertTrue("Files must have distinct paths on disk", file1.absolutePath != file2.absolutePath)
        assertTrue("File 1 name must contain server1 identity", file1.name.contains(server1))
        assertTrue("File 2 name must contain server2 identity", file2.name.contains(server2))

        // 2. Both files must exist simultaneously without overwriting each other
        assertTrue("File 1 must exist", file1.exists())
        assertTrue("File 2 must exist", file2.exists())
        assertEquals(server1Audio.size.toLong(), file1.length())
        assertEquals(server2Audio.size.toLong(), file2.length())

        // 3. getTrackFile with isolation must resolve precisely each target file
        val resolvedFile1 = transferManager.getTrackFile(sharedTrackId, "flac", remoteDeviceId = server1, destinationLibraryId = destLib1)
        val resolvedFile2 = transferManager.getTrackFile(sharedTrackId, "flac", remoteDeviceId = server2, destinationLibraryId = destLib2)
        assertEquals(file1.absolutePath, resolvedFile1?.absolutePath)
        assertEquals(file2.absolutePath, resolvedFile2?.absolutePath)
    }

    @Test
    fun testAirCatRealtimeStreamingCancellation() = runBlocking {
        val serverId = "active_stream_server"
        val destLibId = "lib_phone_active"
        val trackId = "trk_infinite_stream"

        val massiveStream = ByteArray(1024 * 1024) { (it % 255).toByte() }

        val peer = FakeAirCatPeer(serverId)
        peer.setupTrackStreaming(trackId, massiveStream)

        every { pairedRegistry.getDevice(serverId) } returns PairedDevice(
            deviceId = serverId,
            deviceName = "Stream Server",
            deviceToken = "tok_stream_123",
            lastUrl = "http://192.168.1.140:8400",
        )

        val aircatClient = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
        )

        val transferId = "xfer_realtime_cancel_test"
        val intent = TransferIntent(
            transferId = transferId,
            sourceMichiId = serverId,
            destinationMichiId = "client_me",
            sourceLibraryId = "lib_source_remote",
            destinationLibraryId = destLibId,
        )

        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = transferId,
            sourceDeviceId = serverId,
            totalTracks = 1,
            totalBytes = massiveStream.size.toLong(),
            items = listOf(
                AirCatManifestItem(
                    logicalTrackId = trackId,
                    remoteTrackId = trackId,
                    title = "Heavy Symphonic Movement",
                    artist = "Orchestra",
                    album = "Symphony No. 9",
                    format = "flac",
                    size = massiveStream.size.toLong(),
                    sha256 = sha256(massiveStream),
                )
            ),
        )

        // Launch transfer and immediately cancel it
        val job = launch(Dispatchers.IO) {
            aircatClient.executeTransfer(
                intent = intent,
                manifest = manifest,
                linkClient = peer.mockClient,
                availableStorageBytes = 200L * 1024 * 1024,
            )
        }

        // Cancel transfer while active
        aircatClient.cancelTransfer(transferId)
        job.join()

        // Transfer must be marked as CANCELLED
        val latestProgress = aircatClient.progressEvents.first()
        assertNotNull(latestProgress)
        assertEquals(org.michimusic.mobile.devices.aircat.AirCatTransferState.CANCELLED, latestProgress?.state)
    }
}
