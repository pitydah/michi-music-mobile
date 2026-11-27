package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** K48-S5 — discovered variants are review candidates, never automatic merges. */
class ArtistPresentationGroupingTest {

    private fun occurrence(
        credit: String,
        albumIds: List<String>,
        trackCount: Int = albumIds.size,
        mbid: String? = null,
        libraryId: String? = "lib_1",
    ) = ArtistCreditOccurrence(credit, libraryId, albumIds, trackCount, mbid)

    @Test
    fun sameVerifiedIdentityMergesIntoOneEntryWithoutLosingAlbums() {
        val plan = planArtistPresentation(
            listOf(
                occurrence("A-ha", listOf("album_a"), trackCount = 10, mbid = "MBID-AHA"),
                occurrence("a-ha", listOf("album_b"), trackCount = 11, mbid = "mbid-aha"),
            ),
        )
        assertEquals(1, plan.entries.size)
        val entry = plan.entries.single()
        assertEquals(listOf("A-ha", "a-ha"), entry.mergedCredits)
        assertEquals(listOf("album_a", "album_b"), entry.albumIds)
        assertEquals(21, entry.trackCount)
        assertEquals("mbid-aha", entry.mbid)
        assertTrue(plan.variantHints.isEmpty())
    }

    @Test
    fun conflictingIdentitiesAreNeverMergedAndAskForReview() {
        val plan = planArtistPresentation(
            listOf(
                occurrence("A-ha", listOf("album_a"), mbid = "MBID-ONE"),
                occurrence("a-ha", listOf("album_b"), mbid = "MBID-TWO"),
            ),
        )
        assertEquals(2, plan.entries.size)
        assertEquals(listOf("A-ha", "a-ha"), plan.entries.map { it.credit })
        val hint = plan.variantHints.single()
        assertEquals(ArtistVariantReason.IDENTITY_CONFLICT, hint.reason)
        assertEquals(listOf("A-ha", "a-ha"), hint.credits)
    }

    @Test
    fun oneVerifiedAndOneUnverifiedCreditStayApart() {
        val plan = planArtistPresentation(
            listOf(
                occurrence("A-ha", listOf("album_a"), mbid = "MBID-ONE"),
                occurrence("a-ha", listOf("album_b")),
            ),
        )
        assertEquals(2, plan.entries.size)
        assertEquals(ArtistVariantReason.MIXED_EVIDENCE, plan.variantHints.single().reason)
        assertNull(plan.entries.last().mbid)
    }

    @Test
    fun variantReviewRetainsExactCreditAlbumOwnershipAndIndependentIdentity() {
        val plan = planArtistPresentation(listOf(
            occurrence("A-ha", listOf("album_a"), mbid = "MBID-ONE"),
            occurrence("a-ha", listOf("album_b", "album_c")),
        ))
        val hint = plan.variantHints.single()
        assertEquals(2, hint.reviewChoices.size)
        assertEquals(listOf("A-ha", "a-ha"), hint.reviewChoices.map { it.credit })
        assertEquals(listOf("album_a"), hint.reviewChoices[0].albumIds)
        assertEquals(listOf("album_b", "album_c"), hint.reviewChoices[1].albumIds)
        assertEquals("mbid-one", hint.reviewChoices[0].verifiedMbid)
        assertNull(hint.reviewChoices[1].verifiedMbid)
        assertTrue(hint.reviewChoices.none { it.conflictingEvidence })
        assertEquals("lib_1", hint.reviewChoices[1].libraryId)
    }

    @Test
    fun differentConfirmedIdentitiesRemainSeparateReviewChoices() {
        val plan = planArtistPresentation(listOf(
            occurrence("A-ha", listOf("album_a"), mbid = "MBID-ONE"),
            occurrence("a-ha", listOf("album_b"), mbid = "MBID-TWO"),
        ))
        val hint = plan.variantHints.single()
        assertEquals(ArtistVariantReason.IDENTITY_CONFLICT, hint.reason)
        assertEquals(setOf("mbid-one", "mbid-two"),
            hint.reviewChoices.mapNotNull { it.verifiedMbid }.toSet())
        assertEquals(2, hint.reviewChoices.size)
    }

    @Test
    fun withoutEvidenceCreditsRemainSeparateAndAreOnlySuggested() {
        val plan = planArtistPresentation(
            listOf(
                occurrence("A-ha", listOf("album_a")),
                occurrence("a-ha", listOf("album_b")),
            ),
        )
        assertEquals(listOf("A-ha", "a-ha"), plan.entries.map { it.credit })
        assertTrue(plan.entries.all { it.mergedCredits.size == 1 })
        assertEquals(ArtistVariantReason.IDENTITY_UNVERIFIED, plan.variantHints.single().reason)
        assertEquals(listOf("album_a", "album_b"), plan.variantHints.single().albumIds)
    }

    /**
     * K48-S5: the album model stores a single credit, so the screen must feed
     * occurrences built from track credits. Two credits for the same album id
     * are still evidence worth reviewing and must never collapse silently.
     */
    @Test
    fun oneAlbumWithTwoTrackCreditsSurfacesAVariantWithoutDuplicatingTheAlbum() {
        val plan = planArtistPresentation(
            listOf(
                occurrence("A-ha", listOf("album_hunting"), trackCount = 2),
                occurrence("a-ha", listOf("album_hunting"), trackCount = 1),
            ),
        )
        assertEquals(listOf("A-ha", "a-ha"), plan.entries.map { it.credit })
        assertEquals(ArtistVariantReason.IDENTITY_UNVERIFIED, plan.variantHints.single().reason)
        assertEquals(listOf("A-ha", "a-ha"), plan.variantHints.single().credits)
        assertEquals(listOf("album_hunting"), plan.variantHints.single().albumIds)
        assertEquals(3, plan.entries.sumOf { it.trackCount })
    }

