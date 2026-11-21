package org.michimusic.core.metadata

/**
 * ME-08: classifications from a self-owned *synthetic* Android provider fixture.
 * These observations can populate an external certification worksheet only.
 * Never convert this result to a source-mutation grant, a saved receipt or a Room update.
 */
object MetadataSyntheticProviderEvidence {
    enum class Cut { NONE, BEFORE_OPEN, AFTER_TRUNCATE, AFTER_FIRST_BLOCK, AFTER_SYNC }
    enum class Outcome {
        UNCHANGED, CANDIDATE_IN_SYNTHETIC_PROVIDER, PARTIAL_OR_FOREIGN_BYTES,
        TRUNCATED_TO_ZERO, OBSERVATION_UNAVAILABLE, INVALID_FIXTURE,
    }
    enum class Restore { SYNTHETIC_ORIGINAL_RESTORED, STILL_ORIGINAL, NOT_RESTORED, BLOCKED }

    data class Fingerprint(val size: Long, val sha256: String) {
        fun valid(): Boolean = size >= 0 && MetadataSyntheticProviderEvidence.SHA.matches(sha256)
    }
    data class Trial(
        val authority: String,
        val documentId: String,
        val original: Fingerprint,
        val candidate: Fingerprint,
        val readback: Fingerprint?,
        val cut: Cut,
        val providerWriteClosed: Boolean,
    )
    private val SHA = Regex("[a-f0-9]{64}")
    const val FIXTURE_AUTHORITY = "org.michimusic.data.metadata.me08.testing"
    private val fixtureDocument = Regex("me08-fixture-[a-f0-9]{32}")

    fun classify(t: Trial): Outcome {
        if (t.authority != FIXTURE_AUTHORITY || !fixtureDocument.matches(t.documentId) ||
            !t.original.valid() || !t.candidate.valid() ||
            t.original.size == 0L || t.candidate.size == 0L ||
            t.original == t.candidate) return Outcome.INVALID_FIXTURE
        val observed = t.readback ?: return Outcome.OBSERVATION_UNAVAILABLE
        if (!observed.valid()) return Outcome.OBSERVATION_UNAVAILABLE
        return when {
            observed == t.original -> Outcome.UNCHANGED
            observed.size == 0L -> Outcome.TRUNCATED_TO_ZERO
            // Byte identity is necessary. A successful close, ownership and
            // a synthetic test still cannot certify a real Android source write.
            observed == t.candidate -> Outcome.CANDIDATE_IN_SYNTHETIC_PROVIDER
            else -> Outcome.PARTIAL_OR_FOREIGN_BYTES
        }
    }

    fun restoration(t: Trial, afterRestore: Fingerprint?, explicitConfirmation: Boolean): Restore {
        if (!explicitConfirmation || classify(t) in setOf(Outcome.INVALID_FIXTURE,
                Outcome.OBSERVATION_UNAVAILABLE)) return Restore.BLOCKED
        if (t.readback == t.original) return Restore.STILL_ORIGINAL
        return if (afterRestore == t.original) Restore.SYNTHETIC_ORIGINAL_RESTORED
        else Restore.NOT_RESTORED
    }

    /** This laboratory has no capability to enable the product's source writer. */
    fun mayEnableRealSourceWrites(@Suppress("UNUSED_PARAMETER") outcome: Outcome): Boolean = false
}
