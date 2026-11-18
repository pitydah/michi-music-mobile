package org.michimusic.mobile.devices.aircat

import org.michimusic.core.models.PersistentCheckpoint
import org.michimusic.core.models.TransferCheckpointRepository
import org.michimusic.core.models.TransferStatus

/**
 * A checkpoint store that actually remembers what it was told.
 *
 * A68-04/A68-03: a relaxed mock answers every read with a stub, so a receipt that
 * requires "re-read the persisted row and prove it says COMPLETED with these exact
 * fields" can never be satisfied honestly — and a test that relaxes the repository
 * ends up proving that the client trusts in-memory flags instead of Room.
 */
internal class FakeTransferCheckpointRepository(
    initial: List<PersistentCheckpoint> = emptyList(),
) : TransferCheckpointRepository {

    private val rows = LinkedHashMap<String, PersistentCheckpoint>()

    init {
        initial.forEach { rows[it.transferKey] = it }
    }

    /** Snapshot of everything persisted so far, for readback assertions. */
    val persisted: List<PersistentCheckpoint> get() = rows.values.toList()

    fun row(transferKey: String): PersistentCheckpoint? = rows[transferKey]

    override suspend fun getCheckpoint(transferKey: String): PersistentCheckpoint? = rows[transferKey]

    override suspend fun getLatestCheckpointForTrack(trackId: String): PersistentCheckpoint? =
        rows.values.filter { it.trackId == trackId }.maxByOrNull { it.updatedAt }

    override suspend fun getCheckpointsForDevice(remoteDeviceId: String): List<PersistentCheckpoint> =
        rows.values.filter { it.remoteDeviceId == remoteDeviceId }

    override suspend fun saveCheckpoint(checkpoint: PersistentCheckpoint) {
        rows[checkpoint.transferKey] = checkpoint
    }

    override suspend fun updateProgress(
        transferKey: String,
        receivedBytes: Long,
        verifiedBytes: Long,
        state: TransferStatus,
    ) {
        val existing = rows[transferKey] ?: return
        rows[transferKey] = existing.copy(
            receivedBytes = receivedBytes,
            verifiedBytes = verifiedBytes,
            transferState = state,
            updatedAt = existing.updatedAt + 1,
        )
    }

    override suspend fun deleteCheckpoint(transferKey: String) {
        rows.remove(transferKey)
    }

    override suspend fun clearCompletedCheckpoints(remoteDeviceId: String) {
        rows.entries.removeAll {
            it.value.remoteDeviceId == remoteDeviceId && it.value.transferState == TransferStatus.COMPLETED
        }
    }
}
