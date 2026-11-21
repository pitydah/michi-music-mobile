package org.michimusic.core.metadata

/**
 * ME-07B. Evidence checklist for CERTIFICATION REVIEW of a particular provider,
 * codec revision and set of changed fields. The booleans represent evidence
 * supplied by an external Android test run, not facts inferred from file names,
 * MIME types, permission grants or the private fault-injection sandbox.
 *
 * No state in this policy can authorize a source write. The real certification
 * gate deliberately remains closed until provider-specific crash/restore tests.
 */
object MetadataProviderRecoveryProfile {
    enum class Kind { MEDIASTORE_AUDIO, SAF_LOCAL_DOCUMENT, UNKNOWN_OR_REMOTE }
    enum class Verdict {
        REJECT_PROVIDER, DENY_UNSCOPED_GRANT, DENY_UNVERIFIED_STAMP,
        DENY_ACTIVE_PLAYBACK, DENY_UNVERIFIED_BACKUP,
        DENY_UNPROVEN_WRITE_BEHAVIOR, DENY_UNPROVEN_RECOVERY,
        DENY_UNVERIFIED_READBACK, READY_FOR_EXTERNAL_REVIEW,
    }
    data class Evidence(
        val provider: Kind,
        val exactUriGrant: Boolean,
        val localOwnerAndDocumentRevalidated: Boolean,
        val sourceClosedForPlayback: Boolean,
        val originalBackupFullShaVerified: Boolean,
        val stagedCandidateFullShaVerified: Boolean,
        val testedAfterPartialWrite: Boolean,
        val testedAfterTruncate: Boolean,
        val testedAfterProviderCloseFailure: Boolean,
        val originalRestoreFullShaVerified: Boolean,
        val unknownTagsAndAudioBytesPreserved: Boolean,
        val reopenExactUriVerified: Boolean,
        val changedFieldsReadbackVerified: Boolean,
        /** Must be captured for the same specific Android provider and operation. */
        val providerCapabilityRecordExists: Boolean,
    )

    fun evaluate(e: Evidence): Verdict = when {
        e.provider == Kind.UNKNOWN_OR_REMOTE -> Verdict.REJECT_PROVIDER
        !e.exactUriGrant -> Verdict.DENY_UNSCOPED_GRANT
        !e.localOwnerAndDocumentRevalidated -> Verdict.DENY_UNVERIFIED_STAMP
        !e.sourceClosedForPlayback -> Verdict.DENY_ACTIVE_PLAYBACK
        !e.originalBackupFullShaVerified || !e.stagedCandidateFullShaVerified ->
            Verdict.DENY_UNVERIFIED_BACKUP
        !e.testedAfterPartialWrite || !e.testedAfterTruncate ||
            !e.testedAfterProviderCloseFailure || !e.providerCapabilityRecordExists ->
            Verdict.DENY_UNPROVEN_WRITE_BEHAVIOR
        !e.originalRestoreFullShaVerified || !e.unknownTagsAndAudioBytesPreserved ->
            Verdict.DENY_UNPROVEN_RECOVERY
        !e.reopenExactUriVerified || !e.changedFieldsReadbackVerified ->
            Verdict.DENY_UNVERIFIED_READBACK
        else -> Verdict.READY_FOR_EXTERNAL_REVIEW
    }

    /** In particular, READY_FOR_EXTERNAL_REVIEW is NOT WRITE_CERTIFIED. */
    fun permitsSourceMutation(e: Evidence): Boolean = false
}
