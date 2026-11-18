package org.michimusic.mobile.devices.aircat

import org.michimusic.core.models.PersistentCheckpoint
import org.michimusic.core.models.TransferStatus

/** Confirm the persisted row after updateProgress; a Unit return is not a receipt. */
internal object AirCatCommitReceiptPolicy {
    /** A44: issued only after a durable checkpoint and exact library identity readback. */
    data class Confirmed(
        val transferKey: String,
        val transferId: String,
        val sourceMichiId: String,
        val destinationLibraryId: String,
        val logicalTrackId: String,
        val remoteTrackId: String,
        val finalPath: String,
        val size: Long,
        val sha256: String,
    )

    /** The source file SHA was checked before calling this, and its journal is settled.
     * This method is invoked for BOTH cached originals and new imports. It must
     * re-read the persisted Room checkpoint, not trust in-memory aggregate flags.
     */
    fun confirm(
        recorded: PersistentCheckpoint?,
        transferKey: String,
        transferId: String,
        sourceId: String,
        destinationLibraryId: String,
        logicalTrackId: String,
        remoteTrackId: String,
        finalPath: String,
        expectedSize: Long,
        expectedSha256: String,
        locationRegistered: Boolean,
        identityContentSha256: String?,
        journalSettled: Boolean,
    ): Confirmed? {
        if (!locationRegistered || !journalSettled ||
            !identityContentSha256.equals(expectedSha256, ignoreCase = true) ||
            !isCommitted(recorded, transferKey, transferId, sourceId,
                destinationLibraryId, logicalTrackId, remoteTrackId,
                finalPath, expectedSize, expectedSha256)) return null
        val cp = recorded ?: return null
        return Confirmed(cp.transferKey, cp.transferId, cp.remoteDeviceId,
            cp.destinationLibraryId, cp.trackId, cp.remoteTrackId,
            cp.finalFilePath, cp.expectedSize, cp.expectedSha256.lowercase())
    }

    fun isCommitted(
        recorded: PersistentCheckpoint?,
        transferKey: String,
        transferId: String,
        sourceId: String,
        destinationLibraryId: String,
        logicalTrackId: String,
        remoteTrackId: String,
        finalPath: String,
        expectedSize: Long,
        expectedSha256: String,
    ): Boolean {
        val cp = recorded ?: return false
        return expectedSize > 0L && expectedSha256.matches(Regex("[a-fA-F0-9]{64}")) &&
            cp.transferState == TransferStatus.COMPLETED &&
            cp.transferKey == transferKey && cp.transferId == transferId &&
            cp.remoteDeviceId == sourceId && cp.destinationLibraryId == destinationLibraryId &&
            cp.trackId == logicalTrackId && cp.remoteTrackId == remoteTrackId &&
            cp.finalFilePath == finalPath && cp.expectedSize == expectedSize &&
            cp.expectedSha256.equals(expectedSha256, ignoreCase = true) &&
            cp.receivedBytes == expectedSize && cp.verifiedBytes == expectedSize
    }
}
