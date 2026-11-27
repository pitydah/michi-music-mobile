package org.michimusic.mobile.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ArtistTrackChoiceLiveGateTest {
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"
    private val claim = ArtistExactOccurrenceClaim("lib", "album-1", "Alex", "Alex",
        "local:1:/files/old.flac", "track-1", "hash:size:title:artist")
    private val request = ArtistTrackChoiceRequest("lib", "album-1", "track-1", "Alex",
        claim.trackIdentity, a, listOf(a,b), "device_music_root", 0, 2, true)
    private val valid = ArtistTrackChoiceLiveReview(claim, listOf(a,b))

    @Test fun liveMembershipWithSameCandidatesPasses() =
        assertTrue(ArtistTrackChoiceLiveGate.matches(request,claim,valid))
    @Test fun replacedFileRejectsEvenIfTrackIdIsReused() =
        assertFalse(ArtistTrackChoiceLiveGate.matches(request,claim,valid.copy(
            claim=claim.copy(localContentSignature="different-content"))))
    @Test fun changedAlbumRejects() =
        assertFalse(ArtistTrackChoiceLiveGate.matches(request,claim,valid.copy(
            claim=claim.copy(albumId="album-2"))))
    @Test fun changedLiteralCreditRejects() =
        assertFalse(ArtistTrackChoiceLiveGate.matches(request,claim,valid.copy(
            claim=claim.copy(literalCredit="Other Alex"))))
    @Test fun changedIdentityCandidatesRejects() =
        assertFalse(ArtistTrackChoiceLiveGate.matches(request,claim,valid.copy(
            offeredMbids=listOf(a))))
    @Test fun reorderedCandidatesRejectsStaleReview() =
        assertFalse(ArtistTrackChoiceLiveGate.matches(request,claim,valid.copy(
            offeredMbids=listOf(b,a))))
    @Test fun emptySignatureCannotBeConfirmed() =
        assertFalse(ArtistTrackChoiceLiveGate.matches(request,claim,valid.copy(
            claim=claim.copy(localContentSignature=""))))
    @Test fun missingUpdatedSnapshotIsNotAuthority() =
        assertFalse(ArtistTrackChoiceLiveGate.matches(request,claim,null))
    @Test fun anotherSelectedMbidCannotBorrowCandidates() =
        assertFalse(ArtistTrackChoiceLiveGate.matches(request.copy(chosenMbid="33333333-3333-4333-8333-333333333333"),claim,valid))
}
