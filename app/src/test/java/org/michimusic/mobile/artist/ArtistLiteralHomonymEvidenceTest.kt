package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ArtistLiteralHomonymEvidenceTest {
    private val first = "11111111-1111-4111-8111-111111111111"
    private val second = "22222222-2222-4222-8222-222222222222"
    private fun row(
        album: String, key: String, mbid: String? = first,
        library: String? = "library", credit: String = "Alex",
    ) = ArtistMembershipOccurrence(library, album, credit, key, mbid)
    private fun review(rows: List<ArtistMembershipOccurrence>) =
        ArtistLiteralHomonymEvidence.inspect("Alex", "library", rows) as ArtistLiteralHomonymResult.Review

    @Test fun separatesIdenticalCreditsByExactTrackEvidence() {
        val result = review(listOf(row("one", "s1"), row("two", "s2", second)))
        assertEquals(listOf(first, second), result.buckets.map { it.mbid })
        assertEquals(listOf(listOf("one"), listOf("two")), result.buckets.map { it.albumIds })
        assertEquals(2, result.buckets.sumOf { it.memberCount })
        assertTrue(result.needsReview)
    }

    @Test fun neverAttributesEverySongInASharedAlbum() {
        val result = review(listOf(row("same", "trackA"), row("same", "trackB", second)))
        assertEquals(1, result.buckets[0].memberCount)
        assertEquals(1, result.buckets[1].memberCount)
        assertEquals(listOf("trackA"), result.buckets[0].members.map { it.trackIdentity })
    }

    @Test fun unresolvedCreditRemainsPendingInsteadOfBorrowingVerifiedMbid() {
        val result = review(listOf(row("one", "s1"), row("two", "s2", second), row("three", "s3", null)))
        assertEquals(1, result.unresolved.size)
        assertEquals("three", result.unresolved.single().albumId)
        assertEquals(null, result.unresolved.single().verifiedMbid)
    }

    @Test fun singleMbidIsNotAFalseConflict() {
        assertEquals(ArtistLiteralHomonymResult.NotConflicting,
            ArtistLiteralHomonymEvidence.inspect("Alex", "library", listOf(row("a", "1"), row("b", "2"))))
    }

    @Test fun missingLibraryRejectsAll() {
        assertEquals(ArtistLiteralHomonymResult.Reason.NO_EXACT_LIBRARY,
            (ArtistLiteralHomonymEvidence.inspect("Alex", null, listOf(row("a", "1"))) as ArtistLiteralHomonymResult.Blocked).reason)
    }

    @Test fun wrongLibraryIsNotMixedIntoTheSameArtist() {
        assertEquals(ArtistLiteralHomonymResult.Reason.MIXED_SCOPE_OR_CREDIT,
            (ArtistLiteralHomonymEvidence.inspect("Alex", "library", listOf(row("a", "1"), row("b", "2", second, "other"))) as ArtistLiteralHomonymResult.Blocked).reason)
    }

    @Test fun anotherLiteralCreditCannotEnterThePartition() {
        assertEquals(ArtistLiteralHomonymResult.Reason.MIXED_SCOPE_OR_CREDIT,
            (ArtistLiteralHomonymEvidence.inspect("Alex", "library", listOf(row("a", "1"), row("b", "2", second, credit="ALEX"))) as ArtistLiteralHomonymResult.Blocked).reason)
    }

    @Test fun duplicateTrackIdentityFailsEvenWithTwoMbids() {
        assertEquals(ArtistLiteralHomonymResult.Reason.DUPLICATE_OCCURRENCE,
            (ArtistLiteralHomonymEvidence.inspect("Alex", "library", listOf(row("a", "1"), row("a", "1", second))) as ArtistLiteralHomonymResult.Blocked).reason)
    }

    @Test fun duplicateTrackKeyInDifferentAlbumIsNotConfused() {
        val result = review(listOf(row("one", "same"), row("two", "same", second)))
        assertEquals(2, result.buckets.size)
    }

    @Test fun malformedMbidIsRejectedInsteadOfBecomingUnknown() {
        assertEquals(ArtistLiteralHomonymResult.Reason.INVALID_MUSICBRAINZ_ID,
            (ArtistLiteralHomonymEvidence.inspect("Alex", "library", listOf(row("a", "1"), row("b", "2", "not-uuid"))) as ArtistLiteralHomonymResult.Blocked).reason)
    }

    @Test fun albumAndTrackRequired() {
        val one = ArtistLiteralHomonymEvidence.inspect("Alex", "library", listOf(row("", "x"))) as ArtistLiteralHomonymResult.Blocked
        val two = ArtistLiteralHomonymEvidence.inspect("Alex", "library", listOf(row("x", ""))) as ArtistLiteralHomonymResult.Blocked
        assertEquals(ArtistLiteralHomonymResult.Reason.INVALID_OCCURRENCE, one.reason)
        assertEquals(ArtistLiteralHomonymResult.Reason.INVALID_OCCURRENCE, two.reason)
    }

    @Test fun noUnboundedAllocationOnLargeScopes() {
        val rows = (0..2048).map { i -> row("a$i", "t$i", if(i%2==0) first else second) }
        assertEquals(ArtistLiteralHomonymResult.Reason.EXCEEDS_LOCAL_BUDGET,
            (ArtistLiteralHomonymEvidence.inspect("Alex", "library", rows) as ArtistLiteralHomonymResult.Blocked).reason)
    }

    @Test fun determinismAcrossDifferentRowOrder() {
        val rows = listOf(row("b", "z", second), row("a", "y"), row("a", "x", second))
        assertEquals(review(rows), review(rows.reversed()))
    }

    @Test fun canonicalUppercaseUuidDoesNotSplitIdentity() {
        val upper = first.uppercase()
        assertEquals(ArtistLiteralHomonymResult.NotConflicting,
            ArtistLiteralHomonymEvidence.inspect("Alex", "library", listOf(row("a", "1", upper), row("a", "2", first))))
    }

    @Test fun unknownAndOneKnownAreNotAnInferredSecondPerformer() {
        assertEquals(ArtistLiteralHomonymResult.NotConflicting,
            ArtistLiteralHomonymEvidence.inspect("Alex", "library", listOf(row("a", "1", null), row("b", "2", first))))
    }
}
