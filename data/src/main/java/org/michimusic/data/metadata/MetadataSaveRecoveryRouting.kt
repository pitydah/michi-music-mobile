package org.michimusic.data.metadata

import org.michimusic.core.metadata.MetadataRecoveryPhase

/**
 * Fail-closed save/recovery routing. A committed or uncertain write is never
 * evaluated as a new write against the old source size. Private preparation
 * may only be discarded by the exact owner, never by another track.
 */
internal object MetadataSaveRecoveryRouting {
    fun requiresReadOnlyFinalization(phase: MetadataRecoveryPhase): Boolean =
        phase in setOf(
            MetadataRecoveryPhase.MUTATING,
            MetadataRecoveryPhase.MUTATION_UNKNOWN,
            MetadataRecoveryPhase.POSTCHECK_VERIFIED,
        )

    fun mayDiscardPrivatePreparation(
        phase: MetadataRecoveryPhase,
        pendingSourceId: String,
        pendingTrackId: String,
        pendingUri: String,
        requestedSourceId: String,
        requestedTrackId: String,
        requestedUri: String,
    ): Boolean =
        phase in setOf(
            MetadataRecoveryPhase.SNAPSHOTTING,
            MetadataRecoveryPhase.BACKUP_DURABLE,
        ) &&
            pendingSourceId == requestedSourceId &&
            pendingTrackId == requestedTrackId &&
            pendingUri == requestedUri
}
