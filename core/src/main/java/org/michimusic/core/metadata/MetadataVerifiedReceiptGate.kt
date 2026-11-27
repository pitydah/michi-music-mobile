package org.michimusic.core.metadata

import java.net.URI

/**
 * ME-07. A readback after an independently prepared candidate is not proof
 * of an authorized write. This gate prevents callers from issuing a durable
 * SAVED receipt if the journal never entered a certified physical mutation.
 * There are intentionally no certified capabilities in this patch.
 */
data class CertifiedWriteEvidence(
    val transactionId: String,
    val profileId: String,
    val evidenceId: String,
    val sourceId: String,
    val trackId: String,
    val uri: String,
    val originalSha256: String,
    val candidateSha256: String,
    val candidateBytes: Long,
)

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
    ): Decision = assessAuthorized(
        transactionId, phase, expectedStageSha256, physical, fieldsVerified,
        ownerStillCurrent, backupIntact,
        certified = MetadataCommitCertification.mayMutateOriginal(),
    )

    /** Not exposed as a public caller-controlled "certified=true" shortcut. */
    private fun assessAuthorized(
        transactionId: String,
        phase: MetadataRecoveryPhase,
        expectedStageSha256: String,
        physical: MetadataPhysicalOutcomePolicy.State,
        fieldsVerified: Boolean,
        ownerStillCurrent: Boolean,
        backupIntact: Boolean,
        certified: Boolean,
    ): Decision = when {
        !txId.matches(transactionId) || !digest.matches(expectedStageSha256) ->
            Decision.Refused(Refusal.INVALID_TRANSACTION)
        phase != MetadataRecoveryPhase.POSTCHECK_VERIFIED ->
            Decision.Refused(Refusal.NOT_SOURCE_MUTATION)
        !certified ->
            Decision.Refused(Refusal.WRITER_NOT_CERTIFIED)
        !ownerStillCurrent -> Decision.Refused(Refusal.OWNER_CHANGED)
        !backupIntact -> Decision.Refused(Refusal.PRIVATE_BACKUP_NOT_INTACT)
        physical != MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED ->
            Decision.Refused(Refusal.BYTES_NOT_CANDIDATE)
        !fieldsVerified -> Decision.Refused(Refusal.FIELDS_NOT_VERIFIED)
        else -> Decision.ReadyForIndexCompare(transactionId, expectedStageSha256)
    }
    /**
     * ME-13 scoped route: the typed permit replaces the legacy global boolean.
     * The permit itself is built only after the certified MP3 profile and the
     * exact journal transaction/candidate were validated.
     */
    fun assessCertified(
        evidence: CertifiedWriteEvidence,
        transactionId: String,
        phase: MetadataRecoveryPhase,
        expectedStageSha256: String,
        physical: MetadataPhysicalOutcomePolicy.State,
        fieldsVerified: Boolean,
        ownerStillCurrent: Boolean,
        backupIntact: Boolean,
    ): Decision {
        // Requiring a named profile is stronger than an arbitrary nonempty
        // evidence label. The finalizer independently verifies the typed permit
        // against the exact journal entry and candidate before issuing a receipt.
        val profile = MetadataWriteProfileRegistry.byId(evidence.profileId)
        val parsed = runCatching { URI(evidence.uri) }.getOrNull()
        val providerMatches = profile != null && parsed != null &&
            when (MetadataReadAccessPolicy.classify(
                evidence.sourceId, parsed.scheme, parsed.authority,
            )) {
                MetadataReadAccessPolicy.Kind.MEDIASTORE ->
                    profile.provider == MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO
                MetadataReadAccessPolicy.Kind.SAF_LOCAL ->
                    profile.provider ==
                        MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT
                MetadataReadAccessPolicy.Kind.REJECT -> false
            }
        if (!txId.matches(transactionId) ||
            evidence.transactionId != transactionId ||
            evidence.candidateSha256 != expectedStageSha256 ||
            !digest.matches(evidence.originalSha256) ||
            !digest.matches(evidence.candidateSha256) ||
            profile?.capabilityEvidenceId != evidence.evidenceId ||
            !providerMatches ||
            evidence.trackId.isBlank() ||
            evidence.candidateBytes !in 12L..(1024L * 1024L * 1024L))
            return Decision.Refused(Refusal.INVALID_TRANSACTION)
        return assessAuthorized(transactionId, phase, expectedStageSha256, physical,
            fieldsVerified, ownerStillCurrent, backupIntact, certified = true)
    }

}
