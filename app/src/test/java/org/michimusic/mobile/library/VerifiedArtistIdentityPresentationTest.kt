package org.michimusic.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.artist.ArtistMembershipOccurrence
import org.michimusic.mobile.artist.ArtistVariantReason
import org.michimusic.mobile.artist.planArtistMemberships

/**
 * K48-R12-03: the artists grid may only consolidate two credits on corroborated identity,
 * and it must never lose an album or a track while doing it.
 */
class VerifiedArtistIdentityPresentationTest {

    private val library = "lib_device_local"
    private val otherLibrary = "lib_sd_card"
    private val verified = "1f2e3d4c-5b6a-4798-8c1d-2e3f4a5b6c7d"
    private val conflicting = "9a8b7c6d-5e4f-4321-8765-0f1e2d3c4b5a"

    private fun albums(credit: String, libraryId: String?, count: Int, tracksPerAlbum: Int) =
        (1..count).flatMap { album ->
            (1..tracksPerAlbum).map { track ->
                ArtistMembershipOccurrence(
                    libraryId = libraryId,
                    albumId = "album-$album",
                    literalCredit = credit,
                    trackIdentity = "track-$album-$track",
                )
            }
        }

    @Test
    fun twoSpellingsOfOneVerifiedArtistBecomeOneEntryWithEveryAlbumAndTrack() {
        val occurrences =
            albums("A-ha", library, count = 3, tracksPerAlbum = 10) +
                albums("a-ha", library, count = 3, tracksPerAlbum = 10)

        val plan = planArtistMemberships(
            occurrences.map { it.copy(verifiedMbid = verified) },
        )

        assertEquals("one artist", 1, plan.entries.size)
        // Both spellings share album ids in this fixture, so the union stays at three albums
        // and the six track identities per album collapse to three distinct ones.
        assertEquals(3, plan.entries.single().albumIds.size)
        assertEquals(30, plan.entries.single().trackCount)
        assertEquals(setOf("A-ha", "a-ha"), plan.entries.single().mergedCredits.toSet())
        assertEquals(verified, plan.entries.single().mbid)
        assertTrue("no variant hint survives a verified merge", plan.variantHints.isEmpty())
    }

    @Test
    fun repeatedCreditsKeepEveryAlbumEvenWithoutAnyIdentity() {
        val occurrences = albums("A-ha", library, count = 3, tracksPerAlbum = 10)

        val plan = planArtistMemberships(occurrences)

        assertEquals(1, plan.entries.size)
        assertEquals(3, plan.entries.single().albumIds.size)
        assertEquals(30, plan.entries.single().trackCount)
        assertNull(plan.entries.single().mbid)
    }

    @Test
    fun conflictingIdentitiesStayTwoArtistsAndSayWhy() {
        val occurrences =
            albums("A-ha", library, count = 1, tracksPerAlbum = 2).map { it.copy(verifiedMbid = verified) } +
                albums("a-ha", library, count = 1, tracksPerAlbum = 2).map { it.copy(verifiedMbid = conflicting) }

        val plan = planArtistMemberships(occurrences)

        assertEquals("different identities are different artists", 2, plan.entries.size)
        assertEquals(
            ArtistVariantReason.IDENTITY_CONFLICT,
            plan.variantHints.single().reason,
        )
    }

    @Test
    fun oneVerifiedCreditNeverInheritsIdentityForTheOther() {
        val occurrences =
            albums("A-ha", library, count = 1, tracksPerAlbum = 2).map { it.copy(verifiedMbid = verified) } +
                albums("a-ha", library, count = 1, tracksPerAlbum = 2)

        val plan = planArtistMemberships(occurrences)

        assertEquals(2, plan.entries.size)
        assertEquals(ArtistVariantReason.MIXED_EVIDENCE, plan.variantHints.single().reason)
        assertEquals(verified, plan.entries.single { it.credit == "A-ha" }.mbid)
        assertNull(plan.entries.single { it.credit == "a-ha" }.mbid)
    }

    @Test
    fun theSameIdentityInTwoLibrariesIsNotMerged() {
        val occurrences =
            albums("A-ha", library, count = 1, tracksPerAlbum = 2).map { it.copy(verifiedMbid = verified) } +
                albums("a-ha", otherLibrary, count = 1, tracksPerAlbum = 2).map { it.copy(verifiedMbid = verified) }

        val plan = planArtistMemberships(occurrences)

        assertEquals("a library association belongs to one scope", 2, plan.entries.size)
        assertTrue("cross-library hints would leak another library's evidence", plan.variantHints.isEmpty())
    }

    @Test
    fun onlyLookAlikeCreditsAskForAnIdentity() {
        val credits = mapOf(
            "A-ha" to listOf("album-1"),
            "a-ha" to listOf("album-2"),
            "The Cure" to listOf("album-3"),
            "Alphaville" to listOf("album-4"),
        )

        val candidates = identityLookupCandidates(credits)

        assertEquals(setOf("A-ha", "a-ha"), candidates.keys)
    }

    @Test
    fun aSingleCreditNeverAsksForAnIdentity() {
        assertTrue(identityLookupCandidates(mapOf("The Cure" to listOf("album-1"))).isEmpty())
    }
}
