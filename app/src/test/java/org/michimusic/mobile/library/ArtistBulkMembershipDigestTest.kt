package org.michimusic.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.LocationAvailability
import org.michimusic.core.models.ResolvedTrack
import org.michimusic.core.models.TrackIdentity
import org.michimusic.core.models.TrackLocation
import org.michimusic.core.models.TrackLocationType

class ArtistBulkMembershipDigestTest {
    private fun track(id: String, artist: String = "A-ha", uri: String = "content://media/$id") =
        ResolvedTrack(
            identity = TrackIdentity(id = id, title = "Song $id", artist = artist,
                album = "Album $id", durationMs = 123_456),
            locations = listOf(TrackLocation(id = "loc-$id", trackId = id,
                type = TrackLocationType.MEDIASTORE, uri = uri)),
            preferredLocation = null,
        )

    @Test fun stableAcrossReorderedInput() {
        val a = track("a")
        val b = track("b")
        assertEquals(artistBulkMembershipDigest(listOf(a,b), 4),
            artistBulkMembershipDigest(listOf(b,a), 4))
    }

    @Test fun changedCreditRevokesProposal() {
        assertNotEquals(artistBulkMembershipDigest(listOf(track("a")), 4),
            artistBulkMembershipDigest(listOf(track("a", artist = "a-ha")), 4))
    }

    @Test fun changedUriRevokesProposal() {
        assertNotEquals(artistBulkMembershipDigest(listOf(track("a")), 4),
            artistBulkMembershipDigest(listOf(track("a", uri = "content://new")), 4))
    }

    @Test fun libraryScopeABAChangesDigestEvenWhenTracksRestore() {
        val tracks = listOf(track("a"))
        assertNotEquals(artistBulkMembershipDigest(tracks, 4), artistBulkMembershipDigest(tracks, 6))
    }

    @Test fun missingAndAdditionalTrackCannotShareSnapshot() {
        assertNotEquals(artistBulkMembershipDigest(listOf(track("a")), 4),
            artistBulkMembershipDigest(listOf(track("a"),track("b")), 4))
    }

    @Test fun absenceIsValidAndNeverNegative() {
        assertTrue(artistBulkMembershipDigest(emptyList(), 0) >= 0L)
        assertTrue(artistBulkMembershipDigest(listOf(track("a")), 99) >= 0L)
    }

    @Test fun locationAvailabilityChangesDigest() {
        val live = track("a")
        val offline = live.copy(locations = live.locations.map {
            it.copy(availability = LocationAvailability.OFFLINE)
        })
        assertNotEquals(artistBulkMembershipDigest(listOf(live), 0),
            artistBulkMembershipDigest(listOf(offline), 0))
    }
}
