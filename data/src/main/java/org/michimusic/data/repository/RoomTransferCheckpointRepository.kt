package org.michimusic.data.repository

import org.michimusic.core.models.PersistentCheckpoint
import org.michimusic.core.models.TransferCheckpointRepository
import org.michimusic.core.models.TransferStatus
import org.michimusic.data.cache.TransferCheckpointDao
import org.michimusic.data.cache.TransferCheckpointEntity

class RoomTransferCheckpointRepository(
    private val checkpointDao: TransferCheckpointDao
) : TransferCheckpointRepository {

    override suspend fun getCheckpoint(transferKey: String): PersistentCheckpoint? {
        return checkpointDao.getCheckpoint(transferKey)?.toDomain()
    }

    override suspend fun getLatestCheckpointForTrack(trackId: String): PersistentCheckpoint? {
        return checkpointDao.getLatestCheckpointForTrack(trackId)?.toDomain()
    }

    override suspend fun getCheckpointsForDevice(remoteDeviceId: String): List<PersistentCheckpoint> {
        return checkpointDao.getCheckpointsForDevice(remoteDeviceId).map { it.toDomain() }
    }

    override suspend fun saveCheckpoint(checkpoint: PersistentCheckpoint) {
        checkpointDao.insertCheckpoint(checkpoint.toEntity())
    }

    override suspend fun updateProgress(
        transferKey: String,
        receivedBytes: Long,
        verifiedBytes: Long,
        state: TransferStatus
    ) {
        checkpointDao.updateProgress(
            key = transferKey,
            received = receivedBytes,
            verified = verifiedBytes,
            state = state.name,
            updatedAt = System.currentTimeMillis()
        )
    }

    override suspend fun deleteCheckpoint(transferKey: String) {
        checkpointDao.deleteCheckpoint(transferKey)
    }

    override suspend fun clearCompletedCheckpoints(remoteDeviceId: String) {
        checkpointDao.clearCompletedCheckpoints(remoteDeviceId)
    }

    private fun PersistentCheckpoint.toEntity(): TransferCheckpointEntity {
        return TransferCheckpointEntity(
            transferKey = transferKey,
            transferId = transferId,
            remoteDeviceId = remoteDeviceId,
            sourceLibraryId = sourceLibraryId,
            destinationLibraryId = destinationLibraryId,
            trackId = trackId,
            remoteTrackId = remoteTrackId,
            tempFilePath = tempFilePath,
            finalFilePath = finalFilePath,
            expectedSize = expectedSize,
            receivedBytes = receivedBytes,
            verifiedBytes = verifiedBytes,
            expectedSha256 = expectedSha256,
            etag = etag,
            transferState = transferState.name,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun TransferCheckpointEntity.toDomain(): PersistentCheckpoint {
        val parsedStatus = try {
            TransferStatus.valueOf(transferState)
        } catch (_: Exception) {
            TransferStatus.FAILED
        }
        return PersistentCheckpoint(
            transferKey = transferKey,
            transferId = transferId,
            remoteDeviceId = remoteDeviceId,
            sourceLibraryId = sourceLibraryId,
            destinationLibraryId = destinationLibraryId,
            trackId = trackId,
            remoteTrackId = remoteTrackId,
            tempFilePath = tempFilePath,
            finalFilePath = finalFilePath,
            expectedSize = expectedSize,
            receivedBytes = receivedBytes,
            verifiedBytes = verifiedBytes,
            expectedSha256 = expectedSha256,
            etag = etag,
            transferState = parsedStatus,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
