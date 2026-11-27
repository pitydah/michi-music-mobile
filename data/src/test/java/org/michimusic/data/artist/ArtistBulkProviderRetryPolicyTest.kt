package org.michimusic.data.artist

import org.junit.Assert.*
import org.junit.Test

/** D26 S4-G3: no automatic retry without a finite and valid next eligible time. */
class ArtistBulkProviderRetryPolicyTest {
    @Test fun minimumBackoffIsLongerThanMusicBrainzThrottle() {
        assertEquals(31000L, ArtistBulkProviderRetryPolicy.nextRetryAt(1000,1,900,null))
        assertEquals(61000L, ArtistBulkProviderRetryPolicy.nextRetryAt(1000,2,900,null))
    }
    @Test fun validRetryAfterRespectLargerBound() {
        assertEquals(121000L, ArtistBulkProviderRetryPolicy.nextRetryAt(1000,1,900,"120"))
        assertEquals(31000L, ArtistBulkProviderRetryPolicy.nextRetryAt(1000,1,900,"1"))
    }
    @Test fun exhaustedRetryNeverLoopsForever() {
        assertNull(ArtistBulkProviderRetryPolicy.nextRetryAt(1000,3,900,null))
        assertNull(ArtistBulkProviderRetryPolicy.nextRetryAt(1000,0,900,null))
        assertNull(ArtistBulkProviderRetryPolicy.nextRetryAt(1000,-1,900,null))
    }
    @Test fun invalidRetryAfterBlocksAutomaticRetry() {
        assertNull(ArtistBulkProviderRetryPolicy.nextRetryAt(1000,1,900,"-1"))
        assertNull(ArtistBulkProviderRetryPolicy.nextRetryAt(1000,1,900,"86400"))
        assertNull(ArtistBulkProviderRetryPolicy.nextRetryAt(1000,1,900,"nope"))
    }
    @Test fun nonmonotonicTimeAndOverflowFailClosed() {
        assertNull(ArtistBulkProviderRetryPolicy.nextRetryAt(900,1,1000,null))
        assertNull(ArtistBulkProviderRetryPolicy.nextRetryAt(Long.MAX_VALUE,1,1,null))
        assertNull(ArtistBulkProviderRetryPolicy.nextRetryAt(0,1,1,null))
    }
    @Test fun retryAfterIsOnlySecondsNotHTTPDate() {
        assertEquals(1000L,ArtistBulkProviderRetryPolicy.retryAfterDelayMs("1"))
        assertNull(ArtistBulkProviderRetryPolicy.retryAfterDelayMs("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertNull(ArtistBulkProviderRetryPolicy.retryAfterDelayMs(""))
    }
}
