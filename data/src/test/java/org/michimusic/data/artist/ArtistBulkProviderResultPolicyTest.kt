package org.michimusic.data.artist

import org.junit.Assert.*
import org.junit.Test

/** D26 S4-G2: all results are untrusted observations until a certified Android IO bridge exists. */
class ArtistBulkProviderResultPolicyTest {
    private val scope = ArtistBulkTerminalScope("lib", "source", 0, 0, 0)
    private val mbid = "b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d"
    private val preview = ArtistBulkProfileLookupPreview(
        ArtistBulkProfileProvider.MUSICBRAINZ_ARTIST_BY_MBID, mbid,
        "session", "a".repeat(64), "permit", 1, scope, 310000L,
    )
    private fun observed(status:Int=200, contentType:String?="application/json",
        bytes:Long=100, returnedMbid:String?=mbid, at:Long=1500,
        retryAfter:String?=null) = ArtistBulkProviderObservation(preview.provider,
            status, contentType, bytes, returnedMbid, at, retryAfter)
    private fun classify(o:ArtistBulkProviderObservation=observed(),s:ArtistBulkTerminalScope=scope,
        now:Long=2000, attempt:Int=1, last:Long?=1000L) =
        ArtistBulkProviderResultPolicy.classify(preview,o,s,now,attempt,last)

    @Test fun http200CannotBecomeVerifiedTerminal() {
        val result = classify()
        assertEquals(ArtistBulkProviderObservationKind.IDENTITY_MATCH_UNTRUSTED,result.kind)
        assertFalse(result.mayPersistVerifiedProfile)
        assertFalse(result.mayModifyArtwork)
    }
    @Test fun mismatchBadTypeTruncatedOrOversizedBodyIsRejected() {
        assertEquals(ArtistBulkProviderObservationKind.INVALID_RESPONSE,
            classify(observed(returnedMbid="another-mbid")).kind)
        assertEquals(ArtistBulkProviderObservationKind.INVALID_RESPONSE,
            classify(observed(contentType="text/html")).kind)
        assertEquals(ArtistBulkProviderObservationKind.INVALID_RESPONSE,
            classify(observed(bytes=0)).kind)
        assertEquals(ArtistBulkProviderObservationKind.INVALID_RESPONSE,
            classify(observed(bytes=ArtistBulkProviderResultPolicy.MAX_BODY_BYTES+1)).kind)
        assertEquals(ArtistBulkProviderObservationKind.INVALID_RESPONSE,
            classify(observed(bytes=-1)).kind)
    }
    @Test fun notFoundDoesNotFalsifyProfileOrArtistIdentity() {
        val result=classify(observed(status=404,bytes=0,returnedMbid=null))
        assertEquals(ArtistBulkProviderObservationKind.NOT_FOUND_UNTRUSTED,result.kind)
        assertFalse(result.mayPersistVerifiedProfile)
    }
    @Test fun rateLimitedAndServerFailuresAreDeferredWithFiniteBudget() {
        val limited=classify(observed(status=429, bytes=0,retryAfter="60"))
        assertEquals(ArtistBulkProviderObservationKind.RATE_LIMITED,limited.kind)
        assertEquals(62000L,limited.retryAtEpochMs)
        val failed=classify(observed(status=503,bytes=0))
        assertEquals(ArtistBulkProviderObservationKind.RETRYABLE_FAILURE,failed.kind)
        assertEquals(32000L,failed.retryAtEpochMs)
        assertNull(classify(observed(status=503,bytes=0),attempt=3).retryAtEpochMs)
    }
    @Test fun incorrectRootOrExpiredPermitMakesObservationStale() {
        assertEquals(ArtistBulkProviderObservationKind.STALE_SCOPE,
            classify(s=scope.copy(rootEpoch=1)).kind)
        assertEquals(ArtistBulkProviderObservationKind.STALE_SCOPE,
            classify(now=310000).kind)
    }
    @Test fun redirectsAndPermissionDenialsAreNotSuccessfulLookup() {
        assertEquals(ArtistBulkProviderObservationKind.INVALID_RESPONSE,
            classify(observed(status=301)).kind)
        assertEquals(ArtistBulkProviderObservationKind.ACCESS_DENIED,
            classify(observed(status=403,bytes=0)).kind)
    }
    @Test fun invalidRetryAfterRequiresManualFollowup() {
        assertNull(classify(observed(status=429, bytes=0,retryAfter="Sun, 06 Nov 1994 08:49:37 GMT")).retryAtEpochMs)
        assertNull(classify(observed(status=429, bytes=0,retryAfter="99999999")).retryAtEpochMs)
    }
    @Test fun untrustedObservationCannotReuseWrongProviderOrFutureClock() {
        assertEquals(ArtistBulkProviderObservationKind.INVALID_RESPONSE,
            classify(observed().copy(observedAtEpochMs=5000)).kind)
        assertEquals(ArtistBulkProviderObservationKind.INVALID_RESPONSE,
            classify(observed(),attempt=0).kind)
    }
}
