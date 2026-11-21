package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataSafRenameIndexReplayPolicyTest {
    private val intent = MetadataSafRenameRecoveryPolicy.Intent(
        "00000000-0000-4000-8000-000000000001", "owner", "track-1",
        "content://provider/document/old", "old", "content://provider/document/root",
        "Old.flac", "New.flac", "a".repeat(64), 1000L)
    private val row = MetadataSafRenameIndexPublicationPolicy.Indexed(
        "owner", "track-1", intent.beforeUri, 1000L, 1000L,
        "a".repeat(64), 100L, "sha256")
    private val renamed = MetadataSafRenameRecoveryPolicy.Verdict.RenamedVerified(
        "content://provider/document/new", "new")
    private fun decide(
        indexed: MetadataSafRenameIndexPublicationPolicy.Indexed? = row,
        readback: MetadataSafRenameRecoveryPolicy.Verdict = renamed,
        journal: Boolean = true, recovery: Boolean = true,
        owner: Boolean = true, playbackClosed: Boolean = true,
    ) = MetadataSafRenameIndexReplayPolicy.classify(
        intent, readback, indexed, journal, recovery, owner, playbackClosed)

    @Test fun oldUriIsEligibleForSeparateFullCas() {
        assertEquals(MetadataSafRenameIndexReplayPolicy.Decision.EvaluateFirstCas, decide())
    }
    @Test fun newUriIsNotProofOfThisCas() {
        assertEquals(MetadataSafRenameIndexReplayPolicy.Decision.ManualPostCasReconciliation,
            decide(indexed=row.copy(filepath=renamed.uri)))
    }
    @Test fun sameUriProviderRunsFirstCasRatherThanFalseReplay() {
        assertEquals(MetadataSafRenameIndexReplayPolicy.Decision.EvaluateFirstCas,
            decide(readback=MetadataSafRenameRecoveryPolicy.Verdict.RenamedVerified(
                intent.beforeUri, intent.beforeDocumentId)))
    }
    @Test fun changedThirdUriIsRejected() {
        assertEquals(MetadataSafRenameIndexReplayPolicy.Decision.Refused(
            MetadataSafRenameIndexReplayPolicy.Reason.INDEX_CHANGED),
            decide(indexed=row.copy(filepath="content://provider/document/other")))
    }
    @Test fun foreignOwnerIsRejected() {
        assertEquals(MetadataSafRenameIndexReplayPolicy.Decision.Refused(
            MetadataSafRenameIndexReplayPolicy.Reason.INDEX_OWNER_MISMATCH),
            decide(indexed=row.copy(sourceId="foreign")))
    }
    @Test fun missingRowIsRejected() {
        assertEquals(MetadataSafRenameIndexReplayPolicy.Decision.Refused(
            MetadataSafRenameIndexReplayPolicy.Reason.INDEX_MISSING), decide(indexed=null))
    }
    @Test fun missingTerminalJournalIsRejected() {
        assertEquals(MetadataSafRenameIndexReplayPolicy.Decision.Refused(
            MetadataSafRenameIndexReplayPolicy.Reason.JOURNAL_NOT_TERMINAL),decide(journal=false))
    }
    @Test fun competingRecoveryIsRejected() {
        assertEquals(MetadataSafRenameIndexReplayPolicy.Decision.Refused(
            MetadataSafRenameIndexReplayPolicy.Reason.MAIN_RECOVERY_PENDING),decide(recovery=false))
    }
    @Test fun ownerOrPlaybackChangeIsRejected() {
        assertEquals(MetadataSafRenameIndexReplayPolicy.Decision.Refused(
            MetadataSafRenameIndexReplayPolicy.Reason.OWNER_CHANGED),decide(owner=false))
        assertEquals(MetadataSafRenameIndexReplayPolicy.Decision.Refused(
            MetadataSafRenameIndexReplayPolicy.Reason.PLAYBACK_ACTIVE),decide(playbackClosed=false))
    }
    @Test fun originalReadbackNeverMeansRenamed() {
        assertEquals(MetadataSafRenameIndexReplayPolicy.Decision.Refused(
            MetadataSafRenameIndexReplayPolicy.Reason.INDEPENDENT_RENAME_NOT_VERIFIED),
            decide(readback=MetadataSafRenameRecoveryPolicy.Verdict.OriginalVerified(intent.beforeUri,"old")))
    }
}
