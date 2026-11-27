package org.michimusic.data.artist

import org.junit.Assert.*
import org.junit.Test

/** D25 pure policy: no Android or provider API required. */
class ArtistBulkTerminalPolicyTest {
    private val f = "a".repeat(64)
    private fun permission() = ArtistBulkPermissionRecord(
        sessionId = "session", fingerprint = f, permitId = "permit-1",
        libraryId = "library-A", sourceId = "saf-A", rootEpoch = 3,
        catalogRevision = 5, libraryRevision = 7, artistCredit = "Artist",
        mbid = "mbid", associationId = "assoc", status = ArtistBulkPermissionRecord.RESERVED_NO_IO,
        issuedAtEpochMs = 1000, expiresAtEpochMs = 301000,
        updatedAtEpochMs = 1200, revision = 1,
    )
    private fun scope() = ArtistBulkTerminalScope("library-A", "saf-A", 3, 5, 7)
    private fun may(p: ArtistBulkPermissionRecord = permission(),
        s: ArtistBulkTerminalScope = scope(), o: ArtistBulkTerminalOutcome = ArtistBulkTerminalOutcome.STALE_SCOPE,
        t: Long = 301000): Boolean = ArtistBulkTerminalPolicy.mayCloseWithoutIo(p,s,o,t)

    @Test fun noIoMayOnlyRecordCancelledOrStale() {
        assertTrue(may(o=ArtistBulkTerminalOutcome.CANCELLED, t=1300))
        assertTrue(may())
        assertFalse(may(o=ArtistBulkTerminalOutcome.NO_MATCH))
        assertFalse(may(o=ArtistBulkTerminalOutcome.VERIFIED_PROFILE))
        assertFalse(may(o=ArtistBulkTerminalOutcome.NETWORK_FAILURE))
    }
    @Test fun currentReservationCannotBeFalselyCalledStale() {
        assertFalse(may(t=1300))
        assertTrue(may(s=scope().copy(rootEpoch=4),t=1300))
        assertTrue(may(s=scope().copy(catalogRevision=6),t=1300))
        assertTrue(may(s=scope().copy(libraryRevision=8),t=1300))
        assertTrue(may(s=scope().copy(sourceId="saf-B"),t=1300))
        assertTrue(may(s=scope().copy(libraryId="library-B"),t=1300))
    }
    @Test fun staleOrReplayedPermitIsRejected() {
        assertFalse(may(p=permission().copy(status=ArtistBulkPermissionRecord.RELEASED_NO_IO)))
        assertFalse(may(p=permission().copy(status=ArtistBulkPermissionRecord.REVOKED_NOT_DISPATCHED)))
        assertFalse(may(p=permission().copy(revision=0)))
        assertFalse(may(p=permission().copy(allowedOperation="BULK_EDIT")))
        assertFalse(may(p=permission().copy(permitId="")))
        assertFalse(may(p=permission().copy(fingerprint="wrong")))
    }
    @Test fun nonMonotonicOrTooLongTimeNeverCloses() {
        assertFalse(may(t=1199))
        assertFalse(may(t=0))
        assertFalse(may(p=permission().copy(expiresAtEpochMs=400000)))
        assertFalse(may(p=permission().copy(issuedAtEpochMs=0)))
    }
    @Test fun invalidRootCannotAuthorizeResults() {
        assertFalse(may(s=scope().copy(libraryId="SCOPE_ALL")))
        assertFalse(may(s=scope().copy(sourceId="")))
        assertFalse(may(s=scope().copy(rootEpoch=-1)))
    }
    private fun terminal(): ArtistBulkTerminalReceiptRecord {
        val r = ArtistBulkTerminalReceiptRecord("session", f, "permit-1",
            ArtistBulkTerminalOutcome.STALE_SCOPE.name, ArtistBulkTerminalReceiptRecord.NO_IO_RECOVERY,
            1, "library-A", "saf-A", 3, 5, 7, 301000, "")
        return r.copy(receiptDigest=ArtistBulkTerminalPolicy.digest(r))
    }
    @Test fun digestValidatesTheCompleteImmutableReceipt() {
        val r=terminal()
        assertEquals(64,r.receiptDigest.length)
        assertTrue(ArtistBulkTerminalPolicy.authentic(r))
        assertFalse(ArtistBulkTerminalPolicy.authentic(r.copy(permitId="permit-2")))
        assertFalse(ArtistBulkTerminalPolicy.authentic(r.copy(outcome=ArtistBulkTerminalOutcome.CANCELLED.name)))
        assertFalse(ArtistBulkTerminalPolicy.authentic(r.copy(rootEpoch=4)))
        assertFalse(ArtistBulkTerminalPolicy.authentic(r.copy(completedAtEpochMs=301001)))
    }
    @Test fun noProviderOutcomeCanMasqueradeAsLocalEvidence() {
        val r=terminal()
        assertFalse(ArtistBulkTerminalPolicy.authentic(r.copy(outcome=ArtistBulkTerminalOutcome.VERIFIED_PROFILE.name,
            receiptDigest=ArtistBulkTerminalPolicy.digest(r.copy(outcome=ArtistBulkTerminalOutcome.VERIFIED_PROFILE.name)))))
        assertFalse(ArtistBulkTerminalPolicy.authentic(r.copy(evidenceKind="PROVIDER")))
    }
}
