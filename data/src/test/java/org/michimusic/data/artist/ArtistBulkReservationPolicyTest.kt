package org.michimusic.data.artist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D24 - deterministic policy; unit tests do not perform I/O. */
class ArtistBulkReservationPolicyTest {
    private val t = 1_800_000_000_000L
    private val fp = "a".repeat(64)
    private fun session() = ArtistBulkSessionRecord(
        sessionId="s", libraryId="lib", sourceId="source", rootEpoch=3,
        catalogRevision=5, libraryRevision=7, createdAtEpochMs=t,
        updatedAtEpochMs=t, receiptCount=1,
    )
    private fun receipt() = ArtistBulkReceiptRecord(
        sessionId="s", fingerprint=fp, libraryId="lib", artistCredit="Artist A",
        albumIdsJson="[\"album\"]", mbid="artist-mbid", associationId="association",
        updatedAtEpochMs=t,
    )
    private fun permission() = ArtistBulkPermissionRecord(
        sessionId="s", fingerprint=fp, permitId="permit-uuid", libraryId="lib",
        sourceId="source", rootEpoch=3, catalogRevision=5, libraryRevision=7,
        artistCredit="Artist A", mbid="artist-mbid", associationId="association",
        issuedAtEpochMs=t+100, expiresAtEpochMs=t+300_100, updatedAtEpochMs=t+100,
    )
    private fun allowed(p:ArtistBulkPermissionRecord=permission(),
        s:ArtistBulkSessionRecord=session(), r:ArtistBulkReceiptRecord=receipt(),
        pr:Long=p.revision, sr:Long=s.revision, now:Long=t+101
    ) = ArtistBulkReservationPolicy.mayReserve(p,s,r,pr,sr,now)

    @Test fun validExactReceiptPasses() = assertTrue(allowed())
    @Test fun consentNotIssuedYetFails() = assertFalse(allowed(now=t+99))
    @Test fun exactExpiryBoundaryFails() = assertFalse(allowed(now=t+300_100))
    @Test fun stalePermissionRevisionFails() = assertFalse(allowed(pr=1))
    @Test fun staleSessionRevisionFails() = assertFalse(allowed(sr=1))
    @Test fun cancelledSessionFails() = assertFalse(allowed(s=session().copy(status=ArtistBulkSessionRecord.CANCELLED_NOT_SUBMITTED)))
    @Test fun wrongReceiptMbidFails() = assertFalse(allowed(r=receipt().copy(mbid="other")))
    @Test fun wrongReceiptIdentityFails() = assertFalse(allowed(r=receipt().copy(associationId="other")))
    @Test fun wrongReceiptLibraryFails() = assertFalse(allowed(r=receipt().copy(libraryId="other")))
    @Test fun wrongReceiptFingerpintFails() = assertFalse(allowed(r=receipt().copy(fingerprint="b".repeat(64))))
    @Test fun rootEpochMismatchFails() = assertFalse(allowed(p=permission().copy(rootEpoch=4)))
    @Test fun sourceMismatchFails() = assertFalse(allowed(p=permission().copy(sourceId="new-root")))
    @Test fun catalogRescanFails() = assertFalse(allowed(p=permission().copy(catalogRevision=6)))
    @Test fun libraryRevisionMismatchFails() = assertFalse(allowed(p=permission().copy(libraryRevision=8)))
    @Test fun reservedCannotReserveAgain() = assertFalse(allowed(p=permission().copy(status=ArtistBulkPermissionRecord.RESERVED_NO_IO)))
    @Test fun releasedCannotReserveAgain() = assertFalse(allowed(p=permission().copy(status=ArtistBulkPermissionRecord.RELEASED_NO_IO)))
    @Test fun revokedCannotReserveAgain() = assertFalse(allowed(p=permission().copy(status=ArtistBulkPermissionRecord.REVOKED_NOT_DISPATCHED)))
    @Test fun operationScopeCannotEscalate() = assertFalse(allowed(p=permission().copy(allowedOperation="PHOTO_WRITE")))
    @Test fun lifetimeTooLongFails() = assertFalse(allowed(p=permission().copy(expiresAtEpochMs=t+700_000)))
    @Test fun staleSessionAgeFails() = assertFalse(allowed(now=t+300_001))
    @Test fun allLibrariesScopeFails() = assertFalse(allowed(p=permission().copy(libraryId="SCOPE_ALL"),s=session().copy(libraryId="SCOPE_ALL"),r=receipt().copy(libraryId="SCOPE_ALL")))
    @Test fun invalidFingerprintFails() = assertFalse(allowed(p=permission().copy(fingerprint="A".repeat(64)),r=receipt().copy(fingerprint="A".repeat(64))))
    @Test fun noReceiptMbidFails() = assertFalse(allowed(p=permission().copy(mbid=""),r=receipt().copy(mbid="")))
    @Test fun noArtistCreditFails() = assertFalse(allowed(p=permission().copy(artistCredit=""),r=receipt().copy(artistCredit="")))
    @Test fun nonZeroReceiptRevisionFails() = assertFalse(allowed(r=receipt().copy(revision=1)))
}
