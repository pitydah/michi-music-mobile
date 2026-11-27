package org.michimusic.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Album
import org.michimusic.core.models.Track
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum

/** Pure row selector; no network, Room or Compose host is executed in this suite. */
internal class ArtistSameAlbumTrackReviewRowsTest {
    private val a="11111111-1111-4111-8111-111111111111"
    private val b="22222222-2222-4222-8222-222222222222"
    private val one=Track(id="1",title="Primera canción",artist="Alex",filepath="/music/one.flac")
    private val two=Track(id="2",title="Segunda canción",artist="Alex",filepath="/music/two.flac")
    private val album=LocalAlbum(Album("album", "Mismo álbum", "Alex"), listOf(one,two))
    private fun identity(track:Track,confirmed:Boolean=false) =
        ArtistExactOccurrenceKey("album",org.michimusic.mobile.artist.exactArtistTrackIdentity(track)) to
            ArtistExactOccurrenceIdentity(
                confirmedMbid=if (confirmed) a else null,
                untrustedTagMbids=listOf(a,b),
                confirmedByExactTrackChoice=confirmed,
                confirmedAlbumMbid=a,
            )
    @Test fun sameAlbumDisagreementOffersBothTracksForManualReview() {
        val state=ArtistExactOccurrenceIdentityResult.Ready(mapOf(identity(one),identity(two)))
        val rows=sameAlbumTrackReviewRows("lib",listOf(album),state)
        assertEquals(2,rows.size)
        assertEquals(listOf(a,b),rows.first().candidateMbids)
        assertEquals("Primera canción",rows.first().title)
    }
    @Test fun confirmedTrackIsNotAskedAgain() {
        val state=ArtistExactOccurrenceIdentityResult.Ready(mapOf(identity(one,true),identity(two)))
        assertEquals(listOf("2"),sameAlbumTrackReviewRows("lib",listOf(album),state)
            .map{requireNotNull(it.claim.trackId)})
    }
    @Test fun revalidationOffersCandidatesForAlreadyConfirmedTrack() {
        val state=ArtistExactOccurrenceIdentityResult.Ready(mapOf(identity(one,true),identity(two)))
        val claim=ArtistExactOccurrenceClaim(
            "lib", album.album.id, album.album.artist, one.artist,
            org.michimusic.mobile.artist.exactArtistTrackIdentity(one), one.id,
            artistTrackContentSignature(one),
        )
        // Initial review hides the confirmed track; revalidation MUST find it.
        assertEquals(listOf("2"), sameAlbumTrackReviewRows("lib",listOf(album),state)
            .map { it.claim.trackId })
        val allRows = sameAlbumTrackReviewRows("lib", listOf(album), state,
            includeAlreadyConfirmed = true)
        assertEquals(listOf("1", "2"), allRows.map { it.claim.trackId })
        assertEquals(listOf(true, false), allRows.map { it.alreadyConfirmed })
        assertEquals(listOf(a,b), offeredMbidsForExactTrack("lib",listOf(album),state,claim,
            includeAlreadyConfirmed=true))
        assertEquals(null, offeredMbidsForExactTrack("lib",listOf(album),state,claim,
            includeAlreadyConfirmed=false))
    }

    @Test fun duplicateAlbumIdIsNeverAcceptedAsExactSource() {
        val state=ArtistExactOccurrenceIdentityResult.Ready(mapOf(identity(one,true),identity(two)))
        val claim=ArtistExactOccurrenceClaim(
            "lib",album.album.id,album.album.artist,one.artist,
            org.michimusic.mobile.artist.exactArtistTrackIdentity(one),one.id,
            artistTrackContentSignature(one),
        )
        assertEquals(null,offeredMbidsForExactTrack("lib",listOf(album,album),state,claim,true))
        assertEquals(null,offeredMbidsForExactTrack("other",listOf(album),state,claim,true))
    }

    @Test fun unverifiedSingleMbidNeverOffersFalseChoices() {
        val state=ArtistExactOccurrenceIdentityResult.Ready(mapOf(
            ArtistExactOccurrenceKey("album",org.michimusic.mobile.artist.exactArtistTrackIdentity(one)) to
                ArtistExactOccurrenceIdentity(null,listOf(a))))
        assertTrue(sameAlbumTrackReviewRows("lib",listOf(album),state).isEmpty())
    }
    @Test fun scopeAllNeverOffersTrackConfirmation() {
        val state=ArtistExactOccurrenceIdentityResult.Ready(mapOf(identity(one),identity(two)))
        assertTrue(sameAlbumTrackReviewRows("SCOPE_ALL",listOf(album),state).isEmpty())
    }

    @Test fun moreThan512AlbumsDoNotHideOneExactReview() {
        val state = ArtistExactOccurrenceIdentityResult.Ready(mapOf(identity(one), identity(two)))
        val other = (1..600).map { i -> LocalAlbum(Album("other$i", "Other $i", "Other"), emptyList()) }
        val all = other + album
        val rows = sameAlbumTrackReviewRows("lib", all, state)
        assertEquals(2, rows.size)
        assertEquals(listOf(a,b), offeredMbidsForExactTrack("lib", all, state, rows.first().claim, false))
    }
    @Test fun moreThan128ReviewableTracksAreAllVisible() {
        val tracks = (1..160).map { i -> one.copy(id = "id$i", filepath = "/music/$i.flac") }
        val big = LocalAlbum(album.album, tracks)
        val state = ArtistExactOccurrenceIdentityResult.Ready(tracks.associate { identity(it) })
        val result = selectSameAlbumTrackReviewRows("lib", listOf(big), state)
        assertEquals(160, result.rows.size)
        assertEquals(0, result.omittedAlbums)
        assertEquals(listOf(a,b), offeredMbidsForExactTrack("lib", listOf(big), state, result.rows.last().claim, false))
    }
    @Test fun malformedAlbumDoesNotHideUnrelatedValidTracks() {
        val state = ArtistExactOccurrenceIdentityResult.Ready(mapOf(identity(one), identity(two)))
        val bad = LocalAlbum(Album("bad", "Bad", "Alex"), listOf(one, one))
        val result = selectSameAlbumTrackReviewRows("lib", listOf(bad, album), state)
        assertEquals(1, result.omittedAlbums)
        assertEquals(2, result.rows.size)
    }
    @Test fun duplicateAlbumIdsAreReportedAndNotReviewable() {
        val state = ArtistExactOccurrenceIdentityResult.Ready(mapOf(identity(one), identity(two)))
        val result = selectSameAlbumTrackReviewRows("lib", listOf(album, album), state)
        assertEquals(2, result.omittedAlbums)
        assertTrue(result.rows.isEmpty())
    }
    @Test fun catalogueBoundIsExplicitNotAnEmptySuccess() {
        val state = ArtistExactOccurrenceIdentityResult.Ready(mapOf(identity(one), identity(two)))
        val many = List(ArtistExactOccurrenceIdentityPolicy.MAX_OCCURRENCES + 1) { album }
        val result = selectSameAlbumTrackReviewRows("lib", many, state)
        assertTrue(result.catalogLimitExceeded)
        assertTrue(result.rows.isEmpty())
    }

    @Test fun partialEvidenceCannotOfferAReviewForMissingOccurrence() {
        val state = ArtistExactOccurrenceIdentityResult.Ready(mapOf(identity(one)))
        assertEquals(listOf("1"), sameAlbumTrackReviewRows("lib", listOf(album), state).map { it.claim.trackId })
    }
}
