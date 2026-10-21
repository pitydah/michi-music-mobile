package org.michimusic.mobile.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.core.models.DownloadItem
import org.michimusic.core.models.PersistentCheckpoint
import org.michimusic.core.models.TransferCheckpointRepository
import org.michimusic.core.models.TransferStatus
import org.michimusic.data.cache.TrackDao
import org.michimusic.data.cache.TrackIdentityDao
import org.michimusic.data.cache.TrackLocationDao
import org.michimusic.data.repository.MusicLibraryRepository
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkTransferManager
import org.michimusic.link.PairedDevice
import org.michimusic.link.PairedDeviceRegistry
import org.michimusic.mobile.devices.aircat.AirCatManifest
import org.michimusic.mobile.devices.aircat.AirCatManifestItem
import org.michimusic.mobile.devices.aircat.AirCatTransferState
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
    private lateinit var context: Context
    private lateinit var transferManager: LinkTransferManager
    private lateinit var pairedRegistry: PairedDeviceRegistry
    private lateinit var libraryRepo: MusicLibraryRepository
    private lateinit var checkpointRepo: TransferCheckpointRepository
    private val checkpointsMemory = mutableMapOf<String, PersistentCheckpoint>()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("e2e_sync_test").toFile()
        context = mockk(relaxed = true)
        every { context.filesDir } returns tempDir

        transferManager = LinkTransferManager(context)
        pairedRegistry = mockk(relaxed = true)
        libraryRepo = mockk(relaxed = true)

        checkpointRepo = object : TransferCheckpointRepository {
            override suspend fun getCheckpoint(transferKey: String): PersistentCheckpoint? {
                return checkpointsMemory[transferKey]
            }

            override suspend fun getCheckpointsForDevice(remoteDeviceId: String): List<PersistentCheckpoint> {
                return checkpointsMemory.values.filter { it.remoteDeviceId == remoteDeviceId }
            }

            override suspend fun saveCheckpoint(checkpoint: PersistentCheckpoint) {
                checkpointsMemory[checkpoint.transferKey] = checkpoint
            }

            override suspend fun updateProgress(
                transferKey: String,
                receivedBytes: Long,
                verifiedBytes: Long,
                state: TransferStatus
            ) {
                checkpointsMemory[transferKey]?.let {
                    checkpointsMemory[transferKey] = it.copy(
                        receivedBytes = receivedBytes,
                        verifiedBytes = verifiedBytes,
                        transferState = state,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }

            override suspend fun deleteCheckpoint(transferKey: String) {
                checkpointsMemory.remove(transferKey)
            }

            override suspend fun clearCompletedCheckpoints(remoteDeviceId: String) {
                checkpointsMemory.entries.removeIf { it.value.remoteDeviceId == remoteDeviceId && it.value.transferState == TransferStatus.COMPLETED }
            }
        }
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
    fun testCompleteOfflineSyncFlowWithPersistentCheckpointsAndResumption() = runBlocking {
        val serverId = "trusted_desktop_studio"
        val destLibId = "lib_phone_offline_synced"
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

        assertTrue("Transfer must succeed", result.isSuccess)
        val downloaded = result.getOrThrow()
        assertEquals(2, downloaded.size)

        // Verify checkpoints were persisted into checkpoint repository
        val checkpoints = checkpointRepo.getCheckpointsForDevice(serverId)
        assertEquals(2, checkpoints.size)
        assertTrue(checkpoints.all { it.transferState == TransferStatus.COMPLETED })

        val key1 = "$serverId:$destLibId:trk_lossless_1"
        val cp1 = checkpointRepo.getCheckpoint(key1)
        assertNotNull(cp1)
        assertEquals(trackData1.size.toLong(), cp1?.receivedBytes)
        assertEquals(TransferStatus.COMPLETED, cp1?.transferState)

        // Verify audio files exist on disk with valid checksum
        val f1 = downloaded[0]
        val f2 = downloaded[1]
        assertTrue(f1.exists())
        assertTrue(f2.exists())
        assertEquals(hash1, sha256(f1.readBytes()))
        assertEquals(hash2, sha256(f2.readBytes()))

        // Verify library registration was invoked for both tracks with proper multi-library scoping
        io.mockk.coVerify(exactly = 1) {
            libraryRepo.registerTrackWithLocation(
                identity = match { it.id == "trk_lossless_1" && it.title == "Autumn Leaves" },
                location = match { it.trackId == "trk_lossless_1" && it.sourceDeviceId == serverId },
                libraryId = destLibId,
                origin = "AIRC_AT",
            )
        }
        io.mockk.coVerify(exactly = 1) {
            libraryRepo.registerTrackWithLocation(
                identity = match { it.id == "trk_lossless_2" && it.title == "Blue in Green" },
                location = match { it.trackId == "trk_lossless_2" && it.sourceDeviceId == serverId },
                libraryId = destLibId,
                origin = "AIRC_AT",
            )
        }
    }
}
