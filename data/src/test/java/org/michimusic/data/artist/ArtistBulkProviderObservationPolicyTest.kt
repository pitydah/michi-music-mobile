package org.michimusic.data.artist

import org.junit.Assert.*
import org.junit.Test

/** D28 JVM policy tests, independent from HTTP, Room and Android. */
class ArtistBulkProviderObservationPolicyTest {
    private val now=1_800_000_000_000L
    private val mbid="05072650-4b05-4e8b-83d0-dba2576e306a"
    private val fingerprint="a".repeat(64)
    private fun scope()=ArtistBulkTerminalScope("lib","saf-A",3,5,7)
    private fun permit()=ArtistBulkPermissionRecord("s",fingerprint,"permit-1","lib","saf-A",3,5,7,
        "Artist",mbid,"assoc",status=ArtistBulkPermissionRecord.RESERVED_NO_IO,
        issuedAtEpochMs=now-1_000,expiresAtEpochMs=now+100_000,
        updatedAtEpochMs=now-100,revision=1)
    private fun preview()=requireNotNull(ArtistBulkProviderPreflight.inspect(permit(),scope(),
        ArtistBulkProviderConditions(true,true,true,true),now))
    private fun lease()=ArtistBulkProviderLeaseRecord("lease","s",fingerprint,"permit-1",
        ArtistBulkProfileProvider.MUSICBRAINZ_ARTIST_BY_MBID.name,mbid,1,
        "lib","saf-A",3,5,7,now-100,now+29_900,now-100,
        ArtistBulkProviderLeaseRecord.STAGED_NO_IO,0)
    private fun request()=requireNotNull(ArtistBulkProviderResponseBoundary.describe(preview(),lease(),now))
    private fun session()=ArtistBulkSessionRecord("s","lib","saf-A",3,5,7,
        now-2000,now-2000,receiptCount=1)
    private fun receipt()=ArtistBulkReceiptRecord("s",fingerprint,"lib","Artist",
        "[\"album\"]",mbid,"assoc",updatedAtEpochMs=now-2000)
    private fun raw(status:Int=200,body:ByteArray="{\"id\":\"$mbid\",\"name\":\"Artist\"}".toByteArray(),
        parsed:String?=mbid,ct:String?="application/json",retry:String?=null)=
        ArtistBulkProviderRawObservation(status,body,ct,parsed,now,retry)
    private fun review(raw:ArtistBulkProviderRawObservation=raw())=
        requireNotNull(ArtistBulkProviderResponseBoundary.review(preview(),lease(),request(),
            raw,scope(),now,1,null))
    private fun can(note:ArtistBulkProviderBoundedObservation=review(),
        l:ArtistBulkProviderLeaseRecord=lease(),p:ArtistBulkPermissionRecord=permit(),
        s:ArtistBulkSessionRecord=session(),r:ArtistBulkReceiptRecord=receipt(),
        sc:ArtistBulkTerminalScope=scope(),t:Long=now,live:Int=1,active:Int=1,
        terminal:Int=0)=ArtistBulkProviderObservationPolicy.mayRecord(l,preview(),p,s,r,sc,
            note,t,live,active,terminal)

