package org.michimusic.core.metadata

/** ME-06: user grant != certified byte-preserving, recoverable commit. */
enum class MetadataExactGrantKind {
    MEDIASTORE_ITEM, SAF_DOCUMENT,
}

enum class MetadataCommitBlock {
    NOT_PREPARED, NOT_LOCAL, WRONG_DOCUMENT, WRONG_OWNER, NO_EXPLICIT_USER_ACTION,
    PLAYBACK_ACTIVE, GRANT_NOT_CONFIRMED, PROVIDER_NOT_RECOVERABLE,
    CAPABILITY_NOT_CERTIFIED, RECOVERY_PENDING, CHANGED_SOURCE,
}

data class MetadataExactCommitRequest(
    val sourceId: String,
    val trackId: String,
    val contentUri: String,
    val transactionId: String,
    val provider: MetadataExactGrantKind,
    val explicitUserAction: Boolean,
    val playbackClosed: Boolean,
    val ownerStillCurrent: Boolean,
    val grantConfirmed: Boolean,
    val providerRecoverable: Boolean,
    val exactTargetCurrent: Boolean,
    val recoveryPending: Boolean,
)

sealed interface MetadataCommitGateResult {
    data object ReadyForCertifiedCapabilityCheck : MetadataCommitGateResult
    data class Blocked(val reason: MetadataCommitBlock) : MetadataCommitGateResult
}

object MetadataCommitAccessPolicy {
    /** This can validate a request, but NEVER declares a format/provider certified. */
    fun evaluate(request: MetadataExactCommitRequest, preparedTransactionId: String,
        preparedSourceId: String, preparedTrackId: String, preparedUri: String,
        preparedProvider: MetadataExactGrantKind): MetadataCommitGateResult {
        if (preparedTransactionId.isBlank() || request.transactionId != preparedTransactionId)
            return MetadataCommitGateResult.Blocked(MetadataCommitBlock.NOT_PREPARED)
        if (!request.contentUri.startsWith("content://"))
            return MetadataCommitGateResult.Blocked(MetadataCommitBlock.NOT_LOCAL)
        if (request.contentUri != preparedUri || request.provider != preparedProvider)
            return MetadataCommitGateResult.Blocked(MetadataCommitBlock.WRONG_DOCUMENT)
        if (request.sourceId != preparedSourceId || request.trackId != preparedTrackId)
            return MetadataCommitGateResult.Blocked(MetadataCommitBlock.WRONG_OWNER)
        if (!request.explicitUserAction)
            return MetadataCommitGateResult.Blocked(MetadataCommitBlock.NO_EXPLICIT_USER_ACTION)
        if (!request.playbackClosed)
            return MetadataCommitGateResult.Blocked(MetadataCommitBlock.PLAYBACK_ACTIVE)
        if (!request.ownerStillCurrent || !request.exactTargetCurrent)
            return MetadataCommitGateResult.Blocked(MetadataCommitBlock.CHANGED_SOURCE)
        if (request.recoveryPending)
            return MetadataCommitGateResult.Blocked(MetadataCommitBlock.RECOVERY_PENDING)
        if (!request.grantConfirmed)
            return MetadataCommitGateResult.Blocked(MetadataCommitBlock.GRANT_NOT_CONFIRMED)
        if (!request.providerRecoverable)
            return MetadataCommitGateResult.Blocked(MetadataCommitBlock.PROVIDER_NOT_RECOVERABLE)
        return MetadataCommitGateResult.ReadyForCertifiedCapabilityCheck
    }
}

/**
 * There are NO certified physical write capabilities in the current patch.
 * A future OpenCode-certified manifest will be keyed by container/tag revision,
 * field set, provider behavior and recovery proof; never by extension alone.
 */
object MetadataCommitCertification {
    const val WRITE_CAPABILITIES_CERTIFIED: Boolean = false
    fun mayMutateOriginal(): Boolean = WRITE_CAPABILITIES_CERTIFIED
}
