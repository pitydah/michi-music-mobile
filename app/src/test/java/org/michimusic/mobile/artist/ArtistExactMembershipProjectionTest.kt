package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistExactMembershipProjectionTest {
    private val mbid = "123e4567-e89b-42d3-a456-426614174000"
    private val other = "123e4567-e89b-42d3-a456-426614174001"
    private fun entry(
        credits: List<String> = listOf("A-ha", "a-ha"),
        albums: List<String> = listOf("a", "b"),
        mbid: String? = this.mbid,
        count: Int = 2,
        library: String? = "lib-1",
    ) = ArtistPresentationEntry(credits.first(), credits, albums, count, library, mbid)
    private fun members() = listOf(
        ArtistMembershipOccurrence("lib-1", "a", "A-ha", "s1\u0000t1\u0000content://one", mbid),
        ArtistMembershipOccurrence("lib-1", "b", "a-ha", "s1\u0000t2\u0000content://two", mbid),
    )
    private fun project(e: ArtistPresentationEntry = entry(), rows: List<ArtistMembershipOccurrence> = members(),
        scope: String = "lib-1") = ArtistExactMembershipProjection.prepare(e, rows, scope)
    private fun blocked(expected: ArtistExactMembershipResult.Reason, actual: ArtistExactMembershipResult) =
        assertEquals(ArtistExactMembershipResult.Blocked(expected), actual)

    @Test fun verifiedVariantsPreserveBothAlbumAndTrackMemberships() {
        val ready = project() as ArtistExactMembershipResult.Ready
        assertEquals(listOf("a", "b"), ready.snapshot.albumIds)
        assertEquals(listOf("A-ha", "a-ha"), ready.snapshot.mergedCredits)
        assertEquals(2, ready.snapshot.members.size)
        assertEquals(mbid, ready.snapshot.verifiedMbid)
    }
    @Test fun unverifiedVariantsCannotMerge() = blocked(
        ArtistExactMembershipResult.Reason.UNVERIFIED_MERGE, project(entry(mbid = null)))
    @Test fun partialVerificationCannotBorrowIdentity() = blocked(
        ArtistExactMembershipResult.Reason.IDENTITY_CONFLICT,
        project(rows = members().mapIndexed { i, row -> if (i == 1) row.copy(verifiedMbid = null) else row }))
    @Test fun conflictingMbidsStayIsolated() = blocked(
        ArtistExactMembershipResult.Reason.IDENTITY_CONFLICT,
        project(rows = members().mapIndexed { i, row -> if (i == 1) row.copy(verifiedMbid = other) else row }))
    @Test fun missingAlbumFailsClosed() = blocked(
        ArtistExactMembershipResult.Reason.ALBUM_MISMATCH,
        project(rows = members().dropLast(1)))
    @Test fun unknownExtraAlbumFailsClosed() = blocked(
        ArtistExactMembershipResult.Reason.ALBUM_MISMATCH,
        project(rows = members() + members().first().copy(albumId = "third", trackIdentity = "t3")))
    @Test fun repeatedSameTrackAcrossAlbumsIsConflict() = blocked(
        ArtistExactMembershipResult.Reason.DUPLICATE_TRACK_OWNER,
        project(rows = members().mapIndexed { i,row -> if (i == 1) row.copy(trackIdentity = members()[0].trackIdentity) else row }))
    @Test fun identityFromAnotherLibraryCannotPollute() {
        val foreign = members()[0].copy(libraryId = "lib-2", verifiedMbid = other)
        assertTrue(project(rows = members() + foreign) is ArtistExactMembershipResult.Ready)
    }
    @Test fun allScopeCannotClaimExactLibrary() = blocked(
        ArtistExactMembershipResult.Reason.WRONG_LIBRARY, project(entry(library = null)))
    @Test fun wrongSelectedLibraryIsRejected() = blocked(
        ArtistExactMembershipResult.Reason.WRONG_LIBRARY, project(scope = "lib-2"))
    @Test fun unverifiedSingleCreditIsReadOnlyBrowsable() {
        val e = entry(credits = listOf("Alex"), albums = listOf("a"), mbid = null, count = 1)
        val row = members().first().copy(literalCredit = "Alex", verifiedMbid = null)
        val result = project(e, listOf(row)) as ArtistExactMembershipResult.Ready
        assertEquals(null, result.snapshot.verifiedMbid)
    }
    @Test fun twoIdentitiesWithSameCreditMustNotBecomeOne() = blocked(
        ArtistExactMembershipResult.Reason.IDENTITY_CONFLICT,
        project(entry(credits = listOf("Alex"), mbid = null), members().map { it.copy(literalCredit="Alex", verifiedMbid=mbid) }))
    @Test fun sameMembersDifferentOrderHaveSameDigest() {
        val a = project() as ArtistExactMembershipResult.Ready
        val b = project(rows=members().reversed()) as ArtistExactMembershipResult.Ready
        assertEquals(a, b)
        assertTrue(a.snapshot.revalidate(entry(), members().reversed(), "lib-1"))
    }
    @Test fun changedTrackUriInvalidatesSnapshot() {
        val first = project() as ArtistExactMembershipResult.Ready
        val changed = members().mapIndexed { i, row -> if (i==1) row.copy(trackIdentity = "s1\u0000t2\u0000content://replaced") else row }
        assertFalse(first.snapshot.revalidate(entry(), changed, "lib-1"))
    }
    @Test fun changingMbidChangesDigestAndFailsWhenUnverified() {
        val a = project() as ArtistExactMembershipResult.Ready
        val b = project(entry(mbid = other), members().map { it.copy(verifiedMbid = other) })
            as ArtistExactMembershipResult.Ready
        assertNotEquals(a.snapshot.membershipDigest, b.snapshot.membershipDigest)
        assertFalse(a.snapshot.revalidate(entry(mbid = other), members().map { it.copy(verifiedMbid = other) }, "lib-1"))
    }
    @Test fun capRejectsHugeMembershipList() = blocked(
        ArtistExactMembershipResult.Reason.LIMIT_EXCEEDED,
        project(entry(credits=listOf("Alex"), albums=listOf("a"), mbid=null, count=2049),
            (0..2048).map { ArtistMembershipOccurrence("lib-1", "a", "Alex", "track-$it") }))
    @Test fun declaredTrackCountCannotHideMissingTrack() = blocked(
        ArtistExactMembershipResult.Reason.TRACK_COUNT_MISMATCH, project(entry(count=3)))
}
