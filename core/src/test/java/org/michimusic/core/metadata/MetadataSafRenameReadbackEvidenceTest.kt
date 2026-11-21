package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataSafRenameReadbackEvidenceTest {
    private val h = "a".repeat(64)
    private val transaction = "123e4567-e89b-42d3-a456-426614174000"
    private val old = "content://local/tree/root/document/a"
    private val newer = "content://local/tree/root/document/b"
    private val parent = "content://local/tree/root/document/parent"
    private val intent = MetadataSafRenameRecoveryPolicy.Intent(
        transaction, "", "song", old, "a", parent, "Before.flac", "After.flac", h, 100)
    private val row = MetadataSafRenameReadbackEvidence.Row("b", newer, "After.flac", 100)
    private val scan = MetadataSafRenameReadbackEvidence.ParentScan(parent, true, listOf(row))
    private val read = MetadataSafRenameReadbackEvidence.ColdProbe(newer, 100, h, true)

    private fun evaluate(
        first: MetadataSafRenameReadbackEvidence.ParentScan = scan,
        second: MetadataSafRenameReadbackEvidence.ParentScan = scan,
        probes: List<MetadataSafRenameReadbackEvidence.ColdProbe> = listOf(read),
        certified: Boolean = true,
        owner: String = "",
        parentVerified: Boolean = true,
        grant: Boolean = true,
        playbackClosed: Boolean = true,
    ) = MetadataSafRenameReadbackEvidence.assemble(intent, owner, "song", first, second,
        probes, certified, parentVerified, grant, playbackClosed)

    private fun rejected(reason: MetadataSafRenameReadbackEvidence.Rejection, result: Any) =
        assertEquals(MetadataSafRenameReadbackEvidence.Result.Rejected(reason), result)

    @Test fun completeIndependentDoubleListingCanProduceObservation() {
        val result = evaluate() as MetadataSafRenameReadbackEvidence.Result.Ready
        val withReturnedUri = result.observation.copy(returnedDocumentUri = newer)
        assertEquals(MetadataSafRenameRecoveryPolicy.Verdict.RenamedVerified(newer, "b"),
            MetadataSafRenameRecoveryPolicy.reconcile(intent, withReturnedUri))
    }
    @Test fun uncertifiedProviderNeverClaimsCompleteListing() = rejected(
        MetadataSafRenameReadbackEvidence.Rejection.PROVIDER_NOT_CERTIFIED, evaluate(certified=false))
    @Test fun wrongOwnerRejectedBeforeAnyConclusion() = rejected(
        MetadataSafRenameReadbackEvidence.Rejection.WRONG_OWNER, evaluate(owner="other"))
    @Test fun noExactGrantIsNotAnObservation() = rejected(
        MetadataSafRenameReadbackEvidence.Rejection.NO_GRANT, evaluate(grant=false))
    @Test fun playbackMustBeStopped() = rejected(
        MetadataSafRenameReadbackEvidence.Rejection.PLAYBACK_ACTIVE, evaluate(playbackClosed=false))
    @Test fun parentMustBeIndependentlyProven() = rejected(
        MetadataSafRenameReadbackEvidence.Rejection.WRONG_PARENT, evaluate(parentVerified=false))
    @Test fun loadingCursorNeverCountsAsComplete() = rejected(
        MetadataSafRenameReadbackEvidence.Rejection.INCOMPLETE_LISTING,
        evaluate(second=scan.copy(completelyEnumerated=false)))
    @Test fun changedRowBetweenReadsRejected() = rejected(
        MetadataSafRenameReadbackEvidence.Rejection.CHANGED_DURING_READBACK,
        evaluate(second=scan.copy(rows=listOf(row.copy(name="After (1).flac")))))
    @Test fun reorderedButIdenticalRowsAccepted() {
        val extra = MetadataSafRenameReadbackEvidence.Row("unrelated","content://local/document/unused","Other.flac",5)
        val first = scan.copy(rows=listOf(extra,row))
        val second = scan.copy(rows=listOf(row,extra))
        assertTrue(evaluate(first, second) is MetadataSafRenameReadbackEvidence.Result.Ready)
    }
    @Test fun duplicateIdsRejected() = rejected(
        MetadataSafRenameReadbackEvidence.Rejection.AMBIGUOUS_LISTING,
        evaluate(first=scan.copy(rows=listOf(row,row)), second=scan.copy(rows=listOf(row,row))))
    @Test fun crossAuthorityIsNeverAllowed() = rejected(
        MetadataSafRenameReadbackEvidence.Rejection.INVALID_ROW,
        evaluate(first=scan.copy(rows=listOf(row.copy(uri="content://foreign/document/b"))),
            second=scan.copy(rows=listOf(row.copy(uri="content://foreign/document/b"))),
            probes=listOf(read.copy(uri="content://foreign/document/b"))))
    @Test fun missingCandidateProbeRejected() = rejected(
        MetadataSafRenameReadbackEvidence.Rejection.MISSING_COLD_PROBE, evaluate(probes=emptyList()))
    @Test fun forgedCandidateProbeRejected() = rejected(
        MetadataSafRenameReadbackEvidence.Rejection.MISSING_COLD_PROBE,
        evaluate(probes=listOf(read.copy(uri=old))))
    @Test fun incompleteColdReadCannotPass() = rejected(
        MetadataSafRenameReadbackEvidence.Rejection.INVALID_COLD_PROBE,
        evaluate(probes=listOf(read.copy(verifiedEndOfStream=false))))
    @Test fun declaredSizeMustMatchExactRead() = rejected(
        MetadataSafRenameReadbackEvidence.Rejection.INVALID_COLD_PROBE,
        evaluate(probes=listOf(read.copy(bytesRead=99))))
    @Test fun invalidShaRejected() = rejected(
        MetadataSafRenameReadbackEvidence.Rejection.INVALID_COLD_PROBE,
        evaluate(probes=listOf(read.copy(sha256="not-a-sha"))))
    @Test fun completeReadWithDifferentShaIsReportedToReconcilerNotAsSuccess() {
        val evidence = evaluate(probes=listOf(read.copy(sha256="b".repeat(64))))
            as MetadataSafRenameReadbackEvidence.Result.Ready
        assertEquals(MetadataSafRenameRecoveryPolicy.Verdict.Uncertain(
            MetadataSafRenameRecoveryPolicy.Reason.SOURCE_CHANGED),
            MetadataSafRenameRecoveryPolicy.reconcile(intent,
                evidence.observation.copy(returnedDocumentUri=newer)))
    }
    @Test fun noRowsMeansNoMatchingDocumentNotANewOne() {
        val empty = scan.copy(rows=emptyList())
        val proof = evaluate(first=empty,second=empty,probes=emptyList())
            as MetadataSafRenameReadbackEvidence.Result.Ready
        assertEquals(MetadataSafRenameRecoveryPolicy.Verdict.Uncertain(
            MetadataSafRenameRecoveryPolicy.Reason.NO_MATCH),
            MetadataSafRenameRecoveryPolicy.reconcile(intent,proof.observation))
    }
}
