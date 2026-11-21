package org.michimusic.core.metadata

/** ME-05: explicit, non-mutating preparation contract. Android/provider IO lives in :data. */
data class MetadataPrepareTicket(
    val sourceId: String,
    val trackId: String,
    val uri: String,
    val expectedBytes: Long,
    val explicitUserAction: Boolean,
    val playbackClosed: Boolean,
    val ownerStillCurrent: Boolean,
)

enum class MetadataPrepareRejection {
    ACTION_REQUIRED, OWNER_CHANGED, ACTIVE_PLAYBACK, INVALID_TARGET, INVALID_SIZE,
    UNVERIFIED_REVIEW, TARGET_MISMATCH, NO_CHANGED_FIELDS, UNSUPPORTED_PROVIDER,
    UNSUPPORTED_CONTAINER, STAGING_NOT_ALLOWED, RECOVERY_PENDING, NO_PRIVATE_SPACE,
    PROVIDER_CHANGED, PROVIDER_UNREADABLE, READ_PERMISSION_DENIED, INVALID_PROVIDER_STAMP,
    PRIVATE_IO_FAILED, STAGING_FAILED,
    JOURNAL_UNAVAILABLE, PREPARED_NOT_COMMIT_CERTIFIED,
}

sealed interface MetadataPrepareDecision {
    data object EligibleToInspectProvider : MetadataPrepareDecision
    data class Refused(val reason: MetadataPrepareRejection) : MetadataPrepareDecision
}

object MetadataPreparePolicy {
    const val MAX_ORIGINAL_BYTES = 1024L * 1024 * 1024
    const val REQUIRED_RESERVE_BYTES = 64L * 1024 * 1024

    fun evaluate(ticket: MetadataPrepareTicket, review: MetadataReview): MetadataPrepareDecision {
        if (!ticket.explicitUserAction) return MetadataPrepareDecision.Refused(MetadataPrepareRejection.ACTION_REQUIRED)
        if (!ticket.ownerStillCurrent) return MetadataPrepareDecision.Refused(MetadataPrepareRejection.OWNER_CHANGED)
        if (!ticket.playbackClosed) return MetadataPrepareDecision.Refused(MetadataPrepareRejection.ACTIVE_PLAYBACK)
        if (ticket.trackId.isBlank() || !ticket.uri.startsWith("content://"))
            return MetadataPrepareDecision.Refused(MetadataPrepareRejection.INVALID_TARGET)
        if (ticket.expectedBytes !in 12..MAX_ORIGINAL_BYTES)
            return MetadataPrepareDecision.Refused(MetadataPrepareRejection.INVALID_SIZE)
        val ready = review as? MetadataReview.Ready
            ?: return MetadataPrepareDecision.Refused(MetadataPrepareRejection.UNVERIFIED_REVIEW)
        if (!ready.original.tagsVerified || ready.diffs.isEmpty() ||
            ready.diffs.any { it.field !in ready.original.verifiedFields })
            return MetadataPrepareDecision.Refused(MetadataPrepareRejection.UNVERIFIED_REVIEW)
        // Never trust a forged Ready receipt. Rebuild the exact net diff against
        // the original observation before allocating any private backup.
        val changes = ready.diffs.map { diff ->
            if (diff.after.isEmpty()) MetadataChange.Clear(diff.field)
            else MetadataChange.Replace(diff.field, diff.after)
        }
        if (MetadataEditPreview.review(ready.original, changes) != ready)
            return MetadataPrepareDecision.Refused(MetadataPrepareRejection.UNVERIFIED_REVIEW)
        if (ready.original.target.sourceId != ticket.sourceId ||
            ready.original.target.trackId != ticket.trackId ||
            ready.original.target.uri != ticket.uri ||
            ready.proposed.target != ready.original.target)
            return MetadataPrepareDecision.Refused(MetadataPrepareRejection.TARGET_MISMATCH)
        return MetadataPrepareDecision.EligibleToInspectProvider
    }

    fun hasPrivateSpace(usableBytes: Long, originalBytes: Long): Boolean =
        originalBytes in 12..MAX_ORIGINAL_BYTES &&
            usableBytes >= originalBytes * 2 + REQUIRED_RESERVE_BYTES
}

/** Only non-mutating phases are authorized to be discarded in ME-05. */
enum class MetadataRecoveryPhase {
    SNAPSHOTTING, BACKUP_DURABLE, STAGE_READY,
    MUTATING, MUTATION_UNKNOWN, POSTCHECK_VERIFIED, ROLLBACK_PENDING,
    RESTORED_VERIFIED, CLEANUP_SAFE,
}

object MetadataRecoveryPhasePolicy {
    fun canDiscardPrivateOnly(phase: MetadataRecoveryPhase): Boolean = phase in setOf(
        MetadataRecoveryPhase.SNAPSHOTTING,
        MetadataRecoveryPhase.BACKUP_DURABLE,
        MetadataRecoveryPhase.STAGE_READY,
    )
    fun sourceMayHaveChanged(phase: MetadataRecoveryPhase): Boolean = phase in setOf(
        MetadataRecoveryPhase.MUTATING, MetadataRecoveryPhase.MUTATION_UNKNOWN,
        MetadataRecoveryPhase.POSTCHECK_VERIFIED, MetadataRecoveryPhase.ROLLBACK_PENDING,
        MetadataRecoveryPhase.RESTORED_VERIFIED, MetadataRecoveryPhase.CLEANUP_SAFE,
    )
}
