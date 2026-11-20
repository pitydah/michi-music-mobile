package org.michimusic.mobile.devices.aircat.experience

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.PersistentCheckpoint
import org.michimusic.core.models.TransferStatus

class AirCatCheckpointTruthTest {
    private fun row(source: String, transfer: String, track: String, status: TransferStatus) =
        PersistentCheckpoint(
            transferKey = "$source:library:$track",
            transferId = transfer,
            remoteDeviceId = source,
            sourceLibraryId = "remote", destinationLibraryId = "library",
            trackId = track, remoteTrackId = "remote-$track",
            tempFilePath = "", finalFilePath = "", expectedSize = 100L,
            receivedBytes = 50L, verifiedBytes = 0L,
            transferState = status,
        )

    @Test fun recoveryIsRestrictedToExactSourceAndActualUnfinishedCheckpoints() {
        val rows = listOf(
            row("source-a", "transfer-a", "1", TransferStatus.PAUSED),
            row("source-b", "transfer-a", "2", TransferStatus.FAILED),
            row("source-a", "transfer-b", "3", TransferStatus.COMPLETED),
        )
        val found = AirCatCheckpointTruth.recoveries("source-a", rows)
        assertEquals(1, found.size)
        assertEquals("transfer-a", found.single().transferId)
        assertEquals(1, found.single().pendingTracks)
    }

    @Test fun evidenceNeverBorrowsAnotherTransferOrSource() {
        val rows = listOf(
            row("source-a", "transfer-a", "1", TransferStatus.VERIFYING),
            row("source-a", "transfer-b", "2", TransferStatus.COMPLETED),
            row("source-b", "transfer-a", "3", TransferStatus.FAILED),
        )
        val found = AirCatCheckpointTruth.fileEvidence("source-a", "transfer-a", rows)
        assertEquals(listOf("remote-1"), found.map { it.remoteTrackId })
        assertEquals("Verificación local registrada", found.single().label)
        assertTrue(AirCatCheckpointTruth.fileEvidence("source-a", "other", rows).isEmpty())
    }
}
