package org.michimusic.mobile.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.artist.ArtistVariantReviewChoice
import org.michimusic.data.repository.MusicRootStamp

/** D15: an expired/replayed/invalid review route never authorizes a new artist identity. */
class ArtistReviewTicketStoreTest {
    private fun root(epoch: Long = 0L, source: String = "saf:A", revision: Long = 0L) =
        ArtistReviewRootGuard(MusicRootStamp(source, epoch), revision)

    private fun choice(albums: List<String> = listOf("album-a", "album-b")) =
        ArtistVariantReviewChoice(
            credit = "A-ha", libraryId = "local-library", albumIds = albums,
            verifiedMbid = null, conflictingEvidence = false,
        )

    @Test fun exactContextIsRequiredEvenWhenCreditMatches() {
        var now = 10_000L
        val store = ArtistReviewTicketStore { now }
        val ticket = requireNotNull(store.issue(choice(), root()))
        assertFalse(ticket.contains("A-ha"))
        assertFalse(ticket.contains("local-library"))
        val c = requireNotNull(store.resolve(ticket, root()))
        assertTrue(c.permits("A-ha", "local-library", listOf("album-b", "album-a"), root(), now))
        assertFalse(c.permits("A-ha", "other-library", listOf("album-b", "album-a"), root(), now))
        assertFalse(c.permits("a-ha", "local-library", listOf("album-b", "album-a"), root(), now))
        assertFalse(c.permits("A-ha", "local-library", listOf("album-a"), root(), now))
        assertFalse(c.permits("A-ha", "local-library", listOf("album-a", "album-b", "album-c"), root(), now))
        assertNull(store.resolve("invalid-credit-only-route", root()))
        now += ArtistReviewTicketStore.TTL_MS
        assertNull(store.resolve(ticket, root()))
    }

    @Test fun everyTicketIsOpaqueAndRevocable() {
        val store = ArtistReviewTicketStore { 0L }
        val one = requireNotNull(store.issue(choice(), root()))
        val two = requireNotNull(store.issue(choice(), root()))
        assertTrue(one != two)
        assertNotNull(store.resolve(one, root()))
        store.revoke(one)
        assertNull(store.resolve(one, root()))
        assertNotNull(store.resolve(two, root()))
        assertNull(ArtistReviewTicketStore { 0L }.resolve(two, root())) // process restart
    }

    @Test fun rootAbaRejectsTicketEvenAfterReturningToSameSource() {
        val store = ArtistReviewTicketStore { 1_000L }
        val ticket = requireNotNull(store.issue(choice(), root(epoch = 4L)))
        val context = requireNotNull(store.resolve(ticket, root(4L)))
        assertFalse(context.permits("A-ha", "local-library", choice().albumIds, root(6L), 1_000L))
        assertNull(store.resolve(ticket, root(6L))) // ticket revoked on root mismatch
        assertNull(store.resolve(ticket, root(4L))) // returning to A cannot restore it
    }

    @Test fun expiredContextCannotAuthorizeOpenScreenEvenIfRemembered() {
        var now = 10_000L
        val store = ArtistReviewTicketStore { now }
        val ticket = requireNotNull(store.issue(choice(), root()))
        val rememberedContext = requireNotNull(store.resolve(ticket, root()))
        now += ArtistReviewTicketStore.TTL_MS
        assertFalse(rememberedContext.permits("A-ha", "local-library", choice().albumIds, root(), now))
        assertNull(store.resolve(ticket, root()))
    }

    @Test fun sourceChangeWithoutEpochIncreaseStillFailsTheSnapshotComparison() {
        val store = ArtistReviewTicketStore { 123L }
        val ticket = requireNotNull(store.issue(choice(), root()))
        assertNull(store.resolve(ticket, root(source = "saf:B")))
    }

    @Test fun catalogRescanInvalidatesTicketEvenWhenPersistentRootStampIsUnchanged() {
        val store = ArtistReviewTicketStore { 1L }
        val ticket = requireNotNull(store.issue(choice(), root()))
        assertNull(store.resolve(ticket, root(revision = 1L)))
    }

    @Test fun reviewRoutePreservesLibraryChromeWithoutExposingArtist() {
        val presentation = navigationPresentationFor("artist-review/{ticketId}")
        assertEquals(MichiScreenLevel.DETAIL, presentation.level)
        assertEquals("library", presentation.selectedPrimaryTab)
        assertTrue(presentation.showMiniPlayer)
    }

    @Test fun unsupportedMembershipCannotCreateTicket() {
        val store = ArtistReviewTicketStore { 0L }
        assertNull(store.issue(choice(emptyList()), root()))
        assertNull(store.issue(choice(listOf("album-b", "album-a")), root())) // unsorted receipt
        assertNull(store.issue(choice(listOf("album-a", "album-a")), root())) // duplicate receipt
        assertNull(store.issue(choice().copy(libraryId = null), root()))
        assertNull(store.issue(choice().copy(conflictingEvidence = true), root()))
        assertEquals(0, listOf(null, "").count { store.resolve(it ?: "", root()) != null })
    }
}
