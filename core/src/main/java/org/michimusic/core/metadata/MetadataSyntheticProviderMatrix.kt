package org.michimusic.core.metadata

/**
 * ME-08. Audit-only coverage of one disposable DocumentsProvider fixture.
 * No implicit success from a single write; every destructive cut must be
 * restored and verified in a separate explicit action.
 */
object MetadataSyntheticProviderMatrix {
    data class Step(
        val documentId: String,
        val cut: MetadataSyntheticProviderEvidence.Cut,
        val outcome: MetadataSyntheticProviderEvidence.Outcome,
        val restoration: MetadataSyntheticProviderEvidence.Restore,
        val mode: String = "rwt",
    )
    enum class Verdict {
        INVALID_FIXTURE, INCOMPLETE_SCENARIOS, UNRECOVERED_TEST_BYTES,
        INCONSISTENT_OBSERVATION, COMPLETE_SYNTHETIC_ONLY,
    }
    private val required = MetadataSyntheticProviderEvidence.Cut.entries.toSet()

    fun evaluate(steps: List<Step>): Verdict {
        if (steps.isEmpty() || steps.size > 8 || steps.map { it.documentId }.distinct().size != 1 ||
            steps.any { it.mode != "rwt" ||
                !it.documentId.matches(Regex("me08-fixture-[a-f0-9]{32}")) ||
                it.outcome in setOf(MetadataSyntheticProviderEvidence.Outcome.INVALID_FIXTURE,
                    MetadataSyntheticProviderEvidence.Outcome.OBSERVATION_UNAVAILABLE) })
            return Verdict.INVALID_FIXTURE
        if (steps.map { it.cut }.toSet() != required || steps.size != required.size)
            return Verdict.INCOMPLETE_SCENARIOS
        if (steps.any { it.restoration !in setOf(
                MetadataSyntheticProviderEvidence.Restore.STILL_ORIGINAL,
                MetadataSyntheticProviderEvidence.Restore.SYNTHETIC_ORIGINAL_RESTORED) })
            return Verdict.UNRECOVERED_TEST_BYTES
        for (step in steps) {
            val expected = when (step.cut) {
                MetadataSyntheticProviderEvidence.Cut.BEFORE_OPEN ->
                    MetadataSyntheticProviderEvidence.Outcome.UNCHANGED
                MetadataSyntheticProviderEvidence.Cut.AFTER_TRUNCATE ->
                    MetadataSyntheticProviderEvidence.Outcome.TRUNCATED_TO_ZERO
                MetadataSyntheticProviderEvidence.Cut.AFTER_FIRST_BLOCK ->
                    MetadataSyntheticProviderEvidence.Outcome.PARTIAL_OR_FOREIGN_BYTES
                MetadataSyntheticProviderEvidence.Cut.AFTER_SYNC,
                MetadataSyntheticProviderEvidence.Cut.NONE ->
                    MetadataSyntheticProviderEvidence.Outcome.CANDIDATE_IN_SYNTHETIC_PROVIDER
            }
            if (step.outcome != expected) return Verdict.INCONSISTENT_OBSERVATION
        }
        return Verdict.COMPLETE_SYNTHETIC_ONLY
    }

    fun mayEnableOriginalWrite(@Suppress("UNUSED_PARAMETER") verdict: Verdict): Boolean = false
}
