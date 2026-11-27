package org.michimusic.data.artist

import org.junit.Assert.*
import org.junit.Test

/** D26 S4-G1: dry-run policy, not a certified dispatch gate. */
class ArtistBulkProviderPreflightTest {
    private val validMbid = "b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d"
    private fun permit() = ArtistBulkPermissionRecord(
        sessionId="sess", fingerprint="a".repeat(64), permitId="permit-1",
        libraryId="library-A", sourceId="saf:source-A", rootEpoch=4,
        catalogRevision=5, libraryRevision=6, artistCredit="A-ha",
        mbid=validMbid, associationId="assoc-1",
        issuedAtEpochMs=1000L, expiresAtEpochMs=301000L,
        updatedAtEpochMs=1200L, revision=2L, status=ArtistBulkPermissionRecord.RESERVED_NO_IO,
    )
    private fun scope() = ArtistBulkTerminalScope("library-A", "saf:source-A", 4, 5, 6)
    private fun conditions() = ArtistBulkProviderConditions(true,true,true,true)
    private fun inspect(p:ArtistBulkPermissionRecord=permit(), s:ArtistBulkTerminalScope=scope(),
        c:ArtistBulkProviderConditions=conditions(), at:Long=1201) =
        ArtistBulkProviderPreflight.inspect(p,s,c,at)

    @Test fun exactReservedConsentOnlyProducesNonExecutablePreview() {
        val preview = requireNotNull(inspect())
        assertEquals(validMbid, preview.mbid)
        assertEquals("permit-1", preview.permitId)
        assertFalse(preview.mayDispatch)
    }
    @Test fun unreservedExpiredRevokedOrReplayedPermissionsAreDenied() {
        listOf(ArtistBulkPermissionRecord.CONSENT_STORED_NOT_DISPATCHED,
            ArtistBulkPermissionRecord.REVOKED_NOT_DISPATCHED,
            ArtistBulkPermissionRecord.RELEASED_NO_IO).forEach {
            assertNull(inspect(p=permit().copy(status=it)))
        }
        assertNull(inspect(at=301000))
        assertNull(inspect(p=permit().copy(revision=0)))
    }
    @Test fun noCrossRootCatalogLibraryOrSourceLeakage() {
        assertNull(inspect(s=scope().copy(rootEpoch=6))) // A->B->A
        assertNull(inspect(s=scope().copy(sourceId="saf:B")))
        assertNull(inspect(s=scope().copy(catalogRevision=6)))
        assertNull(inspect(s=scope().copy(libraryRevision=7)))
        assertNull(inspect(s=scope().copy(libraryId="another")))
        assertNull(inspect(s=scope().copy(libraryId="SCOPE_ALL")))
    }
    @Test fun localNameIsNeverProfileIdentityAuthority() {
        assertNull(inspect(p=permit().copy(mbid="A-ha")))
        assertNull(inspect(p=permit().copy(mbid="00000000-0000-0000-0000-000000000000")))
        assertEquals(validMbid,inspect(p=permit().copy(artistCredit="a-ha"))?.mbid)
        assertNull(inspect(p=permit().copy(associationId="")))
        assertNull(inspect(p=permit().copy(allowedOperation="WRITE_TAGS")))
    }
    @Test fun unsafeDeviceConditionsBlockPreviewAndNeverGrantPermission() {
        assertNull(inspect(c=conditions().copy(unmeteredNetwork=false)))
        assertNull(inspect(c=conditions().copy(batteryNotLow=false)))
        assertNull(inspect(c=conditions().copy(storageNotLow=false)))
        assertNull(inspect(c=conditions().copy(userInitiatedReview=false)))
    }
    @Test fun impossibleTimeAndMalformedFingerprintsAreDenied() {
        assertNull(inspect(at=999))
        assertNull(inspect(p=permit().copy(updatedAtEpochMs=5000)))
        assertNull(inspect(p=permit().copy(issuedAtEpochMs=0)))
        assertNull(inspect(p=permit().copy(expiresAtEpochMs=301001)))
        assertNull(inspect(p=permit().copy(fingerprint="bad")))
        assertNull(inspect(p=permit().copy(permitId="permit contains spaces")))
    }
}
