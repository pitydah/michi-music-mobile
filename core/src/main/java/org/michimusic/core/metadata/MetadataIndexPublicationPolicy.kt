package org.michimusic.core.metadata

/**
 * ME-09: models a compare-and-swap *request*, not a Room write.
 * A valid postcheck is necessary but insufficient: the receipt must be issued
 * by a certified original-source mutation, which does not yet exist.
 */
object MetadataIndexPublicationPolicy {
    data class IndexIdentity(
        val sourceId: String, val trackId: String, val uri: String,
        val version: Long, val observedSourceSha256: String,
        /** Monotonic Room identity; independent from the verification timestamp. */
        val metadataRevision: Long = 0,
    )
    data class Request(
        val expected: IndexIdentity,
        val current: IndexIdentity,
        val transactionId: String,
        val stageSha256: String,
        val journalPhase: MetadataRecoveryPhase,
        val receipt: MetadataVerifiedReceiptGate.Decision,
        val semanticReadbackVerified: Boolean,
        val playbackClosed: Boolean,
    )
    enum class Denial {
        NO_CERTIFIED_SOURCE_WRITER, INVALID_IDENTITY, STALE_INDEX,
        WRONG_TRANSACTION, WRONG_DIGEST, JOURNAL_NOT_VERIFIED,
        READBACK_NOT_VERIFIED, PLAYBACK_ACTIVE,
    }
    sealed interface Decision {
        data class Blocked(val reason: Denial) : Decision
        /** This is a CAS proposal, not a completed Room transaction. */
        data class PreparedCas(
            val expected: IndexIdentity, val transactionId: String,
            val verifiedStageSha256: String,
        ) : Decision
    }
    sealed interface Structural {
        data class Blocked(val reason: Denial) : Structural
        /** Read-only preflight. Never use this as authority to publish Room. */
        data object Comparable : Structural
    }
    private val sha = Regex("[0-9a-f]{64}")
    private val tx = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")

    fun inspectStructure(r: Request): Structural {
        if (r.expected.trackId.isBlank() || r.expected.uri.isBlank() ||
            !r.expected.uri.startsWith("content://") || r.expected.version < 0 ||
            !(sha.matches(r.expected.observedSourceSha256) ||
                (r.expected.version == 0L && r.expected.observedSourceSha256.isEmpty())) ||
            !sha.matches(r.stageSha256) || !tx.matches(r.transactionId))
            return Structural.Blocked(Denial.INVALID_IDENTITY)
        if (r.expected != r.current) return Structural.Blocked(Denial.STALE_INDEX)
        // A no-hash cache row is structurally distinct from a verified hash row.
        // Physical original/candidate hashes remain required by the V1 journal
        // and the finalizer; neither is inferred from an empty cache field.
        if (r.expected.version > 0L && !sha.matches(r.expected.observedSourceSha256))
            return Structural.Blocked(Denial.INVALID_IDENTITY)
        val receipt = r.receipt as? MetadataVerifiedReceiptGate.Decision.ReadyForIndexCompare
            ?: return Structural.Blocked(Denial.WRONG_TRANSACTION)
        if (receipt.transactionId != r.transactionId)
            return Structural.Blocked(Denial.WRONG_TRANSACTION)
        if (receipt.candidateDigest != r.stageSha256)
            return Structural.Blocked(Denial.WRONG_DIGEST)
        if (r.journalPhase != MetadataRecoveryPhase.POSTCHECK_VERIFIED)
            return Structural.Blocked(Denial.JOURNAL_NOT_VERIFIED)
        if (!r.semanticReadbackVerified) return Structural.Blocked(Denial.READBACK_NOT_VERIFIED)
        if (!r.playbackClosed) return Structural.Blocked(Denial.PLAYBACK_ACTIVE)
        return Structural.Comparable
    }

    fun assess(r: Request): Decision {
        // Tests can inspect structural prerequisites but cannot impersonate certification.
        if (!MetadataCommitCertification.mayMutateOriginal())
            return Decision.Blocked(Denial.NO_CERTIFIED_SOURCE_WRITER)
        return when (val structural = inspectStructure(r)) {
            is Structural.Blocked -> Decision.Blocked(structural.reason)
            Structural.Comparable -> Decision.PreparedCas(r.expected,r.transactionId,r.stageSha256)
        }
    }

    /** The current build cannot publish an index from an uncertified experiment. */
    fun mayPublishNow(): Boolean = MetadataCommitCertification.mayMutateOriginal()
}
