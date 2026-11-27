package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.ui.components.ArtistMosaicEntry
import org.michimusic.mobile.ui.components.orderedArtistMosaicEntries

/** D37 isolated tests; Compose navigation and Room/KSP require OpenCode. */
class ArtistHomonymMosaicPolicyTest {
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"
    private fun occurrence(album: String, track: String, mbid: String?,
                           lib: String = "lib", credit: String = "Alex") =
        ArtistMembershipOccurrence(lib, album, credit, track, mbid)
    private fun entry(albums: List<String> = listOf("a1", "a2")) =
        ArtistPresentationEntry("Alex", listOf("Alex"), albums, 2, "lib", null)
    private fun observations() = listOf(occurrence("a1", "t1", a), occurrence("a2", "t2", b))
    private fun split() = requireNotNull(ArtistHomonymMosaicPolicy.split(entry(), observations()))

    @Test fun separateSameLiteralByConfirmedMbid() {
        val cards = split()
        assertEquals(2, cards.size)
        assertEquals(setOf(a, b), cards.mapNotNull { it.mbid }.toSet())
        assertNotEquals(cards[0].presentationKey, cards[1].presentationKey)
    }
    @Test fun neverMergeDisjointAlbums() {
        assertEquals(setOf(listOf("a1"), listOf("a2")), split().map { it.albumIds }.toSet())
    }
    @Test fun unresolvedEvidenceMustRemainBlocked() {
        assertNull(ArtistHomonymMosaicPolicy.split(entry(),
            listOf(occurrence("a1", "t1", a), occurrence("a2", "t2", null))))
    }
    @Test fun sameAlbumWithTwoMbidsMustRemainBlocked() {
        assertNull(ArtistHomonymMosaicPolicy.split(entry(listOf("a1")),
            listOf(occurrence("a1", "t1", a), occurrence("a1", "t2", b))))
    }
    @Test fun crossLibraryEvidenceCannotSplit() {
        assertNull(ArtistHomonymMosaicPolicy.split(entry(),
            listOf(occurrence("a1", "t1", a), occurrence("a2", "t2", b, lib = "other"))))
    }
    @Test fun duplicateTrackIdCannotSplit() {
        assertNull(ArtistHomonymMosaicPolicy.split(entry(),
            listOf(occurrence("a1", "same", a), occurrence("a2", "same", b))))
    }
    @Test fun otherCreditCannotSplit() {
        assertNull(ArtistHomonymMosaicPolicy.split(entry(),
            listOf(occurrence("a1", "t1", a), occurrence("a2", "t2", b, credit = "Other"))))
    }
    @Test fun invalidOrOnlyOneMbidCannotSplit() {
        assertNull(ArtistHomonymMosaicPolicy.split(entry(),
            listOf(occurrence("a1", "t1", a), occurrence("a2", "t2", "bad"))))
        assertNull(ArtistHomonymMosaicPolicy.split(entry(),
            listOf(occurrence("a1", "t1", a), occurrence("a2", "t2", a))))
    }
    @Test fun allScopeCannotBecomeExactOwner() {
        assertNull(ArtistHomonymMosaicPolicy.split(entry().copy(libraryId="SCOPE_ALL"), observations()))
    }
    @Test fun deterministicRegardlessOfObservationOrder() {
        assertEquals(split(), ArtistHomonymMosaicPolicy.split(entry(), observations().reversed()))
    }
    @Test fun exactProjectionHasOnlyOwnMember() {
        val card = split().first()
        val result = ArtistExactMembershipProjection.prepare(card, observations(), "lib")
        assertTrue(result is ArtistExactMembershipResult.Ready)
        assertEquals(1, (result as ArtistExactMembershipResult.Ready).snapshot.members.size)
    }
    @Test fun exactProjectionRejectsAdoptingOtherAlbum() {
        val card = split().first().copy(albumIds = listOf("a2"))
        assertTrue(ArtistExactMembershipProjection.prepare(card, observations(), "lib")
            is ArtistExactMembershipResult.Blocked)
    }
    @Test fun oldTicketCannotRevalidateAfterTrackChange() {
        val card = split().first()
        val snapshot = (ArtistExactMembershipProjection.prepare(card, observations(), "lib")
            as ArtistExactMembershipResult.Ready).snapshot
        val changed = observations().map { if (it.albumId == "a1") it.copy(trackIdentity="changed") else it }
        assertFalse(snapshot.revalidate(card, changed, "lib"))
    }
    @Test fun routeFailsIfNewUnresolvedHomonymAppears() {
        assertTrue(ArtistExactMembershipProjection.prepare(split().first(),
            observations() + occurrence("a3", "t3", null), "lib")
            is ArtistExactMembershipResult.Blocked)
    }
    @Test fun twoDistinctCardsDoNotEnableSharedArt() {
        val tiles = split().map { card -> ArtistMosaicEntry(card.credit, card.albumIds,
            card.albumIds.size, card.trackCount, entryKey = requireNotNull(card.presentationKey),
            identityMbid = card.mbid, suppressArtwork = card.identityPartitioned) }
        assertEquals(2, orderedArtistMosaicEntries(tiles).map { it.entryKey }.toSet().size)
        assertTrue(tiles.all { it.suppressArtwork })
    }
}
