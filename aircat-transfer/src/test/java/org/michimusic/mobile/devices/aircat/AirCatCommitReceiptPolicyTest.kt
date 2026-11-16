package org.michimusic.mobile.devices.aircat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.PersistentCheckpoint
import org.michimusic.core.models.TransferStatus

class AirCatCommitReceiptPolicyTest {
    private val checksum = "a".repeat(64)
    private val committed = PersistentCheckpoint(
        transferKey = "device:lib:track", transferId = "transfer-1", remoteDeviceId = "device",
        sourceLibraryId = "source", destinationLibraryId = "lib", trackId = "track", remoteTrackId = "remote",
        tempFilePath = "/music/track.part", finalFilePath = "/music/track.flac",
        expectedSize = 128, receivedBytes = 128, verifiedBytes = 128,
        expectedSha256 = checksum, transferState = TransferStatus.COMPLETED,
    )

    private fun accept(cp: PersistentCheckpoint? = committed, size: Long = 128, sha: String = checksum) =
        AirCatCommitReceiptPolicy.isCommitted(cp, "device:lib:track", "transfer-1", "device", "lib",
            "track", "remote", "/music/track.flac", size, sha)

    private fun confirmed(cp: PersistentCheckpoint? = committed,
        location: Boolean = true, contentSha: String? = checksum, journal: Boolean = true) =
        AirCatCommitReceiptPolicy.confirm(cp, "device:lib:track", "transfer-1",
            "device", "lib", "track", "remote", "/music/track.flac", 128,
            checksum, location, contentSha, journal)

    @Test fun typedReceiptRequiresAllIndependentReadbacks() {
        assertTrue(confirmed() != null)
        assertTrue(confirmed(location = false) == null)
        assertTrue(confirmed(contentSha = "b".repeat(64)) == null)
        assertTrue(confirmed(journal = false) == null)
        assertTrue(confirmed(cp = committed.copy(verifiedBytes = 100)) == null)
    }
    @Test fun acceptsExactDurableReceipt() = assertTrue(accept())
    @Test fun rejectsMissingRow() = assertFalse(accept(null))
    @Test fun rejectsDownloadingRow() = assertFalse(accept(committed.copy(transferState = TransferStatus.DOWNLOADING)))
    @Test fun rejectsOtherTransferId() = assertFalse(accept(committed.copy(transferId = "wrong")))
    @Test fun rejectsOtherTransferKey() = assertFalse(accept(committed.copy(transferKey = "wrong")))
    @Test fun rejectsOtherDevice() = assertFalse(accept(committed.copy(remoteDeviceId = "other")))
    @Test fun rejectsOtherLibrary() = assertFalse(accept(committed.copy(destinationLibraryId = "other")))
    @Test fun rejectsOtherLogicalTrack() = assertFalse(accept(committed.copy(trackId = "other")))
    @Test fun rejectsOtherRemoteTrack() = assertFalse(accept(committed.copy(remoteTrackId = "other")))
    @Test fun rejectsOtherFinalPath() = assertFalse(accept(committed.copy(finalFilePath = "/music/other")))
    @Test fun rejectsShortVerifiedBytes() = assertFalse(accept(committed.copy(verifiedBytes = 64)))
    @Test fun rejectsShortReceivedBytes() = assertFalse(accept(committed.copy(receivedBytes = 64)))
    @Test fun rejectsDifferentSize() = assertFalse(accept(committed.copy(expectedSize = 64)))
    @Test fun rejectsDifferentDigest() = assertFalse(accept(committed.copy(expectedSha256 = "b".repeat(64))))
    @Test fun rejectsInvalidDigest() = assertFalse(accept(committed, sha = "bad"))
    @Test fun rejectsZeroExpectedSize() = assertFalse(accept(committed, size = 0))
    @Test fun acceptsEquivalentUppercaseDigest() = assertTrue(accept(committed.copy(expectedSha256 = "A".repeat(64))))
}
