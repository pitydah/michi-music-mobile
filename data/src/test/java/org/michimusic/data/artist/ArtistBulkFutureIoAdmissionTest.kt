package org.michimusic.data.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ArtistBulkFutureIoAdmissionTest {
    private val at=1_800_000_000_000L
    private val root=ArtistBulkTerminalScope("lib","saf-A",5,9,11)
    private val validMbid="11111111-1111-4111-8111-111111111111"
    private fun consent()=ArtistBulkIndependentIoConsent("io:review-123",true,"lib","saf-A",5,9,11,
        validMbid,at-1000,at+20000)
    private fun lease()=ArtistBulkIndependentIoLease("io:review-123","io-lease:123",5,at+10000,true)
    private fun check(c:ArtistBulkIndependentIoConsent?=consent(),l:ArtistBulkIndependentIoLease?=lease(),
        scope:ArtistBulkTerminalScope=root,now:Long=at,enabled:Boolean=true)=
        ArtistBulkFutureIoAdmission.inspect(enabled,c,l,scope,now)
    @Test fun featureIsDisabledByDefault() {
        assertFalse(ArtistBulkFutureIoAdmission.ENABLED_BY_DEFAULT)
        assertEquals(ArtistBulkFutureIoReason.DISABLED,check(enabled=false))
    }
    @Test fun futureIndependentIoIsStillPreviewOnly() =
        assertEquals(ArtistBulkFutureIoReason.ELIGIBLE_BUT_NOT_DISPATCHED,check())
    @Test fun consentCannotBeForgedFromNoIoId() =
        assertEquals(ArtistBulkFutureIoReason.UNVERIFIED_USER_APPROVAL,
            check(c=consent().copy(ioConsentId="permit-1")))
    @Test fun noClickMeansNoExternalPermission() =
        assertEquals(ArtistBulkFutureIoReason.UNVERIFIED_USER_APPROVAL,
            check(c=consent().copy(explicitUserApproval=false)))
    @Test fun changeRootEpochRefuses() =
        assertEquals(ArtistBulkFutureIoReason.INVALID_SCOPE,check(scope=root.copy(rootEpoch=6)))
    @Test fun allScopeRefuses() =
        assertEquals(ArtistBulkFutureIoReason.INVALID_SCOPE,check(c=consent().copy(libraryId="SCOPE_ALL")))
    @Test fun expiredConsentRefuses() =
        assertEquals(ArtistBulkFutureIoReason.EXPIRED,check(now=at+40000))
    @Test fun reusedOrMissingLeaseRefuses() =
        assertEquals(ArtistBulkFutureIoReason.INVALID_LEASE,check(l=lease().copy(oneShot=false)))
    @Test fun malformedArtistIdRefuses() =
        assertEquals(ArtistBulkFutureIoReason.INVALID_IDENTITY,check(c=consent().copy(artistMbid="not-a-mbid")))
}
