package org.michimusic.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ArtistTrackIdentityCollisionTest {
    private fun claim(album: String, uri: String, id: String = "local_123") =
        ArtistExactOccurrenceClaim("library",album,"Alex","Alex",uri,id,"metadata-stamp")

    @Test fun sameTrackIdAcrossDifferentAlbumsIsBlocked() {
        val result = ArtistExactOccurrenceIdentityPolicy.reconcile("library",
            listOf(claim("album-a","content://a"), claim("album-b","content://b")),
            emptyList(),emptyList())
        assertEquals(ArtistExactOccurrenceIdentityResult.Reason.TRACK_ID_COLLISION,
            (result as ArtistExactOccurrenceIdentityResult.Blocked).reason)
    }
    @Test fun sameTrackIdAcrossDifferentUrisSameAlbumIsBlocked() {
        val result = ArtistExactOccurrenceIdentityPolicy.reconcile("library",
            listOf(claim("album-a","content://a"), claim("album-a","content://b")),
            emptyList(),emptyList())
        assertEquals(ArtistExactOccurrenceIdentityResult.Reason.TRACK_ID_COLLISION,
            (result as ArtistExactOccurrenceIdentityResult.Blocked).reason)
    }
    @Test fun distinctTrackIdsAreSafeForReadOnlyGrouping() {
        val result = ArtistExactOccurrenceIdentityPolicy.reconcile("library",
            listOf(claim("album-a","content://a","local_1"),
                claim("album-b","content://b","local_2")),emptyList(),emptyList())
        assertTrue(result is ArtistExactOccurrenceIdentityResult.Ready)
    }
}
