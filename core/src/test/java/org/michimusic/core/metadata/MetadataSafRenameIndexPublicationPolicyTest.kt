package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataSafRenameIndexPublicationPolicyTest {
    private val hash = "a".repeat(64)
    private val intent = MetadataSafRenameRecoveryPolicy.Intent(
        "00000000-0000-4000-8000-000000000001", "", "local_1",
        "content://provider/tree/root/document/a", "a",
        "content://provider/tree/root/document/root", "Old.flac", "New.flac", hash, 1024)
    private val row = MetadataSafRenameIndexPublicationPolicy.Indexed(
        "", "local_1", intent.beforeUri, 1024, 1024, hash, 123L, "sha256")
    private val proof = MetadataSafRenameIndexPublicationPolicy.Preconditions(
        true,true,true,true,true,true)
    private val renamed = MetadataSafRenameRecoveryPolicy.Verdict.RenamedVerified(
        "content://provider/tree/root/document/b", "b")
    private fun decide(
        row: MetadataSafRenameIndexPublicationPolicy.Indexed = this.row,
        proof: MetadataSafRenameIndexPublicationPolicy.Preconditions = this.proof,
        verdict: MetadataSafRenameRecoveryPolicy.Verdict = renamed,
    ) = MetadataSafRenameIndexPublicationPolicy.decide(intent, verdict, row, proof)

    @Test fun verifiedRenameWithStableIdentityCanProduceOneExactCas() {
        assertEquals(MetadataSafRenameIndexPublicationPolicy.Decision.Ready(
            intent.beforeUri, renamed.uri, renamed.documentId),decide())
    }
    @Test fun unverifiedHistoricIndexAllowedButNoHashInvented() {
        assertTrue(decide(row.copy(verifiedSize=0,verifiedSha256="",verifiedAt=0,verifiedAlgorithm=""))
            is MetadataSafRenameIndexPublicationPolicy.Decision.Ready)
    }
    @Test fun ownerIdReusedAcrossSourcesBlocked() {
        assertEquals(MetadataSafRenameIndexPublicationPolicy.Decision.Refused(
            MetadataSafRenameIndexPublicationPolicy.Reason.WRONG_INDEX_IDENTITY),
            decide(row.copy(sourceId="other")))
    }
    @Test fun stalePreviousHashBlockedEvenWithCorrectNewFile() {
        assertEquals(MetadataSafRenameIndexPublicationPolicy.Decision.Refused(
            MetadataSafRenameIndexPublicationPolicy.Reason.INVALID_INDEX_PROOF),
            decide(row.copy(verifiedSha256="b".repeat(64))))
    }
    @Test fun inconsistentPartialHashHistoryBlocked() {
        assertTrue(decide(row.copy(verifiedAt=0)) is
            MetadataSafRenameIndexPublicationPolicy.Decision.Refused)
    }
    @Test fun originalIntactIsNotRenamePublished() {
        assertEquals(MetadataSafRenameIndexPublicationPolicy.Decision.Refused(
            MetadataSafRenameIndexPublicationPolicy.Reason.INDEPENDENT_READBACK_UNVERIFIED),
            decide(verdict=MetadataSafRenameRecoveryPolicy.Verdict.OriginalVerified(intent.beforeUri,"a")))
    }
    @Test fun uncertainReadbackIsNotPublished() {
        assertTrue(decide(verdict=MetadataSafRenameRecoveryPolicy.Verdict.Uncertain(
            MetadataSafRenameRecoveryPolicy.Reason.NO_MATCH)) is
                MetadataSafRenameIndexPublicationPolicy.Decision.Refused)
    }
    @Test fun nonterminalJournalCannotPublish() {
        assertTrue(decide(proof=proof.copy(renameJournalTerminalVerified=false)) is
            MetadataSafRenameIndexPublicationPolicy.Decision.Refused)
    }
    @Test fun uncertifiedParentEnumerationCannotPublish() {
        assertTrue(decide(proof=proof.copy(providerListingCertified=false)) is
            MetadataSafRenameIndexPublicationPolicy.Decision.Refused)
    }
    @Test fun trackIdTiedToDocumentIdCannotPublish() {
        assertEquals(MetadataSafRenameIndexPublicationPolicy.Decision.Refused(
            MetadataSafRenameIndexPublicationPolicy.Reason.UNSTABLE_TRACK_ID),
            decide(proof=proof.copy(trackIdStableAcrossDocumentRename=false)))
    }
    @Test fun playbackAndOwnerRaceBlock() {
        assertTrue(decide(proof=proof.copy(playbackClosed=false)) is
            MetadataSafRenameIndexPublicationPolicy.Decision.Refused)
        assertTrue(decide(proof=proof.copy(ownerStillCurrent=false)) is
            MetadataSafRenameIndexPublicationPolicy.Decision.Refused)
    }
    @Test fun otherRecoveryJournalBlocks() {
        assertTrue(decide(proof=proof.copy(mainRecoveryJournalClear=false)) is
            MetadataSafRenameIndexPublicationPolicy.Decision.Refused)
    }
    @Test fun wrongSizeDoesNotCreateNewIndexIdentity() {
        assertTrue(decide(row.copy(size=2048)) is
            MetadataSafRenameIndexPublicationPolicy.Decision.Refused)
    }
    @Test fun differentProviderAuthorityRejected() {
        assertTrue(decide(verdict=MetadataSafRenameRecoveryPolicy.Verdict.RenamedVerified(
            "content://other/document/b","b")) is
            MetadataSafRenameIndexPublicationPolicy.Decision.Refused)
    }
    @Test fun sameUriUnderRenamedNameIsAllowedOnlyByIndependentVerdict() {
        assertTrue(decide(verdict=MetadataSafRenameRecoveryPolicy.Verdict.RenamedVerified(
            intent.beforeUri,"a")) is
            MetadataSafRenameIndexPublicationPolicy.Decision.Ready)
    }
}
