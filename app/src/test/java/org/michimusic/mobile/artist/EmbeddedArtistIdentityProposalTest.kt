package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedArtistIdentityProposalTest {
    private val a = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val b = "11111111-1111-4111-8111-111111111111"
    private fun review(vararg tags: EmbeddedArtistTags) = EmbeddedArtistReview(
        inspectedFiles = tags.size, taggedFiles = tags.size,
        artistMbids = tags.flatMap { it.artistMbids }.toSet(),
        albumArtistMbids = tags.flatMap { it.albumArtistMbids }.toSet(),
        creditedNames = tags.flatMap { it.creditedNames }.distinct(),
        albumArtists = tags.flatMap { it.albumArtists }.distinct(),
        trackEvidence = tags.mapIndexed { i, t -> EmbeddedTrackEvidence("content://track/$i", t) },
    )
    @Test fun aggregateOnlyZeroInspectedFilesCannotManufactureCandidate() {
        val forged = EmbeddedArtistReview(0, 0, setOf(a), emptySet(), listOf("Band"), emptyList())
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.NO_EVIDENCE),
            EmbeddedArtistIdentityReview.evaluate("Band", forged))
    }

    @Test fun aggregateOnlyZeroTaggedFilesCannotManufactureCandidate() {
        val forged = EmbeddedArtistReview(1, 0, setOf(a), emptySet(), listOf("Band"), emptyList())
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.NO_EVIDENCE),
            EmbeddedArtistIdentityReview.evaluate("Band", forged))
    }

    @Test fun perFileIdsCannotExceedReceiptTaggedFileCount() {
        val forged = review(
            EmbeddedArtistTags(artistMbids = setOf(a), creditedNames = listOf("Band")),
            EmbeddedArtistTags(artistMbids = setOf(a), creditedNames = listOf("Band")),
        ).copy(taggedFiles = 1)
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.NO_EVIDENCE),
            EmbeddedArtistIdentityReview.evaluate("Band", forged))
    }

    @Test fun legacyAggregatesCannotHideContradictoryAlbumArtistMbid() {
        val legacy = EmbeddedArtistReview(1, 1, setOf(a), setOf(b),
            listOf("Band"), listOf("Band"))
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.CONFLICTING_IDS),
            EmbeddedArtistIdentityReview.evaluate("Band", legacy))
    }

    @Test fun legacySingleConsistentMbidRemainsReviewOnly() {
        val legacy = EmbeddedArtistReview(1, 1, setOf(a), setOf(a),
            listOf("Band"), listOf("Band"))
        assertEquals(EmbeddedArtistIdentityProposal.Candidate(a, 1),
            EmbeddedArtistIdentityReview.evaluate("Band", legacy))
    }

    @Test fun legacyMbidWithoutMatchingArtistCreditCannotRecommendIdentity() {
        val legacy = EmbeddedArtistReview(1, 1, setOf(a), emptySet(),
            listOf("Unrelated Band"), emptyList())
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.WRONG_CREDIT),
            EmbeddedArtistIdentityReview.evaluate("Band", legacy))
    }

    @Test fun legacyMbidWithoutCreditedNamesCannotInventCredit() {
        val legacy = EmbeddedArtistReview(1, 1, setOf(a), emptySet(),
            emptyList(), emptyList())
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.WRONG_CREDIT),
            EmbeddedArtistIdentityReview.evaluate("Band", legacy))
    }

    @Test fun legacyInvalidAggregateCannotBeDroppedToManufactureSingleton() {
        val legacy = EmbeddedArtistReview(1, 1, setOf(a, "invalid-id"), emptySet(),
            listOf("Band"), emptyList())
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.CONFLICTING_IDS),
            EmbeddedArtistIdentityReview.evaluate("Band", legacy))
    }

    @Test fun twoIndependentFilesWithSameMbidProduceReviewProposal() {
        val result = EmbeddedArtistIdentityReview.evaluate("Midnight Oil", review(
            EmbeddedArtistTags(artistMbids = setOf(a), creditedNames = listOf("Midnight Oil")),
            EmbeddedArtistTags(artistMbids = setOf(a), creditedNames = listOf("Midnight Oil")),
        ))
        assertEquals(EmbeddedArtistIdentityProposal.Candidate(a, 2), result)
    }
    @Test fun conflictingMbidsFailClosedEvenIfNameMatches() {
        val result = EmbeddedArtistIdentityReview.evaluate("Band", review(
            EmbeddedArtistTags(artistMbids = setOf(a), creditedNames = listOf("Band")),
            EmbeddedArtistTags(artistMbids = setOf(b), creditedNames = listOf("Band")),
        ))
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.CONFLICTING_IDS), result)
    }
    @Test fun albumArtistIdCanOnlyRepresentExplicitlyCreditedAlbumArtist() {
        val tags = EmbeddedArtistTags(albumArtistMbids = setOf(a), albumArtists = listOf("Band"))
        assertEquals(EmbeddedArtistIdentityProposal.Candidate(a,1),
            EmbeddedArtistIdentityReview.evaluate("Band", review(tags)))
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.WRONG_CREDIT),
            EmbeddedArtistIdentityReview.evaluate("Guest", review(tags)))
    }
    @Test fun multipleCreditedNamesWithOneIdDoNotChooseWrongArtist() {
        val tags = EmbeddedArtistTags(artistMbids = setOf(a), creditedNames = listOf("Band", "Guest"))
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.AMBIGUOUS_CREDIT),
            EmbeddedArtistIdentityReview.evaluate("Band", review(tags)))
    }
    @Test fun noEvidenceDoesNotTriggerRemoteLookup() {
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.NO_EVIDENCE),
            EmbeddedArtistIdentityReview.evaluate("Band", review(EmbeddedArtistTags())))
    }
    @Test fun explicitAlbumArtistEvidenceTakesPrecedenceOverUnlabeledTrackArtistId() {
        val tags = EmbeddedArtistTags(
            artistMbids = setOf(b), albumArtistMbids = setOf(a),
            albumArtists = listOf("Band"),
        )
        assertEquals(EmbeddedArtistIdentityProposal.Candidate(a, 1),
            EmbeddedArtistIdentityReview.evaluate("Band", review(tags)))
    }
    @Test fun oneCorrectTrackAndOneWrongCreditMustNotSuggestAnArtist() {
        val result = EmbeddedArtistIdentityReview.evaluate("Band", review(
            EmbeddedArtistTags(artistMbids = setOf(a), creditedNames = listOf("Band")),
            EmbeddedArtistTags(artistMbids = setOf(a), creditedNames = listOf("Another Band")),
        ))
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.WRONG_CREDIT), result)
    }
    @Test fun unrelatedUnidentifiedTrackDoesNotCreateAnIdentityConflict() {
        val result = EmbeddedArtistIdentityReview.evaluate("Band", review(
            EmbeddedArtistTags(artistMbids = setOf(a), creditedNames = listOf("Band")),
            EmbeddedArtistTags(),
        ))
        assertEquals(EmbeddedArtistIdentityProposal.Candidate(a, 1), result)
    }
    @Test fun forgedReceiptWithMoreEvidenceThanInspectedFailsClosed() {
        val forged = review(EmbeddedArtistTags(artistMbids = setOf(a), creditedNames = listOf("Band")))
            .copy(inspectedFiles = 0)
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.NO_EVIDENCE),
            EmbeddedArtistIdentityReview.evaluate("Band", forged))
    }

    @Test fun aggregateAndPerFileMbidConflictCannotCreateReviewCandidate() {
        val forged = review(EmbeddedArtistTags(artistMbids = setOf(a), creditedNames = listOf("Band")))
            .copy(artistMbids = setOf(b))
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.CONFLICTING_IDS),
            EmbeddedArtistIdentityReview.evaluate("Band", forged))
    }

    @Test fun aggregateAndPerFileAlbumArtistMbidConflictIsBlocked() {
        val forged = review(EmbeddedArtistTags(albumArtistMbids = setOf(a), albumArtists = listOf("Band")))
            .copy(albumArtistMbids = setOf(b))
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.CONFLICTING_IDS),
            EmbeddedArtistIdentityReview.evaluate("Band", forged))
    }

    @Test fun duplicateUriCannotDoubleCountIndependentEvidence() {
        val forged = review(
            EmbeddedArtistTags(artistMbids = setOf(a), creditedNames = listOf("Band")),
            EmbeddedArtistTags(artistMbids = setOf(a), creditedNames = listOf("Band")),
        ).let { it.copy(trackEvidence = it.trackEvidence.map { row -> row.copy(uri = "content://track/reused") }) }
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.NO_EVIDENCE),
            EmbeddedArtistIdentityReview.evaluate("Band", forged))
    }

    @Test fun equivalentMbidCasingAcrossReceiptsRemainsValidForReview() {
        val trusted = review(EmbeddedArtistTags(artistMbids = setOf(a), creditedNames = listOf("Band")))
            .copy(artistMbids = setOf(a.uppercase(java.util.Locale.ROOT)))
        assertEquals(EmbeddedArtistIdentityProposal.Candidate(a, 1),
            EmbeddedArtistIdentityReview.evaluate("Band", trusted))
    }

    @Test fun twoRolesWithDifferentIdsAndSameCreditRemainAmbiguous() {
        val tags = EmbeddedArtistTags(
            artistMbids = setOf(b), albumArtistMbids = setOf(a),
            creditedNames = listOf("Band"), albumArtists = listOf("Band"),
        )
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.AMBIGUOUS_CREDIT),
            EmbeddedArtistIdentityReview.evaluate("Band", review(tags)))
    }

    @Test fun forgedAggregateArtistNameCannotOverridePerFileReceipt() {
        val forged = review(EmbeddedArtistTags(artistMbids = setOf(a), creditedNames = listOf("Real Band")))
            .copy(creditedNames = listOf("Fake Band"))
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.WRONG_CREDIT),
            EmbeddedArtistIdentityReview.evaluate("Real Band", forged))
    }

    @Test fun forgedAggregateAlbumCreditNameCannotOverridePerFileReceipt() {
        val forged = review(EmbeddedArtistTags(albumArtistMbids = setOf(a), albumArtists = listOf("Real Band")))
            .copy(albumArtists = listOf("Other Band"))
        assertEquals(EmbeddedArtistIdentityProposal.Blocked(
            EmbeddedArtistIdentityProposal.Reason.WRONG_CREDIT),
            EmbeddedArtistIdentityReview.evaluate("Real Band", forged))
    }

    @Test fun aggregateNameCaseAndWhitespaceStayEquivalent() {
        val receipt = review(EmbeddedArtistTags(artistMbids = setOf(a), creditedNames = listOf("Band")))
            .copy(creditedNames = listOf(" BAND "))
        assertEquals(EmbeddedArtistIdentityProposal.Candidate(a, 1),
            EmbeddedArtistIdentityReview.evaluate("Band", receipt))
    }

}
