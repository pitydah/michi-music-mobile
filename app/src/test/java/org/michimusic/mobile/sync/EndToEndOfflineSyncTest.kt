package org.michimusic.mobile.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import org.michimusic.mobile.devices.aircat.AirCatTestAuthority
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

    /** A27: no AirCat flow runs without a Home grant, so these tests mint real ones. */
    private val authority = AirCatTestAuthority()

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
        val serverId = authority.mid("trusted_desktop_studio")
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
        authority.pin(peer.mockClient, token = "tok_master_999")
        // The wire identity is the remote track id the manifest declares, not the
        // local one: the producer streams rem_1 / rem_2.
        peer.setupTrackStreaming("rem_1", trackData1)
        peer.setupTrackStreaming("rem_2", trackData2)

        val aircatClient = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
            signedGrantSecurity = authority.security,
        )

        val intent = TransferIntent(
            transferId = "xfer_e2e_resumable_001",
            sourceMichiId = serverId,
            destinationMichiId = authority.mid("client_pixel"),
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
            intent = authority.authorized(intent, manifest),
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

        val key1 = org.michimusic.core.models.TransferFileIdentity.buildTransferKey(serverId, destLibId, "trk_lossless_1", "xfer_e2e_resumable_001")
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
        val serverId = authority.mid("desktop_node_interrupted")
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
            etag = "\"etag-v1-hash\"", // A67 needs a strong validator to resume
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
        authority.pin(peer.mockClient)
        val mockClient = peer.mockClient
        val remainingData = fullData.copyOfRange(partialData.size, fullData.size)

        // Intercept streamTrackRange: write remaining bytes to outputStream
        io.mockk.coEvery {
            mockClient.streamTrackRange(
                trackId = "rem_resumable",
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
                    etag = "\"etag-v1-hash\"", // A67 needs a strong validator to resume
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
            // The preflight compares this against the persisted row: a resume that
            // names a different remote track is not the same transfer.
            remoteTrackId = "rem_resumable",
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
        val serverId = authority.mid("trusted_desktop_failing")
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
        authority.pin(peer.mockClient, token = "tok_master_999")
        peer.setupTrackStreaming("rem_fail", trackData)

        // Failing library repository
        val failingLibraryRepo = mockk<MusicLibraryRepository>(relaxed = true)
        io.mockk.coEvery { failingLibraryRepo.registerTrackWithLocation(any(), any(), any(), any()) } throws RuntimeException("SQLite disk I/O error or constraint failure")

        val aircatClient = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = failingLibraryRepo,
            checkpointRepository = checkpointRepo,
            signedGrantSecurity = authority.security,
        )

        val intent = TransferIntent(
            transferId = "xfer_fail_test",
            sourceMichiId = serverId,
            destinationMichiId = authority.mid("client_pixel"),
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
            intent = authority.authorized(intent, manifest),
            manifest = manifest,
            linkClient = peer.mockClient,
            availableStorageBytes = 10L * 1024 * 1024 * 1024,
        )

        // Transfer must fail due to DB error
        assertTrue("Transfer must fail when library registration fails", result.isFailure)

        // Verify checkpoint does NOT remain COMPLETED
        val key = org.michimusic.core.models.TransferFileIdentity.buildTransferKey(serverId, destLibId, "trk_fail", "xfer_fail_test")
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
        val serverId = authority.mid("desktop_node_aircat")
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
            signedGrantSecurity = authority.security,
        )

        val transferId = "xfer_pause_resume_cancel"

        // Test pause, resume, cancel methods
        aircatClient.pauseTransfer(transferId)
        val pausedProgress = aircatClient.progressEvents.first()
        assertNotNull(pausedProgress)
        assertEquals(org.michimusic.mobile.devices.aircat.AirCatTransferState.PAUSED, pausedProgress?.state)
        assertEquals(transferId, pausedProgress?.transferId)

        // A67: resuming a transfer that has no live session must NOT fabricate a
        // running state. Before this guard the UI was told a paused transfer was
        // moving again while nothing was on the wire.
        aircatClient.resumeTransfer(transferId)
        val resumedProgress = aircatClient.progressEvents.first()
        assertNotNull(resumedProgress)
        assertEquals(
            org.michimusic.mobile.devices.aircat.AirCatTransferState.PAUSED,
            resumedProgress?.state,
        )
        assertTrue(aircatClient.isTransferPaused(transferId))

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

        // Download both tracks with identical logical trackId from both servers truly simultaneously
        val client1 = peer1.mockClient
        val client2 = peer2.mockClient

        val (file1Result, file2Result) = coroutineScope {
            val d1 = async(Dispatchers.IO) { transferManager.downloadTrack(client1, item1) }
            val d2 = async(Dispatchers.IO) { transferManager.downloadTrack(client2, item2) }
            d1.await() to d2.await()
        }

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
        val serverId = authority.mid("active_stream_server")
        val destLibId = "lib_phone_active"
        val trackId = "trk_infinite_stream"

        val massiveStream = ByteArray(1024 * 1024) { (it % 255).toByte() }
        val streamStarted = kotlinx.coroutines.CompletableDeferred<Unit>()

        val peer = FakeAirCatPeer(serverId)
        authority.pin(peer.mockClient, token = "tok_stream_123")
        coEvery {
            peer.mockClient.streamTrackRange(
                trackId = trackId,
                outputStream = any(),
                startBytes = any(),
                expectedEtag = any(),
                bufferSize = any(),
                onProgress = any(),
            )
        } answers {
            val os = secondArg<java.io.OutputStream>()
            val onProg = arg<((Long, Long) -> Unit)?>(5)

            // Write first 16KB chunk and signal streaming has actually begun
            os.write(massiveStream, 0, 16384)
            onProg?.invoke(16384L, massiveStream.size.toLong())
            streamStarted.complete(Unit)

            // Sleep briefly while checking coroutine context or thread interrupt
            try {
                Thread.sleep(5000)
            } catch (_: InterruptedException) {
            }

            throw kotlinx.coroutines.CancellationException("Transfer streaming cooperatively cancelled")
        }

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
            signedGrantSecurity = authority.security,
        )

        val transferId = "xfer_realtime_cancel_test"
        val intent = TransferIntent(
            transferId = transferId,
            sourceMichiId = serverId,
            destinationMichiId = authority.mid("client_me"),
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

        // Launch transfer in background coroutine
        val job = launch(Dispatchers.IO) {
            aircatClient.executeTransfer(
                intent = authority.authorized(intent, manifest),
                manifest = manifest,
                linkClient = peer.mockClient,
                availableStorageBytes = 200L * 1024 * 1024,
            )
        }

        // Wait until bytes are actually in-flight over the wire. Bounded: a
        // transfer that never reaches the wire must fail here with a reason, not
        // park the test worker until the build is killed.
        try {
            kotlinx.coroutines.withTimeout(20_000) { streamStarted.await() }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            // A refused transfer returns a Result instead of throwing, so the
            // launched job can finish green without a single byte on the wire.
            // Report what the client actually decided instead of a bare timeout.
            val last = aircatClient.progressEvents.first()
            throw AssertionError(
                "the transfer never reached the wire: " +
                    "state=${last?.state} error=${last?.error}",
                timeout,
            )
        }

        // Cancel transfer while actively streaming
        aircatClient.cancelTransfer(transferId)
        job.join()

        // Verify state is CANCELLED and partial file was preserved without corrupting database
        val latestProgress = aircatClient.progressEvents.first()
        assertNotNull(latestProgress)
        assertEquals(org.michimusic.mobile.devices.aircat.AirCatTransferState.CANCELLED, latestProgress?.state)
    }

    @Test
    fun testAirCatPauseAndResumeFromCheckpoint() = runBlocking {
        val serverId = authority.mid("desktop_node_resume")
        val destLibId = "lib_phone_resume"

        database.libraryDao().insertLibrary(
            org.michimusic.data.cache.LibraryEntity(
                libraryId = destLibId,
                name = "Phone Resume Lib",
                type = "SYNCED",
                ownerId = serverId,
                isWritable = true,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        every { pairedRegistry.getDevice(serverId) } returns PairedDevice(
            deviceId = serverId,
            deviceName = "Resume Server Node",
            deviceToken = "tok_resume_123",
            lastUrl = "http://192.168.1.150:8400",
        )

        val trackData1 = "Track One Full Audio Payload".toByteArray()
        val trackData2 = "Track Two Full Audio Payload".toByteArray()
        val hash1 = sha256(trackData1)
        val hash2 = sha256(trackData2)

        val peer = FakeAirCatPeer(serverId)
        authority.pin(peer.mockClient, token = "tok_resume_123")
        peer.setupTrackStreaming("trk_p_1", trackData1)
        peer.setupTrackStreaming("trk_p_2", trackData2)

        // Deterministic pause point. Track 2 announces that it is on the wire and
        // then suspends; pausing cancels that call. Without it the test raced a
        // three second poll and sometimes paused a batch that had already reported
        // COMPLETED, which is why `isTransferPaused` was false.
        val secondTrackInFlight = kotlinx.coroutines.CompletableDeferred<Unit>()
        var secondTrackAttempts = 0
        coEvery {
            peer.mockClient.streamTrackRange(
                trackId = "trk_p_2",
                outputStream = any(),
                startBytes = any(),
                expectedEtag = any(),
                bufferSize = any(),
                onProgress = any(),
            )
        } answers {
            secondTrackAttempts += 1
            if (secondTrackAttempts == 1) {
                secondTrackInFlight.complete(Unit)
                // The mock answer is not a suspend lambda, so cancellation is
                // delivered by unwinding this call rather than by suspension.
                Thread.sleep(200)
                throw kotlinx.coroutines.CancellationException("paused mid-stream")
            }
            val os = secondArg<java.io.OutputStream>()
            val start = thirdArg<Long>().toInt()
            val slice = trackData2.copyOfRange(start, trackData2.size)
            os.write(slice)
            Result.success(
                StreamRangeResult(
                    bytesRead = slice.size.toLong(),
                    statusCode = if (start > 0) 206 else 200,
                    isPartialContent = start > 0,
                    etag = "test-etag",
                    contentLength = slice.size.toLong(),
                )
            )
        }

        val aircatClient = DefaultAirCatTransferClient(
            transferManager = transferManager,
            pairedDeviceRegistry = pairedRegistry,
            libraryRepository = libraryRepo,
            checkpointRepository = checkpointRepo,
            // Resuming holds the same exclusivity gate as the original transfer.
            // Without it the client can only refuse the resume.
            resumeLease = org.michimusic.mobile.devices.aircat.AirCatLeaseCoordinator(appContext),
            signedGrantSecurity = authority.security,
        )

        val transferId = "xfer_pause_resume_e2e"
        val intent = TransferIntent(
            transferId = transferId,
            sourceMichiId = serverId,
            destinationMichiId = authority.mid("client_me"),
            sourceLibraryId = "lib_source_remote",
            destinationLibraryId = destLibId,
        )

        val manifest = AirCatManifest(
            manifestVersion = 1,
            transferId = transferId,
            sourceDeviceId = serverId,
            totalTracks = 2,
            totalBytes = (trackData1.size + trackData2.size).toLong(),
            items = listOf(
                AirCatManifestItem(
                    logicalTrackId = "trk_p_1",
                    remoteTrackId = "trk_p_1",
                    title = "First Song",
                    artist = "Artist A",
                    album = "Album A",
                    format = "flac",
                    size = trackData1.size.toLong(),
                    sha256 = hash1,
                ),
                AirCatManifestItem(
                    logicalTrackId = "trk_p_2",
                    remoteTrackId = "trk_p_2",
                    title = "Second Song",
                    artist = "Artist B",
                    album = "Album B",
                    format = "flac",
                    size = trackData2.size.toLong(),
                    sha256 = hash2,
                )
            ),
        )

        // 1. Start transfer
        val job = launch(Dispatchers.IO) {
            aircatClient.executeTransfer(
                intent = authority.authorized(intent, manifest),
                manifest = manifest,
                linkClient = peer.mockClient,
                availableStorageBytes = 200L * 1024 * 1024,
            )
        }

        val key_p1 = org.michimusic.core.models.TransferFileIdentity.buildTransferKey(serverId, destLibId, "trk_p_1", transferId)
        val key_p2 = org.michimusic.core.models.TransferFileIdentity.buildTransferKey(serverId, destLibId, "trk_p_2", transferId)

        // The batch is sequential, so track 2 reaching the wire proves that track 1
        // was already committed. Bounded: a stall fails instead of hanging.
        kotlinx.coroutines.withTimeout(20_000) { secondTrackInFlight.await() }

        // Pause transfer
        aircatClient.pauseTransfer(transferId)
        job.join()

        // Verify paused state
        assertTrue(aircatClient.isTransferPaused(transferId))

        // 2. Resume transfer
        aircatClient.resumeTransfer(transferId)

        // Event driven, not polled: wait for the resumed batch to report COMPLETED.
        // A timeout reports what the client actually did instead of a bare failure.
        try {
            kotlinx.coroutines.withTimeout(20_000) {
                aircatClient.progressEvents.first {
                    it?.transferId == transferId &&
                        it.state == org.michimusic.mobile.devices.aircat.AirCatTransferState.COMPLETED
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            val last = aircatClient.progressEvents.first()
            val seen1 = checkpointRepo.getCheckpoint(key_p1)
            val seen2 = checkpointRepo.getCheckpoint(key_p2)
            throw AssertionError(
                "resume never reported COMPLETED: progress=${last?.state}/${last?.error} " +
                    "wireAttempts=$secondTrackAttempts paused=${aircatClient.isTransferPaused(transferId)} " +
                    "cp1=${seen1?.transferState}(${seen1?.receivedBytes}/${seen1?.verifiedBytes}) " +
                    "cp2=${seen2?.transferState}(${seen2?.receivedBytes}/${seen2?.verifiedBytes})",
                timeout,
            )
        }

        val cp1 = checkpointRepo.getCheckpoint(key_p1)
        val cp2 = checkpointRepo.getCheckpoint(key_p2)
        val lastProgress = aircatClient.progressEvents.first()
        val detail = "progress=${lastProgress?.state}/${lastProgress?.error} " +
            "cp1=${cp1?.transferState}(${cp1?.receivedBytes}/${cp1?.verifiedBytes}) " +
            "cp2=${cp2?.transferState}(${cp2?.receivedBytes}/${cp2?.verifiedBytes})"
        assertNotNull(detail, cp1)
        assertNotNull(detail, cp2)
        assertEquals("first track must survive pause/resume; $detail",
            TransferStatus.COMPLETED, cp1?.transferState)
        assertEquals("second track must survive pause/resume; $detail",
            TransferStatus.COMPLETED, cp2?.transferState)

        // Verify library contains both tracks
        val tracks = libraryRepo.observeTracks(LibraryScope.One(destLibId)).first()
        assertEquals(2, tracks.size)
    }
}
