package org.michimusic.mobile.artist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.data.artist.ArtistBulkPermissionRecord

/** D23: pure-data, no Android, no HTTP or worker involved. */
class ArtistBulkPermissionPolicyTest {
    private fun record() = ArtistBulkPermissionRecord(
        sessionId="s", fingerprint="a".repeat(64), permitId="p", libraryId="lib",
        sourceId="root", rootEpoch=1L, catalogRevision=2L, libraryRevision=3L,
        artistCredit="a-ha", mbid="mbid", associationId="assoc",
        issuedAtEpochMs=100L, expiresAtEpochMs=100L+300_000L,
        updatedAtEpochMs=100L,
    )
    @Test fun initiallyCurrent() = assertTrue(record().isCurrent(101L))
    @Test fun expiresAtExactBoundary() = assertFalse(record().isCurrent(300_100L))
    @Test fun cannotBeCurrentBeforeIssue() = assertFalse(record().isCurrent(99L))
    @Test fun revokedConsentNeverCurrent() = assertFalse(record().copy(
        status=ArtistBulkPermissionRecord.REVOKED_NOT_DISPATCHED).isCurrent(102L))
    @Test fun expiredConsentNotEquivalentToRevoked() {
        assertTrue(record().status != ArtistBulkPermissionRecord.REVOKED_NOT_DISPATCHED)
        assertFalse(record().isCurrent(500_000L))
    }
}
