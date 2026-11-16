package org.michimusic.mobile.devices.aircat

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.PersistentCheckpoint
import org.michimusic.core.models.TrackIdentity
import org.michimusic.core.models.TrackLocation
import org.michimusic.core.models.TrackLocationType
import org.michimusic.core.models.LocationAvailability
import org.michimusic.core.models.TransferFileIdentity
import org.michimusic.core.models.TransferStatus
import org.michimusic.core.models.TransferCheckpointRepository
import org.michimusic.data.repository.MusicLibraryRepository
import org.michimusic.link.AirCatRecoveryJournal
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkTransferManager
import org.michimusic.link.PairedDevice
import org.michimusic.link.PairedDeviceRegistry
import java.nio.file.Files
import java.security.MessageDigest

/** A25: a new request may not replace a durable COMMITTED receipt with DOWNLOADING. */
class AirCatA25JournalGuardTest {
    @Test
    fun committedJournalBlocksNewTransferWithoutReplacingRoomReceipt() = runBlocking {
        val root = Files.createTempDirectory("aircat_a25_guard").toFile()
        val remoteId = "peer"
        val transferId = "tx1"
        val dst = "library"
        val raw = "verified original file".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it) }
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns root
        val checkpointRepo = mockk<TransferCheckpointRepository>(relaxed = true)
        val registry = mockk<PairedDeviceRegistry>(relaxed = true)
        val library = mockk<MusicLibraryRepository>(relaxed = true)
        val manager = LinkTransferManager(context, checkpointRepo)
        val network = LinkClient("http://127.0.0.1:8400", deviceToken = "secret")
        try {
            val (final, part) = TransferFileIdentity.resolveFiles(
                manager.musicDir, remoteId, dst, "song", "flac", planId = transferId)
            final.writeText("old file content")
            part.writeBytes(raw)
            val transferKey = TransferFileIdentity.buildTransferKey(remoteId, dst, "song", transferId)
            val journal = AirCatRecoveryJournal(java.io.File(final.parentFile, ".aircat-journal"))
            assertTrue(journal.promote(part, final, transferKey, hash))
            val committed = PersistentCheckpoint(
                transferKey = transferKey, transferId = transferId, remoteDeviceId = remoteId,
                sourceLibraryId = "source", destinationLibraryId = dst,
                trackId = "song", remoteTrackId = "raw-song",
                tempFilePath = part.absolutePath, finalFilePath = final.absolutePath,
                expectedSize = raw.size.toLong(), receivedBytes = raw.size.toLong(),
                verifiedBytes = raw.size.toLong(), expectedSha256 = hash,
                transferState = TransferStatus.COMPLETED,
            )
            var persisted = committed
            coEvery { checkpointRepo.getCheckpoint(transferKey) } answers { persisted }
            coEvery { checkpointRepo.saveCheckpoint(any()) } answers {
                persisted = firstArg()
            }
            coEvery { checkpointRepo.updateProgress(any(), any(), any(), any()) } answers {
                persisted = persisted.copy(transferState = arg(3))
            }
            every { registry.getDevice(remoteId) } returns PairedDevice(
                deviceId = remoteId, deviceToken = "secret")
            coEvery { library.getTrackIdentity("song") } returns TrackIdentity(
                id = "song", title = "song", artist = "artist", album = "album",
                durationMs = 0L, contentHash = hash)
            coEvery { library.getLocationsForTrack("song") } returns listOf(TrackLocation(
                id = "loc", trackId = "song", type = TrackLocationType.SYNCED_MANAGED,
                uri = final.absolutePath, sourceDeviceId = remoteId, remoteTrackId = "raw-song",
                availability = LocationAvailability.AVAILABLE))
            val client = DefaultAirCatTransferClient(manager, registry, library, checkpointRepo,
                clock = { 100L })
            val intent = TransferIntent(transferId, remoteId, "local", "source", dst,
                expiresAt = 2000L)
            val manifest = AirCatManifest(transferId = transferId, sourceDeviceId = remoteId,
                totalTracks = 1, totalBytes = raw.size.toLong(),
                items = listOf(AirCatManifestItem("song", "raw-song", "Song", "Artist",
                    "Album", "flac", raw.size.toLong(), hash)))
            val result = client.executeTransfer(intent, manifest, network, 250_000_000L)
            assertTrue(result.isFailure)
            assertEquals(TransferStatus.COMPLETED, persisted.transferState)
            assertEquals(committed.transferKey, persisted.transferKey)
            assertTrue(journal.hasJournal(final))
            assertTrue(java.io.File(final.parentFile, "${final.name}.bak").exists())
        } finally {
            network.close()
            root.deleteRecursively()
        }
    }
}
