package org.michimusic.data.artist

import org.junit.Assert.*
import org.junit.Test

/** D27 isolated policy tests. Room tests run separately on Android/Robolectric. */
class ArtistBulkProviderLeasePolicyTest {
    private val now = 1_800_000_000_000L
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val fp = "a".repeat(64)
    private fun scope() = ArtistBulkTerminalScope("lib", "saf-A", 3, 5, 7)
    private fun permission() = ArtistBulkPermissionRecord(
        "s", fp, "permit-1", "lib", "saf-A", 3, 5, 7,
        "Artist", mbid, "assoc", status=ArtistBulkPermissionRecord.RESERVED_NO_IO,
        issuedAtEpochMs=now-1_000, expiresAtEpochMs=now+100_000,
        updatedAtEpochMs=now-100, revision=1,
    )
    private fun session() = ArtistBulkSessionRecord(
        "s", "lib", "saf-A", 3, 5, 7, now-2_000, now-2_000, receiptCount=1,
    )
    private fun receipt() = ArtistBulkReceiptRecord(
        "s", fp, "lib", "Artist", "[\"album\"]", mbid, "assoc", updatedAtEpochMs=now-2_000,
    )
    private val conditions = ArtistBulkProviderConditions(true,true,true,true)
    private fun preview() = requireNotNull(ArtistBulkProviderPreflight.inspect(permission(),scope(),conditions,now))
    private fun can(p:ArtistBulkProfileLookupPreview=preview(), permit:ArtistBulkPermissionRecord=permission(),
        s:ArtistBulkSessionRecord=session(), r:ArtistBulkReceiptRecord=receipt(),
        sc:ArtistBulkTerminalScope=scope(), c:ArtistBulkProviderConditions=conditions,
        t:Long=now, n:Int=1, active:Int=1) = ArtistBulkProviderLeasePolicy.mayStage(
        p,permit,s,r,sc,c,t,n,active)
    private fun quota(next:Long=0, updated:Long=0, revision:Long=0) =
        ArtistBulkProviderQuotaRecord(ArtistBulkProfileProvider.MUSICBRAINZ_ARTIST_BY_MBID.name,
            next,updated,revision)

    @Test fun validButStillNonExecutable() {
        assertTrue(can()); assertFalse(preview().mayDispatch)
    }
    @Test fun wrongPermissionSnapshotOrReceiptCannotStage() {
        assertFalse(can(permit=permission().copy(permitId="other")))
        assertFalse(can(permit=permission().copy(revision=2)))
        assertFalse(can(r=receipt().copy(associationId="other")))
        assertFalse(can(r=receipt().copy(mbid="different")))
        assertFalse(can(r=receipt().copy(artistCredit="Other")))
        assertFalse(can(s=session().copy(sourceId="saf-B")))
    }
    @Test fun rootLibraryAndRevisionDriftFailClosed() {
        assertFalse(can(sc=scope().copy(rootEpoch=4)))
        assertFalse(can(sc=scope().copy(libraryRevision=8)))
        assertFalse(can(sc=scope().copy(libraryId="SCOPE_ALL")))
    }
    @Test fun requireCompleteSessionAndSingleGlobalReservation() {
        assertFalse(can(n=0)); assertFalse(can(active=0)); assertFalse(can(active=2))
        assertFalse(can(s=session().copy(receiptCount=65)))
        assertFalse(can(s=session().copy(status="CANCELLED_NOT_SUBMITTED")))
    }
    @Test fun cannotSkipDeviceConstraintsOrExpiry() {
        assertFalse(can(c=conditions.copy(unmeteredNetwork=false)))
        assertFalse(can(c=conditions.copy(batteryNotLow=false)))
        assertFalse(can(c=conditions.copy(storageNotLow=false)))
        assertFalse(can(c=conditions.copy(userInitiatedReview=false)))
        assertFalse(can(t=now+100_000))
    }
    @Test fun quotaIsMonotonicAndBounded() {
        val deadline = requireNotNull(ArtistBulkProviderLeasePolicy.deadlines(now, now+100_000, quota()))
        assertEquals(now+30_000, deadline.first)
        assertEquals(now+1_100, deadline.second)
        assertNull(ArtistBulkProviderLeasePolicy.deadlines(now,now+100_000,quota(next=now+1)))
        assertNull(ArtistBulkProviderLeasePolicy.deadlines(now,now+100_000,quota(updated=now+1)))
        assertNull(ArtistBulkProviderLeasePolicy.deadlines(now,now+100_000,quota(revision=-1)))
    }
    @Test fun leaseNeverRunsPastConsentOrResurrectsAfterClockRollback() {
        assertEquals(now+1,ArtistBulkProviderLeasePolicy.deadlines(now,now+1,quota())?.first)
        assertNull(ArtistBulkProviderLeasePolicy.deadlines(now,now,quota()))
        assertNull(ArtistBulkProviderLeasePolicy.deadlines(now-1,now+1,quota(updated=now)))
        assertNull(ArtistBulkProviderLeasePolicy.deadlines(Long.MAX_VALUE-10,Long.MAX_VALUE,quota()))
    }
    @Test fun stagedLeaseMatchesEntireScopeButGrantsNoNetwork() {
        val p=preview()
        val row=ArtistBulkProviderLeaseRecord("lease","s",fp,"permit-1",p.provider.name,mbid,1,
            "lib","saf-A",3,5,7,now,now+30_000,now,
            ArtistBulkProviderLeaseRecord.STAGED_NO_IO,0)
        assertTrue(ArtistBulkProviderLeasePolicy.matches(row,p))
        assertFalse(row.mayDispatch)
        assertFalse(ArtistBulkProviderLeasePolicy.matches(row.copy(rootEpoch=4),p))
        assertFalse(ArtistBulkProviderLeasePolicy.matches(row.copy(permissionRevision=2),p))
        assertFalse(ArtistBulkProviderLeasePolicy.matches(row.copy(state=ArtistBulkProviderLeaseRecord.EXPIRED_NO_IO),p))
    }
}