    @Test fun requestContainsOnlyVerifiedMbidAndCannotDispatch() {
        val r=request()
        assertEquals("GET",r.method)
        assertEquals("/ws/2/artist/$mbid?fmt=json",r.pathAndQuery)
        assertFalse(r.mayDispatch); assertFalse(preview().mayDispatch)
        assertFalse(r.pathAndQuery.contains("lib"));assertFalse(r.pathAndQuery.contains("Artist"))
    }
    @Test fun staleOrAbandonedLeaseCannotGenerateRequest() {
        assertNull(ArtistBulkProviderResponseBoundary.describe(preview(),lease().copy(rootEpoch=4),now))
        assertNull(ArtistBulkProviderResponseBoundary.describe(preview(),lease().copy(
            state=ArtistBulkProviderLeaseRecord.ABANDONED_NO_IO),now))
        assertNull(ArtistBulkProviderResponseBoundary.describe(preview(),lease(),now+29_900))
    }
    @Test fun reviewBoundsBodyAndDoesNotRetainIt() {
        val n=review();assertEquals(ArtistBulkProviderObservationKind.IDENTITY_MATCH_UNTRUSTED,n.assessment.kind)
        assertEquals(64,n.bodySha256?.length);assertFalse(n.mayDispatch)
        assertFalse(n.mayPersistVerifiedProfile)
        assertNull(ArtistBulkProviderResponseBoundary.review(preview(),lease(),request(),
            raw(body=ByteArray(256*1024+1)),scope(),now,1,null))
    }
    @Test fun mismatchedAndMissingArtistMbidCannotBeVerified() {
        assertEquals(ArtistBulkProviderObservationKind.INVALID_RESPONSE,
            review(raw(parsed="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")).assessment.kind)
        assertEquals(ArtistBulkProviderObservationKind.INVALID_RESPONSE,
            review(raw(body="{\"id\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\"}".toByteArray(),
                parsed=mbid)).assessment.kind)
        assertEquals(ArtistBulkProviderObservationKind.IDENTITY_MATCH_UNTRUSTED,
            review(raw(parsed=null)).assessment.kind)
    }
    @Test fun notFoundIsNotEvidenceOfVerifiedIdentity() {
        val n=review(raw(status=404,body=byteArrayOf(),parsed=null,ct=null))
        assertEquals(ArtistBulkProviderObservationKind.NOT_FOUND_UNTRUSTED,n.assessment.kind)
        assertNull(n.bodySha256)
    }
    @Test fun rateLimitIsAdvisoryWithBoundedRetry() {
        val n=review(raw(status=429,body=byteArrayOf(),parsed=null,ct=null,retry="2"))
        assertEquals(ArtistBulkProviderObservationKind.RATE_LIMITED,n.assessment.kind)
        assertNotNull(n.assessment.retryAtEpochMs)
        assertFalse(n.mayDispatch)
    }
    @Test fun invalidHeadersAndUnreasonableHttpStatusRejected() {
        assertNull(ArtistBulkProviderResponseBoundary.review(preview(),lease(),request(),
            raw(ct="application/json\nInjected: yes"),scope(),now,1,null))
        assertNull(ArtistBulkProviderResponseBoundary.review(preview(),lease(),request(),
            raw(retry="5\r\nX-Header"),scope(),now,1,null))
        assertNull(ArtistBulkProviderResponseBoundary.review(preview(),lease(),request(),
            raw(status=99),scope(),now,1,null))
    }
    @Test fun wrongReceiptScopeAndPermissionAreBlocked() {
        assertTrue(can())
        assertFalse(can(p=permit().copy(permitId="different")))
        assertFalse(can(s=session().copy(sourceId="saf-B")))
        assertFalse(can(r=receipt().copy(associationId="other")))
        assertFalse(can(sc=scope().copy(rootEpoch=4)))
    }
    @Test fun uniqueActiveLeaseAndNoTerminalAreRequired() {
        assertFalse(can(live=0));assertFalse(can(live=2));assertFalse(can(active=0))
        assertFalse(can(terminal=1));assertFalse(can(t=now+29_900))
        assertFalse(can(l=lease().copy(state=ArtistBulkProviderLeaseRecord.EXPIRED_NO_IO)))
    }
    @Test fun forgedClassificationOrUnboundHashIsRejected() {
        val n=review()
        assertFalse(can(note=n.copy(assessment=ArtistBulkProviderAssessment(
            ArtistBulkProviderObservationKind.NOT_FOUND_UNTRUSTED))))
        assertFalse(can(note=n.copy(bodySha256="0")))
        assertFalse(can(note=n.copy(classifiedAtEpochMs=now+1)))
        assertFalse(can(note=n.copy(bodySha256=null)))
    }
    @Test fun replayEquivalenceRejectsModifiedObservation() {
        val n=review()
        val r=ArtistBulkProviderObservationRecord("lease","s",fingerprint,"permit-1",
            ArtistBulkProfileProvider.MUSICBRAINZ_ARTIST_BY_MBID.name,mbid,
            n.observation.observedAtEpochMs,now,n.observation.httpStatus,
            n.observation.responseByteCount,n.bodySha256,n.observation.contentType,
            n.assessment.kind.name,n.assessment.retryAtEpochMs)
        assertTrue(ArtistBulkProviderObservationPolicy.sameNote(r,n,lease()))
        assertFalse(ArtistBulkProviderObservationPolicy.sameNote(r.copy(httpStatusUnverified=404),n,lease()))
        assertFalse(ArtistBulkProviderObservationPolicy.sameNote(r.copy(responseSha256Unverified="0".repeat(64)),n,lease()))
        assertFalse(r.mayDispatch);assertFalse(r.mayPublishIdentity);assertFalse(r.mayPublishArtwork)
    }
    @Test fun aResponseDoesNotRetroactivelyProveNetworkDispatch() {
        val n=review()
        assertTrue(can(note=n))
        assertFalse(n.assessment.mayPersistVerifiedProfile)
        assertFalse(n.assessment.mayModifyArtwork)
    }
}
