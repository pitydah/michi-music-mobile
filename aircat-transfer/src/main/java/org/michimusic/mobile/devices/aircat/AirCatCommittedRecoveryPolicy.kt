package org.michimusic.mobile.devices.aircat

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import org.michimusic.core.models.PersistentCheckpoint
import org.michimusic.core.models.TransferStatus

/**
 * A23: the only automatic action during a user-requested repair is completing a
 * transaction that was ALREADY committed to Room and a durable checkpoint.
 * No network, background scan, rollback, rename, or inferred registration here.
 */
internal object AirCatCommittedRecoveryPolicy {
    fun mayFinalize(
        checkpoint: PersistentCheckpoint,
        requestedRemoteDeviceId: String,
        managedDirectory: File,
        exactRegisteredLocation: Boolean,
        registeredIdentitySha256: String?,
    ): Boolean {
        if (checkpoint.transferState != TransferStatus.COMPLETED ||
            checkpoint.remoteDeviceId != requestedRemoteDeviceId ||
            requestedRemoteDeviceId.isBlank() ||
            checkpoint.transferId.isBlank() || checkpoint.trackId.isBlank() ||
            checkpoint.remoteTrackId.isBlank() || checkpoint.transferKey.isBlank() ||
            checkpoint.destinationLibraryId.isBlank() || !exactRegisteredLocation ||
            registeredIdentitySha256.isNullOrBlank() ||
            !registeredIdentitySha256.equals(checkpoint.expectedSha256, ignoreCase = true)
        ) return false

        // A checkpoint is untrusted input until its target is confirmed within
        // the managed root. No symlink traversal, even when its canonical path
        // happens to end under the managed root.
        val target = File(checkpoint.finalFilePath)
        if (!target.isAbsolute || !managedDirectory.isAbsolute ||
            Files.isSymbolicLink(managedDirectory.toPath())) return false
        val safeTarget = runCatching {
            val parent = target.parentFile ?: return@runCatching false
            !Files.isSymbolicLink(target.toPath()) &&
                !Files.isSymbolicLink(parent.toPath()) &&
                Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                parent.canonicalFile == managedDirectory.canonicalFile
        }.getOrDefault(false)
        if (!safeTarget) return false

        return AirCatCommitReceiptPolicy.isCommitted(
            recorded = checkpoint,
            transferKey = checkpoint.transferKey,
            transferId = checkpoint.transferId,
            sourceId = requestedRemoteDeviceId,
            destinationLibraryId = checkpoint.destinationLibraryId,
            logicalTrackId = checkpoint.trackId,
            remoteTrackId = checkpoint.remoteTrackId,
            finalPath = checkpoint.finalFilePath,
            expectedSize = checkpoint.expectedSize,
            expectedSha256 = checkpoint.expectedSha256,
        ) && target.length() == checkpoint.expectedSize
    }
}
