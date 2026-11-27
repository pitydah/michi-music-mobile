package org.michimusic.mobile.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test
import org.michimusic.mobile.library.ArtistExactOccurrenceClaim
import org.michimusic.mobile.library.ArtistTrackSessionProof
import org.michimusic.mobile.library.ArtistTrackChoiceAuthority
import org.michimusic.data.repository.MusicRootStamp
import org.michimusic.mobile.artist.ArtistMembershipOccurrence
import org.michimusic.mobile.artist.ArtistPresentationEntry

class ArtistExactMembershipTicketsTest {
    private val artist = ArtistPresentationEntry("Alex", listOf("Alex"), listOf("a"), 1, "lib", null)
    private val rows = listOf(ArtistMembershipOccurrence("lib", "a", "Alex", "s\u0000t\u0000content://private", null))
    private val guard = ArtistReviewRootGuard(MusicRootStamp("rootA", 7L), 13L)
    @Test fun issuesOnlyFromExactMembershipAndValidatesArrival() {
        var now = 100L
        val store = ArtistExactMembershipTicketStore { now }
        val token = store.issue(guard, "lib", artist, rows)
        assertNotNull(token)
        assertEquals("Alex", store.resolve(requireNotNull(token), guard, "lib", artist, rows)?.displayCredit)
        assertFalse(token.contains("content"))
    }
    @Test fun rootGenerationAtoBtoACannotReplay() {
        val store = ArtistExactMembershipTicketStore { 100L }
        val token = requireNotNull(store.issue(guard,"lib",artist,rows))
        assertNull(store.resolve(token, ArtistReviewRootGuard(MusicRootStamp("rootA", 9L),13L), "lib", artist, rows))
        assertNull(store.resolve(token, guard,"lib",artist,rows))
    }
    @Test fun changedMembershipOrLibraryInvalidatesTicket() {
        val store = ArtistExactMembershipTicketStore { 100L }
        val token = requireNotNull(store.issue(guard,"lib",artist,rows))
        assertNull(store.resolve(token,guard,"lib",artist, rows.map { it.copy(trackIdentity="different") }))
        assertNull(store.resolve(token,guard,"lib",artist,rows))
    }
    @Test fun wrongLibraryCannotResolve() {
        val store = ArtistExactMembershipTicketStore { 100L }
        val token = requireNotNull(store.issue(guard,"lib",artist,rows))
        assertNull(store.resolve(token,guard,"other",artist,rows))
    }
    @Test fun ttlAndClockRollbackFailClosed() {
        var now=1000L
        val store = ArtistExactMembershipTicketStore { now }
        val token = requireNotNull(store.issue(guard,"lib",artist,rows))
        now=999L
        assertNull(store.resolve(token,guard,"lib",artist,rows))
        now=1000L
        val next=requireNotNull(store.issue(guard,"lib",artist,rows))
        now+=20L*60*1000
        assertNull(store.resolve(next,guard,"lib",artist,rows))
    }
    @Test fun malformedTokenIsRejectedAndExplicitRevocationWorks() {
        val store = ArtistExactMembershipTicketStore { 100L }
        val token=requireNotNull(store.issue(guard,"lib",artist,rows))
        assertNull(store.resolve("artist/Alex?lib=lib",guard,"lib",artist,rows))
        store.revoke(token)
        assertNull(store.resolve(token,guard,"lib",artist,rows))
    }
    @Test fun cannotIssueUnverifiedMerge() {
        val store=ArtistExactMembershipTicketStore { 100L }
        val merged=artist.copy(mergedCredits = listOf("Alex", "alex"), albumIds=listOf("a","b"), trackCount=2)
        val all=rows+rows.first().copy(albumId="b",literalCredit="alex",trackIdentity="track2")
        assertNull(store.issue(guard,"lib",merged,all))
    }
    @Test fun sessionProofOnlyAvailableForExactScopeAndLiveTicket() {
        var now = 100L
        val store = ArtistExactMembershipTicketStore { now }
        val mbid = "11111111-1111-4111-8111-111111111111"
        val member = rows.single().copy(verifiedMbid = mbid, exactTrackConfirmed = true)
        val claim = ArtistExactOccurrenceClaim("lib", "a", "Alex", "Alex", member.trackIdentity,
            "t", "signature")
        val proof = ArtistTrackSessionProof(claim, mbid, "a".repeat(64), 42,
            ArtistTrackChoiceAuthority("lib", "rootA", 7L, 13L), 1, 1)
        val token = requireNotNull(store.issue(guard, "lib", artist.copy(mbid = mbid), listOf(member),
            listOf(proof, proof.copy(claim = claim.copy(albumId = "foreign")))))
        assertEquals(listOf(proof), store.sessionProofs(token, guard, "lib"))
        assertEquals(emptyList<ArtistTrackSessionProof>(), store.sessionProofs(token, guard, "other"))
        now += 20L * 60 * 1000
        assertEquals(emptyList<ArtistTrackSessionProof>(), store.sessionProofs(token, guard, "lib"))
    }

