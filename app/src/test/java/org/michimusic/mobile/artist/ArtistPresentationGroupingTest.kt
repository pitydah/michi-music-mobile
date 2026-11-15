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
}
