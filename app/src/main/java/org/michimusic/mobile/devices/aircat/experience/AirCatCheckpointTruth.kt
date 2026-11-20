package org.michimusic.mobile.devices.aircat.experience

import org.michimusic.core.models.PersistentCheckpoint
import org.michimusic.core.models.TransferStatus

/** Read-only projection. No network, polling, revocation change or grant creation. */
internal data class AirCatRecoveryUi(
    val sourceDeviceId: String,
    val transferId: String,
    val pendingTracks: Int,
    val latestCheckpointAt: Long,
)

/** Facts from a specific durable checkpoint, NOT a signed per-file completion receipt. */
internal data class AirCatFileEvidenceUi(
    val remoteTrackId: String,
    val sourceDeviceId: String,
    val transferId: String,
    val expectedBytes: Long,
    val receivedBytes: Long,
    val verifiedBytes: Long,
    val checkpointState: TransferStatus,
) {
    val label: String
        get() = when (checkpointState) {
            TransferStatus.PENDING -> "Pendiente en registro local"
            TransferStatus.DOWNLOADING -> "Recepción registrada; comprobar estado actual"
            TransferStatus.PAUSED -> "Pausado en registro local"
            TransferStatus.VERIFYING -> "Verificación local registrada"
            TransferStatus.COMMITTING -> "Incorporación local registrada"
            TransferStatus.COMPLETED -> "Checkpoint finalizado"
            TransferStatus.FAILED -> "Falló según registro local"
            TransferStatus.CANCELLED -> "Cancelado según registro local"
        }
}

/** Bounded, identity-bound checkpoint reads; never interprets a checkpoint as a Home grant. */
internal object AirCatCheckpointTruth {
    private val interrupted = setOf(
        TransferStatus.PENDING, TransferStatus.DOWNLOADING, TransferStatus.PAUSED,
        TransferStatus.VERIFYING, TransferStatus.COMMITTING, TransferStatus.FAILED,
    )

    fun recoveries(sourceDeviceId: String, checkpoints: List<PersistentCheckpoint>): List<AirCatRecoveryUi> {
        if (sourceDeviceId.isBlank()) return emptyList()
        return checkpoints.asSequence()
            .filter { it.remoteDeviceId == sourceDeviceId && it.transferId.isNotBlank() }
            .groupBy { it.transferId }
            .mapNotNull { (transferId, rows) ->
                val pending = rows.count { it.transferState in interrupted }
                if (pending == 0) null else AirCatRecoveryUi(
                    sourceDeviceId = sourceDeviceId,
                    transferId = transferId,
                    pendingTracks = pending,
                    latestCheckpointAt = rows.maxOf { it.updatedAt },
                )
            }
            .sortedByDescending { it.latestCheckpointAt }
            .take(20)
            .toList()
    }

    fun fileEvidence(
        sourceDeviceId: String,
        transferId: String,
        checkpoints: List<PersistentCheckpoint>,
    ): List<AirCatFileEvidenceUi> {
        if (sourceDeviceId.isBlank() || transferId.isBlank()) return emptyList()
        return checkpoints.asSequence()
            .filter { it.remoteDeviceId == sourceDeviceId && it.transferId == transferId }
            .filter { it.remoteTrackId.isNotBlank() && it.expectedSize >= 0L &&
                it.receivedBytes in 0L..it.expectedSize && it.verifiedBytes in 0L..it.expectedSize }
            .take(10_000)
            .map {
                AirCatFileEvidenceUi(
                    remoteTrackId = it.remoteTrackId,
                    sourceDeviceId = sourceDeviceId,
                    transferId = transferId,
                    expectedBytes = it.expectedSize,
                    receivedBytes = it.receivedBytes,
                    verifiedBytes = it.verifiedBytes,
                    checkpointState = it.transferState,
                )
            }.toList()
    }
}
