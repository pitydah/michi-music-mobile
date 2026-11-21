package org.michimusic.core.metadata

/**
 * The library can legitimately have a track row without a historical SHA.
 * A missing SHA is never manufactured from file size or mtime. This permits a
 * guarded cache-only CAS strictly AFTER an independent source-origin SHA and
 * candidate postcheck have been verified against the V1 journal.
 * No method here provides write certification or writes to Room.
 */
object MetadataUnverifiedIndexRowPolicy {
    data class Row(
        val sourceId: String, val trackId: String, val uri: String,
        val bytes: Long, val verifiedBytes: Long, val verifiedAt: Long,
        val verifiedSha256: String, val verifiedAlgorithm: String,
    )
    enum class Reason { INVALID_OWNER, WRONG_INDEX_VERSION, PRIOR_PROOF_EXISTS,
        UNVERIFIED_JOURNAL_ORIGINAL, INVALID_ROW }
    sealed interface Verdict {
        data object MayAttemptExternalCas : Verdict
        data class Blocked(val reason: Reason) : Verdict
    }
    private val hex = Regex("[0-9a-f]{64}")

    fun inspect(
        row: Row, expected: MetadataIndexPublicationPolicy.IndexIdentity,
        journalOriginalSha256: String,
    ): Verdict {
        if (row.trackId.isBlank() || !row.uri.startsWith("content://") ||
            row.sourceId != expected.sourceId || row.trackId != expected.trackId ||
            row.uri != expected.uri) return Verdict.Blocked(Reason.INVALID_OWNER)
        if (row.bytes !in 12..(1024L * 1024 * 1024))
            return Verdict.Blocked(Reason.INVALID_ROW)
        if (expected.version != 0L || expected.observedSourceSha256.isNotEmpty())
            return Verdict.Blocked(Reason.WRONG_INDEX_VERSION)
        if (row.verifiedBytes != 0L || row.verifiedAt != 0L ||
            row.verifiedSha256.isNotEmpty() || row.verifiedAlgorithm.isNotEmpty())
            return Verdict.Blocked(Reason.PRIOR_PROOF_EXISTS)
        if (!hex.matches(journalOriginalSha256) ||
            journalOriginalSha256 == "0".repeat(64))
            return Verdict.Blocked(Reason.UNVERIFIED_JOURNAL_ORIGINAL)
        return Verdict.MayAttemptExternalCas
    }
}
