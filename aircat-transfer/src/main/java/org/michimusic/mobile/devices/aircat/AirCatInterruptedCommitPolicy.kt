package org.michimusic.mobile.devices.aircat

import java.io.File
import org.michimusic.core.models.PersistentCheckpoint
import org.michimusic.core.models.TransferStatus

/**
 * A24: explicit crash repair of a Room-registered file whose persistent row
 * was left PENDING/DOWNLOADING/PAUSED/COMMITTING after a process death. This policy never guesses that an
 * unregistered file was committed. The caller must first examine the journal
 * and acquire the same exclusive AirCat lease as normal transfer I/O.
 * No recovery without a valid SHA, trusted metadata and exact Room location.
 */
internal object AirCatInterruptedCommitPolicy {
    fun mayConfirmExistingRoomCommit(
        cp: PersistentCheckpoint,
        requestedRemoteDeviceId: String,
        managedDirectory: File,
        exactRegisteredLocation: Boolean,
        registeredIdentitySha256: String?,
        journalAssessment: String,
    ): Boolean {
        if (cp.transferState !in setOf(
                TransferStatus.PENDING, TransferStatus.DOWNLOADING,
                TransferStatus.PAUSED, TransferStatus.COMMITTING,
            ) || journalAssessment != "READY_FOR_EXPLICIT_COMPLETION") return false
        // Test the hypothetically completed receipt without writing the row.
        // This checks location, identity, path confinement, size and counters.
        val completed = cp.copy(
            transferState = TransferStatus.COMPLETED,
            receivedBytes = cp.expectedSize,
            verifiedBytes = cp.expectedSize,
        )
        return AirCatCommittedRecoveryPolicy.mayFinalize(
            checkpoint = completed,
            requestedRemoteDeviceId = requestedRemoteDeviceId,
            managedDirectory = managedDirectory,
            exactRegisteredLocation = exactRegisteredLocation,
            registeredIdentitySha256 = registeredIdentitySha256,
        )
    }
}