    @Test fun remainingLifetimeAndRevocationAreObservable() {
        var now = 100L
        val store = ArtistExactMembershipTicketStore { now }
        val token = requireNotNull(store.issue(guard, "lib", artist, rows))
        val original = store.validityChanges.value
        assertEquals(20L * 60 * 1000, store.remainingLifetimeMs(token, guard))
        now += 19L * 60 * 1000
        assertEquals(60_000L, store.remainingLifetimeMs(token, guard))
        now += 60_000L
        assertEquals(0L, store.remainingLifetimeMs(token, guard))
        assertEquals(original + 1, store.validityChanges.value)
        store.revoke(token)
        assertEquals(original + 1, store.validityChanges.value)
    }
    @Test fun capacityEvictionInvalidatesVisibleTicket() {
        val store = ArtistExactMembershipTicketStore { 100L }
        val first = requireNotNull(store.issue(guard, "lib", artist, rows))
        repeat(32) { assertNotNull(store.issue(guard, "lib", artist, rows)) }
        assertNull(store.preview(first, guard))
        assertEquals(1L, store.validityChanges.value)
    }
    @Test fun unrelatedProofsDoNotConsumeRelevantBudget() {
        val store = ArtistExactMembershipTicketStore { 100L }
        val mbid = "11111111-1111-4111-8111-111111111111"
        val member = rows.single().copy(verifiedMbid = mbid, exactTrackConfirmed = true)
        val claim = ArtistExactOccurrenceClaim("lib", "a", "Alex", "Alex", member.trackIdentity, "t", "sig")
        val proof = ArtistTrackSessionProof(claim, mbid, "a".repeat(64), 42,
            ArtistTrackChoiceAuthority("lib", "rootA", 7L, 13L), 1, 1)
        val many = (0..150).map { proof.copy(claim = claim.copy(albumId = "foreign$it")) } + proof
        val token = requireNotNull(store.issue(guard, "lib", artist.copy(mbid = mbid), listOf(member), many))
        assertEquals(listOf(proof), store.sessionProofs(token, guard, "lib"))
    }
    @Test fun accepts129RelevantProofsButRejectsConflictingOnes() {
        val store = ArtistExactMembershipTicketStore { 100L }
        val mbid = "11111111-1111-4111-8111-111111111111"
        val members = (0..128).map { rows.single().copy(trackIdentity = "track$it", verifiedMbid = mbid,
            exactTrackConfirmed = true) }
        val proofs = members.map { row ->
            val claim = ArtistExactOccurrenceClaim("lib", "a", "Alex", "Alex", row.trackIdentity, row.trackIdentity, "sig")
            ArtistTrackSessionProof(claim, mbid, "a".repeat(64), 42,
                ArtistTrackChoiceAuthority("lib", "rootA", 7L, 13L), 1, 1)
        }
        val token = requireNotNull(store.issue(guard, "lib", artist.copy(mbid = mbid, trackCount = members.size), members, proofs))
        assertEquals(129, store.sessionProofs(token, guard, "lib").size)
        assertNull(store.issue(guard, "lib", artist.copy(mbid = mbid), members.take(1),
            listOf(proofs.first(), proofs.first().copy(rowRevision = 2))))
    }

    @Test fun oversizedProofTextCannotBeAttachedToTicket() {
        val store = ArtistExactMembershipTicketStore { 100L }
        val mbid = "11111111-1111-4111-8111-111111111111"
        val member = rows.single().copy(verifiedMbid = mbid, exactTrackConfirmed = true)
        val claim = ArtistExactOccurrenceClaim("lib", "a", "Alex", "Alex", member.trackIdentity, "t", "x".repeat(2_100_000))
        val proof = ArtistTrackSessionProof(claim, mbid, "a".repeat(64), 42,
            ArtistTrackChoiceAuthority("lib", "rootA", 7L, 13L), 1, 1)
        assertNull(store.issue(guard, "lib", artist.copy(mbid = mbid), listOf(member), listOf(proof)))
    }
}