    @Test
    fun creditsFromDifferentLibrariesAreNeverMergedEvenWithTheSameIdentity() {
        val plan = planArtistPresentation(
            listOf(
                occurrence("A-ha", listOf("album_a"), mbid = "MBID-ONE", libraryId = "lib_1"),
                occurrence("a-ha", listOf("album_b"), mbid = "MBID-ONE", libraryId = "lib_2"),
            ),
        )
        assertEquals(2, plan.entries.size)
        assertTrue(plan.variantHints.isEmpty())
    }

    @Test
    fun discoveryIgnoresCasePunctuationAndSpacingWithoutMerging() {
        val plan = planArtistPresentation(
            listOf(
                occurrence("A-ha", listOf("album_a")),
                occurrence("a\u2011HA", listOf("album_b")),
                occurrence(" A. Ha ", listOf("album_c")),
            ),
        )
        assertEquals(3, plan.entries.size)
        assertEquals(3, plan.variantHints.single().credits.size)
    }

    @Test
    fun unrelatedCreditsProduceNoHint() {
        val plan = planArtistPresentation(
            listOf(
                occurrence("A-ha", listOf("album_a")),
                occurrence("Ace of Base", listOf("album_b")),
                occurrence("Air", listOf("album_c")),
            ),
        )
        assertEquals(3, plan.entries.size)
        assertTrue(plan.variantHints.isEmpty())
    }

    @Test
    fun literalHomonymWithTwoCorroboratedMbidsIsNotAReviewableSingleIdentity() {
        val plan = planArtistMemberships(listOf(
            ArtistMembershipOccurrence("lib_1", "album-a", "Alex", "track-1", "mbid-one"),
            ArtistMembershipOccurrence("lib_1", "album-b", "Alex", "track-2", "mbid-two"),
        ))
        assertEquals(1, plan.entries.size) // local music remains visible; never invent a split.
        assertNull(plan.entries.single().mbid)
        val hint = plan.variantHints.single()
        assertEquals(ArtistVariantReason.IDENTITY_CONFLICT, hint.reason)
        assertEquals(listOf("Alex"), hint.credits)
        assertEquals(listOf("album-a", "album-b"), hint.albumIds)
        assertTrue(hint.reviewChoices.single().conflictingEvidence)
        assertNull(hint.reviewChoices.single().verifiedMbid)
    }

    @Test
    fun singleCreditsKeepTheirExactAlbumsEvenWhenDuplicated() {
        val plan = planArtistPresentation(
            listOf(
                occurrence("Björk", listOf("album_a", "album_a"), trackCount = 5),
            ),
        )
        assertEquals(listOf("album_a"), plan.entries.single().albumIds)
        assertEquals(5, plan.entries.single().trackCount)
        assertEquals(listOf("Björk"), plan.entries.single().mergedCredits)
    }

    @Test
    fun blankCreditsAreDroppedInsteadOfInventingAnArtist() {
        val plan = planArtistPresentation(
            listOf(
                occurrence("   ", listOf("album_a")),
                occurrence("A-ha", listOf("album_b")),
            ),
        )
        assertEquals(listOf("A-ha"), plan.entries.map { it.credit })
    }
    /**
     * K48-R01: repeating a literal credit across albums must aggregate membership, never
     * replace it. Three A-ha albums of ten tracks stay three albums and thirty tracks.
     */
    @Test
    fun threeAlbumsWithTheSameCreditKeepEveryAlbumAndTrack() {
        val plan = planArtistPresentation(
            listOf(
                occurrence("A-ha", listOf("album_hunting"), trackCount = 10),
                occurrence("A-ha", listOf("album_scoundrel"), trackCount = 10),
                occurrence("A-ha", listOf("album_east"), trackCount = 10),
            ),
        )
        val entry = plan.entries.single()
        assertEquals("A-ha", entry.credit)
        assertEquals(listOf("album_east", "album_hunting", "album_scoundrel"), entry.albumIds)
        assertEquals(30, entry.trackCount)
        assertTrue(plan.variantHints.isEmpty())
    }

    /** The same guarantee through the real membership path used by the Artists screen. */
    @Test
    fun membershipsCountDistinctTracksAcrossRepeatedAlbumsAndCredits() {
        val memberships = buildList {
            listOf("album_hunting", "album_scoundrel", "album_east").forEach { album ->
                (0 until 10).forEach { index ->
                    add(
                        ArtistMembershipOccurrence(
                            libraryId = "lib_1",
                            albumId = album,
                            literalCredit = "A-ha",
                            trackIdentity = "$album#$index",
                        ),
                    )
                }
            }
        }
        val plan = planArtistMemberships(memberships)
        val entry = plan.entries.single()
        assertEquals(3, entry.albumIds.size)
        assertEquals(30, entry.trackCount)
    }

    /** A duplicated membership of the same track must not inflate the count. */
    @Test
    fun repeatedMembershipOfTheSameTrackIsCountedOnce() {
        val membership = ArtistMembershipOccurrence(
            libraryId = "lib_1",
            albumId = "album_hunting",
            literalCredit = "A-ha",
            trackIdentity = "album_hunting#0",
        )
        val plan = planArtistMemberships(listOf(membership, membership.copy()))
        assertEquals(1, plan.entries.single().trackCount)
    }

}
