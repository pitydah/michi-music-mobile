package org.michimusic.core.metadata

/**
 * ME-06. Interpret an actual, complete readback against two independently
 * preserved digests. Equal size, a fresh timestamp or a successful close()
 * are never proof that the provider saved the candidate.
 *
 * Pure domain classification: it does not grant permission or mutate a file.
 */
object MetadataPhysicalOutcomePolicy {
    private val sha256 = Regex("[a-f0-9]{64}")

    data class Expected(
        val originalBytes: Long,
        val originalSha256: String,
        val candidateBytes: Long,
        val candidateSha256: String,
    ) {
        fun valid(): Boolean = originalBytes > 0 && candidateBytes > 0 &&
            sha256.matches(originalSha256) && sha256.matches(candidateSha256) &&
            (originalBytes != candidateBytes || originalSha256 != candidateSha256)
    }

    data class Observed(val bytes: Long, val sha256: String) {
        fun valid(): Boolean = bytes > 0 && MetadataPhysicalOutcomePolicy.sha256.matches(sha256)
    }

    enum class State {
        ORIGINAL_INTACT, CANDIDATE_BYTES_VERIFIED, UNKNOWN_BYTES,
        READBACK_UNAVAILABLE, INVALID_EXPECTATION,
    }

    fun classify(expected: Expected, observed: Observed?): State {
        if (!expected.valid()) return State.INVALID_EXPECTATION
        if (observed == null || !observed.valid()) return State.READBACK_UNAVAILABLE
        return when {
            observed.bytes == expected.originalBytes && observed.sha256 == expected.originalSha256 ->
                State.ORIGINAL_INTACT
            observed.bytes == expected.candidateBytes && observed.sha256 == expected.candidateSha256 ->
                State.CANDIDATE_BYTES_VERIFIED
            else -> State.UNKNOWN_BYTES
        }
    }
}

/**
 * A verified candidate allows index refresh, not a second write. The source
 * must be parsed again with the exact changed fields before publishing Room.
 */
object MetadataReconciliationPolicy {
    enum class State { ELIGIBLE_AFTER_FIELD_READBACK, ORIGINAL_NOT_MODIFIED, BLOCKED_UNKNOWN, BLOCKED_UNVERIFIED }
    fun evaluate(
        physical: MetadataPhysicalOutcomePolicy.State,
        fieldsCompared: Boolean,
        targetStillCurrent: Boolean,
    ): State = when {
        !targetStillCurrent -> State.BLOCKED_UNKNOWN
        physical == MetadataPhysicalOutcomePolicy.State.ORIGINAL_INTACT -> State.ORIGINAL_NOT_MODIFIED
        physical != MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED -> State.BLOCKED_UNKNOWN
        !fieldsCompared -> State.BLOCKED_UNVERIFIED
        else -> State.ELIGIBLE_AFTER_FIELD_READBACK
    }
}
