package org.michimusic.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ME-13 regression for the published E2E save: MediaStore announced `SIZE` can
 * still be the pre-write length and then jump to the physical candidate length
 * while the already committed bytes are read back. Content must decide, and a
 * moved announcement must be re-proved by one fresh read instead of being
 * reported as a source mutation. Any content mismatch stays a refusal even when
 * the stamp is perfectly stable.
 */
class MetadataCommittedCandidateStabilityTest {
    private val candidateBytes = 1_897L
    private val candidateSha = "5ff8673936917cf39c3fb978e7c7863c3777508c0ed6f4f1d909703ab2e7d9c3"
    private val staleBytes = 1_878L

    private fun announcement(bytes: Long, modified: Long = 1_795_402_477L) =
        MetadataCommittedCandidateStability.Announcement(bytes, modified)

    private fun assess(
        before: MetadataCommittedCandidateStability.Announcement,
        after: MetadataCommittedCandidateStability.Announcement,
        physicalBytes: Long = candidateBytes,
        physicalSha: String = candidateSha,
    ) = MetadataCommittedCandidateStability.assess(
        before = before,
        after = after,
        physicalBytes = physicalBytes,
        physicalSha256 = physicalSha,
        expectedBytes = candidateBytes,
        expectedSha256 = candidateSha,
    )

    @Test fun announcedSizeJumpingToThePhysicalCandidateRequiresOneFreshRead() {
        val verdict = assess(announcement(staleBytes), announcement(candidateBytes))
        assertEquals(MetadataCommittedCandidateStability.Verdict.AnnouncementMoved, verdict)
    }

    @Test fun announcedModificationTimeMoveAlsoRequiresOneFreshRead() {
        val verdict = assess(announcement(candidateBytes, 1L), announcement(candidateBytes, 2L))
        assertEquals(MetadataCommittedCandidateStability.Verdict.AnnouncementMoved, verdict)
    }

    @Test fun settledStampWithExactCandidateIsAccepted() {
        val verdict = assess(announcement(candidateBytes), announcement(candidateBytes))
        assertTrue("verdict=$verdict", verdict is MetadataCommittedCandidateStability.Verdict.Exact)
        verdict as MetadataCommittedCandidateStability.Verdict.Exact
        assertEquals(candidateBytes, verdict.announced.bytes)
        assertEquals(false, verdict.announcedLag)
    }

    @Test fun settledButStaleAnnouncedSizeIsReportedLagNotFailure() {
        val verdict = assess(announcement(staleBytes), announcement(staleBytes))
        assertTrue("verdict=$verdict", verdict is MetadataCommittedCandidateStability.Verdict.Exact)
        verdict as MetadataCommittedCandidateStability.Verdict.Exact
        assertEquals(staleBytes, verdict.announced.bytes)
        assertEquals(true, verdict.announcedLag)
    }

    @Test fun stableStampWithWrongShaIsRefused() {
        val verdict = assess(
            announcement(candidateBytes), announcement(candidateBytes),
            physicalSha = "0".repeat(64),
        )
        assertEquals(
            MetadataCommittedCandidateStability.Verdict.Refused(
                MetadataCommittedCandidateStability.Refusal.SHA_MISMATCH),
            verdict,
        )
    }

    @Test fun truncatedContentIsRefusedBeforeAnyStampTolerance() {
        val verdict = assess(
            announcement(staleBytes), announcement(staleBytes),
            physicalBytes = staleBytes,
        )
        assertEquals(
            MetadataCommittedCandidateStability.Verdict.Refused(
                MetadataCommittedCandidateStability.Refusal.ACTUAL_SIZE_MISMATCH),
            verdict,
        )
    }

    @Test fun resolveRetriesExactlyOnceWhenTheAnnouncementMoves() {
        var calls = 0
        val outcome = MetadataCommittedCandidateStability.resolve(
            expectedBytes = candidateBytes, expectedSha256 = candidateSha,
        ) {
            calls++
            if (calls == 1)
                MetadataCommittedCandidateStability.Observation(
                    before = announcement(staleBytes),
                    read = MetadataCommittedCandidateStability.BytesRead.Read(
                        candidateBytes, candidateSha),
                    after = announcement(candidateBytes),
                )
            else
                MetadataCommittedCandidateStability.Observation(
                    before = announcement(candidateBytes),
                    read = MetadataCommittedCandidateStability.BytesRead.Read(
                        candidateBytes, candidateSha),
                    after = announcement(candidateBytes),
                )
        }
        assertTrue("outcome=$outcome", outcome is MetadataCommittedCandidateStability.Outcome.Accepted)
        outcome as MetadataCommittedCandidateStability.Outcome.Accepted
        assertEquals(true, outcome.retried)
        assertEquals(candidateBytes, outcome.bytes)
        assertEquals(candidateSha, outcome.sha256)
        assertEquals(2, calls)
    }

    @Test fun resolveFailsClosedWhenTheAnnouncementKeepsMoving() {
        var calls = 0
        val outcome = MetadataCommittedCandidateStability.resolve(
            expectedBytes = candidateBytes, expectedSha256 = candidateSha,
        ) {
            calls++
            MetadataCommittedCandidateStability.Observation(
                before = announcement(staleBytes),
                read = MetadataCommittedCandidateStability.BytesRead.Read(
                    candidateBytes, candidateSha),
                after = announcement(candidateBytes),
            )
        }
        assertEquals(
            MetadataCommittedCandidateStability.Outcome.Rejected("STAMP_CHANGED_DURING_READ"),
            outcome,
        )
        assertEquals(2, calls)
    }

    @Test fun resolvePropagatesIoRejectionWithoutRetrying() {
        var calls = 0
        val outcome = MetadataCommittedCandidateStability.resolve(
            expectedBytes = candidateBytes, expectedSha256 = candidateSha,
        ) {
            calls++
            MetadataCommittedCandidateStability.Observation(
                before = announcement(candidateBytes),
                read = MetadataCommittedCandidateStability.BytesRead.Rejected("UNREADABLE"),
                after = announcement(candidateBytes),
            )
        }
        assertEquals(
            MetadataCommittedCandidateStability.Outcome.Rejected("UNREADABLE"), outcome,
        )
        assertEquals(1, calls)
    }

    @Test fun movedStampNeverRescuesNonCandidateContent() {
        val wrongLength = assess(
            announcement(staleBytes), announcement(candidateBytes),
            physicalBytes = staleBytes,
        )
        assertEquals(
            MetadataCommittedCandidateStability.Verdict.Refused(
                MetadataCommittedCandidateStability.Refusal.ACTUAL_SIZE_MISMATCH),
            wrongLength,
        )
        val wrongHash = assess(
            announcement(staleBytes), announcement(candidateBytes),
            physicalSha = "f".repeat(64),
        )
        assertEquals(
            MetadataCommittedCandidateStability.Verdict.Refused(
                MetadataCommittedCandidateStability.Refusal.SHA_MISMATCH),
            wrongHash,
        )
    }
}
