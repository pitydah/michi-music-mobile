package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistBulkSessionDraftTest {
    private val stamp = ArtistBulkReadSnapshot("lib", "root", 3L, 6L, 9L)
    private fun row(i: Int, status: ArtistBulkDisposition = ArtistBulkDisposition.ELIGIBLE_FOR_EXPLICIT_REVIEW) =
        ArtistBulkPreviewRow(
            credit = "Artist-$i", albumIds = listOf("album-$i"), trackCount = 1,
            disposition = status, mbid = "mbid-$i", associationId = "association-$i",
            diagnosticFingerprint = "%064x".format(i + 1),
        )
    private fun proposal(vararg rows: ArtistBulkPreviewRow) = ArtistBulkPreview(stamp, rows.toList())
    private fun prepare(preview: ArtistBulkPreview, selections: Set<String>, acknowledged: Boolean = true,
        max: Int = 64) = prepareArtistBulkSession(preview, selections, acknowledged,
            receiptId = "session-123", createdAtEpochMs = 1732100000000L, maxArtists = max)

    @Test fun noImplicitAllSelection() {
        assertEquals(ArtistBulkDraftResult.Rejected(ArtistBulkDraftRejection.NO_SELECTION),
            prepare(proposal(row(0), row(1)), emptySet()))
    }
    @Test fun oneExplicitArtistCannotSelectNeighbor() {
        val result = prepare(proposal(row(0), row(1)), setOf(row(1).diagnosticFingerprint!!))
        val session = (result as ArtistBulkDraftResult.Prepared).session
        assertEquals(listOf("Artist-1"), session.receipts.map { it.artistCredit })
        assertEquals(stamp, session.snapshot)
        assertFalse(session.mayExecute)
    }
    @Test fun requiresIndependentAcknowledgment() {
        assertEquals(ArtistBulkDraftRejection.EXPLICIT_ACKNOWLEDGMENT_REQUIRED,
            (prepare(proposal(row(0)), setOf(row(0).diagnosticFingerprint!!), false)
                as ArtistBulkDraftResult.Rejected).reason)
    }
    @Test fun rejectsUnknownFingerprint() {
        assertEquals(ArtistBulkDraftRejection.UNKNOWN_OR_DUPLICATE_SELECTION,
            (prepare(proposal(row(0)), setOf(row(1).diagnosticFingerprint!!))
                as ArtistBulkDraftResult.Rejected).reason)
    }
    @Test fun rejectsConflictedArtist() {
        assertEquals(null, artistBulkSelectableFingerprint(row(0, ArtistBulkDisposition.IDENTITY_CONFLICT)))
        assertTrue(prepare(proposal(row(0, ArtistBulkDisposition.IDENTITY_CONFLICT)),
            setOf(row(0).diagnosticFingerprint!!)) is ArtistBulkDraftResult.Rejected)
    }
    @Test fun forbidsEvenUnselectedFingerprintCollision() {
        val duplicate = row(1).copy(diagnosticFingerprint = row(0).diagnosticFingerprint)
        assertEquals(ArtistBulkDraftRejection.UNKNOWN_OR_DUPLICATE_SELECTION,
            (prepare(proposal(row(0), duplicate), setOf(row(0).diagnosticFingerprint!!))
                as ArtistBulkDraftResult.Rejected).reason)
    }
    @Test fun refusesBlockedPreview() {
        val blocked = ArtistBulkPreview(stamp, listOf(row(0)), ArtistBulkPreviewBlock.SCOPE_CHANGED)
        assertEquals(ArtistBulkDraftRejection.INVALID_PREVIEW,
            (prepare(blocked, setOf(row(0).diagnosticFingerprint!!)) as ArtistBulkDraftResult.Rejected).reason)
    }
    @Test fun budgetIsFailClosed() {
        val rows = List(65, ::row)
        assertEquals(ArtistBulkDraftRejection.TOO_MANY_ARTISTS,
            (prepare(ArtistBulkPreview(stamp, rows), rows.mapNotNull { it.diagnosticFingerprint }.toSet())
                as ArtistBulkDraftResult.Rejected).reason)
    }
    @Test fun invalidScopeCannotPrepare() {
        val bad = ArtistBulkPreview(stamp.copy(libraryId = "SCOPE_ALL"), listOf(row(0)))
        assertTrue(prepare(bad, setOf(row(0).diagnosticFingerprint!!)) is ArtistBulkDraftResult.Rejected)
    }
    @Test fun cancellationKeepsExplicitReceiptsWithoutScheduling() {
        val session = (prepare(proposal(row(0), row(1)),
            setOf(row(0).diagnosticFingerprint!!, row(1).diagnosticFingerprint!!))
            as ArtistBulkDraftResult.Prepared).session
        assertEquals(2, session.receipts.size)
        val cancelled = session.cancel()
        assertTrue(cancelled.cancelled)
        assertTrue(cancelled.receipts.all { it.status == ArtistBulkPlannedStatus.CANCELLED_NOT_SUBMITTED })
        assertFalse(cancelled.mayExecute)
        assertEquals(ArtistBulkPlannedStatus.PREPARED_NOT_SUBMITTED, session.receipts.first().status)
    }
    @Test fun missingMbidAndAssociationAreRejected() {
        assertEquals(null, artistBulkSelectableFingerprint(row(0).copy(mbid = null)))
        assertEquals(null, artistBulkSelectableFingerprint(row(0).copy(associationId = "")))
    }
    @Test fun malformedFingerprintsDoNotCreateConsent() {
        assertEquals(null, artistBulkSelectableFingerprint(row(0).copy(diagnosticFingerprint = "foo")))
        assertEquals(null, artistBulkSelectableFingerprint(row(0).copy(diagnosticFingerprint = "Z".repeat(64))))
    }
    @Test fun invalidReceiptIdAndClockRejectDraft() {
        val preview = proposal(row(0))
        val selected = setOf(row(0).diagnosticFingerprint!!)
        assertEquals(ArtistBulkDraftRejection.INVALID_RECEIPT_ID,
            (prepareArtistBulkSession(preview, selected, true, "bad receipt", 123L) as ArtistBulkDraftResult.Rejected).reason)
        assertEquals(ArtistBulkDraftRejection.INVALID_TIMESTAMP,
            (prepareArtistBulkSession(preview, selected, true, "valid", 0L) as ArtistBulkDraftResult.Rejected).reason)
    }
    @Test fun distinctCaseVariantsRemainIndependent() {
        val first = row(0).copy(credit = "A-ha")
        val second = row(1).copy(credit = "a-ha")
        val session = (prepare(proposal(first, second), setOf(second.diagnosticFingerprint!!))
            as ArtistBulkDraftResult.Prepared).session
        assertEquals(listOf("a-ha"), session.receipts.map { it.artistCredit })
    }

    @Test fun activeDraftRestoresOnlyAgainstSameFreshSnapshotAndExactRoomIdentity() {
        val preview = proposal(row(0), row(1))
        val draft = (prepare(preview, setOf(row(0).diagnosticFingerprint!!)) as ArtistBulkDraftResult.Prepared).session
        assertTrue(artistBulkCanRestoreDraft(draft, preview, stamp))
        assertFalse(artistBulkCanRestoreDraft(draft, preview, stamp.copy(rootEpoch = 8)))
        assertFalse(artistBulkCanRestoreDraft(draft, preview.copy(blocked = ArtistBulkPreviewBlock.SCOPE_CHANGED), stamp))
        assertFalse(artistBulkCanRestoreDraft(draft.cancel(), preview, stamp))
        assertFalse(artistBulkCanRestoreDraft(draft, proposal(row(0).copy(mbid = "other"), row(1)), stamp))
        assertFalse(artistBulkCanRestoreDraft(draft, proposal(row(0).copy(associationId = "other"), row(1)), stamp))
        assertFalse(artistBulkCanRestoreDraft(draft, proposal(row(0).copy(albumIds = listOf("foreign")), row(1)), stamp))
        assertFalse(artistBulkCanRestoreDraft(draft, proposal(row(0), row(0)), stamp))
        assertFalse(artistBulkCanRestoreDraft(draft, proposal(row(0, ArtistBulkDisposition.IDENTITY_CONFLICT)), stamp))
        assertFalse(artistBulkCanRestoreDraft(draft.copy(receipts = draft.receipts + draft.receipts), preview, stamp))
    }
}
