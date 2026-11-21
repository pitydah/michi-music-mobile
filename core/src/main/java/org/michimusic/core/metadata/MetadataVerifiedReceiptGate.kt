package org.michimusic.core.metadata

/**
 * ME-07. A readback after an independently prepared candidate is not proof
 * of an authorized write. This gate prevents callers from issuing a durable
 * SAVED receipt if the journal never entered a certified physical mutation.
 * There are intentionally no certified capabilities in this patch.
 */
object MetadataVerifiedReceiptGate {
    enum class Refusal {
        INVALID_TRANSACTION, NOT_SOURCE_MUTATION, WRITER_NOT_CERTIFIED,
        OWNER_CHANGED, PRIVATE_BACKUP_NOT_INTACT,
        BYTES_NOT_CANDIDATE, FIELDS_NOT_VERIFIED,
    }
    sealed interface Decision {
        data class Refused(val reason: Refusal) : Decision
        data class ReadyForIndexCompare(val transactionId: String, val candidateDigest: String) : Decision
    }
    private val digest = Regex("[a-f0-9]{64}")
    private val txId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun assess(
        transactionId: String,
        phase: MetadataRecoveryPhase,
        expectedStageSha256: String,
        physical: MetadataPhysicalOutcomePolicy.State,
        fieldsVerified: Boolean,
        ownerStillCurrent: Boolean,
        backupIntact: Boolean,
    ): Decision = when {
        !txId.matches(transactionId) || !digest.matches(expectedStageSha256) ->
            Decision.Refused(Refusal.INVALID_TRANSACTION)
        phase != MetadataRecoveryPhase.POSTCHECK_VERIFIED ->
            Decision.Refused(Refusal.NOT_SOURCE_MUTATION)
        // Never admit a caller-provided boolean as certification evidence.
        !MetadataCommitCertification.mayMutateOriginal() ->
            Decision.Refused(Refusal.WRITER_NOT_CERTIFIED)
        !ownerStillCurrent -> Decision.Refused(Refusal.OWNER_CHANGED)
        !backupIntact -> Decision.Refused(Refusal.PRIVATE_BACKUP_NOT_INTACT)
        physical != MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED ->
            Decision.Refused(Refusal.BYTES_NOT_CANDIDATE)
        !fieldsVerified -> Decision.Refused(Refusal.FIELDS_NOT_VERIFIED)
        else -> Decision.ReadyForIndexCompare(transactionId, expectedStageSha256)
    }
}
