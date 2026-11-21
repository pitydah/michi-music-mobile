package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MetadataIndexPublicationPolicyTest {
    private val sha = "a".repeat(64)
    private val id = "11111111-2222-3333-4444-555555555555"
    private val index = MetadataIndexPublicationPolicy.IndexIdentity(
        "", "song1", "content://media/external/audio/media/2", 3, sha)
    private fun request() = MetadataIndexPublicationPolicy.Request(
        index, index, id, sha, MetadataRecoveryPhase.POSTCHECK_VERIFIED,
        MetadataVerifiedReceiptGate.Decision.ReadyForIndexCompare(id, sha), true, true)

    @Test fun nominalReceiptCannotBypassGlobalCertification() {
        assertEquals(MetadataIndexPublicationPolicy.Decision.Blocked(
            MetadataIndexPublicationPolicy.Denial.NO_CERTIFIED_SOURCE_WRITER),
            MetadataIndexPublicationPolicy.assess(request()))
    }
    @Test fun noPublicationEvenWhenShaAndJournalMatch() {
        assertFalse(MetadataIndexPublicationPolicy.mayPublishNow())
    }
    @Test fun alteredTrackCannotPublish() {
        assertEquals(MetadataIndexPublicationPolicy.Decision.Blocked(
            MetadataIndexPublicationPolicy.Denial.NO_CERTIFIED_SOURCE_WRITER),
            MetadataIndexPublicationPolicy.assess(request().copy(current=index.copy(trackId="other"))))
    }
    @Test fun forgedReceiptCannotPublish() {
        assertEquals(MetadataIndexPublicationPolicy.Decision.Blocked(
            MetadataIndexPublicationPolicy.Denial.NO_CERTIFIED_SOURCE_WRITER),
            MetadataIndexPublicationPolicy.assess(request().copy(receipt=
                MetadataVerifiedReceiptGate.Decision.ReadyForIndexCompare(id,"b".repeat(64)))))
    }
    @Test fun missingSemanticReadbackNeverAllowsPublish() {
        assertEquals(MetadataIndexPublicationPolicy.Decision.Blocked(
            MetadataIndexPublicationPolicy.Denial.NO_CERTIFIED_SOURCE_WRITER),
            MetadataIndexPublicationPolicy.assess(request().copy(semanticReadbackVerified=false)))
    }
    @Test fun syntheticTrialIsNotAProductionReceipt() {
        assertEquals(MetadataIndexPublicationPolicy.Decision.Blocked(
            MetadataIndexPublicationPolicy.Denial.NO_CERTIFIED_SOURCE_WRITER),
            MetadataIndexPublicationPolicy.assess(request().copy(journalPhase=MetadataRecoveryPhase.STAGE_READY)))
    }

    @Test fun structuralCasDetectsStaleVersionAndForeignOwner() {
        assertEquals(MetadataIndexPublicationPolicy.Structural.Blocked(
            MetadataIndexPublicationPolicy.Denial.STALE_INDEX),
            MetadataIndexPublicationPolicy.inspectStructure(request().copy(
                current=index.copy(version=4))))
        assertEquals(MetadataIndexPublicationPolicy.Structural.Blocked(
            MetadataIndexPublicationPolicy.Denial.STALE_INDEX),
            MetadataIndexPublicationPolicy.inspectStructure(request().copy(
                current=index.copy(sourceId="remote"))))
    }
    @Test fun structuralCasRejectsMissingAndForgedReceipts() {
        assertEquals(MetadataIndexPublicationPolicy.Structural.Blocked(
            MetadataIndexPublicationPolicy.Denial.WRONG_TRANSACTION),
            MetadataIndexPublicationPolicy.inspectStructure(request().copy(receipt=
                MetadataVerifiedReceiptGate.Decision.Refused(
                    MetadataVerifiedReceiptGate.Refusal.NOT_SOURCE_MUTATION))))
        assertEquals(MetadataIndexPublicationPolicy.Structural.Blocked(
            MetadataIndexPublicationPolicy.Denial.WRONG_DIGEST),
            MetadataIndexPublicationPolicy.inspectStructure(request().copy(receipt=
                MetadataVerifiedReceiptGate.Decision.ReadyForIndexCompare(id,"b".repeat(64)))))
    }
    @Test fun structuralCasChecksActualJournalAndSemanticProof() {
        assertEquals(MetadataIndexPublicationPolicy.Structural.Blocked(
            MetadataIndexPublicationPolicy.Denial.JOURNAL_NOT_VERIFIED),
            MetadataIndexPublicationPolicy.inspectStructure(request().copy(
                journalPhase=MetadataRecoveryPhase.STAGE_READY)))
        assertEquals(MetadataIndexPublicationPolicy.Structural.Blocked(
            MetadataIndexPublicationPolicy.Denial.READBACK_NOT_VERIFIED),
            MetadataIndexPublicationPolicy.inspectStructure(request().copy(
                semanticReadbackVerified=false)))
    }
    @Test fun structuralComparisonIsNotAnIndexWrite() {
        assertEquals(MetadataIndexPublicationPolicy.Structural.Comparable,
            MetadataIndexPublicationPolicy.inspectStructure(request()))
        assertFalse(MetadataIndexPublicationPolicy.mayPublishNow())
    }
}
